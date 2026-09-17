package fivedomains.database

import java.util.UUID
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64
import fivedomains.model.MellUser

case class RegisterResult(user: MellUser, token: String, recoveryPhrase: String)

/** Passwordless auth: a device holds a bearer token (looked up in `usertoken`); a separate,
  * higher-value recovery phrase (shown once at registration) can mint a new token on another
  * device. The phrase is machine-generated and pasted rather than typed, so it's already
  * high-entropy -- a plain indexed SHA-256 hash is enough, no per-user salt or slow KDF needed.
  */
object Auth {

  private val random = new SecureRandom()

  // Excludes 0/O/1/I/L to avoid transcription mistakes when someone does type it out.
  private val phraseAlphabet = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"

  private def randomToken(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  private def randomRecoveryPhrase(): String =
    (1 to 4).map { _ =>
      (1 to 4).map(_ => phraseAlphabet(random.nextInt(phraseAlphabet.length))).mkString
    }.mkString("-")

  private def normalize(phrase: String): String = phrase.trim.toUpperCase

  private def sha256Hex(s: String): String = {
    val digest = MessageDigest.getInstance("SHA-256").digest(s.getBytes("UTF-8"))
    digest.map(b => f"$b%02x").mkString
  }

  private def issueToken(userId: UUID): String = {
    val token = randomToken()
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        "INSERT INTO usertoken (token, melluser, created) VALUES (?, ?, ?)"
      )
      stmt.setString(1, token)
      stmt.setObject(2, userId)
      stmt.setLong(3, System.currentTimeMillis())
      stmt.executeUpdate()
    }
    token
  }

  def register(name: String): RegisterResult = {
    val recoveryPhrase = randomRecoveryPhrase()
    val user = MellUser(UUID.randomUUID(), name, System.currentTimeMillis())
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement(
        "INSERT INTO melluser (id, name, recoveryhash, created) VALUES (?, ?, ?, ?)"
      )
      stmt.setObject(1, user.id)
      stmt.setString(2, user.name)
      stmt.setString(3, sha256Hex(normalize(recoveryPhrase)))
      stmt.setLong(4, user.created)
      stmt.executeUpdate()
    }
    RegisterResult(user, issueToken(user.id), recoveryPhrase)
  }

  /** Looks up the account for a recovery phrase and mints it a fresh token, without disturbing
    * any tokens already issued to other devices.
    */
  def link(recoveryPhrase: String): Option[(MellUser, String)] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement("SELECT id, name, created FROM melluser WHERE recoveryhash = ?")
    stmt.setString(1, sha256Hex(normalize(recoveryPhrase)))
    val rs = stmt.executeQuery()
    if rs.next() then
      val user = MellUser(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getLong("created"))
      Some((user, issueToken(user.id)))
    else None
  }

  /** Mints a fresh recovery phrase for an already-authenticated user, replacing (invalidating)
    * whatever phrase they had before. Existing device tokens are untouched -- only the recovery
    * phrase changes. Lets a user reveal/replace their recovery phrase from Settings, since for
    * this app losing account access is a bigger risk than the recovery phrase leaking.
    */
  def regenerateRecoveryPhrase(userId: UUID): String = {
    val recoveryPhrase = randomRecoveryPhrase()
    Db.withConnection { conn =>
      val stmt = conn.prepareStatement("UPDATE melluser SET recoveryhash = ? WHERE id = ?")
      stmt.setString(1, sha256Hex(normalize(recoveryPhrase)))
      stmt.setObject(2, userId)
      stmt.executeUpdate()
    }
    recoveryPhrase
  }

  def userForToken(token: String): Option[MellUser] = Db.withConnection { conn =>
    val stmt = conn.prepareStatement(
      """SELECT u.id, u.name, u.created FROM melluser u
        |JOIN usertoken t ON t.melluser = u.id
        |WHERE t.token = ?""".stripMargin
    )
    stmt.setString(1, token)
    val rs = stmt.executeQuery()
    if rs.next() then
      Some(MellUser(UUID.fromString(rs.getString("id")), rs.getString("name"), rs.getLong("created")))
    else None
  }

  def userForRequest(request: cask.Request): Option[MellUser] =
    Option(request.exchange.getRequestHeaders.getFirst("Authorization"))
      .filter(_.startsWith("Bearer "))
      .map(_.stripPrefix("Bearer ").trim)
      .flatMap(userForToken)
}
