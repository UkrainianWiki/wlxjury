package db.scalikejdbc

import scalikejdbc._
import scalikejdbc.orm.JoinTable

/** A Google or Wikimedia identity the user signs in with: the provider's stable subject,
  * with the email and username it last had (shown on the user's edit page).
  */
case class UserIdentity(
    provider: String,
    subject: String,
    userId: Long,
    email: Option[String] = None,
    username: Option[String] = None
) {

  /** What the edit page shows for it. */
  def label: String = username.orElse(email).getOrElse(subject)
}

object UserIdentityJdbc extends JoinTable[UserIdentity] {
  override val tableName = "user_identity"
  override lazy val defaultAlias = createAlias("ui")

  override def extract(rs: WrappedResultSet, n: ResultName[UserIdentity]): UserIdentity =
    UserIdentity(
      provider = rs.string(n.provider),
      subject  = rs.string(n.subject),
      userId   = rs.long(n.userId),
      email    = rs.stringOpt(n.email),
      username = rs.stringOpt(n.username)
    )

  private def row(rs: WrappedResultSet): (UserIdentity, Boolean) =
    UserIdentity(rs.string("provider"), rs.string("subject"), rs.long("user_id"), rs.stringOpt("email"),
      rs.stringOpt("username")) -> rs.timestampOpt("unlinked_at").isDefined

  private val identityColumns = sqls"provider, subject, user_id, email, username, unlinked_at"

  /** The identity, if linked (not unlinked: [[findAny]]). */
  def find(provider: String, subject: String)(implicit session: DBSession = AutoSession): Option[UserIdentity] =
    findAny(provider, subject).collect { case (identity, false) => identity }

  /** The identity's row, linked or an unlinked tombstone (`true`). */
  def findAny(provider: String, subject: String)(implicit session: DBSession = AutoSession): Option[(UserIdentity, Boolean)] =
    sql"SELECT $identityColumns FROM user_identity WHERE provider = $provider AND subject = $subject".map(row).single.apply()

  /** The user's linked identities. */
  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[UserIdentity] =
    sql"""SELECT $identityColumns FROM user_identity WHERE user_id = $userId AND unlinked_at IS NULL
          ORDER BY provider, subject""".map(row).list.apply().map(_._1)

  /** Whether the user has, or had (a tombstone), an identity of the provider. */
  def hasProvider(userId: Long, provider: String)(implicit session: DBSession = AutoSession): Boolean =
    sql"SELECT EXISTS(SELECT 1 FROM user_identity WHERE user_id = $userId AND provider = $provider)"
      .map(_.boolean(1)).single.apply().contains(true)

  /** Links the identity to its user; throws `SQLIntegrityConstraintViolationException` if the
    * identity has a row already (linked, or a tombstone: [[relink]]), or the user is gone.
    */
  def create(identity: UserIdentity)(implicit session: DBSession = AutoSession): Unit =
    sql"""INSERT INTO user_identity (provider, subject, user_id, email, username, last_login_at)
          VALUES (${identity.provider}, ${identity.subject}, ${identity.userId},
                  ${identity.email}, ${identity.username}, CURRENT_TIMESTAMP(3))""".update.apply()

  /** Links an unlinked (tombstoned) identity again, to `identity.userId`: from that user's own
    * profile, or as the very identity signing in (a link it was unlinked by in the same
    * transaction). False if it is not a tombstone.
    */
  def relink(identity: UserIdentity)(implicit session: DBSession = AutoSession): Boolean =
    sql"""UPDATE user_identity
          SET user_id = ${identity.userId}, email = ${identity.email}, username = ${identity.username},
              unlinked_at = NULL, last_login_at = CURRENT_TIMESTAMP(3)
          WHERE provider = ${identity.provider} AND subject = ${identity.subject} AND unlinked_at IS NOT NULL""".update.apply() > 0

  /** Records a sign-in with the identity and the email and username it has now. */
  def touch(identity: UserIdentity)(implicit session: DBSession = AutoSession): Unit =
    sql"""UPDATE user_identity
          SET email = ${identity.email}, username = ${identity.username}, last_login_at = CURRENT_TIMESTAMP(3)
          WHERE provider = ${identity.provider} AND subject = ${identity.subject} AND unlinked_at IS NULL""".update.apply()

  /** Unlinks all the user's identities, or those of one provider, leaving tombstones: the account
    * changed hands (an accepted invitation or password link, root's password reset, an admin's
    * change of its email or wiki account); the number unlinked.
    */
  def deleteByUser(userId: Long, provider: Option[String] = None)(implicit session: DBSession = AutoSession): Int =
    provider.fold(
      sql"UPDATE user_identity SET unlinked_at = NOW(3) WHERE user_id = $userId AND unlinked_at IS NULL".update.apply())(p =>
      sql"""UPDATE user_identity SET unlinked_at = NOW(3)
            WHERE user_id = $userId AND provider = $p AND unlinked_at IS NULL""".update.apply())

  /** Whether the account has, or had, a Google or Wikimedia identity (tombstones too): it could
    * sign in without a password ([[services.UserService.invitationRefused]]).
    */
  def everLinked(userId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"SELECT EXISTS(SELECT 1 FROM user_identity WHERE user_id = $userId)".map(_.boolean(1)).single.apply()
      .contains(true)

  /** Unlinks the identity, if it is the user's, leaving a tombstone; false if it is not. */
  def delete(userId: Long, provider: String, subject: String)(implicit session: DBSession = AutoSession): Boolean =
    sql"""UPDATE user_identity SET unlinked_at = NOW(3)
          WHERE user_id = $userId AND provider = $provider AND subject = $subject AND unlinked_at IS NULL""".update.apply() > 0
}
