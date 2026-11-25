package graphql

import controllers.{Global, ListOrder, RoundList, Secured, VoteFilter}
import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User, UserContestJdbc}
import org.intracer.wmua.{Image, Vote}
import play.api.Application
import play.api.libs.json.{JsArray, JsNull, JsObject, JsString, JsValue, Json}
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

/** GraphQL's round queries through the router, as served (plan §6, slices 2 and 3): `round(id)`,
  * `roundStat(roundId)`, `roundImages` (keyset paging, its statements a page) and `image`, and their
  * negative authorization tests (plan §5): another contest's round, an
  * inactive membership, a juror, the conflict-of-interest rule, a stopped juror, a token with a series
  * grant, root.
  */
class GraphQLRoundsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val rootEmail = "root@example.com"
  private val adminEmail = "admin@example.com" // admin of 10
  private val organizerEmail = "organizer@example.com" // organizer of 10
  private val jurorAEmail = "juror-a@example.com" // juror of 10: the binary, rated and distributing rounds
  private val jurorBEmail = "juror-b@example.com" // juror of 10: the binary round
  private val outsiderEmail = "outsider@example.com" // juror of 20 only
  private val inactiveEmail = "inactive@example.com" // "no" role in 10

  /** Rounds: in 10 an open binary round (jurors A and B), an open rated round of 1-5 stars (A), a round
    * being distributed (A); in 20 an open round (the outsider).
    */
  private case class Fixture(binary: Long, rated: Long, distributing: Long, other20: Long)

  private def account(email: String, root: Boolean = false): User =
    User.create(User(email, email, password = Some(services.PasswordHasher.default.hash("secret")), isRoot = root))

  private def userId(email: String): Long = User.findByEmail(email).head.getId

  /** The accounts above, the rounds, files 1..6 and the jurors' votes:
    *  - binary: A selects 1 and 4, rejects 2, leaves 3, 5 and 6; B selects 1 and 2, rejects 3, leaves the rest;
    *  - rated: A gives 1 five stars, 2 three stars and 3 the cross, leaves 4 to 6.
    */
  private def setUp(): Fixture = {
    createContests(10, 20)
    account(rootEmail, root = true)
    val admin = account(adminEmail)
    UserContestJdbc.createMembership(admin.getId, 10, User.ADMIN_ROLE)
    UserContestJdbc.createMembership(account(organizerEmail).getId, 10, "organizer")
    val a = account(jurorAEmail)
    UserContestJdbc.createMembership(a.getId, 10, User.JURY_ROLE)
    val b = account(jurorBEmail)
    UserContestJdbc.createMembership(b.getId, 10, User.JURY_ROLE)
    val outsider = account(outsiderEmail)
    UserContestJdbc.createMembership(outsider.getId, 20, User.JURY_ROLE)
    UserContestJdbc.createMembership(account(inactiveEmail).getId, 10, User.NO_ROLE)

    def round(contest: Long, number: Long, jurors: Seq[User], rates: db.scalikejdbc.Rates = Round.binaryRound,
              distributing: Boolean = false) = {
      val r = Round.create(Round(None, number, Some(s"round $number of $contest"), contest, rates = rates,
        active = !distributing, distributing = distributing))
      r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
      if (distributing) DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 1 WHERE id = ${r.getId}".update.apply())
      r
    }
    val binary = round(10, 1, Seq(a, b))
    val rated = round(10, 2, Seq(a), rates = Round.ratesById(5))
    val distributing = round(10, 3, Seq(a), distributing = true)
    val other20 = round(20, 1, Seq(outsider))
    val images = (1 to 6).map(i => Image(i.toLong, s"File:Image$i.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image$i.jpg"),
      None, 640, 480, Some(f"80-361-${(i + 1) / 2}%04d")))
    imageDao.batchInsert(images)
    def votes(r: Round, juror: User, rates: Seq[Int]) = images.zip(rates).map { case (image, rate) => Vote(image, juror, r, rate) }
    selectionDao.batchInsert(
      votes(binary, a, Seq(1, -1, 0, 1, 0, 0)) ++ votes(binary, b, Seq(1, 1, -1, 0, 0, 0)) ++
        votes(rated, a, Seq(5, 3, -1, 0, 0, 0)) ++ votes(distributing, a, Seq.fill(6)(0)) ++
        votes(other20, outsider, Seq.fill(6)(0)))
    Fixture(binary.getId, rated.getId, distributing.getId, other20.getId)
  }

  private def signedIn(email: String, contest: Long = 10): Seq[(String, String)] =
    Seq(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString)

  private def post(app: Application, query: String, session: Seq[(String, String)] = Nil,
                   headers: Seq[(String, String)] = Nil, variables: JsObject = Json.obj()): Future[Result] = {
    val result = route(app, FakeRequest(POST, "/api/graphql").withHeaders(headers: _*).withSession(session: _*)
      .withJsonBody(Json.obj("query" -> query, "variables" -> variables))).get
    Await.ready(result, 30.seconds)
    result
  }

  private def gql(app: Application, query: String, as: String, variables: JsObject = Json.obj()): Future[Result] =
    post(app, query, signedIn(as), variables = variables)

  private def asToken(app: Application, user: User, query: String, variables: JsObject = Json.obj()): Future[Result] =
    post(app, query, headers = Seq(AUTHORIZATION -> s"Bearer ${ApiTokenService.create(user.getId, "cli", Some(90))}"),
      variables = variables)

  private def json(result: Future[Result]): JsValue = contentAsJson(result)

  private def data(result: Future[Result], field: String): JsValue = (json(result) \ "data" \ field).get

  private def errors(result: Future[Result]): Seq[JsValue] =
    (json(result) \ "errors").asOpt[JsArray].fold(Seq.empty[JsValue])(_.value.toSeq)

  private def codes(result: Future[Result]): Seq[String] = errors(result).map(e => (e \ "extensions" \ "code").as[String])

  private def messages(result: Future[Result]): Seq[String] = errors(result).map(e => (e \ "message").as[String])

  private def reference(result: Future[Result]): String = (errors(result).head \ "extensions" \ "reference").as[String]

  private def logger(name: String) =
    org.slf4j.LoggerFactory.getLogger(name).asInstanceOf[ch.qos.logback.classic.Logger]

  /** `f`'s result and the lines logged meanwhile on `name`. */
  private def withLog[T](name: String)(f: => T): (T, Seq[String]) = {
    val log = logger(name)
    val level = log.getLevel
    // INFO lines too (round.access), whatever the test config's level
    log.setLevel(ch.qos.logback.classic.Level.INFO)
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    log.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(e => s"${e.getLevel} ${e.getFormattedMessage}"))
    } finally {
      log.detachAppender(appender)
      log.setLevel(level)
    }
  }

  /** `field` is null with one error of `code`, whose reference is on exactly one `security.authz` line. */
  private def deniedAs(field: String, code: String)(result: => Future[Result]) = {
    val (r, lines) = withLog("security.authz")(result)
    val withRef = lines.filter(_.contains(s"ref=${reference(r)}"))
    (data(r, field) === JsNull) and (codes(r) === Seq(code)) and (withRef must haveSize(1)) and
      (withRef.head must startWith("WARN"))
  }

  /** `field` is null with `NOT_FOUND`, a reference, and no denial logged (a missing resource). */
  private def missingAs(field: String)(result: => Future[Result]) = {
    val (r, lines) = withLog("security.authz")(result)
    (data(r, field) === JsNull) and (codes(r) === Seq("NOT_FOUND")) and
      ((errors(r).head \ "extensions" \ "reference").asOpt[String] must beSome) and (lines must beEmpty)
  }

  private val statQuery =
    """query S($id: ID!) { roundStat(roundId: $id) {
      |  round { id title } totalFiles selectedFiles unratedFiles rejectedFiles deletedFiles deletedFilesCounted
      |  commentsInRound commentsAllRounds noActiveJuror
      |  jurors { id fullname email status files unrated rated selected rejected } } }""".stripMargin

  private def stat(app: Application, as: String, round: Long): Future[Result] =
    gql(app, statQuery, as, Json.obj("id" -> round.toString))

  /** Each juror's row of a stat, by email: (files, unrated, rated, selected, rejected, status). */
  private def jurorRows(result: Future[Result]): Map[Long, (Int, Int, Option[Int], Option[Int], Option[Int], String)] =
    (data(result, "roundStat") \ "jurors").as[Seq[JsObject]].map { o =>
      (o \ "id").as[String].toLong -> ((o \ "files").as[Int], (o \ "unrated").as[Int], (o \ "rated").asOpt[Int],
        (o \ "selected").asOpt[Int], (o \ "rejected").asOpt[Int], (o \ "status").as[String])
    }.toMap

  "roundStat" should {

    "give root, the contest's admin and its organizer the statistics page's numbers" in {
      testDbApp { app =>
        val f = setUp()
        val (a, b) = (userId(jurorAEmail), userId(jurorBEmail))
        forall(Seq(rootEmail, adminEmail, organizerEmail)) { email =>
          val result = stat(app, email, f.binary)
          errors(result) must beEmpty
          val s = data(result, "roundStat")
          (s \ "round" \ "id").as[String] === f.binary.toString
          (s \ "totalFiles").as[Int] === 6
          // files with at least one vote of each kind: selected 1, 2, 4; unrated 3, 4, 5, 6; rejected 2, 3
          ((s \ "selectedFiles").as[Int], (s \ "unratedFiles").as[Int], (s \ "rejectedFiles").as[Int]) === ((3, 4, 2))
          (s \ "deletedFiles").as[Int] === 0
          (s \ "noActiveJuror").as[Boolean] === false
          jurorRows(result) === Map(
            a -> ((6, 3, None, Some(2), Some(1), "ACTIVE")),
            b -> ((6, 3, None, Some(2), Some(1), "ACTIVE")))
        }
        // a rated round: each juror's rated (any vote, the cross too) and unrated, no yes/no counts
        val rated = stat(app, adminEmail, f.rated)
        jurorRows(rated) === Map(a -> ((6, 3, Some(3), None, None, "ACTIVE")))
        (data(rated, "roundStat") \ "selectedFiles").toOption must beSome(JsNull: JsValue)
      }
    }

    "show jurors' emails to root and the contest's admin only (decision Q6), not to its organizer" in {
      testDbApp { app =>
        val f = setUp()
        def emails(as: String) = (data(stat(app, as, f.binary), "roundStat") \ "jurors").as[Seq[JsObject]]
          .map(o => (o \ "email").asOpt[String]).toSet
        emails(rootEmail) === Set(Some(jurorAEmail), Some(jurorBEmail))
        emails(adminEmail) === Set(Some(jurorAEmail), Some(jurorBEmail))
        emails(organizerEmail) === Set(None)
      }
    }

    "show a juror stopped on the round as STOPPED, and a round with no juror who can vote" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s => sql"UPDATE round_user SET active = FALSE WHERE round_id = ${f.rated}".update.apply())
        val result = stat(app, organizerEmail, f.rated)
        jurorRows(result).values.map(_._6).toSeq === Seq("STOPPED")
        (data(result, "roundStat") \ "noActiveJuror").as[Boolean] === true
      }
    }

    "apply the conflict-of-interest rule: an organizer who is a juror of the open round sees others' progress, not their votes" in {
      testDbApp { app =>
        val f = setUp()
        val organizer = User.findByEmail(organizerEmail).head
        Round.findById(f.binary).get.addUsers(Seq(RoundUser(f.binary, organizer.getId, "organizer", active = true)))
        selectionDao.batchInsert(Seq(Vote(1, organizer.getId, f.binary, 1), Vote(2, organizer.getId, f.binary, 0)))
        val (a, b) = (userId(jurorAEmail), userId(jurorBEmail))
        val open = stat(app, organizerEmail, f.binary)
        jurorRows(open) === Map(
          a -> ((6, 3, None, None, None, "ACTIVE")),
          b -> ((6, 3, None, None, None, "ACTIVE")),
          organizer.getId -> ((2, 1, None, Some(1), Some(0), "ACTIVE")))
        (data(open, "roundStat") \ "selectedFiles").toOption must beSome(JsNull: JsValue)
        // once the round is stopped, the rule no longer applies
        DB.autoCommit(implicit s => sql"UPDATE round SET active = FALSE WHERE id = ${f.binary}".update.apply())
        jurorRows(stat(app, organizerEmail, f.binary))(a) === ((6, 3, None, Some(2), Some(1), "ACTIVE"))
      }
    }

    "refuse a juror their own round's statistics with FORBIDDEN (the page's 403); another round as NOT_FOUND, both logged" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("roundStat", "FORBIDDEN")(stat(app, jurorAEmail, f.binary))
        deniedAs("roundStat", "FORBIDDEN")(stat(app, jurorAEmail, f.rated))
        // a round the juror doesn't see: its existence not told
        deniedAs("roundStat", "NOT_FOUND")(stat(app, jurorBEmail, f.rated))
        deniedAs("roundStat", "NOT_FOUND")(stat(app, jurorAEmail, f.distributing))
      }
    }

    "answer another contest's round, an inactive member's and a missing round as NOT_FOUND, the denials logged" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("roundStat", "NOT_FOUND")(stat(app, outsiderEmail, f.binary))
        deniedAs("roundStat", "NOT_FOUND")(stat(app, adminEmail, f.other20))
        deniedAs("roundStat", "NOT_FOUND")(stat(app, inactiveEmail, f.binary))
        missingAs("roundStat")(stat(app, adminEmail, 999999))
        missingAs("roundStat")(gql(app, statQuery, adminEmail, Json.obj("id" -> "x")))
        // a deleted round is a missing one
        DB.autoCommit(implicit s => sql"UPDATE round SET deleted_at = NOW() WHERE id = ${f.rated}".update.apply())
        missingAs("roundStat")(stat(app, adminEmail, f.rated))
      }
    }

    "let root see any contest's round, without a membership" in {
      testDbApp { app =>
        val f = setUp()
        (data(stat(app, rootEmail, f.other20), "roundStat") \ "totalFiles").as[Int] === 6
      }
    }
  }

  private def roundQuery(id: Long) = s"{ round(id: $id) { id contestId title kind myStatus othersVotesVisible } }"

  "round(id)" should {

    "show a juror their rounds, once distributed; the organizer, the admin and root every round" in {
      testDbApp { app =>
        val f = setUp()
        val r = data(gql(app, roundQuery(f.binary), jurorAEmail), "round")
        ((r \ "id").as[String], (r \ "contestId").as[String], (r \ "kind").as[String], (r \ "myStatus").as[String],
          (r \ "othersVotesVisible").as[Boolean]) === ((f.binary.toString, "10", "BINARY", "ACTIVE", false))
        (data(gql(app, roundQuery(f.rated), jurorAEmail), "round") \ "kind").as[String] === "RATED"
        forall(Seq(organizerEmail, adminEmail, rootEmail)) { email =>
          forall(Seq(f.binary, f.rated, f.distributing)) { id =>
            (data(gql(app, roundQuery(id), email), "round") \ "othersVotesVisible").as[Boolean] === true
          }
        }
        (data(gql(app, roundQuery(f.other20), rootEmail), "round") \ "contestId").as[String] === "20"
      }
    }

    "answer a round the juror doesn't judge, one being distributed, another contest's and an inactive member's as NOT_FOUND, logged" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.rated), jurorBEmail))
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.distributing), jurorAEmail))
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.binary), outsiderEmail))
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.other20), adminEmail))
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.binary), inactiveEmail))
        missingAs("round")(gql(app, roundQuery(999999), adminEmail))
      }
    }

    "keep a stopped juror's round, STOPPED" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s =>
          sql"UPDATE round_user SET active = FALSE WHERE round_id = ${f.binary} AND user_id = ${userId(jurorBEmail)}".update.apply())
        (data(gql(app, roundQuery(f.binary), jurorBEmail), "round") \ "myStatus").as[String] === "STOPPED"
      }
    }
  }

  "a series admin" should {

    /** Series A with this year's contest `now`, where `seriesAdmin`'s grant makes them a derived Admin;
      * a round `free` of nobody and a round `coi` they judge, open (conflict of interest).
      */
    case class Series(seriesAdmin: User, juror: User, free: Long, coi: Long)

    def seriesSetUp(): Series = {
      val thisYear = java.time.Year.now().getValue
      val root = account(rootEmail, root = true)
      val seriesId = services.series.ContestSeries.createSeriesTx("Nepal", Some("wlm"), Some("Nepal"), None, root.getId)
        .toOption.get
      val now = db.scalikejdbc.ContestJuryJdbc.create(None, "Wiki Loves Monuments", thisYear, "Nepal").getId
      services.series.ContestSeries.setSeries(now, Some(seriesId), root.getId, None)
      val seriesAdmin = account("seriesadmin@example.com")
      UserContestJdbc.createMembership(seriesAdmin.getId, now, "organizer")
      services.series.SeriesAdmins.grant(root, seriesId, seriesAdmin.getId, None, None).toOption.get
      UserContestJdbc.delete(seriesAdmin.getId, now)
      val juror = account(jurorAEmail)
      UserContestJdbc.createMembership(juror.getId, now, User.JURY_ROLE)
      def round(number: Long, jurors: Seq[User]) = {
        val r = Round.create(Round(None, number, Some(s"round $number"), now, active = true))
        r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
        r
      }
      val free = round(1, Seq(juror))
      val coi = round(2, Seq(seriesAdmin, juror))
      val images = (1 to 3).map(i => Image(i.toLong, s"File:S$i.jpg", None, None, 640, 480, None))
      imageDao.batchInsert(images)
      selectionDao.batchInsert(images.flatMap(i => Seq(Vote(i, juror, free, 1), Vote(i, juror, coi, -1),
        Vote(i, seriesAdmin, coi, 0))))
      Series(seriesAdmin, juror, free.getId, coi.getId)
    }

    "see a round's statistics from a session (derived Admin), its own votes only where they judge the open round" in {
      testDbApp { app =>
        val s = seriesSetUp()
        val session = Seq(Secured.UserName -> s.seriesAdmin.email)
        val free = post(app, statQuery, session, variables = Json.obj("id" -> s.free.toString))
        errors(free) must beEmpty
        (data(free, "roundStat") \ "jurors").as[Seq[JsObject]].map(o => (o \ "selected").asOpt[Int]) === Seq(Some(3))
        val coi = post(app, statQuery, session, variables = Json.obj("id" -> s.coi.toString))
        // the series admin has no membership row: the page's table, like this, lists the contest's members
        jurorRows(coi) === Map(s.juror.getId -> ((3, 0, None, None, None, "ACTIVE")))
        (data(post(app, roundQuery(s.free), session), "round") \ "othersVotesVisible").as[Boolean] === true
      }
    }

    "get no series reach through a personal token: NOT_FOUND, logged" in {
      testDbApp { app =>
        val s = seriesSetUp()
        deniedAs("roundStat", "NOT_FOUND")(asToken(app, s.seriesAdmin, statQuery, Json.obj("id" -> s.free.toString)))
        deniedAs("round", "NOT_FOUND")(asToken(app, s.seriesAdmin, roundQuery(s.free)))
      }
    }
  }

  // --- slice 3: roundImages and image

  private val imageQuery =
    "query I($r: ID!, $p: ID!, $u: ID) { image(roundId: $r, pageId: $p, userId: $u) { pageId vote { rate } } }"

  private val imagesQuery =
    """query I($round: ID!, $user: ID, $filter: ImageFilter, $order: ImageOrder, $first: Int, $after: String) {
      |  roundImages(roundId: $round, userId: $user, filter: $filter, order: $order, first: $first, after: $after) {
      |    totalCount pageInfo { endCursor hasNextPage }
      |    nodes { pageId title width height monumentId isVideo vote { rate voted votedAt }
      |      thumbnail { url srcset width height } row: thumbnail(size: ROW) { url srcset } largeImage { src srcset fallback } } } }""".stripMargin

  /** The same list with a file's page id only: a page of 200 within the complexity budget. */
  private val idsQuery =
    """query I($round: ID!, $user: ID, $filter: ImageFilter, $order: ImageOrder, $first: Int, $after: String) {
      |  roundImages(roundId: $round, userId: $user, filter: $filter, order: $order, first: $first, after: $after) {
      |    pageInfo { endCursor hasNextPage } nodes { pageId } } }""".stripMargin

  private def images(app: Application, as: String, round: Long, user: Option[Long] = None, filter: Option[String] = None,
                     order: Option[String] = None, first: Option[Int] = None, after: Option[String] = None,
                     query: String = imagesQuery, session: Option[Seq[(String, String)]] = None): Future[Result] = {
    val variables = Json.obj("round" -> round.toString) ++
      user.fold(Json.obj())(u => Json.obj("user" -> u.toString)) ++
      filter.fold(Json.obj())(v => Json.obj("filter" -> v)) ++ order.fold(Json.obj())(v => Json.obj("order" -> v)) ++
      first.fold(Json.obj())(v => Json.obj("first" -> v)) ++ after.fold(Json.obj())(v => Json.obj("after" -> v))
    post(app, query, session.getOrElse(signedIn(as)), variables = variables)
  }

  private def nodes(result: Future[Result]): Seq[JsObject] = (data(result, "roundImages") \ "nodes").as[Seq[JsObject]]

  private def pageIdsOf(result: Future[Result]): Seq[Long] = nodes(result).map(o => (o \ "pageId").as[String].toLong)

  private def ratesOf(result: Future[Result]): Seq[(Long, Int)] =
    nodes(result).map(o => (o \ "pageId").as[String].toLong -> (o \ "vote" \ "rate").as[Int])

  private def pageInfo(result: Future[Result]): (Option[String], Boolean) = {
    val info = data(result, "roundImages") \ "pageInfo"
    ((info \ "endCursor").asOpt[String], (info \ "hasNextPage").as[Boolean])
  }

  /** All pages of a list, `first` at a time, following the cursors: each page's page ids. */
  private def allPages(app: Application, as: String, round: Long, first: Int, filter: Option[String] = None,
                       order: Option[String] = None): Seq[Seq[Long]] = {
    val pages = Seq.newBuilder[Seq[Long]]
    var after = Option.empty[String]
    var more = true
    var n = 0
    while (more && n < 100) {
      val result = images(app, as, round, filter = filter, order = order, first = Some(first), after = after, query = idsQuery)
      errors(result) must beEmpty
      val ids = pageIdsOf(result)
      val (end, hasNext) = pageInfo(result)
      if (hasNext) ids.size === first
      if (ids.nonEmpty) pages += ids
      after = end
      more = hasNext
      n += 1
    }
    // past the last page: nothing, no cursor
    val past = images(app, as, round, filter = filter, order = order, first = Some(first), after = after, query = idsQuery)
    pageIdsOf(past) must beEmpty
    pageInfo(past) === ((None, false))
    pages.result()
  }

  /** A rated round (1-5 stars) of juror A in 10, files 101..113: monument ids in threes, two files with none,
    * rates with ties, vote times with ties (some untimed).
    */
  private def pagingRound(): Round = {
    val a = User.findByEmail(jurorAEmail).head
    val round = Round.create(Round(None, 4, Some("paging"), 10, rates = Round.ratesById(5), active = true))
    round.addUsers(Seq(RoundUser(round.getId, a.getId, "jury", active = true)))
    val files = (101 to 113).map { i =>
      Image(i.toLong, s"File:P$i.jpg", None, None, 800, 600,
        if (i == 104 || i == 109) None else Some(f"80-361-${(i - 101) / 3}%04d"))
    }
    imageDao.batchInsert(files)
    val rates = Seq(5, 5, 3, 0, 3, 5, -1, 0, 0, 3, 5, -1, 0)
    selectionDao.batchInsert(files.zip(rates).map { case (image, rate) => Vote(image, a, round, rate) })
    def at(time: String, pages: Int*) = DB.autoCommit(implicit s =>
      sql"UPDATE vote SET voted_at = TIMESTAMP($time) WHERE round_id = ${round.getId} AND page_id IN ($pages)".update.apply())
    at("2026-11-01 10:00:00.123", 101, 102, 105)
    at("2026-11-02 10:00:00.456", 103, 106)
    at("2026-11-03 10:00:00", 107, 110)
    DB.autoCommit(implicit s =>
      sql"UPDATE vote SET voted_at = NULL WHERE round_id = ${round.getId} AND page_id IN (111, 112)".update.apply())
    round
  }

  /** The juror's whole list as the galleries read it (one query, no paging). */
  private def wholeList(round: Round, juror: Long, filter: VoteFilter, order: ListOrder): Seq[Long] =
    RoundList(round, juror, filter = filter, order = order).query.list().map(_.pageId)

  "roundImages" should {

    "list a juror's own files with their own votes, and the thumbnails the web galleries load" in {
      testDbApp { app =>
        val f = setUp()
        val result = images(app, jurorAEmail, f.binary)
        errors(result) must beEmpty
        ratesOf(result) === Seq(1L -> 1, 2L -> -1, 3L -> 0, 4L -> 1, 5L -> 0, 6L -> 0)
        (data(result, "roundImages") \ "totalCount").as[Int] === 6
        pageInfo(result)._2 === false
        val image = Image(1L, "File:Image1.jpg", Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Image1.jpg"),
          None, 640, 480, Some("80-361-0001"))
        val first = nodes(result).head
        (first \ "thumbnail" \ "url").as[String] === Global.resizeTo(image, Global.gallerySizeX, Global.gallerySizeY)
        (first \ "thumbnail" \ "srcset").as[String] === Global.srcSet(image, Global.gallerySizeX, Global.gallerySizeY)
        (first \ "thumbnail" \ "url").as[String] must startWith("/wikipedia/commons/thumb/a/ab/Image1.jpg/")
        (first \ "row" \ "url").as[String] === Global.resizeTo(image, Global.gallerySizeY)
        (first \ "largeImage" \ "src").as[String] === Global.largeImage(image).src
        (first \ "vote" \ "voted").as[Boolean] === true
        // a filter of the round's tabs; another kind's is refused
        pageIdsOf(images(app, jurorAEmail, f.binary, filter = Some("UNRATED"))) === Seq(3L, 5L, 6L)
        pageIdsOf(images(app, jurorAEmail, f.binary, filter = Some("SELECTED"))) === Seq(1L, 4L)
        messages(images(app, jurorAEmail, f.binary, filter = Some("RATED"))) === Seq("filter_not_in_round")
        pageIdsOf(images(app, jurorAEmail, f.rated, filter = Some("RATED"), order = Some("RATE"))) === Seq(1L, 2L, 3L)
      }
    }

    "page by keyset in every order and tab: stable, ties by page id, no file twice or missing, the last page last" in {
      testDbApp { app =>
        setUp()
        val round = pagingRound()
        val a = userId(jurorAEmail)
        val orders = Seq("FILE" -> ListOrder.FileOrder, "RATE" -> ListOrder.ByRate, "LATEST_VOTE" -> ListOrder.LatestVote,
          "OLDEST_VOTE" -> ListOrder.OldestVote)
        val filters = Seq("ALL" -> VoteFilter.All, "UNRATED" -> VoteFilter.Unrated, "RATED" -> VoteFilter.Rated)
        // file order on its own: no monument id first, then by monument id, ties by page id
        val byFile = (101L to 113L).sortBy(i => (if (i == 104 || i == 109) "" else f"80-361-${(i - 101) / 3}%04d", i))
        wholeList(round, a, VoteFilter.All, ListOrder.FileOrder) === byFile
        forall(for (o <- orders; flt <- filters; size <- Seq(1, 2, 5)) yield (o, flt, size)) {
          case ((orderName, order), (filterName, filter), size) =>
            val pages = allPages(app, jurorAEmail, round.getId, size, Some(filterName), Some(orderName))
            val all = pages.flatten
            val expected = wholeList(round, a, filter, order)
            (all === expected) and (all.distinct === all) and (pages.dropRight(1).forall(_.size == size) must beTrue)
        }
      }
    }

    "continue after a page's last file where it was when the page was read, whatever votes came after" in {
      testDbApp { app =>
        setUp()
        val round = pagingRound()
        val a = userId(jurorAEmail)
        // by rate: the page's last file voted down after the page was read
        val before = wholeList(round, a, VoteFilter.All, ListOrder.ByRate)
        val page1 = images(app, jurorAEmail, round.getId, order = Some("RATE"), first = Some(4), query = idsQuery)
        pageIdsOf(page1) === before.take(4)
        DB.autoCommit(implicit s =>
          sql"UPDATE vote SET rate = -1 WHERE round_id = ${round.getId} AND page_id = ${before(3)}".update.apply())
        // the next pages up to the last: each with a page of files and a new cursor while there are more, at most
        // one request per file of the round and one more (a cursor that doesn't advance fails, never loops)
        def rest(after: Option[String], order: String, filter: Option[String] = None): Seq[Long] = {
          val bound = before.size + 1
          // fails the example here, ending the loop
          def stop(why: String): Nothing = throw org.specs2.execute.FailureException(org.specs2.execute.Failure(why))
          var cursor = after
          val ids = Seq.newBuilder[Long]
          var more = true
          var requests = 0
          while (more) {
            requests += 1
            if (requests > bound) stop(s"more than $bound requests: the cursor never reached the last page")
            val r = images(app, jurorAEmail, round.getId, order = Some(order), filter = filter, first = Some(3), after = cursor,
              query = idsQuery)
            val page = pageIdsOf(r)
            ids ++= page
            val (end, hasNext) = pageInfo(r)
            if (hasNext && (page.isEmpty || end.isEmpty || end == cursor))
              stop(s"a page with more after it gave no file or no new cursor (after $cursor: $page, $end)")
            cursor = end
            more = hasNext
          }
          ids.result()
        }
        // nothing skipped; the moved file not on the next page, but again at its new place further on (a client
        // drops a page id it already shows, as the web gallery's script does): the list as it is now, less the
        // page's files before it
        val moved = before(3)
        val now = wholeList(round, a, VoteFilter.All, ListOrder.ByRate)
        val continued = rest(pageInfo(page1)._1, "RATE")
        now.indexOf(moved) must be_>(3)
        continued === now.filterNot(before.take(3).contains)
        continued.take(3) must not(contain(moved))
        continued.count(_ == moved) === 1

        // the unrated tab: its first page voted, the next page starts at the next unrated file
        val unrated = wholeList(round, a, VoteFilter.Unrated, ListOrder.FileOrder)
        val u1 = images(app, jurorAEmail, round.getId, filter = Some("UNRATED"), first = Some(2), query = idsQuery)
        pageIdsOf(u1) === unrated.take(2)
        DB.autoCommit(implicit s =>
          sql"UPDATE vote SET rate = 4 WHERE round_id = ${round.getId} AND page_id IN (${unrated.take(2)})".update.apply())
        rest(pageInfo(u1)._1, "FILE", Some("UNRATED")) === unrated.drop(2)
      }
    }

    "refuse a cursor that is no cursor, or one of another list, and clamp the page size to 1..200" in {
      testDbApp { app =>
        val f = setUp()
        val round = pagingRound()
        messages(images(app, jurorAEmail, round.getId, after = Some("garbage"))) === Seq("bad_cursor")
        codes(images(app, jurorAEmail, round.getId, after = Some("garbage"))) === Seq("BAD_INPUT")
        val fileCursor = pageInfo(images(app, jurorAEmail, round.getId, first = Some(2), query = idsQuery))._1.get
        messages(images(app, jurorAEmail, round.getId, order = Some("RATE"), after = Some(fileCursor))) === Seq("bad_cursor")
        messages(images(app, jurorAEmail, round.getId, filter = Some("UNRATED"), after = Some(fileCursor))) === Seq("bad_cursor")
        // another round's list of the same juror, order and tab
        messages(images(app, jurorAEmail, f.rated, after = Some(fileCursor))) === Seq("bad_cursor")
        codes(images(app, jurorAEmail, f.rated, after = Some(fileCursor))) === Seq("BAD_INPUT")
        // another juror's list
        round.addUsers(Seq(RoundUser(round.getId, userId(jurorBEmail), "jury", active = true)))
        messages(images(app, organizerEmail, round.getId, user = Some(userId(jurorBEmail)), after = Some(fileCursor))) ===
          Seq("bad_cursor")
        // a cursor of the juror's list, its values tampered: another slice of the same list, never an error
        val tampered = {
          val json = Json.parse(java.util.Base64.getUrlDecoder.decode(fileCursor)).as[JsObject]
          val k = (json \ "k").as[JsArray].value.toSeq
          java.util.Base64.getUrlEncoder.withoutPadding.encodeToString(
            Json.stringify(json ++ Json.obj("k" -> (Seq(JsString("80-361-0002' OR 1=1 --")) ++ k.tail))).getBytes("UTF-8"))
        }
        val slice = images(app, jurorAEmail, round.getId, after = Some(tampered), query = idsQuery)
        errors(slice) must beEmpty
        val list = wholeList(round, userId(jurorAEmail), VoteFilter.All, ListOrder.FileOrder).toSet
        pageIdsOf(slice).filterNot(list) must beEmpty
        pageIdsOf(images(app, jurorAEmail, round.getId, first = Some(0), query = idsQuery)) must haveSize(1)
        // more files than the cap: a page asked of 1000 is 200, with more after it, the rest on the next
        val juror = User.findByEmail(jurorAEmail).head
        val large = Round.create(Round(None, 6, Some("large"), 10, rates = Round.ratesById(5), active = true))
        large.addUsers(Seq(RoundUser(large.getId, juror.getId, "jury", active = true)))
        val total = RoundQueries.MaxImagePage + 5
        val files = (1 to total).map(i => Image(5000L + i, s"File:L$i.jpg", None, None, 800, 600, Some(f"80-361-$i%04d")))
        imageDao.batchInsert(files)
        selectionDao.batchInsert(files.map(image => Vote(image, juror, large, 0)))
        val big = images(app, jurorAEmail, large.getId, first = Some(1000), query = idsQuery)
        val next = images(app, jurorAEmail, large.getId, first = Some(1000), after = pageInfo(big)._1, query = idsQuery)
        (RoundQueries.MaxImagePage === 200) and (GraphQLSchema.MaxPageSize === 200) and
          ((pageIdsOf(big).size, pageInfo(big)._2) === ((200, true))) and
          ((pageIdsOf(next).size, pageInfo(next)._2) === ((5, false))) and
          ((pageIdsOf(big) ++ pageIdsOf(next)).distinct.size === total)
      }
    }

    "read a constant number of statements a page, whatever its size (no statement per file)" in {
      testDbApp { app =>
        setUp()
        val a = User.findByEmail(jurorAEmail).head
        val round = Round.create(Round(None, 5, Some("big"), 10, rates = Round.ratesById(5), active = true))
        round.addUsers(Seq(RoundUser(round.getId, a.getId, "jury", active = true)))
        val files = (1001 to 1080).map(i => Image(i.toLong, s"File:B$i.jpg",
          Some(s"https://upload.wikimedia.org/wikipedia/commons/b/bc/B$i.jpg"), None, 800, 600, Some(f"80-361-${i % 7}%04d")))
        imageDao.batchInsert(files)
        selectionDao.batchInsert(files.map(image => Vote(image, a, round, (image.pageId % 6).toInt)))
        val counter = new java.util.concurrent.atomic.AtomicInteger
        def statements(first: Int, after: Option[String] = None): (Int, Future[Result]) = {
          val previous = GlobalSettings.queryCompletionListener
          GlobalSettings.queryCompletionListener = (sql, params, ms) => { counter.incrementAndGet(); previous(sql, params, ms) }
          counter.set(0)
          try {
            val result = images(app, jurorAEmail, round.getId, order = Some("RATE"), first = Some(first), after = after)
            errors(result) must beEmpty
            (counter.get, result)
          } finally GlobalSettings.queryCompletionListener = previous
        }
        statements(5) // warm-up: caches of the caller's lookups
        val (small, smallResult) = statements(5)
        val (large, largeResult) = statements(50)
        val (next, _) = statements(50, pageInfo(smallResult)._1)
        println(s"roundImages statements per request (totalCount and the checks included): first 5: $small, " +
          s"first 50: $large, first 50 after a cursor: $next")
        nodes(largeResult) must haveSize(50)
        // 10 is a deliberate budget (the caller's lookup, the checks, the page, the count): raise it only on
        // purpose, with the reason in the review
        small must beBetween(1, 10)
        large === small
        next === small
      }
    }
  }

  "roundImages and image, who sees what" should {

    "refuse a juror another juror's list or all jurors' (FORBIDDEN, logged), and a file not in their list (NOT_FOUND)" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("roundImages", "FORBIDDEN")(images(app, jurorAEmail, f.binary, user = Some(userId(jurorBEmail))))
        deniedAs("roundImages", "FORBIDDEN")(images(app, jurorAEmail, f.binary, user = Some(0)))
        val (r, lines) = withLog("security.authz")(images(app, jurorAEmail, f.binary, user = Some(userId(jurorBEmail))))
        lines.head must contain("reason=other_users_votes")
        val imageQuery = "query I($r: ID!, $p: ID!, $u: ID) { image(roundId: $r, pageId: $p, userId: $u) { pageId vote { rate } } }"
        def image(as: String, page: Long, user: Option[Long] = None) =
          gql(app, imageQuery, as, Json.obj("r" -> f.binary.toString, "p" -> page.toString) ++
            user.fold(Json.obj())(u => Json.obj("u" -> u.toString)))
        (data(image(jurorAEmail, 2), "image") \ "vote" \ "rate").as[Int] === -1
        (data(image(organizerEmail, 2, Some(userId(jurorBEmail))), "image") \ "vote" \ "rate").as[Int] === 1
        deniedAs("image", "FORBIDDEN")(image(jurorAEmail, 2, Some(userId(jurorBEmail))))
        missingAs("image")(image(jurorAEmail, 999))
      }
    }

    "show the organizer, the admin and root a juror's list with that juror's votes; not a non-juror's, nor all jurors' yet" in {
      testDbApp { app =>
        val f = setUp()
        forall(Seq(organizerEmail, adminEmail, rootEmail)) { email =>
          ratesOf(images(app, email, f.binary, user = Some(userId(jurorBEmail)))) ===
            Seq(1L -> 1, 2L -> 1, 3L -> -1, 4L -> 0, 5L -> 0, 6L -> 0)
        }
        // their own list: they are not its jurors
        val own = images(app, organizerEmail, f.binary)
        (pageIdsOf(own), (data(own, "roundImages") \ "totalCount").as[Int]) === ((Nil, 0))
        val (notJuror, lines) = withLog("security.authz")(images(app, organizerEmail, f.binary, user = Some(userId(adminEmail))))
        (codes(notJuror), messages(notJuror), lines) === ((Seq("NOT_FOUND"), Seq("juror_not_found"), Nil))
        messages(images(app, organizerEmail, f.binary, user = Some(0))) === Seq("all_jurors_not_supported")
        ratesOf(images(app, rootEmail, f.other20, user = Some(userId(outsiderEmail)))).map(_._2) === Seq.fill(6)(0)
      }
    }

    "apply the conflict-of-interest rule: an organizer who is a juror of the open round gets their own votes only" in {
      testDbApp { app =>
        val f = setUp()
        val organizer = User.findByEmail(organizerEmail).head
        Round.findById(f.binary).get.addUsers(Seq(RoundUser(f.binary, organizer.getId, "organizer", active = true)))
        selectionDao.batchInsert(Seq(Vote(1, organizer.getId, f.binary, 1)))
        ratesOf(images(app, organizerEmail, f.binary)) === Seq(1L -> 1)
        deniedAs("roundImages", "FORBIDDEN")(images(app, organizerEmail, f.binary, user = Some(userId(jurorAEmail))))
        DB.autoCommit(implicit s => sql"UPDATE round SET active = FALSE WHERE id = ${f.binary}".update.apply())
        ratesOf(images(app, organizerEmail, f.binary, user = Some(userId(jurorAEmail)))).size === 6
      }
    }

    "answer a stopped juror and a juror of a round being created with CONFLICT round_closed and the state, not a denial" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s =>
          sql"UPDATE round_user SET active = FALSE WHERE round_id = ${f.binary} AND user_id = ${userId(jurorBEmail)}".update.apply())
        def state(as: String, round: Long) = {
          val ((r, authz), access) = withLog("round.access")(withLog("security.authz")(images(app, as, round)))
          (data(r, "roundImages") === JsNull) and (codes(r) === Seq("CONFLICT")) and (messages(r) === Seq("round_closed")) and
            (authz must beEmpty) and (access must haveSize(1)) and
            ((errors(r).head \ "extensions" \ "reason").as[String] must beOneOf("juror_stopped", "round_distributing"))
        }
        state(jurorBEmail, f.binary)
        (errors(images(app, jurorBEmail, f.binary)).head \ "extensions" \ "reason").as[String] === "juror_stopped"
        state(jurorAEmail, f.distributing)
        (errors(images(app, jurorAEmail, f.distributing)).head \ "extensions" \ "reason").as[String] === "round_distributing"
      }
    }

    "answer another contest's round, an inactive member's and a round the juror doesn't judge as NOT_FOUND, logged" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("roundImages", "NOT_FOUND")(images(app, outsiderEmail, f.binary))
        deniedAs("roundImages", "NOT_FOUND")(images(app, adminEmail, f.other20, user = Some(userId(outsiderEmail))))
        deniedAs("roundImages", "NOT_FOUND")(images(app, inactiveEmail, f.binary))
        deniedAs("roundImages", "NOT_FOUND")(images(app, jurorBEmail, f.rated))
        missingAs("roundImages")(images(app, jurorAEmail, 999999))
      }
    }
  }

  "the round queries' further cases" should {

    "answer a retired contest's round as NOT_FOUND to its admin (logged), not to root" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s => sql"UPDATE contest_jury SET retired_at = NOW(3) WHERE id = 10".update.apply())
        val a = Some(userId(jurorAEmail))
        deniedAs("round", "NOT_FOUND")(gql(app, roundQuery(f.binary), adminEmail))
        deniedAs("roundStat", "NOT_FOUND")(stat(app, adminEmail, f.binary))
        deniedAs("roundImages", "NOT_FOUND")(images(app, adminEmail, f.binary, user = a))
        deniedAs("roundImages", "NOT_FOUND")(images(app, jurorAEmail, f.binary))
        (data(gql(app, roundQuery(f.binary), rootEmail), "round") \ "id").as[String] === f.binary.toString
        (data(stat(app, rootEmail, f.binary), "roundStat") \ "totalFiles").as[Int] === 6
        ratesOf(images(app, rootEmail, f.binary, user = a)).size === 6
      }
    }

    "answer a deleted round as a missing one to round(id), roundImages and image" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s => sql"UPDATE round SET deleted_at = NOW() WHERE id = ${f.binary}".update.apply())
        missingAs("round")(gql(app, roundQuery(f.binary), adminEmail))
        missingAs("roundImages")(images(app, jurorAEmail, f.binary))
        missingAs("roundImages")(images(app, adminEmail, f.binary, user = Some(userId(jurorAEmail))))
        missingAs("image")(gql(app, imageQuery, jurorAEmail, Json.obj("r" -> f.binary.toString, "p" -> "1")))
      }
    }

    "tell a juror of a stopped round round_stopped, and of a round paused by a job round_paused, from both queries" in {
      testDbApp { app =>
        val f = setUp()
        DB.autoCommit(implicit s => sql"UPDATE round SET active = FALSE WHERE id = ${f.binary}".update.apply())
        def reasons(result: Future[Result]) =
          (codes(result), messages(result), errors(result).map(e => (e \ "extensions" \ "reason").as[String]))
        reasons(images(app, jurorAEmail, f.binary)) === ((Seq("CONFLICT"), Seq("round_closed"), Seq("round_stopped")))
        reasons(gql(app, imageQuery, jurorAEmail, Json.obj("r" -> f.binary.toString, "p" -> "1"))) ===
          ((Seq("CONFLICT"), Seq("round_closed"), Seq("round_stopped")))
        // the organisers see a stopped round's files
        ratesOf(images(app, organizerEmail, f.binary, user = Some(userId(jurorAEmail)))).size === 6

        // the rated round paused by a rebalance: frozen while open, the job holding its key
        val rated = Round.findById(f.rated).get
        DB.localTx { s => Round.freeze(Seq(f.rated))(s) }
        services.jobs.JobJdbc.insert(services.jobs.NewJob(services.distribution.RoundRebalanceHandler.Kind,
          Some(rated.contestId), Some("round"), rated.id,
          Some(services.distribution.RoundDistributionHandler.dedupeKey(f.rated)),
          params = Json.obj("roundId" -> f.rated, "add" -> userId(jurorBEmail)),
          state = Json.obj("frozen" -> Seq(f.rated))), owner = None)(AutoSession)
        reasons(images(app, jurorAEmail, f.rated)) === ((Seq("CONFLICT"), Seq("round_closed"), Seq("round_paused")))
        reasons(gql(app, imageQuery, jurorAEmail, Json.obj("r" -> f.rated.toString, "p" -> "1"))) ===
          ((Seq("CONFLICT"), Seq("round_closed"), Seq("round_paused")))
        ratesOf(images(app, organizerEmail, f.rated, user = Some(userId(jurorAEmail)))).size === 6
      }
    }

    "apply the conflict-of-interest rule to root as a juror of the open round, and to image" in {
      testDbApp { app =>
        val f = setUp()
        val root = User.findByEmail(rootEmail).head
        val organizer = User.findByEmail(organizerEmail).head
        Round.findById(f.binary).get.addUsers(Seq(RoundUser(f.binary, root.getId, "jury", active = true),
          RoundUser(f.binary, organizer.getId, "organizer", active = true)))
        selectionDao.batchInsert(Seq(Vote(1, root.getId, f.binary, -1), Vote(1, organizer.getId, f.binary, 1)))
        val a = userId(jurorAEmail)
        ratesOf(images(app, rootEmail, f.binary)) === Seq(1L -> -1)
        deniedAs("roundImages", "FORBIDDEN")(images(app, rootEmail, f.binary, user = Some(a)))
        def image(as: String, user: Option[Long]) = gql(app, imageQuery, as,
          Json.obj("r" -> f.binary.toString, "p" -> "1") ++ user.fold(Json.obj())(u => Json.obj("u" -> u.toString)))
        (data(image(organizerEmail, None), "image") \ "vote" \ "rate").as[Int] === 1
        deniedAs("image", "FORBIDDEN")(image(organizerEmail, Some(a)))
        deniedAs("image", "FORBIDDEN")(image(rootEmail, Some(a)))
        // the round's statistics: their own row's votes only
        (data(stat(app, rootEmail, f.binary), "roundStat") \ "selectedFiles").toOption must beSome(JsNull: JsValue)
        // a juror stopped on the round: image tells the state too
        DB.autoCommit(implicit s =>
          sql"UPDATE round_user SET active = FALSE WHERE round_id = ${f.binary} AND user_id = ${userId(jurorBEmail)}".update.apply())
        val stopped = image(jurorBEmail, None)
        (codes(stopped), (errors(stopped).head \ "extensions" \ "reason").as[String]) === (("CONFLICT" :: Nil, "juror_stopped"))
      }
    }

    "answer each round's checks once a request, however many aliases ask" in {
      testDbApp { app =>
        val f = setUp()
        val query = (1 to 5).map(i => s"s$i: roundStat(roundId: ${f.binary}) { totalFiles }").mkString("{ ", " ", " }")
        val (result, lines) = withLog("security.authz")(gql(app, query, jurorAEmail))
        // one denial line for the five (the same error, its reference), no repeat count: one check
        codes(result) === Seq.fill(5)("FORBIDDEN")
        errors(result).map(e => (e \ "extensions" \ "reference").as[String]).distinct must haveSize(1)
        lines must haveSize(1)
        val many = (1 to 30).map(i => s"s$i: roundStat(roundId: ${f.binary}) { totalFiles }").mkString("{ ", " ", " }")
        messages(gql(app, many, adminEmail)) === Seq("query_too_complex")
      }
    }
  }

  "a series admin's roundImages" should {

    "follow the derived Admin role from a session, own votes only in the open round they judge; nothing from a token" in {
      testDbApp { app =>
        val root = account(rootEmail, root = true)
        val thisYear = java.time.Year.now().getValue
        val seriesId = services.series.ContestSeries.createSeriesTx("Nepal", Some("wlm"), Some("Nepal"), None, root.getId)
          .toOption.get
        val now = db.scalikejdbc.ContestJuryJdbc.create(None, "Wiki Loves Monuments", thisYear, "Nepal").getId
        services.series.ContestSeries.setSeries(now, Some(seriesId), root.getId, None)
        val seriesAdmin = account("seriesadmin@example.com")
        UserContestJdbc.createMembership(seriesAdmin.getId, now, "organizer")
        services.series.SeriesAdmins.grant(root, seriesId, seriesAdmin.getId, None, None).toOption.get
        UserContestJdbc.delete(seriesAdmin.getId, now)
        val juror = account(jurorAEmail)
        UserContestJdbc.createMembership(juror.getId, now, User.JURY_ROLE)
        def round(number: Long, jurors: Seq[User]) = {
          val r = Round.create(Round(None, number, Some(s"round $number"), now, active = true))
          r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
          r
        }
        val free = round(1, Seq(juror))
        val coi = round(2, Seq(seriesAdmin, juror))
        val files = (1 to 3).map(i => Image(i.toLong, s"File:S$i.jpg", None, None, 640, 480, None))
        imageDao.batchInsert(files)
        selectionDao.batchInsert(files.flatMap(i => Seq(Vote(i, juror, free, 1), Vote(i, juror, coi, -1),
          Vote(i, seriesAdmin, coi, 0))))
        val session = Some(Seq(Secured.UserName -> seriesAdmin.email, Secured.CurrentContestId -> now.toString))
        ratesOf(images(app, "", free.getId, user = Some(juror.getId), session = session)).map(_._2) === Seq(1, 1, 1)
        ratesOf(images(app, "", coi.getId, session = session)).map(_._2) === Seq(0, 0, 0)
        deniedAs("roundImages", "FORBIDDEN")(images(app, "", coi.getId, user = Some(juror.getId), session = session))
        val token = Seq(AUTHORIZATION -> s"Bearer ${ApiTokenService.create(seriesAdmin.getId, "cli", Some(90))}")
        deniedAs("roundImages", "NOT_FOUND")(post(app, imagesQuery, headers = token,
          variables = Json.obj("round" -> free.getId.toString, "user" -> juror.getId.toString)))
      }
    }
  }
}
