package controllers

import db.scalikejdbc.{User, UserIdentityJdbc}
import modules.BlockingExecutionContext
import play.api.Logging
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.oauth._

import javax.inject.Inject
import scala.concurrent.Future

/** Login with a Google or Wikimedia account (docs/oauth.md), and linking one to the
  * logged-in user's account.
  *
  * Between leaving for the provider and its callback, the sign-in's state, PKCE verifier
  * and nonce wait in the (signed) session; the callback always drops them. A sign-in only
  * logs in to an existing account ([[IdentityLinker]]). Provider calls block, so they run
  * on the blocking dispatcher.
  */
class OAuthController @Inject() (
    val oauthSettings: OAuthSettings,
    providers: OAuthProviders,
    blocking: BlockingExecutionContext,
    cc: ControllerComponents
) extends Secured(cc)
    with I18nSupport
    with LoginResults
    with Logging {

  import OAuthController._

  /** Leaves for the provider's sign-in page. */
  def login(provider: String): Action[AnyContent] = Action.async { implicit request =>
    Future(start(provider, Map(Mode -> LoginMode)))(blocking)
  }

  /** Leaves for the provider, to link the account signed in with there to the user's. */
  def link(provider: String): EssentialAction = withAuthOn(blocking)(anyUser) { user => implicit request =>
    start(provider, Map(Mode -> LinkMode, LinkUser -> user.getId.toString))
  }

  /** Where the provider sends the user back to. */
  def callback(provider: String): Action[AnyContent] = Action.async { implicit request =>
    Future(finish(provider))(blocking)
  }

  /** Unlinks one of the user's own identities (form field `subject`). */
  def unlink(provider: String): EssentialAction = withAuth(anyUser) { user => implicit request =>
    val subject = request.body.asFormUrlEncoded.flatMap(_.get("subject")).flatMap(_.headOption)
    val unlinked = subject.exists(UserIdentityJdbc.delete(user.getId, provider, _))
    if (unlinked) logger.info(s"OAuth $provider identity unlinked by user ${user.getId}")
    Redirect(routes.UserController.editUser(user.getId))
      .flashing(if (unlinked) FlashSuccess -> "oauth.unlinked" else FlashError -> "oauth.failed")
  }

  /** Any logged-in user: linking and unlinking change only their own account, as their own
    * edit page does (open to them by their USER_ID_ role, whatever their contest roles).
    */
  private def anyUser(user: User): Boolean = true

  private def configured(providerId: String, request: RequestHeader): Option[(OAuthProvider, OAuthClient)] =
    for {
      provider <- providers.get(providerId)
      client   <- oauthSettings.client(providerId, request.host)
    } yield (provider, client)

  private def start(providerId: String, flowData: Map[String, String])(implicit request: RequestHeader): Result =
    configured(providerId, request) match {
      case None => NotFound
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
            failure(providerId, flowData.get(Mode), flowData.get(LinkUser), "oauth.failed", e.getMessage)
        }
    }

  private def finish(providerId: String)(implicit request: RequestHeader): Result = {
    val session  = request.session
    val mode     = session.get(Mode)
    val linkUser = session.get(LinkUser)

    def fail(messageKey: String, detail: String): Result =
      failure(providerId, mode, linkUser, messageKey, detail)

    val started = for {
      p        <- session.get(Provider) if p == providerId
      state    <- session.get(StateKey)
      verifier <- session.get(Verifier)
      nonce    <- session.get(NonceKey)
      at       <- session.get(StartedAt).flatMap(_.toLongOption)
    } yield (OAuthFlow(state, verifier, nonce), at)

    started match {
      case None =>
        fail("oauth.failed", "no sign-in in progress")
      case Some((flow, _)) if !request.getQueryString("state").contains(flow.state.getValue) =>
        fail("oauth.failed", "state mismatch")
      case Some((_, at)) if System.currentTimeMillis - at > oauthSettings.flowTimeout.toMillis =>
        fail("oauth.failed", "sign-in took too long")
      case Some(_) if request.getQueryString("error").isDefined =>
        val error = request.getQueryString("error").get
        fail(if (error == "access_denied") "oauth.cancelled" else "oauth.failed", s"provider error $error")
      case Some((flow, _)) =>
        (configured(providerId, request), request.getQueryString("code")) match {
          case (None, _) => fail("oauth.failed", "provider not offered on this host")
          case (_, None) => fail("oauth.failed", "no code")
          case (Some((provider, client)), Some(code)) =>
            val redirectUri = oauthSettings.redirectUri(providerId, request.host)
            try {
              val profile = provider.exchange(client, redirectUri, code, flow)
              mode match {
                case Some(LinkMode) => linked(profile, linkUser, fail)
                case _              => loggedInWith(profile, fail)
              }
            } catch {
              case e: OAuthException => fail("oauth.failed", e.getMessage)
            }
        }
    }
  }

  private def loggedInWith(profile: ExternalProfile, fail: (String, String) => Result): Result =
    IdentityLinker.resolve(profile) match {
      case Right(user) =>
        logger.info(s"OAuth ${profile.provider} login of user ${user.getId}")
        loggedIn(user, loginName(user))
      case Left(error) =>
        fail(error.messageKey, error.toString)
    }

  private def linked(profile: ExternalProfile, linkUser: Option[String], fail: (String, String) => Result)(implicit
      request: RequestHeader
  ): Result = {
    // the user who asked for the link must still be the one logged in
    val userId = Secured.sessionUser(request.session).map(_.getId).filter(id => linkUser.contains(id.toString))
    userId.fold(fail("oauth.failed", "link for another session user")) { id =>
      IdentityLinker.link(id, profile) match {
        case Right(_) =>
          logger.info(s"OAuth ${profile.provider} identity linked by user $id")
          Redirect(routes.UserController.editUser(id))
            .withSession(request.session -- FlowKeys)
            .flashing(FlashSuccess -> "oauth.linked")
        case Left(error) =>
          fail(error.messageKey, error.toString)
      }
    }
  }

  /** A redirect back to the login page, or to the edit page for a link, with the message,
    * so that the callback's code does not stay in the address bar; logs why.
    */
  private def failure(
      providerId: String,
      mode: Option[String],
      linkUser: Option[String],
      messageKey: String,
      detail: String
  )(implicit request: RequestHeader): Result = {
    logger.warn(s"OAuth $providerId ${mode.getOrElse("-")} failed: $detail")
    val session = request.session -- FlowKeys
    (mode, linkUser.flatMap(_.toLongOption)) match {
      case (Some(LinkMode), Some(userId)) =>
        Redirect(routes.UserController.editUser(userId)).withSession(session).flashing(FlashError -> messageKey)
      case _ =>
        Redirect(routes.LoginController.login()).withSession(session).flashing(FlashError -> messageKey)
    }
  }

  /** The session's login name; [[Secured.UserId]] is what identifies the user. */
  private def loginName(user: User): String =
    if (user.email.trim.nonEmpty) user.emailLo else user.wikiAccount.getOrElse("")
}

object OAuthController {
  val Provider  = "oauth.provider"
  val StateKey  = "oauth.state"
  val Verifier  = "oauth.verifier"
  val NonceKey  = "oauth.nonce"
  val StartedAt = "oauth.at"
  val Mode      = "oauth.mode"
  val LinkUser  = "oauth.uid"

  val FlowKeys: Seq[String] = Seq(Provider, StateKey, Verifier, NonceKey, StartedAt, Mode, LinkUser)

  val LoginMode = "login"
  val LinkMode  = "link"

  /** The flash keys the login and edit pages show the outcome of a sign-in or link with. */
  val FlashSuccess = "oauth-success"
  val FlashError   = "oauth-error"
}
