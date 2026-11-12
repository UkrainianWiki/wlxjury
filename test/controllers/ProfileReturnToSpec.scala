package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._

import scala.concurrent.Future

/** The own profile goes back to the page it was opened from: the navbar's link carries the
  * page as returnTo, the form keeps it (also when a save is refused), and a save goes back there.
  * Only a local path that is not the profile's or another account action's, nor sign-out or a
  * contest switch ([[UserController.profileReturnTo]]); otherwise the save goes where it did.
  */
class ProfileReturnToSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def session[A](user: User)(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> "10")

  private def save(user: User, fields: (String, String)*)(implicit app: Application): Future[Result] = {
    implicit val mat: Materializer = app.materializer
    call(userController(new RecordingMail).saveUser(),
      session(user)(FakeRequest(POST, "/users/save")).withFormUrlEncodedBody(fields: _*).withCSRFToken)
  }

  private def own(user: User, changes: (String, String)*): Seq[(String, String)] =
    (Map("id" -> user.getId.toString, "fullname" -> "New Name", "email" -> user.email, "wikiAccount" -> "") ++
      changes).toSeq

  private val Gallery = "/gallery/round/3/user/7/page/2?sort=rate"

  "the navbar's profile link" should {

    "carry the page shown as returnTo" in {
      testDbApp { implicit app =>
        createContests(10)
        val admin = account("admin@example.com", Some(10), "admin")
        val html = contentAsString(route(app, session(admin)(FakeRequest(GET, "/admin/rounds?contestId=10")).withCSRFToken).get)
        val href = Jsoup.parse(html).select(s"a.dropdown-item[href^=/users/edit/${admin.getId}]").attr("href")
        href === s"/users/edit/${admin.getId}?returnTo=%2Fadmin%2Frounds%3FcontestId%3D10"
      }
    }

    "keep only safe pages" in {
      def link(path: String) = UserController.profileLinkReturnTo(FakeRequest(GET, path), backHere = true)
      (link(Gallery) must beSome(Gallery)) and
        (link("/users/edit/5?returnTo=%2Frounds") must beSome("/rounds")) and
        (link("/users/edit/5") must beNone) and
        (link("/logout") must beNone) and
        (UserController.profileLinkReturnTo(FakeRequest(POST, "/rounds"), backHere = true) must beNone) and
        (UserController.profileLinkReturnTo(FakeRequest(GET, "/rounds"), backHere = false) must beNone)
    }
  }

  "the own profile" should {

    "keep returnTo in its form" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury")
        val html = contentAsString(call(userController(new RecordingMail).editUser(juror.getId, None, Some(Gallery)),
          session(juror)(FakeRequest(GET, s"/users/edit/${juror.getId}?returnTo=${java.net.URLEncoder.encode(Gallery, "UTF-8")}"))
            .withCSRFToken))
        Jsoup.parse(html).select("form[action=/users/save] input[name=returnTo]").attr("value") === Gallery
      }
    }

    "go back to a safe returnTo after a save" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury")
        val result = save(juror, own(juror, "returnTo" -> Gallery): _*)
        (status(result) === SEE_OTHER) and (redirectLocation(result) must beSome(Gallery)) and
          (User.findById(juror.getId).map(_.fullname) must beSome("New Name"))
      }
    }

    "go where it did before for an unsafe or looping returnTo" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury")
        val admin = account("admin@example.com", Some(10), "admin")
        val unsafe = Seq("//evil.example", "https://evil.example", "/\\evil.example", s"/users/edit/${juror.getId}",
          "/users/save", "/logout", "/switch-contest/10")
        val juror_ = unsafe.map(r => r -> redirectLocation(save(juror, own(juror, "returnTo" -> r): _*)))
        val admin_ = unsafe.map(r => r -> redirectLocation(save(admin, own(admin, "returnTo" -> r): _*)))
        (juror_.filterNot(_._2.contains("/")) must beEmpty) and
          (admin_.filterNot(_._2.contains("/admin/users?contestId=10")) must beEmpty)
      }
    }

    "keep returnTo when a save is refused" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury")
        val result = save(juror, own(juror, "fullname" -> "", "returnTo" -> Gallery): _*)
        (status(result) === BAD_REQUEST) and
          (Jsoup.parse(contentAsString(result)).select("form[action=/users/save] input[name=returnTo]").attr("value") === Gallery)
      }
    }

    "keep returnTo on the profile that shows a save's notice" in {
      testDbApp { implicit app =>
        createContests(10)
        val juror = account("juror@example.com", Some(10), "jury")
        // an own email change: the link is mailed, the profile says so
        val result = save(juror, own(juror, "email" -> "new@example.com", "currentPassword" -> "secret",
          "returnTo" -> Gallery): _*)
        (status(result) === SEE_OTHER) and
          (redirectLocation(result) must beSome(
            s"/users/edit/${juror.getId}?returnTo=${java.net.URLEncoder.encode(Gallery, "UTF-8")}")) and
          (flash(result).get("account-notice") must beSome)
      }
    }
  }
}
