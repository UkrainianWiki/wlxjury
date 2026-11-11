package db.scalikejdbc

import scalikejdbc._

import java.sql.SQLException

/** The table `session_upgrade_window` (V80): until when a session cookie from before S3 is upgraded
  * in place ([[modules.SessionLifetimeFilter]]).
  */
object SessionUpgradeWindowJdbc {

  /** Seconds from now (the database clock) to the window's end, negative once it has passed; None
    * without the row or the table (V80 undone by `DROP TABLE` or `DELETE`: the window is closed,
    * as [[LegacyPasswordRetirementJdbc.secondsLeft]] treats V81's). `until_at` is a DATETIME written by V80 with the migration connection's
    * NOW(3) and compared here with this connection's: both must use the same time zone (today both
    * use the server's default; a per-connection `time_zone` would shift the window by its offset).
    */
  def secondsLeft()(implicit session: DBSession = AutoSession): Option[Long] =
    try
      sql"SELECT TIMESTAMPDIFF(SECOND, NOW(3), until_at) FROM session_upgrade_window WHERE id = 1"
        .map(_.long(1)).single.apply()
    catch {
      // ER_NO_SUCH_TABLE: the documented undo
      case e: SQLException if e.getErrorCode == 1146 || e.getSQLState == "42S02" => None
    }
}
