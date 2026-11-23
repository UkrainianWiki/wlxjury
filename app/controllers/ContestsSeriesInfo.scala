package controllers

import db.scalikejdbc.{SeriesJdbc, User}
import org.intracer.wmua.ContestJury
import services.series.{ContestReuse, ContestSeries}

import java.time.Instant

/** The contests page's series columns (contest series plan §5.3): each listed contest's series,
  * the previous year's contest (derived among all contests), who created it and when (V109; none
  * before it), and whether the inline reuse check flags it by its sources and rounds ("reused";
  * not by its rounds' files, which the contest's series page reads). For the contests shown only:
  * a few queries.
  */
final case class ContestsSeriesInfo(
    seriesNames: Map[Long, String],
    previous: Map[Long, ContestJury],
    created: Map[Long, (Option[Long], Option[Instant])],
    creators: Map[Long, String],
    reused: Set[Long]
)

object ContestsSeriesInfo {

  val Empty: ContestsSeriesInfo = ContestsSeriesInfo(Map.empty, Map.empty, Map.empty, Map.empty, Set.empty)

  def of(shown: Seq[ContestJury], all: Seq[ContestJury], reuse: ContestReuse.Settings): ContestsSeriesInfo =
    if (shown.isEmpty) Empty
    else {
      val ids = shown.flatMap(_.id)
      val created = ContestSeries.createdInfo(ids)
      val creators = User.findByIds(created.values.flatMap(_._1).toSeq.distinct).map(u => u.getId -> u.fullname).toMap
      ContestsSeriesInfo(
        SeriesJdbc.findByIds(shown.flatMap(_.seriesId).distinct).map(s => s.id -> s.name).toMap,
        ContestSeries.previousIn(shown, all),
        created,
        creators,
        // the sources and rounds only, never the rounds' files: root's main page doesn't carry
        // their cost (a year's 50 rounds' files took 142 ms on the production copy); the contest's
        // series page and the proposals read them
        ContestReuse.checkAll(shown, reuse, files = false).keySet
      )
    }
}
