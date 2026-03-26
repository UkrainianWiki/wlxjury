package services

import db.scalikejdbc.{SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.scalawiki.MwBot
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

class ContestSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  val email = "email@1.com"

  "import contests" should {
    "import Ukraine" in {

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
