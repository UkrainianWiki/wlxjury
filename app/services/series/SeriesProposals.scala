package services.series

import db.scalikejdbc.Series
import org.intracer.wmua.ContestJury
import services.series.SeriesIdentity.{Place, SeriesType, Unusual}

/** The assignment proposals (contest series plan §3.5, `/admin/series/assign`): the live
  * contests in no series, grouped across years by type ([[SeriesIdentity.typeOf]]) and country
  * ([[SeriesIdentity.placeOf]], "International" taken literally), each group with a suggested
  * name and, if a live series of that type and country exists, that series offered first. Root
  * confirms them in bulk ([[ContestSeries.confirm]]).
  *
  * Never proposed (root assigns them one by one, on the contest's series page), each with why:
  * a name of no type (`unparsed`), an unusual country (`country`), two live contests of one type,
  * country and year (`duplicate`), a year the matching series already has (`taken`), and a
  * contest the inline reuse check or the latest reuse scan flags (`reuse`, [[ContestReuse]]).
  */
object SeriesProposals {

  /** A group: its key (`wlm:np`), type and place, suggested name, contests by year (the newest
    * first), and the existing series offered first, if any.
    */
  final case class Proposal(key: String, contestType: SeriesType, place: Place, name: String,
      contests: Seq[ContestJury], existing: Option[Series], campaign: Option[String]) {
    def countryName: String = SeriesIdentity.countryName(place)
  }

  /** A contest left out, with the reason's message key (`series.assign.left.<reason>`), and for
    * `reuse` the reuse check's signs.
    */
  final case class LeftOut(contest: ContestJury, reason: String, signs: Seq[ContestReuse.Sign] = Nil)

  final case class Result(proposals: Seq[Proposal], left: Seq[LeftOut])

  /** A contest's proposal key (`wlm:np`), if it has a type and a usual country. */
  def keyOf(c: ContestJury): Option[String] =
    SeriesIdentity.typeOf(c).map(_.code).filterNot(_ => SeriesIdentity.placeOf(c).isInstanceOf[Unusual])
      .map(code => s"$code:${SeriesIdentity.placeOf(c).key}")

  /** The reasons a contest is left out ([[LeftOut.reason]]). */
  val Reasons: Seq[String] = Seq("unparsed", "country", "duplicate", "taken", "reuse")

  /** The proposals from every contest, the series, and the reuse check's signs by contest
    * (contests with none left out of the map).
    */
  def of(contests: Seq[ContestJury], series: Seq[Series], flagged: Map[Long, Seq[ContestReuse.Sign]]): Result = {
    val candidates = contests.filter(c => !c.retired && c.seriesId.isEmpty)
    val typed = candidates.map(c => (c, SeriesIdentity.typeOf(c), SeriesIdentity.placeOf(c)))
    val (unparsed, rest1) = typed.partition(_._2.isEmpty)
    val (unusual, rest2) = rest1.partition(_._3.isInstanceOf[Unusual])
    val (reused, rest3) = rest2.partition(t => flagged.get(t._1.getId).exists(_.nonEmpty))
    val groups = rest3.groupBy(t => (t._2.get.code, t._3.key)).toSeq
    val liveSeries = series.filter(_.live)

    val built = groups.map { case ((_, _), members) =>
      val contestType = members.head._2.get
      val place = members.head._3
      val existing = liveSeries.filter(SeriesIdentity.seriesOf(_, contestType, place)).sortBy(_.id).headOption
      // years held by the existing series' live contests
      val taken = existing.toSeq.flatMap(s => contests.filter(c => c.seriesId.contains(s.id) && !c.retired).map(_.year)).toSet
      val byYear = members.map(_._1).groupBy(_.year)
      val duplicate = byYear.values.filter(_.size > 1).flatten.toSeq
      val takenOut = byYear.values.filter(_.size == 1).flatten.filter(c => taken(c.year)).toSeq
      val kept = members.map(_._1).filterNot(c => duplicate.contains(c) || takenOut.contains(c)).sortBy(c => (-c.year, c.getId))
      val campaigns = kept.map(_.campaign.map(_.trim).filter(_.nonEmpty)).distinct
      val proposal = Option.when(kept.nonEmpty)(Proposal(s"${contestType.code}:${place.key}", contestType, place,
        existing.fold(SeriesIdentity.suggestedName(contestType, place))(_.name), kept, existing,
        campaigns match { case Seq(Some(c)) => Some(c); case _ => None }))
      (proposal, duplicate.map(LeftOut(_, "duplicate")) ++ takenOut.map(LeftOut(_, "taken")))
    }

    val left = unparsed.map(t => LeftOut(t._1, "unparsed")) ++ unusual.map(t => LeftOut(t._1, "country")) ++
      reused.map(t => LeftOut(t._1, "reuse", flagged.getOrElse(t._1.getId, Nil))) ++ built.flatMap(_._2)
    Result(built.flatMap(_._1).sortBy(p => (p.contestType.name, p.countryName)),
      left.sortBy(l => (-l.contest.year, l.contest.getId)))
  }
}
