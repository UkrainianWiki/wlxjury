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
  * @param quotasSet       "stop" fixed the share (N > 0): `quota` and `waitingQuota` (kept on Retry)
  * @param waitingQuota    "waiting": the files waiting for a juror the new juror takes first, within
  *                        their share ([[RoundRebalanceHandler.addSplit]])
  * @param waitingGiven    "waiting": the waiting files given so far (in `filesGiven` too)
  * @param waitingCursor   "waiting": the last page id a committed window read
  * @param checks          the verify step's invariant baseline ([[VerifyChecks]])
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
    donorRowsBefore: Map[String, Long] = Map.empty,
    quotasSet: Boolean = false,
    waitingQuota: Long = 0L,
    waitingGiven: Long = 0L,
    waitingCursor: Long = 0L,
    checks: VerifyChecks = VerifyChecks()
) {
  def givenBy(donor: Long): Long = given.getOrElse(donor.toString, 0L)
  def quotaOf(donor: Long): Long = quota.getOrElse(donor.toString, 0L)
}

object RebalanceState {
  implicit val format: OFormat[RebalanceState] = Json.using[Json.WithDefaultValues].format[RebalanceState]
}

/** What a `round_rebalance` job's "verify" checks beyond its counts (round-editing plan §5, §7): no new
  * violation of the round's invariants 1-3 ([[db.scalikejdbc.RoundImageInvariants]]: every vote row's
  * file `in`, set-aside rows' files `deleted` or `dq`, no votes for those) since its first "stop", and
  * the files a reassign left waiting for a juror still in the round.
  *
  * @param violationsBefore per invariant ("1", "2", "3"), the round's violations when the first "stop"
  *                         ran, bounded ([[ViolationBaseline]]); None for a job queued before this
  *                         check (not checked)
  * @param waitingPages     a reassign's files left waiting, the first [[RoundRebalanceHandler.MaxWaitingChecked]]
  */
final case class VerifyChecks(violationsBefore: Option[Map[String, ViolationBaseline]] = None, waitingPages: Seq[Long] = Nil)

/** An invariant's violations at the baseline: how many, and the first [[ViolationBaseline.MaxPages]]
  * pages. While `pages` lists them all, a violation of another page is new; past that, only a count
  * above `count` is (the job state stays small however broken a round is).
  */
final case class ViolationBaseline(count: Long, pages: Seq[Long]) {
  def complete: Boolean = pages.size.toLong >= count

  /** Of `now` (the invariant's pages now, sorted), the new ones and how many: every page not listed while
    * the list is complete; else, if there are more than `count`, the excess, with no page named (which
    * of the unlisted ones are new is unknown).
    */
  def fresh(now: Seq[Long]): (Long, Seq[Long]) =
    if (complete) {
      val unlisted = now.filterNot(pages.toSet)
      (unlisted.size.toLong, unlisted)
    } else if (now.size > count) (now.size - count, Nil)
    else (0L, Nil)
}

object ViolationBaseline {
  val MaxPages = 50
  implicit val format: OFormat[ViolationBaseline] = Json.using[Json.WithDefaultValues].format[ViolationBaseline]
}

object VerifyChecks {
  private val base: OFormat[VerifyChecks] = Json.using[Json.WithDefaultValues].format[VerifyChecks]

  /** Each field read on its own, a wrong type taken as absent: a state written by an earlier build (its
    * `violationsBefore` a list of strings) must not fail the whole job state, which would read as the
    * default and run the job from its start again. The baseline is then None (not checked).
    */
  implicit val format: OFormat[VerifyChecks] = OFormat(
    Reads { js =>
      JsSuccess(VerifyChecks(
        violationsBefore = (js \ "violationsBefore").validate[Map[String, ViolationBaseline]].asOpt,
        waitingPages = (js \ "waitingPages").validate[Seq[Long]].getOrElse(Nil)))
    },
    base)
}

/** Adds a juror to a running or a stopped round, as a [[Job]] of kind `round_rebalance` whose subject is the
  * round (docs/plans/2026-11-16-round-editing.md, §5). It shares the round's dedupe key with its
  * distribution ([[RoundDistributionHandler.dedupeKey]]): the key's unique index makes the two
  * exclude each other, and every check of a round "being distributed" sees it too; the
  * distribution's readers of its latest job (retry, resubmit, lineage) read their own kind only.
  *
  * Votes move only while the round isn't open (the 2026-11-03 rule), so the job pauses the round
  * itself: its first step freezes it ([[Round.freeze]]) and the last releases it; failure and
  * Cancel release it, and Retry freezes it again from its first step. While it is paused its
  * jurors keep it in their menus and get the "paused" page and the vote error `round_paused`
  * ([[Round.pausedCond]]). A stopped round (D3 revised 2026-11-25) is frozen and released the same
  * way, which keeps it stopped (`active_before_freeze = 0`), also on failure, Cancel, Retry and a
  * restart's recovery: its jurors keep `round_closed`. Its steps are claimed before other kinds'
  * ([[priority]]).
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
  *  2'. "waiting" (a round where each file goes to N > 0 jurors): the files waiting for a juror
  *     ([[RoundReassign.waitingForCond]]: `in`, not deleted on Commons, fewer than N vote rows, none
  *     of the new juror's in `vote` or `vote_aside`) go to the new juror first, within their share
  *     ([[RoundRebalanceHandler.addSplit]]), by page id, at most `chunkRows` a step; each file's number
  *     of vote rows is checked one more in the same transaction, else the step rolls back;
  *  2''. "move" (N > 0), then: the ticked donors'
  *     (`params.donors`) unrated rows (`rate = 0` and no criterion rated above 0), last assigned
  *     first, each donor up to its quota (the rest of the share: water-filling,
  *     [[RoundRebalanceHandler.quotas]], donors end with about as many unrated files as the new juror;
  *     with waiting files taken, [[RoundRebalanceHandler.fromTop]]), at most `chunkRows` rows a step. The
  *     candidates' images are read in share mode, then their rows locked (`FOR UPDATE`, by id); a row
  *     rated meanwhile is skipped, and a row moves only if the new juror has no row of its page in
  *     `vote` or `vote_aside`. The new juror's row is written first, then the donor's deleted with its
  *     criteria rates (none above 0), then the files' `round_image` rows synced; each page's number
  *     of vote rows is checked unchanged in the same transaction, else the step rolls back. A ticked
  *     stopped donor gives and stays stopped;
  *  3. "verify": the juror holds exactly the rows before plus those given, each donor exactly its
  *     rows before less those it gave, no new violation of the round's invariants 1-3
  *     ([[VerifyChecks]]); the round released.
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

  override def title(job: Job): String = fromOf(job) match {
    case Some(from) => s"Reassigning juror $from's unrated files in round ${job.subjectId.getOrElse("?")}"
    case None       => s"Adding juror ${jurorOf(job)} to round ${job.subjectId.getOrElse("?")}"
  }

  override def details(job: Job): JsObject = fromOf(job) match {
    case Some(from) =>
      val st = reassignStateOf(job)
      Json.obj("roundId" -> roundIdOf(job), "fromId" -> from, "moved" -> st.moved, "waiting" -> st.waiting,
        "removed" -> st.removed, "aside" -> st.asideMoved, "phase" -> st.phase, "given" -> st.given)
    case None =>
      val st = stateOf(job)
      Json.obj("roundId" -> roundIdOf(job), "jurorId" -> jurorOf(job), "filesGiven" -> st.filesGiven, "phase" -> st.phase,
        "given" -> st.given, "waiting" -> st.waitingGiven)
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

  /** Hook for tests: runs in a "waiting" step's transaction after the new juror's rows were written,
    * before its check.
    */
  protected def afterTakeWaiting(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = ()

  // ── failure, cancel, retry ─────────────────────────────────────────────────

  override def onFailed(job: Job, ctx: StepContext): JsObject =
    if (fromOf(job).isDefined) reassignEnd(job, ctx, cancelled = false) else end(job, ctx, cancelled = false)

  override def onCancelled(job: Job, ctx: StepContext): JsObject =
    if (fromOf(job).isDefined) reassignEnd(job, ctx, cancelled = true) else end(job, ctx, cancelled = true)

  /** Releases the round if the job froze it; on Cancel, a juror the job started who wasn't
    * active before is stopped again, only if it gave them nothing: a juror holding files the job
    * gave stays started, so no file it moved sits with a stopped juror (to give them the rest:
    * Stop, then "Start and give a fair share…"). The round first, then round_user, as the "stop" step.
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
  override def onRetry(job: Job, ctx: StepContext): JsObject =
    if (fromOf(job).isDefined) reassignRetry(job, ctx) else addRetry(job, ctx)

  private def addRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    lockRun(roundId)
    Round.release(st.frozen, close = false)
    val resumed =
      if (!st.verifyFailed) rebaseline(st, roundId, jurorOf(job))
      // fresh accounting and, for a move, fresh quotas from the files as they are now
      else st.copy(cursor = 0L, filesGiven = 0L, jurorRowsBefore = None, total = None, verifyFailed = false,
        quota = Map.empty, given = Map.empty, cursors = Map.empty, exhausted = Nil, donorRowsBefore = Map.empty,
        quotasSet = false, waitingQuota = 0L, waitingGiven = 0L, waitingCursor = 0L)
    Json.toJsObject(resumed.copy(phase = Phase.Stop, frozen = Nil))
  }

  /** The count check's baselines as of now: what others changed while the job was failed. */
  protected def rebaseline(st: RebalanceState, roundId: Long, jurorId: Long)(implicit session: DBSession): RebalanceState =
    st.copy(
      jurorRowsBefore = st.jurorRowsBefore.map(_ => jurorRows(roundId, jurorId) - st.filesGiven),
      // each donor's rows now, and those it gave back on
      donorRowsBefore = st.donorRowsBefore.map { case (d, _) => d -> (jurorRows(roundId, d.toLong) + st.givenBy(d.toLong)) })

  // ── steps ──────────────────────────────────────────────────────────────────

  def step(job: Job, ctx: StepContext): StepResult =
    if (fromOf(job).isDefined) reassignStep(job, ctx) else addStep(job, ctx)

  private def addStep(job: Job, ctx: StepContext): StepResult = {
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
        // the juror's users row share-locked and re-checked before anything is written (A-4): an
        // account retired since the add was queued is refused; one being retired waits for this
        // step, then sees the round_user row below and is refused
        // in an account join (J-2): refused before the shared lock (the join holds the row) and after it
        if (AccountJoinJdbc.joining(Seq(jurorId)).nonEmpty) return failed(job, st, JobError(JoiningKey, Seq(jurorId.toString)))
        if (!User.lockLive(Seq(jurorId)).contains(jurorId))
          return failed(job, st, JobError(NotJurorKey, Seq(jurorId.toString)))
        if (AccountJoinJdbc.joining(Seq(jurorId)).nonEmpty) return failed(job, st, JobError(JoiningKey, Seq(jurorId.toString)))
        val frozen =
          if (st.frozen.contains(roundId)) st.frozen
          else { Round.freeze(Seq(roundId)); Seq(roundId) }
        // the juror's row active, in every "stop" (a Retry's too); its state before, the first time
        // a NULL (a schema before V108) is stopped
        val before = sql"""SELECT active FROM round_user WHERE round_id = $roundId AND user_id = $jurorId FOR UPDATE"""
          .map(_.booleanOpt(1).getOrElse(false)).single()
        sql"""INSERT INTO round_user (round_id, user_id, role, active) VALUES ($roundId, $jurorId, ${User.JURY_ROLE}, TRUE)
              ON DUPLICATE KEY UPDATE active = TRUE""".update()
        val (wasActive, added) = (if (st.jurorAdded) st.jurorWasActive else before, true)
        val rowsBefore = st.jurorRowsBefore.getOrElse(jurorRows(roundId, jurorId))
        val stopped = st.copy(frozen = frozen, jurorWasActive = wasActive, jurorAdded = added,
          jurorRowsBefore = Some(rowsBefore), checks = baseline(roundId, st.checks))
        if (round.distribution == 0) {
          val total = st.total.getOrElse(st.filesGiven + toGiveCount(roundId, jurorId))
          continue(stopped.copy(phase = Phase.Give, total = Some(total)))
        } else {
          // the share once, from the unrated work as it is when the round is paused (kept on Retry): the
          // files waiting for a juror first, the rest from the donors
          val withQuotas =
            if (st.quotasSet || st.quota.nonEmpty || st.given.nonEmpty) stopped
            else {
              // the donors' files the receiver could take, and the receiver's own unrated files
              val unrated = unratedCounts(roundId, donors, receiver = Some(jurorId))
              val own = unratedCounts(roundId, Seq(jurorId)).getOrElse(jurorId, 0L)
              val split = addSplit(own, donors.map(d => d -> unrated.getOrElse(d, 0L)),
                RoundReassign.waitingForCount(roundId, round.distribution, jurorId))
              stopped.copy(quotasSet = true, quota = split.fromDonors.map { case (d, n) => d.toString -> n },
                waitingQuota = split.waiting,
                donorRowsBefore = donors.map(d => d.toString -> jurorRows(roundId, d)).toMap,
                total = Some(split.total))
            }
          continue(withQuotas.copy(phase = if (withQuotas.waitingGiven < withQuotas.waitingQuota) Phase.Waiting else Phase.Move))
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

      case Phase.Waiting => takeWaiting(job, ctx, round, jurorId, st)

      case Phase.Move => move(job, ctx, round, jurorId, donors, st)

      case Phase.Verify =>
        val count = jurorRows(roundId, jurorId)
        val expected = st.jurorRowsBefore.getOrElse(0L) + st.filesGiven
        // each donor lost exactly the rows it gave
        val donorMismatch = st.donorRowsBefore.toSeq.sortBy(_._1.toLong).collectFirst {
          case (d, before) if jurorRows(roundId, d.toLong) != before - st.givenBy(d.toLong) => d.toLong
        }
        lazy val broken = invariantsFailure(round, st.checks)
        if (count != expected)
          StepResult.Failed(JobError(VerifyKey, Seq(round.number.toString, count.toString, expected.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else if (donorMismatch.isDefined)
          StepResult.Failed(JobError(DonorVerifyKey, Seq(round.number.toString, donorMismatch.get.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else if (broken.isDefined)
          StepResult.Failed(broken.get, Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
        else {
          Round.release(st.frozen, close = false)
          ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          adminLog.info(s"action=juror_add accountId=${byOf(job).getOrElse(0L)} contestId=${round.contestId} " +
            s"roundId=$roundId jurorId=$jurorId moved=${st.filesGiven} " +
            (if (st.waitingGiven > 0) s"waiting=${st.waitingGiven} " else "") +
            (if (st.given.nonEmpty) s"from=${st.given.toSeq.sortBy(_._1.toLong).map { case (d, n) => s"$d:$n" }.mkString(",")} "
             else "") + s"jobId=${job.id} state=done")
          StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), progress(st))
        }

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  /** One "waiting" step (N > 0): the next window of the files waiting for a juror that the new juror
    * could take, by page id, up to what is left of `waitingQuota`; their images read in share mode
    * (their monument ids; one marked deleted on Commons meanwhile left out), the juror's unrated rows
    * inserted, the files' `round_image` rows kept with their origin. The check, in the same
    * transaction: each file given has exactly one more vote row, at most N; otherwise the step rolls
    * back. Then "move" when the share's waiting part is given or none is left.
    */
  private def takeWaiting(job: Job, ctx: StepContext, round: Round, jurorId: Long, st: RebalanceState)(implicit
      session: DBSession
  ): StepResult = {
    val roundId = round.getId
    val want = math.min(chunkRows.toLong, st.waitingQuota - st.waitingGiven).toInt
    val window =
      if (want <= 0) Nil else RoundReassign.waitingForWindow(roundId, round.distribution, jurorId, st.waitingCursor, want)
    if (window.isEmpty) continue(st.copy(phase = Phase.Move))
    else {
      val pages = window.map(_._1)
      val images = sql"""SELECT page_id, monument_id FROM images WHERE page_id IN ($pages) AND deleted_at IS NULL
                         ORDER BY page_id LOCK IN SHARE MODE""".map(rs => rs.long(1) -> rs.stringOpt(2)).list()
      val rows = images.map { case (pageId, monumentId) =>
        Vote(pageId = pageId, juryId = jurorId, roundId = roundId, rate = 0, monumentId = monumentId)
      }
      val given = rows.map(_.pageId)
      if (rows.nonEmpty) {
        val before = votesPerPage(roundId, given)
        DistributeImages.insertRows(rows, RoundImageJdbc.Origin.Manual)
        afterTakeWaiting(roundId, given)
        // the check: each file given has one more vote row, at most N; otherwise the whole step rolls back
        val after = votesPerPage(roundId, given)
        val wrong = given.filter { p =>
          after.getOrElse(p, 0) != before.getOrElse(p, 0) + 1 || after.getOrElse(p, 0) > round.distribution
        }
        if (wrong.nonEmpty)
          throw new IllegalStateException(s"Job ${job.id}: round $roundId's vote rows per file changed in giving waiting " +
            s"files (${wrong.take(10).mkString(", ")})")
        ctx.afterCommit(RoundImageCounts.invalidate(roundId))
      }
      continue(st.copy(waitingCursor = pages.max, waitingGiven = st.waitingGiven + rows.size,
        filesGiven = st.filesGiven + rows.size, gaveAny = st.gaveAny || rows.nonEmpty))
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

  // ── reassigning a juror's unrated files (§7, params.from) ──────────────────

  /** Hook for tests: runs in a reassign "move" step's transaction, its rows locked, before they move. */
  protected def beforeReassign(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit = ()

  /** Hook for tests: runs in a reassign "verify" step's transaction, the round locked, before its rescan. */
  protected def beforeReassignVerify(roundId: Long)(implicit session: DBSession): Unit = ()

  /** Releases the round if the job froze it; on Cancel, if no row moved yet, the juror and the
    * receivers it started are put back as they were (the juror active again if they were, the
    * restarted receivers stopped again). Once a row moved, the juror stays stopped and the receivers
    * started: the files moved are theirs. The round first, then round_user, as the "stop" step.
    */
  private def reassignEnd(job: Job, ctx: StepContext, cancelled: Boolean): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = reassignStateOf(job)
    val roundId = roundIdOf(job)
    lockRun(roundId)
    Round.release(st.frozen, close = false)
    if (cancelled && st.stopped && !st.movedAny) {
      if (st.fromWasActive.contains(true))
        sql"UPDATE round_user SET active = TRUE WHERE round_id = $roundId AND user_id = ${fromOf(job).get}".update()
      if (st.restarted.nonEmpty)
        sql"UPDATE round_user SET active = FALSE WHERE round_id = $roundId AND user_id IN (${st.restarted})".update()
    }
    if (st.frozen.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
    Json.toJsObject(st.copy(frozen = Nil))
  }

  /** A reassign cancelled once a row had moved: the juror stays stopped with the unrated files not yet
    * moved; the admin is told to run "Reassign files…" again for the rest (the cancel's flash).
    */
  override def cancelledNote(job: Job): Option[JobError] =
    fromOf(job).flatMap { from =>
      val st = reassignStateOf(job)
      // rows removed (a round where every file goes to every juror: nothing is given), or the files
      // handled: given, left waiting, given aside
      val everyJuror = Round.findById(roundIdOf(job)).exists(_.distribution == 0)
      Option.when(st.movedAny)(
        if (everyJuror) JobError(CancelledRemovedKey, Seq((st.removed + st.asideRemoved).toString, from.toString))
        else JobError(CancelledPartwayKey, Seq((st.moved + st.waiting + st.asideMoved + st.asideRemoved).toString, from.toString)))
    }

  /** As [[addRetry]]: releases what a failure left frozen and resumes from "stop". The count check's
    * baselines move by what others changed meanwhile; after a failed count check the accounting and
    * the quotas start again from the files as they are now.
    */
  private def reassignRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = reassignStateOf(job)
    val roundId = roundIdOf(job)
    lockRun(roundId)
    Round.release(st.frozen, close = false)
    val resumed =
      if (!st.verifyFailed)
        st.copy(
          receiverRowsBefore = st.receiverRowsBefore.map { case (r, _) =>
            r -> (jurorRows(roundId, r.toLong) - st.givenTo(r.toLong) - st.asideGivenTo(r.toLong))
          },
          fromRatedBefore = st.fromRatedBefore.map(_ => RoundReassign.ratedCount(roundId, fromOf(job).get)))
      else
        st.copy(quotasSet = false, quota = Map.empty, given = Map.empty, asideGiven = Map.empty, cursor = 0L, waiting = 0L,
          removed = 0L, asideRemoved = 0L, receiverRowsBefore = Map.empty, fromRatedBefore = None, total = None,
          repassed = false, verifyFailed = false)
    Json.toJsObject(resumed.copy(phase = Phase.Stop, frozen = Nil))
  }

  private def reassignStep(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    val from = fromOf(job).get
    val receivers = receiversOf(job).filterNot(_ == from)
    val st = reassignStateOf(job)
    def fail(error: JobError) = {
      logger.warn(s"Job ${job.id} ($Kind): round $roundId refused: ${error.describe}")
      StepResult.Failed(error, Json.toJsObject(st), reassignProgress(st))
    }
    val run = lockRun(roundId).getOrElse(return fail(JobError(MissingKey, Seq(roundId.toString))))
    val round = Round.findById(roundId).getOrElse(return fail(JobError(MissingKey, Seq(roundId.toString))))
    reassignRefusal(round, run, from, receivers, frozenByUs = st.frozen.contains(roundId)).foreach(e => return fail(e))
    val shares = round.distribution > 0
    val to = if (shares) receivers else Nil

    st.phase match {
      case Phase.Stop =>
        // enough other jurors to take the files, checked again under the round's lock before the juror is
        // stopped (with every file to every juror the others aren't the job's receivers): one stopped,
        // removed or no longer a member meanwhile refuses, nothing changed; at each "stop", a Retry's too
        // the juror's account id, not their name, in job.error and the log lines (as FromKey and ReceiverKey)
        aloneRefusal(round, from, from.toString).foreach(e => return fail(e))
        // the receivers' users rows share-locked and re-checked (A-4), outside an account join (J-2),
        // as the add checks its juror
        val accounts = (to :+ from).distinct
        AccountJoinJdbc.joining(accounts).headOption.foreach(a => return fail(JobError(JoiningKey, Seq(a.toString))))
        val live = User.lockLive(to)
        to.find(r => !live.contains(r)).foreach(r => return fail(JobError(ReceiverKey, Seq(r.toString, round.number.toString))))
        AccountJoinJdbc.joining(accounts).headOption.foreach(a => return fail(JobError(JoiningKey, Seq(a.toString))))
        val frozen =
          if (st.frozen.contains(roundId)) st.frozen
          else { Round.freeze(Seq(roundId)); Seq(roundId) }
        // the jurors' rows locked in id order; the juror stopped, the ticked receivers started (D1), in
        // every "stop" (a Retry's too); their states before, the first time
        val before = sql"""SELECT user_id, active FROM round_user WHERE round_id = $roundId AND user_id IN (${accounts.sorted})
                           ORDER BY user_id FOR UPDATE""".map(rs => rs.long(1) -> rs.booleanOpt(2).getOrElse(false)).list().toMap
        sql"UPDATE round_user SET active = FALSE WHERE round_id = $roundId AND user_id = $from".update()
        if (to.nonEmpty) sql"UPDATE round_user SET active = TRUE WHERE round_id = $roundId AND user_id IN ($to)".update()
        val restartedNow = to.filter(r => before.get(r).contains(false))
        val stopped = st.copy(frozen = frozen, stopped = true, checks = baseline(roundId, st.checks),
          fromWasActive = if (st.stopped) st.fromWasActive else before.get(from),
          restarted = if (st.stopped) (st.restarted ++ restartedNow).distinct else restartedNow)
        val withQuotas =
          if (st.quotasSet) stopped
          else {
            val unratedVotes = RoundReassign.unratedCount(roundId, from, sqls"vote")
            val unratedAside = RoundReassign.unratedCount(roundId, from, sqls"vote_aside")
            val open = unratedCounts(roundId, to)
            stopped.copy(quotasSet = true,
              quota = RoundReassign.fillQuotas(unratedVotes, to.map(r => r -> open.getOrElse(r, 0L))).map { case (r, n) => r.toString -> n },
              receiverRowsBefore = receivers.map(r => r.toString -> jurorRows(roundId, r)).toMap,
              fromRatedBefore = Some(RoundReassign.ratedCount(roundId, from)),
              total = Some(unratedVotes + unratedAside))
          }
        reassignContinue(withQuotas.copy(phase = Phase.Move))

      case Phase.Move =>
        val candidates = RoundReassign.unratedRows(roundId, from, st.cursor, chunkRows)
        if (candidates.isEmpty) reassignContinue(st.copy(phase = Phase.Aside))
        else {
          beforeLock(roundId, candidates.map(_._1))
          // the lock order: the round (held), the files' images in share mode, then the rows by id
          val pages = candidates.map(_._2).distinct.sorted
          sql"SELECT page_id FROM images WHERE page_id IN ($pages) ORDER BY page_id LOCK IN SHARE MODE".map(_.long(1)).list()
          val ids = candidates.map(_._1).sorted
          val locked = sql"""SELECT id, page_id, jury_id, rate, monument_id FROM vote WHERE id IN ($ids) ORDER BY id FOR UPDATE"""
            .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4), rs.stringOpt(5))).list()
          val criteriaRated = sql"SELECT DISTINCT vote_id FROM criteria_rate WHERE vote_id IN ($ids) AND rate > 0 LOCK IN SHARE MODE"
            .map(_.long(1)).list().toSet
          // rated just before its lock (the juror's vote in flight before the stop): it stays
          val moving = locked.filter { case (id, _, jury, rate, _) => rate == 0 && jury == from && !criteriaRated(id) }
          val (choices, _) = RoundReassign.allocate(moving.map(_._2),
            RoundReassign.holders(roundId, moving.map(_._2), to), to, st.remaining(to))
          val receiverOf = choices.toMap
          if (moving.nonEmpty) {
            beforeReassign(roundId, moving.map(_._1))
            val movingPages = moving.map(_._2).sorted
            val before = votesPerPage(roundId, movingPages)
            // the receivers' rows first, then the juror's rows with their (unrated) criteria rates
            VoteJdbc.batchInsert(moving.flatMap { case (_, page, _, _, monument) =>
              receiverOf.get(page).flatten.map(r => Vote(pageId = page, juryId = r, roundId = roundId, rate = 0, monumentId = monument))
            }, RoundImageJdbc.Origin.Manual)
            val movedIds = moving.map(_._1)
            RoundWrites.requireRounds(Seq(roundId))
            sql"DELETE FROM criteria_rate WHERE vote_id IN ($movedIds)".update()
            sql"DELETE FROM vote WHERE id IN ($movedIds)".update()
            // a file left with no vote keeps its row and status (P3-1): it waits for a juror
            RoundImageJdbc.syncPairs(movingPages.map(roundId -> _))
            afterMove(roundId, movingPages)
            // the check: a file given keeps its number of vote rows, one left waiting has one less
            val after = votesPerPage(roundId, movingPages)
            val wrong = movingPages.filter { p =>
              after.getOrElse(p, 0) != before.getOrElse(p, 0) - (if (receiverOf.get(p).flatten.isDefined) 0 else 1)
            }
            if (wrong.nonEmpty)
              throw new IllegalStateException(s"Job ${job.id}: round $roundId's vote rows per file changed in a reassign " +
                s"(${wrong.take(10).mkString(", ")})")
            ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          }
          val givenNow = choices.flatMap(_._2).groupMapReduce(identity)(_ => 1L)(_ + _)
          val unplaced = choices.count(_._2.isEmpty).toLong
          // the files left waiting, for "verify" to check they are still `in`
          val leftWaiting = if (shares) choices.collect { case (p, None) => p } else Nil
          reassignContinue(st.copy(
            checks = st.checks.copy(waitingPages = (st.checks.waitingPages ++ leftWaiting).distinct.take(MaxWaitingChecked)),
            cursor = candidates.map(_._1).max,
            given = givenNow.foldLeft(st.given) { case (acc, (r, n)) => acc.updated(r.toString, st.givenTo(r) + n) },
            waiting = st.waiting + (if (shares) unplaced else 0L),
            removed = st.removed + (if (shares) 0L else unplaced),
            movedAny = st.movedAny || moving.nonEmpty))
        }

      case Phase.Aside =>
        // the juror's unrated rows aside (files deleted on Commons or disqualified): each goes to an
        // eligible receiver in place (its id kept), so a restore or requalify gives the file to them, not
        // to the stopped juror; with none, it is deleted. The files' images in share mode first, then
        // the rows (vote_aside before criteria_rate, the lock order)
        val plain = sql"""SELECT v.id, v.page_id FROM vote_aside v WHERE v.round_id = $roundId AND v.jury_id = $from
                          AND ${RoundReassign.unrated(sqls"v")} ORDER BY v.id LIMIT $chunkRows"""
          .map(rs => rs.long(1) -> rs.long(2)).list()
        if (plain.isEmpty) reassignContinue(st.copy(phase = Phase.Verify))
        else {
          val pages = plain.map(_._2).distinct.sorted
          sql"SELECT page_id FROM images WHERE page_id IN ($pages) ORDER BY page_id LOCK IN SHARE MODE".map(_.long(1)).list()
          val ids = plain.map(_._1).sorted
          val locked = sql"SELECT id, page_id, jury_id, rate FROM vote_aside WHERE id IN ($ids) ORDER BY id FOR UPDATE"
            .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4))).list()
          val criteriaRated = sql"SELECT DISTINCT vote_id FROM criteria_rate WHERE vote_id IN ($ids) AND rate > 0 LOCK IN SHARE MODE"
            .map(_.long(1)).list().toSet
          val rows = locked.filter { case (id, _, jury, rate) => rate == 0 && jury == from && !criteriaRated(id) }
          val remaining = st.remaining(to).map { case (r, n) => r -> (n - st.asideGivenTo(r)) }
          val (choices, _) = RoundReassign.allocate(rows.map(_._2), RoundReassign.holders(roundId, rows.map(_._2), to), to, remaining)
          val receiverOf = choices.toMap
          val ofRows = rows.map(_._1)
          if (ofRows.nonEmpty) {
            RoundWrites.requireRounds(Seq(roundId))
            sql"DELETE FROM criteria_rate WHERE vote_id IN ($ofRows)".update()
            rows.foreach { case (id, page, _, _) =>
              receiverOf.get(page).flatten match {
                case Some(r) => sql"UPDATE vote_aside SET jury_id = $r WHERE id = $id".update()
                case None    => sql"DELETE FROM vote_aside WHERE id = $id".update()
              }
            }
            RoundImageJdbc.syncPairs(rows.map(r => roundId -> r._2).distinct)
            ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          }
          val givenNow = choices.flatMap(_._2).groupMapReduce(identity)(_ => 1L)(_ + _)
          val next = st.copy(
            asideGiven = givenNow.foldLeft(st.asideGiven) { case (acc, (r, n)) => acc.updated(r.toString, st.asideGivenTo(r) + n) },
            asideRemoved = st.asideRemoved + choices.count(_._2.isEmpty),
            movedAny = st.movedAny || rows.nonEmpty)
          // rows rated meanwhile stay; fewer than asked: none left
          reassignContinue(if (plain.size < chunkRows) next.copy(phase = Phase.Verify) else next)
        }

      case Phase.Verify =>
        beforeReassignVerify(roundId)
        // rescanned from the start: a file restored or requalified meanwhile brings back a row the
        // windows had passed
        val left = RoundReassign.unratedCount(roundId, from, sqls"vote") + RoundReassign.unratedCount(roundId, from, sqls"vote_aside")
        if (left > 0 && !st.repassed) reassignContinue(st.copy(phase = Phase.Move, cursor = 0L, repassed = true))
        else if (left > 0)
          StepResult.Failed(JobError(ReassignVerifyKey, Seq(round.number.toString, from.toString, left.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)), reassignProgress(st))
        else {
          val receiverMismatch = st.receiverRowsBefore.toSeq.sortBy(_._1.toLong).collectFirst {
            case (r, before) if jurorRows(roundId, r.toLong) != before + st.givenTo(r.toLong) + st.asideGivenTo(r.toLong) => r.toLong
          }
          val ratedNow = RoundReassign.ratedCount(roundId, from)
          // the files left waiting still `in`, and no new violation of the round's invariants 1-3
          lazy val broken = invariantsFailure(round, st.checks)
          if (receiverMismatch.isDefined)
            StepResult.Failed(JobError(ReceiverVerifyKey, Seq(round.number.toString, receiverMismatch.get.toString)),
              Json.toJsObject(st.copy(verifyFailed = true)), reassignProgress(st))
          else if (st.fromRatedBefore.exists(_ != ratedNow))
            StepResult.Failed(JobError(FromVerifyKey, Seq(round.number.toString, from.toString)),
              Json.toJsObject(st.copy(verifyFailed = true)), reassignProgress(st))
          else if (broken.isDefined)
            StepResult.Failed(broken.get, Json.toJsObject(st.copy(verifyFailed = true)), reassignProgress(st))
          else {
            Round.release(st.frozen, close = false)
            ctx.afterCommit(RoundImageCounts.invalidate(roundId))
            val toText = (st.given.keySet ++ st.asideGiven.keySet).toSeq.sortBy(_.toLong)
              .map(r => s"$r:${st.givenTo(r.toLong) + st.asideGivenTo(r.toLong)}").mkString(",")
            adminLog.info(s"action=juror_reassign accountId=${byOf(job).getOrElse(0L)} contestId=${round.contestId} " +
              s"roundId=$roundId jurorId=$from moved=${st.moved} waiting=${st.waiting} removed=${st.removed} " +
              s"aside=${st.asideMoved} asideRemoved=${st.asideRemoved} " + (if (toText.nonEmpty) s"to=$toText " else "") +
              s"jobId=${job.id} state=done")
            StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), reassignProgress(st))
          }
        }

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  private def reassignContinue(st: ReassignState) = StepResult.Continue(Json.toJsObject(st), reassignProgress(st))

  private def reassignProgress(st: ReassignState) =
    Progress(done = Some(st.done), total = st.total, unit = Some("files"))

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
    /** A reassign's: the juror's unrated rows in vote_aside. */
    val Aside = "aside"
    /** An add's (N > 0): the files waiting for a juror, before "move". */
    val Waiting = "waiting"
    val Verify = "verify"
  }

  /** The audit lines: queued and done (`action=juror_add ...`). */
  val adminLog: Logger = Logger("round.admin")

  // refusals: job error keys (job.error.*, shown by the job status and the add page)
  val MissingKey = "job.error.rebalance.missing"
  /** "Isn't running": no longer given (a stopped round takes a juror since 2026-11-25); kept for the
    * jobs that failed with it before.
    */
  val NotRunningKey = "job.error.rebalance.not.running"
  /** The round is being set up (`distributing`: being created, its creation failed or was cancelled;
    * held in review has its own path, [[RoundRebalanceService.add]]): its jurors are chosen there.
    */
  val SettingUpKey = "job.error.rebalance.setting.up"
  /** The round's contest is retired: its jurors don't change (contest series plan §3.1). */
  val RetiredKey = "job.error.rebalance.retired"
  val FrozenKey = "job.error.rebalance.frozen"
  val LegacyKey = "job.error.rebalance.legacy"
  val NotJurorKey = "job.error.rebalance.not.juror"
  /** The juror's account is in an account join (J-2): added to no round meanwhile. */
  val JoiningKey = "job.error.rebalance.joining"
  val BusyKey = "job.error.rebalance.busy"
  val VerifyKey = "job.error.rebalance.verify"
  val DonorVerifyKey = "job.error.rebalance.donor.verify"
  val DonorKey = "job.error.rebalance.donor"
  /** The juror is already an active juror of the round: refused by the page and the POST
    * ([[RoundRebalanceService]]), not by the steps, whose own "stop" makes its juror active.
    */
  val AlreadyJurorKey = "job.error.rebalance.already.juror"
  /** The juror is a stopped juror of the round, sent without the restart page: refused by the POST
    * ([[RoundRebalanceService.add]]'s `restart`), not by the steps.
    */
  val StoppedJurorKey = "job.error.rebalance.stopped.juror"
  // a reassign's (§7)
  val ReassignNotRunningKey = "job.error.rebalance.reassign.not.running"
  val ReassignLegacyKey = "job.error.rebalance.reassign.legacy"
  val FromKey = "job.error.rebalance.from"
  /** The juror has no unrated file to hand on ([[RoundReassign.reassignableCount]] 0): refused by the
    * page and the POST, no job queued.
    */
  val NothingKey = "job.error.rebalance.reassign.nothing"
  /** Too few other jurors of the round could receive the juror's files ([[RoundReassign.enoughReceivers]]:
    * a round of one juror, the others without an active jury membership, fewer than N, or with every
    * file to every juror no other active one): refused by the page, the POST and the job's "stop", no
    * job queued; the statistics show no "Reassign files…" for the juror.
    */
  val AloneKey = "job.error.rebalance.reassign.alone"

  /** [[AloneKey]] if too few other jurors of the round could receive `from`'s files now
    * ([[RoundReassign.enoughReceivers]]); its third argument is the round's N (0: every file to every juror).
    * `fromName`: the juror's name on the page and the POST, their account id in a job step (stored and logged).
    */
  def aloneRefusal(round: Round, from: Long, fromName: String)(implicit session: DBSession): Option[JobError] =
    Option.unless(RoundReassign.enoughReceivers(from, RoundReassign.possibleReceivers(round.getId, round.contestId),
      round.distribution))(JobError(AloneKey, Seq(fromName, round.number.toString, round.distribution.max(0).toString)))
  val ReceiverKey = "job.error.rebalance.receiver"
  val FewReceiversKey = "job.error.rebalance.receivers.few"
  val ReassignVerifyKey = "job.error.rebalance.reassign.verify"
  val ReceiverVerifyKey = "job.error.rebalance.receiver.verify"
  val FromVerifyKey = "job.error.rebalance.from.verify"
  /** "verify": a file left waiting no longer `in`, or a new violation of the round's invariants 1-3. */
  val InvariantsKey = "job.error.rebalance.invariants"
  /** The same past a truncated baseline: how many more, no file named. */
  val InvariantsCountKey = "job.error.rebalance.invariants.count"
  /** The cancel's flash of a reassign cancelled once a row had moved ([[RoundRebalanceHandler.cancelledNote]]). */
  val CancelledPartwayKey = "round.juror.reassign.cancelled.partway"
  /** The same in a round where every file goes to every juror: the juror's rows were removed. */
  val CancelledRemovedKey = "round.juror.reassign.cancelled.removed"

  /** The files a reassign left waiting that "verify" checks are still `in` ([[VerifyChecks.waitingPages]]). */
  val MaxWaitingChecked = 5000

  /** The round's invariants a rebalance's writes could break: vote rows of a file not `in` (1), rows set
    * aside of a file neither `deleted` nor `dq` (2), votes of a `deleted` or `dq` file (3).
    */
  private val CheckedInvariants = Set(1, 2, 3)

  /** The round's violations of [[CheckedInvariants]], per invariant, their pages sorted: only those
    * invariants' queries run (they are read under the round's lock).
    */
  def checkedViolations(roundId: Long)(implicit session: DBSession): Map[Int, Seq[Long]] =
    RoundImageInvariants.violations(roundId, only = CheckedInvariants)
      .groupMap(_.invariant)(_.pageId).view.mapValues(_.distinct.sorted).toMap

  /** The checks' baseline, read by the first "stop" only (kept on Retry, so a Retry after a failed check
    * fails again until the round is repaired): per invariant, the count and the first pages.
    */
  def baseline(roundId: Long, checks: VerifyChecks)(implicit session: DBSession): VerifyChecks =
    if (checks.violationsBefore.isDefined) checks
    else {
      val now = checkedViolations(roundId)
      checks.copy(violationsBefore = Some(now.map { case (i, pages) =>
        i.toString -> ViolationBaseline(pages.size.toLong, pages.take(ViolationBaseline.MaxPages))
      }))
    }

  /** Why "verify"'s invariant check fails, if it does: a file left waiting whose row is gone or has a
    * status other than `in`, `deleted` or `dq` (a file left waiting may be deleted on Commons, or
    * disqualified, before "verify": its row is then `deleted` or `dq`, rightly), or a violation of
    * [[CheckedInvariants]] new since the first "stop" ([[ViolationBaseline.fresh]]). The error names the
    * round, how many files, and the first.
    */
  def invariantsFailure(round: Round, checks: VerifyChecks)(implicit session: DBSession): Option[JobError] = {
    val roundId = round.getId
    val known = Seq(RoundImageJdbc.Status.In, RoundImageJdbc.Status.Deleted, RoundImageJdbc.Status.Dq)
    val lost = checks.waitingPages.distinct.sorted.grouped(1000).flatMap { chunk =>
      val there = sql"""SELECT page_id FROM round_image WHERE round_id = $roundId AND page_id IN ($chunk)
                        AND status IN ($known)""".map(_.long(1)).list().toSet
      chunk.filterNot(there)
    }.toSeq
    val fresh = checks.violationsBefore.fold(Seq.empty[(Long, Seq[Long])]) { before =>
      checkedViolations(roundId).toSeq.sortBy(_._1).map { case (i, now) =>
        before.getOrElse(i.toString, ViolationBaseline(0L, Nil)).fresh(now)
      }.filter(_._1 > 0)
    }
    // the files, each once (one may break invariants 1 and 3 at once); past a truncated baseline only how
    // many more, no file named
    val files = (lost ++ fresh.flatMap(_._2)).distinct.sorted
    val unnamed = fresh.filter(_._2.isEmpty).map(_._1).sum
    if (unnamed > 0) Some(JobError(InvariantsCountKey, Seq(round.number.toString, (files.size + unnamed).toString)))
    else files.headOption.map(first => JobError(InvariantsKey, Seq(round.number.toString, files.size.toString, first.toString)))
  }

  /** An add's share in a round where each file goes to N > 0 jurors: how many files waiting for a juror
    * the new juror takes first, and how many each donor gives (round-editing plan §5, the maintainer's
    * decision of 2026-11-17). The share is the water-filling's ([[quotas]], as without waiting files);
    * the waiting files the juror could take count toward it: `waiting` is the smaller of the two. If
    * they are fewer, the donors give the rest ([[fromTop]]); if more, the share is filled from them
    * only and the others keep waiting. A share of 0 (no donor has more unrated files than the new
    * juror) takes every waiting file the juror could take, nothing from the donors (the maintainer's
    * decision of 2026-11-17, after the build).
    *
    * @param jurorUnrated    the new juror's own unrated files
    * @param donors          (id, unrated files the new juror could take)
    * @param waitingEligible the round's files waiting for a juror that the new juror could take
    */
  def addSplit(jurorUnrated: Long, donors: Seq[(Long, Long)], waitingEligible: Long): AddSplit = {
    val share = quotas(jurorUnrated, donors)
    val total = share.values.sum
    val eligible = math.max(0L, waitingEligible)
    if (total == 0) AddSplit(eligible, Map.empty, eligible, 0L)
    else {
      val waiting = math.min(eligible, total)
      if (waiting == 0) AddSplit(0L, share, eligible, total)
      else AddSplit(waiting, fromTop(total - waiting, donors), eligible, total)
    }
  }

  /** @param waiting    the waiting files the new juror takes
    * @param fromDonors each donor's quota
    * @param eligible   the waiting files the new juror could take (those over `waiting` keep waiting)
    * @param share      the fair share ([[quotas]]): 0 when no donor has more unrated files than the juror
    */
  final case class AddSplit(waiting: Long, fromDonors: Map[Long, Long], eligible: Long, share: Long) {
    def total: Long = waiting + fromDonors.values.sum
    def waitingLeft: Long = eligible - waiting
  }

  /** How many of `total` files each donor gives, taken from the most loaded first so that those who
    * give end level: the largest k donors, k the most for which the k-th largest is at least the level
    * `(their sum - total) / k`, each kept at the level's floor (the remainder one more each, the largest
    * first). The quotas sum exactly to `total` (at most all the donors' files); none is negative.
    */
  def fromTop(total: Long, donors: Seq[(Long, Long)]): Map[Long, Long] = {
    val sorted = donors.sortBy { case (id, n) => (-n, id) }
    val take = math.min(math.max(total, 0L), sorted.map(_._2).sum)
    if (take == 0 || sorted.isEmpty) Map.empty
    else {
      val k = (1 to sorted.size).filter { k =>
        sorted(k - 1)._2 * k >= sorted.take(k).map(_._2).sum - take
      }.last
      val top = sorted.take(k)
      val left = top.map(_._2).sum - take
      val base = left / k
      val extra = (left % k).toInt // donors (the largest first) that keep one more
      top.zipWithIndex.map { case ((id, n), i) => id -> (n - base - (if (i < extra) 1 else 0)) }.filter(_._2 > 0).toMap
    }
  }

  def roundIdOf(job: Job): Long =
    (job.params \ "roundId").asOpt[Long].orElse(job.subjectId).getOrElse(
      throw new IllegalStateException(s"Job ${job.id}: no round"))

  def jurorOf(job: Job): Long =
    (job.params \ "add").asOpt[Long].getOrElse(throw new IllegalStateException(s"Job ${job.id}: no juror to add"))

  def byOf(job: Job): Option[Long] = (job.params \ "by").asOpt[Long]

  /** A reassign's juror, whose unrated files go to the receivers (§7); None for an add. */
  def fromOf(job: Job): Option[Long] = (job.params \ "from").asOpt[Long]

  /** The receivers ticked on the reassign page. */
  def receiversOf(job: Job): Seq[Long] = (job.params \ "receivers").asOpt[Seq[Long]].getOrElse(Nil).distinct.sorted

  def reassignStateOf(job: Job): ReassignState = readState(job, ReassignState())

  /** The donors ticked on the page: their unrated files may go to the new juror. */
  def donorsOf(job: Job): Seq[Long] = (job.params \ "donors").asOpt[Seq[Long]].getOrElse(Nil).distinct

  def stateOf(job: Job): RebalanceState = readState(job, RebalanceState())

  private val stateLog = Logger("services.distribution.RoundRebalanceHandler")

  /** The job's state, or `default` with a WARN line if it doesn't read (never silently: the job would run
    * from its start again).
    */
  private def readState[S](job: Job, default: S)(implicit reads: Reads[S]): S =
    job.state.validate[S] match {
      case JsSuccess(st, _) => st
      case error: JsError =>
        stateLog.warn(s"Job ${job.id} ($Kind): its state doesn't read (${JsError.toJson(error)}): taken as the default")
        default
    }

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

  /** Why adding a juror to the round is refused now, whoever it is, if it is (round-editing plan §5,
    * D3 revised 2026-11-25: a running or a stopped round takes a juror). In this order: frozen by
    * another job ([[FrozenKey]]); being set up, `distributing` ([[SettingUpKey]]; a round held in
    * review has its own path, [[RoundRebalanceService.add]]); its contest retired ([[RetiredKey]],
    * read plainly: a retirement locks the contest's rounds and is refused while a job holds one
    * frozen, [[services.series.ContestSeries.liveRoundRefusal]]); no round_user rows, a legacy round
    * ([[LegacyKey]]). Not being set up nor frozen by another job, the round is running or stopped
    * (with this job's freeze, `active_before_freeze` says which): both take a juror. A stopped round
    * stays stopped: [[Round.freeze]] saves `active = 0` and [[Round.release]] restores it, so it is
    * never open to its jurors (no `round_paused`, [[Round.pausedCond]]: they keep `round_closed`).
    * The stat table's "Add juror…" and "Start and give a fair share…" are offered exactly when this
    * and [[BusyKey]] don't refuse ([[addOffered]]).
    */
  def roundRefusal(round: Round, run: RoundRun, frozenByUs: Boolean)(implicit session: DBSession): Option[JobError] = {
    val n = round.number.toString
    if (run.frozenByOther(frozenByUs)) Some(JobError(FrozenKey, Seq(n)))
    else if (run.distributing) Some(JobError(SettingUpKey, Seq(n)))
    else if (contestRetired(round.contestId)) Some(JobError(RetiredKey, Seq(n)))
    else if (!hasRoundUsers(round.getId)) Some(JobError(LegacyKey, Seq(n)))
    else None
  }

  /** Whether the contest is retired, a plain read. */
  def contestRetired(contestId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT retired_at IS NOT NULL FROM contest_jury WHERE id = $contestId".map(_.boolean(1)).single().getOrElse(false)

  /** Whether the round statistics offer "Add juror…" and a stopped juror's "Start and give a fair
    * share…": exactly when the add page and its POST would take a juror into this round now, as far
    * as the round decides ([[roundRefusal]], from the round as read, and no job holding its key,
    * [[BusyKey]]); plain reads. A round held in review isn't offered (its jurors are chosen on the
    * jurors form), though its add page keeps the allowed exception (the `round_user` row only).
    */
  def addOffered(round: Round)(implicit session: DBSession = AutoSession): Boolean =
    roundRefusal(round, RoundRun(round.active, round.distributing, round.freezeCount, activeBeforeFreeze = false),
      frozenByUs = false).isEmpty &&
      JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId)).isEmpty

  /** Why adding `jurorId` to the round is refused now, if it is: checked on the page, on the POST
    * and by every step, each under the round's lock (but the page). In this order: the round's
    * refusals ([[roundRefusal]]: frozen by another job, being set up, retired, legacy); the juror
    * not an active jury member of the round's contest; a ticked donor not a juror of the round (round_user, active or
    * not). Not checked here, so that the steps don't refuse their own juror: a juror already active
    * in the round ([[AlreadyJurorKey]]), and a stopped one sent without the restart page
    * ([[StoppedJurorKey]]), are refused by [[RoundRebalanceService]] (the page, and the POST under the
    * round's lock) before a job exists. The job's "stop" makes its juror active, so each later step
    * and a Retry find them active; they still give only the files the juror lacks (N = 0) or count
    * the juror's own unrated files (N > 0).
    *
    * @param frozenByUs the job holds the round frozen (its own pause)
    */
  def refusal(round: Round, run: RoundRun, jurorId: Long, donors: Seq[Long], frozenByUs: Boolean)(implicit
      session: DBSession
  ): Option[JobError] = {
    val n = round.number.toString
    roundRefusal(round, run, frozenByUs)
      .orElse(Option.when(eligibleJuror(round.contestId, jurorId).isEmpty)(JobError(NotJurorKey, Seq(jurorId.toString))))
      .orElse(donors.find(d => !isRoundJuror(round.getId, d)).map(d => JobError(DonorKey, Seq(d.toString, n))))
  }

  /** Whether the round runs as [[RoundRun.running]] says, from the round as read (no lock): the stat
    * table offers "Reassign files…" only then.
    */
  def isRunning(round: Round): Boolean =
    RoundRun(round.active, round.distributing, round.freezeCount, activeBeforeFreeze = false).running(frozenByUs = false)

  /** Why reassigning `from`'s unrated files to `receivers` is refused now, if it is (§7, D3): checked on
    * the page, on the POST and by every step, each under the round's lock (but the page). In this
    * order: frozen by another job; not running; no round_user rows (a legacy round); `from` not a
    * juror of the round; a receiver not a juror of the round with an active jury membership of the
    * contest (an inactive or removed membership can't be started again); in a round where each file
    * goes to N > 0 jurors, fewer receivers than N ("add a juror first"). Receivers don't matter in a
    * round where every file goes to every juror: nothing is given there.
    *
    * @param fromName the juror's name, given by the page and the POST (not the steps, whose juror has
    *                 nothing left once the files moved): then, after `from` not a juror of the round,
    *                 also refused if the juror has nothing to reassign ([[RoundReassign.reassignableCount]] 0),
    *                 then if too few other jurors of the round could receive the files ([[aloneRefusal]])
    */
  def reassignRefusal(round: Round, run: RoundRun, from: Long, receivers: Seq[Long], frozenByUs: Boolean,
      fromName: Option[String] = None)(implicit
      session: DBSession
  ): Option[JobError] = {
    val n = round.number.toString
    val to = if (round.distribution > 0) receivers.filterNot(_ == from) else Nil
    if (run.frozenByOther(frozenByUs)) Some(JobError(FrozenKey, Seq(n)))
    else if (!run.running(frozenByUs)) Some(JobError(ReassignNotRunningKey, Seq(n)))
    else if (!hasRoundUsers(round.getId)) Some(JobError(ReassignLegacyKey, Seq(n)))
    else if (!isRoundJuror(round.getId, from)) Some(JobError(FromKey, Seq(from.toString, n)))
    else if (fromName.exists(_ => RoundReassign.reassignableCount(round.getId, from) == 0))
      Some(JobError(NothingKey, fromName.toSeq :+ n))
    else fromName.flatMap(aloneRefusal(round, from, _)).orElse(
      to.find(r => !isRoundJuror(round.getId, r) || eligibleJuror(round.contestId, r).isEmpty)
        .map(r => JobError(ReceiverKey, Seq(r.toString, n)))
        .orElse(Option.when(round.distribution > 0 && to.size < round.distribution)(
          JobError(FewReceiversKey, Seq(n, round.distribution.toString)))))
  }

  def hasRoundUsers(roundId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId LIMIT 1".map(_ => ()).single().isDefined

  def isRoundJuror(roundId: Long, jurorId: Long)(implicit session: DBSession): Boolean =
    sql"SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $jurorId".map(_ => ()).single().isDefined

  /** The juror's `round_user` row in the round: None (not in it), Some(true) active, Some(false)
    * stopped (a NULL, of a schema before V108, too: stopped, as the add page lists it).
    */
  def jurorState(roundId: Long, jurorId: Long)(implicit session: DBSession): Option[Boolean] =
    sql"SELECT COALESCE(active, FALSE) FROM round_user WHERE round_id = $roundId AND user_id = $jurorId"
      .map(_.boolean(1)).single()

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
