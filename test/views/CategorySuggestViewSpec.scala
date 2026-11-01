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
      input(page, "source") must contain("""data-category-suggest="mixed"""")
    }
  }

  "the campaigns page" should {
    "load the script and mark the import source and a new contest's images as mixed" in {
      val page = contentAsString(views.html.contests(root, Seq(contest), Seq(contest),
        Form(ignored(contest)), Form(single("source" -> nonEmptyText))))
      page must contain(script)
      input(page, "source") must contain("""data-category-suggest="mixed"""")
      input(page, "images") must contain("""data-category-suggest="mixed"""")
      input(page, "name") must not contain "data-category-suggest"
    }
  }

  "the admin shell" should {
    "not load the script on a page without a category field" in {
      contentAsString(views.html.mainAdmin("users", admin, admin.getId)(Html(""))) must not contain script
    }
  }
}
