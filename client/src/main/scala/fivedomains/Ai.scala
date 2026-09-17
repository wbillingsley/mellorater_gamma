package fivedomains

import com.wbillingsley.veautiful.PushVariable
import org.scalajs.dom.window.localStorage
import org.scalajs.dom.HttpMethod
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import upickle.default.*
import model.{Animal, Assessment, AiFeedback, AnimalId}

enum AiBackend derives ReadWriter:
    case Server
    case PuterJs

/** Client-side entry point for the AI-generated feedback feature. Two backends are supported,
  * toggled from Settings (see settingsScreen.scala):
  *  - Server: our own server calls Groq with our API key -- see server/.../server/Groq.scala.
  *  - PuterJs: the browser calls puter.js directly, using the visitor's own Puter account, with
  *    no server round trip (or key of ours) involved in the AI call itself.
  * Both use the same system prompt, defined once on the server (AiPrompts.scala) -- the PuterJs
  * path fetches it via GET /api/ai/system-prompt rather than duplicating it client-side.
  *
  * Generated feedback is cached in DataStore and pushed to the server (POST /api/ai-feedback) so
  * it doesn't need regenerating every time an assessment is viewed -- see
  * animals/animalDetailsPage.scala for where it's shown and re-triggered.
  */
object Ai {

    private val backendKey = "aiBackend"

    val backend: PushVariable[AiBackend] = PushVariable(
        Option(localStorage.getItem(backendKey)).flatMap(s => scala.util.Try(read[AiBackend](s)).toOption).getOrElse(AiBackend.Server)
    ) { v =>
        localStorage.setItem(backendKey, write(v))
        Router.update()
    }

    private def payload(animal: Animal, assessment: Assessment): ujson.Value =
        ujson.Obj(
            "animal" -> upickle.default.writeJs(animal),
            "assessment" -> upickle.default.writeJs(assessment)
        )

    /** Calls the currently-selected backend to (re)generate feedback for this assessment, then
      * caches the result in DataStore and pushes it to the server. Always makes a fresh call --
      * check DataStore.aiFeedbackFor first if a cached result would do.
      */
    def generateFeedback(animal: Animal, assessment: Assessment): Future[Either[String, AiFeedback]] =
        val result = backend.value match
            case AiBackend.Server => viaServer(animal, assessment)
            case AiBackend.PuterJs => viaPuter(animal, assessment)

        result.map {
            case Right((overall, perDomain)) =>
                val feedback = AiFeedback(animal.id, assessment.time, overall, perDomain, new scalajs.js.Date().valueOf)
                DataStore.saveAiFeedback(feedback)
                syncToServer(feedback)
                Right(feedback)
            case Left(msg) => Left(msg)
        }

    private def viaServer(animal: Animal, assessment: Assessment): Future[Either[String, (String, Map[String, String])]] =
        Auth.authedRequest(HttpMethod.POST, "/api/ai/assessment-feedback", Some(payload(animal, assessment))).map {
            case Right(body) =>
                val json = ujson.read(body)
                Right((json.obj("overall").str, json.obj("domains").obj.map((k, v) => k -> v.str).toMap))
            case Left(msg) => Left(msg)
        }

    private def viaPuter(animal: Animal, assessment: Assessment): Future[Either[String, (String, Map[String, String])]] =
        Auth.authedRequest(HttpMethod.GET, "/api/ai/system-prompt", None).flatMap {
            case Left(msg) => Future.successful(Left(msg))
            case Right(body) =>
                val systemPrompt = ujson.read(body).obj("systemPrompt").str
                Puter.chat(systemPrompt, ujson.write(payload(animal, assessment))).map { reply =>
                    try
                        val json = ujson.read(reply)
                        Right((json.obj("overall").str, json.obj("domains").obj.map((k, v) => k -> v.str).toMap))
                    catch
                        case _: Throwable =>
                            // The model didn't reply with valid JSON -- fall back to showing its raw text
                            Right((reply, Map.empty[String, String]))
                }.recover { case e: Throwable => Left(Option(e.getMessage).getOrElse("puter.js call failed")) }
        }

    /** Best-effort push of newly (re)generated feedback to the server so it's retrievable from
      * another device -- failures are swallowed since DataStore already has it cached locally.
      */
    private def syncToServer(feedback: AiFeedback): Unit =
        Auth.authedRequest(HttpMethod.POST, "/api/ai-feedback", Some(upickle.default.writeJs(feedback)))

    private def loadCachedFromServer(animal: AnimalId): Future[Either[String, Seq[AiFeedback]]] =
        Auth.authedRequest(HttpMethod.GET, s"/api/ai-feedback?animal=$animal", None).map {
            case Right(body) =>
                val feedbacks = read[Seq[AiFeedback]](body)
                feedbacks.foreach(DataStore.saveAiFeedback)
                Right(feedbacks)
            case Left(msg) => Left(msg)
        }

    private val hydratedAnimals = mutable.Set.empty[AnimalId]

    /** Fetches this animal's cached AI feedback from the server into DataStore, once per animal
      * per session -- for a device/browser that doesn't already have it in local storage. Safe
      * to call on every render of a page that might show cached feedback; it no-ops after the
      * first call. Triggers a full re-render (via Router.update()) once the fetch completes.
      */
    def ensureHydrated(animal: AnimalId): Unit =
        if !hydratedAnimals.contains(animal) then
            hydratedAnimals += animal
            loadCachedFromServer(animal).onComplete { _ => Router.update() }
}
