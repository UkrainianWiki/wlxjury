package controllers

import com.typesafe.config.{Config, ConfigFactory}
import play.api.mvc.Session

import scala.concurrent.duration._

/** How long a signed-in session lasts (security batch S3, O3), on the server whatever the cookie
  * says: at most [[Settings.absolute]] from sign-in ([[IssuedAt]], never extended) and
  * [[Settings.idle]] without a request ([[LastSeen]], refreshed by [[modules.SessionLifetimeFilter]]
  * at most every [[Settings.touchEvery]], so that not every response sets a cookie). The account's
  * session version ([[Secured.AuthVersion]]) ends every session at once; this ends one.
  *
  * A session from before S3 has neither time: it counts as signed in now, and the filter stamps
  * both on its first request (the in-place upgrade; the user's decision).
  */
object SessionLifetime {

  /** When the session was signed in, in epoch seconds: the absolute limit counts from it. */
  val IssuedAt = "issued_at"

  /** The session's latest request, in epoch seconds, to [[Settings.touchEvery]]. */
  val LastSeen = "last_seen"

  /** "Keep me signed in" was checked: a persistent cookie (Max-Age); otherwise a browser-session
    * cookie. The server-side limits are the same.
    */
  val Keep = "keep"

  /** The keys of a signed-in session: an expired session loses all of them (and the session's
    * contest), keeping what is not the sign-in's (the CSRF token, the token form's nonce).
    */
  val SignInKeys: Seq[String] =
    Seq(Secured.UserName, Secured.AccountId, Secured.AuthVersion, Secured.CurrentContestId, IssuedAt, LastSeen, Keep)

  /** @param absolute   the longest a session lasts from sign-in
    * @param idle       the longest a session lasts without a request
    * @param touchEvery how stale [[LastSeen]] may get before a response refreshes it
    */
  final case class Settings(absolute: FiniteDuration, idle: FiniteDuration, touchEvery: FiniteDuration)

  object Settings {
    val Defaults: Settings = Settings(365.days, 30.days, 5.minutes)

    /** `wlxjury.session.absoluteDays`, `idleDays`, `touchMinutes`; the defaults if absent. */
    def from(config: Config): Settings = {
      def get(key: String, default: Long): Long =
        if (config.hasPath(s"wlxjury.session.$key")) config.getLong(s"wlxjury.session.$key") else default
      val settings = Settings(
        get("absoluteDays", Defaults.absolute.toDays).days,
        get("idleDays", Defaults.idle.toDays).days,
        get("touchMinutes", Defaults.touchEvery.toMinutes).minutes
      )
      require(settings.absolute > Duration.Zero && settings.idle > Duration.Zero && settings.touchEvery > Duration.Zero,
        "wlxjury.session limits must be positive")
      require(settings.touchEvery < settings.idle, "wlxjury.session.touchMinutes must be shorter than idleDays")
      settings
    }
  }

  /** The app's settings, read once (as [[services.PasswordHasher.default]]'s). */
  lazy val default: Settings = Settings.from(ConfigFactory.load())

  def nowSeconds(): Long = System.currentTimeMillis() / 1000

  /** Why a session's times end it, if they do: `absolute`, `idle`, or `malformed` (a value that
    * is not a number, which a cookie of this app never has). A missing time counts as now (a
    * session from before S3, upgraded in place).
    */
  def expiry(session: Session, now: Long = nowSeconds(), settings: Settings = default): Option[String] = {
    def time(key: String): Either[Unit, Long] =
      session.get(key).fold[Either[Unit, Long]](Right(now))(v => v.toLongOption.toRight(()))
    (time(IssuedAt), time(LastSeen)) match {
      case (Right(issued), Right(seen)) =>
        if (now - issued > settings.absolute.toSeconds) Some("absolute")
        else if (now - seen > settings.idle.toSeconds) Some("idle")
        else None
      case _ => Some("malformed")
    }
  }

  /** Whether the session's times still let it act ([[expiry]]). */
  def live(session: Session): Boolean = expiry(session).isEmpty

  /** A session from before S3: neither time. */
  def needsUpgrade(session: Session): Boolean =
    session.get(IssuedAt).isEmpty || session.get(LastSeen).isEmpty

  /** Whether [[LastSeen]] is older than [[Settings.touchEvery]] (or missing). */
  def needsTouch(session: Session, now: Long = nowSeconds(), settings: Settings = default): Boolean =
    session.get(LastSeen).flatMap(_.toLongOption).forall(seen => now - seen >= settings.touchEvery.toSeconds)

  /** The keys of a new sign-in's times: issued and seen now, persistent if `keep`. */
  def signedIn(keep: Boolean, now: Long = nowSeconds()): Seq[(String, String)] =
    Seq(IssuedAt -> now.toString, LastSeen -> now.toString) ++ Option.when(keep)(Keep -> "1")

  /** A persistent cookie's Max-Age: the idle limit, but never past the absolute one. */
  def maxAgeSeconds(session: Session, now: Long = nowSeconds(), settings: Settings = default): Int = {
    val issued = session.get(IssuedAt).flatMap(_.toLongOption).getOrElse(now)
    val left = settings.absolute.toSeconds - (now - issued)
    math.max(0L, math.min(settings.idle.toSeconds, left)).toInt
  }

  /** Whether the session asks for a persistent cookie. */
  def keep(session: Session): Boolean = session.get(Keep).contains("1")
}
