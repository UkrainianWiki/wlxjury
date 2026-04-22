package db.scalikejdbc

import org.intracer.wmua.ContestJury
import munit.FunSuite

class ContestSpec extends FunSuite with TestDb {

  test("fresh database should be empty") {
    withDb {
      assertEquals(contestDao.findAll(), Nil)
    }
  }

  test("create contest") {
    withDb {
      val images =
        Some("Category:Images from Wiki Loves Earth 2015 in Ukraine")

      val contest =
        contestDao.create(
          id = None,
          name = "WLE",
          year = 2015,
          country = "Ukraine",
          images = images,
          categoryId = None,
          currentRound = None
        )

      assertEquals(contest.name, "WLE")
      assertEquals(contest.year, 2015)
      assertEquals(contest.country, "Ukraine")
      assertEquals(contest.images, images)

      assertEquals(contestDao.findById(contest.getId), Some(contest))

      assertEquals(contestDao.findAll(), List(contest))
    }
  }

  test("create contests") {
    withDb {
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
}
