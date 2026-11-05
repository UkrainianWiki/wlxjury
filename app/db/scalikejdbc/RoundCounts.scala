package db.scalikejdbc

import scalikejdbc._

/** The vote counts of a round's files on `round_image` (V89: `assigned`, `voted`, `approvals`,
  * `rejections`, `rate_sum`), kept by dirty marks and a background recompute instead of deltas
  * in the vote's transaction (decision W6 of docs/plans/2026-11-03-write-path-simplification.md;
  * docs/plans/2026-11-02-round-images-two-step.md §9 "P2a" and §16). Read by all the jurors'
  * lists behind `roundImage.read` (P2b, [[rewrite.RoundImageQuery]], §17), which overlays the
  * marked files' votes, and reads the votes when the round's generation isn't clean or changes
  * across a statement ([[cleanGeneration]], [[generation]]). Exact
  * consumers (top-N distributions, the CSV, a finalize) read the votes themselves; the stored
  * counts alone may be seconds old.
  *
  * '''Marks.'''
  *  - A juror's vote whose rate changes upserts its file's entry in `round_image_dirty`
  *    ([[markFile]], the last statement of [[VoteJdbc.rate]] and
  *    [[org.intracer.wmua.CriteriaRate.updateRate]]): no count delta, no lock on the round or
  *    on `round_image`, only the entry's record (exclusive: the file's votes queue on it).
  *  - A bulk writer of a round's votes bumps the round's generation, `round.counts_gen`, in its
  *    own transaction ([[markRounds]]): it holds the round for update already (the round-first
  *    rule, [[RoundWrites]]), so it takes no new lock. The production ones, all through
  *    [[RoundImageJdbc]]: [[RoundImageJdbc.addVoted]] (every vote insert: a distribution chunk,
  *    [[VoteJdbc.create]] / [[VoteJdbc.batchInsert]]); [[ImageJdbc]]'s `moveVotesAside` and
  *    `moveVotesBack` through [[RoundImageJdbc.syncPairs]] (a file deleted or restored on
  *    Commons, a round's opening or a distribution's end, a disqualify's and a requalify's
  *    moves); [[RoundImageJdbc.requalifyRows]]; the repair job's [[RoundImageJdbc.sync]]
  *    ([[services.distribution.RoundImageCheckHandler]]). [[VoteJdbc.deleteVotes]] marks too, but
  *    only the test-only writers call it (`TestVoteWrites`, since W8).
  *  - Writes that need no mark, since the counts read only `vote`'s rows and rates: a change of
  *    a round's criteria ([[RoundCriterion.update]]: before the first vote no rate is set, after
  *    it only a criterion's renaming, which moves `criteria_rate` rows and leaves `vote.rate`);
  *    monument id and region updates (no count reads them); a juror stopped or started on the
  *    round (`round_user.active`: their vote rows stay). So the counts include a stopped juror's
  *    votes, as today's grouped reads over `vote` do (`ImageDbNew` doesn't join `round_user`);
  *    P2b keeps that, or leaves them out on both sides.
  *
  * '''The recompute''' ([[fileWindow]], [[roundWindow]]; run by
  * `services.counts.RoundCountsRecompute` on a timer, `modules.RoundCountsTask`), one window of
  * at most `limit` files per transaction:
  *  1. the round `FOR UPDATE` (round-first: no structural writer of the round runs meanwhile);
  *  2. the window's `round_image` rows `FOR UPDATE`, with their stored counts;
  *  3. the window's dirty entries `FOR UPDATE`: a vote that has marked its file and not yet
  *     committed holds its entry, so this waits for its commit; a vote that marks it after this
  *     waits for this transaction;
  *  4. the votes of the window's files, a plain read: at READ COMMITTED (every connection, db
  *     practices §6.1) the latest committed, so every vote whose entry was locked in 3 is read;
  *  5. the counts written where they differ, and the entries locked in 3 deleted (by key: an
  *     entry a vote inserted after 3 stays, for the next pass).
  *
  * A vote is thus either read by a pass or leaves its entry for the next one: none is lost.
  * A full recount ([[roundWindow]]) walks the round's rows by page id, starting from the
  * generation it read in its first window, and sets `counts_gen_done` to it at its end: a bulk
  * write between two of its windows bumps `counts_gen` past it, and the round stays dirty for
  * another pass. A window never runs while a structural writer holds the round.
  *
  * Lock order: `round` → … → `round_image` → `round_image_dirty` → `round_image_event`. A
  * juror's vote takes `vote` (→ `round_user`, `round_criterion` shared → `criteria_rate`) →
  * `round_image_dirty`; the recompute `round` → `round_image` → `round_image_dirty` and reads
  * `vote` without a lock; no other transaction locks `round_image_dirty`.
  */
object RoundCounts {

  /** A file's counts over its `vote` rows in the round. */
  final case class Counts(assigned: Int, voted: Int, approvals: Int, rejections: Int, rateSum: Int)

  object Counts {
    val Zero: Counts = Counts(0, 0, 0, 0, 0)
  }

  /** Files per recompute window (one transaction): ~100 ms on a 38k-file round's rows. */
  val DefaultWindow = 2000

  /** The isolation level the recompute needs (`@@tx_isolation`'s value): its plain read of the
    * votes after the locks must see the latest committed votes, which REPEATABLE READ's snapshot
    * may not.
    */
  val ReadCommitted = "READ-COMMITTED"

  /** The caller's connection's isolation level, as [[services.ReadinessCheck]] reads it
    * (`@@tx_isolation`: MariaDB 10.6 has no `@@transaction_isolation`, which 11.1 added).
    */
  def isolation()(implicit session: DBSession): String =
    sql"SELECT @@tx_isolation".map(_.string(1)).single().getOrElse("")

  // ── marks ──────────────────────────────────────────────────────────────────

  /** Marks the file's counts in the round dirty, in the caller's (the juror's vote's)
    * transaction, after the vote's rate is written: its last statement. The entry may be there
    * already (another vote since the last pass): a no-op `ON DUPLICATE KEY UPDATE`, which locks
    * the entry exclusively (a record lock, no gap), so the votes of one file queue on it.
    * Not `INSERT IGNORE`: its duplicate check takes a shared lock, and two votes that both held
    * one on an entry the recompute had just deleted both upgraded to re-insert it and
    * deadlocked (`RoundCountsLockSpec`, 20 jurors on one file: 56 deadlocks in 800 votes).
    */
  def markFile(roundId: Long, pageId: Long)(implicit session: DBSession): Unit =
    sql"""INSERT INTO round_image_dirty (round_id, page_id) VALUES ($roundId, $pageId)
          ON DUPLICATE KEY UPDATE round_id = round_id""".update(): Unit

  /** Marks the rounds' counts dirty (their generation bumped), in a bulk writer's transaction,
    * which holds the rounds for update ([[RoundWrites]]; checked). `created_at` is set to itself:
    * production's `round.created_at` has `ON UPDATE CURRENT_TIMESTAMP` (V86).
    */
  private[scalikejdbc] def markRounds(roundIds: Iterable[Long])(implicit session: DBSession): Unit = {
    val ids = roundIds.toSeq.distinct.sorted
    if (ids.nonEmpty) {
      RoundWrites.requireRounds(ids)
      sql"UPDATE round SET counts_gen = counts_gen + 1, created_at = created_at WHERE id IN ($ids)".update(): Unit
    }
  }

  // ── what is dirty ──────────────────────────────────────────────────────────

  /** The rounds whose counts need a full recount (`counts_gen_done < counts_gen`), open rounds
    * first, then the newest: a plain read of `round` (small; no index needed).
    */
  def roundsToRecount()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"""SELECT id FROM round WHERE counts_gen_done < counts_gen
          ORDER BY ${Round.openCond(sqls"round")} DESC, id DESC""".map(_.long(1)).list()

  /** The rounds with files marked dirty by votes: a loose scan of the entries' key. */
  def roundsWithDirtyFiles()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT DISTINCT round_id FROM round_image_dirty ORDER BY round_id".map(_.long(1)).list()

  /** The round's counts are current: no full recount pending and no file marked. */
  def isClean(roundId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"SELECT counts_gen_done >= counts_gen FROM round WHERE id = $roundId".map(_.boolean(1)).single().getOrElse(true) &&
      sql"SELECT 1 FROM round_image_dirty WHERE round_id = $roundId LIMIT 1".map(_ => ()).single().isEmpty

  /** The round's generation (`counts_gen`), if no full recount of it is pending
    * (`counts_gen_done >= counts_gen`): while it stays the same, its stored counts with its marked
    * files' votes are its votes (P2b, `controllers.RoundList.fromCounts`). A primary-key read;
    * None for a round that doesn't exist.
    */
  def cleanGeneration(roundId: Long)(implicit session: DBSession = AutoSession): Option[Long] =
    sql"SELECT counts_gen, counts_gen_done FROM round WHERE id = $roundId"
      .map(rs => rs.long(1) -> rs.long(2)).single().collect { case (gen, done) if done >= gen => gen }

  /** The round's generation (`counts_gen`): bumped by every bulk writer of its votes. */
  def generation(roundId: Long)(implicit session: DBSession = AutoSession): Option[Long] =
    sql"SELECT counts_gen FROM round WHERE id = $roundId".map(_.long(1)).single()

  /** The round's generation, the generation of its last full recount, and its files marked. */
  final case class State(gen: Long, genDone: Long, dirtyFiles: Long)

  def state(roundId: Long)(implicit session: DBSession = AutoSession): Option[State] =
    sql"""SELECT counts_gen, counts_gen_done,
                 (SELECT COUNT(*) FROM round_image_dirty d WHERE d.round_id = round.id)
          FROM round WHERE id = $roundId""".map(rs => State(rs.long(1), rs.long(2), rs.long(3))).single()

  // ── the recompute's windows ────────────────────────────────────────────────

  /** What a window did: files read, counts changed, dirty entries cleared, and the page id to
    * continue a full recount after (None: done).
    */
  final case class Window(files: Int, changed: Int, cleared: Int, next: Option[Long])

  /** A window of the round's full recount. */
  sealed trait RecountStep
  object RecountStep {

    /** The window ran; `next` (in [[Window]]) is where the recount goes on. */
    final case class Ran(window: Window, gen: Long) extends RecountStep

    /** Nothing to do: the round's last recount is as new as its generation. */
    case object Clean extends RecountStep

    /** Not now: the round is being distributed (created, or its distribution job queued or
      * running), whose chunks bump the generation; the recount runs once it has ended.
      */
    case object Distributing extends RecountStep

    /** The round no longer exists. */
    case object Gone extends RecountStep
  }

  /** Locks the round for update (recorded, [[RoundWrites]]): its generation, the generation of
    * its last recount and whether it is being created; None if it is gone.
    */
  private def lockRound(roundId: Long)(implicit session: DBSession): Option[(Long, Long, Boolean)] = {
    val row = sql"SELECT counts_gen, counts_gen_done, distributing FROM round WHERE id = $roundId FOR UPDATE"
      .map(rs => (rs.long(1), rs.long(2), rs.booleanOpt(3).getOrElse(false))).single()
    row.foreach(_ => RoundWrites.recordRounds(Seq(roundId)))
    row
  }

  /** The files' counts over their `vote` rows, read plainly (the latest committed at READ
    * COMMITTED) from `vote_round_page_rate_idx`: `cond` on alias `v` narrows the round's votes
    * to the window's files. A NULL rate is unrated.
    */
  private def votedCounts(roundId: Long, cond: SQLSyntax)(implicit session: DBSession): Map[Long, Counts] =
    sql"""SELECT v.page_id, COUNT(*), COALESCE(SUM(${VoteSql.voted(VoteSql.v)}), 0),
                 COALESCE(SUM(${VoteSql.positive(VoteSql.v)}), 0),
                 COALESCE(SUM(${VoteSql.rejected(VoteSql.v)}), 0), COALESCE(SUM(v.rate), 0)
          FROM vote v WHERE v.round_id = $roundId AND $cond GROUP BY v.page_id"""
      .map(rs => rs.long(1) -> Counts(rs.int(2), rs.int(3), rs.int(4), rs.int(5), rs.int(6))).list().toMap

  private def stored(rs: WrappedResultSet): (Long, Counts) =
    rs.long(1) -> Counts(rs.int(2), rs.int(3), rs.int(4), rs.int(5), rs.int(6))

  private val countColumns = sqls"page_id, assigned, voted, approvals, rejections, rate_sum"

  /** Writes the counts of the round's rows that differ from `actual` (a file without votes: 0),
    * by key, in one batch. The caller holds the round and the rows for update (checked: the
    * round).
    * @return the rows changed
    */
  private def writeCounts(roundId: Long, rows: Seq[(Long, Counts)], actual: Map[Long, Counts])(implicit
      session: DBSession
  ): Int = {
    RoundWrites.requireRounds(Seq(roundId))
    val changed = rows.collect { case (pageId, was) if actual.getOrElse(pageId, Counts.Zero) != was =>
      pageId -> actual.getOrElse(pageId, Counts.Zero)
    }
    if (changed.nonEmpty)
      sql"""UPDATE round_image SET assigned = ?, voted = ?, approvals = ?, rejections = ?, rate_sum = ?
            WHERE round_id = ? AND page_id = ?"""
        .batch(changed.map { case (p, c) => Seq(c.assigned, c.voted, c.approvals, c.rejections, c.rateSum, roundId, p) }: _*)
        .apply()
    changed.size
  }

  /** Deletes the round's dirty entries `pageIds`, which the caller has locked. */
  private def clear(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Int =
    if (pageIds.isEmpty) 0
    else sql"DELETE FROM round_image_dirty WHERE round_id = $roundId AND page_id IN ($pageIds)".update()

  /** One window of the round's files marked dirty by votes, the lowest page ids first, in the
    * caller's transaction (steps 1–5 above). A round that no longer exists loses its entries.
    */
  def fileWindow(roundId: Long, limit: Int = DefaultWindow)(implicit session: DBSession): Window =
    lockRound(roundId) match {
      case None =>
        // entries of a round deleted since (no foreign key): nothing to count
        Window(0, 0, sql"DELETE FROM round_image_dirty WHERE round_id = $roundId".update(), None)
      case Some(_) =>
        // which entries: a plain read under the round's lock (only this recompute deletes
        // entries, and it holds the round); a vote's entry added after it waits for the next one
        val candidates = sql"""SELECT page_id FROM round_image_dirty WHERE round_id = $roundId
                               ORDER BY page_id LIMIT $limit""".map(_.long(1)).list()
        if (candidates.isEmpty) Window(0, 0, 0, None)
        else {
          val rows = sql"""SELECT $countColumns FROM round_image
                           WHERE round_id = $roundId AND page_id IN ($candidates) ORDER BY page_id FOR UPDATE"""
            .map(stored).list()
          val locked = sql"""SELECT page_id FROM round_image_dirty
                             WHERE round_id = $roundId AND page_id IN ($candidates) ORDER BY page_id FOR UPDATE"""
            .map(_.long(1)).list()
          val actual = if (rows.isEmpty) Map.empty[Long, Counts]
                       else votedCounts(roundId, sqls"v.page_id IN (${rows.map(_._1)})")
          val changed = writeCounts(roundId, rows, actual)
          val cleared = clear(roundId, locked)
          Window(rows.size, changed, cleared, Option.when(candidates.size >= limit)(candidates.last))
        }
    }

  /** One window of the round's full recount, in the caller's transaction (steps 1–5 above):
    * the rows after page id `after` (None: from the first), up to `limit`, and every dirty
    * entry up to the window's last row (past the round's last row in its last window). The
    * first window (`gen` None) reads the generation the recount starts from; the last sets
    * `counts_gen_done` to it.
    *
    * @param gen          the generation the recount started from (None: start one)
    * @param distribution the dedupe key of the round's distribution job
    */
  def roundWindow(roundId: Long, gen: Option[Long], after: Option[Long], distribution: String,
      limit: Int = DefaultWindow)(implicit session: DBSession): RecountStep =
    lockRound(roundId) match {
      case None => RecountStep.Gone
      case Some((current, done, distributing)) =>
        // a plain read under the round's lock: a distribution is queued, and ends, under it
        def distributionActive =
          sql"SELECT 1 FROM job WHERE active_dedupe_key = $distribution".map(_ => ()).single().isDefined
        if (gen.isEmpty && done >= current) RecountStep.Clean
        else if (distributing || distributionActive) RecountStep.Distributing
        else {
          val from = gen.getOrElse(current)
          val start = after.getOrElse(Long.MinValue)
          val rows = sql"""SELECT $countColumns FROM round_image WHERE round_id = $roundId AND page_id > $start
                           ORDER BY page_id LIMIT $limit FOR UPDATE""".map(stored).list()
          val last = rows.size < limit
          val upTo = if (last) sqls"" else sqls"AND page_id <= ${rows.last._1}"
          val locked = sql"""SELECT page_id FROM round_image_dirty WHERE round_id = $roundId AND page_id > $start $upTo
                             ORDER BY page_id FOR UPDATE""".map(_.long(1)).list()
          val actual = if (rows.isEmpty) Map.empty[Long, Counts]
                       else votedCounts(roundId, sqls"v.page_id > $start AND v.page_id <= ${rows.last._1}")
          val changed = writeCounts(roundId, rows, actual)
          val cleared = clear(roundId, locked)
          if (last)
            sql"""UPDATE round SET counts_gen_done = $from, created_at = created_at
                  WHERE id = $roundId AND counts_gen_done < $from""".update()
          RecountStep.Ran(Window(rows.size, changed, cleared, Option.unless(last)(rows.last._1)), from)
        }
    }

  // ── invariant 5 ────────────────────────────────────────────────────────────

  /** A row whose stored counts differ from its votes'. */
  final case class Drift(pageId: Long, stored: Counts, actual: Counts)

  /** The round's rows whose counts differ from the GROUP BY over their `vote` rows (invariant 5,
    * [[RoundImageInvariants]]): a plain read of both, meaningful when the round is
    * [[isClean]] and no vote is being written.
    */
  def drift(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Drift] =
    sql"""SELECT ri.page_id, ri.assigned, ri.voted, ri.approvals, ri.rejections, ri.rate_sum,
                 COALESCE(v.a, 0), COALESCE(v.vt, 0), COALESCE(v.ap, 0), COALESCE(v.rj, 0), COALESCE(v.rs, 0)
          FROM round_image ri
          LEFT JOIN (SELECT page_id, COUNT(*) a, SUM(${VoteSql.voted(VoteSql.bare)}) vt,
                            SUM(${VoteSql.positive(VoteSql.bare)}) ap, SUM(${VoteSql.rejected(VoteSql.bare)}) rj, SUM(rate) rs
                     FROM vote WHERE round_id = $roundId GROUP BY page_id) v ON v.page_id = ri.page_id
          WHERE ri.round_id = $roundId
            AND (ri.assigned, ri.voted, ri.approvals, ri.rejections, ri.rate_sum)
                <> (COALESCE(v.a, 0), COALESCE(v.vt, 0), COALESCE(v.ap, 0), COALESCE(v.rj, 0), COALESCE(v.rs, 0))
          ORDER BY ri.page_id"""
      .map(rs => Drift(rs.long(1), Counts(rs.int(2), rs.int(3), rs.int(4), rs.int(5), rs.int(6)),
        Counts(rs.int(7), rs.int(8), rs.int(9), rs.int(10), rs.int(11))))
      .list()

  /** The round's file's stored counts. */
  def of(roundId: Long, pageId: Long)(implicit session: DBSession = AutoSession): Option[Counts] =
    sql"SELECT $countColumns FROM round_image WHERE round_id = $roundId AND page_id = $pageId"
      .map(stored).single().map(_._2)
}
