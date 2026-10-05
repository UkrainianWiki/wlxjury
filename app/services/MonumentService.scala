package services

import db.scalikejdbc.MonumentJdbc
import org.scalawiki.wlx.{ImageDB, MonumentDB}
import org.scalawiki.wlx.dto.{Contest, ContestType, Monument, SpecialNomination}
import org.scalawiki.wlx.query.MonumentQuery
import org.scalawiki.wlx.stat.ContestStat
import play.api.Logging

import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

class MonumentService extends Logging {

  def updateLists(contest: Contest): Unit = {
    val monumentQuery = MonumentQuery.create(contest)
    val monuments = Await.result(monumentQuery.byMonumentTemplate(), 30.minutes)
    val monumentIds = monuments.map(_.id).toSet

    val stat =
      ContestStat(
        contest,
        contest.year,
        Some(new MonumentDB(contest, monuments.toSeq)),
        new ImageDB(contest, Nil),
        new ImageDB(contest, Nil)
      )
    val specialNominationMonuments =
      if (contest.contestType == ContestType.WLM) {
        Await
          .result(SpecialNomination.getMonumentsMap(SpecialNomination.nominations, stat), 30.minutes)
          .values
          .flatten
          .filterNot(m => monumentIds.contains(m.id))
      } else Nil

    val allValidMonuments = (monuments ++ specialNominationMonuments)
      .filter(_.id.matches("\\d{2}-\\d{3}-\\d{4}"))
      .toSeq

    logger.info(
      s"Updating monuments, " +
        s"from lists: ${monuments.size}, " +
        s"special nominations: ${specialNominationMonuments.size}," +
        s"allValidMonuments: ${allValidMonuments.size}"
    )

    MonumentJdbc.batchInsert(allValidMonuments)
  }

}
