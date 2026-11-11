package db.scalikejdbc

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.classic.{Level, Logger => LogbackLogger}
import ch.qos.logback.core.read.ListAppender
import com.typesafe.config.ConfigFactory
import org.slf4j.LoggerFactory
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

import java.io.File
import scala.jdk.CollectionConverters._

/** N11: a slow or failed statement is logged at WARN/ERROR without its bound values; the
  * value-printing ScalikeJDBC warnings are off in the shipped config.
  */
class SqlLoggingSpec extends Specification with BeforeAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val Secret = "sql-logging-secret@example.com"

  /** Every event at WARN or above while `f` runs, from any logger, and `db.sql`'s. */
  private def logged[T](f: => T): Seq[ILoggingEvent] = {
    val root = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[LogbackLogger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    root.addAppender(appender)
    val saved = (GlobalSettings.loggingSQLAndTime, GlobalSettings.loggingSQLErrors,
      GlobalSettings.taggedQueryCompletionListener, GlobalSettings.taggedQueryFailureListener)
    val savedSlowMillis = SqlLogging.slowStatementMillis
    try {
      // as before the fix: a 0 ms threshold, so the statement is slow, and every warning on
      GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = true, logLevel = "debug",
        warningEnabled = true, warningThresholdMillis = 0, warningLogLevel = "warn")
      GlobalSettings.loggingSQLErrors = true
      SqlLogging.install(slowStatementMillis = 0)
      scala.util.Try(f)
      appender.list.asScala.toList.filter(_.getLevel.isGreaterOrEqual(Level.WARN))
    } finally {
      root.detachAppender(appender)
      SqlLogging.install(savedSlowMillis) // the threshold as it was; then the settings and listeners
      GlobalSettings.loggingSQLAndTime = saved._1
      GlobalSettings.loggingSQLErrors = saved._2
      GlobalSettings.taggedQueryCompletionListener = saved._3
      GlobalSettings.taggedQueryFailureListener = saved._4
    }
  }

  private def text(e: ILoggingEvent): String =
    e.getFormattedMessage + Option(e.getThrowableProxy).fold("")(t => " " + t.getMessage)

  "a slow statement" should {
    "be logged at WARN as its template, without the bound value" in {
      val events = logged(DB.readOnly { implicit s =>
        sql"SELECT id FROM users WHERE email = $Secret".map(_.long(1)).list.apply()
      })
      val slow = events.filter(e => e.getLoggerName == SqlLogging.LoggerName && e.getLevel == Level.WARN)
      (slow.map(_.getFormattedMessage) must contain(contain("SELECT id FROM users WHERE email = ?"))) and
        (events.map(text) must not(contain(contain(Secret))))
    }
  }

  "a failed statement" should {
    "be logged at ERROR as its template and the exception's class, without the bound value" in {
      val events = logged(DB.readOnly { implicit s =>
        sql"SELECT no_such_column FROM users WHERE email = $Secret".map(_.long(1)).list.apply()
      })
      val failed = events.filter(e => e.getLoggerName == SqlLogging.LoggerName && e.getLevel == Level.ERROR)
      (failed.map(_.getFormattedMessage) must contain(
        contain("(java.sql.") and contain("SQLState 42S22") and contain("SELECT no_such_column FROM users WHERE email = ?"))) and
        (events.map(text) must not(contain(contain(Secret))))
    }
  }

  "a statement's template" should {
    "lose quoted literals and line breaks" in {
      SqlLogging.template("SELECT *\n  FROM users WHERE email = 'a@b.c' AND name = 'O''Brien' AND id = ?") ===
        "SELECT * FROM users WHERE email = '?' AND name = '?' AND id = ?"
    }

    "lose a literal with a backslash-escaped quote whole (R227-2)" in {
      // the SQL text: email = 'a\'b@x.c'
      SqlLogging.template("SELECT * FROM users WHERE email = 'a\\'b@x.c' AND id = ?") ===
        "SELECT * FROM users WHERE email = '?' AND id = ?"
    }

    "lose a literal with a backslash before a line break whole (R227-2)" in {
      // the SQL text: name = 'secret\<line break>value'
      SqlLogging.template("SELECT * FROM users WHERE name = 'secret\\\nvalue' AND id = ?") ===
        "SELECT * FROM users WHERE name = '?' AND id = ?"
    }

    "lose an empty literal, and literals of only escapes or doubled quotes" in {
      // the SQL text: a = '' AND b = '\\' AND c = '''' AND d = 'x\'''y'
      SqlLogging.template("SELECT 1 WHERE a = '' AND b = '\\\\' AND c = '''' AND d = 'x\\'''y'") ===
        "SELECT 1 WHERE a = '?' AND b = '?' AND c = '?' AND d = '?'"
    }

    "mask a 10,000-character literal without a stack overflow" in {
      val long = "x\\'" * 2500 + "y''" * 900 + "z" * 300
      SqlLogging.template(s"SELECT * FROM comment WHERE body = '$long' AND id = ?") ===
        "SELECT * FROM comment WHERE body = '?' AND id = ?"
    }
  }

  "an exception's description" should {
    "give the SQLState and code of a wrapped SQLException, never a message" in {
      val sql = new java.sql.SQLIntegrityConstraintViolationException("Duplicate entry 'a@b.c'", "23000", 1062)
      val wrapped = new RuntimeException("wrapper: Duplicate entry 'a@b.c'", new IllegalStateException(sql))
      SqlLogging.describe(sql) === "java.sql.SQLIntegrityConstraintViolationException, SQLState 23000, code 1062"
      SqlLogging.describe(wrapped) ===
        "java.lang.RuntimeException caused by java.sql.SQLIntegrityConstraintViolationException, SQLState 23000, code 1062"
      SqlLogging.describe(new IllegalStateException("secret")) === "java.lang.IllegalStateException"
    }
  }

  "the shipped config" should {
    "turn off ScalikeJDBC's value-printing warning and failure log" in {
      Seq("conf/application.conf", "test/resources/application.conf").map { path =>
        val c = ConfigFactory.parseFile(new File(path))
        (c.getBoolean("scalikejdbc.global.loggingSQLAndTime.warningEnabled"),
          c.getBoolean("scalikejdbc.global.loggingSQLErrors"), c.getLong(SqlLogging.SlowMillisKey))
      } === Seq((false, false, 1000L), (false, false, 1000L))
    }
  }
}
