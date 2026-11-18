package services

import db.scalikejdbc.{AccountChange, AccountChangeJdbc, ContestJuryJdbc, User}
import org.intracer.wmua.ContestJury
import scalikejdbc.{AutoSession, DBSession}

/** The account change log (V96, the maintainer's decision of 2026-11-12): what an admin or root
  * changed in another person's account, written in the transaction of the change where there is
  * one, never for a refused change. One's own changes are not logged here. The `security.account`
  * lines stay as they are (ids only); this log is for the contest's admins and root, and holds
  * addresses.
  */
object AccountChangeLog {

  /** Records the change `actor` made to account `accountId` in `contestId`, unless it is the
    * actor's own account; the entry's id, if one was written.
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
  )(implicit session: DBSession = AutoSession): Option[Long] =
    for (actorId <- actor.id if actorId != accountId)
      yield AccountChangeJdbc.create(accountId, actorId, contestId, field, oldValue, newValue, mailSent, comment)

  /** How many entries the edit page shows. */
  val ShownOnEditPage = 50

  /** Entries per page of the contest's "Account changes". */
  val PageSize = 100

  /** An entry as the pages show it: with its actor's and account's names, and for an add from
    * another contest ([[UserService.fromContestComment]]) that contest's id and the contest, if it
    * still exists (the maintainer, 2026-11-18: the new contest's admins see where people come
    * from).
    */
  final case class Row(change: AccountChange, actor: Option[User], account: Option[User],
      fromContest: Option[(Long, Option[ContestJury])] = None)

  /** The entries with their actors, accounts and source contests, read in one query each. */
  def rows(changes: Seq[AccountChange]): Seq[Row] = {
    val ids = (changes.map(_.actorId) ++ changes.map(_.userId)).distinct
    val users = User.findByIds(ids).flatMap(u => u.id.map(_ -> u)).toMap
    val sources = changes.flatMap(_.comment).flatMap(UserService.fromContestId).distinct
    val contests = if (sources.isEmpty) Map.empty[Long, ContestJury]
      else ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, sources))
        .flatMap(c => c.id.map(_ -> c)).toMap
    changes.map { c =>
      val from = c.comment.flatMap(UserService.fromContestId).map(id => id -> contests.get(id))
      Row(c, users.get(c.actorId), users.get(c.userId), from)
    }
  }
}
