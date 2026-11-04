package db.scalikejdbc

import scalikejdbc._

/** A round's files (`round_image`, V84): one row per round and file, kept in step with the
  * round's `vote` and `vote_aside` rows in the same transaction as their writes
  * (docs/plans/2026-11-02-round-images-two-step.md, §5 and P1).
  *
  * Until P3 (a round filled before it is distributed) a row is exactly a (round, file) pair
  * with votes: status `in` while the pair has `vote` rows, `deleted` while its votes are only
  * in `vote_aside` (a file deleted on Commons in an open round), and no row once the pair has
  * none ([[RoundImageInvariants]]). A disqualified file (`dq`, P1b: [[RoundConflicts]]) keeps
  * its row, with its votes aside, whatever its votes: only an explicit requalify
  * ([[requalifyRows]]) takes it back to `in`, so "Distribute new files" never adds it again.
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
  *  - the `round_image_check` job repairs drift ([[services.distribution.RoundImageCheckHandler]]).
  *
  * Lock order: `round` → `images` → `vote` → `criteria_rate` → `vote_aside` → `round_image`
  * → `round_image_event` (V88: a disqualify's or requalify's events, after its rows): every
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

  /** What a [[sync]] changed: rows added, statuses changed, rows removed (their pair has no
    * votes left).
    */
  final case class SyncResult(added: Int = 0, statusChanged: Int = 0, removed: Int = 0) {
    def +(o: SyncResult): SyncResult = SyncResult(added + o.added, statusChanged + o.statusChanged, removed + o.removed)
    def isEmpty: Boolean = added == 0 && statusChanged == 0 && removed == 0
  }

  /** Page ids per statement: an `IN` list the (round_id, page_id) key reads one by one. */
  private[scalikejdbc] val Chunk = 2000

  /** Runs `f` in the caller's transaction, or in one of its own when the caller passed an
    * auto-commit session (the DAO defaults): a vote insert and its rows commit together.
    */
  private[scalikejdbc] def inTransaction[A](session: DBSession)(f: DBSession => A): A =
    session match {
      case _: AutoSession            => WriteTx.write(f)
      case NamedAutoSession(name, _) => WriteTx.retry(NamedDB(name).localTx(f))
      case s                         => f(s)
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
              ON DUPLICATE KEY UPDATE status = IF(status = ${Status.Dq}, ${Status.Dq}, ${Status.In})""".update()
        val disqualified = sql"""SELECT page_id FROM round_image
                                 WHERE round_id = $roundId AND page_id IN ($pageIds) AND status = ${Status.Dq}
                                 ORDER BY page_id LIMIT 10"""
          .map(_.long(1)).list()
        if (disqualified.nonEmpty)
          throw new IllegalStateException(
            s"Votes inserted for disqualified files of round $roundId (pages ${disqualified.mkString(", ")}): refused")
      }
    }
  }

  /** Brings the rows of the round's files `pageIds` in line with their votes, after a bulk
    * write of those votes in the same transaction: `in` where the pair has `vote` rows; else
    * `deleted` where it has `vote_aside` rows; no row where it has neither. A `dq` row stays
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
    * their votes as any other row (`in` with votes, `deleted` with votes only aside, no row with
    * none), and lose their `dq_*`. Each row that leaves `dq` is recorded, after the rows, as a
    * `requalify` event with `reason` ([[RoundImageEventJdbc]], V88): its status before (`dq`)
    * and after.
    *
    * @param reason one of [[RoundConflicts.RequalifyReason.All]]
    */
  private[scalikejdbc] def requalifyRows(roundId: Long, pageIds: Seq[Long], reason: String, by: Option[Long])(implicit
      session: DBSession
  ): SyncResult = {
    val (result, lifted) = syncWith(roundId, pageIds, Origin.Manual, requalify = pageIds.toSet)
    RoundImageEventJdbc.record(roundId, RoundImageEventJdbc.Action.Requalify, reason, by,
      lifted.map { case (pageId, now) => RoundImageEventJdbc.Change(pageId, Status.Dq, now) })
    result
  }

  /** The rows synced, and those that left `dq`: page id -> their status now (None: removed). */
  private def syncWith(roundId: Long, pageIds: Seq[Long], missing: Origin, requalify: Set[Long])(implicit
      session: DBSession
  ): (SyncResult, Seq[(Long, Option[String])]) = {
    if (pageIds.nonEmpty) RoundWrites.requireRounds(Seq(roundId))
    pageIds.distinct.sorted.grouped(Chunk).foldLeft((SyncResult(), Seq.empty[(Long, Option[String])])) {
      case ((acc, lifted), chunk) =>
        val (r, l) = syncChunk(roundId, chunk, missing, requalify)
        (acc + r, lifted ++ l)
    }
  }

  /** [[sync]] of each round's pairs, rounds in id order. */
  def syncPairs(pairs: Iterable[(Long, Long)], missing: Origin = Origin.Manual)(implicit session: DBSession): SyncResult =
    pairs.groupBy(_._1).toSeq.sortBy(_._1).foldLeft(SyncResult()) { case (acc, (roundId, ofRound)) =>
      acc + sync(roundId, ofRound.map(_._2).toSeq, missing)
    }

  private def syncChunk(roundId: Long, pageIds: Seq[Long], missing: Origin, requalify: Set[Long])(implicit
      session: DBSession
  ): (SyncResult, Seq[(Long, Option[String])]) = {
    val voted = sql"""SELECT DISTINCT page_id FROM vote WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val aside = sql"""SELECT DISTINCT page_id FROM vote_aside WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val rows = sql"""SELECT page_id, status FROM round_image WHERE round_id = $roundId AND page_id IN ($pageIds)
                     FOR UPDATE""".map(rs => rs.long(1) -> rs.string(2)).list().toMap

    def wanted(pageId: Long): Option[String] =
      if (rows.get(pageId).contains(Status.Dq) && !requalify(pageId)) Some(Status.Dq)
      else if (voted(pageId)) Some(Status.In)
      else if (aside(pageId)) Some(Status.Deleted)
      else None

    val gone = rows.keys.filter(wanted(_).isEmpty).toSeq.sorted
    val changed = rows.toSeq.collect { case (p, status) if wanted(p).exists(_ != status) => p -> wanted(p).get }
    val toAdd = pageIds.filter(p => !rows.contains(p) && wanted(p).isDefined)

    if (gone.nonEmpty) sql"DELETE FROM round_image WHERE round_id = $roundId AND page_id IN ($gone)".update()
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
    (SyncResult(added = added, statusChanged = changed.size, removed = gone.size), lifted)
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

  /** The round's files, whatever their status: those with votes moved aside too. */
  def countAll(roundId: Long)(implicit session: DBSession = AutoSession): Long =
    sql"SELECT COUNT(*) FROM round_image WHERE round_id = $roundId".map(_.long(1)).single().getOrElse(0L)

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
    sql"""SELECT page_id, dq_code, dq_detail, dq_by, dq_at FROM round_image
          WHERE round_id = $roundId AND status = ${Status.Dq} ORDER BY page_id"""
      .map(rs => DqRow(rs.long(1), rs.stringOpt(2), rs.stringOpt(3), rs.longOpt(4), rs.zonedDateTimeOpt(5)))
      .list()

  private def row(rs: WrappedResultSet): Row =
    Row(rs.long(1), rs.long(2), rs.string(3), rs.string(4), rs.longOpt(5), rs.stringOpt(6), rs.stringOpt(7))
}
