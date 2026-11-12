package views

import controllers.{AccountTestSupport, Secured}
import db.scalikejdbc.{PlayTestDb, User}
import org.jsoup.Jsoup
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** The navbar's user menu says who is signed in (logged.scala.html): the account's own name,
  * wiki login, sign-in email and role in the current contest (root: root), as text before the
  * menu's items, never another account's.
  */
class AccountMenuViewSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  /** The signed-in account's own profile page, as a page through the router. */
  private def menu(user: User, contestId: Option[Long])(implicit app: Application) = {
    val request = FakeRequest(GET, s"/users/edit/${user.getId}")
      .withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)
      .withCSRFToken
    val result = route(app, request).get
    status(result) === OK
    val doc = Jsoup.parse(contentAsString(result))
    doc.select("#navbar-account")
  }

  private def named(email: String, contestId: Option[Long], role: String, name: String, wiki: Option[String],
      root: Boolean = false): User = {
    val created = account(email, contestId, role, wikiAccount = wiki, root = root)
    User.updateUser(created.getId, name, None)
    created.copy(fullname = name)
  }

  "the user menu" should {

    "name the signed-in juror and admin, with wiki login, email and contest role, and nobody else" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = named("juror@example.com", Some(10), "jury", "Jana Juror", Some("JanaWiki"))
        val admin = named("admin@example.com", Some(10), "admin", "Adam Admin", None)
        val other = named("other@example.com", Some(10), "jury", "Olga Other", Some("OlgaWiki"))

        val ofJuror = menu(juror, Some(10))
        val ofAdmin = menu(admin, Some(10))
        def shows(m: org.jsoup.select.Elements, texts: String*) = texts.filterNot(m.text.contains) must beEmpty
        def hides(m: org.jsoup.select.Elements, texts: String*) = texts.filter(m.text.contains) must beEmpty

        (ofJuror.size === 1) and
          (ofJuror.select(".dropdown-item, a, button").size === 0) and
          shows(ofJuror, "Signed in as", "Jana Juror", "JanaWiki", "juror@example.com") and
          (ofJuror.select(".navbar-account-role").text === "Role: Juror") and
          (ofJuror.select(".navbar-account-wiki .visually-hidden").text === "Wiki account:") and
          hides(ofJuror, other.fullname, "OlgaWiki", other.email, "Admin") and
          shows(ofAdmin, "Adam Admin", "admin@example.com") and
          (ofAdmin.select(".navbar-account-role").text === "Role: Admin") and
          (ofAdmin.select(".navbar-account-wiki").size === 0) and
          hides(ofAdmin, other.fullname, other.email, juror.fullname, juror.email, "Juror")
      }
    }

    "show root as root" in {
      testDbApp { implicit app =>
        createContests(10)
        val root = named("root@example.com", None, "jury", "Rita Root", None, root = true)
        val ofRoot = menu(root, None)
        (ofRoot.text must contain("Rita Root")) and (ofRoot.text must contain("root@example.com")) and
          (ofRoot.select(".navbar-account-role").text must contain("Root"))
      }
    }

    "escape what it shows" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = named("x@example.com", Some(10), "jury", "<b>Bold</b>", Some("Wiki<i>"))
        val ofJuror = menu(juror, Some(10))
        (ofJuror.select("b, i:not(.bi)").size === 0) and (ofJuror.text must contain("<b>Bold</b>"))
      }
    }
  }
}
