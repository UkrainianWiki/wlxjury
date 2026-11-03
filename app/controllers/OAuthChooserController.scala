package controllers

import db.scalikejdbc.{ContestJuryJdbc, User, UserContestJdbc}
import modules.BlockingExecutionContext
import play.api.Logger
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.oauth.{ExternalProfile, IdentityLinker, OAuthProviders}
import services.{AccountTokenService, LinkHost, PasswordHasher, UserService}

import javax.inject.Inject
import scala.concurrent.Future
import scala.util.control.NonFatal

/** The account chooser (the user's decision, 2026-11-03): a Wikimedia sign-in whose username
  * several accounts have as their wiki account (duplicates) lists them, and links the one the user
  * chooses. The choice is held in the (signed) session under an opaque per-flow token, each
  * account under an opaque option token: the page and its forms carry those, never account ids.
  * Root accounts are never listed (the linker leaves them out), deleted ones never found.
  *
  *  - An account the linking rules let in ([[IdentityLinker.choosableDirectly]]: a never-linked
  *    placeholder) is linked on its POST.
  *  - Any other needs proof of control first: its password (a failure logged at WARN on
  *    `security.auth`, `oauth_choose_proof_failed`, as a sign-in failure would be, for fail2ban),
  *    or a short single-use link mailed to its verified address (`oauth_choose`), opened in this
  *    browser (the choice waits in its session).
  *
  * Then the identity is linked, the wiki account marked verified, and the user signed in; the next
  * sign-in with the identity goes straight to the account. Refusals are message keys.
  */
class OAuthChooserController @Inject() (
    blocking: BlockingExecutionContext,
    cc: ControllerComponents,
    val themeCookies: ThemeCookies,
    userService: UserService
) extends Secured(cc)
    with I18nSupport
    with LoginResults {

  import OAuthChooserController._

  private val log     = Logger("security.account")
  private val authLog = Logger("security.auth")

  /** The list: the session's choice, if `flow` is its token and it has not expired. */
  def page(flow: String): Action[AnyContent] = Action.async { implicit request =>
    Future {
      choice(request.session, flow).fold(refused("oauth.choose.expired")) { c =>
        noStore(Ok(views.html.oauthChoose(flow, c.options.flatMap { case (option, id) =>
          User.findById(id).filter(u => u.deletedAt.isEmpty && !u.isRoot).map(u => entry(option, u))
        })))
      }
    }(blocking)
  }

  /** A choice (a POST with the CSRF token): `flow`, `option`, and `proof` (`direct`, `password`
    * with `password`, or `mail`).
    */
  def choose(): Action[AnyContent] = Action.async { implicit request =>
    Future {
      val form = request.body.asFormUrlEncoded.getOrElse(Map.empty).view.mapValues(_.headOption.getOrElse("")).toMap
      val flow = form.getOrElse("flow", "")
      val back = Redirect(routes.OAuthChooserController.page(flow))
      (for {
        c    <- choice(request.session, flow)
        id   <- c.options.collectFirst { case (o, id) if o == form.getOrElse("option", "") => id }
        user <- User.findById(id).filter(u => u.deletedAt.isEmpty && !u.isRoot)
      } yield (c, user)).fold(refused("oauth.choose.expired")) { case (c, user) =>
        form.getOrElse("proof", "") match {
          // F1: an account without a login name of its own can't be signed in to: nothing is linked
          case _ if OAuthController.loginName(user).isEmpty => back.flashing(OAuthController.FlashError -> "oauth.choose.ask.organizer")
          case "direct" if IdentityLinker.choosableDirectly(user) => linkAndSignIn(c, user)
          case "password" if passwordProof(user) =>
            if (Failures.count(flow) >= MaxPasswordFailures) back.flashing(OAuthController.FlashError -> "oauth.choose.too.many")
            else
              try {
                if (PasswordHasher.default.check(User.usableHash(user), form.getOrElse("password", "")) != PasswordHasher.Rejected)
                  linkAndSignIn(c, user)
                else {
                  Failures.add(flow)
                  authLog.warn(s"oauth_choose_proof_failed accountId=${user.getId} proof=password " +
                    s"ip=${Secured.logValue(request.remoteAddress)}")
                  back.flashing(OAuthController.FlashError -> "oauth.choose.password.wrong")
                }
              } catch {
                // no turn to check the password: busy, not wrong (F4)
                case _: PasswordHasher.Busy => back.flashing(OAuthController.FlashError -> "oauth.busy")
              }
          case "mail" if user.emailVerified =>
            implicit val linkHost: LinkHost = LinkHost.of(request)
            val name = c.profile.username.getOrElse("")
            val sent = userService.sendChooseLink(user, name, token => routes.OAuthChooserController.confirmPage(token).url)
            log.info(s"oauth_choose_link_sent accountId=${user.getId} sent=$sent")
            Redirect(routes.OAuthChooserController.page(flow))
              .flashing(OAuthController.FlashSuccess -> (if (sent) "oauth.choose.mail.sent" else "oauth.choose.mail.not.sent"))
          case _ =>
            Redirect(routes.OAuthChooserController.page(flow)).flashing(OAuthController.FlashError -> "oauth.choose.proof.needed")
        }
      }
    }(blocking)
  }

  /** The mailed link's page: a button that confirms (a POST), so that a mail scanner opening the
    * link changes nothing. Never cached, its URL never sent as a referrer.
    */
  def confirmPage(token: String): Action[AnyContent] = Action { implicit request =>
    linkPage(Ok(views.html.oauthChooseConfirm(token)))
  }

  /** Confirms the mailed link (a POST with the CSRF token): the link must be live, its account's,
    * and that account one of this browser's choice; then linked and signed in.
    */
  def confirm(): Action[AnyContent] = Action.async { implicit request =>
    Future {
      val token = request.body.asFormUrlEncoded.flatMap(_.get("token")).flatMap(_.headOption).getOrElse("")
      val pending = request.session.get(Flow).flatMap(choice(request.session, _))
      pending.fold(linkPage(refused("oauth.choose.expired"))) { c =>
        AccountTokenService.useChooseLink(token) match {
          case Right(user) if c.options.exists(_._2 == user.getId) && !user.isRoot => linkPage(linkAndSignIn(c, user))
          case Right(user) =>
            authLog.warn(s"oauth_choose_proof_failed accountId=${user.getId} proof=mail reason=not_this_choice")
            linkPage(refused("oauth.choose.expired"))
          case Left(_) => linkPage(refused("oauth.choose.link.invalid"))
        }
      }
    }(blocking)
  }

  private def linkPage(result: Result): Result =
    result.withHeaders(CACHE_CONTROL -> "no-store", "Referrer-Policy" -> "no-referrer")

  /** Whether the chooser offers a password proof for the account: a usable password, and nobody
    * but its email's owner can hold it (F4: a pre-claimer's password with an unverified email is
    * no proof).
    */
  private def passwordProof(user: User): Boolean = user.hasUsablePassword && user.mayTakeAccessRole

  /** The session's login name first (F1: a refusal links nothing), then the link and sign-in. */
  private def linkAndSignIn(c: Choice, user: User)(implicit request: RequestHeader): Result =
    try
      OAuthController.loginName(user).fold(refused("oauth.choose.ask.organizer")) { name =>
        IdentityLinker.linkChosen(user, c.profile) match {
          case Left(error) => refused(error.messageKey)
          case Right(linked) =>
            if (linked.isRoot) log.warn(s"oauth_signin provider=wikimedia accountId=${linked.getId} root=true via=chooser")
            else log.info(s"oauth_signin provider=wikimedia accountId=${linked.getId} via=chooser")
            signedIn(linked, name, c.returnTo, keep = true)
        }
      }
    catch {
      case NonFatal(e) =>
        val reference = Secured.newReference()
        log.error(s"oauth_choose_failed ref=$reference error=${e.getClass.getName}")
        refused("oauth.failed")
    }

  /** Back to the sign-in page with the message; the choice leaves the session. */
  private def refused(messageKey: String)(implicit request: RequestHeader): Result =
    Redirect(routes.LoginController.login(None))
      .withSession(request.session -- Keys)
      .flashing(OAuthController.FlashError -> messageKey)

  /** An account as the list shows it (F3): contests and roles only for one chosen directly; for
    * one that needs a proof, its masked email and the proofs; none without a login name of its
    * own (F1: "ask your organizer").
    */
  private def entry(option: String, user: User): Entry = {
    val signable = OAuthController.loginName(user).isDefined
    val direct   = signable && IdentityLinker.choosableDirectly(user)
    val contests =
      if (!direct) Nil
      else UserContestJdbc.findByUser(user.getId).flatMap { m =>
        ContestJuryJdbc.findById(m.contestId).map(c => c.fullName -> m.role)
      }
    Entry(option, user.wikiAccount.getOrElse(""), Option(user.emailLo).filter(_.nonEmpty).map(maskEmail),
      contests, direct, signable && !direct && passwordProof(user),
      signable && !direct && user.emailVerified && user.emailLo.nonEmpty)
  }
}

object OAuthChooserController {
  val Flow     = "oauth.choose.flow"
  val At       = "oauth.choose.at"
  val Subject  = "oauth.choose.sub"
  val Username = "oauth.choose.user"
  val Options  = "oauth.choose.options"
  val ReturnTo = "oauth.choose.returnTo"

  val Keys: Seq[String] = Seq(Flow, At, Subject, Username, Options, ReturnTo)

  /** How long a choice waits in the session (the mailed link's lifetime too). */
  val ValidMillis: Long = AccountTokenService.OAuthChooseValidSeconds * 1000

  /** Wrong passwords a choice may take (F4); then the user signs in with Wikimedia again. */
  val MaxPasswordFailures = 5

  /** The wrong passwords of each choice by its flow token, in memory (one app instance, as the
    * other in-memory limits): a replayed older session cookie can't reset it. Forgotten after
    * [[ValidMillis]].
    */
  private[controllers] object Failures {
    private val byFlow = new java.util.concurrent.ConcurrentHashMap[String, (Int, Long)]()

    def count(flow: String): Int = {
      val now = System.currentTimeMillis
      byFlow.entrySet().removeIf(e => now - e.getValue._2 > ValidMillis)
      Option(byFlow.get(flow)).fold(0)(_._1)
    }

    def add(flow: String): Unit =
      byFlow.merge(flow, (1, System.currentTimeMillis), (a, b) => (a._1 + b._1, a._2))
  }

  /** An email with its local part and domain masked (F3): `a***@e***.org`. */
  def maskEmail(email: String): String = email.indexOf('@') match {
    case at if at > 0 =>
      val domain = email.substring(at + 1)
      val dot    = domain.lastIndexOf('.')
      val masked =
        if (dot > 0) domain.take(1) + "***" + domain.substring(dot)
        else domain.take(1) + "***"
      email.take(1) + "***@" + masked
    case _ => "***"
  }

  /** An account of the list, as the page shows it: its option token, wiki account, masked email,
    * contests and roles, and the ways it may be chosen.
    */
  final case class Entry(option: String, wikiAccount: String, maskedEmail: Option[String],
                         contests: Seq[(String, String)], direct: Boolean, password: Boolean, mail: Boolean)

  /** The session's choice: the Wikimedia identity and the listed accounts by option token. */
  final case class Choice(profile: ExternalProfile, options: Seq[(String, Long)], returnTo: Option[String])

  /** The session keys of a new choice of `accounts` for the identity: a new flow token, and the
    * flow token, for the page's URL.
    */
  def start(profile: ExternalProfile, accounts: Seq[User], returnTo: Option[String]): (String, Seq[(String, String)]) = {
    val flow    = AccountTokenService.generate()
    val options = accounts.map(u => s"${AccountTokenService.generate()}:${u.getId}").mkString(",")
    flow -> (Seq(
      Flow     -> flow,
      At       -> System.currentTimeMillis.toString,
      Subject  -> profile.subject,
      Username -> profile.username.getOrElse(""),
      Options  -> options
    ) ++ Secured.safeLocalPath(returnTo).map(ReturnTo -> _))
  }

  /** The session's choice if `flow` is its token and it is younger than [[ValidMillis]]. */
  def choice(session: Session, flow: String): Option[Choice] =
    for {
      f    <- session.get(Flow) if f.nonEmpty && f == flow
      at   <- session.get(At).flatMap(_.toLongOption) if System.currentTimeMillis - at <= ValidMillis
      sub  <- session.get(Subject).filter(_.nonEmpty)
      name <- session.get(Username).filter(_.nonEmpty)
      opts <- session.get(Options)
    } yield Choice(
      ExternalProfile(OAuthProviders.Wikimedia, sub, username = Some(name)),
      opts.split(",").toSeq.flatMap(_.split(":", 2) match {
        case Array(o, id) if o.nonEmpty => id.toLongOption.map(o -> _)
        case _                          => None
      }),
      Secured.safeLocalPath(session.get(ReturnTo))
    )
}
