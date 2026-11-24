package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.series.{ContestSeries, SeriesAdmins}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The conflict-of-interest rule (the maintainer's decision of 2026-11-24): a juror of a round sees
  * only their own votes in it, whatever their contest role: a contest admin or organizer, a series
  * admin by a derived role, root. Another juror's votes and all the jurors' together are refused by
  * every route (the gallery, by rate, the file list, the CSV, the large view as a page and as JSON);
  * the round's statistics keep each juror's progress but show no other juror's selections or
  * rejections, nor the totals by vote; the round's authors with their votes are refused. In a round
  * they don't judge, the same people see every juror's votes; root, not a juror, too.
  */
class JurorConflictVotesSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(round: Round, other: Round, juror2: User, adminJ: User, orgJ: User, seriesJ: User,
      rootJ: User, root: User, images: Seq[Image])

  /** A binary round R judged by juror 2 and by an admin, an organizer, a series admin (a derived
    * Admin, no real row) and a root of the contest; a round R2 judged by juror 2 only.
    */
  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    val year = java.time.Year.now().getValue
    val c = contestDao.create(None, "WLE", year, "Ukraine")
    val round = roundDao.create(Round(None, 1, Some("R"), contestId = c.getId, rates = Round.binaryRound, active = true))
    val other = roundDao.create(Round(None, 2, Some("R2"), contestId = c.getId, rates = Round.binaryRound, active = true))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = c.id))
    val juror2 = user("Juror Two", "jury")
    val adminJ = user("Admin Juror", "admin")
    val orgJ = user("Org Juror", "organizer")
    val seriesJ = user("Series Juror", "organizer")
    val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    val rootJ = User.create(User("Root Juror", "rootjuror@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    val s = ContestSeries.createSeriesTx("WLE in Ukraine", Some("wle"), Some("Ukraine"), None, root.getId).toOption.get
    ContestSeries.setSeries(c.getId, Some(s), root.getId, None) must beRight
    SeriesAdmins.grant(root, s, seriesJ.getId, None, None) must beRight
    UserContestJdbc.delete(seriesJ.getId, c.getId)
    val images = (0L until 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    val jurorsOfR = Seq(juror2, adminJ, orgJ, seriesJ, rootJ)
    round.addUsers(jurorsOfR.map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    selectionDao.batchInsert(for (j <- jurorsOfR; image <- images) yield Vote(image, j, round, rate = 1))
    other.addUsers(Seq(RoundUser(other.getId, juror2.getId, "jury", active = true)))
    selectionDao.batchInsert(for (image <- images) yield Vote(image, juror2, other, rate = 1))
    Fixture(round, other, juror2, adminJ, orgJ, seriesJ, rootJ, root, images)
  }

  private def get(app: Application, viewer: User, path: String, json: Boolean = false): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email)
      .withHeaders("Accept" -> (if (json) "application/json" else "text/html"))
      .withCSRFToken).get

  private def paths(round: Round, asUserId: Long, pageId: Long): Seq[String] = Seq(
    s"/gallery/round/${round.getId}/user/$asUserId/page/1",
    s"/byrate/round/${round.getId}/user/$asUserId/page/1",
    s"/filelist/round/${round.getId}/user/$asUserId/page/1",
    s"/query?module=csv&round=${round.getId}&user=$asUserId",
    s"/large/round/${round.getId}/user/$asUserId/pageid/$pageId"
  )

  private def views(app: Application, viewer: User, round: Round, asUserId: Long, pageId: Long, expected: Int) =
    forall(paths(round, asUserId, pageId)) { path =>
      (viewer.fullname, path, status(get(app, viewer, path))) === ((viewer.fullname, path, expected))
    } and
      ((viewer.fullname, status(get(app, viewer, s"/large/round/${round.getId}/user/$asUserId/pageid/$pageId", json = true))) ===
        ((viewer.fullname, expected)))

  "a juror of a round with another role" should {

    "see only their own votes in it by every route, and every juror's in a round they don't judge" in {
      testDbApp { app =>
        val f = fixture()
        val page = f.images.head.pageId
        forall(Seq(f.adminJ, f.orgJ, f.seriesJ, f.rootJ)) { viewer =>
          views(app, viewer, f.round, f.juror2.getId, page, FORBIDDEN) and
            views(app, viewer, f.round, 0L, page, FORBIDDEN) and
            views(app, viewer, f.round, viewer.getId, page, OK)
        } and
          forall(Seq(f.adminJ, f.orgJ, f.seriesJ, f.rootJ)) { viewer =>
            views(app, viewer, f.other, f.juror2.getId, page, OK) and views(app, viewer, f.other, 0L, page, OK)
          } and
          // root, not a juror of the round, is unaffected
          views(app, f.root, f.round, f.juror2.getId, page, OK) and views(app, f.root, f.round, 0L, page, OK)
      }
    }

    "keep each juror's progress on the round's statistics but no other juror's votes or totals by vote" in {
      testDbApp { app =>
        val f = fixture()
        val mine = Jsoup.parse(contentAsString(get(app, f.adminJ, s"/roundstat/${f.round.getId}")))
        val theirs = Jsoup.parse(contentAsString(get(app, f.root, s"/roundstat/${f.round.getId}")))
        val otherRows = mine.select("tbody tr").eachText().asScala
        (otherRows.size must be_>=(3)) and
        (mine.select("td.juror-selected").eachText().asScala.count(_ == "—") === otherRows.size - 1) and
          (mine.select("th.round-total-rate").eachText().asScala.forall(_ == "—") must beTrue) and
          (mine.select(s"table a[href*=/user/${f.juror2.getId}/]").size === 0) and
          (mine.select("table a[href*=/user/0/]").size === 0) and
          (theirs.select("td.juror-selected").eachText().asScala.count(_ == "—") === 0) and
          (theirs.select(s"table a[href*=/user/${f.juror2.getId}/]").size must be_>(0))
      }
    }

    "be refused the round's authors with their votes, not the contest's authors" in {
      testDbApp { app =>
        val f = fixture()
        val c = f.round.contestId
        (status(get(app, f.adminJ, s"/contest/$c/authors?round=${f.round.getId}")) === FORBIDDEN) and
          (status(get(app, f.adminJ, s"/contest/$c/authors?round=${f.other.getId}")) === OK) and
          (status(get(app, f.adminJ, s"/contest/$c/authors")) === OK) and
          (status(get(app, f.root, s"/contest/$c/authors?round=${f.round.getId}")) === OK)
      }
    }

    "see the round's results as any admin once it is closed; a paused or reviewed round counts as open" in {
      testDbApp { app =>
        val f = fixture()
        val page = f.images.head.pageId
        def set(sql: String) = scalikejdbc.DB.autoCommit(implicit s => scalikejdbc.SQL(sql).update())
        // paused by a freeze while open: still open
        set(s"UPDATE round SET active = 0, freeze_count = 1, active_before_freeze = 1 WHERE id = ${f.round.getId}")
        val paused = views(app, f.adminJ, f.round, 0L, page, FORBIDDEN)
        // being reviewed (distributing): open too
        set(s"UPDATE round SET freeze_count = 0, active_before_freeze = 0, distributing = 1 WHERE id = ${f.round.getId}")
        val reviewed = status(get(app, f.adminJ, s"/roundstat/${f.round.getId}")) === OK and
          (status(get(app, f.adminJ, s"/contest/${f.round.contestId}/authors?round=${f.round.getId}")) === FORBIDDEN)
        // closed (stopped): results for all
        set(s"UPDATE round SET distributing = 0 WHERE id = ${f.round.getId}")
        paused and reviewed and
          forall(Seq(f.adminJ, f.orgJ, f.seriesJ, f.rootJ)) { viewer =>
            views(app, viewer, f.round, f.juror2.getId, page, OK) and views(app, viewer, f.round, 0L, page, OK)
          } and
          (status(get(app, f.adminJ, s"/contest/${f.round.contestId}/authors?round=${f.round.getId}")) === OK) and
          (Jsoup.parse(contentAsString(get(app, f.adminJ, s"/roundstat/${f.round.getId}")))
            .select("td.juror-selected").eachText().asScala.count(_ == "—") === 0)
      }
    }

    "open a round they judge on their own votes from the round menu of an all-jurors list" in {
      testDbApp { app =>
        val f = fixture()
        def roundLink(viewer: User) =
          Jsoup.parse(contentAsString(get(app, viewer, s"/gallery/round/${f.other.getId}/user/0/page/1")))
            .select("#round-menu .dropdown-item").asScala.map(_.attr("href")).find(_.contains(s"/round/${f.round.getId}/"))
        (roundLink(f.adminJ).exists(_.startsWith(s"/gallery/round/${f.round.getId}/user/${f.adminJ.getId}/")) must beTrue) and
          (roundLink(f.root).exists(_.startsWith(s"/gallery/round/${f.round.getId}/user/0/")) must beTrue) and
          (roundLink(f.adminJ).exists(href => status(get(app, f.adminJ, href)) == OK) must beTrue)
      }
    }
  }
}
