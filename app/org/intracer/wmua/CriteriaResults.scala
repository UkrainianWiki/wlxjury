package org.intracer.wmua

import db.scalikejdbc.{Round, RoundCriterion}
import scalikejdbc.{AutoSession, DBSession}

/** A criteria round's per-criterion results of some of its files (C3): the round's criteria
  * in order, and each file's average of each one ([[CriteriaRate.averages]], in stored units:
  * half stars in a half-star round). A round without criteria has none ([[CriteriaResults.empty]]).
  */
final case class CriteriaResults(criteria: Seq[Criterion], averages: Map[Long, Map[Long, BigDecimal]]) {

  def nonEmpty: Boolean = criteria.nonEmpty

  /** The file's average of each criterion, in the round's order; none where no juror rated it
    * (a vote counts once every criterion is rated, U5).
    */
  def of(pageId: Long): Seq[(Criterion, Option[BigDecimal])] = {
    val file = averages.getOrElse(pageId, Map.empty)
    criteria.map(c => c -> file.get(c.id))
  }
}

object CriteriaResults {

  val empty: CriteriaResults = CriteriaResults(Nil, Map.empty)

  /** The round's criteria and the averages of the files `pageIds`: all the jurors' votes
    * (`asUserId` 0) or one juror's (a list of that juror's files: the caller has checked that
    * the viewer may see them, [[services.GalleryService.viewAs]]). Two queries, none for a
    * round without criteria.
    */
  def of(round: Round, pageIds: Seq[Long], asUserId: Long)(implicit session: DBSession = AutoSession): CriteriaResults =
    if (!round.hasCriteria) empty
    else {
      val criteria = RoundCriterion.ofRound(round.getId)
      if (criteria.isEmpty || pageIds.isEmpty) CriteriaResults(criteria, Map.empty)
      else CriteriaResults(criteria, CriteriaRate.averages(round.getId, pageIds, Option.when(asUserId != 0)(asUserId)))
    }

  /** An average in stored units as the tables and the CSV show rates: two decimals, a point. */
  def format(average: BigDecimal): String =
    average.setScale(2, BigDecimal.RoundingMode.HALF_UP).bigDecimal.toPlainString
}

/** Each juror's rates of a criteria round's criteria on one file, by vote id, the criteria
  * rated only ([[CriteriaRate.ratesOfFile]]): the large view's list of the file's votes, which
  * only organisers see.
  */
final case class JurorCriteria(criteria: Seq[Criterion], rates: Map[Long, Map[Long, Int]]) {

  def nonEmpty: Boolean = criteria.nonEmpty

  /** The vote's rate of each criterion, in the round's order; none for one not rated yet. */
  def of(voteId: Long): Seq[(Criterion, Option[Int])] = {
    val vote = rates.getOrElse(voteId, Map.empty)
    criteria.map(c => c -> vote.get(c.id))
  }
}

object JurorCriteria {
  val empty: JurorCriteria = JurorCriteria(Nil, Map.empty)
}
