package services.imports

import org.scalawiki.MwBot
import play.api.libs.json.{JsValue, Json}
import play.api.{Configuration, Logging}

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
  * batches.
  *
  * @param now nanoseconds of a monotonic clock
  */
class CommonsLogin(
    bot: MwBot,
    credentials: Option[CommonsLogin.Credentials],
    recheck: FiniteDuration = CommonsLogin.Recheck,
    now: () => Long = () => System.nanoTime()
) extends Logging {

  import CommonsLogin._

  @volatile private var status: Status = Status.Anonymous
  @volatile private var checkedAt: Option[Long] = None

  /** Who the session is: checked, and logged in again if needed, when the last check is
    * older than `recheck`. Without credentials, anonymous, and nothing is asked.
    */
  def ensure(): Status = synchronized {
    if (credentials.nonEmpty && checkedAt.forall(t => now() - t >= recheck.toNanos)) check()
    status
  }

  /** Page ids or titles per request: [[HighLimit]] with `apihighlimits`, else [[Limit]]. */
  def idsPerRequest: Int = if (ensure().highLimits) HighLimit else Limit

  /** Forgets the last check, so the next [[ensure]] asks again: e.g. after the API refused
    * a batch as too large for the session.
    */
  def invalidate(): Unit = synchronized { checkedAt = None }

  private def check(): Unit = {
    val checked =
      try {
        val current = userInfo()
        if (!current.anonymous) current
        else {
          credentials.foreach(login)
          userInfo()
        }
      } catch {
        case NonFatal(e) =>
          logger.warn(s"${bot.host}: could not check or log in the account: ${e.getMessage}")
          Status.Anonymous
      }
    if (checked != status || checkedAt.isEmpty) logger.info(s"${bot.host}: ${checked.describe}")
    status = checked
    checkedAt = Some(now())
  }

  private def userInfo(): Status =
    parseUserInfo(Json.parse(Await.result(bot.post(UserInfoParams), Timeout)))

  /** Logs in; MediaWiki answers a failed login with a result other than `Success`. */
  private def login(c: Credentials): Unit = {
    val result = Await.result(bot.login(c.user, c.password), Timeout)
    if (result != "Success") logger.warn(s"${bot.host}: login as ${c.user} failed: $result")
  }
}

object CommonsLogin {

  /** Page ids or titles per request without `apihighlimits`. */
  val Limit = 50

  /** Page ids or titles per request with `apihighlimits`. */
  val HighLimit = 500

  val Recheck: FiniteDuration = 10.minutes
  val Timeout: FiniteDuration = 1.minute

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

  /** A `meta=userinfo` answer: `anon` (and id 0) for an anonymous session. */
  def parseUserInfo(json: JsValue): Status = {
    val info = json \ "query" \ "userinfo"
    val anonymous = (info \ "anon").isDefined || (info \ "id").asOpt[Long].forall(_ == 0L)
    if (anonymous) Status.Anonymous
    else Status((info \ "name").asOpt[String], (info \ "rights").asOpt[Seq[String]].exists(_.contains("apihighlimits")))
  }
}
