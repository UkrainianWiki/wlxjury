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
      val users = User.findAll()
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

  /**
    * Executes code block if currentUser is allowed to edit other user, redirects to Login page otherwise
    *
    * @param currentUser currently logged user
    * @param otherUser   other user to edit
    * @param block       code block that operates on other user
    * @return view returned from code block or Login page if not allowed
    */
  def havingEditRights(currentUser: User, otherUser: User)(
      block: => Result): Result = {
    if (!currentUser.canEdit(otherUser)) {
      Redirect(routes.LoginController.index) // TODO message
    } else {
      block
    }
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
        val editedUser = User.findById(userId).get

        havingEditRights(user, editedUser) {
          val filledForm = editUserForm.fill(editedUser)
          Ok(views.html.editUser(user, filledForm, user.currentContest))
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
          havingEditRights(user, formUser) {

            val userId = formUser.getId
            val count = User.countByEmail(userId, formUser.email)
            if (count > 0) {
              BadRequest(
                views.html.editUser(
                  user,
                  editUserForm
                    .fill(formUser)
                    .withError("email", "email should be unique"),
                  contestId = formUser.contestId
                )
              )
            } else {
              if (userId == 0) {
                userService.createNewUser(user, formUser)
              } else {

                // only admin can update roles
                val newRoles = if (user.hasAnyRole(User.ADMIN_ROLES)) {
                  if (!user.hasRole(User.ROOT_ROLE) && formUser.roles.contains(
                        User.ROOT_ROLE)) {
                    userRolesFromDb(formUser)
                  } else {
                    formUser.roles
                  }
                } else {
                  userRolesFromDb(formUser)
                }

                User.updateUser(userId,
                                formUser.fullname,
                                formUser.wikiAccount,
                                formUser.email,
                                newRoles,
                                formUser.lang,
                                formUser.sort)

                for {
                  cid  <- formUser.contestId.orElse(user.contestId)
                  role <- newRoles.find(r => r != User.ROOT_ROLE && !r.startsWith("USER_ID_"))
                } UserContestJdbc.updateRole(userId, cid, role)

                for (password <- formUser.password) {
                  val hash = User.hash(formUser, password)
                  User.updateHash(userId, hash)
                }
              }

              val result = if (user.hasAnyRole(User.ADMIN_ROLES)) {
                Redirect(routes.UserController.users(formUser.contestId))
              } else {
                Redirect(routes.LoginController.index)
              }
              val lang = for (lang <- formUser.lang; if formUser.id == user.id)
                yield lang

              lang.fold(result)(l => result.withLang(Lang(l)))
            }
          }
        }
      )
  }

  /**
    *
    * @param user user with id
    * @return user roles from database
    */
  def userRolesFromDb(user: User): Set[String] = {
    (for (userId <- user.id;
          dbUser <- User.findById(userId))
      yield dbUser.roles).getOrElse(Set.empty)
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

  def resetPassword(id: Long): EssentialAction =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val editedUser = User.findById(id).get

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
