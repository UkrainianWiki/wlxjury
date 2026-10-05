package org.intracer.wmua.cmd

import controllers.Global.commons
import db.ImageRepo
import db.scalikejdbc._
import org.intracer.wmua._
import org.intracer.wmua.cmd.DistributeImages.Rebalance
import org.scalawiki.dto.Namespace
import play.api.Logging
import scalikejdbc.DB
import spray.util.pimpFuture

import javax.inject.Inject
import scala.concurrent.duration._

class DistributeImages @Inject()(imageRepo: ImageRepo) extends Logging {

  /** Builds and persists the selection rows for `images` in `round`.
    *
    * The insert (plus criteria rates, if any) runs in a single transaction so a
    * failure part-way through — a deadlock, a lock-wait timeout, an oversized batch,
    * a unique-index violation — rolls back cleanly instead of leaving the round with
    * only some of its images. The caller is expected to verify the resulting image
    * count (see [[services.RoundService.createNewRound]]).
    *
    * @return the number of selection rows written
    */
  def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Int = {
    require(
      jurors.nonEmpty,
      s"Round ${round.id.orNull}: cannot distribute ${images.size} images to an empty jury"
    )

    val selection: Seq[Selection] = newSelection(round, images, jurors)

    // Duplicate (page_id, jury_id, round_id) tuples would be rejected mid-batch by the
    // unique index and leave the round half-populated; fail early with a clear reason
    // (most often a juror listed twice in the round).
    val distinctAssignments =
      selection.iterator.map(s => (s.pageId, s.juryId, s.roundId)).toSet.size
    require(
      distinctAssignments == selection.size,
      s"Round ${round.id.orNull}: ${selection.size - distinctAssignments} duplicate " +
        s"juror/image assignments among ${jurors.size} jurors; check for duplicate jurors"
    )

    logger.debug("saving selection: " + selection.size)
    DB.localTx { implicit session =>
      SelectionJdbc.batchInsert(selection)
      if (round.hasCriteria) {
        addCriteriaRates(selection)
      }
    }
    round.id.foreach(RoundImageCounts.invalidate(_)) // after the commit
    logger.debug("saved selection")
    selection.size
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
  ): Int = {
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
