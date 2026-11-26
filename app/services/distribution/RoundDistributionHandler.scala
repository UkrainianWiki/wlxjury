package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{AverageRule, BinaryOrder, Image, ImageWithRating, RejectedMode, Vote}
import play.api.libs.json._
import play.api.{Configuration, Logging}
import scalikejdbc._
import services.ImageService
import services.imports.{CommonsSource, ImageText}
import services.jobs._
import services.regions.ContestKind

import javax.inject.{Inject, Singleton}
import scala.collection.concurrent.TrieMap
import scala.collection.mutable
import scala.util.control.NonFatal

/** The resume cursor of a round distribution (`job.state`).
  *
  * @param phase          "rank" (top-N: rank the previous rounds' images, the first step),
  *                       "distribute" (chunks), "list" (chunks of the pasted list's files),
  *                       "verify" (the fill's count check, and a one-pass job's end); a job of
  *                       mode `distribute` (P3): "assign" (chunks of the round's files with no
  *                       votes yet, given to the jurors), "complete" (none left, the end)
  * @param cursor         the keyset cursor: the last candidate page_id a committed chunk read
  *                       (of the phase's candidates)
  * @param position       images assigned so far: the juror rotation continues from it
  * @param candidatesSeen candidates read (the progress)
  * @param imagesBefore   the round's images this job didn't add (for the count check): counted
  *                       by the job's first step, and moved by [[othersBefore]]'s change at the
  *                       first step after each Retry
  * @param othersBefore   the round's `round_image` rows not added by this job (`added_by_job_id`)
  *                       when `imagesBefore` was last counted: the first step after a Retry adds
  *                       their change since to `imagesBefore`, so files others added or removed in
  *                       between (while it ran, while it was failed, while it waited in the queue)
  *                       don't fail its count check. None in a state written before it existed:
  *                       `imagesBefore` is then kept
  * @param ownBefore      the round's rows added by this job (`added_by_job_id`) before the
  *                       accounting of `imagesAdded` began (0, or after a rescan the rows of its
  *                       earlier passes): the count check also requires exactly `ownBefore +
  *                       imagesAdded` of them, so its own files that went missing fail it even
  *                       when others' changes make the total come out right
  * @param rebase         a Retry resumed the job from its cursor: its next step rebaselines
  * @param total          the number of candidates, for the progress
  * @param ranked         top-N: the chosen images, ascending
  * @param frozen         the previous rounds this job holds frozen
  * @param verifyFailed   the count check failed: a retry rescans with fresh accounting
  * @param listIndex      the next title of the pasted list (phase "list")
  * @param missing        titles of the pasted list that neither the database nor Commons
  *                       has a file of
  * @param missingTitles  the first [[RoundDistributionHandler.MaxMissingTitles]] of them
  * @param coiLeftOut     files of the pasted list left out as conflicts of interest
  *                       ([[Round.excludeCoi]])
  * @param coiTitles      the first [[RoundDistributionHandler.MaxMissingTitles]] of them, each
  *                       with the person it is by and their role
  * @param passes         a distribute's second passes and its top-up ([[Passes]]); `rescanned`
  *                       stood alone before (read into it, [[DistributionState.format]])
  * @param pruned         a re-apply's files removed ([[Pruned]])
  * @param prevFingerprint a fill's end: the previous rounds' votes as it read them
  *                       ([[RoundDistributionHandler.prevFingerprint]]); the review page warns when
  *                       they changed since (D18)
  */
final case class DistributionState(
    phase: String,
    cursor: Long = 0L,
    position: Long = 0L,
    candidatesSeen: Long = 0L,
    imagesAdded: Long = 0L,
    rowsAdded: Long = 0L,
    imagesBefore: Option[Long] = None,
    othersBefore: Option[Long] = None,
    ownBefore: Option[Long] = None,
    rebase: Boolean = false,
    total: Option[Long] = None,
    ranked: Seq[Long] = Nil,
    frozen: Seq[Long] = Nil,
    verifyFailed: Boolean = false,
    listIndex: Int = 0,
    missing: Long = 0L,
    missingTitles: Seq[String] = Nil,
    coiLeftOut: Long = 0L,
    coiTitles: Seq[String] = Nil,
    passes: Passes = Passes(),
    pruned: Pruned = Pruned(),
    prevFingerprint: Option[String] = None
) {
  /** [[Passes.rescanned]]. */
  def rescanned: Boolean = passes.rescanned

  /** [[Pruned.count]]: the count check's. */
  def removed: Long = pruned.count

  /** [[Pruned.total]]: the review page's. */
  def removedTotal: Long = pruned.total

  /** [[Pruned.titles]]. */
  def removedTitles: Seq[String] = pruned.titles
}

/** A re-apply's files removed (P3-5): the round's `fill` and `new_files` rows from before it that no
  * longer pass the filters, deleted by its "scan" phase (D16).
  *
  * @param count  since `imagesBefore` was last counted (the count check subtracts them; a Retry after a
  *               failed count check starts it again)
  * @param titles the first [[RoundDistributionHandler.MaxMissingTitles]] of them: the review page's
  *               re-apply diff
  * @param total  all of them, across Retries: the review page's
  */
final case class Pruned(count: Long = 0L, titles: Seq[String] = Nil, total: Long = 0L)

/** A distribute's second passes, and its top-up of the files waiting for a juror (round-editing plan §7,
  * "Also give out the waiting files"): nested so that the state stays within a case class's 22 fields.
  *
  * @param rescanned      a distribute found files still to give at its end (a file restored on
  *                       Commons below its cursor) and went over the round once more
  * @param topUpRescanned the top-up found a waiting file an active juror could take at its end and went
  *                       over the waiting files once more
  * @param toppedUp       waiting files the top-up gave jurors to
  * @param topUpRows      the vote rows it gave (counted apart from `rowsAdded`)
  */
final case class Passes(rescanned: Boolean = false, topUpRescanned: Boolean = false, toppedUp: Long = 0L,
    topUpRows: Long = 0L)

object Passes {
  implicit val format: OFormat[Passes] = Json.using[Json.WithDefaultValues].format[Passes]
}

object Pruned {
  implicit val format: OFormat[Pruned] = Json.using[Json.WithDefaultValues].format[Pruned]
}

object DistributionState {
  private val base: OFormat[DistributionState] = Json.using[Json.WithDefaultValues].format[DistributionState]

  /** The state's JSON; a job's state written before [[Passes]] has `rescanned` at its top, read into
    * `passes`.
    */
  implicit val format: OFormat[DistributionState] = OFormat(
    Reads {
      case o: JsObject if !o.keys.contains("passes") && o.keys.contains("rescanned") =>
        val rescanned: Boolean = (o \ "rescanned").asOpt[Boolean].getOrElse(false)
        base.reads(o - "rescanned" + ("passes" -> Json.obj("rescanned" -> rescanned)))
      case js => base.reads(js)
    },
    base)
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
  *     (`vote_aside`); and those deleted on Commons (`images.deleted_at`, V63, set
  *     by [[services.imports.CommonsFileCheck]]); and the files of the rounds the round leaves out
  *     ([[Round.excludeRounds]], V95: any row in their `round_image`), in every candidate query
  *     (windows, counts, candidates, the top-N's ranking);
  *     the round's files are its `round_image` rows (V84), written with the chunk's votes
  *     ([[RoundImageJdbc.addVoted]], `added_how` `fill`, `list` or `new_files`, the job's id);
  *  3. apply the round's per-image filters ([[DistributeImages.perImageFilters]], the
  *     same chain as the in-memory path);
  *  4. assign jurors from the job's running position ([[DistributeImages.assign]]) and
  *     insert the vote rows (a criteria round's rates are written by its votes).
  *
  * A job may carry a pasted list of files (`params.titles`, from the round form's "Add
  * files"): after the filtered candidates (or instead of them, with `params.listOnly`) its
  * "list" phase adds the listed files to the round, whatever the round's filters but one:
  * those in the round already or deleted on Commons are left out, and, if the round leaves
  * out conflicts of interest ([[Round.excludeCoi]]), those (reported in the job's details). Each step takes the next titles,
  * as many as one Commons request of their details takes, looked up in [[prepare]], outside
  * any transaction: by title among the stored images (usually the contest's), and on
  * Commons only those not stored; these are inserted, but not put in any category (they are
  * the round's, not the contest's).
  *
  * The last step checks the round's distinct-image count against the images added
  * (exact, as `distributeAndVerify` did) and, for a creation, shows the round to its
  * jurors. The count includes the round's votes moved aside (`vote_aside`), so files
  * deleted or restored on Commons meanwhile ([[services.imports.DeletedFiles]]) don't
  * change it. The previous rounds stay frozen ([[Round.freeze]]) from the job's creation
  * until it ends; a creation then closes them, anything else restores them.
  *
  * Two steps (P3 of docs/plans/2026-11-02-round-images-two-step.md, §6 "Job"), by `params.mode`:
  *  - `fill`: the chunks add the files to the round (`round_image`, no votes) and the count check
  *    ends the job; a round being created is then `paused`, held in review, still hidden from its
  *    jurors, and its previous rounds are released (D18: not frozen during the review). With
  *    `distributeAfter` ("Distribute as soon as ready", D4; "Distribute new files" on a round
  *    already distributed) it queues the `distribute` job as it ends ([[StepResult.Done.followUp]]);
  *  - `distribute` (the review page's Distribute): its windows are the round's `in` files with no
  *    votes yet whose image isn't deleted ([[RoundImageJdbc.undistributedWindow]]), given to the
  *    jurors from the running position; when none is left the round opens, as a creation did;
  *  - a job without a mode (queued before P3): one pass, the files given to the jurors as they are
  *    added, as before.
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

  /** Vote rows per chunk: the chunk's window holds rows / jurors-per-image images. */
  val chunkRows: Int =
    config.getOptional[Int]("jobs.roundDistribution.chunkRows").getOrElse(DefaultChunkRows)

  private val contexts = TrieMap.empty[Long, Context]

  /** The pasted list's files of each job's next "list" step. */
  private val listChunks = TrieMap.empty[Long, ListChunk]

  override def title(job: Job): String = {
    val round = job.subjectId.getOrElse("?")
    // "Also give out the files waiting for a juror" (round-editing plan §7), alone or after new files
    if (distributeMode(job) && topUpWaiting(job)) s"Distributing images of round $round and its files waiting for a juror"
    else if (distributeMode(job)) s"Distributing the reviewed images of round $round"
    else if (fillOnly(job)) s"Selecting the images of round $round for review"
    else titleCountOf(job) match {
      case 0                  => s"Distributing images to round $round"
      case n if listOnly(job) => s"Adding $n listed files to round $round"
      case n                  => s"Distributing images and $n listed files to round $round"
    }
  }

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("roundId" -> roundIdOf(job), "imagesAdded" -> st.imagesAdded, "rowsAdded" -> st.rowsAdded,
      "phase" -> st.phase, "missing" -> st.missing, "missingTitles" -> st.missingTitles,
      "coiLeftOut" -> st.coiLeftOut, "coiTitles" -> st.coiTitles, "mode" -> modeOf(job),
      "toppedUp" -> st.passes.toppedUp, "topUpRows" -> st.passes.topUpRows)
  }

  override def prepare(job: Job): Unit =
    // refused: nothing to prepare, the step fails the job with the refusal
    contextOf(job).foreach { c =>
      val st = stateOf(job)
      if (st.phase == Phase.List && st.listIndex < titleCountOf(job) && !listChunks.get(job.id).exists(_.index == st.listIndex))
        listChunks.put(job.id, listChunk(c.round, st.listIndex, titlesOf(job, st.listIndex, commons.maxIdsPerRequest)))
    }

  /** The job's context, built once per run; or the refusal of the round's rounds to leave out
    * ([[excludedRounds]], [[requireNoExcluderBusy]]): a refusal of our own checks, which fails the
    * job with its message ([[refused]]) rather than as a crash.
    */
  private def contextOf(job: Job): Either[ExcludeRoundsRefused, Context] =
    contexts.get(job.id) match {
      case Some(c) => Right(c)
      case None =>
        try Right(contexts.getOrElseUpdate(job.id,
            context(roundIdOf(job), Some(jobAverageRules(job)), Some(jobRejectedMode(job)),
              distribute = distributeMode(job), checkPrevious = !listOnly(job) && !distributeMode(job),
              checkJurors = !fillOnly(job))))
        catch { case refusal: ExcludeRoundsRefused => Left(refusal) }
    }

  /** The job failed by a refusal of its round's rounds to leave out: its message key and arguments
    * (the job page shows them in the reader's language), logged at WARN, no stack trace.
    */
  private def refused(job: Job, refusal: ExcludeRoundsRefused): StepResult = {
    logger.warn(s"Job ${job.id} (${job.kind}): round ${roundIdOf(job)}'s distribution refused: ${refusal.text}")
    val st = stateOf(job)
    StepResult.Failed(JobError(refusal.refusal.key, refusal.refusal.args), Json.toJsObject(st), progress(st))
  }

  override def forget(jobId: Long): Unit = {
    contexts.remove(jobId)
    listChunks.remove(jobId)
  }

  /** Hooks for tests: run in a chunk's transaction before and after its rows are inserted. */
  protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = ()
  /** A hook for tests: run in a re-apply's scan step, after its vote check, before its rows are pruned. */
  protected def beforePrune(round: Round, pageIds: Seq[Long])(implicit session: DBSession): Unit = ()
  protected def afterInsert(round: Round, rows: Seq[Vote])(implicit session: DBSession): Unit = ()

  /** What the steps need. Rated previous rounds score their files by `averageRules` if given (a
    * job's, per previous round, [[jobAverageRules]]), else by their own rule ("Count new files":
    * as a job queued now); "rejected by at least N" is applied by `rejectedMode` if given (a
    * job's, [[jobRejectedMode]]), else by the round's own mode.
    */
  private def context(roundId: Long, averageRules: Option[Long => AverageRule],
      rejectedMode: Option[RejectedMode] = None, countOnly: Boolean = false, distribute: Boolean = false,
      checkPrevious: Boolean = true, checkJurors: Boolean = true,
      categoryIds: Option[(Iterable[Long], Iterable[Long])] = None): Context =
    contextFor(Round.findById(roundId).getOrElse(throw new NoSuchElementException(s"Round $roundId not found")),
      averageRules, rejectedMode, countOnly, distribute, checkPrevious, checkJurors, categoryIds)

  /** [[context]] of `round` as given: a stored round's, or the round form's settings as posted (the cut
    * picker's ranking of a round not saved yet: no jurors, read only). `categoryIds`: its category
    * rule's files, read by the caller before its session ([[pickerCategoryIds]]); else read here.
    */
  private def contextFor(round: Round, averageRules: Option[Long => AverageRule],
      rejectedMode: Option[RejectedMode], countOnly: Boolean, distribute: Boolean,
      checkPrevious: Boolean, checkJurors: Boolean,
      categoryIds: Option[(Iterable[Long], Iterable[Long])] = None): Context = {
    val prevRounds = RoundDistributionHandler.previousRounds(round)
      .map(p => averageRules.filterNot(_ => p.isBinary).fold(p)(rules => p.copy(averageRule = rules(p.getId))))
    // a job's run refuses a round to leave out that is being distributed itself (its files still
    // coming); "Count new files" only reads, and counts by the files it has now
    // a distribute (P3) reads no candidates and adds no row: neither check applies to it
    val excluded = if (distribute) Nil else RoundDistributionHandler.excludedRounds(round, checkBusy = !countOnly)
    // and the reverse: a round leaving out this one's files being distributed now (its job reads
    // them as they are) refuses this run; the service checks it before queueing, this is the backstop
    if (!countOnly && !distribute) RoundDistributionHandler.requireNoExcluderBusy(round)
    // and a previous round having a juror added (its votes moving): the service checks it before
    // queueing, this is the backstop (a distribute reads no votes of them)
    if (!countOnly && checkPrevious) RoundDistributionHandler.requireNoPreviousRebalance(round)
    val jurors = round.id.fold(IndexedSeq.empty[User])(User.distributionJurors(_).sortBy(_.getId).toIndexedSeq)
    // a fill (P3) gives no file to a juror: a round held in review may have none yet (they are
    // chosen before its Distribute, which checks them); "Count new files" only counts
    if (checkJurors && !countOnly) DistributeImages.checkJury(round, jurors)
    val categoryRule = round.categoryRule
    if (!distribute) categoryRule.requireValid()
    // from Commons: a distribute applies no filter, so it fetches none (and runs while Commons is down)
    val (firstCategoryIds, secondCategoryIds) =
      if (distribute) (Nil, Nil)
      else categoryIds.getOrElse(
        (distributeImages.categoryFileIds(categoryRule.first), distributeImages.categoryFileIds(categoryRule.second)))
    val prev = prevRounds.headOption
    val perImage = if (round.distribution == 0) jurors.size else round.distribution
    // read once per job run: a membership changed later applies to the next distribution
    val coi = if (round.excludeCoi) CoiMembers.forContest(round.contestId) else Map.empty[String, CoiMembers.Member]
    Context(
      round = round,
      prevRounds = prevRounds,
      jurors = jurors,
      perImage = perImage,
      filter = distributeImages.perImageFilters(round, prevRounds, firstCategoryIds, secondCategoryIds, coi.keySet),
      coi = coi,
      votes = PrevVotes(
        rated = prev.isDefined && round.selectedByFilter.isDefined,
        // a yes/no round's "no" votes, or a rated round's ✗ (the worst mark), from any previous
        // round that may reject (Round.rejects, not the live option), as RoundService records it;
        // "only" whatever the sources (the form refuses it without one): none rejects, no file
        // passes, rather than every file
        rejectedBy = round.rejectedByFilter.filter(_ =>
          prevRounds.exists(_.rejects) || (prev.isDefined && jobMode(rejectedMode, round) == RejectedMode.Only)),
        // leave those files out, or keep only them (V93): the job's recorded mode
        rejectedMode = jobMode(rejectedMode, round),
        grades = prev.exists(!_.isBinary)),
      topN = prev.flatMap(_ => round.topImages),
      // none for a round from before V72: the legacy rule, by the rate sum (ratedRows)
      topOrder = prev.filter(_.isBinary).flatMap(_ => round.topBinaryOrder),
      categoryId =
        if (prevRounds.isEmpty) ContestJuryJdbc.findById(round.contestId).flatMap(_.categoryId) else None,
      // a file's row of each previous round is scored by that round's ([[DistributionSource.rank]])
      denominators = prevRounds.map(p => p.getId -> denominatorOf(p)),
      excluded = excluded,
      windowSize = math.max(1, math.min(MaxWindow, chunkRows / math.max(1, perImage)))
    )
  }

  /** The denominator of a file's score in previous round `p`, as [[ImageWithRating.totalRate]]
    * has it: a yes/no round's jurors; a rated round's by its rule.
    */
  private def denominatorOf(p: Round): Denominator =
    if (p.isBinary) Denominator.Const(p.numberOfAssignedJurors)
    else p.averageRule match {
      case AverageRule.Rated    => Denominator.PerImageRated
      case AverageRule.Assigned => Denominator.PerImage
      // the sum itself: the score a sum round shows
      case AverageRule.Sum      => Denominator.Const(1)
    }

  /** The files of `titles`, the pasted list's from the `index`th, as Commons has them (one
    * request, with their text; their authors as an import gets them,
    * [[ImageText.imagesWithAuthors]]): those not stored, to insert; the stored ones, usually
    * imported with the contest already, and those of them Commons now has otherwise, to
    * update as an import does ([[ImageService.withStoredText]]): a file another contest
    * stored may have an author from before authors were resolved, and the round's conflicts
    * of interest are read from it.
    */
  private def listChunk(round: Round, index: Int, titles: Seq[String]): ListChunk = {
    val contest = ContestJuryJdbc
      .findById(round.contestId)
      .getOrElse(throw new NoSuchElementException(s"Contest ${round.contestId} not found"))
    val template = ContestKind.monumentIdTemplate(contest)
    val pages = commons.filesByTitles(titles, info = true, text = true)
    val images = ImageText.imagesWithAuthors(pages, contest, template, commons)
    val found = images.map(_.pageId).toSet
    // by the listed titles and the fetched ones: a file renamed since is stored under either
    val stored = ImageJdbc.findByTitles((titles ++ images.map(_.title)).distinct).distinctBy(_.pageId)
    val storedById = stored.map(i => i.pageId -> i).toMap
    val textless = pages.filter(_.text.isEmpty).flatMap(_.id).toSet
    val refreshed = images.flatMap { i =>
      storedById.get(i.pageId).map(ImageService.withStoredText(i, _, text = !textless.contains(i.pageId), template.isDefined))
    }
    val storedTitles = stored.map(_.title).toSet
    // a missing title, or a page that isn't a file, unless stored
    val missing = pages.filterNot(_.id.exists(found.contains)).map(_.title).distinct.filterNot(storedTitles.contains)
    // a stored title is the stored file, as before, also when Commons now has another file
    // (another page id) of that name: the stored one was deleted there and a new one uploaded
    val fresh = images.filterNot(i => storedById.contains(i.pageId) || storedTitles.contains(i.title))
    ListChunk(index, titles.size, stored, fresh, ImageService.changedImages(refreshed, stored), missing)
  }

  // ── job creation, failure, cancel, retry ───────────────────────────────────

  /** The new job's state: freezes the previous rounds, in the creating transaction. A job
    * that only adds a pasted list (`listOnly`) doesn't read them: it starts with the list,
    * and freezes nothing.
    */
  def initialState(round: Round, listOnly: Boolean = false, distribute: Boolean = false,
      completingCreation: Boolean = true, reapply: Boolean = false)(implicit session: DBSession): JsObject =
    if (listOnly) Json.toJsObject(DistributionState(phase = Phase.List))
    else if (distribute) {
      // a distribute reads no votes: it freezes them only to close them at its end, as a creation
      // does (§6 "Job"); one that completes no creation leaves them as they are
      val prevIds = if (completingCreation) round.previousIds else Nil
      Round.freeze(prevIds)
      Json.toJsObject(DistributionState(phase = Phase.Assign, frozen = prevIds))
    } else {
      val prevIds = round.previousIds
      // a fill reads them
      Round.freeze(prevIds)
      val topN = prevIds.headOption.flatMap(_ => round.topImages)
      // a re-apply (P3-5) scans the round's files first (after the top-N's ranking)
      val first = if (topN.isDefined) Phase.Rank else if (reapply) Phase.Scan else Phase.Distribute
      Json.toJsObject(DistributionState(phase = first, frozen = prevIds))
    }

  override def onFailed(job: Job, ctx: StepContext): JsObject = end(job, ctx)

  /** [[end]]; a creation that never completed (the round still `distributing`) is `cancelled`
    * (round lineage §2.6: an admin cancelled its creation job), by the admin who cancelled it.
    */
  override def onCancelled(job: Job, ctx: StepContext): JsObject = {
    val ended = end(job, ctx)
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    // locked by end, which runs first
    val distributing = sql"SELECT distributing FROM round WHERE id = $roundId".map(_.boolean(1)).single().getOrElse(false)
    // cancelled by a person (the job page's Cancel): in the round's settings log (RoundChanges), with
    // the status from and to; a cancel without one (shutdown, a script) writes none
    if (distributing && cancelsCreation(job))
      RoundChanges.logged(roundId, ctx.by, ContestChangeJdbc.RoundChanged, RoundChanges.Path.Cancel) {
        Round.setStatus(roundId, Round.Status.Cancelled, ctx.by)
      }
    ended
  }

  /** Cancelling this job cancels its round's creation: a creation's job (or one finishing it). */
  protected def cancelsCreation(job: Job): Boolean =
    (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false) && !fromReview(job) && !distributeMode(job)

  /** Stores what Commons now has of the chunk's stored files that changed, as an import
    * does, with a former title of a renamed one.
    */
  private def updateStored(chunk: ListChunk)(implicit session: DBSession): Unit = {
    val storedTitles = chunk.stored.map(i => i.pageId -> i.title).toMap
    chunk.changed.foreach { image =>
      ImageJdbc.updateIn(image)
      storedTitles.get(image.pageId).filter(_ != image.title).foreach(ImageJdbc.recordRename(_, image.pageId))
    }
  }

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
    * images already in it are skipped, the missing ones added. After any other failure it
    * resumes from its cursor, and its next step moves `imagesBefore` by the change of the
    * files others added ([[DistributionState.othersBefore]], `rebase`): the round's count may
    * have changed meanwhile (a file removed from the round, a repair), and the old baseline
    * would fail the count check once for nothing. Its own files are checked exactly
    * ([[DistributionState.ownBefore]]).
    */
  override def onRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    forget(job.id) // the jurors and filters are read again
    val st = stateOf(job)
    val roundId = roundIdOf(job)
    // as initialState: a list-only fill reads none; a distribute that completes no creation freezes none
    val completing = (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false)
    val prevIds =
      if (listOnly(job) || (distributeMode(job) && !completing)) Nil
      else Round.findById(roundId).map(_.previousIds).getOrElse(Nil)
    // the round too, as creating the job locks it (RoundConflicts.disqualify)
    Round.lock((st.frozen ++ prevIds) :+ roundId)
    // a fill queued on a round in review (a re-apply, "Distribute new files" there) only while it still is:
    // distributed since, its files would go to no juror, and a re-apply would delete files of an open round
    if (fillOnly(job) && fromReview(job)) Round.findById(roundId).filterNot(_.inReview).foreach { round =>
      throw JobScheduler.RetryRefusal(ReviewGoneKey, Seq(round.number.toString))
    }
    // not while a previous round is having a juror added (its votes moving; its rebalance would fail
    // on the freeze below): refused, rolling the Retry back, the job staying failed
    if (!listOnly(job)) Round.findById(roundId).foreach { round =>
      try RoundDistributionHandler.requireNoPreviousRebalance(round)
      catch { case e: ExcludeRoundsRefused => throw JobScheduler.RetryRefusal(e.refusal.key, e.refusal.args) }
    }
    Round.release(st.frozen, close = false) // nothing, normally: a failure released them
    Round.freeze(prevIds)
    val resumed =
      if (!st.verifyFailed) st.copy(rebase = true)
      else
        st.copy(phase = if (distributeMode(job)) Phase.Assign else if (listOnly(job)) Phase.List
            else if (reapplyMode(job)) Phase.Scan else Phase.Distribute,
          cursor = 0L, candidatesSeen = 0L, pruned = st.pruned.copy(count = 0L),
          imagesAdded = 0L, rowsAdded = 0L, imagesBefore = None, othersBefore = None, ownBefore = None,
          rebase = false, verifyFailed = false, listIndex = 0, missing = 0L,
          missingTitles = Nil, coiLeftOut = 0L, coiTitles = Nil)
    Json.toJsObject(resumed.copy(frozen = prevIds))
  }

  /** `imagesBefore` moved by the change in the round's files this job didn't add. */
  private def rebaseline(st: DistributionState, roundId: Long, jobId: Long)(implicit session: DBSession) =
    (st.imagesBefore, st.othersBefore) match {
      case (Some(before), Some(others)) =>
        // the rows a re-apply pruned since the baseline aren't others' change (`removed` counts them)
        val now = RoundImageJdbc.countNotAddedBy(roundId, jobId) + st.removed
        st.copy(imagesBefore = Some(before + now - others), othersBefore = Some(now), rebase = false)
      case _ => st.copy(rebase = false)
    }

  // ── steps ──────────────────────────────────────────────────────────────────

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val roundId = roundIdOf(job)
    val c = contextOf(job) match {
      case Right(context) => context
      case Left(refusal)  => return refused(job, refusal)
    }
    val st = stateOf(job) match {
      case s if s.imagesBefore.isEmpty =>
        val total = imageCount(roundId)
        val own = RoundImageJdbc.countAddedBy(roundId, job.id)
        s.copy(imagesBefore = Some(total - s.imagesAdded + s.removed), othersBefore = Some(total - own + s.removed),
          ownBefore = Some(own - s.imagesAdded), rebase = false)
      // the first step after a Retry: what others changed since the baseline isn't a mismatch
      case s if s.rebase => rebaseline(s, roundId, job.id)
      case s             => s
    }

    st.phase match {
      case Phase.Rank =>
        // a re-apply ranks from scratch, the round's disqualified files skipped (D17)
        val n = c.topN.get
        def ranked(upTo: Int) = DistributionSource.rankRows(c.prevRounds.flatMap(_.id), c.votes, c.denominators, upTo,
          c.filter, c.topOrder, c.excluded, skipDqOf = Option.when(reapplyMode(job))(roundId))
        val rows = ranked(n + 1)
        // the backstop of the save's check (decided 2026-11-26): the votes frozen now, an N the round
        // form just checked (a new round's, a re-apply's: TopNCheckedParam) must not cut inside a tie
        // (by the score alone, RoundDistributionHandler.tieKey); else the job fails with the form's
        // message, nothing added, and N is chosen again. "Distribute new files" keeps a round's N,
        // chosen before the picker too
        val checked = (job.params \ TopNCheckedParam).asOpt[Boolean].getOrElse(false)
        (if (checked) RoundDistributionHandler.cutInTie(n, rows, ranked, c.topOrder, c.denominators.toMap) else None) match {
          case Some(error) => StepResult.Failed(error, Json.toJsObject(st), progress(st))
          case None =>
            val top = rows.take(n).map(_.pageId)
            val next = st.copy(phase = if (reapplyMode(job)) Phase.Scan else Phase.Distribute, ranked = top.sorted,
              total = Some(top.size.toLong + titleCountOf(job)), cursor = 0L)
            continue(next)
        }

      case Phase.Scan =>
        // a re-apply (P3-5, D16): the round's `fill` files from before this job, a window by page id,
        // checked against the filters as they are now; those that no longer pass are deleted, only
        // while the round has no vote (read under its lock, which every vote writer takes first).
        // `list`, `new_files`, `manual` and disqualified rows, and those this job adds, stay.
        RoundWrites.lock(Seq(roundId))
        if (RoundImageJdbc.hasVotes(roundId))
          StepResult.Failed(JobError(ReapplyVotedKey, Seq(c.round.number.toString)), Json.toJsObject(st), progress(st))
        else {
          val ids = RoundImageJdbc.filledWindow(roundId, job.id, st.cursor, c.windowSize)
          if (ids.isEmpty) continue(st.copy(phase = Phase.Distribute, cursor = 0L))
          else {
            val passing: Set[Long] =
              if (c.topN.isDefined) st.ranked.toSet
              else c.filter(candidates(c, roundId, st.cursor, ids.max, restrictTo = Some(ids))).map(_.pageId).toSet
            val gone = ids.filterNot(passing.contains)
            val titles =
              if (gone.isEmpty || st.removedTitles.size >= MaxMissingTitles) Nil
              else ImageJdbc.findByPageIds(gone.take(MaxMissingTitles - st.removedTitles.size)).values.map(_.title).toSeq.sorted
            beforePrune(c.round, gone)
            val pruned = RoundImageJdbc.prune(roundId, gone)
            if (pruned > 0) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
            continue(st.copy(cursor = ids.max,
              pruned = Pruned(st.removed + pruned, (st.removedTitles ++ titles).take(MaxMissingTitles),
                st.removedTotal + pruned)))
          }
        }

      case Phase.Distribute =>
        val st1 = if (st.total.isDefined) st else st.copy(total = Some(estimate(c, st) + titleCountOf(job)))
        val window = nextWindow(c, st1)
        if (window.size == 0)
          continue(st1.copy(phase = if (titleCountOf(job) > 0) Phase.List else Phase.Verify, cursor = 0L))
        else {
          // the round for update first (RoundWrites), so the candidates (and their anti-join on
          // the round's files) are read with every other structural writer of it held off;
          // then the files' current monument ids (h6)
          RoundWrites.lock(Seq(roundId))
          val read: Seq[Image] =
            if (c.topN.isDefined) DistributionSource.imagesIn(window.ids, roundId)
            else c.filter(candidates(c, roundId, st1.cursor, window.last)).map(_.image)
          val passed = withLockedMonumentIds(read)
          // a fill for review adds the files only (no votes); one pass gives them to the jurors too
          val rows = if (fillOnly(job)) Nil else DistributeImages.assign(c.round, passed, c.jurors, st1.position)
          beforeInsert(c.round, passed)
          if (fillOnly(job)) RoundImageJdbc.addFilled(roundId, passed.map(_.pageId), origin(job, listed = false))
          else DistributeImages.insertRows(rows, origin(job, listed = false))
          afterInsert(c.round, rows)
          if (passed.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          continue(
            st1.copy(
              cursor = window.last,
              position = st1.position + (if (fillOnly(job)) 0 else passed.size),
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
              // the round first (share mode), the lock order: updateStored locks the stored
              // files' images and their votes (monument ids), some in this round, before the
              // chunk's insert would lock the round; a criteria change (the round for update,
              // then its votes) deadlocked with that
              // a structural write of this round and, through the changed stored files' monument
              // ids, of every round those files are in: all those rounds for update first
              // (RoundWrites), then the changed files in page order (h2), then the rest
              RoundWrites.lockForFiles(chunk.changed.map(_.pageId), Seq(roundId))
              RoundWrites.lockFiles(chunk.changed.map(_.pageId))
              ImageJdbc.insertIgnore(chunk.fetched)
              updateStored(chunk)
              if (chunk.changed.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateMonumentIds())
              val listed = withLockedMonumentIds(
                DistributionSource.imagesIn((chunk.stored ++ chunk.fetched).map(_.pageId).distinct.sorted, roundId))
              // a round that leaves out conflicts of interest leaves them out of its list too
              val conflicts = listed.flatMap(image => CoiMembers.matchOf(c.coi)(image.author).map(image -> _))
              val conflictIds = conflicts.map(_._1.pageId).toSet
              val passed = listed.filterNot(i => conflictIds.contains(i.pageId))
              val rows = if (fillOnly(job)) Nil else DistributeImages.assign(c.round, passed, c.jurors, st1.position)
              beforeInsert(c.round, passed)
              if (fillOnly(job)) RoundImageJdbc.addFilled(roundId, passed.map(_.pageId), origin(job, listed = true))
              else DistributeImages.insertRows(rows, origin(job, listed = true))
              afterInsert(c.round, rows)
              if (passed.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
              continue(
                st1.copy(
                  listIndex = chunk.index + chunk.titles,
                  position = st1.position + (if (fillOnly(job)) 0 else passed.size),
                  candidatesSeen = st1.candidatesSeen + chunk.titles,
                  imagesAdded = st1.imagesAdded + passed.size,
                  rowsAdded = st1.rowsAdded + rows.size,
                  missing = st1.missing + chunk.missingTitles.size,
                  missingTitles = (st1.missingTitles ++ chunk.missingTitles).take(MaxMissingTitles),
                  coiLeftOut = st1.coiLeftOut + conflicts.size,
                  coiTitles = (st1.coiTitles ++ conflicts.map { case (i, m) => s"${i.title} (${m.describe})" })
                    .take(MaxMissingTitles)
                )
              )
          }

      case Phase.Verify =>
        val count = imageCount(roundId)
        val expected = st.imagesBefore.getOrElse(0L) + st.imagesAdded - st.removed
        // its own files exactly: what it added is all there, whatever others changed
        val own = st.ownBefore.map(_ => RoundImageJdbc.countAddedBy(roundId, job.id))
        val ownExpected = st.ownBefore.map(_ + st.imagesAdded)
        val reported =
          if (count != expected) Some(expected)
          else if (own != ownExpected) Some(count - own.get + ownExpected.get) // the total its own files should give
          else None
        if (reported.isDefined)
          StepResult.Failed(
            JobError(JobError.CountMismatchKey, Seq(roundId, count, reported.get).map(_.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)),
            progress(st)
          )
        else if (fillOnly(job)) endFill(job, ctx, st)
        else complete(job, ctx, st, c)

      case Phase.Assign =>
        val st1 = if (st.total.isDefined) st else st.copy(total = Some(RoundImageJdbc.undistributedCount(roundId)))
        // the round for update first (RoundWrites): its files and their votes read with every other
        // structural writer held off; then the files' current monument ids (h6)
        RoundWrites.lock(Seq(roundId))
        val ids = RoundImageJdbc.undistributedWindow(roundId, st1.cursor, c.windowSize)
        // then, if ticked, the files waiting for a juror (round-editing plan §7)
        if (ids.isEmpty)
          continue(st1.copy(phase = if (topUpWaiting(job) && c.round.distribution > 0) Phase.TopUp else Phase.Complete, cursor = 0L))
        else {
          val found = ImageJdbc.findByPageIds(ids)
          val images = withLockedMonumentIds(ids.flatMap(found.get))
          val rows = DistributeImages.assign(c.round, images, c.jurors, st1.position)
          beforeInsert(c.round, images)
          DistributeImages.insertRows(rows, origin(job, listed = false))
          afterInsert(c.round, rows)
          if (rows.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
          continue(st1.copy(cursor = ids.max, position = st1.position + images.size,
            candidatesSeen = st1.candidatesSeen + ids.size, imagesAdded = st1.imagesAdded + images.size,
            rowsAdded = st1.rowsAdded + rows.size))
        }

      case Phase.TopUp => topUp(job, ctx, st, c)

      case Phase.Complete =>
        // per job (§6 "Count checks"): every file this round still has to give is given now, read
        // under the round's lock (every writer of its files holds it)
        RoundWrites.lock(Seq(roundId))
        val left = RoundImageJdbc.undistributedCount(roundId)
        // a file restored on Commons below the cursor meanwhile: once more over the round
        if (left > 0 && !st.rescanned) continue(st.copy(phase = Phase.Assign, cursor = 0L, passes = st.passes.copy(rescanned = true)))
        else if (left > 0)
          StepResult.Failed(
            JobError(JobError.CountMismatchKey, Seq(roundId, left, 0L).map(_.toString)),
            Json.toJsObject(st.copy(verifyFailed = true)),
            progress(st)
          )
        else complete(job, ctx, st, c)

      case other => throw new IllegalStateException(s"Job ${job.id}: unknown phase $other")
    }
  }

  /** One step of "Also give out the waiting files" (round-editing plan §7, opt-in): a keyset window of
    * the round's files waiting for a juror (`in`, not deleted on Commons, fewer vote rows than its
    * jurors per file), each given its missing jurors from the round's active jurors with no row of it in
    * `vote` or `vote_aside`, the least loaded (unrated files) first. The files' rows keep their origin
    * ([[RoundImageJdbc.addVoted]]); the rows given are counted apart (`toppedUp`, `topUpRows`). At the
    * end the count check: no waiting file is left that an active juror could take (one more pass for a
    * file restored meanwhile), then "complete".
    */
  private def topUp(job: Job, ctx: StepContext, st: DistributionState, c: Context)(implicit session: DBSession): StepResult = {
    val roundId = roundIdOf(job)
    val perFile = c.round.distribution
    val jurorIds = c.jurors.map(_.getId)
    RoundWrites.lock(Seq(roundId))
    val window = RoundReassign.waitingWindow(roundId, perFile, st.cursor, c.windowSize)
    if (window.isEmpty) {
      val left = RoundReassign.waitingTakeable(roundId, perFile, jurorIds)
      if (left > 0 && !st.passes.topUpRescanned) continue(st.copy(cursor = 0L, passes = st.passes.copy(topUpRescanned = true)))
      else if (left > 0)
        StepResult.Failed(JobError(JobError.CountMismatchKey, Seq(roundId, left, 0L).map(_.toString)),
          Json.toJsObject(st.copy(verifyFailed = true)), progress(st))
      else continue(st.copy(phase = Phase.Complete, cursor = 0L))
    } else {
      val pages = window.map(_._1)
      val found = ImageJdbc.findByPageIds(pages)
      val images = withLockedMonumentIds(pages.flatMap(found.get)).map(i => i.pageId -> i).toMap
      val holders = RoundReassign.holders(roundId, pages, jurorIds)
      val load = scala.collection.mutable.Map.from(RoundRebalanceHandler.unratedCounts(roundId, jurorIds))
      val rows = window.flatMap { case (page, count) =>
        images.get(page).toSeq.flatMap { image =>
          val eligible = c.jurors.filterNot(j => holders.getOrElse(page, Set.empty[Long]).contains(j.getId))
            .sortBy(j => (load.getOrElse(j.getId, 0L), j.getId)).take(perFile - count)
          eligible.foreach(j => load.update(j.getId, load.getOrElse(j.getId, 0L) + 1))
          eligible.map(j => Vote(image, j, c.round))
        }
      }
      DistributeImages.insertRows(rows, RoundImageJdbc.Origin.Manual)
      if (rows.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(roundId))
      continue(st.copy(cursor = pages.max, passes = st.passes.copy(toppedUp = st.passes.toppedUp + rows.map(_.pageId).distinct.size,
        topUpRows = st.passes.topUpRows + rows.size)))
    }
  }

  /** The end of a fill: the previous rounds released (not closed: D18), a round being created
    * `paused`, held in review, still hidden from its jurors ([[Round.inReview]]). With
    * `distributeAfter` (D4, or "Distribute new files" on a distributed round) the `distribute` job
    * is queued as this one ends, in its transaction (its previous rounds frozen again by it).
    */
  private def endFill(job: Job, ctx: StepContext, st: DistributionState)(implicit session: DBSession): StepResult = {
    val roundId = roundIdOf(job)
    // the distribute it queues freezes the previous rounds only to close them, so only for a creation
    val creating = (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false)
    val prevIds =
      if (distributeAfter(job) && creating) Round.findById(roundId).map(_.previousIds).getOrElse(Nil) else Nil
    val distributing = lockRounds((st.frozen ++ prevIds) :+ roundId).getOrElse(roundId, false)
    Round.release(st.frozen, close = false)
    if (distributing) Round.setStatus(roundId, Round.Status.Paused, job.createdBy)
    settleFiles(roundId)
    ctx.afterCommit(RoundImageCounts.invalidate(roundId))
    // the previous rounds' votes as this fill read them (they were frozen while it ran): the review
    // page warns when they change before Distribute (D18)
    // only for a round held in review (one distributed after the fill, or already open, never reads it)
    val fingerprint =
      if (!distributing || distributeAfter(job)) None
      else prevFingerprint(Round.findById(roundId).map(_.previousIds).getOrElse(Nil))
    // its state as initialState gives it, the freeze done only once the scheduler has queued it: if
    // another job held the key, nothing is left frozen (the round stays held in review)
    val followUp = Option.when(distributeAfter(job))(
      NewJob(kind = Kind, contestId = job.contestId, subjectType = job.subjectType, subjectId = job.subjectId,
        dedupeKey = job.dedupeKey, params = distributeParams(job),
        state = Json.toJsObject(DistributionState(phase = Phase.Assign, frozen = prevIds)),
        progress = Progress(unit = Some("images")), createdBy = job.createdBy))
    logger.info(s"Round $roundId: selected ${st.imagesAdded} images" +
      (if (reapplyMode(job)) s" (filters re-applied: ${st.removed} no longer passing removed)" else "") +
      (if (titleCountOf(job) > 0) s", with a list of ${titleCountOf(job)} files (${st.missing} not found)" else "") +
      (if (followUp.isDefined) "; distributing them next" else if (distributing) "; held for review" else ""))
    StepResult.Done(Json.toJsObject(st.copy(frozen = Nil, prevFingerprint = fingerprint)), progress(st), followUp,
      onFollowUpQueued = s => Round.freeze(prevIds)(s))
  }

  /** The end of a one-pass job or of a distribute: a creation shown to its jurors (`active`), its
    * previous rounds closed; anything else restores them.
    */
  private def complete(job: Job, ctx: StepContext, st: DistributionState, c: Context)(implicit
      session: DBSession): StepResult = {
    val roundId = roundIdOf(job)
    val distributing = lockRounds(st.frozen :+ roundId).getOrElse(roundId, false)
    val creating = distributing || (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false)
    // who queued it (the creator, the reviewer who distributed, a reinstating admin): the status's who
    if (distributing) Round.completeCreation(roundId, job.createdBy)
    val openBefore = if (creating) Round.openIds(st.frozen) else Set.empty[Long]
    Round.release(st.frozen, close = creating)
    // previous rounds closed by the creation of one by an account that judges them: logged
    val jurorChanges = RoundJurorChanges.record(ContestChangeJdbc.RoundJurorClosed,
      (openBefore -- Round.openIds(openBefore.toSeq)).toSeq.sorted, job.createdBy, Some(roundId))
    ctx.afterCommit(RoundJurorChanges.log(jurorChanges))
    settleFiles(roundId) // after the rounds' updates: rounds before votes, as DeletedFiles locks them
    val visible = visibleImageCount(roundId)
    ctx.afterCommit(RoundImageCounts.refresh(roundId)(visible.toInt))
    logger.info(
      s"Round $roundId: distributed ${st.imagesAdded} images (${st.rowsAdded} vote rows) " +
        (if (topUpWaiting(job)) s"and topped up ${st.passes.toppedUp} waiting files (${st.passes.topUpRows} vote rows) " else "") +
        s"to ${c.jurors.size} jurors" +
        (if (c.prevRounds.nonEmpty && !listOnly(job))
           s" from previous rounds [${c.prevRounds.flatMap(_.id).mkString(", ")}]"
         else "") +
        (if (titleCountOf(job) > 0)
           s", with a list of ${titleCountOf(job)} files (${st.missing} not found" +
             (if (st.coiLeftOut > 0) s", ${st.coiLeftOut} left out as conflicts of interest" else "") + ")"
         else "")
    )
    StepResult.Done(Json.toJsObject(st.copy(frozen = Nil)), progress(st))
  }

  /** The images with their monument ids as committed now, their rows read in share mode in
    * page id order (h6): the vote rows built from them carry the copy (`vote.monument_id`), and
    * a monument change committing after a plain read would otherwise leave the new rows with
    * the old id. Called after the round's lock (round, then images).
    */
  private def withLockedMonumentIds(images: Seq[Image])(implicit session: DBSession): Seq[Image] =
    if (images.isEmpty) images
    else {
      val ids = images.map(_.pageId).distinct.sorted
      val current = ids.grouped(2000).flatMap { chunk =>
        sql"SELECT page_id, monument_id FROM images WHERE page_id IN ($chunk) ORDER BY page_id LOCK IN SHARE MODE"
          .map(rs => rs.long(1) -> rs.stringOpt(2)).list()
      }.toMap
      images.map(i => current.get(i.pageId).fold(i)(m => i.copy(monumentId = m)))
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
  private def lockRounds(ids: Seq[Long])(implicit session: DBSession): Map[Long, Boolean] = {
    val locked = sql"SELECT id, distributing FROM round WHERE id IN (${ids.distinct.sorted}) ORDER BY id FOR UPDATE"
      .map(rs => rs.long(1) -> rs.boolean(2))
      .list()
      .toMap
    RoundWrites.recordRounds(ids)
    locked
  }

  private def continue(st: DistributionState) = StepResult.Continue(Json.toJsObject(st), progress(st))

  private def progress(st: DistributionState) =
    Progress(done = Some(st.candidatesSeen), total = st.total, unit = Some("images"))

  /** The round's images for the count check: also those with votes moved aside (V63), so
    * moving votes aside or back doesn't change it: its rows in round_image (V84), whatever
    * their status, one primary key range.
    */
  private def imageCount(roundId: Long)(implicit session: DBSession): Long = RoundImageJdbc.countAll(roundId)

  /** The round's images the galleries show: those `in` it. */
  private def visibleImageCount(roundId: Long)(implicit session: DBSession): Long = RoundImageJdbc.countIn(roundId)

  /** How this job adds files to the round ([[RoundImageJdbc.Origin]]): a creation's by its
    * filters (`fill`) or its pasted list (`list`); "Distribute new files" on a round created
    * before (`new_files`, its list `list`).
    */
  private def origin(job: Job, listed: Boolean): RoundImageJdbc.Origin = {
    // files a round in review gets from "Distribute new files" are new files (§6), not its fill
    // a re-apply's files are the round's fill (P3-5): a later re-apply checks them as such
    val creating = (job.params \ "completingCreation").asOpt[Boolean].getOrElse(false) && !fromReview(job)
    val how =
      if (listed) RoundImageJdbc.AddedHow.List
      else if (creating || reapplyMode(job)) RoundImageJdbc.AddedHow.Fill
      else RoundImageJdbc.AddedHow.NewFiles
    RoundImageJdbc.Origin(how, Some(job.id))
  }

  private def estimate(c: Context, st: DistributionState)(implicit session: DBSession): Long =
    if (c.topN.isDefined) st.ranked.size.toLong
    else if (c.prevRounds.nonEmpty) DistributionSource.previousCount(c.prevRounds.flatMap(_.id), c.votes, c.excluded)
    else c.categoryId.fold(0L)(DistributionSource.categoryCount(_, c.excluded))

  private def nextWindow(c: Context, st: DistributionState)(implicit session: DBSession): Window =
    if (c.topN.isDefined) {
      val ids = st.ranked.iterator.filter(_ > st.cursor).take(c.windowSize).toSeq
      Window(ids.size, ids.lastOption.getOrElse(st.cursor), ids)
    } else if (c.prevRounds.nonEmpty)
      DistributionSource.previousWindow(c.prevRounds.flatMap(_.id), c.votes, st.cursor, c.windowSize, c.excluded)
    else c.categoryId.fold(Window.empty)(DistributionSource.categoryWindow(_, st.cursor, c.windowSize, c.excluded))

  /** The candidates in (from, to], those already in the round left out; with `restrictTo`, only those
    * files, whether in the round or not (a re-apply's scan checks the round's own files against the
    * filters).
    */
  private def candidates(c: Context, roundId: Long, from: Long, to: Long, restrictTo: Option[Seq[Long]] = None)(implicit
      session: DBSession
  ): Seq[ImageWithRating] =
    if (c.prevRounds.nonEmpty)
      DistributionSource.previousCandidates(c.prevRounds.flatMap(_.id), c.votes, from, to, roundId, c.excluded, restrictTo)
    else c.categoryId.fold(Seq.empty[ImageWithRating])(
      DistributionSource.categoryCandidates(_, from, to, roundId, c.excluded, restrictTo))

  /** Whatever a distribution of the round would add now ("Count new files"), without
    * adding it: the same windows and filters as the job, read only.
    */
  def countNew(roundId: Long): Long = {
    // its category rule's files first, outside the session (Commons may take a while), cached briefly
    val categoryIds = Round.findById(roundId).map(pickerCategoryIds)
    countNewIn(roundId, categoryIds)
  }

  private def countNewIn(roundId: Long, categoryIds: Option[(Iterable[Long], Iterable[Long])]): Long =
    DB.readOnly { implicit session =>
    // "Count new files": as a distribution queued now would, by the previous round's rule
    val c = context(roundId, None, countOnly = true, categoryIds = categoryIds)
    if (c.topN.isDefined) {
      val ranked = DistributionSource.rank(c.prevRounds.flatMap(_.id), c.votes, c.denominators, c.topN.get, c.filter,
        c.topOrder, c.excluded)
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

  /** The cut picker's ranking (regional winners plan §10.4): every file a top-N of `round` (a stored
    * round, or the round form's settings as posted) would rank, as its distribution would rank them now
    * ([[DistributionSource.rankAll]]: the same previous rounds' rows, rank key and per-image filters;
    * a re-apply's round's disqualified files skipped, `skipDqOf`), read only. The first `keep` files
    * whole; of all, their rank keys and scores. None without previous rounds.
    */
  def topNRanking(round: Round, keep: Int, skipDqOf: Option[Long] = None): Option[TopNRanking] =
    Option.when(round.previousIds.nonEmpty) {
      // its category rule's files first, outside the session (Commons may take a while), cached briefly
      val categoryIds = pickerCategoryIds(round)
      DB.readOnly { implicit session =>
      val c = contextFor(round, None, None, countOnly = true, distribute = false, checkPrevious = false,
        checkJurors = false, categoryIds = Some(categoryIds))
      // a yes/no source's order: the round's, else the default, as a save stores it with a top N
      // (Round.withExplicitTopOrder: a new round and a re-apply never keep the legacy rule)
      val order = c.prevRounds.headOption.filter(_.isBinary).map(_ => round.topBinaryOrder.getOrElse(BinaryOrder.Default))
      val denominators = c.denominators.toMap
      val (files, summaries) = DistributionSource.rankAll(c.prevRounds.flatMap(_.id), c.votes, c.denominators, c.filter,
        order, c.excluded, skipDqOf, keep) { row =>
        val key = RoundDistributionHandler.tieKey(row, order, denominators)
        (key, if (order.isDefined) 0.0 else key.head)
      }
      TopNRanking(c.prevRounds, order, files, summaries.map(_._1), summaries.map(_._2))
      }
    }

  /** The files of `round`'s category rule (its first and second operands) for the cut picker, the
    * save's check of its top N and "Count new files": read from Commons before their database session,
    * kept for a few minutes ([[RoundDistributionHandler.CategoryIdsTtlNanos]]), so the picker's clicks
    * and the save after them read Commons once. A job's fill reads Commons itself, uncached. Commons
    * failing: [[RoundDistributionHandler.CategoryFilesUnavailable]].
    */
  private[distribution] def pickerCategoryIds(round: Round): (Iterable[Long], Iterable[Long]) = {
    val rule = round.categoryRule
    rule.requireValid()
    (cachedCategoryIds(rule.first), cachedCategoryIds(rule.second))
  }

  private def cachedCategoryIds(category: Option[String]): Iterable[Long] =
    category.map(_.trim).filter(_.nonEmpty).fold[Iterable[Long]](Nil) { name =>
      val now = System.nanoTime()
      def fresh(at: Long) = now - at < RoundDistributionHandler.CategoryIdsTtlNanos
      categoryIdsCache.get(name).filter { case (at, _) => fresh(at) } match {
        case Some((_, ids)) => ids
        case None =>
          val ids =
            try distributeImages.categoryFileIds(Some(name)).toSet
            catch { case NonFatal(e) => throw RoundDistributionHandler.CategoryFilesUnavailable(name, e) }
          // bounded: the expired entries go, then the oldest above the limit
          categoryIdsCache.filterInPlace { case (_, (at, _)) => fresh(at) }
          categoryIdsCache.toSeq.sortBy(_._2._1).dropRight(RoundDistributionHandler.CategoryIdsMax - 1)
            .foreach { case (k, _) => categoryIdsCache.remove(k) }
          categoryIdsCache.put(name, (now, ids))
          ids
      }
    }

  /** [[pickerCategoryIds]]' cache: a category's name -> (when read, its files' page ids). */
  private val categoryIdsCache = TrieMap.empty[String, (Long, Set[Long])]
}

object RoundDistributionHandler {

  /** A row's score in its round, as [[DistributionSource.rank]]'s SQL computes it: the rate sum,
    * below 0 as 0, over the round's denominator.
    */
  def rowScore(row: DistributionSource.RankRow, d: Denominator): Double = {
    val sum = math.max(row.rateSum, 0L).toDouble
    d match {
      case Denominator.Const(n)      => if (n == 0) 0.0 else sum / n
      case Denominator.PerImage      => if (row.count == 0) 0.0 else sum / row.count
      case Denominator.PerImageRated => if (row.rated == 0) 0.0 else sum / row.rated
    }
  }

  /** A ranked row's key in the top N's ties (decided 2026-11-26: the score alone): two files of the same
    * key tie, and no N cuts between them. A yes/no source ranked by `order`: its level, the order's
    * values of its approvals and rejections; else (a rated source, or the legacy rule): its score
    * ([[rowScore]]) alone, not its sum, by which the fill orders files of one score. The cut picker,
    * the save's check of a top N (through it) and the job's Rank step use this one function.
    */
  def tieKey(row: DistributionSource.RankRow, order: Option[BinaryOrder], denominators: Map[Long, Denominator]): Seq[Double] =
    (order, row.tally) match {
      case (Some(o), Some((approvals, rejections))) => o.values(approvals, rejections).map(_.toDouble)
      case _ => Seq(rowScore(row, denominators.getOrElse(row.roundId, Denominator.Const(0))))
    }

  /** A top N of `n` cutting inside a tie of the ranked `rows` (the first n + 1, at least): the
    * refusal the round form gives ([[services.TopNCandidates.Result.refusal]]: "N = n is inside a tie
    * (places a–b); choose …"), the tie read to its end (`more` ranks further, doubling); None if file n
    * and file n + 1 differ in [[tieKey]] (or there is no file n + 1).
    */
  def cutInTie(n: Int, rows: Seq[DistributionSource.RankRow], more: Int => Seq[DistributionSource.RankRow],
      order: Option[BinaryOrder], denominators: Map[Long, Denominator]): Option[JobError] = {
    def key(row: DistributionSource.RankRow) = tieKey(row, order, denominators)
    if (n < 1 || rows.sizeIs <= n || key(rows(n - 1)) != key(rows(n))) None
    else {
      val tie = key(rows(n - 1))
      var read = rows
      var exhausted = false
      while (!exhausted && key(read.last) == tie) {
        val wanted = read.size * 2
        read = more(wanted)
        exhausted = read.sizeIs < wanted
      }
      val keys = read.map(key).toIndexedSeq
      services.TopNCandidates.Result(keys, keys.map(_ => 0.0), services.TopNCandidates.Window.main, Nil).refusal(n)
        .map(msg => JobError(msg.key, msg.args.map(_.toString)))
    }
  }

  /** A job's param: its round's top N was checked by the round form (a new round's, a re-apply's), so
    * its Rank step checks it again ([[cutInTie]]).
    */
  val TopNCheckedParam = "topNChecked"

  /** How long the cut picker's category files are kept ([[RoundDistributionHandler.pickerCategoryIds]]). */
  val CategoryIdsTtlNanos: Long = 5L * 60 * 1000 * 1000 * 1000

  /** The categories kept at most. */
  val CategoryIdsMax = 20

  /** Commons didn't give a category filter's files (down, slow, refused): the cut picker and the save's
    * check of a top N say so, not a server error.
    */
  final case class CategoryFilesUnavailable(category: String, cause: Throwable)
      extends RuntimeException(s"Commons didn't give the files of $category: ${cause.getClass.getSimpleName}", cause)

  /** The cut picker's ranking ([[RoundDistributionHandler.topNRanking]]): the previous rounds, the
    * order of yes/no ones (None: rated, or the legacy rule), the first files whole, and every file's
    * key ([[tieKey]]: equal keys tie) and score (a rated row's; 0 for a yes/no order's).
    */
  final case class TopNRanking(prevRounds: Seq[Round], order: Option[BinaryOrder],
      files: Seq[DistributionSource.RankedFile], keys: IndexedSeq[Seq[Double]], scores: IndexedSeq[Double])


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

  /** Which votes of the previous rounds pick their files, each round's on its own (a file
    * qualifies in any of its rounds): with `rated` ("selected by at least" set) only approvals (a
    * rated round's: any vote) are read; with `rejectedBy` ("exclude files rejected by at least
    * N") no file's round with N or more rejections in it (a yes/no round's "no", a rated round's
    * ✗). `grades`: the previous rounds are rated, their sums of
    * grades (a ✗ as 0) and their rated votes any vote, as the in-memory path reads them
    * (`SelectionQuery.grades`).
    */
  final case class PrevVotes(rated: Boolean = false, rejectedBy: Option[Int] = None, grades: Boolean = false,
      rejectedMode: RejectedMode = RejectedMode.Default)

  /** What the steps of one job need besides its state: built by `prepare`. */
  final case class Context(
      round: Round,
      prevRounds: Seq[Round],
      jurors: IndexedSeq[User],
      perImage: Int,
      filter: Seq[ImageWithRating] => Seq[ImageWithRating],
      coi: Map[String, CoiMembers.Member],
      votes: PrevVotes,
      topN: Option[Int],
      topOrder: Option[BinaryOrder],
      categoryId: Option[Long],
      // each previous round's: a file's row of a round is scored in it
      denominators: Seq[(Long, Denominator)],
      // "Leave out files that are in round(s)" (V95): their files are no candidates, in every query
      excluded: Seq[Long],
      windowSize: Int
  )

  /** The files of `titles` titles of a job's pasted list, from the `index`th: those stored,
    * those fetched from Commons (to insert), those stored that Commons has otherwise now (to
    * update), and the titles found nowhere.
    */
  final case class ListChunk(
      index: Int,
      titles: Int,
      stored: Seq[Image],
      fetched: Seq[Image],
      changed: Seq[Image],
      missingTitles: Seq[String]
  )

  object Phase {
    val Rank = "rank"
    val Distribute = "distribute"
    val List = "list"
    val Verify = "verify"
    val Assign = "assign"
    val Complete = "complete"
    /** A re-apply (P3-5): the round's own `fill` files checked against the filters, before the fill. */
    val Scan = "scan"
    /** A distribute's "Also give out the waiting files" (round-editing plan §7): after "assign". */
    val TopUp = "topup"
  }

  /** "Also give out the files waiting for a juror" was ticked with "Distribute new files" (§7). */
  def topUpWaiting(job: Job): Boolean = (job.params \ "topUpWaiting").asOpt[Boolean].getOrElse(false)

  /** The job's pasted list of files to add to the round (`params.titles`), if it has one. */
  def titlesOf(job: Job): Seq[String] = (job.params \ "titles").asOpt[Seq[String]].getOrElse(Nil)

  /** At most `n` of the pasted titles, from the `from`th. */
  def titlesOf(job: Job, from: Int, n: Int): Seq[String] =
    (job.params \ "titles").asOpt[JsArray].fold(Seq.empty[String])(_.value.slice(from, from + n).map(_.as[String]).toSeq)

  def titleCountOf(job: Job): Int = (job.params \ "titleCount").asOpt[Int].getOrElse(0)

  /** The rule the job's rated previous round scored its files by when it was queued
    * (`params.settings.prevAverageRule`, RoundService): None for a job queued before V90.
    */
  def averageRuleOf(job: Job): Option[AverageRule] =
    (job.params \ "settings" \ "prevAverageRule").asOpt[String].flatMap(AverageRule.parse)

  /** The rule a job's rated previous rounds score their files by: the one it recorded, else, for
    * a job queued before V90 (resumed across the deploy), [[AverageRule.Assigned]], the rule its
    * distribution applied before V90 (the rate sum over each file's vote rows). Not the round's
    * own rule, which V90 sets to `rated`: a job resumed across the deploy would apply two rules.
    * (A former `optional_rate` source's rule, the sum over its active jurors, isn't reproduced:
    * such a distribution finishes before the deploy, round images §17's deploy note.)
    */
  def jobAverageRule(job: Job): AverageRule = averageRuleOf(job).getOrElse(AverageRule.Assigned)

  /** The rule each of a job's rated previous rounds scores its files by: the one recorded for it
    * (`params.settings.prevAverageRules`, by round id, RoundService), else [[jobAverageRule]] (a
    * job queued before the rules were recorded per round, triage R196-1: its one rule for all).
    */
  def jobAverageRules(job: Job): Long => AverageRule = {
    val perRound = (job.params \ "settings" \ "prevAverageRules").asOpt[Map[String, String]].getOrElse(Map.empty)
    val fallback = jobAverageRule(job)
    roundId => perRound.get(roundId.toString).flatMap(AverageRule.parse).getOrElse(fallback)
  }

  /** How the job applies "rejected by at least N" (`params.settings.prevRejectedMode`, recorded by
    * RoundService when queued, V93): a job queued before V93 excludes those files, the filter's
    * meaning then.
    */
  def jobRejectedMode(job: Job): RejectedMode =
    RejectedMode.fromStored((job.params \ "settings" \ "prevRejectedMode").asOpt[String])

  /** The mode a context applies: the job's recorded one, else ("Count new files") the round's. */
  private def jobMode(recorded: Option[RejectedMode], round: Round): RejectedMode =
    recorded.getOrElse(round.prevRejectedMode)

  /** The job only adds its list: the round's filters choose nothing. */
  def listOnly(job: Job): Boolean = (job.params \ "listOnly").asOpt[Boolean].getOrElse(false)

  /** A job's step of the two (P3): `params.mode`, None for a job queued before P3 (one pass). */
  object Mode {
    val Fill = "fill"
    val Distribute = "distribute"
  }

  def modeOf(job: Job): Option[String] = (job.params \ "mode").asOpt[String]

  /** A fill (P3): it adds the files with no votes; a distribute gives them out after it. */
  def fillOnly(job: Job): Boolean = modeOf(job).contains(Mode.Fill)

  /** A fill that queues the distribute as it ends ("Distribute as soon as ready", D4). */
  def distributeAfter(job: Job): Boolean = (job.params \ "distributeAfter").asOpt[Boolean].getOrElse(false)

  /** The distribute job a fill queues: its params, mode `distribute`, without the fill's list. */
  def distributeParams(job: Job): JsObject =
    (job.params - "distributeAfter" - "titles" - "titleCount" - "listOnly" - "fromReview" - "reapply") ++
      Json.obj("mode" -> Mode.Distribute)

  /** The review's Distribute: mode `distribute`. */
  def distributeMode(job: Job): Boolean = modeOf(job).contains(Mode.Distribute)

  /** Queued on a round in review ("Distribute new files" there): its end leaves it in review. */
  def fromReview(job: Job): Boolean = (job.params \ "fromReview").asOpt[Boolean].getOrElse(false)

  /** A fill re-applying the round's filters in review (P3-5): it scans the round's `fill` files
    * first and deletes those that no longer pass (D16).
    */
  def reapplyMode(job: Job): Boolean = (job.params \ "reapply").asOpt[Boolean].getOrElse(false)

  /** A Retry refused: the review fill's round is no longer in review. */
  val ReviewGoneKey = "job.error.review.not.in.review"

  /** A re-apply refused at its scan: the round has a vote now (D16: no file is deleted then). */
  val ReapplyVotedKey = "job.error.reapply.voted"

  /** The previous rounds' votes as a short text (their count, rate sum and a checksum of each
    * vote's file, juror, round and rate), None without previous rounds: a fill records it as it
    * ends, the review page compares it with the current one (D18). One read of their votes.
    */
  def prevFingerprint(prevIds: Seq[Long])(implicit session: DBSession = AutoSession): Option[String] =
    if (prevIds.isEmpty) None
    else
      sql"""SELECT COUNT(*), COALESCE(SUM(rate), 0),
                   COALESCE(BIT_XOR(CRC32(CONCAT_WS(':', page_id, jury_id, round_id, COALESCE(rate, 0)))), 0)
            FROM vote WHERE round_id IN (${prevIds.distinct.sorted})"""
        .map(rs => s"${rs.long(1)}:${rs.long(2)}:${rs.long(3)}").single()

  def roundIdOf(job: Job): Long =
    (job.params \ "roundId").asOpt[Long].orElse(job.subjectId).getOrElse(
      throw new IllegalStateException(s"Job ${job.id}: no round"))

  def stateOf(job: Job): DistributionState =
    job.state.validate[DistributionState].getOrElse(DistributionState(Phase.Distribute))

  def imagesAdded(job: Job): Long = stateOf(job).imagesAdded

  /** The round's previous rounds, in its order, from its own contest only (another
    * contest's rounds would be frozen and their images copied), all of one rating, the same
    * scale ([[Round.sameRateType]]).
    */
  def previousRounds(round: Round): Seq[Round] = {
    val ids = round.previousIds
    val found = if (ids.isEmpty) Nil else Round.findByIds(round.contestId, ids)
    val prevRounds = ids.flatMap(id => found.find(_.id.contains(id)))
    require(
      Round.sameRateType(prevRounds),
      s"previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] must all have the same rating (one scale, as many criteria)"
    )
    require(
      ids.size == prevRounds.size,
      s"previous rounds [${ids.mkString(", ")}] not all found: got [${prevRounds.flatMap(_.id).mkString(", ")}]"
    )
    prevRounds
  }

  /** The rounds whose files the round leaves out ([[Round.excludeRounds]], V95), checked as the round
    * form checks them ([[excludedRoundsError]]). A round deleted since its creation fails the
    * distribution rather than leaving its files in. `checkBusy`: also refuse one being distributed
    * now (all but "Count new files").
    */
  def excludedRounds(round: Round, checkBusy: Boolean = true): Seq[Long] = {
    val ids = round.excludeRounds
    // a stored round's ids may name a round deleted since (by SQL): said so; a new one's are as posted
    excludedRoundsError(round, Round.findByIdsInAnyContest, if (checkBusy) distributingNow else _ => false,
      tellMissing = round.id.isDefined)
      .foreach { refusal =>
        val text = refusal.key match {
          case ExcludeRoundsSelf     => s"round ${round.id.orNull} can't leave out its own files"
          case ExcludeRoundsPrevious =>
            s"rounds to leave out [${ids.mkString(", ")}] include one of its previous rounds [${round.previousIds.mkString(", ")}]"
          case ExcludeRoundsBusy     =>
            s"round ${refusal.args.mkString} to leave out is being distributed, or its creation failed: " +
              "finish or retry its distribution first"
          case ExcludeRoundsMissing  =>
            s"round ${refusal.args.mkString}, left out by round ${round.id.orNull}, no longer exists: a database admin " +
              "must remove it from the round's exclude_rounds"
          case _ => s"rounds to leave out [${ids.mkString(", ")}] must be other rounds of contest ${round.contestId}"
        }
        // a refusal of our own checks: the round form shows its message (RoundController.failed)
        throw ExcludeRoundsRefused(refusal, text)
      }
    ids
  }

  /** A refusal of a round's rounds to leave out: a message key and its arguments. */
  final case class ExcludeRoundsRefusal(key: String, args: Seq[String] = Nil)

  /** [[excludedRounds]]' refusal, a `require`'s (its text after "requirement failed: ", as
    * [[controllers.RoundController.refusal]] reads one), with the message the form shows.
    */
  final case class ExcludeRoundsRefused(refusal: ExcludeRoundsRefusal, text: String)
      extends IllegalArgumentException("requirement failed: " + text)

  /** The refusal of `round`'s rounds to leave out, if any, in this order, each checked before the
    * lookups the next ones need: the round itself; a value that is no round id, or more than the
    * column holds (no lookup); with `tellMissing` (a stored round's setting: the service and the
    * job), one that no longer exists (deleted by SQL; `lookup` finds rounds by id in any contest),
    * with its id; one not among its contest's rounds (another contest's, or, without `tellMissing`,
    * none: the form doesn't tell which, so a posted id says nothing of other contests); a previous
    * round of the round (its files would all be left out: an empty round); one being distributed
    * now (`busy`: [[distributingNow]], or nothing for "Count new files"), whose files still coming
    * would end up in both rounds, with its description.
    */
  def excludedRoundsError(round: Round, lookup: Seq[Long] => Seq[Round], busy: Round => Boolean,
      tellMissing: Boolean = false): Option[ExcludeRoundsRefusal] = {
    val ids = round.excludeRounds
    if (ids.isEmpty) None
    else if (round.id.exists(ids.contains)) Some(ExcludeRoundsRefusal(ExcludeRoundsSelf))
    else if (ids.exists(_ <= 0) || round.excludeRoundsStored.exists(_.length > ExcludeRoundsMaxLength))
      Some(ExcludeRoundsRefusal(ExcludeRoundsOther))
    else {
      val found = lookup(ids)
      val ofContest = found.filter(_.contestId == round.contestId)
      val missing = ids.filterNot(id => found.exists(_.id.contains(id)))
      if (tellMissing && missing.nonEmpty) Some(ExcludeRoundsRefusal(ExcludeRoundsMissing, Seq(missing.head.toString)))
      else if (!ids.forall(id => ofContest.exists(_.id.contains(id)))) Some(ExcludeRoundsRefusal(ExcludeRoundsOther))
      else if (ids.exists(round.previousIds.contains)) Some(ExcludeRoundsRefusal(ExcludeRoundsPrevious))
      else
        ofContest.find(busy)
          .map(r => ExcludeRoundsRefusal(ExcludeRoundsBusy, Seq(r.description)))
    }
  }

  /** The round is being distributed: being created (`distributing`), or with an active distribution
    * job, queued or running (V60's `active_dedupe_key`). `distributing` also holds for a round whose
    * creation failed (no active job, until its Retry or resubmit): it is refused too, since it is
    * only partly filled and its Retry adds the rest, files the round leaving it out could take.
    * A round in review ([[Round.inReview]], P3) isn't busy: its files change only by a job, which
    * holds the key this checks (round-editing plan §6.2); otherwise a round leaving it out would be
    * blocked for the whole review.
    */
  def distributingNow(round: Round): Boolean =
    (round.distributing && !round.inReview) || hasActiveJob(round)

  private def hasActiveJob(round: Round): Boolean =
    round.id.exists(id => JobJdbc.activeByDedupeKey(dedupeKey(id)).isDefined)

  /** The reverse of [[excludedRounds]]' busy check: another round of `round`'s contest that leaves
    * out `round`'s files ([[Round.excludeRounds]]) and has an active distribution job, queued or
    * running. That job reads `round`'s files as they are when each of its windows runs, so files a
    * distribution of `round` added now could end up in both rounds. Only an active job counts, not
    * the `distributing` flag: a round leaving out `round` whose creation failed checks `round`
    * again when it is retried ([[excludedRounds]]).
    */
  def excluderBusy(round: Round): Option[Round] =
    round.id.flatMap { id =>
      Round.findByContest(round.contestId)
        .filter(r => !r.id.contains(id) && r.excludeRounds.contains(id))
        .find(hasActiveJob)
    }

  /** Refuses ([[ExcludeRoundsRefused]], [[ExcludeRoundsExcluderBusy]]) a distribution of `round`
    * while a round leaving out its files is being distributed ([[excluderBusy]]).
    */
  def requireNoExcluderBusy(round: Round): Unit =
    excluderBusy(round).foreach { r =>
      throw ExcludeRoundsRefused(
        ExcludeRoundsRefusal(ExcludeRoundsExcluderBusy, Seq(r.description)),
        s"round ${r.getId}, which leaves out round ${round.getId}'s files, is being distributed: " +
          s"distribute round ${round.getId} again when it has finished")
    }

  /** Refuses ([[ExcludeRoundsRefused]], [[PreviousRebalancing]]) a distribution of `round` while
    * one of its previous rounds has a `round_rebalance` job queued or running (a juror being added:
    * its votes move while the distribution would read them; docs/plans/2026-11-16-round-editing.md,
    * §5 "Refusals"). The rounds it leaves out need no such check: a rebalance adds no file to its
    * round, and any active job of theirs already counts as busy ([[distributingNow]]).
    */
  def requireNoPreviousRebalance(round: Round)(implicit session: DBSession = AutoSession): Unit =
    round.previousIds.foreach { id =>
      JobJdbc.activeByDedupeKey(dedupeKey(id)).filter(_.kind == RoundRebalanceHandler.Kind).foreach { _ =>
        val number = Round.findById(id).map(_.description).getOrElse(id.toString)
        throw ExcludeRoundsRefused(ExcludeRoundsRefusal(PreviousRebalancing, Seq(number)),
          s"previous round $id is having a juror added: distribute again when that has finished")
      }
    }

  /** A previous round has a juror being added ([[requireNoPreviousRebalance]]). */
  val PreviousRebalancing = "error.previous.round.rebalancing"

  val ExcludeRoundsSelf = "error.exclude.rounds.self"
  val ExcludeRoundsOther = "error.exclude.rounds.other"
  val ExcludeRoundsPrevious = "error.exclude.rounds.previous"
  val ExcludeRoundsBusy = "error.exclude.rounds.busy"
  val ExcludeRoundsMissing = "error.exclude.rounds.missing"
  val ExcludeRoundsExcluderBusy = "error.exclude.rounds.excluder.busy"

  /** Every refusal key above: a job failed by one stores it ([[services.jobs.JobError.decode]]). */
  val ExcludeRoundsKeys: Set[String] = Set(ExcludeRoundsSelf, ExcludeRoundsOther, ExcludeRoundsPrevious,
    ExcludeRoundsBusy, ExcludeRoundsMissing, ExcludeRoundsExcluderBusy, PreviousRebalancing)

  /** `round.exclude_rounds` is a VARCHAR(255) (V95). */
  val ExcludeRoundsMaxLength = 255

  /** The denominator of an image's score in a previous round ([[ImageWithRating.totalRate]] of
    * a grouped image), by the round's [[AverageRule]]: a constant for a binary round (its
    * jurors) and a sum round (1: the sum), else the image's rows (`PerImage`, assigned) or its
    * rated rows (`PerImageRated`).
    */
  sealed trait Denominator
  object Denominator {
    final case class Const(n: Long) extends Denominator
    case object PerImage extends Denominator
    case object PerImageRated extends Denominator
  }
}

/** A keyset window of candidates: `size` page ids after the cursor, up to `last`. */
final case class Window(size: Int, last: Long, ids: Seq[Long] = Nil)

object Window {
  val empty: Window = Window(0, 0L)
}

/** The streamed candidate queries of [[RoundDistributionHandler]]. */
object DistributionSource {

  import RoundDistributionHandler.{Denominator, PrevVotes}

  private val i = ImageJdbc.syntax("i")

  /** With "selected by at least" set, only approvals (rated votes) are read; with "rejected by at
    * least N", no vote row `s` of a round where its file was rejected N times or more (`Exclude`),
    * or only those (`Only`, V93): each round on its own (a juror has one vote of a file in a
    * round), so a file passes if it passes in one of its rounds (triage R196-1). The rejection
    * check is correlated to the row's round and page id: NULL-safe (unlike `NOT IN`, where one
    * NULL page id in the subquery would drop every row) and read from the `(round_id, page_id,
    * rate)` index alone, rather than counting every previous-round rejection again in each window.
    */
  private def votesCond(votes: PrevVotes): SQLSyntax = {
    val rated = if (votes.rated) sqls"AND ${ratedVote(votes)}" else sqls""
    val rejected = votes.rejectedBy.fold(sqls"") { n =>
      val atLeast = sqls"""EXISTS (SELECT 1 FROM vote r
             WHERE r.round_id = s.round_id AND r.page_id = s.page_id AND ${VoteSql.rejected(VoteSql.r)}
             HAVING COUNT(*) >= $n)"""
      votes.rejectedMode match {
        case RejectedMode.Exclude => sqls"AND NOT $atLeast"
        case RejectedMode.Only    => sqls"AND $atLeast"
      }
    }
    sqls"$rated $rejected"
  }

  /** A rated vote of the previous rounds: a yes/no round's yes, a rated round's any vote. */
  private def ratedVote(votes: PrevVotes): SQLSyntax =
    if (votes.grades) VoteSql.voted() else VoteSql.positive()

  /** A file's aggregates in one previous round, over its vote rows `s` read: the rate sum `rs`
    * (a rated round's of grades, a ✗ as 0), the rows `c` and the rated rows `v`.
    */
  private def roundAggregates(votes: PrevVotes): SQLSyntax =
    if (votes.grades) sqls"SUM(${VoteSql.grade()}) rs, COUNT(*) c, SUM(${VoteSql.voted()}) v"
    else sqls"SUM(s.rate) rs, COUNT(*) c, SUM(${VoteSql.positive()}) v"

  /** Images of the round never come back: the anti-join that makes a resume idempotent.
    * Nor do those with votes of the round moved aside (V63): they are in the round's count
    * already, and get their votes back if the file is restored. A file with a row in the
    * round (round_image, V84), whatever its status, is in it; the vote and vote_aside checks
    * stay until the round_image_check job has shown no drift on production (plan §6, P1).
    */
  private def notInRound(roundId: Long, pageId: SQLSyntax): SQLSyntax =
    sqls"""NOT EXISTS (SELECT 1 FROM round_image xr WHERE xr.round_id = $roundId AND xr.page_id = $pageId)
           AND NOT EXISTS (SELECT 1 FROM vote x WHERE x.round_id = $roundId AND x.page_id = $pageId)
           AND NOT EXISTS (SELECT 1 FROM vote_aside xd WHERE xd.round_id = $roundId AND xd.page_id = $pageId)"""

  /** Not a file of the rounds the round leaves out ([[Round.excludeRounds]], V95): no row of the file
    * in their round_image, whatever its status (in, deleted, dq) or votes; nothing without them. A
    * correlated anti-join on round_image's primary key (round_id, page_id), as [[notInRound]]'s.
    */
  private def notInExcluded(excluded: Seq[Long], pageId: SQLSyntax): SQLSyntax =
    if (excluded.isEmpty) sqls""
    else sqls"AND NOT EXISTS (SELECT 1 FROM round_image xe WHERE xe.round_id IN ($excluded) AND xe.page_id = $pageId)"

  /** A candidate query's files: those not in the round ([[notInRound]]), or, `restrictTo`, only those
    * files, in the round or not (a re-apply's scan window).
    */
  private def inWindow(roundId: Long, restrictTo: Option[Seq[Long]], pageId: SQLSyntax): SQLSyntax =
    restrictTo.fold(notInRound(roundId, pageId)) { ids =>
      if (ids.isEmpty) sqls"FALSE" else sqls"$pageId IN ($ids)"
    }

  /** Not a disqualified file of `roundId` (a re-apply's top-N, D17); nothing without it. */
  private def notDqIn(roundId: Option[Long], pageId: SQLSyntax): SQLSyntax =
    roundId.fold(sqls"")(id =>
      sqls"""AND NOT EXISTS (SELECT 1 FROM round_image xq
                             WHERE xq.round_id = $id AND xq.page_id = $pageId AND xq.status = ${RoundImageJdbc.Status.Dq})""")

  /** No vote filter: the previous rounds' files are those `in` them (round_image), read
    * one row per file instead of per vote.
    */
  private def unfiltered(votes: PrevVotes): Boolean = !votes.rated && votes.rejectedBy.isEmpty

  def categoryCount(categoryId: Long, excluded: Seq[Long])(implicit session: DBSession): Long =
    sql"""SELECT COUNT(*) FROM category_members cm WHERE cm.category_id = $categoryId
          ${notInExcluded(excluded, sqls"cm.page_id")}""".map(_.long(1)).single().getOrElse(0L)

  def categoryWindow(categoryId: Long, cursor: Long, size: Int, excluded: Seq[Long])(implicit
      session: DBSession
  ): Window =
    sql"""SELECT COUNT(*), MAX(page_id) FROM (
            SELECT cm.page_id FROM category_members cm WHERE cm.category_id = $categoryId AND cm.page_id > $cursor
              ${notInExcluded(excluded, sqls"cm.page_id")}
            ORDER BY cm.page_id LIMIT $size) w"""
      .map(rs => Window(rs.int(1), rs.longOpt(2).getOrElse(cursor)))
      .single()
      .getOrElse(Window.empty)

  def categoryCandidates(categoryId: Long, from: Long, to: Long, roundId: Long, excluded: Seq[Long],
      restrictTo: Option[Seq[Long]] = None)(implicit
      session: DBSession
  ): Seq[ImageWithRating] =
    sql"""SELECT ${i.result.*} FROM category_members cm JOIN images i ON i.page_id = cm.page_id
          WHERE cm.category_id = $categoryId AND cm.page_id > $from AND cm.page_id <= $to
            AND i.deleted_at IS NULL AND ${inWindow(roundId, restrictTo, sqls"cm.page_id")}
            ${notInExcluded(excluded, sqls"cm.page_id")}
          ORDER BY cm.page_id"""
      .map(rs => new ImageWithRating(ImageJdbc(i)(rs), Seq.empty))
      .list()

  /** An unfiltered previous round's file (`ri`) was given to a juror there: it has a vote. A row
    * with none (P3-1: filled and not distributed, or its votes gone) isn't one of its files for a
    * next round, as the candidates, read from the votes ([[previousCandidates]]), never have it.
    */
  private val assignedThere: SQLSyntax =
    sqls"EXISTS (SELECT 1 FROM vote pv WHERE pv.round_id = ri.round_id AND pv.page_id = ri.page_id)"

  def previousCount(prevIds: Seq[Long], votes: PrevVotes, excluded: Seq[Long])(implicit session: DBSession): Long =
    (if (unfiltered(votes))
       sql"""SELECT COUNT(DISTINCT ri.page_id) FROM round_image ri
             WHERE ri.round_id IN ($prevIds) AND ri.status = ${RoundImageJdbc.Status.In} AND $assignedThere
               ${notInExcluded(excluded, sqls"ri.page_id")}"""
     else
       sql"""SELECT COUNT(DISTINCT s.page_id) FROM vote s
             WHERE s.round_id IN ($prevIds) ${votesCond(votes)} ${notInExcluded(excluded, sqls"s.page_id")}""")
      .map(_.long(1)).single().getOrElse(0L)

  def previousWindow(prevIds: Seq[Long], votes: PrevVotes, cursor: Long, size: Int, excluded: Seq[Long])(implicit
      session: DBSession
  ): Window = {
    val pages =
      if (unfiltered(votes))
        sqls"""SELECT DISTINCT ri.page_id FROM round_image ri
               WHERE ri.round_id IN ($prevIds) AND ri.status = ${RoundImageJdbc.Status.In} AND ri.page_id > $cursor
                 AND $assignedThere ${notInExcluded(excluded, sqls"ri.page_id")}
               ORDER BY ri.page_id LIMIT $size"""
      else
        sqls"""SELECT DISTINCT s.page_id FROM vote s
               WHERE s.round_id IN ($prevIds) ${votesCond(votes)} AND s.page_id > $cursor
                 ${notInExcluded(excluded, sqls"s.page_id")}
               ORDER BY s.page_id LIMIT $size"""
    sql"SELECT COUNT(*), MAX(page_id) FROM ($pages) w"
      .map(rs => Window(rs.int(1), rs.longOpt(2).getOrElse(cursor)))
      .single()
      .getOrElse(Window.empty)
  }

  /** The window's images not yet in the round, each with its ratings in each of its previous
    * rounds as the in-memory path builds them (`byRoundMerged` per round, tagged with its round):
    * one row per file and previous round, a vote of juror 0 of that round with the round's rate
    * sum, its rows and its rated rows; the per-image filters evaluate each row in its round
    * ([[org.intracer.wmua.cmd.DistributeImages.perImageFilters]]). By page id, then round id.
    */
  def previousCandidates(prevIds: Seq[Long], votes: PrevVotes, from: Long, to: Long, roundId: Long,
      excluded: Seq[Long], restrictTo: Option[Seq[Long]] = None)(implicit session: DBSession): Seq[ImageWithRating] =
    sql"""SELECT g.page_id, g.round_id, g.rs, g.c, g.v, ${i.result.*} FROM (
              SELECT s.page_id, s.round_id, ${roundAggregates(votes)} FROM vote s
              WHERE s.round_id IN ($prevIds) ${votesCond(votes)} AND s.page_id > $from AND s.page_id <= $to
                AND ${inWindow(roundId, restrictTo, sqls"s.page_id")}
                ${notInExcluded(excluded, sqls"s.page_id")}
              GROUP BY s.page_id, s.round_id) g
            JOIN images i ON i.page_id = g.page_id
            WHERE i.deleted_at IS NULL
            ORDER BY g.page_id, g.round_id"""
      .map { rs =>
        val image = ImageJdbc(i)(rs)
        val rated = rs.int("v")
        // a rated round's rated rows are its votes, a ✗ included (ImageWithRating.ratedVotes)
        ImageWithRating(image, Seq(Vote(image.pageId, juryId = 0, roundId = rs.long("round_id"), rate = rs.int("rs"))),
          rs.int("c"), approvalsFromDb = Some(rated), votedFromDb = Option.when(votes.grades)(rated))
      }
      .list()

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
    * one row per file and previous round, each scored in its round (`totalRate`: the round's
    * rate sum, below 0 as 0, over its denominator), ties broken by that sum, then by the raw sum,
    * then by page id; a file ranks by its best row that passes the filters (triage R196-1).
    * Yes/no previous rounds by `binaryOrder` instead ([[binaryRows]]). The per-image filters
    * apply before ranking: the ranked rows are filtered in order until `n` files pass, each file
    * once. Pages grow (n, 2n, 4n, ...) while filters reject rows, so a selective filter costs a
    * few queries, not one per image.
    *
    * The images already in the round count in the ranking: the round's top-N doesn't
    * grow when "Distribute new files" runs again.
    */
  def rank(
      prevIds: Seq[Long],
      votes: PrevVotes,
      // each previous round's denominator: a row is scored in its round ([[ratedRows]])
      denominators: Seq[(Long, Denominator)],
      n: Int,
      filter: Seq[ImageWithRating] => Seq[ImageWithRating],
      binaryOrder: Option[BinaryOrder],
      // the rounds whose files are left out before the ranking ([[notInExcluded]]): a top-N of the rest
      excluded: Seq[Long],
      // a re-apply's round (D17): its disqualified files take no place in the top-N
      skipDqOf: Option[Long] = None
  )(implicit session: DBSession): Seq[Long] =
    rankRows(prevIds, votes, denominators, n, filter, binaryOrder, excluded, skipDqOf).map(_.pageId)

  /** [[rank]]'s files with the row each ranks by (its key: [[RoundDistributionHandler.tieKey]]). */
  def rankRows(
      prevIds: Seq[Long],
      votes: PrevVotes,
      denominators: Seq[(Long, Denominator)],
      n: Int,
      filter: Seq[ImageWithRating] => Seq[ImageWithRating],
      binaryOrder: Option[BinaryOrder],
      excluded: Seq[Long],
      skipDqOf: Option[Long] = None
  )(implicit session: DBSession): Seq[RankRow] = {
    val picked = mutable.LinkedHashMap.empty[Long, RankRow]
    var offset = 0
    var pageSize = math.max(n, 100)
    var exhausted = n <= 0
    while (!exhausted && picked.size < n) {
      val page = binaryOrder.fold(ratedRows(prevIds, votes, denominators, pageSize, offset, excluded, skipDqOf))(
        binaryRows(prevIds, votes, _, pageSize, offset, excluded, skipDqOf))
      val ids = page.map(_.pageId).distinct
      val images = if (page.isEmpty) Map.empty[Long, Image] else
        sql"SELECT ${i.result.*} FROM images i WHERE i.page_id IN ($ids) AND i.deleted_at IS NULL"
          .map(rs => ImageJdbc(i)(rs)).list().map(img => img.pageId -> img).toMap
      val byKey = page.map(row => (row.pageId, row.roundId) -> row).toMap
      val candidates = page.flatMap(row => images.get(row.pageId).map(rating(row, votes)))
      // a file picked by a better row on an earlier page is already in
      filter(candidates).iterator.filterNot(f => picked.contains(f.pageId)).take(n - picked.size)
        .foreach(f => picked(f.pageId) = byKey((f.pageId, f.votes.head.roundId)))
      exhausted = page.size < pageSize
      offset += page.size
      pageSize *= 2
    }
    picked.values.toSeq
  }


  /** A file's rating in one previous round, from its aggregate row, as [[rank]] filters it: a vote of
    * juror 0 of that round with the round's rate sum (a rated round's, below 0 as 0; a yes/no round's
    * approvals), its rows and its rated rows (or a yes/no round's approvals and rejections).
    */
  private def rating(row: RankRow, votes: PrevVotes): Image => ImageWithRating = row.tally match {
    case Some((approvals, rejections)) => (img: Image) =>
      ImageWithRating(img, Seq(Vote(row.pageId, juryId = 0, roundId = row.roundId, rate = approvals)), row.count.toInt,
        approvalsFromDb = Some(approvals), rejectionsFromDb = Some(rejections))
    case None => (img: Image) =>
      ImageWithRating(img, Seq(Vote(row.pageId, juryId = 0, roundId = row.roundId, rate = row.rateSum.toInt)),
        row.count.toInt, approvalsFromDb = Some(row.rated.toInt), votedFromDb = Option.when(votes.grades)(row.rated.toInt))
  }

  /** A page of the previous rounds' rows by average rating ([[rank]]): one per file and previous
    * round, its page id, the round and its rate sum, rows and rated rows there. Each row is scored in
    * its round, by the round's denominator (`denominators`: its rule, or a yes/no round's jurors), as
    * the in-memory path scores it ([[ImageWithRating.sourceRound]]).
    */
  private def ratedRows(prevIds: Seq[Long], votes: PrevVotes, denominators: Seq[(Long, Denominator)], pageSize: Int,
      offset: Int, excluded: Seq[Long], skipDqOf: Option[Long])(implicit session: DBSession): Seq[RankRow] = {
    sql"""${ratedQuery(prevIds, votes, denominators, excluded, skipDqOf)}
          LIMIT $pageSize OFFSET $offset"""
      .map(rs => RankRow(rs.long(1), rs.long(2), rs.long(3), rs.long(4), rs.long(5), None))
      .list()
  }

  /** [[ratedRows]]' query, in its order, without a limit; with `numbered`, in no order but each row
    * with its place in that order (`rn`, `ROW_NUMBER() OVER` the same order), for [[rowsWithImages]].
    */
  private def ratedQuery(prevIds: Seq[Long], votes: PrevVotes, denominators: Seq[(Long, Denominator)],
      excluded: Seq[Long], skipDqOf: Option[Long], numbered: Boolean = false): SQLSyntax = {
    // one row's score in its round (t.round_id), by that round's denominator
    def rowScore(d: Denominator): SQLSyntax = d match {
      case Denominator.Const(n)      => sqls"IF($n = 0, 0, GREATEST(t.rs, 0) * 1e0 / $n)"
      case Denominator.PerImage      => sqls"IF(t.c = 0, 0, GREATEST(t.rs, 0) * 1e0 / t.c)"
      case Denominator.PerImageRated => sqls"IF(t.v = 0, 0, GREATEST(t.rs, 0) * 1e0 / t.v)"
    }
    require(denominators.nonEmpty, "a top-N needs previous rounds")
    val cases = denominators.map { case (id, d) => sqls"WHEN $id THEN ${rowScore(d)}" }
    val score = sqls"CASE t.round_id ${sqls.join(cases, sqls" ")} END"
    val orderBy = sqls"$score DESC, GREATEST(t.rs, 0) DESC, t.rs DESC, t.page_id ASC, t.round_id ASC"
    val (number, order) = if (numbered) (sqls", ROW_NUMBER() OVER (ORDER BY $orderBy) rn", sqls"") else (sqls"", sqls"ORDER BY $orderBy")
    sqls"""SELECT t.page_id, t.round_id, GREATEST(t.rs, 0) rate_sum, t.c, t.v $number FROM (
            SELECT s.page_id, s.round_id, ${roundAggregates(votes)} FROM vote s
            WHERE s.round_id IN ($prevIds) ${votesCond(votes)} ${notInExcluded(excluded, sqls"s.page_id")}
              ${notDqIn(skipDqOf, sqls"s.page_id")}
            GROUP BY s.page_id, s.round_id) t
          $order"""
  }


  /** A page of yes/no previous rounds' rows by `order` over all of a round's votes of a file
    * (one row per file and previous round, tagged with the round), ties by page id: the same
    * rule whatever "selected by at least" is (docs/history/2026-11-01-binary-aggregate-votes.md,
    * issue 2); a file ranks by its best row (triage R196-1). `votes` keeps the rows with an
    * approval ("selected by at least" set) and those with fewer rejections in their round than
    * "exclude files rejected by at least N" (or, `Only`, those with N or more). Each row's rating
    * has its approvals as its rate, so "selected by at least" compares them.
    */
  private def binaryRows(prevIds: Seq[Long], votes: PrevVotes, order: BinaryOrder, pageSize: Int,
      offset: Int, excluded: Seq[Long], skipDqOf: Option[Long])(implicit session: DBSession): Seq[RankRow] = {
    val (grouped, orderBy) = binaryGrouped(prevIds, votes, order, excluded, skipDqOf)
    sql"""$grouped
          ORDER BY $orderBy
          LIMIT $pageSize OFFSET $offset"""
      .map(binaryRow)
      .list()
  }

  /** [[binaryRows]]' grouped query (without its order) and its order. */
  private def binaryGrouped(prevIds: Seq[Long], votes: PrevVotes, order: BinaryOrder, excluded: Seq[Long],
      skipDqOf: Option[Long], extra: SQLSyntax = sqls""): (SQLSyntax, SQLSyntax) = {
    val conditions = Option.when(votes.rated)(sqls"SUM(${VoteSql.positive()}) > 0").toSeq ++
      votes.rejectedBy.map(n => votes.rejectedMode match {
        case RejectedMode.Exclude => sqls"SUM(${VoteSql.rejected()}) < $n"
        case RejectedMode.Only    => sqls"SUM(${VoteSql.rejected()}) >= $n"
      })
    val having = if (conditions.isEmpty) sqls"" else sqls"HAVING ${sqls.joinWithAnd(conditions: _*)}"
    val orderBy = SQLSyntax.createUnsafely(
      (order.columns ++ Seq("s.page_id" -> 1, "s.round_id" -> 1))
        .map { case (column, dir) => column + (if (dir < 0) " DESC" else " ASC") }
        .mkString(", "))
    (sqls"""SELECT s.page_id, s.round_id, SUM(${VoteSql.positive()}) approvals, SUM(${VoteSql.rejected()}) rejections,
            COUNT(*) cnt $extra
          FROM vote s
          WHERE s.round_id IN ($prevIds) ${notInExcluded(excluded, sqls"s.page_id")} ${notDqIn(skipDqOf, sqls"s.page_id")}
          GROUP BY s.page_id, s.round_id $having""", orderBy)
  }

  private def binaryRow(rs: WrappedResultSet): RankRow = {
    val (approvals, rejections) = (rs.int(3), rs.int(4))
    RankRow(rs.long(1), rs.long(2), approvals.toLong, rs.long(5), approvals.toLong, Some(approvals -> rejections))
  }

  /** The columns of a file's image the per-image filters read ([[org.intracer.wmua.cmd.DistributeImages.perImageFilters]]:
    * the monument (its region), the size, width and height (megapixels), the media type and title (a
    * video), the author (conflicts of interest)); the page id is the row's (a category). Not the
    * description or URLs. A filter reading another column must be added here and in [[filterImage]].
    */
  private val ImageColumns =
    sqls"i.title, i.width, i.height, i.monument_id, i.size, i.author, i.mime, i.media_type"

  /** A row's image, from its [[ImageColumns]] (after the row's five columns and its place). */
  private def filterImage(rs: WrappedResultSet): Image =
    Image(pageId = rs.long(1), title = rs.string(7), width = rs.int(8), height = rs.int(9), monumentId = rs.stringOpt(10),
      size = rs.intOpt(11), author = rs.stringOpt(12), mime = rs.stringOpt(13), mediaType = rs.stringOpt(14))

  /** Every row of [[ratedRows]] (or, with `order`, [[binaryRows]]), in its order, each with its image's
    * [[ImageColumns]]; a row without a (not deleted) image left out, as [[rank]] skips it. One query:
    * the grouped rows numbered by that order (`ROW_NUMBER() OVER` it: sorted while they are narrow),
    * joined to their images in no order, put in order here by their numbers (a sort of the joined, wide
    * rows by the database took several times longer).
    */
  private def rowsWithImages(prevIds: Seq[Long], votes: PrevVotes, denominators: Seq[(Long, Denominator)],
      order: Option[BinaryOrder], excluded: Seq[Long], skipDqOf: Option[Long])(implicit
      session: DBSession): Seq[(RankRow, Image)] = {
    val (numbered, row): (SQLSyntax, WrappedResultSet => RankRow) = order match {
      case None =>
        (ratedQuery(prevIds, votes, denominators, excluded, skipDqOf, numbered = true),
          rs => RankRow(rs.long(1), rs.long(2), rs.long(3), rs.long(4), rs.long(5), None))
      case Some(o) =>
        val (_, orderBy) = binaryGrouped(prevIds, votes, o, excluded, skipDqOf)
        (binaryGrouped(prevIds, votes, o, excluded, skipDqOf, extra = sqls", ROW_NUMBER() OVER (ORDER BY $orderBy) rn")._1,
          binaryRow)
    }
    sql"""SELECT n.*, $ImageColumns
          FROM ($numbered) n JOIN images i ON i.page_id = n.page_id AND i.deleted_at IS NULL"""
      .map(rs => (rs.long(6), row(rs), filterImage(rs)))
      .list()
      .sortBy(_._1)
      .map { case (_, r, image) => r -> image }
  }

  /** A file's aggregate row of one previous round, as the top-N reads it: its rate sum (a rated round's,
    * below 0 as 0; a yes/no round's approvals), its vote rows and its rated rows (a rated round's voted,
    * or positive, rows; a yes/no round's approvals), and a yes/no round's approvals and rejections (its
    * rows ranked by a [[BinaryOrder]], [[binaryRows]]).
    */
  final case class RankRow(pageId: Long, roundId: Long, rateSum: Long, count: Long, rated: Long,
      tally: Option[(Int, Int)])

  /** A file as the cut picker ranks it: its rating as [[rank]] filters it, and the row it ranks by. */
  final case class RankedFile(file: ImageWithRating, row: RankRow)

  /** The rows filtered at once while ranking every file ([[rankAll]]). */
  val RankBatch = 1000

  /** Every file the fill's [[rank]] would take with no limit, in its order: the same rows (one
    * aggregate query, [[ratedRows]]' or [[binaryRows]]', each row with the columns of its image the
    * per-image filters read, [[rowsWithImages]]), the same per-image filters, applied to batches of
    * [[RankBatch]] rows, a file by its best row that passes, a row without a (not deleted) image
    * skipped, as [[rank]] skips it. The first `keep` files are kept whole (the cut gallery's), their
    * images read in full. Every file is kept only as
    * `summary` of its row (the rank keys the candidate cuts are found in). So a top N of the fill takes
    * exactly the first N of this list.
    */
  def rankAll[K](
      prevIds: Seq[Long],
      votes: PrevVotes,
      denominators: Seq[(Long, Denominator)],
      filter: Seq[ImageWithRating] => Seq[ImageWithRating],
      binaryOrder: Option[BinaryOrder],
      excluded: Seq[Long],
      skipDqOf: Option[Long],
      keep: Int
  )(summary: RankRow => K)(implicit session: DBSession): (Seq[RankedFile], IndexedSeq[K]) = {
    val rows = rowsWithImages(prevIds, votes, denominators, binaryOrder, excluded, skipDqOf)
    val picked = mutable.HashSet.empty[Long]
    val kept = mutable.ArrayBuffer.empty[RankRow]
    val summaries = IndexedSeq.newBuilder[K]
    summaries.sizeHint(rows.size)
    rows.grouped(RankBatch).foreach { batch =>
      val byKey = batch.map { case (row, _) => (row.pageId, row.roundId) -> row }.toMap
      val candidates = batch.map { case (row, image) => rating(row, votes)(image) }
      filter(candidates).foreach { file =>
        if (picked.add(file.pageId))
          byKey.get((file.pageId, file.votes.head.roundId)).foreach { row =>
            if (kept.size < keep) kept += row
            summaries += summary(row)
          }
      }
    }
    // the gallery's files whole: their images in full (title, URL), rated as the filters rated them
    val full = if (kept.isEmpty) Map.empty[Long, Image] else
      sql"SELECT ${i.result.*} FROM images i WHERE i.page_id IN (${kept.map(_.pageId)}) AND i.deleted_at IS NULL"
        .map(rs => ImageJdbc(i)(rs)).list().map(img => img.pageId -> img).toMap
    (kept.toSeq.flatMap(row => full.get(row.pageId).map(img => RankedFile(rating(row, votes)(img), row))), summaries.result())
  }

}
