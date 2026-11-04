package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc, UserIdentityJdbc}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.mvc._
import play.api.test.CSRFTokenHelper._
import play.api.test._
import play.api.{Application, Configuration}
import services.oauth._

import java.net.URI
import scala.concurrent.Future

/** The account chooser (OAuthChooserController): a Wikimedia sign-in whose username several
  * accounts have. A fake provider; no call leaves the JVM.
  */
class OAuthChooserSpec extends PlaySpecification with Results with PlayTestDb with AccountTestSupport {

  sequential

  implicit def materializer(implicit app: Application): Materializer = app.materializer

  val Host = "jury.example.org"

  object FakeWikimedia extends OAuthProvider {
    @volatile var profile: ExternalProfile = ExternalProfile(OAuthProviders.Wikimedia, "nobody")
    val id: String = OAuthProviders.Wikimedia
    def authorizationUri(client: OAuthClient, redirectUri: URI, flow: OAuthFlow): URI =
      URI.create(s"https://wikimedia.example/authorize?state=${flow.state.getValue}")
    def exchange(client: OAuthClient, redirectUri: URI, code: String, flow: OAuthFlow): ExternalProfile = profile
  }

  val settings = new OAuthSettings(Configuration.from(Map(
    "oauth.wikimedia.clients" -> Seq(Map("host" -> Host, "clientId" -> "wm-client", "clientSecret" -> "wm-secret"))
  )))

  def oauth(implicit app: Application) =
    new OAuthController(settings, new OAuthProviders(Seq(FakeWikimedia)), app.injector.instanceOf[BlockingExecutionContext],
      app.injector.instanceOf[ControllerComponents], ThemeCookies.default)

  def chooser(mail: RecordingMail)(implicit app: Application) =
    new OAuthChooserController(app.injector.instanceOf[BlockingExecutionContext],
      app.injector.instanceOf[ControllerComponents], ThemeCookies.default, userService(mail))

  def get(path: String, session: Session = Session()) =
    FakeRequest(GET, path).withHeaders(HOST -> Host).withSession(session.data.toSeq: _*)

  def post(path: String, session: Session, form: (String, String)*) =
    FakeRequest(POST, path).withHeaders(HOST -> Host).withSession(session.data.toSeq: _*).withFormUrlEncodedBody(form: _*)

  /** Signs in with Wikimedia as `username`: the callback's result. */
  def signIn(username: String, subject: String = "wm-1")(implicit app: Application): Future[Result] = {
    FakeWikimedia.profile = ExternalProfile(OAuthProviders.Wikimedia, subject, username = Some(username))
    val started = oauth.login("wikimedia")(get("/oauth/wikimedia/login"))
    val s = session(started)
    oauth.callback("wikimedia")(get(s"/oauth/wikimedia/callback?code=c&state=${s.get(OAuthController.StateKey).get}", s))
  }

  def flowOf(result: Future[Result]): String = session(result).get(OAuthChooserController.Flow).get

  /** The session's option token of the account. */
  def optionOf(s: Session, user: User): String =
    s.get(OAuthChooserController.Options).get.split(",").map(_.split(":")).collectFirst {
      case Array(o, id) if id == user.getId.toString => o
    }.get

  def actsAs(s: Session): Option[Long] = Secured.sessionUserOf(FakeRequest().withSession(s.data.toSeq: _*)).flatMap(_.id)

  /** Two accounts with the wiki account "Twin Name", as S2 stores both: a placeholder (no
    * password, no email) and a juror who signs in with a password and has a verified email.
    */
  def twins(): (User, User) = {
    createContests(10L)
    val placeholder = User.create(User("Placeholder", "", password = None, wikiAccount = Some("Twin Name")))
    UserContestJdbc.createMembership(placeholder.getId, 10L, "board")
    val juror = account("juror@example.com", Some(10L), "jury", verified = true, wikiAccount = Some("Twin Name"))
    (placeholder, juror)
  }

  "a Wikimedia sign-in matching several accounts [J-05]" should {
    "list them by option tokens, with masked emails, and contests only for one chosen directly" in testDbApp { implicit app =>
      val (placeholder, juror) = twins()

      val result = signIn("Twin Name")

      redirectLocation(result).get must startWith("/oauth/choose?flow=")
      val s = session(result)
      s.get(Secured.AccountId) must beNone
      val page = contentAsString(chooser(new RecordingMail).page(flowOf(result))(get("/oauth/choose", s).withCSRFToken))
      page must contain("j***@e***.com")
      page must not(contain("juror@example.com"))
      page must not(contain("example.com"))
      // F3: the account that needs a proof shows no contests or roles
      page must not(contain("contest10"))
      // the placeholder can't be chosen (F1): no form names its option
      page must not(contain(optionOf(s, placeholder)))
      page must contain(optionOf(s, juror))
      page must not(contain(s"""value="${juror.getId}""""))
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
    }

    "not link an account without a login name of its own, nor list it as choosable (F1)" in testDbApp { implicit app =>
      val (placeholder, _) = twins()
      val s = session(signIn("Twin Name"))
      val flow = s.get(OAuthChooserController.Flow).get

      // the placeholder's only name is the shared wiki account: no session could name it
      val page = contentAsString(chooser(new RecordingMail).page(flow)(get("/oauth/choose", s).withCSRFToken))
      page must contain("This account cannot be chosen here")
      val chosen = chooser(new RecordingMail).choose()(post("/oauth/choose", s, "flow" -> flow,
        "option" -> optionOf(s, placeholder), "proof" -> "direct"))

      flash(chosen).get(OAuthController.FlashError) === Some("oauth.choose.ask.organizer")
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
      User.findById(placeholder.getId).get.wikiAccountVerified must beFalse
      session(chosen).get(Secured.AccountId) must beNone
    }

    "offer no password proof for a password with an unverified email (F4)" in testDbApp { implicit app =>
      createContests(10L)
      account("one@example.com", Some(10L), "jury", verified = true, wikiAccount = Some("Twin Name"))
      val claimed = account("claimed@example.com", Some(10L), "jury", verified = false, wikiAccount = Some("Twin Name"))
      val s = session(signIn("Twin Name"))

      val tried = chooser(new RecordingMail).choose()(post("/oauth/choose", s, "flow" -> s.get(OAuthChooserController.Flow).get,
        "option" -> optionOf(s, claimed), "proof" -> "password", "password" -> "secret"))

      flash(tried).get(OAuthController.FlashError) === Some("oauth.choose.proof.needed")
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
    }

    "stop password proofs after five wrong ones in a choice (F4)" in testDbApp { implicit app =>
      val (_, juror) = twins()
      val s = session(signIn("Twin Name", subject = "wm-cap"))
      val flow = s.get(OAuthChooserController.Flow).get
      def wrong() = flash(chooser(new RecordingMail).choose()(post("/oauth/choose", s, "flow" -> flow,
        "option" -> optionOf(s, juror), "proof" -> "password", "password" -> "not-it"))).get(OAuthController.FlashError)

      forall(1 to OAuthChooserController.MaxPasswordFailures)(_ => wrong() === Some("oauth.choose.password.wrong"))
      wrong() === Some("oauth.choose.too.many")
      val right = chooser(new RecordingMail).choose()(post("/oauth/choose", s, "flow" -> flow,
        "option" -> optionOf(s, juror), "proof" -> "password", "password" -> "secret"))
      flash(right).get(OAuthController.FlashError) === Some("oauth.choose.too.many")
      UserIdentityJdbc.find("wikimedia", "wm-cap") must beNone
    }

    "need the password of an account one can sign in to; a wrong one links nothing" in testDbApp { implicit app =>
      val (_, juror) = twins()
      val s = session(signIn("Twin Name"))
      val flow = s.get(OAuthChooserController.Flow).get
      def choose(form: (String, String)*) = chooser(new RecordingMail).choose()(post("/oauth/choose", s, form: _*))

      val direct = choose("flow" -> flow, "option" -> optionOf(s, juror), "proof" -> "direct")
      flash(direct).get(OAuthController.FlashError) === Some("oauth.choose.proof.needed")

      val (wrong, lines) = accountLogOf("security.auth")(await(choose("flow" -> flow, "option" -> optionOf(s, juror),
        "proof" -> "password", "password" -> "not-it")))
      flash(Future.successful(wrong)).get(OAuthController.FlashError) === Some("oauth.choose.password.wrong")
      lines.exists(_.startsWith(s"oauth_choose_proof_failed accountId=${juror.getId} proof=password ip=")) must beTrue
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
      session(Future.successful(wrong)).get(Secured.AccountId) must beNone

      val right = choose("flow" -> flow, "option" -> optionOf(s, juror), "proof" -> "password", "password" -> "secret")
      actsAs(session(right)) === Some(juror.getId)
      UserIdentityJdbc.find("wikimedia", "wm-1").map(_.userId) === Some(juror.getId)
      User.findById(juror.getId).get.wikiAccountVerified must beTrue
      // the next sign-in goes straight to it, by its subject
      actsAs(session(signIn("Twin Name"))) === Some(juror.getId)
    }

    "prove an account by a link mailed to its verified address, opened in the same browser" in testDbApp { implicit app =>
      val (_, juror) = twins()
      val mail = new RecordingMail
      val s = session(signIn("Twin Name"))
      val flow = s.get(OAuthChooserController.Flow).get

      val sent = chooser(mail).choose()(post("/oauth/choose", s, "flow" -> flow, "option" -> optionOf(s, juror), "proof" -> "mail"))
      flash(sent).get(OAuthController.FlashSuccess) === Some("oauth.choose.mail.sent")
      mail.recipients === Seq("juror@example.com")
      mail.sent.last._4 must contain("Twin Name")
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
      val token = """/oauth/choose/confirm/([A-Za-z0-9_-]{43})""".r.findFirstMatchIn(mail.sent.last._4).get.group(1)

      // another browser (no choice in its session) gets nothing
      val elsewhere = chooser(mail).confirm()(post("/oauth/choose/confirm", Session(), "token" -> token))
      flash(elsewhere).get(OAuthController.FlashError) === Some("oauth.choose.expired")

      val confirmed = chooser(mail).confirm()(post("/oauth/choose/confirm", s, "token" -> token))
      actsAs(session(confirmed)) === Some(juror.getId)
      UserIdentityJdbc.find("wikimedia", "wm-1").map(_.userId) === Some(juror.getId)
      // single use
      val again = chooser(mail).confirm()(post("/oauth/choose/confirm", s, "token" -> token))
      session(again).get(Secured.AccountId) must beNone
    }

    "refuse a tampered or expired flow, and an option not of the session's choice" in testDbApp { implicit app =>
      val (_, placeholder) = twins()
      val s = session(signIn("Twin Name"))
      val flow = s.get(OAuthChooserController.Flow).get
      val c = chooser(new RecordingMail)

      flash(c.page("forged")(get("/oauth/choose", s))).get(OAuthController.FlashError) === Some("oauth.choose.expired")
      val forgedFlow = c.choose()(post("/oauth/choose", s, "flow" -> "forged", "option" -> optionOf(s, placeholder), "proof" -> "direct"))
      flash(forgedFlow).get(OAuthController.FlashError) === Some("oauth.choose.expired")
      val forgedOption = c.choose()(post("/oauth/choose", s, "flow" -> flow, "option" -> placeholder.getId.toString, "proof" -> "direct"))
      flash(forgedOption).get(OAuthController.FlashError) === Some("oauth.choose.expired")
      val old = s + (OAuthChooserController.At -> (System.currentTimeMillis - OAuthChooserController.ValidMillis - 1000).toString)
      val expired = c.choose()(post("/oauth/choose", old, "flow" -> flow, "option" -> optionOf(s, placeholder), "proof" -> "password",
        "password" -> "secret"))
      flash(expired).get(OAuthController.FlashError) === Some("oauth.choose.expired")
      UserIdentityJdbc.find("wikimedia", "wm-1") must beNone
    }

    "never list a root account" in testDbApp { implicit app =>
      val (placeholder, juror) = twins()
      val root = account("root@example.com", None, "jury", verified = true, root = true, wikiAccount = Some("Twin Name"))
      val s = session(signIn("Twin Name"))

      s.get(OAuthChooserController.Options).get.split(",").map(_.split(":")(1)).toSet ===
        Set(placeholder.getId.toString, juror.getId.toString)
      val page = contentAsString(chooser(new RecordingMail).page(s.get(OAuthChooserController.Flow).get)(get("/oauth/choose", s).withCSRFToken))
      page must not(contain("r***@e***.com"))
      root.id must beSome
    }
  }

  /** The lines a logger wrote while `f` ran. */
  def accountLogOf[T](name: String)(f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger(name).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      (result, scala.jdk.CollectionConverters.ListHasAsScala(appender.list).asScala.toSeq.map(_.getFormattedMessage))
    } finally logger.detachAppender(appender)
  }
}
