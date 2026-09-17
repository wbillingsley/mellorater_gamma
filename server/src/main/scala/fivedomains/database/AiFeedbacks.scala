package fivedomains.database

import java.util.UUID
import java.sql.ResultSet
import upickle.default.*
import fivedomains.model.{AiFeedback, AnimalId}

/** Cached AI-generated feedback for an assessment, stored as a jsonb blob keyed by (animal,
  * assessmenttime) -- the same pair the client uses to identify an assessment, since Assessment
  * itself has no id (see common/.../model/Assessment.scala). Lets past feedback be retrieved
  * without re-calling the AI, even from a different device.
  */
object AiFeedbacks {

  private def fromRow(rs: ResultSet): AiFeedback =
    read[AiFeedback](rs.getString("data"))

  def upsert(owner: UUID, feedback: AiFeedback): AiFeedback = {
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        """INSERT INTO aifeedback (animal, assessmenttime, owner, data, created)
          |VALUES (?, ?, ?, ?, ?)
          |ON CONFLICT (animal, assessmenttime) DO UPDATE SET data = EXCLUDED.data""".stripMargin
      )
      stmt.setObject(1, feedback.animal)
      stmt.setDouble(2, feedback.assessmentTime)
      stmt.setObject(3, owner)
      stmt.setObject(4, Db.jsonb(write(feedback)))
      stmt.setLong(5, System.currentTimeMillis())
      stmt.executeUpdate()
    }
    feedback
  }

  /** Scoped to owner so one user can't read another's cached feedback by guessing an animal id. */
  def listForAnimal(animal: AnimalId, owner: UUID): Seq[AiFeedback] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT data FROM aifeedback WHERE animal = ? AND owner = ? ORDER BY assessmenttime")
    stmt.setObject(1, animal)
    stmt.setObject(2, owner)
    val rs = stmt.executeQuery()
    Iterator.unfold(rs)(rs => if rs.next() then Some((fromRow(rs), rs)) else None).toSeq
  }
}
