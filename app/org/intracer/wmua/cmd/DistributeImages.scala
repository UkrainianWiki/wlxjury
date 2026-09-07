package org.intracer.wmua.cmd

import controllers.Global.commons
import db.ImageRepo
import db.scalikejdbc._
import org.intracer.wmua._
import org.intracer.wmua.cmd.DistributeImages.Rebalance
import org.scalawiki.dto.Namespace
import play.api.Logging
import spray.util.pimpFuture

import javax.inject.Inject
import scala.concurrent.duration._

class DistributeImages @Inject()(imageRepo: ImageRepo) extends Logging {

  def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Unit = {
    val selection: Seq[Selection] = newSelection(round, images, jurors)

    logger.debug("saving selection: " + selection.size)
    SelectionJdbc.batchInsert(selection)
    logger.debug(s"saved selection")

    if (round.hasCriteria) {
      addCriteriaRates(selection)
    }
  }

  def newSelection(round: Round, images: Seq[Image], jurors: Seq[User]): Seq[Selection] = {
    val sortedJurors = jurors.sorted
    val selection: Seq[Selection] = round.distribution match {
      case 0 =>
        sortedJurors.flatMap { juror =>
          images.map(img => Selection(img, juror, round))
        }
      case x if x > 0 =>
        images.zipWithIndex.flatMap { case (img, i) =>
          (0 until x).map(j => Selection(img, sortedJurors((i + j) % sortedJurors.size), round))
        }
    }
    selection
  }

  def addCriteriaRates(selection: Seq[Selection]): Unit = {
    val criteriaIds = Seq(6, 7, 8, 9) // TODO load form DB
    val rates = selection.flatMap { s =>
      criteriaIds.map(id => new CriteriaRate(0, s.getId, id, 0))
    }

    CriteriaRate.batchInsert(rates)
  }

  def distributeImages(
      round: Round,
      jurors: Seq[User],
      prevRounds: Seq[Round],
      removeUnrated: Boolean = false
  ): Unit = {
    if (removeUnrated) {
      SelectionJdbc.removeUnrated(round.getId)
    }

    val images = imagesByRound(round, prevRounds)

    distributeImages(round, images, jurors)
  }

  def imagesByRound(round: Round, prevRounds: Seq[Round] = Nil): Seq[Image] = {
    getFilteredImages(
      round,
      prevRounds,
      selectedAtLeast = round.prevSelectedBy,
      selectMinAvgRating = round.prevMinAvgRate,
      selectTopByRating = round.topImages,
      includeCategory = round.category,
      excludeCategory = round.excludeCategory,
      includeRegionIds = round.regionIds.toSet,
      includeMonumentIds = round.monumentIds.toSet
    )
  }

  def categoryFileIds(maybeCategory: Option[String]): Iterable[Long] = {
    maybeCategory
      .filter(_.trim.nonEmpty)
      .map { category =>
        commons
          .page(category)
          .imageInfoByGenerator("categorymembers", "cm", Set(Namespace.FILE))
          .await(5.minutes)
          .flatMap(_.id)
      }
      .getOrElse(Nil)
  }

  private def getFilteredImages(
      round: Round,
      prevRounds: Seq[Round],
      includeRegionIds: Set[String] = Set.empty,
      excludeRegionIds: Set[String] = Set.empty,
      includeMonumentIds: Set[String] = Set.empty,
      includePageIds: Set[Long] = Set.empty,
      excludePageIds: Set[Long] = Set.empty,
      includeTitles: Set[String] = Set.empty,
      excludeTitles: Set[String] = Set.empty,
      selectMinAvgRating: Option[BigDecimal] = None,
      selectTopByRating: Option[Int] = None,
      selectedAtLeast: Option[Int] = None,
      includeJurorId: Set[Long] = Set.empty,
      excludeJurorId: Set[Long] = Set.empty,
      includeCategory: Option[String] = None,
      excludeCategory: Option[String] = None
  ): Seq[Image] = {

    require(
      Round.sameRateType(prevRounds),
      s"Round ${round.id.orNull}: previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] " +
        "must all be of the same rate type (all binary or all rated)"
    )

    val includeFromCats = categoryFileIds(includeCategory)
    val excludeFromCats = categoryFileIds(excludeCategory)

    val currentImages = imageRepo
      .byRoundMerged(round.getId)
      .filter(iwr => iwr.selection.nonEmpty)
      .toSet
    val existingImageIds = currentImages.map(_.pageId)
    val existingJurorIds = currentImages.flatMap(_.jurors)
    val mpxAtLeast = round.minMpx
    val sizeAtLeast = round.minImageSize.map(_ * 1024 * 1024)

    // All selected previous rounds share the same rate type and the same filtering
    // conditions, so any one of them is a valid context for rate scaling / gating.
    val prevRound = prevRounds.headOption

    val imagesAll: Seq[ImageWithRating] =
      if (prevRounds.isEmpty)
        imageRepo
          .findByContestId(round.contestId)
          .map(i => new ImageWithRating(i, Seq.empty))
      else
        mergeByPageId(
          prevRounds.flatMap(r =>
            imageRepo.byRoundMerged(r.getId, rated = selectedAtLeast.map(_ > 0))
          )
        )
    logger.debug("Total images: " + imagesAll.size)

    val funGens = ImageWithRatingSeqFilter.funGenerators(
      prevRound,
      includeRegionIds = includeRegionIds,
      excludeRegionIds = excludeRegionIds,
      includeMonumentIds = includeMonumentIds,
      includePageIds = includePageIds ++ includeFromCats.toSet,
      excludePageIds = excludePageIds ++ existingImageIds ++ excludeFromCats.toSet,
      includeTitles = includeTitles,
      excludeTitles = excludeTitles,
      includeJurorId = includeJurorId,
      excludeJurorId = excludeJurorId /*++ existingJurorIds*/,
      selectMinAvgRating =
        prevRound.flatMap(_ => selectMinAvgRating.filter(_ => !prevRound.exists(_.isBinary))),
      selectTopByRating = prevRound.flatMap(_ => selectTopByRating),
      selectedAtLeast = prevRound.flatMap(_ => selectedAtLeast),
      mpxAtLeast = mpxAtLeast,
      sizeAtLeast = sizeAtLeast,
      specialNomination = round.specialNomination,
      mediaType = round.mediaType
    )

    val filterChain = ImageWithRatingSeqFilter.makeFunChain(funGens)

    val images = filterChain(imagesAll).map(_.image)
    logger.debug("Images after filtering: " + images.size)

    images
  }

  /** Collapses the per-previous-round [[ImageWithRating]] rows for the same image into a
    * single row, unioning their selections and juror counts. An image that advanced in
    * several previous rounds is then one row rather than one-per-round, so the rating
    * filters (top-N, min average, selected-at-least) see a single combined rating
    * instead of duplicates competing for the same slots. First-seen order is kept.
    */
  private def mergeByPageId(images: Seq[ImageWithRating]): Seq[ImageWithRating] = {
    val merged = scala.collection.mutable.LinkedHashMap.empty[Long, ImageWithRating]
    images.foreach { iwr =>
      merged.updateWith(iwr.pageId) {
        case Some(acc) =>
          Some(
            acc.copy(
              selection = acc.selection ++ iwr.selection,
              countFromDb = acc.countFromDb + iwr.countFromDb
            )
          )
        case None => Some(iwr)
      }
    }
    merged.values.toSeq
  }

  def rebalanceImages(
      round: Round,
      jurors: Seq[User],
      images: Seq[Image],
      currentSelection: Seq[Selection]
  ): Rebalance = {

    if (currentSelection == Nil) {
      Rebalance(newSelection(round, images, jurors), Nil)
    } else {
      Rebalance(Nil, Nil)
    }
  }

}

object DistributeImages {

  case class Rebalance(newSelections: Seq[Selection], removedSelections: Seq[Selection])

  val NoRebalance = Rebalance(Nil, Nil)

}
