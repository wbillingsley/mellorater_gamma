package fivedomains.database

import java.util.UUID
import java.sql.ResultSet
import upickle.default.ReadWriter

case class MellUser(id: UUID, name: String, created: Long) derives ReadWriter

/** Plain JDBC access to the melluser table. No ORM: the SQL is short enough to just read. */
object Users {

  private def fromRow(rs: ResultSet): MellUser =
    MellUser(
      id = UUID.fromString(rs.getString("id")),
      name = rs.getString("name"),
      created = rs.getLong("created")
    )

  def create(name: String): MellUser = {
    val user = MellUser(UUID.randomUUID(), name, System.currentTimeMillis())
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        "INSERT INTO melluser (id, name, created) VALUES (?, ?, ?)"
      )
      stmt.setObject(1, user.id)
      stmt.setString(2, user.name)
      stmt.setLong(3, user.created)
      stmt.executeUpdate()
    }
    user
  }

  def find(id: UUID): Option[MellUser] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT id, name, created FROM melluser WHERE id = ?")
    stmt.setObject(1, id)
    val rs = stmt.executeQuery()
    if rs.next() then Some(fromRow(rs)) else None
  }

  def list(): Seq[MellUser] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT id, name, created FROM melluser ORDER BY created")
    val rs = stmt.executeQuery()
    Iterator.unfold(rs)(rs => if rs.next() then Some((fromRow(rs), rs)) else None).toSeq
  }
}
