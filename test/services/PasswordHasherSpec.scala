package services

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import play.api.data.Forms.{single, text}
import play.api.data.Form

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{CountDownLatch, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

class PasswordHasherSpec extends Specification {

  import PasswordHasher._

  private def hasher(cost: Int = 4, maxConcurrent: Int = 2, maxWait: FiniteDuration = 10.seconds) =
    new PasswordHasher(Settings(cost, maxConcurrent, maxWait))

  private val fast = hasher()

  /** The hash the app stored before bcrypt: the hex SHA-1 of the trimmed password. */
  private def legacy(password: String): String =
    MessageDigest.getInstance("SHA-1").digest(password.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  "a bcrypt hash" should {
    "be $2b$ at the configured cost, salted, and match only its password" in {
      val stored = fast.hash("correct horse")
      stored must beMatching("""\$2b\$04\$[./A-Za-z0-9]{53}""")
      fast.hash("correct horse") must not(beEqualTo(stored))
      fast.check(Some(stored), "correct horse") === Accepted(None)
      fast.check(Some(stored), "correct hors") === Rejected
      fast.check(Some(stored), "Correct horse") === Rejected
    }

    "trim the password when it is set and when it is checked, as sign-in always did" in {
      val stored = fast.hash("  spaced ")
      fast.check(Some(stored), "spaced") === Accepted(None)
      fast.check(Some(stored), " spaced  ") === Accepted(None)
    }

    "be checked at its own cost, and rehashed at the configured cost when that is higher" in {
      val cost4 = fast.hash("pw")
      val upgraded = hasher(cost = 5).check(Some(cost4), "pw")
      upgraded must beLike { case Accepted(Some(h)) => h must startWith("$2b$05$") }
      hasher(cost = 5).check(Some(hasher(cost = 5).hash("pw")), "pw") === Accepted(None)
      // never lowered
      fast.check(Some(hasher(cost = 5).hash("pw")), "pw") === Accepted(None)
    }

    "accept a $2a$ hash" in {
      val a = at.favre.lib.crypto.bcrypt.BCrypt.`with`(at.favre.lib.crypto.bcrypt.BCrypt.Version.VERSION_2A)
        .hashToString(4, "pw".toCharArray)
      a must startWith("$2a$04$")
      fast.check(Some(a), "pw") === Accepted(None)
      fast.check(Some(a), "px") === Rejected
    }
  }

  "a legacy SHA-1 hash" should {
    "match its password once, with a bcrypt hash to store instead" in {
      fast.check(Some(legacy("secret")), "secret") must beLike { case Accepted(Some(rehash)) =>
        rehash must startWith("$2b$04$")
        fast.check(Some(rehash), "secret") === Accepted(None)
      }
      // trimmed before the SHA-1, as before
      fast.check(Some(legacy("secret")), " secret ") must beLike { case Accepted(Some(_)) => ok }
    }

    "not match another password, with nothing to store" in {
      fast.check(Some(legacy("secret")), "Secret") === Rejected
      fast.check(Some(legacy("secret")), "secret2") === Rejected
    }

    "be only 40 lowercase hex characters" in {
      fast.check(Some(legacy("secret").toUpperCase), "secret") === Rejected
      fast.check(Some(legacy("secret").drop(1)), "secret") === Rejected
      fast.check(Some(legacy("secret") + " "), "secret") === Rejected
    }
  }

  "any other stored value" should {
    "never match, not even the password that is its own text" in {
      val garbage = Seq("hash", "password hash", "", " ", "secret", "$2y$04$" + "a" * 53, "$2b$4$" + "a" * 53,
        "$2b$04$" + "a" * 52, "$2b$03$" + "a" * 53)
      garbage.filterNot(g => fast.check(Some(g), g) == Rejected) must beEmpty
      fast.check(Some("hash"), "hash") === Rejected
      fast.check(None, "anything") === Rejected
    }

    "never be checked at a cost that would take minutes" in {
      val cost31 = "$2b$31$" + fast.hash("pw").drop(7)
      val started = System.nanoTime()
      fast.check(Some(cost31), "pw") === Rejected
      (System.nanoTime() - started).nanos must be_<(5.seconds)
    }

    "a blank password never matches, whatever is stored" in {
      fast.check(Some(legacy("")), "") === Rejected
      fast.check(Some(legacy("")), "   ") === Rejected
    }
  }

  "a new password" should {
    def form = Form(single("password" -> text.verifying(PasswordHasher.newPassword)))

    "be at most 72 UTF-8 bytes after trimming, and not blank" in {
      newPasswordError("a" * 72) must beNone
      newPasswordError(" " + "a" * 72 + " ") must beNone
      newPasswordError("a" * 73) === Some("password.too.long")
      newPasswordError("я" * 36) must beNone // 2 bytes each
      newPasswordError("я" * 37) === Some("password.too.long")
      newPasswordError("   ") === Some("password.empty")
      form.bind(Map("password" -> "a" * 73)).errors.map(e => (e.message, e.args)) === Seq(("password.too.long", Seq(72)))
      form.bind(Map("password" -> "a" * 72)).hasErrors must beFalse
    }

    "over 72 bytes (a legacy one only) still sign in, and be rehashed so it alone matches" in {
      val long = "x" * 72 + "the rest of a long legacy password"
      fast.check(Some(legacy(long)), long) must beLike { case Accepted(Some(rehash)) =>
        fast.check(Some(rehash), long) === Accepted(None)
        // not truncated at 72 bytes
        fast.check(Some(rehash), "x" * 72) === Rejected
        fast.check(Some(rehash), "x" * 72 + "the rest of another one") === Rejected
      }
      // the same pre-hash when it is hashed directly
      fast.check(Some(fast.hash(long)), long) === Accepted(None)
      fast.check(Some(fast.hash(long)), "x" * 72) === Rejected
    }
  }

  "the settings" should {
    def settings(text: String) = Settings.from(ConfigFactory.parseString(text))

    "default to cost 12, 2 at once, 10 seconds' wait" in {
      settings("") === Settings(12, 2, 10.seconds)
    }

    "never go below cost 10 unless low costs are allowed (the tests' config only)" in {
      settings("bcrypt.cost = 4").cost === 10
      settings("bcrypt.cost = 9").cost === 10
      settings("bcrypt { cost = 4, allowLowCost = true }").cost === 4
      settings("bcrypt.cost = 13").cost === 13
      settings("bcrypt { cost = 3, allowLowCost = true }") must throwAn[IllegalArgumentException]
      settings("bcrypt.maxConcurrent = 0") must throwAn[IllegalArgumentException]
    }

    "be cost 12 in the shipped config, which doesn't allow low costs, and 4 in the tests'" in {
      val shipped = ConfigFactory.parseFile(new File("conf/application.conf"))
      shipped.hasPath("bcrypt.allowLowCost") must beFalse
      Settings.from(shipped) === Settings(12, 2, 10.seconds)
      PasswordHasher.default.settings.cost === 4
    }
  }

  "the limit on hash computations at once" should {
    implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(java.util.concurrent.Executors.newCachedThreadPool())

    "let at most maxConcurrent run, the others waiting for a turn rather than failing" in {
      val limited = hasher(maxConcurrent = 2)
      val running = new AtomicInteger
      val peak = new AtomicInteger
      val all = Future.sequence((1 to 8).map { _ =>
        Future {
          limited.withPermit(limited.deadline()) {
            val now = running.incrementAndGet()
            peak.accumulateAndGet(now, math.max)
            Thread.sleep(50)
            running.decrementAndGet()
          }
        }
      })
      Await.result(all, 30.seconds).size === 8
      peak.get === 2
      limited.availablePermits === 2
    }

    "fail a check with Busy once its deadline passes, and give the turn back" in {
      val limited = hasher(maxConcurrent = 1, maxWait = 100.millis)
      val holding = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val holder = Future(limited.withPermit(limited.deadline() + 10.seconds.toNanos) {
        holding.countDown()
        release.await(10, TimeUnit.SECONDS)
      })
      holding.await(10, TimeUnit.SECONDS) must beTrue
      val stored = Some(fast.hash("pw"))
      val started = System.nanoTime()
      limited.check(stored, "pw") must throwA[Busy]
      limited.hash("pw") must throwA[Busy]
      (System.nanoTime() - started).nanos must be_<(5.seconds)
      release.countDown()
      Await.result(holder, 10.seconds)
      limited.check(stored, "pw") === Accepted(None)
      limited.availablePermits === 1
    }

    "count the deadline from when a sign-in started, the time queued included" in {
      val limited = hasher(maxConcurrent = 1)
      // a deadline already past gets a turn only if one is free at once
      limited.check(Some(fast.hash("pw")), "pw", System.nanoTime() - 1) === Accepted(None)
      val holding = new CountDownLatch(1)
      val release = new CountDownLatch(1)
      val holder = Future(limited.withPermit(limited.deadline()) { holding.countDown(); release.await(10, TimeUnit.SECONDS) })
      holding.await(10, TimeUnit.SECONDS) must beTrue
      val stored = Some(fast.hash("pw"))
      limited.check(stored, "pw", System.nanoTime() - 1) must throwA[Busy]
      release.countDown()
      Await.result(holder, 10.seconds)
      limited.availablePermits === 1
    }
  }
}
