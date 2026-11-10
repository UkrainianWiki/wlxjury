package controllers

import db.scalikejdbc.{AccountTokenJdbc, ApiTokenJdbc, ContestJuryJdbc, User, UserContestJdbc, UserIdentityJdbc}
import org.intracer.wmua._
import org.scalawiki.dto.cmd.query.Query
import org.scalawiki.dto.cmd.query.list._
import play.api.Configuration
import play.api.data.Form
import play.api.data.Forms._
import play.api.data.validation.{Constraint, Constraints, Valid}
import play.api.i18n.{I18nSupport, Lang, Messages}
import play.api.mvc.{Action, AnyContent, ControllerComponents, EssentialAction, Result}
import modules.BlockingExecutionContext
import services.oauth.{OAuthProviders, OAuthSettings}
import services.{AccountTokenService, ApiTokenService, PasswordHasher, ShareLinkService, UserService}
import spray.util.pimpFuture

import javax.inject.Inject
import scala.util.{Failure, Success, Try}

/**
  * Controller for admin views
  */
class UserController @Inject()(cc: ControllerComponents,
                               userService: UserService,
                               blocking: BlockingExecutionContext,
                               themeCookies: ThemeCookies = ThemeCookies.default,
                               oauthSettings: OAuthSettings = OAuthSettings.disabled)
    extends Secured(cc)
    with I18nSupport {

  /** Links mailed while serving a request point at its host, if it is a configured one
    * ([[UserService.linkUrl]]).
    */
  private implicit def linkHostOf(implicit request: play.api.mvc.RequestHeader): services.LinkHost =
    services.LinkHost.of(request)

  /** A refused role value: the editor's and the target's account ids, never the value. */
  private val rolesLogger = play.api.Logger("security.roles")

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
          val invitations = AccountTokenJdbc.latestInvitations(contestId, UserController.InvitationsShownDays)
          val members = users.flatMap(u => u.id.map(_ -> u)).toMap
          val accounts = members ++ invitations.map(_.userId).distinct.filterNot(members.contains)
            .flatMap(id => User.findById(id).map(id -> _))
          // a link to share only where this admin may create one (checked again on the POST), in a
          // few queries for the whole list
          val candidates = (members.values.filter(u => !u.isRoot && !(u.emailVerified && u.hasUsablePassword)).map(_.getId) ++
            invitations.filter(_.live).map(_.userId)).toSeq.distinct.flatMap(accounts.get)
          val shareable = ShareLinkService.mayCreateFor(user, candidates, AccountTokenJdbc.Invitation, Some(contestId),
            oauthSettings.wikimediaConfigured)

          Ok(
            views.html.users(user,
                             withWiki,
                             editUserForm.copy(data = Map("roles" -> "jury")),
                             Some(contest),
                             pending = invitations,
                             accounts = accounts,
                             shareable = shareable,
                             wikimediaSignIn = oauthSettings.wikimediaConfigured))
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
    * admin of each contest other is in or worked in ([[User.contestsWorkedIn]]).
    * Otherwise only other's role in the admin's contest can change: an admin of one
    * contest must not take over an account that is, say, an admin of another, nor an
    * account with a past elsewhere that they have just added to their contest by its
    * email.
    */
  def canEditAccount(currentUser: User, other: User): Boolean =
    currentUser.hasRole(User.ROOT_ROLE) || currentUser.id == other.id || !other.hasRole(User.ROOT_ROLE) && {
      val adminOf = currentUser.userContests.filter(_.roles.contains(User.ADMIN_ROLE)).map(_.contestId).toSet
      val contests = UserContestJdbc.findByUser(other.getId).map(_.contestId) ++
        User.contestsWorkedIn(other.getId)
      contests.forall(adminOf.contains)
    }

  /**
    * Shows edit user form
    *
    * @param userId userId of the user to edit
    * @param contestId for root, the contest whose role the form shows
    * @return edit user form view
    */
  def editUser(userId: Long, contestId: Option[Long] = None): EssentialAction =
    withAuth(rolePermission(
      Set(User.ADMIN_ROLE, User.ROOT_ROLE, s"USER_ID_$userId"))) {
      user => implicit request =>
        editableUser(user, userId, contestId).fold(Redirect(routes.LoginController.index)) { editedUser =>
          if (user.id.contains(userId)) ownProfile(Ok, user)
          else {
            val pageContest = editedUser.contestId.orElse(user.currentContest)
            val refusal = ShareLinkService.mayCreate(user, editedUser, AccountTokenJdbc.PasswordReset, pageContest,
              oauthSettings.wikimediaConfigured)
            // an account without an email signs in with Wikimedia where it is offered: said instead
            val wikimedia = refusal.filter(_ == ShareLinkService.SignsInWithWikimedia).flatMap(_ => editedUser.wikiAccount)
            // "Set password" where a link to share would be allowed: the same rule (updateUser)
            Ok(views.html.editUser(user, editUserForm.fill(editedUser), pageContest,
              passwordLinks = passwordLinksShown(user, userId), mayShareLink = refusal.isEmpty,
              signsInWithWikimedia = wikimedia, maySetPassword = refusal.isEmpty))
          }
        }
    }

  /** The contest an edit page of another account acts in: the account's as the page shows it,
    * else the user's current one.
    */
  private def pageContestOf(user: User, editedUser: User): Option[Long] =
    editedUser.contestId.orElse(user.currentContest)

  /** Why `user` may not set `editedUser`'s password on its edit page, None if they may: exactly
    * where they may create a password link to share for it ([[ShareLinkService.mayCreate]]).
    * [[AccountTokenService.setPasswordByAdmin]] checks it again, the account's row locked.
    */
  private def setPasswordRefusal(user: User, editedUser: User): Option[ShareLinkService.Refusal] =
    ShareLinkService.mayCreate(user, editedUser, AccountTokenJdbc.PasswordReset, pageContestOf(user, editedUser),
      oauthSettings.wikimediaConfigured)

  /** The account's latest links that set the password, with their status, for its edit page:
    * root sees them all; an admin, password links and invitations into contests they are an
    * admin of.
    */
  private def passwordLinksShown(user: User, accountId: Long): Seq[db.scalikejdbc.AccountToken] = {
    val adminOf = user.userContests.filter(_.role == User.ADMIN_ROLE).map(_.contestId).toSet
    AccountTokenJdbc.findByUser(accountId)
      .filter(t => AccountTokenJdbc.SetsPassword.contains(t.purpose))
      .filter(t => user.hasRole(User.ROOT_ROLE) || t.purpose == AccountTokenJdbc.PasswordReset ||
        t.contestId.exists(adminOf.contains))
      .take(UserController.LinksShown)
  }

  /** The own profile with `status`: the form (`userForm`, else the user's, its theme the stored
    * one, else the one this browser shows) and the API tokens card ([[ApiTokensCard]]) with
    * `tokenForm`, a `notice` (a message key) and, right after creating one, `newToken`. Each
    * rendering puts a new nonce in the session and the create form, so that a create is taken
    * once: reloading its response re-posts a used nonce.
    */
  private def ownProfile(
      status: Status,
      user: User,
      userForm: Option[Form[User]] = None,
      tokenForm: Form[(String, Option[Int])] = ApiTokensCard.form,
      newToken: Option[String] = None,
      notice: Option[String] = None
  )(implicit request: play.api.mvc.Request[_]): Result = {
    val withTheme =
      if (user.theme.flatMap(Theme.find).isEmpty) user.copy(theme = Some(Theme.of(request).value)) else user
    val nonce = ApiTokensCard.newNonce()
    // root only for now (the user, 2026-11-05): almost nothing can be done through the API yet,
    // and only by root, so nobody else gets the card ([[createApiToken]] refuses them too)
    val card = Option.when(user.hasRole(User.ROOT_ROLE))(
      ApiTokensCard(ApiTokenJdbc.findByUser(user.getId), tokenForm, newToken, nonce, notice))
    val linkNonce = ApiTokensCard.newNonce()
    val linked = LinkedAccountsCard(UserIdentityJdbc.findByUser(user.getId), oauthSettings.providersFor(request.host),
      linkNonce)
    status(views.html.editUser(user, userForm.getOrElse(editUserForm.fill(withTheme)), user.currentContest, card,
      Some(linked)))
      .addingToSession(ApiTokensCard.NonceKey -> nonce, OAuthController.LinkNonceKey -> linkNonce)
  }

  /** Creates a personal API token of the signed-in user (a POST of their profile's form, with
    * the CSRF token and the page's nonce) and shows it on the profile, in this response only
    * (`no-store`), never in the flash or a URL. The session only: page routes take no token,
    * so a token can't make or revoke tokens. A used or missing nonce (the response reloaded),
    * or [[ApiTokenService.MaxActive]] usable tokens already create nothing. A double click
    * (both requests with the page's nonce) leaves one usable token, the last response's
    * ([[ApiTokenService.createOnce]]). Root only, for now (see [[ownProfile]]): anyone else is
    * refused.
    */
  def createApiToken(): EssentialAction = withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
    val nonce = request.body.asFormUrlEncoded.flatMap(_.get(ApiTokensCard.NonceKey)).flatMap(_.headOption)
    if (nonce.isEmpty || nonce != request.session.get(ApiTokensCard.NonceKey))
      ownProfile(Conflict, user, notice = Some("error.api.token.already.submitted"))
    else
      ApiTokensCard.form
        .bindFromRequest()
        .fold(
          formWithErrors => ownProfile(BadRequest, user, tokenForm = formWithErrors),
          { case (name, expiresInDays) =>
            ApiTokenService.createOnce(user, name, expiresInDays, nonce.get) match {
              case ApiTokenService.CreateOutcome.Created(token) =>
                ownProfile(Ok, user, newToken = Some(token)).withHeaders(CACHE_CONTROL -> "no-store")
              case ApiTokenService.CreateOutcome.LimitReached =>
                ownProfile(Conflict, user, notice = Some("error.api.token.limit"))
              // the account's sessions ended meanwhile (an accepted link): no token
              case ApiTokenService.CreateOutcome.SessionEnded =>
                Redirect(routes.LoginController.login(None)).withNewSession
            }
          }
        )
  }

  /** Revokes every token of the signed-in user (a POST with the CSRF token, from their profile):
    * a password change leaves them (the user's decision), this ends them.
    */
  def revokeAllApiTokens(): EssentialAction = withAuth(_ => true) { user => implicit request =>
    val revoked = ApiTokenService.revokeAllOwn(user.getId)
    Redirect(routes.UserController.editUser(user.getId)).flashing("api-tokens-revoked-all" -> revoked.toString)
  }

  /** Revokes one of the signed-in user's own tokens; another user's, or none, is not found. */
  def revokeApiToken(id: Long): EssentialAction = withAuth(_ => true) { user => implicit request =>
    ApiTokenJdbc.find(id).filter(_.userId == user.getId).fold[Result](
      NotFound(views.html.errorPage(Some(user), "error.title", "error.api.token.not.found"))
    ) { token =>
      ApiTokenJdbc.revoke(token.id, user.getId)
      Redirect(routes.UserController.editUser(user.getId)).flashing("api-token-revoked" -> "1")
    }
  }

  /**
    * Saves user on user editing form submitting
    *
    * @return list of users
    */
  def saveUser(): EssentialAction = withAuthOn(blocking)() { user => implicit request => // may hash a password
    def formErrors(formWithErrors: Form[User]): Result =
      if (formWithErrors("id").value.contains(user.getId.toString))
        ownProfile(BadRequest, user, userForm = Some(formWithErrors))
      else {
        val contestId = Try(formWithErrors.data("contest").toLong).toOption
        // the "Set password" field again (with its error, a password too long) where it was shown
        val maySetPassword = formWithErrors("id").value.flatMap(_.toLongOption).filter(_ != 0)
          .flatMap(editableUser(user, _, contestId)).exists(setPasswordRefusal(user, _).isEmpty)
        BadRequest(views.html.editUser(user, formWithErrors, contestId = contestId, maySetPassword = maySetPassword))
      }

    /** A role that is not a contest role, or root from anyone but root: refused before
      * anything is written ([[User.mayAssignRoles]]).
      */
    def roleRefused(formUser: User): Result = {
      rolesLogger.warn(s"refused a role that is not assignable accountId=${user.getId} targetId=${formUser.getId}")
      formErrors(editUserForm.bindFromRequest().withError("roles", "error.invalid"))
    }

    editUserForm
      .bindFromRequest()
      .fold(
        formWithErrors => formErrors(formWithErrors), // binding failure
        formUser => {
          val userId = formUser.getId
          if (!User.mayAssignRoles(user, formUser.roles)) {
            roleRefused(formUser)
          } else if (userId == 0) {
            if (user.isAdmin(formUser.contestId)) {
              // an existing email joins the contest with its account, or is invited
              try {
                val added = userService.createNewUser(user, formUser)
                Redirect(routes.UserController.users(formUser.contestId))
                  .flashing("users-added" -> addedMessage(Seq(added)))
              } catch {
                // a role the service refuses ([[UserService.addToContest]]): nothing created
                case _: UserService.RoleNotAssignable => roleRefused(formUser)
                case _: UserService.InvalidWikiAccount =>
                  formErrors(editUserForm.bindFromRequest().withError("wikiAccount", "wiki.account.invalid"))
              }
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

  /** The current password the own profile's form sends with a new password or email (N2). */
  private def currentPassword(implicit request: play.api.mvc.Request[_]): Option[String] =
    request.body match {
      case body: AnyContent => body.asFormUrlEncoded.flatMap(_.get("currentPassword")).flatMap(_.headOption)
      case _                => None
    }

  /** Saves the edit form of `editedUser`, `user`'s own or another's. Everything is checked before
    * anything is written:
    *
    *  - one's own new password or email needs the current password (N2); a new email of one's
    *    own is not written but confirmed by a link mailed to it; one's own wiki account can't
    *    change (until the Commons OAuth login verifies it);
    *  - another account's password is set only where a password link to share for it would be
    *    allowed ([[setPasswordRefusal]]: root for anyone, an admin for an account that is theirs
    *    alone; otherwise 403), never with an email change in the same save, and with what using
    *    such a link does ([[AccountTokenService.setPasswordByAdmin]]); its
    *    email changes at once only for an invitee without a password (and a new invitation
    *    goes to it), else root's change is confirmed by a link and an admin's refused; its
    *    wiki account must be valid and no other account's, and is unverified;
    *  - an access role for an account that may not take one directly
    *    ([[User.mayTakeAccessRole]]) becomes an invitation.
    */
  private def updateUser(user: User, editedUser: User, formUser: User)(
      implicit request: play.api.mvc.Request[AnyContent]): Result = {
    val own = editedUser.id == user.id
    lazy val passwordRefusal = if (own) None else setPasswordRefusal(user, editedUser)
    // the "Set password" field on a page shown again: where the rule passed (false once it was
    // refused under the account's lock)
    def badRequest(form: Form[User], maySetPassword: => Boolean = passwordRefusal.isEmpty,
        notice: Option[String] = None) =
      if (own) ownProfile(BadRequest, user, userForm = Some(form))
      else BadRequest(views.html.editUser(user, form, contestId = editedUser.contestId,
        maySetPassword = maySetPassword, notice = notice))
    def refused(field: Option[String], key: String, maySetPassword: => Boolean = passwordRefusal.isEmpty,
        notice: Option[String] = None) = {
      val form = editUserForm.fill(formUser.copy(password = None))
      badRequest(field.fold(form.withGlobalError(key))(form.withError(_, key)), maySetPassword, notice)
    }

    val userId = editedUser.getId
    def wikiAccount(u: User) = u.wikiAccount.map(_.trim).filter(_.nonEmpty)
    val wikiChanged = wikiAccount(formUser) != wikiAccount(editedUser)
    val newWiki = wikiAccount(formUser).fold[Either[String, Option[String]]](Right(None))(User.normalizeWikiAccount)
    val emailChanged = formUser.email != editedUser.emailLo
    // another account's password has its own rule (passwordRefusal); one's own, the current password
    val accountChanged =
      formUser.fullname.trim != editedUser.fullname.trim || emailChanged || wikiChanged
    val setsOthersPassword = !own && formUser.password.nonEmpty
    /** Another account's password refused by the rule: a denial (403, logged on `security.authz`)
      * for who may not, else the form with the reason, without the field (the page shows no field
      * to put it on): an account without an email where Wikimedia sign-in is offered, or without a
      * name to sign in with, gets no password from anyone, root included. `notice`: refused under
      * the account's lock after the form's other fields were saved, it says so.
      */
    def passwordRefused(refusal: ShareLinkService.Refusal, notice: Option[String] = None): Result = refusal match {
      case r if r.forbidden =>
        onUnAuthorized(user, request, Secured.Denial(pageContestOf(user, editedUser), Some(s"account:$userId"),
          Set(User.ADMIN_ROLE), Some(r.reason)), notice)
      case ShareLinkService.SignsInWithWikimedia | ShareLinkService.NoLogin =>
        refused(None, "password.set.no.login", maySetPassword = false, notice)
      case _ => refused(None, "password.others.not.allowed", maySetPassword = false, notice)
    }
    // an access role the account can't take directly: an invitation, if it has an email
    val newRole = formUser.roles.headOption
      .filter(r => User.isContestRole(r) && user.hasAnyRole(User.ADMIN_ROLES))
      .filter(_ => user.hasRole(User.ROOT_ROLE) || !editedUser.hasRole(User.ROOT_ROLE))
      .filterNot(r => editedUser.contestRole.contains(r))
    val roleNeedsInvitation =
      newRole.exists(User.ACCESS_ROLES.contains) && editedUser.contestId.isDefined && !editedUser.mayTakeAccessRole
    // a role change, root's made or taken included: decided on the account before the save, so
    // never in the same save as a password set (which unverifies the email it was decided on)
    // (a contest role only for an account shown in a contest: updateAccount ignores it otherwise)
    val roleChanged = newRole.nonEmpty && editedUser.contestId.isDefined || !own && user.hasRole(User.ROOT_ROLE) &&
      formUser.roles.contains(User.ROOT_ROLE) != (editedUser.isRoot || editedUser.hasRole(User.ROOT_ROLE))

    if (accountChanged && !canEditAccount(user, editedUser)) {
      badRequest(editUserForm.fill(formUser).withGlobalError("user.in.other.contests"))
    } else if (own && wikiChanged) {
      refused(Some("wikiAccount"), "wiki.account.own.readonly")
    } else if (setsOthersPassword && passwordRefusal.nonEmpty) {
      passwordRefused(passwordRefusal.get)
    } else if (setsOthersPassword && emailChanged) {
      // the new password unverifies the email and revokes links; an email change mails one
      refused(Some("password"), "password.set.email.separately")
    } else if (setsOthersPassword && roleChanged) {
      // the role would be given as to a verified account: an admin whose password the setter chose
      refused(Some("password"), "password.set.role.separately")
    } else if (own && emailChanged && formUser.email.trim.isEmpty) {
      refused(Some("email"), "error.required")
    } else if (wikiChanged && newWiki.isLeft) {
      refused(Some("wikiAccount"), "wiki.account.invalid")
    } else if (wikiChanged && newWiki.toOption.flatten.exists(User.otherAccountsWithWikiAccount(userId, _).nonEmpty)) {
      refused(Some("wikiAccount"), "wiki.account.taken")
    } else if (!own && emailChanged && editedUser.password.nonEmpty && !user.hasRole(User.ROOT_ROLE)) {
      refused(Some("email"), "user.email.change.ask.user")
    } else if (!own && emailChanged && editedUser.password.nonEmpty && formUser.email.trim.isEmpty) {
      // root's change of an account with a password goes through a link to the new address: none
      refused(Some("email"), "error.required")
    } else if (!own && user.hasRole(User.ROOT_ROLE) && formUser.roles.contains(User.ROOT_ROLE) &&
      !editedUser.isRoot && editedUser.password.nonEmpty && !editedUser.emailVerified) {
      // L2: root only for an account whose holder proved its email (an invitation, a password link)
      refused(Some("roles"), "root.needs.verified.email")
    } else if (!own && emailChanged && editedUser.password.isEmpty && formUser.email.nonEmpty &&
      User.countByEmail(userId, formUser.email) > 0) {
      refused(Some("email"), "error.email.already.exist")
    } else if (roleNeedsInvitation && (editedUser.emailLo.isEmpty || editedUser.isRoot)) {
      refused(Some("roles"), "role.needs.invitation.email")
    } else {
      // one's own new password or email: the current password first (N2); then the new hash,
      // before anything is written: no turn to hash it changes nothing
      val needsCurrent = own && (formUser.password.nonEmpty || emailChanged)
      val checked: Either[Result, Option[String]] =
        try {
          if (needsCurrent && !currentPassword.exists(p =>
              // a legacy hash past its grace period counts as none (P8), replaced yet or not
              PasswordHasher.default.check(User.usableHash(user), p) != PasswordHasher.Rejected))
            Left(refused(Some("currentPassword"), "password.current.wrong"))
          // one's own, or another account's where the rule passed (checked again when it is stored)
          else Right(formUser.password.map(PasswordHasher.default.hash(_)))
        } catch {
          case _: PasswordHasher.Busy =>
            Left(passwordBusy(user, editUserForm.fill(formUser), editedUser.contestId, !own && passwordRefusal.isEmpty))
        }
      checked.fold(identity, {
        case Some(hash) if !own =>
          // another account's: last, after the rest of the form is saved (a failure there leaves
          // the password as it was), in its own transaction with the rule checked again under the
          // account's row lock; refused meanwhile, the password stays and the refusal is shown
          val setPassword = (othersSaved: Boolean) =>
            AccountTokenService.setPasswordByAdmin(user, userId, pageContestOf(user, editedUser), hash,
              oauthSettings.wikimediaConfigured).left.map(passwordRefused(_,
                Option.when(othersSaved)(Messages("password.set.others.saved")))).map(_ => ())
          updateAccount(user, editedUser, formUser, None, newWiki.toOption.flatten, roleNeedsInvitation,
            Some(setPassword))
        case newHash =>
          updateAccount(user, editedUser, formUser, newHash, newWiki.toOption.flatten, roleNeedsInvitation, None)
      })
    }
  }

  /** An admin's change of another account's email or wiki account: its Google and Wikimedia
    * identities (or one provider's), which may have been linked by the old one, are unlinked.
    */
  private def unlinkIdentities(userId: Long, provider: Option[String]): Unit = {
    val unlinked = UserIdentityJdbc.deleteByUser(userId, provider)
    if (unlinked > 0)
      play.api.Logger("security.account").info(s"oauth_unlinked_by_admin accountId=$userId count=$unlinked")
  }

  /** 503 with the form: no turn to hash the password came in time. */
  private def passwordBusy(user: User, form: Form[User], contestId: Option[Long], maySetPassword: Boolean)(
      implicit request: play.api.mvc.Request[_]): Result =
    ServiceUnavailable(views.html.editUser(user, form.withGlobalError("password.busy"), contestId = contestId,
      maySetPassword = maySetPassword))
      .withHeaders(RETRY_AFTER -> "5")

  /** Saves the checked form. `setOthersPassword`: another account's password, set after
    * everything else is saved ([[AccountTokenService.setPasswordByAdmin]]), given whether that
    * changed anything; its refusal (the account changed meanwhile) is the response.
    */
  private def updateAccount(user: User, editedUser: User, formUser: User, newHash: Option[String],
                            newWiki: Option[String], roleNeedsInvitation: Boolean,
                            setOthersPassword: Option[Boolean => Either[Result, Unit]])(
      implicit request: play.api.mvc.Request[_]): Result = {
    val userId = editedUser.getId
    val own = formUser.id == user.id
    val contest = editedUser.contestId.flatMap(ContestJuryJdbc.findById)
    val notices = Seq.newBuilder[String]
    var newVersion: Option[Int] = None
    // what the save writes that differs from the account's values (the fields left with a password
    // set: a role or email change is refused with it)
    def trimmed(wiki: Option[String]) = wiki.map(_.trim).filter(_.nonEmpty)
    val otherFieldsChanged = canEditAccount(user, editedUser) && (formUser.fullname != editedUser.fullname ||
      formUser.lang != editedUser.lang || formUser.sort != editedUser.sort ||
      !own && trimmed(formUser.wikiAccount) != trimmed(editedUser.wikiAccount))

    if (canEditAccount(user, editedUser)) {
      User.updateUser(userId, formUser.fullname, formUser.lang, formUser.sort)
      if (!own && formUser.wikiAccount.map(_.trim).filter(_.nonEmpty) != editedUser.wikiAccount.map(_.trim).filter(_.nonEmpty)) {
        User.setWikiAccount(userId, newWiki)
        // the name may now be someone else's: a Wikimedia account linked to the old one goes
        unlinkIdentities(userId, Some(OAuthProviders.Wikimedia))
      }
      // a new password ends the account's other sessions; this one stays, with the new version
      newVersion = newHash.flatMap(User.setPasswordHash(userId, _))
      for (_ <- newVersion)
        play.api.Logger("security.account").info(s"password_changed accountId=$userId by=self sessionsEnded=true")

      if (formUser.email != editedUser.emailLo) {
        if (own || editedUser.password.nonEmpty) {
          // confirmed by a link mailed to the new address; the same answer if it is taken (N7)
          userService.requestEmailChange(user, editedUser, formUser.email)
          notices += Messages("email.change.sent")
        } else {
          // an invitee who has not set a password: a fixed address, still unverified, and a
          // new invitation to it (the old one was bound to the old address)
          User.setEmail(userId, formUser.email, verified = false)
          // the invitee may now be someone else: a Google or Wikimedia account linked by the old
          // address goes (as the old address's invitation, which no longer matches)
          unlinkIdentities(userId, None)
          val updated = User.findById(userId).map(u => editedUser.copy(email = u.email, emailVerifiedAt = None))
          for (u <- updated if u.emailLo.nonEmpty && !u.isRoot && u.contestRole.exists(User.ACCESS_ROLES.contains))
            notices += Messages(if (userService.invite(user, u, contest, None)) "invitation.sent" else "invitation.not.sent")
        }
      }
    }

    // only admins change roles, only root makes root (users.is_root, never a contest
    // role), and only in editedUser's contest. A root user's form shows root, which an
    // admin's role list lacks: an admin leaves a root user's role alone.
    val newRole = formUser.roles.headOption
    if (user.hasRole(User.ROOT_ROLE) && !user.id.contains(userId)) {
      User.setRoot(userId, newRole.contains(User.ROOT_ROLE))
    }
    for {
      role <- newRole if User.isContestRole(role) && user.hasAnyRole(User.ADMIN_ROLES)
      if user.hasRole(User.ROOT_ROLE) || !editedUser.hasRole(User.ROOT_ROLE)
      contestId <- editedUser.contestId
      if !editedUser.contestRole.contains(role)
    } {
      if (roleNeedsInvitation) {
        // O2: the role waits for the account's owner to accept an invitation
        notices += Messages(if (userService.invite(user, editedUser, contest, Some(role))) "invitation.sent" else "invitation.not.sent")
      } else {
        UserContestJdbc.updateRole(userId, contestId, role)
        // a pending invitation's role must not come back when it is accepted
        AccountTokenJdbc.revokePendingRoles(userId, contestId)
      }
    }

    // another account's password, last: refused meanwhile, that refusal is the response
    val passwordRefusedNow = setOthersPassword.flatMap(set => set(otherFieldsChanged).left.toOption)
    if (passwordRefusedNow.nonEmpty) return passwordRefusedNow.get
    if (setOthersPassword.nonEmpty) notices += Messages("password.set.by.admin.done")

    val notice = notices.result().mkString(" ")
    // one's own profile with a notice (an email change's link sent) shows it there
    val result = if (own && notice.nonEmpty) {
      Redirect(routes.UserController.editUser(userId))
    } else if (user.hasAnyRole(User.ADMIN_ROLES)) {
      Redirect(routes.UserController.users(editedUser.contestId))
    } else {
      Redirect(routes.LoginController.index)
    }
    val withNotice = if (notice.isEmpty) result else result.flashing((if (own) "account-notice" else "users-added") -> notice)
    val lang = for (lang <- formUser.lang; if own)
      yield lang

    // the own profile's theme only (another user's form has none); a value that is not
    // one of the three changes nothing
    val theme = formUser.theme.flatMap(Theme.find).filter(_ => own)
    for (t <- theme) User.updateTheme(userId, t)

    // the own new password: this session goes on with the account's new version
    val withVersion = newVersion.filter(_ => own).fold(withNotice)(v =>
      withNotice.addingToSession(Secured.AuthVersion -> v.toString))
    val withLang = lang.fold(withVersion)(l => withVersion.withLang(Lang(l)))
    theme.fold(withLang)(themeCookies.set(withLang, _))
  }

  /** The navbar's colour mode switch (logged.scala.html): a POST with the CSRF token
    * that stores light, dark or auto in the signed-in user's own row and in the theme
    * cookie, then goes back to `returnTo`, a local path ([[Secured.safeLocalPath]]), else
    * the start page. Any signed-in user, root or without a contest too; the session's
    * contest stays as it is. Another value changes nothing.
    */
  def setTheme(): EssentialAction = withAuth(_ => true) { user => request =>
    val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    def field(name: String) = form.get(name).flatMap(_.headOption)
    val back = Redirect(Secured.safeLocalPath(field("returnTo")).getOrElse(routes.LoginController.index.url))
    field("theme").flatMap(Theme.find).fold(back) { theme =>
      User.updateTheme(user.getId, theme)
      themeCookies.set(back, theme)
    }
  }

  /** Runs `f` on `contestIdParam`, else the user's current contest; without either (a root
    * user, who needs no contest), shows the contests to pick one from.
    */
  private def withContestId(user: User, contestIdParam: Option[Long])(f: Long => Result): Result =
    contestIdParam.orElse(user.currentContest).fold(Redirect(routes.ContestController.list()))(f)

  /** The flash message for people added to a contest: how many got a new account, how many
    * joined it with the account they had, and how many were in it already.
    */
  private def addedMessage(added: Seq[UserService.Added])(implicit messages: Messages): String = {
    import UserService._
    val invited = added.count(_.isInstanceOf[Invited])
    val unverifiedWiki = added.count(_.isInstanceOf[WikiAccountUnverified])
    val notSent = added.count {
      case NewAccount(_, Some(false)) | Invited(_, false) => true
      case _                                              => false
    }
    (Seq(messages("users.added",
                  added.count(_.isInstanceOf[NewAccount]),
                  added.count(_.isInstanceOf[JoinedContest]),
                  added.count(_.isInstanceOf[AlreadyMember]))) ++
      (if (invited > 0) Seq(messages("users.invited", invited)) else Nil) ++
      (if (unverifiedWiki > 0) Seq(messages("users.not.added.wiki.unverified", unverifiedWiki)) else Nil) ++
      (if (notSent > 0) Seq(messages("users.invitations.not.sent", notSent)) else Nil)).mkString(" ")
  }

  def showImportUsers(contestIdParam: Option[Long]): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        withContestId(user, contestIdParam) { contestId =>
          Ok(views.html.importUsers(user, importUsersForm, contestId))
        }
    }

  def importUsers(contestIdParam: Option[Long] = None): EssentialAction =
    // a bcrypt hash per new account: off Play's default dispatcher
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request => withContestId(user, contestIdParam) { contestId =>
        importUsersForm
          .bindFromRequest()
          .fold(
            formWithErrors => // binding failure, you retrieve the form containing errors,
              BadRequest(
                views.html.importUsers(user, formWithErrors, contestId)),
            { case (formUsers, role) =>
              val contest = ContestJuryJdbc.findById(contestId)
              val parsed = User
                .parseList(formUsers)
                .map(
                  _.copy(
                    lang = user.lang,
                    contestId = Some(contestId)
                  ))

              // one at a time; after a password that got no turn to be hashed, the rest
              // aren't tried (each would wait as long), and all those not added are counted
              val (added, notAdded, _) =
                parsed.foldLeft((Vector.empty[UserService.Added], 0, false)) {
                  case ((done, failed, true), _) => (done, failed + 1, true)
                  case ((done, failed, false), pu) =>
                    Try(userService.addToContest(user, pu.copy(roles = Set(role)), contest)) match {
                      case Success(a)                       => (done :+ a, failed, false)
                      case Failure(_: PasswordHasher.Busy) => (done, failed + 1, true)
                      case Failure(_)                       => (done, failed + 1, false)
                    }
                }
              val message = addedMessage(added) +
                (if (notAdded > 0) " " + Messages("users.not.added", notAdded) else "")

              Redirect(routes.UserController.users(Some(contestId)))
                .flashing("users-added" -> message)
            }
          )
      }
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
      user => implicit request => withContestId(user, contestIdParam) { contestId =>
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
    }

  /** A user added by wiki account only has no email: the email is optional, but a user needs
    * an email or a wiki account.
    */
  val editUserForm = Form(
    mapping(
      "id" -> longNumber,
      "fullname" -> nonEmptyText,
      "wikiAccount" -> optional(text),
      "email" -> text.verifying(Constraint[String]("constraint.email") { e =>
        if (e.trim.isEmpty) Valid else Constraints.emailAddress()(e)
      }),
      // a new password: not blank, at most 72 bytes (bcrypt's limit)
      "password" -> optional(text.verifying(PasswordHasher.newPassword)),
      // exactly one contest role, or root (only root may submit it: [[User.mayAssignRoles]]);
      // no trimming, case folding or lists. Empty or absent: no role given
      "roles" -> optional(text.verifying("error.invalid", r => User.isContestRole(r) || r == User.ROOT_ROLE)),
      "contest" -> optional(longNumber),
      "lang" -> optional(text),
      "sort" -> optional(number),
      // light, dark or auto; any other value is dropped (no change, or none for a new user)
      "theme" -> optional(text).transform[Option[String]](_.flatMap(Theme.find).map(_.value), identity)
    )(User.applyEdit)(User.unapplyEdit)
      .verifying("user.email.or.wiki.account",
                 u => u.email.trim.nonEmpty || u.wikiAccount.exists(_.trim.nonEmpty))
  )

  /** "Import users": the people, and the role they join the contest with (jury by default;
    * one of [[User.IMPORT_ROLES]]).
    */
  val importUsersForm: Form[(String, String)] = Form(
    tuple(
      "userstoimport" -> nonEmptyText,
      "role" -> default(text, User.JURY_ROLE).verifying("error.invalid", User.IMPORT_ROLES.contains(_))
    )
  )

  val greetingTemplateForm = Form(
    mapping(
      "greetingtemplate" -> optional(text),
      "use" -> boolean
    )(Greeting.apply)(Greeting.unapply)
  )

  /** Makes `contestId`, one of the user's contests, the session's current contest: a POST
    * with the CSRF token (the navbar's contest menu), never a link another site could open.
    */
  def switchContest(contestId: Long): EssentialAction = withAuth() { user => request =>
    if (user.userContests.exists(m => m.contestId == contestId && m.active)) {
      val newSession = request.session + (Secured.CurrentContestId -> contestId.toString)
      Redirect(routes.LoginController.index).withSession(newSession)
    } else {
      onUnAuthorized(user, request, Secured.Denial.contest(Some(contestId), Secured.ViewerRoles))
    }
  }

  /** An old link to switch the contest (or a page cached from before it was a POST): the
    * start page, the contest unchanged.
    */
  def switchContestLink(contestId: Long): Action[AnyContent] = Action {
    Redirect(routes.LoginController.index)
  }

  /** Root's "reset password" (P7): mails the account a link that sets a new password
    * ([[UserService.sendPasswordLink]]). The old password works until the link is used; nobody
    * but the email's owner sees the new one. Root only; contest admins re-send an invitation
    * to an unverified member of their contest instead ([[reinvite]]), which does the same.
    */
  def resetPassword(id: Long): EssentialAction =
    // sends mail (SMTP blocks): off Play's default dispatcher
    withAuthOn(blocking)(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      User.findById(id).filter(_.deletedAt.isEmpty).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
      ) { found =>
        // B1: the account may be about to change hands; its holder re-links after accepting
        unlinkIdentities(id, None)
        val message =
          if (found.emailLo.isEmpty) "password.link.no.email"
          else if (userService.sendPasswordLink(user, found)) "password.link.sent"
          else "password.link.not.sent"
        Redirect(routes.UserController.editUser(id)).flashing("password-reset" -> Messages(message))
      }
    }

  /** Root's "end sessions" of an account (S3; a POST with the CSRF token, from its edit page):
    * its session version goes up, so every session of it ends (root's own too, if it is root's
    * account). API tokens stay: root revokes them on the account's tokens page.
    */
  def endSessions(id: Long): EssentialAction =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      User.findById(id).filter(_.deletedAt.isEmpty).flatMap(_ => User.endSessions(id)).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
      ) { _ =>
        play.api.Logger("security.account").info(s"sessions_ended accountId=$id byAccountId=${user.getId}")
        Redirect(routes.UserController.editUser(id)).flashing("sessions-ended" -> Messages("sessions.ended"))
      }
    }

  /** Cancels the account's unused invitations into the contest (L4): an admin of that contest
    * (root too). Nothing to cancel is not found.
    */
  def cancelInvitation(id: Long, contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      if (AccountTokenJdbc.revokeInvitations(id, contestId) == 0)
        NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
      else {
        play.api.Logger("security.account").info(
          s"invitation_cancelled accountId=$id byAccountId=${user.getId} contestId=$contestId")
        Redirect(routes.UserController.users(Some(contestId))).flashing("users-added" -> Messages("invitation.cancelled"))
      }
    }

  /** Sends a member (or a pending invitee) of the contest a new invitation: an admin of that
    * contest (root too), for an account whose email is unverified; never a root account, nor
    * one without an email. A person not in the contest, or invited into it, is not found.
    */
  def reinvite(id: Long, contestId: Long): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val inContest = User.findInContest(id, contestId).orElse {
        val pending = AccountTokenJdbc.findByUser(id)
          .exists(t => t.live && t.purpose == AccountTokenJdbc.Invitation && t.contestId.contains(contestId))
        if (pending) User.findById(id) else None
      }.filter(_.deletedAt.isEmpty)
      (inContest, ContestJuryJdbc.findById(contestId)) match {
        case (Some(account), Some(contest)) =>
          // a verified account without a password (none set yet) may still need its invitation
          // nor one with a usable password: a retired legacy one (P8) needs the invitation to set one
          if (account.isRoot || account.emailVerified && account.hasUsablePassword || account.emailLo.isEmpty) {
            BadRequest(views.html.errorPage(Some(user), "error.title", "invitation.not.possible"))
          } else {
            val sent = userService.reinvite(user, account, contest)
            Redirect(routes.UserController.users(Some(contestId)))
              .flashing("users-added" -> Messages(if (sent) "invitation.sent" else "invitation.not.sent"))
          }
        case _ => NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
      }
    }

  /** "Create a link to share" on a member's or a pending invitee's invitation into the contest
    * (a POST with the CSRF token, from the users page): a new invitation link, shown once on
    * the response to copy and pass on, never mailed, stored, logged or put in the session
    * ([[ShareLinkService]]). Root, or an admin of the contest for an account that is theirs
    * alone ([[ShareLinkService.refusal]]); the check runs as the user in that contest, never
    * switching the session's.
    */
  def shareInvitation(id: Long, contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      shareLink(user, id, AccountTokenJdbc.Invitation, Some(contestId))
    }

  /** "Create a link to share" on an account's edit page: a password link as root's mailed
    * reset, shown once ([[shareInvitation]]'s rules). An admin acts in `contestId`, else in
    * their current contest; root in `contestId` if given, else in none.
    */
  def sharePasswordLink(id: Long, contestId: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestId)) { user => implicit request =>
      val inContest = if (user.hasRole(User.ROOT_ROLE)) contestId else contestId.orElse(user.contestId)
      shareLink(user, id, AccountTokenJdbc.PasswordReset, inContest)
    }

  private def shareLink(user: User, id: Long, purpose: String, contestId: Option[Long])(implicit
      request: play.api.mvc.Request[AnyContent]
  ): Result =
    ShareLinkService.create(user, id, purpose, contestId, oauthSettings.wikimediaConfigured) match {
      case Right((link, token, account)) =>
        val back =
          if (purpose == AccountTokenJdbc.Invitation) routes.UserController.users(contestId).url
          else routes.UserController.editUser(id, contestId).url
        // the link in this response only: never cached, never sent on as a referrer
        Ok(views.html.shareLink(user, account, userService.invitationUrl(token), link, back, contestId))
          .withHeaders(CACHE_CONTROL -> "no-store", "Referrer-Policy" -> "no-referrer")
      case Left(refused) if refused.forbidden =>
        onUnAuthorized(user, request,
          Secured.Denial(contestId, Some(s"account:$id"), Set(User.ADMIN_ROLE), Some(refused.reason)))
      case Left(ShareLinkService.NotNeeded) =>
        BadRequest(views.html.errorPage(Some(user), "error.title", "invitation.not.possible"))
      case Left(ShareLinkService.NoLogin) =>
        BadRequest(views.html.errorPage(Some(user), "error.title", "share.link.no.login"))
      case Left(ShareLinkService.SignsInWithWikimedia) =>
        BadRequest(views.html.errorPage(Some(user), "error.title", "share.link.wikimedia"))
      case Left(_) => NotFound(views.html.errorPage(Some(user), "error.title", "error.user.not.found"))
    }
}

object UserController {

  /** The users page lists the live invitations into the contest and those of the last 30 days. */
  val InvitationsShownDays = 30

  /** An account's edit page shows its latest 5 links that set the password. */
  val LinksShown = 5
}

case class Greeting(text: Option[String], use: Boolean)
