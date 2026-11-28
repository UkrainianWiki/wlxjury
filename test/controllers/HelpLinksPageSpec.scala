package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.scalawiki.wlx.dto.Monument
import scalikejdbc.DB
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The help links on the pages (docs/plans/2026-11-26-help-links-and-guide-split.md §6) by role: the
  * admins' round form with its field and section links; the statistics' guide link for organizers
  * and admins, its pausing a juror for the round's admins only; a juror's gallery with the jurors'
  * Help and no results link, an organizer's gallery of all jurors with the results link and no
  * Help; changing a person for an admin, not on one's own profile. Each link leaves the tool as
  * every off-site link does (views.ExternalLinksCheck).
  */
class HelpLinksPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, previous: Round, round: Round, admin: User, organizer: User, juror: User)

  private def fixture(): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    val previous = roundDao.create(Round(None, 1, Some("Round 1"), contest.getId, rates = Round.binaryRound))
    val round = roundDao.create(Round(None, 2, Some("Round 2"), contest.getId, active = true, rates = Round.ratesById(5)))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = contest.id))
    val admin = user("Admin", "admin")
    val organizer = user("Org", "organizer")
    val juror = user("Juror", "jury")
    val images = (1L to 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    DB.autoCommit { implicit session =>
      MonumentJdbc.batchInsert(Seq(Monument(page = "List of monuments", id = "12-345-1", name = "Town hall")))
    }
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    selectionDao.batchInsert(images.map(i => Vote(i, juror, round, 0)))
    Fixture(contest, previous, round, admin, organizer, juror)
  }

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email)
      .withHeaders(ACCEPT_LANGUAGE -> "en").withCSRFToken).get
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** The page's help links' topics. */
  private def topics(doc: Document): Seq[String] =
    doc.select("a.help-link").asScala.toSeq.map(_.attr("data-help-topic"))

  /** Each help link is the table's URL, leaves the tool as every off-site link does, and the page's
    * off-site links are all right. */
  private def linksRight(doc: Document, ownNewTabs: Set[Element] = Set.empty) = {
    val links = doc.select("a.help-link").asScala.toSeq
    (links.filterNot(a => views.HelpLinks.url(a.attr("data-help-topic")).contains(a.attr("href"))).map(_.outerHtml) must beEmpty) and
      (links.filterNot(views.ExternalLinksCheck.marked).map(_.outerHtml) must beEmpty) and
      (views.ExternalLinksCheck.problems(doc, ownNewTabs) must beEmpty)
  }

  "the round form" should {
    "give a new round's hard fields and its sections their help links" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}")
        val review = roundDao.create(Round(None, 3, Some("Review"), f.contest.getId, distributing = true,
          status = Some(Round.Status.Paused)))
        val jurors = page(app, f.admin, s"/admin/rounds/${review.getId}/jurors")
        (topics(doc) must containAllOf(Seq("create-round", "tracks", "choose-files", "categories", "top-files",
          "rejection-filter", "leave-out-rounds", "add-files", "leave-out-conflicts"))) and
          (topics(jurors) must containAllOf(Seq("score-rule", "worst-mark", "criteria"))) and
          // the field links in the fields' help, which describes the field
          (doc.select("#trackLabel_field a.help-link").attr("data-help-topic") === "tracks") and
          (doc.select("#source_field a.help-link").attr("data-help-topic") === "categories") and
          (doc.select("#excludeCategory_field a.help-link").attr("data-help-topic") === "category-second") and
          (doc.select("#category-more_field a.help-link").attr("data-help-topic") === "category-options") and
          (jurors.select("#averageRule_field a.help-link").attr("data-help-topic") === "score-rule") and
          (doc.select("#topImages_field a.help-link").attr("data-help-topic") === "top-files") and
          linksRight(doc) and linksRight(jurors, jurors.select("#round-jurors-form a[href^='/admin/criteria?']").asScala.toSet)
      }
    }

    "give an existing round's new files, image cache and image check their help links" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}&id=${f.round.getId}")
        (topics(doc) must containAllOf(Seq("create-round", "distribute-new-files", "image-cache", "image-check"))) and
          linksRight(doc, doc.select("#round-form #round-review-link").asScala.toSet)
      }
    }
  }

  "the round's statistics" should {
    "link the guide's statistics for organizers and admins, its pausing a juror for admins only" in {
      testDbApp { app =>
        val f = fixture()
        val org = page(app, f.organizer, s"/roundstat/${f.round.getId}")
        val admin = page(app, f.admin, s"/roundstat/${f.round.getId}")
        (topics(org) must contain("statistics")) and (topics(org) must not(contain("pause-juror"))) and
          (topics(admin) must containAllOf(Seq("statistics", "pause-juror"))) and
          linksRight(org) and linksRight(admin)
      }
    }
  }

  "a round's gallery" should {
    "give a juror the jurors' Help and no organizer's link" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/page/1")
        (topics(doc) === Seq("guide")) and
          (doc.select("#nav-help a.help-link").text must startWith("Help")) and
          (doc.select("#nav-help a.help-link").attr("href") must endWith("/docs/user/juror-guide.md")) and
          linksRight(doc)
      }
    }

    "give an organizer the results' link in the gallery of all jurors, not the jurors' Help" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/gallery/round/${f.round.getId}/user/0/page/1")
        (topics(doc) === Seq("results")) and linksRight(doc)
      }
    }
  }

  "the role-specific help links" should {
    "hide staff help from a juror viewing their comments report and monument page" in {
      testDbApp { app =>
        val f = fixture()
        val commentsPath = s"/comments/round/${f.round.getId}/user/${f.juror.getId}/page/1"
        val monumentPath = s"/monuments/id/12-345-1?contest=${f.contest.getId}&round=${f.round.getId}"
        val comments = page(app, f.juror, commentsPath)
        val monument = page(app, f.juror, monumentPath)
        (topics(comments) must not(contain("comments-report"))) and
          (topics(monument) must not(contain("monuments"))) and
          linksRight(comments) and linksRight(monument)
      }
    }

    "show an organizer the contest files help" in {
      testDbApp { app =>
        val f = fixture()
        val files = page(app, f.organizer, s"/contest/${f.contest.getId}/files")
        (topics(files) must contain("contest-files")) and linksRight(files)
      }
    }

    "show root the Contests, Series and image check topics" in {
      testDbApp { app =>
        val f = fixture()
        val root = userDao.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), isRoot = true))
        val contests = page(app, root, "/contests")
        val series = page(app, root, "/admin/series")
        val rounds = page(app, root, s"/admin/rounds?contestId=${f.contest.getId}")
        (topics(contests) must contain("contest-settings")) and
          (topics(series) must contain("root")) and
          (topics(rounds) must containAllOf(Seq("image-check", "normalize-authors"))) and
          linksRight(contests) and linksRight(series) and linksRight(rounds)
      }
    }
  }

  "the account form" should {
    "link changing a person for an admin, not on one's own profile" in {
      testDbApp { app =>
        val f = fixture()
        val admin = page(app, f.admin, s"/users/edit/${f.juror.getId}?contestId=${f.contest.getId}")
        val own = page(app, f.juror, s"/users/edit/${f.juror.getId}?contestId=${f.contest.getId}")
        val users = page(app, f.admin, s"/admin/users?contestId=${f.contest.getId}")
        (topics(admin) must contain("edit-person")) and (topics(own) must beEmpty) and
          (topics(users) must contain("add-person")) and linksRight(admin) and linksRight(users)
      }
    }
  }
}
