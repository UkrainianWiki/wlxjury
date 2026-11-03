package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

/** A file's stars in the by-rate gallery (Round.numberOfStars): the round's stars, times its
  * criteria in a criteria round, whose count is the round's criteria (RoundCriterion.countOf).
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

    "count each criterion's stars in a criteria round" in {
      val criteria = rated.copy(hasCriteria = true)
      // not loaded with the round: the by-rate gallery sets it (RoundCriterion.countOf)
      criteria.numberOfStars === 10
      criteria.copy(criteriaCount = 2).numberOfStars === 20
      criteria.copy(criteriaCount = 2, halfStar = true).numberOfStars === 10
      // the count matters only in a criteria round
      rated.copy(criteriaCount = 2).numberOfStars === 10
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
