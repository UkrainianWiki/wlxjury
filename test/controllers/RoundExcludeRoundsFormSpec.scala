package controllers

import db.scalikejdbc.{PlayTestDb, Round, User, VoteJdbc}
import org.intracer.wmua.{ContestJury, Image}
import org.jsoup.Jsoup
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The round form's "Leave out files that are in round(s)" (V95): the contest's other rounds offered,
  * with its help; saved with a new round and filling it without their files; shown, selected and
  * disabled, on the round's form; refused with the form's error for another contest's round, a value
  * that is no round and the round itself.
  */
class RoundExcludeRoundsFormSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  /** A contest with an admin, two jurors, four images and two rounds: Round 1 of all four, Round 2
    * of the first two; and another contest's round.
    */
  private def fixture(): (ContestJury, User, Seq[Long], Round, Round, Round) = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    contestDao.setImagesSource(contest.getId, Some("Category:WLE images"))
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    val jurors = (1 to 2).map(i =>
      userDao.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id)).getId)
    val images = (1L to 4L).map(image)
    imageDao.batchInsert(images)
    db.scalikejdbc.CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    val first = roundDao.create(Round(None, 1, Some("Round 1"), contest.getId, active = true))
    val second = roundDao.create(Round(None, 2, Some("Round 2"), contest.getId, active = true))
    for (img <- images; juror <- jurors) VoteJdbc.create(img.pageId, rate = 0, juryId = juror, roundId = first.getId)
    for (img <- images.take(2); juror <- jurors) VoteJdbc.create(img.pageId, rate = 0, juryId = juror, roundId = second.getId)
    val other = roundDao.create(Round(None, 1, Some("Elsewhere"), contestDao.create(None, "WLM", 2024, "Ukraine").getId))
    (contest, admin, jurors, first, second, other)
  }

  private def get(url: String, user: User) =
    FakeRequest(GET, url).withSession(Secured.UserName -> user.email).withCSRFToken

  private def save(contestId: Long, jurors: Seq[Long], user: User, extra: Seq[(String, String)]) =
    FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> user.email)
      .withFormUrlEncodedBody(
        Seq("number" -> "0", "name" -> "Round 3", "contest" -> contestId.toString, "roles" -> "jury",
          "distribution" -> "1", "rates" -> "1", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
          "submitToken" -> java.util.UUID.randomUUID().toString) ++ extra ++
          jurors.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString }: _*)
      .withCSRFToken

  /** The select's options (value, text), the selected values, and whether it is disabled. */
  private def select(html: String) = {
    val doc = Jsoup.parse(html)
    val select = doc.selectFirst("select#excludeRounds")
    val options = select.select("option").asScala.toSeq
    (options.map(o => o.attr("value") -> o.text), options.filter(_.hasAttr("selected")).map(_.attr("value")),
      select.hasAttr("disabled"), doc)
  }

  "the round form's rounds to leave out" should {

    "offer the contest's rounds on a new round's form, with the help, none selected" in {
      testDbApp { implicit app =>
        val (contest, admin, _, first, second, _) = fixture()
        val (options, selected, disabled, doc) =
          select(contentAsString(route(app, get(s"/admin/rounds/edit?contestId=${contest.getId}", admin)).get))
        (options === Seq(first.getId.toString -> "Round 1", second.getId.toString -> "Round 2")) and
          (selected must beEmpty) and (disabled must beFalse) and
          (doc.text must contain("Leave out files that are in round(s)")) and
          (doc.text must contain("however it was voted there")) and
          (doc.select("select#excludeRounds[name=excludeRounds[]][multiple]").size === 1)
      }
    }

    "save them with a new round, which gets the previous round's files that aren't in them" in {
      testDbApp { implicit app =>
        val (contest, admin, jurors, first, second, _) = fixture()
        val result = route(app, save(contest.getId, jurors, admin,
          Seq("previousRound[]" -> first.getId.toString, "excludeRounds[]" -> second.getId.toString))).get
        val (saved, error) = (status(result), flash(result).get("error"))
        val created = roundDao.findByContest(contest.getId).find(_.name.contains("Round 3"))
        (saved must_== SEE_OTHER) and (error must beNone) and
          (created.map(_.excludeRounds) must beSome(Seq(second.getId))) and
          (created.map(r => VoteJdbc.byRound(r.getId).map(_.pageId).toSet) === Some(Set(3L, 4L)))
      }
    }

    "show a round's stored ones selected, disabled, without the round itself" in {
      testDbApp { implicit app =>
        val (contest, admin, jurors, first, second, _) = fixture()
        status(route(app, save(contest.getId, jurors, admin,
          Seq("previousRound[]" -> first.getId.toString, "excludeRounds[]" -> second.getId.toString))).get)
        val round = roundDao.findByContest(contest.getId).find(_.name.contains("Round 3")).get
        val (options, selected, disabled, _) = select(contentAsString(route(app,
          get(s"/admin/rounds/edit?contestId=${contest.getId}&id=${round.getId}", admin)).get))
        (options.map(_._1) === Seq(first.getId.toString, second.getId.toString)) and
          (selected === Seq(second.getId.toString)) and (disabled must beTrue)
      }
    }

    "refuse another contest's round and a value that is no round, keeping the form, creating nothing" in {
      testDbApp { implicit app =>
        val (contest, admin, jurors, first, _, other) = fixture()
        def refused(value: String) = {
          val result = route(app, save(contest.getId, jurors, admin,
            Seq("previousRound[]" -> first.getId.toString, "excludeRounds[]" -> value))).get
          (status(result) must_== BAD_REQUEST) and
            (Jsoup.parse(contentAsString(result)).text must contain("The rounds to leave out must be other rounds of this contest."))
        }
        refused(other.getId.toString) and refused("abc") and refused((other.getId + 1000).toString) and
          (roundDao.findByContest(contest.getId).map(_.name.get) === Seq("Round 1", "Round 2"))
      }
    }

    "refuse the round itself on its form" in {
      testDbApp { implicit app =>
        val (contest, admin, jurors, _, second, _) = fixture()
        val result = route(app, save(contest.getId, jurors, admin,
          Seq("id" -> second.getId.toString, "number" -> "2", "name" -> "Round 2",
            "excludeRounds[]" -> second.getId.toString))).get
        (status(result) must_== BAD_REQUEST) and
          (Jsoup.parse(contentAsString(result)).text must contain("A round can't leave out its own files.")) and
          (roundDao.findById(second.getId).map(_.excludeRounds) must beSome(Seq.empty[Long]))
      }
    }

    "round-trip through the form: filled as stored, bound back, each once, in their order" in {
      val round = new Round(id = None, number = 3, name = Some("R"), contestId = 1, excludeRounds = Seq(5L, 2L))
      val filled = EditRound.editRoundForm.fill(EditRound(round, Seq(1L), None))
      (filled.data.filter(_._1.startsWith("excludeRounds")) ===
        Map("excludeRounds[0]" -> "5", "excludeRounds[1]" -> "2")) and
        (EditRound.excludeRoundIdsOf(Seq("5", " 2 ", "5", "", "x")) === Seq(5L, 2L, -1L))
    }
  }
}
