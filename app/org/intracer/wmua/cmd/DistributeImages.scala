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

  /** Builds and persists the selection rows for `images` in `round`, in one transaction
    * (all chunks or none): the old in-memory path, kept for scripts, tests and as the
    * reference the streamed background distribution
    * ([[services.distribution.RoundDistributionHandler]]) is checked against.
    *
    * @return the number of selection rows written
    */
  def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Int = {
    DistributeImages.checkJury(round, jurors)
    val selection: Seq[Selection] = newSelection(round, images, jurors)
    logger.debug("saving selection: " + selection.size)
    DB.localTx { implicit session =>
      // in batches, with the criteria rates: see DistributeImages.insertRows
      DistributeImages.insertRows(round, selection)
    }
    round.id.foreach(RoundImageCounts.invalidate(_)) // after the commit
    logger.debug("saved selection")
    selection.size
  }

  def newSelection(round: Round, images: Seq[Image], jurors: Seq[User]): Seq[Selection] =
    DistributeImages.assign(round, images, jurors.sorted.toIndexedSeq, startPosition = 0L)

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

  /** The round's per-image filters: every filter of [[imagesByRound]] except top-N by
    * rating (a global ranking) and the exclusion of the images already in the round. Each
    * decides per image, so applying them to any slice of the candidates (a chunk) selects
    * exactly the images the whole list would. `includeFromCats` / `excludeFromCats` are
    * the page ids of the round's Commons category filters ([[categoryFileIds]]).
    */
  def perImageFilters(
      round: Round,
      prevRounds: Seq[Round],
      includeFromCats: Iterable[Long],
      excludeFromCats: Iterable[Long]
  ): Seq[ImageWithRating] => Seq[ImageWithRating] = {
    val prevRound = prevRounds.headOption
    ImageWithRatingSeqFilter.makeFunChain(
      ImageWithRatingSeqFilter.funGenerators(
        prevRound,
        includeRegionIds = round.regionIds.toSet,
        includeMonumentIds = round.monumentIds.toSet,
        includePageIds = includeFromCats.toSet,
        excludePageIds = excludeFromCats.toSet,
        selectMinAvgRating =
          prevRound.flatMap(_ => round.prevMinAvgRate.filter(_ => !prevRound.exists(_.isBinary))),
        selectTopByRating = None,
        selectedAtLeast = prevRound.flatMap(_ => round.prevSelectedBy),
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

  /** Selection rows per insert batch: ~200 KB, well below any max_allowed_packet. */
  val InsertChunk = 5000

  /** The criteria of a criteria round (legacy rounds only: the round form can't create
    * one): every selection row gets a criteria_rate row per criterion.
    */
  val CriteriaIds: Seq[Int] = Seq(6, 7, 8, 9) // TODO load from DB

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

  /** The selection rows for `images`, the first of them being the `startPosition`-th
    * image the distribution assigns: with `distribution = x`, image i gets the jurors
    * (i + j) % n, j < x, of the jurors in their order (by id); with 0, every juror gets
    * every image. Carrying the position over chunks continues the rotation.
    */
  def assign(round: Round, images: Seq[Image], jurors: IndexedSeq[User], startPosition: Long): Seq[Selection] =
    round.distribution match {
      case 0 =>
        jurors.flatMap(juror => images.map(img => Selection(img, juror, round)))
      case x =>
        val n = jurors.size
        images.zipWithIndex.flatMap { case (img, k) =>
          val i = startPosition + k
          (0 until x).map(j => Selection(img, jurors(((i + j) % n).toInt), round))
        }
    }

  /** Inserts selection rows, and their criteria rates in a criteria round, in the caller's
    * transaction, in batches: the driver sends one batch without splitting it to fit
    * the server's max_allowed_packet, so a batch of a whole distribution (images x jurors
    * rows, ~40 bytes each) fails with "Socket error" once it outgrows it (16M by default).
    *
    * The rows' images must be new to the round (the distribution excludes the round's
    * images): the criteria rates are added for every row of those images.
    */
  def insertRows(round: Round, rows: Seq[Selection])(implicit session: DBSession): Unit =
    if (rows.nonEmpty) {
      rows.grouped(InsertChunk).foreach(SelectionJdbc.batchInsert(_))
      if (round.hasCriteria) addCriteriaRates(round.getId, rows.map(_.pageId).distinct)
    }

  /** criteria_rate.id is unique but not auto-increment: the new ids continue from the
    * largest, read with a lock on the end of its index so two transactions can't take
    * the same ones.
    */
  def addCriteriaRates(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = {
    val base = sql"SELECT COALESCE(MAX(id), 0) FROM criteria_rate FOR UPDATE".map(_.long(1)).single().getOrElse(0L)
    val criteria = SQLSyntax.createUnsafely(CriteriaIds.map(id => s"SELECT $id AS cid").mkString(" UNION ALL "))
    pageIds.grouped(InsertChunk).foldLeft(base) { (from, ids) =>
      val added = sql"""INSERT INTO criteria_rate (id, selection, criteria, rate)
            SELECT $from + ROW_NUMBER() OVER (ORDER BY s.id, c.cid), s.id, c.cid, 0
            FROM selection s JOIN ($criteria) c
            WHERE s.round_id = $roundId AND s.page_id IN ($ids)""".update()
      from + added
    }
  }

  case class Rebalance(newSelections: Seq[Selection], removedSelections: Seq[Selection])

  val NoRebalance = Rebalance(Nil, Nil)

}
