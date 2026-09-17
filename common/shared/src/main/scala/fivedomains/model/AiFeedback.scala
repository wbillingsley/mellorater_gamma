package fivedomains.model

import upickle.default.ReadWriter

/** A cached AI-generated write-up of an assessment, grouped by welfare domain (keyed by
  * `Domain.toString`, e.g. "Nutrition" -> "..."), plus a short overall summary. Cached
  * client-side (DataStore) and server-side (see server/.../database/AiFeedbacks.scala) so it
  * doesn't need regenerating every time the assessment is viewed.
  *
  * Identified by (animal, assessmentTime) rather than its own id, since Assessment itself has
  * no id -- see Assessment.scala.
  */
case class AiFeedback(
    animal: AnimalId,
    assessmentTime: Double,
    overall: String,
    perDomain: Map[String, String],
    generated: Double
) derives ReadWriter
