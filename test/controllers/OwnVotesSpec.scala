package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** A juror sees only their own votes: a round's gallery, file list, CSV and large view
  * show another user's votes (`asUserId`), or all jurors' together (`asUserId` 0), only
  * to those who may see the round's organiser info (root, the contest's admins and
  * organizers, its jurors in a `juryOrgView` round), and of a juror of the round only
  * ([[services.GalleryService.viewAs]]).
  */
class OwnVotesSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      roundA: Round,
      orgViewRound: Round,
      juror1: User,
      juror2: User,
      member: User,
      jurorB: User,
      organizer: User,
      admin: User,
      root: User,
      images: Seq[Image]
  )

  /** Contests A and B. A has a rated round and a `juryOrgView` one, each with jurors 1 and 2
    * who voted on two files, a member who is in neither round, an organizer and an admin;
    * B's juror is in B's round. And a root.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLE", 2024, "Ukraine")
    val b = contestDao.create(None, "WLM", 2024, "Poland")
    val roundA = roundDao.create(Round(None, 1, Some("A round"), contestId = a.getId, rates = Round.ratesById(5), active = true))
    val orgViewRound = roundDao.create(Round(None, 2, Some("A open round"), contestId = a.getId,
      rates = Round.ratesById(5), active = true, juryOrgView = true))
    val roundB = roundDao.create(Round(None, 1, Some("B round"), contestId = b.getId, rates = Round.ratesById(5), active = true))
    def user(name: String, role: String, contest: ContestJury) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = contest.id))
    val juror1 = user("Juror 1", "jury", a)
    val juror2 = user("Juror 2", "jury", a)
    val member = user("Member", "jury", a)
    val jurorB = user("Juror B", "jury", b)
    val organizer = user("Org", "organizer", a)
    val admin = user("Admin", "admin", a)
    val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    val images = (0L until 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    Seq(roundA -> Seq(juror1, juror2), orgViewRound -> Seq(juror1, juror2), roundB -> Seq(jurorB)).foreach {
      case (r, jurors) =>
        r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
        selectionDao.batchInsert(for (j <- jurors; (image, i) <- images.zipWithIndex)
          yield Selection(image, j, r, rate = i + 3))
    }
    Fixture(roundA, orgViewRound, juror1, juror2, member, jurorB, organizer, admin, root, images)
  }

  private def get(app: Application, viewer: User, path: String, json: Boolean = false): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email)
      .withHeaders("Accept" -> (if (json) "application/json" else "text/html"))
      .withCSRFToken).get

  /** The round's views with `asUserId`'s votes: its gallery, file list, CSV and a file's
    * large view.
    */
  private def galleryPaths(round: Round, asUserId: Long, pageId: Long): Seq[String] = Seq(
    s"/gallery/round/${round.getId}/user/$asUserId/page/1",
    s"/byrate/round/${round.getId}/user/$asUserId/page/1",
    s"/filelist/round/${round.getId}/user/$asUserId/page/1",
    s"/query?module=csv&round=${round.getId}&user=$asUserId",
    s"/large/round/${round.getId}/user/$asUserId/pageid/$pageId"
  )

  private def largePath(round: Round, asUserId: Long, pageId: Long) =
    s"/large/round/${round.getId}/user/$asUserId/pageid/$pageId"

  /** Each view as `asUserId` answers `expected` to `viewer`; the large view as JSON too. */
  private def views(app: Application, viewer: User, round: Round, asUserId: Long, pageId: Long, expected: Int) = {
    forall(galleryPaths(round, asUserId, pageId)) { path =>
      (path, status(get(app, viewer, path))) === (path, expected)
    }
    status(get(app, viewer, largePath(round, asUserId, pageId), json = true)) === expected
  }

  "a juror of a round" should {

    "be refused another juror's votes in it, as a page and as JSON" in {
      testDbApp { app =>
        val f = fixture()
        views(app, f.juror2, f.roundA, f.juror1.getId, f.images.head.pageId, FORBIDDEN)

        val page = get(app, f.juror2, s"/gallery/round/${f.roundA.getId}/user/${f.juror1.getId}/page/1")
        contentAsString(page) must not(contain("File:Image0.jpg"))
        val json = get(app, f.juror2, largePath(f.roundA, f.juror1.getId, f.images.head.pageId), json = true)
        (contentAsJson(json) \ "error").as[String] === "forbidden"
      }
    }

    "be refused all the round's jurors' votes together (user 0)" in {
      testDbApp { app =>
        val f = fixture()
        views(app, f.juror1, f.roundA, 0L, f.images.head.pageId, FORBIDDEN)
      }
    }

    "see their own votes" in {
      testDbApp { app =>
        val f = fixture()
        views(app, f.juror1, f.roundA, f.juror1.getId, f.images.head.pageId, OK)
        contentAsString(get(app, f.juror1, largePath(f.roundA, f.juror1.getId, f.images.head.pageId))) must
          contain("File:Image0.jpg")
      }
    }

    "see another juror's votes, and all together, in a round open to its jurors (juryOrgView)" in {
      testDbApp { app =>
        val f = fixture()
        views(app, f.juror2, f.orgViewRound, f.juror1.getId, f.images.head.pageId, OK)
        views(app, f.juror2, f.orgViewRound, 0L, f.images.head.pageId, OK)
      }
    }
  }

  "the round's contest's organizer and admin, and root" should {

    "see a juror's votes, and all together" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.organizer, f.admin, f.root)) { viewer =>
          views(app, viewer, f.roundA, f.juror1.getId, f.images.head.pageId, OK)
          views(app, viewer, f.roundA, 0L, f.images.head.pageId, OK)
        }
      }
    }

    "find no member of the contest who is not in the round, user of another contest, or missing user in it" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.member.getId, f.jurorB.getId, 999999L)) { asUserId =>
          views(app, f.organizer, f.roundA, asUserId, f.images.head.pageId, NOT_FOUND)
          val json = get(app, f.organizer, largePath(f.roundA, asUserId, f.images.head.pageId), json = true)
          (contentAsJson(json) \ "error").as[String] === "juror_not_found"
        }
      }
    }
  }

  "a juror's normal navigation" should {

    /** The app's links to galleries and large views in the page, unescaped. */
    def links(page: String): Seq[String] =
      """(?:href|data-largeUrl)="(/(?:gallery|byrate|filelist|large|query)[^"#]*)"""".r
        .findAllMatchIn(page).map(_.group(1).replace("&amp;", "&")).toSeq.distinct

    "land on their gallery and follow its and its large view's links without a denial" in {
      testDbApp { app =>
        val f = fixture()
        // the landing page: the first active round's gallery
        val landing = get(app, f.juror1, "/")
        status(landing) === SEE_OTHER
        val galleryUrl = redirectLocation(landing).get
        galleryUrl must contain(s"/user/${f.juror1.getId}/")
        val gallery = get(app, f.juror1, galleryUrl)
        status(gallery) === OK

        val galleryLinks = links(contentAsString(gallery))
        galleryLinks must not(beEmpty)
        val large = galleryLinks.find(_.startsWith("/large")).get
        val largeLinks = links(contentAsString(get(app, f.juror1, large)))
        largeLinks must not(beEmpty)

        forall((galleryLinks ++ largeLinks).distinct) { link =>
          (link, status(get(app, f.juror1, link))) must beLike { case (_, s) => s must beOneOf(OK, SEE_OTHER) }
        }
      }
    }
  }
}
