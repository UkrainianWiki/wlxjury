package db.scalikejdbc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import org.slf4j.LoggerFactory
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.PlaySpecification
import scalikejdbc._
import services.PasswordHasher

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import scala.jdk.CollectionConverters._
import scala.util.Try

/** How [[User]] stores password hashes: only in place of the hash it read, never logged, and
  * a failed rehash never fails a sign-in.
  */
class UserPasswordSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def legacy(password: String): String =
    MessageDigest.getInstance("SHA-1").digest(password.getBytes(UTF_8)).map(b => f"${b & 0xff}%02x").mkString

  private def stored(id: Long): Option[String] = User.findById(id).flatMap(_.password)

  /** The log events (WARN and up, as the root logger passes them) while `body` runs. */
  private def logged[A](body: => A): (A, Seq[String]) = {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    root.addAppender(appender)
    try {
      val result = body
      (result, appender.list.asScala.toSeq.map(e => e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(" " + _.getMessage)))
    } finally {
      root.detachAppender(appender)
      appender.stop()
    }
  }

  "the rehash on sign-in" should {
    "replace only the hash it read: a password changed meanwhile is kept" in {
      testDbApp { _ =>
        val user = User.create(User("A", "a@example.com", password = Some(legacy("old"))))
        val read = stored(user.getId).get
        User.setPassword(user.getId, "new")
        val changed = stored(user.getId)

        User.replaceHash(user.getId, read, PasswordHasher.default.hash("old")) must beFalse
        stored(user.getId) === changed

        val upgraded = PasswordHasher.default.hash("new")
        User.replaceHash(user.getId, changed.get, upgraded) must beTrue
        stored(user.getId) === Some(upgraded)
      }
    }

    "not fail the sign-in when the hash can't be stored, logging the account id only" in {
      testDbApp { _ =>
        val user = User.create(User("A", "a@example.com", password = Some(legacy("old")))).copy(password = Some(legacy("old")))
        val newHash = PasswordHasher.default.hash("old")
        val (signedIn, lines) = logged {
          User.upgradeHash(user, legacy("old"), newHash)((_, _, _) => throw new java.sql.SQLException(s"lock wait $newHash"))
        }
        signedIn.id === user.id
        signedIn.password === Some(legacy("old"))
        lines.filter(_.contains("password hash not upgraded")) must haveSize(1)
        lines.filter(_.contains(s"accountId=${user.getId}")) must haveSize(1)
        lines.filter(_.contains(newHash)) must beEmpty
        stored(user.getId) === Some(legacy("old"))
      }
    }
  }

  "a statement that writes a hash" should {
    // too long for users.password varchar(255): MariaDB's strict mode refuses it
    val marker = "MARKER-" + "x" * 300

    "log neither the statement nor its failure, with the hash in it" in {
      testDbApp { _ =>
        val user = User.create(User("A", "a@example.com", password = Some("hash")))

        // the check itself: with ScalikeJDBC's error logging on (S5 turns it off in the app's
        // config), an ordinary statement that fails logs the value
        val (control, controlLines) = logged {
          val before = GlobalSettings.loggingSQLErrors
          GlobalSettings.loggingSQLErrors = true
          try Try(sql"UPDATE users SET password = $marker WHERE id = ${user.getId}".update.apply()(AutoSession))
          finally GlobalSettings.loggingSQLErrors = before
        }
        control.isFailure must beTrue
        controlLines.exists(_.contains("MARKER-")) must beTrue

        val (failed, lines) = logged(Try(User.setPasswordHash(user.getId, marker)))
        failed.isFailure must beTrue
        failed.failed.get.getMessage must not(contain("MARKER-"))
        lines.filter(_.contains("MARKER-")) must beEmpty

        val (replaced, replaceLines) = logged(Try(User.replaceHash(user.getId, "hash", marker)))
        replaced.isFailure must beTrue
        replaceLines.filter(_.contains("MARKER-")) must beEmpty

        val (created, createLines) = logged(Try(User.create(User("B", "b@example.com", password = Some(marker)))))
        created.isFailure must beTrue
        createLines.filter(_.contains("MARKER-")) must beEmpty

        stored(user.getId) === Some("hash")
      }
    }
  }

  "the pre-release checks of stored values" should {
    "count, case-sensitively, the values that never match and the legacy hashes, on MariaDB" in {
      testDbApp { _ =>
        val bcrypt = PasswordHasher.default.hash("pw")
        val values = Seq(legacy("a"), legacy("b").toUpperCase, bcrypt, bcrypt.replace("$2b$", "$2B$"),
          "$2y$04$" + bcrypt.drop(7), "hash", "")
        values.zipWithIndex.foreach { case (p, i) => User.create(User(s"U$i", s"u$i@example.com", password = Some(p))) }
        User.create(User("None", "none@example.com"))

        // the queries in the B4 results (98a8a79e:docs/plans/2026-11-01-open-followups.md)
        val neverMatch = DB.readOnly { implicit s =>
          SQL("""SELECT COUNT(*) FROM users WHERE password IS NOT NULL AND password NOT REGEXP BINARY '^([0-9a-f]{40}|\\$2[ab]\\$[0-9]{2}\\$[./A-Za-z0-9]{53})$'""")
            .map(_.long(1)).single.apply()
        }
        val legacyCount = DB.readOnly { implicit s =>
          SQL("""SELECT COUNT(*) FROM users WHERE password REGEXP BINARY '^[0-9a-f]{40}$'""").map(_.long(1)).single.apply()
        }
        // uppercase hex, $2B$, $2y$, "hash" and ""
        neverMatch === Some(5L)
        legacyCount === Some(1L)
        values.count(v => PasswordHasher.default.check(Some(v), "a") != PasswordHasher.Rejected) === 1
      }
    }
  }

  "an invalid bcrypt setting" should {
    "fail the app's start" in {
      try {
        val failure = Try(new GuiceApplicationBuilder().configure("bcrypt.cost" -> "99").build()).failed.toOption
        failure must beSome
        Iterator.iterate(failure.orNull)(_.getCause).takeWhile(_ != null).map(_.getMessage).mkString(" ") must
          contain("bcrypt.cost must be between 4 and 16, was 99")
      } finally SharedTestDb.reregisterDefault()
    }
  }
}
