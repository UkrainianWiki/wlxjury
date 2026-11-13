package controllers

import db.scalikejdbc.{AccountChangeJdbc, AccountTokenJdbc, ApiTokenJdbc, ContestJuryJdbc, User, UserContestJdbc,
  UserIdentityJdbc}
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
import services.{AccountChangeLog, AccountTokenService, ApiTokenService, PasswordHasher, ShareLinkService, UserService}
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
  def editUser(userId: Long, contestId: Option[Long] = None, returnTo: Option[String] = None): EssentialAction =
    withAuth(rolePermission(
      Set(User.ADMIN_ROLE, User.ROOT_ROLE, s"USER_ID_$userId"))) {
      user => implicit request =>
        editableUser(user, userId, contestId).fold(Redirect(routes.LoginController.index)) { editedUser =>
          // returnTo: the page the profile was opened from (the navbar's link), read again by
          // ownProfile from the request
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
              signsInWithWikimedia = wikimedia, maySetPassword = refusal.isEmpty,
              changes = changesShown(user, userId, pageContest)))
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

  /** The account change log's entries for another account's edit page (V96): root sees all of
    * them; an admin, those made in the page's contest, which [[editableUser]] has checked they
    * are an admin of. Nobody else gets the edit page of another account.
    */
  private def changesShown(user: User, accountId: Long, pageContest: Option[Long]): Seq[AccountChangeLog.Row] =
    if (user.hasRole(User.ROOT_ROLE))
      AccountChangeLog.rows(AccountChangeJdbc.findByUser(accountId, None, AccountChangeLog.ShownOnEditPage))
    else if (pageContest.exists(c => user.isAdmin(Some(c))))
      AccountChangeLog.rows(AccountChangeJdbc.findByUser(accountId, pageContest, AccountChangeLog.ShownOnEditPage))
    else Nil

  /** The own profile with `status`: the form (`userForm`, else the user's, its theme the stored
    * one, else the one this browser shows, and the request's returnTo,
    * [[UserController.requestReturnTo]], to go back to after a save) and the API tokens card
    * ([[ApiTokensCard]]) with
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
      Some(linked), returnTo = UserController.requestReturnTo(request)))
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

    /** A role that is not a contest role (root included: it is no role): refused before
      * anything is written ([[User.mayAssignRoles]]).
      */
    def roleRefused(formUser: User): Result = {
      rolesLogger.warn(s"refused a role that is not assignable accountId=${user.getId} targetId=${formUser.getId}")
      formErrors(editUserForm.bindFromRequest().withError("roles", "error.invalid"))
    }
    val bound = editUserForm.bindFromRequest()
    // "Send an invitation email" and the comment for the account change log: not the account's
    val boundExtras = UserController.editExtrasForm.bindFromRequest()
    val extras = boundExtras.value.getOrElse(UserController.EditExtras(sendInvitation = false, comment = None))
    bound
      .fold(
        formWithErrors => formErrors(formWithErrors), // binding failure
        formUser => {
          val userId = formUser.getId
          // the Root field as bound (formUser.isRoot), if the form has it ([[rootFields]])
          val (rootGiven, rootWas) = rootFields(bound, formUser)
          if (boundExtras.hasErrors) {
            // a comment longer than the log keeps: the form again, nothing written
            formErrors(boundExtras.errors.foldLeft(bound)((f, e) => f.withError(e)))
          } else if (!User.mayAssignRoles(user, formUser.roles)) {
            roleRefused(formUser)
          } else if (userId == 0 && formUser.isRoot) {
            // no new account is made root, by anyone: root is given on Edit user, once its owner
            // has accepted the invitation (L2)
            rolesLogger.warn(s"refused root for a new account accountId=${user.getId}")
            formErrors(bound.withGlobalError("root.new.account"))
          } else if (userId == 0) {
            if (user.isAdmin(formUser.contestId)) {
              // an existing email joins the contest with its account, or is invited
              try {
                val added = userService.createNewUser(user, formUser, extras.sendInvitation)
                Redirect(routes.UserController.users(formUser.contestId))
                  .flashing("users-added" -> addedMessage(Seq(added)))
              } catch {
                // a role the service refuses ([[UserService.addToContest]]): nothing created
                case _: UserService.RoleNotAssignable => roleRefused(formUser)
                case _: UserService.ContestNotFound =>
                  NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
                case _: UserService.InvalidWikiAccount =>
                  formErrors(editUserForm.bindFromRequest().withError("wikiAccount", "wiki.account.invalid"))
              }
            } else {
              Redirect(routes.LoginController.index) // TODO message
            }
          } else {
            editableUser(user, userId, formUser.contestId).fold(Redirect(routes.LoginController.index)) { editedUser =>
              val editedRoot = editedUser.isRoot || editedUser.hasRole(User.ROOT_ROLE)
              // root changes only with the Root field, only by root, never for themselves; a
              // contest role never changes it. A toggle of the state the page showed (rootWas;
              // without it, the account's now): a page loaded before another root's change of it
              // doesn't undo that change
              val toggle = rootGiven.filter(_ != rootWas.getOrElse(editedRoot))
              // another account's address as the page showed it (`emailWas`): changed meanwhile by
              // someone else, an untouched field keeps the stored one (no revert), an edited one is
              // refused (reload the page)
              val emailWas = if (user.id.contains(userId)) None else bound.data.get("emailWas").map(LoginText.email)
              val staleEmail = emailWas.exists(_ != editedUser.emailLo)
              val emailEdited = !emailWas.contains(LoginText.email(formUser.email))
              if (staleEmail && emailEdited)
                // shown again with the stored address as the one the page showed: a resubmit is current
                formErrors(bound.copy(data = bound.data + ("emailWas" -> editedUser.emailLo))
                  .withError("email", "user.email.changed.meanwhile"))
              else if (toggle.nonEmpty && !user.hasRole(User.ROOT_ROLE))
                // only a crafted request: nobody else's page has the field
                onUnAuthorized(user, request, Secured.Denial(pageContestOf(user, editedUser), Some(s"account:$userId"),
                  Set(User.ROOT_ROLE), Some("root_change")))
              else if (toggle.nonEmpty && user.id.contains(userId))
                formErrors(bound.withError("root", "root.own.unchanged"))
              else
                updateUser(user, editedUser, formUser.copy(isRoot = rootGiven.getOrElse(editedRoot),
                  email = if (staleEmail) Option(editedUser.email).getOrElse("") else formUser.email),
                  toggle.filter(_ != editedRoot), extras)
            }
          }
        }
      )
  }

  /** The form shown again with the submitted `rootWas` (the account's root as the page first
    * showed it), so that its Root checkbox, as submitted, is still saved only as a toggle of
    * that state: another root's change of it meanwhile isn't undone by a resubmit; and with the
    * fields that are not the account's ("Send an invitation email", the log's comment) as sent.
    */
  private def keepRootWas(form: Form[User])(implicit request: play.api.mvc.Request[_]): Form[User] = {
    val sent = editUserForm.bindFromRequest().data
    form.copy(data = form.data ++ Seq("rootWas", "emailWas", "sendInvitation", "comment").flatMap(k =>
      sent.get(k).map(k -> _)))
  }

  /** The edit page's Root field, from the bound form (whatever the body's encoding): the value
    * bound to `formUser.isRoot` (the first one: the checkbox's `true` if ticked, else the hidden
    * `false` after it), None if the form has no such field (not root's page, or a client that
    * leaves it out), which changes nothing; and `rootWas`, the account's root as the page showed
    * it, if sent.
    */
  private def rootFields(bound: Form[User], formUser: User): (Option[Boolean], Option[Boolean]) =
    (bound.data.get("root").filter(_.nonEmpty).map(_ => formUser.isRoot),
     bound.data.get("rootWas").flatMap(_.toBooleanOption))

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
    *    such a link does ([[AccountTokenService.setPasswordByAdmin]]); its email changes at once
    *    (the maintainer's decision of 2026-11-12: root, or an admin who may edit the account),
    *    password or not ([[UserService.changeEmailByAdmin]]: unverified, the password kept, a
    *    notice to a verified former address), never to another account's, nor to none for an
    *    account with a password; its wiki account must be valid and no other account's, and is
    *    unverified;
    *  - `extras`: an invitation is mailed only if "Send an invitation email" was ticked; the
    *    comment goes with the save's entries in the account change log;
    *  - an access role for an account that may not take one directly
    *    ([[User.mayTakeAccessRole]]) becomes an invitation;
    *  - `rootChange`: root's change of another account's users.is_root with the Root field
    *    (checked in [[saveUser]]), only for an account whose holder proved its email (L2). A
    *    contest role never changes it.
    */
  private def updateUser(user: User, editedUser: User, formUser: User, rootChange: Option[Boolean],
      extras: UserController.EditExtras)(
      implicit request: play.api.mvc.Request[AnyContent]): Result = {
    val own = editedUser.id == user.id
    lazy val passwordRefusal = if (own) None else setPasswordRefusal(user, editedUser)
    // the "Set password" field on a page shown again: where the rule passed (false once it was
    // refused under the account's lock)
    // `emailWas`: the address the page shows again as the one it showed (an address changed
    // meanwhile: the stored one), else as sent
    def badRequest(form: Form[User], maySetPassword: => Boolean = passwordRefusal.isEmpty,
        notice: Option[String] = None, emailWas: Option[String] = None) =
      if (own) ownProfile(BadRequest, user, userForm = Some(form))
      else {
        val kept = keepRootWas(form)
        BadRequest(views.html.editUser(user, emailWas.fold(kept)(was => kept.copy(data = kept.data + ("emailWas" -> was))),
          contestId = editedUser.contestId, maySetPassword = maySetPassword, notice = notice))
      }
    def refused(field: Option[String], key: String, maySetPassword: => Boolean = passwordRefusal.isEmpty,
        notice: Option[String] = None, emailWas: Option[String] = None) = {
      val form = editUserForm.fill(formUser.copy(password = None))
      badRequest(field.fold(form.withGlobalError(key))(form.withError(_, key)), maySetPassword, notice, emailWas)
    }

    val userId = editedUser.getId
    // both sides cleaned as the form cleans: a stored name with a pasted space is not a change
    def wikiAccount(u: User) = u.wikiAccount.map(LoginText.clean).filter(_.nonEmpty)
    val wikiChanged = wikiAccount(formUser) != wikiAccount(editedUser)
    val newWiki = wikiAccount(formUser).fold[Either[String, Option[String]]](Right(None))(User.normalizeWikiAccount)
    // another account's address as stored (lowercased): a change of case only is none
    val emailChanged =
      if (own) formUser.email != editedUser.emailLo else LoginText.email(formUser.email) != editedUser.emailLo
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
    val roleChanged = newRole.nonEmpty && editedUser.contestId.isDefined || rootChange.nonEmpty

    if (accountChanged && !canEditAccount(user, editedUser)) {
      badRequest(editUserForm.fill(formUser).withGlobalError("user.in.other.contests"))
    } else if (rootChange.nonEmpty && emailChanged) {
      // an invitee's new email mails an invitation, never one to a root account: separate saves
      refused(Some("root"), "root.set.email.separately")
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
    } else if (!own && emailChanged && editedUser.password.nonEmpty && formUser.email.trim.isEmpty) {
      // an account with a password keeps an address to sign in with
      refused(Some("email"), "error.required")
    } else if (rootChange.contains(true) && editedUser.password.nonEmpty && !editedUser.emailVerified) {
      // L2: root only for an account whose holder proved its email (an invitation, a password link)
      // L2, checked again as root is written ([[User.setRootLocked]])
      refused(Some("root"), "root.needs.verified.email")
    } else if (!own && emailChanged && formUser.email.trim.nonEmpty &&
      User.countByEmail(userId, LoginText.email(formUser.email)) > 0) {
      // checked again as it is written (UserService.changeEmailByAdmin)
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
            Left(passwordBusy(user, editUserForm.fill(formUser), own, editedUser.contestId,
              !own && passwordRefusal.isEmpty))
        }
      // root first, decided again under the rows' locks ([[User.setRootLocked]]): refused, nothing
      // of the form is written
      def writeRoot(): Option[Result] = rootChange.flatMap { root =>
        User.setRootLocked(user.getId, userId, root, onChanged = s =>
          // logged in the account's contest only (None: root's log), never the page's fallback
          AccountChangeLog.record(user, userId, editedUser.contestId, AccountChangeJdbc.Root,
            Some((!root).toString), Some(root.toString), comment = extras.comment)(s)) match {
          case Left(key) =>
            rolesLogger.warn(s"refused a root change accountId=${user.getId} targetId=$userId reason=$key")
            Some(refused(Some("root"), key))
          case Right(changed) =>
            if (changed) rolesLogger.info(s"root_changed accountId=$userId root=$root byAccountId=${user.getId}")
            None
        }
      }
      // a save refused inside its transaction: the address's, or the account no longer the editor's
      def refusedInside(key: String): Result =
        refused(if (key == "user.in.other.contests") None else Some("email"), key,
          emailWas = Option.when(key == "user.email.changed.meanwhile")(
            User.findById(userId).map(_.emailLo).getOrElse("")))
      val save: Option[String] => Result = {
        case Some(hash) if !own =>
          // another account's: last, after the rest of the form is saved (a failure there leaves
          // the password as it was), in its own transaction with the rule checked again under the
          // account's row lock; refused meanwhile, the password stays and the refusal is shown
          val setPassword = (othersSaved: Boolean) =>
            AccountTokenService.setPasswordByAdmin(user, userId, pageContestOf(user, editedUser), hash,
              oauthSettings.wikimediaConfigured, extras.comment, logContest = editedUser.contestId).left.map(passwordRefused(_,
                Option.when(othersSaved)(Messages("password.set.others.saved")))).map(_ => ())
          updateAccount(user, editedUser, formUser, None, newWiki.toOption.flatten, roleNeedsInvitation,
            emailChanged, extras, refusedInside, Some(setPassword))
        case newHash =>
          updateAccount(user, editedUser, formUser, newHash, newWiki.toOption.flatten, roleNeedsInvitation,
            emailChanged, extras, refusedInside, None)
      }
      checked.fold(identity, newHash => writeRoot().getOrElse(save(newHash)))
    }
  }

  /** Unlinks the account's Google and Wikimedia identities (or one provider's), which may have
    * been linked by a former holder ([[resetPassword]]). With `endSessions`, the account's
    * sessions end in the same transaction if any was unlinked: the new session version, if they
    * ended.
    */
  private def unlinkIdentities(userId: Long, provider: Option[String], endSessions: Boolean = false): Option[Int] = {
    val (unlinked, version) = scalikejdbc.DB.localTx { implicit s =>
      val unlinked = UserIdentityJdbc.deleteByUser(userId, provider)
      (unlinked, if (endSessions && unlinked > 0) User.endSessionsIn(userId) else None)
    }
    if (unlinked > 0)
      play.api.Logger("security.account").info(s"oauth_unlinked_by_admin accountId=$userId count=$unlinked" +
        (if (endSessions) s" sessionsEnded=${version.isDefined}" else ""))
    version
  }

  /** 503 with the form: no turn to hash the password came in time. One's own profile (`own`) is
    * shown as its other refusals are, with its cards ([[ownProfile]]); another account's edit
    * form in `contestId`.
    */
  private def passwordBusy(user: User, form: Form[User], own: Boolean, contestId: Option[Long],
      maySetPassword: Boolean)(implicit request: play.api.mvc.Request[_]): Result = {
    val busy = form.withGlobalError("password.busy")
    val page =
      if (own) ownProfile(ServiceUnavailable, user, userForm = Some(busy))
      else ServiceUnavailable(views.html.editUser(user, keepRootWas(busy), contestId = contestId,
        maySetPassword = maySetPassword))
    page.withHeaders(RETRY_AFTER -> "5")
  }

  /** Saves the checked form (root is written before, [[updateUser]]). The account's fields,
    * another account's email ([[UserService.changeEmailByAdmin]]) and a role set directly are
    * written in one transaction, the account's row locked first, with their account change log
    * entries; refused inside (the new address taken meanwhile: `refusedEmail`), nothing is
    * written. Mails (the former address's notice, an invitation if asked for) go after the
    * commit. `setOthersPassword`: another account's password, set after
    * everything else is saved ([[AccountTokenService.setPasswordByAdmin]]), given whether that
    * changed anything; its refusal (the account changed meanwhile) is the response.
    */
  private def updateAccount(user: User, editedUser: User, formUser: User, newHash: Option[String],
                            newWiki: Option[String], roleNeedsInvitation: Boolean, emailChanged: Boolean,
                            extras: UserController.EditExtras, refusedEmail: String => Result,
                            setOthersPassword: Option[Boolean => Either[Result, Unit]])(
      implicit request: play.api.mvc.Request[_]): Result = {
    val userId = editedUser.getId
    val own = formUser.id == user.id
    val contest = editedUser.contestId.flatMap(ContestJuryJdbc.findById)
    // the contest the change is logged in: the account's own, as the page shows it (None, root's
    // edit of an account in no contest: only root reads it), never the editor's session contest
    val inContest = editedUser.contestId
    val comment = extras.comment.filter(_ => !own)
    val notices = Seq.newBuilder[String]
    var newVersion: Option[Int] = None
    val mayEdit = canEditAccount(user, editedUser)
    // what the save writes that differs from the account's values (the fields left with a password
    // set: a role or email change is refused with it)
    def trimmed(wiki: Option[String]) = wiki.map(LoginText.clean).filter(_.nonEmpty)
    val wikiChanged = !own && trimmed(formUser.wikiAccount) != trimmed(editedUser.wikiAccount)
    val otherFieldsChanged = mayEdit && (formUser.fullname != editedUser.fullname ||
      formUser.lang != editedUser.lang || wikiChanged)

    // root is the Root field's, written before (updateUser), never a contest role's: a role
    // change leaves it as it is. Only admins change roles, and only in editedUser's contest; an
    // admin leaves a root user's role alone.
    val newRole = for {
      role <- formUser.roles.headOption if User.isContestRole(role) && user.hasAnyRole(User.ADMIN_ROLES)
      if user.hasRole(User.ROOT_ROLE) || !editedUser.hasRole(User.ROOT_ROLE)
      contestId <- editedUser.contestId
      if !editedUser.contestRole.contains(role)
    } yield (role, contestId)

    val written: Either[String, (Option[UserService.EmailChanged], Int)] =
      try scalikejdbc.DB.localTx { implicit session =>
        val locked = User.lockRow(userId)
        // the account may have joined another contest since the checks: decided again under its lock
        if (mayEdit && !locked.exists(UserController.mayEditAccountNow(user, _)))
          throw new UserController.Refused("user.in.other.contests")
        var emailChange = Option.empty[UserService.EmailChanged]
        var wikiUnlinked = 0
        if (mayEdit) {
          User.updateUser(userId, formUser.fullname, formUser.lang)
          if (formUser.fullname.trim != editedUser.fullname.trim)
            AccountChangeLog.record(user, userId, inContest, AccountChangeJdbc.Name, Some(editedUser.fullname),
              Some(formUser.fullname), comment = comment)
          if (wikiChanged) {
            // checked before the transaction (otherAccountsWithWikiAccount); a concurrent save of the
            // same name elsewhere hits the unique key here: a 500 with a reference, not mapped by
            // isDuplicateKey (users.email's key only), and nothing of this save is written
            User.setWikiAccount(userId, newWiki)
            // the name may now be someone else's: a Wikimedia account linked to the old one goes
            wikiUnlinked = UserIdentityJdbc.deleteByUser(userId, Some(OAuthProviders.Wikimedia))
            AccountChangeLog.record(user, userId, inContest, AccountChangeJdbc.WikiAccount, editedUser.wikiAccount,
              newWiki, comment = comment)
          }
          if (!own && emailChanged)
            userService.changeEmailByAdmin(user, userId, formUser.email, inContest, comment,
                expectedOld = Some(editedUser.emailLo)) match {
              case Left(key)    => throw new UserController.Refused(key)
              case Right(change) => emailChange = Some(change)
            }
        }
        for ((role, contestId) <- newRole if !roleNeedsInvitation) {
          UserContestJdbc.updateRole(userId, contestId, role)
          // a pending invitation's role must not come back when it is accepted
          AccountTokenJdbc.revokePendingRoles(userId, contestId)
          AccountChangeLog.record(user, userId, Some(contestId), AccountChangeJdbc.Role, editedUser.contestRole,
            Some(role), comment = comment)
        }
        Right(emailChange -> wikiUnlinked)
      } catch {
        case e: UserController.Refused => Left(e.key)
        // another account took the address meanwhile (the unique key)
        case e: Exception if UserController.isDuplicateKey(e) => Left("error.email.already.exist")
      }
    val (emailChange, wikiUnlinked) = written match {
      case Left(key)    => return refusedEmail(key)
      case Right(saved) => saved
    }
    if (wikiUnlinked > 0)
      play.api.Logger("security.account").info(s"oauth_unlinked_by_admin accountId=$userId count=$wikiUnlinked")

    if (mayEdit) {
      // a new password ends the account's other sessions; this one stays, with the new version
      newVersion = newHash.flatMap(User.setPasswordHash(userId, _))
      for (_ <- newVersion)
        play.api.Logger("security.account").info(s"password_changed accountId=$userId by=self sessionsEnded=true")
      if (own && formUser.email != editedUser.emailLo) {
        // confirmed by a link mailed to the new address; the same answer if it is taken (N7)
        userService.requestEmailChange(user, editedUser, formUser.email)
        notices += Messages("email.change.sent")
      }
    }

    // the account as it is now, for the mails and an invitation
    lazy val updated = User.findById(userId).map(u => editedUser.copy(email = u.email, emailVerifiedAt = u.emailVerifiedAt,
      password = u.password)).getOrElse(editedUser)
    for (change <- emailChange if change.changed) {
      play.api.Logger("security.account").info(
        s"email_changed_by_admin accountId=$userId byAccountId=${user.getId}" + inContest.fold("")(c => s" contestId=$c") +
          s" formerNotified=${change.notifyFormer} identitiesUnlinked=${change.unlinked} sessionsEnded=true")
      notices += Messages("email.changed.by.admin.done",
        if (updated.emailLo.isEmpty) Messages("email.none") else updated.emailLo)
      if (updated.hasUsablePassword) notices += Messages("email.changed.by.admin.password")
      // the former, verified address hears of it (the maintainer's decision): whatever the checkbox
      if (change.notifyFormer) {
        userService.notifyEmailChangedByAdmin(updated, change.oldEmail, user)
        notices += Messages("email.changed.by.admin.notified")
      }
    }

    // an invitation, mailed only if asked for ("Send an invitation email"): one that gives a role
    // the account can't take directly (O2, kept unsent otherwise, to send or share from the users
    // page), else one to sign in with, for a member with an access role who has no password
    // to use (or an unverified address)
    def invitable(u: User) = !own && u.emailLo.nonEmpty && !u.isRoot && u.contestRole.exists(User.ACCESS_ROLES.contains) &&
      !(u.emailVerified && u.hasUsablePassword) && !userService.invitationRefused(user, u)
    def outcome(sent: Boolean) = Messages(if (sent) "invitation.sent" else "invitation.not.sent")
    newRole.filter(_ => roleNeedsInvitation) match {
      case Some((role, _)) =>
        // O2: the role waits for the account's owner to accept an invitation
        if (updated.emailLo.isEmpty) notices += Messages("role.needs.invitation.email")
        else if (userService.invitationRefused(user, updated)) notices += Messages("invitation.not.possible")
        else {
          val sent = userService.invite(user, updated, contest, Some(role), mail = extras.sendInvitation, comment)
          notices += (if (extras.sendInvitation) outcome(sent) else Messages("invitation.created.not.sent"))
        }
      case None if extras.sendInvitation && !own =>
        notices += (if (invitable(updated)) outcome(userService.invite(user, updated, contest, None, comment = comment))
                    else Messages("invitation.not.possible"))
      case None if emailChange.exists(_.changed) && invitable(updated) && !updated.hasUsablePassword =>
        notices += Messages("invitation.not.mailed")
      case None =>
    }

    // another account's password, last: refused meanwhile, that refusal is the response
    val passwordRefusedNow = setOthersPassword.flatMap(set => set(otherFieldsChanged).left.toOption)
    if (passwordRefusedNow.nonEmpty) return passwordRefusedNow.get
    if (setOthersPassword.nonEmpty) notices += Messages("password.set.by.admin.done")

    val notice = notices.result().mkString(" ")
    // one's own profile with a notice (an email change's link sent) shows it there, still
    // with the page it was opened from; without one, back to that page
    val back = if (own) UserController.requestReturnTo(request) else None
    val result = if (own && notice.nonEmpty) {
      Redirect(routes.UserController.editUser(userId, None, back))
    } else if (back.nonEmpty) {
      Redirect(back.get)
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
    * joined it with the account they had, and how many were in it already; the invitations that
    * could not be mailed, and those not mailed because no mail was asked for ("Send an
    * invitation email" unticked).
    */
  private def addedMessage(added: Seq[UserService.Added])(implicit messages: Messages): String = {
    import UserService._
    val invited = added.count(_.isInstanceOf[Invited])
    val unverifiedWiki = added.count(_.isInstanceOf[WikiAccountUnverified])
    def mailOf(a: Added): Option[Mail] = a match {
      case NewAccount(_, mail) => Some(mail)
      case Invited(_, mail)    => Some(mail)
      case _                   => None
    }
    val notSent = added.count(a => mailOf(a).contains(Mail.Failed))
    val notAsked = added.count(a => mailOf(a).contains(Mail.NotAsked))
    val notPossible = added.count(a => mailOf(a).contains(Mail.NotPossible))
    (Seq(messages("users.added",
                  added.count(_.isInstanceOf[NewAccount]),
                  added.count(_.isInstanceOf[JoinedContest]),
                  added.count(_.isInstanceOf[AlreadyMember]))) ++
      (if (invited > 0) Seq(messages("users.invited", invited)) else Nil) ++
      (if (unverifiedWiki > 0) Seq(messages("users.not.added.wiki.unverified", unverifiedWiki)) else Nil) ++
      (if (notSent > 0) Seq(messages("users.invitations.not.sent", notSent)) else Nil) ++
      (if (notAsked > 0) Seq(messages("users.invitations.not.mailed", notAsked)) else Nil) ++
      (if (notPossible > 0) Seq(messages("users.invitations.not.possible", notPossible)) else Nil)).mkString(" ")
  }

  def showImportUsers(contestIdParam: Option[Long]): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request =>
        withContestId(user, contestIdParam) { contestId =>
          // after an import that didn't add every line (post/redirect/get): its result, once
          request.flash.get(UserController.ImportResultKey) match {
            case None => Ok(views.html.importUsers(user, importUsersForm, contestId))
            case Some(key) =>
              val summary = request.flash.get("users-added")
              ImportResults.take(key, user.getId, contestId).fold {
                // gone (a restart, or read already): how many lines it had, from the flash
                val lost = request.flash.get(UserController.ImportResultCount).flatMap(_.toIntOption).getOrElse(0)
                Ok(views.html.importUsers(user, importUsersForm, contestId, result = summary, lost = lost))
              } { r =>
                Ok(views.html.importUsers(user,
                  importUsersForm.fill((r.problems.map(_.entryText).mkString("\n"), r.role, r.sendInvitation)),
                  contestId, r.problems, summary, notListed = r.notListed))
              }
          }
        }
    }

  def importUsers(contestIdParam: Option[Long] = None): EssentialAction =
    // a database round trip or more per line: off Play's default dispatcher
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES, contestIdParam)) {
      user => implicit request => withContestId(user, contestIdParam) { contestId =>
        importUsersForm
          .bindFromRequest()
          .fold(
            formWithErrors => // binding failure, you retrieve the form containing errors,
              BadRequest(
                views.html.importUsers(user, formWithErrors, contestId)),
            { case (formUsers, role, sendInvitation) =>
              val contest = ContestJuryJdbc.findById(contestId)
              // each person with the line (1-based) the admin typed them on
              val parsed = UserController.importEntries(formUsers).map { case (line, pu) =>
                line -> pu.copy(lang = user.lang, contestId = Some(contestId))
              }

              // One at a time, each in its own transactions (UserService.addToContest): a line
              // that fails rolls back only its own step, never another line's, and the lines
              // before and after it are added as usual. A failure after the line's account was
              // created (in its invitation) leaves the account, which the users page then lists
              // (a retry finds it a member: its invitation is sent from there); no mail goes out
              // for a failed line (the invitation's mail is its last step, UserService.invite).
              val (added, problems) =
                parsed.foldLeft((Vector.empty[UserService.Added], Vector.empty[UserController.ImportProblem])) {
                  case ((done, problems), (line, pu)) =>
                    Try(userService.addToContest(user, pu.copy(roles = Set(role)), contest, sendInvitation)) match {
                      case Success(a) => (done :+ a, problems)
                      case Failure(_: UserService.InvalidWikiAccount) =>
                        (done, problems :+ UserController.ImportProblem(line, pu, None))
                      case Failure(e) =>
                        // as ErrorHandler's: the reference the admin is shown, on the log line;
                        // with the classes and stacks of the exception and its causes, not their
                        // messages, which can carry the line's email or SQL values (R220-1)
                        val reference = Secured.newReference()
                        play.api.Logger("security.account").error(s"user_import_line_failed ref=$reference " +
                          s"line=$line byAccountId=${user.getId} contestId=$contestId error=${e.getClass.getName}" +
                          services.LogSafe.causesField(e), services.LogSafe.withoutMessages(e))
                        (done, problems :+ UserController.ImportProblem(line, pu, Some(reference)))
                    }
                }
              val message = addedMessage(added)

              if (problems.isEmpty)
                Redirect(routes.UserController.users(Some(contestId)))
                  .flashing("users-added" -> message)
              else {
                // the import page (post/redirect/get: a reload imports nothing again), with what
                // was added, each line not added with why (and an error's reference), and the
                // form with only those people, to import again ([[ImportResults]])
                val key = ImportResults.put(user.getId, contestId, ImportResults.Result(problems, role, sendInvitation))
                Redirect(routes.UserController.showImportUsers(Some(contestId))).flashing(
                  "users-added" -> message, UserController.ImportResultKey -> key,
                  UserController.ImportResultCount -> problems.size.toString)
              }
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
      // as pasted from a spreadsheet or messenger: without surrounding spaces, tabs or
      // non-breaking spaces ([[LoginText.clean]]), before the email is checked
      "wikiAccount" -> optional(text).transform[Option[String]](_.map(LoginText.clean).filter(_.nonEmpty), identity),
      "email" -> text.transform[String](LoginText.clean, identity).verifying(Constraint[String]("constraint.email") { e =>
        if (e.trim.isEmpty) Valid else Constraints.emailAddress()(e)
      }),
      // a new password: not blank, at most 72 bytes (bcrypt's limit)
      "password" -> optional(text.verifying(PasswordHasher.newPassword)),
      // exactly one contest role, never root ([[User.mayAssignRoles]]); no trimming, case
      // folding or lists. Empty or absent: no role given
      "roles" -> optional(text.verifying("error.invalid", User.isContestRole(_))),
      "contest" -> optional(longNumber),
      "lang" -> optional(text),
      // light, dark or auto; any other value is dropped (no change, or none for a new user)
      "theme" -> optional(text).transform[Option[String]](_.flatMap(Theme.find).map(_.value), identity),
      // the account's root flag (users.is_root), not a contest role: the edit page's checkbox
      // for root, with a hidden "false" after it and the shown state, rootWas ([[rootFields]]);
      // absent: no change
      "root" -> optional(boolean)
    )(User.applyEdit)(User.unapplyEdit)
      .verifying("user.email.or.wiki.account",
                 u => u.email.trim.nonEmpty || u.wikiAccount.exists(_.trim.nonEmpty))
  )

  /** "Import users": the people, the role they join the contest with (jury by default; one of
    * [[User.IMPORT_ROLES]]), and whether invitations are mailed ("Send an invitation email",
    * unticked by default).
    */
  val importUsersForm: Form[(String, String, Boolean)] = Form(
    tuple(
      "userstoimport" -> nonEmptyText,
      "role" -> default(text, User.JURY_ROLE).verifying("error.invalid", User.IMPORT_ROLES.contains(_)),
      "sendInvitation" -> default(boolean, false)
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
        // B1: the account may be about to change hands. Once its link is mailed, its identities
        // are unlinked (its holder re-links after accepting) and the sessions they may have
        // signed in end with them. Without a mailed link nothing changes: an account without an
        // email (a wiki-only one, whose identity is its only way in) or a failed mail keeps its
        // sign-in methods, and root can try again.
        val (message, version) =
          if (found.emailLo.isEmpty) ("password.link.no.email", None)
          else if (userService.sendPasswordLink(user, found)) ("password.link.sent", unlinkIdentities(id, None, endSessions = true))
          else ("password.link.not.sent", None)
        val result = Redirect(routes.UserController.editUser(id)).flashing("password-reset" -> Messages(message))
        // root's own account: this session keeps the new version, as an own unlink's does
        version.filter(_ => id == user.getId).fold(result)(v => result.addingToSession(Secured.AuthVersion -> v.toString))
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
        AccountChangeLog.record(user, id, None, AccountChangeJdbc.SessionsEnded)
        Redirect(routes.UserController.editUser(id)).flashing("sessions-ended" -> Messages("sessions.ended"))
      }
    }

  /** Cancels the account's unused invitations into the contest (L4): an admin of that contest
    * (root too). Nothing to cancel is not found.
    */
  def cancelInvitation(id: Long, contestId: Long): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, Some(contestId))) { user => implicit request =>
      val cancelled = scalikejdbc.DB.localTx { implicit session =>
        // the account's row first, then its links: the order of a link's acceptance and of
        // UserService.invite (the log entry's foreign key would take the row after the links)
        val _ = User.lockRow(id)
        val n = AccountTokenJdbc.revokeInvitations(id, contestId)
        if (n > 0) AccountChangeLog.record(user, id, Some(contestId), AccountChangeJdbc.InvitationCancelled)
        n
      }
      if (cancelled == 0)
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
          // nor one that would hand an account with a password to an address an admin set, unless
          // this admin may hand it a set-password link ([[UserService.invitationRefused]])
          if (account.isRoot || account.emailVerified && account.hasUsablePassword || account.emailLo.isEmpty ||
              userService.invitationRefused(user, account)) {
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

  /** A page the own profile may send back to after a save: a local path
    * ([[Secured.safeLocalPath]]), not the profile's or another account action's (`/users/...`,
    * no loop), nor signing out or switching the contest, also written with dot segments. None: the save goes where it did
    * before (the users list for an admin, else the start page).
    */
  def profileReturnTo(value: Option[String]): Option[String] =
    Secured.safeLocalPath(value).filterNot { url =>
      // as the router would take it: dot segments resolved, percent-escapes decoded
      // (/./users/edit/1, /%75sers/...); one that doesn't parse is refused
      Try(new java.net.URI(url).normalize().getPath).toOption.forall { path =>
        path.startsWith("/users/") || path == "/logout" || path.startsWith("/logout/") ||
          path.startsWith("/switch-contest/")
      }
    }

  /** The request's returnTo for the profile: the query's (the profile page's GET) or the form's
    * (its save, also a refused one), if [[profileReturnTo]] takes it.
    */
  def requestReturnTo(request: play.api.mvc.Request[_]): Option[String] = {
    val formField = request.body match {
      case body: AnyContent => body.asFormUrlEncoded.flatMap(_.get("returnTo")).flatMap(_.headOption)
      case _                => None
    }
    profileReturnTo(request.getQueryString("returnTo").orElse(formField))
  }

  /** The navbar's profile link's returnTo: the page shown (a GET's, `backHere`), or on the
    * profile itself the one it was opened from.
    */
  def profileLinkReturnTo(request: play.api.mvc.RequestHeader, backHere: Boolean): Option[String] =
    if (!backHere || request.method != "GET") None
    else if (request.path.startsWith("/users/edit/")) profileReturnTo(request.getQueryString("returnTo"))
    else profileReturnTo(Some(request.uri))

  /** The flash keys of an import's result ([[ImportResults]]): its key, and how many lines it
    * lists (said if the result is gone).
    */
  val ImportResultKey = "import-result"
  val ImportResultCount = "import-result-count"

  /** "Import users"' people, each with the line (1-based) it is on: [[User.parseList]] line by
    * line (it splits at line ends as well as commas and `|`, so this finds the same people).
    */
  def importEntries(text: String): Seq[(Int, User)] =
    text.split("\n", -1).toSeq.zipWithIndex.flatMap { case (line, i) => User.parseList(line).map(i + 1 -> _) }

  /** An imported person not added: on `line`, as entered; with the reference of the server log's
    * line for an error (None: an invalid wiki account name, the admin's to correct).
    */
  final case class ImportProblem(line: Int, person: User, reference: Option[String]) {

    /** The email or the wiki account, as the import read it. */
    def entry: String = Option(person.email).filter(_.nonEmpty).orElse(person.wikiAccount).getOrElse("")

    /** The person as an import line again: `Name <email>` (quoted as needed), the email or the
      * wiki account.
      */
    def entryText: String =
      if (Option(person.email).exists(_.nonEmpty) && person.fullname.nonEmpty)
        Try(new javax.mail.internet.InternetAddress(person.email, person.fullname, "UTF-8").toUnicodeString)
          .getOrElse(person.email)
      else entry
  }

  /** The edit form's fields that are not the account's: "Send an invitation email" (unticked by
    * default) and an optional comment for the account change log.
    */
  final case class EditExtras(sendInvitation: Boolean, comment: Option[String])

  val editExtrasForm: Form[EditExtras] = Form(
    mapping(
      "sendInvitation" -> default(boolean, false),
      "comment" -> optional(text(maxLength = AccountChangeJdbc.CommentMaxLength))
    )(EditExtras.apply)(EditExtras.unapply)
  )

  /** A save refused inside its transaction (a message key): nothing of it is written. */
  final class Refused(val key: String) extends RuntimeException(key, null, false, false)

  /** Whether the exception is a violation of users.email's unique key (`email`, V2: another
    * account took the address): error 1062 naming that key, not any integrity violation.
    */
  def isDuplicateKey(e: Throwable): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(5).exists {
      case s: java.sql.SQLException =>
        val text = Option(s.getMessage).getOrElse("")
        s.getErrorCode == 1062 && (text.contains("for key 'email'") || text.contains("for key 'users.email'"))
      case _ => false
    }

  /** [[UserController.canEditAccount]] again, read with the transaction's session under the
    * account's row lock (`account` as locked): root, oneself, or an admin of every contest the
    * account is in or worked in, never a root account.
    */
  def mayEditAccountNow(actor: User, account: User)(implicit session: scalikejdbc.DBSession): Boolean =
    actor.hasRole(User.ROOT_ROLE) || actor.id == account.id || !account.isRoot && {
      val adminOf = UserContestJdbc.findByUser(actor.getId).filter(_.role == User.ADMIN_ROLE).map(_.contestId).toSet
      (UserContestJdbc.findByUser(account.getId).map(_.contestId) ++ User.contestsWorkedIn(account.getId))
        .forall(adminOf.contains)
    }

  /** The users page lists the live invitations into the contest and those of the last 30 days. */
  val InvitationsShownDays = 30

  /** An account's edit page shows its latest 5 links that set the password. */
  val LinksShown = 5
}

case class Greeting(text: Option[String], use: Boolean)
