package services.imports

import org.intracer.wmua.JuryTestHelpers
import org.mockito.ArgumentCaptor
import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.{MwException, Page}
import org.specs2.mutable.Specification
import play.api.libs.json.Json

import scala.concurrent.Future
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** The API paging of [[MwCommonsSource]], against canned responses. */
class MwCommonsSourceSpec extends Specification with JuryTestHelpers {

  private def source(bot: MwBot) = new MwCommonsSource(bot) {
    override protected def retryDelay: FiniteDuration = 1.milli
  }

  private def postedParams(bot: MwBot, times: Int): Seq[Map[String, String]] = {
    val captor = ArgumentCaptor.forClass(classOf[Map[String, String]])
    org.mockito.Mockito.verify(bot, org.mockito.Mockito.times(times)).post(captor.capture())
    captor.getAllValues.asScala.toSeq
  }

  private val firstPage =
    """{"batchcomplete":"","continue":{"gcmcontinue":"file|41|12","continue":"gcmcontinue||"},
      |"query":{"pages":{"12":{"pageid":12,"ns":6,"title":"File:B.jpg"},"11":{"pageid":11,"ns":6,"title":"File:A.jpg"}}}}""".stripMargin
  private val lastPage = """{"batchcomplete":"","query":{"pages":{"13":{"pageid":13,"ns":6,"title":"File:C.jpg"}}}}"""

  "listFiles" should {

    "list a category's files one API page at a time, the continue map being the cursor" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful(firstPage) thenReturns Future.successful(lastPage)
      val commons = source(bot)
      val category = ImportSource("Category:Images from Wiki Loves Earth 2019")

      val page1 = commons.listFiles(category, Json.obj(), 2)
      page1 === FilePage(Seq(11L, 12L), Some(Json.obj("gcmcontinue" -> "file|41|12", "continue" -> "gcmcontinue||")))
      commons.listFiles(category, page1.next.get, 2) === FilePage(Seq(13L), None)

      val Seq(first, second) = postedParams(bot, 2)
      first("generator") === "categorymembers"
      first("gcmtitle") === "Category:Images from Wiki Loves Earth 2019"
      first("gcmnamespace") === "6"
      first("gcmlimit") === "2"
      first("continue") === ""
      first.contains("gcmcontinue") must beFalse
      second("gcmcontinue") === "file|41|12"
      second("continue") === "gcmcontinue||"
    }

    "list a page's files with the images generator" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful(lastPage)
      source(bot).listFiles(ImportSource("Commons:Wiki Loves Earth 2019/Winners"), Json.obj(), 50) ===
        FilePage(Seq(13L), None)
      val Seq(params) = postedParams(bot, 1)
      params("generator") === "images"
      params("titles") === "Commons:Wiki Loves Earth 2019/Winners"
    }

    "list a template's embedding files" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful(lastPage)
      source(bot).listFiles(ImportSource("Template:UkrainianNaturalHeritageSite"), Json.obj(), 50)
      val Seq(params) = postedParams(bot, 1)
      params("generator") === "embeddedin"
      params("geititle") === "Template:UkrainianNaturalHeritageSite"
    }

    "retry a response that isn't JSON (an error page)" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful("<html>502 Bad Gateway</html>") thenReturns
        Future.successful(lastPage)
      source(bot).listFiles(ImportSource("Category:X"), Json.obj(), 50) === FilePage(Seq(13L), None)
      postedParams(bot, 2).size === 2
    }

    "give up after its retries" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.failed(new java.io.IOException("connection reset"))
      source(bot).listFiles(ImportSource("Category:X"), Json.obj(), 50) must throwA[java.io.IOException]
    }

    "not retry an API error" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns
        Future.successful("""{"error":{"code":"invalidcategory","info":"The category name you entered is not valid."}}""")
      source(bot).listFiles(ImportSource("Category:"), Json.obj(), 50) must throwA[MwException]
      postedParams(bot, 1).size === 1
    }

    "retry a lagged API" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns
        Future.successful("""{"error":{"code":"maxlag","info":"Waiting for a database server: 6 seconds lagged."}}""") thenReturns
        Future.successful(lastPage)
      source(bot).listFiles(ImportSource("Category:X"), Json.obj(), 50) === FilePage(Seq(13L), None)
      postedParams(bot, 2).size === 2
    }
  }

  "files" should {

    def run(bot: MwBot) = bot.run(any[Action], any[Map[String, String]], any[Option[Long]])
    val file = Seq(Page(13L, Some(6), "File:C.jpg"))

    "retry a lagged API" in {
      val bot = mockBot()
      run(bot) returns Future.failed(MwException("maxlag", "Waiting for a database server: 6 seconds lagged.")) thenReturns
        Future.successful(file)
      source(bot).files(Seq(13L), info = true, text = false) === file
    }

    "retry a response that didn't parse" in {
      val bot = mockBot()
      run(bot) returns Future.failed(MwException("Unexpected end-of-input", "Unexpected end-of-input")) thenReturns
        Future.successful(file)
      source(bot).files(Seq(13L), info = true, text = false) === file
    }

    "not retry an API error" in {
      val bot = mockBot()
      run(bot) returns Future.failed(MwException("toomanyvalues", "Too many values supplied for parameter \"pageids\"."))
      source(bot).files(Seq(13L), info = true, text = false) must throwA[MwException]
      org.mockito.Mockito.verify(bot, org.mockito.Mockito.times(1)).run(any[Action], any[Map[String, String]], any[Option[Long]])
      ok
    }
  }

  "ImportSource.titles" should {
    "normalize a pasted list" in {
      ImportSource.titles(
        "File:A_b.jpg\r\n\r\n  https://commons.wikimedia.org/wiki/File:C%C3%A9.jpg \nFile:A b.jpg\n") ===
        Seq("File:A b.jpg", "File:Cé.jpg")
    }
  }
}
