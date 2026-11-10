package db.scalikejdbc

import scalikejdbc._

/** Sets a round's legacy `jury_org_view` column, as a round from before 26.11 may have it set
  * by hand. The app no longer reads it ([[Round]]): its jurors get the juror view.
  */
object LegacyJuryOrgView {
  def set(roundId: Long): Unit =
    DB.autoCommit { implicit s =>
      sql"UPDATE round SET jury_org_view = 1 WHERE id = $roundId".update.apply()
    }
}
