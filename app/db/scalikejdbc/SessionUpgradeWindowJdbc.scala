package db.scalikejdbc

import scalikejdbc._

/** The table `session_upgrade_window` (V80): until when a session cookie from before S3 is upgraded
  * in place ([[modules.SessionLifetimeFilter]]).
  */
object SessionUpgradeWindowJdbc {

  /** Seconds from now (the database clock) to the window's end, negative once it has passed; None
    * without the row.
    */
  def secondsLeft()(implicit session: DBSession = AutoSession): Option[Long] =
    sql"SELECT TIMESTAMPDIFF(SECOND, NOW(3), until_at) FROM session_upgrade_window WHERE id = 1"
      .map(_.long(1)).single.apply()
}
