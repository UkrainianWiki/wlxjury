package services.imports

import org.scalawiki.MwBot
import play.api.libs.json.{JsValue, Json}
import play.api.{Configuration, Logging}

import java.util.concurrent.atomic.AtomicBoolean
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** The Commons account behind the app's API requests. With `commons.user` and
  * `commons.password` set (a bot password, `User@name`), it logs the shared [[MwBot]] in.
  * An account with the `apihighlimits` right (the bot group) may then ask for 500 page ids
  * per request instead of 50, and list 5000 files per request instead of 500.
  *
  * A MediaWiki session ends after a while without requests, and reads on an ended session
  * are anonymous, without an error. So [[ensure]], called before each import step, asks the
  * API who the session is (`meta=userinfo`) when its last answer is older than `recheck`,
  * and logs in again only if the session has become anonymous: a job that keeps making
  * requests keeps its session, and one that starts after days without any logs in again.
  * A failed login or check leaves the anonymous limits: imports still work, in smaller
  * batches, and the next check is after `recheck`. That includes a check the API answers
  * with an error, a login that throws (an error page, a timeout), and a login refused for
  * its token ([[TransientLoginResults]]): none of these is a wrong password. A login the
  * wiki refuses otherwise (a wrong or revoked bot password, a blocked account) is tried
  * again only after `loginRetry`, so as not to run into the wiki's limit of failed logins.
  *
  * One caller checks at a time, and the others don't wait for it: they go on with the last
  * answer (a batch too large for it is fetched again in smaller ones, [[MwCommonsSource]]).
  *
  * @param now nanoseconds of a monotonic clock
  */
class CommonsLogin(
    bot: MwBot,
    credentials: Option[CommonsLogin.Credentials],
    recheck: FiniteDuration = CommonsLogin.Recheck,
    loginRetry: FiniteDuration = CommonsLogin.LoginRetry,
    now: () => Long = () => System.nanoTime()
) extends Logging {

  import CommonsLogin._

  @volatile private var status: Status = Status.Anonymous
  /** When [[ensure]] asks again; None: at the next call. */
  @volatile private var nextCheck: Option[Long] = None
  /** Set while a caller checks. */
  private val checking = new AtomicBoolean(false)

  /** Who the session is: checked, and logged in again if needed, when the last check is
    * older than `recheck` (`loginRetry` after a refused login); while another caller
    * checks, the last answer. Without credentials, anonymous, and nothing is asked.
    */
  def ensure(): Status = {
    if (credentials.nonEmpty && due && checking.compareAndSet(false, true))
      try { if (due) check() }
      finally checking.set(false)
    status
  }

  /** Page ids or titles per request: [[HighLimit]] with `apihighlimits`, else [[Limit]]. */
  def idsPerRequest: Int = if (ensure().highLimits) HighLimit else Limit

  /** Forgets the last check, so the next [[ensure]] asks again: e.g. after the API refused
    * a batch as too large for the session.
    */
  def invalidate(): Unit = nextCheck = None

  private def due: Boolean = nextCheck.forall(t => now() - t >= 0)

  private def check(): Unit = {
    var refused = false
    val checked =
      try {
        val current = userInfo()
        if (!current.anonymous) current
        else
          credentials.fold(current) { c =>
            val result = login(c)
            if (result == LoginSuccess) userInfo()
            else {
              refused = !TransientLoginResults(result)
              val retry = if (refused) loginRetry else recheck
              logger.warn(s"${bot.host}: login as ${c.user} failed: $result; trying again in $retry")
              Status.Anonymous
            }
          }
      } catch {
        case NonFatal(e) =>
          logger.warn(s"${bot.host}: could not check or log in the account: ${e.getMessage}; trying again in $recheck")
          Status.Anonymous
      }
    if (checked != status || nextCheck.isEmpty) logger.info(s"${bot.host}: ${checked.describe}")
    status = checked
    nextCheck = Some(now() + (if (refused) loginRetry else recheck).toNanos)
  }

  private def userInfo(): Status =
    parseUserInfo(Json.parse(Await.result(bot.post(UserInfoParams), Timeout)))

  /** Logs in: `Success`, or why MediaWiki didn't log the account in. */
  private def login(c: Credentials): String = Await.result(bot.login(c.user, c.password), Timeout)
}

object CommonsLogin {

  /** Page ids or titles per request without `apihighlimits`. */
  val Limit = 50

  /** Page ids or titles per request with `apihighlimits`. */
  val HighLimit = 500

  val Recheck: FiniteDuration = 10.minutes

  /** The wait after a refused login: well within Wikimedia's 150 failed logins in 48 hours. */
  val LoginRetry: FiniteDuration = 6.hours

  val Timeout: FiniteDuration = 1.minute

  val LoginSuccess = "Success"

  /** `action=login` results that aren't about the password: a token the session lost. */
  val TransientLoginResults: Set[String] = Set("NeedToken", "WrongToken")

  val UserInfoParams: Map[String, String] =
    Map("action" -> "query", "meta" -> "userinfo", "uiprop" -> "rights", "format" -> "json", "utf8" -> "")

  /** A bot password. Its `toString` leaves the password out. */
  final case class Credentials(user: String, password: String) {
    override def toString: String = s"Credentials($user, ***)"
  }

  /** Anonymous requests: no credentials, nothing to check. */
  def anonymous(bot: MwBot): CommonsLogin = new CommonsLogin(bot, None)

  /** `commons.user` and `commons.password`, if both are set and not empty. */
  def credentials(config: Configuration): Option[Credentials] =
    for {
      user <- config.getOptional[String]("commons.user").filter(_.nonEmpty)
      password <- config.getOptional[String]("commons.password").filter(_.nonEmpty)
    } yield Credentials(user, password)

  /** Who a session is.
    * @param user the account's name; None for an anonymous session
    */
  final case class Status(user: Option[String], highLimits: Boolean) {
    def anonymous: Boolean = user.isEmpty
    def describe: String = user.fold("anonymous requests")(u => s"logged in as $u, apihighlimits: ${if (highLimits) "yes" else "no"}")
  }

  object Status {
    val Anonymous: Status = Status(None, highLimits = false)
  }

  /** A `meta=userinfo` answer: `anon` (and id 0) for an anonymous session.
    * @throws IllegalStateException for an answer without `userinfo` (an API error): it
    *                               doesn't say whether the session has ended
    */
  def parseUserInfo(json: JsValue): Status = {
    val info = (json \ "query" \ "userinfo").toOption.getOrElse(
      throw new IllegalStateException(s"no userinfo in the answer: ${Json.stringify(json).take(200)}"))
    val anonymous = (info \ "anon").isDefined || (info \ "id").asOpt[Long].forall(_ == 0L)
    if (anonymous) Status.Anonymous
    else Status((info \ "name").asOpt[String], (info \ "rights").asOpt[Seq[String]].exists(_.contains("apihighlimits")))
  }
}
