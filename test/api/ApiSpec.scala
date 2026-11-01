package api

import controllers.CsrfTestApp.{token => csrfToken, withCsrfApp}
import controllers.Secured
import db.scalikejdbc.{ApiTokenJdbc, ContestJuryJdbc, PlayTestDb, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Application
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Cookie, Result}
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The REST API (`/api`, [[Api]]) through the router and the filters, as served: root's
  * session or token lets in; no credentials, a refused token, or a user who is not root
  * does not, and changes nothing. Errors are JSON without the request's text; CSRF is
  * checked for every session write, and skipped only for a Bearer request without the
  * session cookie.
  */
class ApiSpec extends PlaySpecification with PlayTestDb with JsonFormat {

  sequential

  private val rootEmail = "root@example.com"
  private val adminEmail = "admin@example.com"
  private val jurorEmail = "juror@example.com"

  private val newContest = ContestJury(None, "Wiki Loves Earth", 2026, "Ukraine", images = None)

  /** Contests 10 and 20; root (no contest), an admin of 10 who is not root and a juror of 10. */
  private def setUp(): Unit = {
    createContests(10, 20)
    User.create(User(rootEmail, rootEmail, password = Some(User.sha1("secret")), isRoot = true))
    val admin = User.create(User(adminEmail, adminEmail, password = Some(User.sha1("secret"))))
    UserContestJdbc.createMembership(admin.getId, 10, User.ADMIN_ROLE)
    val juror = User.create(User(jurorEmail, jurorEmail, password = Some(User.sha1("secret"))))
    UserContestJdbc.createMembership(juror.getId, 10, User.JURY_ROLE)
  }

  private def userId(email: String): Long = User.findByEmail(email).head.getId

  private def tokenOf(email: String, expiresInDays: Option[Int] = Some(90)): String =
    ApiTokenService.create(userId(email), "cli", expiresInDays)

  private def tokenId(token: String): Long = ApiTokenJdbc.findByHash(ApiTokenService.hash(token)).get.id

  private def bearer(token: String): (String, String) = AUTHORIZATION -> s"Bearer $token"

  private def contestNames: Seq[String] = ContestJuryJdbc.findAll().map(_.name).sorted

  /** A request: its method, path and JSON body. */
  private case class Req(method: String, path: String, body: Option[JsValue] = None)

  /** The four endpoints; create and update would change the contests if let in. */
  private val endpoints: Seq[Req] = Seq(
    Req(GET, "/api/contests"),
    Req(GET, "/api/contests/10"),
    Req(POST, "/api/contests", Some(Json.toJson(newContest))),
    Req(PUT, "/api/contests", Some(Json.toJson(ContestJury(Some(10), "renamed", 2026, "Ukraine", images = None))))
  )

  /** `req` with the headers and cookies, signed in as `as` (in contest 10) if given, with
    * `session` too. A FakeRequest's session and cookies are attributes: a `Cookie` header
    * alone is not read as cookies.
    */
  private def send(app: Application, req: Req, headers: Seq[(String, String)] = Nil, as: Option[String] = None,
                   session: Seq[(String, String)] = Nil, cookies: Seq[Cookie] = Nil): Future[Result] = {
    val signedIn = as.toSeq.flatMap(email => Seq(Secured.UserName -> email, Secured.CurrentContestId -> "10"))
    val request = FakeRequest(req.method, req.path).withHeaders(headers: _*).withSession(signedIn ++ session: _*)
      .withCookies(cookies: _*)
    req.body.fold(route(app, request).get)(body => route(app, request.withJsonBody(body)).get)
  }

  private def json(result: Future[Result]): JsValue = contentAsJson(result)

  private def isUnauthorized(result: Future[Result]) = {
    status(result) === UNAUTHORIZED
    header(WWW_AUTHENTICATE, result) === Some("Bearer")
    json(result) === Json.obj("error" -> "unauthenticated")
  }

  /** The result, done, so that the lines it logs are captured. */
  private def done(result: Future[Result]): Future[Result] = {
    scala.concurrent.Await.ready(result, scala.concurrent.duration.Duration(30, "seconds"))
    result
  }

  private def logger(name: String) =
    org.slf4j.LoggerFactory.getLogger(name).asInstanceOf[ch.qos.logback.classic.Logger]

  /** `f`'s result and the lines logged meanwhile, at any logger, the API's and the tokens'
    * DEBUG lines included.
    */
  private def withLog[T](f: => T): (T, Seq[String]) = {
    val root = logger(org.slf4j.Logger.ROOT_LOGGER_NAME)
    val verbose = Seq("api", "services.ApiTokenService").map(logger)
    val levels = verbose.map(_.getLevel)
    verbose.foreach(_.setLevel(ch.qos.logback.classic.Level.DEBUG))
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    root.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(e => s"${e.getLevel} ${e.getFormattedMessage}"))
    } finally {
      root.detachAppender(appender)
      verbose.zip(levels).foreach { case (l, level) => l.setLevel(level) }
    }
  }

  "the API without credentials" should {
    "answer 401 with WWW-Authenticate, changing nothing" in {
      testDbApp { app =>
        setUp()
        val before = contestNames
        forall(endpoints) { request =>
          isUnauthorized(send(app, request))
        }
        contestNames === before
      }
    }
  }

  "the API for a user who is not root" should {
    "refuse a juror's and an admin's session (403, logged with its reference), changing nothing" in {
      testDbApp { app =>
        setUp()
        val before = contestNames
        forall(Seq(jurorEmail, adminEmail)) { email =>
          forall(endpoints) { request =>
            val (result, lines) = withLog(done(send(app, request, as = Some(email))))
            status(result) === FORBIDDEN
            val reference = (json(result) \ "reference").as[String]
            (json(result) \ "error").as[String] === "forbidden"
            lines.count(line => line.startsWith("WARN authorization_denied") && line.contains(s"ref=$reference") &&
              line.contains("auth=session") && line.contains("requiredRoles=\"root\"")) === 1
          }
        }
        contestNames === before
      }
    }

    "refuse an admin's token, logging the token's id but never the token" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(adminEmail)
        val (result, lines) = withLog(done(send(app, endpoints.head, Seq(bearer(token)))))
        status(result) === FORBIDDEN
        val reference = (json(result) \ "reference").as[String]
        lines.count(line => line.contains(s"ref=$reference") && line.contains("auth=token") &&
          line.contains(s"tokenId=${tokenId(token)}")) === 1
        lines.exists(_.contains(token)) must beFalse
      }
    }
  }

  "a refused token" should {
    "answer 401 when revoked, expired, malformed, unknown or not Bearer, changing nothing and never logged" in {
      testDbApp { app =>
        setUp()
        val revoked = tokenOf(rootEmail)
        ApiTokenJdbc.revoke(tokenId(revoked), userId(rootEmail))
        val expired = tokenOf(rootEmail, Some(30))
        DB.autoCommit { implicit session =>
          sql"UPDATE api_token SET expires_at = NOW(3) - INTERVAL 1 SECOND WHERE id = ${tokenId(expired)}".update.apply()
        }
        val unknown = ApiTokenService.generate()
        val basic = java.util.Base64.getEncoder.encodeToString(s"$rootEmail:secret".getBytes)
        val authorizations = Seq(
          s"Bearer $revoked", s"Bearer $expired", s"Bearer $unknown", "Bearer wlxj_short", "Bearer ", "Bearer",
          s"Basic $basic", s"Token $unknown"
        )
        val before = contestNames
        val (_, lines) = withLog {
          forall(authorizations) { authorization =>
            forall(endpoints) { request =>
              isUnauthorized(send(app, request, Seq(AUTHORIZATION -> authorization)))
            }
          }
          ()
        }
        contestNames === before
        Seq(revoked, expired, unknown, basic).exists(t => lines.exists(_.contains(t))) must beFalse
        lines must contain("WARN api_token_refused reason=revoked tokenId=" + tokenId(revoked))
        lines must contain("WARN api_token_refused reason=expired tokenId=" + tokenId(expired))
        lines must contain("INFO api_token_refused reason=unknown prefix=\"" + unknown.take(11) + "\"")
        lines must contain("DEBUG api_token_refused reason=malformed")
        lines must contain("DEBUG api_auth_refused reason=not_bearer")
        // random input reaches neither WARN (it needs a real token's hash) nor the database
        lines.filter(line => line.startsWith("WARN") && line.contains("api_")).map(_.split(" tokenId=").head)
          .distinct.sorted === Seq("WARN api_token_refused reason=expired", "WARN api_token_refused reason=revoked")
        ApiTokenJdbc.findByUser(userId(rootEmail)).flatMap(_.lastUsedAt) must beEmpty
      }
    }

    "answer 401 for a deleted user's token" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        DB.autoCommit { implicit session =>
          sql"UPDATE users SET deleted_at = NOW() WHERE id = ${userId(rootEmail)}".update.apply()
        }
        val (result, lines) = withLog(done(send(app, endpoints.head, Seq(bearer(token)))))
        isUnauthorized(result)
        lines must contain(s"WARN api_token_refused reason=user_missing tokenId=${tokenId(token)}")
      }
    }

    "not be rescued by root's session cookie" in {
      testDbApp { app =>
        setUp()
        val before = contestNames
        forall(endpoints) { request =>
          isUnauthorized(send(app, request, Seq(bearer(ApiTokenService.generate())), as = Some(rootEmail)))
        }
        contestNames === before
      }
    }
  }

  "the API for root" should {
    "answer root's session on each endpoint" in {
      testDbApp { app =>
        setUp()
        val list = send(app, endpoints.head, as = Some(rootEmail))
        status(list) === OK
        json(list).as[List[ContestJury]].map(_.name) === Seq("contest10", "contest20")
        forall(endpoints.tail) { request =>
          status(send(app, request, as = Some(rootEmail))) === OK
        }
        ContestJuryJdbc.findById(10).map(_.name) === Some("renamed")
        contestNames must contain(newContest.name)
      }
    }

    "answer root's token for each endpoint, and record the use" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val (list, lines) = withLog(done(send(app, endpoints.head, Seq(bearer(token)))))
        status(list) === OK
        lines.exists(_.contains(token)) must beFalse
        json(list).as[List[ContestJury]].map(_.id) === Seq(Some(10L), Some(20L))
        ApiTokenJdbc.find(tokenId(token)).get.lastUsedAt must beSome

        val one = send(app, Req(GET, "/api/contests/20"), Seq(bearer(token)))
        status(one) === OK
        json(one).as[ContestJury].name === "contest20"

        // an id in the body does not pick the new contest's
        val created = send(app, Req(POST, "/api/contests", Some(Json.toJson(newContest.copy(id = Some(10))))),
          Seq(bearer(token)))
        status(created) === OK
        val createdId = json(created).as[ContestJury].id
        createdId must beSome
        createdId must not(beSome(10L))
        ContestJuryJdbc.findById(10).map(_.name) === Some("contest10")
        ContestJuryJdbc.findById(createdId.get).map(_.name) === Some(newContest.name)

        val updated = send(app, Req(PUT, "/api/contests", Some(Json.toJson(ContestJury(Some(20), "renamed", 2026, "Ukraine", images = None)))),
          Seq(bearer(token)))
        status(updated) === OK
        ContestJuryJdbc.findById(20).map(_.name) === Some("renamed")
      }
    }
  }

  "an API error" should {
    "be JSON without the request's text" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val marker = "<script>marker-7f3a</script>"
        val malformed = route(app, FakeRequest(POST, "/api/contests")
          .withTextBody(s"""{"name": "$marker", """)
          .withHeaders(bearer(token), CONTENT_TYPE -> "application/json")).get
        status(malformed) === BAD_REQUEST
        json(malformed) === Json.obj("error" -> "bad_request")
        contentAsString(malformed) must not(contain("marker-7f3a"))

        val wrongShape = send(app, Req(POST, "/api/contests", Some(Json.obj("name" -> marker))), Seq(bearer(token)))
        status(wrongShape) === BAD_REQUEST
        contentAsString(wrongShape) must not(contain("marker-7f3a"))

        val badId = send(app, Req(GET, "/api/contests/marker-7f3a"), Seq(bearer(token)))
        status(badId) === BAD_REQUEST
        json(badId) === Json.obj("error" -> "bad_request")

        val unknown = send(app, Req(GET, "/api/contests/999"), Seq(bearer(token)))
        status(unknown) === NOT_FOUND
        json(unknown) === Json.obj("error" -> "not_found")

        val updateUnknown = send(app, Req(PUT, "/api/contests", Some(Json.toJson(ContestJury(Some(999), "x", 2026, "Ukraine", images = None)))),
          Seq(bearer(token)))
        status(updateUnknown) === NOT_FOUND
        ContestJuryJdbc.findById(999) must beNone

        val updateWithoutId = send(app, Req(PUT, "/api/contests", Some(Json.toJson(newContest))), Seq(bearer(token)))
        status(updateWithoutId) === BAD_REQUEST
        contestNames must not(contain(newContest.name))

        // over the parser's limit (play.http.parser.maxMemoryBuffer, 100 KB): Play answers,
        // through the app's error handler
        val oversized = send(app, Req(POST, "/api/contests",
          Some(Json.toJson(newContest.copy(images = Some("x" * 200000))))), Seq(bearer(token)))
        status(oversized) === REQUEST_ENTITY_TOO_LARGE
        json(oversized) === Json.obj("error" -> "payload_too_large")

        val otherPath = send(app, Req(GET, "/api/marker-7f3a"))
        status(otherPath) === NOT_FOUND
        json(otherPath) === Json.obj("error" -> "not_found")
      }
    }
  }

  "the API docs" should {
    "be served without credentials, with both security schemes, from this server only" in {
      testDbApp { app =>
        // Swagger UI's files are streamed
        implicit val materializer: org.apache.pekko.stream.Materializer = app.materializer
        val redirect = send(app, Req(GET, "/api/docs"))
        status(redirect) === PERMANENT_REDIRECT
        redirectLocation(redirect) === Some("./docs/")

        val page = send(app, Req(GET, "/api/docs/"))
        status(page) === OK
        val html = contentAsString(page)
        html must contain("swagger-ui")
        // the production CSP allows this server's scripts and styles only ('self')
        """(?:src|href)="(?:[a-z]+:)?//""".r.findAllIn(html).toSeq must beEmpty
        html must contain("src=\"./swagger-ui-bundle.js\"")

        val initializer = send(app, Req(GET, "/api/docs/swagger-initializer.js"))
        status(initializer) === OK
        contentAsString(initializer) must contain("url: \"./docs.yaml\"")
        contentAsString(initializer) must contain("validatorUrl: null")
        """https?://""".r.findFirstIn(contentAsString(initializer)) must beNone

        val yaml = send(app, Req(GET, "/api/docs/docs.yaml"))
        status(yaml) === OK
        val spec = contentAsString(yaml)
        spec must contain("bearerToken")
        spec must contain("sessionCookie")
        spec must contain("/contests")
        spec must contain("url: /api")
      }
    }
  }

  "CSRF on the API" should {

    /** A signed-in browser's script request to `req`: the session cookie (which makes the
      * filter check it), root's session with its CSRF token, and the `headers`.
      */
    def browser(app: Application, req: Req, sessionToken: String, headers: (String, String)*): Future[Result] =
      send(app, req, Seq(COOKIE -> "PLAY_SESSION=signed-in", "X-Requested-With" -> "XMLHttpRequest") ++ headers,
        as = Some(rootEmail), session = Seq("csrfToken" -> sessionToken))

    "refuse a session's write without the Csrf-Token header, changing nothing" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = csrfToken(app)
        val before = contestNames
        forall(endpoints.filter(r => r.method == POST || r.method == PUT)) { request =>
          val result = browser(app, request, sessionToken)
          status(result) === FORBIDDEN
          (json(result) \ "error").as[String] === "csrf"
        }
        contestNames === before
      }
    }

    "answer a refusal as JSON under /api, also to a request not sent by script" in {
      withCsrfApp { app =>
        setUp()
        val result = send(app, endpoints(2), Seq(COOKIE -> "PLAY_SESSION=signed-in"), as = Some(rootEmail),
          session = Seq("csrfToken" -> csrfToken(app)))
        status(result) === FORBIDDEN
        (json(result) \ "error").as[String] === "csrf"
        (json(result) \ "reference").as[String] must beMatching("[0-9a-f]{8}")
      }
    }

    "let a session's writes in with the Csrf-Token header" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = csrfToken(app)
        val created = browser(app, endpoints(2), sessionToken, "Csrf-Token" -> sessionToken)
        status(created) === OK
        contestNames must contain(newContest.name)
        val updated = browser(app, endpoints(3), sessionToken, "Csrf-Token" -> sessionToken)
        status(updated) === OK
        ContestJuryJdbc.findById(10).map(_.name) === Some("renamed")
      }
    }

    "let a Bearer write in without a CSRF token only without the session cookie" in {
      withCsrfApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val tool = send(app, endpoints(2), Seq(bearer(token)))
        status(tool) === OK
        contestNames.count(_ == newContest.name) === 1

        val withCookie = browser(app, Req(POST, "/api/contests", Some(Json.toJson(newContest.copy(name = "second")))),
          csrfToken(app), bearer(token))
        status(withCookie) === FORBIDDEN
        (json(withCookie) \ "error").as[String] === "csrf"
        contestNames must not(contain("second"))

        // the session cookie, even invalid or empty, makes a Bearer write need the CSRF token
        forall(Seq("not-a-signed-session", "")) { value =>
          val result = send(app, Req(POST, "/api/contests", Some(Json.toJson(newContest.copy(name = "third")))),
            Seq(bearer(token)), cookies = Seq(Cookie("PLAY_SESSION", value)))
          status(result) === FORBIDDEN
          (json(result) \ "error").as[String] === "csrf"
        }
        contestNames must not(contain("third"))

        // another cookie (the theme's) does not
        val withTheme = send(app, Req(POST, "/api/contests", Some(Json.toJson(newContest.copy(name = "fourth")))),
          Seq(bearer(token)), cookies = Seq(Cookie(controllers.Theme.CookieName, "dark")))
        status(withTheme) === OK
        contestNames must contain("fourth")

        // a Bearer POST outside /api is checked as any other
        val page = route(app, FakeRequest(POST, "/theme").withHeaders(bearer(token))
          .withFormUrlEncodedBody("theme" -> "dark")).get
        status(page) === FORBIDDEN
        User.findByEmail(rootEmail).head.theme must beNone
      }
    }
  }

  "a token on a page route" should {
    "not sign in: the same answer as without credentials" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail)
        val requests = Seq(
          Req(GET, "/admin/rounds"),
          Req(GET, "/contests"),
          Req(POST, "/rate/round/1/pageid/1/select/1"),
          Req(POST, "/theme", Some(Json.obj("theme" -> "dark")))
        )
        forall(requests) { request =>
          val without = send(app, request)
          val withToken = send(app, request, Seq(bearer(token)))
          status(withToken) === status(without)
          redirectLocation(withToken) === redirectLocation(without)
        }
        User.findByEmail(rootEmail).head.theme must beNone
        ApiTokenJdbc.find(tokenId(token)).get.lastUsedAt must beNone
      }
    }
  }
}
