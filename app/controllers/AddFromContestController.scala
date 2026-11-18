package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import modules.BlockingExecutionContext
import org.intracer.wmua.ContestJury
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import services.{AddFromContest, UserService}

import javax.inject.Inject

/** "Add people from another of your contests" on the users page ([[AddFromContest]]): the
  * current contest's admins (and root) pick one of their other contests (root: any) and add some
  * of its members, none ticked, with "Send an invitation email" unticked. The page and the add
  * are the users page's (its admins and root); organizers, jurors, inactive members and other
  * contests' admins are denied (403, logged on `security.authz`); signed out, the login page. A
  * source contest the actor may not use, or an id that is not one of its members, is a 404. The
  * add is a form POST with the CSRF token.
  */
class AddFromContestController @Inject()(cc: ControllerComponents, userService: UserService,
    blocking: BlockingExecutionContext, oauthSettings: services.oauth.OAuthSettings)
    extends Secured(cc)
    with I18nSupport {

  private implicit def linkHostOf(implicit request: play.api.mvc.RequestHeader): services.LinkHost =
    services.LinkHost.of(request)

  /** The page: the contests to pick from and, with `from`, its members (only those of `role`, one
    * of [[AddFromContest.RoleFilters]], if given).
    */
  def page(contestId: Option[Long] = None, from: Option[Long] = None, role: Option[String] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestId)) { user => implicit request =>
      contestId.orElse(user.contestId).flatMap(ContestJuryJdbc.findById) match {
        case None => NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
        case Some(contest) =>
          val shownRole = role.filter(AddFromContest.RoleFilters.contains)
          from.map(id => AddFromContest.source(user, contest.getId, id)) match {
            case Some(None) => NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
            case picked =>
              val source = picked.flatten
              val rows = source.fold(Seq.empty[AddFromContest.Row])(AddFromContest.members(user, contest.getId, _, shownRole))
              // email addresses: never kept by a cache
              Ok(views.html.addFromContest(user, contest, AddFromContest.sources(user, contest.getId), source, shownRole,
                rows)).withHeaders(CACHE_CONTROL -> "no-store")
          }
      }
    }

  /** The add: the source contest (`from`), the ids ticked (`accountId`, repeated), each one's role
    * (`role-<id>`), and "Send an invitation email". Back to the users page with what happened;
    * nothing ticked: back to this page, which says so.
    */
  def add(contestId: Option[Long] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestId)) { user => implicit request =>
      def badRequest = BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
      contestId.orElse(user.contestId).flatMap(ContestJuryJdbc.findById) match {
        case None => NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
        case Some(contest) =>
          val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
          def one(name: String) = form.get(name).flatMap(_.headOption).map(_.trim)
          val from = one("from").flatMap(_.toLongOption)
          val ids = form.getOrElse("accountId", Nil).map(_.trim.toLongOption)
          val picks = ids.flatten.distinct.map(id => id -> one(s"role-$id").getOrElse(""))
          val sendInvitation = one("sendInvitation").contains("true")
          if (from.isEmpty || ids.exists(_.isEmpty) || picks.size > AddFromContest.MaxPeople ||
              picks.exists { case (_, role) => !User.isContestRole(role) })
            badRequest
          else AddFromContest.source(user, contest.getId, from.get) match {
            case None => NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
            case Some(source) if picks.isEmpty =>
              Redirect(routes.AddFromContestController.page(contest.id, source.id))
                .flashing("add-from" -> "users.add.from.none.ticked")
            case Some(source) => added(user, source, contest, picks, sendInvitation)
          }
      }
    }

  private def added(user: User, source: ContestJury, contest: ContestJury, picks: Seq[(Long, String)],
      sendInvitation: Boolean)(implicit request: play.api.mvc.Request[_]): Result =
    try {
      val outcome = AddFromContest.add(userService, user, source, contest, picks, sendInvitation,
        oauthSettings.wikimediaConfigured)
      Redirect(routes.UserController.users(contest.id)).flashing("users-added" -> outcome.message)
    } catch {
      case _: AddFromContest.NotMembers =>
        NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
      case _: UserService.RoleNotAssignable =>
        BadRequest(views.html.errorPage(Some(user), "error.title", "error.bad.request"))
    }
}
