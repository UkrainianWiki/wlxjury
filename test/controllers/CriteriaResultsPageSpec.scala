package controllers

import db.scalikejdbc._
import org.apache.pekko.stream.Materializer
import org.intracer.wmua._
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A criteria round's results through the app (C3): each criterion's average in the file list,
  * the by-rate gallery, the CSV (U8) and the large view for all the jurors, over the votes
  * rated in every criterion (U5); names in the viewer's language or by their fallback text,
  * escaped, and a formula-safe CSV; only organisers and admins see all the jurors' results,
  * a juror only their own; the round form says a criteria round's "min average rate" is a sum
  * (U7).
  */
class CriteriaResultsPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  /** An admin's fallback name that is both markup and a spreadsheet formula. */
  private val hostileName = "=HYPERLINK(\"http://x\")<script>alert(1)</script>"

  private case class Fixture(round: Round, custom: Criterion, juror1: User, juror2: User, juror3: User,
      organizer: User, admin: User, jurorB: User, images: Seq[Image])

  /** Contest A's criteria round (10 stars; "Scientific value", V83's 1, and an admin's
    * criterion named [[hostileName]]) with jurors 1-3: file 0 rated 8 + 6 by juror 1, 6 + 9 by
    * juror 2 and only partly by juror 3 (2 in the first criterion: unrated, U5); file 1 5 + 5
    * by juror 1, nothing by juror 2. So file 0's averages are 7 and 7.5, file 1's 5 and 5.
    * Contest B has a juror of its own.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLE", 2024, "Ukraine")
    val b = contestDao.create(None, "WLM", 2024, "Poland")
    val custom = CriterionJdbc.add(hostileName, None).toOption.get
    val round = roundDao.create(Round(None, 1, Some("Criteria round"), contestId = a.getId, rates = Round.ratesById(10),
      active = true, criterionIds = Seq(1L, custom.id)))
    def user(name: String, role: String, contest: ContestJury) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = contest.id))
    val Seq(juror1, juror2, juror3) = (1 to 3).map(i => user(s"Juror $i", "jury", a))
    val organizer = user("Org", "organizer", a)
    val admin = user("Admin", "admin", a)
    val jurorB = user("Juror B", "jury", b)
    val images = (0L until 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    round.addUsers(Seq(juror1, juror2, juror3).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    selectionDao.batchInsert(for (j <- Seq(juror1, juror2, juror3); image <- images) yield Vote(image, j, round, rate = 0))
    def rate(image: Image, juror: User, rates: (Long, Int)*): Unit = {
      val vote = VoteJdbc.findBy(image.pageId, juror.getId, round.getId).get
      rates.foreach { case (criterion, r) => CriteriaRate.updateRate(vote.getId, criterion, r) must beTrue }
    }
    rate(images(0), juror1, 1L -> 8, custom.id -> 6)
    rate(images(0), juror2, 1L -> 6, custom.id -> 9)
    rate(images(0), juror3, 1L -> 2)
    rate(images(1), juror1, 1L -> 5, custom.id -> 5)
    Fixture(roundDao.findById(round.getId).get, custom, juror1, juror2, juror3, organizer, admin, jurorB, images)
  }

  private def get(app: Application, viewer: User, path: String, lang: String = "en"): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email, Secured.CurrentContestId -> viewer.contestId.fold("")(_.toString))
      .withHeaders("Accept" -> "text/html", "Accept-Language" -> lang)
      .withCSRFToken).get

  private def fileList(f: Fixture, asUserId: Long) = s"/filelist/round/${f.round.getId}/user/$asUserId/page/1"
  private def byRate(f: Fixture, asUserId: Long) = s"/byrate/round/${f.round.getId}/user/$asUserId/page/1"
  private def csv(f: Fixture, asUserId: Long) = s"/query?module=csv&round=${f.round.getId}&user=$asUserId"
  private def large(f: Fixture, asUserId: Long, pageId: Long) = s"/large/round/${f.round.getId}/user/$asUserId/pageid/$pageId"

  /** The CSV's rows, read back as a spreadsheet would (the BOM dropped). */
  private def csvRows(result: Future[Result])(implicit mat: Materializer): Seq[Seq[String]] =
    com.github.tototoshi.csv.CSVReader.open(new java.io.StringReader(contentAsString(result).stripPrefix("﻿"))).all()

  /** The file list's table: its head, and each file's cells by title. */
  private def table(html: String): (Seq[String], Map[String, Seq[String]]) = {
    val doc = Jsoup.parse(html)
    val head = doc.select("thead th").asScala.map(_.text).toSeq
    val rows = doc.select("tbody tr").asScala.map(_.select("td").asScala.map(_.text).toSeq)
      .map(cells => cells(1) -> cells).toMap
    head -> rows
  }

  "the results of a criteria round for all its jurors [O-04]" should {

    "show each criterion's average in the file list, over the votes rated in every criterion (U5)" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.organizer, fileList(f, 0))
        status(result) === OK
        val html = contentAsString(result)
        val (head, rows) = table(html)
        head.slice(2, 5) === Seq("Total rating (sum of the criteria)", "Scientific value", hostileName)
        // file 0: 29 over its two rated jurors (the list reads each vote: juror 3's partial one
        // is unrated, U5); averages of jurors 1 and 2 only
        rows("File:Image0.jpg").slice(2, 5) === Seq("14.50 (29 / 2)", "7.00", "7.50")
        rows("File:Image1.jpg").slice(3, 5) === Seq("5.00", "5.00")
        // juror columns stay the sums: 14, 15, and 0 for the partial vote
        rows("File:Image0.jpg").takeRight(3) === Seq("14", "15", "0")
        html must not(contain("<script>alert(1)"))
      }
    }

    "name the criteria in the viewer's language, an admin's by its fallback text in every language" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq("en" -> "Scientific value", "uk" -> "Науковість", "ru" -> "Научность", "fr" -> "Valeur scientifique")) {
          case (lang, name) =>
            val (head, _) = table(contentAsString(get(app, f.organizer, fileList(f, 0), lang)))
            head.slice(3, 5) === Seq(name, hostileName)
        }
      }
    }

    "show each criterion's average under the stars of the by-rate gallery, the stars per criterion" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.admin, byRate(f, 0))
        status(result) === OK
        val html = contentAsString(result)
        val tile = Jsoup.parse(html).getElementById(f.images.head.pageId.toString)
        tile.select(".wlx-criteria-averages li").asScala.map(_.text) ===
          Seq("Scientific value: 7.00", s"$hostileName: 7.50")
        // the gallery's grouped row: 29 over the 3 assigned jurors (juror 3's partly rated vote
        // counts 0), 9.67, over 2 criteria: 4.83, rounded to 5 of the round's 10 stars; the
        // number says so (Q2: its denominator shown)
        tile.select(".rate-stars i.wlx-star").size === 10
        tile.select(".rate-stars i.bi-star-fill").size === 5
        tile.select(".rate-stars").text() must contain("4.83 (Σ 9.67: 29 / 3 jurors)")
        html must not(contain("<script>alert(1)"))
      }
    }

    "export each criterion's average after Overall, the juror columns the sums, the names formula-safe (U8)" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val f = fixture()
        val result = get(app, f.organizer, csv(f, 0))
        status(result) === OK
        val rows = csvRows(result)
        rows.head === Seq("Rank", "File", "Overall", "Scientific value", "'" + hostileName, "Juror 1", "Juror 2", "Juror 3")
        val byTitle = rows.tail.map(r => r(1) -> r).toMap
        byTitle("File:Image0.jpg").drop(2) === Seq("14.50 (29 / 2)", "7.00", "7.50", "14", "15", "0")
        byTitle("File:Image1.jpg").drop(3) === Seq("5.00", "5.00", "10", "0", "0")
      }
    }

    "show the large view's rate per criterion and each criterion's average, and each juror's rates" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.organizer, large(f, 0, f.images.head.pageId))
        status(result) === OK
        val html = contentAsString(result)
        val doc = Jsoup.parse(html)
        // 29 / 3 per juror, over 2 criteria
        doc.getElementById("rate-number").text() === "4.83"
        doc.select(".wlx-criteria-overall-rate").text() === "4.83 (Σ 9.67: 29 / 3 jurors)"
        doc.getElementById("rate-number1").text() === "7"
        doc.getElementById("rate-number2").text() === "7.5"
        doc.select(".large-side .wlx-criterion-name").asScala.map(_.text).take(2) === Seq("Scientific value", hostileName)
        // the votes' list: each juror's rates, juror 3's partial one too
        doc.select(".large-line .wlx-criteria-rates").asScala.map(_.select("li").asScala.map(_.text).toSeq).toSet ===
          Set(Seq("Scientific value: 8", s"$hostileName: 6"), Seq("Scientific value: 6", s"$hostileName: 9"),
            Seq("Scientific value: 2", s"$hostileName: –"))
        html must not(contain("<script>alert(1)"))
      }
    }

    "average the file list's rate over the round's active jurors in an optional-rate round" in {
      testDbApp { app =>
        val f = fixture()
        // juror 3 rates file 1 fully: active in the round, though file 0's vote stays partial
        val vote = VoteJdbc.findBy(f.images(1).pageId, f.juror3.getId, f.round.getId).get
        Seq(1L -> 4, f.custom.id -> 4).foreach { case (c, r) => CriteriaRate.updateRate(vote.getId, c, r) must beTrue }
        DB.autoCommit(implicit s => sql"UPDATE round SET optional_rate = 1 WHERE id = ${f.round.getId}".update())
        val (_, rows) = table(contentAsString(get(app, f.organizer, fileList(f, 0))))
        // over the 3 active jurors, not file 0's 2 rated votes; its averages still over those 2
        rows("File:Image0.jpg").slice(2, 5) === Seq("9.67 (29 / 3)", "7.00", "7.50")
      }
    }
  }

  "a juror of a juryOrgView criteria round [J-28]" should {

    "see all the jurors' averages and each juror's criteria rates, as organisers do" in {
      testDbApp { app =>
        val f = fixture()
        DB.autoCommit(implicit s => sql"UPDATE round SET jury_org_view = 1 WHERE id = ${f.round.getId}".update())
        val (head, rows) = table(contentAsString(get(app, f.juror1, fileList(f, 0))))
        head.slice(3, 5) === Seq("Scientific value", hostileName)
        rows("File:Image0.jpg").slice(3, 5) === Seq("7.00", "7.50")
        val doc = Jsoup.parse(contentAsString(get(app, f.juror1, large(f, 0, f.images.head.pageId))))
        doc.getElementById("rate-number1").text() === "7"
        doc.select(".wlx-criteria-overall-rate").text() === "4.83 (Σ 9.67: 29 / 3 jurors)"
        doc.select(".large-line .wlx-criteria-rates").asScala.map(_.select("li").asScala.map(_.text).toSeq).toSet ===
          Set(Seq("Scientific value: 8", s"$hostileName: 6"), Seq("Scientific value: 6", s"$hostileName: 9"),
            Seq("Scientific value: 2", s"$hostileName: –"))
      }
    }
  }

  "a juror of a criteria round [J-23]" should {

    "see only their own rates as the averages of their file list and CSV" in {
      testDbApp { implicit app =>
        implicit val mat: Materializer = app.materializer
        val f = fixture()
        val (head, rows) = table(contentAsString(get(app, f.juror1, fileList(f, f.juror1.getId))))
        head.slice(3, 5) === Seq("Scientific value", hostileName)
        rows("File:Image0.jpg").slice(3, 5) === Seq("8.00", "6.00")
        val csvResult = csvRows(get(app, f.juror1, csv(f, f.juror1.getId)))
        csvResult.head === Seq("Rank", "File", "Overall", "Scientific value", "'" + hostileName, "Juror 1")
        csvResult.tail.map(r => r(1) -> r.drop(3)).toMap === Map(
          "File:Image0.jpg" -> Seq("8.00", "6.00", "14"),
          "File:Image1.jpg" -> Seq("5.00", "5.00", "10"))
        // juror 3's own partly rated vote has no averages
        val (_, partial) = table(contentAsString(get(app, f.juror3, fileList(f, f.juror3.getId))))
        partial("File:Image0.jpg").slice(3, 5) === Seq("", "")
      }
    }

    "see their own rates in the large view, not the others' or the averages" in {
      testDbApp { app =>
        val f = fixture()
        val html = contentAsString(get(app, f.juror2, large(f, f.juror2.getId, f.images.head.pageId)))
        val doc = Jsoup.parse(html)
        doc.select(".wlx-criteria-overall").size === 0
        doc.getElementById("rate-number1").text() === "6"
        doc.getElementById("rate-number2").text() === "9"
        doc.select(".large-line li").size === 0
      }
    }

    "be refused all the jurors' results and another juror's, as is a juror of another contest" in {
      testDbApp { app =>
        val f = fixture()
        val page = f.images.head.pageId
        forall(Seq(
          f.juror1 -> 0L, f.juror1 -> f.juror2.getId, f.jurorB -> 0L, f.jurorB -> f.juror1.getId
        )) { case (viewer, asUserId) =>
          forall(Seq(fileList(f, asUserId), byRate(f, asUserId), csv(f, asUserId), large(f, asUserId, page))) { path =>
            val result = get(app, viewer, path)
            (path, status(result)) === (path, FORBIDDEN)
            contentAsString(result) must not(contain("7.50"))
          }
        }
      }
    }

    "be sent to the login page signed out" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(fileList(f, 0), csv(f, 0), large(f, 0, f.images.head.pageId))) { path =>
          val result = route(app, FakeRequest(GET, path).withHeaders("Accept" -> "text/html")).get
          status(result) === SEE_OTHER
          redirectLocation(result) must beSome(startWith("/auth"))
        }
      }
    }
  }

  "the round form [A-01]" should {

    "say that a criteria round's min average rate is a sum (U7), and not without one" in {
      testDbApp { app =>
        val f = fixture()
        val contestId = f.round.contestId
        val html = contentAsString(get(app, f.admin, s"/admin/rounds/edit?contestId=$contestId"))
        val help = Jsoup.parse(html).getElementById("minAvgRate_help")
        help must not(beNull)
        help.text() must contain("a sum of the criteria rates, averaged over the jurors")

        DB.autoCommit { implicit s =>
          sql"DELETE FROM round_criterion WHERE round_id = ${f.round.getId}".update()
          sql"UPDATE round SET has_criteria = 0 WHERE id = ${f.round.getId}".update()
        }
        Jsoup.parse(contentAsString(get(app, f.admin, s"/admin/rounds/edit?contestId=$contestId")))
          .getElementById("minAvgRate_help") must beNull
      }
    }
  }
}
