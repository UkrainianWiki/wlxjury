package controllers

import db.scalikejdbc.{User, UserIdentityJdbc}
import modules.BlockingExecutionContext
import play.api.Logger
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.oauth._

import javax.inject.Inject
import scala.concurrent.Future
import scala.util.control.NonFatal

/** Sign-in with a Google or Wikimedia account (docs/oauth.md), and linking one to the signed-in
  * user's own account. Ported from the oauth-login branch onto S2/S3
  * (docs/plans/2026-11-03-oauth-login-port.md).
  *
  * Between leaving for the provider and its callback, the sign-in's state, PKCE verifier and
  * nonce wait in the (signed) session; the callback always drops them. A sign-in only signs in to
  * an existing account by S2's rules ([[IdentityLinker]]): never an uninvited person, never an
  * account someone else may hold. It makes the same session as the password's
  * ([[LoginResults.signedIn]]: account id, auth version, S3's times), so that "sign out
  * everywhere", a password or email change and root's "end sessions" end it too.
  *
  * The starts are GET links (a navigation to the provider: the CSP's `form-action 'self'` stays);
  * they change only the session's flow keys. The callback is a GET from the provider, accepted
  * only with the state of a flow this browser started. Provider calls block: they run on the
  * blocking dispatcher, at most [[OAuthSettings.maxConcurrent]] at once.
  */
class OAuthController @Inject() (
    oauthSettings: OAuthSettings,
    providers: OAuthProviders,
    blocking: BlockingExecutionContext,
    cc: ControllerComponents,
    val themeCookies: ThemeCookies
) extends Secured(cc)
    with I18nSupport
    with LoginResults {

  import OAuthController._

  /** `security.account`: ids and our own codes only (named so that the guard specs scan its calls). */
  private val log = Logger("security.account")

  /** Leaves for the provider's sign-in page; back to `returnTo` (a local path only) once signed in. */
  def login(provider: String, returnTo: Option[String] = None): Action[AnyContent] = Action.async { implicit request =>
    Future(start(provider, Map(Mode -> LoginMode) ++ Secured.safeLocalPath(returnTo).map(ReturnTo -> _)))(blocking)
  }

  /** Leaves for the provider, to link the account signed in with there to the user's own: only
    * from the own profile's link (its nonce `n`, and no cross-site `Sec-Fetch-Site`: M3), and for
    * an account nobody else may hold (B1: [[User.mayTakeAccessRole]]).
    */
  def link(provider: String, n: Option[String] = None): EssentialAction = withAuthOn(blocking)(_ => true) {
    user => implicit request =>
      val flowData = Map(Mode -> LinkMode, LinkUser -> user.getId.toString)
      if (!fromOwnProfile(n)) failure(provider, flowData, "oauth.failed", "link_not_from_profile", None)
      else
        linkRefusal(user).fold(start(provider, flowData)) { case (key, reason) =>
          failure(provider, flowData, key, reason, None)
        }
  }

  /** Whether a link start came from the own profile page: its nonce, and a browser that, if it
    * says, says the request is not another site's (M3).
    */
  private def fromOwnProfile(n: Option[String])(implicit request: RequestHeader): Boolean =
    n.exists(v => request.session.get(LinkNonceKey).contains(v)) &&
      request.headers.get("Sec-Fetch-Site").forall(site => site == "same-origin" || site == "none")

  /** Why the session's user may not link an identity now, if they may not (B1). */
  private def linkRefusal(user: User): Option[(String, String)] =
    if (!user.mayTakeAccessRole) Some("oauth.link.unverified" -> "link_account_unverified")
    else None

  /** Where the provider sends the user back to. */
  def callback(provider: String): Action[AnyContent] = Action.async { implicit request =>
    Future(callbackPage(finish(provider)))(blocking)
  }

  /** Unlinks one of the user's own identities (a POST with the CSRF token; form field `subject`):
    * every session of the account ends but this one, which keeps the new version (B5: a session
    * signed in with the identity, perhaps in someone else's browser, ends with it).
    */
  def unlink(provider: String): EssentialAction = withAuthOn(blocking)(_ => true) { user => implicit request =>
    val subject = request.body.asFormUrlEncoded.flatMap(_.get("subject")).flatMap(_.headOption)
    // the unlink and the sessions' end commit together: an unlink without it couldn't be
    // repaired by a retry (the identity is no longer the user's, so a retry ends nothing)
    val (unlinked, version) = scalikejdbc.DB.localTx { implicit s =>
      val unlinked = subject.exists(UserIdentityJdbc.delete(user.getId, provider, _))
      (unlinked, if (unlinked) User.endSessionsIn(user.getId) else None)
    }
    if (unlinked)
      log.info(s"oauth_unlinked provider=${Secured.logValue(provider)} accountId=${user.getId} sessionsEnded=true")
    val result = Redirect(routes.UserController.editUser(user.getId))
      .flashing(if (unlinked) FlashSuccess -> "oauth.unlinked" else FlashError -> "oauth.failed")
    version.fold(result)(v => result.addingToSession(Secured.AuthVersion -> v.toString))
  }

  /** The confirmation page for setting the own wiki account to a linked Wikimedia account's
    * username (B4: asked, never set by the link itself): only if the account has no wiki account
    * yet. The name comes from the identity (`subject`, the user's own), never from a form.
    */
  def wikiAccountPage(subject: String): EssentialAction = withAuth(_ => true) { user => implicit request =>
    ownWikimediaName(user, subject) match {
      case Some(name) if user.wikiAccount.forall(_.trim.isEmpty) =>
        noStore(Ok(views.html.oauthWikiAccount(user, subject, name)))
      case _ =>
        Redirect(routes.UserController.editUser(user.getId)).flashing(FlashError -> "oauth.wiki.ask.organizer")
    }
  }

  /** Sets the own wiki account to the linked Wikimedia account's username, verified (a POST with
    * the CSRF token, from [[wikiAccountPage]]): re-checked in one transaction, the account's row
    * locked ([[User.setVerifiedWikiAccount]]: empty or the same name, and nobody else's). A changed
    * name ends the account's other sessions; this one keeps the new version.
    */
  def setWikiAccount(): EssentialAction = withAuthOn(blocking)(_ => true) { user => implicit request =>
    val subject = request.body.asFormUrlEncoded.flatMap(_.get("subject")).flatMap(_.headOption).getOrElse("")
    val back    = Redirect(routes.UserController.editUser(user.getId))
    ownWikimediaName(user, subject).fold(back.flashing(FlashError -> "oauth.failed")) { name =>
      User.setVerifiedWikiAccount(user.getId, name) match {
        case Left(refusal) => back.flashing(FlashError -> refusal.messageKey)
        case Right(version) =>
          log.info(s"wiki_account_set accountId=${user.getId} by=self via=oauth")
          back.flashing(FlashSuccess -> "oauth.linked.wiki.set").addingToSession(Secured.AuthVersion -> version.toString)
      }
    }
  }

  /** The username of the user's own linked Wikimedia identity `subject`, normalised. */
  private def ownWikimediaName(user: User, subject: String): Option[String] =
    UserIdentityJdbc.find(OAuthProviders.Wikimedia, subject).filter(_.userId == user.getId)
      .flatMap(_.username).flatMap(User.normalizeWikiAccount(_).toOption.flatten)

  /** The callback's answer: never cached, and its URL (with the code and state) never sent on. */
  private def callbackPage(result: Result): Result =
    result.withHeaders(CACHE_CONTROL -> "no-store", "Referrer-Policy" -> "no-referrer")

  private def configured(providerId: String, request: RequestHeader): Option[(OAuthProvider, OAuthClient)] =
    for {
      provider <- providers.get(providerId)
      client   <- oauthSettings.client(providerId, request.host)
    } yield (provider, client)

  private def start(providerId: String, flowData: Map[String, String])(implicit request: RequestHeader): Result =
    configured(providerId, request) match {
      case None =>
        NotFound(views.html.errorPage(userFromRequest(request), "error.title", "error.not.found"))
      case Some((provider, client)) =>
        val flow = OAuthFlow.random()
        try {
          val uri = provider.authorizationUri(client, oauthSettings.redirectUri(providerId, request.host), flow)
          val session = request.session -- FlowKeys ++ flowData ++ Map(
            Provider  -> providerId,
            StateKey  -> flow.state.getValue,
            Verifier  -> flow.verifier.getValue,
            NonceKey  -> flow.nonce.getValue,
            StartedAt -> System.currentTimeMillis.toString
          )
          noStore(Redirect(uri.toString).withSession(session))
        } catch {
          case e: OAuthException =>
            failure(providerId, flowData, "oauth.failed", "start", Some(e))
        }
    }

  private def finish(providerId: String)(implicit request: RequestHeader): Result = {
    val session  = request.session
    val flowData = FlowKeys.flatMap(k => session.get(k).map(k -> _)).toMap

    def fail(messageKey: String, reason: String, cause: Option[Throwable] = None): Result =
      failure(providerId, flowData, messageKey, reason, cause)

    val started = for {
      p        <- session.get(Provider) if p == providerId
      state    <- session.get(StateKey)
      verifier <- session.get(Verifier)
      nonce    <- session.get(NonceKey)
      at       <- session.get(StartedAt).flatMap(_.toLongOption)
    } yield (OAuthFlow(state, verifier, nonce), at)

    started match {
      case None =>
        fail("oauth.failed", "no_flow")
      case Some((flow, _)) if !request.getQueryString("state").contains(flow.state.getValue) =>
        fail("oauth.failed", "state_mismatch")
      case Some((_, at)) if System.currentTimeMillis - at > oauthSettings.flowTimeout.toMillis =>
        fail("oauth.failed", "flow_timeout")
      case Some(_) if request.getQueryString("error").isDefined =>
        // the provider's error code is its text: only whether the user cancelled is used
        if (request.getQueryString("error").contains("access_denied")) fail("oauth.cancelled", "cancelled")
        else fail("oauth.failed", "provider_error")
      case Some((flow, _)) =>
        (configured(providerId, request), request.getQueryString("code")) match {
          case (None, _) => fail("oauth.failed", "not_offered")
          case (_, None) => fail("oauth.failed", "no_code")
          case (Some((provider, client)), Some(code)) =>
            val redirectUri = oauthSettings.redirectUri(providerId, request.host)
            try {
              providers.withTurn(provider.exchange(client, redirectUri, code, flow)) match {
                case None => fail("oauth.busy", "busy")
                case Some(profile) =>
                  flowData.get(Mode) match {
                    case Some(LinkMode) => linked(profile, flowData, fail(_, _))
                    case _              => signedInWith(profile, flowData, fail(_, _))
                  }
              }
            } catch {
              case e: OAuthException => fail("oauth.failed", "exchange", Some(e))
              case NonFatal(e) =>
                // a database error, say: the user still gets the message, the code leaves the address bar
                val reference = Secured.newReference()
                // the class and the reference only (L3): a driver's text could hold bound values
                log.error(s"oauth_callback_failed ref=$reference provider=${Secured.logValue(providerId)} error=${e.getClass.getName}")
                fail("oauth.failed", s"error ref=$reference")
            }
        }
    }
  }

  private def signedInWith(profile: ExternalProfile, flowData: Map[String, String], fail: (String, String) => Result)(
      implicit request: RequestHeader
  ): Result =
    IdentityLinker.resolve(profile).flatMap(user => loginName(user).map(user -> _).toRight(LinkError.Ambiguous)) match {
      case Left(LinkError.Choose(accounts)) =>
        // duplicates: the user chooses (OAuthChooserController); the flow keys leave the session
        val (flow, keys) = OAuthChooserController.start(profile, accounts, flowData.get(ReturnTo))
        log.info(s"oauth_choose provider=${profile.provider} accounts=${accounts.size}")
        Redirect(routes.OAuthChooserController.page(flow))
          .withSession((request.session -- FlowKeys -- OAuthChooserController.Keys) ++ keys)
      case Right((user, name)) =>
        // a root's sign-in by a provider stands out in the log (B2)
        if (user.isRoot) log.warn(s"oauth_signin provider=${profile.provider} accountId=${user.getId} root=true")
        else log.info(s"oauth_signin provider=${profile.provider} accountId=${user.getId}")
        // no checkbox on a link: kept signed in, the sign-in form's default
        signedIn(user, name, flowData.get(ReturnTo), keep = true)
      case Left(error) =>
        fail(error.messageKey, error.reason)
    }

  /** Links the identity to the signed-in user's own account. The user who asked for the link
    * must still be the session's (its version included: a sign-out everywhere meanwhile ends
    * it), and still may link ([[linkRefusal]]). A root's link is logged at WARN (B2).
    * A Wikimedia identity verifies the same wiki account, or offers to set an empty one
    * ([[wikiAccountPage]], B4); another one is the organizer's to change.
    */
  private def linked(profile: ExternalProfile, flowData: Map[String, String], fail: (String, String) => Result)(implicit
      request: RequestHeader
  ): Result =
    Secured.sessionUserOf(request).filter(u => flowData.get(LinkUser).contains(u.getId.toString)) match {
      case None => fail("oauth.failed", "link_session_changed")
      case Some(user) =>
        linkRefusal(user) match {
          case Some((key, reason)) => fail(key, reason)
          case None =>
            // from the own profile: an identity unlinked before may be linked again here (F2)
            IdentityLinker.link(user.getId, profile, relink = true) match {
              case Left(error) => fail(error.messageKey, error.reason)
              case Right(_) =>
                if (user.isRoot) log.warn(s"oauth_linked provider=${profile.provider} accountId=${user.getId} root=true")
                else log.info(s"oauth_linked provider=${profile.provider} accountId=${user.getId}")
                val session = request.session -- FlowKeys
                val back    = Redirect(routes.UserController.editUser(user.getId)).withSession(session)
                IdentityLinker.wikiName(profile) match {
                  case Some(_) if IdentityLinker.sameWikiAccount(user, profile) =>
                    IdentityLinker.verifyWikiAccount(user, profile)
                    back.flashing(FlashSuccess -> "oauth.linked")
                  case Some(_) if user.wikiAccount.forall(_.trim.isEmpty) =>
                    Redirect(routes.OAuthController.wikiAccountPage(profile.subject))
                      .withSession(session)
                      .flashing(FlashSuccess -> "oauth.linked")
                  case Some(_) => back.flashing(FlashSuccess -> "oauth.linked.wiki.other")
                  case None    => back.flashing(FlashSuccess -> "oauth.linked")
                }
            }
        }
    }

  /** A redirect back to the sign-in page, or to the own profile for a link, with the message, so
    * that the callback's code does not stay in the address bar; logs why (ids and our own codes
    * only; a provider's error text never).
    */
  private def failure(
      providerId: String,
      flowData: Map[String, String],
      messageKey: String,
      reason: String,
      cause: Option[Throwable]
  )(implicit request: RequestHeader): Result = {
    val mode   = flowData.get(Mode).filter(m => m == LoginMode || m == LinkMode).getOrElse("none")
    // an OAuthException's text is ours (a step, and the provider's error code at most); its
    // cause, a library's, only by class
    log.warn(s"oauth_refused provider=${Secured.logValue(providerId)} mode=$mode reason=${Secured.logValue(reason)}" +
      cause.fold("")(e => s" detail=${Secured.logValue(e.getMessage)} cause=${Option(e.getCause).fold("none")(_.getClass.getName)}"))
    val session = request.session -- FlowKeys
    (flowData.get(Mode), flowData.get(LinkUser).flatMap(_.toLongOption)) match {
      case (Some(LinkMode), Some(userId)) =>
        Redirect(routes.UserController.editUser(userId)).withSession(session).flashing(FlashError -> messageKey)
      case _ =>
        Redirect(routes.LoginController.login(Secured.safeLocalPath(flowData.get(ReturnTo))))
          .withSession(session)
          .flashing(FlashError -> messageKey)
    }
  }
}

object OAuthController {
  /** The session's login name: one that names this account alone ([[User.byUserName]], as
    * [[Secured.sessionUser]] reads it), its email first, else its wiki account; None if neither
    * does (a wiki account two accounts have).
    */
  def loginName(user: User): Option[String] =
    (Option(user.emailLo).filter(_.nonEmpty).toSeq ++ user.wikiAccount.map(_.trim).filter(_.nonEmpty))
      .find(name => User.byUserName(Secured.normalizeUserName(name)).exists(_.id == user.id))

  val Provider  = "oauth.provider"
  val StateKey  = "oauth.state"
  val Verifier  = "oauth.verifier"
  val NonceKey  = "oauth.nonce"
  val StartedAt = "oauth.at"
  val Mode      = "oauth.mode"
  val LinkUser  = "oauth.uid"
  val ReturnTo  = "oauth.returnTo"

  val FlowKeys: Seq[String] = Seq(Provider, StateKey, Verifier, NonceKey, StartedAt, Mode, LinkUser, ReturnTo)

  /** The own profile's nonce for its Link buttons (M3), set by each rendering of the profile. */
  val LinkNonceKey = "oauth.link.nonce"

  val LoginMode = "login"
  val LinkMode  = "link"

  /** The flash keys the sign-in and profile pages show the outcome of a sign-in or link with. */
  val FlashSuccess = "oauth-success"
  val FlashError   = "oauth-error"

  /** The messages those flashes may hold; the pages show no other. */
  val MessageKeys: Set[String] = Set(
    "oauth.failed", "oauth.cancelled", "oauth.busy", "oauth.no.account", "oauth.ambiguous", "oauth.unverified",
    "oauth.linked.other", "oauth.linked", "oauth.linked.wiki.set", "oauth.linked.wiki.taken", "oauth.unlinked",
    "oauth.link.unverified", "oauth.google.email.not.proof",
    "oauth.wiki.other.identity", "oauth.wiki.ask.organizer", "oauth.linked.wiki.other",
    "oauth.choose.expired", "oauth.choose.password.wrong", "oauth.choose.mail.sent", "oauth.choose.mail.not.sent",
    "oauth.choose.proof.needed", "oauth.choose.link.invalid", "oauth.unlinked.before", "oauth.choose.ask.organizer",
    "oauth.choose.too.many"
  )
}
