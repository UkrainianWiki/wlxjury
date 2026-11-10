package org.intracer.wmua.cmd

import db.scalikejdbc.{MediaType, Round}
import org.intracer.wmua.cmd.ImageWithRatingSeqFilter.ImageFilter
import org.intracer.wmua.{BinaryOrder, CategoryRule, Image, ImageWithRating, RejectedMode}
import org.scalawiki.MwBot
import org.scalawiki.wlx.{ImageDB, MonumentDB}
import org.scalawiki.wlx.dto.{Contest, SpecialNomination}
import org.scalawiki.wlx.query.MonumentQuery
import org.scalawiki.wlx.stat.ContestStat
import play.api.Logging
import services.imports.AnonymousBots

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt
import scala.runtime.ScalaRunTime

trait ImageFilterGen extends (() => ImageFilter) with Product with Logging {

  override def toString: String = ScalaRunTime._toString(this)

  def imageFilter(p: Image => Boolean): ImageFilter =
    (images: Seq[ImageWithRating]) => {
      val result = images.filter(i => p(i.image))
      logger.debug(
        s"imageFilter: ${toString()}\n images before: ${images.size}, images after: ${result.size}"
      )
      result
    }

  def imageRatingFilter(p: ImageWithRating => Boolean): ImageFilter =
    (images: Seq[ImageWithRating]) => {
      val result = images.filter(p)
      logger.debug(
        s"imageRatingFilter: ${toString()}\n images before: ${images.size}, images after: ${result.size}"
      )
      result
    }

}

case class IncludeRegionIds(regionIds: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageFilter(_.region.exists(regionIds.contains))
}

case class IncludeMonumentIds(monumentIds: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageFilter(_.monumentId.exists(monumentIds.contains))
}

case class ExcludeRegionIds(regionIds: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageFilter(!_.region.exists(regionIds.contains))
}

case class IncludePageIds(pageIds: Set[Long]) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(i => pageIds.contains(i.pageId))
}

case class CategoryImages(rule: CategoryRule, firstIds: Set[Long], secondIds: Set[Long]) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(image => rule.accepts(image.pageId, firstIds, secondIds))
}

case class ExcludePageIds(pageIds: Set[Long]) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(i => !pageIds.contains(i.pageId))
}

/** Leaves out the files whose stored author is one of `names` (normalized accounts, as the
  * import stores authors, [[db.scalikejdbc.CoiMembers.matchOf]]): a round's conflicts of
  * interest.
  */
case class ExcludeAuthors(names: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageFilter(i => !i.author.exists(names.contains))
}

case class IncludeTitles(titles: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(i => titles.contains(i.title))
}

case class ExcludeTitles(titles: Set[String]) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(i => titles.contains(i.title))
}

case class IncludeJurorId(jurors: Set[Long]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageRatingFilter(i => i.votes.map(_.juryId).toSet.intersect(jurors).nonEmpty)
}

case class ExcludeJurorId(jurors: Set[Long]) extends ImageFilterGen {
  def apply(): ImageFilter =
    imageRatingFilter(i => i.votes.map(_.juryId).toSet.intersect(jurors).isEmpty)
}

/** The `topN` best rated images of the previous rounds. The rows are one per file and previous
  * round (a distribution's candidates), each evaluated in its own round of `rounds`
  * ([[ImageWithRating.sourceRound]], else `round`): a file ranks by its best row (triage
  * R196-1). A yes/no round's rows by `order` of their approvals and rejections, ties by page id;
  * with no order (a round from before V72, or a rated round) by the score in the row's round (by
  * its rule and jurors), ties by its rate sum, below 0 as 0, then by the rate sum, then by page
  * id; as [[services.distribution.DistributionSource.rank]] ranks them.
  */
case class SelectTopByRating(topN: Int, round: Round, order: Option[BinaryOrder] = Some(BinaryOrder.Default),
    rounds: Seq[Round] = Nil)
    extends ImageFilterGen {
  def apply(): ImageFilter =
    (images: Seq[ImageWithRating]) => {
      import scala.math.Ordering.Double.TotalOrdering
      import scala.math.Ordering.Implicits.seqOrdering
      def roundOf(i: ImageWithRating): Round = i.sourceRound(round, rounds)
      val ranked = order.filter(_ => round.isBinary) match {
        case Some(o) =>
          images.sortBy(i => (o.sortKey(i.approvals, i.rejections), i.pageId, roundOf(i).id.getOrElse(0L)))
        case None =>
          images.sortBy { i =>
            val r = roundOf(i)
            (-i.totalRate(r), -i.rateSum, -i.votes.map(_.rate).sum, i.pageId, r.id.getOrElse(0L))
          }
      }
      // a file's best row first: one row per file
      ranked.distinctBy(_.pageId).take(topN)
    }
}

/** The rows whose score is at least `rate`, each scored in its own previous round of `rounds`
  * ([[ImageWithRating.sourceRound]]), else in `round`.
  */
case class SelectMinAvgRating(rate: BigDecimal, round: Round, rounds: Seq[Round] = Nil) extends ImageFilterGen {
  def apply(): ImageFilter = imageRatingFilter(i => i.totalRate(i.sourceRound(round, rounds)) >= rate)
}

/** "Selected by at least `by` jurors": the rows (of one previous round each) with that many
  * approvals, read as their rate sum (only the approvals are read).
  */
case class SelectedAtLeast(by: Int) extends ImageFilterGen {
  def apply(): ImageFilter = imageRatingFilter(i => i.rateSum >= by)
}

/** "Exclude files rejected by at least `by` jurors": the rows (of one previous round each) with
  * fewer rejections ([[ImageWithRating.rejections]]: "no" votes, or ✗).
  */
case class RejectedByLessThan(by: Int) extends ImageFilterGen {
  def apply(): ImageFilter = imageRatingFilter(i => i.rejections < by)
}

/** "Only files rejected by at least `by` jurors" (V93, `RejectedMode.Only`): the rows (of one
  * previous round each) with that many rejections or more, to review them in a round of their own.
  */
case class RejectedByAtLeast(by: Int) extends ImageFilterGen {
  def apply(): ImageFilter = imageRatingFilter(i => i.rejections >= by)
}

case class MegaPixelsAtLeast(mpx: Int) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(_.mpx >= mpx)
}

case class SizeAtLeast(size: Int) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter(_.size.exists(_ >= size))
}

case class MediaTypeIs(mediaType: String) extends ImageFilterGen {
  def apply(): ImageFilter = imageFilter { image =>
    mediaType match {
      case MediaType.Video => image.isVideo
      case MediaType.Image => image.isImage
      case _               => true
    }
  }
}

case class SpecialNominationFilter(specialNominationName: String) extends ImageFilterGen {
  val specialNominationIds: Set[String] = SpecialNomination.nominations
    .find(_.name == specialNominationName)
    .map { nomination =>
      val contest = Contest.WLMUkraine(2020)
      // anonymous bots (services.imports.AnonymousBots): an explicit one for the query here, and
      // the seeded cache for the one getMonumentsMap's own MonumentQuery.create takes by default
      AnonymousBots.seed()
      val ukWiki = AnonymousBots.bot(MwBot.ukWiki)
      val stat = if (nomination.cities.nonEmpty) {
        ContestStat(contest, 2012, currentYearImageDb = new ImageDB(contest, Nil), totalImageDb = new ImageDB(contest, Nil))
          .copy(monumentDb =
            Some(Await.result(MonumentDB.getMonumentDb(contest, MonumentQuery.create(contest)(ukWiki)), 30.minutes)))
      } else {
        ContestStat(contest, 2012, currentYearImageDb = new ImageDB(contest, Nil), totalImageDb = new ImageDB(contest, Nil))
      }
      val map = Await.result(SpecialNomination.getMonumentsMap(Seq(nomination), stat), 30.minutes)
      map.values.flatten.map(_.id).toSet
    }
    .getOrElse(Set.empty)

  def apply(): ImageFilter =
    imageFilter(_.monumentId.exists(specialNominationIds.contains))
}

object ImageWithRatingSeqFilter {
  type ImageFilter = Seq[ImageWithRating] => Seq[ImageWithRating]
  def funGenerators(
      round: Option[Round] = None,
      includeRegionIds: Set[String] = Set.empty,
      excludeRegionIds: Set[String] = Set.empty,
      includeMonumentIds: Set[String] = Set.empty,
      includePageIds: Set[Long] = Set.empty,
      excludePageIds: Set[Long] = Set.empty,
      includeTitles: Set[String] = Set.empty,
      excludeTitles: Set[String] = Set.empty,
      includeJurorId: Set[Long] = Set.empty,
      excludeJurorId: Set[Long] = Set.empty,
      selectMinAvgRating: Option[BigDecimal] = None,
      selectTopByRating: Option[Int] = None,
      topOrder: Option[BinaryOrder] = Some(BinaryOrder.Default),
      selectedAtLeast: Option[Int] = None,
      rejectedBy: Option[Int] = None,
      mpxAtLeast: Option[Int] = None,
      sizeAtLeast: Option[Int] = None,
      specialNomination: Option[String] = None,
      mediaType: Option[String] = None,
      // "rejected by at least N": leave those files out (the default), or keep only them (V93)
      rejectedMode: RejectedMode = RejectedMode.Default,
      // all the previous rounds (`round` is the first): a file of one of them is scored in it
      prevRounds: Seq[Round] = Nil
  ): Seq[ImageFilterGen] = {

    val setMap = Map(
      IncludeRegionIds(includeRegionIds) -> includeRegionIds,
      ExcludeRegionIds(excludeRegionIds) -> excludeRegionIds,
      IncludeMonumentIds(includeMonumentIds) -> includeMonumentIds,
      IncludePageIds(includePageIds) -> includePageIds,
      ExcludePageIds(excludePageIds) -> excludePageIds,
      IncludeTitles(includeTitles) -> includeTitles,
      ExcludeTitles(excludeTitles) -> excludeTitles,
      IncludeJurorId(includeJurorId) -> includeJurorId,
      ExcludeJurorId(excludeJurorId) -> excludeJurorId
    )

    val optionMap = Map(
      selectMinAvgRating
        .map(top => SelectMinAvgRating(top, round.get, prevRounds)) -> selectMinAvgRating,
      selectTopByRating
        .map(top => SelectTopByRating(top, round.get, topOrder, prevRounds)) -> selectTopByRating,
      selectedAtLeast.map(n => SelectedAtLeast(n)) -> selectedAtLeast,
      rejectedBy.map(n => if (rejectedMode == RejectedMode.Only) RejectedByAtLeast(n) else RejectedByLessThan(n)) -> rejectedBy,
      mpxAtLeast.map(MegaPixelsAtLeast) -> mpxAtLeast,
      sizeAtLeast.map(SizeAtLeast) -> sizeAtLeast,
      specialNomination.map(SpecialNominationFilter) -> specialNomination,
      mediaType.map(MediaTypeIs) -> mediaType
    )

    // the top-N last: it ranks the files the other filters leave, as the streamed
    // distribution does (DistributionSource.rank), whatever the maps' order
    (setMap
      .filter(_._2.nonEmpty)
      .keys ++ optionMap.filter(_._2.nonEmpty).keys.flatten).toSeq
      .sortBy(_.isInstanceOf[SelectTopByRating])
  }

  def makeFunChain(
      gens: Seq[ImageFilterGen]): Seq[ImageWithRating] => Seq[ImageWithRating] =
    Function.chain(gens.map(_.apply()))
}
