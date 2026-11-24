package db.scalikejdbc

import org.intracer.wmua.ContestJury
import munit.FunSuite
import scalikejdbc._

class ContestSpec extends FunSuite with AutoRollbackMunitDb {

  dbTest("fresh database should be empty") { implicit session =>
    assertEquals(contestDao.findAll(), Nil)
  }

  dbTest("create contest") { implicit session =>
    val images = Some("Category:Images from Wiki Loves Earth 2015 in Ukraine")

    val contest =
      contestDao.create(
        id = None,
        name = "WLE",
        year = 2015,
        country = "Ukraine",
        images = images,
        categoryId = None
      )

    assertEquals(contest.name, "WLE")
    assertEquals(contest.year, 2015)
    assertEquals(contest.country, "Ukraine")
    assertEquals(contest.images, images)

    assertEquals(contestDao.findById(contest.getId), Some(contest))

    assertEquals(contestDao.findAll(), List(contest))
  }

  dbTest("a contest's JSON and writes carry no current round (the contest's open rounds are the Round queries')") { implicit session =>
    val contest = contestDao.create(id = None, name = "WLE", year = 2015, country = "Ukraine")
    val round = roundDao.create(
      Round(None, 1, Some("Round 1"), contest.getId, Set("jury"), 3, Round.ratesById(10), active = true, createdAt = TestDb.now)
    )
    // an open round does not set the contest's field (nothing writes it); the round is found by the contest
    assertEquals(
      sql"select count(*) from contest_jury where id = ${contest.getId} and current_round = ${round.getId}"
        .map(_.int(1)).single.apply(),
      Some(0)
    )
    assertEquals(roundDao.activeRounds(contest.getId).map(_.id), Seq(round.id))
    assertEquals(
      (new api.JsonFormat {}).contestFormat.writes(contest).as[play.api.libs.json.JsObject].keys.contains("currentRound"),
      false
    )
  }

  dbTest("create contests") { implicit session =>
    def images(contest: String, year: Int, country: String) =
      Some(s"Category:Images from $contest $year in $country")

    val contests = List(
      ContestJury(
        id = None,
        name = "WLE",
        year = 2015,
        country = "Ukraine",
        images = images("WLE", 2015, "Ukraine")
      ),
      ContestJury(
        id = None,
        name = "WLM",
        year = 2015,
        country = "Ukraine",
        images = images("WLM", 2015, "Ukraine")
      ),
      ContestJury(
        id = None,
        name = "WLM",
        year = 2015,
        country = "Poland",
        images = images("WLM", 2015, "Poland")
      )
    )
    contestDao.batchInsert(contests)

    assertEquals(contestDao.findAll().map(_.copy(id = None)), contests)
  }
}
