package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import play.api.Application
import play.api.test.FakeRequest
import play.api.test.Helpers._
import scalikejdbc._
import services.imports.DeletedFiles

/** The monument page (decisions D5-D8 of 2026-11-13): the public list data for every signed-in
  * user; the monument's files in a contest and round only where the user may see them, checked
  * server-side; never an author or another juror's files. A juror gets the round line only, linking
  * their own gallery with the monument (the maintainer's decision of 2026-11-14). And the
  * monuments' entry.
  */
class MonumentControllerSpec extends Specification with PlayTestDb {

  sequential

  private val Id = "07-101-0001"
  private val Author = "SecretAuthor"

  private case class Fixture(contest: ContestJury, admin: User, organizer: User, juror: User, juror2: User,
      root: User, round: Long, otherRound: Long, foreignRound: Long)

  /** The monument (a regional id, with a Commons category and a Wikidata item) and one of a
    * nomination's (an id of its own, neither). In the contest's category, the monument's files 1,
    * 2 (deleted on Commons) and 5, and 3 of another monument; all by the secret author. In an open
    * round, the juror has 1 and 3, the second juror 5; another open round has only the second
    * juror; a round of another contest.
    */
  private def fixture(): Fixture = {
    implicit val contest: ContestJury = createContests(10).head
    def user(role: String, contestId: Option[Long] = contest.id) =
      userDao.create(User(role, s"$role@example.com", None, Set(role), contestId = contestId))
    val Seq(admin, organizer, juror) = Seq("admin", "organizer", "jury").map(user(_))
    val juror2 = userDao.create(User("jury2", "jury2@example.com", None, Set("jury"), contestId = contest.id))
    val root = user("root", None)
    DB.autoCommit { implicit s =>
      MonumentJdbc.batchInsert(Seq(
        Monument(page = "Вікіпедія:Вікі любить пам'ятки/Волинська область", id = Id, name = "[[Замок Любарта]]",
          city = Some("[[Луцьк]]"), place = Some("вул. Кафедральна, 1"), year = Some("XIV ст."), typ = Some("А-нац"),
          gallery = Some("Lubart's Castle"), otherParams = Map("wikidata" -> "Q1229950")),
        Monument(page = "Вікіпедія:Вікі любить пам'ятки/Єврейська спадщина", id = "99-101-0001", name = "Synagogue")
      ))
    }
    def image(id: Long, monument: String) =
      Image(id, s"File:F$id.jpg", None, None, 800, 600, monumentId = Some(monument), author = Some(Author))
    imageDao.batchInsert(Seq(image(1, Id), image(2, Id), image(3, "07-101-0002"), image(5, Id)))
    val round = roundDao.create(Round(None, 1, Some("Main"), contest.getId, createdAt = now, active = true))
    round.addUsers(Seq(juror, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    Seq(1L -> juror, 3L -> juror, 5L -> juror2).foreach { case (p, j) =>
      selectionDao.create(p, rate = 0, juryId = j.getId, roundId = round.getId)
    }
    val otherRound = roundDao.create(Round(None, 2, Some("Other"), contest.getId, createdAt = now, active = true))
    otherRound.addUsers(Seq(RoundUser(otherRound.getId, juror2.getId, "jury", active = true)))
    selectionDao.create(5L, rate = 0, juryId = juror2.getId, roundId = otherRound.getId)
    val foreign = createContests(20).head
    val foreignRound = roundDao.create(Round(None, 1, None, foreign.getId, createdAt = now, active = true))
    DB.localTx { implicit s =>
      val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Test')".updateAndReturnGeneratedKey()
      sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${contest.getId}".update()
      Seq(1L, 2L, 3L, 5L).foreach(id =>
        sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, $id)".update())
      DeletedFiles.markDeleted(Seq(2L))
    }
    Fixture(contest, admin, organizer, juror, juror2, root, round.getId, otherRound.getId, foreignRound.getId)
  }

  private def get(path: String, user: User)(implicit app: Application): (Int, String) = {
    val result = route(app, FakeRequest(GET, path).withSession(Secured.UserName -> user.email)).get
    (status(result), contentAsString(result))
  }

  private def page(user: User, query: String = "")(implicit app: Application): String = {
    val (code, body) = get(s"/monuments/id/$Id$query", user)
    code === OK
    body
  }

  /** The files line's text, tags dropped and spaces joined; None without one. */
  private def files(body: String): Option[String] =
    """(?s)<dd class="col-sm-10" id="monument-files">(.*?)</dd>""".r.findFirstMatchIn(body)
      .map(_.group(1).replaceAll("<[^>]*>", "").replace("&rarr;", "→").replaceAll("\\s+", " ").trim)

  /** The round line's link: the round's list with only the monument's files; None without one. */
  private def roundLink(body: String): Option[String] =
    """<a id="monument-round-files" href="([^"]*)"""".r.findFirstMatchIn(body).map(_.group(1).replace("&amp;", "&"))

  private def contestQuery(f: Fixture, round: Option[Long] = None) =
    s"?contest=${f.contest.getId}" + round.fold("")(r => s"&round=$r")

  "the monument lists [A-13]" should {
    "send a visitor who isn't signed in to the login page" in {
      testDbApp { implicit app =>
        Seq("/monuments", "/monuments/id/07-101-0001").map { path =>
          val result = route(app, FakeRequest("GET", path)).get
          (status(result), redirectLocation(result))
        } === Seq("/monuments", "/monuments/id/07-101-0001")
          .map(path => (SEE_OTHER, Some(routes.LoginController.login(Some(path)).url)))
      }
    }
  }

  "a monument's page" should {

    "show the list's data and links to everyone signed in, without files out of a contest" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.juror)
        body must contain("Замок Любарта")
        body must contain("""<a href="https://uk.wikipedia.org/wiki/%D0%9B%D1%83%D1%86%D1%8C%D0%BA">Луцьк</a>""")
        body must contain("вул. Кафедральна, 1")
        body must contain("07-101-0001 · А-нац · XIV ст.")
        // the list's row: its id is the anchor of the regional lists' rows
        """id="monument-list-link" href="([^"]*)"""".r.findFirstMatchIn(body).map(_.group(1)) must
          beSome(MonumentPage.wikiUrl("uk.wikipedia.org", "Вікіпедія:Вікі любить пам'ятки/Волинська область") + "#07-101-0001")
        body must contain("""href="https://uk.wikipedia.org/wiki/%D0%92%D1%96%D0%BA%D1%96%D0%BF%D0%B5%D0%B4%D1%96%D1%8F:""")
        body must contain("/%D0%92%D0%BE%D0%BB%D0%B8%D0%BD%D1%81%D1%8C%D0%BA%D0%B0_%D0%BE%D0%B1%D0%BB%D0%B0%D1%81%D1%82%D1%8C#07-101-0001\"")
        body must contain(" #07-101-0001 ↗")
        body must contain("""id="monument-commons-link" href="https://commons.wikimedia.org/wiki/Category:Lubart%27s_Castle"""")
        body must contain("""id="monument-wikidata-link" href="https://www.wikidata.org/wiki/Q1229950"""")
        // no contest: no files
        body must not contain "monument-files"
        body must not contain Author
        // the same for an organizer and root without a contest
        Seq(f.organizer, f.root).map(u => files(page(u))) === Seq(None, None)
      }
    }

    "link a nomination's list without an anchor, and leave out a category or item it doesn't have" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, body) = get("/monuments/id/99-101-0001", f.juror)
        code === OK
        body must contain("Synagogue")
        """id="monument-list-link" href="([^"]*)"""".r.findFirstMatchIn(body).map(_.group(1)) must
          beSome(MonumentPage.wikiUrl("uk.wikipedia.org", "Вікіпедія:Вікі любить пам'ятки/Єврейська спадщина"))
        body must not contain "#99-101-0001"
        body must not contain "monument-commons-link"
        body must not contain "monument-wikidata-link"
      }
    }

    "be a 404 for an unknown monument" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, body) = get("/monuments/id/07-101-9999", f.organizer)
        code === NOT_FOUND
        body must contain("There is no monument with this id.")
      }
    }

    "count the contest's and the round's files for organizers, admins and root, linking the contest's and all the jurors' list" in {
      testDbApp { implicit app =>
        val f = fixture()
        val expected = Some("In the contest: 3 → · In round Main: 2 →")
        Seq(f.organizer, f.admin, f.root).map(u => files(page(u, contestQuery(f, Some(f.round))))) === Seq.fill(3)(expected)
        val body = page(f.organizer, contestQuery(f, Some(f.round)))
        body must contain(s"""id="monument-contest-files" href="/contest/${f.contest.getId}/files?monument=07-101-0001"""")
        // the round's list of all the jurors' votes (0), in its default order, with the monument
        roundLink(body) must beSome(s"/gallery/round/${f.round}/user/0/page/1?monument=07-101-0001&sort=rate")
        // the round alone gives its contest
        files(page(f.organizer, s"?round=${f.round}")) === expected
      }
    }

    "count only a juror's own files in the round, linking their gallery; no contest count" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.juror, contestQuery(f, Some(f.round)))
        files(body) === Some("In round Main: 1 (yours) →")
        // their own gallery (their id, never 0), in their order, with the monument
        roundLink(body) must beSome(s"/gallery/round/${f.round}/user/${f.juror.getId}/page/1?monument=07-101-0001&sort=file")
        body must not contain "monument-contest-files"
        // no link to the contest files page anywhere on a juror's page
        body must not contain "/contest/"
        body must not contain Author
        files(page(f.juror2, contestQuery(f, Some(f.round)))) === Some("In round Main: 1 (yours) →")
        roundLink(page(f.juror2, contestQuery(f, Some(f.round)))) must
          beSome(s"/gallery/round/${f.round}/user/${f.juror2.getId}/page/1?monument=07-101-0001&sort=file")
        files(page(f.juror2, contestQuery(f, Some(f.otherRound)))) === Some("In round Other: 1 (yours) →")
      }
    }

    "show a juror's round without the monument's files as a count without a link" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, body) = get(s"/monuments/id/99-101-0001${contestQuery(f, Some(f.round))}", f.juror)
        code === OK
        files(body) === Some("In round Main: 0 (yours)")
        roundLink(body) must beNone
        body must not contain "monument=99-101-0001"
      }
    }

    "leave out a round the juror isn't in, a round of another contest, and an unknown one" in {
      testDbApp { implicit app =>
        val f = fixture()
        // the juror isn't in the other round: no count of its files, and no contest count for a
        // juror, so no files row at all
        files(page(f.juror, contestQuery(f, Some(f.otherRound)))) === None
        files(page(f.organizer, contestQuery(f, Some(f.foreignRound)))) === Some("In the contest: 3 →")
        files(page(f.organizer, contestQuery(f, Some(999999L)))) === Some("In the contest: 3 →")
        // a round being created is hidden from its jurors
        DB.autoCommit { implicit s => sql"UPDATE round SET distributing = 1 WHERE id = ${f.round}".update() }
        files(page(f.juror, contestQuery(f, Some(f.round)))) === None
      }
    }

    "show no files to a juror of another contest, nor for an unknown contest or a round of another" in {
      testDbApp { implicit app =>
        val f = fixture()
        val stranger =
          userDao.create(User("stranger", "stranger@example.com", None, Set("jury"), contestId = createContests(30).head.id))
        files(page(stranger, contestQuery(f, Some(f.round)))) === None
        files(page(stranger, s"?round=${f.round}")) === None
        files(page(f.organizer, "?contest=999999")) === None
        files(page(f.organizer, s"?round=${f.foreignRound}")) === None
        files(page(f.juror, s"?contest=${f.contest.getId}&round=${f.foreignRound}")) === None
        files(page(f.juror, s"?contest=${f.contest.getId}")) === None
      }
    }

    "show no files to an inactive membership of the contest, no role or one without access" in {
      testDbApp { implicit app =>
        val f = fixture()
        val former =
          userDao.create(User("former", "former@example.com", None, Set("jury"), contestId = createContests(30).head.id))
        UserContestJdbc.createMembership(former.getId, f.contest.getId, User.NO_ROLE)
        val board =
          userDao.create(User("board", "board@example.com", None, Set("jury"), contestId = createContests(40).head.id))
        UserContestJdbc.createMembership(board.getId, f.contest.getId, User.BOARD_ROLE)
        Seq(former, board).map(u => files(page(u, contestQuery(f, Some(f.round))))) === Seq(None, None)
      }
    }

    "count in another of a juror's contests than the session's, the round line only, without switching to it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val both =
          userDao.create(User("both", "both@example.com", None, Set("jury"), contestId = createContests(30).head.id))
        UserContestJdbc.createMembership(both.getId, f.contest.getId, "jury")
        Round.findById(f.round).get.addUsers(Seq(RoundUser(f.round, both.getId, "jury", active = true)))
        selectionDao.create(1L, rate = 0, juryId = both.getId, roundId = f.round)
        val result = route(app, FakeRequest(GET, s"/monuments/id/$Id${contestQuery(f, Some(f.round))}")
          .withSession(Secured.UserName -> both.email, Secured.CurrentContestId -> "30")).get
        status(result) === OK
        files(contentAsString(result)) === Some("In round Main: 1 (yours) →")
        roundLink(contentAsString(result)) must
          beSome(s"/gallery/round/${f.round}/user/${both.getId}/page/1?monument=07-101-0001&sort=file")
        session(result).get(Secured.CurrentContestId) must not(beSome(f.contest.getId.toString)) // still 30
      }
    }

    "show a contest without the monument's files as a count without a link" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, body) = get(s"/monuments/id/99-101-0001${contestQuery(f, Some(f.round))}", f.organizer)
        code === OK
        files(body) === Some("In the contest: 0 · In round Main: 0")
        body must not contain "files?monument="
      }
    }
  }

  "the monuments' entry" should {
    "open a monument by its id, and say when there is none" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, form) = get("/monuments", f.juror)
        code === OK
        form must contain("""<input id="monument-id" name="id"""")
        val found = route(app, FakeRequest(GET, s"/monuments?id=+$Id+").withSession(Secured.UserName -> f.juror.email)).get
        status(found) === SEE_OTHER
        redirectLocation(found) must beSome(s"/monuments/id/$Id")
        val (missing, body) = get("/monuments?id=07-000-0000", f.juror)
        missing === NOT_FOUND
        body must contain("There is no monument 07-000-0000.")
      }
    }
  }

  "a monument card's link" should {
    "open the monument's page in the round's contest and round" in {
      testDbApp { implicit app =>
        val f = fixture()
        val monument = MonumentJdbc.find(Id)
        implicit val messages: play.api.i18n.Messages = play.api.i18n.MessagesImpl(play.api.i18n.Lang("en"),
          app.injector.instanceOf[play.api.i18n.MessagesApi])
        implicit val request: play.api.mvc.Request[Any] = FakeRequest()
        implicit val flash: play.api.mvc.Flash = play.api.mvc.Flash()
        val image = Image(1, "File:F1.jpg", None, None, 800, 600, monumentId = Some(Id))
        views.html.large.monumentInfo(image, monument, f.contest.id, Some(f.round)).body must
          contain(s"""<span id="monument-id">07-101-0001</span> &middot; <a id="monument-page" href="/monuments/id/07-101-0001?contest=${f.contest.getId}&amp;round=${f.round}" title="Monument page">Page &rarr;</a></div>""")
        views.html.large.monumentInfo(image, monument).body must
          contain("""id="monument-page" href="/monuments/id/07-101-0001" title="Monument page">""")
      }
    }

    "carry one line: the id, the gallery unless filtered or the only file, the page; a minimal card without a monument row" in {
      testDbApp { implicit app =>
        val f = fixture()
        val monument = MonumentJdbc.find(Id)
        implicit val messages: play.api.i18n.Messages = play.api.i18n.MessagesImpl(play.api.i18n.Lang("en"),
          app.injector.instanceOf[play.api.i18n.MessagesApi])
        implicit val request: play.api.mvc.Request[Any] = FakeRequest()
        implicit val flash: play.api.mvc.Flash = play.api.mvc.Flash()
        val image = Image(1, "File:F1.jpg", None, None, 800, 600, monumentId = Some(Id))
        val page = s"""<a id="monument-page" href="/monuments/id/07-101-0001?contest=${f.contest.getId}&amp;round=${f.round}" title="Monument page">Page &rarr;</a>"""
        val linked = views.html.large.monumentInfo(image, monument, f.contest.id, Some(f.round),
          Some(MonumentLine(Id, 3, Some("/gallery/round/1/user/2/page/1?monument=07-101-0001&sort=file")))).body
        linked must contain(
          """<span id="monument-id">07-101-0001</span> &middot; <a id="monument-round-list" href="/gallery/round/1/user/2/page/1?monument=07-101-0001&amp;sort=file" title="Show only this monument&#x27;s photos in the gallery">Gallery (3)</a> &middot; """ + page)
        // the id once, "Monument" nowhere
        linked.split("07-101-0001</span>").length === 2
        linked must not contain "Monument 07"
        // the only file: the id and the page
        val only = views.html.large.monumentInfo(image, monument, f.contest.id, Some(f.round), Some(MonumentLine(Id, 1, None))).body
        only must contain("""<span id="monument-id">07-101-0001</span> &middot; """ + page)
        only must not contain "monument-round-list"
        // the list already filtered to the monument (no line): the id and the page
        val filtered = views.html.large.monumentInfo(image, monument, f.contest.id, Some(f.round), None, Some(Id)).body
        filtered must contain("""<span id="monument-id">07-101-0001</span> &middot; """ + page)
        filtered must not contain "monument-round-list"
        // a monument without a row: its id and the gallery, no monument page (a 404 without a row)
        val bare = Image(9, "File:F9.jpg", None, None, 800, 600, monumentId = Some("07-101-9999"))
        val minimal = views.html.large.monumentInfo(bare, None, f.contest.id, Some(f.round),
          Some(MonumentLine("07-101-9999", 2, Some("/x")))).body
        minimal must contain("""id="monument-card"""")
        minimal must contain("""<span id="monument-id">07-101-9999</span> &middot; <a id="monument-round-list" href="/x" title="Show only this monument&#x27;s photos in the gallery">Gallery (2)</a></div>""")
        minimal must not contain "monument-page"
        // its only file, or the list filtered to it: just the id
        views.html.large.monumentInfo(bare, None, f.contest.id, Some(f.round), Some(MonumentLine("07-101-9999", 1, None))).body must
          contain("""<span id="monument-id">07-101-9999</span></div>""")
        views.html.large.monumentInfo(bare, None, f.contest.id, Some(f.round), None, Some("07-101-9999")).body must
          contain("""<span id="monument-id">07-101-9999</span></div>""")
        // filtered to another monument: no card
        views.html.large.monumentInfo(bare, None, f.contest.id, Some(f.round), None, Some(Id)).body must not contain "monument-card"
        views.html.large.monumentInfo(bare, None, f.contest.id, Some(f.round)).body must not contain "monument-card"
      }
    }
  }
}
