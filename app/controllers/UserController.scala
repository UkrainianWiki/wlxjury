package controllers

import db.scalikejdbc.{ContestJuryJdbc, User, UserContestJdbc}
import org.intracer.wmua._
import org.scalawiki.dto.cmd.query.Query
import org.scalawiki.dto.cmd.query.list._
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Lang}
import play.api.mvc.{ControllerComponents, EssentialAction, Result}
import services.UserService
import spray.util.pimpFuture

import javax.inject.Inject
import scala.util.Try

/**
  * Controller for admin views
  */
class UserController @Inject()(cc: ControllerComponents,
                               userService: UserService)
    extends Secured(cc)
    with I18nSupport {

  /**
    * @param contestIdParam optional contest Id. If not set, contest of the admin user is used
    * @return List of users in admin view
    */
  def users(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        (for (contestId <- contestIdParam.orElse(user.currentContest);
              contest <- ContestJuryJdbc.findById(contestId)) yield {
          val users = User.findByContest(contestId).sorted
          val withWiki = wikiAccountInfo(users)

          Ok(
            views.html.users(user,
                             withWiki,
                             editUserForm.copy(data = Map("roles" -> "jury")),
                             Some(contest)))
        }).getOrElse(Redirect(routes.LoginController.index)) // TODO message
    }

  def allUsers(): EssentialAction =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val users = User.withMemberships(User.findAll())
      val contests = ContestJuryJdbc.findAll()
      Ok(
        views.html.users(user,
                         users,
                         editUserForm.copy(data = Map("roles" -> "jury")),
                         None,
                         contests))
    }

  /**
    * Checks if wiki accounts are valid and can be emailed via wiki acount
    *
    * @param users list of users to fetch wiki project account information
    * @return users with hasWikiEmail and accountValid flags set
    */
  def wikiAccountInfo(users: Seq[User]): Seq[User] = {
    val names = users.flatMap(_.wikiAccount)
    if (names.nonEmpty) {

      val accounts = Global.commons
        .run(
          org.scalawiki.dto.cmd.Action(
            Query(
              ListParam(
                Users(
                  UsUsers(names),
                  UsProp(UsEmailable, UsGender)
                ))
            ))
        )
        .await
        .flatMap(_.lastRevisionUser)
        .collect {
          case u: org.scalawiki.dto.User if u.id.isDefined => u
        }

      users.map { u =>
        val account = accounts.find(a => a.login == u.wikiAccount)

        u.copy(
          hasWikiEmail = account.flatMap(_.emailable).getOrElse(false),
          accountValid = account.isDefined
        )
      }
    } else {
      users
    }
  }

  /** The user `currentUser` may edit, in the contest whose role the edit form shows:
    * themselves; for root, anyone (in `contestId` if a member of it, else in their latest
    * contest); for an admin, members of the admin's current contest.
    */
  def editableUser(currentUser: User, userId: Long, contestId: Option[Long] = None): Option[User] =
    if (currentUser.id.contains(userId)) Some(currentUser)
    else if (currentUser.hasRole(User.ROOT_ROLE))
      contestId
        .flatMap(User.findInContest(userId, _))
        .orElse(User.findById(userId).map(Secured.inCurrentContest(_, None)))
    else if (currentUser.hasRole(User.ADMIN_ROLE))
      currentUser.contestId.flatMap(User.findInContest(userId, _))
    else None

  /** Whether `currentUser` may change `other`'s account (name, email, wiki account,
    * password), shared by all of other's contests: as root, as themselves, or as an
    * admin of each of other's contests. Otherwise only other's role in the admin's
    * contest can change: an admin of one contest must not take over an account that is,
    * say, an admin of another.
    */
  def canEditAccount(currentUser: User, other: User): Boolean =
    currentUser.hasRole(User.ROOT_ROLE) || currentUser.id == other.id || !other.hasRole(User.ROOT_ROLE) && {
      val adminOf = currentUser.userContests.filter(_.roles.contains(User.ADMIN_ROLE)).map(_.contestId).toSet
      UserContestJdbc.findByUser(other.getId).forall(m => adminOf.contains(m.contestId))
    }

  /**
    * Shows edit user form
    *
    * @param userId userId of the user to edit
    * @return edit user form view
    */
  def editUser(userId: Long): EssentialAction =
    withAuth(rolePermission(
      Set(User.ADMIN_ROLE, User.ROOT_ROLE, s"USER_ID_$userId"))) {
      user => implicit request =>
        editableUser(user, userId).fold(Redirect(routes.LoginController.index)) { editedUser =>
          val filledForm = editUserForm.fill(editedUser)
          Ok(views.html.editUser(user, filledForm, editedUser.contestId.orElse(user.currentContest)))
        }
    }

  /**
    * Saves user on user editing form submitting
    *
    * @return list of users
    */
  def saveUser(): EssentialAction = withAuth() { user => implicit request =>
    editUserForm
      .bindFromRequest()
      .fold(
        formWithErrors => // binding failure, you retrieve the form containing errors,
          BadRequest(
            views.html.editUser(
              user,
              formWithErrors,
              contestId = Try(formWithErrors.data("contest").toLong).toOption
            )
        ),
        formUser => {
          val userId = formUser.getId
          if (userId == 0) {
            if (user.isAdmin(formUser.contestId)) {
              // an existing email joins the contest with its account
              userService.createNewUser(user, formUser)
              Redirect(routes.UserController.users(formUser.contestId))
            } else {
              Redirect(routes.LoginController.index) // TODO message
            }
          } else {
            editableUser(user, userId, formUser.contestId).fold(Redirect(routes.LoginController.index)) {
              editedUser => updateUser(user, editedUser, formUser)
            }
          }
        }
      )
  }

  private def updateUser(user: User, editedUser: User, formUser: User)(
      implicit request: play.api.mvc.Request[_]): Result = {
    def badRequest(form: Form[User]) =
      BadRequest(views.html.editUser(user, form, contestId = editedUser.contestId))

    val userId = editedUser.getId
    def wikiAccount(u: User) = u.wikiAccount.map(_.trim).filter(_.nonEmpty)
    val accountChanged =
      formUser.fullname.trim != editedUser.fullname.trim ||
        formUser.email != editedUser.emailLo ||
        wikiAccount(formUser) != wikiAccount(editedUser) ||
        formUser.password.nonEmpty

    if (User.countByEmail(userId, formUser.email) > 0) {
      badRequest(editUserForm.fill(formUser).withError("email", "email should be unique"))
    } else if (accountChanged && !canEditAccount(user, editedUser)) {
      badRequest(editUserForm.fill(formUser).withGlobalError("user.in.other.contests"))
    } else {
      if (canEditAccount(user, editedUser)) {
        User.updateUser(userId,
                        formUser.fullname,
                        formUser.wikiAccount,
                        formUser.email,
                        formUser.lang,
                        formUser.sort)

        for (password <- formUser.password) {
          val hash = User.hash(formUser, password)
          User.updateHash(userId, hash)
        }
      }

      // only admins change roles, only root makes root, and only in editedUser's contest
      val newRole = formUser.roles.headOption
      if (user.hasRole(User.ROOT_ROLE) && !user.id.contains(userId)) {
        User.setRoot(userId, newRole.contains(User.ROOT_ROLE))
      }
      for {
        role <- newRole if role != User.ROOT_ROLE && user.hasAnyRole(User.ADMIN_ROLES)
        contestId <- editedUser.contestId
      } UserContestJdbc.updateRole(userId, contestId, role)

      val result = if (user.hasAnyRole(User.ADMIN_ROLES)) {
        Redirect(routes.UserController.users(editedUser.contestId))
      } else {
        Redirect(routes.LoginController.index)
      }
      val lang = for (lang <- formUser.lang; if formUser.id == user.id)
        yield lang

      lang.fold(result)(l => result.withLang(Lang(l)))
    }
  }

  def showImportUsers(contestIdParam: Option[Long]): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get
        Ok(views.html.importUsers(user, importUsersForm, contestId))
    }

  def importUsers(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        importUsersForm
          .bindFromRequest()
          .fold(
            formWithErrors => // binding failure, you retrieve the form containing errors,
              BadRequest(
                views.html.importUsers(user, importUsersForm, contestId)),
            formUsers => {
              val contest = ContestJuryJdbc.findById(contestId)
              val parsed = User
                .parseList(formUsers)
                .map(
                  _.copy(
                    lang = user.lang,
                    contestId = Some(contestId)
                  ))

              val results = parsed.map(
                pu =>
                  Try(userService
                    .createUser(user, pu.copy(roles = Set("jury")), contest)))

              Redirect(routes.UserController.users(Some(contestId)))
            }
          )

    }

  def editGreeting(contestIdParam: Option[Long],
                   substituteJurors: Boolean = true): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        (for (contestId <- contestIdParam.orElse(user.currentContest);
              contest <- ContestJuryJdbc.findById(contestId)) yield {

          val greeting = userService.getGreeting(contest)

          val recipient = new User(fullname = "Recipient Full Name",
                                   email = "Recipient email",
                                   id = None,
                                   contestId = contest.id)

          val substitution = if (substituteJurors) {
            val users = User.findByContest(contestId)
            users.map { recipient =>
              userService.fillGreeting(
                greeting.text.get,
                contest,
                user,
                recipient.copy(password = Some("***PASSWORD***")))
            }
          } else {
            Seq.empty
          }

          Ok(
            views.html.greetingTemplate(
              user,
              greetingTemplateForm.fill(greeting),
              contestId,
              userService.variables(contest, user, recipient),
              substitution
            ))
        }).getOrElse(Redirect(routes.LoginController.index))
    }

  def saveGreeting(contestIdParam: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        val contestId = contestIdParam.orElse(user.currentContest).get

        greetingTemplateForm
          .bindFromRequest()
          .fold(
            formWithErrors => // binding failure, you retrieve the form containing errors,
              BadRequest(
                views.html.importUsers(user, importUsersForm, contestId)),
            formGreeting => {

              ContestJuryJdbc.updateGreeting(contestId, formGreeting)
              Redirect(routes.UserController.users(Some(contestId)))
            }
          )

    }

  val editUserForm = Form(
    mapping(
      "id" -> longNumber,
      "fullname" -> nonEmptyText,
      "wikiAccount" -> optional(text),
      "email" -> email,
      "password" -> optional(text),
      "roles" -> optional(text),
      "contest" -> optional(longNumber),
      "lang" -> optional(text),
      "sort" -> optional(number)
    )(User.applyEdit)(User.unapplyEdit)
  )

  val importUsersForm = Form(
    single(
      "userstoimport" -> nonEmptyText
    )
  )

  val greetingTemplateForm = Form(
    mapping(
      "greetingtemplate" -> optional(text),
      "use" -> boolean
    )(Greeting.apply)(Greeting.unapply)
  )

  /** Makes `contestId`, one of the user's contests, the session's current contest. */
  def switchContest(contestId: Long): EssentialAction = withAuth() { user => request =>
    if (user.userContests.exists(_.contestId == contestId)) {
      val newSession = request.session + (Secured.CurrentContestId -> contestId.toString)
      Redirect(routes.LoginController.index).withSession(newSession)
    } else {
      onUnAuthorized(user)
    }
  }

  def resetPassword(id: Long): EssentialAction =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val editedUser = Secured.inCurrentContest(User.findById(id).get, None)

      val password = User.randomString(8)
      val contest: Option[ContestJury] =
        editedUser.currentContest.flatMap(ContestJuryJdbc.findById)
      val contestName = contest.fold("")(_.name)
      val hash = User.hash(editedUser, password)

      User.updateHash(editedUser.getId, hash)

      val juryhome = "http://localhost:9000"
      //        User.updateUser(formUser.fullname, formUser.email, hash, formUser.roles, formUser.contest)
      val subject: String = s"Password changed for $contestName jury"
      val message: String = s"Password changed for $contestName jury\n" +
        s" Please login to our jury tool $juryhome \nwith login: ${editedUser.email} and password: $password\n" +
        s"Regards, ${user.fullname}"
      // sendMail.sendMail(from = (user.fullname, user.email), to = Seq(user.email), bcc = Seq(user.email), subject = subject, message = message)

      Redirect(routes.UserController.editUser(id)).flashing(
        "password-reset" -> s"Password reset. New Password sent to ${editedUser.email}")

    }
}

case class Greeting(text: Option[String], use: Boolean)
