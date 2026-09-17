package fivedomains.database

import java.util.UUID
import java.sql.ResultSet
import upickle.default.*
import fivedomains.model.Animal

/** Animal records are stored as a jsonb blob (the id and owner are pulled out into their own
  * columns for querying/foreign keys) so the shape of Animal can keep evolving without a
  * matching schema migration every time.
  */
object Animals {

  private def fromRow(rs: ResultSet): Animal =
    read[Animal](rs.getString("data"))

  def upsert(owner: UUID, animal: Animal): Animal = {
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        """INSERT INTO animal (id, owner, data, created, lastupdate)
          |VALUES (?, ?, ?, ?, ?)
          |ON CONFLICT (id) DO UPDATE SET data = EXCLUDED.data, lastupdate = EXCLUDED.lastupdate""".stripMargin
      )
      val now = System.currentTimeMillis()
      stmt.setObject(1, animal.id)
      stmt.setObject(2, owner)
      stmt.setObject(3, Db.jsonb(write(animal)))
      stmt.setLong(4, now)
      stmt.setLong(5, now)
      stmt.executeUpdate()
    }
    animal
  }

  /** Scoped to owner so one user can't fetch another's animal by guessing its id. */
  def find(id: UUID, owner: UUID): Option[Animal] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT data FROM animal WHERE id = ? AND owner = ?")
    stmt.setObject(1, id)
    stmt.setObject(2, owner)
    val rs = stmt.executeQuery()
    if rs.next() then Some(fromRow(rs)) else None
  }

  def listForOwner(owner: UUID): Seq[Animal] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT data FROM animal WHERE owner = ? ORDER BY created")
    stmt.setObject(1, owner)
    val rs = stmt.executeQuery()
    Iterator.unfold(rs)(rs => if rs.next() then Some((fromRow(rs), rs)) else None).toSeq
  }
}
