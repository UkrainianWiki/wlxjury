package db.scalikejdbc

import scalikejdbc._

import java.time.{Instant, ZoneId, ZonedDateTime}

/** Reading a TIMESTAMP column as the instant it stores, whatever the JVM's and the database
  * session's time zones.
  *
  * A TIMESTAMP read by JDBC (`rs.timestamp`, `rs.zonedDateTime`) is the session time zone's wall
  * time taken in the JVM's zone: wrong by their difference whenever they differ (2 h for a UTC
  * database session read by a Kyiv JVM, or the other way round), for every value the database
  * wrote (`NOW(3)`, a column default). `UNIX_TIMESTAMP` of a TIMESTAMP column is its stored
  * instant in seconds since the epoch, exact to the millisecond, whatever either zone, so the
  * readers select it ([[epoch]]) and read it ([[instant]], [[instantOpt]]). Nothing written
  * changes: the stored values keep their meaning, only their reading is zone-independent.
  *
  * When both zones are the same (as on a server whose JVM and database use the system zone)
  * this reads exactly what a JDBC timestamp read did.
  */
object DbTime {

  private val Column = "[a-z_]+(\\.[a-z_]+)?".r

  /** The label [[epoch]] selects `column` as: its name (without a table alias) and `_epoch`. */
  def label(column: String): String = column.substring(column.lastIndexOf('.') + 1) + "_epoch"

  /** `UNIX_TIMESTAMP(column) AS <column>_epoch` ([[label]]), for [[instant]] and [[instantOpt]].
    * `column` is a column name in code, optionally with its table alias, never request text.
    */
  def epoch(column: String): SQLSyntax = {
    require(Column.matches(column), s"not a column name: $column")
    SQLSyntax.createUnsafely(s"UNIX_TIMESTAMP($column) AS ${label(column)}")
  }

  /** An instant from seconds since the epoch, as `UNIX_TIMESTAMP` gives them. */
  def fromEpochSeconds(seconds: java.math.BigDecimal): Instant =
    Instant.ofEpochMilli(seconds.movePointRight(3).setScale(0, java.math.RoundingMode.HALF_UP).longValueExact())

  /** The instant of `column`, selected with [[epoch]]. */
  def instant(rs: WrappedResultSet, column: String): Instant = fromEpochSeconds(rs.bigDecimal(label(column)))

  /** The instant of `column`, selected with [[epoch]], if not NULL. */
  def instantOpt(rs: WrappedResultSet, column: String): Option[Instant] =
    rs.bigDecimalOpt(label(column)).map(fromEpochSeconds)

  /** The instant from `UNIX_TIMESTAMP` at a position, if not NULL. */
  def instantOpt(rs: WrappedResultSet, index: Int): Option[Instant] = rs.bigDecimalOpt(index).map(fromEpochSeconds)

  /** As a time in the JVM's zone: what a JDBC read gave for a ZonedDateTime, for views that show
    * the local date or time.
    */
  def local(at: Instant): ZonedDateTime = at.atZone(ZoneId.systemDefault())
}
