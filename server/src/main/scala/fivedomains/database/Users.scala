package fivedomains.database

import java.util.UUID
import java.sql.ResultSet
import fivedomains.model.MellUser

/** Plain JDBC access to the melluser table. No ORM: the SQL is short enough to just read. */
object Users {

  private def fromRow(rs: ResultSet): MellUser =
    MellUser(
      id = UUID.fromString(rs.getString("id")),
      name = rs.getString("name"),
      created = rs.getLong("created")
    )

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
