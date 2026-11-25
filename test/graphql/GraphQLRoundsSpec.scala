package graphql

import controllers.Secured
import db.scalikejdbc.{PlayTestDb, Round, RoundUser, User, UserContestJdbc}
import org.intracer.wmua.{Image, Vote}
import play.api.Application
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

/** GraphQL's round queries through the router, as served (plan §6, slices 2 and 3): `round(id)`,
  * `roundStat(roundId)`, and their negative authorization tests (plan §5): another contest's round, an
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
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    log.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(e => s"${e.getLevel} ${e.getFormattedMessage}"))
    } finally log.detachAppender(appender)
  }

  /** `field` is null with one error of `code`, whose reference is on exactly one `security.authz` line. */
  private def deniedAs(field: String, code: String)(result: => Future[Result]) = {
    val (r, lines) = withLog("security.authz")(result)
    (data(r, field) === JsNull) and (codes(r) === Seq(code)) and
      (lines.filter(_.contains(s"ref=${reference(r)}")) must haveSize(1)) and
      (lines.head must startWith("WARN"))
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

    "refuse a juror with FORBIDDEN, logged with the error's reference, as the page's 403" in {
      testDbApp { app =>
        val f = setUp()
        deniedAs("roundStat", "FORBIDDEN")(stat(app, jurorAEmail, f.binary))
        deniedAs("roundStat", "FORBIDDEN")(stat(app, jurorBEmail, f.rated))
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
}
