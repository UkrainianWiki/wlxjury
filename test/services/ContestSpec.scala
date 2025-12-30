package services

import db.scalikejdbc.TestDb
import org.intracer.wmua.ContestJury
import org.scalawiki.MwBot
import play.api.test.PlaySpecification

class ContestSpec extends PlaySpecification with TestDb {

  val email = "email@1.com"

  "import contests" should {
    "import Ukraine" in {
      withDb {

        val bot = MwBot.fromHost("commons.wikimedia.org")
        val contestService = new ContestService(bot)

        contestService.importContests("Category:Wiki Loves Earth 2013 in Ukraine")

        val contests = contestDao.findAll()
        contests === List(
          ContestJury(
            id = Some(1),
            name = "Wiki Loves Earth",
            year = 2013,
            country = "Ukraine",
            images = Some("Category:Images from Wiki Loves Earth 2013 in Ukraine"),
            monumentIdTemplate = Some("UkrainianNaturalHeritageSite")
          )
        )
      }
    }
  }
}
