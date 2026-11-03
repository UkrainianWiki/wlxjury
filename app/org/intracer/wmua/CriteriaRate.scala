package org.intracer.wmua

import scalikejdbc._

case class CriteriaRate(id: Long, voteId: Long, criterionId: Long, rate: Int)

object CriteriaRate extends SQLSyntaxSupport[CriteriaRate] {

  val c = CriteriaRate.syntax("c")

  def apply(c: SyntaxProvider[CriteriaRate])(
      rs: WrappedResultSet
  ): CriteriaRate = apply(c.resultName)(rs)

  def apply(c: ResultName[CriteriaRate])(rs: WrappedResultSet): CriteriaRate =
    new CriteriaRate(
      id = rs.long(c.id),
      voteId = rs.long(c.voteId),
      criterionId = rs.long(c.criterionId),
      rate = rs.int(c.rate)
    )

  /** Rates one criterion of a vote, in one transaction: the vote row is locked first
    * (`FOR UPDATE`), so votes on two criteria of one vote follow each other. The rate is
    * written whether the vote had one for the criterion or not (an upsert: a distribution
    * writes no criteria rates, V83), and the vote's rate set to the sum of its criteria's
    * rates once every criterion of the round is rated, else to 0 (U5: a criteria vote counts
    * as rated only when all its criteria are; a criterion rated 0 is unrated).
    *
    * Lock order: vote, round_criterion (shared: the round's criteria, which their change
    * replaces only after locking the round's votes, [[db.scalikejdbc.RoundCriterion.update]]),
    * criteria_rate: the rates of a vote are written only while it is locked, also when votes
    * are deleted ([[db.scalikejdbc.VoteJdbc.deleteVotes]]). Retried once if the database
    * picked it as a deadlock's victim.
    *
    * At READ COMMITTED ([[db.scalikejdbc.ReadCommitted]]): the vote's rates are summed by a
    * plain read, which then sees the latest committed rates whatever the transaction read
    * before (a later read added here, as P2a's round_image delta, can't make it a stale
    * snapshot); a locking read would lock past the vote's last rate into the next vote's and
    * deadlock with its upsert.
    *
    * @return whether the vote's round has that criterion: false for a vote gone, of a juror
    *         stopped on the round, or a criterion the round hasn't, which changes nothing
    */
  def updateRate(voteId: Long, criterionId: Long, rate: Int): Boolean =
    db.scalikejdbc.DbErrors.retryOnDeadlock(db.scalikejdbc.ReadCommitted.localTx { implicit session =>
      updateRateIn(voteId, criterionId, rate)
    })

  /** [[updateRate]] in the caller's transaction: at READ COMMITTED, or at REPEATABLE READ one
    * that has not read before (the sum's plain read must see the latest rates).
    */
  def updateRateIn(voteId: Long, criterionId: Long, rate: Int)(implicit session: DBSession): Boolean =
    // not the vote of a juror stopped on the round (P6), also when the stop races the vote
    sql"""SELECT s.round_id FROM vote s WHERE s.id = $voteId
            AND NOT EXISTS (SELECT 1 FROM round_user ru
                            WHERE ru.round_id = s.round_id AND ru.user_id = s.jury_id AND ru.active = FALSE)
          FOR UPDATE""".map(_.long(1)).single().exists { roundId =>
      val criteria =
        sql"SELECT criterion_id FROM round_criterion WHERE round_id = $roundId LOCK IN SHARE MODE"
          .map(_.long(1)).list()
      criteria.contains(criterionId) && {
        sql"""INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES ($voteId, $criterionId, $rate)
              ON DUPLICATE KEY UPDATE rate = VALUES(rate)""".update()
        // the vote's rates, written only while it is locked, as now: a plain read at READ
        // COMMITTED (updateRate). A locking read would lock past the vote's last rate into the
        // next vote's, and deadlock with its upsert (seen with neighbouring votes voted at once)
        val rates = sql"SELECT criterion_id, rate FROM criteria_rate WHERE vote_id = $voteId"
          .map(rs => rs.long(1) -> rs.int(2)).list().toMap
        val ofRound = criteria.map(id => rates.getOrElse(id, 0))
        val total = if (ofRound.forall(_ > 0)) ofRound.sum else 0
        sql"UPDATE vote SET rate = $total WHERE id = $voteId".update()
        true
      }
    }

  /** Files per [[averages]] query. */
  val AveragesChunk = 1000

  /** The per-criterion averages of the files `pageIds` in the round, in stored units (half
    * stars in a half-star round), by page id, then criterion id: all the jurors' votes, or
    * `juror`'s only (a juror's own list: never the others'). Read on demand from the votes,
    * no maintained count (C3; also with round_image as the list's source, P2b):
    *  - only votes rated in every criterion (`vote.rate > 0`: U5 keeps a partly rated vote's
    *    rate at 0), and of those only criteria rated (`rate > 0`: a partly rated vote from
    *    before U5 keeps its sum, and its pre-created rows of 0 are not rates);
    *  - only the round's criteria (`round_criterion`): a vote moved from another round
    *    ([[db.scalikejdbc.VoteJdbc.setRound]]) keeps that round's rates.
    * A file without such a vote, or a criterion none rated, has no entry. One query per
    * [[AveragesChunk]] files, from the votes by `vote_round_page_rate_idx`, then each one's
    * rates by `criteria_rate_vote_criterion_uidx`.
    */
  def averages(roundId: Long, pageIds: Seq[Long], juror: Option[Long] = None)(implicit
      session: DBSession = autoSession
  ): Map[Long, Map[Long, BigDecimal]] = {
    val ofJuror = juror.fold(sqls"")(id => sqls"AND v.jury_id = $id")
    pageIds.distinct.grouped(AveragesChunk).flatMap { chunk =>
      sql"""SELECT v.page_id, cr.criterion_id, AVG(cr.rate) FROM vote v
            STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
            STRAIGHT_JOIN round_criterion rc ON rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id
            WHERE v.round_id = $roundId AND v.page_id IN ($chunk) AND v.rate > 0 $ofJuror AND cr.rate > 0
            GROUP BY v.page_id, cr.criterion_id"""
        .map(rs => (rs.long(1), rs.long(2), BigDecimal(rs.bigDecimal(3))))
        .list()
    }.toSeq.groupMap(_._1)(r => r._2 -> r._3).view.mapValues(_.toMap).toMap
  }

  /** The rates of the round's criteria of each vote of one file in the round, by vote id,
    * then criterion id, the criteria rated only (`rate > 0`), also of a partly rated vote:
    * the large view's list of each juror's vote, which only organisers see. A criterion of
    * another round (a vote moved by [[db.scalikejdbc.VoteJdbc.setRound]]) is left out.
    */
  def ratesOfFile(roundId: Long, pageId: Long)(implicit
      session: DBSession = autoSession
  ): Map[Long, Map[Long, Int]] =
    sql"""SELECT v.id, cr.criterion_id, cr.rate FROM vote v
          STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
          STRAIGHT_JOIN round_criterion rc ON rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id
          WHERE v.round_id = $roundId AND v.page_id = $pageId AND cr.rate > 0"""
      .map(rs => (rs.long(1), rs.long(2), rs.int(3)))
      .list()
      .groupMap(_._1)(r => r._2 -> r._3).view.mapValues(_.toMap).toMap

  /** The vote's rates, by criterion; a criterion without a row is unrated (0). */
  def getRates(
      voteId: Long
  )(implicit session: DBSession = autoSession): Seq[CriteriaRate] = withSQL {
    select.from(CriteriaRate as c).where.eq(column.voteId, voteId)
  }.map(CriteriaRate(c)).list()
}
