package controllers

import db.scalikejdbc._
import org.intracer.wmua.Image
import org.jsoup.Jsoup
import play.api.Application
import play.api.libs.json.{JsNull, JsObject, JsString, JsValue}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The round form's saves in the round's settings log (RoundChanges, V115): an edit writes one
  * `round_changed` entry with who and, of the settings it changed, the old and new values as
  * persisted, and none when it changes nothing; a refused edit saves nothing and logs nothing; a new
  * round writes `round_created` with its settings. The round's statistics show the entries to those
  * who see its organiser view (admin, organizer), not to a juror of the open round; the contest's
  * change log to root only, as before.
  */
class RoundChangesSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** A rated round R, open, voted by its juror and by an admin who judges it (`adminj`). */
  private def setUp(): Round = {
    createContests(contestId, 20)
    imageDao.batchInsert(Seq(Image(1, "File:Image1.jpg", None, None, 640, 480, Some("12-345-1"))))
    val juror = member("juror@example.com", "jury")
    val adminJ = member("adminj@example.com", "admin")
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    val round = roundDao.create(Round(None, 1, Some("R"), contestId, rates = Round.ratesById(5), active = true))
    round.addUsers(Seq(juror, adminJ).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    VoteJdbc.create(1, 0, juror.getId, round.getId)
    VoteJdbc.create(1, 0, adminJ.getId, round.getId)
    round
  }

  /** The edit form as the page posts it, without the score and worst mark fields unless given. */
  private def form(round: Round, name: String, extra: Seq[(String, String)] = Nil) =
    Seq("id" -> round.getId.toString, "number" -> round.number.toString, "name" -> name,
      "contest" -> round.contestId.toString, "roles" -> "jury", "distributeAfter" -> "true", "distribution" -> "0",
      "rates" -> round.rates.id.toString, "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
      User.findRoundJurors(round.getId).zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString } ++ extra

  private def save(app: Application, email: String, body: Seq[(String, String)]): Future[Result] =
    route(app, FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withFormUrlEncodedBody(body: _*)
      .withCSRFToken).get

  private def page(app: Application, email: String, path: String): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString)
      .withCSRFToken).get

  private def entries(roundId: Long): Seq[ContestChange] =
    ContestChangeJdbc.findByRound(contestId, roundId, 50)
      .filter(c => ContestChangeJdbc.RoundSettingKinds.contains(c.kind)).reverse

  private def changed(c: ContestChange): Map[String, (JsValue, JsValue)] =
    (c.detail \ "changed").as[JsObject].fields.map { case (f, v) => f -> ((v \ "from").get, (v \ "to").get) }.toMap

  private def adminId = User.findByEmail("admin@example.com").head.getId

  "the round form's edit" should {

    "log what it changed, from and to, with who and the path; nothing when it changes nothing" in {
      testDbApp { app =>
        val round = setUp()
        val first = status(save(app, "admin@example.com", form(round, "R2", Seq("averageRule" -> "sum"))))
        val same = status(save(app, "admin@example.com", form(round, "R2", Seq("averageRule" -> "sum"))))
        val logged = entries(round.getId)
        (first === SEE_OTHER) and (same === SEE_OTHER) and (logged.size === 1) and
          (logged.head.kind === ContestChangeJdbc.RoundChanged) and (logged.head.actorId === adminId) and
          ((logged.head.detail \ "round").as[Long] === round.getId) and ((logged.head.detail \ "number").as[Long] === 1L) and
          ((logged.head.detail \ "path").as[String] === "edit") and
          (changed(logged.head) === Map("name" -> (JsString("R"), JsString("R2")),
            "averageRule" -> (JsString("rated"), JsString("sum")))) and
          // never an email in the entry
          (logged.head.detail.toString must not(contain("@example.com")))
      }
    }

    "log its criteria and its conflicts flag with the other settings, in one entry" in {
      testDbApp { app =>
        setUp()
        val fresh = roundDao.create(Round(None, 2, Some("S"), contestId, rates = Round.ratesById(10)))
        val criteria = CriterionJdbc.all().take(2)
        val result = save(app, "admin@example.com", form(fresh, "S", Seq("criteriaShown" -> "true", "excludeCoi" -> "true") ++
          criteria.zipWithIndex.map { case (c, i) => s"criteria[$i]" -> c.id.toString }))
        // the request done first: then its entry
        val code = status(result)
        val logged = entries(fresh.getId)
        (code === SEE_OTHER) and (logged.size === 1) and
          (changed(logged.head) === Map(
            "criteria" -> (JsNull, play.api.libs.json.Json.toJson(criteria.map(_.fallbackName))),
            "excludeCoi" -> (play.api.libs.json.JsBoolean(false), play.api.libs.json.JsBoolean(true))))
      }
    }

    "save nothing and log nothing when its criteria change is refused (a started round)" in {
      testDbApp { app =>
        val round = setUp()
        val criteria = CriterionJdbc.all().take(2)
        val result = save(app, "admin@example.com", form(round, "Renamed", Seq("criteriaShown" -> "true") ++
          criteria.zipWithIndex.map { case (c, i) => s"criteria[$i]" -> c.id.toString }))
        (status(result) === BAD_REQUEST) and (Round.findById(round.getId).flatMap(_.name) === Some("R")) and
          (RoundCriterion.idsOf(round.getId) must beEmpty) and (entries(round.getId) must beEmpty)
      }
    }
  }

  "the round form's new round" should {

    "log its settings, from none, without its lifecycle" in {
      testDbApp { app =>
        val round = setUp()
        val juror = User.findByEmail("juror@example.com").head
        val body = Seq("number" -> "2", "name" -> "Next", "contest" -> contestId.toString, "roles" -> "jury",
          "distributeAfter" -> "true", "distribution" -> "0", "rates" -> "5", "minMpx" -> "2", "minSize" -> "",
          "mediaType" -> "all", "jurors[0]" -> juror.getId.toString, "previousRound[0]" -> round.getId.toString)
        val code = status(save(app, "admin@example.com", body))
        val created = Round.findByContest(contestId).find(_.name.contains("Next"))
        val logged = created.toSeq.flatMap(r => entries(r.getId))
        val values = logged.headOption.map(changed).getOrElse(Map.empty)
        (code === SEE_OTHER) and (logged.map(_.kind) === Seq(ContestChangeJdbc.RoundCreated)) and
          ((logged.head.detail \ "path").as[String] === "create") and (logged.head.actorId === adminId) and
          (values.get("name") === Some(JsNull -> JsString("Next"))) and
          (values.get("rates") === Some(JsNull -> JsString("1-5 rating"))) and
          (values.get("minMpx") === Some(JsNull -> play.api.libs.json.JsNumber(2))) and
          (values.get("previous") === Some(JsNull -> play.api.libs.json.Json.arr(round.getId))) and
          (values.keySet must not(contain("active"))) and (values.keySet must not(contain("status"))) and
          (values.keySet must not(contain("excludeCoi")))
      }
    }
  }

  "a round's settings entries" should {

    "show on its statistics to an admin and an organizer, not to a juror of the open round" in {
      testDbApp { app =>
        val round = setUp()
        status(save(app, "admin@example.com", form(round, "R2"))) === SEE_OTHER
        def lines(email: String) = Jsoup.parse(contentAsString(page(app, email, s"/roundstat/${round.getId}")))
          .select("#round-setting-changes li.round-setting-change")
        val admin = lines("admin@example.com")
        (admin.eachText().asScala.map(_.replaceFirst("^.* UTC — ", "")) ===
          Seq("Round 1 changed by admin@example.com: Name: R → R2")) and
          (admin.eachAttr("data-path").asScala === Seq("edit")) and
          (lines("organizer@example.com").size === 1) and
          // an admin who judges the open round: their own votes' view, without the entries
          (lines("adminj@example.com").size === 0)
      }
    }

    "show in the contest's change log to root, which stays root's only" in {
      testDbApp { app =>
        val round = setUp()
        User.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), isRoot = true))
        status(save(app, "admin@example.com", form(round, "R2"))) === SEE_OTHER
        val doc = Jsoup.parse(contentAsString(page(app, "root@example.com", s"/admin/contests/$contestId/series")))
        val row = doc.select("tr.contest-change[data-kind=round_changed]")
        (row.size === 1) and (row.select("td").last.text === "Round 1: Name: R → R2") and
          (row.select("td").get(1).text === "Round settings changed") and
          (status(page(app, "admin@example.com", s"/admin/contests/$contestId/series")) === FORBIDDEN)
      }
    }
  }
}
