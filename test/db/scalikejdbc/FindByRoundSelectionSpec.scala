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
    DB.autoCommit { implicit session =>
      insertImagesFor(1L to 99L: _*) // the images of the tests' selection rows (page ids < 100)
      body(session)
    }
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

  "findRoundJurors" should {

    "return the jurors with selections, not round_user, when there are selections" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(40), "contest40", 2040, "country40")
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2)
        selectionDao.create(pageId = 30L, rate = 0, juryId = jurors(1).getId, roundId = round.getId)
        round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))

        User.findRoundJurors(round.getId).map(_.id) must_== Seq(jurors(1).id)
      }
    }

    "fall back to round_user for a round without selections" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(50), "contest50", 2050, "country50")
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2, 3)
        round.addUsers(Seq(jurors(2), jurors(0)).map(u => RoundUser(round.getId, u.getId, "jury", active = true)))

        User.findRoundJurors(round.getId).map(_.id) must_== Seq(jurors(0).id, jurors(2).id)
      }
    }
  }

  "distributionJurors" should {

    "be the round's active jurors in round_user, also when it has selections" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(60), "contest60", 2060, "country60")
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2, 3)
        round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
        selectionDao.create(pageId = 40L, rate = 0, juryId = jurors(1).getId, roundId = round.getId)
        RoundUser.setActive(round.getId, jurors(1).getId, active = false)

        User.distributionJurors(round.getId).map(_.id) must_== Seq(jurors(0).id, jurors(2).id)
        User.findRoundJurors(round.getId).map(_.id) must_== Seq(jurors(1).id) // the edit page: by selection
      }
    }

    "fall back to the jurors with selections for a round without round_user rows" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(70), "contest70", 2070, "country70")
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2)
        selectionDao.create(pageId = 41L, rate = 0, juryId = jurors(1).getId, roundId = round.getId)

        User.distributionJurors(round.getId).map(_.id) must_== Seq(jurors(1).id)
      }
    }
  }

  "findRoundJurors, for a round without selections," should {
    "leave out a stopped juror of a round without selections" in {
      withCleanDb { implicit session =>
        implicit val contest: ContestJury = ContestJuryJdbc.create(Some(80), "contest80", 2080, "country80")
        val round  = roundDao.create(mkRound(contest.getId))
        val jurors = createUsers("jury", 1, 2)
        round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
        RoundUser.setActive(round.getId, jurors(0).getId, active = false)

        User.findRoundJurors(round.getId).map(_.id) must_== Seq(jurors(1).id)
      }
    }
  }
}
