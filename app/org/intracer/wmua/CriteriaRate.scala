package org.intracer.wmua

import scalikejdbc._

case class CriteriaRate(id: Long, selection: Long, criteria: Long, rate: Int)

object CriteriaRate extends SQLSyntaxSupport[CriteriaRate] {

  val c = CriteriaRate.syntax("c")

  def apply(c: SyntaxProvider[CriteriaRate])(
      rs: WrappedResultSet
  ): CriteriaRate = apply(c.resultName)(rs)

  def apply(c: ResultName[CriteriaRate])(rs: WrappedResultSet): CriteriaRate =
    new CriteriaRate(
      id = rs.long(c.id),
      selection = rs.long(c.selection),
      criteria = rs.long(c.criteria),
      rate = rs.int(c.rate)
    )

  def updateRate(selection: Long, criteria: Long, rate: Int)(implicit
      session: DBSession = autoSession
  ): Unit = {
    withSQL {
      update(CriteriaRate)
        .set(column.rate -> rate)
        .where
        .eq(column.selection, selection)
        .and
        .eq(column.criteria, criteria)
    }.update()

    sql"""UPDATE selection s
          SET rate =
          IFNULL((SELECT sum(rate) FROM criteria_rate WHERE selection = $selection AND rate>0), 0)
         WHERE s.id = $selection"""
      .update()
  }

  /** The number of criteria of a criteria round: those rated in one of its selections, as a
    * distribution gives each of them a rate per criterion (DistributeImages.addCriteriaRates);
    * None if no selection of the round has criteria rates.
    */
  def countOf(roundId: Long)(implicit session: DBSession = autoSession): Option[Int] =
    sql"""SELECT COUNT(DISTINCT criteria) FROM criteria_rate
          WHERE selection = (SELECT s.id FROM selection s
            WHERE s.round_id = $roundId AND EXISTS (SELECT 1 FROM criteria_rate cr WHERE cr.selection = s.id)
            LIMIT 1)""".map(_.int(1)).single().filter(_ > 0)

  def getRates(
      selection: Long
  )(implicit session: DBSession = autoSession): Seq[CriteriaRate] = withSQL {
    select.from(CriteriaRate as c).where.eq(column.selection, selection)
  }.map(CriteriaRate(c)).list()

  def batchInsert(rates: Seq[CriteriaRate]): Unit = {
    val column = CriteriaRate.column
    DB localTx { implicit session =>
      val batchParams: Seq[Seq[Any]] =
        rates.map(i => Seq(i.selection, i.criteria, i.rate))
      withSQL {
        insert
          .into(CriteriaRate)
          .namedValues(
            column.selection -> sqls.?,
            column.criteria -> sqls.?,
            column.rate -> sqls.?
          )
      }.batch(batchParams: _*).apply()
    }
  }
}
