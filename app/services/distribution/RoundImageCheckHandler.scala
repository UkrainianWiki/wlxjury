package services.distribution

import db.scalikejdbc.{RegionCountCache, RoundImageCounts, RoundImageJdbc, RoundWrites, User}
import play.api.Logging
import play.api.libs.json._
import scalikejdbc._
import services.jobs._

import javax.inject.{Inject, Singleton}

/** The resume cursor of a `round_image_check` job (`job.state`).
  *
  * @param phase       "sync" (the round's files brought in line with their votes, window by
  *                    window of page ids), "monument" (its rows' monument ids), "orphans"
  *                    (criteria rates of no vote: the all-rounds check only)
  * @param roundId     the round being checked; 0 before the first
  * @param cursor      the last page id (in "orphans": criteria_rate id) a committed step read
  * @param rounds      rounds checked
  * @param skipped     rounds left out: their distribution was queued or running
  * @param added       rows added (a pair with votes and no row)
  * @param statusFixed rows whose status didn't match their votes
  * @param removed     rows of a pair with no votes left
  * @param monumentFixed rows whose monument id wasn't their image's
  * @param notMarkedDeleted `deleted` rows whose image isn't marked deleted (invariant 4:
  *                    reported, not repaired)
  * @param dqWithVotes `dq` rows with `vote` rows (invariant 3: a vote written for a
  *                    disqualified file; reported, not repaired: the row stays `dq`)
  * @param orphansArchived criteria rates of no vote, archived in criteria_rate_orphan and deleted
  * @param drift       per round with drift, what was fixed (the first [[RoundImageCheckHandler.MaxDrift]])
  */
final case class ImageCheckState(
    phase: String = RoundImageCheckHandler.Phase.Sync,
    roundId: Long = 0L,
    cursor: Long = 0L,
    rounds: Long = 0L,
    skipped: Seq[Long] = Nil,
    added: Long = 0L,
    statusFixed: Long = 0L,
    removed: Long = 0L,
    monumentFixed: Long = 0L,
    notMarkedDeleted: Long = 0L,
    dqWithVotes: Long = 0L,
    orphansArchived: Long = 0L,
    drift: Seq[String] = Nil,
    roundAdded: Long = 0L,
    roundStatusFixed: Long = 0L,
    roundRemoved: Long = 0L,
    roundMonumentFixed: Long = 0L,
    roundNotMarkedDeleted: Long = 0L,
    roundDqWithVotes: Long = 0L
)

object ImageCheckState {
  implicit val format: OFormat[ImageCheckState] = Json.using[Json.WithDefaultValues].format[ImageCheckState]
}

/** Checks and repairs rounds' `round_image` rows (V84) against their votes, as a [[Job]] of
  * kind `round_image_check` (docs/plans/2026-11-02-round-images-two-step.md, §5): after a
  * deploy of a release that changes how they are written, or after running an older release
  * (which ignores the table) and coming back. Queued by a round's admin (one round,
  * `params.roundId`) or by root (every round, then the criteria rates of no vote: `params.all`).
  *
  * Each step is one short transaction: a window of the round's page ids (up to
  * [[WindowRows]] rows of each of vote, vote_aside and round_image), brought in line with the
  * votes as every bulk write does ([[RoundImageJdbc.sync]], added `repair`); then, in steps of
  * their own, the rows' monument ids: those that differ read plainly, their images then locked
  * in share mode, their rows written by key (images before round_image, the lock order). The
  * caches follow after each commit (the round's count, the monument ids). Every step
  * first takes the round's row in share mode (the lock order's first) and leaves the round
  * out if its distribution is queued or running (a distribution writes the same rows).
  * What a round needed is logged at WARN and kept in the job's details. A `deleted` row whose
  * file isn't marked deleted (invariant 4) is only reported: its votes would have to move. So
  * is a disqualified (`dq`) row with votes in `vote` (invariant 3): the sync keeps a `dq` row
  * `dq` whatever its votes (P1b), and a `dq` row with no votes stays, so the file is never
  * distributed again; only a requalify takes it back.
  *
  * The criteria rates of no vote (invariant 7: a vote id in neither vote nor vote_aside) are
  * archived in criteria_rate_orphan (reason `orphan`, as V78 did) and deleted, window by
  * window of their ids: candidates read plainly, then their votes looked up with locks (vote,
  * then vote_aside: one moved meanwhile is found), then the rates still of no vote locked,
  * archived and deleted. A rate is written only while its vote row is locked, so one of no
  * vote is written by nobody.
  */
@Singleton
class RoundImageCheckHandler(windowRows: Int, orphanWindow: Int) extends JobHandler with Logging {

  import RoundImageCheckHandler._

  @Inject() def this() = this(RoundImageCheckHandler.WindowRows, RoundImageCheckHandler.OrphanWindow)

  val kind: String = Kind

  override def title(job: Job): String =
    roundOf(job).fold("Checking every round's images")(r => s"Checking the images of round $r")

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("rounds" -> st.rounds, "skipped" -> st.skipped, "added" -> st.added, "statusFixed" -> st.statusFixed,
      "removed" -> st.removed, "monumentFixed" -> st.monumentFixed, "notMarkedDeleted" -> st.notMarkedDeleted,
      "dqWithVotes" -> st.dqWithVotes, "orphansArchived" -> st.orphansArchived, "drift" -> st.drift,
      "phase" -> st.phase)
  }

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    st.phase match {
      case Phase.Orphans => orphansStep(st)
      case _ if st.roundId == 0L =>
        nextRound(job, 0L).fold(afterRounds(job, st))(r => continue(st.copy(roundId = r, cursor = 0L)))
      case Phase.Sync     => syncStep(job, st, ctx)
      case Phase.Monument => monumentStep(job, st, ctx)
      case other          => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  /** The round's row for update, first (a structural write, [[RoundWrites]]); then whether
    * its distribution is active, a plain read at READ COMMITTED (the latest committed: a
    * distribution is queued, and ends, under the round's lock, so none changes while this step
    * holds it; no lock on `job`, whose row a running step holds before the round: break 11).
    * None if the round is gone.
    */
  private def lockRound(roundId: Long)(implicit session: DBSession): Option[Boolean] =
    sql"SELECT id FROM round WHERE id = $roundId FOR UPDATE".map(_.long(1)).single().map { _ =>
      RoundWrites.recordRounds(Seq(roundId))
      sql"""SELECT 1 FROM job WHERE active_dedupe_key = ${RoundDistributionHandler.dedupeKey(roundId)}"""
        .map(_ => ()).single().isDefined
    }

  private def syncStep(job: Job, st: ImageCheckState, ctx: StepContext)(implicit session: DBSession): StepResult =
    lockRound(st.roundId) match {
      case None        => roundDone(job, st, gone = true)
      case Some(true)  => roundDone(job, st.copy(skipped = st.skipped :+ st.roundId), gone = true)
      case Some(false) =>
        val to = windowEnd(st.roundId, st.cursor)
        val pageIds = Seq(sqls"vote", sqls"vote_aside", sqls"round_image").flatMap { table =>
          sql"""SELECT DISTINCT page_id FROM $table WHERE round_id = ${st.roundId}
                AND page_id > ${st.cursor} AND page_id <= $to"""
            .map(_.long(1)).list()
        }.distinct
        if (pageIds.isEmpty) continue(st.copy(phase = Phase.Monument, cursor = 0L))
        else {
          val fixed = RoundImageJdbc.sync(st.roundId, pageIds, RoundImageJdbc.Origin(RoundImageJdbc.AddedHow.Repair, Some(job.id)))
          val roundId = st.roundId
          if (!fixed.isEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          continue(st.copy(cursor = pageIds.max, roundAdded = st.roundAdded + fixed.added,
            roundStatusFixed = st.roundStatusFixed + fixed.statusChanged, roundRemoved = st.roundRemoved + fixed.removed))
        }
    }

  private def monumentStep(job: Job, st: ImageCheckState, ctx: StepContext)(implicit session: DBSession): StepResult =
    lockRound(st.roundId) match {
      case None        => roundDone(job, st, gone = true)
      case Some(true)  => roundDone(job, st.copy(skipped = st.skipped :+ st.roundId), gone = true)
      case Some(false) =>
        val last = sql"""SELECT MAX(page_id) FROM (SELECT page_id FROM round_image WHERE round_id = ${st.roundId}
                           AND page_id > ${st.cursor} ORDER BY page_id LIMIT $windowRows) w"""
          .map(_.longOpt(1)).single().flatten
        last match {
          case None => roundDone(job, st, gone = false)
          case Some(to) =>
            val window = sqls"ri.round_id = ${st.roundId} AND ri.page_id > ${st.cursor} AND ri.page_id <= $to"
            // the rows that differ, read plainly; then their images locked (shared, in page id
            // order) before their rows are written by key: images before round_image, the
            // lock order, and no vote touched in this transaction
            val differ = sql"""SELECT ri.page_id FROM round_image ri JOIN images i ON i.page_id = ri.page_id
                                WHERE $window AND NOT (ri.monument_id <=> i.monument_id) ORDER BY ri.page_id"""
              .map(_.long(1)).list()
            val fixed =
              if (differ.isEmpty) 0
              else {
                val monumentIds = sql"""SELECT page_id, monument_id FROM images WHERE page_id IN ($differ)
                                        ORDER BY page_id LOCK IN SHARE MODE"""
                  .map(rs => rs.long(1) -> rs.stringOpt(2)).list()
                monumentIds.map { case (pageId, monumentId) =>
                  sql"""UPDATE round_image SET monument_id = $monumentId
                        WHERE round_id = ${st.roundId} AND page_id = $pageId AND NOT (monument_id <=> $monumentId)"""
                    .update()
                }.sum
              }
            // the galleries' region counts read the monument ids
            if (fixed > 0) ctx.afterCommit(RegionCountCache.invalidateMonumentIds())
            val notMarked = sql"""SELECT COUNT(*) FROM round_image ri JOIN images i ON i.page_id = ri.page_id
                                  WHERE $window AND ri.status = ${RoundImageJdbc.Status.Deleted}
                                    AND i.deleted_at IS NULL"""
              .map(_.long(1)).single().getOrElse(0L)
            val dqWithVotes = sql"""SELECT COUNT(*) FROM round_image ri
                                    WHERE $window AND ri.status = ${RoundImageJdbc.Status.Dq}
                                      AND EXISTS (SELECT 1 FROM vote v WHERE v.round_id = ri.round_id
                                                  AND v.page_id = ri.page_id)"""
              .map(_.long(1)).single().getOrElse(0L)
            continue(st.copy(cursor = to, roundMonumentFixed = st.roundMonumentFixed + fixed,
              roundNotMarkedDeleted = st.roundNotMarkedDeleted + notMarked,
              roundDqWithVotes = st.roundDqWithVotes + dqWithVotes))
        }
    }

  /** The end of the next window after `cursor`: the page id of the [[WindowRows]]th row
    * after it in the table with the fewest pages per row, so no table gives more rows than that;
    * the end of the round if none has that many.
    */
  private def windowEnd(roundId: Long, cursor: Long)(implicit session: DBSession): Long =
    Seq(sqls"vote", sqls"vote_aside", sqls"round_image").flatMap { table =>
      sql"""SELECT page_id FROM $table WHERE round_id = $roundId AND page_id > $cursor
            ORDER BY page_id LIMIT 1 OFFSET ${windowRows - 1}"""
        .map(_.long(1)).single()
    }.minOption.getOrElse(Long.MaxValue)

  /** The round is done: its drift recorded and logged, then the next round. */
  private def roundDone(job: Job, st: ImageCheckState, gone: Boolean)(implicit session: DBSession): StepResult = {
    val drift = st.roundAdded + st.roundStatusFixed + st.roundRemoved + st.roundMonumentFixed + st.roundNotMarkedDeleted +
      st.roundDqWithVotes
    val line = s"round ${st.roundId}: ${st.roundAdded} added, ${st.roundStatusFixed} status fixed, " +
      s"${st.roundRemoved} removed, ${st.roundMonumentFixed} monument ids fixed, " +
      s"${st.roundNotMarkedDeleted} deleted but not marked deleted, ${st.roundDqWithVotes} disqualified with votes"
    if (drift > 0) logger.warn(s"round_image_check job ${job.id}: $line")
    if (st.skipped.lastOption.contains(st.roundId))
      logger.info(s"round_image_check job ${job.id}: round ${st.roundId} left out, its distribution is active")
    val done = st.copy(
      rounds = st.rounds + (if (gone) 0 else 1),
      added = st.added + st.roundAdded,
      statusFixed = st.statusFixed + st.roundStatusFixed,
      removed = st.removed + st.roundRemoved,
      monumentFixed = st.monumentFixed + st.roundMonumentFixed,
      notMarkedDeleted = st.notMarkedDeleted + st.roundNotMarkedDeleted,
      dqWithVotes = st.dqWithVotes + st.roundDqWithVotes,
      drift = if (drift > 0) (st.drift :+ line).take(MaxDrift) else st.drift,
      roundAdded = 0L, roundStatusFixed = 0L, roundRemoved = 0L, roundMonumentFixed = 0L, roundNotMarkedDeleted = 0L,
      roundDqWithVotes = 0L,
      phase = Phase.Sync, cursor = 0L
    )
    nextRound(job, st.roundId).fold(afterRounds(job, done))(r => continue(done.copy(roundId = r)))
  }

  /** The next round to check after `after`: the job's own round once, or every round by id. */
  private def nextRound(job: Job, after: Long)(implicit session: DBSession): Option[Long] =
    roundOf(job) match {
      case Some(r) => Option.when(after == 0L)(r)
      case None    => sql"SELECT MIN(id) FROM round WHERE id > $after".map(_.longOpt(1)).single().flatten
    }

  private def afterRounds(job: Job, st: ImageCheckState): StepResult =
    if (roundOf(job).isEmpty) continue(st.copy(phase = Phase.Orphans, roundId = 0L, cursor = 0L))
    else finish(job, st)

  private def orphansStep(st: ImageCheckState)(implicit session: DBSession): StepResult = {
    val to = sql"""SELECT MAX(id) FROM (SELECT id FROM criteria_rate WHERE id > ${st.cursor}
                     ORDER BY id LIMIT $orphanWindow) w"""
      .map(_.longOpt(1)).single().flatten
    to match {
      case None => StepResult.Done(Json.toJsObject(st), progress(st))
      case Some(last) =>
        val candidates = sql"""SELECT cr.id, cr.vote_id FROM criteria_rate cr
                               WHERE cr.id > ${st.cursor} AND cr.id <= $last
                                 AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.id = cr.vote_id)
                                 AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.id = cr.vote_id)"""
          .map(rs => rs.long(1) -> rs.long(2)).list()
        val archived =
          if (candidates.isEmpty) 0
          else {
            val voteIds = candidates.map(_._2).distinct.sorted
            // their votes, if moved or written meanwhile, locked: vote, then vote_aside
            val found = Seq(sqls"vote", sqls"vote_aside").flatMap { table =>
              sql"SELECT id FROM $table WHERE id IN ($voteIds) FOR UPDATE".map(_.long(1)).list()
            }.toSet
            val ids = candidates.filterNot(c => found(c._2)).map(_._1).sorted
            if (ids.isEmpty) 0
            else {
              sql"SELECT id FROM criteria_rate WHERE id IN ($ids) FOR UPDATE".map(_.long(1)).list()
              sql"""INSERT INTO criteria_rate_orphan (id, vote_id, criteria, rate, reason)
                    SELECT id, vote_id, criterion_id, rate, ${OrphanReason} FROM criteria_rate WHERE id IN ($ids)"""
                .update()
              sql"DELETE FROM criteria_rate WHERE id IN ($ids)".update()
            }
          }
        if (archived > 0)
          logger.warn(s"round_image_check: archived and deleted $archived criteria rates of no vote (ids up to $last)")
        continue(st.copy(cursor = last, orphansArchived = st.orphansArchived + archived))
    }
  }

  private def finish(job: Job, st: ImageCheckState): StepResult = {
    logger.info(s"round_image_check job ${job.id}: ${st.rounds} rounds checked, ${st.added} rows added, " +
      s"${st.statusFixed} statuses fixed, ${st.removed} removed, ${st.monumentFixed} monument ids fixed, " +
      s"${st.notMarkedDeleted} deleted but not marked deleted, ${st.dqWithVotes} disqualified with votes, " +
      s"${st.skipped.size} left out")
    StepResult.Done(Json.toJsObject(st), progress(st))
  }

  private def continue(st: ImageCheckState) = StepResult.Continue(Json.toJsObject(st), progress(st))

  private def progress(st: ImageCheckState) = Progress(done = Some(st.rounds), unit = Some("rounds"))
}

object RoundImageCheckHandler {

  val Kind = "round_image_check"
  val SubjectType = "round"

  /** Rows of each table per window. */
  val WindowRows = 10000

  /** Criteria rates per orphans step. */
  val OrphanWindow = 5000

  val MaxDrift = 100

  val OrphanReason = "orphan"

  object Phase {
    val Sync = "sync"
    val Monument = "monument"
    val Orphans = "orphans"
  }

  /** One check per round at a time, and one of every round. */
  def dedupeKey(roundId: Long): String = s"$Kind:$SubjectType:$roundId"
  val AllDedupeKey: String = s"$Kind:all"

  def roundOf(job: Job): Option[Long] = (job.params \ "roundId").asOpt[Long]

  def stateOf(job: Job): ImageCheckState = job.state.validate[ImageCheckState].getOrElse(ImageCheckState())

  /** A check of one round, of its contest. */
  def roundJob(roundId: Long, contestId: Long, by: User): NewJob =
    NewJob(kind = Kind, contestId = Some(contestId), subjectType = Some(SubjectType), subjectId = Some(roundId),
      dedupeKey = Some(dedupeKey(roundId)), params = Json.obj("roundId" -> roundId),
      state = Json.toJsObject(ImageCheckState()), progress = Progress(unit = Some("rounds")), createdBy = by.id)

  /** A check of every round, then of the criteria rates: no contest (root's). */
  def allJob(by: User): NewJob =
    NewJob(kind = Kind, dedupeKey = Some(AllDedupeKey), params = Json.obj("all" -> true),
      state = Json.toJsObject(ImageCheckState()), progress = Progress(unit = Some("rounds")), createdBy = by.id)
}
