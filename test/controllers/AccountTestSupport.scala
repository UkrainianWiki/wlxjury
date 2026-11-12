package controllers

import db.scalikejdbc.User
import play.api.Application
import play.api.i18n.{Lang, MessagesApi, MessagesImpl}
import play.api.mvc.ControllerComponents
import services.{PasswordHasher, SMTPOrWikiMail, UserService}

import scala.collection.mutable.ArrayBuffer
import scala.jdk.CollectionConverters._

/** A mailer that keeps what it is given (or fails, with `failing`): specs read the links in
  * the mails it got. Never sends anything.
  */
class RecordingMail extends SMTPOrWikiMail(null) {
  val sent: ArrayBuffer[(User, User, String, String)] = ArrayBuffer.empty
  @volatile var failing = false

  override def sendMail(from: User, to: User, subject: String, message: String): Unit = {
    if (failing) throw new RuntimeException("mail server down")
    sent += ((from, to, subject, message))
  }

  def recipients: Seq[String] = sent.map(_._2.email).toSeq

  /** The token of the last link in the last mail (an invitation, a password or an email link). */
  def lastToken: String =
    RecordingMail.Link.findAllMatchIn(sent.last._4).toSeq.last.group(2)
}

object RecordingMail {
  val Link = """https?://[^\s/]+/(invitation|email/confirm)/([A-Za-z0-9_-]{43})""".r
}

/** Controllers and helpers for the account specs: the app's messages, a recording mailer. */
trait AccountTestSupport {

  def messagesOf(implicit app: Application): MessagesImpl =
    MessagesImpl(Lang("en"), app.injector.instanceOf[MessagesApi])

  /** With the mails that go out after the answer sent at once, for the specs to read. */
  def userService(mail: RecordingMail)(implicit app: Application): UserService =
    userServiceWith(mail, app.configuration)

  def userServiceWith(mail: RecordingMail, configuration: play.api.Configuration): UserService =
    new UserService(mail, configuration) {
      override protected def mailInBackground(send: => Unit): Unit = send
    }

  def userController(mail: RecordingMail)(implicit app: Application) =
    new UserController(app.injector.instanceOf[ControllerComponents], userService(mail),
      app.injector.instanceOf[modules.BlockingExecutionContext])

  def loginController(mail: RecordingMail)(implicit app: Application) =
    new LoginController(userService(mail), app.injector.instanceOf[ControllerComponents],
      app.injector.instanceOf[modules.SignInExecutionContext])

  def hash(password: String): String = PasswordHasher.default.hash(password)

  /** A member of the contest with the role, a password, and the email verified or not. */
  def account(email: String, contestId: Option[Long], role: String, password: Option[String] = Some("secret"),
              verified: Boolean = false, wikiAccount: Option[String] = None, root: Boolean = false): User =
    User.create(User(email, email, None, if (root) Set(User.ROOT_ROLE) else Set(role), password.map(hash), contestId,
      wikiAccount = wikiAccount, isRoot = root,
      emailVerifiedAt = if (verified) Some(java.time.ZonedDateTime.now) else None))

  /** The lines a `security.account` logger wrote while `f` ran. */
  def accountLog[T](f: => T): (T, Seq[String]) = {
    val (result, entries) = accountLogWithThrowables(f)
    (result, entries.map(_._1))
  }

  /** The lines a `security.account` logger wrote while `f` ran, each with the exception logged
    * with it as the log file shows it (its stack trace, causes and suppressed ones), if any.
    */
  def accountLogWithThrowables[T](f: => T): (T, Seq[(String, Option[String])]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.account").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    val level = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG)
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(e => e.getFormattedMessage ->
        Option(e.getThrowableProxy).map(ch.qos.logback.classic.spi.ThrowableProxyUtil.asString)))
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
  }
}
