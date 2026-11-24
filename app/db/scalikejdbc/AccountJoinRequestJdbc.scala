package db.scalikejdbc

import scalikejdbc._

/** The `account_join_request` table (V106, the account join plan): a user's request to join two
  * accounts and its outcome (J-2, J-3), or a performer's "Keep separate" on a pair the duplicates
  * report lists (J-1, [[services.DuplicateAccounts]]). A pair is stored ordered, the lower id
  * first; at most one `kept_apart` row per pair (its unique key).
  */
object AccountJoinRequestJdbc {

  val Pending = "pending"
  val Done = "done"
  val Refused = "refused"
  val Cancelled = "cancelled"
  val KeptApart = "kept_apart"

  /** The states V106's check allows. */
  val States: Seq[String] = Seq(Pending, Done, Refused, Cancelled, KeptApart)

  /** The pair as stored: the lower id first. */
  def pair(a: Long, b: Long): (Long, Long) = if (a < b) (a, b) else (b, a)

  /** The pairs kept apart of which both accounts are among `ids`, each as [[pair]] orders it. */
  def keptApartAmong(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[(Long, Long)] =
    if (ids.size < 2) Set.empty
    else {
      val among = ids.distinct
      sql"""SELECT first_account_id, second_account_id FROM account_join_request
            WHERE state = $KeptApart AND first_account_id IN ($among) AND second_account_id IN ($among)"""
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().toSet
    }

  /** Records that `actorId` keeps the two accounts apart (in `contestId`; None: root's full
    * report), unless the pair is kept apart already; whether a row was written. The caller holds
    * both accounts' users rows ([[services.DuplicateAccounts.keepSeparate]]), so two performers'
    * requests for one pair take turns. A plain (non-locking) read, then a plain insert: an
    * `INSERT ... SELECT ... WHERE NOT EXISTS` would take shared next-key locks on the unique
    * key's gap, which two transactions can then deadlock on. The unique key is the backstop: a
    * duplicate key (a row written meanwhile) counts as not written.
    */
  def keepApart(a: Long, b: Long, actorId: Long, contestId: Option[Long])(implicit session: DBSession): Boolean = {
    val (first, second) = pair(a, b)
    require(first != second, "a pair of two accounts")
    val kept = sql"""SELECT 1 FROM account_join_request
                     WHERE first_account_id = $first AND second_account_id = $second AND state = $KeptApart
                     LIMIT 1""".map(_.int(1)).single.apply().nonEmpty
    !kept && {
      try {
        sql"""INSERT INTO account_join_request
                (first_account_id, second_account_id, state, requested_by, decided_by, contest_id, decided_at)
              VALUES ($first, $second, $KeptApart, $actorId, $actorId, $contestId, NOW(3))""".update.apply() > 0
      } catch {
        // only the pair's unique key (ER_DUP_ENTRY, 1062): a row written meanwhile; any other
        // integrity error (a foreign key, a CHECK) is a bug and propagates
        case e: java.sql.SQLIntegrityConstraintViolationException if e.getErrorCode == 1062 => false
      }
    }
  }

  /** The non-deleted accounts with a wiki account (not blank): id and the name as stored; root
    * accounts only if `withRoot`. One scan of `users` (about 2,800 rows in production), for the
    * report to group by the normalised name.
    */
  def wikiAccounts(withRoot: Boolean)(implicit session: DBSession = AutoSession): Seq[(Long, String)] = {
    // root's only, as root accounts: the series admins (contest series plan §3.3, protected targets)
    val rootShown = if (withRoot) sqls"TRUE" else sqls"is_root = FALSE AND NOT ${AccountJoinRequestJdbc.liveGrant(sqls"users.id")}"
    sql"""SELECT id, wiki_account FROM users
          WHERE deleted_at IS NULL AND wiki_account IS NOT NULL AND TRIM(wiki_account) <> '' AND $rootShown"""
      .map(rs => rs.long(1) -> rs.string(2)).list.apply()
  }

  /** The accounts that share a verified email: (the address, the account that proved it, an
    * account with a linked Google or Wikimedia sign-in with that address). Two accounts can't
    * hold one address (`users.email` is unique), so a shared address is one account's verified
    * address on another's sign-in. An address an admin set is unverified (V96). Non-deleted
    * accounts only; root accounts only if `withRoot`.
    */
  def sharedVerifiedEmails(withRoot: Boolean)(implicit session: DBSession = AutoSession): Seq[(String, Long, Long)] = {
    val rootShown =
      if (withRoot) sqls"TRUE"
      else sqls"""a.is_root = FALSE AND b.is_root = FALSE AND NOT ${AccountJoinRequestJdbc.liveGrant(sqls"a.id")}
                  AND NOT ${AccountJoinRequestJdbc.liveGrant(sqls"b.id")}"""
    sql"""SELECT a.email, a.id, b.id FROM users a
          JOIN user_identity i ON i.email = a.email AND i.unlinked_at IS NULL AND i.user_id <> a.id
          JOIN users b ON b.id = i.user_id
          WHERE a.deleted_at IS NULL AND a.email_verified_at IS NOT NULL AND a.email_set_by_admin_at IS NULL
            AND b.deleted_at IS NULL AND $rootShown"""
      .map(rs => (rs.string(1), rs.long(2), rs.long(3))).list.apply()
  }

  /** Whether the account `id` holds a live series grant (a protected target, contest series plan
    * §3.3): `EXISTS (…)` on its id column.
    */
  private[scalikejdbc] def liveGrant(id: SQLSyntax): SQLSyntax =
    sqls"""EXISTS (SELECT 1 FROM series_admin sa JOIN series s ON s.id = sa.series_id AND s.retired_at IS NULL
                   WHERE sa.user_id = $id AND ${SeriesAdminJdbc.liveGrantCondition(sqls"sa")})"""

  /** The accounts' users rows, locked until the transaction ends, in ascending id (the account
    * join plan's lock order: `users` before `user_contest` and everything else).
    */
  def lockAccounts(ids: Seq[Long])(implicit session: DBSession): Seq[User] =
    ids.distinct.sorted.flatMap(User.lockRow)
}
