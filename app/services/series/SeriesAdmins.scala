package services.series

import controllers.Secured
import db.scalikejdbc.{AccountChangeJdbc, AccountJoinJdbc, ContestJuryJdbc, DbErrors, Series, SeriesAdminJdbc,
  SeriesGrant, SeriesJdbc, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Logger
import scalikejdbc._
import services.AccountChangeLog

/** Series admins (contest series plan §3.3; PR CY-2; CA3, CA17, CA19, CA20, CA22): granting "Series
  * admin" of a series, revoking it (with the chained revoke along `granted_by`), root's later change
  * of a grant's start year, and what an account join (J-2) or retirement (A-4) does to grants.
  *
  * Each write is one transaction that locks the series row `FOR UPDATE` first (as every write in a
  * series, [[SeriesJdbc.lock]]), then the account's row ([[User.lockRow]]), re-checks everything,
  * writes, logs an `account_change` row (CA8) and an INFO line on `security.roles`. A refusal is a
  * message key (`Left`), nothing written.
  *
  * '''Who:''' root grants to any account it may (below); a series admin of the series (a live grant,
  * read again under the lock) adds series admins of their own series at any depth (CA19, CA22),
  * with a start year from their own up to next year; only root revokes and changes a start year;
  * a grant root revoked is granted again by root only.
  *
  * '''Whom''' (under the account's row lock): an active real member of one of the series' live
  * contests; never a deleted account, one an account join is retiring, a root account, the adder,
  * or an account that already holds an unrevoked grant on the series.
  */
object SeriesAdmins {

  private val log = Logger("security.roles")

  type Refusal = String

  /** The years root may choose: any plausible one. */
  val EarliestYear = 1990

  /** A grant done: its id, the start year chosen and the computed default. */
  final case class Granted(grantId: Long, fromYear: Int, defaultYear: Int)

  private def isRoot(u: User): Boolean = u.isRoot || u.hasRole(User.ROOT_ROLE)

  /** The years a grant by `actor` may start in, with `default` raised into them (CA20): root any
    * year up to next year; a series admin from their own start year (their live grant's) up to next
    * year. None: the actor may not grant on the series.
    */
  def bounds(actor: User, seriesId: Long, thisYear: Int)(implicit session: DBSession = AutoSession): Option[(Int, Int)] =
    if (isRoot(actor)) Some(EarliestYear -> (thisYear + 1))
    else SeriesAdminJdbc.liveGrant(actor.getId, seriesId).map(g => g.adminFromYear -> (thisYear + 1))

  /** The default start year of a grant to `accountId` by `actor` (CA20): the computed one, raised to
    * the actor's own start year for a series admin.
    */
  def defaultYear(actor: User, seriesId: Long, accountId: Long, thisYear: Int)(implicit
      session: DBSession = AutoSession): Int = {
    val computed = SeriesAdminJdbc.defaultFromYear(accountId, seriesId, thisYear)
    bounds(actor, seriesId, thisYear).fold(computed) { case (from, to) => math.min(math.max(computed, from), to) }
  }

  /** Whether `accountId` is an active real member (Juror, Organizer or Admin) of one of the series'
    * live contests.
    */
  def isMember(accountId: Long, seriesId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM user_contest uc JOIN contest_jury c ON c.id = uc.contest_id
          WHERE uc.user_id = $accountId AND c.series_id = $seriesId AND c.retired_at IS NULL
            AND BINARY uc.role IN (${User.ACCESS_ROLES.toSeq}))""".map(_.boolean(1)).single.apply().getOrElse(false)

  /** The accounts `actor` may pick on the series page's "Add a series admin": the active real members
    * of the series' live contests, but deleted and root accounts, the actor and the series' series
    * admins (the grant checks each again).
    */
  def candidates(actor: User, seriesId: Long)(implicit session: DBSession = AutoSession): Seq[User] = {
    val ids = sql"""SELECT DISTINCT uc.user_id FROM user_contest uc JOIN contest_jury c ON c.id = uc.contest_id
                    WHERE c.series_id = $seriesId AND c.retired_at IS NULL
                      AND BINARY uc.role IN (${User.ACCESS_ROLES.toSeq})""".map(_.long(1)).list.apply()
    val granted = SeriesAdminJdbc.liveBySeries(seriesId).map(_.userId).toSet
    User.findByIds(ids).filter(u => u.deletedAt.isEmpty && !isRoot(u) && !actor.id.contains(u.getId) && !granted(u.getId))
      .sortBy(u => (u.fullname.toLowerCase(java.util.Locale.ROOT), u.getId))
  }

  /** Why `actor` may not grant `account` on the series now, if so (the grant's own checks). */
  private def refusal(actor: User, series: Series, account: Option[User])(implicit session: DBSession): Option[Refusal] =
    if (series.retired) Some("series.admin.refused.retired")
    // a series admin's own grant must count: not revoked, not waiting for root's confirmation
    else if (!isRoot(actor) && SeriesAdminJdbc.liveGrant(actor.getId, series.id).isEmpty) Some("series.admin.refused.not.admin")
    else account match {
      case None                                   => Some("series.admin.refused.account")
      case Some(a) if a.deletedAt.nonEmpty        => Some("series.admin.refused.account")
      case Some(a) if isRoot(a)                   => Some("series.admin.refused.root")
      case Some(a) if actor.id.contains(a.getId)  => Some("series.admin.refused.self")
      case Some(a) if AccountJoinJdbc.retiring(Seq(a.getId)).nonEmpty => Some("series.admin.refused.retiring")
      case Some(a) if SeriesAdminJdbc.unrevoked(a.getId, series.id).nonEmpty => Some("series.admin.refused.existing")
      case Some(a) if !isMember(a.getId, series.id) => Some("series.admin.refused.not.member")
      case Some(a) if !isRoot(actor) && SeriesAdminJdbc.lastRevokedByRoot(a.getId, series.id) =>
        Some("series.admin.refused.root.revoked")
      case Some(_) => None
    }

  /** Grants `accountId` "Series admin" of the series, starting in `fromYear` (None: the default),
    * as `actor` (root, or a series admin of the series). Each grant by a series admin shows on
    * root's `/admin/series` (root's notice, CA19).
    */
  def grant(actor: User, seriesId: Long, accountId: Long, fromYear: Option[Int], comment: Option[String],
      thisYear: Int = java.time.Year.now().getValue): Either[Refusal, Granted] =
    try DB.localTx { implicit s =>
      SeriesJdbc.lock(seriesId) match {
        case None => Left("series.not.found")
        case Some(series) =>
          val account = User.lockRow(accountId)
          refusal(actor, series, account).toLeft(()).flatMap { _ =>
            val computed = SeriesAdminJdbc.defaultFromYear(accountId, seriesId, thisYear)
            bounds(actor, seriesId, thisYear) match {
              case None => Left("series.admin.refused.not.admin")
              case Some((from, to)) =>
                val default = math.min(math.max(computed, from), to)
                val year = fromYear.getOrElse(default)
                if (year < from || year > to) Left("series.admin.refused.year")
                else {
                  val id = SeriesAdminJdbc.create(seriesId, accountId, year, actor.getId, comment)
                  AccountChangeLog.record(actor, accountId, None, AccountChangeJdbc.SeriesAdminGranted,
                    oldValue = Some(computed.toString), newValue = Some(year.toString),
                    comment = Some(AccountChangeJdbc.seriesComment(seriesId)))
                  log.info(s"series_admin_granted account=$accountId series=$seriesId from_year=$year " +
                    s"default_year=$computed by=${actor.getId} root=${isRoot(actor)} grantId=$id")
                  Right(Granted(id, year, computed))
                }
            }
          }
      }
    } catch {
      // a second grant committed meanwhile: the live-grant key refuses this one
      case e: Exception if DbErrors.isDuplicateKey(e) => Left("series.admin.refused.existing")
    }

  /** Root revokes the grant, and with `chain` the grants its account added, to any depth (CA22),
    * in one transaction under the series row's lock; the grants revoked. Only root.
    */
  def revoke(actor: User, grantId: Long, chain: Boolean): Either[Refusal, Seq[SeriesGrant]] =
    if (!isRoot(actor)) Left("series.admin.refused.root.only")
    else DB.localTx { implicit s =>
      SeriesAdminJdbc.findById(grantId) match {
        case None => Left("series.admin.not.found")
        case Some(found) =>
          SeriesJdbc.lock(found.seriesId)
          SeriesAdminJdbc.findById(grantId).filterNot(_.revoked) match {
            case None => Left("series.admin.refused.revoked")
            case Some(grant) =>
              val branch = if (chain) SeriesAdminJdbc.chainOf(grant.seriesId, grant.userId) else Nil
              val all = grant +: branch
              SeriesAdminJdbc.revoke(all.map(_.id), actor.getId, byRoot = true)
              all.foreach(g => logRevoked(actor, g, s"chain=${chain && g.id != grant.id} top=${grant.id}"))
              Right(all)
          }
      }
    }

  private def logRevoked(actor: User, g: SeriesGrant, fields: String, note: Option[String] = None)(implicit
      session: DBSession): Unit = {
    AccountChangeLog.record(actor, g.userId, None, AccountChangeJdbc.SeriesAdminRevoked,
      oldValue = Some(g.adminFromYear.toString),
      comment = Some(AccountChangeJdbc.seriesComment(g.seriesId) + note.fold("")("; " + _)))
    log.info(s"series_admin_revoked account=${g.userId} series=${g.seriesId} grantId=${g.id} by=${actor.getId} $fields")
  }

  /** Root changes the grant's start year (CA20; root only, any year up to next year). Takes effect on
    * the next request: derived roles and account reach are read per request. It doesn't cascade to
    * the grants this admin added ([[addedBy]] lists them for root).
    */
  def setFromYear(actor: User, grantId: Long, year: Int, thisYear: Int = java.time.Year.now().getValue): Either[Refusal, Unit] =
    if (!isRoot(actor)) Left("series.admin.refused.root.only")
    else if (year < EarliestYear || year > thisYear + 1) Left("series.admin.refused.year")
    else DB.localTx { implicit s =>
      SeriesAdminJdbc.findById(grantId) match {
        case None => Left("series.admin.not.found")
        case Some(found) =>
          SeriesJdbc.lock(found.seriesId)
          SeriesAdminJdbc.findById(grantId).filterNot(_.revoked) match {
            case None => Left("series.admin.refused.revoked")
            case Some(g) if g.adminFromYear == year => Right(())
            case Some(g) =>
              SeriesAdminJdbc.setFromYear(g.id, year)
              AccountChangeLog.record(actor, g.userId, None, AccountChangeJdbc.SeriesAdminFromYear,
                oldValue = Some(g.adminFromYear.toString), newValue = Some(year.toString),
                comment = Some(AccountChangeJdbc.seriesComment(g.seriesId)))
              log.info(s"series_admin_from_year account=${g.userId} series=${g.seriesId} old=${g.adminFromYear} " +
                s"new=$year by=${actor.getId} grantId=${g.id}")
              Right(())
          }
      }
    }

  /** The unrevoked grants of the series that `userId` added (root's start-year form lists them). */
  def addedBy(seriesId: Long, userId: Long)(implicit session: DBSession = AutoSession): Seq[SeriesGrant] =
    SeriesAdminJdbc.findBySeries(seriesId).filter(g => !g.revoked && g.grantedBy == userId)

  /** An account join (J-2), in its transaction after the accounts' rows are locked: each unrevoked
    * grant of the joined account moves to the kept account with its start year, unless the kept
    * account holds an unrevoked grant on that series, which stays while the joined one is revoked;
    * the grants the joined account added count as the kept account's (the chained revoke follows
    * them). The number moved and revoked.
    */
  def onJoin(actor: User, keptId: Long, joinedId: Long)(implicit session: DBSession): (Int, Int) = {
    val joined = SeriesAdminJdbc.unrevokedOf(joinedId)
    val kept = SeriesAdminJdbc.unrevokedOf(keptId).map(_.seriesId).toSet
    val (clash, move) = joined.partition(g => kept(g.seriesId))
    SeriesAdminJdbc.revoke(clash.map(_.id), actor.getId, byRoot = isRoot(actor))
    clash.foreach(g => logRevoked(actor, g, s"reason=account_join kept=$keptId", Some(s"#$keptId")))
    move.foreach { g =>
      SeriesAdminJdbc.moveTo(g.id, keptId)
      AccountChangeLog.record(actor, keptId, None, AccountChangeJdbc.SeriesAdminGranted,
        oldValue = Some(g.adminFromYear.toString), newValue = Some(g.adminFromYear.toString),
        comment = Some(AccountChangeJdbc.seriesComment(g.seriesId) + s"; #$joinedId"))
      log.info(s"series_admin_moved account=$keptId from=$joinedId series=${g.seriesId} grantId=${g.id} " +
        s"from_year=${g.adminFromYear} by=${actor.getId}")
    }
    SeriesAdminJdbc.regrantedBy(joinedId, keptId)
    (move.size, clash.size)
  }

  /** An account retired (A-4), in its transaction: its unrevoked grants are revoked. The number. */
  def onRetire(actor: User, accountId: Long)(implicit session: DBSession): Int = {
    val grants = SeriesAdminJdbc.unrevokedOf(accountId)
    SeriesAdminJdbc.revoke(grants.map(_.id), actor.getId, byRoot = isRoot(actor))
    grants.foreach(g => logRevoked(actor, g, "reason=account_retired"))
    grants.size
  }

  /** A series un-retired by root (in its transaction, under the series' lock): its unrevoked grants
    * come back waiting for root's confirmation (§3.1, "un-retiring asks root to re-confirm each
    * grant"; the maintainer's decision of 2026-11-24): no access and no protection until root
    * confirms each ([[confirm]]); their `granted_by` chains are kept. The number waiting.
    */
  def onSeriesUnretired(actorId: Long, seriesId: Long)(implicit session: DBSession): Int = {
    val grants = SeriesAdminJdbc.findBySeries(seriesId).filter(_.active)
    SeriesAdminJdbc.markToConfirm(seriesId)
    grants.foreach(g => log.info(s"series_admin_to_confirm account=${g.userId} series=$seriesId grantId=${g.id} " +
      s"by=$actorId reason=series_unretired"))
    grants.size
  }

  /** Root confirms a grant waiting since its series was un-retired: it counts again, its start year
    * as it was. Only root, only while the series is live and the account not deleted. A grant added
    * by a series admin whose own grant on the series is still waiting is refused
    * (`series.admin.refused.granter.unconfirmed`): root confirms the granter first, so a branch never
    * comes back below an unconfirmed top. A granter whose grant was revoked doesn't block it: a
    * revoke without the chain keeps the grants that admin added (§3.3), as before the retirement.
    * Logged (`series_admin_confirmed`).
    */
  def confirm(actor: User, grantId: Long): Either[Refusal, SeriesGrant] =
    if (!isRoot(actor)) Left("series.admin.refused.root.only")
    else DB.localTx { implicit s =>
      SeriesAdminJdbc.findById(grantId) match {
        case None => Left("series.admin.not.found")
        case Some(found) =>
          val series = SeriesJdbc.lock(found.seriesId)
          val account = User.lockRow(found.userId)
          SeriesAdminJdbc.findById(grantId) match {
            case None                                  => Left("series.admin.not.found")
            case Some(g) if g.revoked                  => Left("series.admin.refused.revoked")
            case Some(g) if !g.toConfirm               => Left("series.admin.refused.confirmed")
            case Some(_) if !series.exists(_.live)     => Left("series.admin.refused.retired")
            case Some(_) if !account.exists(_.deletedAt.isEmpty) => Left("series.admin.refused.account")
            case Some(g) if SeriesAdminJdbc.findBySeries(g.seriesId)
                .exists(o => o.userId == g.grantedBy && o.toConfirm) => Left("series.admin.refused.granter.unconfirmed")
            case Some(g) =>
              SeriesAdminJdbc.confirm(g.id)
              AccountChangeLog.record(actor, g.userId, None, AccountChangeJdbc.SeriesAdminConfirmed,
                oldValue = Some(g.adminFromYear.toString), comment = Some(AccountChangeJdbc.seriesComment(g.seriesId)))
              log.info(s"series_admin_confirmed account=${g.userId} series=${g.seriesId} grantId=${g.id} " +
                s"from_year=${g.adminFromYear} by=${actor.getId}")
              Right(g)
          }
      }
    }

  // ── what a grant gives, for the grant page ─────────────────────────────────

  /** One of the series' live contests as a grant starting in a year gives it (CA20): derived Admin
    * from the start year on, derived Organizer before it; `realAdmin`: the grantee's real Admin row
    * there; `juror`: a real Juror row, where the series doesn't apply (CA7).
    */
  final case class Reach(contest: ContestJury, admin: Boolean, realAdmin: Boolean, juror: Boolean)

  def reachOf(seriesId: Long, accountId: Long, fromYear: Int)(implicit session: DBSession = AutoSession): Seq[Reach] = {
    val contests = ContestSeries.contestsOf(seriesId).filterNot(_.retired)
    val rows = UserContestJdbc.findByUser(accountId).map(m => m.contestId -> m.role).toMap
    contests.map { c =>
      Reach(c, admin = c.year >= fromYear, realAdmin = rows.get(c.getId).contains(User.ADMIN_ROLE),
        juror = rows.get(c.getId).contains(User.JURY_ROLE))
    }
  }

  /** The names of the accounts, by id (the pages' "added by"). */
  def names(ids: Seq[Long]): Map[Long, String] =
    User.findByIds(ids.distinct).map(u => u.getId -> Option(u.fullname).filter(_.trim.nonEmpty).getOrElse(s"#${u.getId}")).toMap

  /** The contest's series, if live (the users page's line). */
  def liveSeriesOf(contestId: Long)(implicit session: DBSession = AutoSession): Option[Series] =
    ContestJuryJdbc.findById(contestId).flatMap(_.seriesId).flatMap(SeriesJdbc.findById(_)).filter(_.live)
}
