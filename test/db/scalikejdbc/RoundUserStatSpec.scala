package db.scalikejdbc

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.{AutoSession, DB, DBSession}

/** Tests for Round.roundUserStat and Round.roundRateStat.
 *
 *  Both methods use autoSession (no implicit DBSession parameter), so they
 *  can only see *committed* data.  Each test therefore:
 *    1. Calls SharedTestDb.truncateAll() to start with an empty DB.
 *    2. Inserts fixture data via DB.autoCommit (committed immediately).
 *    3. Calls the method under test.
 *    4. Relies on truncateAll() at the start of the *next* test for cleanup
 *       (the final truncateAll() is called in afterAll).
 */
class RoundUserStatSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  // Helpers that commit data immediately so autoSession queries can see it

  private def autoCommitSession: DBSession = AutoSession

  private def withCleanDb[A](body: DBSession => A): A = {
    SharedTestDb.truncateAll()
    DB.autoCommit { implicit session => body(session) }
  }

  private def mkRound(contestId: Long): Round =
    Round(id = None, number = 1, name = Some("Round 1"), contestId = contestId,
      active = true, createdAt = TestDb.now)

  private def mkContest(id: Long)(implicit session: DBSession): ContestJury = {
    val c = ContestJuryJdbc.create(Some(id), s"contest$id", (2000 + id).toInt, s"country$id")
    ContestJuryJdbc.setImagesSource(id, Some(s"source$id"))
    c
  }

  // ─── roundUserStat ────────────────────────────────────────────────────────

  "roundUserStat" should {

    "return empty for a round with no selections" in {
      withCleanDb { implicit session =>
        val contest = mkContest(10)
        val round   = roundDao.create(mkRound(contest.getId))
        Round.roundUserStat(round.getId) must_== Seq.empty
      }
    }

    "return one row per (juror, rate)" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = mkContest(20)
        val round  = roundDao.create(mkRound(contest.getId))
        val juror  = createUsers("jury", 1).head

        // juror votes select(1) twice and reject(-1) once
        selectionDao.create(pageId = 10L, rate =  1, juryId = juror.getId, roundId = round.getId)
        selectionDao.create(pageId = 11L, rate =  1, juryId = juror.getId, roundId = round.getId)
        selectionDao.create(pageId = 12L, rate = -1, juryId = juror.getId, roundId = round.getId)

        val stat = Round.roundUserStat(round.getId).sortBy(r => (r.juror, r.rate))
        stat must_== Seq(
          Round.RoundStatRow(juror.getId, -1, 1),
          Round.RoundStatRow(juror.getId,  1, 2)
        )
      }
    }

    "return rows for all jurors in the round" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = mkContest(30)
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2)
        val juror1 = jurors(0)
        val juror2 = jurors(1)

        selectionDao.create(pageId = 20L, rate = 1, juryId = juror1.getId, roundId = round.getId)
        selectionDao.create(pageId = 21L, rate = 1, juryId = juror2.getId, roundId = round.getId)
        selectionDao.create(pageId = 22L, rate = 1, juryId = juror2.getId, roundId = round.getId)

        val stat = Round.roundUserStat(round.getId).sortBy(r => (r.juror, r.rate))
        stat must_== Seq(
          Round.RoundStatRow(juror1.getId, 1, 1),
          Round.RoundStatRow(juror2.getId, 1, 2)
        )
      }
    }

    "not include selections from other rounds" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = mkContest(40)
        val round1 = roundDao.create(mkRound(contest.getId))
        val round2 = roundDao.create(mkRound(contest.getId).copy(number = 2))
        val juror  = createUsers("jury", 1).head

        selectionDao.create(pageId = 30L, rate = 1, juryId = juror.getId, roundId = round1.getId)
        selectionDao.create(pageId = 31L, rate = 1, juryId = juror.getId, roundId = round2.getId)
        selectionDao.create(pageId = 32L, rate = 1, juryId = juror.getId, roundId = round2.getId)

        val stat = Round.roundUserStat(round1.getId)
        stat must_== Seq(Round.RoundStatRow(juror.getId, 1, 1))
      }
    }
  }

  // ─── roundRateStat ────────────────────────────────────────────────────────

  "roundRateStat" should {

    "return empty for a round with no selections" in {
      withCleanDb { implicit session =>
        val contest = mkContest(50)
        val round   = roundDao.create(mkRound(contest.getId))
        Round.roundRateStat(round.getId) must_== Seq.empty
      }
    }

    "count DISTINCT images per rate value" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = mkContest(60)
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2)
        val juror1 = jurors(0)
        val juror2 = jurors(1)

        // Both jurors vote rate=1 on the SAME image → 1 distinct image at rate=1
        selectionDao.create(pageId = 40L, rate =  1, juryId = juror1.getId, roundId = round.getId)
        selectionDao.create(pageId = 40L, rate =  1, juryId = juror2.getId, roundId = round.getId)
        // One more distinct image at rate=1
        selectionDao.create(pageId = 41L, rate =  1, juryId = juror1.getId, roundId = round.getId)
        // One image at rate=-1
        selectionDao.create(pageId = 42L, rate = -1, juryId = juror1.getId, roundId = round.getId)

        val stat = Round.roundRateStat(round.getId).sortBy(_._1)
        // rate -1 → 1 distinct image, rate 1 → 2 distinct images
        stat must_== Seq((-1, 1), (1, 2))
      }
    }
  }
}
