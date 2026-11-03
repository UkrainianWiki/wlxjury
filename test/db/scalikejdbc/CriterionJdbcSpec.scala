package db.scalikejdbc

import org.intracer.wmua.{Criterion, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

/** The criteria catalogue ([[CriterionJdbc]]) and a round's criteria ([[RoundCriterion]]),
  * each example in a transaction rolled back after it.
  */
class CriterionJdbcSpec extends Specification with BeforeAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val rated = Round(None, 1, contestId = 1, rates = Round.ratesById(10))

  private def rates(voteId: Long)(implicit session: DBSession): Map[Long, Int] =
    sql"SELECT criterion_id, rate FROM criteria_rate WHERE vote_id = $voteId"
      .map(rs => rs.long(1) -> rs.int(2)).list().toMap

  private def voteOf(roundId: Long)(implicit session: DBSession): Long =
    sql"SELECT id FROM vote WHERE round_id = $roundId".map(_.long(1)).single().get

  "the catalogue" should {

    "have the criteria V83 ships, under their ids, with their keys" in new AutoRollbackDb {
      CriterionJdbc.all().map(c => c.id -> c.messageKey) === Criterion.Shipped
      CriterionJdbc.findById(6).map(_.fallbackName) must beSome("Технічна якість")
      CriterionJdbc.existing(Seq(6L, 7L, 12345L)) === Set(6L, 7L)
      CriterionJdbc.existing(Nil) must beEmpty
    }

    "add a criterion under a key of its own, refusing a name it has, in any case" in new AutoRollbackDb {
      val added = CriterionJdbc.addIn("Composition", Some(42L))
      added must beRight.like { case c =>
        (c.messageKey === Criterion.customKey(c.id)) and (c.id must be_>(9L)) and
          (CriterionJdbc.findById(c.id) must beSome(c))
      }
      sql"SELECT created_by FROM criterion WHERE fallback_name = 'Composition'".map(_.longOpt(1)).single() ===
        Some(Some(42L))
      CriterionJdbc.addIn("composition", None) must beLeft(CriterionJdbc.AddRefusal.Exists: CriterionJdbc.AddRefusal)
      CriterionJdbc.addIn("Технічна ЯКІСТЬ", None) must beLeft(CriterionJdbc.AddRefusal.Exists: CriterionJdbc.AddRefusal)
      CriterionJdbc.all().size === 10
    }

    "refuse a criterion once it has its limit" in new AutoRollbackDb {
      val more = Criterion.MaxEntries - CriterionJdbc.all().size
      sql"INSERT INTO criterion (message_key, fallback_name) VALUES (?, ?)"
        .batch((1 to more).map(i => Seq(s"criterion.test.$i", s"Test $i")): _*).apply()
      CriterionJdbc.addIn("One more", None) must beLeft(CriterionJdbc.AddRefusal.Full: CriterionJdbc.AddRefusal)
      CriterionJdbc.all().size === Criterion.MaxEntries
    }
  }

  "a round's criteria" should {

    "be written with the round, in their order, with has_criteria; none for a round without" in new AutoRollbackDb {
      val round = roundDao.create(rated.copy(criterionIds = Seq(9L, 6L, 7L)))
      val plain = roundDao.create(rated)
      RoundCriterion.idsOf(round.getId) === Seq(9L, 6L, 7L)
      RoundCriterion.ofRound(round.getId).map(_.messageKey) ===
        Seq("criterion.extra.points", "criterion.technical.quality", "criterion.wikipedia.usefulness")
      RoundCriterion.countOf(round.getId) === 3
      roundDao.findById(round.getId).map(_.hasCriteria) must beSome(true)
      RoundCriterion.idsOf(plain.getId) must beEmpty
      roundDao.findById(plain.getId).map(_.hasCriteria) must beSome(false)
      // gone with their round
      sql"DELETE FROM round WHERE id = ${round.getId}".update()
      sql"SELECT COUNT(*) FROM round_criterion".map(_.int(1)).single() === Some(0)
    }

    "change freely before the round's first vote, to none too, changing nothing when the same" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(Image(1, "File:1.jpg")))
      val round = roundDao.create(rated.copy(criterionIds = Seq(6L, 7L)))
      // an unrated vote, as a distribution writes: no vote yet
      VoteJdbc.batchInsert(Seq(org.intracer.wmua.Vote(1, 5, round.getId, 0)))

      RoundCriterion.updateIn(round.getId, Seq(6L, 7L)) must beRight(false)
      RoundCriterion.updateIn(round.getId, Seq(8L, 6L, 7L)) must beRight(true)
      RoundCriterion.idsOf(round.getId) === Seq(8L, 6L, 7L)
      RoundCriterion.updateIn(round.getId, Nil) must beRight(true)
      RoundCriterion.idsOf(round.getId) must beEmpty
      roundDao.findById(round.getId).map(_.hasCriteria) must beSome(false)
      RoundCriterion.updateIn(round.getId, Seq(1L, 2L)) must beRight(true)
      roundDao.findById(round.getId).map(_.hasCriteria) must beSome(true)
      RoundCriterion.updateIn(12345L, Seq(1L, 2L)) must beLeft(RoundCriterion.Refusal.NotFound: RoundCriterion.Refusal)
    }

    "after the first vote, take only criteria replaced in their places, moving their rates" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(Image(1, "File:1.jpg"), Image(2, "File:2.jpg")))
      val round = roundDao.create(rated.copy(criterionIds = Seq(6L, 7L, 8L)))
      val other = roundDao.create(rated.copy(criterionIds = Seq(6L, 7L, 8L)))
      VoteJdbc.batchInsert(Seq(org.intracer.wmua.Vote(1, 5, round.getId, 0), org.intracer.wmua.Vote(1, 5, other.getId, 0)))
      val vote = voteOf(round.getId)
      val others = voteOf(other.getId)
      org.intracer.wmua.CriteriaRate.updateRateIn(vote, 7L, 4) must beTrue
      org.intracer.wmua.CriteriaRate.updateRateIn(others, 7L, 2) must beTrue
      // a vote of the round moved aside, with its rate
      sql"""INSERT INTO vote_aside (id, page_id, jury_id, round_id, rate) VALUES (9999, 2, 5, ${round.getId}, 0)""".update()
      sql"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES (9999, 7, 3)".update()

      val refused = RoundCriterion.Refusal.Voted: RoundCriterion.Refusal
      RoundCriterion.updateIn(round.getId, Seq(6L, 7L, 8L, 9L)) must beLeft(refused) // added
      RoundCriterion.updateIn(round.getId, Seq(6L, 7L)) must beLeft(refused)         // removed
      RoundCriterion.updateIn(round.getId, Seq(7L, 6L, 8L)) must beLeft(refused)     // moved
      RoundCriterion.updateIn(round.getId, Seq(6L, 8L, 9L)) must beLeft(refused)     // 8 in another place
      RoundCriterion.updateIn(round.getId, Nil) must beLeft(refused)
      RoundCriterion.idsOf(round.getId) === Seq(6L, 7L, 8L)

      // 7 and 8 replaced in their places
      RoundCriterion.updateIn(round.getId, Seq(6L, 1L, 2L)) must beRight(true)
      RoundCriterion.idsOf(round.getId) === Seq(6L, 1L, 2L)
      rates(vote) === Map(1L -> 4)
      rates(9999L) === Map(1L -> 3)
      // another round's rates of 7 stay
      rates(others) === Map(7L -> 2)
      RoundCriterion.idsOf(other.getId) === Seq(6L, 7L, 8L)
    }
  }
}
