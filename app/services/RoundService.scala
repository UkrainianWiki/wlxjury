package services

import controllers.RoundStat
import db.RoundRepo
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.{ImageJdbc, Round, RoundImageCounts, RoundUser, SelectionJdbc, User}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Logging
import scalikejdbc.DB

import java.time.{Duration, ZonedDateTime}
import javax.inject.Inject
import scala.collection.concurrent.TrieMap
import scala.util.control.NonFatal

class RoundService @Inject() (distributeImages: DistributeImages, dao: RoundRepo) extends Logging {

  /** Creates the round and distributes its images.
    *
    * One creation per contest at a time, and a submission repeating a round created in
    * the last [[RoundService.DuplicateWindow]] is refused ([[RoundService.DuplicateRound]]):
    * a distribution can outlast the browser's or the proxy's wait, and the organizer's
    * resubmit would otherwise create a second copy of the round. A round without images
    * doesn't count: its distribution failed and was rolled back, so submitting it again
    * is a retry.
    */
  def createNewRound(round: Round, jurorIds: Seq[Long]): Round =
    RoundService.lock("contest", round.contestId).synchronized {
      val since = ZonedDateTime.now.minus(RoundService.DuplicateWindow)
      dao
        .findByContest(round.contestId)
        .find(r =>
          !r.createdAt.isBefore(since) && RoundService.sameSettings(r, round) &&
            SelectionJdbc.imageCountByRound(r.getId) > 0
        )
        .foreach(r => throw RoundService.DuplicateRound(r))
      create(round, jurorIds)
    }

  private def create(round: Round, jurorIds: Seq[Long]): Round = {
    val prevRounds = round.previousIds.flatMap(dao.findById)
    require(
      Round.sameRateType(prevRounds),
      s"previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}] must all be of the same rate type"
    )
    require(
      round.previousIds.size == prevRounds.size,
      s"previous rounds [${round.previousIds.mkString(", ")}] not all found: " +
        s"got [${prevRounds.flatMap(_.id).mkString(", ")}]"
    )

    val jurors = User.loadJurors(round.contestId, jurorIds).distinct
    require(
      jurors.nonEmpty,
      "no jurors selected for the round; nothing to distribute"
    )

    val numberOfRounds = dao.countByContest(round.contestId)
    val created = dao.create(round.copy(number = numberOfRounds + 1))

    // Everything past this point works on a round row that already exists: on failure
    // report it against `created` so the caller can send the admin to the round to
    // retry / delete it, instead of the round being silently orphaned.
    val added =
      try {
        created.addUsers(
          jurors.map(u => RoundUser(created.getId, u.getId, u.roles.head, active = true))
        )
        // Freeze the source rounds before distributing so jurors can't add selections
        // to them while the snapshot is being copied (which previously let images slip
        // through and land as "new files" right after creation).
        created.previousIds.foreach(rId => Round.setActive(rId, active = false))
        distributeAndVerify(created, prevRounds, jurors)
      } catch {
        case e: RoundService.RoundNotFullyDistributed =>
          // Round exists but is short; leave the source rounds frozen so the admin can
          // top it up with "Distribute new files" without images moving underneath.
          throw e
        case NonFatal(e) =>
          // Restore the source rounds and report against the created round id.
          prevRounds.foreach(r => Round.setActive(r.getId, active = r.active))
          logger.error(s"Round ${created.getId}: creation failed after the row was inserted", e)
          throw RoundService.RoundDistributionFailed(created.getId, RoundService.describe(e))
      }

    Round.setActive(created.getId, active = created.active)
    logger.info(
      s"Round ${created.getId} created for contest ${created.contestId}: " +
        s"$added images, ${jurors.size} jurors" +
        (if (prevRounds.nonEmpty) s", from previous rounds [${prevRounds.flatMap(_.id).mkString(", ")}]" else "")
    )

    created
  }

  /** Distributes the images that currently qualify for `round` but are not in it yet
    * (the "Distribute new files" action on an existing round) and verifies the result.
    *
    * @return the number of images added
    */
  def distributeNewImages(roundId: Long): Int =
    // one at a time per round: a resubmit waits, then finds nothing new to add
    RoundService.lock("round", roundId).synchronized(distributeNew(roundId))

  private def distributeNew(roundId: Long): Int = {
    val round = dao.findById(roundId).getOrElse(
      throw new NoSuchElementException(s"Round $roundId not found")
    )
    val prevRounds = round.previousIds.flatMap(dao.findById)
    require(
      round.previousIds.size == prevRounds.size,
      s"previous rounds [${round.previousIds.mkString(", ")}] not all found"
    )
    val jurors = User.findRoundJurors(roundId).distinct
    val added = distributeAndVerify(round, prevRounds, jurors)
    logger.info(s"Round $roundId: distributed $added new images")
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

  /** How long an identical round submission is taken for a resubmit of the same one. */
  val DuplicateWindow: Duration = Duration.ofMinutes(30)

  private val locks = TrieMap.empty[(String, Long), AnyRef]

  /** In-JVM lock per contest / round: the app runs as a single instance. */
  private def lock(kind: String, id: Long): AnyRef = locks.getOrElseUpdate((kind, id), new AnyRef)

  /** The settings a round is created with (everything the round form submits but its
    * jurors), to recognize a resubmitted creation.
    */
  def sameSettings(a: Round, b: Round): Boolean = {
    def settings(r: Round) = (
      r.name.map(_.trim).filter(_.nonEmpty), r.roles, r.distribution, r.rates.id, r.minMpx,
      r.previousIds.toSet, r.prevSelectedBy, r.prevMinAvgRate, r.category, r.excludeCategory,
      r.regionIds.toSet, r.minImageSize, r.monuments, r.topImages, r.specialNomination, r.mediaType
    )
    a.contestId == b.contestId && settings(a) == settings(b)
  }

  /** A round with the same settings was created moments ago: most likely this is the
    * same submission again (the first one outlasted the browser's wait).
    */
  final case class DuplicateRound(existing: Round)
      extends RuntimeException(
        s"Round ${existing.number} (${existing.description}) with the same settings was created at " +
          s"${existing.createdAt.toLocalTime.withNano(0)}; not creating it a second time. " +
          "Give the new round a different name to create another one."
      )

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
    * left in place (empty or partial) so the admin can retry "Distribute new files"
    * on it or delete it, rather than it being silently orphaned.
    */
  final case class RoundDistributionFailed(roundId: Long, reason: String)
      extends RuntimeException(
        s"Round $roundId was created but distributing its images failed: $reason. " +
          "Retry with the \"Distribute new files\" box below, or delete the round."
      )
}
