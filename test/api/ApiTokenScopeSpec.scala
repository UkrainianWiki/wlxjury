package api

import controllers.Secured
import db.scalikejdbc.{ApiTokenJdbc, ApiTokenScope, ContestJuryJdbc, PlayTestDb, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Application
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.Result
import play.api.mvc.request.RemoteConnection
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

import scala.concurrent.Future

/** The read-only, server-only token scope (support diagnostics plan §3.4, §7, DG3; D-1b): such a
  * token is taken on a GET from the server itself only. On any other method, from a remote
  * address, or with a forwarding header (a request through Apache) it is refused with 403 and an
  * `authorization_denied` line on `security.authz` that names the reason and the token by id,
  * never its value, and nothing changes. A full token (every token before V110) is not limited.
  * The FakeRequest's address is 127.0.0.1 unless set.
  */
class ApiTokenScopeSpec extends PlaySpecification with PlayTestDb with JsonFormat {

  sequential

  private val rootEmail = "root@example.com"
  private val adminEmail = "admin@example.com"

  private def setUp(): Unit = {
    createContests(10, 20)
    User.create(User(rootEmail, rootEmail, isRoot = true))
    val admin = User.create(User(adminEmail, adminEmail))
    UserContestJdbc.createMembership(admin.getId, 10, User.ADMIN_ROLE)
  }

  private def userId(email: String): Long = User.findByEmail(email).head.getId

  private def tokenOf(email: String, scope: ApiTokenScope): String =
    ApiTokenService.create(userId(email), "diag", Some(90), scope = scope)

  private def tokenId(token: String): Long = ApiTokenJdbc.findByHash(ApiTokenService.hash(token)).get.id

  private val remote = RemoteConnection("203.0.113.9", secure = false, clientCertificateChain = None)

  private def send(app: Application, method: String, path: String, token: String, headers: Seq[(String, String)] = Nil,
                   from: Option[RemoteConnection] = None, body: Option[JsValue] = None): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders((AUTHORIZATION -> s"Bearer $token") +: headers: _*)
    val request = from.fold(base)(base.withConnection)
    val result = body.fold(route(app, request).get)(b => route(app, request.withJsonBody(b)).get)
    scala.concurrent.Await.ready(result, scala.concurrent.duration.Duration(30, "seconds"))
    result
  }

  private def contestNames: Seq[String] = ContestJuryJdbc.findAll().map(_.name).sorted

  private val writes: Seq[(String, JsValue)] = Seq(
    POST -> Json.toJson(ContestJury(None, "Wiki Loves Earth", 2026, "Ukraine", images = None)),
    PUT -> Json.toJson(ContestJury(Some(10), "renamed", 2026, "Ukraine", images = None))
  )

  /** The 403 and its one denial line, with the reason, the token's id and scope, never its value. */
  private def refused(result: Future[Result], lines: Seq[String], token: String, reason: String,
                      from: String = "127.0.0.1") = {
    status(result) === FORBIDDEN
    val body = contentAsJson(result)
    (body \ "error").as[String] === "forbidden"
    val reference = (body \ "reference").as[String]
    val line = lines.filter(_.contains(s"ref=$reference"))
    line must haveSize(1)
    line.head must startWith("authorization_denied")
    line.head must contain(s"reason=$reason")
    line.head must contain(s"auth=token tokenId=${tokenId(token)} scope=local_read remote=" + "\"" + from + "\"")
    lines.mkString("\n") must not(contain(token))
  }

  "a read-only, server-only token" should {

    "read on GET from the server itself, as its account may" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail, ApiTokenScope.LocalRead)
        val list = send(app, GET, "/api/contests", token)
        status(list) === OK
        contentAsJson(list).as[Seq[ContestJury]].flatMap(_.id) must contain(allOf(10L, 20L))
        status(send(app, GET, "/api/contests/10", token)) === OK
        // its account's permission still decides: an admin of 10 is not root
        status(send(app, GET, "/api/contests", tokenOf(adminEmail, ApiTokenScope.LocalRead))) === FORBIDDEN
      }
    }

    "be refused on POST and PUT, from the server itself too, changing nothing" in {
      testDbApp { app =>
        setUp()
        val before = contestNames
        val token = tokenOf(rootEmail, ApiTokenScope.LocalRead)
        forall(writes) { case (method, body) =>
          val (result, logs) = controllers.LogCapture("security.authz")(send(app, method, "/api/contests", token, body = Some(body)))
          refused(result, logs("security.authz"), token, "token_read_only")
        }
        // and from a remote address, the method checked first
        val (result, logs) = controllers.LogCapture("security.authz")(
          send(app, PUT, "/api/contests", token, from = Some(remote), body = Some(writes(1)._2)))
        refused(result, logs("security.authz"), token, "token_read_only", from = "203.0.113.9")
        contestNames === before
      }
    }

    "be refused from a remote address" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail, ApiTokenScope.LocalRead)
        forall(Seq("/api/contests", "/api/contests/10", "/api/contests/10/health", "/api/diagnostics/recent")) { path =>
          val (result, logs) = controllers.LogCapture("security.authz")(send(app, GET, path, token, from = Some(remote)))
          refused(result, logs("security.authz"), token, "token_not_local", from = "203.0.113.9")
          contentAsString(result) must not(contain("Contest 10"))
        }
        // IPv6 loopback is the server itself
        val v6 = RemoteConnection("::1", secure = false, clientCertificateChain = None)
        status(send(app, GET, "/api/contests", token, from = Some(v6))) === OK
      }
    }

    "be refused through a proxy: any forwarding header, from loopback, whatever its value" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail, ApiTokenScope.LocalRead)
        val forwarded = Seq(
          "X-Forwarded-For" -> "203.0.113.9",
          "X-Forwarded-For" -> "127.0.0.1",
          "X-Forwarded-For" -> "",
          "Forwarded" -> "for=203.0.113.9",
          "forwarded" -> "for=127.0.0.1",
          "X-Forwarded-Host" -> "jury.example.org",
          "X-Forwarded-Server" -> "jury.example.org"
        )
        forall(forwarded) { header =>
          val (result, logs) = controllers.LogCapture("security.authz")(send(app, GET, "/api/contests", token, Seq(header)))
          refused(result, logs("security.authz"), token, "token_not_local")
        }
      }
    }
  }

  "a full token" should {
    "keep working as before: writes, a remote address and forwarding headers" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail, ApiTokenScope.Full)
        status(send(app, GET, "/api/contests", token, Seq("X-Forwarded-For" -> "203.0.113.9"), from = Some(remote))) === OK
        status(send(app, POST, "/api/contests", token, from = Some(remote), body = Some(writes.head._2))) === OK
        contestNames must contain("Wiki Loves Earth")
        ApiTokenJdbc.find(tokenId(token)).flatMap(_.scope) must beSome(ApiTokenScope.Full: ApiTokenScope)
      }
    }
  }

  "the session" should {
    "not be limited by the token scope" in {
      testDbApp { app =>
        setUp()
        val request = FakeRequest(GET, "/api/contests").withSession(Secured.UserName -> rootEmail)
          .withConnection(remote).withHeaders("X-Forwarded-For" -> "203.0.113.9")
        status(route(app, request).get) === OK
      }
    }
  }

  "a token whose stored scope this release doesn't know" should {
    "be refused (401, api_token_refused reason=unknown_scope), never taken as full, and listed as unknown" in {
      testDbApp { app =>
        setUp()
        val token = tokenOf(rootEmail, ApiTokenScope.LocalRead)
        val id = tokenId(token)
        // a newer release's value, after a backout: V110's CHECK dropped for the test, put back after
        try {
          DB.autoCommit { implicit s =>
            sql"ALTER TABLE api_token DROP CONSTRAINT api_token_scope_chk".execute.apply()
            sql"UPDATE api_token SET scope = 'future' WHERE id = $id".update.apply()
          }
          val (result, logs) = controllers.LogCapture("services.ApiTokenService")(send(app, GET, "/api/contests", token))
          status(result) === UNAUTHORIZED
          logs("services.ApiTokenService") must contain(s"api_token_refused reason=unknown_scope tokenId=$id")
          ApiTokenJdbc.find(id).map(_.scope) === Some(None)
          val page = route(app, FakeRequest(GET, s"/users/edit/${userId(rootEmail)}")
            .withSession(Secured.UserName -> rootEmail)).get
          status(page) === OK
          contentAsString(page) must contain("<td>Unknown (refused)</td>")
        } finally DB.autoCommit { implicit s =>
          sql"UPDATE api_token SET scope = 'full' WHERE BINARY scope NOT IN ('full', 'local_read')".update.apply()
          sql"""ALTER TABLE api_token DROP CONSTRAINT IF EXISTS api_token_scope_chk,
                ADD CONSTRAINT api_token_scope_chk CHECK (BINARY scope IN ('full', 'local_read'))""".execute.apply()
        }
      }
    }
  }

  "the scope check" should {
    "refuse a read-only token on an endpoint not on the allowlist, even a GET from the server itself" in {
      val request = FakeRequest(GET, "/api/contests")
      val readOnly = ApiAuth.ByToken(1L, ApiTokenScope.LocalRead)
      ApiAuth.scopeRefusal(readOnly, request, "listContests") must beNone
      ApiAuth.scopeRefusal(readOnly, request, "someNewEndpoint") must beSome("token_endpoint")
      ApiAuth.scopeRefusal(readOnly, request, "createContest") must beSome("token_endpoint")
      ApiAuth.scopeRefusal(ApiAuth.ByToken(1L), request, "someNewEndpoint") must beNone
      ApiAuth.scopeRefusal(ApiAuth.BySession, request, "someNewEndpoint") must beNone
    }
  }

  "a scope" should {
    "be read exactly as stored, an unknown value known as none" in {
      ApiTokenScope.find("full") must beSome(ApiTokenScope.Full: ApiTokenScope)
      ApiTokenScope.find("local_read") must beSome(ApiTokenScope.LocalRead: ApiTokenScope)
      ApiTokenScope.find("Full") must beNone
      ApiTokenScope.find(" local_read") must beNone
      ApiTokenScope.find("") must beNone
    }
  }
}
