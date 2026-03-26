package db

import _root_.scalikejdbc.{AutoSession, DBSession}
import db.scalikejdbc.Round
import db.scalikejdbc.Round.RoundStatRow
import db.scalikejdbc.User.autoSession

trait RoundRepo {

  def countByContest(contestId: Long): Long

  def create(round: Round)(implicit session: DBSession = AutoSession): Round

  def findByContest(contest: Long): Seq[Round]

  def findById(id: Long)(implicit s: DBSession = autoSession): Option[Round]

  def findByIds(contestId: Long, roundIds: Seq[Long]): Seq[Round]

  def roundUserStat(roundId: Long): Seq[RoundStatRow]

  def roundRateStat(roundId: Long): Seq[(Int, Int)]

}
