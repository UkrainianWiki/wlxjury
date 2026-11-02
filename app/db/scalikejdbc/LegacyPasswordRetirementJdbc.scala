package db.scalikejdbc

import scalikejdbc._
import services.PasswordHasher

/** The grace period of legacy SHA-1 password hashes (P8, V81: `legacy_password_retirement`, 3
  * months from the S6 deploy) and their replacement after it.
  */
object LegacyPasswordRetirementJdbc {

  /** A legacy hash as stored ([[PasswordHasher.isLegacy]]; any case, as MariaDB compares here). */
  private val LegacyPattern = "^[0-9a-f]{40}$"

  /** Seconds from now (the database clock) to the end of the grace period, negative once it has
    * passed; None without the row (V81 undone: never over). `retire_at` is a DATETIME written by
    * V81 with the migration connection's NOW(3), compared with this connection's (both use the
    * server's default time zone, as V80's window does).
    */
  def secondsLeft()(implicit session: DBSession = AutoSession): Option[Long] =
    sql"SELECT TIMESTAMPDIFF(SECOND, NOW(3), retire_at) FROM legacy_password_retirement WHERE id = 1"
      .map(_.long(1)).single.apply()

  /** Whether the grace period is over: a legacy hash no longer signs in ([[User.login]]). */
  def over()(implicit session: DBSession = AutoSession): Boolean = secondsLeft().exists(_ <= 0)

  /** If the grace period is over, replaces every legacy hash by [[PasswordHasher.RetiredLegacy]]
    * and records the accounts in `legacy_password_retired` (for root to send them password
    * links), in one transaction with the rows locked; the number replaced. Their sessions are
    * kept (an active session proves nothing about the hash; its owner sets a password through
    * a link when it ends). Quiet ([[User.quietTx]]): the statements read password hashes.
    */
  def retire(): Int =
    User.quietTx { implicit session =>
      if (!over()) 0
      else {
        val ids = sql"SELECT id FROM users WHERE password REGEXP $LegacyPattern FOR UPDATE".map(_.long(1)).list.apply()
        if (ids.isEmpty) 0
        else {
          sql"""INSERT IGNORE INTO legacy_password_retired (user_id, retired_at)
                SELECT id, NOW(3) FROM users WHERE password REGEXP $LegacyPattern""".update.apply()
          sql"UPDATE users SET password = ${PasswordHasher.RetiredLegacy} WHERE password REGEXP $LegacyPattern"
            .update.apply()
        }
      }
    }

  /** The accounts whose legacy hash was replaced ([[retire]]), by id. */
  def retiredAccounts()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT user_id FROM legacy_password_retired ORDER BY user_id".map(_.long(1)).list.apply()
}
