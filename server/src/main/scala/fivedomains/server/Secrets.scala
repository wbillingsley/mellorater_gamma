package fivedomains.server

import scala.util.Using
import scala.io.Source
import java.io.File

/** Reads local secrets that aren't committed to the repo. Prefers an env var; falls back to a
  * plain-text file under the gitignored `.env/` directory at the working directory, for keys
  * that are easier to drop in a file locally than export in every shell.
  */
object Secrets {

  def fromEnvOrFile(envVar: String, fileName: String): Option[String] =
    sys.env.get(envVar).filter(_.nonEmpty).orElse {
      val file = new File(".env", fileName)
      if file.exists() then
        val content = Using.resource(Source.fromFile(file))(_.mkString.trim)
        Option(content).filter(_.nonEmpty)
      else None
    }
}
