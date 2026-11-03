package db.scalikejdbc

import scalikejdbc._
import services.PasswordHasher

import java.sql.SQLException
import scala.util.control.NonFatal

/** The grace period of legacy SHA-1 password hashes (P8, V81: `legacy_password_retirement`, 3
  * months from the S6 deploy) and their replacement after it.
  */
object LegacyPasswordRetirementJdbc {

  /** A legacy hash as stored ([[PasswordHasher.isLegacy]]; any case, as MariaDB compares here). */
  private val LegacyPattern = "^[0-9a-f]{40}$"

  /** A chunk of [[retire]] failed after `replaced` hashes were replaced (and recorded) by the
    * chunks before it; the cause is the chunk's error.
    */
  final class RetireFailed(val replaced: Int, cause: Throwable) extends Exception(cause)

  /** Seconds from now (the database clock) to the end of the grace period, negative once it has
    * passed; None without the row or the table (V81 undone by `DROP TABLE` or `DELETE`: never
    * over). `retire_at` is a DATETIME written by V81 with the migration connection's NOW(3),
    * compared with this connection's (both use the server's default time zone, as V80's window
    * does).
    */
  def secondsLeft()(implicit session: DBSession = AutoSession): Option[Long] =
    try
      sql"SELECT TIMESTAMPDIFF(SECOND, NOW(3), retire_at) FROM legacy_password_retirement WHERE id = 1"
        .map(_.long(1)).single.apply()
    catch {
      // ER_NO_SUCH_TABLE: the documented undo
      case e: SQLException if e.getErrorCode == 1146 || e.getSQLState == "42S02" => None
    }

  /** Whether the grace period is over: a legacy hash no longer signs in ([[User.login]]). */
  def over()(implicit session: DBSession = AutoSession): Boolean = secondsLeft().exists(_ <= 0)

  /** If the grace period is over, replaces every legacy hash by [[PasswordHasher.RetiredLegacy]]
    * and records the accounts in `legacy_password_retired` (organizers see them in the users
    * list and invite them again; root can send a password link); the number replaced. The
    * accounts are read without locks, then each chunk is replaced in a transaction of its own,
    * only where the hash is still a legacy one (a sign-in that rehashed it meanwhile wins), and
    * exactly those rows are recorded. Sessions are kept (see the plan's S6 review fixes). Quiet
    * ([[User.quietTx]]): the statements read password hashes.
    */
  def retire(): Int =
    if (!over()) 0
    else {
      val ids = User.quietTx(implicit s =>
        sql"SELECT id FROM users WHERE password REGEXP $LegacyPattern".map(_.long(1)).list.apply())
      ids.grouped(500).foldLeft(0) { (done, chunk) =>
        done + (try User.quietTx { implicit session =>
          val replaced = sql"""UPDATE users SET password = ${PasswordHasher.RetiredLegacy}
                                WHERE id IN ($chunk) AND password REGEXP $LegacyPattern""".update.apply()
          if (replaced > 0)
            sql"""INSERT IGNORE INTO legacy_password_retired (user_id, retired_at)
                  SELECT id, NOW(3) FROM users WHERE id IN ($chunk) AND password = ${PasswordHasher.RetiredLegacy}"""
              .update.apply()
          replaced
        } catch { case NonFatal(e) => throw new RetireFailed(done, e) })
      }
    }

  /** The accounts whose legacy hash was replaced ([[retire]]), by id. */
  def retiredAccounts()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT user_id FROM legacy_password_retired ORDER BY user_id".map(_.long(1)).list.apply()
}
