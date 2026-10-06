package services

import controllers.RoundStat
import db.RoundRepo
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.{ImageJdbc, Round, RoundImageCounts, RoundUser, SelectionJdbc, User}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Logging
import scalikejdbc.DB

import javax.inject.Inject
import scala.util.control.NonFatal

class RoundService @Inject() (distributeImages: DistributeImages, dao: RoundRepo) extends Logging {

  /** Creates the round and distributes its images: [[submit]], returning the round. */
  def createNewRound(round: Round, jurorIds: Seq[Long], submitToken: Option[String] = None): Round =
    submit(round, jurorIds, submitToken).round

  /** Creates a round from the new-round form and distributes its images.
    *
    * With a submission token (the form's hidden field, see V59), one submission creates
    * one round: the token is stored with the round, under a unique index, in the
    * transaction that creates it. A resubmit of the same form (a double click, a browser
    * retry after a timeout, Back and Submit) finds that round instead of creating
    * another one, and finishes its distribution if it failed or stopped short
    * ([[fillRound]]). Two identical submits at once are decided by the unique index: the
    * one whose insert fails becomes the resubmit. Without a token (scripts, the API)
    * every call creates a round.
    *
    * Failures past the round's creation are reported against it
    * ([[RoundService.RoundDistributionFailed]], [[RoundService.RoundNotFullyDistributed]]),
    * so the organizer can finish it by submitting the form again, or with "Distribute
    * new files".
    */
  def submit(round: Round, jurorIds: Seq[Long], submitToken: Option[String] = None): RoundService.Submitted =
    submitToken.flatMap(submitted(_, round.contestId)) match {
      case Some(existing) => resume(existing)
      case None =>
        val prevRounds = validPreviousRounds(round)
        val jurors = User.loadJurors(round.contestId, jurorIds).distinct
        require(jurors.nonEmpty, "no jurors selected for the round; nothing to distribute")
        insert(round, jurors, submitToken) match {
          case Left(existing) => resume(existing)
          case Right(created) =>
            val added = fillOrReport(created.getId, completingCreation = true)
            logger.info(
              s"Round ${created.getId} created for contest ${created.contestId}: " +
                s"$added images, ${jurors.size} jurors" +
                (if (prevRounds.nonEmpty) s", from previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}]"
                 else "")
            )
            RoundService.Created(created, added)
        }
    }

  /** The round an earlier submission of this form created, if any. */
  private def submitted(token: String, contestId: Long): Option[Round] =
    Round.findBySubmitToken(token).map { round =>
      require(round.contestId == contestId, "this form was submitted for another contest")
      round
    }

  /** A known submission again: no new round; finish the existing one's distribution. */
  private def resume(existing: Round): RoundService.Submitted = {
    val added = fillOrReport(existing.getId, completingCreation = true)
    logger.info(s"Round ${existing.getId}: its form was submitted again; $added more images distributed")
    RoundService.Resubmitted(existing, added)
  }

  /** Inserts the round, its submission token and its jurors in one transaction, or
    * returns the round of the token if another submission of the same form won.
    */
  private def insert(round: Round, jurors: Seq[User], submitToken: Option[String]): Either[Round, Round] = {
    val number = dao.countByContest(round.contestId) + 1
    try
      Right(DB.localTx { implicit session =>
        val created = dao.create(round.copy(number = number, submitToken = submitToken))
        created.addUsersIn(jurors.map(u => RoundUser(created.getId, u.getId, u.roles.head, active = true)))
        created
      })
    catch {
      case e: java.sql.SQLIntegrityConstraintViolationException
          if submitToken.isDefined && Option(e.getMessage).exists(_.contains("rounds_submit_token")) =>
        Left(submitToken.flatMap(submitted(_, round.contestId)).getOrElse(throw e))
    }
  }

  /** [[fillRound]] for a round being created or resumed: a failure is reported against
    * the round, which stays for a resubmit or "Distribute new files" to finish.
    */
  private def fillOrReport(roundId: Long, completingCreation: Boolean): Int =
    try fillRound(roundId, completingCreation)
    catch {
      case e: RoundService.RoundNotFullyDistributed => throw e
      case NonFatal(e) =>
        logger.error(s"Round $roundId: distributing its images failed", e)
        throw RoundService.RoundDistributionFailed(roundId, RoundService.describe(e))
    }

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

  private def validPreviousRounds(round: Round): Seq[Round] = {
    val prevRounds = previousRounds(round)
    require(
      Round.sameRateType(prevRounds),
      s"previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] must all be of the same rate type"
    )
    require(
      round.previousIds.size == prevRounds.size,
      s"previous rounds [${round.previousIds.mkString(", ")}] not all found: " +
        s"got [${prevRounds.flatMap(_.id).mkString(", ")}]"
    )
    prevRounds
  }

  /** Distributes the images that currently qualify for `round` but are not in it yet
    * (the "Distribute new files" action on an existing round) and verifies the result.
    *
    * @return the number of images added
    */
  def distributeNewImages(roundId: Long): Int = {
    val added = fillRound(roundId, completingCreation = false)
    logger.info(s"Round $roundId: distributed $added new images")
    added
  }

  /** Distributes to the round every image that qualifies for it but isn't in it yet: the
    * one path for a new round, a resubmitted or retried creation and "Distribute new
    * files". It needs only the round's id, not the request, so it can also run as a
    * background job.
    *
    *  - One fill per round at a time (a per-round lock): a resubmit and "Distribute new
    *    files" on the same round don't fill it twice; the second finds nothing new.
    *  - The images go to the round's active jurors ([[User.distributionJurors]]).
    *  - The previous rounds are frozen (inactive) while their images are copied, so votes
    *    can't change what qualifies mid-copy. A creation leaves them frozen, as creating
    *    the next round always has; "Distribute new files" and any failure restore their
    *    earlier state.
    *
    * @return the number of images added
    */
  def fillRound(roundId: Long, completingCreation: Boolean): Int =
    RoundService.roundLock(roundId).synchronized {
      val round = dao.findById(roundId).getOrElse(throw new NoSuchElementException(s"Round $roundId not found"))
      val prevRounds = validPreviousRounds(round)
      val jurors = User.distributionJurors(roundId)

      def restorePrevious(): Unit = prevRounds.foreach(r => Round.setActive(r.getId, active = r.active))

      prevRounds.foreach(r => Round.setActive(r.getId, active = false))
      val added =
        try distributeAndVerify(round, prevRounds, jurors)
        catch {
          case NonFatal(e) =>
            restorePrevious()
            throw e
        }
      if (!completingCreation) restorePrevious()
      added
    }

  /** Distributes every image that qualifies for `round` but is not in it yet, then
    * checks that the round's distinct-image count grew by exactly that many. A
    * shortfall is logged and raised as [[RoundService.RoundNotFullyDistributed]] so
    * the caller can report it; the round is left in place for a follow-up top-up.
    *
    * @return the number of images added
    */
  private def distributeAndVerify(
      round: Round,
      prevRounds: Seq[Round],
      jurors: Seq[User]
  ): Int = {
    val toAdd = distributeImages.imagesByRound(round, prevRounds)
    if (toAdd.isEmpty) return 0

    // the verification counts uncached; the "after" count also refreshes the cache
    val before = SelectionJdbc.imageCountByRound(round.getId)
    distributeImages.distributeImages(round, toAdd, jurors)
    val after = RoundImageCounts.refresh(round.getId)(SelectionJdbc.imageCountByRound(round.getId).toInt)
    val actuallyAdded = (after - before).toInt

    if (actuallyAdded != toAdd.size) {
      logger.error(
        s"Round ${round.getId}: tried to distribute ${toAdd.size} images " +
          s"(from previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}]) " +
          s"but only $actuallyAdded landed. Retry 'Distribute new files' on the round."
      )
      throw RoundService.RoundNotFullyDistributed(
        round.getId,
        expected = before.toInt + toAdd.size,
        actual = before.toInt + actuallyAdded
      )
    }
    actuallyAdded
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

  def setCurrentRound(prevRoundIds: Seq[Long], round: Round): Unit = {
    logger.info(
      s"Setting current round ${if (prevRoundIds.nonEmpty) s"from ${prevRoundIds.mkString(", ")}" else ""} to ${round.getId}"
    )

    prevRoundIds.foreach(rId => Round.setActive(rId, active = false))
    Round.setActive(round.getId, active = round.active)
  }
}

object RoundService {

  /** The outcome of a round form submission. */
  sealed trait Submitted {
    def round: Round

    /** Images distributed by this submission. */
    def imagesAdded: Int
  }

  /** A new round, created and distributed. */
  final case class Created(round: Round, imagesAdded: Int) extends Submitted

  /** The form was submitted before: no new round; the existing round's distribution
    * finished, if it hadn't.
    */
  final case class Resubmitted(round: Round, imagesAdded: Int) extends Submitted {
    def message: String =
      s"Round ${round.number} (${round.description}) was already created from this form; " +
        "not creating it a second time" +
        (if (imagesAdded > 0) s". Distributed its remaining $imagesAdded images." else ".")
  }

  /** One lock per round for [[RoundService.fillRound]], striped over a fixed array: no map
    * that grows with every round. Rounds sharing a stripe only wait for each other.
    * In-JVM, matching the single app instance.
    */
  private val roundLocks = Array.fill(64)(new AnyRef)

  private def roundLock(roundId: Long): AnyRef =
    roundLocks(java.lang.Math.floorMod(roundId, roundLocks.length.toLong).toInt)

  private def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** Round exists but received fewer images than it should have. Recoverable by the
    * admin with the "Distribute new files" action; carries the numbers for the UI.
    */
  final case class RoundNotFullyDistributed(roundId: Long, expected: Int, actual: Int)
      extends RuntimeException(
        s"Round $roundId has $actual of $expected images after distribution. " +
          "Tick the \"Distribute new files\" box below to add the missing ones."
      )

  /** The round row was created but image distribution failed outright. The round is
    * left in place (empty or partial) so the admin can finish it, by submitting the form
    * again or with "Distribute new files", or delete it, rather than it being silently
    * orphaned.
    */
  final case class RoundDistributionFailed(roundId: Long, reason: String)
      extends RuntimeException(
        s"Round $roundId was created but distributing its images failed: $reason. " +
          "Retry with the \"Distribute new files\" box below, or delete the round."
      )
}
