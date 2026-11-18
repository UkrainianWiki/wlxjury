package services

import db.scalikejdbc.{AccountChangeJdbc, User}
import play.api.mvc.{Cookie, RequestHeader, Result}
import scalikejdbc._

import java.time.Instant

/** The in-app notices of an account's owner: the adds from another admin's users page search
  * (accounts plan A-1, the maintainer's correction of 2026-11-16: never an automatic mail), the
  * adds by an unverified wiki account an admin confirmed (A-3), and the adds from another of the
  * admin's contests ([[AddFromContest]]). Each is an account change log row of the account with one
  * of the comments only the tool writes ([[UserService.NoticeComments]], or a
  * [[UserService.fromContestComment]]): the
  * role given (`role`), or an invitation that waits to give one (`invitation` with a role), of
  * the last [[ShownDays]] days. No table of their own: the owner dismisses them in this browser
  * (a cookie with the newest row's id they saw, [[CookieName]]); at sign-in a session flag
  * ([[SessionKey]]) shows a bar under the navbar that leads to the list on their own profile.
  */
object AccountNotices {

  /** The notices of the last 30 days, at most [[MaxShown]]. */
  val ShownDays = 30
  val MaxShown = 10

  /** The session flag set at sign-in while the account has notices to see. */
  val SessionKey = "accountNotices"

  /** The cookie of the newest notice dismissed in this browser: `<accountId>.<changeId>`. */
  val CookieName = "wlx_notices_seen"

  /** An add from search: into `contestName` (its id) by `byName` (blank if the actor has none),
    * with `role`; `invited`: the role waits for an invitation.
    */
  final case class Notice(changeId: Long, contestId: Option[Long], contestName: String, role: String, byName: String,
      invited: Boolean, createdAt: Instant, fromContest: Option[String] = None)

  /** The account's notices after `seenUpTo` (a change id), the newest first. */
  def pending(accountId: Long, seenUpTo: Long)(implicit session: DBSession = AutoSession): Seq[Notice] =
    sql"""SELECT ac.id, ac.contest_id, c.name, c.year, c.country, ac.field, ac.new_value, a.fullname,
                 ${db.scalikejdbc.DbTime.epoch("ac.created_at")}, ac.comment, s.name, s.year, s.country
          FROM account_change ac
          LEFT JOIN contest_jury c ON c.id = ac.contest_id
          LEFT JOIN users a ON a.id = ac.actor_id
          LEFT JOIN contest_jury s ON ac.comment IS NOT NULL AND BINARY ac.comment = CONCAT('from contest #', s.id)
          WHERE ac.user_id = $accountId AND ac.id > $seenUpTo
            AND (BINARY ac.comment IN (${UserService.NoticeComments})
                 OR BINARY ac.comment REGEXP ${UserService.FromContestCommentPattern})
            AND ac.field IN (${AccountChangeJdbc.Role}, ${AccountChangeJdbc.Invitation})
            AND ac.new_value IS NOT NULL
            AND ac.created_at > NOW(3) - INTERVAL $ShownDays DAY
          ORDER BY ac.id DESC LIMIT $MaxShown"""
      .map { rs =>
        val name = rs.stringOpt(3).map(n => s"$n ${rs.int(4)} in ${rs.stringOpt(5).getOrElse("")}".trim)
        // an add from another contest: that contest by name (its id, if it no longer exists)
        val from = rs.stringOpt(10).flatMap(UserService.fromContestId).map { id =>
          rs.stringOpt(11).fold(s"#$id")(n => s"$n ${rs.int(12)} in ${rs.stringOpt(13).getOrElse("")}".trim + s" (#$id)")
        }
        Notice(rs.long(1), rs.longOpt(2), name.getOrElse(rs.longOpt(2).fold("")(_.toString)), rs.string(7),
          rs.stringOpt(8).getOrElse(""), invited = rs.string(6) == AccountChangeJdbc.Invitation,
          db.scalikejdbc.DbTime.instant(rs, "ac.created_at"), from)
      }.list.apply()

  /** The newest notice id dismissed in this browser by `accountId` (0: none). */
  def seenUpTo(request: RequestHeader, accountId: Long): Long =
    request.cookies.get(CookieName).map(_.value).flatMap { v =>
      v.split('.') match {
        case Array(id, change) if id == accountId.toString => change.toLongOption
        case _                                             => None
      }
    }.getOrElse(0L)

  /** The account's notices not dismissed in this browser. */
  def pendingFor(request: RequestHeader, user: User): Seq[Notice] =
    user.id.fold(Seq.empty[Notice])(id => pending(id, seenUpTo(request, id)))

  /** `result` with the notices up to `changeId` dismissed in this browser, the session flag gone;
    * the cookie `Secure` as the session's is (`secure`).
    */
  def dismissed(result: Result, accountId: Long, changeId: Long, secure: Boolean)(implicit request: RequestHeader): Result =
    result
      .withCookies(Cookie(CookieName, s"$accountId.$changeId", maxAge = Some(ShownDays * 86400), path = "/",
        secure = secure, httpOnly = true, sameSite = Some(Cookie.SameSite.Lax)))
      .removingFromSession(SessionKey)

  /** The sentence of what the role does for its owner: an invitation's, a role that opens the
    * contest's pages, or one that doesn't ([[User.INACTIVE_ROLES]]); the mail's too.
    */
  def outcomeKey(role: String, invited: Boolean): String =
    if (invited) "account.added.notice.invited"
    else if (User.INACTIVE_ROLES.contains(role)) "account.added.notice.no.access"
    else "account.added.notice.member"

  /** Who added the account, for a notice: the admin's name, or "an admin of the contest". */
  def byText(byName: String)(implicit messages: play.api.i18n.Messages): String =
    if (byName.trim.isEmpty) messages("account.added.notice.by.unnamed") else messages("account.added.notice.by", byName.trim)
}
