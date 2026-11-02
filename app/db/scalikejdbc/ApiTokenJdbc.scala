package db.scalikejdbc

import scalikejdbc._

import java.time.Instant

/** A personal API token (table `api_token`, V75), without its hash: what the profile page
  * lists and the API checks ([[services.ApiTokenService]]).
  *
  * @param prefix  "wlxj_" and the token's first 6 characters, to tell tokens apart
  * @param expired whether `expiresAt` has passed, by the database clock
  */
final case class ApiToken(
    id: Long,
    userId: Long,
    name: String,
    prefix: String,
    createdAt: Instant,
    expiresAt: Option[Instant],
    lastUsedAt: Option[Instant],
    revokedAt: Option[Instant],
    expired: Boolean
) {
  def revoked: Boolean = revokedAt.isDefined

  /** Neither revoked nor expired: a request with it is let in as its user. */
  def usable: Boolean = !revoked && !expired
}

/** The `api_token` table. Times come from the database clock (NOW(3)), as [[services.jobs.JobJdbc]]'s. */
object ApiTokenJdbc {

  private val columns =
    sqls"""id, user_id, name, prefix, created_at, expires_at, last_used_at, revoked_at,
           (expires_at IS NOT NULL AND expires_at <= NOW(3)) AS expired"""

  private def extract(rs: WrappedResultSet): ApiToken =
    ApiToken(
      id = rs.long("id"),
      userId = rs.long("user_id"),
      name = rs.string("name"),
      prefix = rs.string("prefix"),
      createdAt = rs.timestamp("created_at").toInstant,
      expiresAt = rs.timestampOpt("expires_at").map(_.toInstant),
      lastUsedAt = rs.timestampOpt("last_used_at").map(_.toInstant),
      revokedAt = rs.timestampOpt("revoked_at").map(_.toInstant),
      expired = rs.boolean("expired")
    )

  /** A new token of the user, expiring in `expiresInDays` (never without), by its hash; its id. */
  def create(userId: Long, name: String, tokenHash: String, prefix: String, expiresInDays: Option[Int])(implicit
      session: DBSession = AutoSession
  ): Long =
    sql"""INSERT INTO api_token (user_id, name, token_hash, prefix, created_at, expires_at)
          VALUES ($userId, $name, $tokenHash, $prefix, NOW(3),
                  IF(${expiresInDays.isDefined}, NOW(3) + INTERVAL ${expiresInDays.getOrElse(0)} DAY, NULL))"""
      .updateAndReturnGeneratedKey.apply()

  def findByHash(tokenHash: String)(implicit session: DBSession = AutoSession): Option[ApiToken] =
    sql"SELECT $columns FROM api_token WHERE token_hash = $tokenHash".map(extract).single.apply()

  def find(id: Long)(implicit session: DBSession = AutoSession): Option[ApiToken] =
    sql"SELECT $columns FROM api_token WHERE id = $id".map(extract).single.apply()

  /** The user's tokens, the latest first, revoked and expired ones included. */
  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[ApiToken] =
    sql"SELECT $columns FROM api_token WHERE user_id = $userId ORDER BY id DESC".map(extract).list.apply()

  /** Revokes the user's token; false, changing nothing, if it is not theirs or revoked already. */
  def revoke(id: Long, userId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""UPDATE api_token SET revoked_at = NOW(3)
          WHERE id = $id AND user_id = $userId AND revoked_at IS NULL""".update.apply() > 0

  /** Records a use: sets `last_used_at` unless it was set less than a minute ago, so that a
    * busy tool writes the row at most once a minute.
    */
  def touch(id: Long)(implicit session: DBSession = AutoSession): Unit =
    sql"""UPDATE api_token SET last_used_at = NOW(3)
          WHERE id = $id AND (last_used_at IS NULL OR last_used_at < NOW(3) - INTERVAL 1 MINUTE)""".update.apply()

  /** Locks the user's row until the transaction ends: one token create of a user at a time
    * ([[services.ApiTokenService.createOnce]]).
    */
  def lockOwner(userId: Long)(implicit session: DBSession): Unit = {
    val _ = sql"SELECT id FROM users WHERE id = $userId FOR UPDATE".map(_.long(1)).single.apply()
  }

  /** Whether the user created a token named `name` less than `seconds` ago, by the database
    * clock. A locking read: after [[lockOwner]] it sees a create that committed while it waited.
    */
  def createdWithin(userId: Long, name: String, seconds: Int)(implicit session: DBSession): Boolean =
    sql"""SELECT COUNT(*) FROM api_token
          WHERE user_id = $userId AND name = $name AND created_at > NOW(3) - INTERVAL $seconds SECOND
          LOCK IN SHARE MODE""".map(_.long(1)).single.apply().exists(_ > 0)
}
