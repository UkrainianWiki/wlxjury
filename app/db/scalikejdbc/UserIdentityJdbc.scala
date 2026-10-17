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

  def find(provider: String, subject: String)(implicit session: DBSession = AutoSession): Option[UserIdentity] =
    where("provider" -> provider, "subject" -> subject).apply().headOption

  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[UserIdentity] =
    where("userId" -> userId).orderBy(defaultAlias.provider, defaultAlias.subject).apply()

  /** Links the identity to its user; throws if the identity is linked already. */
  def create(identity: UserIdentity)(implicit session: DBSession = AutoSession): Unit =
    sql"""INSERT INTO user_identity (provider, subject, user_id, email, username, last_login_at)
          VALUES (${identity.provider}, ${identity.subject}, ${identity.userId},
                  ${identity.email}, ${identity.username}, CURRENT_TIMESTAMP)""".update.apply()

  /** Records a sign-in with the identity and the email and username it has now. */
  def touch(identity: UserIdentity)(implicit session: DBSession = AutoSession): Unit =
    sql"""UPDATE user_identity
          SET email = ${identity.email}, username = ${identity.username}, last_login_at = CURRENT_TIMESTAMP
          WHERE provider = ${identity.provider} AND subject = ${identity.subject}""".update.apply()

  /** Unlinks the identity, if it is the user's; false if it is not. */
  def delete(userId: Long, provider: String, subject: String)(implicit session: DBSession = AutoSession): Boolean =
    sql"""DELETE FROM user_identity
          WHERE user_id = $userId AND provider = $provider AND subject = $subject""".update.apply() > 0
}
