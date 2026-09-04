package fivedomains.server

import cask.model.{Request, Response}
import cask.router.Result

/** Adds permissive CORS headers to every response, since the client (vite dev server, or a
  * static gh-pages build) is served from a different origin than this API. Fine for a research
  * prototype with no cookie-based auth; tighten allowOrigin if that changes.
  */
class Cors(allowOrigin: String) extends cask.RawDecorator {
  def headers = Seq(
    "Access-Control-Allow-Origin" -> allowOrigin,
    "Access-Control-Allow-Methods" -> "GET, POST, OPTIONS",
    "Access-Control-Allow-Headers" -> "Content-Type"
  )

  def wrapFunction(ctx: Request, delegate: Delegate): Result[Response.Raw] =
    delegate(ctx, Map()).map(res => res.copy(headers = res.headers ++ headers))
}
