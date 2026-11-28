package services

import controllers.RoundStat
import db.RoundRepo
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.{AccountJoinJdbc, ContestChangeJdbc, ImageJdbc, Round, RoundChanges, RoundImageCounts, RoundImageJdbc, RoundUser, VoteJdbc, User, WriteTx}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Logging
import play.api.libs.json.{JsObject, JsString, Json}
import scalikejdbc.{DB, DBSession}
import scalikejdbc._
import services.distribution.RoundDistributionHandler
import services.jobs.{Job, JobError, JobJdbc, JobScheduler, NewJob, Progress}

import javax.inject.Inject

class RoundService @Inject() (
    dao: RoundRepo,
    jobs: JobScheduler,
    distribution: RoundDistributionHandler
) extends Logging {

  import RoundService._

  /** A job's title, as its status partial's label (snippets/jobStatus). */
  def jobTitle(job: Job): String = jobs.title(job)

  /** Creates the round and distributes its images, waiting for the distribution (scripts,
    * the API, tests): [[submit]], then the job's end.
    *
    * @throws RoundService.RoundDistributionFailed if the round was created but its
    *                                              distribution failed
    */
  def createNewRound(round: Round, jurorIds: Seq[Long], submitToken: Option[String] = None): Round = {
    val submitted = submit(round, jurorIds, submitToken, review = false)
    submitted.job.foreach(j => checkFinished(submitted.round.getId, awaitWithFollowUp(submitted.round.getId, j.id)))
    dao.findById(submitted.round.getId).getOrElse(submitted.round)
  }

  /** Creates a round from the new-round form and starts distributing its images as a
    * background job ([[RoundDistributionHandler]]); returns at once.
    *
    * The round is created hidden from its jurors (`distributing`, inactive) with its
    * jurors and its job, and its previous rounds frozen, in one transaction. The job
    * shows the round to its jurors once its images have passed the count check.
    *
    * With a submission token (the form's hidden field, see V59), one submission creates
    * one round: a resubmit of the same form (a double click, a browser retry, Back and
    * Submit) finds that round instead, and resumes its distribution if it failed. Two
    * identical submits at once are decided by the token's unique index. Without a token
    * (scripts, the API) every call creates a round.
    *
    * Invalid settings (no jurors, previous rounds of another contest or of mixed rate
    * types, rounds to leave out that are not other rounds of its contest, more jurors per image
    * than jurors) are refused before anything is created.
    *
    * @param titles a pasted list of files ("Add files"), added to the round after the
    *               images its filters choose
    * @param review the round waits in review after its fill (the new-round form's, always: its jurors
    *               are chosen on its jurors form); without, it is distributed to `jurorIds` at once
    *               (services, tests). Required: no caller gets either by default
    */
  def submit(
      round: Round,
      jurorIds: Seq[Long],
      submitToken: Option[String] = None,
      createdBy: Option[Long] = None,
      titles: Seq[String] = Nil,
      review: Boolean,
      // how its top N was chosen (the cut picker): kept with its settings log entry
      topImagesBasis: Option[JsObject] = None
  ): Submitted =
    submitToken.flatMap(submitted(_, round.contestId)) match {
      case Some(existing) => resubmitted(existing)
      case None =>
        val canonical = round.withFixedCategories.withCanonicalCategoryRule
        val prevRounds = RoundDistributionHandler.previousRounds(canonical)
        RoundDistributionHandler.excludedRounds(canonical)
        val configuredRound = canonical.withExplicitTopOrder(prevRounds.exists(_.isBinary))
        val jurors = User.loadJurors(round.contestId, jurorIds).distinct
        // a round created for review may have no jurors yet: they are chosen on its review page,
        // before Distribute (P3, D3); distributing as soon as it is filled (D4) needs them now
        require(jurors.nonEmpty || review, "no jurors selected for the round; nothing to distribute")
        require(
          jurors.isEmpty || round.distribution <= jurors.size,
          s"${round.distribution} jurors per image, but only ${jurors.size} jurors selected"
        )
        insert(configuredRound, jurors, submitToken, createdBy, titles, review, topImagesBasis) match {
          case Left(existing) => resubmitted(existing)
          case Right((created, job)) =>
            logger.info(
              s"Round ${created.getId} created for contest ${created.contestId} with ${jurors.size} jurors; " +
                s"distributing its images (job ${job.id})"
            )
            jobs.started(job.id)
            Created(created, JobJdbc.findById(job.id).getOrElse(job))
        }
    }

  /** The round an earlier submission of this form created, if any. */
  private def submitted(token: String, contestId: Long): Option[Round] =
    Round.findBySubmitToken(token).map { round =>
      require(round.contestId == contestId, "this form was submitted for another contest")
      round
    }

  /** A new-round form posted again after it saved `existing` (its token checked before the form is
    * validated, [[controllers.RoundController.saveRound]]): no new round; [[resubmitted]]'s outcome.
    * Authorization is the caller's.
    */
  def resubmit(existing: Round): Resubmitted = resubmitted(existing)

  /** A known submission again: no new round. A failed distribution is resumed (but not one
    * given up after its attempts: that waits for the job page's Retry); a running one goes on.
    */
  private def resubmitted(existing: Round): Resubmitted =
    distributionJob(existing.getId) match {
      // a failed distribute (P3) is retried on its job page or by the review's Distribute, not by
      // the creation's form posted again
      case Some(j) if j.isFailed && !RoundDistributionHandler.distributeMode(j) =>
        // not while a round leaving out its files is being distributed (refused: the form says so)
        RoundDistributionHandler.requireNoExcluderBusy(existing)
        jobs.retry(j.id, explicit = false) match {
          case Right(job) =>
            logger.info(s"Round ${existing.getId}: its form was submitted again; its distribution resumed")
            Resubmitted(existing, Some(job), resumed = true)
          case Left(_) => Resubmitted(existing, distributionJob(existing.getId), resumed = false)
        }
      case other => Resubmitted(existing, other, resumed = false)
    }

  /** Inserts the round (hidden until distributed), its submission token, its jurors and
    * its distribution job, and freezes its previous rounds, in one transaction; or
    * returns the round of the token if another submission of the same form won.
    */
  private def insert(
      round: Round,
      jurors: Seq[User],
      submitToken: Option[String],
      createdBy: Option[Long],
      titles: Seq[String],
      review: Boolean,
      topImagesBasis: Option[JsObject]
  ): Either[Round, (Round, Job)] = {
    val number = dao.countByContest(round.contestId) + 1
    try
      Right(WriteTx.write { implicit session =>
        val created = dao.create(
          round.copy(number = number, submitToken = submitToken, active = false, distributing = true)
        )
        // each juror's users row share-locked and re-checked (A-4): an account retired since the
        // form was read is left out; one being retired now waits for this to commit, then sees
        // its round_user row and is refused (AccountRetirement)
        // and an account a join retires is left out too (the join plan, J-2: round creation drops
        // the retiring account and keeps the kept one), read after the shared lock, which a join's
        // start takes exclusively: it either committed before (read here) or waits for this
        val locked = User.lockLive(jurors.flatMap(_.id))
        val live = locked -- AccountJoinJdbc.retiring(locked.toSeq)
        val (kept, retired) = jurors.partition(u => live.contains(u.getId))
        if (retired.nonEmpty)
          logger.info(s"Round ${created.getId}: retired accounts left out of its jurors: ${retired.map(_.getId).mkString(",")}")
        created.addUsersIn(kept.map(u => RoundUser(created.getId, u.getId, u.contestRole.getOrElse(User.JURY_ROLE), active = true)))
        // "Create and review images" (D3): a fill that ends in review; else one pass (D4)
        val job = enqueueOrFail(created, completingCreation = true, createdBy, titles, distributeAfter = !review,
          topNChecked = topImagesBasis.isDefined)
        // its settings as written, in the round's settings log (RoundChanges), with the round
        RoundChanges.recordCreated(created.getId, createdBy, topImagesBasis)
        (created, job)
      })
    catch {
      // the unique index round_submit_token_uidx (rounds_submit_token_uidx before V82)
      case e: java.sql.SQLIntegrityConstraintViolationException
          if submitToken.isDefined && Option(e.getMessage).exists(_.contains("submit_token_uidx")) =>
        Left(submitToken.flatMap(submitted(_, round.contestId)).getOrElse(throw e))
    }
  }

  /** Creates the round's distribution job in the caller's transaction, freezing its
    * previous rounds; throws [[AlreadyDistributing]] (rolling the transaction back, the
    * freeze included) if the round already has an active one.
    *
    * @param titles   a pasted list of files the job adds to the round
    * @param listOnly the job adds only `titles`, not the images the round's filters choose
    * @param mode     the job's step (P3, [[RoundDistributionHandler.Mode]]): `fill` (the round's
    *                 files), or `distribute` (a round in review given to its jurors)
    * @param distributeAfter a fill queues the distribute as it ends (D4; "Distribute new files" on a
    *                 distributed round); without, a round being created ends held in review
    * @param fromReview queued on a round in review: it stays in review (`paused`)
    * @param requireInReview a distribute: refused ([[NotInReviewRefused]]) unless the round, read under the
    *                 locks below, is in review
    * @param reapply  a fill re-applying the round's filters in review (P3-5): it scans the round's
    *                 `fill` files first and deletes those no longer passing (D16)
    * @param underLocks run under the locks, after the checks above and before the job is queued: the
    *                 caller's own checks (refusing by an exception, which rolls back) and writes
    */
  private def enqueueOrFail(
      round: Round,
      completingCreation: Boolean,
      createdBy: Option[Long],
      titles: Seq[String] = Nil,
      listOnly: Boolean = false,
      mode: String = RoundDistributionHandler.Mode.Fill,
      distributeAfter: Boolean = false,
      fromReview: Boolean = false,
      requireInReview: Boolean = false,
      reapply: Boolean = false,
      topUpWaiting: Boolean = false,
      // its top N checked by the round form (the cut picker's `topImagesBasis`): the job's Rank step
      // checks it again, the votes frozen (RoundDistributionHandler.cutInTie)
      topNChecked: Boolean = false,
      underLocks: DBSession => Unit = _ => ()
  )(implicit session: DBSession): Job = {
    val distribute = mode == RoundDistributionHandler.Mode.Distribute
    // the round first, with the rounds it freezes: "Leave out conflicts of interest now"
    // checks for its active job under the round's lock (RoundConflicts.disqualify). And the rounds
    // it leaves out and those leaving it out (V95), in one id order: two such rounds queued at once
    // take turns here, so the second sees the first's job (checked below)
    val excluders = Round.findByContest(round.contestId)
      .filter(r => !r.id.contains(round.getId) && r.excludeRounds.contains(round.getId)).flatMap(_.id)
    Round.lock(round.previousIds ++ round.excludeRounds ++ excluders :+ round.getId)
    // read under that lock: in review still, with no job (checked next)
    if (requireInReview && !Round.findById(round.getId).exists(_.inReview)) throw NotInReviewRefused
    // an active distribution of the round, read plainly under the round's lock (READ
    // COMMITTED: the latest committed; jobs of the round are queued and end under this lock):
    // refused here, before an insert whose duplicate key would lock the running job's row, which
    // its step holds before the round (break 11 of the write-path plan)
    JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId)).foreach(active => throw AlreadyDistributing(active))
    underLocks(session)
    // under those locks: no round it leaves out, nor one leaving it out, being distributed now
    // (refused, rolling back; the round form shows the message). The job checks again as it runs.
    // a distribute reads no candidates and adds no row: neither applies to it
    if (!distribute) {
      RoundDistributionHandler.excludedRounds(round)
      RoundDistributionHandler.requireNoExcluderBusy(round)
    }
    // nor a previous round having a juror added (its votes moving), read under its lock: a
    // rebalance is queued and ends under it. A distribute too: it freezes and closes them
    if (!listOnly) RoundDistributionHandler.requireNoPreviousRebalance(round)
    val state = distribution.initialState(round, listOnly, distribute, completingCreation, reapply)
    // a creation's fill (or one finished by "Distribute new files") is `draft` while it runs, by
    // who queued it (round lineage §2.6); a round in review stays `paused`, its distribute too (the
    // two-step plan's §4); under the round's lock, taken above
    if (completingCreation && !distribute && !fromReview) Round.setStatus(round.getId, Round.Status.Draft, createdBy)
    // the top-N's order and the rejected-by filter only where they apply: from a yes/no
    // previous round, and the rejected-by from a rated one without criteria too (its ✗, whether
    // its option is on now or not): from any such previous round, as the job and the form have it
    val previous = if (round.previousIds.isEmpty) Nil else Round.findByIds(round.contestId, round.previousIds)
    val binaryPrevious = previous.exists(_.isBinary)
    val rejectingPrevious = previous.exists(_.rejects)
    // the rule a rated previous round scores its files by (V90), read under its lock: the
    // distribution ranks and filters by this one, whatever the round's rule is changed to later
    val prevAverageRule = round.previousIds.headOption.flatMap(id => previous.find(_.id.contains(id)))
      .filterNot(_.isBinary).map(_.averageRule.param)
    // and each rated previous round's, by its id: a file of one of them is scored by that
    // round's rule (triage R196-1; RoundDistributionHandler.jobAverageRules)
    val prevAverageRules = JsObject(previous.filterNot(_.isBinary).flatMap(p => p.id.map(id =>
      id.toString -> JsString(p.averageRule.param))))
    val topOrder = round.topImages.filter(_ => binaryPrevious).map(_ => round.topOrderParam)
    // "only" whatever the sources, as the job applies it (the form refuses a filter without one)
    val rejectedBy = round.rejectedByFilter.filter(_ =>
      rejectingPrevious || (previous.nonEmpty && round.prevRejectedMode == org.intracer.wmua.RejectedMode.Only))
    jobs.enqueue(
      NewJob(
        kind = RoundDistributionHandler.Kind,
        contestId = Some(round.contestId),
        subjectType = Some(RoundDistributionHandler.SubjectType),
        subjectId = round.id,
        dedupeKey = Some(RoundDistributionHandler.dedupeKey(round.getId)),
        params = Json.obj(
          "roundId" -> round.getId,
          "completingCreation" -> completingCreation,
          "mode" -> mode
        ) ++ (if (distribute) Json.obj() else Json.obj("distributeAfter" -> distributeAfter)) ++
          (if (fromReview) Json.obj("fromReview" -> true) else Json.obj()) ++
          (if (reapply) Json.obj("reapply" -> true) else Json.obj()) ++
          // "Also give out the waiting files" (round-editing plan §7): kept by the distribute it queues
          (if (topUpWaiting) Json.obj("topUpWaiting" -> true) else Json.obj()) ++
          (if (topNChecked && round.topImages.isDefined) Json.obj(RoundDistributionHandler.TopNCheckedParam -> true)
           else Json.obj()) ++ Json.obj(
          // for the record: the job reads the round, whose settings don't change once created;
          // but prevAverageRule(s) and prevRejectedMode, which the job reads from here
          // (RoundDistributionHandler.context)
          "settings" -> Json.obj(
            "previous" -> round.previous,
            "distribution" -> round.distribution,
            "prevSelectedBy" -> round.selectedByFilter,
            "prevRejectedBy" -> rejectedBy,
            "prevRejectedMode" -> rejectedBy.map(_ => round.prevRejectedMode.param),
            "prevMinAvgRate" -> round.prevMinAvgRate,
            "topImages" -> round.topImages,
            "topBinaryOrder" -> topOrder,
            "prevAverageRule" -> prevAverageRule,
            "prevAverageRules" -> prevAverageRules,
            "regions" -> round.regions,
            "monuments" -> round.monuments,
            "category" -> round.category,
            "excludeCategory" -> round.excludeCategory,
            "categoryClause" -> round.categoryClause,
            "minMpx" -> round.minMpx,
            "minImageSize" -> round.minImageSize,
            "mediaType" -> round.mediaType,
            "specialNomination" -> round.specialNomination,
            // "Leave out files that are in round(s)" (V95); the job reads the round's
            "excludeRounds" -> round.excludeRounds
          )
        ) ++ (if (titles.isEmpty) Json.obj()
              else Json.obj("titles" -> titles, "titleCount" -> titles.size, "listOnly" -> listOnly)),
        state = state,
        progress = Progress(unit = Some("images")),
        createdBy = createdBy
      )
    ) match {
      case Right(job)   => job
      case Left(active) => throw AlreadyDistributing(active)
    }
  }

  /** The round's latest distribution job, if it ever had one. */
  def distributionJob(roundId: Long): Option[Job] =
    JobJdbc
      .latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
      .get(roundId)

  /** Authorization is the caller's: only the round's contest's admins and root may call it
    * ([[controllers.RoundController.saveRound]] checks it), as it also reinstates a cancelled
    * creation.
    *
    * "Distribute new files": distributes to the round, in the background, the images that
    * qualify for it but aren't in it yet, and/or ("Add files") the files of a pasted list.
    * A failed distribution of the round is resumed instead (without the list); a running
    * one goes on. One given up after its attempts is not resumed ([[GivenUp]]): only the job
    * page's Retry does, once the cause is fixed.
    *
    * @param titles   a pasted list of files to add to the round, whatever its filters
    * @param newFiles also the images that qualify for the round; without, only the list
    *                 (but for a round still being created, which its filters must fill)
    * @param topUpWaiting also give out the files waiting for a juror (round-editing plan §7): the
    *                 distribute's `topup` pass after its `assign`. Alone (no new files, no list), only a
    *                 distribute is queued, on a distributed round (`distribution` > 0): no fill. Ignored
    *                 when a failed distribution is resumed instead ([[Resumed]]: the caller says so)
    */
  def distributeNewFiles(
      roundId: Long,
      createdBy: Option[Long] = None,
      titles: Seq[String] = Nil,
      newFiles: Boolean = true,
      topUpWaiting: Boolean = false
  ): DistributionStart = {
    val round = dao.findById(roundId).getOrElse(throw new NoSuchElementException(s"Round $roundId not found"))
    val latest = distributionJob(roundId)
    latest match {
      case Some(j) if j.isActive => AlreadyRunning(j)
      // a failed distribute (P3) is retried on its job page or by the review's Distribute, never
      // from here: in review "Distribute new files" only fills (round-editing plan §6.2)
      case Some(j) if j.isFailed && !RoundDistributionHandler.distributeMode(j) =>
        // not while a round leaving out its files is being distributed (refused: the form says so;
        // the job checks again as it runs)
        RoundDistributionHandler.requireNoExcluderBusy(round)
        // nor while a previous round is having a juror added (the job's onRetry refuses it too)
        if (!RoundDistributionHandler.listOnly(j)) RoundDistributionHandler.requireNoPreviousRebalance(round)
        jobs.retry(j.id, explicit = false) match {
          case Right(job) => Resumed(job)
          case Left(refused) if refused.messageKey == JobScheduler.AttemptsRefusedKey => GivenUp(j)
          // a rebalance of a previous round queued since the check above: its refusal, as the check's
          case Left(refused) if refused.messageKey == RoundDistributionHandler.PreviousRebalancing =>
            throw RoundDistributionHandler.ExcludeRoundsRefused(
              RoundDistributionHandler.ExcludeRoundsRefusal(refused.messageKey, refused.args),
              s"a previous round of round $roundId is having a juror added: distribute again when that has finished")
          case Left(_)    => AlreadyRunning(distributionJob(roundId).getOrElse(j))
        }
      case _ =>
        RoundDistributionHandler.previousRounds(round)
        RoundDistributionHandler.requireNoExcluderBusy(round)
        RoundDistributionHandler.excludedRounds(round)
        // a round in review gets the new files only: distributing stays the review's action
        // (round-editing plan §6.2). A creation never completed keeps its intent: held in review
        // after its fill, or distributed after it (D4); a cancelled one is so reinstated (round
        // lineage decision 7), by `createdBy`. A distributed round gets them distributed after.
        val distributeAfter =
          if (round.inReview) false
          else if (round.distributing) RoundService.creationIntent(latest)
          else true
        // the jurors only where the files go to them now: a fill held in review needs none yet
        if (distributeAfter) DistributeImages.checkJury(round, User.distributionJurors(roundId))
        // the top-up alone: a distribute (its `assign`, then `topup`), no fill reading new files
        val topUpOnly = topUpWaiting && !newFiles && titles.isEmpty
        if (topUpOnly)
          require(!round.distributing && round.distribution > 0,
            s"Round $roundId: no files to top up (not a distributed round of N > 0 jurors per file)")
        try {
          val reinstating = round.distributing && round.lifecycle == Round.Status.Cancelled
          val job = WriteTx.write { implicit session =>
            if (topUpOnly)
              enqueueOrFail(round, completingCreation = false, createdBy, mode = RoundDistributionHandler.Mode.Distribute,
                topUpWaiting = true)
            else {
              // a reinstated creation's status (cancelled to draft, by createdBy) in the round's settings
              // log (RoundChanges): read under the round's lock, before and after the queueing writes it,
              // for any creation not completed (a cancel committed after the read above included); an
              // unchanged status writes no entry
              var before: Option[RoundChanges.Snapshot] = None
              val queued = enqueueOrFail(round, completingCreation = round.distributing, createdBy, titles,
                listOnly = titles.nonEmpty && !newFiles && !round.distributing,
                distributeAfter = distributeAfter, fromReview = round.inReview,
                // the waiting files are topped up by the distribute after the fill: only a distributed round's
                topUpWaiting = topUpWaiting && distributeAfter && !round.distributing && round.distribution > 0,
                underLocks = s => before = if (round.distributing) RoundChanges.snapshot(roundId)(s).map(_._2) else None)
              for (b <- before; (r, after) <- RoundChanges.snapshot(roundId))
                RoundChanges.record(ContestChangeJdbc.RoundChanged, Some(b), r, after, createdBy, RoundChanges.Path.Reinstate)
              queued
            }
          }
          if (reinstating)
            logger.info(s"Round $roundId: reinstated from cancelled by user ${createdBy.orNull} (job ${job.id}, " +
              (if (distributeAfter) "distributed after its fill)" else "held for review after its fill)"))
          jobs.started(job.id)
          Started(JobJdbc.findById(job.id).getOrElse(job))
        } catch {
          case AlreadyDistributing(active) => AlreadyRunning(active)
        }
    }
  }

  /** The review page's Distribute (P3): the round's files given to its jurors, as a job of mode
    * `distribute`; the round opens to them when it ends. Only a round in review
    * ([[Round.inReview]], read under its lock) with no job queued or running.
    *
    * The jurors are chosen there (a round created for review may have none yet): `jurors` and
    * `distribution` (jurors per file, 0 for all), when given, replace the round's (its `round_user`
    * rows and `distribution`) under its lock, in the job's transaction; without them the round's
    * own. Refused ([[RoundService.ReviewDistribution.Refused]], nothing written) with no juror, more
    * jurors per file than jurors, a change of jurors once the round has a vote (a partial
    * distribution: Retry it instead), a change of jurors when the round's jurors are no longer those
    * the page showed (`shownJury`, [[RoundService.jurySignature]]: a juror added since would be
    * dropped), a re-apply of its filters not finished (failed or cancelled: its files half chosen),
    * or, unless `confirmPrevChanged`, previous rounds' votes changed since the fill (D18).
    */
  def distributeReviewed(
      roundId: Long,
      createdBy: Option[Long],
      jurors: Option[Seq[Long]] = None,
      distribution: Option[Int] = None,
      confirmPrevChanged: Boolean = false,
      shownJury: Option[String] = None,
      rates: Option[db.scalikejdbc.Rates] = None,
      averageRule: Option[org.intracer.wmua.AverageRule] = None,
      criteria: Option[Seq[Long]] = None,
      worstMark: Option[Boolean] = None,
      shownRating: Option[String] = None
  ): RoundService.ReviewDistribution =
    dao.findById(roundId) match {
      case None => RoundService.ReviewDistribution.NotInReview
      case Some(round) =>
        try {
          // the contest's jurors among those posted, deleted accounts left out (as the picker lists them)
          val chosen = jurors.map(ids => User.loadJurors(round.contestId, ids).filter(_.deletedAt.isEmpty).distinct
            .sortBy(_.getId))
          // the round as read in the transaction, its previous rounds locked with it (enqueueOrFail, id
          // order); read again under the locks: changed in between (a re-apply ending), once more
          def attempt(left: Int): Job =
            try WriteTx.write { implicit session =>
              val fresh = dao.findById(roundId).getOrElse(throw NotInReviewRefused)
              val perFile = distribution.getOrElse(fresh.distribution)
              enqueueOrFail(fresh.copy(distribution = perFile), completingCreation = true, createdBy,
                mode = RoundDistributionHandler.Mode.Distribute, requireInReview = true,
                // the jurors per file it sets, in the round's settings log (RoundChanges)
                underLocks = s => RoundChanges.logged(roundId, createdBy, ContestChangeJdbc.RoundChanged,
                  RoundChanges.Path.Jurors)({
                    reviewJury(fresh, chosen, perFile, confirmPrevChanged, shownJury)(s)
                    val current = dao.findById(roundId).getOrElse(throw NotInReviewRefused)
                    val oldCriteria = db.scalikejdbc.RoundCriterion.idsOf(roundId)(s)
                    if (shownRating.exists(_ != RoundService.ratingSignature(current, oldCriteria)))
                      throw ReviewRefused("round.review.rating.changed", Nil)
                    val selected = rates.getOrElse(current.rates)
                    val rated = current.copy(rates = selected).isRated
                    val selectedCriteria = if (!rated) {
                      if (selected != current.rates) Nil else oldCriteria
                    } else criteria.getOrElse(oldCriteria)
                    val changedRating = selected != current.rates
                    val changedCriteria = selectedCriteria != oldCriteria
                    if (changedRating && (RoundImageJdbc.hasVotes(roundId)(s) ||
                        controllers.RoundController.takenFrom(roundId, Round.findByContestIn(current.contestId)(s))))
                      throw ReviewRefused(controllers.RoundController.RatesLockedKey, Nil)
                    if (changedCriteria) {
                      if (RoundImageJdbc.hasVotes(roundId)(s))
                        throw ReviewRefused(controllers.RoundController.RatesLockedKey, Nil)
                      org.intracer.wmua.Criterion.validationError(selectedCriteria, rated,
                        ids => db.scalikejdbc.CriterionJdbc.existing(ids)(s)).foreach(key =>
                          throw ReviewRefused(key, Seq("1", org.intracer.wmua.Criterion.MaxPerRound.toString)))
                      db.scalikejdbc.RoundCriterion.updateIn(roundId, selectedCriteria)(s).left.foreach(r =>
                        throw ReviewRefused(r.messageKey, Nil))
                    }
                    val selectedWorst = if (changedRating && !rated) false else worstMark.getOrElse(
                      if (changedCriteria && selectedCriteria.nonEmpty) false else current.worstMark)
                    if (worstMark.contains(true) && selectedWorst && (!rated || selectedCriteria.nonEmpty))
                      throw ReviewRefused(controllers.EditRound.WorstMarkRatedOnly, Nil)
                    if (changedRating) Round.updateRates(roundId, selected)(s)
                    if (averageRule.isDefined || selectedWorst != current.worstMark)
                      Round.updateRound(roundId, current.copy(averageRule = averageRule.getOrElse(current.averageRule),
                        worstMark = selectedWorst))(s)
                  })(s))
            } catch {
              case StaleRound if left > 0 => attempt(left - 1)
            }
          val job = attempt(1)
          logger.info(s"Round $roundId: distributing its reviewed images (job ${job.id}), by user ${createdBy.orNull}")
          jobs.started(job.id)
          RoundService.ReviewDistribution.Started(JobJdbc.findById(job.id).getOrElse(job))
        } catch {
          case AlreadyDistributing(active) => RoundService.ReviewDistribution.Running(active)
          case NotInReviewRefused          => RoundService.ReviewDistribution.NotInReview
          case ReviewRefused(key, args)    => RoundService.ReviewDistribution.Refused(key, args)
          case StaleRound                  =>
            distributionJob(roundId).fold[RoundService.ReviewDistribution](RoundService.ReviewDistribution.NotInReview)(
              RoundService.ReviewDistribution.Running(_))
          // a previous round having a juror added (its rebalance would fail on the freeze)
          case refused: RoundDistributionHandler.ExcludeRoundsRefused =>
            RoundService.ReviewDistribution.Refused(refused.refusal.key, refused.refusal.args)
        }
    }

  /** Under the round's lock, before its distribute is queued: the jurors it goes to (`chosen`, else
    * the round's), refused ([[ReviewRefused]]) as [[distributeReviewed]] says, then the chosen ones
    * and `perFile` written.
    */
  private def reviewJury(locked: Round, chosen: Option[Seq[User]], perFile: Int, confirmPrevChanged: Boolean,
      shownJury: Option[String])(implicit session: DBSession): Unit = {
    val roundId = locked.getId
    val round = dao.findById(roundId).getOrElse(throw NotInReviewRefused)
    // what enqueueOrFail locked and freezes must be the round's now
    if (round.previousIds != locked.previousIds) throw StaleRound
    val number = round.number.toString
    // a re-apply that didn't finish (failed or cancelled) left its filters saved and its files half
    // chosen: re-apply again or Retry it first (an active one is refused as running)
    RoundService.latestJob(round).filter(j => RoundDistributionHandler.reapplyMode(j) && !j.isDone && !j.isActive)
      .foreach(_ => throw ReviewRefused(ReapplyUnfinishedKey, Seq(number)))
    val stored = User.distributionJurors(roundId).sortBy(_.getId)
    val jury = chosen.getOrElse(stored)
    if (jury.isEmpty) throw ReviewRefused(NoJurorsKey, Seq(number))
    if (perFile < 0 || perFile > jury.size) throw ReviewRefused(TooManyPerFileKey, Seq(perFile.toString, jury.size.toString))
    val changed = chosen.exists(_.flatMap(_.id) != stored.flatMap(_.id)) || perFile != round.distribution
    // the page showed other jurors than the round has now (a juror added since): its picker would drop them
    if (chosen.exists(_.flatMap(_.id) != stored.flatMap(_.id)) && shownJury.exists(_ != RoundService.jurySignature(roundId)))
      throw ReviewRefused(JurorsChangedKey, Seq(number))
    // a partial distribution (a failed or cancelled distribute) gave files to the jurors: they stay
    if (changed && RoundImageJdbc.hasVotes(roundId)) throw ReviewRefused(JurorsLockedKey, Seq(number))
    if (!confirmPrevChanged && RoundService.prevChanged(round)) throw ReviewRefused(PrevChangedKey, Seq(number))
    chosen.filter(_ => changed).foreach { users =>
      val ids = users.flatMap(_.id)
      // each chosen juror's users row share-locked and re-checked (A-4): one retired since the page
      // was read refuses the review's jury as changed (shown again without it)
      if (User.lockLive(ids).size != ids.distinct.size) throw ReviewRefused(JurorsChangedKey, Seq(number))
      // an account in a join (J-2) is added to no round: read after the shared lock, which the
      // join's start takes exclusively
      AccountJoinJdbc.joining(ids).headOption.foreach(id => throw ReviewRefused(JoiningKey, Seq(number, id.toString)))
      sql"DELETE FROM round_user WHERE round_id = $roundId AND user_id NOT IN ($ids)".update()
      users.foreach { u =>
        sql"""INSERT INTO round_user (user_id, round_id, role, active)
              VALUES (${u.getId}, $roundId, ${u.contestRole.getOrElse(User.JURY_ROLE)}, TRUE)
              ON DUPLICATE KEY UPDATE active = TRUE""".update()
      }
    }
    if (perFile != round.distribution)
      sql"UPDATE round SET distribution = $perFile, created_at = created_at WHERE id = $roundId".update()
    if (changed)
      logger.info(s"Round $roundId: jurors set on its review to ${jury.flatMap(_.id).mkString(",")}, $perFile per file")
  }

  /** Whether the round can have its filters re-applied now (P3-5): in review, no vote in it, no job
    * of it queued or running. A plain read: [[reapplyFilters]] checks again under the round's lock.
    */
  def reapplyable(round: Round): Boolean =
    round.inReview && !RoundImageJdbc.hasVotes(round.getId) &&
      JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId)).isEmpty

  /** "Re-apply filters" (P3-5): the round held in review gets the filters of `updated` (the round
    * form's), and a `fill` job in re-apply mode: the round's `fill` files that no longer pass are
    * deleted (D16), the new matches added, a top-N ranked again from scratch (D17); the round stays
    * in review. Only the filters of `updated` are taken (the settings that choose files,
    * [[Round.updateFilters]]'s); the rest stays the stored round's. Under the round's lock (and its
    * new previous rounds', in id order):
    * refused unless it is in review with no job ([[RoundService.ReviewDistribution.NotInReview]],
    * [[RoundService.ReviewDistribution.Running]]) and no vote; previous rounds of another contest, of
    * mixed rating, the round itself or a round taking files from it are refused before anything is
    * written. Authorization is the caller's: the round's contest's admins and root (D20).
    *
    * @param titles a pasted list of files ("Add files"), added after the filters' files
    * @param alsoWrite the caller's other writes of the round, under its lock: done only if the re-apply
    *                  is queued
    */
  def reapplyFilters(updated: Round, createdBy: Option[Long], titles: Seq[String] = Nil,
      alsoWrite: DBSession => Unit = _ => (), topImagesBasis: Option[JsObject] = None): RoundService.ReviewDistribution =
    dao.findById(updated.getId) match {
      case None => RoundService.ReviewDistribution.NotInReview
      case Some(stored) =>
        val roundId = stored.getId
        val u = updated
        val merged = stored.copy(previous = u.previous, prevSelectedBy = u.prevSelectedBy,
          prevRejectedBy = u.prevRejectedBy, prevRejectedMode = u.prevRejectedMode, prevMinAvgRate = u.prevMinAvgRate,
          category = u.category, excludeCategory = u.excludeCategory, categoryClause = u.categoryClause,
          regions = u.regions, minImageSize = u.minImageSize, minMpx = u.minMpx, monuments = u.monuments,
          topImages = u.topImages, topBinaryOrder = u.topBinaryOrder, specialNomination = u.specialNomination,
          mediaType = u.mediaType, excludeRounds = u.excludeRounds)
        val canonical = merged.withFixedCategories.withCanonicalCategoryRule
        // previous rounds of its contest, one rating (refused by a requirement, which the form shows)
        val prevRounds = RoundDistributionHandler.previousRounds(canonical)
        // as at creation, only rounds created before it: never itself, nor a round taking files from it
        if (canonical.previousIds.exists(_ >= roundId))
          RoundService.ReviewDistribution.Refused(PreviousSelfKey, Seq(stored.number.toString))
        else {
          val configured = canonical.withExplicitTopOrder(prevRounds.exists(_.isBinary))
          try {
            val job = WriteTx.write { implicit session =>
              // the new previous rounds are locked and frozen while it runs; the old ones were released
              // at the fill's end (D18) and aren't read
              enqueueOrFail(configured,
                completingCreation = true, createdBy, titles, fromReview = true, requireInReview = true, reapply = true,
                topNChecked = topImagesBasis.isDefined,
                underLocks = { s =>
                  // D16: no file is deleted once the round has a vote
                  if (RoundImageJdbc.hasVotes(roundId)(s)) throw ReviewRefused(ReapplyVotedKey, Seq(stored.number.toString))
                  // what the filters and the caller's writes change, in the round's settings log
                  // (RoundChanges), committed only with them and the job
                  RoundChanges.logged(roundId, createdBy, ContestChangeJdbc.RoundFiltersReapplied,
                    RoundChanges.Path.Reapply, topImagesBasis) {
                    Round.updateFilters(roundId, configured)(s)
                    // the caller's other changes (the form's name, score rule), committed only with the job
                    alsoWrite(s)
                  }(s)
                })
            }
            logger.info(s"Round $roundId: filters re-applied in review (job ${job.id}), by user ${createdBy.orNull}")
            jobs.started(job.id)
            RoundService.ReviewDistribution.Started(JobJdbc.findById(job.id).getOrElse(job))
          } catch {
            case AlreadyDistributing(active) => RoundService.ReviewDistribution.Running(active)
            case NotInReviewRefused          => RoundService.ReviewDistribution.NotInReview
            case ReviewRefused(key, args)    => RoundService.ReviewDistribution.Refused(key, args)
          }
        }
    }

  /** [[distributeNewFiles]], waiting for it (scripts, tests).
    *
    * @return the number of images this call distributed (0 if it found a distribution
    *         already running, which it waited for)
    */
  def distributeNewImages(roundId: Long): Int = {
    val start = distributeNewFiles(roundId)
    val done = jobs.await(start.job.id)
    checkFinished(roundId, awaitWithFollowUp(roundId, start.job.id))
    start match {
      case _: AlreadyRunning => 0
      case _                 => RoundDistributionHandler.imagesAdded(done).toInt
    }
  }

  /** The job, waited for, and then the distribute its fill queued as it ended (D4), if any. */
  private def awaitWithFollowUp(roundId: Long, jobId: Long): Job = {
    val done = jobs.await(jobId)
    if (done.isDone && RoundDistributionHandler.distributeAfter(done))
      distributionJob(roundId).filter(_.id > jobId).fold(done)(next => jobs.await(next.id))
    else done
  }

  private def checkFinished(roundId: Long, job: Job): Unit =
    if (!job.isDone)
      throw RoundDistributionFailed(roundId, job.failure.fold(s"the distribution is ${job.status}")(_.describe))

  /** "Count new files": the number of images [[distributeNewFiles]] would add now. */
  def newFilesCount(roundId: Long): Long = distribution.countNew(roundId)

  /** The round form's cut picker (regional winners plan §10.4) of `round` (a stored round, or the
    * form's settings as posted): the files its cut would take from now, ranked as its distribution
    * ranks them, with the candidate cuts of the main round's window and the form's `chosen` N. Ranked
    * without the round's score threshold (`prevMinAvgRate`): it is the cut itself (decided 2026-11-26).
    * `skipDqOf`: a re-apply's round, whose disqualified files take no place. None without previous
    * rounds. Read only; may read Commons (a category filter's files), as "Count new files" does.
    */
  def cutPicker(round: Round, chosen: Option[Int], skipDqOf: Option[Long] = None,
      includeVoteChoices: Boolean = false): Option[CutPicker] = {
    val canonical = round.copy(prevMinAvgRate = None).withFixedCategories.withCanonicalCategoryRule
    // no file kept whole: the control shows no file (decided 2026-11-26: no cut gallery)
    distribution.topNRanking(canonical, keep = 0, skipDqOf, includeVoteChoices).map { ranking =>
      // a criteria round's criteria: its score range and its files' scores are their sums
      val prev = ranking.prevRounds.map { p =>
        if (!p.hasCriteria) p
        else {
          val ids = db.scalikejdbc.RoundCriterion.idsOf(p.getId)
          p.copy(criterionIds = ids, criteriaCount = ids.size.max(1))
        }
      }
      val ranges = prev.filterNot(_.isBinary).map(p =>
        CutPicker.range(p, if (p.distribution > 0) p.distribution.toLong else p.numberOfAssignedJurors))
      CutPicker.build(ranking.copy(prevRounds = prev), ranges, chosen)
    }
  }

  /** Whether the picker's cuts of `round` may be shown in its previous round's rating view, to `viewer`
    * ([[CutPicker.viewable]] and the data it can't see, [[RoundDistributionHandler.cutViewData]]): the
    * caller adds the viewer's rule (GalleryService.othersVotesVisible).
    */
  def cutViewable(round: Round, picker: CutPicker, skipDqOf: Option[Long]): Boolean =
    CutPicker.viewable(round, picker.ranking) &&
      picker.ranking.prevRounds.headOption.flatMap(_.id).exists(RoundDistributionHandler.cutViewData(_, skipDqOf, picker.ranking))

  /** The round's previous rounds, in its order, looked up in the round's own contest
    * only: another contest's rounds would be frozen and their images copied. An id not
    * found there is left out, which the callers' "not all found" check rejects.
    */
  def previousRounds(round: Round): Seq[Round] =
    if (round.previousIds.isEmpty) Nil
    else {
      val found = dao.findByIds(round.contestId, round.previousIds)
      round.previousIds.flatMap(id => found.find(_.id.contains(id)))
    }

  /** The round statistics page's numbers ([[RoundService.roundStat]]). */
  def getRoundStat(roundId: Long, round: Round, withDeletedFiles: Boolean = false): RoundStat =
    RoundService.roundStat(dao, roundId, round, withDeletedFiles)

  /** Starts or stops a round (and stops `prevRoundIds`, logged for `by` like the round's own change, RoundJurorChanges). A frozen round takes the change
    * when its distribution ends; a round still being created can't be started.
    *
    * @return false if the round can't be started: it is still being distributed
    */
  def setCurrentRound(prevRoundIds: Seq[Long], round: Round, by: Option[Long] = None): Boolean = {
    logger.info(
      s"Setting current round ${if (prevRoundIds.nonEmpty) s"from ${prevRoundIds.mkString(", ")}" else ""} to ${round.getId}"
    )

    prevRoundIds.foreach(rId => Round.setActiveGuarded(rId, active = false, by))
    Round.setActiveGuarded(round.getId, active = round.active, by)
  }
}

object RoundService {

  /** The numbers of a round's statistics (the statistics page, the edit page's stat table, GraphQL's
    * `roundStat`): each juror's votes by rate, the round's files, a binary round's files by rate.
    *
    * @param withDeletedFiles count the round's files deleted on Commons too: only the
    *                         statistics page (and GraphQL) shows them, not the edit page's stat table
    */
  def roundStat(dao: RoundRepo, roundId: Long, round: Round, withDeletedFiles: Boolean = false): RoundStat = {
    val rounds = dao.findByContest(round.contestId)

    val statRows: Seq[RoundStatRow] = dao.roundUserStat(roundId)

    val byJuror: Map[Long, Seq[RoundStatRow]] =
      statRows.groupBy(_.juror).filter { case (juror, rows) =>
        rows.map(_.count).sum > 0
      }

    val byUserCount = byJuror.view.mapValues(_.map(_.count).sum).toMap

    val byUserRateCount = byJuror.view.mapValues { v =>
      v.groupBy(_.rate)
        .view
        .mapValues {
          _.headOption.map(_.count).getOrElse(0)
        }
        .toMap
    }.toMap

    // a binary round's files with at least one vote of each rate: the stat table's footer
    val totalByRate =
      if (round.isBinary) dao.imageCountsByRate(roundId) else Map.empty[Int, Int]
    val total = RoundImageCounts
      .get(Seq(roundId))(ImageJdbc.imageCountByRounds)
      .getOrElse(roundId, 0)

    val roundUsers = RoundUser.byRoundId(roundId).groupBy(_.userId)
    val jurors = User
      .findByContest(round.contestId)
      .filter(_.id.exists(byUserCount.contains))
      .map(u => u.copy(active = roundUsers.get(u.getId).flatMap(_.headOption.map(_.active))))

    val (deletedFiles, deletedCounted) =
      if (withDeletedFiles) ImageJdbc.roundDeletedFileCounts(roundId) else (0, 0)

    RoundStat(jurors, round, rounds, byUserCount, byUserRateCount, total, totalByRate, deletedFiles, deletedCounted)
  }

  /** enqueueOrFail's refusal of a distribute of a round not in review (read under its locks). */
  private case object NotInReviewRefused extends RuntimeException("not in review", null, false, false)

  /** distributeReviewed's round changed between its read and its locks: read again. */
  private case object StaleRound extends RuntimeException("stale round", null, false, false)

  /** A review action refused under the round's lock: its message key and arguments. */
  private final case class ReviewRefused(key: String, args: Seq[String])
      extends RuntimeException(key, null, false, false)

  /** Distribute refused: the round has no juror (chosen, or its own). */
  val NoJurorsKey = "round.review.distribute.no.jurors"
  /** Distribute refused: more jurors per file than jurors. */
  val TooManyPerFileKey = "round.review.distribute.too.many.per.file"
  /** Distribute refused: the round's jurors changed since the page showed them (M1 of the review). */
  val JurorsChangedKey = "round.review.distribute.jurors.changed"
  /** A chosen juror's account is in an account join (J-2): no round_user row for it meanwhile. */
  val JoiningKey = "round.review.distribute.joining"
  /** Distribute refused: a re-apply of the filters failed or was cancelled. */
  val ReapplyUnfinishedKey = "round.review.distribute.reapply.unfinished"
  /** Distribute refused: the jurors changed after a partial distribution gave them files. */
  val JurorsLockedKey = "round.review.distribute.jurors.locked"
  /** Distribute refused: previous rounds' votes changed since the fill, not confirmed (D18). */
  val PrevChangedKey = "round.review.prev.changed.confirm"
  /** Re-apply refused: the round has a vote (D16). */
  val ReapplyVotedKey = "round.reapply.voted"
  /** Re-apply refused: the round itself, or one taking files from it, as a previous round. */
  val PreviousSelfKey = "round.reapply.previous.self"

  /** The round's latest distribution job, if any. */
  def latestJob(round: Round): Option[Job] =
    JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(round.getId))
      .get(round.getId)

  /** The round's latest fill, if its latest distribution job is one, done: what the review shows of
    * it (the re-apply diff, the previous rounds' fingerprint).
    */
  def latestFill(round: Round): Option[Job] =
    latestJob(round).filter(j => j.isDone && RoundDistributionHandler.fillOnly(j))

  /** The round's jurors as the review page shows them, active and stopped ids
    * ([[services.distribution.RoundRebalanceService.signature]]'s form): its Distribute posts it back.
    */
  def jurySignature(roundId: Long): String = {
    val (active, stopped) = RoundUser.byRoundId(roundId).partition(_.active)
    active.map(_.userId).distinct.sorted.mkString(",") + "|" + stopped.map(_.userId).distinct.sorted.mkString(",")
  }

  /** Rating settings shown on the jurors form, compared again under the round lock. */
  def ratingSignature(round: Round, criteria: Seq[Long]): String =
    s"${round.rates.id}|${round.averageRule.param}|${round.worstMark}|${criteria.mkString(",")}"

  /** D18: the round is in review and its previous rounds' votes changed since its latest fill read
    * them ([[RoundDistributionHandler.prevFingerprint]]). False without previous rounds, or for a
    * fill from before the fingerprint was recorded.
    */
  def prevChanged(round: Round)(implicit session: DBSession = scalikejdbc.AutoSession): Boolean =
    round.inReview && round.previousIds.nonEmpty &&
      latestFill(round).flatMap(j => RoundDistributionHandler.stateOf(j).prevFingerprint).exists(recorded =>
        !RoundDistributionHandler.prevFingerprint(round.previousIds).contains(recorded))

  /** What a creation never completed wanted after its fill, by its latest job (`latest`): held in
    * review (D3), or distributed after it (D4); a job from before P3 (no mode) distributed in one
    * pass, so after; none known, held in review (the form's default).
    */
  def creationIntent(latest: Option[Job]): Boolean =
    latest.exists(j => (j.params \ "distributeAfter").asOpt[Boolean].getOrElse(RoundDistributionHandler.modeOf(j).isEmpty))

  /** What the review page's Distribute did ([[RoundService.distributeReviewed]]). */
  sealed trait ReviewDistribution
  object ReviewDistribution {
    final case class Started(job: Job) extends ReviewDistribution
    final case class Running(job: Job) extends ReviewDistribution
    case object NotInReview extends ReviewDistribution
    final case class Refused(messageKey: String, args: Seq[Any]) extends ReviewDistribution
  }

  /** The outcome of a round form submission. */
  sealed trait Submitted {
    def round: Round

    /** The round's distribution job, if it has one. */
    def job: Option[Job]

    /** Images distributed so far by the job this submission started or resumed. */
    def imagesAdded: Int
  }

  /** A new round, created; its distribution started. */
  final case class Created(round: Round, createdJob: Job) extends Submitted {
    def job: Option[Job] = Some(createdJob)
    def imagesAdded: Int = RoundDistributionHandler.imagesAdded(createdJob).toInt
  }

  /** The form was submitted before: no new round. A failed distribution was resumed. */
  final case class Resubmitted(round: Round, job: Option[Job], resumed: Boolean) extends Submitted {
    def imagesAdded: Int = if (resumed) job.fold(0)(RoundDistributionHandler.imagesAdded(_).toInt) else 0

    /** Its distribution was given up after its attempts: not resumed, it waits for a Retry. */
    def givenUp: Boolean = !resumed && job.exists(_.failure.exists(_.key == JobError.AttemptsKey))

    /** The given-up job's logged reference, for the message. */
    def reference: String = job.flatMap(_.failure).flatMap(_.reference).getOrElse("")

    def message: String =
      s"Round ${round.number} (${round.description}) was already created from this form; " +
        "not creating it a second time" +
        (if (resumed) ". Its distribution was resumed."
         else if (job.exists(_.isActive)) ". Its images are still being distributed."
         else if (givenUp) s". Its distribution was stopped after repeated crashes (ref=$reference), not resumed."
         else ".")

    /** [[message]] for the admin, with the round's number, its description and the given-up
      * job's reference as arguments.
      */
    def messageKey: String =
      if (resumed) "round.resubmitted.resumed"
      else if (job.exists(_.isActive)) "round.resubmitted.distributing"
      else if (givenUp) "round.resubmitted.attempts"
      else "round.resubmitted"
  }

  /** What "Distribute new files" did. */
  sealed trait DistributionStart { def job: Job }
  final case class Started(job: Job) extends DistributionStart
  final case class Resumed(job: Job) extends DistributionStart
  final case class AlreadyRunning(job: Job) extends DistributionStart

  /** The round's distribution was given up after its attempts: not resumed. */
  final case class GivenUp(job: Job) extends DistributionStart {
    def reference: String = job.failure.flatMap(_.reference).getOrElse("")
  }

  private final case class AlreadyDistributing(active: Job) extends RuntimeException(s"job ${active.id} is active")

  /** The round row was created but distributing its images failed (or was cancelled).
    * The round stays, hidden if it was being created, for a Retry or "Distribute new
    * files" to finish.
    */
  final case class RoundDistributionFailed(roundId: Long, reason: String)
      extends RuntimeException(
        s"Round $roundId was created but distributing its images failed: $reason. " +
          "Retry it on the rounds page, or with \"Distribute new files\"."
      )
}
