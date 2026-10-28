package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A comment on a file of a round, as its large view shows them: for those who may view
  * the round and the file in it, checked in the round's contest, without switching the
  * session's contest.
  */
class CommentAuthorizationSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      a: ContestJury,
      b: ContestJury,
      round: Round,
      image: Image,
      juror: User,
      member: User,
      both: User,
      jurorB: User,
      organizer: User,
      admin: User,
      root: User
  )

  /** Contest A's round with a file voted on by its juror and by `both`, a juror of A and B;
    * a member of A not in the round; B's juror; A's organizer and admin; and root.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLE", 2024, "Ukraine")
    val b = contestDao.create(None, "WLM", 2024, "Poland")
    val round = roundDao.create(Round(None, 1, Some("A round"), contestId = a.getId, active = true))
    def user(name: String, role: String, contest: ContestJury) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = contest.id))
    val juror = user("Juror", "jury", a)
    val member = user("Member", "jury", a)
    val both = user("Both", "jury", b)
    UserContestJdbc.createMembership(both.getId, a.getId, "jury")
    val jurorB = user("Juror B", "jury", b)
    val organizer = user("Org", "organizer", a)
    val admin = user("Admin", "admin", a)
    val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    val image = Image(1L, "File:Image1.jpg", None, None, 640, 480, Some("12-345-1"))
    imageDao.batchInsert(Seq(image))
    round.addUsers(Seq(juror, both).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    selectionDao.batchInsert(Seq(juror, both).map(j => Selection(image, j, round)))
    Fixture(a, b, round, image, juror, member, both, jurorB, organizer, admin, root)
  }

  private def comment(app: Application, as: User, f: Fixture, sessionContest: Option[Long] = None): Future[Result] = {
    val url = routes.ImageDiscussionController
      .addComment(f.image.pageId, "all", Some(f.round.getId), None).url
    route(app, FakeRequest(POST, url)
      .withSession(Seq(Secured.UserName -> as.email) ++ sessionContest.map(Secured.CurrentContestId -> _.toString): _*)
      .withFormUrlEncodedBody("id" -> "0", "text" -> s"by ${as.fullname}")
      .withCSRFToken).get
  }

  private def commented(as: User): Boolean = CommentJdbc.findAll().exists(_.userId == as.getId)

  "a comment on a round's file" should {

    "be added by a juror of the round" in {
      testDbApp { app =>
        val f = fixture()
        val result = comment(app, f.juror, f)
        status(result) === SEE_OTHER
        commented(f.juror) must beTrue
      }
    }

    "be refused to a juror of the contest who is not in the round" in {
      testDbApp { app =>
        val f = fixture()
        status(comment(app, f.member, f)) === FORBIDDEN
        commented(f.member) must beFalse
      }
    }

    "be added by a juror of the round whose session is in another of their contests, not switching it" in {
      testDbApp { app =>
        val f = fixture()
        val result = comment(app, f.both, f, sessionContest = f.b.id)
        status(result) === SEE_OTHER
        commented(f.both) must beTrue
        session(result).get(Secured.CurrentContestId) must beNone
      }
    }

    "be refused to a juror of another contest" in {
      testDbApp { app =>
        val f = fixture()
        status(comment(app, f.jurorB, f)) === FORBIDDEN
        commented(f.jurorB) must beFalse
      }
    }

    "be added by the contest's organizer and admin, and root" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.organizer, f.admin, f.root)) { viewer =>
          status(comment(app, viewer, f)) === SEE_OTHER
          commented(viewer) must beTrue
        }
      }
    }
  }
}
