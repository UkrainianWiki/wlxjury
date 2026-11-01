package services

import controllers.Secured
import db.scalikejdbc.{ApiToken, ApiTokenJdbc, User}
import play.api.Logging

import java.nio.charset.StandardCharsets.UTF_8
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Personal API tokens (V75): a tool sends one as `Authorization: Bearer wlxj_...` to the REST
  * API ([[api.ApiAuth]]) and acts as its user, with that user's memberships and roles. Only
  * the token's SHA-256 is stored; the token itself is shown once, when it is created, and is
  * never logged: a log line names a token by its id, or a presented unknown one by its prefix.
  */
object ApiTokenService extends Logging {

  /** Every token starts with it, so that secret scanners can recognise one. */
  val Prefix = "wlxj_"

  /** The longest name the profile's form takes (api_token.name). */
  val NameMaxLength = 100

  /** The expiries the profile's form offers, in days (None: never), and its default. */
  val ExpiryDays: Seq[Option[Int]] = Seq(Some(30), Some(90), Some(365), None)
  val DefaultExpiryDays: Option[Int] = Some(90)

  /** The most usable (neither revoked nor expired) tokens a user may have: a soft cap, checked
    * before a create.
    */
  val MaxActive = 20

  def activeCount(userId: Long): Int = ApiTokenJdbc.findByUser(userId).count(_.usable)

  private val random = new SecureRandom()

  /** [[Prefix]] and 32 random bytes, base64url without padding (43 characters). */
  private val Format = (java.util.regex.Pattern.quote(Prefix) + "[A-Za-z0-9_-]{43}").r

  def generate(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Prefix + Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  /** The SHA-256 of the whole token, in lowercase hex: the form it is stored and found in. */
  def hash(token: String): String =
    MessageDigest.getInstance("SHA-256").digest(token.getBytes(UTF_8)).map("%02x".format(_)).mkString

  /** What names the token in lists and logs: [[Prefix]] and its first 6 characters. */
  def displayPrefix(token: String): String = token.take(Prefix.length + 6)

  /** A new token of the user; its value, to be shown once. */
  def create(userId: Long, name: String, expiresInDays: Option[Int]): String = {
    val token = generate()
    ApiTokenJdbc.create(userId, name.trim.take(NameMaxLength), hash(token), displayPrefix(token), expiresInDays)
    token
  }

  /** The token's record and its user, in their latest contest ([[Secured.inCurrentContest]],
    * as a session without a current contest), if the token is usable: well-formed, known,
    * neither revoked nor expired, and its user not deleted. Records the use. A refused token
    * is logged with its id (an unknown one with its prefix), never its value. A revoked or
    * expired token, or one of a deleted user, at WARN: it takes a real token's hash, so random
    * input can't flood it, and a revoked token still in use is the clearest sign of a leak.
    * An unknown one at INFO, and a malformed one, checked before any lookup, at DEBUG, so
    * that a client sending garbage neither floods the log nor queries the database.
    */
  def authenticate(token: String): Option[(ApiToken, User)] =
    if (!Format.matches(token)) {
      logger.debug("api_token_refused reason=malformed")
      None
    } else
      ApiTokenJdbc.findByHash(hash(token)) match {
        case None                         => refused(info = true, "unknown", s"prefix=${Secured.logValue(displayPrefix(token))}")
        case Some(found) if found.revoked   => refused(info = false, "revoked", s"tokenId=${found.id}")
        case Some(found) if found.expired   => refused(info = false, "expired", s"tokenId=${found.id}")
        case Some(found) =>
          User.findById(found.userId).filter(_.deletedAt.isEmpty) match {
            case None => refused(info = false, "user_missing", s"tokenId=${found.id}")
            case Some(user) =>
              ApiTokenJdbc.touch(found.id)
              Some(found -> Secured.inCurrentContest(user, None))
          }
      }

  /** Logs the refusal at INFO, else at WARN. */
  private def refused(info: Boolean, reason: String, fields: String*): Option[Nothing] = {
    val line = (s"api_token_refused reason=$reason" +: fields).mkString(" ")
    if (info) logger.info(line) else logger.warn(line)
    None
  }
}
