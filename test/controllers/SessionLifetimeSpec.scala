package controllers

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import play.api.mvc.Session

import scala.concurrent.duration._

/** [[SessionLifetime]]'s rules without a database (S3). */
class SessionLifetimeSpec extends Specification {

  private val settings = SessionLifetime.Settings(365.days, 30.days, 5.minutes)
  private val day = 24 * 3600L
  private val now = 2000000000L

  private def session(issuedAgo: Long, seenAgo: Long, keep: Boolean = false) =
    Session(Map(Secured.UserName -> "a@example.com", SessionLifetime.IssuedAt -> (now - issuedAgo).toString,
      SessionLifetime.LastSeen -> (now - seenAgo).toString) ++ Option.when(keep)(SessionLifetime.Keep -> "1"))

  "expiry" should {
    "end a session past the absolute or the idle limit, or with a malformed time" in {
      SessionLifetime.expiry(session(365 * day, 0), now, settings) must beNone
      SessionLifetime.expiry(session(365 * day + 1, 0), now, settings) === Some("absolute")
      SessionLifetime.expiry(session(40 * day, 30 * day), now, settings) must beNone
      SessionLifetime.expiry(session(40 * day, 30 * day + 1), now, settings) === Some("idle")
      SessionLifetime.expiry(Session(Map(SessionLifetime.IssuedAt -> "x")), now, settings) === Some("malformed")
    }

    "count a session from before S3 (no times) as signed in now" in {
      val old = Session(Map(Secured.UserName -> "a@example.com"))
      SessionLifetime.expiry(old, now, settings) must beNone
      SessionLifetime.needsUpgrade(old) must beTrue
      SessionLifetime.needsUpgrade(session(0, 0)) must beFalse
    }
  }

  "the last request time" should {
    "be refreshed only once it is touchEvery old" in {
      SessionLifetime.needsTouch(session(0, 299), now, settings) must beFalse
      SessionLifetime.needsTouch(session(0, 300), now, settings) must beTrue
    }
  }

  "a persistent cookie's Max-Age" should {
    "be the idle limit, never past the absolute one" in {
      SessionLifetime.maxAgeSeconds(session(0, 0, keep = true), now, settings).toLong === 30 * day
      SessionLifetime.maxAgeSeconds(session(364 * day, 0, keep = true), now, settings).toLong === day
      SessionLifetime.keep(session(0, 0, keep = true)) must beTrue
      SessionLifetime.keep(session(0, 0)) must beFalse
    }
  }

  "a new sign-in" should {
    "be issued and seen now, kept only if asked" in {
      SessionLifetime.signedIn(keep = true, now).toMap ===
        Map(SessionLifetime.IssuedAt -> now.toString, SessionLifetime.LastSeen -> now.toString, SessionLifetime.Keep -> "1")
      SessionLifetime.signedIn(keep = false, now).toMap.get(SessionLifetime.Keep) must beNone
    }
  }

  "the settings" should {
    "be read from wlxjury.session, with the defaults, and refuse nonsense" in {
      SessionLifetime.Settings.from(ConfigFactory.empty()) === SessionLifetime.Settings.Defaults
      SessionLifetime.Settings.from(ConfigFactory.parseString(
        "wlxjury.session { absoluteDays = 30, idleDays = 1, touchMinutes = 2 }")) ===
        SessionLifetime.Settings(30.days, 1.day, 2.minutes)
      SessionLifetime.Settings.from(ConfigFactory.parseString("wlxjury.session.idleDays = 0")) must
        throwA[IllegalArgumentException]
      SessionLifetime.Settings.from(ConfigFactory.parseString("wlxjury.session.touchMinutes = 100000")) must
        throwA[IllegalArgumentException]
      SessionLifetime.default === SessionLifetime.Settings.Defaults
    }
  }
}
