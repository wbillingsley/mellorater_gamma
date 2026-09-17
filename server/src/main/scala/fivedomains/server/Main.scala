package fivedomains.server

import java.util.UUID
import fivedomains.model.{Animal, Assessment, MellUser}
import fivedomains.database.{Auth, Animals, Assessments}

object Main extends cask.MainRoutes {

  override def host = "0.0.0.0"
  override def port = sys.env.get("PORT").map(_.toInt).getOrElse(8081)

  private val corsOrigin = sys.env.getOrElse("CORS_ALLOW_ORIGIN", "*")
  override def mainDecorators = Seq(new Cors(corsOrigin))

  // Undertow answers CORS preflight requests as 405s (no route is registered for OPTIONS);
  // turn those into a plain 204 with the CORS headers so the browser lets the real request through.
  override def handleMethodNotAllowed(req: cask.model.Request): cask.model.Response.Raw =
    if req.exchange.getRequestMethod.toString.equalsIgnoreCase("OPTIONS") then
      cask.model.Response("", statusCode = 204, headers = new Cors(corsOrigin).headers)
    else super.handleMethodNotAllowed(req)

  private def ok(v: ujson.Value): cask.model.Response[ujson.Value] = cask.model.Response(v)

  /** Runs `f` with the user identified by the request's `Authorization: Bearer <token>` header,
    * or a 401 if there isn't a valid one. Every route that reads or writes a user's own data
    * should go through this rather than trusting an owner id supplied by the client.
    */
  private def authed(request: cask.Request)(f: MellUser => cask.model.Response[ujson.Value]): cask.model.Response[ujson.Value] =
    Auth.userForRequest(request) match
      case Some(user) => f(user)
      case None => cask.model.Response(ujson.Obj("error" -> "unauthorized"), statusCode = 401)

  @cask.get("/api/health")
  def health() = "ok"

  @cask.postJson("/api/register")
  def register(name: String) = {
    val result = Auth.register(name)
    ujson.Obj(
      "user" -> upickle.default.writeJs(result.user),
      "token" -> result.token,
      "recoveryPhrase" -> result.recoveryPhrase
    )
  }

  @cask.postJson("/api/link")
  def link(recoveryPhrase: String) =
    Auth.link(recoveryPhrase) match
      case Some((user, token)) =>
        cask.model.Response(ujson.Obj("user" -> upickle.default.writeJs(user), "token" -> token))
      case None =>
        cask.model.Response(ujson.Obj("error" -> "recovery phrase not recognised"), statusCode = 404)

  @cask.getJson("/api/me")
  def me(request: cask.Request) = authed(request) { user => ok(upickle.default.writeJs(user)) }

  /** Mints a fresh recovery phrase for the logged-in user, invalidating their previous one, so
    * it can be revealed again from Settings if they lost/never saved it.
    */
  @cask.postJson("/api/recovery-phrase")
  def regenerateRecoveryPhrase(request: cask.Request) =
    authed(request) { user => ok(ujson.Obj("recoveryPhrase" -> Auth.regenerateRecoveryPhrase(user.id))) }

  @cask.postJson("/api/animals")
  def saveAnimal(animal: Animal, request: cask.Request) =
    authed(request) { user => ok(upickle.default.writeJs(Animals.upsert(user.id, animal))) }

  @cask.getJson("/api/animals")
  def listAnimals(request: cask.Request) =
    authed(request) { user => ok(upickle.default.writeJs(Animals.listForOwner(user.id))) }

  @cask.getJson("/api/animals/:id")
  def getAnimal(id: String, request: cask.Request) =
    authed(request) { user =>
      Animals.find(UUID.fromString(id), user.id) match
        case Some(a) => ok(upickle.default.writeJs(a))
        case None => cask.model.Response(ujson.Obj("error" -> "not found"), statusCode = 404)
    }

  @cask.postJson("/api/assessments")
  def saveAssessment(assessment: Assessment, request: cask.Request) =
    authed(request) { user => ok(ujson.Obj("id" -> Assessments.insert(user.id, assessment).toString)) }

  @cask.getJson("/api/assessments")
  def listAssessmentsForAnimal(animal: String, request: cask.Request) =
    authed(request) { user => ok(upickle.default.writeJs(Assessments.listForAnimal(UUID.fromString(animal), user.id))) }

  initialize()
}
