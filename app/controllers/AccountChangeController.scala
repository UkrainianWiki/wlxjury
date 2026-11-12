package controllers

import db.scalikejdbc.{AccountChangeJdbc, ContestJuryJdbc, User}
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import services.AccountChangeLog

import javax.inject.Inject

/** The contest's "Account changes" page (V96, the maintainer's decision of 2026-11-12): what the
  * contest's admins and root changed in other people's accounts in it. Only for the contest's
  * admins and root: it holds email addresses. Organizers, jurors and admins of other contests are
  * denied (403, logged on `security.authz`), the session's contest never switched by it but for
  * a page GET as every page ([[Secured.withAuth]]).
  */
class AccountChangeController @Inject()(cc: ControllerComponents) extends Secured(cc) with I18nSupport {

  /** A page of the contest's entries, the newest first ([[AccountChangeLog.PageSize]] a page; a
    * page past the last shows the last). A contest that doesn't exist (for root) is not found.
    */
  def changes(contestId: Long, page: Int = 1): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      ContestJuryJdbc.findById(contestId).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
      ) { contest =>
        val size = AccountChangeLog.PageSize
        val total = AccountChangeJdbc.countByContest(contestId)
        val pages = math.max(1L, (total + size - 1) / size).toInt
        val shown = math.min(math.max(page, 1), pages)
        val rows = AccountChangeLog.rows(AccountChangeJdbc.findByContest(contestId, size, (shown - 1) * size))
        // email addresses: never kept by a cache
        Ok(views.html.accountChanges(user, contest, contestId, rows, shown, pages)).withHeaders(CACHE_CONTROL -> "no-store")
      }
    }
}
