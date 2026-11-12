package services

import db.scalikejdbc.{AccountChange, AccountChangeJdbc, User}
import scalikejdbc.{AutoSession, DBSession}

/** The account change log (V96, the maintainer's decision of 2026-11-12): what an admin or root
  * changed in another person's account, written in the transaction of the change where there is
  * one, never for a refused change. One's own changes are not logged here. The `security.account`
  * lines stay as they are (ids only); this log is for the contest's admins and root, and holds
  * addresses.
  */
object AccountChangeLog {

  /** Records the change `actor` made to account `accountId` in `contestId`, unless it is the
    * actor's own account.
    */
  def record(
      actor: User,
      accountId: Long,
      contestId: Option[Long],
      field: String,
      oldValue: Option[String] = None,
      newValue: Option[String] = None,
      mailSent: Option[Boolean] = None,
      comment: Option[String] = None
  )(implicit session: DBSession = AutoSession): Unit =
    for (actorId <- actor.id if actorId != accountId)
      AccountChangeJdbc.create(accountId, actorId, contestId, field, oldValue, newValue, mailSent, comment)

  /** How many entries the edit page shows. */
  val ShownOnEditPage = 50

  /** Entries per page of the contest's "Account changes". */
  val PageSize = 100

  /** An entry as the pages show it: with its actor's and account's names. */
  final case class Row(change: AccountChange, actor: Option[User], account: Option[User])

  /** The entries with their actors and accounts, read in one query. */
  def rows(changes: Seq[AccountChange]): Seq[Row] = {
    val ids = (changes.map(_.actorId) ++ changes.map(_.userId)).distinct
    val users = User.findByIds(ids).flatMap(u => u.id.map(_ -> u)).toMap
    changes.map(c => Row(c, users.get(c.actorId), users.get(c.userId)))
  }
}
