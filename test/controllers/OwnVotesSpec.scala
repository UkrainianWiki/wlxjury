package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._
import scala.concurrent.Future

/** A juror sees only their own votes: a round's gallery, file list, CSV and large view
  * show another user's votes (`asUserId`), or all jurors' together (`asUserId` 0), only
  * to those who may see the round's organiser info (root, the contest's admins and
  * organizers, never a juror), and of a juror of the round only
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

  /** Contests A and B. A has a rated round and one with the legacy `jury_org_view` flag set
    * (ignored: its jurors get the juror view), each with jurors 1 and 2
    * who voted on two files, a member who is in neither round, an organizer and an admin;
    * B's juror is in B's round. And a root.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLE", 2024, "Ukraine")
    val b = contestDao.create(None, "WLM", 2024, "Poland")
    val roundA = roundDao.create(Round(None, 1, Some("A round"), contestId = a.getId, rates = Round.ratesById(5), active = true))
    val orgViewRound = roundDao.create(Round(None, 2, Some("A open round"), contestId = a.getId,
      rates = Round.ratesById(5), active = true))
    LegacyJuryOrgView.set(orgViewRound.getId)
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
          yield Vote(image, j, r, rate = i + 3))
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

  "a juror of a round [J-27]" should {

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

    "be refused another juror's votes, and all together, in a round with the legacy juryOrgView flag set, as pages and as JSON [J-27]" in {
      testDbApp { app =>
        val f = fixture()
        views(app, f.juror2, f.orgViewRound, f.juror1.getId, f.images.head.pageId, FORBIDDEN)
        views(app, f.juror2, f.orgViewRound, 0L, f.images.head.pageId, FORBIDDEN)
        views(app, f.juror2, f.orgViewRound, f.juror2.getId, f.images.head.pageId, OK)
      }
    }
  }

  "a binary round's large view [O-03]" should {

    "list every juror's vote to an organiser, rejections and votes yet to come too, and none to a juror" in {
      testDbApp { app =>
        val f = fixture()
        val binary = roundDao.create(Round(None, 3, Some("A yes/no round"), contestId = f.roundA.contestId,
          rates = Round.binaryRound, active = true))
        binary.addUsers(Seq(f.juror1, f.juror2).map(j => RoundUser(binary.getId, j.getId, "jury", active = true)))
        val image = f.images.head
        selectionDao.batchInsert(Seq(Vote(image, f.juror1, binary, rate = -1), Vote(image, f.juror2, binary, rate = 0)))
        def votes(viewer: User, asUserId: Long) =
          org.jsoup.Jsoup.parse(contentAsString(get(app, viewer, largePath(binary, asUserId, image.pageId))))
            .select(".large-line li").asScala.toList.map(li => (li.select("a").text, li.select(".visually-hidden").text))
        (votes(f.organizer, 0L) === List(("Juror 1", "Rejected"), ("Juror 2", "Unrated"))) and
          (votes(f.organizer, f.juror2.getId) === List(("Juror 1", "Rejected"), ("Juror 2", "Unrated"))) and
          // a juror's own large view: not the other juror's rejection, nor their own list of votes
          (votes(f.juror2, f.juror2.getId) === Nil)
      }
    }
  }

  "the round's contest's organizer and admin, and root [O-03]" should {

    "see a juror's votes, and all together" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.organizer, f.admin, f.root)) { viewer =>
          views(app, viewer, f.roundA, f.juror1.getId, f.images.head.pageId, OK)
          views(app, viewer, f.roundA, 0L, f.images.head.pageId, OK)
        }
      }
    }

    "get no stars to vote with on a juror's gallery tiles, which the juror has (tile-votes.js)" in {
      testDbApp { app =>
        val f = fixture()
        // a rated round's gallery is its list by rate: the stars' number on the list
        // (data-stars-number, tile-votes.js addListStars), and raty only with it
        def stars(viewer: User, round: Round = f.roundA) = {
          val page = contentAsString(get(app, viewer, s"/gallery/round/${round.getId}/user/${f.juror1.getId}/page/1"))
          val list = org.jsoup.Jsoup.parse(page).selectFirst("[data-stars-number]")
          (Option(list).map(_.attr("data-stars-number")), page.contains("javascripts/jquery.raty.js"))
        }
        stars(f.juror1) === (Some("5"), true)
        forall(Seq(f.organizer, f.admin, f.root)) { viewer => stars(viewer) === (None, false) }
        stars(f.juror2, f.orgViewRound) === (None, false) // another juror's votes, open to its jurors
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

    "view the round as a juror assigned to it who has not voted yet, listed in the juror menu" in {
      testDbApp { app =>
        val f = fixture()
        val newcomer = userDao.create(User("Newcomer", "newcomer@example.com", None, Set("jury"),
          contestId = Some(f.roundA.contestId)))
        f.roundA.addUsers(Seq(RoundUser(f.roundA.getId, newcomer.getId, "jury", active = true)))

        forall(Seq(
          s"/gallery/round/${f.roundA.getId}/user/${newcomer.getId}/page/1",
          s"/byrate/round/${f.roundA.getId}/user/${newcomer.getId}/page/1",
          s"/filelist/round/${f.roundA.getId}/user/${newcomer.getId}/page/1"
        )) { path =>
          val page = get(app, f.organizer, path)
          (path, status(page)) === (path, OK)
          contentAsString(page) must contain("Newcomer")
        }
        // and the round's menu lists them from any of its views
        contentAsString(get(app, f.organizer, s"/byrate/round/${f.roundA.getId}/user/0/page/1")) must
          contain("Newcomer")
      }
    }

    "find no view of the round a module does not name" in {
      testDbApp { app =>
        val f = fixture()
        val page = get(app, f.organizer, s"/query?module=nosuch&round=${f.roundA.getId}&user=0")
        status(page) === NOT_FOUND
        contentAsString(page) must contain("The page you asked for was not found.")
      }
    }
  }

  "the galleries' round menu, viewing a juror's votes" should {

    /** The page's links to the round's lists, unescaped. */
    def roundLinks(page: String, round: Round): Seq[String] =
      s"""href="(/(?:gallery|byrate|filelist)/round/${round.getId}/[^"#]*)"""".r
        .findAllMatchIn(page).map(_.group(1).replace("&amp;", "&")).toSeq.distinct

    /** Rounds of contest A besides the fixture's: a rated and a yes/no one of juror 2's alone, and
      * one juror 1 is assigned to without a vote yet.
      */
    def otherRounds(f: Fixture): (Round, Round, Round) = {
      def round(n: Int, name: String, rates: Rates) =
        roundDao.create(Round(None, n, Some(name), contestId = f.roundA.contestId, rates = rates, active = true))
      val rated = round(3, "Juror 2's rated round", Round.ratesById(5))
      val binary = round(4, "Juror 2's yes/no round", Round.binaryRound)
      val assigned = round(5, "Juror 1 assigned", Round.ratesById(5))
      Seq(rated, binary).foreach { r =>
        r.addUsers(Seq(RoundUser(r.getId, f.juror2.getId, "jury", active = true)))
        selectionDao.batchInsert(f.images.map(image => Vote(image, f.juror2, r, rate = 1)))
      }
      assigned.addUsers(Seq(RoundUser(assigned.getId, f.juror1.getId, "jury", active = true)))
      (rated, binary, assigned)
    }

    "keep the juror in the rounds they are a juror of, by votes or assigned, with the list's order" in {
      testDbApp { app =>
        val f = fixture()
        val (_, _, assigned) = otherRounds(f)
        val page = contentAsString(get(app, f.organizer,
          s"/gallery/round/${f.roundA.getId}/user/${f.juror1.getId}/page/1?sort=oldest-vote"))
        roundLinks(page, f.orgViewRound) === Seq(
          s"/gallery/round/${f.orgViewRound.getId}/user/${f.juror1.getId}/page/1?sort=oldest-vote")
        roundLinks(page, assigned) === Seq(
          s"/gallery/round/${assigned.getId}/user/${f.juror1.getId}/page/1?sort=oldest-vote")
        forall(roundLinks(page, f.orgViewRound) ++ roundLinks(page, assigned)) { link =>
          (link, status(get(app, f.organizer, link))) === (link, OK)
        }
      }
    }

    "open all the jurors' votes, in their default order, in a round the juror is not a juror of, which is no 404 and sets no order cookie" in {
      testDbApp { app =>
        val f = fixture()
        val (rated, binary, _) = otherRounds(f)
        forall(Seq(f.organizer, f.admin, f.root)) { viewer =>
          val page = contentAsString(get(app, viewer,
            s"/gallery/round/${f.roundA.getId}/user/${f.juror1.getId}/page/1?filter=rated&sort=oldest-vote"))
          forall(Seq(rated, binary)) { round =>
            val links = roundLinks(page, round)
            // a binary round has no "rated" filter: all files there, as before
            val filter = if (round.isBinary) "" else "filter=rated&"
            links === Seq(s"/gallery/round/${round.getId}/user/0/page/1?${filter}sort=rate")
            val target = get(app, viewer, links.head)
            (links.head, status(target)) === (links.head, OK)
            cookies(target).get(RoundList.SortCookie) must beNone
            cookies(target).get(RoundList.BinarySortCookie) must beNone
          }
        }
      }
    }

    "still refuse the juror's votes in a round they are not a juror of, by its URL" in {
      testDbApp { app =>
        val f = fixture()
        val (rated, binary, _) = otherRounds(f)
        forall(Seq(rated, binary)) { round =>
          status(get(app, f.organizer, s"/gallery/round/${round.getId}/user/${f.juror1.getId}/page/1")) === NOT_FOUND
        }
      }
    }

    "leave a juror's own gallery's round menu as it was: their own votes in their other rounds" in {
      testDbApp { app =>
        val f = fixture()
        otherRounds(f)
        val page = contentAsString(get(app, f.juror1,
          s"/gallery/round/${f.roundA.getId}/user/${f.juror1.getId}/page/1?sort=oldest-vote"))
        roundLinks(page, f.orgViewRound) === Seq(
          s"/gallery/round/${f.orgViewRound.getId}/user/${f.juror1.getId}/page/1?sort=oldest-vote")
      }
    }
  }

  "a juror's normal navigation [J-27]" should {

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
