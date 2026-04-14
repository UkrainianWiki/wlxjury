package services

import controllers.RoundStat
import db.RoundRepo
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import db.scalikejdbc.{Round, RoundUser, SelectionJdbc, User}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Logging

import javax.inject.Inject

class RoundService @Inject() (distributeImages: DistributeImages, dao: RoundRepo) extends Logging {

  def createNewRound(round: Round, jurorIds: Seq[Long]): Round = {
    val numberOfRounds = dao.countByContest(round.contestId)
    val created = dao.create(round.copy(number = numberOfRounds + 1))

    val prevRound = created.previous.flatMap(dao.findById)
    val jurors = User.loadJurors(round.contestId, jurorIds)

    created.addUsers(
      jurors.map(u => RoundUser(created.getId, u.getId, u.roles.head, active = true))
    )

    distributeImages.distributeImages(created, jurors, prevRound)

    setCurrentRound(created.previous, created)

    created
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

    val totalByRate = if (round.isBinary) dao.roundRateStat(roundId).toMap else Map.empty[Int, Int]
    val total = SelectionQuery(roundId = Some(roundId), grouped = true).count()

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
    }
    SelectionJdbc.mergeRounds(targetRoundId = targetId, sourceRoundId = sourceId)
  }

  def setCurrentRound(prevRoundId: Option[Long], round: Round): Unit = {
    logger.info(
      s"Setting current round ${prevRoundId.fold("")(rId => s"from $rId")} to ${round.getId}"
    )

    prevRoundId.foreach(rId => Round.setActive(rId, active = false))
    Round.setActive(round.getId, active = round.active)
  }

}
