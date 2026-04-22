package services

import db.scalikejdbc.TestDb
import org.intracer.wmua.JuryTestHelpers
import org.scalawiki.dto.{Namespace, Page}
import org.scalawiki.query.SinglePageQuery
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification

import scala.concurrent.Future

class ContestSpec extends Specification with Mockito with JuryTestHelpers with TestDb {

  "import contests" should {
    "import Ukraine" in {
      withDb {
        val imagesCategory = Page("Category:Images from Wiki Loves Earth 2013 in Ukraine")
        val query = mock[SinglePageQuery]
        query.categoryMembers(Set(Namespace.CATEGORY)) returns Future.successful(Seq(imagesCategory))
        val bot = mockBot()
        bot.page("Category:Wiki Loves Earth 2013 in Ukraine") returns query

        val contestService = new ContestService(bot)
        contestService.importContests("Category:Wiki Loves Earth 2013 in Ukraine")

        val contests = contestDao.findAll()
        contests.size === 1
        val c = contests.head
        c.id must beSome
        c.name === "Wiki Loves Earth"
        c.year === 2013
        c.country === "Ukraine"
        c.images === Some("Category:Images from Wiki Loves Earth 2013 in Ukraine")
        c.monumentIdTemplate === Some("UkrainianNaturalHeritageSite")
      }
    }
  }
}
