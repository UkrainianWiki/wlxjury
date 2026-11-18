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

  private def source(bot: MwBot, login: MwBot => CommonsLogin = CommonsLogin.anonymous) =
    new MwCommonsSource(bot, login(bot)) {
      override protected def retryDelay: FiniteDuration = 1.milli
    }

  private val loggedIn =
    """{"batchcomplete":"","query":{"userinfo":{"id":678,"name":"ExampleBot","rights":["read","bot","apihighlimits"]}}}"""

  private def withAccount(bot: MwBot) =
    new CommonsLogin(bot, Some(CommonsLogin.Credentials("ExampleBot@jury", "secret")))

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
      first.contains("redirects") must beFalse
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
      // a renamed file the page names by its old title: the file
      params("redirects") === ""
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

  "filesByTitles" should {

    "ask for the files that the titles redirect to: a renamed file by its old title" in {
      val bot = mockBot()
      val renamed = Seq(Page(13L, Some(6), "File:New name.jpg"))
      bot.run(any[Action], any[Map[String, String]], any[Option[Long]]) returns Future.successful(renamed)
      source(bot).filesByTitles(Seq("File:Old name.jpg"), info = true, text = false) === renamed

      val captor = ArgumentCaptor.forClass(classOf[Action])
      org.mockito.Mockito.verify(bot).run(captor.capture(), any[Map[String, String]], any[Option[Long]])
      val params = captor.getValue.pairs.toMap
      params("titles") === "File:Old name.jpg"
      params("redirects") === ""
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

    "fetch a batch the API refuses as too large 50 at a time, and check the account again" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful(loggedIn)
      run(bot) returns Future.failed(MwException("toomanyvalues", "Too many values supplied for parameter \"pageids\".")) thenReturns
        Future.successful(file)
      val commons = source(bot, withAccount)
      commons.maxIdsPerRequest === 500
      commons.files((1L to 500L), info = true, text = false) === Seq.fill(10)(file).flatten
      org.mockito.Mockito.verify(bot, org.mockito.Mockito.times(11)).run(any[Action], any[Map[String, String]], any[Option[Long]])
      commons.maxIdsPerRequest === 500
      postedParams(bot, 2).map(_("meta")) === Seq("userinfo", "userinfo")
    }
  }

  "maxIdsPerRequest" should {

    "be 50 for anonymous requests, without asking the API" in {
      val bot = mockBot()
      source(bot).maxIdsPerRequest === 50
      org.mockito.Mockito.verify(bot, org.mockito.Mockito.never()).post(any[Map[String, String]])
      ok
    }

    "be 500 for an account with apihighlimits" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns Future.successful(loggedIn)
      source(bot, withAccount).maxIdsPerRequest === 500
    }
  }

  "the author lookups" should {

    def version(user: String) =
      org.scalawiki.dto.Image.basic("File:A.jpg", None, Some(user), None, None, None, None, None, Some(13L))

    def captured(bot: MwBot, times: Int): Seq[Map[String, String]] = {
      val captor = ArgumentCaptor.forClass(classOf[Action])
      org.mockito.Mockito.verify(bot, org.mockito.Mockito.times(times))
        .run(captor.capture(), any[Map[String, String]], any[Option[Long]])
      captor.getAllValues.asScala.toSeq.map(_.pairs.toMap)
    }

    "credit a file to the user of its oldest version, asking for its whole history, 50 files a request" in {
      val bot = mockBot()
      // the versions newest first, as bot.run merges them across iicontinue
      val history = Seq(Page(Some(13L), Some(6), "File:A.jpg", images = Seq(version("Retoucher"), version("Original"))))
      bot.run(any[Action], any[Map[String, String]], any[Option[Long]]) returns Future.successful(history)

      source(bot).originalUploaders(1L to 60L) === Map(13L -> "Original")

      val Seq(first, second) = captured(bot, 2)
      first("prop") === "imageinfo"
      first("iiprop") === "user"
      first("iilimit") === "max"
      first("pageids").split('|').length === 50
      second("pageids").split('|').length === 10
    }

    "credit a photographer's credit template to the user its page links to" in {
      val bot = mockBot()
      val templates = Seq(
        Page(Some(1L), Some(10), "Template:Yuriy Kvach",
          revisions = Page.revisionsFromText(Some("{{Creator|...}} [[User:Kvachyuriy|Yuriy Kvach]]"))),
        Page(Some(2L), Some(10), "Template:Nobody", revisions = Page.revisionsFromText(Some("a photographer")))
      )
      bot.run(any[Action], any[Map[String, String]], any[Option[Long]]) returns Future.successful(templates)

      source(bot).creditTemplateUsers(Seq("Yuriy Kvach", "Nobody")) === Map("Yuriy Kvach" -> "Kvachyuriy")
      val Seq(params) = captured(bot, 1)
      params("titles") === "Template:Yuriy Kvach|Template:Nobody"
      params("prop") === "revisions"
    }
  }

  "ImportSource.titles" should {
    "normalize a pasted list" in {
      ImportSource.titles(
        "File:A_b.jpg\r\n\r\n  https://commons.wikimedia.org/wiki/File:C%C3%A9.jpg \nFile:A b.jpg\n") ===
        Seq("File:A b.jpg", "File:Cé.jpg")
    }
  }

  "probeName (DG11)" should {
    val name = "Wiki Loves Monuments 2026 in Nepal"

    def answer(pages: String*) = Future.successful(s"""{"batchcomplete":true,"query":{"pages":[${pages.mkString(",")}]}}""")
    val page = s"""{"pageid":5,"ns":0,"title":"$name"}"""
    val noPage = s"""{"ns":0,"title":"$name","missing":true}"""
    val category = s"""{"pageid":6,"ns":14,"title":"Category:$name","categoryinfo":{"size":40,"pages":0,"files":37,"subcats":3}}"""
    // a category with members but without a page of its own
    val categoryWithoutPage =
      s"""{"ns":14,"title":"Category:$name","missing":true,"categoryinfo":{"size":2,"pages":0,"files":2,"subcats":0}}"""
    val noCategory = s"""{"ns":14,"title":"Category:$name","missing":true}"""

    "ask once for the page and the category, and read which exists" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns answer(page, noCategory) thenReturns answer(noPage, category) thenReturns
        answer(noPage, categoryWithoutPage) thenReturns answer(noPage, noCategory)
      val commons = source(bot)
      commons.probeName(name, 5.seconds) === NameProbe(page = true, category = false)
      commons.probeName(name, 5.seconds) === NameProbe(page = false, category = true, categoryFiles = 37)
      commons.probeName(name, 5.seconds) === NameProbe(page = false, category = true, categoryFiles = 2)
      commons.probeName(s" $name ", 5.seconds) === NameProbe(page = false, category = false)
      val first = postedParams(bot, 4).head
      first("titles") === s"$name|Category:$name"
      first("prop") === "categoryinfo"
      first.contains("redirects") must beTrue
    }

    "count the page's files, at most one request's worth, only when both exist" in {
      val bot = mockBot()
      val images = (1 to 500).map(i => s"""{"ns":6,"title":"File:$i.jpg"}""").mkString(",")
      bot.post(any[Map[String, String]]) returns answer(page, category) thenReturns Future.successful(
        s"""{"continue":{"imcontinue":"5|X","continue":"||"},"query":{"pages":[{"pageid":5,"ns":0,"title":"$name","images":[$images]}]}}""")
      source(bot).probeName(name, 5.seconds) ===
        NameProbe(page = true, category = true, categoryFiles = 37, pageFiles = Some(500), pageFilesCapped = true)
      val Seq(_, second) = postedParams(bot, 2)
      second("prop") === "images"
      second("titles") === name
      second("imlimit") === "500"
    }

    "skip the page's files when the counts aren't wanted (the admin chose already)" in {
      val bot = mockBot()
      bot.post(any[Map[String, String]]) returns answer(page, category)
      source(bot).probeName(name, 5.seconds, countPageFiles = false) ===
        NameProbe(page = true, category = true, categoryFiles = 37)
      postedParams(bot, 1).head("prop") === "categoryinfo"
    }

    "throw when Commons times out or answers an error, and not ask about a title with a |" in {
      val slow = mockBot()
      slow.post(any[Map[String, String]]) returns scala.concurrent.Promise[String]().future
      source(slow).probeName(name, 50.millis) must throwA[java.util.concurrent.TimeoutException]
      val failing = mockBot()
      failing.post(any[Map[String, String]]) returns Future.successful("""{"error":{"code":"maxlag","info":"Waiting"}}""")
      source(failing).probeName(name, 5.seconds) must throwA[MwException]
      val unused = mockBot()
      source(unused).probeName("A|B", 5.seconds) === NameProbe(page = false, category = false)
      org.mockito.Mockito.verify(unused, org.mockito.Mockito.never()).post(any[Map[String, String]])
      ok
    }
  }

  "SourceName (DG11)" should {
    "tell a name without a namespace prefix from a prefixed one" in {
      SourceName.prefixless("Images from Wiki Loves Monuments 2026 in Nepal") must beTrue
      SourceName.prefixless("Wiki Loves Monuments 2026: Nepal") must beTrue // a colon, but no namespace
      forall(Seq("Category:A", "category: A", "Template:Monument", "Commons:Wiki Loves Monuments 2026/Winners",
        "User:Someone/list", "File:A.jpg", "  ", "")) { title =>
        SourceName.prefixless(title) must beFalse
      }
      SourceName.category(" Images from X ") === "Category:Images from X"
      SourceName.commonsUrl("Category:Images from X & Y") === "https://commons.wikimedia.org/wiki/Category%3AImages_from_X_%26_Y"
    }
  }
}
