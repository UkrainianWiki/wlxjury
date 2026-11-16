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
  *                        file goes to every juror), "verify" (the count check, then the round
  *                        released)
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
    gaveAny: Boolean = false
)

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
  *  3. "verify": the juror holds exactly the rows before plus those given; the round released.
  *
  * A round where each file goes to N > 0 jurors is refused for now (PR 1b moves donors' unrated
  * files). Rated votes never move. The audit is a `round.admin` INFO line when queued and when
  * done, and the job row (`params`, `state`).
  */
@Singleton
class RoundRebalanceHandler @Inject() (config: Configuration) extends JobHandler with Logging {

  import RoundRebalanceHandler._

  val kind: String = Kind

  override def priority: Boolean = true

  /** Files per "give" step. */
  val chunkRows: Int = config.getOptional[Int]("jobs.roundRebalance.chunkRows").getOrElse(DefaultChunkRows)

  override def title(job: Job): String =
    s"Adding juror ${jurorOf(job)} to round ${job.subjectId.getOrElse("?")}"

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("roundId" -> roundIdOf(job), "jurorId" -> jurorOf(job), "filesGiven" -> st.filesGiven, "phase" -> st.phase)
  }

  /** Hook for tests: runs in a "give" step's transaction before its rows are inserted. */
  protected def beforeGive(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = ()

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
    * those given), as a distribution's Retry rebaselines. After a failed count check it gives
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
      else st.copy(cursor = 0L, filesGiven = 0L, jurorRowsBefore = None, total = None, verifyFailed = false)
    Json.toJsObject(resumed.copy(phase = Phase.Stop, frozen = Nil))
  }

  /** The count check's baselines as of now: what others changed while the job was failed. */
  protected def rebaseline(st: RebalanceState, roundId: Long, jurorId: Long)(implicit session: DBSession): RebalanceState =
    st.copy(jurorRowsBefore = st.jurorRowsBefore.map(_ => jurorRows(roundId, jurorId) - st.filesGiven))

  // ── steps ──────────────────────────────────────────────────────────────────

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    val jurorId = jurorOf(job)
    val st = stateOf(job)
    val run = lockRun(roundId).getOrElse(return failed(job, st, JobError(MissingKey, Seq(roundId.toString))))
    val round = Round.findById(roundId).getOrElse(return failed(job, st, JobError(MissingKey, Seq(roundId.toString))))
    refusal(round, run, jurorId, frozenByUs = st.frozen.contains(roundId)) match {
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
        val total = st.total.getOrElse(st.filesGiven + toGiveCount(roundId, jurorId))
        continue(st.copy(phase = Phase.Give, frozen = frozen, jurorWasActive = wasActive, jurorAdded = added,
          jurorRowsBefore = Some(rowsBefore), total = Some(total)))

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

      case Phase.Verify =>
        val count = jurorRows(roundId, jurorId)
        val expected = st.jurorRowsBefore.getOrElse(0L) + st.filesGiven
        if (count != expected)
          StepResult.Failed(JobError(VerifyKey, Seq(round.number.toString, count.toString, expected.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else {
          Round.release(st.frozen, close = false)
          ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          adminLog.info(s"action=juror_add accountId=${byOf(job).getOrElse(0L)} contestId=${round.contestId} " +
            s"roundId=$roundId jurorId=$jurorId moved=${st.filesGiven} jobId=${job.id} state=done")
          StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), progress(st))
        }

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

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
  val DistributionKey = "job.error.rebalance.distribution"
  val BusyKey = "job.error.rebalance.busy"
  val VerifyKey = "job.error.rebalance.verify"

  def roundIdOf(job: Job): Long =
    (job.params \ "roundId").asOpt[Long].orElse(job.subjectId).getOrElse(
      throw new IllegalStateException(s"Job ${job.id}: no round"))

  def jurorOf(job: Job): Long =
    (job.params \ "add").asOpt[Long].getOrElse(throw new IllegalStateException(s"Job ${job.id}: no juror to add"))

  def byOf(job: Job): Option[Long] = (job.params \ "by").asOpt[Long]

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
    * member of the round's contest; a round whose files go to N > 0 jurors (not yet). A juror
    * already active in the round isn't refused: the add tops them up (the files they don't have).
    *
    * @param frozenByUs the job holds the round frozen (its own pause)
    */
  def refusal(round: Round, run: RoundRun, jurorId: Long, frozenByUs: Boolean)(implicit
      session: DBSession
  ): Option[JobError] = {
    val n = round.number.toString
    if (run.frozenByOther(frozenByUs)) Some(JobError(FrozenKey, Seq(n)))
    else if (!run.running(frozenByUs)) Some(JobError(NotRunningKey, Seq(n)))
    else if (!hasRoundUsers(round.getId)) Some(JobError(LegacyKey, Seq(n)))
    else if (eligibleJuror(round.contestId, jurorId).isEmpty) Some(JobError(NotJurorKey, Seq(jurorId.toString)))
    else if (round.distribution != 0) Some(JobError(DistributionKey, Seq(n)))
    else None
  }

  def hasRoundUsers(roundId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId LIMIT 1".map(_ => ()).single().isDefined

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
}
