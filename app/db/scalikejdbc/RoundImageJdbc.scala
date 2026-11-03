package db.scalikejdbc

import scalikejdbc._

/** A round's files (`round_image`, V84): one row per round and file, kept in step with the
  * round's `vote` and `vote_aside` rows in the same transaction as their writes
  * (docs/plans/2026-11-02-round-images-two-step.md, §5 and P1).
  *
  * Until P3 (a round filled before it is distributed) a row is exactly a (round, file) pair
  * with votes: status `in` while the pair has `vote` rows, `deleted` while its votes are only
  * in `vote_aside` (a file deleted on Commons in an open round), `dq` (P1b) likewise, and no
  * row once the pair has none ([[RoundImageInvariants]]).
  *
  * Writers:
  *  - a vote insert ([[VoteJdbc.create]], [[VoteJdbc.batchInsert]], so a distribution, the
  *    in-memory path, the Gatling fixture and the test helpers) adds its files
  *    ([[addVoted]]);
  *  - a bulk write (moving votes aside or back, deleting a file's or the unrated votes, moving
  *    a file to another round, leaving out conflicts of interest) brings the pairs it touched
  *    in line with their votes afterwards ([[sync]]);
  *  - a monument id change follows the image ([[syncMonumentId]]);
  *  - a deleted round or image takes its rows with it (foreign keys, ON DELETE CASCADE);
  *  - the `round_image_check` job repairs drift ([[services.distribution.RoundImageCheckHandler]]).
  *
  * Lock order: `round` → `images` → `vote` → `criteria_rate` → `vote_aside` → `round_image`:
  * every writer here runs after its votes are written and locked, and locks the pairs' rows
  * last. A recount never reads a non-locking snapshot of the votes ([[sync]] reads them in
  * share mode): a concurrent write would be missed.
  */
object RoundImageJdbc {

  object Status {
    val In = "in"
    val Dq = "dq"
    val Deleted = "deleted"
    val All: Seq[String] = Seq(In, Dq, Deleted)
  }

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
      case _: AutoSession            => DB.localTx(f)
      case NamedAutoSession(name, _) => NamedDB(name).localTx(f)
      case s                         => f(s)
    }

  /** Locks the rounds in share mode, in id order: before a vote insert (or anything else that
    * adds rows here), as the lock order puts the round first. round_image's foreign key, and
    * [[addVoted]]'s read of the round, would otherwise lock it after the votes, and a
    * transaction holding the round for update while it locks the round's votes (a criteria
    * change, [[RoundCriterion.updateIn]]; leaving out conflicts of interest) then deadlocked
    * with the insert.
    */
  def lockRounds(roundIds: Seq[Long])(implicit session: DBSession): Unit =
    if (roundIds.nonEmpty)
      sql"SELECT id FROM round WHERE id IN (${roundIds.distinct.sorted}) ORDER BY id LOCK IN SHARE MODE"
        .map(_.long(1)).list(): Unit

  /** Adds the files of vote rows just inserted to their rounds, status `in`, after the
    * insert and in its transaction: the insert's foreign key check has locked the files'
    * image rows (shared), whose monument ids are copied. A row there already (a file moved
    * back, or votes added to a file whose votes are aside) becomes `in`; its `added_*` stay.
    * A vote of a round that doesn't exist (vote.round_id has no foreign key: scripts, old
    * specs) adds no row, as V84's backfill leaves such pairs out. The caller has locked the
    * rounds ([[lockRounds]], before the votes). P1b: `status = 'in'` here would requalify a
    * disqualified (`dq`) file; votes are never inserted for one (the fill's anti-join), and
    * P1b keeps `dq` here (plan §9).
    *
    * @param pairs (round id, page id) of the inserted votes
    */
  def addVoted(pairs: Iterable[(Long, Long)], origin: Origin)(implicit session: DBSession): Unit =
    pairs.groupBy(_._1).toSeq.sortBy(_._1).foreach { case (roundId, ofRound) =>
      ofRound.map(_._2).toSeq.distinct.sorted.grouped(Chunk).foreach { pageIds =>
        sql"""INSERT INTO round_image (round_id, page_id, status, added_how, added_by_job_id, added_at, monument_id)
              SELECT r.id, i.page_id, ${Status.In}, ${origin.how}, ${origin.jobId}, NOW(3), i.monument_id
              FROM round r JOIN images i ON i.page_id IN ($pageIds) WHERE r.id = $roundId ORDER BY i.page_id
              ON DUPLICATE KEY UPDATE status = ${Status.In}""".update()
      }
    }

  /** Brings the rows of the round's files `pageIds` in line with their votes, after a bulk
    * write of those votes in the same transaction: `in` where the pair has `vote` rows; else
    * `deleted` where it has `vote_aside` rows (`dq` stays `dq`); no row where it has neither.
    * The votes are read in share mode (the caller holds those it wrote; the others' latest
    * committed state, not the snapshot's), then the rows locked and written. A missing row is
    * added with `missing` as its origin, with its image's monument id.
    */
  def sync(roundId: Long, pageIds: Seq[Long], missing: Origin = Origin.Manual)(implicit session: DBSession): SyncResult =
    pageIds.distinct.sorted.grouped(Chunk).foldLeft(SyncResult()) { (acc, chunk) =>
      acc + syncChunk(roundId, chunk, missing)
    }

  /** [[sync]] of each round's pairs, rounds in id order. */
  def syncPairs(pairs: Iterable[(Long, Long)], missing: Origin = Origin.Manual)(implicit session: DBSession): SyncResult =
    pairs.groupBy(_._1).toSeq.sortBy(_._1).foldLeft(SyncResult()) { case (acc, (roundId, ofRound)) =>
      acc + sync(roundId, ofRound.map(_._2).toSeq, missing)
    }

  private def syncChunk(roundId: Long, pageIds: Seq[Long], missing: Origin)(implicit session: DBSession): SyncResult = {
    val voted = sql"""SELECT DISTINCT page_id FROM vote WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val aside = sql"""SELECT DISTINCT page_id FROM vote_aside WHERE round_id = $roundId AND page_id IN ($pageIds)
                      LOCK IN SHARE MODE""".map(_.long(1)).list().toSet
    val rows = sql"""SELECT page_id, status FROM round_image WHERE round_id = $roundId AND page_id IN ($pageIds)
                     FOR UPDATE""".map(rs => rs.long(1) -> rs.string(2)).list().toMap

    def wanted(pageId: Long): Option[String] =
      if (voted(pageId)) Some(Status.In)
      else if (aside(pageId)) Some(if (rows.get(pageId).contains(Status.Dq)) Status.Dq else Status.Deleted)
      else None

    val gone = rows.keys.filter(wanted(_).isEmpty).toSeq.sorted
    val changed = rows.toSeq.collect { case (p, status) if wanted(p).exists(_ != status) => p -> wanted(p).get }
    val toAdd = pageIds.filter(p => !rows.contains(p) && wanted(p).isDefined)

    if (gone.nonEmpty) sql"DELETE FROM round_image WHERE round_id = $roundId AND page_id IN ($gone)".update()
    changed.groupBy(_._2).foreach { case (status, ps) =>
      sql"""UPDATE round_image SET status = $status
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
    SyncResult(added = added, statusChanged = changed.size, removed = gone.size)
  }

  /** The rows' copy of the image's monument id (as vote's, V48), after the image's row is
    * updated and locked. Rows already right aren't rewritten.
    */
  def syncMonumentId(pageId: Long, monumentId: Option[String])(implicit session: DBSession): Unit =
    sql"""UPDATE round_image SET monument_id = $monumentId
          WHERE page_id = $pageId AND NOT (monument_id <=> $monumentId)""".update(): Unit

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

  private def row(rs: WrappedResultSet): Row =
    Row(rs.long(1), rs.long(2), rs.string(3), rs.string(4), rs.longOpt(5), rs.stringOpt(6), rs.stringOpt(7))
}
