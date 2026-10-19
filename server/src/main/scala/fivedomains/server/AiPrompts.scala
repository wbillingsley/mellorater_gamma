package fivedomains.server

import fivedomains.model.Domain

import java.io.File
import scala.io.Source
import scala.util.Using

/** Prompts for the AI-generated feedback feature, read from text files in the prompts directory
  * (`PROMPTS_DIR`, default `prompts/` under the working directory) so they can be edited without
  * recompiling. The files are re-read on every request, so edits take effect without a restart.
  *
  *  - `system.md` -- the main instructions: tone, how to read ratings and confidence, structure.
  *  - `reference.md` -- optional background material for the AI (e.g. welfare science notes).
  *    Leave it empty to omit it.
  *  - `output-format.md` -- the JSON reply contract; `{{domainKeys}}` is replaced with the domain
  *    keys. See AiFeedback in common/ for the shape this is parsed into.
  *
  * The combined prompt is used by the server's own Claude and Groq calls, and by the client's
  * puter.js path (fetched via GET /api/ai/system-prompt) so all the backends stay in sync. All of
  * it is the same for every request, so it forms a stable prefix that AI providers can cache --
  * see Claude.scala, which marks it for Anthropic's prompt cache.
  */
object AiPrompts {

  private val domainKeys = Domain.values.map(_.toString).mkString(", ")

  private val dir = new File(sys.env.get("PROMPTS_DIR").filter(_.nonEmpty).getOrElse("prompts"))

  private def read(name: String): Option[String] =
    val file = new File(dir, name)
    if file.exists() then Option(Using.resource(Source.fromFile(file, "UTF-8"))(_.mkString.trim)).filter(_.nonEmpty)
    else None

  private def required(name: String): String =
    read(name).getOrElse(throw new IllegalStateException(s"Missing or empty prompt file ${new File(dir, name).getAbsolutePath}"))

  /** The system prompt as separate blocks, in order: instructions, reference material (if any),
    * output format. Claude.scala sends these as separate system blocks; other backends join them.
    */
  def systemBlocks: Seq[String] =
    Seq(required("system.md")) ++
    read("reference.md").map(r => s"<reference>\n$r\n</reference>") :+
    required("output-format.md").replace("{{domainKeys}}", domainKeys)

  def systemPrompt: String = systemBlocks.mkString("\n\n")

}
