package db.scalikejdbc

import db.scalikejdbc.RoundImageJdbc.Status
import scalikejdbc._

/** The invariants that keep `round_image` (V84) in step with the votes
  * (docs/plans/2026-11-02-round-images-two-step.md, §5), checked per round. Specs assert
  * [[violations]] empty after each scenario; the `round_image_check` job repairs what it can
  * (1, 2, 6, 8; 3 for `deleted` rows) and reports the rest (3 for `dq` rows: a vote written
  * for a disqualified file). Only pairs of a round and an image that exist count:
  * vote.round_id and vote_aside have no foreign keys, and V84 left such pairs out.
  *
  *  1. Every (round, file) with a `vote` row has a row with status `in`.
  *  2. Every `vote_aside` pair without `vote` rows has a row with status `deleted` or `dq`.
  *  3. `dq` and `deleted` rows have no `vote` rows.
  *  4. A `deleted` row's image has `deleted_at` set (reported, not repaired: its votes would
  *     have to move back).
  *  5. The counts equal the GROUP BY over the pair's `vote` rows (P2a, V89), once the round's
  *     counts are current ([[RoundCounts.isClean]]: no file marked, no recount pending; the
  *     background recompute gets there seconds after a write). Not checked while dirty.
  *  6. `monument_id` equals `images.monument_id`.
  *  7. Every `criteria_rate.vote_id` is a vote's in `vote` or `vote_aside` (not per round:
  *     [[criteriaOrphans]]).
  *  8. Until P3 (a round filled before it is distributed): a row has votes, `in` in `vote`,
  *     `deleted` in `vote_aside`. A `dq` row (P1b) may have none: it is kept whatever its
  *     votes, so that the file is never distributed again ([[RoundImageJdbc.sync]]).
  *  9. A `dq` row has its `dq_code` and `dq_at`; any other row has no `dq_*` (P1b; reported).
  */
object RoundImageInvariants {

  final case class Violation(invariant: Int, roundId: Long, pageId: Long)

  def violations(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Violation] = {
    def pages(invariant: Int, query: SQL[Nothing, NoExtractor]): Seq[Violation] =
      query.map(rs => Violation(invariant, roundId, rs.long(1))).list()

    val roundExists = sql"SELECT 1 FROM round WHERE id = $roundId".map(_ => ()).single().isDefined
    if (!roundExists) Nil
    else {
      val inVote = sqls"EXISTS (SELECT 1 FROM vote s WHERE s.round_id = ri.round_id AND s.page_id = ri.page_id)"
      val inAside = sqls"EXISTS (SELECT 1 FROM vote_aside d WHERE d.round_id = ri.round_id AND d.page_id = ri.page_id)"
      val aside = Seq(Status.Deleted, Status.Dq)
      Seq(
        pages(1, sql"""SELECT DISTINCT v.page_id FROM vote v JOIN images i ON i.page_id = v.page_id
                       WHERE v.round_id = $roundId AND NOT EXISTS (SELECT 1 FROM round_image ri
                         WHERE ri.round_id = v.round_id AND ri.page_id = v.page_id AND ri.status = ${Status.In})
                       ORDER BY v.page_id"""),
        pages(2, sql"""SELECT DISTINCT d.page_id FROM vote_aside d JOIN images i ON i.page_id = d.page_id
                       WHERE d.round_id = $roundId
                         AND NOT EXISTS (SELECT 1 FROM vote s WHERE s.round_id = d.round_id AND s.page_id = d.page_id)
                         AND NOT EXISTS (SELECT 1 FROM round_image ri
                           WHERE ri.round_id = d.round_id AND ri.page_id = d.page_id AND ri.status IN ($aside))
                       ORDER BY d.page_id"""),
        pages(3, sql"""SELECT ri.page_id FROM round_image ri
                       WHERE ri.round_id = $roundId AND ri.status IN ($aside) AND $inVote ORDER BY ri.page_id"""),
        pages(4, sql"""SELECT ri.page_id FROM round_image ri JOIN images i ON i.page_id = ri.page_id
                       WHERE ri.round_id = $roundId AND ri.status = ${Status.Deleted} AND i.deleted_at IS NULL
                       ORDER BY ri.page_id"""),
        pages(6, sql"""SELECT ri.page_id FROM round_image ri JOIN images i ON i.page_id = ri.page_id
                       WHERE ri.round_id = $roundId AND NOT (ri.monument_id <=> i.monument_id)
                       ORDER BY ri.page_id"""),
        pages(8, sql"""SELECT ri.page_id FROM round_image ri
                       WHERE ri.round_id = $roundId
                         AND (ri.status = ${Status.In} AND NOT $inVote OR ri.status = ${Status.Deleted} AND NOT $inAside)
                       ORDER BY ri.page_id"""),
        pages(9, sql"""SELECT ri.page_id FROM round_image ri
                       WHERE ri.round_id = $roundId
                         AND (ri.status = ${Status.Dq} AND (ri.dq_code IS NULL OR ri.dq_at IS NULL)
                           OR ri.status <> ${Status.Dq} AND (ri.dq_code IS NOT NULL OR ri.dq_detail IS NOT NULL
                             OR ri.dq_by IS NOT NULL OR ri.dq_at IS NOT NULL))
                       ORDER BY ri.page_id""")
      ).flatten ++ counts(roundId)
    }
  }

  /** Invariant 5: the rows whose counts differ from their votes', if the round's counts are
    * current; none while it is dirty.
    */
  def counts(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Violation] =
    if (!RoundCounts.isClean(roundId)) Nil
    else RoundCounts.drift(roundId).map(d => Violation(5, roundId, d.pageId))

  /** [[violations]] of every round. */
  def all()(implicit session: DBSession = AutoSession): Seq[Violation] =
    sql"SELECT id FROM round ORDER BY id".map(_.long(1)).list().flatMap(violations(_)) ++
      criteriaOrphans().map(id => Violation(7, 0L, id))

  /** Invariant 7: the criteria rates (their ids) whose vote is in neither `vote` nor
    * `vote_aside`.
    */
  def criteriaOrphans()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"""SELECT cr.id FROM criteria_rate cr
          WHERE NOT EXISTS (SELECT 1 FROM vote v WHERE v.id = cr.vote_id)
            AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.id = cr.vote_id)
          ORDER BY cr.id""".map(_.long(1)).list()
}
