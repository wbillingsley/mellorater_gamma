package fivedomains.database

import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import org.postgresql.util.PGobject
import java.sql.Connection

/** Connection pool and small JDBC helpers. Configured entirely from environment
  * variables so the same jar runs against the docker-compose db or a deployed one.
  */
object Db {

  private def env(name: String, default: String): String =
    sys.env.getOrElse(name, default)

  private val pool: HikariDataSource = {
    val config = new HikariConfig()
    config.setJdbcUrl(
      s"jdbc:postgresql://${env("PGHOST", "localhost")}:${env("PGPORT", "25432")}/${env("PGDATABASE", "mellorator")}"
    )
    config.setUsername(env("PGUSER", "postgres"))
    config.setPassword(env("PGPASSWORD", "example"))
    config.setMaximumPoolSize(env("PGPOOLSIZE", "10").toInt)
    new HikariDataSource(config)
  }

  /** Borrows a connection from the pool for the duration of `f`. */
  def withConnection[T](f: Connection => T): T = {
    val conn = pool.getConnection()
    try f(conn) finally conn.close()
  }

  /** Wraps a JSON string as a jsonb value for use with PreparedStatement.setObject */
  def jsonb(json: String): PGobject = {
    val obj = new PGobject()
    obj.setType("jsonb")
    obj.setValue(json)
    obj
  }
}
