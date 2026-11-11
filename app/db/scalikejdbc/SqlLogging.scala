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
    * an escaped line break included). This assumes that default: under `NO_BACKSLASH_ESCAPES` a
    * literal ending in a backslash (`'a\'`) would run on to the next quote, and part of a literal
    * could be logged; the app never sets that mode.
    *
    * The pattern is unrolled and possessive, so a long literal costs no recursion; should the
    * regex engine still overflow its stack, the line gets [[NotShown]] instead of the statement.
    */
  def template(statement: String): String =
    try statement.replaceAll(QuotedLiteral, "'?'").replaceAll("\\s+", " ").trim
    catch { case _: StackOverflowError => NotShown }

  /** A quoted literal: plain characters, then any number of (an escaped character or a doubled
    * quote, then plain characters), all possessive.
    */
  private val QuotedLiteral = """'[^'\\]*+(?:(?:\\[\s\S]|'')[^'\\]*+)*+'"""

  /** What [[template]] gives for a statement it could not mask. */
  val NotShown = "(statement not shown)"

  /** An exception without its message, which may quote a value ("Duplicate entry '…'"): its
    * class, and the SQLState and vendor code of it or of the first SQLException among its causes
    * (a few levels down: a wrapper's).
    */
  def describe(e: Throwable): String = {
    def sqlOf(t: Throwable, depth: Int): Option[java.sql.SQLException] = t match {
      case null                       => None
      case sql: java.sql.SQLException => Some(sql)
      case _ if depth >= 8            => None
      case _                          => sqlOf(t.getCause, depth + 1)
    }
    def codes(sql: java.sql.SQLException) =
      s"${sql.getClass.getName}, SQLState ${sql.getSQLState}, code ${sql.getErrorCode}"
    sqlOf(e, 0) match {
      case Some(sql) if sql eq e => codes(sql)
      case Some(sql)             => s"${e.getClass.getName} caused by ${codes(sql)}"
      case None                  => e.getClass.getName
    }
  }
}
