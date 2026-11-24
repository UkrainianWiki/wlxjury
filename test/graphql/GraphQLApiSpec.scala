package graphql

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import controllers.Secured
import db.scalikejdbc.{ApiTokenScope, PlayTestDb, Round, RoundUser, User, UserContestJdbc}
import play.api.Application
import play.api.libs.json.{JsArray, JsNull, JsObject, JsValue, Json}
import play.api.mvc.{Cookie, Result}
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise, TimeoutException}
import scala.jdk.CollectionConverters._

/** `POST /api/graphql` ([[GraphQLController]]) through the router and the filters, as served: who
  * may call it (session, token, neither; the token deciding over a cookie; tokens queries only), what
  * each role sees of `me`, `contests` and a contest's rounds, the denials logged on `security.authz`
  * with the reference the error carries, the limits, batching, the methods, CSRF and the `Host` check
  * (plan §5, the negative tests that apply to slice 1).
  */
class GraphQLApiSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val rootEmail = "root@example.com"
  private val adminEmail = "admin@example.com" // admin of 10, juror of 20
  private val organizerEmail = "organizer@example.com" // organizer of 10
  private val jurorEmail = "juror@example.com" // juror of 10
  private val outsiderEmail = "outsider@example.com" // juror of 20 only
  private val inactiveEmail = "inactive@example.com" // "no" role in 10
  private val retiredEmail = "retired@example.com" // admin of 30, a retired contest

  private case class Rounds(open10: Long, distributing10: Long, others10: Long, mine20: Long, others20: Long)

  /** Contests 10, 20 and 30 (retired); the accounts above; rounds: in 10 one the juror judges, one
    * being distributed that the juror is assigned to, one of another juror; in 20 one the admin
    * judges and one of nobody.
    */
  private def setUp(): Rounds = {
    createContests(10, 20, 30)
    DB.autoCommit(implicit s => sql"UPDATE contest_jury SET retired_at = NOW(3) WHERE id = 30".update.apply())
    def account(email: String, root: Boolean = false) =
      User.create(User(email, email, password = Some(services.PasswordHasher.default.hash("secret")), isRoot = root))
    account(rootEmail, root = true)
    val admin = account(adminEmail)
    UserContestJdbc.createMembership(admin.getId, 10, User.ADMIN_ROLE)
    UserContestJdbc.createMembership(admin.getId, 20, User.JURY_ROLE)
    UserContestJdbc.createMembership(account(organizerEmail).getId, 10, "organizer")
    val juror = account(jurorEmail)
    UserContestJdbc.createMembership(juror.getId, 10, User.JURY_ROLE)
    val outsider = account(outsiderEmail)
    UserContestJdbc.createMembership(outsider.getId, 20, User.JURY_ROLE)
    UserContestJdbc.createMembership(account(inactiveEmail).getId, 10, User.NO_ROLE)
    UserContestJdbc.createMembership(account(retiredEmail).getId, 30, User.ADMIN_ROLE)

    def round(contest: Long, number: Long, jurors: Seq[User], distributing: Boolean = false) = {
      val r = Round.create(Round(None, number, Some(s"round $number of $contest"), contest, active = !distributing,
        distributing = distributing))
      r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
      if (distributing) DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 1 WHERE id = ${r.getId}".update.apply())
      r.getId
    }
    Rounds(
      open10 = round(10, 1, Seq(juror)),
      distributing10 = round(10, 2, Seq(juror), distributing = true),
      others10 = round(10, 3, Nil),
      mine20 = round(20, 1, Seq(admin)),
      others20 = round(20, 2, Seq(outsider))
    )
  }

  private def userId(email: String): Long = User.findByEmail(email).head.getId

  private def tokenOf(email: String, scope: ApiTokenScope = ApiTokenScope.Full): String =
    ApiTokenService.create(userId(email), "cli", Some(90), scope = scope)

  private def bearer(token: String): (String, String) = AUTHORIZATION -> s"Bearer $token"

  private def signedIn(email: String, contest: Long = 10): Seq[(String, String)] =
    Seq(Secured.UserName -> email, Secured.CurrentContestId -> contest.toString)

  /** A POST of `body` (JSON) to `/api/graphql`, with the headers, session and cookies. */
  private def postJson(app: Application, body: JsValue, headers: Seq[(String, String)] = Nil,
                       session: Seq[(String, String)] = Nil, cookies: Seq[Cookie] = Nil): Future[Result] =
    route(app, FakeRequest(POST, "/api/graphql").withHeaders(headers: _*).withSession(session: _*)
      .withCookies(cookies: _*).withJsonBody(body)).get

  private def gql(app: Application, query: String, as: Option[String] = None, headers: Seq[(String, String)] = Nil,
                  variables: JsObject = Json.obj()): Future[Result] =
    postJson(app, Json.obj("query" -> query, "variables" -> variables), headers, as.toSeq.flatMap(signedIn(_)))

  private def json(result: Future[Result]): JsValue = contentAsJson(result)

  private def errors(result: Future[Result]): Seq[JsValue] = (json(result) \ "errors").asOpt[JsArray].fold(Seq.empty[JsValue])(_.value.toSeq)

  private def codes(result: Future[Result]): Seq[String] = errors(result).map(e => (e \ "extensions" \ "code").as[String])

  private def messages(result: Future[Result]): Seq[String] = errors(result).map(e => (e \ "message").as[String])

  private def done(result: Future[Result]): Future[Result] = {
    Await.ready(result, 30.seconds)
    result
  }

  private def logger(name: String) =
    org.slf4j.LoggerFactory.getLogger(name).asInstanceOf[ch.qos.logback.classic.Logger]

  /** `f`'s result and the `security.authz` lines logged meanwhile. */
  private def withAuthzLog[T](f: => T): (T, Seq[String]) = {
    val authz = logger("security.authz")
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    authz.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(e => s"${e.getLevel} ${e.getFormattedMessage}"))
    } finally authz.detachAppender(appender)
  }

  private def ids(values: JsValue): Seq[Long] = values.as[Seq[JsObject]].map(o => (o \ "id").as[String].toLong)

  private def contestIds(result: Future[Result]): Seq[Long] = ids((json(result) \ "data" \ "contests" \ "items").get)

  private def roundIds(result: Future[Result], alias: String = "contest"): Seq[Long] =
    ids((json(result) \ "data" \ alias \ "rounds").get)

  private val meQuery = "{ me { id fullname email isRoot currentContestId authMethod } }"

  "the endpoint without credentials" should {
    "answer 401 with WWW-Authenticate and the REST API's body, also to introspection" in {
      testDbApp { app =>
        setUp()
        forall(Seq(meQuery, "{ __schema { queryType { name } } }", "{ __type(name: \"Contest\") { name } }")) { query =>
          val result = gql(app, query)
          status(result) === UNAUTHORIZED
          header(WWW_AUTHENTICATE, result) === Some("Bearer")
          (json(result) \ "error").as[String] === "unauthenticated"
          codes(result) === Seq("UNAUTHENTICATED")
          (json(result) \ "data").toOption must beNone
        }
      }
    }

    "ignore the old branch's X-Authenticated-User header and a forged JWT; refuse revoked and unknown tokens" in {
      testDbApp { app =>
        setUp()
        status(gql(app, meQuery, headers = Seq("X-Authenticated-User" -> rootEmail))) === UNAUTHORIZED
        val jwt = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJyb290QGV4YW1wbGUuY29tIn0.c2lnbmF0dXJl"
        status(gql(app, meQuery, headers = Seq(bearer(jwt)))) === UNAUTHORIZED
        status(gql(app, meQuery, headers = Seq(bearer("wlxj_" + "A" * 43)))) === UNAUTHORIZED
        val token = tokenOf(rootEmail)
        db.scalikejdbc.ApiTokenJdbc.revoke(db.scalikejdbc.ApiTokenJdbc.findByHash(ApiTokenService.hash(token)).get.id,
          userId(rootEmail))
        status(gql(app, meQuery, headers = Seq(bearer(token)))) === UNAUTHORIZED
        val expired = tokenOf(jurorEmail)
        DB.autoCommit(implicit s => sql"UPDATE api_token SET expires_at = NOW(3) - INTERVAL 1 DAY WHERE user_id = ${userId(jurorEmail)}".update.apply())
        status(gql(app, meQuery, headers = Seq(bearer(expired)))) === UNAUTHORIZED
        // a refused token is refused even with a signed-in session: the header alone decides
        status(gql(app, meQuery, as = Some(rootEmail), headers = Seq(bearer(token)))) === UNAUTHORIZED
      }
    }
  }

  "the endpoint's methods and body" should {
    "answer 405 to GET and other methods, running nothing" in {
      testDbApp { app =>
        setUp()
        forall(Seq(GET, PUT, DELETE)) { method =>
          val result = route(app, FakeRequest(method, "/api/graphql?query=%7Bme%7Bid%7D%7D")
            .withSession(signedIn(rootEmail): _*)).get
          status(result) === METHOD_NOT_ALLOWED
          header("Allow", result) === Some("POST")
          (json(result) \ "data").toOption must beNone
        }
      }
    }

    "refuse a batch (an array body) with 400, and a document with two operations" in {
      testDbApp { app =>
        setUp()
        val batch = postJson(app, Json.arr(Json.obj("query" -> meQuery), Json.obj("query" -> meQuery)),
          session = signedIn(rootEmail))
        status(batch) === BAD_REQUEST
        (json(batch) \ "error").as[String] === "bad_request"
        messages(batch) === Seq("batch_not_supported")

        val two = postJson(app, Json.obj("query" -> "query A { me { id } } query B { me { isRoot } }",
          "operationName" -> "A"), session = signedIn(rootEmail))
        status(two) === OK
        messages(two) === Seq("one_operation_only")
        (json(two) \ "data").toOption must beNone
      }
    }

    "refuse another content type (415) and a body over the limit (413)" in {
      testDbApp { app =>
        setUp()
        val text = route(app, FakeRequest(POST, "/api/graphql").withSession(signedIn(rootEmail): _*)
          .withTextBody(Json.obj("query" -> meQuery).toString)).get
        status(text) === UNSUPPORTED_MEDIA_TYPE
        forall(Seq("application/graphql", "application/x-www-form-urlencoded", "multipart/form-data; boundary=x")) { ct =>
          val result = route(app, FakeRequest(POST, "/api/graphql").withSession(signedIn(rootEmail): _*)
            .withHeaders(CONTENT_TYPE -> ct).withBody(org.apache.pekko.util.ByteString(meQuery))).get
          status(result) === UNSUPPORTED_MEDIA_TYPE
          codes(result) === Seq("BAD_INPUT")
        }

        val oversized = postJson(app, Json.obj("query" -> ("{ me { id } }" + " " * 70000)), session = signedIn(rootEmail))
        status(oversized) === REQUEST_ENTITY_TOO_LARGE
        (json(oversized) \ "error").as[String] === "payload_too_large"
      }
    }
  }

  "me" should {
    "show a juror and an organizer themselves without their email" in {
      testDbApp { app =>
        setUp()
        forall(Seq(jurorEmail, organizerEmail)) { email =>
          val me = json(gql(app, meQuery, as = Some(email))) \ "data" \ "me"
          (me \ "id").as[String] === userId(email).toString
          (me \ "email").get === JsNull
          (me \ "isRoot").as[Boolean] must beFalse
          (me \ "currentContestId").as[String] === "10"
          (me \ "authMethod").as[String] === "SESSION"
        }
      }
    }

    "show root and a contest's Admin their email" in {
      testDbApp { app =>
        setUp()
        val root = json(gql(app, meQuery, as = Some(rootEmail))) \ "data" \ "me"
        (root \ "email").as[String] === rootEmail
        (root \ "isRoot").as[Boolean] must beTrue
        ((json(gql(app, meQuery, as = Some(adminEmail))) \ "data" \ "me") \ "email").as[String] === adminEmail
        // an Admin of a retired contest only: no
        ((json(gql(app, meQuery, as = Some(retiredEmail))) \ "data" \ "me") \ "email").get === JsNull
      }
    }
  }

  "contests" should {
    val query = "{ contests { totalCount offset limit items { id name year country retired viewerRoles } } }"

    "list each caller's contests: their roles' contests, none retired or with an inactive role; root's all" in {
      testDbApp { app =>
        setUp()
        contestIds(gql(app, query, as = Some(jurorEmail))) === Seq(10)
        contestIds(gql(app, query, as = Some(organizerEmail))) === Seq(10)
        contestIds(gql(app, query, as = Some(adminEmail))) === Seq(20, 10) // the latest year first
        contestIds(gql(app, query, as = Some(outsiderEmail))) === Seq(20)
        contestIds(gql(app, query, as = Some(inactiveEmail))) === Nil
        contestIds(gql(app, query, as = Some(retiredEmail))) === Nil
        val root = gql(app, query, as = Some(rootEmail))
        contestIds(root) === Seq(30, 20, 10)
        (json(root) \ "data" \ "contests" \ "items" \ 0 \ "retired").as[Boolean] must beTrue
        (json(root) \ "data" \ "contests" \ "items" \ 0 \ "viewerRoles").as[Seq[String]] === Nil

        val admin = json(gql(app, query, as = Some(adminEmail))) \ "data" \ "contests" \ "items"
        (admin \ 0 \ "viewerRoles").as[Seq[String]] === Seq("JURY")
        (admin \ 1 \ "viewerRoles").as[Seq[String]] === Seq("ADMIN")

        // root who is also a juror of 10 (their current contest): that role in 10 only
        UserContestJdbc.createMembership(userId(rootEmail), 10, User.JURY_ROLE)
        val rootItems = json(gql(app, query, as = Some(rootEmail))) \ "data" \ "contests" \ "items"
        (rootItems \ 1 \ "viewerRoles").as[Seq[String]] === Nil
        (rootItems \ 2 \ "viewerRoles").as[Seq[String]] === Seq("JURY")
      }
    }

    "page with offset and limit, a limit over 200 clamped" in {
      testDbApp { app =>
        setUp()
        val page = json(gql(app, "{ contests(offset: 1, limit: 1) { totalCount offset limit items { id } } }",
          as = Some(rootEmail))) \ "data" \ "contests"
        (page \ "totalCount").as[Int] === 3
        ids((page \ "items").get) === Seq(20)
        val clamped = json(gql(app, "{ contests(limit: 100000) { limit items { id } } }", as = Some(rootEmail)))
        (clamped \ "data" \ "contests" \ "limit").as[Int] === 200
      }
    }
  }

  "contest(id)" should {
    val query = "query C($id: ID!) { contest(id: $id) { id name rounds { id number title status kind inReview } } }"

    def ask(app: Application, email: String, id: String) = gql(app, query, as = Some(email), variables = Json.obj("id" -> id))

    "answer a contest the caller is not in as a missing one, logging the denial with the error's reference" in {
      testDbApp { app =>
        setUp()
        val cases = Seq(jurorEmail -> "20", outsiderEmail -> "10", inactiveEmail -> "10", retiredEmail -> "30")
        forall(cases) { case (email, id) =>
          val (result, lines) = withAuthzLog(done(ask(app, email, id)))
          status(result) === OK
          (json(result) \ "data" \ "contest").get === JsNull
          codes(result) === Seq("NOT_FOUND")
          messages(result) === Seq("not_found")
          val reference = (errors(result).head \ "extensions" \ "reference").as[String]
          reference must beMatching("[0-9a-f]{8}")
          lines.filter(_.contains(s"ref=$reference")) must haveSize(1)
          lines.find(_.contains(s"ref=$reference")).get must (startWith("WARN") and contain(s"contest:$id") and
            contain("auth=session"))
        }
      }
    }

    "answer a missing contest and an id that is no number the same way, with a reference, logging no denial" in {
      testDbApp { app =>
        setUp()
        forall(Seq("999", "abc", "-1")) { id =>
          val (result, lines) = withAuthzLog(done(ask(app, jurorEmail, id)))
          (json(result) \ "data" \ "contest").get === JsNull
          codes(result) === Seq("NOT_FOUND")
          (errors(result).head \ "extensions" \ "reference").as[String] must beMatching("[0-9a-f]{8}")
          lines must beEmpty
        }
      }
    }

    "show a juror only their rounds once distributed; the organizer, the admin and root all rounds" in {
      testDbApp { app =>
        setUp() match {
          case Rounds(open10, distributing10, others10, _, _) =>
            roundIds(ask(app, jurorEmail, "10")) === Seq(open10)
            roundIds(ask(app, organizerEmail, "10")) === Seq(open10, distributing10, others10)
            roundIds(ask(app, adminEmail, "10")) === Seq(open10, distributing10, others10)
            roundIds(ask(app, rootEmail, "10")) === Seq(open10, distributing10, others10)
            val retired = ask(app, rootEmail, "30")
            (json(retired) \ "data" \ "contest" \ "id").as[String] === "30"
        }
      }
    }

    "hide a deleted round from everyone, and keep listing a round to its juror whose own row is stopped" in {
      testDbApp { app =>
        val r = setUp()
        DB.autoCommit(implicit s => sql"UPDATE round SET deleted_at = NOW() WHERE id = ${r.others10}".update.apply())
        roundIds(ask(app, organizerEmail, "10")) === Seq(r.open10, r.distributing10)
        roundIds(ask(app, rootEmail, "10")) === Seq(r.open10, r.distributing10)
        // the gallery's round menu lists it too (User.roundsJuredIn: a round_user row, active or not)
        DB.autoCommit(implicit s =>
          sql"UPDATE round_user SET active = FALSE WHERE round_id = ${r.open10} AND user_id = ${userId(jurorEmail)}".update.apply())
        roundIds(ask(app, jurorEmail, "10")) === Seq(r.open10)
      }
    }

    "log one denial line per request, however many fields are denied, all carrying its reference" in {
      testDbApp { app =>
        setUp()
        val (result, lines) = withAuthzLog(done(gql(app,
          "{ a: contest(id: 20) { id } b: contest(id: 20) { id } c: contest(id: 30) { id } }", as = Some(jurorEmail))))
        codes(result) === Seq("NOT_FOUND", "NOT_FOUND", "NOT_FOUND")
        val references = errors(result).map(e => (e \ "extensions" \ "reference").as[String]).distinct
        references must haveSize(1)
        lines.filter(_.contains("authorization_denied ")) must haveSize(1)
        lines.filter(_.contains("authorization_denied_repeated")) === Seq(s"WARN authorization_denied_repeated ref=${references.head} " +
          "more=2 resource=\"api:graphql\" auth=session")
      }
    }

    "judge each contest in its own role, two contests in one request" in {
      testDbApp { app =>
        val r = setUp()
        val both = gql(app, "{ a: contest(id: 10) { rounds { id } } b: contest(id: 20) { rounds { id } } }",
          as = Some(adminEmail))
        roundIds(both, "a") === Seq(r.open10, r.distributing10, r.others10)
        roundIds(both, "b") === Seq(r.mine20)
      }
    }
  }

  "a personal API token" should {
    "run queries, as its owner" in {
      testDbApp { app =>
        setUp()
        val me = json(gql(app, meQuery, headers = Seq(bearer(tokenOf(jurorEmail))))) \ "data" \ "me"
        (me \ "id").as[String] === userId(jurorEmail).toString
        (me \ "authMethod").as[String] === "TOKEN"
        contestIds(gql(app, "{ contests { items { id } } }", headers = Seq(bearer(tokenOf(rootEmail))))) === Seq(30, 20, 10)
      }
    }

    "be refused a mutation or a subscription (403, logged), even root's" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        forall(Seq("mutation { me { id } }", "subscription { me { id } }", "mutation M { deleteContest(id: 10) }")) { query =>
          val (result, lines) = withAuthzLog(done(gql(app, query, headers = Seq(bearer(token)))))
          status(result) === FORBIDDEN
          (json(result) \ "error").as[String] === "forbidden"
          codes(result) === Seq("FORBIDDEN")
          val reference = (json(result) \ "reference").as[String]
          (errors(result).head \ "extensions" \ "reference").as[String] === reference
          lines.find(_.contains(s"ref=$reference")).get must (startWith("WARN") and contain("reason=token_query_only") and
            contain("auth=token") and contain("resource=\"api:graphql\""))
        }
      }
    }

    "decide over a session cookie: an admin's or root's session never adds to a juror's token" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(jurorEmail)
        val me = json(gql(app, meQuery, as = Some(rootEmail), headers = Seq(bearer(token)))) \ "data" \ "me"
        (me \ "isRoot").as[Boolean] must beFalse
        (me \ "id").as[String] === userId(jurorEmail).toString
        val mutation = gql(app, "mutation { me { id } }", as = Some(rootEmail), headers = Seq(bearer(tokenOf(rootEmail))))
        status(mutation) === FORBIDDEN
        contestIds(gql(app, "{ contests { items { id } } }", as = Some(rootEmail), headers = Seq(bearer(token)))) === Seq(10)
      }
    }

    "be refused when read-only and server-only, as on the REST API's writes" in {
      testDbApp { app =>
        setUp()
        val (result, lines) = withAuthzLog(done(gql(app, meQuery, headers = Seq(bearer(tokenOf(rootEmail, ApiTokenScope.LocalRead))))))
        status(result) === FORBIDDEN
        val reference = (json(result) \ "reference").as[String]
        lines.find(_.contains(s"ref=$reference")).get must contain("reason=token_read_only")
      }
    }
  }

  "a session's mutation" should {
    "pass the token rule and fail validation: the schema has no mutations yet" in {
      testDbApp { app =>
        setUp()
        val result = gql(app, "mutation { me { id } }", as = Some(rootEmail))
        status(result) === OK
        codes(result) === Seq("BAD_INPUT")
        messages(result) === Seq("invalid_query")
      }
    }
  }

  "introspection" should {
    "be open to a signed-in juror (the schema is not secret)" in {
      testDbApp { app =>
        setUp()
        val result = gql(app, RenderSchema.IntrospectionQuery, as = Some(jurorEmail))
        status(result) === OK
        errors(result) must beEmpty
        (json(result) \ "data" \ "__schema" \ "queryType" \ "name").as[String] === "Query"
      }
    }
  }

  "the limits" should {
    "refuse a deep query, an alias flood, deep introspection and an over-complex query before running them" in {
      testDbApp { app =>
        setUp()
        val deep = gql(app, "{ a { b { c { d { e { f { g { h { i } } } } } } } } }", as = Some(rootEmail))
        messages(deep) === Seq("query_too_deep")
        val aliases = gql(app, (1 to 31).map(i => s"a$i: me { id }").mkString("{ ", " ", " }"), as = Some(rootEmail))
        messages(aliases) === Seq("too_many_aliases")
        val introspection = gql(app, "{ __type(name: \"Query\") { fields { type { fields { type { fields { name } } } } } } }",
          as = Some(jurorEmail))
        messages(introspection) === Seq("introspection_too_deep")
        // 200 contests, each with 20 rounds of 5 fields: over the budget of 5000
        val complex = gql(app, "{ contests(limit: 200) { items { id rounds { id number title status kind } } } }",
          as = Some(rootEmail))
        messages(complex) === Seq("query_too_complex")
        codes(complex) === Seq("BAD_INPUT")
        (json(complex) \ "data").toOption must beNone
        // the same with a smaller page passes
        errors(gql(app, "{ contests(limit: 20) { items { id rounds { id number title status kind } } } }",
          as = Some(rootEmail))) must beEmpty
      }
    }

    "refuse a document nested deeper than the parser should see, the app still answering" in {
      testDbApp { app =>
        setUp()
        forall(Seq("{a" * 30000, "{ contest(id: " + "[" * 30000 + ") { id } }")) { query =>
          val result = gql(app, query, as = Some(jurorEmail))
          status(result) === OK
          messages(result) === Seq("query_too_deep")
        }
        status(gql(app, meQuery, as = Some(jurorEmail))) === OK
      }
    }

    "refuse an unused fragment bomb before validation" in {
      testDbApp { app =>
        setUp()
        val unused = (0 until 40).map(i => s"fragment F$i on Query { ...F${i + 1} ...F${i + 1} }").mkString(" ") +
          " fragment F40 on Query { me { id } } query { me { id } }"
        messages(gql(app, unused, as = Some(jurorEmail))) === Seq("unused_fragment")
      }
    }

    "refuse a fragment bomb of spreads only, and count a limit given as a variable" in {
      testDbApp { app =>
        setUp()
        val bomb = (0 until 40).map(i => s"fragment F$i on Query { ...F${i + 1} ...F${i + 1} }").mkString(" ") +
          " fragment F40 on Query { me { id } } query { ...F0 }"
        messages(gql(app, bomb, as = Some(jurorEmail))) === Seq("query_too_large")
        val byVariable = gql(app, "query Q($n: Int) { contests(limit: $n) { items { id rounds { id number title status kind } } } }",
          as = Some(rootEmail), variables = Json.obj("n" -> 200))
        messages(byVariable) === Seq("query_too_complex")
      }
    }

    "answer a variable of the wrong type and an unknown operation name with a key, never their text" in {
      testDbApp { app =>
        setUp()
        val coercion = gql(app, "query Q($id: ID!) { contest(id: $id) { id } }", as = Some(jurorEmail),
          variables = Json.obj("id" -> Json.obj("x" -> "secret-text")))
        codes(coercion) === Seq("BAD_INPUT")
        contentAsString(coercion) must not(contain("secret-text"))
        val unknownOperation = postJson(app, Json.obj("query" -> "query Q { me { id } }", "operationName" -> "secret-name"),
          session = signedIn(jurorEmail))
        codes(unknownOperation) === Seq("BAD_INPUT")
        contentAsString(unknownOperation) must not(contain("secret-name"))
      }
    }

    "answer a syntax or validation error with a key, never the query's text" in {
      testDbApp { app =>
        setUp()
        val syntax = gql(app, "{ me { id \"injected-text", as = Some(rootEmail))
        messages(syntax) === Seq("query_syntax")
        contentAsString(syntax) must not(contain("injected-text"))
        val invalid = gql(app, "{ me { injectedField } }", as = Some(rootEmail))
        messages(invalid) === Seq("invalid_query")
        (errors(invalid).head \ "extensions" \ "rule").as[String] === "UndefinedField"
        contentAsString(invalid) must not(contain("injectedField"))
      }
    }

    "time out a slow run, the pool usable afterwards" in {
      testDbApp { app =>
        setUp()
        val controller = app.injector.instanceOf[GraphQLController]
        val never = Promise[Int]().future
        val outcome = Await.ready(controller.withTimeout(never, 50.millis), 5.seconds).value.get
        outcome.failed.get must beAnInstanceOf[TimeoutException]
        controller.settings.timeout === 20.seconds
        status(gql(app, meQuery, as = Some(rootEmail))) === OK
      }
    }
  }

  "CSRF, Host and the production filters" should {
    def browser(app: Application, query: String, sessionToken: String, headers: (String, String)*): Future[Result] =
      postJson(app, Json.obj("query" -> query),
        Seq(COOKIE -> "PLAY_SESSION=signed-in", "X-Requested-With" -> "XMLHttpRequest") ++ headers,
        signedIn(rootEmail) :+ ("csrfToken" -> sessionToken))

    "refuse a session's query without the Csrf-Token header, and let it in with one" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = csrfToken(app)
        val refused = browser(app, meQuery, sessionToken)
        status(refused) === FORBIDDEN
        (json(refused) \ "error").as[String] === "csrf"
        val allowed = browser(app, meQuery, sessionToken, "Csrf-Token" -> sessionToken)
        status(allowed) === OK
        (json(allowed) \ "data" \ "me" \ "isRoot").as[Boolean] must beTrue
      }
    }

    "refuse Bearer with the session cookie and no CSRF token; let Bearer without the cookie in" in {
      withCsrfApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val withCookie = browser(app, meQuery, csrfToken(app), bearer(token))
        status(withCookie) === FORBIDDEN
        (json(withCookie) \ "error").as[String] === "csrf"
        forall(Seq("not-a-signed-session", "")) { value =>
          val result = postJson(app, Json.obj("query" -> meQuery), Seq(bearer(token)), cookies = Seq(Cookie("PLAY_SESSION", value)))
          status(result) === FORBIDDEN
        }
        val tool = postJson(app, Json.obj("query" -> meQuery), Seq(bearer(token)))
        status(tool) === OK
        (json(tool) \ "data" \ "me" \ "authMethod").as[String] === "TOKEN"
      }
    }

    "refuse a POST with neither the session cookie nor a Bearer token as CSRF, with GraphQL's errors too" in {
      withCsrfApp { app =>
        setUp()
        val result = postJson(app, Json.obj("query" -> meQuery))
        status(result) === FORBIDDEN
        (json(result) \ "error").as[String] === "csrf"
        codes(result) === Seq("CSRF")
        (errors(result).head \ "extensions" \ "reference").as[String] === (json(result) \ "reference").as[String]
      }
    }

    "answer a Host not of the deployment with the REST API's 400" in {
      withCsrfApp { app =>
        setUp()
        val result = postJson(app, Json.obj("query" -> meQuery), Seq(HOST -> "evil.example.com", bearer(tokenOf(rootEmail))))
        status(result) === BAD_REQUEST
        (json(result) \ "error").as[String] === "bad_request"
      }
    }
  }

  "a series admin" should {
    "get no contest or round through a series grant alone (no derived role in slice 1)" in {
      testDbApp { app =>
        createContests(10)
        val root = User.create(User("Root", rootEmail, None, isRoot = true))
        val thisYear = java.time.Year.now().getValue
        val series = services.series.ContestSeries.createSeriesTx("Nepal", Some("wlm"), Some("Nepal"), None, root.getId)
          .toOption.get
        val contests = Seq(thisYear - 1, thisYear).map { year =>
          val c = db.scalikejdbc.ContestJuryJdbc.create(None, "Wiki Loves Monuments", year, "Nepal")
          services.series.ContestSeries.setSeries(c.getId, Some(series), root.getId, None)
          c.getId
        }
        val now = contests.last
        val admin = User.create(User("Series admin", "series@example.com", None, Set("organizer"), Some("hash"), Some(now)))
        services.series.SeriesAdmins.grant(root, series, admin.getId, None, None) must beRight
        UserContestJdbc.delete(admin.getId, now)
        Round.create(Round(None, 1, Some("r"), now, active = true))

        def asSeriesAdmin(query: String) =
          postJson(app, Json.obj("query" -> query), session = Seq(Secured.UserName -> admin.email,
            Secured.CurrentContestId -> now.toString))
        contestIds(asSeriesAdmin("{ contests { items { id } } }")) === Nil
        forall(contests) { id =>
          val result = asSeriesAdmin(s"{ contest(id: $id) { id rounds { id } } }")
          (json(result) \ "data" \ "contest").get === JsNull
          codes(result) === Seq("NOT_FOUND")
        }
        ((json(asSeriesAdmin(meQuery)) \ "data" \ "me") \ "email").get === JsNull
      }
    }
  }

  "the per-account limit of runs" should {
    "answer 429 while the account has its runs in flight, a token's and a session's together" in {
      testDbApp { app =>
        setUp()
        val controller = app.injector.instanceOf[GraphQLController]
        val juror = userId(jurorEmail)
        controller.inFlight.tryAcquire(juror) must beTrue
        controller.inFlight.tryAcquire(juror) must beTrue
        try {
          val session = gql(app, meQuery, as = Some(jurorEmail))
          status(session) === TOO_MANY_REQUESTS
          (json(session) \ "error").as[String] === "too_many_requests"
          codes(session) === Seq("TOO_MANY_REQUESTS")
          status(gql(app, meQuery, headers = Seq(bearer(tokenOf(jurorEmail))))) === TOO_MANY_REQUESTS
          status(gql(app, meQuery, as = Some(organizerEmail))) === OK // another account
        } finally {
          controller.inFlight.release(juror)
          controller.inFlight.release(juror)
        }
        status(gql(app, meQuery, as = Some(jurorEmail))) === OK
        controller.inFlight.inFlight(juror) === 0
      }
    }
  }

  "the endpoint's timeout" should {
    implicit val cfg: Map[String, String] = Map("wlxjury.graphql.timeout" -> "1 second")

    "answer a run held up past it with a referenced timeout, the run's slot freed when it ends" in {
      db.scalikejdbc.SharedTestDb.init()
      db.scalikejdbc.SharedTestDb.truncateAll()
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val controller = app.injector.instanceOf[GraphQLController]
        controller.settings.timeout === 1.second
        // another connection holds the round table: the run's read of the rounds waits for it
        val lock = scalikejdbc.ConnectionPool.borrow()
        val held = try {
          lock.createStatement().execute("LOCK TABLES round WRITE")
          val result = gql(app, "{ contest(id: 10) { rounds { id } } }", headers = Seq(bearer(token)))
          status(result) === OK
          messages(result) === Seq("timeout")
          (errors(result).head \ "extensions" \ "reference").as[String] must beMatching("[0-9a-f]{8}")
          controller.inFlight.inFlight(userId(rootEmail)) === 1
        } finally {
          lock.createStatement().execute("UNLOCK TABLES")
          lock.close()
        }
        val deadline = 15.seconds.fromNow
        while (controller.inFlight.inFlight(userId(rootEmail)) > 0 && deadline.hasTimeLeft()) Thread.sleep(50)
        controller.inFlight.inFlight(userId(rootEmail)) === 0
        roundIds(gql(app, "{ contest(id: 10) { rounds { id } } }", headers = Seq(bearer(token)))) must haveSize(3)
        held
      }
    }
  }
}
