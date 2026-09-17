package fivedomains.database

import java.util.UUID
import java.sql.ResultSet
import upickle.default.*
import fivedomains.model.{Assessment, AnimalId}

/** Assessments (survey responses) are stored as a jsonb blob of the whole Assessment, since
  * the answers map is exactly the free-form JSON shape the client already works with.
  */
object Assessments {

  private def fromRow(rs: ResultSet): Assessment =
    read[Assessment](rs.getString("data"))

  def insert(owner: UUID, assessment: Assessment): UUID = {
    val id = UUID.randomUUID()
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        "INSERT INTO assessment (id, animal, owner, data, created) VALUES (?, ?, ?, ?, ?)"
      )
      stmt.setObject(1, id)
      stmt.setObject(2, assessment.animal)
      stmt.setObject(3, owner)
      stmt.setObject(4, Db.jsonb(write(assessment)))
      stmt.setLong(5, System.currentTimeMillis())
      stmt.executeUpdate()
    }
    id
  }

  /** Scoped to owner so one user can't read another's assessments by guessing an animal id. */
  def listForAnimal(animal: AnimalId, owner: UUID): Seq[Assessment] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT data FROM assessment WHERE animal = ? AND owner = ? ORDER BY created")
    stmt.setObject(1, animal)
    stmt.setObject(2, owner)
    val rs = stmt.executeQuery()
    Iterator.unfold(rs)(rs => if rs.next() then Some((fromRow(rs), rs)) else None).toSeq
  }

  def listForOwner(owner: UUID): Seq[Assessment] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT data FROM assessment WHERE owner = ? ORDER BY created")
    stmt.setObject(1, owner)
    val rs = stmt.executeQuery()
    Iterator.unfold(rs)(rs => if rs.next() then Some((fromRow(rs), rs)) else None).toSeq
  }
}
