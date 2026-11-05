package controllers

import db.scalikejdbc._
import org.apache.pekko.stream.Materializer
import org.intracer.wmua.{AverageRule, Image, Vote}
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.counts.RoundCountsRecompute

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Every view of a rated round's results by each score rule (V90, [[AverageRule]]), through the
  * router, read from the votes and from `round_image`'s counts (`roundImage.read`, P2b): the
  * by-rate gallery (order and text), the large view for all the jurors, the file list, the CSV,
  * all agreeing. Two files of three jurors: F1 7, 8 and one unrated vote (sum 15); F2 6, 6, 6
  * (sum 18). A stopped juror's votes still count; a vote after the recount (its file marked dirty)
  * shows at once from the counts too.
  */
class AverageRulePagesSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false))

  private case class Fixture(round: Round, organizer: User, jurors: Seq[User])

  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, Some("Rated"), contestId = contest.getId, rates = Round.ratesById(10),
      active = true))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = contest.id))
    val jurors = (1 to 3).map(i => user(s"Juror $i", "jury"))
    val organizer = user("Org", "organizer")
    round.addUsers(jurors.map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    val images = Seq(1L, 2L).map(id => Image(id, s"File:F$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    selectionDao.batchInsert(Seq(
      Vote(images(0), jurors(0), round, 7), Vote(images(0), jurors(1), round, 8), Vote(images(0), jurors(2), round, 0),
      Vote(images(1), jurors(0), round, 6), Vote(images(1), jurors(1), round, 6), Vote(images(1), jurors(2), round, 6)))
    recompute.untilClean()
    Fixture(round, organizer, jurors)
  }

  private def withRule(f: Fixture, rule: AverageRule): Unit = Round.updateRound(f.round.getId, f.round.copy(averageRule = rule))

  private def get(app: Application, viewer: User, path: String): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email, Secured.CurrentContestId -> viewer.contestId.fold("")(_.toString))
      .withHeaders("Accept" -> "text/html")
      .withCSRFToken).get

  /** What the views show: the by-rate gallery's files in order with their texts, the large view's
    * text for F1, the file list's rate column by title, the CSV's Overall by title.
    */
  private def views(app: Application, f: Fixture): Views = {
    implicit val mat: Materializer = app.materializer
    val r = f.round.getId
    val byRate = Jsoup.parse(contentAsString(get(app, f.organizer, s"/byrate/round/$r/user/0/page/1")))
    val tiles = byRate.select(".gallerybox").asScala.toSeq.map(t => t.attr("id") -> t.select(".rate-stars").text().trim)
    val large = Jsoup.parse(contentAsString(get(app, f.organizer, s"/large/round/$r/user/0/pageid/1")))
      .select(".wlx-overall-rate").text()
    val fileList = Jsoup.parse(contentAsString(get(app, f.organizer, s"/filelist/round/$r/user/0/page/1")))
      .select("tbody tr").asScala.map(_.select("td").asScala.map(_.text).toSeq).map(c => c(1) -> c(2)).toMap
    val csv = com.github.tototoshi.csv.CSVReader.open(new java.io.StringReader(
      contentAsString(get(app, f.organizer, s"/query?module=csv&round=$r&user=0")).stripPrefix("﻿"))).all()
      .tail.filter(_.size > 2).map(row => row(1) -> row(2)).toMap
    (tiles, large, fileList, csv)
  }

  /** Each rule's expected views. */
  private val expected: Map[AverageRule, Views] = Map(
    AverageRule.Rated -> ((Seq("1" -> "7.50 (15 / 2 of 3 jurors)", "2" -> "6.00 (18 / 3 jurors)"),
      "7.50 (15 / 2 of 3 jurors)",
      Map("File:F1.jpg" -> "7.50 (15 / 2 of 3 jurors)", "File:F2.jpg" -> "6.00 (18 / 3 jurors)"),
      Map("File:F1.jpg" -> "7.50 (15 / 2)", "File:F2.jpg" -> "6.00 (18 / 3)"))),
    AverageRule.Assigned -> ((Seq("2" -> "6.00 (18 / 3 jurors)", "1" -> "5.00 (15 / 3 jurors)"),
      "5.00 (15 / 3 jurors)",
      Map("File:F1.jpg" -> "5.00 (15 / 3 jurors)", "File:F2.jpg" -> "6.00 (18 / 3 jurors)"),
      Map("File:F1.jpg" -> "5.00 (15 / 3)", "File:F2.jpg" -> "6.00 (18 / 3)"))),
    AverageRule.Sum -> ((Seq("2" -> "18 (sum of 3 jurors)", "1" -> "15 (sum of 3 jurors)"),
      "15 (sum of 3 jurors)",
      Map("File:F1.jpg" -> "15 (sum of 3 jurors)", "File:F2.jpg" -> "18 (sum of 3 jurors)"),
      Map("File:F1.jpg" -> "15", "File:F2.jpg" -> "18")))
  )

  private type Views = (Seq[(String, String)], String, Map[String, String], Map[String, String])

  /** The views as expected: the tiles in order, each tile's text containing its expected one
    * (the stars' markup is around it).
    */
  private def agree(got: Views, want: Views) =
    (got._1.map(_._1) === want._1.map(_._1)) and
      forall(got._1.zip(want._1)) { case ((_, text), (_, w)) => text must contain(w) } and
      (got._2 === want._2) and (got._3 === want._3) and (got._4 === want._4)

  private def allRules(app: Application) = {
    val f = fixture()
    forall(AverageRule.all) { rule =>
      withRule(f, rule)
      agree(views(app, f), expected(rule))
    }
  }

  "a rated round's views" should {

    "follow the round's score rule, read from the votes" in {
      testDbApp(allRules)
    }

    "follow it the same, read from round_image's counts" in {
      testDbApp(allRules)(Map("roundImage.read" -> "true"))
    }

    "count a stopped juror's votes, as before V90" in {
      testDbApp { app =>
        val f = fixture()
        RoundUser.setActive(f.round.getId, f.jurors(1).getId, active = false)
        agree(views(app, f), expected(AverageRule.Rated))
      }
    }

    "show a vote made after the recount at once from the counts, its file marked dirty" in {
      testDbApp { app =>
        val f = fixture()
        VoteJdbc.rate(1, f.jurors(2).getId, f.round.getId, 10) must be_>(0)
        val dirty = RoundCounts.state(f.round.getId).map(_.dirtyFiles)
        (dirty must beSome(1L)) and agree(views(app, f),
          (Seq("1" -> "8.33 (25 / 3 jurors)", "2" -> "6.00 (18 / 3 jurors)"), "8.33 (25 / 3 jurors)",
            Map("File:F1.jpg" -> "8.33 (25 / 3 jurors)", "File:F2.jpg" -> "6.00 (18 / 3 jurors)"),
            Map("File:F1.jpg" -> "8.33 (25 / 3)", "File:F2.jpg" -> "6.00 (18 / 3)")))
      }(Map("roundImage.read" -> "true"))
    }
  }
}
