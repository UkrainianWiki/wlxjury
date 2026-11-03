package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

/** A file's stars (Round.numberOfStars): the round's, also in a criteria round, whose rate (the
  * sum of its criteria's) is drawn per criterion (Round.perCriterion, C3) on them; its count
  * is the round's criteria (RoundCriterion.countOf).
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

    "be one criterion's stars in a criteria round, which multiplied them by its criteria before" in {
      val criteria = rated.copy(hasCriteria = true)
      criteria.numberOfStars === 10
      criteria.copy(criteriaCount = 2).numberOfStars === 10
      criteria.copy(criteriaCount = 2, halfStar = true).numberOfStars === 5
    }
  }

  "a rate on one criterion's stars" should {
    "be a criteria round's sum over its criteria, any other round's rate as it is" in {
      val criteria = rated.copy(hasCriteria = true, criteriaCount = 4)
      // 4 criteria rated 7, 8, 9 and 10: 34, drawn as 8.5
      criteria.perCriterion(34) === 8.5
      criteria.copy(criteriaCount = 2).perCriterion(7) === 3.5
      // the count matters only in a criteria round; not loaded, it is 1
      rated.copy(criteriaCount = 4).perCriterion(34) === 34
      rated.copy(hasCriteria = true).perCriterion(34) === 34
      criteria.copy(criteriaCount = 0).perCriterion(34) === 34
    }
  }

  "a round's criteria count" should {
    "be the round's criteria, none without them, whatever its votes' rates" in new AutoRollbackDb {
      val plain = roundDao.create(rated)
      val round = roundDao.create(rated.copy(criterionIds = Seq(6L, 7L)))
      val other = roundDao.create(rated.copy(criterionIds = Seq(6L, 7L, 8L, 9L)))
      RoundCriterion.countOf(plain.getId) === 0
      RoundCriterion.countOf(round.getId) === 2
      RoundCriterion.countOf(other.getId) === 4
      // no votes, nor rates, needed: the round's definition
      roundDao.findById(round.getId).map(_.hasCriteria) must beSome(true)
      roundDao.findById(plain.getId).map(_.hasCriteria) must beSome(false)
      RoundCriterion.idsOf(other.getId) === Seq(6L, 7L, 8L, 9L)
    }
  }
}
