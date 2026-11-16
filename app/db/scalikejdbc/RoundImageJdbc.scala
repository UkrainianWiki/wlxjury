package db.scalikejdbc

import scalikejdbc._

/** A round's files (`round_image`, V84): one row per round and file, kept in step with the
  * round's `vote` and `vote_aside` rows in the same transaction as their writes
  * (docs/plans/2026-11-02-round-images-two-step.md, §5 and P1).
  *
  * A row is never deleted by a write here (P3-1, invariant 8 ended: a round is filled before
  * it is distributed, so a filled file has no votes yet), but by a re-apply's Prune of a round in
  * review with no vote (D16, [[prune]]). Its status follows the pair's votes:
  * `in` while it has `vote` rows, `deleted` while its votes are only in `vote_aside` (a file
  * deleted on Commons in an open round); a pair with no votes keeps its row and its status (a
  * file filled and not distributed yet, or one whose votes were deleted). A vote-less file
  * deleted on Commons is marked `deleted` by the deletion paths, and `in` again on a restore
  * ([[markVotelessDeleted]], [[restoreVoteless]]). A disqualified file (`dq`, P1b:
  * [[RoundConflicts]]) keeps its row, with its votes aside, whatever its votes: only an
  * explicit requalify ([[requalifyRows]]) takes it back, so "Distribute new files" never adds
  * it again.
  *
  * Writers:
  *  - a vote insert ([[VoteJdbc.create]], [[VoteJdbc.batchInsert]], so a distribution, the
  *    in-memory path, the Gatling fixture and the test helpers) adds its files
  *    ([[addVoted]]);
  *  - a bulk write (moving votes aside or back, deleting a file's or the unrated votes, moving
  *    a file to another round, leaving out conflicts of interest) brings the pairs it touched
  *    in line with their votes afterwards ([[sync]]);
  *  - disqualifying a file marks its row `dq` after its votes are moved aside
  *    ([[disqualify]]); requalifying it lifts the mark ([[requalifyRows]]);
  *  - a monument id change follows the image ([[syncMonumentId]]);
  *  - a deleted round or image takes its rows with it (foreign keys, ON DELETE CASCADE);
  *  - the `round_image_check` job repairs drift ([[services.distribution.RoundImageCheckHandler]]);
  *  - the count columns (V89, P2a) are written only by the background recompute
  *    ([[RoundCounts]]); a vote insert and a sync here bump their round's generation
  *    ([[RoundCounts.markRounds]]), so the round is recounted.
  *
  * Lock order: `round` → `images` → `vote` → `criteria_rate` → `vote_aside` → `round_image`
  * → `round_image_dirty` (V89, [[RoundCounts]]) → `round_image_event` (V88: a disqualify's or
  * requalify's events, after its rows): every
  * writer here runs after its votes are written and locked, and locks the pairs' rows last. A recount never reads a non-locking snapshot of the votes ([[sync]] reads them in
  * share mode): a concurrent write would be missed.
  */
object RoundImageJdbc {

  object Status {
    val In = "in"
    val Dq = "dq"
    val Deleted = "deleted"
    val All: Seq[String] = Seq(In, Dq, Deleted)
  }

  /** Why a file is disqualified (`dq_code`, V84's comment): only `coi` is written so far. */
  object DqCode {
    val Coi = "coi"
    val Manual = "manual"
    val All: Seq[String] = Seq(Coi, "min_mpx", "category", "date", Manual)
  }

  /** `dq_detail`'s length (V84). */
  val DqDetailLength = 255

  object AddedHow {
    val Fill = "fill"
    val List = "list"
    val NewFiles = "new_files"
    val Manual = "manual"
    val Repair = "repair"
    val Legacy = "legacy"
    val All: Seq[String] = Seq(Fill, List, NewFiles, Manual, Repair, Legacy)
  }

  /** How rows a write adds were added, and by which job. */
  final case class Origin(how: String, jobId: Option[Long] = None) {
    require(AddedHow.All.contains(how), s"unknown added_how $how")
  }

  object Origin {

    /** Votes inserted outside a distribution job: a file moved from another round, the
      * in-memory path, scripts, tests.
      */
    val Manual: Origin = Origin(AddedHow.Manual)
  }

  /** A row, as stored. */
  final case class Row(
      roundId: Long,
      pageId: Long,
      status: String,
      addedHow: String,
      addedByJobId: Option[Long],
      monumentId: Option[String],
      region: Option[String]
  )

  /** What a [[sync]] changed: rows added and statuses changed. No row is removed (P3-1). */
  final case class SyncResult(added: Int = 0, statusChanged: Int = 0) {
    def +(o: SyncResult): SyncResult = SyncResult(added + o.added, statusChanged + o.statusChanged)
    def isEmpty: Boolean = added == 0 && statusChanged == 0
  }

  /** Page ids per statement: an `IN` list the (round_id, page_id) key reads one by one. */
  private[scalikejdbc] val Chunk = 2000

  /** Runs `f` in the caller's transaction, or in one of its own when the caller passed an
    * auto-commit session (the DAO defaults, or a `DB.autoCommit` session, whose round lock would
    * end with its statement: [[RoundWrites.lock]] refuses it): a vote insert and its rows commit
    * together, under the round's lock. A read-only session (`DB.readOnly`) runs `f` as it is, so
    * the write fails as before, never on another connection. A `DB.autoCommit` session of a named
    * pool (`NamedDB(name).autoCommit`) isn't told apart: its transaction would be on the default
    * pool, so that case is not supported (the app has none).
    */
  private[scalikejdbc] def inTransaction[A](session: DBSession)(f: DBSession => A): A =
    session match {
      case _: AutoSession                                      => WriteTx.write(f)
      case NamedAutoSession(name, _)                           => WriteTx.retry(NamedDB(name).localTx(f))
      case s if !s.isReadOnly && RoundWrites.inAutoCommitMode(s) => WriteTx.write(f)
      case s                                                   => f(s)
    }

  /** Adds the files of vote rows just inserted to their rounds, status `in`, after the
    * insert and in its transaction: the insert's foreign key check has locked the files'
    * image rows (shared), whose monument ids are copied. A row there already (a file moved
    * back, or votes added to a file whose votes are aside) becomes `in`; its `added_*` stay.
    * A vote of a round that doesn't exist (vote.round_id has no foreign key: scripts, old
    * specs) adds no row, as V84's backfill leaves such pairs out. The caller has locked the
    * rounds for update ([[RoundWrites]], before the votes; checked).
    *
    * A disqualified (`dq`) file is never requalified here (plan §9, P1b): its row stays `dq`,
    * and the insert is refused (an [[IllegalStateException]], so the caller's transaction
    * rolls back with its votes). No writer inserts one: the distribution's anti-join leaves out
    * every file with a row, and a restore from Commons leaves a `dq` file's votes aside.
    *
    * @param pairs (round id, page id) of the inserted votes
    */
  def addVoted(pairs: Iterable[(Long, Long)], origin: Origin)(implicit session: DBSession): Unit = {
    RoundWrites.requireRounds(pairs.map(_._1))
    pairs.groupBy(_._1).toSeq.sortBy(_._1).foreach { case (roundId, ofRound) =>
      ofRound.map(_._2).toSeq.distinct.sorted.grouped(Chunk).foreach { pageIds =>
        sql"""INSERT INTO round_image (round_id, page_id, status, added_how, added_by_job_id, added_at, monument_id)
              SELECT r.id, i.page_id, ${Status.In}, ${origin.how}, ${origin.jobId}, NOW(3), i.monument_id
              FROM round r JOIN images i ON i.page_id IN ($pageIds) WHERE r.id = $roundId ORDER BY i.page_id
              ON DUPLICATE KEY UPDATE round_image.status = IF(round_image.status = ${Status.Dq}, ${Status.Dq}, ${Status.In})""".update()
        val disqualified = sql"""SELECT page_id FROM round_image
                                 WHERE round_id = $roundId AND page_id IN ($pageIds) AND status = ${Status.Dq}
                                 ORDER BY page_id LIMIT 10"""
          .map(_.long(1)).list()
        if (disqualified.nonEmpty)
          throw new IllegalStateException(
            s"Votes inserted for disqualified files of round $roundId (pages ${disqualified.mkString(", ")}): refused")
      }
    }
    // the rounds' counts follow in the background (P2a): their generation bumped, last
    RoundCounts.markRounds(pairs.map(_._1))
  }

  /** Adds the files to the round, `in` with no votes (P3: a round filled before it is
    * distributed), with the images' monument ids; a file with a row already keeps it. The round
    * is locked for update by the caller (the fill's chunk; checked).
    *
    * @return the rows added
    */
  def addFilled(roundId: Long, pageIds: Seq[Long], origin: Origin)(implicit session: DBSession): Int =
    if (pageIds.isEmpty) 0
    else {
      RoundWrites.requireRounds(Seq(roundId))
      pageIds.distinct.sorted.grouped(Chunk).map { chunk =>
        sql"""INSERT IGNORE INTO round_image (round_id, page_id, status, added_how, added_by_job_id, added_at, monument_id)
              SELECT r.id, i.page_id, ${Status.In}, ${origin.how}, ${origin.jobId}, NOW(3), i.monument_id
              FROM round r JOIN images i ON i.page_id IN ($chunk) WHERE r.id = $roundId ORDER BY i.page_id""".update()
      }.sum
    }

  /** Brings the rows of the round's files `pageIds` in line with their votes, after a bulk
    * write of those votes in the same transaction: `in` where the pair has `vote` rows; else
    * `deleted` where it has `vote_aside` rows; a row of a pair with neither stays as it is (P3-1:
    * never deleted). A `dq` row stays
    * `dq` whatever its votes (P1b: a vote written for it is invariant 3's to report; one with
    * none keeps its row, so it is never distributed again); only [[requalifyRows]] lifts it. The votes are read in share mode (the caller holds those it
    * wrote; the others' latest committed state, not the snapshot's), then the rows locked and
    * written. A missing row is added with `missing` as its origin, with its image's monument
    * id.
    */
  def sync(roundId: Long, pageIds: Seq[Long], missing: Origin = Origin.Manual)(implicit session: DBSession): SyncResult =
    syncWith(roundId, pageIds, missing, requalify = Set.empty)._1

  /** [[sync]] of the round's files `pageIds` with their `dq` lifted (a requalify,
    * [[RoundConflicts.requalify]], after the votes to bring back are moved back): they follow
    * their votes as any other row (`in` with votes, `deleted` with votes only aside), and lose
    * their `dq_*`. One with no votes anywhere stays in the round (P3-1): `deleted` if it is in
    * `deleted` (the caller's rule for a file deleted on Commons), else `in`. Each row that leaves `dq` is recorded, after the rows, as a
    * `requalify` event with `reason` ([[RoundImageEventJdbc]], V88): its status before (`dq`)
    * and after.
    *
    * @param reason one of [[RoundConflicts.RequalifyReason.All]]
    */
  private[scalikejdbc] def requalifyRows(
      roundId: Long,
      pageIds: Seq[Long],
      reason: String,
      by: Option[Long],
      deleted: Set[Long] = Set.empty
  )(implicit session: DBSession): SyncResult = {
    val (result, lifted) = syncWith(roundId, pageIds, Origin.Manual, requalify = pageIds.toSet, deleted)
    RoundImageEventJdbc.record(roundId, RoundImageEventJdbc.Action.Requalify, reason, by,
      lifted.map { case (pageId, now) => RoundImageEventJdbc.Change(pageId, Status.Dq, now) })
    result
  }

  /** The rows synced, and those that left `dq`: page id -> their status now. */
  private def syncWith(
      roundId: Long,
      pageIds: Seq[Long],
      missing: Origin,
      requalify: Set[Long],
      requalifiedDeleted: Set[Long] = Set.empty
  )(implicit
      session: DBSession
  ): (SyncResult, Seq[(Long, Option[String])]) = {
    if (pageIds.nonEmpty) RoundWrites.requireRounds(Seq(roundId))
    val synced = pageIds.distinct.sorted.grouped(Chunk).foldLeft((SyncResult(), Seq.empty[(Long, Option[String])])) {
      case ((acc, lifted), chunk) =>
        val (r, l) = syncChunk(roundId, chunk, missing, requalify, requalifiedDeleted)
        (acc + r, lifted ++ l)
    }
    // the caller wrote these files' votes: the round's counts follow in the background (P2a)
    if (pageIds.nonEmpty) RoundCounts.markRounds(Seq(roundId))
    synced
  }

  /** [[sync]] of each round's pairs, rounds in id order. */
  def syncPairs(pairs: Iterable[(Long, Long)], missing: Origin = Origin.Manual)(implicit session: DBSession): SyncResult =
    pairs.groupBy(_._1).toSeq.sortBy(_._1).foldLeft(SyncResult()) { case (acc, (roundId, ofRound)) =>
      acc + sync(roundId, ofRound.map(_._2).toSeq, missing)
    }

  private def syncChunk(
      roundId: Long,
      pageIds: Seq[Long],
      missing: Origin,
      requalify: Set[Long],
      requalifiedDeleted: Set[Long]
  )(implicit session: DBSession): (SyncResult, Seq[(Long, Option[String])]) = {
    val voted = sql"""SELECT DISTINCT page_id FROM vote WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val aside = sql"""SELECT DISTINCT page_id FROM vote_aside WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val rows = sql"""SELECT page_id, status FROM round_image WHERE round_id = $roundId AND page_id IN ($pageIds)
                     FOR UPDATE""".map(rs => rs.long(1) -> rs.string(2)).list().toMap

    // None: no votes anywhere; a row there keeps its status (P3-1), none is added
    def wanted(pageId: Long): Option[String] =
      if (rows.get(pageId).contains(Status.Dq) && !requalify(pageId)) Some(Status.Dq)
      else if (voted(pageId)) Some(Status.In)
      else if (aside(pageId)) Some(Status.Deleted)
      else if (rows.contains(pageId) && requalify(pageId))
        Some(if (requalifiedDeleted(pageId)) Status.Deleted else Status.In)
      else None

    val changed = rows.toSeq.collect { case (p, status) if wanted(p).exists(_ != status) => p -> wanted(p).get }
    val toAdd = pageIds.filter(p => !rows.contains(p) && wanted(p).isDefined)

    // never to `dq` (kept above; set only by disqualify): a row leaving it loses its dq_*
    changed.groupBy(_._2).foreach { case (status, ps) =>
      sql"""UPDATE round_image SET status = $status, dq_code = NULL, dq_detail = NULL, dq_by = NULL, dq_at = NULL
            WHERE round_id = $roundId AND page_id IN (${ps.map(_._1).sorted})""".update()
    }
    val added = toAdd.groupBy(p => wanted(p).get).toSeq.map { case (status, ps) =>
      // only a round that exists (vote.round_id has no foreign key; the moved-back votes
      // of a deleted round are dropped): its row read in share mode, as the key would
      sql"""INSERT INTO round_image (round_id, page_id, status, added_how, added_by_job_id, added_at, monument_id)
            SELECT r.id, i.page_id, $status, ${missing.how}, ${missing.jobId}, NOW(3), i.monument_id
            FROM round r JOIN images i ON i.page_id IN (${ps.sorted})
            WHERE r.id = $roundId ORDER BY i.page_id""".update()
    }.sum
    val lifted = rows.toSeq.collect { case (p, Status.Dq) if requalify(p) => p -> wanted(p) }.sortBy(_._1)
    (SyncResult(added = added, statusChanged = changed.size), lifted)
  }

  /** Marks the round's files disqualified (`dq`, P1b), after their votes were moved aside in
    * the same transaction ([[ImageJdbc.moveVotesAsideInRound]]): `dq_code`, a detail per file
    * (cut to [[DqDetailLength]]), who and when. Rows already `dq` keep theirs. Each row marked
    * is recorded, after the rows, as a `disqualify` event with `code` ([[RoundImageEventJdbc]],
    * V88): its status before, as `prior` gives it (the caller's read before it moved the votes
    * aside, which made the rows `deleted`), and after (`dq`). A row to mark that `prior` lacks
    * is refused before anything is written (an [[IllegalArgumentException]]): the status found
    * here may already be the move's, so it is never guessed. A structural write: the caller has
    * locked the round first ([[RoundWrites]]; checked).
    *
    * @param details page id -> `dq_detail`
    * @param prior   page id -> the row's status before the caller's moves ([[statuses]], read
    *                under the round's lock before them); required for every row to mark
    * @return the rows marked
    */
  def disqualify(
      roundId: Long,
      details: Map[Long, String],
      code: String,
      by: Option[Long],
      prior: Map[Long, String]
  )(implicit session: DBSession): Int = {
    require(DqCode.All.contains(code), s"unknown dq_code $code")
    if (details.isEmpty) 0
    else {
      RoundWrites.requireRounds(Seq(roundId))
      val pageIds = details.keys.toSeq.sorted
      val found = sql"""SELECT page_id, status FROM round_image WHERE round_id = $roundId AND page_id IN ($pageIds)
                        ORDER BY page_id FOR UPDATE""".map(rs => rs.long(1) -> rs.string(2)).list()
      val toMark = found.collect { case (pageId, status) if status != Status.Dq => pageId }
      val unknown = toMark.filterNot(prior.contains)
      require(unknown.isEmpty, s"no prior status for pages ${unknown.take(10).mkString(", ")} of round $roundId")
      val marked = details.toSeq.groupBy { case (_, detail) => detail.take(DqDetailLength) }.toSeq.sortBy(_._1).map {
        case (detail, ofDetail) =>
          sql"""UPDATE round_image SET status = ${Status.Dq}, dq_code = $code, dq_detail = $detail, dq_by = $by,
                  dq_at = NOW(3)
                WHERE round_id = $roundId AND page_id IN (${ofDetail.map(_._1).sorted}) AND status <> ${Status.Dq}"""
            .update()
      }.sum
      // the rows found locked and not yet `dq` are those the updates marked
      val changes = toMark.map(pageId => RoundImageEventJdbc.Change(pageId, prior(pageId), Some(Status.Dq)))
      RoundImageEventJdbc.record(roundId, RoundImageEventJdbc.Action.Disqualify, code, by, changes)
      marked
    }
  }

  /** Marks `deleted` the rows of `pageIds` in the rounds `roundIds` that are `in` with no
    * `vote` rows (P3-1: a file filled and not distributed yet, deleted on Commons): the deletion
    * paths move a deleted file's votes aside ([[ImageJdbc.archiveVotes]]) and so reach only
    * pairs with votes. Run after those moves, in their transaction, with the rounds locked for
    * update ([[RoundWrites]]; checked): no vote is inserted for them meanwhile.
    *
    * @return the rounds that had rows marked
    */
  def markVotelessDeleted(pageIds: Seq[Long], roundIds: Set[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty || roundIds.isEmpty) Nil
    else {
      val rounds = roundIds.toSeq.sorted
      RoundWrites.requireRounds(rounds)
      val pairs = pageIds.distinct.sorted.grouped(Chunk).flatMap { chunk =>
        sql"""SELECT ri.round_id, ri.page_id FROM round_image ri
              WHERE ri.round_id IN ($rounds) AND ri.page_id IN ($chunk) AND ri.status = ${Status.In}
                AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = ri.round_id AND v.page_id = ri.page_id)
              ORDER BY ri.round_id, ri.page_id FOR UPDATE""".map(rs => rs.long(1) -> rs.long(2)).list()
      }.toSeq
      setStatus(pairs, from = Status.In, to = Status.Deleted)
    }

  /** Marks `in` again the `deleted` rows of `pageIds` with no votes in `vote` or `vote_aside`
    * (a file restored on Commons that [[markVotelessDeleted]] marked), after the restore's votes
    * moved back ([[ImageJdbc.restoreVotes]]), in its transaction. The caller has locked their
    * rounds ([[ImageJdbc.lockRoundsOfArchived]]; checked).
    *
    * @return the rounds that had rows marked
    */
  def restoreVoteless(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else {
      val pairs = pageIds.distinct.sorted.grouped(Chunk).flatMap { chunk =>
        sql"""SELECT ri.round_id, ri.page_id FROM round_image ri
              WHERE ri.page_id IN ($chunk) AND ri.status = ${Status.Deleted}
                AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = ri.round_id AND v.page_id = ri.page_id)
                AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.round_id = ri.round_id AND d.page_id = ri.page_id)
              ORDER BY ri.round_id, ri.page_id""".map(rs => rs.long(1) -> rs.long(2)).list()
      }.toSeq
      // locked by the caller; one whose row was marked since its read is locked now (as moveVotesBack)
      RoundWrites.lockMissing(pairs.map(_._1).distinct.sorted)
      RoundWrites.requireRounds(pairs.map(_._1).distinct)
      setStatus(pairs, from = Status.Deleted, to = Status.In)
    }

  /** The rounds of `pairs` whose rows went from `from` to `to`. */
  private def setStatus(pairs: Seq[(Long, Long)], from: String, to: String)(implicit session: DBSession): Seq[Long] =
    pairs.groupBy(_._1).toSeq.sortBy(_._1).flatMap { case (roundId, ofRound) =>
      val changed = ofRound.map(_._2).sorted.grouped(Chunk).map { pages =>
        sql"""UPDATE round_image SET status = $to WHERE round_id = $roundId AND page_id IN ($pages)
              AND status = $from""".update()
      }.sum
      Option.when(changed > 0)(roundId)
    }

  /** The statuses of the round's files `pageIds` that have a row: read plainly, under the
    * round's lock the caller holds (every writer of a round's rows but a juror's vote holds it,
    * and a vote doesn't change a status).
    */
  def statuses(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Map[Long, String] =
    pageIds.distinct.sorted.grouped(Chunk).flatMap { chunk =>
      sql"SELECT page_id, status FROM round_image WHERE round_id = $roundId AND page_id IN ($chunk)"
        .map(rs => rs.long(1) -> rs.string(2)).list()
    }.toMap

  /** The rows' copy of the image's monument id (as vote's, V48), after the image's row is
    * updated and locked. Rows already right aren't rewritten.
    */
  def syncMonumentId(pageId: Long, monumentId: Option[String])(implicit session: DBSession): Unit = {
    RoundWrites.requireFiles(Seq(pageId))
    sql"""UPDATE round_image SET monument_id = $monumentId
          WHERE page_id = $pageId AND NOT (monument_id <=> $monumentId)""".update(): Unit
  }

  // ── reads ────────────────────────────────────────────────────────────────

  /** The number of files `in` each round (rounds without any left out): one range of the
    * primary key per round. Callers go through [[RoundImageCounts]].
    */
  def countsIn(roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, Int] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"""SELECT round_id, COUNT(*) FROM round_image
            WHERE round_id IN ($roundIds) AND status = ${Status.In} GROUP BY round_id"""
        .map(rs => rs.long(1) -> rs.int(2))
        .list()
        .toMap

  /** The round's files `in` it. */
  def countIn(roundId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = $roundId AND status = ${Status.In}"
      .map(_.long(1)).single().getOrElse(0L)

  /** The round's files by status (`in`, `deleted`, `dq`): its review page's tabs. */
  def statusCounts(roundId: Long)(implicit session: DBSession = AutoSession): Map[String, Int] =
    sql"SELECT status, COUNT(*) FROM round_image WHERE round_id = $roundId GROUP BY status"
      .map(rs => rs.string(1) -> rs.int(2)).list().toMap

  /** The round's files of `status`, by page id: what its review page and jurors form count by
    * country (services.places.PlaceScheme.Countries.counts). One range of the primary key.
    */
  def pageIds(roundId: Long, status: String)(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT page_id FROM round_image WHERE round_id = $roundId AND status = $status ORDER BY page_id"
      .map(_.long(1)).list()

  /** The round's files `in` it by how they were added ([[AddedHow]]): its review page's summary. */
  def addedHowCounts(roundId: Long)(implicit session: DBSession = AutoSession): Map[String, Int] =
    sql"""SELECT added_how, COUNT(*) FROM round_image WHERE round_id = $roundId AND status = ${Status.In}
          GROUP BY added_how""".map(rs => rs.string(1) -> rs.int(2)).list().toMap

  /** The round's files `in` it with no votes, in `vote` or aside, whose image isn't deleted on
    * Commons: those a distribution still has to give its jurors (P3's `distribute` step).
    */
  def undistributedCount(roundId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"""SELECT COUNT(*) FROM round_image ri JOIN images i ON i.page_id = ri.page_id
          WHERE ri.round_id = $roundId AND ri.status = ${Status.In} AND i.deleted_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = ri.round_id AND v.page_id = ri.page_id)
            AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.round_id = ri.round_id AND d.page_id = ri.page_id)"""
      .map(_.long(1)).single().getOrElse(0L)

  /** The next window of [[undistributedCount]]'s files after `cursor`, by page id: at most
    * `limit` page ids. Read under the round's lock (the distribute step holds it).
    */
  def undistributedWindow(roundId: Long, cursor: Long, limit: Int)(implicit session: DBSession): Seq[Long] =
    sql"""SELECT ri.page_id FROM round_image ri JOIN images i ON i.page_id = ri.page_id
          WHERE ri.round_id = $roundId AND ri.status = ${Status.In} AND ri.page_id > $cursor AND i.deleted_at IS NULL
            AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = ri.round_id AND v.page_id = ri.page_id)
            AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.round_id = ri.round_id AND d.page_id = ri.page_id)
          ORDER BY ri.page_id LIMIT $limit""".map(_.long(1)).list()

  /** Whether the round has a vote, in `vote` or aside (`vote_aside`): a re-apply deletes no file
    * then (D16). Read under the round's lock by the writers that depend on it.
    */
  def hasVotes(roundId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT 1 FROM vote WHERE round_id = $roundId
          UNION ALL SELECT 1 FROM vote_aside WHERE round_id = $roundId LIMIT 1""".map(_ => ()).first().isDefined

  /** How the rows a re-apply checks against the filters were added (P3-5): by the filters, of the
    * creation's fill or of "Distribute new files" in review (the maintainer, 2026-11-16).
    */
  val Prunable: Seq[String] = Seq(AddedHow.Fill, AddedHow.NewFiles)

  /** The next window after `cursor`, by page id, of the round's files a re-apply checks against the
    * filters (P3-5): `in`, added by the filters ([[Prunable]]), not by the job `jobId`. At most
    * `limit` page ids.
    */
  def filledWindow(roundId: Long, jobId: Long, cursor: Long, limit: Int)(implicit session: DBSession): Seq[Long] =
    sql"""SELECT page_id FROM round_image
          WHERE round_id = $roundId AND page_id > $cursor AND status = ${Status.In} AND added_how IN ($Prunable)
            AND NOT (added_by_job_id <=> $jobId)
          ORDER BY page_id LIMIT $limit""".map(_.long(1)).list()

  /** Deletes the round's `in` rows of `pageIds` the filters added ([[Prunable]]; a re-apply's Prune,
    * D16: of a round with no vote; the caller holds the round's lock and has checked that). A row of
    * any other status or origin, or with a vote or one aside, is never deleted here.
    *
    * @return the rows deleted
    */
  def prune(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Int =
    if (pageIds.isEmpty) 0
    else {
      RoundWrites.requireRounds(Seq(roundId))
      pageIds.distinct.sorted.grouped(Chunk).map { chunk =>
        sql"""DELETE FROM round_image
              WHERE round_id = $roundId AND page_id IN ($chunk) AND status = ${Status.In} AND added_how IN ($Prunable)
                AND NOT EXISTS (SELECT 1 FROM vote v WHERE v.round_id = round_image.round_id AND v.page_id = round_image.page_id)
                AND NOT EXISTS (SELECT 1 FROM vote_aside d
                                WHERE d.round_id = round_image.round_id AND d.page_id = round_image.page_id)"""
          .update()
      }.sum
    }

  /** The round's files, whatever their status: those with votes moved aside too. */
  def countAll(roundId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = $roundId".map(_.long(1)).single().getOrElse(0L)

  /** The round's files, whatever their status, that the job added (`added_by_job_id`). */
  def countAddedBy(roundId: Long, jobId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = $roundId AND added_by_job_id = $jobId"
      .map(_.long(1)).single().getOrElse(0L)

  /** The round's files, whatever their status, that the job didn't add (`added_by_job_id`). */
  def countNotAddedBy(roundId: Long, jobId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = $roundId AND NOT (added_by_job_id <=> $jobId)"
      .map(_.long(1)).single().getOrElse(0L)

  /** Whether the file is `in` the round. */
  def isIn(roundId: Long, pageId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT 1 FROM round_image WHERE round_id = $roundId AND page_id = $pageId AND status = ${Status.In}"""
      .map(_ => ()).single().isDefined

  def find(roundId: Long, pageId: Long)(implicit session: DBSession = AutoSession): Option[Row] =
    sql"""SELECT round_id, page_id, status, added_how, added_by_job_id, monument_id, region FROM round_image
          WHERE round_id = $roundId AND page_id = $pageId"""
      .map(row).single()

  /** The round's rows, by page id. */
  def byRound(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Row] =
    sql"""SELECT round_id, page_id, status, added_how, added_by_job_id, monument_id, region FROM round_image
          WHERE round_id = $roundId ORDER BY page_id"""
      .map(row).list()

  /** A disqualified file's row: why, by whom, when. */
  final case class DqRow(
      pageId: Long,
      code: Option[String],
      detail: Option[String],
      by: Option[Long],
      at: Option[java.time.ZonedDateTime]
  )

  /** The round's disqualified files, by page id. */
  def disqualified(roundId: Long)(implicit session: DBSession = AutoSession): Seq[DqRow] =
    sql"""SELECT page_id, dq_code, dq_detail, dq_by, UNIX_TIMESTAMP(dq_at) FROM round_image
          WHERE round_id = $roundId AND status = ${Status.Dq} ORDER BY page_id"""
      .map(rs => DqRow(rs.long(1), rs.stringOpt(2), rs.stringOpt(3), rs.longOpt(4),
        DbTime.instantOpt(rs, 5).map(DbTime.local))) // whatever the zones (DbTime)
      .list()

  private def row(rs: WrappedResultSet): Row =
    Row(rs.long(1), rs.long(2), rs.string(3), rs.string(4), rs.longOpt(5), rs.stringOpt(6), rs.stringOpt(7))
}
