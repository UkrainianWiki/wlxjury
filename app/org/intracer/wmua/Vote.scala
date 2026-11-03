package org.intracer.wmua

import java.time.ZonedDateTime

import db.scalikejdbc.{Round, VoteJdbc, User}

case class Vote(pageId: Long, juryId: Long, roundId: Long,
                     var rate: Int = 0, id: Option[Long] = None,
                     createdAt: Option[ZonedDateTime] = None,
                     deletedAt: Option[ZonedDateTime] = None,
                     criteriaId: Option[Int] = None,
                     monumentId: Option[String] = None) extends HasId {


}

object Vote {

  def apply(img: Image, juror: User, round: Round, rate: Int): Vote =
    Vote(img.pageId, juror.getId, round.getId, rate, monumentId = img.monumentId)

  def apply(img: Image, juror: User, round: Round): Vote = apply(img, juror, round, 0)
}
