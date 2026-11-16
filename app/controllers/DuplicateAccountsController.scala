package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import modules.BlockingExecutionContext
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import services.DuplicateAccounts

import javax.inject.Inject

/** The duplicates report (the account join plan's J-1): groups of accounts that share a
  * normalised wiki account or a verified email ([[DuplicateAccounts]]), read-only, with "Keep
  * separate". A contest's report is for its admins and root; the full report for root only.
  * Organizers, jurors, inactive members and other contests' admins are denied (403, logged on
  * `security.authz`); signed out, the login page. Keep separate is a form POST with the CSRF token;
  * nothing is mailed.
  */
class DuplicateAccountsController @Inject()(cc: ControllerComponents, blocking: BlockingExecutionContext)
    extends Secured(cc)
    with I18nSupport {

  /** The contest's report: the groups with an account in it (a member, or worked in it). */
  def report(contestId: Long): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      ContestJuryJdbc.findById(contestId).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
      ) { contest =>
        val report = DuplicateAccounts.report(user, Some(contestId))
        // email addresses: never kept by a cache
        Ok(views.html.duplicateAccounts(user, Some(contest), report)).withHeaders(CACHE_CONTROL -> "no-store")
      }
    }

  /** Root's report of every group, across the contests and outside them. */
  def all(): EssentialAction =
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val report = DuplicateAccounts.report(user, None)
      Ok(views.html.duplicateAccounts(user, None, report)).withHeaders(CACHE_CONTROL -> "no-store")
    }

  /** "Keep separate" on a group of the report of `contestId` (None: root's full report): the
    * group's accounts (`account`, repeated) and, to keep one of them apart from the others,
    * `apartFrom`. Back to the report with what happened; a group that has changed since the page
    * was shown records nothing; who may not decide for the group's accounts gets the 403 page.
    */
  def keepSeparate(contestId: Option[Long]): EssentialAction =
    // the builder first in the body (RouteAuthSpec reads it): root for the full report, the
    // contest's admins (and root) for a contest's
    withAuthOn(blocking)(contestId.fold[Secured.DescribedPermission](rolePermission(Set(User.ROOT_ROLE)))(c =>
      contestPermission(User.ADMIN_ROLES, Some(c)))) { user => implicit request =>
      val back = contestId.fold(routes.DuplicateAccountsController.all())(routes.DuplicateAccountsController.report)
      val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
      val accounts = form.getOrElse("account", Nil).map(_.trim.toLongOption)
      val apartFrom = form.get("apartFrom").flatMap(_.headOption).filter(_.trim.nonEmpty).map(_.trim.toLongOption)
      if (accounts.isEmpty || accounts.exists(_.isEmpty) || apartFrom.exists(_.isEmpty))
        BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
      else if (contestId.exists(c => ContestJuryJdbc.findById(c).isEmpty))
        NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
      else
        DuplicateAccounts.keepSeparate(user, contestId, accounts.flatten, apartFrom.flatten) match {
          case DuplicateAccounts.KeptApart(_) =>
            Redirect(back).flashing("duplicates" -> "duplicates.kept.apart")
          case DuplicateAccounts.Changed =>
            Redirect(back).flashing("duplicates" -> "duplicates.changed")
          case DuplicateAccounts.NotAllowed =>
            onUnAuthorized(user, request, Secured.Denial(contestId, Some(s"accounts:${accounts.flatten.distinct.sorted.mkString(",")}"),
              Set(User.ADMIN_ROLE), Some("not_an_admin_of_every_contest")))
        }
    }
}
