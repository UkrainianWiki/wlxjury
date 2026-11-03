package org.intracer.wmua.cmd

import controllers.Global.commons
import db.ImageRepo
import db.scalikejdbc._
import org.intracer.wmua._
import org.intracer.wmua.cmd.DistributeImages.Rebalance
import org.scalawiki.dto.Namespace
import play.api.Logging
import scalikejdbc._
import spray.util.pimpFuture

import javax.inject.Inject
import scala.concurrent.duration._

class DistributeImages @Inject()(imageRepo: ImageRepo) extends Logging {

  /** Builds and persists the vote rows for `images` in `round`, in one transaction
    * (all chunks or none): the old in-memory path, kept for scripts, tests and as the
    * reference the streamed background distribution
    * ([[services.distribution.RoundDistributionHandler]]) is checked against.
    *
    * @return the number of vote rows written
    */
  def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Int = {
    DistributeImages.checkJury(round, jurors)
    val selection: Seq[Vote] = newSelection(round, images, jurors)
    logger.debug("saving selection: " + selection.size)
    DB.localTx { implicit session =>
      // in batches: see DistributeImages.insertRows
      DistributeImages.insertRows(selection)
    }
    round.id.foreach(RoundImageCounts.invalidate(_)) // after the commit
    logger.debug("saved selection")
    selection.size
  }

  def newSelection(round: Round, images: Seq[Image], jurors: Seq[User]): Seq[Vote] =
    DistributeImages.assign(round, images, jurors.sorted.toIndexedSeq, startPosition = 0L)

  def distributeImages(
      round: Round,
      jurors: Seq[User],
      prevRounds: Seq[Round],
      removeUnrated: Boolean = false
  ): Int = {
    if (removeUnrated) {
      VoteJdbc.removeUnrated(round.getId)
    }

    val images = imagesByRound(round, prevRounds)

    distributeImages(round, images, jurors)
  }

  def imagesByRound(round: Round, prevRounds: Seq[Round] = Nil): Seq[Image] = {
    getFilteredImages(
      round,
      prevRounds,
      selectedAtLeast = round.selectedByFilter,
      rejectedBy = round.rejectedByFilter,
      selectMinAvgRating = round.prevMinAvgRate,
      selectTopByRating = round.topImages,
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

  /** The round's per-image filters: every filter of [[imagesByRound]] except top-N by
    * rating (a global ranking), the exclusion of the images already in the round and
    * "exclude files rejected by at least N" (the streamed queries apply it,
    * `DistributionSource.PrevVotes`). Each
    * decides per image, so applying them to any slice of the candidates (a chunk) selects
    * exactly the images the whole list would. The category id sets are the effective
    * first and second operands of [[Round.categoryRule]] ([[categoryFileIds]]);
    * `excludeAuthors` the conflict-of-interest accounts of a round that leaves them out
    * ([[db.scalikejdbc.CoiMembers]]).
    */
  def perImageFilters(
      round: Round,
      prevRounds: Seq[Round],
      firstCategoryIds: Iterable[Long],
      secondCategoryIds: Iterable[Long],
      excludeAuthors: Set[String] = Set.empty
  ): Seq[ImageWithRating] => Seq[ImageWithRating] = {
    val prevRound = prevRounds.headOption
    val categoryRule = round.categoryRule
    categoryRule.requireValid()
    ImageWithRatingSeqFilter.makeFunChain(
      Seq(CategoryImages(categoryRule, firstCategoryIds.toSet, secondCategoryIds.toSet)) ++
      Option.when(excludeAuthors.nonEmpty)(ExcludeAuthors(excludeAuthors)).toSeq ++
      ImageWithRatingSeqFilter.funGenerators(
        prevRound,
        includeRegionIds = round.regionIds.toSet,
        includeMonumentIds = round.monumentIds.toSet,
        selectMinAvgRating =
          prevRound.flatMap(_ => round.prevMinAvgRate.filter(_ => !prevRound.exists(_.isBinary))),
        selectTopByRating = None,
        selectedAtLeast = prevRound.flatMap(_ => round.selectedByFilter),
        mpxAtLeast = round.minMpx,
        sizeAtLeast = round.minImageSize.map(_ * 1024 * 1024),
        specialNomination = round.specialNomination,
        mediaType = round.mediaType
      )
    )
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
      rejectedBy: Option[Int] = None,
      includeJurorId: Set[Long] = Set.empty,
      excludeJurorId: Set[Long] = Set.empty
  ): Seq[Image] = {

    require(
      Round.sameRateType(prevRounds),
      s"Round ${round.id.orNull}: previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] " +
        "must all be of the same rate type (all binary or all rated)"
    )

    val categoryRule = round.categoryRule
    categoryRule.requireValid()
    val firstCategoryIds = categoryFileIds(categoryRule.first).toSet
    val secondCategoryIds = categoryFileIds(categoryRule.second).toSet

    val currentImages = imageRepo
      .byRoundMerged(round.getId, tally = round.isBinary)
      .filter(iwr => iwr.votes.nonEmpty)
      .toSet
    val existingImageIds = currentImages.map(_.pageId)
    val existingJurorIds = currentImages.flatMap(_.jurors)
    val mpxAtLeast = round.minMpx
    val sizeAtLeast = round.minImageSize.map(_ * 1024 * 1024)

    // All selected previous rounds share the same rate type and the same filtering
    // conditions, so any one of them is a valid context for rate scaling / gating.
    val prevRound = prevRounds.headOption

    // a top-N from yes/no rounds is ranked by all their votes, whatever "selected by" reads;
    // a round from before V72 (no order stored) by the legacy rule, the rate sum of the rows read
    val binaryTop = prevRound.exists(_.isBinary) && selectTopByRating.isDefined && round.topBinaryOrder.isDefined

    val imagesAll: Seq[ImageWithRating] =
      if (prevRounds.isEmpty)
        imageRepo
          .findByContestId(round.contestId)
          .map(i => new ImageWithRating(i, Seq.empty))
      else if (binaryTop)
        DistributeImages.binaryCandidates(
          mergeByPageId(prevRounds.flatMap(r => imageRepo.byRoundMerged(r.getId, tally = r.isBinary))),
          rated = selectedAtLeast.isDefined
        )
      else
        mergeByPageId(
          prevRounds.flatMap(r =>
            imageRepo.byRoundMerged(r.getId, rated = Option.when(selectedAtLeast.isDefined)(true), tally = r.isBinary)
          )
        )
    logger.debug("Total images: " + imagesAll.size)

    // "exclude files rejected by at least N" counts the rejections of all the votes of yes/no
    // previous rounds, whatever "selected by" reads (its rows are the approvals only)
    val notRejected = prevRound.filter(_.isBinary).flatMap(_ => rejectedBy)
    val rejections: Map[Long, Int] = notRejected.fold(Map.empty[Long, Int])(_ =>
      mergeByPageId(prevRounds.flatMap(r => imageRepo.byRoundMerged(r.getId, tally = r.isBinary)))
        .map(i => i.pageId -> i.rejections).toMap)
    val candidates = if (notRejected.isEmpty) imagesAll
      else imagesAll.map(i => i.copy(rejectionsFromDb = Some(rejections.getOrElse(i.pageId, 0))))

    val funGens = Seq(CategoryImages(categoryRule, firstCategoryIds, secondCategoryIds)) ++
      ImageWithRatingSeqFilter.funGenerators(
      prevRound,
      includeRegionIds = includeRegionIds,
      excludeRegionIds = excludeRegionIds,
      includeMonumentIds = includeMonumentIds,
      includePageIds = includePageIds,
      excludePageIds = excludePageIds ++ existingImageIds,
      includeTitles = includeTitles,
      excludeTitles = excludeTitles,
      includeJurorId = includeJurorId,
      excludeJurorId = excludeJurorId /*++ existingJurorIds*/,
      selectMinAvgRating =
        prevRound.flatMap(_ => selectMinAvgRating.filter(_ => !prevRound.exists(_.isBinary))),
      selectTopByRating = prevRound.flatMap(_ => selectTopByRating),
      topOrder = round.topBinaryOrder,
      selectedAtLeast = prevRound.flatMap(_ => selectedAtLeast),
      rejectedBy = notRejected,
      mpxAtLeast = mpxAtLeast,
      sizeAtLeast = sizeAtLeast,
      specialNomination = round.specialNomination,
      mediaType = round.mediaType
    )

    val filterChain = ImageWithRatingSeqFilter.makeFunChain(funGens)

    val images = filterChain(candidates).map(_.image)
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
              votes = acc.votes ++ iwr.votes,
              countFromDb = acc.countFromDb + iwr.countFromDb,
              approvalsFromDb = sum(acc.approvalsFromDb, iwr.approvalsFromDb),
              rejectionsFromDb = sum(acc.rejectionsFromDb, iwr.rejectionsFromDb)
            )
          )
        case None => Some(iwr)
      }
    }
    merged.values.toSeq
  }

  private def sum(a: Option[Int], b: Option[Int]): Option[Int] =
    if (a.isEmpty && b.isEmpty) None else Some(a.getOrElse(0) + b.getOrElse(0))

  def rebalanceImages(
      round: Round,
      jurors: Seq[User],
      images: Seq[Image],
      currentSelection: Seq[Vote]
  ): Rebalance = {

    if (currentSelection == Nil) {
      Rebalance(newSelection(round, images, jurors), Nil)
    } else {
      Rebalance(Nil, Nil)
    }
  }

}

object DistributeImages {

  /** Yes/no previous rounds' images, grouped with all their votes, for a top-N: with `rated`
    * ("selected by at least" set) those with an approval, else all, each with its approvals as
    * its rate, so that "selected by at least" compares them, as
    * [[services.distribution.DistributionSource.rank]] has them.
    */
  def binaryCandidates(images: Seq[ImageWithRating], rated: Boolean): Seq[ImageWithRating] =
    images
      .filter(i => !rated || i.approvals > 0)
      .map(i => i.copy(votes = Seq(Vote(i.pageId, juryId = 0, roundId = 0, rate = i.approvals))))

  /** Vote rows per insert batch: ~200 KB, well below any max_allowed_packet. */
  val InsertChunk = 5000

  /** A distribution needs jurors, each once: a juror listed twice would get duplicate
    * (page_id, jury_id, round_id) rows, which the unique index rejects mid-insert; and
    * no more jurors per image than there are jurors.
    */
  def checkJury(round: Round, jurors: Seq[User]): Unit = {
    require(jurors.nonEmpty, s"Round ${round.id.orNull}: cannot distribute images to an empty jury")
    val distinct = jurors.flatMap(_.id).distinct.size
    require(
      distinct == jurors.size,
      s"Round ${round.id.orNull}: ${jurors.size - distinct} duplicate jurors; check for duplicate jurors"
    )
    require(
      round.distribution <= jurors.size,
      s"Round ${round.id.orNull}: ${round.distribution} jurors per image, but only ${jurors.size} jurors"
    )
  }

  /** The vote rows for `images`, the first of them being the `startPosition`-th
    * image the distribution assigns: with `distribution = x`, image i gets the jurors
    * (i + j) % n, j < x, of the jurors in their order (by id); with 0, every juror gets
    * every image. Carrying the position over chunks continues the rotation.
    */
  def assign(round: Round, images: Seq[Image], jurors: IndexedSeq[User], startPosition: Long): Seq[Vote] =
    round.distribution match {
      case 0 =>
        jurors.flatMap(juror => images.map(img => Vote(img, juror, round)))
      case x =>
        val n = jurors.size
        images.zipWithIndex.flatMap { case (img, k) =>
          val i = startPosition + k
          (0 until x).map(j => Vote(img, jurors(((i + j) % n).toInt), round))
        }
    }

  /** Inserts vote rows in the caller's transaction, in batches: the driver sends one batch
    * without splitting it to fit the server's max_allowed_packet, so a batch of a whole
    * distribution (images x jurors rows, ~40 bytes each) fails with "Socket error" once it
    * outgrows it (16M by default). A criteria round's rates are written by its votes
    * ([[org.intracer.wmua.CriteriaRate.updateRate]]), none here.
    */
  def insertRows(rows: Seq[Vote])(implicit session: DBSession): Unit =
    rows.grouped(InsertChunk).foreach(VoteJdbc.batchInsert(_))

  case class Rebalance(newSelections: Seq[Vote], removedSelections: Seq[Vote])

  val NoRebalance = Rebalance(Nil, Nil)

}
