package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.apache.pekko.stream.Materializer
import org.jsoup.Jsoup
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._

import scala.jdk.CollectionConverters._

/** The maintainer's decision of 2026-11-12: the edit page has no Sort field any more and
  * users.sort (V34) is neither read nor written; lists of users are in account id order, also
  * for accounts that still have a sort value.
  */
class UserOrderSpec extends PlaySpecification with PlayTestDb with AccountTestSupport {

  sequential

  private def as[A](user: User, contestId: Option[Long])(request: FakeRequest[A]): FakeRequest[A] =
    request.withSession(Seq(Secured.UserName -> user.email) ++ contestId.map(Secured.CurrentContestId -> _.toString): _*)

  private def setSort(userId: Long, sort: Int): Unit =
    DB.autoCommit(implicit s => sql"UPDATE users SET sort = $sort WHERE id = $userId".update())

  private def storedSort(userId: Long): Option[Int] =
    DB.readOnly(implicit s => sql"SELECT sort FROM users WHERE id = $userId".map(_.intOpt(1)).single().flatten)

  /** Three jurors whose stored sort values are the reverse of their ids, and an admin. */
  private def fixture(): (User, Seq[User]) = {
    createContests(10)
    val admin = account("admin@example.com", Some(10), "admin")
    val jurors = (1 to 3).map(i => account(s"juror$i@example.com", Some(10), "jury"))
    jurors.zipWithIndex.foreach { case (j, i) => setSort(j.getId, 3 - i) }
    (admin, jurors)
  }

  "users" should {

    "compare by account id only" in {
      val users = Seq(5L, 2L, 9L).map(id => User("u", s"u$id@example.com", Some(id)))
      users.sorted.map(_.getId) === Seq(2L, 5L, 9L)
    }

    "be listed in account id order on the users page, a stored sort value ignored" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val (admin, jurors) = fixture()
        val html = contentAsString(call(userController(new RecordingMail).users(Some(10)),
          as(admin, Some(10))(FakeRequest(GET, "/admin/users?contestId=10")).withCSRFToken))
        val positions = jurors.map(j => html.indexOf(j.email))
        (positions must not(contain(-1))) and (positions === positions.sorted)
      }
    }

    "be offered in account id order as the round form's jurors" in {
      testDbApp { implicit app =>
        val (admin, jurors) = fixture()
        val html = contentAsString(route(app, as(admin, Some(10))(
          FakeRequest(GET, "/admin/rounds/edit?contestId=10")).withCSRFToken).get)
        val options = Jsoup.parse(html).select("select[name^=jurors] option").asScala.map(_.attr("value"))
          .filter(_.nonEmpty).toSeq
        options === jurors.map(_.getId.toString)
      }
    }
  }

  "the edit page" should {

    "have no Sort field, also for root" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val (_, jurors) = fixture()
        val root = account("root@example.com", None, "jury", root = true)
        val html = contentAsString(call(userController(new RecordingMail).editUser(jurors.head.getId, Some(10)),
          as(root, Some(10))(FakeRequest(GET, s"/users/edit/${jurors.head.getId}")).withCSRFToken))
        val doc = Jsoup.parse(html)
        (doc.select("input[name=fullname]").size === 1) and (doc.select("[name=sort]").size === 0)
      }
    }

    "save without it, leaving the stored value alone" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val (admin, jurors) = fixture()
        val juror = jurors.head
        val result = call(userController(new RecordingMail).saveUser(), as(admin, Some(10))(FakeRequest(POST, "/users/save"))
          .withFormUrlEncodedBody("id" -> juror.getId.toString, "fullname" -> "Renamed Juror", "email" -> juror.email,
            "wikiAccount" -> "", "roles" -> "jury", "contest" -> "10", "sort" -> "99").withCSRFToken)
        (status(result) === SEE_OTHER) and
          (User.findById(juror.getId).map(_.fullname) must beSome("Renamed Juror")) and
          (storedSort(juror.getId) must beSome(3))
      }
    }
  }
}
