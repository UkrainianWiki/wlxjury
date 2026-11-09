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

  def newSelection(round: Round, images: Seq[Image], jurors: Seq[User]): Seq[Vote] =
    DistributeImages.assign(round, images, jurors.sorted.toIndexedSeq, startPosition = 0L)

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
    * ([[db.scalikejdbc.CoiMembers]]). The candidates are rows, one per file and previous round
    * (tagged with it), each evaluated in its own round: a file passes if one of its rows does, and
    * comes back once, its first passing row (the rows of a file in a slice together).
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
    val chain = ImageWithRatingSeqFilter.makeFunChain(
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
        mediaType = round.mediaType,
        // a candidate row is evaluated in its own previous round (it is tagged with it)
        prevRounds = prevRounds
      )
    )
    if (prevRounds.isEmpty) chain else rows => chain(rows).distinctBy(_.pageId)
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
        "must all have the same rating (all yes/no, all comments only, or all rated on one scale, with as many criteria)"
    )

    val categoryRule = round.categoryRule
    categoryRule.requireValid()
    val firstCategoryIds = categoryFileIds(categoryRule.first).toSet
    val secondCategoryIds = categoryFileIds(categoryRule.second).toSet

    val currentImages = imageRepo
      .byRoundMerged(round.getId, tally = true, grades = !round.isBinary)
      .filter(iwr => iwr.votes.nonEmpty)
      .toSet
    val existingImageIds = currentImages.map(_.pageId)
    val existingJurorIds = currentImages.flatMap(_.jurors)
    val mpxAtLeast = round.minMpx
    val sizeAtLeast = round.minImageSize.map(_ * 1024 * 1024)

    // All selected previous rounds share one rating (Round.sameRateType). A file qualifies if it
    // qualifies in at least one of the previous rounds it is in (triage R196-1, the user's rule
    // of 2026-11-10): one row per file and previous round, tagged with it
    // (ImageWithRating.sourceRound), each evaluated in its own round by every filter (its score
    // by its rule and jurors, its approvals, its rejections); a top-N ranks a file by its best
    // row. The first round is the context of yes/no gating.
    val prevRound = prevRounds.headOption
    def ofRound(r: Round)(rows: Seq[ImageWithRating]): Seq[ImageWithRating] =
      rows.map(i => i.copy(votes = i.votes.map(_.copy(roundId = r.getId))))

    // a top-N from yes/no rounds is ranked by all of a round's votes, whatever "selected by"
    // reads; a round from before V72 (no order stored) by the legacy rule, the rate sum of the
    // rows read
    val binaryTop = prevRound.exists(_.isBinary) && selectTopByRating.isDefined && round.topBinaryOrder.isDefined

    // "exclude files rejected by at least N" counts the rejections of all the votes of the
    // round, whatever "selected by" reads (its rows are the approvals only): a yes/no round's
    // "no" votes, a rated round's ✗ (the worst mark); from any previous round that may reject
    // (Round.rejects), as the job and the round form have it; "only" whatever the sources (none
    // rejects: no file passes, rather than every file)
    val notRejected = rejectedBy.filter(_ =>
      prevRounds.exists(_.rejects) || (prevRounds.nonEmpty && round.prevRejectedMode == RejectedMode.Only))

    def rowsOf(r: Round): Seq[ImageWithRating] = {
      val read =
        if (binaryTop)
          DistributeImages.binaryCandidates(imageRepo.byRoundMerged(r.getId, tally = true), rated = selectedAtLeast.isDefined)
        else
          imageRepo.byRoundMerged(r.getId, rated = Option.when(selectedAtLeast.isDefined)(true), tally = true,
            grades = !r.isBinary)
      val rejections: Map[Long, Int] = notRejected.fold(Map.empty[Long, Int])(_ =>
        imageRepo.byRoundMerged(r.getId, tally = true, grades = !r.isBinary).map(i => i.pageId -> i.rejections).toMap)
      ofRound(r)(read).map(i =>
        if (notRejected.isEmpty) i else i.copy(rejectionsFromDb = Some(rejections.getOrElse(i.pageId, 0))))
    }

    val candidates: Seq[ImageWithRating] =
      if (prevRounds.isEmpty)
        imageRepo
          .findByContestId(round.contestId)
          .map(i => new ImageWithRating(i, Seq.empty))
      else prevRounds.flatMap(rowsOf)
    logger.debug("Total rows: " + candidates.size)

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
      rejectedMode = round.prevRejectedMode,
      mpxAtLeast = mpxAtLeast,
      sizeAtLeast = sizeAtLeast,
      specialNomination = round.specialNomination,
      mediaType = round.mediaType,
      prevRounds = prevRounds
    )

    val filterChain = ImageWithRatingSeqFilter.makeFunChain(funGens)

    // a file whose row passes in any of its previous rounds, once (in the first round's order)
    val passed = filterChain(candidates)
    val images = (if (prevRounds.isEmpty) passed else passed.distinctBy(_.pageId)).map(_.image)
    logger.debug("Images after filtering: " + images.size)

    images
  }

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
    * ([[org.intracer.wmua.CriteriaRate.updateRate]]), none here. Each batch adds its files to
    * the round (round_image, V84) as `origin` says.
    */
  def insertRows(rows: Seq[Vote], origin: RoundImageJdbc.Origin = RoundImageJdbc.Origin.Manual)(implicit
      session: DBSession
  ): Unit =
    rows.grouped(InsertChunk).foreach(VoteJdbc.batchInsert(_, origin))

  case class Rebalance(newSelections: Seq[Vote], removedSelections: Seq[Vote])

  val NoRebalance = Rebalance(Nil, Nil)

}
