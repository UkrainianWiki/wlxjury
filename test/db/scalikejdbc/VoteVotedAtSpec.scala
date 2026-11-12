package db.scalikejdbc

import org.intracer.wmua.{ContestJury, CriteriaRate, Image, Vote}
import org.specs2.matcher.ThrownExpectations
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

import java.time.{Instant, ZoneId, ZonedDateTime}

/** `vote.voted_at` (V97): set by the database clock when a vote's rate changes to another value
  * ([[VoteJdbc.rate]], [[CriteriaRate.updateRate]]), kept when the rate is the same, cleared when
  * the vote goes back to unrated; set on insert for a rated row only ([[VoteJdbc.create]],
  * [[VoteJdbc.batchInsert]], which keeps a time it is given); kept when the vote moves aside and
  * back ([[ImageJdbc.moveVotesAsideInRound]]).
  *
  * Service-level: the writes open their own transactions, so the tables are truncated before
  * each example (CLAUDE.md). Thrown expectations: a check in the middle of an example counts.
  */
class VoteVotedAtSpec extends Specification with ThrownExpectations with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private case class Fixture(contest: ContestJury, juror: User, round: Round)

  private def fixture(rates: Rates = Round.ratesById(5), criteria: Seq[Long] = Nil): Fixture = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val juror = userDao.create(User("Juror", "juror@example.org", None, Set("jury"), contestId = contest.id))
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = rates, active = true,
      criterionIds = criteria))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    imageDao.batchInsert((1 to 5).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i"))))
    Fixture(contest, juror, round)
  }

  /** An instant from seconds since the epoch (UNIX_TIMESTAMP): zone-independent, unlike a JDBC
    * timestamp read (the session's wall time taken in the JVM's zone, VoteJdbc.votedAtEpoch). */
  private def instant(seconds: java.math.BigDecimal): Instant = Instant.ofEpochMilli(seconds.movePointRight(3).longValueExact())

  private def votedAt(pageId: Long, f: Fixture): Option[Instant] =
    DB.readOnly { implicit s =>
      sql"""SELECT UNIX_TIMESTAMP(voted_at) FROM vote
            WHERE page_id = $pageId AND jury_id = ${f.juror.getId} AND round_id = ${f.round.getId}"""
        .map(_.bigDecimalOpt(1).map(instant)).single().flatten
    }

  private def setVotedAt(pageId: Long, f: Fixture, at: Option[String]): Unit =
    DB.autoCommit { implicit s =>
      val value = at.fold(sqls"NULL")(t => sqls"$t")
      sql"UPDATE vote SET voted_at = $value WHERE page_id = $pageId AND jury_id = ${f.juror.getId}".update()
    }

  private def dbNow: Instant = DB.readOnly(implicit s => sql"SELECT UNIX_TIMESTAMP(NOW(3))".map(rs => instant(rs.bigDecimal(1))).single().get)

  private val old = "2026-01-02 03:04:05.678"
  private def oldInstant: Instant = DB.readOnly { implicit s =>
    sql"SELECT UNIX_TIMESTAMP(TIMESTAMP '2026-01-02 03:04:05.678')".map(rs => instant(rs.bigDecimal(1))).single().get
  }

  "a juror's vote" should {

    "get the time when its rate changes, keep it for the same rate, and lose it when unrated" in {
      val f = fixture()
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId)))
      val unrated = votedAt(1L, f)

      val start = dbNow
      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 3) === 1
      val rated = votedAt(1L, f)

      setVotedAt(1L, f, Some(old))
      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 3)
      val same = votedAt(1L, f)

      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 4)
      val changed = votedAt(1L, f)

      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 0)
      val cleared = votedAt(1L, f)
      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 0)

      (unrated must beNone) and
        (rated must beSome((t: Instant) => !t.isBefore(start.minusSeconds(1)))) and
        (same === Some(oldInstant)) and
        (changed must beSome((t: Instant) => t.isAfter(oldInstant) && !t.isBefore(start.minusSeconds(1)))) and
        (cleared must beNone) and
        (votedAt(1L, f) must beNone) and
        // the model reads it
        (VoteJdbc.findBy(1L, f.juror.getId, f.round.getId).flatMap(_.votedAt) must beNone)
    }

    "get the time for a yes and a no in a yes/no round, and for a rejection after a yes" in {
      val f = fixture(Round.binaryRound)
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId), Vote(2L, f.juror.getId, f.round.getId)))
      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 1)
      VoteJdbc.rate(2L, f.juror.getId, f.round.getId, -1)
      setVotedAt(1L, f, Some(old))
      VoteJdbc.rate(1L, f.juror.getId, f.round.getId, -1)
      (votedAt(2L, f) must beSome) and (votedAt(1L, f) must beSome((t: Instant) => t.isAfter(oldInstant))) and
        (VoteJdbc.findBy(1L, f.juror.getId, f.round.getId).flatMap(_.votedAt).map(_.toInstant) === votedAt(1L, f))
    }

    "change nothing for a juror stopped on the round" in {
      val f = fixture()
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId)))
      DB.autoCommit { implicit s =>
        sql"UPDATE round_user SET active = FALSE WHERE round_id = ${f.round.getId} AND user_id = ${f.juror.getId}".update()
      }
      (VoteJdbc.rate(1L, f.juror.getId, f.round.getId, 3) === 0) and (votedAt(1L, f) must beNone)
    }
  }

  "a vote inserted" should {

    "be timed if rated, keeping a time it is given, and untimed if unrated" in {
      val f = fixture()
      val given = ZonedDateTime.of(2026, 3, 4, 5, 6, 7, 891000000, ZoneId.systemDefault())
      val start = dbNow
      selectionDao.batchInsert(Seq(
        Vote(1L, f.juror.getId, f.round.getId, 0),
        Vote(2L, f.juror.getId, f.round.getId, 0, votedAt = Some(given)),
        Vote(3L, f.juror.getId, f.round.getId, 2),
        Vote(4L, f.juror.getId, f.round.getId, 2, votedAt = Some(given))
      ))
      VoteJdbc.create(5L, 4, f.juror.getId, f.round.getId)
      (votedAt(1L, f) must beNone) and
        (votedAt(2L, f) must beNone) and
        (votedAt(3L, f) must beSome((t: Instant) => !t.isBefore(start.minusSeconds(1)))) and
        (votedAt(4L, f) === Some(given.toInstant)) and
        (votedAt(5L, f) must beSome((t: Instant) => !t.isBefore(start.minusSeconds(1))))
    }

    "be untimed when created unrated" in {
      val f = fixture()
      VoteJdbc.create(1L, 0, f.juror.getId, f.round.getId)
      votedAt(1L, f) must beNone
    }
  }

  "a vote's time" should {

    /** `body` on one connection in the session time zone `zone`, set back after it (pooled). */
    def inZone[A](zone: String)(body: DBSession => A): A =
      DB.localTx { implicit s =>
        SQL(s"SET time_zone = '$zone'").execute()
        try body(s)
        finally sql"SET time_zone = @@global.time_zone".execute()
      }

    "read back as the instant written, whatever the session's and the JVM's time zones" in {
      val f = fixture()
      val instant = Instant.parse("2026-11-12T14:05:00.123Z")
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId, 3), Vote(2L, f.juror.getId, f.round.getId, 4)))
      // written as wall time in +05:00: 19:05:00.123 there is 14:05:00.123Z
      inZone("+05:00") { implicit s =>
        sql"UPDATE vote SET voted_at = '2026-11-12 19:05:00.123' WHERE page_id = 1".update()
      }
      // given to batchInsert in a +05:00 session, read in -03:00
      val given = instant.plusSeconds(3600).atZone(ZoneId.of("Asia/Tokyo"))
      inZone("+05:00") { implicit s =>
        VoteJdbc.batchInsert(Seq(Vote(3L, f.juror.getId, f.round.getId, 2, votedAt = Some(given))))
      }
      def read(zone: String) = inZone(zone) { implicit s =>
        // the finders' select (VoteJdbc.singleSelectQuery)
        val finder = VoteJdbc.where("pageId" -> 1L, "juryId" -> f.juror.getId, "roundId" -> f.round.getId).apply()(s)
          .headOption.flatMap(_.votedAt).map(_.toInstant)
        val list = db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery(userId = Some(f.juror.getId), roundId = f.round.id,
          order = Seq("s.page_id" -> 1)).list()(s).flatMap(_.votes).map(v => v.pageId -> v.votedAt.map(_.toInstant)).toMap
        val epoch = sql"SELECT UNIX_TIMESTAMP(voted_at) FROM vote WHERE page_id = 3".map(_.bigDecimal(1)).single().get
        (finder, list(1L), list(3L), Instant.ofEpochMilli(epoch.movePointRight(3).longValueExact()))
      }
      val expected = (Some(instant), Some(instant), Some(given.toInstant), given.toInstant)
      (read("+05:00") === expected) and (read("-03:00") === expected) and (read("+00:00") === expected)
    }
  }

  "a vote moved aside and back" should {
    "keep its time" in {
      val f = fixture()
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId, 3)))
      setVotedAt(1L, f, Some(old))
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(f.round.getId))
        ImageJdbc.moveVotesAsideInRound(f.round.getId, Seq(1L))
      }
      val aside = DB.readOnly { implicit s =>
        sql"SELECT UNIX_TIMESTAMP(voted_at) FROM vote_aside WHERE page_id = 1".map(_.bigDecimalOpt(1).map(instant)).single().flatten
      }
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(f.round.getId))
        ImageJdbc.moveVotesBackInRound(f.round.getId, Seq(1L))
      }
      (aside === Some(oldInstant)) and (votedAt(1L, f) === Some(oldInstant))
    }
  }

  "a criteria round's vote" should {
    "get the time when its total changes, none while partly rated, and keep it for the same rate" in {
      val f = fixture(Round.ratesById(10), criteria = Seq(1L, 2L))
      selectionDao.batchInsert(Seq(Vote(1L, f.juror.getId, f.round.getId)))
      val voteId = VoteJdbc.findBy(1L, f.juror.getId, f.round.getId).get.getId

      CriteriaRate.updateRate(voteId, 1L, 4) must beTrue
      val partly = votedAt(1L, f)
      CriteriaRate.updateRate(voteId, 2L, 3)
      val whole = votedAt(1L, f)
      setVotedAt(1L, f, Some(old))
      CriteriaRate.updateRate(voteId, 2L, 3)
      val same = votedAt(1L, f)
      CriteriaRate.updateRate(voteId, 2L, 5)
      val changed = votedAt(1L, f)

      (partly must beNone) and (whole must beSome) and (same === Some(oldInstant)) and
        (changed must beSome((t: Instant) => t.isAfter(oldInstant)))
    }
  }
}
