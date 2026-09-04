package fivedomains.server

import java.util.UUID
import fivedomains.model.{Animal, Assessment}
import fivedomains.database.{Users, Animals, Assessments}

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

  @cask.get("/api/health")
  def health() = "ok"

  @cask.postJson("/api/users")
  def createUser(name: String) = Users.create(name)

  @cask.getJson("/api/users/:id")
  def getUser(id: String) =
    Users.find(UUID.fromString(id)) match
      case Some(u) => cask.model.Response(upickle.default.writeJs(u))
      case None => cask.model.Response(ujson.Obj("error" -> "not found"), statusCode = 404)

  @cask.postJson("/api/animals")
  def saveAnimal(owner: UUID, animal: Animal) = Animals.upsert(owner, animal)

  @cask.getJson("/api/animals")
  def listAnimals(owner: String) = Animals.listForOwner(UUID.fromString(owner))

  @cask.getJson("/api/animals/:id")
  def getAnimal(id: String) =
    Animals.find(UUID.fromString(id)) match
      case Some(a) => cask.model.Response(upickle.default.writeJs(a))
      case None => cask.model.Response(ujson.Obj("error" -> "not found"), statusCode = 404)

  @cask.postJson("/api/assessments")
  def saveAssessment(owner: UUID, assessment: Assessment) =
    ujson.Obj("id" -> Assessments.insert(owner, assessment).toString)

  @cask.getJson("/api/assessments")
  def listAssessmentsForAnimal(animal: String) =
    Assessments.listForAnimal(UUID.fromString(animal))

  initialize()
}
