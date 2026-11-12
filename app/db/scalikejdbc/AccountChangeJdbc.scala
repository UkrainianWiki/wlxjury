package db.scalikejdbc

import scalikejdbc._

import java.time.Instant

/** One entry of the account change log (table `account_change`, V96): a change an admin or root
  * made to another person's account. It holds email addresses: only the contest's admins and root
  * read it ([[controllers.AccountChangeController]], the edit page).
  *
  * @param userId    whose account
  * @param actorId   who changed it
  * @param contestId the contest it was changed in (None: by root outside a contest)
  * @param field     what changed, one of [[AccountChangeJdbc.Fields]]
  * @param mailSent  whether the change mailed someone (None: no mail applies)
  * @param comment   the editor's optional comment for the log
  */
final case class AccountChange(
    id: Long,
    userId: Long,
    actorId: Long,
    contestId: Option[Long],
    field: String,
    oldValue: Option[String],
    newValue: Option[String],
    mailSent: Option[Boolean],
    comment: Option[String],
    createdAt: Instant
)

/** The `account_change` table (V96). Times from the database clock. */
object AccountChangeJdbc {

  val Email = "email"
  val Password = "password"
  val Role = "role"
  val Name = "name"
  val WikiAccount = "wiki_account"
  val Root = "root"
  val AccountCreated = "account_created"
  val Invitation = "invitation"
  val InvitationCancelled = "invitation_cancelled"
  val PasswordLink = "password_link"
  val ShareLink = "share_link"
  val SessionsEnded = "sessions_ended"

  /** The fields V96's check allows. */
  val Fields: Seq[String] = Seq(Email, Password, Role, Name, WikiAccount, Root, AccountCreated, Invitation,
    InvitationCancelled, PasswordLink, ShareLink, SessionsEnded)

  /** The longest old or new value, and comment, the columns take: longer ones are cut. */
  val ValueMaxLength = 255
  val CommentMaxLength = 500

  private val columns =
    sqls"id, user_id, actor_id, contest_id, field, old_value, new_value, mail_sent, comment, created_at"

  private def extract(rs: WrappedResultSet): AccountChange =
    AccountChange(
      id = rs.long("id"),
      userId = rs.long("user_id"),
      actorId = rs.long("actor_id"),
      contestId = rs.longOpt("contest_id"),
      field = rs.string("field"),
      oldValue = rs.stringOpt("old_value"),
      newValue = rs.stringOpt("new_value"),
      mailSent = rs.booleanOpt("mail_sent"),
      comment = rs.stringOpt("comment"),
      createdAt = rs.timestamp("created_at").toInstant
    )

  private def cut(value: Option[String], max: Int): Option[String] =
    value.map(_.trim).filter(_.nonEmpty).map(v => if (v.length > max) v.take(max) else v)

  /** A new entry; its id. Values and the comment are trimmed (blank: none) and cut to the columns. */
  def create(
      userId: Long,
      actorId: Long,
      contestId: Option[Long],
      field: String,
      oldValue: Option[String] = None,
      newValue: Option[String] = None,
      mailSent: Option[Boolean] = None,
      comment: Option[String] = None
  )(implicit session: DBSession = AutoSession): Long = {
    require(Fields.contains(field), "not an account change field")
    val (oldV, newV) = (cut(oldValue, ValueMaxLength), cut(newValue, ValueMaxLength))
    val text = cut(comment, CommentMaxLength)
    sql"""INSERT INTO account_change
            (user_id, actor_id, contest_id, field, old_value, new_value, mail_sent, comment, created_at)
          VALUES ($userId, $actorId, $contestId, $field, $oldV, $newV, $mailSent, $text, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()
  }

  /** The account's entries, the newest first, at most `limit`: those made in `contestId` if given
    * (an admin's view), else all of them (root's).
    */
  def findByUser(userId: Long, contestId: Option[Long], limit: Int)(implicit
      session: DBSession = AutoSession
  ): Seq[AccountChange] =
    sql"""SELECT $columns FROM account_change
          WHERE user_id = $userId AND (${contestId.isEmpty} OR contest_id <=> $contestId)
          ORDER BY created_at DESC, id DESC LIMIT $limit""".map(extract).list.apply()

  /** The contest's entries, the newest first: a page of `limit` after `offset`. */
  def findByContest(contestId: Long, limit: Int, offset: Int)(implicit
      session: DBSession = AutoSession
  ): Seq[AccountChange] =
    sql"""SELECT $columns FROM account_change WHERE contest_id = $contestId
          ORDER BY created_at DESC, id DESC LIMIT $limit OFFSET $offset""".map(extract).list.apply()

  /** Whether the account's address `email` was set by an admin who is not root now: an `email`
    * entry to it by such an actor ([[services.UserService.invitationRefused]]). Before V96 no admin
    * could change the address of an account with a password.
    */
  def emailSetByAdmin(userId: Long, email: String)(implicit session: DBSession = AutoSession): Boolean =
    email.nonEmpty && sql"""SELECT EXISTS(SELECT 1 FROM account_change c JOIN users a ON a.id = c.actor_id
          WHERE c.user_id = $userId AND c.field = $Email AND c.new_value = $email AND a.is_root = 0)"""
      .map(_.boolean(1)).single.apply().contains(true)

  def countByContest(contestId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM account_change WHERE contest_id = $contestId".map(_.long(1)).single.apply().getOrElse(0L)
}
