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
  * freely while the round is stopped and without votes, read only once it is started. The
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
    TestDb.setSource(a.getId, Some("Category:WLE images"))
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
      "distributeAfter" -> "true", "distribution" -> "0", "rates" -> rates.toString, "minMpx" -> "", "minSize" -> "", "mediaType" -> "all") ++
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

  /** A criteria round of the jurors over the contest's files, as the form creates it (held in
    * review) and its jurors form distributes it.
    */
  private def created(app: Application, contest: ContestJury, jurors: Seq[User], criteria: Seq[Long]): Round = {
    status(save(app, "admin@example.com", contest.getId, roundForm(contest.getId, jurors, criteria))) === SEE_OTHER
    val round = roundDao.findByContest(contest.getId).last
    status(route(app, session(FakeRequest(POST, s"/admin/rounds/${round.getId}/distribute"), "admin@example.com",
      contest.getId).withHeaders(ACCEPT -> "text/html").withFormUrlEncodedBody(
        Seq("jurorsShown" -> "true", "distribution" -> "0") ++
          jurors.zipWithIndex.map { case (u, i) => s"jurors[$i]" -> u.getId.toString }: _*).withCSRFToken).get) === SEE_OTHER
    roundDao.findById(round.getId).get
  }

  /** The round stopped (or started) as setRound does: a stopped round without a vote has its
    * criteria editable, a running one not.
    */
  private def setActive(round: Round, active: Boolean): Round = {
    Round.setActive(round.getId, active)
    roundDao.findById(round.getId).get
  }

  /** The refusal of a started round's criteria change, and its form's help line. */
  private val Locked = "The round's criteria can't be changed while it is running or once it has votes."

  /** The page's text (entities decoded: the apostrophes are escaped in its HTML). */
  private def textOf(result: Future[Result]): String = Jsoup.parse(contentAsString(result)).text

  /** The selected value of each criteria select, by position. */
  private def selected(html: String): Seq[String] = {
    val doc = Jsoup.parse(html)
    (0 until doc.select("select[name^=criteria]").size).map { i =>
      doc.select(s"select[name='criteria[$i]'] option[selected]").asScala.headOption.fold("")(_.attr("value"))
    }
  }

  "the round form [A-01]" should {

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

    "create a round of one criterion and one of ten, the bounds of 1 to 10 (0: none)" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val one = created(app, a, jurors, Seq(6L))
        (criteriaOf(one.getId), hasCriteria(one.getId)) === ((Seq(6L), Some(true)))
        val custom = CriterionJdbc.add("Composition", None).toOption.get.id
        val ten = (1L to 9L) :+ custom
        val tenRound = created(app, a, jurors, ten)
        criteriaOf(tenRound.getId) === ten
      }
    }

    "refuse criteria in a binary round, eleven, one twice, or one not in the catalogue, creating nothing" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val custom = Seq("Composition", "Lighting").map(n => CriterionJdbc.add(n, None).toOption.get.id)
        val refusals = Seq(
          roundForm(a.getId, jurors, Seq(6L, 7L), rates = 1) -> "Criteria are for rated rounds only.",
          roundForm(a.getId, jurors, Seq(6L), rates = 1) -> "Criteria are for rated rounds only.",
          roundForm(a.getId, jurors, (1L to 9L) ++ custom) -> "Choose 1 to 10 criteria, or none.",
          roundForm(a.getId, jurors, Seq(6L, 6L)) -> "Choose each criterion once.",
          roundForm(a.getId, jurors, Seq(6L, 12345L)) -> "Choose criteria from the catalogue.",
          (roundForm(a.getId, jurors, Nil).filterNot(_._1.startsWith("criteria[")) ++
            (0 to 10).map(i => s"criteria[$i]" -> (if (i < 9) (i + 1).toString else "x"))) ->
            "Choose 1 to 10 criteria, or none."
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
        // an existing round's own (stopped: running, they would be read only)
        setActive(first, active = false)
        selected(contentAsString(page(app, "admin@example.com", a.getId,
          s"/admin/rounds/edit?contestId=${a.getId}&id=${first.getId}"))).take(2) === Seq("9", "6")
        custom.messageKey === Criterion.customKey(custom.id)
      }
    }

    "open round form reference links separately and keep Cancel in the form tab" in {
      testDbApp { app =>
        val (contest, _, jurors) = fixture()
        val saved = created(app, contest, jurors, Seq(9L, 6L))
        val newUrl = s"/admin/rounds/edit?contestId=${contest.getId}"
        val newForm = Jsoup.parse(contentAsString(page(app, "admin@example.com", contest.getId, newUrl)))
        val editForm = Jsoup.parse(contentAsString(page(app, "admin@example.com", contest.getId,
          s"$newUrl&id=${saved.getId}")))
        def pageLinks(doc: org.jsoup.nodes.Document) =
          doc.select("#round-form a[href]").asScala.filterNot(_.attr("href").startsWith("#")).toSeq
        val (cancel, links) = (pageLinks(newForm) ++ pageLinks(editForm))
          .partition(_.attr("href").takeWhile(_ != '?') == "/admin/rounds")

        (links.exists(_.attr("href").startsWith("/admin/criteria")) must beTrue) and
          (links.exists(_.id() == "round-review-link") must beTrue) and
          (links.filterNot(a => a.attr("target") == "_blank" && a.attr("rel").split(" ").contains("noopener"))
            .map(_.attr("href")) must beEmpty) and
          (cancel.size === 2) and (cancel.exists(_.hasAttr("target")) must beFalse) and
          (newForm.select("#round-form a[data-bs-toggle=collapse][target]").size === 0)
      }
    }

    "change a stopped round's criteria freely before its first vote, also to none" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = false)
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

    "refuse any change of a round's criteria once it has votes, a replacement in its place too, nothing saved" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = false)
        val votes = VoteJdbc.byRound(round.getId).filter(_.juryId == jurors.head.getId).sortBy(_.pageId)
        CriteriaRate.updateRate(votes.head.getId, 6L, 3) must beTrue
        CriteriaRate.updateRate(votes.head.getId, 7L, 4) must beTrue
        def edit(criteria: Seq[Long], name: String) =
          save(app, "admin@example.com", a.getId, roundForm(a.getId, jurors, criteria, id = round.id, name = name))
        def rates(voteId: Long) = CriteriaRate.getRates(voteId).map(r => r.criterionId -> r.rate).toMap

        // added, moved, replaced in its place, both replaced, none (no longer a criteria round)
        forall(Seq(Seq(6L, 7L, 8L), Seq(7L, 6L), Seq(6L, 9L), Seq(8L, 9L), Nil)) { criteria =>
          val result = edit(criteria, name = "Not saved")
          status(result) === BAD_REQUEST
          textOf(result) must contain(Locked)
          criteriaOf(round.getId) === Seq(6L, 7L)
        }
        // one criterion: refused by the form's own check first, not saved either
        status(edit(Seq(6L), name = "Not saved")) === BAD_REQUEST
        criteriaOf(round.getId) === Seq(6L, 7L)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Criteria")
        rates(votes.head.getId) === Map(6L -> 3, 7L -> 4)
        // the same criteria posted: the rest of the form saved
        status(edit(Seq(6L, 7L), name = "Renamed")) === SEE_OTHER
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed")
        criteriaOf(round.getId) === Seq(6L, 7L)
      }
    }

    "be refused to a juror, an organizer, another contest's admin and a juror who is another contest's admin, nothing written" in {
      testDbApp { app =>
        val (a, b, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = false)
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

    "keep one criterion (as the form now creates too), saving the rest of its form" in {
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

    "keep the criteria of a yes/no round with has_criteria = 1, its page without criteria" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = roundDao.create(Round(None, 1, Some("Legacy"), a.getId,
          rates = Round.binaryRound, criterionIds = Seq(6L, 7L)))
        val doc = Jsoup.parse(contentAsString(page(app, "admin@example.com", a.getId,
          s"/admin/rounds/edit?contestId=${a.getId}&id=${round.getId}")))
        // saved as its page posts it: no selects, no criteriaShown
        val result = save(app, "admin@example.com", a.getId,
          roundForm(a.getId, jurors, Nil, rates = 1, id = round.id, name = "Renamed", shown = false)
            .filterNot(_._1.startsWith("criteria")))
        (doc.select("select[name^=criteria], input[name=criteriaShown]").size === 0) and
          (status(result) === SEE_OTHER) and (criteriaOf(round.getId) === Seq(6L, 7L)) and
          (roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed"))
      }
    }

    "refuse removing the criteria of a yes/no round with has_criteria = 1 by a posted criteriaShown" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = roundDao.create(Round(None, 1, Some("Legacy"), a.getId,
          rates = Round.binaryRound, criterionIds = Seq(6L, 7L)))
        // a hand-made post: the marker with every select empty, as a save, and as a re-apply
        val result = save(app, "admin@example.com", a.getId,
          roundForm(a.getId, jurors, Nil, rates = 1, id = round.id, name = "Renamed"))
        val reapply = save(app, "admin@example.com", a.getId,
          roundForm(a.getId, jurors, Nil, rates = 1, id = round.id, name = "Renamed") :+ ("reapply" -> "true"))
        (status(result) === BAD_REQUEST) and
          (contentAsString(result) must contain("Criteria are for rated rounds only.")) and
          (status(reapply) === BAD_REQUEST) and
          (contentAsString(reapply) must contain("Criteria are for rated rounds only.")) and
          (criteriaOf(round.getId) === Seq(6L, 7L)) and (hasCriteria(round.getId) must beSome(true)) and
          (roundDao.findById(round.getId).flatMap(_.name) must beSome("Legacy"))
      }
    }
  }

  "a rated round's edit form" should {

    def editPage(app: Application, a: ContestJury, round: Round) =
      Jsoup.parse(contentAsString(page(app, "admin@example.com", a.getId,
        s"/admin/rounds/edit?contestId=${a.getId}&id=${round.getId}")))

    /** Each criteria select's offered values, by position. */
    def offered(doc: org.jsoup.nodes.Document): Seq[Seq[String]] =
      doc.select("select[name^=criteria]").asScala.toSeq.map(_.select("option").asScala.toSeq.map(_.attr("value")))

    /** The read-only criteria of a started round (its help line there): the listed names. */
    def locked(doc: org.jsoup.nodes.Document): Option[Seq[String]] =
      Option.when(doc.select("#round-criteria-locked").size == 1)(
        doc.select("#round-criteria-list li").asScala.toSeq.map(_.text))

    def noSelects(doc: org.jsoup.nodes.Document) =
      doc.select("select[name^=criteria], input[name=criteriaShown]").size === 0

    /** A first vote: a juror's file rated (a round without criteria) or a criterion rated. */
    def voteIn(round: Round, juror: User, criterion: Option[Long] = None): Unit = {
      val vote = VoteJdbc.byRound(round.getId).filter(_.juryId == juror.getId).minBy(_.pageId)
      criterion.fold(VoteJdbc.rate(vote.pageId, juror.getId, round.getId, 5): Any)(c =>
        CriteriaRate.updateRate(vote.getId, c, 3) must beTrue)
    }

    /** The edit form posted: with its criteria selects, or, `shown = false`, as a locked page
      * posts it (no selects, no criteriaShown).
      */
    def posted(a: ContestJury, jurors: Seq[User], round: Round, criteria: Seq[Long], name: String,
        shown: Boolean = true, extra: Seq[(String, String)] = Nil): Seq[(String, String)] =
      roundForm(a.getId, jurors, criteria, id = round.id, name = name, shown = shown)
        .filterNot(f => !shown && f._1.startsWith("criteria")) ++ extra

    "lock a stopped round without criteria once it has votes: no selects, a line that none can be added" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Nil), active = false)
        voteIn(round, jurors.head)
        RoundCriterion.hasVote(round.getId, criteriaRates = false) must beTrue
        val doc = editPage(app, a, round)
        noSelects(doc)
        locked(doc) must beSome(Seq.empty[String])
        doc.select("#round-criteria-locked").text === Locked
        doc.select("#round-criteria-none").text === "It has no criteria, and none can be added."

        // saved as its page posts it, with the worst mark (no criteriaShown): saved, no criteria
        doc.select("input[name=worstMarkShown]").size === 1
        status(save(app, "admin@example.com", a.getId, posted(a, jurors, round, Nil, "Renamed", shown = false,
          extra = Seq("worstMarkShown" -> "true", "worstMark" -> "true")))) === SEE_OTHER
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed")
        roundDao.findById(round.getId).map(_.worstMark) must beSome(true)
        criteriaOf(round.getId) must beEmpty

        // criteria posted anyway (an older page): refused, nothing saved
        val result = save(app, "admin@example.com", a.getId, posted(a, jurors, round, Seq(6L, 7L), "Not saved"))
        status(result) === BAD_REQUEST
        textOf(result) must contain(Locked)
        criteriaOf(round.getId) must beEmpty
        hasCriteria(round.getId) must beSome(false)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed")
      }
    }

    "lock a stopped criteria round once it has votes: its criteria listed in order, read only" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(7L, 6L)), active = false)
        // the names as the editable form offers them
        val before = editPage(app, a, round)
        val names = before.select("select[name='criteria[0]'] option").asScala.map(o => o.attr("value") -> o.text).toMap
        voteIn(round, jurors.head, Some(7L))
        val doc = editPage(app, a, round)
        noSelects(doc)
        locked(doc) must beSome(Seq(names("7"), names("6")))
        doc.select("#round-criteria-none").size === 0

        // a replacement in its place posted (an older page): refused, nothing saved, read only again
        val result = save(app, "admin@example.com", a.getId, posted(a, jurors, round, Seq(7L, 9L), "Not saved"))
        status(result) === BAD_REQUEST
        textOf(result) must contain(Locked)
        locked(Jsoup.parse(contentAsString(result))) must beSome(Seq(names("7"), names("6")))
        criteriaOf(round.getId) === Seq(7L, 6L)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Criteria")
        // saved as its page posts it: kept, the name saved
        status(save(app, "admin@example.com", a.getId, posted(a, jurors, round, Nil, "Renamed", shown = false))) ===
          SEE_OTHER
        criteriaOf(round.getId) === Seq(7L, 6L)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Renamed")
      }
    }

    "lock a running round without votes (active, or frozen while active), editable again once stopped" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = true)
        RoundCriterion.hasVote(round.getId, criteriaRates = true) must beFalse
        val doc = editPage(app, a, round)
        noSelects(doc)
        locked(doc).map(_.size) must beSome(2)
        val result = save(app, "admin@example.com", a.getId, posted(a, jurors, round, Seq(6L, 9L), "Not saved"))
        status(result) === BAD_REQUEST
        textOf(result) must contain(Locked)
        criteriaOf(round.getId) === Seq(6L, 7L)
        roundDao.findById(round.getId).flatMap(_.name) must beSome("Criteria")

        // frozen while active (a job reads it): still running
        Round.setActive(round.getId, false)
        sql"UPDATE round SET freeze_count = 1, active_before_freeze = 1 WHERE id = ${round.getId}".update()(AutoSession)
        RoundCriterion.started(round.getId, criteriaRates = true) must beTrue
        noSelects(editPage(app, a, round))
        status(save(app, "admin@example.com", a.getId, posted(a, jurors, round, Seq(6L, 9L), "Not saved"))) ===
          BAD_REQUEST
        criteriaOf(round.getId) === Seq(6L, 7L)

        // stopped (unfrozen), no vote: the full form, and a change taken
        sql"UPDATE round SET freeze_count = 0, active_before_freeze = 0 WHERE id = ${round.getId}".update()(AutoSession)
        val stopped = editPage(app, a, round)
        locked(stopped) must beNone
        offered(stopped).size === Criterion.MaxPerRound
        status(save(app, "admin@example.com", a.getId, posted(a, jurors, round, Seq(6L, 9L), "Renamed"))) === SEE_OTHER
        criteriaOf(round.getId) === Seq(6L, 9L)
      }
    }

    "show a stopped round without votes and without criteria the ten selects with their empty option" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Nil), active = false)
        RoundCriterion.started(round.getId, criteriaRates = false) must beFalse
        val doc = editPage(app, a, round)
        val options = offered(doc)
        options.size === Criterion.MaxPerRound
        forall(options)(values => values must contain(allOf("", "6", "7")))
        doc.select("input[name=criteriaShown]").size === 1
        locked(doc) must beNone
        // all ten shown without scripts; round-form.js shows the chosen ones and the add button,
        // whose row is hidden as rendered
        doc.select("#round-criteria-selects select").size === Criterion.MaxPerRound
        doc.select("#round-criteria-selects [hidden]").size === 0
        doc.getElementById("round-criteria-selects").attr("data-remove-label") === "Remove criterion $X"
        doc.getElementById("criteria-add-row").hasAttr("hidden") must beTrue
        val add = doc.getElementById("criteria-add")
        (add.tagName, add.attr("type"), add.text) === (("button", "button", "Add criteria"))
        add.attr("data-text-more") === "Add another criterion"
      }
    }

    "show a stopped criteria round without votes the ten selects, its criteria chosen, any criterion offered" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = false)
        // the jurors' vote rows, not rated (rate 0, no criterion rate), are no vote
        val rows = VoteJdbc.byRound(round.getId)
        rows must not(beEmpty)
        rows.map(_.rate).distinct === Seq(0)
        ratesCount === 0L
        RoundCriterion.started(round.getId, criteriaRates = true) must beFalse
        val doc = editPage(app, a, round)
        selected(doc.outerHtml).take(3) === Seq("6", "7", "")
        val options = offered(doc)
        options.size === Criterion.MaxPerRound
        forall(options)(values => values must contain(allOf("", "6", "7", "8")))
        locked(doc) must beNone
      }
    }

    "count a criterion rate of a vote moved aside (vote_aside) as a vote" in {
      testDbApp { app =>
        val (a, _, jurors) = fixture()
        val round = setActive(created(app, a, jurors, Seq(6L, 7L)), active = false)
        val vote = VoteJdbc.byRound(round.getId).filter(_.juryId == jurors.head.getId).minBy(_.pageId)
        CriteriaRate.updateRate(vote.getId, 6L, 3) must beTrue
        DB.localTx(implicit s => services.imports.DeletedFiles.markDeleted(Seq(vote.pageId)))
        RoundCriterion.hasVote(round.getId, criteriaRates = true) must beTrue
        locked(editPage(app, a, round)).map(_.size) must beSome(2)
      }
    }
  }

  "a partly rated criteria vote (U5) [J-23]" should {

    "stay in the juror's Unrated list and out of the round's rated votes until every criterion is rated" in {
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
        (listed("unrated"), listed("rated"), VoteJdbc.byRound(round.getId).count(_.rate > 0)) === ((true, false, 0))
        CriteriaRate.updateRate(vote.getId, 7L, 4) must beTrue
        (listed("unrated"), listed("rated"), VoteJdbc.byRound(round.getId).count(_.rate > 0)) === ((false, true, 1))
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
