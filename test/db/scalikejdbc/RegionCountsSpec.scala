package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{Image, Selection}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

/** A round gallery's region counts ([[SelectionQuery.regionCounts]]): a juror's, and the
  * whole round's, for each rate filter.
  */
class RegionCountsSpec extends Specification with BeforeAll with TestDb {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val round = 10L
  private val (juror1, juror2) = (1L, 2L)

  /** Files 1-5: 1 and 2 in 07, 3 in 14, 4 in the nomination prefix 99, 5 without a monument
    * id. Juror 1 rates 1: 1, 2: -1, 3: 1, 4: 0, 5: 1; juror 2 rates 1: 1, 3: -1.
    */
  private def votes()(implicit session: DBSession): Unit = {
    val ids = Map(1L -> Some("07-101-0001"), 2L -> Some("07-101-0002"), 3L -> Some("14-101-0003"),
      4L -> Some("99-141-2306"), 5L -> None)
    imageDao.batchInsert(ids.toSeq.sortBy(_._1).map { case (id, m) => Image(id, s"File:Image$id.jpg", None, None, 640, 480, m) })
    def vote(page: Long, juror: Long, rate: Int) = Selection(page, juror, round, rate, monumentId = ids(page))
    selectionDao.batchInsert(Seq(vote(1, juror1, 1), vote(2, juror1, -1), vote(3, juror1, 1), vote(4, juror1, 0),
      vote(5, juror1, 1), vote(1, juror2, 1), vote(3, juror2, -1)))
  }

  private def juror(rate: Option[Int] = None, rated: Option[Boolean] = None)(implicit session: DBSession) =
    SelectionQuery(userId = Some(juror1), roundId = Some(round), rate = rate, rated = rated).regionCounts()

  private def wholeRound(rate: Option[Int] = None, rated: Option[Boolean] = None)(implicit session: DBSession) =
    SelectionQuery(roundId = Some(round), rate = rate, rated = rated).regionCounts()

  "a juror's region counts" should {

    "count the juror's files per region, without those without a monument id" in new AutoRollbackDb {
      votes()
      juror() === Map("07" -> 2, "14" -> 1, "99" -> 1)
    }

    "count a rate filter's files, every region kept" in new AutoRollbackDb {
      votes()
      juror(rate = Some(1)) === Map("07" -> 1, "14" -> 1, "99" -> 0)
      juror(rate = Some(-1)) === Map("07" -> 1, "14" -> 0, "99" -> 0)
      juror(rate = Some(0)) === Map("07" -> 0, "14" -> 0, "99" -> 1)
      juror(rated = Some(true)) === Map("07" -> 1, "14" -> 1, "99" -> 0)
      juror(rated = Some(false)) === Map("07" -> 0, "14" -> 0, "99" -> 1)
    }

    "be read from the juror's index alone" in new AutoRollbackDb {
      votes()
      val plan = sql"""EXPLAIN SELECT LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3) AS region, SUM(s.rate = 1)
                       FROM selection s WHERE s.jury_id = $juror1 AND s.round_id = $round
                       GROUP BY region ORDER BY NULL"""
        .map(rs => (rs.stringOpt("key"), rs.stringOpt("Extra").getOrElse(""))).list()
      plan.map(_._1) === Seq(Some("idx_selection_jury_round_rate_mon_page"))
      plan.head._2 must contain("Using index")
    }
  }

  "a whole round's region counts" should {

    "count a file voted on by several jurors once" in new AutoRollbackDb {
      votes()
      wholeRound() === Map("07" -> 2, "14" -> 1, "99" -> 1)
    }

    "count the files a rate filter lists: those with such a vote" in new AutoRollbackDb {
      votes()
      wholeRound(rate = Some(1)) === Map("07" -> 1, "14" -> 1, "99" -> 0)
      wholeRound(rate = Some(-1)) === Map("07" -> 1, "14" -> 1, "99" -> 0)
      wholeRound(rated = Some(true)) === Map("07" -> 1, "14" -> 1, "99" -> 0)
    }
  }
}
