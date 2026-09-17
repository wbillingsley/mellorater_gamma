package fivedomains.server

import fivedomains.model.{Animal, Assessment}

/** Calls Groq's OpenAI-compatible chat completions endpoint to generate advice text for an
  * assessment. Requires Main.groqApiKey to be set (see Secrets.scala).
  */
object Groq {

  private val endpoint = "https://api.groq.com/openai/v1/chat/completions"

  // Groq-hosted model to use for advice generation; change here if a newer/cheaper one suits better.
  private val model = "llama-3.3-70b-versatile"

  def advice(animal: Animal, assessment: Assessment): String = {
    val apiKey = Main.groqApiKey.getOrElse(
      throw new IllegalStateException("Groq API key not configured -- set GROQ_API_KEY or .env/apikey.txt")
    )

    val payload = ujson.Obj(
      "animal" -> upickle.default.writeJs(animal),
      "assessment" -> upickle.default.writeJs(assessment)
    )

    val body = ujson.Obj(
      "model" -> model,
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

    ujson.read(response.text())("choices")(0)("message")("content").str
  }
}
