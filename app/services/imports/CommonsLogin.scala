package services.imports

import org.scalawiki.MwBot
import play.api.libs.json.{JsValue, Json}
import play.api.{Configuration, Logging, Mode}

import java.time.Instant
import java.time.temporal.ChronoUnit
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** The Commons account behind the app's API requests. With `commons.user` and
  * `commons.password` set (a bot password, `User@name`), it logs the shared [[MwBot]] in.
  * An account with the `apihighlimits` right (the bot group) may then ask for 500 page ids
  * per request instead of 50, and list 5000 files per request instead of 500.
  *
  * A MediaWiki session ends after a while without requests, and reads on an ended session
  * are anonymous, without an error. So [[ensure]], called at the start of each import
  * step's fetch ([[MwCommonsSource.maxIdsPerRequest]]), asks the API who the session is
  * (`meta=userinfo`) when its last answer is older than `recheck`, and logs in again only
  * if the session has become anonymous: a job that keeps making requests keeps its
  * session, and one that starts after days without any logs in again.
  *
  * A check the API doesn't answer (a timeout, an error page, an API error) keeps the last
  * answer: it doesn't say the session has ended, and a batch too large for the session is
  * fetched again in smaller ones anyway ([[MwCommonsSource]]). A failed login leaves the
  * anonymous limits: imports still work, in smaller batches, and the next check is after
  * `recheck`. That includes a login that throws, a login refused for its token
  * ([[TransientLoginResults]]) and one that succeeds but leaves the session anonymous:
  * none of these is a wrong password. A login the wiki refuses otherwise (a wrong or
  * revoked bot password, a blocked account) is tried again when an import step needs it,
  * but not before a gap that starts at `loginRetryFirst` (5 minutes) and doubles with each
  * refusal in a row (5, 10, 20, 40 minutes) up to `loginRetryMax` (1 hour); a successful
  * login, or a session found logged in, resets it. A refusal may be a one-off (the
  * password itself is fine), so imports should not stay anonymous (50 ids per request
  * instead of 500) for long; but repeated or concurrent jobs must not run into the wiki's
  * limits of failed logins: 150 per 48 hours per IP and 5 per 5 minutes per account. With
  * a bot password that stays wrong, the worst case is 4 tries in the first 75 minutes and
  * then one an hour: about 50 in 48 hours, and never more than one in 5 minutes. A login
  * that throws or is refused for its token, after refusals in a row, waits at least the
  * current gap too, and no login (not even after an [[invalidate]]) starts before it.
  *
  * One caller checks at a time, and the others don't wait for it: they go on with the last
  * answer. An [[invalidate]] while it checks makes the next [[ensure]] check again, as the
  * check may have asked before the session ended.
  *
  * [[state]] says, in a short line for `/health/ready`, what the last check found, without the
  * account's name.
  *
  * @param now   nanoseconds of a monotonic clock
  * @param clock the wall clock, for the times [[state]] shows
  */
class CommonsLogin(
    bot: MwBot,
    credentials: Option[CommonsLogin.Credentials],
    recheck: FiniteDuration = CommonsLogin.Recheck,
    loginRetryFirst: FiniteDuration = CommonsLogin.LoginRetryFirst,
    loginRetryMax: FiniteDuration = CommonsLogin.LoginRetryMax,
    now: () => Long = () => System.nanoTime(),
    clock: () => Instant = () => Instant.now()
) extends Logging {

  import CommonsLogin._

  @volatile private var status: Status = Status.Anonymous
  /** When [[ensure]] asks again; None: at the next call. */
  @volatile private var nextCheck: Option[Long] = None
  /** Logins the wiki refused in a row (not counting [[TransientLoginResults]] or a throw). */
  @volatile private var refusals: Int = 0
  /** No login before this time, even when a check finds the session anonymous. */
  @volatile private var nextLogin: Option[Long] = None
  /** When the wiki last refused the login, and when the next login may start: for [[state]]. */
  @volatile private var refusedAt: Option[Instant] = None
  @volatile private var nextLoginAt: Option[Instant] = None
  /** Set once a check has finished. */
  @volatile private var checkedOnce = false
  /** Set while a caller checks. */
  private val checking = new AtomicBoolean(false)
  /** Counts [[invalidate]] calls, so a check knows whether one came while it ran. */
  private val invalidations = new AtomicLong()

  /** Who the session is: checked, and logged in again if needed, when the last check is
    * older than `recheck` (the login gap after a refused login); while another caller
    * checks, the last answer. Without credentials, anonymous, and nothing is asked.
    */
  def ensure(): Status = {
    if (credentials.nonEmpty && due && checking.compareAndSet(false, true))
      try { if (due) check() }
      finally checking.set(false)
    status
  }

  /** What the last check found, in a short line for `/health/ready`: "logged in,
    * apihighlimits: yes", "refused at <time>, next try at <time> at the earliest", "anonymous
    * (no account configured)", "not checked yet". Asks nothing; no account name, no password.
    */
  def state: String =
    if (credentials.isEmpty) "anonymous (no account configured)"
    else if (!checkedOnce) "not checked yet"
    else if (!status.anonymous) s"logged in, apihighlimits: ${if (status.highLimits) "yes" else "no"}"
    else {
      val next = nextLoginAt.filter(_.isAfter(clock())).map(t => s", next try at ${iso(t)} at the earliest").getOrElse("")
      refusedAt.filter(_ => refusals > 0) match {
        case Some(at) => s"refused at ${iso(at)}$next"
        case None => s"anonymous$next"
      }
    }

  /** Page ids or titles per request: [[HighLimit]] with `apihighlimits`, else [[Limit]]. */
  def idsPerRequest: Int = if (ensure().highLimits) HighLimit else Limit

  /** Forgets the last check, so the next [[ensure]] asks again: e.g. after the API refused
    * a batch as too large for the session.
    */
  def invalidate(): Unit = {
    invalidations.incrementAndGet()
    nextCheck = None
  }

  private def due: Boolean = nextCheck.forall(t => now() - t >= 0)

  /** The gap after `n` refused logins in a row: `loginRetryFirst` doubled `n - 1` times, at
    * most `loginRetryMax`.
    */
  private def refusedGap(n: Int): FiniteDuration =
    if (n <= 0) Duration.Zero
    else {
      val first = loginRetryFirst.toNanos
      val max = loginRetryMax.toNanos
      // stop doubling once past the cap, so the Long can't overflow
      var gap = first
      var i = 1
      while (i < n && gap < max) { gap *= 2; i += 1 }
      math.min(gap, max).nanos
    }

  /** The wait after a login that threw or was refused for its token: `recheck`, or the
    * current refused-login gap if longer, so a throw can't shorten that gap.
    */
  private def afterFailedLogin(): FiniteDuration = recheck.max(refusedGap(refusals))

  /** Records a login attempt that didn't log in: no login before `gap` from now. */
  private def noLoginFor(gap: FiniteDuration): FiniteDuration = {
    nextLogin = Some(now() + gap.toNanos)
    nextLoginAt = Some(clock().plusNanos(gap.toNanos))
    gap
  }

  private def loginOk(): Unit = {
    refusals = 0
    nextLogin = None
    refusedAt = None
    nextLoginAt = None
  }

  private def check(): Unit = {
    val invalidated = invalidations.get()
    var retry = recheck
    // set once the API has said who the session is: an error before that keeps the last answer
    var answered = false
    val checked =
      try {
        val current = userInfo()
        answered = true
        if (!current.anonymous) {
          loginOk()
          current
        } else
          credentials.fold(current) { c =>
            nextLogin.map(_ - now()).filter(_ > 0) match {
              case Some(wait) =>
                // a login refused or failed recently: no new one before its gap
                retry = wait.nanos
                Status.Anonymous
              case None =>
                val result = login(c)
                if (result == LoginSuccess) {
                  loginOk()
                  loggedIn(c)
                } else {
                  retry =
                    if (TransientLoginResults(result)) noLoginFor(afterFailedLogin())
                    else {
                      refusals += 1
                      refusedAt = Some(clock())
                      noLoginFor(refusedGap(refusals))
                    }
                  logger.warn(s"${bot.host}: login as ${c.user} failed: ${controllers.Secured.logValue(result.toString)}; " +
                    s"trying again in ${describe(retry)} at the earliest, when an import needs it")
                  Status.Anonymous
                }
            }
          }
      } catch {
        case NonFatal(e) if !answered =>
          logger.warn(s"${bot.host}: could not check the account: ${controllers.Secured.logValue(String.valueOf(e.getMessage))}; " +
            s"keeping the last answer (${status.describe}), checking again in $recheck")
          status
        case NonFatal(e) =>
          retry = noLoginFor(afterFailedLogin())
          logger.warn(s"${bot.host}: could not log in the account: ${controllers.Secured.logValue(String.valueOf(e.getMessage))}; " +
            s"trying again in ${describe(retry)} at the earliest, when an import needs it")
          Status.Anonymous
      }
    if (checked != status || nextCheck.isEmpty) logger.info(s"${bot.host}: ${checked.describe}")
    status = checked
    checkedOnce = true
    nextCheck = if (invalidations.get() != invalidated) None else Some(now() + retry.toNanos)
  }

  /** The session after a successful login: anonymous still if it didn't keep the login
    * (e.g. its cookies weren't stored), which logging in again won't fix by itself.
    */
  private def loggedIn(c: Credentials): Status = {
    val after = userInfo()
    if (after.anonymous)
      logger.warn(s"${bot.host}: logged in as ${c.user}, but the session is still anonymous " +
        s"(are its cookies kept?); trying again in ${describe(recheck)}")
    after
  }

  private def userInfo(): Status =
    parseUserInfo(Json.parse(Await.result(bot.post(UserInfoParams), Timeout)))

  /** Logs in: `Success`, or why MediaWiki didn't log the account in. */
  private def login(c: Credentials): String = Await.result(bot.login(c.user, c.password), Timeout)
}

object CommonsLogin {

  /** Not `Logging`: the class imports this object's members and has its own `logger`. */
  private val credentialsLogger = play.api.Logger(classOf[CommonsLogin])

  /** Page ids or titles per request without `apihighlimits`. */
  val Limit = 50

  /** Page ids or titles per request with `apihighlimits`. */
  val HighLimit = 500

  val Recheck: FiniteDuration = 10.minutes

  /** The gap after a first refused login; it doubles with each refusal in a row, up to
    * [[LoginRetryMax]]. At most one login in 5 minutes: Wikimedia allows 5 failed logins per
    * 5 minutes per account.
    */
  val LoginRetryFirst: FiniteDuration = 5.minutes

  /** The longest gap between refused logins: about 50 tries in 48 hours with a bot password
    * that stays wrong, well within Wikimedia's 150 failed logins per 48 hours per IP.
    */
  val LoginRetryMax: FiniteDuration = 1.hour

  val Timeout: FiniteDuration = 1.minute

  val LoginSuccess = "Success"

  /** `action=login` results that aren't about the password: a token the session lost. */
  val TransientLoginResults: Set[String] = Set("NeedToken", "WrongToken")

  val UserInfoParams: Map[String, String] =
    Map("action" -> "query", "meta" -> "userinfo", "uiprop" -> "rights", "format" -> "json", "utf8" -> "")

  private def iso(t: Instant): String = t.truncatedTo(ChronoUnit.SECONDS).toString

  /** A wait for the log: "40 minutes", "1 hour", "90 seconds". */
  def describe(d: FiniteDuration): String = {
    def unit(n: Long, name: String) = s"$n $name${if (n == 1) "" else "s"}"
    val s = math.max(0L, (d.toMillis + 999) / 1000)
    if (s >= 3600 && s % 3600 == 0) unit(s / 3600, "hour")
    else if (s >= 60 && s % 60 == 0) unit(s / 60, "minute")
    else if (s >= 120) unit((s + 59) / 60, "minute")
    else unit(s, "second")
  }

  /** A bot password. Its `toString` leaves the password out. */
  final case class Credentials(user: String, password: String) {
    override def toString: String = s"Credentials($user, ***)"
  }

  /** Anonymous requests: no credentials, nothing to check. */
  def anonymous(bot: MwBot): CommonsLogin = new CommonsLogin(bot, None)

  /** Lets a Mode.Test app log in. No spec or CI job sets it, and build.sbt doesn't forward it
    * from sbt's command line: the test JVM is forked, and the test config's account is empty. A
    * deliberate manual run against Commons with an account needs all three in the forked JVM:
    * `set Test / javaOptions ++= Seq("-Dcommons.allowTestLogin=true", "-Dcommons.user=…",
    * "-Dcommons.password=…")` (the password is then visible in the process list).
    */
  val AllowTestLoginKey = "commons.allowTestLogin"

  /** `commons.user` and `commons.password`, if both are set and not empty. None in
    * Mode.Test unless [[AllowTestLoginKey]] is true: tests use Commons anonymously, so a
    * machine's credentials (or a stale bot password) can't turn test runs into logins.
    */
  def credentials(config: Configuration, mode: Mode): Option[Credentials] = {
    val configured = for {
      user <- config.getOptional[String]("commons.user").filter(_.nonEmpty)
      password <- config.getOptional[String]("commons.password").filter(_.nonEmpty)
    } yield Credentials(user, password)
    if (mode != Mode.Test || config.getOptional[Boolean](AllowTestLoginKey).contains(true)) configured
    else {
      configured.foreach(c => credentialsLogger.warn(s"Not logging in as ${c.user} in Mode.Test: set -D$AllowTestLoginKey=true to allow it"))
      None
    }
  }

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
