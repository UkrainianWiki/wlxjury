package db

import org.intracer.wmua.{ContestJury, Image, ImageWithRating}
import _root_.scalikejdbc.{AutoSession, DBSession}

trait ImageRepo {

  def findByContestId(contestId: Long)(implicit session: DBSession = AutoSession): List[Image]

  def byRoundMerged(
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0,
      rated: Option[Boolean] = None,
      // the rows count their approvals and rejections apart
      tally: Boolean,
      // a rated round's (not `isBinary`): a row's rate sums the grades (a ✗ as 0), and the rated
      // are any vote (`SelectionQuery.grades`)
      grades: Boolean = false
  ): Seq[ImageWithRating]
}
