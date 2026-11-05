package views

import controllers.EditRound
import db.scalikejdbc.{Round, User}
import org.intracer.wmua.ContestJury
import play.api.data.Form
import play.api.data.Forms.{ignored, nonEmptyText, single, text, tuple}
import play.api.i18n.{Lang, Messages}
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import play.twirl.api.Html

/** The category fields' Commons suggestions (category-suggest.js): the script and its hook,
  * `data-category-suggest`, on the pages with a category field: "category" on the round form's
  * two categories, "mixed" on the import sources, which may also be a page. The admin shell
  * doesn't load the script for every page, as it did the old typeahead.
  */
class CategorySuggestViewSpec extends PlaySpecification {

  implicit val messages: Messages = Helpers.stubMessagesApi().preferred(Seq(Lang("en")))
  implicit val flash: Flash = Flash()
  implicit val request: RequestHeader = FakeRequest("GET", "/").withCSRFToken

  private val script = "javascripts/category-suggest.js"
  // category-suggest.js makes each field a Tom Select control: without Tom Select it does nothing
  private val tomSelect = "tom-select/2.6.2/dist/js/tom-select.complete.min.js"
  private val contest = ContestJury(Some(1), "Wiki Loves Earth", 2025, "Ukraine", Some("Category:Images from Wiki Loves Earth 2025 in Ukraine"))
  private val admin = User("Admin", "admin@example.org", id = Some(1), roles = Set("admin"), contestId = Some(1))
  private val root = User("Root", "root@example.org", id = Some(2), roles = Set(User.ROOT_ROLE))

  /** The `<input>` tag of id `id`. */
  private def input(page: String, id: String): String =
    s"""<input[^>]*id="$id"[^>]*>""".r.findFirstIn(page).getOrElse(s"no input $id")

  private def roundFilters(noEdit: Boolean): String =
    views.html.round.edit.imageFiltering(
      EditRound.editRoundForm.fill(EditRound(Round(Some(1), 1, Some("Round 1"), 1, category = Some("Category:A, B")), Nil, None)),
      Nil, Map.empty, admin, noEdit = noEdit).body

  "the round form" should {
    "mark both categories as category fields, the value as stored" in {
      val page = roundFilters(noEdit = false)
      input(page, "source") must contain("""data-category-suggest="category"""")
      input(page, "source") must contain("""value="Category:A, B"""")
      input(page, "excludeCategory") must contain("""data-category-suggest="category"""")
      input(page, "source") must not contain "readonly"
    }

    "keep them readonly when the round can't be edited, which the script leaves alone" in {
      val page = roundFilters(noEdit = true)
      input(page, "source") must contain("readonly")
      input(page, "excludeCategory") must contain("readonly")
    }
  }

  // the pages' forms with the controllers' field names: the fields are what matters here
  "the contest files page" should {
    "load the script and mark its source, a category or a page, as mixed" in {
      val page = contentAsString(views.html.contest_images(
        Form(tuple("source" -> text, "list" -> text, "action" -> text)), contest, admin, 0, 0))
      page must contain(script)
      page must contain(tomSelect)
      input(page, "source") must contain("""data-category-suggest="mixed"""")
    }
  }

  "the contests page" should {
    "load the script and mark the import source and a new contest's images as mixed" in {
      val page = contentAsString(views.html.contests(root, Seq(contest), Seq(contest),
        Form(ignored(contest)), Form(single("source" -> nonEmptyText))))
      page must contain(script)
      page must contain(tomSelect)
      input(page, "source") must contain("""data-category-suggest="mixed"""")
      input(page, "images") must contain("""data-category-suggest="mixed"""")
      input(page, "name") must not contain "data-category-suggest"
    }
    "be titled Contests, shown as its heading, the import a section of its own" in {
      val doc = org.jsoup.Jsoup.parse(contentAsString(views.html.contests(root, Seq(contest), Seq(contest),
        Form(ignored(contest)), Form(single("source" -> nonEmptyText)))))
      val h2 = doc.select("h2").eachText()
      (doc.title === messages("contests")) and (doc.select("h1").text === messages("contests")) and
        (doc.select("h1.visually-hidden").size === 0) and
        (h2.get(0) === messages("import.from.list.page.images.category")) and (h2.get(1) === messages("contests.list"))
    }
    "link a contest's files page by its source, or say it is not set beside a link to set it, never an empty link" in {
      import scala.jdk.CollectionConverters._
      val noSource = contest.copy(id = Some(2), images = None)
      val blank = contest.copy(id = Some(3), images = Some("  "))
      val all = Seq(contest, noSource, blank)
      val doc = org.jsoup.Jsoup.parse(contentAsString(views.html.contests(root, all, all,
        Form(ignored(contest)), Form(single("source" -> nonEmptyText)))))
      val links = doc.select("tbody a[href]").asScala.toSeq
      (links.forall(a => a.text.trim.nonEmpty || a.attr("aria-label").nonEmpty) must beTrue) and
        (links.count(_.text == messages("files.source.set")) === 2) and
        // each set link on its own line, named for its contest as the edit links are
        (links.filter(_.text == messages("files.source.set")).forall { a =>
          a.hasClass("d-block") && Set(noSource, blank).exists(c =>
            a.attr("aria-label") == messages("files.source.set.of", c.fullName) && a.attr("title") == a.attr("aria-label"))
        } must beTrue) and
        // the state is text on its own line, not part of the link
        (doc.select("tbody span.d-block").asScala.count(_.text == messages("files.source.none")) === 2) and
        (links.exists(_.text.contains(messages("files.source.none"))) must beFalse) and
        (links.count(a => contest.images.contains(a.text)) === 1) and
        (doc.select("thead th").eachText().contains(messages("files.source")) must beTrue)
    }
    "name its icon links, and its import field apart from the field's example" in {
      val doc = org.jsoup.Jsoup.parse(contentAsString(views.html.contests(root, Seq(contest), Seq(contest),
        Form(ignored(contest)), Form(single("source" -> nonEmptyText)))))
      val source = doc.getElementById("source")
      forall(Seq("contest.users.edit", "contest.rounds.edit")) { key =>
        val link = doc.select(s"""a[aria-label="${messages(key, contest.fullName)}"]""")
        (link.size === 1) and (link.attr("title") === messages(key, contest.fullName)) and
          (link.select("i[aria-hidden=true]").size === 1)
      } and
        (doc.select("a button").size === 0) and
        (doc.select("label[for=source]").text === messages("import.source")) and
        (source.attr("placeholder") === messages("import.source.placeholder")) and
        // the add row's source field: named and exemplified as the column and the import field
        (doc.getElementById("images").attr("placeholder") === messages("import.source.placeholder")) and
        (doc.select("label[for=images]").text === messages("files.source")) and
        // the list's heading set apart from the import form above it
        (doc.select("h2.mt-4").text === messages("contests.list"))
    }
  }

  "every page with a category field" should {
    "load Tom Select in its head: each template with a data-category-suggest field, or the page that includes it" in {
      import java.nio.file.{Files, Paths}
      import scala.jdk.CollectionConverters._
      // a template's text without its Twirl comments
      def source(path: String) = new String(Files.readAllBytes(Paths.get(path)), java.nio.charset.StandardCharsets.UTF_8)
        .replaceAll("""(?s)@\*.*?\*@""", "")
      val walk = Files.walk(Paths.get("app", "views"))
      val withField = try walk.iterator.asScala.map(_.toString.replace(java.io.File.separatorChar, '/'))
          .filter(p => p.endsWith(".scala.html") && source(p).contains("data-category-suggest")).toList.sorted
        finally walk.close()
      // a partial's page: the round form's filters are in editRound
      val pageOf = Map("app/views/round/edit/imageFiltering.scala.html" -> "app/views/editRound.scala.html")
      withField must_== Seq("app/views/contest_images.scala.html", "app/views/contests.scala.html",
        "app/views/round/edit/imageFiltering.scala.html")
      forall(withField.map(f => pageOf.getOrElse(f, f))) { page =>
        // the snippets that load Tom Select (multiSelectScripts includes tomSelectScripts)
        Seq("snippets.tomSelectScripts()", "snippets.multiSelectScripts()").exists(source(page).contains) aka
          s"$page loads Tom Select" must beTrue
      }
    }
  }

  "the admin shell" should {
    "not load the script on a page without a category field" in {
      contentAsString(views.html.mainAdmin("users", admin, admin.getId)(Html(""))) must not contain script
    }
  }
}
