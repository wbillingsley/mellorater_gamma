package fivedomains

import com.wbillingsley.veautiful.PushVariable
import org.scalajs.dom.window.localStorage
import org.scalajs.dom.HttpMethod
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import upickle.default.*
import model.{Animal, Assessment}

enum AiBackend derives ReadWriter:
    case Server
    case PuterJs

/** Client-side entry point for the AI-generated advice feature. Two backends are supported,
  * toggled from Settings (see settingsScreen.scala):
  *  - Server: our own server calls Groq with our API key -- see server/.../server/Groq.scala.
  *  - PuterJs: the browser calls puter.js directly, using the visitor's own Puter account, with
  *    no server round trip (or key of ours) involved in the AI call itself.
  * Both use the same system prompt, defined once on the server (AiPrompts.scala) -- the PuterJs
  * path fetches it via GET /api/ai/system-prompt rather than duplicating it client-side.
  */
object Ai {

    private val backendKey = "aiBackend"

    val backend: PushVariable[AiBackend] = PushVariable(
        Option(localStorage.getItem(backendKey)).flatMap(s => scala.util.Try(read[AiBackend](s)).toOption).getOrElse(AiBackend.Server)
    ) { v =>
        localStorage.setItem(backendKey, write(v))
        Router.update()
    }

    /** Generates advice text for this assessment using whichever backend is currently selected. */
    def advice(animal: Animal, assessment: Assessment): Future[Either[String, String]] =
        backend.value match
            case AiBackend.Server => adviceViaServer(animal, assessment)
            case AiBackend.PuterJs => adviceViaPuter(animal, assessment)

    private def assessmentPayload(animal: Animal, assessment: Assessment): ujson.Value =
        ujson.Obj(
            "animal" -> upickle.default.writeJs(animal),
            "assessment" -> upickle.default.writeJs(assessment)
        )

    private def adviceViaServer(animal: Animal, assessment: Assessment): Future[Either[String, String]] =
        Auth.authedRequest(HttpMethod.POST, "/api/ai/advice", Some(assessmentPayload(animal, assessment))).map {
            case Right(body) => Right(ujson.read(body).obj("advice").str)
            case Left(msg) => Left(msg)
        }

    private def adviceViaPuter(animal: Animal, assessment: Assessment): Future[Either[String, String]] =
        Auth.authedRequest(HttpMethod.GET, "/api/ai/system-prompt", None).flatMap {
            case Left(msg) => Future.successful(Left(msg))
            case Right(body) =>
                val systemPrompt = ujson.read(body).obj("systemPrompt").str
                Puter.chat(systemPrompt, ujson.write(assessmentPayload(animal, assessment)))
                    .map(Right(_))
                    .recover { case e: Throwable => Left(Option(e.getMessage).getOrElse("puter.js call failed")) }
        }
}
