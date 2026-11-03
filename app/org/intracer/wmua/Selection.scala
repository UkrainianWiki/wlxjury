package org.intracer.wmua

import java.time.ZonedDateTime

import db.scalikejdbc.{Round, SelectionJdbc, User}

case class Selection(pageId: Long, juryId: Long, roundId: Long,
                     var rate: Int = 0, id: Option[Long] = None,
                     createdAt: Option[ZonedDateTime] = None,
                     deletedAt: Option[ZonedDateTime] = None,
                     criteriaId: Option[Int] = None,
                     monumentId: Option[String] = None) extends HasId {

  def destroy() = SelectionJdbc.destroy(pageId, juryId, roundId)

}

object Selection {

  def apply(img: Image, juror: User, round: Round, rate: Int): Selection =
    Selection(img.pageId, juror.getId, round.getId, rate, monumentId = img.monumentId)

  def apply(img: Image, juror: User, round: Round): Selection = apply(img, juror, round, 0)
}
