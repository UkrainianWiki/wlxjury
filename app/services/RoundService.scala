package services

import controllers.RoundStat
import db.RoundRepo
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.{ImageJdbc, Round, RoundImageCounts, RoundUser, SelectionJdbc, User}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Logging
import play.api.libs.json.Json
import scalikejdbc.{DB, DBSession}
import services.distribution.RoundDistributionHandler
import services.jobs.{Job, JobJdbc, JobScheduler, NewJob, Progress}

import javax.inject.Inject

class RoundService @Inject() (
    dao: RoundRepo,
    jobs: JobScheduler,
    distribution: RoundDistributionHandler
) extends Logging {

  import RoundService._

  /** Creates the round and distributes its images, waiting for the distribution (scripts,
    * the API, tests): [[submit]], then the job's end.
    *
    * @throws RoundService.RoundDistributionFailed if the round was created but its
    *                                              distribution failed
    */
  def createNewRound(round: Round, jurorIds: Seq[Long], submitToken: Option[String] = None): Round = {
    val submitted = submit(round, jurorIds, submitToken)
    submitted.job.foreach(j => checkFinished(submitted.round.getId, jobs.await(j.id)))
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
    * types, more jurors per image than jurors) are refused before anything is created.
    */
  def submit(
      round: Round,
      jurorIds: Seq[Long],
      submitToken: Option[String] = None,
      createdBy: Option[Long] = None
  ): Submitted =
    submitToken.flatMap(submitted(_, round.contestId)) match {
      case Some(existing) => resubmitted(existing)
      case None =>
        RoundDistributionHandler.previousRounds(round)
        val jurors = User.loadJurors(round.contestId, jurorIds).distinct
        require(jurors.nonEmpty, "no jurors selected for the round; nothing to distribute")
        require(
          round.distribution <= jurors.size,
          s"${round.distribution} jurors per image, but only ${jurors.size} jurors selected"
        )
        insert(round, jurors, submitToken, createdBy) match {
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

  /** A known submission again: no new round. A failed distribution is resumed; a running
    * one goes on.
    */
  private def resubmitted(existing: Round): Submitted =
    distributionJob(existing.getId) match {
      case Some(j) if j.isFailed =>
        jobs.retry(j.id) match {
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
      createdBy: Option[Long]
  ): Either[Round, (Round, Job)] = {
    val number = dao.countByContest(round.contestId) + 1
    try
      Right(DB.localTx { implicit session =>
        val created = dao.create(
          round.copy(number = number, submitToken = submitToken, active = false, distributing = true)
        )
        created.addUsersIn(jurors.map(u => RoundUser(created.getId, u.getId, u.contestRole.getOrElse(User.JURY_ROLE), active = true)))
        val job = enqueueOrFail(created, completingCreation = true, createdBy)
        (created, job)
      })
    catch {
      case e: java.sql.SQLIntegrityConstraintViolationException
          if submitToken.isDefined && Option(e.getMessage).exists(_.contains("rounds_submit_token")) =>
        Left(submitToken.flatMap(submitted(_, round.contestId)).getOrElse(throw e))
    }
  }

  /** Creates the round's distribution job in the caller's transaction, freezing its
    * previous rounds; throws [[AlreadyDistributing]] (rolling the transaction back, the
    * freeze included) if the round already has an active one.
    */
  private def enqueueOrFail(
      round: Round,
      completingCreation: Boolean,
      createdBy: Option[Long]
  )(implicit session: DBSession): Job = {
    val state = distribution.initialState(round)
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
          // for the record: the job reads the round, whose settings don't change once created
          "settings" -> Json.obj(
            "previous" -> round.previous,
            "distribution" -> round.distribution,
            "prevSelectedBy" -> round.prevSelectedBy,
            "prevMinAvgRate" -> round.prevMinAvgRate,
            "topImages" -> round.topImages,
            "regions" -> round.regions,
            "monuments" -> round.monuments,
            "category" -> round.category,
            "excludeCategory" -> round.excludeCategory,
            "minMpx" -> round.minMpx,
            "minImageSize" -> round.minImageSize,
            "mediaType" -> round.mediaType,
            "specialNomination" -> round.specialNomination
          )
        ),
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

  /** "Distribute new files": distributes to the round, in the background, the images that
    * qualify for it but aren't in it yet. A failed distribution of the round is resumed
    * instead; a running one goes on.
    */
  def distributeNewFiles(roundId: Long, createdBy: Option[Long] = None): DistributionStart = {
    val round = dao.findById(roundId).getOrElse(throw new NoSuchElementException(s"Round $roundId not found"))
    distributionJob(roundId) match {
      case Some(j) if j.isActive => AlreadyRunning(j)
      case Some(j) if j.isFailed =>
        jobs.retry(j.id) match {
          case Right(job) => Resumed(job)
          case Left(_)    => AlreadyRunning(distributionJob(roundId).getOrElse(j))
        }
      case _ =>
        RoundDistributionHandler.previousRounds(round)
        DistributeImages.checkJury(round, User.distributionJurors(roundId))
        try {
          val job = DB.localTx { implicit session =>
            enqueueOrFail(round, completingCreation = round.distributing, createdBy)
          }
          jobs.started(job.id)
          Started(JobJdbc.findById(job.id).getOrElse(job))
        } catch {
          case AlreadyDistributing(active) => AlreadyRunning(active)
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
    checkFinished(roundId, done)
    start match {
      case _: AlreadyRunning => 0
      case _                 => RoundDistributionHandler.imagesAdded(done).toInt
    }
  }

  private def checkFinished(roundId: Long, job: Job): Unit =
    if (!job.isDone)
      throw RoundDistributionFailed(roundId, job.error.getOrElse(s"the distribution is ${job.status}"))

  /** "Count new files": the number of images [[distributeNewFiles]] would add now. */
  def newFilesCount(roundId: Long): Long = distribution.countNew(roundId)

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

  def getRoundStat(roundId: Long, round: Round): RoundStat = {
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

    // The stat table reads only the selected count (rate 1) of a binary round;
    // "unrated" is total - selected.
    val totalByRate =
      if (round.isBinary) Map(1 -> dao.selectedImageCount(roundId)) else Map.empty[Int, Int]
    val total = RoundImageCounts
      .get(Seq(roundId))(ImageJdbc.imageCountByRounds)
      .getOrElse(roundId, 0)

    val roundUsers = RoundUser.byRoundId(roundId).groupBy(_.userId)
    val jurors = User
      .findByContest(round.contestId)
      .filter(_.id.exists(byUserCount.contains))
      .map(u => u.copy(active = roundUsers.get(u.getId).flatMap(_.headOption.map(_.active))))

    RoundStat(jurors, round, rounds, byUserCount, byUserRateCount, total, totalByRate)
  }

  def mergeRounds(contestId: Long, targetRoundId: Long, sourceRoundId: Long): Unit = {
    val rounds = dao.findByIds(contestId, Seq(targetRoundId, sourceRoundId))
    assert(rounds.size == 2)
    for {
      targetId <- rounds.find(_.id.contains(targetRoundId)).flatMap(_.id)
      sourceId <- rounds.find(_.id.contains(sourceRoundId)).flatMap(_.id)
    } {
      DB.localTx { implicit session =>
        SelectionJdbc.mergeRounds(targetRoundId = targetId, sourceRoundId = sourceId)
      }
      RoundImageCounts.invalidate(targetId, sourceId)
    }
  }

  /** Starts or stops a round (and stops `prevRoundIds`). A frozen round takes the change
    * when its distribution ends; a round still being created can't be started.
    *
    * @return false if the round can't be started: it is still being distributed
    */
  def setCurrentRound(prevRoundIds: Seq[Long], round: Round): Boolean = {
    logger.info(
      s"Setting current round ${if (prevRoundIds.nonEmpty) s"from ${prevRoundIds.mkString(", ")}" else ""} to ${round.getId}"
    )

    prevRoundIds.foreach(rId => Round.setActiveGuarded(rId, active = false))
    Round.setActiveGuarded(round.getId, active = round.active)
  }
}

object RoundService {

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

    def message: String =
      s"Round ${round.number} (${round.description}) was already created from this form; " +
        "not creating it a second time" +
        (if (resumed) ". Its distribution was resumed."
         else if (job.exists(_.isActive)) ". Its images are still being distributed."
         else ".")
  }

  /** What "Distribute new files" did. */
  sealed trait DistributionStart { def job: Job }
  final case class Started(job: Job) extends DistributionStart
  final case class Resumed(job: Job) extends DistributionStart
  final case class AlreadyRunning(job: Job) extends DistributionStart

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
