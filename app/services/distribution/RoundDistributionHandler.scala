package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import play.api.libs.json._
import play.api.{Configuration, Logging}
import scalikejdbc._
import services.imports.{CommonsSource, ImageText}
import services.jobs._
import services.regions.ContestKind

import javax.inject.{Inject, Singleton}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable

/** The resume cursor of a round distribution (`job.state`).
  *
  * @param phase          "rank" (top-N: rank the previous rounds' images, the first step),
  *                       "distribute" (chunks), "list" (chunks of the pasted list's files),
  *                       "verify" (the final count check)
  * @param cursor         the keyset cursor: the last candidate page_id a committed chunk read
  *                       (of the phase's candidates)
  * @param position       images assigned so far: the juror rotation continues from it
  * @param candidatesSeen candidates read (the progress)
  * @param imagesBefore   the round's images this job didn't add (for the count check): counted
  *                       by the job's first step
  * @param total          the number of candidates, for the progress
  * @param ranked         top-N: the chosen images, ascending
  * @param frozen         the previous rounds this job holds frozen
  * @param verifyFailed   the count check failed: a retry rescans with fresh accounting
  * @param listIndex      the next title of the pasted list (phase "list")
  * @param missing        titles of the pasted list that neither the database nor Commons
  *                       has a file of
  * @param missingTitles  the first [[RoundDistributionHandler.MaxMissingTitles]] of them
  */
final case class DistributionState(
    phase: String,
    cursor: Long = 0L,
    position: Long = 0L,
    candidatesSeen: Long = 0L,
    imagesAdded: Long = 0L,
    rowsAdded: Long = 0L,
    imagesBefore: Option[Long] = None,
    total: Option[Long] = None,
    ranked: Seq[Long] = Nil,
    frozen: Seq[Long] = Nil,
    verifyFailed: Boolean = false,
    listIndex: Int = 0,
    missing: Long = 0L,
    missingTitles: Seq[String] = Nil
)

object DistributionState {
  implicit val format: OFormat[DistributionState] = Json.using[Json.WithDefaultValues].format[DistributionState]
}

/** Distributes a round's images in the background, as a [[Job]] of kind
  * `round_distribution` whose subject is the round. The single fill path for a new
  * round, a resubmitted or retried creation and "Distribute new files"
  * ([[services.RoundService]]).
  *
  * Each step is one chunk, in one short transaction, with the job's state:
  *  1. read the next keyset window of candidates by page_id: the contest category's
  *     images for a first round; the previous rounds' selections, grouped per image in
  *     SQL, otherwise; for top-N the ranked images (ranked once, by the first step);
  *  2. leave out the images already in the round (`NOT EXISTS`): a resumed or repeated
  *     job never adds an image twice, nor one whose votes of the round were moved aside
  *     (`selection_deleted`); and those deleted on Commons (`images.deleted_at`, V63, set
  *     by [[services.imports.CommonsFileCheck]]);
  *  3. apply the round's per-image filters ([[DistributeImages.perImageFilters]], the
  *     same chain as the in-memory path);
  *  4. assign jurors from the job's running position ([[DistributeImages.assign]]) and
  *     insert the selection rows, and criteria rates in a criteria round.
  *
  * A job may carry a pasted list of files (`params.titles`, from the round form's "Add
  * files"): after the filtered candidates (or instead of them, with `params.listOnly`) its
  * "list" phase adds the listed files to the round, whatever the round's filters; those in
  * the round already or deleted on Commons are left out. Each step takes the next titles,
  * as many as one Commons request of their details takes, looked up in [[prepare]], outside
  * any transaction: by title among the stored images (usually the contest's), and on
  * Commons only those not stored; these are inserted, but not put in any category (they are
  * the round's, not the contest's).
  *
  * The last step checks the round's distinct-image count against the images added
  * (exact, as `distributeAndVerify` did) and, for a creation, shows the round to its
  * jurors. The count includes the round's votes moved aside (`selection_deleted`), so files
  * deleted or restored on Commons meanwhile ([[services.imports.DeletedFiles]]) don't
  * change it. The previous rounds stay frozen ([[Round.freeze]]) from the job's creation
  * until it ends; a creation then closes them, anything else restores them.
  *
  * Held in memory per job: at most one window of candidates (the chunk) or one chunk of the
  * pasted list's files, the jurors and the Commons category file ids of the round's
  * category filters (fetched once per job run, in [[prepare]], outside any transaction).
  */
@Singleton
class RoundDistributionHandler @Inject() (
    distributeImages: DistributeImages,
    config: Configuration,
    commons: CommonsSource
) extends JobHandler
    with Logging {

  import RoundDistributionHandler._

  val kind: String = Kind

  /** Selection rows per chunk: the chunk's window holds rows / jurors-per-image images. */
  val chunkRows: Int =
    config.getOptional[Int]("jobs.roundDistribution.chunkRows").getOrElse(DefaultChunkRows)

  private val contexts = TrieMap.empty[Long, Context]

  /** The pasted list's files of each job's next "list" step. */
  private val listChunks = TrieMap.empty[Long, ListChunk]

  override def title(job: Job): String = {
    val round = job.subjectId.getOrElse("?")
    titleCountOf(job) match {
      case 0                  => s"Distributing images to round $round"
      case n if listOnly(job) => s"Adding $n listed files to round $round"
      case n                  => s"Distributing images and $n listed files to round $round"
    }
  }

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("roundId" -> roundIdOf(job), "imagesAdded" -> st.imagesAdded, "rowsAdded" -> st.rowsAdded,
      "phase" -> st.phase, "missing" -> st.missing, "missingTitles" -> st.missingTitles)
  }

  override def prepare(job: Job): Unit = {
    val c = contexts.getOrElseUpdate(job.id, context(roundIdOf(job)))
    val st = stateOf(job)
    if (st.phase == Phase.List && st.listIndex < titleCountOf(job) && !listChunks.get(job.id).exists(_.index == st.listIndex))
      listChunks.put(job.id, listChunk(c.round, st.listIndex, titlesOf(job, st.listIndex, commons.maxIdsPerRequest)))
  }

  override def forget(jobId: Long): Unit = {
    contexts.remove(jobId)
    listChunks.remove(jobId)
  }

  /** Hooks for tests: run in a chunk's transaction before and after its rows are inserted. */
  protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = ()
  protected def afterInsert(round: Round, rows: Seq[Selection])(implicit session: DBSession): Unit = ()

  /** What the steps need. */
  private def context(roundId: Long): Context = {
    val round = Round.findById(roundId).getOrElse(throw new NoSuchElementException(s"Round $roundId not found"))
    val prevRounds = RoundDistributionHandler.previousRounds(round)
    val jurors = User.distributionJurors(roundId).sortBy(_.getId).toIndexedSeq
    DistributeImages.checkJury(round, jurors)
    val includeFromCats = distributeImages.categoryFileIds(round.category)
    val excludeFromCats = distributeImages.categoryFileIds(round.excludeCategory)
    val prev = prevRounds.headOption
    val perImage = if (round.distribution == 0) jurors.size else round.distribution
    Context(
      round = round,
      prevRounds = prevRounds,
      jurors = jurors,
      perImage = perImage,
      filter = distributeImages.perImageFilters(round, prevRounds, includeFromCats, excludeFromCats),
      rated = prev.flatMap(_ => round.prevSelectedBy.map(_ > 0)),
      topN = prev.flatMap(_ => round.topImages),
      categoryId =
        if (prevRounds.isEmpty) ContestJuryJdbc.findById(round.contestId).flatMap(_.categoryId) else None,
      denominator = prev match {
        case Some(p) if p.isBinary     => Denominator.Const(p.numberOfAssignedJurors)
        case Some(p) if p.optionalRate => Denominator.Const(p.numberOfActiveJurors.toLong)
        case _                         => Denominator.PerImage
      },
      windowSize = math.max(1, math.min(MaxWindow, chunkRows / math.max(1, perImage)))
    )
  }

  /** The files of `titles`, the pasted list's from the `index`th: the stored images of
    * those titles, usually imported with the contest already, and the rest as Commons has
    * them (one request, with their text when the contest has a monument id template).
    */
  private def listChunk(round: Round, index: Int, titles: Seq[String]): ListChunk = {
    val stored = ImageJdbc.findByTitles(titles)
    val storedTitles = stored.map(_.title).toSet
    val notStored = titles.filterNot(storedTitles.contains)
    val (fetched, missing) =
      if (notStored.isEmpty) (Nil, Nil)
      else {
        val contest = ContestJuryJdbc
          .findById(round.contestId)
          .getOrElse(throw new NoSuchElementException(s"Contest ${round.contestId} not found"))
        val template = ContestKind.monumentIdTemplate(contest)
        val pages = commons.filesByTitles(notStored, info = true, text = template.isDefined)
        val images = ImageText.images(pages, contest, template)
        val found = images.map(_.pageId).toSet
        // a missing title, or a page that isn't a file
        (images, pages.filterNot(_.id.exists(found.contains)).map(_.title).distinct)
      }
    ListChunk(index, titles.size, stored, fetched, missing)
  }

  // ── job creation, failure, cancel, retry ───────────────────────────────────

  /** The new job's state: freezes the previous rounds, in the creating transaction. A job
    * that only adds a pasted list (`listOnly`) doesn't read them: it starts with the list,
    * and freezes nothing.
    */
  def initialState(round: Round, listOnly: Boolean = false)(implicit session: DBSession): JsObject =
    if (listOnly) Json.toJsObject(DistributionState(phase = Phase.List))
    else {
      val prevIds = round.previousIds
      Round.freeze(prevIds)
      val topN = prevIds.headOption.flatMap(_ => round.topImages)
      Json.toJsObject(
        DistributionState(phase = if (topN.isDefined) Phase.Rank else Phase.Distribute, frozen = prevIds)
      )
    }

  override def onFailed(job: Job, ctx: StepContext): JsObject = end(job, ctx)

  override def onCancelled(job: Job, ctx: StepContext): JsObject = end(job, ctx)

  /** Unfreezes the previous rounds, and moves aside the votes of deleted files that the job
    * added ([[settleFiles]]).
    */
  private def end(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    lockRounds(st.frozen :+ roundId)
    Round.release(st.frozen, close = false)
    if (settleFiles(roundId)) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
    Json.toJsObject(st.copy(frozen = Nil))
  }

  /** Freezes the previous rounds again and resumes. After a failed count check the round is
    * rescanned from the start with fresh accounting, as "Distribute new files" would: the
    * images already in it are skipped, the missing ones added.
    */
  override def onRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    forget(job.id) // the jurors and filters are read again
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    val prevIds = if (listOnly(job)) Nil else Round.findById(roundId).map(_.previousIds).getOrElse(Nil)
    Round.release(st.frozen, close = false) // nothing, normally: a failure released them
    Round.freeze(prevIds)
    val resumed =
      if (!st.verifyFailed) st
      else
        st.copy(phase = if (listOnly(job)) Phase.List else Phase.Distribute, cursor = 0L, candidatesSeen = 0L,
          imagesAdded = 0L, rowsAdded = 0L, imagesBefore = None, verifyFailed = false, listIndex = 0, missing = 0L,
          missingTitles = Nil)
    Json.toJsObject(resumed.copy(frozen = prevIds))
  }

  // ── steps ──────────────────────────────────────────────────────────────────

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    val c = contexts.getOrElseUpdate(job.id, context(roundId))
    val st = stateOf(job) match {
      case s if s.imagesBefore.isEmpty => s.copy(imagesBefore = Some(imageCount(roundId) - s.imagesAdded))
      case s                           => s
    }

    st.phase match {
      case Phase.Rank =>
        val ranked = DistributionSource.rank(c.prevRounds.flatMap(_.id), c.rated, c.denominator, c.topN.get, c.filter)
        val next = st.copy(phase = Phase.Distribute, ranked = ranked.sorted,
          total = Some(ranked.size.toLong + titleCountOf(job)), cursor = 0L)
        continue(next)

      case Phase.Distribute =>
        val st1 = if (st.total.isDefined) st else st.copy(total = Some(estimate(c, st) + titleCountOf(job)))
        val window = nextWindow(c, st1)
        if (window.size == 0)
          continue(st1.copy(phase = if (titleCountOf(job) > 0) Phase.List else Phase.Verify, cursor = 0L))
        else {
          val passed: Seq[Image] =
            if (c.topN.isDefined) DistributionSource.imagesIn(window.ids, roundId)
            else c.filter(candidates(c, roundId, st1.cursor, window.last)).map(_.image)
          val rows = DistributeImages.assign(c.round, passed, c.jurors, st1.position)
          beforeInsert(c.round, passed)
          DistributeImages.insertRows(c.round, rows)
          afterInsert(c.round, rows)
          if (rows.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          continue(
            st1.copy(
              cursor = window.last,
              position = st1.position + passed.size,
              candidatesSeen = st1.candidatesSeen + window.size,
              imagesAdded = st1.imagesAdded + passed.size,
              rowsAdded = st1.rowsAdded + rows.size
            )
          )
        }

      case Phase.List =>
        val st1 = if (st.total.isDefined) st else st.copy(total = Some(titleCountOf(job).toLong))
        if (st1.listIndex >= titleCountOf(job)) continue(st1.copy(phase = Phase.Verify))
        else
          listChunks.get(job.id).filter(_.index == st1.listIndex) match {
            // prepared for another position: the next turn prepares this one
            case None => continue(st1)
            // the params have fewer titles than they count
            case Some(chunk) if chunk.titles == 0 => continue(st1.copy(phase = Phase.Verify))
            case Some(chunk) =>
              ImageJdbc.insertIgnore(chunk.fetched)
              val passed =
                DistributionSource.imagesIn((chunk.stored ++ chunk.fetched).map(_.pageId).distinct.sorted, roundId)
              val rows = DistributeImages.assign(c.round, passed, c.jurors, st1.position)
              beforeInsert(c.round, passed)
              DistributeImages.insertRows(c.round, rows)
              afterInsert(c.round, rows)
              if (rows.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
              continue(
                st1.copy(
                  listIndex = chunk.index + chunk.titles,
                  position = st1.position + passed.size,
                  candidatesSeen = st1.candidatesSeen + chunk.titles,
                  imagesAdded = st1.imagesAdded + passed.size,
                  rowsAdded = st1.rowsAdded + rows.size,
                  missing = st1.missing + chunk.missingTitles.size,
                  missingTitles = (st1.missingTitles ++ chunk.missingTitles).take(MaxMissingTitles)
                )
              )
          }

      case Phase.Verify =>
        val count = imageCount(roundId)
        val expected = st.imagesBefore.getOrElse(0L) + st.imagesAdded
        if (count != expected)
          StepResult.Failed(
            s"Round $roundId has $count of $expected images after the distribution. Retry to add the missing ones.",
            Json.toJsObject(st.copy(verifyFailed = true)),
            progress(st)
          )
        else {
          val distributing = lockRounds(st.frozen :+ roundId).getOrElse(roundId, false)
          val creating = distributing || (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false)
          if (distributing) Round.completeCreation(roundId)
          Round.release(st.frozen, close = creating)
          settleFiles(roundId) // after the rounds' updates: rounds before votes, as DeletedFiles locks them
          val visible = visibleImageCount(roundId)
          ctx.afterCommit(RoundImageCounts.refresh(roundId)(visible.toInt))
          logger.info(
            s"Round $roundId: distributed ${st.imagesAdded} images (${st.rowsAdded} selection rows) " +
              s"to ${c.jurors.size} jurors" +
              (if (c.prevRounds.nonEmpty && !listOnly(job))
                 s" from previous rounds [${c.prevRounds.flatMap(_.id).mkString(", ")}]"
               else "") +
              (if (titleCountOf(job) > 0) s", with a list of ${titleCountOf(job)} files (${st.missing} not found)"
               else "")
          )
          StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), progress(st))
        }

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  /** Moves aside the round's votes of files deleted on Commons, if the round is open
    * ([[Round.openCond]]): a step may have read a file as a candidate just before it was
    * marked deleted ([[services.imports.DeletedFiles.markDeleted]]), and added it after its
    * votes were moved. A round that isn't open moves them when it opens.
    *
    * @return whether any votes moved
    */
  private def settleFiles(roundId: Long)(implicit session: DBSession): Boolean = {
    val deleted = if (Round.isOpen(roundId)) ImageJdbc.archiveDeletedVotesInRound(roundId) else Nil
    if (deleted.nonEmpty) logger.info(s"Round $roundId: moved aside the votes of ${deleted.size} files deleted on Commons")
    deleted.nonEmpty
  }

  /** Locks the rounds for update in id order, as DeletedFiles locks them, before any of
    * their updates.
    *
    * @return whether each is being created (`distributing`)
    */
  private def lockRounds(ids: Seq[Long])(implicit session: DBSession): Map[Long, Boolean] =
    sql"SELECT id, distributing FROM rounds WHERE id IN (${ids.distinct}) ORDER BY id FOR UPDATE"
      .map(rs => rs.long(1) -> rs.boolean(2))
      .list()
      .toMap

  private def continue(st: DistributionState) = StepResult.Continue(Json.toJsObject(st), progress(st))

  private def progress(st: DistributionState) =
    Progress(done = Some(st.candidatesSeen), total = st.total, unit = Some("images"))

  /** The round's images for the count check: also those with votes moved aside (V63), so
    * moving votes aside or back doesn't change it: the visible ones, counted on the
    * (round_id, page_id) index, and the few aside that have no visible vote in the round.
    */
  private def imageCount(roundId: Long)(implicit session: DBSession): Long =
    visibleImageCount(roundId) +
      sql"""SELECT COUNT(DISTINCT d.page_id) FROM selection_deleted d
            WHERE d.round_id = $roundId
              AND NOT EXISTS (SELECT 1 FROM selection s WHERE s.round_id = $roundId AND s.page_id = d.page_id)"""
        .map(_.long(1)).single().getOrElse(0L)

  /** The round's images the galleries show. */
  private def visibleImageCount(roundId: Long)(implicit session: DBSession): Long =
    sql"SELECT COUNT(DISTINCT page_id) FROM selection WHERE round_id = $roundId".map(_.long(1)).single().getOrElse(0L)

  private def estimate(c: Context, st: DistributionState)(implicit session: DBSession): Long =
    if (c.topN.isDefined) st.ranked.size.toLong
    else if (c.prevRounds.nonEmpty) DistributionSource.previousCount(c.prevRounds.flatMap(_.id), c.rated)
    else c.categoryId.fold(0L)(DistributionSource.categoryCount)

  private def nextWindow(c: Context, st: DistributionState)(implicit session: DBSession): Window =
    if (c.topN.isDefined) {
      val ids = st.ranked.iterator.filter(_ > st.cursor).take(c.windowSize).toSeq
      Window(ids.size, ids.lastOption.getOrElse(st.cursor), ids)
    } else if (c.prevRounds.nonEmpty)
      DistributionSource.previousWindow(c.prevRounds.flatMap(_.id), c.rated, st.cursor, c.windowSize)
    else c.categoryId.fold(Window.empty)(DistributionSource.categoryWindow(_, st.cursor, c.windowSize))

  private def candidates(c: Context, roundId: Long, from: Long, to: Long)(implicit
      session: DBSession
  ): Seq[ImageWithRating] =
    if (c.prevRounds.nonEmpty) DistributionSource.previousCandidates(c.prevRounds.flatMap(_.id), c.rated, from, to, roundId)
    else c.categoryId.fold(Seq.empty[ImageWithRating])(DistributionSource.categoryCandidates(_, from, to, roundId))

  /** Whatever a distribution of the round would add now ("Count new files"), without
    * adding it: the same windows and filters as the job, read only.
    */
  def countNew(roundId: Long): Long = DB.readOnly { implicit session =>
    val c = context(roundId)
    if (c.topN.isDefined) {
      val ranked = DistributionSource.rank(c.prevRounds.flatMap(_.id), c.rated, c.denominator, c.topN.get, c.filter)
      ranked.grouped(MaxWindow).map(ids => DistributionSource.imagesIn(ids, roundId).size.toLong).sum
    } else {
      var st = DistributionState(Phase.Distribute)
      var added = 0L
      var window = nextWindow(c, st)
      while (window.size > 0) {
        added += c.filter(candidates(c, roundId, st.cursor, window.last)).size
        st = st.copy(cursor = window.last)
        window = nextWindow(c, st)
      }
      added
    }
  }
}

object RoundDistributionHandler {

  val Kind = "round_distribution"
  val SubjectType = "round"

  /** One active distribution per round (see V60). */
  def dedupeKey(roundId: Long): String = s"$Kind:$SubjectType:$roundId"

  /** Rows per chunk when `jobs.roundDistribution.chunkRows` isn't set: see the plan's
    * 13.7 measurements.
    */
  val DefaultChunkRows = 15000

  /** At most this many candidate images per window, whatever the jurors per image. */
  val MaxWindow = 20000

  val MaxMissingTitles = 100

  /** What the steps of one job need besides its state: built by `prepare`. */
  final case class Context(
      round: Round,
      prevRounds: Seq[Round],
      jurors: IndexedSeq[User],
      perImage: Int,
      filter: Seq[ImageWithRating] => Seq[ImageWithRating],
      rated: Option[Boolean],
      topN: Option[Int],
      categoryId: Option[Long],
      denominator: Denominator,
      windowSize: Int
  )

  /** The files of `titles` titles of a job's pasted list, from the `index`th: those stored,
    * those fetched from Commons (to insert), and the titles found nowhere.
    */
  final case class ListChunk(
      index: Int,
      titles: Int,
      stored: Seq[Image],
      fetched: Seq[Image],
      missingTitles: Seq[String]
  )

  object Phase {
    val Rank = "rank"
    val Distribute = "distribute"
    val List = "list"
    val Verify = "verify"
  }

  /** The job's pasted list of files to add to the round (`params.titles`), if it has one. */
  def titlesOf(job: Job): Seq[String] = (job.params \ "titles").asOpt[Seq[String]].getOrElse(Nil)

  /** At most `n` of the pasted titles, from the `from`th. */
  def titlesOf(job: Job, from: Int, n: Int): Seq[String] =
    (job.params \ "titles").asOpt[JsArray].fold(Seq.empty[String])(_.value.slice(from, from + n).map(_.as[String]).toSeq)

  def titleCountOf(job: Job): Int = (job.params \ "titleCount").asOpt[Int].getOrElse(0)

  /** The job only adds its list: the round's filters choose nothing. */
  def listOnly(job: Job): Boolean = (job.params \ "listOnly").asOpt[Boolean].getOrElse(false)

  def roundIdOf(job: Job): Long =
    (job.params \ "roundId").asOpt[Long].orElse(job.subjectId).getOrElse(
      throw new IllegalStateException(s"Job ${job.id}: no round"))

  def stateOf(job: Job): DistributionState =
    job.state.validate[DistributionState].getOrElse(DistributionState(Phase.Distribute))

  def imagesAdded(job: Job): Long = stateOf(job).imagesAdded

  /** The round's previous rounds, in its order, from its own contest only (another
    * contest's rounds would be frozen and their images copied), all of one rate type.
    */
  def previousRounds(round: Round): Seq[Round] = {
    val ids = round.previousIds
    val found = if (ids.isEmpty) Nil else Round.findByIds(round.contestId, ids)
    val prevRounds = ids.flatMap(id => found.find(_.id.contains(id)))
    require(
      Round.sameRateType(prevRounds),
      s"previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] must all be of the same rate type"
    )
    require(
      ids.size == prevRounds.size,
      s"previous rounds [${ids.mkString(", ")}] not all found: got [${prevRounds.flatMap(_.id).mkString(", ")}]"
    )
    prevRounds
  }

  /** The denominator of an image's average rating in a previous round
    * ([[ImageWithRating.totalRate]] of a grouped image): a constant for a binary round
    * (its jurors) and an optional-rate round (its active jurors), else the image's rows.
    */
  sealed trait Denominator
  object Denominator {
    final case class Const(n: Long) extends Denominator
    case object PerImage extends Denominator
  }
}

/** A keyset window of candidates: `size` page ids after the cursor, up to `last`. */
final case class Window(size: Int, last: Long, ids: Seq[Long] = Nil)

object Window {
  val empty: Window = Window(0, 0L)
}

/** The streamed candidate queries of [[RoundDistributionHandler]]. */
object DistributionSource {

  import RoundDistributionHandler.Denominator

  private val i = ImageJdbc.syntax("i")

  private def ratedCond(rated: Option[Boolean]): SQLSyntax = rated match {
    case Some(true)  => sqls"AND s.rate > 0"
    case Some(false) => sqls"AND s.rate = 0"
    case None        => sqls""
  }

  /** Images of the round never come back: the anti-join that makes a resume idempotent.
    * Nor do those with votes of the round moved aside (V63): they are in the round's count
    * already, and get their votes back if the file is restored.
    */
  private def notInRound(roundId: Long, pageId: SQLSyntax): SQLSyntax =
    sqls"""NOT EXISTS (SELECT 1 FROM selection x WHERE x.round_id = $roundId AND x.page_id = $pageId)
           AND NOT EXISTS (SELECT 1 FROM selection_deleted xd WHERE xd.round_id = $roundId AND xd.page_id = $pageId)"""

  def categoryCount(categoryId: Long)(implicit session: DBSession): Long =
    sql"SELECT COUNT(*) FROM category_members WHERE category_id = $categoryId".map(_.long(1)).single().getOrElse(0L)

  def categoryWindow(categoryId: Long, cursor: Long, size: Int)(implicit session: DBSession): Window =
    sql"""SELECT COUNT(*), MAX(page_id) FROM (
            SELECT page_id FROM category_members WHERE category_id = $categoryId AND page_id > $cursor
            ORDER BY page_id LIMIT $size) w"""
      .map(rs => Window(rs.int(1), rs.longOpt(2).getOrElse(cursor)))
      .single()
      .getOrElse(Window.empty)

  def categoryCandidates(categoryId: Long, from: Long, to: Long, roundId: Long)(implicit
      session: DBSession
  ): Seq[ImageWithRating] =
    sql"""SELECT ${i.result.*} FROM category_members cm JOIN images i ON i.page_id = cm.page_id
          WHERE cm.category_id = $categoryId AND cm.page_id > $from AND cm.page_id <= $to
            AND i.deleted_at IS NULL AND ${notInRound(roundId, sqls"cm.page_id")}
          ORDER BY cm.page_id"""
      .map(rs => new ImageWithRating(ImageJdbc(i)(rs), Seq.empty))
      .list()

  def previousCount(prevIds: Seq[Long], rated: Option[Boolean])(implicit session: DBSession): Long =
    sql"""SELECT COUNT(DISTINCT s.page_id) FROM selection s
          WHERE s.round_id IN ($prevIds) ${ratedCond(rated)}"""
      .map(_.long(1)).single().getOrElse(0L)

  def previousWindow(prevIds: Seq[Long], rated: Option[Boolean], cursor: Long, size: Int)(implicit
      session: DBSession
  ): Window =
    sql"""SELECT COUNT(*), MAX(page_id) FROM (
            SELECT DISTINCT s.page_id FROM selection s
            WHERE s.round_id IN ($prevIds) ${ratedCond(rated)} AND s.page_id > $cursor
            ORDER BY s.page_id LIMIT $size) w"""
      .map(rs => Window(rs.int(1), rs.longOpt(2).getOrElse(cursor)))
      .single()
      .getOrElse(Window.empty)

  /** The window's images not yet in the round, each with its ratings in the previous
    * rounds as the in-memory path builds them (`byRoundMerged` per round, then merged
    * per image): one entry per previous round, juror 0, with the round's rate sum, and
    * the number of rated rows.
    */
  def previousCandidates(prevIds: Seq[Long], rated: Option[Boolean], from: Long, to: Long, roundId: Long)(implicit
      session: DBSession
  ): Seq[ImageWithRating] = {
    val rows = sql"""SELECT g.page_id, g.rs, g.c, ${i.result.*} FROM (
              SELECT s.page_id, s.round_id, SUM(s.rate) rs, COUNT(s.rate) c FROM selection s
              WHERE s.round_id IN ($prevIds) ${ratedCond(rated)} AND s.page_id > $from AND s.page_id <= $to
                AND ${notInRound(roundId, sqls"s.page_id")}
              GROUP BY s.page_id, s.round_id) g
            JOIN images i ON i.page_id = g.page_id
            WHERE i.deleted_at IS NULL
            ORDER BY g.page_id, g.round_id"""
      .map(rs => (ImageJdbc(i)(rs), rs.intOpt("rs").getOrElse(0), rs.int("c")))
      .list()
    val merged = mutable.LinkedHashMap.empty[Long, ImageWithRating]
    rows.foreach { case (image, sum, count) =>
      val entry = Selection(image.pageId, juryId = 0, roundId = 0, rate = sum)
      merged.updateWith(image.pageId) {
        case Some(acc) => Some(acc.copy(selection = acc.selection :+ entry, countFromDb = acc.countFromDb + count))
        case None      => Some(ImageWithRating(image, Seq(entry), count))
      }
    }
    merged.values.toSeq
  }

  /** The images of `ids` not yet in the round, by page id. */
  def imagesIn(ids: Seq[Long], roundId: Long)(implicit session: DBSession): Seq[Image] =
    if (ids.isEmpty) Nil
    else
      sql"""SELECT ${i.result.*} FROM images i
            WHERE i.page_id IN ($ids) AND i.deleted_at IS NULL AND ${notInRound(roundId, sqls"i.page_id")}
            ORDER BY i.page_id"""
        .map(rs => ImageJdbc(i)(rs))
        .list()

  /** Top-N by rating over the previous rounds, ranked in SQL (only the chosen images are
    * held), with the semantics of the in-memory [[org.intracer.wmua.cmd.SelectTopByRating]]:
    * the images ordered by their average rating (`totalRate`: the sum of each round's
    * rate sum, negatives counted as 0, over the denominator), ties broken by that sum,
    * then by the raw sum (the in-memory input order of one previous round), then by
    * page id. The per-image filters apply before ranking: the ranked pages are filtered
    * in order until `n` images pass. Pages grow (n, 2n, 4n, ...) while filters reject
    * images, so a selective filter costs a few queries, not one per image.
    *
    * The images already in the round count in the ranking: the round's top-N doesn't
    * grow when "Distribute new files" runs again.
    */
  def rank(
      prevIds: Seq[Long],
      rated: Option[Boolean],
      denominator: Denominator,
      n: Int,
      filter: Seq[ImageWithRating] => Seq[ImageWithRating]
  )(implicit session: DBSession): Seq[Long] = {
    val total = denominator match {
      case Denominator.Const(d) => sqls"IF($d = 0, 0, SUM(GREATEST(t.rs, 0)) * 1e0 / $d)"
      case Denominator.PerImage => sqls"IF(SUM(t.c) = 0, 0, SUM(GREATEST(t.rs, 0)) * 1e0 / SUM(t.c))"
    }
    val picked = mutable.ArrayBuffer.empty[Long]
    var offset = 0
    var pageSize = math.max(n, 100)
    var exhausted = n <= 0
    while (!exhausted && picked.size < n) {
      val page = sql"""SELECT t.page_id, SUM(GREATEST(t.rs, 0)) rate_sum, SUM(t.c) cnt FROM (
                SELECT s.page_id, s.round_id, SUM(s.rate) rs, COUNT(s.rate) c FROM selection s
                WHERE s.round_id IN ($prevIds) ${ratedCond(rated)}
                GROUP BY s.page_id, s.round_id) t
              GROUP BY t.page_id
              ORDER BY $total DESC, SUM(GREATEST(t.rs, 0)) DESC, SUM(t.rs) DESC, t.page_id ASC
              LIMIT $pageSize OFFSET $offset"""
        .map(rs => (rs.long(1), rs.long(2), rs.long(3)))
        .list()
      val images = if (page.isEmpty) Map.empty[Long, Image] else
        sql"SELECT ${i.result.*} FROM images i WHERE i.page_id IN (${page.map(_._1)}) AND i.deleted_at IS NULL"
          .map(rs => ImageJdbc(i)(rs)).list().map(img => img.pageId -> img).toMap
      val candidates = page.flatMap { case (pageId, sum, count) =>
        images.get(pageId).map(img =>
          ImageWithRating(img, Seq(Selection(pageId, juryId = 0, roundId = 0, rate = sum.toInt)), count.toInt))
      }
      picked ++= filter(candidates).iterator.map(_.pageId).take(n - picked.size)
      exhausted = page.size < pageSize
      offset += page.size
      pageSize *= 2
    }
    picked.toSeq
  }
}
