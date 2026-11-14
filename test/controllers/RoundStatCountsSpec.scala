package controllers

import db.scalikejdbc._
import modules.BlockingExecutionContext
import org.intracer.wmua.{CommentJdbc, ContestJury, Image, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.libs.json.{JsNumber, JsObject}
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.RoundService
import services.imports.DeletedFiles

import java.util.concurrent.ConcurrentLinkedQueue
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The round statistics page's counts on its links (docs: the round filters and comments
  * proposal, §3): the comments report's numbers inline, and the authors and conflicts counts
  * from `GET /roundstat/:round/counts`, each equal to what its page shows; the endpoint only
  * for those who may see all three links, in the round's own contest, on the blocking pool.
  */
class RoundStatCountsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(a: ContestJury, b: ContestJury, round: Round, history: Round, empty: Round, roundB: Round,
      admin: User, organizer: User, juror: User, organizerB: User, adminB: User, root: User)

  private val OrgAccount = "Org account"

  /** Contest A, its round R with the juror's votes on files 1-9:
    *   1 "Ivan", 2 "ivan", 3 `[[User:Ivan|Ivan]]` (shown as "Ivan": one author with 1),
    *   4 `{{Unknown|author}}` (shows as nothing), 5 blank, 6 no author: 3 without an author;
    *   7 the organizer's account (a conflict, `in`), 8 the same, deleted on Commons (its votes
    *   moved aside: a conflict, `deleted`, not on the authors list), 9 the same, disqualified.
    * Comments in R: two on 1, one on 2, one on 8 (no vote row left: counted by neither); in the
    * pre-screening round of A: on 1 and 3, and on 20 (not in R); in contest B: on 1.
    * Round H: files 1 and 2 (1 "Ivan", 2 "ivan"), no conflict, a history of two events. Round E: no files.
    * Contest B: its round of a file by the organizer's account, and no conflict-of-interest
    * people (its organizer has no wiki account).
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    val b = contestDao.create(None, "WLE", 2026, "Ukraine")
    def user(name: String, role: String, contest: Option[ContestJury], account: Option[String] = None) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = contest.flatMap(_.id),
        wikiAccount = account))
    val admin = user("Admin", "admin", Some(a))
    val organizer = user("Org", "organizer", Some(a), Some(OrgAccount))
    val juror = user("Juror", "jury", Some(a))
    val organizerB = user("OrgB", "organizer", Some(b))
    val adminB = user("AdminB", "admin", Some(b))
    val root = user("Root", "root", None)
    val authors = Seq(1L -> Some("Ivan"), 2L -> Some("ivan"), 3L -> Some("[[User:Ivan|Ivan]]"),
      4L -> Some("{{Unknown|author}}"), 5L -> Some("  "), 6L -> None, 7L -> Some(OrgAccount), 8L -> Some(OrgAccount),
      9L -> Some(OrgAccount), 20L -> Some("Someone"), 30L -> Some(OrgAccount))
    val images = authors.map { case (id, author) => Image(id, s"File:F$id.jpg", None, None, 640, 480, author = author) }
    imageDao.batchInsert(images)
    def img(id: Long) = images.find(_.pageId == id).get
    val pre = roundDao.create(Round(None, 1, Some("Pre-screening"), contestId = a.getId, active = false))
    val round = roundDao.create(Round(None, 2, Some("R"), contestId = a.getId, active = true))
    val history = roundDao.create(Round(None, 3, Some("H"), contestId = a.getId, active = true))
    val empty = roundDao.create(Round(None, 4, Some("E"), contestId = a.getId, active = true))
    val roundB = roundDao.create(Round(None, 1, Some("B"), contestId = b.getId, active = true))
    selectionDao.batchInsert((1L to 9L).map(id => Vote(img(id), juror, round, 0)))
    selectionDao.batchInsert(Seq(1L, 2L).map(id => Vote(img(id), juror, history, 0)))
    selectionDao.batchInsert(Seq(Vote(img(30L), juror, roundB, 0)))
    DB.localTx { implicit s =>
      DeletedFiles.markDeleted(Seq(8L))
      sql"UPDATE round_image SET status = 'dq', dq_code = 'coi' WHERE round_id = ${round.getId} AND page_id = 9".update()
      sql"""INSERT INTO round_image_event (round_id, page_id, action, code, prior_status, new_status, created_by)
            VALUES (${history.getId}, 1, 'disqualify', 'coi', 'in', 'dq', ${admin.getId}),
                   (${history.getId}, 1, 'requalify', 'mistaken_match', 'dq', 'in', ${admin.getId})""".update()
    }
    def comment(r: Round, contest: ContestJury, room: Long) =
      CommentJdbc.create(admin.getId, "Admin", r.getId, contest.id, room, s"on $room")
    Seq(1L, 1L, 2L, 8L).foreach(comment(round, a, _))
    Seq(1L, 3L, 20L).foreach(comment(pre, a, _))
    comment(roundB, b, 1L)
    Fixture(a, b, round, history, empty, roundB, admin, organizer, juror, organizerB, adminB, root)
  }

  private def request(path: String, viewer: Option[User], script: Boolean, contestId: Option[Long] = None) = {
    val withSession = viewer.fold(FakeRequest(GET, path))(v =>
      FakeRequest(GET, path).withSession(Secured.UserName -> v.email,
        Secured.CurrentContestId -> contestId.orElse(v.contestId).fold("")(_.toString)))
    val headers = if (script) Seq("X-Requested-With" -> "XMLHttpRequest", "Accept" -> "application/json") else Nil
    withSession.withHeaders(headers ++ Seq("Accept-Language" -> "en"): _*).withCSRFToken
  }

  private def counts(app: Application, round: Round, viewer: Option[User], contestId: Option[Long] = None)
      : Future[Result] =
    route(app, request(s"/roundstat/${round.getId}/counts", viewer, script = true, contestId)).get

  private def okCounts(app: Application, round: Round, viewer: User): JsObject = {
    val result = counts(app, round, Some(viewer))
    status(result) aka "counts" must_== OK
    contentAsJson(result).as[JsObject]
  }

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = route(app, request(path, Some(viewer), script = false)).get
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** The numbers of a text, in order. */
  private def numbers(text: String): Seq[Int] = "\\d+".r.findAllIn(text).map(_.toInt).toSeq

  "the round statistics page's comments link" should {

    "say this round's comments and all rounds' when more, as the report's header in each scope" in {
      testDbApp { app =>
        val f = fixture()
        val stat = page(app, f.organizer, s"/roundstat/${f.round.getId}")
        val links = stat.select("#round-comments-link a").asScala.toList
        val (thisRound, allRounds) = (links.head, links(1))
        def header(href: String) = page(app, f.organizer, href).selectFirst("#comments-report-count").text
        (links.size === 2) and
          (thisRound.text === "Comments on the round's files (3)") and
          (allRounds.text === "all rounds (5)") and
          (allRounds.attr("href") must contain("scope=contest")) and
          // "· 2 files, 3 comments": the comments, and the files of the same statement
          (numbers(header(thisRound.attr("href"))) === Seq(2, 3)) and
          (numbers(header(allRounds.attr("href"))) === Seq(3, 5)) and
          (CommentJdbc.roundCounts(f.a.getId, f.round.getId) === CommentJdbc.RoundCounts(3, 2, 5, 3))
      }
    }

    "be plain text with no comments, and leave out all rounds when not more" in {
      testDbApp { app =>
        val f = fixture()
        val empty = page(app, f.organizer, s"/roundstat/${f.empty.getId}")
        val none = empty.select("#round-comments-link a").asScala.toList
        // round H (files 1 and 2): none of its own; R's three and the pre-screening one on its files
        val history = page(app, f.admin, s"/roundstat/${f.history.getId}").select("#round-comments-link a").asScala.toList
        (none.map(a => (a.text, a.hasAttr("href"), a.attr("aria-disabled"), a.attr("tabindex"))) ===
          List(("Comments on the round's files: none", false, "true", "-1"))) and
          (history.map(a => (a.text, a.hasAttr("href"))) ===
            List(("Comments on the round's files: none", false), ("all rounds (4)", true))) and
          // the deferred links keep their href at render, whatever their counts
          (empty.selectFirst("#round-authors-link a").hasAttr("href") must beTrue) and
          (empty.selectFirst("#round-conflicts-link a").hasAttr("href") must beTrue)
      }
    }
  }

  "the counts of the authors and conflicts links" should {

    "be loaded by the page for an organizer and an admin, and not offered to a juror" in {
      testDbApp { app =>
        val f = fixture()
        val url = s"/roundstat/${f.round.getId}/counts"
        forall(Seq(f.organizer, f.admin, f.root)) { viewer =>
          val stat = page(app, viewer, s"/roundstat/${f.round.getId}")
          (stat.selectFirst("#round-authors-link a").attr("data-roundstat-counts") === url) and
            (stat.selectFirst("#round-conflicts-link a").attr("data-roundstat-counts") === url) and
            (stat.select("script[src*=roundstat-counts.js]").size === 1)
        } and {
          val juror = route(app, request(s"/roundstat/${f.round.getId}", Some(f.juror), script = false)).get
          status(juror) === FORBIDDEN
        }
      }
    }

    "equal the authors list's authors and files without one" in {
      testDbApp { app =>
        val f = fixture()
        val rounds = Round.findByContest(f.a.getId)
        forall(Seq(f.round, f.history, f.empty)) { round =>
          val json = okCounts(app, round, f.organizer)
          val list = AuthorsPage.of(f.a, Some(round), rounds, AuthorsPage.Sort.parse(None))
          ((json \ "authors").as[Int] === list.authors) and ((json \ "withoutAuthor").as[Int] === list.withoutAuthor)
        } and {
          val json = okCounts(app, f.round, f.organizer)
          // "Ivan" with the markup naming him, "ivan", the organizer: 3; 4, 5 and 6 without one
          ((json \ "authors").as[Int] === 3) and ((json \ "withoutAuthor").as[Int] === 3)
        }
      }
    }

    "equal the conflicts page's open conflicts, disqualified files and history" in {
      testDbApp { app =>
        val f = fixture()
        val r = okCounts(app, f.round, f.organizer)
        val review = RoundConflicts.review(f.round)
        val h = okCounts(app, f.history, f.admin)
        val e = okCounts(app, f.empty, f.admin)
        // contest B has no conflict-of-interest people: its file by A's organizer is none
        val b = okCounts(app, f.roundB, f.adminB)
        ((r \ "conflicts").as[Int] === review.matches.size) and (review.matches.map(_._1) === Seq(7L, 8L)) and
          ((r \ "disqualified").as[Int] === review.disqualified.size) and (review.disqualified.size === 1) and
          // the history is read only with neither a conflict nor a disqualified file
          ((r \ "history").toOption must beNone) and
          ((h \ "conflicts").as[Int] === 0) and ((h \ "disqualified").as[Int] === 0) and
          ((h \ "history").as[Int] === RoundConflicts.review(f.history).history.size) and ((h \ "history").as[Int] === 2) and
          ((e \ "history").as[Int] === 0) and
          ((b \ "conflicts").as[Int] === RoundConflicts.review(f.roundB).matches.size) and ((b \ "conflicts").as[Int] === 0) and
          ((b \ "authors").as[Int] === 1)
      }
    }

    "hold numbers only, no names" in {
      testDbApp { app =>
        val f = fixture()
        val json = okCounts(app, f.round, f.organizer)
        (json.keys === Set("authors", "withoutAuthor", "conflicts", "disqualified")) and
          forall(json.values)(_ must beAnInstanceOf[JsNumber]) and
          (json.toString must not(contain("Ivan"))) and (json.toString must not(contain(OrgAccount)))
      }
    }

    "refuse a juror of the round's contest with JSON 403" in {
      testDbApp { app =>
        val f = fixture()
        val result = counts(app, f.round, Some(f.juror))
        (status(result) === FORBIDDEN) and ((contentAsJson(result) \ "error").as[String] === "forbidden") and
          ((contentAsJson(result) \ "reference").asOpt[String] must beSome)
      }
    }

    "refuse a member of another contest with JSON 403" in {
      testDbApp { app =>
        val f = fixture()
        val result = counts(app, f.round, Some(f.adminB))
        (status(result) === FORBIDDEN) and ((contentAsJson(result) \ "error").as[String] === "forbidden")
      }
    }

    "refuse an organizer whose session is in their contest B, for a round of contest A, keeping the session in B" in {
      testDbApp { app =>
        val f = fixture()
        val result = counts(app, f.round, Some(f.organizerB), Some(f.b.getId))
        (status(result) === FORBIDDEN) and ((contentAsJson(result) \ "error").as[String] === "forbidden") and
          (session(result).get(Secured.CurrentContestId) must beNone.or(beSome(f.b.getId.toString))) and
          // their own contest's round, though, as an organizer of B
          (status(counts(app, f.roundB, Some(f.organizerB), Some(f.b.getId))) === OK)
      }
    }

    "answer signed out with JSON 401, and a missing round with JSON 404" in {
      testDbApp { app =>
        val f = fixture()
        val signedOut = counts(app, f.round, None)
        val missing = route(app, request(s"/roundstat/${f.round.getId + 1000}/counts", Some(f.organizer), script = true)).get
        (status(signedOut) === UNAUTHORIZED) and ((contentAsJson(signedOut) \ "error").as[String] === "unauthenticated") and
          (status(missing) === NOT_FOUND) and ((contentAsJson(missing) \ "error").as[String] === "round_not_found")
      }
    }

    "run on the blocking pool" in {
      testDbApp { app =>
        implicit val materializer: org.apache.pekko.stream.Materializer = app.materializer
        val f = fixture()
        val threads = new ConcurrentLinkedQueue[String]()
        val blocking = new BlockingExecutionContext(app.actorSystem) {
          override def execute(command: Runnable): Unit =
            super.execute(() => { threads.add(Thread.currentThread.getName); command.run() })
        }
        val controller = new RoundController(app.injector.instanceOf[ControllerComponents],
          app.injector.instanceOf[ContestController], app.injector.instanceOf[RoundService], blocking)
        val result = controller.roundStatCounts(f.round.getId)
          .apply(request(s"/roundstat/${f.round.getId}/counts", Some(f.organizer), script = true)).run()
        (status(result) === OK) and ((contentAsJson(result) \ "conflicts").as[Int] === 2) and
          (threads.asScala.toList must not(beEmpty)) and
          forall(threads.asScala.toList)(_ must contain("blocking-dispatcher"))
      }
    }
  }
}
