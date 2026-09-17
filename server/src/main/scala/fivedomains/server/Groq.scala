package fivedomains.server

import fivedomains.model.{Animal, Assessment}

/** Calls Groq's OpenAI-compatible chat completions endpoint to generate structured feedback for
  * an assessment. Requires Main.groqApiKey to be set (see Secrets.scala).
  */
object Groq {

  private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

  // Groq-hosted model to use for feedback generation; change here if a newer/cheaper one suits
  // better. llama-3.3-70b-versatile (and Groq's other older Llama chat models) have since been
  // retired from this account's catalog -- gpt-oss-20b is OpenAI's open-weight model, hosted by
  // Groq, and supports response_format=json_object below. Check `GET /openai/v1/models` against
  // your key if this 404s again.
  private val model = "openai/gpt-oss-20b"

  /** Returns (overall summary, perDomain feedback map) parsed from the model's JSON reply. */
  def assessmentFeedback(animal: Animal, assessment: Assessment): (String, Map[String, String]) = {
    val apiKey = Main.groqApiKey.getOrElse(
      throw new IllegalStateException("Groq API key not configured -- set GROQ_API_KEY or .env/apikey.txt")
    )

    val payload = ujson.Obj(
      "animal" -> upickle.default.writeJs(animal),
      "assessment" -> upickle.default.writeJs(assessment)
    )

    val body = ujson.Obj(
      "model" -> model,
      // Groq's JSON mode makes the model reply with a parseable object, matching AiPrompts.systemPrompt's contract.
      "response_format" -> ujson.Obj("type" -> "json_object"),
      "messages" -> ujson.Arr(
        ujson.Obj("role" -> "system", "content" -> AiPrompts.systemPrompt),
        ujson.Obj("role" -> "user", "content" -> ujson.write(payload))
      )
    )

    val response = requests.post(
      endpoint,
      headers = Map("Authorization" -> s"Bearer $apiKey", "Content-Type" -> "application/json"),
      data = ujson.write(body)
    )

    val content = ujson.read(response.text())("choices")(0)("message")("content").str
    val parsed = ujson.read(content)
    val overall = parsed.obj.get("overall").map(_.str).getOrElse("")
    val perDomain = parsed.obj.get("domains").map(_.obj.map((k, v) => k -> v.str).toMap).getOrElse(Map.empty)
    (overall, perDomain)
  }
}
