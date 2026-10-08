package fivedomains.server

import java.util.UUID
import fivedomains.model.{Animal, Assessment, MellUser, AiFeedback}
import fivedomains.database.{Auth, Animals, Assessments, AiFeedbacks}

object Main extends cask.MainRoutes {

  override def host = "0.0.0.0"
  override def port = sys.env.get("PORT").map(_.toInt).getOrElse(8081)

  private val corsOrigin = sys.env.getOrElse("CORS_ALLOW_ORIGIN", "*")
  private val cors = new Cors(corsOrigin)
  override def mainDecorators = Seq(cors)

  // For AI-assisted advice text. Claude is used when its key is configured, with Groq as the
  // fallback; each falls back to a file under .env/ if its env var isn't set.
  val claudeApiKey: Option[String] = Secrets.fromEnvOrFile("ANTHROPIC_API_KEY", "claude-apikey.txt")
  val groqApiKey: Option[String] = Secrets.fromEnvOrFile("GROQ_API_KEY", "apikey.txt")

  // cask only runs mainDecorators around a *successful* route dispatch -- a 404, a 405, or an
  // endpoint throwing (400 for a bad request body, 500 for anything else, e.g. Groq.scala's
  // IllegalStateException when the API key isn't configured) is generated outside that decorator
  // chain and so skips the Cors headers by default. The browser then reports a misleading CORS
  // error instead of showing the real status/body. Add the headers back on for all three paths.
  override def handleMethodNotAllowed(req: cask.model.Request): cask.model.Response.Raw =
    if req.exchange.getRequestMethod.toString.equalsIgnoreCase("OPTIONS") then
      cask.model.Response("", statusCode = 204, headers = cors.headers)
    else
      val res = super.handleMethodNotAllowed(req)
      res.copy(headers = res.headers ++ cors.headers)

  override def handleNotFound(req: cask.model.Request): cask.model.Response.Raw =
    val res = super.handleNotFound(req)
    res.copy(headers = res.headers ++ cors.headers)

  override def handleEndpointError(
    routes: cask.main.Routes,
    metadata: cask.router.EndpointMetadata[?],
    e: cask.router.Result.Error,
    req: cask.model.Request
  ): cask.model.Response.Raw =
    val res = super.handleEndpointError(routes, metadata, e, req)
    res.copy(headers = res.headers ++ cors.headers)

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

  // Serves the built client (npm run build -> dist/, see vite.config.js's `base`) so a single
  // process can be the only backend behind a TLS-terminating proxy (haproxy/nginx/etc) -- no
  // separate static file server needed. Not used by `npm run dev`, which serves the client itself.
  // One endpoint (rather than cask.staticFiles + a separate index route) because cask refuses to
  // register a subpath-capturing route and another endpoint at the same path: the bare
  // "/mellorater-alpha" request (no remaining segments) needs index.html, same as every other
  // Scala.js routes client-side via the URL hash.
  private val clientDistDir = sys.env.getOrElse("CLIENT_DIST_DIR", "dist")

  @cask.get("/")
  def rootRedirect() = cask.model.Redirect("/mellorater-alpha/")

  @cask.get("/mellorater-alpha", subpath = true)
  def clientApp(remaining: cask.RemainingPathSegments): cask.model.Response.Raw =
    val segments = remaining.value.filter(s => s != "." && s != "..")
    val relPath = if segments.isEmpty then "index.html" else segments.mkString("/")
    val file = java.nio.file.Paths.get(clientDistDir, relPath)
    if java.nio.file.Files.isRegularFile(file) then
      val contentType = Option(java.nio.file.Files.probeContentType(file)).getOrElse("application/octet-stream")
      cask.model.Response(
        java.nio.file.Files.newInputStream(file): cask.model.Response.Data,
        headers = Seq("Content-Type" -> contentType)
      )
    else
      cask.model.Response("Not found", statusCode = 404)

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

  /** Generates structured AI feedback for an assessment, grouped per welfare domain -- via Claude
    * if configured, otherwise (or if the Claude call fails and a Groq key is set) via Groq.
    * Used by the client's "server" AI backend (see client/.../Ai.scala); the alternative
    * "puter.js" backend calls puter.js directly from the browser instead of this route.
    */
  @cask.postJson("/api/ai/assessment-feedback")
  def aiAssessmentFeedback(animal: Animal, assessment: Assessment, request: cask.Request) =
    authed(request) { user =>
      val (overall, perDomain) =
        if claudeApiKey.isEmpty then Groq.assessmentFeedback(animal, assessment)
        else
          try Claude.assessmentFeedback(animal, assessment)
          catch
            case scala.util.control.NonFatal(e) if groqApiKey.isDefined =>
              System.err.println(s"Claude feedback failed, falling back to Groq: $e")
              Groq.assessmentFeedback(animal, assessment)
      ok(ujson.Obj("overall" -> overall, "domains" -> perDomain))
    }

  /** Hands out the system prompt (see AiPrompts.scala) so the client's puter.js backend uses the
    * same wording -- and the same structured-JSON contract -- as the server's own Groq calls.
    */
  @cask.getJson("/api/ai/system-prompt")
  def aiSystemPrompt(request: cask.Request) =
    authed(request) { _ => ok(ujson.Obj("systemPrompt" -> AiPrompts.systemPrompt)) }

  /** Saves (or updates) the cached AI feedback for one assessment, so it doesn't need
    * regenerating next time it's viewed -- see database/AiFeedbacks.scala.
    */
  @cask.postJson("/api/ai-feedback")
  def saveAiFeedback(feedback: AiFeedback, request: cask.Request) =
    authed(request) { user => ok(upickle.default.writeJs(AiFeedbacks.upsert(user.id, feedback))) }

  /** Lists cached AI feedback for an animal's assessments, so a returning session or another
    * device can show past feedback without re-calling the AI.
    */
  @cask.getJson("/api/ai-feedback")
  def listAiFeedback(animal: String, request: cask.Request) =
    authed(request) { user => ok(upickle.default.writeJs(AiFeedbacks.listForAnimal(UUID.fromString(animal), user.id))) }

  initialize()
}
