package db.scalikejdbc

import scalikejdbc._

import java.time.Instant

/** A grant of "Series admin" (table `series_admin`, V112; contest series plan §3.3, PR CY-2).
  *
  * @param adminFromYear the start year (CA20): derived Admin, with account reach (CA17), in the
  *                      series' live contests with year >= it; derived Organizer in older ones
  * @param grantedBy     root or a series admin of the series (CA19, CA22); the chained revoke
  *                      follows it
  * @param revokedByRoot a grant root revoked is granted again by root only
  */
final case class SeriesGrant(
    id: Long,
    seriesId: Long,
    userId: Long,
    adminFromYear: Int,
    grantedBy: Long,
    grantedAt: Instant,
    revokedBy: Option[Long],
    revokedAt: Option[Instant],
    revokedByRoot: Boolean,
    comment: Option[String],
    confirmNeededAt: Option[Instant] = None
) {
  def revoked: Boolean = revokedAt.isDefined

  /** Waiting for root's confirmation since its series was un-retired (the maintainer's decision of
    * 2026-11-24): no access and no protection, as a revoked grant, until root confirms it.
    */
  def toConfirm: Boolean = confirmNeededAt.isDefined && !revoked

  /** Neither revoked nor waiting for confirmation (live if its series is live and its account not
    * deleted too).
    */
  def active: Boolean = !revoked && confirmNeededAt.isEmpty
}

/** What a live grant gives its account in one contest of its series (the session's reach,
  * [[controllers.Secured.withSeries]]): derived Admin from the grant's start year on, derived
  * Organizer below it (CA20).
  */
final case class SeriesContest(contestId: Long, seriesId: Long, year: Int, role: String) {
  def admin: Boolean = role == User.ADMIN_ROLE
}

/** The `series_admin` table (V112). A grant is live while it is not revoked, its series is live and
  * its account is not deleted; only live grants give access or protection. Every write goes through
  * [[services.series.SeriesAdmins]], which locks the series row first and logs it.
  */
object SeriesAdminJdbc {

  val CommentMaxLength = 500

  private val columns =
    sqls"""sa.id, sa.series_id, sa.user_id, sa.admin_from_year, sa.granted_by, ${DbTime.epoch("sa.granted_at")},
           sa.revoked_by, ${DbTime.epoch("sa.revoked_at")}, sa.revoked_by_root, sa.comment,
           ${DbTime.epoch("sa.confirm_needed_at")}"""

  private def extract(rs: WrappedResultSet): SeriesGrant =
    SeriesGrant(
      id = rs.long("id"),
      seriesId = rs.long("series_id"),
      userId = rs.long("user_id"),
      adminFromYear = rs.int("admin_from_year"),
      grantedBy = rs.long("granted_by"),
      grantedAt = DbTime.instant(rs, "sa.granted_at"),
      revokedBy = rs.longOpt("revoked_by"),
      revokedAt = DbTime.instantOpt(rs, "sa.revoked_at"),
      revokedByRoot = rs.boolean("revoked_by_root"),
      comment = rs.stringOpt("comment"),
      confirmNeededAt = DbTime.instantOpt(rs, "sa.confirm_needed_at")
    )

  /** A grant that counts (`alias`, a `series_admin` alias): neither revoked nor waiting for root's
    * confirmation. The one condition every read of live grants uses, here and in [[CoiMembers]] and
    * [[AccountJoinRequestJdbc]] (a guard spec): an unconfirmed grant is exactly as a revoked one.
    */
  def liveGrantCondition(alias: SQLSyntax): SQLSyntax =
    sqls"$alias.revoked_at IS NULL AND $alias.confirm_needed_at IS NULL"

  /** A live grant: [[liveGrantCondition]], its series live, its account not deleted. */
  private val liveJoin =
    sqls"""JOIN series s ON s.id = sa.series_id AND s.retired_at IS NULL AND ${liveGrantCondition(sqls"sa")}
           JOIN users u ON u.id = sa.user_id AND u.deleted_at IS NULL"""

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[SeriesGrant] =
    sql"SELECT $columns FROM series_admin sa WHERE sa.id = $id".map(extract).single.apply()

  /** Every grant of the series, revoked ones too, the oldest first (root's view). */
  def findBySeries(seriesId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    sql"SELECT $columns FROM series_admin sa WHERE sa.series_id = $seriesId ORDER BY sa.granted_at, sa.id"
      .map(extract).list.apply()

  /** The live grants of the series (its series admins), the oldest first. None of a retired series. */
  def liveBySeries(seriesId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa $liveJoin
          WHERE sa.series_id = $seriesId ORDER BY sa.granted_at, sa.id"""
      .map(extract).list.apply()

  /** The live grants of the series, in one query (a move's confirmation, §3.5). */
  def liveInSeries(seriesIds: Seq[Long])(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    if (seriesIds.isEmpty) Nil
    else
      sql"""SELECT $columns FROM series_admin sa $liveJoin
            WHERE sa.series_id IN (${seriesIds.distinct}) ORDER BY sa.granted_at, sa.id""".map(extract).list.apply()

  /** The account's live grants, one per series at most (the key). */
  def liveByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa $liveJoin
          WHERE sa.user_id = $userId ORDER BY sa.series_id"""
      .map(extract).list.apply()

  /** The account's live grant on the series, if any. */
  def liveGrant(userId: Long, seriesId: Long)(implicit session: DBSession = AutoSession): Option[SeriesGrant] =
    liveByUser(userId).find(_.seriesId == seriesId)

  /** The account's grant on the series not revoked, whatever the series' or account's state (the
    * key's row: a second can't be added beside it).
    */
  def unrevoked(userId: Long, seriesId: Long)(implicit session: DBSession): Option[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa
          WHERE sa.user_id = $userId AND sa.series_id = $seriesId AND sa.revoked_at IS NULL FOR UPDATE"""
      .map(extract).single.apply()

  /** Whether the account's latest revoked grant on the series was revoked by root (CA19: then only
    * root grants it again).
    */
  def lastRevokedByRoot(userId: Long, seriesId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT revoked_by_root FROM series_admin
          WHERE user_id = $userId AND series_id = $seriesId AND revoked_at IS NOT NULL
          ORDER BY revoked_at DESC, id DESC LIMIT 1""".map(_.boolean(1)).single.apply().getOrElse(false)

  /** The live grants of the series added in the last `hours` hours, the newest first (root's
    * 24-hour view, CA22).
    */
  def recentBySeries(seriesIds: Seq[Long], hours: Int)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    if (seriesIds.isEmpty) Nil
    else
      sql"""SELECT $columns FROM series_admin sa
            WHERE sa.series_id IN (${seriesIds.distinct}) AND sa.granted_at > NOW(3) - INTERVAL $hours HOUR
            ORDER BY sa.granted_at DESC, sa.id DESC""".map(extract).list.apply()

  /** The grants series admins (not root) added in the last `days` days, the newest first: root's
    * in-app notices of them (CA19).
    */
  def recentBySeriesAdmins(days: Int, limit: Int)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa JOIN users g ON g.id = sa.granted_by
          WHERE g.is_root = FALSE AND sa.granted_at > NOW(3) - INTERVAL $days DAY
          ORDER BY sa.granted_at DESC, sa.id DESC LIMIT $limit""".map(extract).list.apply()

  /** Adds a grant; its id. The key refuses a second unrevoked grant of the account on the series. */
  def create(seriesId: Long, userId: Long, adminFromYear: Int, grantedBy: Long, comment: Option[String])(implicit
      session: DBSession): Long = {
    val text = comment.map(_.trim).filter(_.nonEmpty).map(_.take(CommentMaxLength))
    sql"""INSERT INTO series_admin (series_id, user_id, admin_from_year, granted_by, granted_at, comment)
          VALUES ($seriesId, $userId, $adminFromYear, $grantedBy, NOW(3), $text)""".updateAndReturnGeneratedKey.apply()
  }

  /** Revokes the grants still unrevoked among `ids`; the number revoked. */
  def revoke(ids: Seq[Long], by: Long, byRoot: Boolean)(implicit session: DBSession): Int =
    if (ids.isEmpty) 0
    else
      sql"""UPDATE series_admin SET revoked_at = NOW(3), revoked_by = $by, revoked_by_root = $byRoot
            WHERE id IN (${ids.distinct}) AND revoked_at IS NULL""".update.apply()

  /** The series' unrevoked grants now wait for root's confirmation (its un-retirement); the number. */
  def markToConfirm(seriesId: Long)(implicit session: DBSession): Int =
    sql"""UPDATE series_admin SET confirm_needed_at = NOW(3)
          WHERE series_id = $seriesId AND revoked_at IS NULL AND confirm_needed_at IS NULL""".update.apply()

  /** Root confirms the grant: it counts again. */
  def confirm(id: Long)(implicit session: DBSession): Int =
    sql"""UPDATE series_admin SET confirm_needed_at = NULL
          WHERE id = $id AND revoked_at IS NULL AND confirm_needed_at IS NOT NULL""".update.apply()

  def setFromYear(id: Long, year: Int)(implicit session: DBSession): Int =
    sql"UPDATE series_admin SET admin_from_year = $year WHERE id = $id AND revoked_at IS NULL".update.apply()

  /** The unrevoked grants of the series that `userId` added, then those their accounts added, to
    * any depth (CA22: the chained revoke along `granted_by`), in the order found; `userId`'s own not
    * among them. The walk goes through revoked grants too (a branch whose middle root revoked
    * earlier is still the top's), and reaches each account once. Read `FOR UPDATE` (the caller holds
    * the series' lock).
    */
  def chainOf(seriesId: Long, userId: Long)(implicit session: DBSession): Seq[SeriesGrant] = {
    val all = sql"""SELECT $columns FROM series_admin sa WHERE sa.series_id = $seriesId ORDER BY sa.id FOR UPDATE"""
      .map(extract).list.apply()
    val byGranter = all.groupBy(_.grantedBy)
    @annotation.tailrec
    def walk(frontier: Seq[Long], seen: Set[Long], found: Vector[SeriesGrant]): Vector[SeriesGrant] =
      if (frontier.isEmpty) found
      else {
        val next = frontier.flatMap(byGranter.getOrElse(_, Nil)).filterNot(g => seen(g.userId)).distinctBy(_.id)
        val accounts = next.map(_.userId).distinct
        walk(accounts, seen ++ accounts, found ++ next.filterNot(_.revoked))
      }
    walk(Seq(userId), Set(userId), Vector.empty)
  }

  /** The default start year of a grant to `userId` on the series (CA20): the earliest year of the
    * series' live contests where the account holds a real Admin row (rows a join moved onto it
    * count; retired contests and other roles don't), capped at `thisYear - 1`; with none,
    * `thisYear - 1`.
    */
  def defaultFromYear(userId: Long, seriesId: Long, thisYear: Int)(implicit session: DBSession = AutoSession): Int = {
    val earliest = sql"""SELECT MIN(c.year) FROM user_contest uc JOIN contest_jury c ON c.id = uc.contest_id
                         WHERE uc.user_id = $userId AND BINARY uc.role = ${User.ADMIN_ROLE}
                           AND c.series_id = $seriesId AND c.retired_at IS NULL"""
      .map(_.intOpt(1)).single.apply().flatten
    earliest.fold(thisYear - 1)(math.min(_, thisYear - 1))
  }

  /** The contests `userId`'s live grants reach (the session's reach, [[controllers.Secured.withSeries]]):
    * each live grant's series' live contests, Admin from its start year on and Organizer below it;
    * not a contest where the account holds a real Juror row (CA7: the series doesn't apply there).
    * One query.
    */
  def seriesContests(userId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesContest] =
    sql"""SELECT c.id, c.series_id, c.year, c.year >= sa.admin_from_year
          FROM series_admin sa $liveJoin
          JOIN contest_jury c ON c.series_id = sa.series_id AND c.retired_at IS NULL
          WHERE sa.user_id = $userId
            AND NOT EXISTS (SELECT 1 FROM user_contest uc WHERE uc.user_id = sa.user_id AND uc.contest_id = c.id
                              AND BINARY uc.role = ${User.JURY_ROLE})
          ORDER BY c.year DESC, c.id DESC"""
      .map(rs => SeriesContest(rs.long(1), rs.long(2), rs.int(3),
        if (rs.boolean(4)) User.ADMIN_ROLE else "organizer")).list.apply()

  /** The contests where `userId`'s live grants count as real Admin rows for the account rules
    * (CA17; contest series plan §3.3 "Account reach"): each live grant's series' live contests with
    * year >= the grant's start year, whether created before or after the grant; not retired
    * contests, a retired series, revoked grants, or a contest where the account holds a real Juror
    * row (CA7). The same contests where the grant gives derived Admin. From the database, never the
    * session: an invitation's acceptance ([[services.AccountTokenService]]) gets the same answer as
    * its issue. The account rules' only input from the series (the guard spec allows it there only).
    */
  def seriesAccountContests(userId: Long)(implicit session: DBSession = AutoSession): Set[Long] =
    seriesContests(userId).filter(_.admin).map(_.contestId).toSet

  /** Whether the account holds a live grant (on a live series): with root, a protected target
    * ([[User.isProtectedTarget]]).
    */
  def hasLiveGrant(userId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM series_admin sa $liveJoin WHERE sa.user_id = $userId)"""
      .map(_.boolean(1)).single.apply().getOrElse(false)

  /** Those of the accounts that hold a live grant, in one query. */
  def withLiveGrant(userIds: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    if (userIds.isEmpty) Set.empty
    else
      sql"""SELECT DISTINCT sa.user_id FROM series_admin sa $liveJoin
            WHERE sa.user_id IN (${userIds.distinct})""".map(_.long(1)).list.apply().toSet

  /** Whether the account holds a live grant on the contest's live series ([[User.seriesProtected]]). */
  def holdsGrantOnContest(userId: Long, contestId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM series_admin sa $liveJoin JOIN contest_jury c ON c.series_id = sa.series_id
          WHERE sa.user_id = $userId AND c.id = $contestId)"""
      .map(_.boolean(1)).single.apply().getOrElse(false)

  /** The live series admins of the contest's live series (the users page's "Series admins" line). */
  def liveForContest(contestId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa $liveJoin JOIN contest_jury c ON c.series_id = sa.series_id
          WHERE c.id = $contestId ORDER BY sa.granted_at, sa.id"""
      .map(extract).list.apply()

  /** The joined account's unrevoked grants, locked (a join, J-2). */
  def unrevokedOf(userId: Long)(implicit session: DBSession): Seq[SeriesGrant] =
    sql"""SELECT $columns FROM series_admin sa WHERE sa.user_id = $userId AND sa.revoked_at IS NULL
          ORDER BY sa.id FOR UPDATE""".map(extract).list.apply()

  /** Moves the grant to `toUserId` (a join: the kept account), keeping its start year. */
  def moveTo(id: Long, toUserId: Long)(implicit session: DBSession): Int =
    sql"UPDATE series_admin SET user_id = $toUserId WHERE id = $id AND revoked_at IS NULL".update.apply()

  /** The grants `fromUserId` added now count as added by `toUserId` (a join), so that the chained
    * revoke still follows them.
    */
  def regrantedBy(fromUserId: Long, toUserId: Long)(implicit session: DBSession): Int =
    sql"UPDATE series_admin SET granted_by = $toUserId WHERE granted_by = $fromUserId".update.apply()
}
