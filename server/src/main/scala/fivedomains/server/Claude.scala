package fivedomains.server

import fivedomains.model.{Animal, Assessment, Domain}

/** Calls Anthropic's Messages API to generate structured feedback for an assessment -- the
  * preferred AI backend when Main.claudeApiKey is set (see Secrets.scala), with Groq.scala as the
  * fallback. Same signature and output as Groq.assessmentFeedback.
  */
object Claude {

  private val endpoint = "https://api.anthropic.com/v1/messages"

  // Overridable via env vars so models can be compared without a rebuild. Sonnet is quicker and
  // half the price of Opus; set CLAUDE_MODEL=claude-opus-5-5 to try Opus instead.
  private val model = sys.env.get("CLAUDE_MODEL").filter(_.nonEmpty).getOrElse("claude-sonnet-5-5")

  // Effort (low | medium | high) trades depth against latency -- the guardian is waiting on a
  // phone. Set explicitly because the default differs by model (high on Sonnet, medium on Opus).
  private val effort = sys.env.get("CLAUDE_EFFORT").filter(_.nonEmpty).getOrElse("medium")

  // Prompt cache lifetime: 5m (default) or 1h. A cache hit only happens if the next request starts
  // within this window of the last one; 1h costs 2x to write (vs 1.25x) but survives quiet spells.
  private val cacheTtl = sys.env.get("CLAUDE_CACHE_TTL").filter(_.nonEmpty).getOrElse("5m")

  // Structured outputs: constrains the reply to exactly the JSON shape AiPrompts.systemPrompt
  // asks for, so parsing below can't trip over stray prose or a missing domain key.
  private val outputSchema = ujson.Obj(
    "type" -> "object",
    "properties" -> ujson.Obj(
      "overall" -> ujson.Obj("type" -> "string"),
      "domains" -> ujson.Obj(
        "type" -> "object",
        "properties" -> ujson.Obj.from(Domain.values.map(d => d.toString -> ujson.Obj("type" -> "string"))),
        "required" -> ujson.Arr.from(Domain.values.map(_.toString)),
        "additionalProperties" -> false
      )
    ),
    "required" -> ujson.Arr("overall", "domains"),
    "additionalProperties" -> false
  )

  private def systemBlocks =
    val blocks = AiPrompts.systemBlocks.map(text => ujson.Obj("type" -> "text", "text" -> text))
    blocks.last("cache_control") = ujson.Obj("type" -> "ephemeral", "ttl" -> cacheTtl)
    ujson.Arr.from(blocks)

  /** Returns (overall summary, perDomain feedback map) parsed from the model's JSON reply. */
  def assessmentFeedback(animal: Animal, assessment: Assessment): (String, Map[String, String]) = {
    val apiKey = Main.claudeApiKey.getOrElse(
      throw new IllegalStateException("Claude API key not configured -- set ANTHROPIC_API_KEY or .env/claude-apikey.txt")
    )

    val payload = ujson.Obj(
      "animal" -> upickle.default.writeJs(animal),
      "assessment" -> upickle.default.writeJs(assessment)
    )

    val body = ujson.Obj(
      "model" -> model,
      "max_tokens" -> 16000,
      // The system prompt (instructions, reference material, output format) is identical for every
      // request, so a cache breakpoint on its last block lets Anthropic reuse it at ~10% of the
      // input price. Only the per-assessment user message below is processed fresh.
      "system" -> systemBlocks,
      "output_config" -> ujson.Obj(
        "effort" -> effort,
        "format" -> ujson.Obj("type" -> "json_schema", "schema" -> outputSchema)
      ),
      // If a safety classifier declines the request, Anthropic re-runs it on a suitable fallback
      // model server-side rather than returning a refusal.
      "fallbacks" -> "default",
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "user", "content" -> ujson.write(payload))
      )
    )

    val response = requests.post(
      endpoint,
      headers = Map(
        "x-api-key" -> apiKey,
        "anthropic-version" -> "2023-06-01",
        "anthropic-beta" -> "server-side-fallback-2026-07-01",
        "Content-Type" -> "application/json"
      ),
      data = ujson.write(body),
      // requests' default 10s read timeout is too short once the model thinks before answering.
      readTimeout = 120000
    )

    val json = ujson.read(response.text())
    val usage = json("usage")
    println(s"Claude usage: input=${usage("input_tokens")} cache_write=${usage.obj.get("cache_creation_input_tokens").getOrElse(0)} cache_read=${usage.obj.get("cache_read_input_tokens").getOrElse(0)} output=${usage("output_tokens")}")
    json("stop_reason").str match
      case "refusal" => throw new IllegalStateException(s"Claude declined the request: ${json.obj.get("stop_details")}")
      case "max_tokens" => throw new IllegalStateException("Claude's reply was cut off at max_tokens")
      case _ => ()

    // The reply may also hold (empty) thinking blocks; the JSON answer is in the text block.
    val content = json("content").arr.find(_("type").str == "text").map(_("text").str).getOrElse(
      throw new IllegalStateException("Claude's reply had no text block")
    )
    val parsed = ujson.read(content)
    val overall = parsed("overall").str
    val perDomain = parsed("domains").obj.map((k, v) => k -> v.str).toMap
    (overall, perDomain)
  }
}
