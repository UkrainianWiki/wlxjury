package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, CriteriaRate, Criterion, Image}
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A criteria round's criteria (C2, V83): chosen from the global catalogue in the round form,
  * written with a new round, prefilled from the contest's latest criteria round; changed
  * freely before the round's first vote, then only replaced in their places (U10). The
  * catalogue: listed and added to by root and the admins of the session's contest. Refusals:
  * a juror, an organizer, another contest's admin (403, nothing written), no CSRF token.
  */
class RoundCriteriaSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def member(email: String, role: String, contest: Long): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contest)))

  /** Contest A with an admin, an organizer, two jurors and four files in its category, a
    * juror who is also contest B's admin; contest B with its own admin; a root.
    */
  private def fixture(): (ContestJury, ContestJury, Seq[User]) = {
    val a = contestDao.create(None, "WLE", 2024, "Ukraine")
    val b = contestDao.create(None, "WLM", 2024, "Poland")
    contestDao.setImagesSource(a.getId, Some("Category:WLE images"))
    member("admin@example.com", "admin", a.getId)
    member("organizer@example.com", "organizer", a.getId)
    val jurors = (1 to 2).map(i => member(s"juror$i@example.com", "jury", a.getId))
    val both = member("both@example.com", "jury", a.getId)
    UserContestJdbc.createMembership(both.getId, b.getId, "admin")
    member("other-admin@example.com", "admin", b.getId)
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    val images = (1L to 4L).map(image)
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(a.getId).get.categoryId.get, images)
    (a, b, jurors)
  }

  private def session[A](r: FakeRequest[A], email: String, contest: Long): FakeRequest[A] =
    r.withSession(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString)

  private def page(app: Application, email: String, contest: Long, url: String): Future[Result] =
    route(app, session(FakeRequest(GET, url), email, contest).withHeaders(ACCEPT -> "text/html").withCSRFToken).get

  /** The round form as the browser posts it: a new round (no `id`) of the jurors, or an edit. */
  private def roundForm(contest: Long, jurors: Seq[User], criteria: Seq[Long], rates: Int = 10,
      id: Option[Long] = None, name: String = "Criteria", shown: Boolean = true): Seq[(String, String)] =
    Seq("number" -> "0", "name" -> name, "contest" -> contest.toString, "roles" -> "jury",
      "distribution" -> "0", "rates" -> rates.toString, "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
      id.map("id" -> _.toString) ++
      jurors.zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString } ++
      (if (shown) Seq("criteriaShown" -> "true") else Nil) ++
      // the ten selects, the empty ones posted too
      (0 until math.max(Criterion.MaxPerRound, criteria.size)).map(i => s"criteria[$i]" -> criteria.lift(i).fold("")(_.toString))

  private def save(app: Application, email: String, contest: Long, body: Seq[(String, String)]): Future[Result] =
    route(app, session(FakeRequest(POST, "/admin/rounds/save"), email, contest)
      .withHeaders(ACCEPT -> "text/html").withFormUrlEncodedBody(body: _*).withCSRFToken).get

  private def criteriaOf(round: Long): Seq[Long] = RoundCriterion.idsOf(round)
  private def hasCriteria(round: Long): Option[Boolean] = roundDao.findById(round).map(_.hasCriteria)
  private def ratesCount: Long = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM criteria_rate".map(_.long(1)).single().get)

  /** A criteria round of the jurors over the contest's files, as the form creates it. */
  private def created(app: Application, contest: ContestJury, jurors: Seq[User], criteria: Seq[Long]): Round = {
    status(save(app, "admin@example.com", contest.getId, roundForm(contest.getId, jurors, criteria))) === SEE_OTHER
    roundDao.findByContest(contest.getId).last
  }

  /** The selected value of each criteria select, by position. */
  private def selected(html: String): Seq[String] = {
    val doc = Jsoup.parse(html)
    (0 until doc.select("select[name^=criteria]").size).map { i =>
      doc.select(s"select[name='criteria[$i]'] option[selected]").asScala.headOption.fold("")(_.attr("value"))
    }
  }

  "the round form" should {

    "create a criteria round with its criteria in their order, distributed without rates" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Seq(8L, 6L, 7L))
        criteriaOf(round.getId) === Seq(8L, 6L, 7L)
        hasCriteria(round.getId) must beSome(true)
        VoteJdbc.byRound(round.getId).size === 8
        ratesCount === 0L
      }
    }

    "create a round without criteria with the selects left empty" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Nil)
        criteriaOf(round.getId) must beEmpty
        hasCriteria(round.getId) must beSome(false)
      }
    }

    "refuse criteria in a binary round, one criterion, eleven, one twice, or one not in the catalogue, creating nothing" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val refusals = Seq(
          roundForm(a.getId, jurors, Seq(6L, 7L), rates = 1) -> "Criteria are for rated rounds only.",
          roundForm(a.getId, jurors, Seq(6L)) -> "Choose 2 to 10 criteria, or none.",
          roundForm(a.getId, jurors, Seq(6L, 6L)) -> "Choose each criterion once.",
          roundForm(a.getId, jurors, Seq(6L, 12345L)) -> "Choose criteria from the catalogue.",
          (roundForm(a.getId, jurors, Nil).filterNot(_._1.startsWith("criteria[")) ++
            (0 to 10).map(i => s"criteria[$i]" -> (if (i < 9) (i + 1).toString else "x"))) ->
            "Choose 2 to 10 criteria, or none."
        )
        forall(refusals) { case (body, message) =>
          val result = save(app, "admin@example.com", a.getId, body)
          status(result) === BAD_REQUEST
          contentAsString(result) must contain(message)
        }
        roundDao.findByContest(a.getId) must beEmpty
      }
    }

    "prefill a new round's criteria and rating from the contest's latest round, if it has criteria (U3)" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val first = created(app, a, jurors, Seq(9L, 6L))
        val custom = CriterionJdbc.add("Composition", None).toOption.get
        val html = contentAsString(page(app, "admin@example.com", a.getId, s"/admin/rounds/edit?contestId=${a.getId}"))
        selected(html).take(3) === Seq("9", "6", "")
        Jsoup.parse(html).select("select#rates option[selected]").attr("value") === "10"
        // the catalogue's criteria offered, by name, the one added by its fallback text
        Jsoup.parse(html).select("select[name='criteria[0]'] option").asScala.map(_.text).toSeq must
          contain(allOf("Technical quality", "Extra points", "Composition"))

        // the latest round without criteria: none prefilled
        status(save(app, "admin@example.com", a.getId, roundForm(a.getId, jurors, Nil, rates = 1))) === SEE_OTHER
        val next = contentAsString(page(app, "admin@example.com", a.getId, s"/admin/rounds/edit?contestId=${a.getId}"))
        selected(next).forall(_.isEmpty) must beTrue
        // an existing round's own
        selected(contentAsString(page(app, "admin@example.com", a.getId,
          s"/admin/rounds/edit?contestId=${a.getId}&id=${first.getId}"))).take(2) === Seq("9", "6")
        custom.messageKey === Criterion.customKey(custom.id)
      }
    }

    "change an existing round's criteria freely before its first vote, also to none" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Seq(6L, 7L))
        def edit(criteria: Seq[Long]) =
          save(app, "admin@example.com", a.getId, roundForm(a.getId, jurors, criteria, id = round.id))
        status(edit(Seq(7L, 9L, 6L))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(7L, 9L, 6L)
        status(edit(Nil)) === SEE_OTHER
        criteriaOf(round.getId) must beEmpty
        hasCriteria(round.getId) must beSome(false)
        status(edit(Seq(8L, 9L))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(8L, 9L)
        hasCriteria(round.getId) must beSome(true)
      }
    }

    "leave an existing round's criteria as they are when the form has none of its selects" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Seq(6L, 7L))
        status(save(app, "admin@example.com", a.getId,
          roundForm(a.getId, jurors, Nil, id = round.id, name = "Renamed", shown = false))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(6L, 7L)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed")
      }
    }

    "refuse criteria for an existing binary round, whatever rating the form posts" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Nil)
        sql"UPDATE round SET rates = 1 WHERE id = ${round.getId}".update()(AutoSession)
        val result = save(app, "admin@example.com", a.getId, roundForm(a.getId, jurors, Seq(6L, 7L), id = round.id))
        status(result) === BAD_REQUEST
        contentAsString(result) must contain("Criteria are for rated rounds only.")
        criteriaOf(round.getId) must beEmpty
      }
    }

    "after the first vote, take a criterion replaced in its place with its rates, and refuse adding, removing or moving one" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Seq(6L, 7L))
        val votes = VoteJdbc.byRound(round.getId).filter(_.juryId == jurors.head.getId).sortBy(_.pageId)
        CriteriaRate.updateRate(votes.head.getId, 6L, 3) must beTrue
        CriteriaRate.updateRate(votes.head.getId, 7L, 4) must beTrue
        // a vote moved aside keeps its rates: they follow the criterion too
        CriteriaRate.updateRate(votes(1).getId, 7L, 5) must beTrue
        DB.localTx(implicit s => services.imports.DeletedFiles.markDeleted(Seq(votes(1).pageId)))
        def edit(criteria: Seq[Long], name: String = "Criteria") =
          save(app, "admin@example.com", a.getId, roundForm(a.getId, jurors, criteria, id = round.id, name = name))
        def rates(voteId: Long) = CriteriaRate.getRates(voteId).map(r => r.criterionId -> r.rate).toMap

        forall(Seq(Seq(6L, 7L, 8L), Seq(6L), Seq(7L, 6L), Seq(6L, 6L), Nil, Seq(7L, 8L))) { criteria =>
          val result = edit(criteria, name = "Not saved")
          status(result) === BAD_REQUEST
          if (criteria.size == 2 && criteria.distinct.size == 2)
            contentAsString(result) must contain("its criteria can only be replaced in their places")
          criteriaOf(round.getId) === Seq(6L, 7L)
        }
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Criteria")
        rates(votes.head.getId) === Map(6L -> 3, 7L -> 4)

        // 7 replaced by 9 in its place: a renaming, the rates kept under 9
        status(edit(Seq(6L, 9L))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(6L, 9L)
        rates(votes.head.getId) === Map(6L -> 3, 9L -> 4)
        rates(votes(1).getId) === Map(9L -> 5)
        VoteJdbc.findBy(votes.head.pageId, jurors.head.getId, round.getId).map(_.rate) must beSome(7)
        // and voted under the new criterion
        CriteriaRate.updateRate(votes.head.getId, 9L, 2) must beTrue
        CriteriaRate.updateRate(votes.head.getId, 7L, 2) must beFalse
        rates(votes.head.getId) === Map(6L -> 3, 9L -> 2)
      }
    }

    "be refused to a juror, an organizer, another contest's admin and a juror who is another contest's admin, nothing written" in {
      testDbApp { app =>
        val (a, b, jurors) = fixture()
        val round = created(app, a, jurors, Seq(6L, 7L))
        val users = Seq("juror1@example.com" -> a, "organizer@example.com" -> a, "other-admin@example.com" -> b,
          "both@example.com" -> a)
        forall(users) { case (email, current) =>
          status(save(app, email, current.getId, roundForm(a.getId, jurors, Seq(7L, 8L, 9L), id = round.id))) === FORBIDDEN
          status(save(app, email, current.getId, roundForm(a.getId, jurors, Seq(7L, 8L)))) === FORBIDDEN
        }
        criteriaOf(round.getId) === Seq(6L, 7L)
        roundDao.findByContest(a.getId).size === 1
        // root may
        status(save(app, "root@example.com", a.getId, roundForm(a.getId, jurors, Seq(8L, 9L), id = round.id))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(8L, 9L)
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (a, _, jurors) = fixture()
        val round = roundDao.create(Round(None, 1, Some("Criteria"), a.getId, rates = Round.ratesById(10),
          criterionIds = Seq(6L, 7L)))
        val sessionToken = token(app)
        def post(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/rounds/save")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> a.getId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        val body = roundForm(a.getId, jurors, Seq(8L, 9L), id = round.id)
        status(post(body: _*)) === FORBIDDEN
        status(post(body :+ ("csrfToken" -> "forged"): _*)) === FORBIDDEN
        criteriaOf(round.getId) === Seq(6L, 7L)
        status(post(body :+ ("csrfToken" -> sessionToken): _*)) === SEE_OTHER
        criteriaOf(round.getId) === Seq(8L, 9L)
      }
    }
  }

  "a legacy criteria round (V83) the form can't create" should {

    /** The round's edit page shows its criteria as stored; saved so, with a new name, they
      * stay and the name is saved.
      */
    def keptOnSave(app: Application, a: ContestJury, jurors: Seq[User], round: Round) = {
      val stored = criteriaOf(round.getId)
      val html = contentAsString(page(app, "admin@example.com", a.getId,
        s"/admin/rounds/edit?contestId=${a.getId}&id=${round.getId}"))
      val shown = selected(html).filter(_.nonEmpty).map(_.toLong)
      val result = save(app, "admin@example.com", a.getId,
        roundForm(a.getId, jurors, shown, rates = round.rates.id, id = round.id, name = "Renamed"))
      (shown === stored) and (status(result) === SEE_OTHER) and (criteriaOf(round.getId) === stored) and
        (roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed"))
    }

    "keep one criterion, saving the rest of its form" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        keptOnSave(app, a, jurors, roundDao.create(Round(None, 1, Some("Legacy"), a.getId,
          rates = Round.ratesById(10), criterionIds = Seq(7L))))
      }
    }

    "keep eleven criteria, all shown" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val custom = Seq("Composition", "Lighting").map(n => CriterionJdbc.add(n, None).toOption.get.id)
        val round = roundDao.create(Round(None, 1, Some("Legacy"), a.getId, rates = Round.ratesById(10),
          criterionIds = (1L to 9L) ++ custom))
        keptOnSave(app, a, jurors, round)
      }
    }

    "keep the criteria of a yes/no round with has_criteria = 1" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        keptOnSave(app, a, jurors, roundDao.create(Round(None, 1, Some("Legacy"), a.getId,
          rates = Round.binaryRound, criterionIds = Seq(6L, 7L))))
      }
    }
  }

  "a partly rated criteria vote (U5)" should {

    "stay in the juror's Unrated list and out of the round's active jurors until every criterion is rated" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = created(app, a, jurors, Seq(6L, 7L))
        val juror = jurors.head
        val vote = VoteJdbc.byRound(round.getId).filter(_.juryId == juror.getId).minBy(_.pageId)
        def listed(filter: String): Boolean = {
          val html = contentAsString(page(app, juror.email, a.getId,
            s"/gallery/round/${round.getId}/user/${juror.getId}/page/1?filter=$filter"))
          // the gallery's tiles (GalleryOrderSpec's way of reading them)
          """(?:data-pageId|class="gallerybox" id)="(\d+)"""".r.findAllMatchIn(html).map(_.group(1).toLong)
            .contains(vote.pageId)
        }
        listed("unrated") must beTrue
        CriteriaRate.updateRate(vote.getId, 6L, 3) must beTrue
        (listed("unrated"), listed("rated"), VoteJdbc.activeJurors(round.getId)) === ((true, false, 0))
        CriteriaRate.updateRate(vote.getId, 7L, 4) must beTrue
        (listed("unrated"), listed("rated"), VoteJdbc.activeJurors(round.getId)) === ((false, true, 1))
      }
    }
  }

  "the criteria catalogue" should {

    def add(app: Application, email: String, contest: Long, name: String): Future[Result] =
      route(app, session(FakeRequest(POST, "/admin/criteria"), email, contest)
        .withHeaders(ACCEPT -> "text/html").withFormUrlEncodedBody("name" -> name).withCSRFToken).get

    def catalogue: Seq[Criterion] = CriterionJdbc.all()

    "list the criteria to an admin and root, the shipped ones by name in the viewer's language" in {
      testDbApp { app =>
        val (a, _, _) = fixture()
        forall(Seq("admin@example.com", "root@example.com")) { email =>
          val result = page(app, email, a.getId, s"/admin/criteria?contestId=${a.getId}")
          status(result) === OK
          val rows = Jsoup.parse(contentAsString(result)).select("#criteria-catalogue tbody tr").asScala
          rows.map(_.attr("data-criterion-id")).toSeq === (1 to 9).map(_.toString)
          rows.map(_.child(0).text).toSeq must contain("Technical quality")
        }
      }
    }

    "add a criterion for an admin, under a key of its own, its name trimmed" in {
      testDbApp { app =>
        val (a, _, _) = fixture()
        val result = add(app, "admin@example.com", a.getId, "  Composition  ")
        status(result) === SEE_OTHER
        redirectLocation(result) must beSome("/admin/criteria")
        val added = catalogue.last
        (added.fallbackName, added.messageKey) === ("Composition" -> Criterion.customKey(added.id))
        DB.readOnly(implicit s => sql"SELECT created_by FROM criterion WHERE id = ${added.id}".map(_.longOpt(1)).single())
          .flatten === User.findByEmail("admin@example.com").headOption.flatMap(_.id)
        // root too
        status(add(app, "root@example.com", a.getId, "Lighting")) === SEE_OTHER
        catalogue.map(_.fallbackName) must contain("Lighting")
      }
    }

    "refuse a name a criterion has (its fallback text or its name shown, any case), an empty one and a long one" in {
      testDbApp { app =>
        val (a, _, _) = fixture()
        val before = catalogue
        forall(Seq("технічна ЯКІСТЬ", "technical quality", "", "   ", "x" * 256)) { name =>
          status(add(app, "admin@example.com", a.getId, name)) === BAD_REQUEST
        }
        catalogue === before
        contentAsString(add(app, "admin@example.com", a.getId, "Technical Quality")) must
          contain("A criterion of this name exists.")
      }
    }

    "refuse a juror, an organizer and a juror who is another contest's admin: 403, nothing added; signed out to sign in" in {
      testDbApp { app =>
        val (a, b, _) = fixture()
        val before = catalogue
        forall(Seq("juror1@example.com", "organizer@example.com", "both@example.com")) { email =>
          (status(add(app, email, a.getId, "Composition")) === FORBIDDEN) and
            (status(page(app, email, a.getId, "/admin/criteria")) === FORBIDDEN)
        }
        // a contest named: the user's roles there
        status(page(app, "admin@example.com", a.getId, s"/admin/criteria?contestId=${b.getId}")) === FORBIDDEN
        catalogue === before
        val signedOut = route(app, FakeRequest(POST, "/admin/criteria").withFormUrlEncodedBody("name" -> "X")
          .withHeaders(ACCEPT -> "text/html").withCSRFToken).get
        status(signedOut) === SEE_OTHER
        catalogue === before
      }
    }

    "need the CSRF token" in {
      withCsrfApp { app =>
        val (a, _, _) = fixture()
        val sessionToken = token(app)
        def post(body: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/criteria")
            .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
            .withSession(Secured.UserName -> "admin@example.com", Secured.CurrentContestId -> a.getId.toString,
              "csrfToken" -> sessionToken)
            .withFormUrlEncodedBody(body: _*)).get
        status(post("name" -> "Composition")) === FORBIDDEN
        status(post("name" -> "Composition", "csrfToken" -> "forged")) === FORBIDDEN
        catalogue.map(_.fallbackName) must not(contain("Composition"))
        status(post("name" -> "Composition", "csrfToken" -> sessionToken)) === SEE_OTHER
        catalogue.map(_.fallbackName) must contain("Composition")
      }
    }
  }
}
