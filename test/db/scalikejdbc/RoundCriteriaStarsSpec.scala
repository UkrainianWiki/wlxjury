package db.scalikejdbc

import org.intracer.wmua.{CriteriaRate, Image}
import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

/** A file's stars in the by-rate gallery (Round.numberOfStars): the round's stars, times its
  * criteria in a criteria round, whose count is read from the round's criteria rates
  * (CriteriaRate.countOf) rather than hard-coded.
  */
class RoundCriteriaStarsSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  private val rated = Round(None, 1, contestId = 1, rates = Round.ratesById(10))

  "a round's number of stars" should {
    "be its rates without criteria, halved in half stars" in {
      rated.numberOfStars === 10
      rated.copy(halfStar = true).numberOfStars === 5
      rated.copy(rates = Round.ratesById(5), halfStar = true).numberOfStars === 5
    }

    "count each criterion's stars in a criteria round: those a distribution gives by default" in {
      val criteria = rated.copy(hasCriteria = true)
      criteria.numberOfStars === 10 * DistributeImages.CriteriaIds.size
      criteria.copy(criteriaCount = 2).numberOfStars === 20
      criteria.copy(criteriaCount = 2, halfStar = true).numberOfStars === 10
      // the count matters only in a criteria round
      rated.copy(criteriaCount = 2).numberOfStars === 10
    }
  }

  "a round's criteria count" should {
    "be the criteria rated in its selections, none without criteria rates" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(Image(1, "File:1.jpg"), Image(2, "File:2.jpg")))
      val round = roundDao.create(rated.copy(hasCriteria = true))
      val other = roundDao.create(rated.copy(hasCriteria = true))
      CriteriaRate.countOf(round.getId) must beNone

      // a selection without criteria rates first, then two with two criteria each
      SelectionJdbc.create(1, 0, 7, round.getId)
      val s1 = SelectionJdbc.create(1, 0, 8, round.getId)
      val s2 = SelectionJdbc.create(2, 0, 8, round.getId)
      val s3 = SelectionJdbc.create(1, 0, 8, other.getId)
      sql"""INSERT INTO criteria_rate (id, selection, criteria, rate) VALUES
            (1, ${s1.getId}, 6, 3), (2, ${s1.getId}, 7, 0), (3, ${s2.getId}, 6, 0), (4, ${s2.getId}, 7, 5),
            (5, ${s3.getId}, 6, 1), (6, ${s3.getId}, 7, 1), (7, ${s3.getId}, 8, 1), (8, ${s3.getId}, 9, 1)"""
        .update()

      CriteriaRate.countOf(round.getId) === Some(2)
      CriteriaRate.countOf(other.getId) === Some(4)
    }
  }
}
