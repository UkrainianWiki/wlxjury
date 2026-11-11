package db.scalikejdbc

import org.slf4j.{Logger, LoggerFactory}
import scalikejdbc.GlobalSettings

/** The SQL lines kept in production logs (WARN and ERROR): a slow statement and a failed one,
  * each without its bound values (N11). ScalikeJDBC's own slow-statement warning
  * (`loggingSQLAndTime.warningEnabled`) and failure log (`loggingSQLErrors`) print the
  * statement with its values (emails of sign-in lookups, names, comment text), so both are
  * off; these listeners log the statement's template instead, its quoted literals replaced.
  *
  * The per-statement log at `loggingSQLAndTime.logLevel` (INFO, with values, for `sbt run`)
  * is unchanged; production's logback writes WARN and up only. A failure's exception still
  * reaches whoever handles it; this line gives only its class, SQLState and vendor code.
  *
  * Uses the tagged listeners, which nothing else sets, so installing again replaces them
  * and [[modules.RequestTimingFilter]]'s untagged one is left alone.
  */
object SqlLogging {

  /** Logger of the lines: WARN for a slow statement, ERROR for a failed one. */
  val LoggerName = "db.sql"

  /** `wlxjury.sql.slowStatementMillis`: from this many milliseconds a statement is slow. */
  val SlowMillisKey = "wlxjury.sql.slowStatementMillis"
  val DefaultSlowMillis = 1000L

  private val logger: Logger = LoggerFactory.getLogger(LoggerName)

  @volatile private var slowMillis: Long = DefaultSlowMillis

  /** The current threshold ([[install]]'s). */
  def slowStatementMillis: Long = slowMillis

  def install(slowStatementMillis: Long = DefaultSlowMillis): Unit = {
    slowMillis = slowStatementMillis
    GlobalSettings.loggingSQLAndTime = GlobalSettings.loggingSQLAndTime.copy(warningEnabled = false)
    GlobalSettings.loggingSQLErrors = false
    GlobalSettings.taggedQueryCompletionListener = completed
    GlobalSettings.taggedQueryFailureListener = failed
  }

  private val completed: (String, scala.collection.Seq[Any], Long, scala.collection.Seq[String]) => Unit =
    (statement, _, millis, _) =>
      if (millis >= slowMillis) logger.warn(s"Slow SQL statement ($millis ms): ${template(statement)}")

  private val failed: (String, scala.collection.Seq[Any], Throwable, scala.collection.Seq[String]) => Unit =
    (statement, _, e, _) => logger.error(s"SQL statement failed (${describe(e)}): ${template(statement)}")

  /** The statement on one line, its quoted literals (if a fragment was built with
    * `createUnsafely`) as `'?'`; bound values are `?` already. A literal ends at a quote that is
    * neither doubled nor after a backslash (MariaDB's default `sql_mode` keeps backslash escapes;
    * an escaped line break included).
    */
  def template(statement: String): String =
    statement.replaceAll("""'(?:[^'\\]|\\[\s\S]|'')*'""", "'?'").replaceAll("\\s+", " ").trim

  /** An exception without its message, which may quote a value ("Duplicate entry '…'"). */
  def describe(e: Throwable): String = e match {
    case sql: java.sql.SQLException =>
      s"${sql.getClass.getName}, SQLState ${sql.getSQLState}, code ${sql.getErrorCode}"
    case other => other.getClass.getName
  }
}
