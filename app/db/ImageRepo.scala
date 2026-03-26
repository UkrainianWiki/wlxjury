package db

import org.intracer.wmua.{ContestJury, Image, ImageWithRating}
import _root_.scalikejdbc.{AutoSession, DBSession}

trait ImageRepo {

  def findByContestId(contestId: Long)(implicit session: DBSession = AutoSession): List[Image]

  def byRoundMerged(
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0,
      rated: Option[Boolean] = None
  ): Seq[ImageWithRating]
}
