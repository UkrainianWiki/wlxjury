package org.intracer.wmua

import scalikejdbc._

case class CriteriaRate(id: Long, voteId: Long, criteria: Long, rate: Int)

object CriteriaRate extends SQLSyntaxSupport[CriteriaRate] {

  val c = CriteriaRate.syntax("c")

  def apply(c: SyntaxProvider[CriteriaRate])(
      rs: WrappedResultSet
  ): CriteriaRate = apply(c.resultName)(rs)

  def apply(c: ResultName[CriteriaRate])(rs: WrappedResultSet): CriteriaRate =
    new CriteriaRate(
      id = rs.long(c.id),
      voteId = rs.long(c.voteId),
      criteria = rs.long(c.criteria),
      rate = rs.int(c.rate)
    )

  /** Rates one criterion of a vote and sets the vote's rate to the sum of its criteria's
    * positive rates, in one transaction: the vote row is locked first (`FOR UPDATE`), so
    * votes on two criteria of one vote follow each other and the second sums both. Lock
    * order: vote, then criteria_rate (the rates of a vote are written only while it is
    * locked, also when votes are deleted: [[db.scalikejdbc.VoteJdbc.deleteVotes]]).
    * Retried once if the database picked it as a deadlock's victim.
    *
    * @return whether the vote has that criterion: false for a vote gone or a criterion it
    *         hasn't, which changes nothing
    */
  def updateRate(voteId: Long, criteria: Long, rate: Int): Boolean =
    db.scalikejdbc.DbErrors.retryOnDeadlock(DB.localTx { implicit session => updateRateIn(voteId, criteria, rate) })

  /** [[updateRate]] in the caller's transaction. */
  def updateRateIn(voteId: Long, criteria: Long, rate: Int)(implicit session: DBSession): Boolean =
    // not the vote of a juror stopped on the round (P6), also when the stop races the vote
    sql"""SELECT s.id FROM vote s WHERE s.id = $voteId
            AND NOT EXISTS (SELECT 1 FROM round_user ru
                            WHERE ru.round_id = s.round_id AND ru.user_id = s.jury_id AND ru.active = FALSE)
          FOR UPDATE""".map(_.long(1)).single().isDefined && {
      // the driver counts the rows matched, not changed (useAffectedRows off): a vote of the
      // stored rate counts too
      val matched = sql"""UPDATE criteria_rate SET rate = $rate
                          WHERE vote_id = $voteId AND criteria = $criteria""".update()
      matched > 0 && {
        val sum = sql"""SELECT COALESCE(SUM(IF(rate > 0, rate, 0)), 0) FROM criteria_rate
                        WHERE vote_id = $voteId FOR UPDATE""".map(_.int(1)).single().getOrElse(0)
        sql"UPDATE vote SET rate = $sum WHERE id = $voteId".update()
        true
      }
    }

  /** The number of criteria of a criteria round: those rated in one of its votes, as a
    * distribution gives each of them a rate per criterion (DistributeImages.addCriteriaRates);
    * None if no vote of the round has criteria rates.
    */
  def countOf(roundId: Long)(implicit session: DBSession = autoSession): Option[Int] =
    sql"""SELECT COUNT(DISTINCT criteria) FROM criteria_rate
          WHERE vote_id = (SELECT s.id FROM vote s
            WHERE s.round_id = $roundId AND EXISTS (SELECT 1 FROM criteria_rate cr WHERE cr.vote_id = s.id)
            LIMIT 1)""".map(_.int(1)).single().filter(_ > 0)

  def getRates(
      voteId: Long
  )(implicit session: DBSession = autoSession): Seq[CriteriaRate] = withSQL {
    select.from(CriteriaRate as c).where.eq(column.voteId, voteId)
  }.map(CriteriaRate(c)).list()
}
