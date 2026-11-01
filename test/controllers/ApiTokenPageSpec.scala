package controllers

import controllers.CsrfTestApp.{token, withCsrfApp}
import db.scalikejdbc.{ApiTokenJdbc, PlayTestDb, User, UserContestJdbc}
import play.api.Application
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}
import services.ApiTokenService

import scala.concurrent.Future

/** The own profile's personal API tokens (UserController.createApiToken / revokeApiToken,
  * apiTokens.scala.html): a signed-in session creates one with the CSRF token and sees it in
  * that response only; a user revokes only their own; a token signs nothing in here.
  */
class ApiTokenPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val email = "tina@example.com"
  private val otherEmail = "omar@example.com"

  /** Tina and Omar, jurors of contest 10. */
  private def setUp(): Unit = {
    createContests(10)
    for (e <- Seq(email, otherEmail)) {
      val user = User.create(User(e, e, password = Some(User.sha1("secret"))))
      UserContestJdbc.createMembership(user.getId, 10, User.JURY_ROLE)
    }
  }

  private def userId(e: String): Long = User.findByEmail(e).head.getId

  private val tokenPattern = "wlxj_[A-Za-z0-9_-]{43}".r

  /** A signed-in browser's POST: the session cookie (which makes the CSRF filter check it),
    * the session with its CSRF token, and the form with that token if `withToken`.
    */
  private def post(app: Application, path: String, sessionToken: String, body: Seq[(String, String)] = Nil,
                   withToken: Boolean = true, as: String = email): Future[Result] =
    route(app, FakeRequest(POST, path)
      .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
      .withSession(Secured.UserName -> as, Secured.CurrentContestId -> "10", "csrfToken" -> sessionToken)
      .withFormUrlEncodedBody(body ++ (if (withToken) Seq("csrfToken" -> sessionToken) else Nil): _*)).get

  private def profile(app: Application, as: String = email): Future[Result] =
    route(app, FakeRequest(GET, s"/users/edit/${userId(as)}")
      .withSession(Secured.UserName -> as, Secured.CurrentContestId -> "10")).get

  private val create = Seq("name" -> "laptop", "expiry" -> "90")

  "creating an API token" should {

    "need the CSRF token, changing nothing without it" in {
      withCsrfApp { app =>
        setUp()
        val result = post(app, "/users/tokens", token(app), create, withToken = false)
        status(result) === FORBIDDEN
        ApiTokenJdbc.findByUser(userId(email)) must beEmpty
      }
    }

    "need a session: signed out or with a token, nothing is created" in {
      testDbApp { app =>
        setUp()
        val existing = ApiTokenService.create(userId(email), "cli", Some(90))
        def signedOut(headers: (String, String)*) =
          route(app, FakeRequest(POST, "/users/tokens").withHeaders(Seq(ACCEPT -> "text/html") ++ headers: _*)
            .withFormUrlEncodedBody(create: _*)).get
        val without = signedOut()
        val withBearer = signedOut(AUTHORIZATION -> s"Bearer $existing")
        status(without) === SEE_OTHER
        status(withBearer) === status(without)
        redirectLocation(withBearer) === redirectLocation(without)
        ApiTokenJdbc.findByUser(userId(email)).map(_.name) === Seq("cli")
      }
    }

    "show the new token once, store only its hash, and list it on the profile" in {
      withCsrfApp { app =>
        setUp()
        val result = post(app, "/users/tokens", token(app), create)
        status(result) === OK
        header(CACHE_CONTROL, result) === Some("no-store")
        val shown = tokenPattern.findAllIn(contentAsString(result)).toSeq.distinct
        shown must haveSize(1)
        val plain = shown.head

        // not in a cookie, the flash or the session either
        (cookies(result).toSeq.map(_.value) ++ flash(result).data.values ++ session(result).data.values)
          .exists(_.contains(plain)) must beFalse

        val stored = ApiTokenJdbc.findByUser(userId(email))
        stored.map(_.name) === Seq("laptop")
        stored.map(_.prefix) === Seq(plain.take(11))
        ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).map(_.id) === stored.headOption.map(_.id)

        val reloaded = contentAsString(profile(app))
        reloaded must not(contain(plain))
        reloaded must contain("laptop")
        reloaded must contain(plain.take(11))
        reloaded must contain("api-token-form")
      }
    }

    "refuse a missing name or an expiry not offered, creating nothing" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        forall(Seq(Seq("name" -> " ", "expiry" -> "90"), Seq("name" -> "cli", "expiry" -> "7"),
                   Seq("name" -> "x" * 101, "expiry" -> "never"), Seq("name" -> "cli"))) { body =>
          val result = post(app, "/users/tokens", sessionToken, body)
          status(result) === BAD_REQUEST
          tokenPattern.findFirstIn(contentAsString(result)) must beNone
        }
        ApiTokenJdbc.findByUser(userId(email)) must beEmpty
      }
    }

    "expire after the days chosen, or never" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        status(post(app, "/users/tokens", sessionToken, Seq("name" -> "forever", "expiry" -> "never"))) === OK
        status(post(app, "/users/tokens", sessionToken, Seq("name" -> "month", "expiry" -> "30"))) === OK
        val byName = ApiTokenJdbc.findByUser(userId(email)).map(t => t.name -> t).toMap
        byName("forever").expiresAt must beNone
        byName("month").expiresAt.map(java.time.temporal.ChronoUnit.DAYS.between(byName("month").createdAt, _)) === Some(30L)
      }
    }
  }

  "the profile" should {
    "show the tokens card on the user's own profile only" in {
      withCsrfApp { app =>
        setUp()
        ApiTokenService.create(userId(otherEmail), "omar-cli", Some(90))
        contentAsString(profile(app)) must contain("api-tokens")
        contentAsString(profile(app)) must not(contain("omar-cli"))

        // root editing Omar's account sees no tokens card, Omar's or theirs
        User.create(User("root@example.com", "root@example.com", password = Some(User.sha1("secret")), isRoot = true))
        val asRoot = route(app, FakeRequest(GET, s"/users/edit/${userId(otherEmail)}")
          .withSession(Secured.UserName -> "root@example.com")).get
        status(asRoot) === OK
        contentAsString(asRoot) must not(contain("api-tokens"))
        contentAsString(asRoot) must not(contain("omar-cli"))
      }
    }
  }

  "revoking an API token" should {

    "revoke the user's own token, which then lets nobody in" in {
      withCsrfApp { app =>
        setUp()
        val plain = ApiTokenService.create(userId(email), "cli", Some(90))
        val id = ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).get.id
        val result = post(app, s"/users/tokens/$id/revoke", token(app))
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.UserController.editUser(userId(email)).url)
        ApiTokenJdbc.find(id).get.revoked must beTrue
        ApiTokenService.authenticate(plain) must beNone
      }
    }

    "not revoke another user's token: not found, unchanged" in {
      withCsrfApp { app =>
        setUp()
        val plain = ApiTokenService.create(userId(otherEmail), "omar-cli", Some(90))
        val id = ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).get.id
        val result = post(app, s"/users/tokens/$id/revoke", token(app))
        status(result) === NOT_FOUND
        status(post(app, "/users/tokens/9999/revoke", token(app))) === NOT_FOUND
        ApiTokenJdbc.find(id).get.revoked must beFalse
      }
    }

    "need the CSRF token and a session, not a token" in {
      withCsrfApp { app =>
        setUp()
        val plain = ApiTokenService.create(userId(email), "cli", Some(90))
        val id = ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).get.id
        status(post(app, s"/users/tokens/$id/revoke", token(app), withToken = false)) === FORBIDDEN
        val withBearer = route(app, FakeRequest(POST, s"/users/tokens/$id/revoke")
          .withHeaders(AUTHORIZATION -> s"Bearer $plain", ACCEPT -> "text/html")).get
        // the CSRF filter checks a request with an Authorization header outside /api
        status(withBearer) === FORBIDDEN
        ApiTokenJdbc.find(id).get.revoked must beFalse
      }
    }
  }
}
