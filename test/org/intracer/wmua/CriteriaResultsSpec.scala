package org.intracer.wmua

import db.scalikejdbc._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** A criteria round's per-criterion results (C3): [[CriteriaRate.averages]] (galleries, file
  * list, CSV, the large view for all the jurors) and [[CriteriaRate.ratesOfFile]] (the large
  * view's list of each juror's vote), read on demand from vote, criteria_rate and
  * round_criterion.
  *
  * Service-level: the votes are written by [[CriteriaRate.updateRate]] and the moves by
  * [[TestVoteWrites.setRound]], each in a transaction of its own, so the tables are truncated
  * before each example (CLAUDE.md).
  */
class CriteriaResultsSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def criteriaRound(contest: ContestJury, number: Int = 1, criteria: Seq[Long] = Seq(1L, 2L)): Round =
    roundDao.create(Round(None, number, contestId = contest.getId, rates = Round.ratesById(10), active = true,
      criterionIds = criteria))

  private def juror(contest: ContestJury, i: Int): User =
    userDao.create(User(s"Juror$i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id))

  /** The juror's vote of the file in the round, rated `rates` (criterion id -> rate) by the
    * app's writer: its rate their sum once all the round's criteria are rated (U5).
    */
  private def vote(pageId: Long, juror: User, round: Round, rates: Map[Long, Int]): Long = {
    insertImagesFor(pageId)
    val id = DB.autoCommit { implicit s =>
      sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($pageId, ${juror.getId}, ${round.getId}, 0)"
        .updateAndReturnGeneratedKey.apply()
    }
    DB.localTx { implicit s =>
      RoundWrites.lock(Seq(round.getId))
      RoundImageJdbc.sync(round.getId, Seq(pageId))
    }
    rates.foreach { case (criterion, rate) => CriteriaRate.updateRate(id, criterion, rate) must beTrue }
    id
  }

  private def rateOf(voteId: Long): Int =
    DB.readOnly(implicit s => sql"SELECT rate FROM vote WHERE id = $voteId".map(_.int(1)).single().get)

  "per-criterion averages" should {
    "average each criterion over the votes rated in every criterion, leaving partly rated votes out (U5)" in {
      val contest = createContests(1).head
      val round = criteriaRound(contest)
      val Seq(a, b, c) = (1 to 3).map(juror(contest, _))
      vote(10, a, round, Map(1L -> 8, 2L -> 6))
      vote(10, b, round, Map(1L -> 6, 2L -> 9))
      // only one criterion rated: unrated (rate 0), its 2 is no part of criterion 1's average
      val partial = vote(10, c, round, Map(1L -> 2))
      rateOf(partial) === 0
      // a file only partly rated has no averages
      vote(11, a, round, Map(2L -> 5))

      val averages = CriteriaRate.averages(round.getId, Seq(10L, 11L))
      averages.keySet === Set(10L)
      averages(10L) === Map(1L -> BigDecimal(7), 2L -> BigDecimal("7.5"))
    }

    "count a partly rated vote from before U5 (a rate above 0) by its rated criteria only" in {
      val contest = createContests(1).head
      val round = criteriaRound(contest)
      val Seq(a, b) = (1 to 2).map(juror(contest, _))
      vote(10, a, round, Map(1L -> 8, 2L -> 6))
      insertImagesFor(10)
      // the old rule's sum of the rated criteria, with a pre-created row of 0 (Q2: not zeroed)
      val legacy = DB.autoCommit { implicit s =>
        val id = sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (10, ${b.getId}, ${round.getId}, 4)"
          .updateAndReturnGeneratedKey.apply()
        sql"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES ($id, 1, 4), ($id, 2, 0)".update()
        id
      }
      CriteriaRate.averages(round.getId, Seq(10L))(AutoSession)(10L) === Map(1L -> BigDecimal(6), 2L -> BigDecimal(6))
      CriteriaRate.ratesOfFile(round.getId, 10L)(AutoSession)(legacy) === Map(1L -> 4)
    }

    "leave out the rates of another round's criteria, which a vote moved by setRound keeps" in {
      val contest = createContests(1).head
      val first = criteriaRound(contest, 1, Seq(1L, 2L))
      val second = criteriaRound(contest, 2, Seq(3L, 4L))
      val Seq(a, b) = (1 to 2).map(juror(contest, _))
      val moved = vote(10, a, first, Map(1L -> 8, 2L -> 6))
      vote(10, b, second, Map(3L -> 5, 4L -> 7))
      TestVoteWrites.setRound(10, first.getId, second.getId)
      // the moved vote is in the second round with its sum and the first round's rates
      DB.readOnly { implicit s =>
        sql"SELECT round_id, rate FROM vote WHERE id = $moved".map(rs => rs.long(1) -> rs.int(2)).single()
      } must beSome(second.getId -> 14)

      CriteriaRate.averages(second.getId, Seq(10L))(AutoSession) === Map(10L -> Map(3L -> BigDecimal(5), 4L -> BigDecimal(7)))
      CriteriaRate.averages(first.getId, Seq(10L))(AutoSession) === Map.empty
      CriteriaRate.ratesOfFile(second.getId, 10L)(AutoSession).get(moved) must beNone
    }

    "read one juror's votes only, for that juror's list" in {
      val contest = createContests(1).head
      val round = criteriaRound(contest)
      val Seq(a, b) = (1 to 2).map(juror(contest, _))
      vote(10, a, round, Map(1L -> 8, 2L -> 6))
      vote(10, b, round, Map(1L -> 2, 2L -> 4))
      vote(11, b, round, Map(1L -> 3, 2L -> 3))
      CriteriaRate.averages(round.getId, Seq(10L, 11L), Some(a.getId))(AutoSession) ===
        Map(10L -> Map(1L -> BigDecimal(8), 2L -> BigDecimal(6)))
      CriteriaRate.averages(round.getId, Seq(10L, 11L), Some(b.getId))(AutoSession) ===
        Map(10L -> Map(1L -> BigDecimal(2), 2L -> BigDecimal(4)), 11L -> Map(1L -> BigDecimal(3), 2L -> BigDecimal(3)))
    }

    "read the files in chunks, and none for no files" in {
      val contest = createContests(1).head
      val round = criteriaRound(contest)
      val a = juror(contest, 1)
      val pageIds = (1L to (CriteriaRate.AveragesChunk + 2).toLong)
      insertImagesFor(pageIds: _*)
      DB.autoCommit { implicit s =>
        val ids = pageIds.map { p =>
          sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($p, ${a.getId}, ${round.getId}, 10)"
            .updateAndReturnGeneratedKey.apply()
        }
        sql"INSERT INTO criteria_rate (vote_id, criterion_id, rate) VALUES (?, ?, ?)"
          .batch(ids.flatMap(id => Seq(Seq(id, 1L, 4), Seq(id, 2L, 6))): _*).apply()
      }
      val averages = CriteriaRate.averages(round.getId, pageIds ++ pageIds.take(3))(AutoSession)
      averages.size === pageIds.size
      averages.values.toSet === Set(Map(1L -> BigDecimal(4), 2L -> BigDecimal(6)))
      CriteriaRate.averages(round.getId, Nil)(AutoSession) === Map.empty
    }

    "be none for a round without criteria" in {
      val contest = createContests(1).head
      val plain = roundDao.create(Round(None, 1, contestId = contest.getId, rates = Round.ratesById(10)))
      CriteriaResults.of(plain, Seq(10L), 0) === CriteriaResults.empty
    }
  }

  "each juror's criteria rates of a file" should {
    "be the rated criteria of each vote, also a partly rated one's" in {
      val contest = createContests(1).head
      val round = criteriaRound(contest)
      val Seq(a, b, c) = (1 to 3).map(juror(contest, _))
      val full = vote(10, a, round, Map(1L -> 8, 2L -> 6))
      val partial = vote(10, b, round, Map(2L -> 3))
      vote(10, c, round, Map.empty)
      vote(11, a, round, Map(1L -> 1, 2L -> 1))
      CriteriaRate.ratesOfFile(round.getId, 10L)(AutoSession) === Map(full -> Map(1L -> 8, 2L -> 6), partial -> Map(2L -> 3))
    }
  }

  "the results' numbers" should {
    "be two decimals, rounded half up" in {
      CriteriaResults.format(BigDecimal(7)) === "7.00"
      CriteriaResults.format(BigDecimal("7.125")) === "7.13"
      CriteriaResults.format(BigDecimal("6.6666")) === "6.67"
    }
  }
}
