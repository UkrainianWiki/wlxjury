package org.intracer.wmua

import java.time.ZonedDateTime

import db.scalikejdbc.{Round, VoteJdbc, User}

/** A juror's vote on a file in a round. Its `rate`: 0 unrated, a yes/no round's 1 (yes) and -1
  * (no), a rated round's stars 1…N in stored units. Code asks what a rate means through
  * [[Vote.isPositive]], [[Vote.isRejected]], [[Vote.isUnrated]], [[Vote.isVoted]] and
  * [[Vote.grade]] (in SQL, `db.scalikejdbc.VoteSql`), not by comparing it.
  */
case class Vote(pageId: Long, juryId: Long, roundId: Long,
                     var rate: Int = 0, id: Option[Long] = None,
                     createdAt: Option[ZonedDateTime] = None,
                     deletedAt: Option[ZonedDateTime] = None,
                     criteriaId: Option[Int] = None,
                     monumentId: Option[String] = None) extends HasId {

  def isPositive: Boolean = Vote.isPositive(rate)

  def isRejected: Boolean = Vote.isRejected(rate)

  def isUnrated: Boolean = Vote.isUnrated(rate)

  def isVoted: Boolean = Vote.isVoted(rate)

  def grade: Int = Vote.grade(rate)
}

object Vote {

  /** Not rated yet: a new vote's rate, and a cleared one's. */
  val Unrated = 0

  /** A yes/no round's yes. */
  val Selected = 1

  /** A yes/no round's no. */
  val Rejected = -1

  /** A yes/no round's yes, or a rated round's stars (in a criteria round, every criterion rated). */
  def isPositive(rate: Int): Boolean = rate > 0

  /** A yes/no round's no. */
  def isRejected(rate: Int): Boolean = rate < 0

  /** No vote yet, or a vote cleared: the two aren't told apart. */
  def isUnrated(rate: Int): Boolean = rate == 0

  /** Any vote: a yes or a no, or stars. */
  def isVoted(rate: Int): Boolean = rate != 0

  /** A rated round's grade: its stars, anything below as 0. */
  def grade(rate: Int): Int = math.max(rate, 0)

  def apply(img: Image, juror: User, round: Round, rate: Int): Vote =
    Vote(img.pageId, juror.getId, round.getId, rate, monumentId = img.monumentId)

  def apply(img: Image, juror: User, round: Round): Vote = apply(img, juror, round, 0)
}
