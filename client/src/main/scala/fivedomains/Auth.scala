package fivedomains

import com.wbillingsley.veautiful.PushVariable
import org.scalajs.dom
import org.scalajs.dom.window.localStorage
import org.scalajs.dom.{Headers, HttpMethod, RequestInit}
import scala.scalajs.js
import scala.scalajs.js.Thenable.Implicits.*
import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global
import upickle.default.*
import model.MellUser

enum AuthState:
    case Checking
    case LoggedOut
    case LoggedIn(user: MellUser)

/** Passwordless, multi-user auth: a device holds a bearer token (in localStorage), used on every
  * API call. A separate recovery phrase, shown once at registration, can link a second device to
  * the same account. See server/.../database/Auth.scala for the matching server-side half.
  */
object Auth {

    // TODO: this will need to become configurable once the server has a real deployment target,
    // rather than just localhost for dev.
    val apiBase = "http://localhost:8081"

    private val tokenKey = "authToken"

    private def storedToken: Option[String] = Option(localStorage.getItem(tokenKey))

    val state: PushVariable[AuthState] = PushVariable[AuthState](AuthState.Checking) { _ => Router.update() }

    case class RegisterResponse(user: MellUser, token: String, recoveryPhrase: String) derives ReadWriter
    private case class LinkResponse(user: MellUser, token: String) derives ReadWriter

    private def errorMessage(body: String): String =
        try ujson.read(body).obj.get("error").map(_.str).getOrElse("Something went wrong")
        catch case _: Throwable => "Something went wrong"

    private def request(method: HttpMethod, path: String, body: Option[ujson.Value], token: Option[String]): Future[(Int, String)] =
        val headers = new Headers()
        headers.append("Content-Type", "application/json")
        token.foreach(t => headers.append("Authorization", s"Bearer $t"))
        val init = new RequestInit {}
        init.method = method
        init.headers = headers
        body.foreach(b => init.body = ujson.write(b))
        dom.fetch(apiBase + path, init).toFuture.flatMap { resp =>
            resp.text().toFuture.map(text => (resp.status, text))
        }

    /** Stores the token and flips the app into the logged-in state. Public because registration
      * defers this until the user has acknowledged their recovery phrase (see AccountGate) --
      * flipping state here immediately would re-render the recovery-phrase screen away.
      */
    def confirmSession(token: String, user: MellUser): Unit =
        localStorage.setItem(tokenKey, token)
        state.value = AuthState.LoggedIn(user)

    def logOut(): Unit =
        localStorage.removeItem(tokenKey)
        state.value = AuthState.LoggedOut

    /** Call once at startup: if there's a stored token, check it's still recognised by the server. */
    def init(): Unit =
        storedToken match
            case Some(token) =>
                request(HttpMethod.GET, "/api/me", None, Some(token)).map {
                    case (200, body) => confirmSession(token, read[MellUser](body))
                    case _ =>
                        localStorage.removeItem(tokenKey)
                        state.value = AuthState.LoggedOut
                }
            case None =>
                state.value = AuthState.LoggedOut

    /** Registers a new account. Doesn't establish the session itself -- the caller should show
      * the recovery phrase and only call `confirmSession` once the user has acknowledged it.
      */
    def register(name: String): Future[Either[String, RegisterResponse]] =
        request(HttpMethod.POST, "/api/register", Some(ujson.Obj("name" -> name)), None).map {
            case (200, body) => Right(read[RegisterResponse](body))
            case (_, body) => Left(errorMessage(body))
        }

    def link(recoveryPhrase: String): Future[Either[String, Unit]] =
        request(HttpMethod.POST, "/api/link", Some(ujson.Obj("recoveryPhrase" -> recoveryPhrase)), None).map {
            case (200, body) =>
                val r = read[LinkResponse](body)
                confirmSession(r.token, r.user)
                Right(())
            case (_, body) => Left(errorMessage(body))
        }

    /** Mints a fresh recovery phrase for the current session, invalidating whatever phrase the
      * user had before. Used by Settings to let a user reveal/replace their recovery phrase if
      * they lost or never saved it -- for this app, losing account access outweighs the risk of
      * the phrase leaking.
      */
    def regenerateRecoveryPhrase(): Future[Either[String, String]] =
        authedRequest(HttpMethod.POST, "/api/recovery-phrase", None).map(_.map(body => ujson.read(body).obj("recoveryPhrase").str))

    /** Makes an authenticated API call using the stored device token, for other client modules
      * (e.g. Ai.scala) that need to hit an authed route. Returns the raw response body on 200,
      * or an error message otherwise (including when there's no session).
      */
    def authedRequest(method: HttpMethod, path: String, body: Option[ujson.Value]): Future[Either[String, String]] =
        storedToken match
            case Some(token) =>
                request(method, path, body, Some(token)).map {
                    case (200, respBody) => Right(respBody)
                    case (_, respBody) => Left(errorMessage(respBody))
                }
            case None => Future.successful(Left("Not logged in"))
}
