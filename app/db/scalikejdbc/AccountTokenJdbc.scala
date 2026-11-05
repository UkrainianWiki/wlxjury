package db.scalikejdbc

import scalikejdbc._

import java.time.Instant

/** A link mailed to an account (table `account_token`, V77), or shown to its admin to pass on
  * (`shared`, V94), without its hash ([[services.AccountTokenService]]).
  *
  * @param email     the address the link was sent to, which accepting a mailed one proves; a
  *                  shared one's is the account's address when it was created ("" without one)
  * @param contestId an invitation's contest; a shared password link's, the contest it was
  *                  created in (None: by root without one)
  * @param role      a pending invitation's role, given once accepted (None: nothing pending)
  * @param live      neither used, revoked nor expired, by the database clock
  * @param shared    shown to an admin or root to copy and pass on, not mailed (V94)
  */
final case class AccountToken(
    id: Long,
    userId: Long,
    purpose: String,
    email: String,
    contestId: Option[Long],
    role: Option[String],
    createdBy: Option[Long],
    createdAt: Instant,
    expiresAt: Instant,
    usedAt: Option[Instant],
    revokedAt: Option[Instant],
    live: Boolean,
    shared: Boolean = false
) {

  /** What became of the link, for its admin: used, revoked, expired, else active. */
  def status: AccountToken.Status =
    usedAt.map(AccountToken.Used)
      .orElse(revokedAt.map(AccountToken.Revoked))
      .getOrElse(if (live) AccountToken.Active(expiresAt) else AccountToken.Expired(expiresAt))
}

object AccountToken {

  /** A link's state and its time: when it expires, expired, was used or revoked. */
  sealed abstract class Status(val key: String) { def at: Instant }
  final case class Active(at: Instant) extends Status("active")
  final case class Used(at: Instant) extends Status("used")
  final case class Expired(at: Instant) extends Status("expired")
  final case class Revoked(at: Instant) extends Status("revoked")
}

/** The `account_token` table. Times come from the database clock (NOW(3)), as [[ApiTokenJdbc]]'s. */
object AccountTokenJdbc {

  val Invitation = "invitation"
  val PasswordReset = "password_reset"
  val EmailChange = "email_change"

  /** The OAuth account chooser's proof by mail: the account's holder reads its verified address. */
  val OAuthChoose = "oauth_choose"

  /** The purposes whose acceptance sets the password and proves the account's email. */
  val SetsPassword: Seq[String] = Seq(Invitation, PasswordReset)

  private val columns =
    sqls"""id, user_id, purpose, email, contest_id, role, created_by, created_at, expires_at, used_at, revoked_at,
           (used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)) AS live, shared"""

  private def extract(rs: WrappedResultSet): AccountToken =
    AccountToken(
      id = rs.long("id"),
      userId = rs.long("user_id"),
      purpose = rs.string("purpose"),
      email = rs.string("email"),
      contestId = rs.longOpt("contest_id"),
      role = rs.stringOpt("role"),
      createdBy = rs.longOpt("created_by"),
      createdAt = rs.timestamp("created_at").toInstant,
      expiresAt = rs.timestamp("expires_at").toInstant,
      usedAt = rs.timestampOpt("used_at").map(_.toInstant),
      revokedAt = rs.timestampOpt("revoked_at").map(_.toInstant),
      live = rs.boolean("live"),
      shared = rs.boolean("shared")
    )

  /** A new link, by its hash, expiring in `validForSeconds`; its id. A role must be a contest role.
    * `shared`: shown to an admin to pass on, not mailed (V94).
    */
  def create(
      userId: Long,
      purpose: String,
      tokenHash: String,
      email: String,
      contestId: Option[Long],
      role: Option[String],
      createdBy: Option[Long],
      validForSeconds: Long,
      shared: Boolean = false
  )(implicit session: DBSession = AutoSession): Long = {
    require(role.forall(User.isContestRole), "not a contest role")
    sql"""INSERT INTO account_token
            (user_id, purpose, token_hash, email, contest_id, role, created_by, created_at, expires_at, shared)
          VALUES ($userId, $purpose, $tokenHash, $email, $contestId, $role, $createdBy, NOW(3),
                  NOW(3) + INTERVAL $validForSeconds SECOND, $shared)"""
      .updateAndReturnGeneratedKey.apply()
  }

  /** The latest invitation into the contest of each account that still has the address it was
    * sent to ("" for an account without one), not deleted: live ones, and the others created in
    * the last `days` days, the latest first. The users page's invitations, with their status.
    */
  def latestInvitations(contestId: Long, days: Int)(implicit session: DBSession = AutoSession): Seq[AccountToken] =
    sql"""SELECT $columns FROM account_token
          WHERE contest_id = $contestId AND purpose = $Invitation
            AND id = (SELECT MAX(t2.id) FROM account_token t2
                      WHERE t2.user_id = account_token.user_id AND t2.contest_id = $contestId
                        AND t2.purpose = $Invitation)
            AND ((used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3))
                 OR created_at > NOW(3) - INTERVAL $days DAY)
            AND EXISTS (SELECT 1 FROM users u WHERE u.id = account_token.user_id AND u.deleted_at IS NULL
                          AND COALESCE(u.email, '') = account_token.email)
          ORDER BY id DESC""".map(extract).list.apply()

  def findByHash(tokenHash: String)(implicit session: DBSession = AutoSession): Option[AccountToken] =
    sql"SELECT $columns FROM account_token WHERE token_hash = $tokenHash".map(extract).single.apply()

  /** As [[findByHash]], the row locked until the transaction ends: one acceptance at a time. */
  def lockByHash(tokenHash: String)(implicit session: DBSession): Option[AccountToken] =
    sql"SELECT $columns FROM account_token WHERE token_hash = $tokenHash FOR UPDATE".map(extract).single.apply()

  /** The account's live links of the purposes sent to `email`, the latest first, their rows
    * locked until the transaction ends.
    */
  def lockLive(userId: Long, purposes: Seq[String], email: String)(implicit session: DBSession): Seq[AccountToken] =
    sql"""SELECT $columns FROM account_token
          WHERE user_id = $userId AND purpose IN ($purposes) AND email = $email
            AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)
          ORDER BY id DESC FOR UPDATE""".map(extract).list.apply()

  def find(id: Long)(implicit session: DBSession = AutoSession): Option[AccountToken] =
    sql"SELECT $columns FROM account_token WHERE id = $id".map(extract).single.apply()

  /** The account's links, the latest first. */
  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[AccountToken] =
    sql"SELECT $columns FROM account_token WHERE user_id = $userId ORDER BY id DESC".map(extract).list.apply()

  /** The live invitations into the contest that wait to give a role and were sent to the
    * account's current address (one sent before an email change can't be accepted), the latest
    * first.
    */
  def pendingInvitations(contestId: Long)(implicit session: DBSession = AutoSession): Seq[AccountToken] =
    sql"""SELECT ${columns} FROM account_token
          WHERE contest_id = $contestId AND purpose = $Invitation AND role IS NOT NULL
            AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)
            AND EXISTS (SELECT 1 FROM users u WHERE u.id = account_token.user_id AND u.email = account_token.email)
          ORDER BY id DESC""".map(extract).list.apply()

  /** Whether the account has a link of the purpose created less than `seconds` ago. */
  def createdSince(userId: Long, purpose: String, seconds: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM account_token WHERE user_id = $userId AND purpose = $purpose
            AND created_at > NOW(3) - INTERVAL $seconds SECOND)""".map(_.boolean(1)).single.apply().contains(true)

  /** How many links of the purpose the account got in the last `seconds`. */
  def countSince(userId: Long, purpose: String, seconds: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM account_token WHERE user_id = $userId AND purpose = $purpose
            AND created_at > NOW(3) - INTERVAL $seconds SECOND""".map(_.long(1)).single.apply().getOrElse(0L)

  /** Revokes the account's unused invitations into the contest (an admin's cancel); how many. */
  def revokeInvitations(userId: Long, contestId: Long)(implicit session: DBSession = AutoSession): Int =
    sql"""UPDATE account_token SET revoked_at = NOW(3)
          WHERE user_id = $userId AND purpose = $Invitation AND contest_id = $contestId
            AND used_at IS NULL AND revoked_at IS NULL""".update.apply()

  /** Every purpose. */
  val AllPurposes: Seq[String] = Seq(Invitation, PasswordReset, EmailChange, OAuthChoose)

  /** The purposes refused once the account's email is no longer the address the link went to. */
  val BoundToEmail: Seq[String] = SetsPassword :+ OAuthChoose

  /** The account's live invitations sent to `email` that wait to give a role. */
  def pendingInvitationsOf(userId: Long, email: String)(implicit session: DBSession = AutoSession): Seq[AccountToken] =
    sql"""SELECT $columns FROM account_token
          WHERE user_id = $userId AND purpose = $Invitation AND role IS NOT NULL AND email = $email
            AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)
          ORDER BY id""".map(extract).list.apply()

  /** Marks the link used, if it is live; false, changing nothing, otherwise. */
  def markUsed(id: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""UPDATE account_token SET used_at = NOW(3)
          WHERE id = $id AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)""".update.apply() > 0

  /** Revokes the account's unused invitations into the contest that wait to give a role: the
    * member's role was set directly since.
    */
  def revokePendingRoles(userId: Long, contestId: Long)(implicit session: DBSession = AutoSession): Int =
    sql"""UPDATE account_token SET revoked_at = NOW(3)
          WHERE user_id = $userId AND purpose = $Invitation AND contest_id = $contestId AND role IS NOT NULL
            AND used_at IS NULL AND revoked_at IS NULL""".update.apply()

  /** Revokes the account's unused links of the purposes (into `contestId` only, if given); how many. */
  def revokeUnused(userId: Long, purposes: Seq[String], contestId: Option[Long] = None)(implicit
      session: DBSession = AutoSession
  ): Int =
    if (purposes.isEmpty) 0
    else
      sql"""UPDATE account_token SET revoked_at = NOW(3)
            WHERE user_id = $userId AND purpose IN ($purposes) AND used_at IS NULL AND revoked_at IS NULL
              AND (${contestId.isEmpty} OR contest_id <=> $contestId)""".update.apply()
}
