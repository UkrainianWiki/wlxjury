package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.Vote
import org.intracer.wmua.cmd.DistributeImages
import play.api.libs.json._
import play.api.{Configuration, Logger, Logging}
import scalikejdbc._
import services.jobs._

import javax.inject.{Inject, Singleton}

/** The resume cursor of a `round_rebalance` job (`job.state`).
  *
  * @param phase           "stop" (lock, check, pause the round, add the juror), "give" (the
  *                        round's files to the new juror, window by window: a round where every
  *                        file goes to every juror), "move" (the donors' unrated rows to the new
  *                        juror: a round where each file goes to N > 0 jurors), "verify" (the
  *                        count check, then the round released)
  * @param cursor          "give": the last page id a committed step read
  * @param filesGiven      vote rows the job gave the new juror
  * @param jurorRowsBefore the new juror's rows of the round (vote and vote_aside) before its
  *                        first give, counted by the first "stop" (after a failed count check, by
  *                        the next): the count check expects exactly `jurorRowsBefore + filesGiven`
  * @param jurorWasActive  the juror's round_user row before the job (its first "stop"): None if
  *                        it had none. Cancel puts a juror who wasn't active back to stopped if the
  *                        job gave them nothing
  * @param jurorAdded      a "stop" step has written the juror's round_user row (every "stop" writes
  *                        it active again: a juror stopped by hand while the job was failed is
  *                        started again by its Retry, not given files while stopped)
  * @param frozen          the round, while this job holds it paused ([[Round.freeze]])
  * @param total           the files to give, counted by "stop" (the progress)
  * @param verifyFailed    the count check failed: a Retry gives again from the start, with fresh
  *                        accounting (the files the juror has are left out)
  * @param gaveAny         a committed step gave the juror files; never reset (a Retry after a failed
  *                        count check resets `filesGiven`, but the files stay the juror's): Cancel
  *                        stops the juror again only if it is false
  * @param quota           "move": the most rows each donor gives (by donor id), its water-filling
  *                        share ([[RoundRebalanceHandler.quotas]]), fixed by "stop"
  * @param given           "move": the rows each donor gave
  * @param cursors         "move": per donor, the lowest vote id its committed windows read (its
  *                        rows are taken last assigned first, by descending id)
  * @param exhausted       "move": donors with no eligible unrated row left below their cursor
  * @param donorRowsBefore "move": each donor's rows (vote and vote_aside) when "stop" counted: the
  *                        count check expects each to have lost exactly the rows it gave
  */
final case class RebalanceState(
    phase: String = RoundRebalanceHandler.Phase.Stop,
    cursor: Long = 0L,
    filesGiven: Long = 0L,
    jurorRowsBefore: Option[Long] = None,
    jurorWasActive: Option[Boolean] = None,
    jurorAdded: Boolean = false,
    frozen: Seq[Long] = Nil,
    total: Option[Long] = None,
    verifyFailed: Boolean = false,
    gaveAny: Boolean = false,
    quota: Map[String, Long] = Map.empty,
    given: Map[String, Long] = Map.empty,
    cursors: Map[String, Long] = Map.empty,
    exhausted: Seq[Long] = Nil,
    donorRowsBefore: Map[String, Long] = Map.empty
) {
  def givenBy(donor: Long): Long = given.getOrElse(donor.toString, 0L)
  def quotaOf(donor: Long): Long = quota.getOrElse(donor.toString, 0L)
}

object RebalanceState {
  implicit val format: OFormat[RebalanceState] = Json.using[Json.WithDefaultValues].format[RebalanceState]
}

/** Adds a juror to a running round, as a [[Job]] of kind `round_rebalance` whose subject is the
  * round (docs/plans/2026-11-16-round-editing.md, §5). It shares the round's dedupe key with its
  * distribution ([[RoundDistributionHandler.dedupeKey]]): the key's unique index makes the two
  * exclude each other, and every check of a round "being distributed" sees it too; the
  * distribution's readers of its latest job (retry, resubmit, lineage) read their own kind only.
  *
  * Votes move only while the round isn't open (the 2026-11-03 rule), so the job pauses the round
  * itself: its first step freezes it ([[Round.freeze]]) and the last releases it; failure and
  * Cancel release it, and Retry freezes it again from its first step. While it is paused its
  * jurors keep it in their menus and get the "paused" page and the vote error `round_paused`
  * ([[Round.pausedCond]]). Its steps are claimed before other kinds' ([[priority]]).
  *
  * Steps, each in one short transaction with the round locked first and the refusals checked
  * again ([[RoundRebalanceHandler.refusal]]):
  *  1. "stop": freeze the round; insert or reactivate the juror's round_user row (written only
  *     here: a vote locks its row, then round_user, so no step that holds vote rows writes it);
  *     count what the juror has and what is to give;
  *  2. "give" (a round where every file goes to every juror, `distribution = 0`): a keyset window
  *     of the round's `in` files the juror has no row for in `vote` or `vote_aside` and not
  *     deleted on Commons, their images read in share mode (their monument ids, and whether
  *     marked deleted meanwhile), the juror's unrated rows inserted. The files' `round_image`
  *     rows exist and keep their origin ([[RoundImageJdbc.addVoted]]);
  *  2'. "move" (a round where each file goes to N > 0 jurors), instead: the ticked donors'
  *     (`params.donors`) unrated rows (`rate = 0` and no criterion rated above 0), last assigned
  *     first, each donor up to its quota (water-filling, [[RoundRebalanceHandler.quotas]]: donors
  *     end with about as many unrated files as the new juror), at most `chunkRows` rows a step. The
  *     candidates' images are read in share mode, then their rows locked (`FOR UPDATE`, by id); a row
  *     rated meanwhile is skipped, and a row moves only if the new juror has no row of its page in
  *     `vote` or `vote_aside`. The new juror's row is written first, then the donor's deleted with its
  *     criteria rates (none above 0), then the files' `round_image` rows synced; each page's number
  *     of vote rows is checked unchanged in the same transaction, else the step rolls back. A ticked
  *     stopped donor gives and stays stopped;
  *  3. "verify": the juror holds exactly the rows before plus those given, each donor exactly its
  *     rows before less those it gave; the round released.
  *
  * Rated votes never move. The audit is a `round.admin` INFO line when queued and when done, and
  * the job row (`params`, `state`).
  */
@Singleton
class RoundRebalanceHandler @Inject() (config: Configuration) extends JobHandler with Logging {

  import RoundRebalanceHandler._

  val kind: String = Kind

  override def priority: Boolean = true

  /** Files per "give" or "move" step. */
  val chunkRows: Int = config.getOptional[Int]("jobs.roundRebalance.chunkRows").getOrElse(DefaultChunkRows)

  override def title(job: Job): String =
    s"Adding juror ${jurorOf(job)} to round ${job.subjectId.getOrElse("?")}"

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("roundId" -> roundIdOf(job), "jurorId" -> jurorOf(job), "filesGiven" -> st.filesGiven, "phase" -> st.phase,
      "given" -> st.given)
  }

  /** Hook for tests: runs in a "give" step's transaction before its rows are inserted. */
  protected def beforeGive(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = ()

  /** Hook for tests: runs in a "move" step's transaction after its candidates' plain read, before
    * any of them is locked.
    */
  protected def beforeLock(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit = ()

  /** Hook for tests: runs in a "move" step's transaction, its rows locked, before they move. */
  protected def beforeMove(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit = ()

  /** Hook for tests: runs in a "move" step's transaction after the rows moved, before its check. */
  protected def afterMove(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = ()

  // ── failure, cancel, retry ─────────────────────────────────────────────────

  override def onFailed(job: Job, ctx: StepContext): JsObject = end(job, ctx, cancelled = false)

  override def onCancelled(job: Job, ctx: StepContext): JsObject = end(job, ctx, cancelled = true)

  /** Releases the round if the job froze it; on Cancel, a juror the job started who wasn't
    * active before is stopped again, only if it gave them nothing: a juror holding files the job
    * gave stays started, so no file it moved sits with a stopped juror (adding them again tops up
    * the rest). The round first, then round_user, as the "stop" step.
    */
  private def end(job: Job, ctx: StepContext, cancelled: Boolean): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    lockRun(roundId)
    Round.release(st.frozen, close = false)
    if (cancelled && st.jurorAdded && !st.gaveAny && !st.jurorWasActive.contains(true))
      sql"UPDATE round_user SET active = FALSE WHERE round_id = $roundId AND user_id = ${jurorOf(job)}".update()
    if (st.frozen.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
    Json.toJsObject(st.copy(frozen = Nil))
  }

  /** Releases what a failure left frozen (nothing, normally) and resumes from "stop", which checks
    * the refusals again and pauses the round again. The count check's baseline moves by what
    * others changed while the round was released (`jurorRowsBefore` = the juror's rows now less
    * those given; each donor's, its rows now plus those it gave), as a distribution's Retry
    * rebaselines. After a failed count check it gives
    * again from the start, with fresh accounting.
    */
  override def onRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    lockRun(roundId)
    Round.release(st.frozen, close = false)
    val resumed =
      if (!st.verifyFailed) rebaseline(st, roundId, jurorOf(job))
      // fresh accounting and, for a move, fresh quotas from the files as they are now
      else st.copy(cursor = 0L, filesGiven = 0L, jurorRowsBefore = None, total = None, verifyFailed = false,
        quota = Map.empty, given = Map.empty, cursors = Map.empty, exhausted = Nil, donorRowsBefore = Map.empty)
    Json.toJsObject(resumed.copy(phase = Phase.Stop, frozen = Nil))
  }

  /** The count check's baselines as of now: what others changed while the job was failed. */
  protected def rebaseline(st: RebalanceState, roundId: Long, jurorId: Long)(implicit session: DBSession): RebalanceState =
    st.copy(
      jurorRowsBefore = st.jurorRowsBefore.map(_ => jurorRows(roundId, jurorId) - st.filesGiven),
      // each donor's rows now, and those it gave back on
      donorRowsBefore = st.donorRowsBefore.map { case (d, _) => d -> (jurorRows(roundId, d.toLong) + st.givenBy(d.toLong)) })

  // ── steps ──────────────────────────────────────────────────────────────────

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    val jurorId = jurorOf(job)
    val st = stateOf(job)
    val run = lockRun(roundId).getOrElse(return failed(job, st, JobError(MissingKey, Seq(roundId.toString))))
    val round = Round.findById(roundId).getOrElse(return failed(job, st, JobError(MissingKey, Seq(roundId.toString))))
    val donors = donorsOf(job).filterNot(_ == jurorId)
    refusal(round, run, jurorId, donors, frozenByUs = st.frozen.contains(roundId)) match {
      case Some(error) => return failed(job, st, error)
      case None        =>
    }

    st.phase match {
      case Phase.Stop =>
        val frozen =
          if (st.frozen.contains(roundId)) st.frozen
          else { Round.freeze(Seq(roundId)); Seq(roundId) }
        // the juror's row active, in every "stop" (a Retry's too); its state before, the first time
        val before = sql"""SELECT active FROM round_user WHERE round_id = $roundId AND user_id = $jurorId FOR UPDATE"""
          .map(_.boolean(1)).single()
        sql"""INSERT INTO round_user (round_id, user_id, role, active) VALUES ($roundId, $jurorId, ${User.JURY_ROLE}, TRUE)
              ON DUPLICATE KEY UPDATE active = TRUE""".update()
        val (wasActive, added) = (if (st.jurorAdded) st.jurorWasActive else before, true)
        val rowsBefore = st.jurorRowsBefore.getOrElse(jurorRows(roundId, jurorId))
        val stopped = st.copy(frozen = frozen, jurorWasActive = wasActive, jurorAdded = added,
          jurorRowsBefore = Some(rowsBefore))
        if (round.distribution == 0) {
          val total = st.total.getOrElse(st.filesGiven + toGiveCount(roundId, jurorId))
          continue(stopped.copy(phase = Phase.Give, total = Some(total)))
        } else {
          // the quotas once, from the unrated work as it is when the round is paused (kept on Retry)
          val withQuotas =
            if (st.quota.nonEmpty || st.given.nonEmpty) stopped
            else {
              // the donors' files the receiver could take, and the receiver's own unrated files
              val unrated = unratedCounts(roundId, donors, receiver = Some(jurorId))
              val own = unratedCounts(roundId, Seq(jurorId)).getOrElse(jurorId, 0L)
              val quota = quotas(own, donors.map(d => d -> unrated.getOrElse(d, 0L)))
              stopped.copy(quota = quota.map { case (d, n) => d.toString -> n },
                donorRowsBefore = donors.map(d => d.toString -> jurorRows(roundId, d)).toMap,
                total = Some(quota.values.sum))
            }
          continue(withQuotas.copy(phase = Phase.Move))
        }

      case Phase.Give =>
        val ids = toGiveWindow(roundId, jurorId, st.cursor, chunkRows)
        if (ids.isEmpty) continue(st.copy(phase = Phase.Verify))
        else {
          // the files' images in share mode, in page order (h6): the rows carry the monument id as
          // committed now; one marked deleted on Commons since the window's read is left out
          val images = sql"""SELECT page_id, monument_id FROM images WHERE page_id IN ($ids) AND deleted_at IS NULL
                             ORDER BY page_id LOCK IN SHARE MODE"""
            .map(rs => rs.long(1) -> rs.stringOpt(2)).list()
          val rows = images.map { case (pageId, monumentId) =>
            Vote(pageId = pageId, juryId = jurorId, roundId = roundId, rate = 0, monumentId = monumentId)
          }
          beforeGive(roundId, rows.map(_.pageId))
          DistributeImages.insertRows(rows, RoundImageJdbc.Origin.Manual)
          if (rows.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          continue(st.copy(cursor = ids.last, filesGiven = st.filesGiven + rows.size, gaveAny = st.gaveAny || rows.nonEmpty))
        }

      case Phase.Move => move(job, ctx, round, jurorId, donors, st)

      case Phase.Verify =>
        val count = jurorRows(roundId, jurorId)
        val expected = st.jurorRowsBefore.getOrElse(0L) + st.filesGiven
        // each donor lost exactly the rows it gave
        val donorMismatch = st.donorRowsBefore.toSeq.sortBy(_._1.toLong).collectFirst {
          case (d, before) if jurorRows(roundId, d.toLong) != before - st.givenBy(d.toLong) => d.toLong
        }
        if (count != expected)
          StepResult.Failed(JobError(VerifyKey, Seq(round.number.toString, count.toString, expected.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else if (donorMismatch.isDefined)
          StepResult.Failed(JobError(DonorVerifyKey, Seq(round.number.toString, donorMismatch.get.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else {
          Round.release(st.frozen, close = false)
          ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          adminLog.info(s"action=juror_add accountId=${byOf(job).getOrElse(0L)} contestId=${round.contestId} " +
            s"roundId=$roundId jurorId=$jurorId moved=${st.filesGiven} " +
            (if (st.given.nonEmpty) s"from=${st.given.toSeq.sortBy(_._1.toLong).map { case (d, n) => s"$d:$n" }.mkString(",")} "
             else "") + s"jobId=${job.id} state=done")
          StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), progress(st))
        }

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  /** One "move" step: the next rows of the donors with quota left, in donor id order, at most
    * `chunkRows` in all; then "verify" when every donor has given its quota or has no eligible row
    * left.
    */
  private def move(job: Job, ctx: StepContext, round: Round, jurorId: Long, donors: Seq[Long], st: RebalanceState)(implicit
      session: DBSession
  ): StepResult = {
    val roundId = round.getId
    val open = donors.sorted.filter(d => st.givenBy(d) < st.quotaOf(d) && !st.exhausted.contains(d))
    if (open.isEmpty) return continue(st.copy(phase = Phase.Verify))
    // the candidates, a plain read per donor: its unrated rows below its cursor whose page the new
    // juror has no row of, last assigned first
    var budget = chunkRows
    val windows = open.flatMap { d =>
      if (budget <= 0) None
      else {
        val want = math.min(budget.toLong, st.quotaOf(d) - st.givenBy(d)).toInt
        val cursor = st.cursors.getOrElse(d.toString, Long.MaxValue)
        val rows = sql"""SELECT v.id, v.page_id FROM vote v
                         WHERE v.round_id = $roundId AND v.jury_id = $d AND v.id < $cursor
                           AND ${movable(roundId, Some(jurorId))}
                         ORDER BY v.id DESC LIMIT $want""".map(rs => rs.long(1) -> rs.long(2)).list()
        budget -= rows.size
        Some((d, want, rows))
      }
    }
    val candidates = windows.flatMap(_._3)
    if (candidates.nonEmpty) beforeLock(roundId, candidates.map(_._1))
    val pages = candidates.map(_._2).distinct.sorted
    // the lock order: the round (held), the files' images in share mode, then the rows by id
    if (pages.nonEmpty)
      sql"SELECT page_id FROM images WHERE page_id IN ($pages) ORDER BY page_id LOCK IN SHARE MODE".map(_.long(1)).list()
    val ids = candidates.map(_._1).sorted
    val locked =
      if (ids.isEmpty) Nil
      else
        sql"""SELECT id, page_id, jury_id, rate, monument_id FROM vote WHERE id IN ($ids) ORDER BY id FOR UPDATE"""
          .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4), rs.stringOpt(5))).list()
    // rated meanwhile (its rate, or a criterion), or no longer the donor's: skipped
    val criteriaRated =
      if (ids.isEmpty) Set.empty[Long]
      else sql"SELECT DISTINCT vote_id FROM criteria_rate WHERE vote_id IN ($ids) AND rate > 0 LOCK IN SHARE MODE"
        .map(_.long(1)).list().toSet
    val byDonor = windows.map(_._1).toSet
    val moving = locked.filter { case (id, _, jury, rate, _) => rate == 0 && byDonor(jury) && !criteriaRated(id) }
      .distinctBy(_._2) // one row per page: two donors may both hold a file the new juror takes once
    val movingPages = moving.map(_._2).sorted
    if (moving.nonEmpty) {
      beforeMove(roundId, moving.map(_._1))
      val before = votesPerPage(roundId, movingPages)
      // the new juror's rows first, then the donors' rows with their (unrated) criteria rates
      VoteJdbc.batchInsert(moving.map { case (_, page, _, _, monument) =>
        Vote(pageId = page, juryId = jurorId, roundId = roundId, rate = 0, monumentId = monument)
      }, RoundImageJdbc.Origin.Manual)
      val movedIds = moving.map(_._1)
      // a structural write: the round is locked first (lockRun, recorded)
      RoundWrites.requireRounds(Seq(roundId))
      sql"DELETE FROM criteria_rate WHERE vote_id IN ($movedIds)".update()
      sql"DELETE FROM vote WHERE id IN ($movedIds)".update()
      RoundImageJdbc.syncPairs(movingPages.map(roundId -> _))
      afterMove(roundId, movingPages)
      // the check: each file keeps its number of vote rows; otherwise the whole step rolls back
      val after = votesPerPage(roundId, movingPages)
      if (after != before)
        throw new IllegalStateException(s"Job ${job.id}: round $roundId's vote rows per file changed in a move " +
          s"(${movingPages.filter(p => after.get(p) != before.get(p)).take(10).mkString(", ")})")
      ctx.afterCommit(RoundImageCounts.invalidate(roundId))
    }
    val movedBy = moving.groupMapReduce(_._3)(_ => 1L)(_ + _)
    val next = windows.foldLeft(st) { case (acc, (d, want, rows)) =>
      acc.copy(
        given = acc.given.updated(d.toString, acc.givenBy(d) + movedBy.getOrElse(d, 0L)),
        cursors = rows.lastOption.fold(acc.cursors)(last => acc.cursors.updated(d.toString, last._1)),
        // fewer candidates than asked: none left below its cursor
        exhausted = if (rows.size < want) acc.exhausted :+ d else acc.exhausted
      )
    }
    continue(next.copy(filesGiven = st.filesGiven + moving.size, gaveAny = st.gaveAny || moving.nonEmpty))
  }

  private def votesPerPage(roundId: Long, pages: Seq[Long])(implicit session: DBSession): Map[Long, Int] =
    sql"SELECT page_id, COUNT(*) FROM vote WHERE round_id = $roundId AND page_id IN ($pages) GROUP BY page_id"
      .map(rs => rs.long(1) -> rs.int(2)).list().toMap

  /** A refusal found by a step: the job fails with it, and its cleanup releases the round. */
  private def failed(job: Job, st: RebalanceState, error: JobError): StepResult = {
    logger.warn(s"Job ${job.id} ($Kind): round ${roundIdOf(job)} refused: ${error.describe}")
    StepResult.Failed(error, Json.toJsObject(st), progress(st))
  }

  private def continue(st: RebalanceState) = StepResult.Continue(Json.toJsObject(st), progress(st))

  private def progress(st: RebalanceState) = Progress(done = Some(st.filesGiven), total = st.total, unit = Some("files"))
}

object RoundRebalanceHandler {

  val Kind = "round_rebalance"

  /** Files per "give" step when `jobs.roundRebalance.chunkRows` isn't set: a 22,577-file round in
    * about 12 steps.
    */
  val DefaultChunkRows = 2000

  object Phase {
    val Stop = "stop"
    val Give = "give"
    val Move = "move"
    val Verify = "verify"
  }

  /** The audit lines: queued and done (`action=juror_add ...`). */
  val adminLog: Logger = Logger("round.admin")

  // refusals: job error keys (job.error.*, shown by the job status and the add page)
  val MissingKey = "job.error.rebalance.missing"
  val NotRunningKey = "job.error.rebalance.not.running"
  val FrozenKey = "job.error.rebalance.frozen"
  val LegacyKey = "job.error.rebalance.legacy"
  val NotJurorKey = "job.error.rebalance.not.juror"
  val BusyKey = "job.error.rebalance.busy"
  val VerifyKey = "job.error.rebalance.verify"
  val DonorVerifyKey = "job.error.rebalance.donor.verify"
  val DonorKey = "job.error.rebalance.donor"

  def roundIdOf(job: Job): Long =
    (job.params \ "roundId").asOpt[Long].orElse(job.subjectId).getOrElse(
      throw new IllegalStateException(s"Job ${job.id}: no round"))

  def jurorOf(job: Job): Long =
    (job.params \ "add").asOpt[Long].getOrElse(throw new IllegalStateException(s"Job ${job.id}: no juror to add"))

  def byOf(job: Job): Option[Long] = (job.params \ "by").asOpt[Long]

  /** The donors ticked on the page: their unrated files may go to the new juror. */
  def donorsOf(job: Job): Seq[Long] = (job.params \ "donors").asOpt[Seq[Long]].getOrElse(Nil).distinct

  def stateOf(job: Job): RebalanceState = job.state.validate[RebalanceState].getOrElse(RebalanceState())

  def filesGiven(job: Job): Long = stateOf(job).filesGiven

  /** The round's flags that decide whether it is running, read with its row locked. */
  final case class RoundRun(active: Boolean, distributing: Boolean, freezeCount: Int, activeBeforeFreeze: Boolean) {

    /** Frozen by a job other than this one (a distribution reading it, or another rebalance). */
    def frozenByOther(frozenByUs: Boolean): Boolean = freezeCount > (if (frozenByUs) 1 else 0)

    /** Open to its jurors (or held paused by this job): active, not being created. */
    def running(frozenByUs: Boolean): Boolean =
      !distributing && (if (frozenByUs) activeBeforeFreeze else active && freezeCount == 0)
  }

  /** Locks the round for update (the lock order's first, [[RoundWrites]]) and reads its flags. */
  def lockRun(roundId: Long)(implicit session: DBSession): Option[RoundRun] = {
    val run = sql"""SELECT active, distributing, freeze_count, active_before_freeze FROM round WHERE id = $roundId FOR UPDATE"""
      .map(rs => RoundRun(rs.booleanOpt(1).getOrElse(false), rs.booleanOpt(2).getOrElse(false), rs.intOpt(3).getOrElse(0),
        rs.booleanOpt(4).getOrElse(false)))
      .single()
    RoundWrites.recordRounds(Seq(roundId))
    run
  }

  /** The juror an add may give files to: an active jury member of the contest whose account isn't
    * deleted.
    */
  def eligibleJuror(contestId: Long, jurorId: Long)(implicit session: DBSession = AutoSession): Option[User] =
    // the membership's role exactly `jury` (contestMembers' filter), so an active one
    User.contestMembers(contestId, roles = Some(Seq(User.JURY_ROLE)), ids = Some(Seq(jurorId))).find(_.deletedAt.isEmpty)

  /** Why adding `jurorId` to the round is refused now, if it is: checked on the page, on the POST
    * and by every step, each under the round's lock (but the page). In this order: frozen by
    * another job; not running; no round_user rows (a legacy round); the juror not an active jury
    * member of the round's contest; a ticked donor not a juror of the round (round_user, active or
    * not). A juror already active in the round isn't refused: the add tops them up (N = 0: the files
    * they don't have; N > 0: the water-filling counts their own unrated files).
    *
    * @param frozenByUs the job holds the round frozen (its own pause)
    */
  def refusal(round: Round, run: RoundRun, jurorId: Long, donors: Seq[Long], frozenByUs: Boolean)(implicit
      session: DBSession
  ): Option[JobError] = {
    val n = round.number.toString
    if (run.frozenByOther(frozenByUs)) Some(JobError(FrozenKey, Seq(n)))
    else if (!run.running(frozenByUs)) Some(JobError(NotRunningKey, Seq(n)))
    else if (!hasRoundUsers(round.getId)) Some(JobError(LegacyKey, Seq(n)))
    else if (eligibleJuror(round.contestId, jurorId).isEmpty) Some(JobError(NotJurorKey, Seq(jurorId.toString)))
    else donors.find(d => !isRoundJuror(round.getId, d)).map(d => JobError(DonorKey, Seq(d.toString, n)))
  }

  def hasRoundUsers(roundId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId LIMIT 1".map(_ => ()).single().isDefined

  def isRoundJuror(roundId: Long, jurorId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $jurorId".map(_ => ()).single().isDefined

  def isActiveJuror(roundId: Long, jurorId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $jurorId AND active = TRUE"
      .map(_ => ()).single().isDefined

  /** The juror's rows of the round, in `vote` and `vote_aside`: a file deleted or restored on
    * Commons meanwhile moves its rows between the two, not out of both.
    */
  def jurorRows(roundId: Long, jurorId: Long)(implicit session: DBSession): Long =
    Seq(sqls"vote", sqls"vote_aside").map { table =>
      sql"SELECT COUNT(*) FROM $table WHERE round_id = $roundId AND jury_id = $jurorId".map(_.long(1)).single().getOrElse(0L)
    }.sum

  /** The round's files the juror would get: `in`, not deleted on Commons, no row of the juror in
    * `vote` or `vote_aside`.
    */
  private def toGiveCond(roundId: Long, jurorId: Long): SQLSyntax =
    sqls"""ri.round_id = $roundId AND ri.status = ${RoundImageJdbc.Status.In}
           AND NOT EXISTS (SELECT 1 FROM images i WHERE i.page_id = ri.page_id AND i.deleted_at IS NOT NULL)
           AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = $roundId AND v.page_id = ri.page_id AND v.jury_id = $jurorId)
           AND NOT EXISTS (SELECT 1 FROM vote_aside a WHERE a.round_id = $roundId AND a.page_id = ri.page_id
                           AND a.jury_id = $jurorId)"""

  def toGiveCount(roundId: Long, jurorId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image ri WHERE ${toGiveCond(roundId, jurorId)}".map(_.long(1)).single().getOrElse(0L)

  def toGiveWindow(roundId: Long, jurorId: Long, after: Long, size: Int)(implicit session: DBSession): Seq[Long] =
    sql"""SELECT ri.page_id FROM round_image ri WHERE ${toGiveCond(roundId, jurorId)} AND ri.page_id > $after
          ORDER BY ri.page_id LIMIT $size""".map(_.long(1)).list()

  /** Each juror's unrated rows of the round (`rate = 0` and no criterion rated above 0: a partly
    * rated criteria vote holds the juror's work), of `jurorIds`.
    */
  def unratedCounts(roundId: Long, jurorIds: Seq[Long], receiver: Option[Long] = None)(implicit
      session: DBSession = AutoSession
  ): Map[Long, Long] =
    if (jurorIds.isEmpty) Map.empty
    else
      sql"""SELECT v.jury_id, COUNT(*) FROM vote v
            WHERE v.round_id = $roundId AND v.jury_id IN (${jurorIds.distinct}) AND ${movable(roundId, receiver)}
            GROUP BY v.jury_id""".map(rs => rs.long(1) -> rs.long(2)).list().toMap

  /** A vote row `v` of the round that is unrated work a receiver may take: rate 0, no criterion rated
    * above 0, its file `in` the round and not deleted on Commons (as [[toGiveCond]]); with
    * `receiver`, also a file the receiver has no row of in `vote` or `vote_aside`. The move's
    * candidates and the quotas' counts both use it, so a donor's rows that can't move don't count.
    */
  private def movable(roundId: Long, receiver: Option[Long]): SQLSyntax =
    sqls"""v.rate = 0
           AND NOT EXISTS (SELECT 1 FROM criteria_rate cr WHERE cr.vote_id = v.id AND cr.rate > 0)
           AND EXISTS (SELECT 1 FROM round_image ri WHERE ri.round_id = $roundId AND ri.page_id = v.page_id
                       AND ri.status = ${RoundImageJdbc.Status.In})
           AND NOT EXISTS (SELECT 1 FROM images i WHERE i.page_id = v.page_id AND i.deleted_at IS NOT NULL)""".append(
      receiver.fold(sqls"") { y =>
        sqls"""AND NOT EXISTS (SELECT 1 FROM vote y WHERE y.round_id = $roundId AND y.page_id = v.page_id AND y.jury_id = $y)
           AND NOT EXISTS (SELECT 1 FROM vote_aside a WHERE a.round_id = $roundId AND a.page_id = v.page_id
                           AND a.jury_id = $y)"""
      })

  /** How many unrated files each donor gives the new juror: water-filling from above, so that the
    * donors who give and the new juror end with about the same number of unrated files (the
    * plan's §13 default). With `jurorUnrated` the new juror's own and `donors` (id, unrated), the
    * largest k donors give, k the most for which the k-th largest has at least the level
    * `(jurorUnrated + their sum) / (k + 1)`; each ends at the level's floor (the remainder one more
    * each, the largest first), the new juror at the floor. The quotas sum exactly to what the new
    * juror gets (the floor less what they have). Equal work gives an equal split; a new juror who
    * has as much as the largest donor gets nothing.
    */
  def quotas(jurorUnrated: Long, donors: Seq[(Long, Long)]): Map[Long, Long] = {
    val sorted = donors.sortBy { case (id, n) => (-n, id) }
    val k = (1 to sorted.size).filter { k =>
      sorted(k - 1)._2 * (k + 1) >= jurorUnrated + sorted.take(k).map(_._2).sum
    }.lastOption.getOrElse(0)
    if (k == 0) Map.empty
    else {
      val top = sorted.take(k)
      val total = jurorUnrated + top.map(_._2).sum
      val base = total / (k + 1)
      val extra = (total % (k + 1)).toInt // donors (the largest first) that keep one more
      top.zipWithIndex.map { case ((id, n), i) => id -> (n - base - (if (i < extra) 1 else 0)) }
        .filter(_._2 > 0).toMap
    }
  }
}
