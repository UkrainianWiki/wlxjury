package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import modules.BlockingExecutionContext
import play.api.Configuration
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import services.diagnostics.ContestHealth

import javax.inject.Inject

/** A contest's health page (support diagnostics plan, D-1a): what its data shows now and what may
  * be wrong ([[ContestHealth]]), read-only. For root and the contest's admins (DG2); organizers,
  * jurors, inactive members and other contests' admins are denied (403, logged on
  * `security.authz`); signed out, the login page. A page GET: it switches the session's contest as
  * every admin page does (CLAUDE.md, "Contest switching"). On the blocking pool: it reads the
  * contest's files' country links (H2).
  */
class ContestHealthController @Inject() (cc: ControllerComponents, blocking: BlockingExecutionContext,
    config: Configuration)
    extends Secured(cc)
    with I18nSupport {

  private val settings = ContestHealth.Settings(config)

  def page(contestId: Long): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId), derived = true)) { user => implicit request =>
      ContestJuryJdbc.findById(contestId).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
      ) { contest =>
        Ok(views.html.contestHealth(user, ContestHealth.report(contest, user, settings)))
          .withHeaders(CACHE_CONTROL -> "no-store")
      }
    }
}
