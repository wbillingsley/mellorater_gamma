package fivedomains

import scala.scalajs.js
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import scala.scalajs.js.Thenable.Implicits.*

/** Thin interop wrapper around the puter.js global (loaded via a <script> tag in index.html), so
  * the AI call can be made directly from the browser using the visitor's own Puter account -- no
  * server round trip, and no API key of ours involved. See https://docs.puter.com/AI/chat/
  */
object Puter {

    /** Sends a system + user message pair to puter.js's chat AI and returns the reply text. */
    def chat(systemPrompt: String, userMessage: String): Future[String] =
        val messages = js.Array(
            js.Dynamic.literal(role = "system", content = systemPrompt),
            js.Dynamic.literal(role = "user", content = userMessage)
        )
        val promise = js.Dynamic.global.puter.ai.chat(messages).asInstanceOf[js.Promise[js.Dynamic]]
        promise.toFuture.map { result =>
            // Non-streaming puter.ai.chat resolves to {message: {role, content}, ...}
            if !js.isUndefined(result.message) then result.message.content.asInstanceOf[String]
            else result.asInstanceOf[String]
        }
}
