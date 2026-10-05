package db.scalikejdbc

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.{DB, DBSession}

/** User.findByRoundSelection reads with autoSession, so each test commits its data
  * (after emptying the database) instead of running in a rolled-back transaction.
  */
class FindByRoundSelectionSpec extends Specification with BeforeAll with TestDb {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private def withCleanDb[A](body: DBSession => A): A = {
    SharedTestDb.truncateAll()
    DB.autoCommit { implicit session => body(session) }
  }

  private def mkRound(contestId: Long, number: Int = 1): Round =
    Round(id = None, number = number, contestId = contestId, active = true, createdAt = TestDb.now)

  "findByRoundSelection" should {

    "be empty for a round without selections" in {
      withCleanDb { implicit session =>
        val contest = ContestJuryJdbc.create(Some(10), "contest10", 2010, "country10")
        val round   = roundDao.create(mkRound(contest.getId))
        User.findByRoundSelection(round.getId) must beEmpty
      }
    }

    "return each juror with selections in the round once, ordered by id" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(20), "contest20", 2020, "country20")
        val round  = roundDao.create(mkRound(contest.getId))
        val other  = roundDao.create(mkRound(contest.getId, 2))
        val jurors = createUsers("jury", 1, 2, 3, 4)

        // juror 3 and juror 1 rate several images; juror 2 only in another round;
        // juror 4 has no selections at all
        Seq(10L, 11L, 12L).foreach(p => selectionDao.create(pageId = p, rate = 1, juryId = jurors(2).getId, roundId = round.getId))
        Seq(10L, 11L).foreach(p => selectionDao.create(pageId = p, rate = 0, juryId = jurors(0).getId, roundId = round.getId))
        selectionDao.create(pageId = 10L, rate = 1, juryId = jurors(1).getId, roundId = other.getId)

        User.findByRoundSelection(round.getId) must_== Seq(jurors(0), jurors(2)).map(u => userDao.findById(u.getId).get)
        User.findByRoundSelection(other.getId).map(_.id) must_== Seq(jurors(1).id)
      }
    }

    "include a juror whose contest has since changed" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(30), "contest30", 2030, "country30")
        val later = ContestJuryJdbc.create(Some(31), "contest31", 2031, "country31")
        val round = roundDao.create(mkRound(contest.getId))
        val juror = createUsers("jury", 1).head
        selectionDao.create(pageId = 20L, rate = 1, juryId = juror.getId, roundId = round.getId)
        userDao.updateById(juror.getId).withAttributes("contestId" -> later.getId)

        User.findByRoundSelection(round.getId).map(_.id) must_== Seq(juror.id)
      }
    }
  }
}
