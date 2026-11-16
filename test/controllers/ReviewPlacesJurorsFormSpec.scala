package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.{CommentJdbc, ContestJury, Image}
import play.api.Application
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.TestRoundServices
import services.imports.ContestCountries
import services.places.PlaceScheme

import scala.concurrent.Future

/** The round-review improvements of 2026-11-16 (the maintainer's decisions):
  *
  *  1. One shared place filter of one level ([[PlaceScheme]]): an international contest's countries,
  *     a national one's regions; the same counts on the contest files page, a round's review page
  *     (per status tab), its jurors form and, after distribution, its gallery; the country menu for
  *     the round's staff only (a juror gets none, nor its JSON, and a juror's `country=` is ignored).
  *  2. The jurors form ([[RoundReviewController.jurors]]): the round's admins only; its refusals
  *     back to it; the new-round form without jurors, file distribution or D4's box, with "Review
  *     images first" and "Choose jurors next".
  *  3. Staff comments on a round in review: ordinary round comments, which jurors see once it is
  *     distributed, not before.
  */
class ReviewPlacesJurorsFormSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L
  private val nationalId = 30L

  private def member(email: String, role: String, contest: Long = contestId): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  private final case class Fixture(round: Round, jurors: Seq[User], contest: ContestJury)

  /** Contest 10, international (WLE 2026, no monument ids): files 1-3 in Armenia, 4-5 in Bénin, 6
    * of no country, all in its category; two jurors, an organizer, an admin; a round in review
    * with all six files and no juror. Contest 20 with its own admin.
    */
  private def setUp(): Fixture = {
    createContests(contestId, 20)
    DB.autoCommit { implicit s =>
      sql"""UPDATE contest_jury SET name = 'Wiki Loves Earth', country = 'international', year = 2026,
            monument_id_template = NULL WHERE id = $contestId""".update()
    }
    val contest = contestDao.findById(contestId).get
    val imgs = (1L to 6L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, None))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contest.categoryId.get, imgs)
    val prefix = ContestCountries.prefixes(contest).head
    DB.autoCommit { implicit s =>
      CategoryLinkJdbc.addCountries(Seq(1L, 2L, 3L).map(_ -> (prefix + "Armenia")) ++
        Seq(4L, 5L).map(_ -> (prefix + "Bénin")))
    }
    val jurors = Seq(member("juror1@example.com", "jury"), member("juror2@example.com", "jury"))
    member("organizer@example.com", "organizer")
    member("admin@example.com", "admin")
    member("other-admin@example.com", "admin", contest = 20)
    val round = TestRoundServices.roundService()
      .submit(Round(None, 0, Some("R"), contestId, active = true), Nil, None, review = true).round
    Fixture(roundDao.findById(round.getId).get, jurors, contest)
  }

  private def request(method: String, email: String, path: String, body: Seq[(String, String)] = Nil) = {
    val contest = if (email.startsWith("other-")) 20L else contestId
    FakeRequest(method, path).withFormUrlEncodedBody(body: _*)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString).withCSRFToken
  }

  private def get(app: Application, email: String, path: String): Future[Result] = route(app, request(GET, email, path)).get
  private def post(app: Application, email: String, path: String, body: (String, String)*): Future[Result] =
    route(app, request(POST, email, path, body)).get
  private def script(app: Application, email: String, path: String): Future[Result] =
    route(app, FakeRequest(GET, path).withHeaders("X-Requested-With" -> "XMLHttpRequest", ACCEPT -> "application/json")
      .withSession(Secured.UserName -> email,
        Secured.CurrentContestId -> (if (email.startsWith("other-")) "20" else contestId.toString))).get

  /** A place menu's JSON as (name, count) pairs. */
  private def places(json: JsValue): Seq[(String, Option[Int])] =
    (json \ "regions").as[Seq[JsValue]].map(p => (p \ "name").as[String] -> (p \ "count").asOpt[Int])

  private def tiles(html: String): Set[Long] = "data-page(?:Id|-id)=\"(\\d+)\"".r.findAllMatchIn(html).map(_.group(1).toLong).toSet

  private def review(r: Long) = s"/admin/rounds/$r/review"
  private def jurorsForm(r: Long) = s"/admin/rounds/$r/jurors"
  private def distribute(r: Long) = s"/admin/rounds/$r/distribute"

  /** Distributes the round to `jurors`, each file to all of them, from its jurors form. */
  private def distributeTo(app: Application, r: Long, jurors: Seq[User]): Future[Result] =
    post(app, "admin@example.com", distribute(r), Seq("jurorsShown" -> "true", "distribution" -> "0") ++
      jurors.zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString }: _*)

  "the shared place scheme" should {

    "be countries for an international contest and regions for a national one, counted one way" in {
      testDbApp { _ =>
        val f = setUp()
        createContests(nationalId)
        DB.autoCommit(implicit s =>
          sql"UPDATE contest_jury SET monument_id_template = 'UkrainianMonument' WHERE id = $nationalId".update())
        val national = contestDao.findById(nationalId).get
        val countries = PlaceScheme.of(f.contest)
        (countries must beSome(beAnInstanceOf[PlaceScheme.Countries])) and
          (PlaceScheme.of(national) must beSome(beAnInstanceOf[PlaceScheme.Regions])) and
          (PlaceScheme.byRegions(national) must beTrue) and (PlaceScheme.byCountries(national) must beFalse) and
          (PlaceScheme.byCountries(f.contest) must beTrue) and
          (countries.collect { case c: PlaceScheme.Countries => c.counts(Seq(1L, 2L, 4L, 6L)) } must
            beSome(Map("Armenia" -> 2, "Bénin" -> 1))) and
          (countries.collect { case c: PlaceScheme.Countries => c.filesOf("Bénin") } must beSome(Seq(4L, 5L)))
      }
    }
  }

  "an international round" should {

    "count each country's files alike on the contest files page, the review page, the jurors form and the gallery" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val expected = Seq("Armenia" -> Some(3), "Bénin" -> Some(2))
        val contestFiles = places(contentAsJson(script(app, "admin@example.com", s"/contest/$contestId/files/countries")))
        val reviewPage = contentAsString(get(app, "admin@example.com", review(r)))
        val reviewPlaces = places(contentAsJson(script(app, "organizer@example.com", review(r) + "/regions")))
        val armenia = contentAsString(get(app, "admin@example.com", review(r) + "?country=Armenia"))
        val form = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        val distributed = status(distributeTo(app, r, f.jurors))
        val gallery = places(contentAsJson(script(app, "admin@example.com", s"/countries/round/$r/user/0/region/all")))
        val galleryArmenia = contentAsString(get(app, "admin@example.com", s"/gallery/round/$r/user/0/page/1?country=Armenia"))
        (contestFiles === expected) and (reviewPlaces === expected) and (gallery === expected) and
          (reviewPage must contain("id=\"countrymenu\"")) and (reviewPage must not(contain("id=\"regionmenu\""))) and
          (tiles(armenia) === Set(1L, 2L, 3L)) and (armenia must contain(s"/admin/rounds/$r/review/file/1?")) and
          (form must contain("id=\"round-jurors-places\"")) and
          (form must contain("""<li data-place="Armenia">Armenia<span class="wlx-count"> 3</span></li>""")) and
          (form must contain("""<li data-place="Bénin">Bénin<span class="wlx-count"> 2</span></li>""")) and
          (distributed === SEE_OTHER) and
          (tiles(galleryArmenia) === Set(1L, 2L, 3L)) and (galleryArmenia must contain("id=\"countrymenu\""))
      }
    }

    "count a status tab's files: none deleted, no country 404" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val deleted = places(contentAsJson(script(app, "admin@example.com", review(r) + "/regions?status=deleted")))
        (deleted must beEmpty) and
          (status(get(app, "admin@example.com", review(r) + "?country=Nowhere")) === NOT_FOUND)
      }
    }

    "give its country menu to its staff only: a juror gets none, nor its countries, and their filter is ignored" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        status(distributeTo(app, r, f.jurors))
        val j = f.jurors.head.getId
        val jurorGallery = contentAsString(get(app, "juror1@example.com", s"/gallery/round/$r/user/$j/page/1?country=Armenia"))
        val organizerGallery = contentAsString(get(app, "organizer@example.com", s"/gallery/round/$r/user/0/page/1"))
        (Round.findById(r).get.openToJurors must beTrue) and
          (jurorGallery must not(contain("countrymenu"))) and (tiles(jurorGallery) === (1L to 6L).toSet) and
          (status(script(app, "juror1@example.com", s"/countries/round/$r/user/$j/region/all")) === FORBIDDEN) and
          (status(script(app, "other-admin@example.com", s"/countries/round/$r/user/0/region/all")) === FORBIDDEN) and
          (organizerGallery must contain("id=\"countrymenu\"")) and
          (status(script(app, "organizer@example.com", s"/countries/round/$r/user/0/region/all")) === OK) and
          (status(script(app, "juror1@example.com", review(r) + "/regions")) === FORBIDDEN)
      }
    }
  }

  "the jurors form" should {

    "offer the round's admin the contest's jurors and the images that passed; refuse everyone else" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val admin = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        val reviewAdmin = contentAsString(get(app, "admin@example.com", review(r)))
        val reviewOrganizer = contentAsString(get(app, "organizer@example.com", review(r)))
        (admin must contain("id=\"round-jurors-form\"")) and (admin must contain("name=\"jurorsShown\"")) and
          (admin must contain("name=\"jury\" value=\"|\"")) and (admin must contain("id=\"jurors\"")) and
          (admin must contain(s"""<option value="${f.jurors.head.getId}" selected>""")) and
          (admin must contain("""id="round-jurors-images">Images that passed the filters<span class="wlx-count"> 6</span>""")) and
          (admin must contain("javascripts/round-form.js")) and
          (reviewAdmin must contain(s"""href="${jurorsForm(r)}"""")) and (reviewAdmin must not(contain("id=\"jurors\""))) and
          (reviewOrganizer must not(contain(jurorsForm(r)))) and
          (status(get(app, "organizer@example.com", jurorsForm(r))) === FORBIDDEN) and
          (status(get(app, "juror1@example.com", jurorsForm(r))) === FORBIDDEN) and
          (status(get(app, "other-admin@example.com", jurorsForm(r))) === FORBIDDEN) and
          (status(get(app, "admin@example.com", jurorsForm(99999L))) === NOT_FOUND)
      }
    }

    "send its refusals back to it, saying why, and a distribution to the review page" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val (j1, j2) = (f.jurors(0).getId.toString, f.jurors(1).getId.toString)
        def refused(body: (String, String)*) = {
          val res = post(app, "admin@example.com", distribute(r), ("jurorsShown" -> "true") +: body: _*)
          (redirectLocation(res), flash(res).get("error"))
        }
        val none = refused("distribution" -> "0")
        val tooMany = refused("jurors[0]" -> j1, "distribution" -> "2")
        val changed = refused("jurors[0]" -> j1, "jurors[1]" -> j2, "distribution" -> "0", "jury" -> s"$j1|")
        val stillInReview = Round.findById(r).get.inReview
        val roundsListPost = post(app, "admin@example.com", distribute(r))
        val roundsListBack = redirectLocation(roundsListPost)
        val done = post(app, "admin@example.com", distribute(r), "jurorsShown" -> "true", "jurors[0]" -> j1,
          "jurors[1]" -> j2, "distribution" -> "1", "jury" -> "|")
        val doneBack = redirectLocation(done)
        val after = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        (none._1 must beSome(jurorsForm(r))) and (none._2 must beSome(contain("has no jurors"))) and
          (tooMany._1 must beSome(jurorsForm(r))) and (tooMany._2 must beSome(contain("only 1 jurors"))) and
          (changed._1 must beSome(jurorsForm(r))) and (changed._2 must beSome(contain("jurors have changed"))) and
          (stillInReview must beTrue) and (roundsListBack must beSome(jurorsForm(r))) and
          (doneBack must beSome(review(r))) and (flash(done).get("success") must beSome) and
          (Round.findById(r).get.openToJurors must beTrue) and (VoteJdbc.byRound(r).size === 6) and
          (after must contain("id=\"round-jurors-not-in-review\"")) and (after must not(contain("id=\"round-jurors-form\"")))
      }
    }

    "send a refusal back to it also when the jurors are fixed (a partial distribution)" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        // a vote given out to a juror since stopped, so no active juror: the form shows the jurors as
        // they are, no picker; Distribute refuses
        val j = f.jurors.head.getId
        DB.localTx { implicit s =>
          RoundWrites.lock(Seq(r)); VoteJdbc.create(1L, 0, j, r)
          sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES ($j, $r, 'jury', FALSE)".update()
        }
        val form = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        val refused = post(app, "admin@example.com", distribute(r))
        (form must contain("id=\"round-jurors-fixed\"")) and (form must not(contain("name=\"jurorsShown\""))) and
          (redirectLocation(refused) must beSome(jurorsForm(r))) and (flash(refused).get("error") must beSome(contain("has no jurors")))
      }
    }

    "reload itself while the round is being created with no job to poll" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        DB.autoCommit { implicit s =>
          sql"UPDATE round SET status = 'draft' WHERE id = $r".update()
          sql"DELETE FROM job WHERE subject_type = 'round' AND subject_id = $r".update()
        }
        val page = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        // after about a minute of reloads it stops, saying the round isn't waiting
        val gaveUp = contentAsString(get(app, "admin@example.com", jurorsForm(r) + s"?waited=${RoundReviewController.MaxWaits}"))
        (page must contain("id=\"round-jurors-filling\"")) and (page must contain("http-equiv=\"refresh\"")) and
          (page must contain(s"${jurorsForm(r)}?waited=1")) and (page must not(contain("id=\"round-jurors-form\""))) and
          (gaveUp must not(contain("http-equiv=\"refresh\""))) and (gaveUp must contain("id=\"round-jurors-not-in-review\"")) and
          (gaveUp must contain("id=\"round-jurors-rounds\""))
      }
    }

    "wait for the fill of a round being created, showing its job" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        DB.autoCommit { implicit s =>
          sql"UPDATE round SET status = 'draft' WHERE id = $r".update()
          sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
            VALUES ('round_distribution', $contestId, 'round', $r, ${services.distribution.RoundDistributionHandler.dedupeKey(r)},
              'running', '{"mode": "fill"}', '{}')""".update()
        }
        val page = contentAsString(get(app, "admin@example.com", jurorsForm(r)))
        (page must contain("id=\"round-jurors-filling\"")) and (page must contain("class=\"job-status\"")) and
          (page must not(contain("id=\"round-jurors-form\"")))
      }
    }
  }

  "the new-round form" should {

    "have filters only, with \"Review images first\" and \"Choose jurors next\"" in {
      testDbApp { app =>
        val f = setUp()
        // each answered before the next
        def create(name: String, extra: (String, String)*) = {
          val res = post(app, "admin@example.com", "/admin/rounds/save",
            Seq("number" -> "2", "name" -> name, "contest" -> contestId.toString, "roles" -> "jury",
              "rates" -> "1", "minMpx" -> "No", "minSize" -> "No", "mediaType" -> "all") ++ extra: _*)
          status(res)
          res
        }
        val form = contentAsString(get(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId"))
        val first = create("First", "next" -> "review")
        val next = create("Next", "next" -> "jurors")
        // an older page's D4 box and jurors: the round waits in review all the same, without them
        val old = create("Old", "distributeAfter" -> "true", "jurors[0]" -> f.jurors.head.getId.toString, "distribution" -> "1")
        def named(n: String) = Round.findByContest(contestId).find(_.name.contains(n)).get
        (form must contain("id=\"round-form-review-first\"")) and (form must contain("Review images first")) and
          (form must contain("id=\"round-form-jurors-next\"")) and (form must contain("Choose jurors next")) and
          (form must not(contain("name=\"distributeAfter\""))) and (form must not(contain("id=\"jurors\""))) and
          (form must not(contain("id=\"distribution\""))) and
          (redirectLocation(first) must beSome(s"/admin/rounds?contestId=$contestId")) and
          (redirectLocation(next) must beSome(jurorsForm(named("Next").getId))) and
          (named("First").inReview must beTrue) and (named("Next").inReview must beTrue) and
          (status(old) === SEE_OTHER) and (named("Old").inReview must beTrue) and
          (RoundUser.byRoundId(named("Old").getId) must beEmpty) and (VoteJdbc.byRound(named("Old").getId) must beEmpty)
      }
    }
  }

  "a comment on a round in review" should {

    "be written by its admin and organizer in the review's large view, refused to anyone else" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        // each answered before the next request (status and location read at once)
        def comment(email: String, pageId: Long, text: String): (Int, Option[String]) = {
          val res = post(app, email, s"/comment/region/all/pageid/$pageId?round=$r&review=in&country=Armenia", "id" -> "0", "text" -> text)
          (status(res), redirectLocation(res))
        }
        val byAdmin = comment("admin@example.com", 1L, "Check the licence")
        val byOrganizer = comment("organizer@example.com", 2L, "Nice")
        val byJuror = comment("juror1@example.com", 1L, "juror's")
        val byOther = comment("other-admin@example.com", 1L, "other's")
        val notInRound = comment("admin@example.com", 99L, "nowhere")
        val large = contentAsString(get(app, "admin@example.com", s"/admin/rounds/$r/review/file/1"))
        val texts = CommentJdbc.findBySubjectAndContest(1L, contestId).map(c => (c.body, c.roundId))
        (byAdmin._2 must beSome(s"/admin/rounds/$r/review/file/1?country=Armenia#comments")) and
          (byOrganizer._1 === SEE_OTHER) and
          (byJuror._1 === FORBIDDEN) and (byOther._1 === FORBIDDEN) and (notInRound._1 === FORBIDDEN) and
          (texts === Seq("Check the licence" -> r)) and
          (CommentJdbc.findBySubjectAndContest(2L, contestId).map(_.body) === Seq("Nice")) and
          (large must contain("Check the licence")) and (large must contain("id=\"addComment\"")) and
          (large must contain(s"/comment/region/all/pageid/1?round=$r&amp;review=in"))
      }
    }

    "be seen by jurors once the round is distributed, not before" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val (juror1, juror2) = (f.jurors(0), f.jurors(1))
        // another round, open to juror2, with the same files
        val open = TestRoundServices.roundService()
          .submit(Round(None, 0, Some("Open"), contestId, active = true), Seq(juror2.getId), None, review = false).round
        status(post(app, "admin@example.com", s"/comment/region/all/pageid/1?round=$r&review=in", "id" -> "0", "text" -> "Review note"))
        val stored = CommentJdbc.findBySubjectAndContest(1L, contestId).map(_.body)
        val beforeOther = contentAsString(get(app, "juror2@example.com", s"/large/round/${open.getId}/user/${juror2.getId}/pageid/1"))
        val beforeOwn = status(get(app, "juror1@example.com", s"/large/round/$r/user/${juror1.getId}/pageid/1"))
        val distributed = status(distributeTo(app, r, Seq(juror1)))
        val afterOther = contentAsString(get(app, "juror2@example.com", s"/large/round/${open.getId}/user/${juror2.getId}/pageid/1"))
        val afterOwn = contentAsString(get(app, "juror1@example.com", s"/large/round/$r/user/${juror1.getId}/pageid/1"))
        (stored === Seq("Review note")) and (Round.findById(open.getId).get.openToJurors must beTrue) and
          (beforeOther must contain("data-nav")) and (beforeOther must not(contain("Review note"))) and
          (beforeOwn must beOneOf(FORBIDDEN, SEE_OTHER)) and (distributed === SEE_OTHER) and
          (afterOther must contain("Review note")) and (afterOwn must contain("Review note"))
      }
    }

    "reach no juror of another round before it is distributed: no comments report, badge or filter" in {
      testDbApp { app =>
        val f = setUp()
        val r = f.round.getId
        val juror2 = f.jurors(1)
        val j2 = juror2.getId
        val open = TestRoundServices.roundService()
          .submit(Round(None, 0, Some("Open"), contestId, active = true), Seq(j2), None, review = false).round
        val o = open.getId
        status(post(app, "admin@example.com", s"/comment/region/all/pageid/1?round=$r&review=in", "id" -> "0", "text" -> "Review note"))
        // what juror2 sees in their open round: the comments report, the gallery's badges, "only with comments"
        def seen() = {
          val report = contentAsString(get(app, "juror2@example.com", s"/comments/round/$o/user/$j2/page/1"))
          val gallery = contentAsString(get(app, "juror2@example.com", s"/gallery/round/$o/user/$j2/page/1"))
          val commented = contentAsString(get(app, "juror2@example.com", s"/gallery/round/$o/user/$j2/page/1?comments=1"))
          (report.contains("Review note"), "data-comments=\"(\\d+)\"".r.findFirstIn(gallery).isDefined, tiles(commented))
        }
        val before = seen()
        val organizerReport = contentAsString(get(app, "organizer@example.com", s"/comments/round/$o/user/0/page/1?scope=contest"))
        val distributed = status(distributeTo(app, r, Seq(f.jurors.head)))
        val after = seen()
        (Round.findById(o).get.openToJurors must beTrue) and
          (before === ((false, false, Set.empty[Long]))) and
          (organizerReport must contain("Review note")) and
          (distributed === SEE_OTHER) and
          (after === ((true, true, Set(1L))))
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val f = setUp()
        val r = f.round.getId
        val sessionToken = token(app)
        def send(body: (String, String)*) =
          route(app, FakeRequest(POST, s"/comment/region/all/pageid/1?round=$r&review=in")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> contestId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        (status(send("id" -> "0", "text" -> "a")) === FORBIDDEN) and
          (status(send("id" -> "0", "text" -> "b", "csrfToken" -> "forged")) === FORBIDDEN) and
          (CommentJdbc.findBySubjectAndContest(1L, contestId) must beEmpty) and
          (status(send("id" -> "0", "text" -> "c", "csrfToken" -> sessionToken)) === SEE_OTHER) and
          (CommentJdbc.findBySubjectAndContest(1L, contestId).map(_.body) === Seq("c"))
      }
    }
  }
}
