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

  /** Tina and Omar, members of contest 10: Omar a juror; Tina root if `tinaRoot` (only root has
    * API tokens on the profile for now, the user's decision of 2026-11-05), else a juror too.
    */
  private def setUp(tinaRoot: Boolean = true): Unit = {
    createContests(10)
    for (e <- Seq(email, otherEmail)) {
      val user = User.create(User(e, e, password = Some(services.PasswordHasher.default.hash("secret")),
        isRoot = tinaRoot && e == email))
      UserContestJdbc.createMembership(user.getId, 10, User.JURY_ROLE)
    }
  }

  private def userId(e: String): Long = User.findByEmail(e).head.getId

  private val tokenPattern = "wlxj_[A-Za-z0-9_-]{43}".r

  /** The create form's nonce as the profile page gave it ([[ApiTokensCard.NonceKey]]). */
  private val pageNonce = "page-nonce"

  /** A signed-in browser's POST: the session cookie (which makes the CSRF filter check it),
    * the session with its CSRF token and `sessionNonce`, and the form with that token if
    * `withToken` and with `nonce`.
    */
  private def post(app: Application, path: String, sessionToken: String, body: Seq[(String, String)] = Nil,
                   withToken: Boolean = true, as: String = email, nonce: Option[String] = Some(pageNonce),
                   sessionNonce: Option[String] = Some(pageNonce)): Future[Result] =
    route(app, FakeRequest(POST, path)
      .withHeaders(COOKIE -> "PLAY_SESSION=signed-in", ACCEPT -> "text/html")
      .withSession(Seq(Secured.UserName -> as, Secured.CurrentContestId -> "10", "csrfToken" -> sessionToken) ++
        sessionNonce.map(ApiTokensCard.NonceKey -> _): _*)
      .withFormUrlEncodedBody(body ++ (if (withToken) Seq("csrfToken" -> sessionToken) else Nil) ++
        nonce.map(ApiTokensCard.NonceKey -> _): _*)).get

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

    "take a create once: reloading its response, or a form without the page's nonce, creates nothing" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val first = post(app, "/users/tokens", sessionToken, create)
        status(first) === OK
        val nextNonce = session(first).get(ApiTokensCard.NonceKey)
        nextNonce must beSome(not(be_==(pageNonce)))
        contentAsString(first) must contain(s"""value="${nextNonce.get}"""")

        // the browser re-posts the form, with the nonce it had, and the session the response set
        val reload = post(app, "/users/tokens", sessionToken, create, sessionNonce = nextNonce)
        status(reload) === CONFLICT
        contentAsString(reload) must contain("This form was already submitted")
        tokenPattern.findFirstIn(contentAsString(reload)) must beNone

        status(post(app, "/users/tokens", sessionToken, create, nonce = None)) === CONFLICT
        status(post(app, "/users/tokens", sessionToken, create, sessionNonce = None)) === CONFLICT
        ApiTokenJdbc.findByUser(userId(email)) must haveSize(1)

        // the refusal's page has a new nonce, which creates
        val again = post(app, "/users/tokens", sessionToken, Seq("name" -> "desktop", "expiry" -> "90"),
          nonce = session(reload).get(ApiTokensCard.NonceKey),
          sessionNonce = session(reload).get(ApiTokensCard.NonceKey))
        status(again) === OK
        ApiTokenJdbc.findByUser(userId(email)) must haveSize(2)
      }
    }

    "leave one usable token of a double click, the one the last response shows" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        def shown(result: Future[Result]): Option[String] = tokenPattern.findFirstIn(contentAsString(result))
        def usable(name: String) = ApiTokenJdbc.findByUser(userId(email)).filter(t => t.usable && t.name == name)

        val first = post(app, "/users/tokens", sessionToken, create)
        status(first) === OK
        // the second click's request: the same session (its nonce unchanged) and form, the same
        // submit; the browser shows its response, and the first token, which nobody saw, is revoked
        val second = post(app, "/users/tokens", sessionToken, create)
        status(second) === OK
        val token2 = shown(second).get
        usable("laptop").map(_.id) === ApiTokenJdbc.findByHash(ApiTokenService.hash(token2)).map(_.id).toSeq
        ApiTokenService.authenticate(shown(first).get) must beNone
        ApiTokenService.authenticate(token2) must beSome // and now it is used

        // a used token is kept, even by a repeat of its submit
        status(post(app, "/users/tokens", sessionToken, create)) === OK
        usable("laptop") must haveSize(2)

        // five at once: each answers with a token, one of them usable
        val clicks = (1 to 5).map(_ => post(app, "/users/tokens", sessionToken, Seq("name" -> "burst", "expiry" -> "90")))
        clicks.map(status(_)).distinct === Seq(OK)
        val burst = usable("burst")
        burst must haveSize(1)
        clicks.flatMap(shown).flatMap(t => ApiTokenJdbc.findByHash(ApiTokenService.hash(t))).filter(_.usable).map(_.id) ===
          burst.map(_.id)
      }
    }

    "keep a token copied from a create when another of the same name follows from the new page" in {
      withCsrfApp { app =>
        setUp()
        val sessionToken = token(app)
        val first = post(app, "/users/tokens", sessionToken, create)
        status(first) === OK
        val copied = tokenPattern.findFirstIn(contentAsString(first)).get
        // the response page's create form: its new nonce, in the form and the session
        val next = session(first).get(ApiTokensCard.NonceKey)
        val second = post(app, "/users/tokens", sessionToken, create, nonce = next, sessionNonce = next)
        status(second) === OK
        val other = tokenPattern.findFirstIn(contentAsString(second)).get
        (ApiTokenService.authenticate(copied) must beSome) and (ApiTokenService.authenticate(other) must beSome) and
          (ApiTokenJdbc.findByUser(userId(email)).count(t => t.usable && t.name == "laptop") === 2)
      }
    }

    "refuse a token over the 20 usable ones, creating nothing" in {
      withCsrfApp { app =>
        setUp()
        val id = userId(email)
        val tokens = (1 to ApiTokenService.MaxActive).map(i => ApiTokenService.create(id, s"cli$i", Some(90)))
        val sessionToken = token(app)
        val refused = post(app, "/users/tokens", sessionToken, create)
        status(refused) === CONFLICT
        contentAsString(refused) must contain(s"You have ${ApiTokenService.MaxActive} usable tokens already")
        ApiTokenJdbc.findByUser(id) must haveSize(ApiTokenService.MaxActive)

        // a revoked one no longer counts
        ApiTokenJdbc.revoke(ApiTokenJdbc.findByHash(ApiTokenService.hash(tokens.head)).get.id, id)
        status(post(app, "/users/tokens", sessionToken, create)) === OK
        ApiTokenService.activeCount(id) === ApiTokenService.MaxActive
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
    "give the create form a nonce, also in the session" in {
      withCsrfApp { app =>
        setUp()
        val page = profile(app)
        val nonce = session(page).get(ApiTokensCard.NonceKey)
        nonce must beSome
        contentAsString(page) must contain(s"""name="${ApiTokensCard.NonceKey}" value="${nonce.get}"""")
      }
    }

    "keep the tokens card when saving the own profile fails" in {
      withCsrfApp { app =>
        setUp()
        ApiTokenService.create(userId(email), "tina-cli", Some(90))
        val sessionToken = token(app)
        val base = Seq("id" -> userId(email).toString, "email" -> email, "contest" -> "10", "lang" -> "en")
        forall(Seq(base :+ ("fullname" -> ""), base.map {
          case ("email", _) => "email" -> otherEmail // another account's
          case field        => field
        } :+ ("fullname" -> "Tina"))) { body =>
          val result = post(app, "/users/save", sessionToken, body, nonce = None, sessionNonce = None)
          status(result) === BAD_REQUEST
          contentAsString(result) must contain("api-tokens")
          contentAsString(result) must contain("tina-cli")
        }
        User.findByEmail(email).head.fullname === email
      }
    }

    "show the tokens card on the user's own profile only" in {
      withCsrfApp { app =>
        setUp()
        ApiTokenService.create(userId(otherEmail), "omar-cli", Some(90))
        contentAsString(profile(app)) must contain("api-tokens")
        contentAsString(profile(app)) must not(contain("omar-cli"))

        // root editing Omar's account sees no tokens card, Omar's or theirs
        User.create(User("root@example.com", "root@example.com", password = Some(services.PasswordHasher.default.hash("secret")), isRoot = true))
        val asRoot = route(app, FakeRequest(GET, s"/users/edit/${userId(otherEmail)}")
          .withSession(Secured.UserName -> "root@example.com")).get
        status(asRoot) === OK
        contentAsString(asRoot) must not(contain("api-tokens"))
        contentAsString(asRoot) must not(contain("omar-cli"))
      }
    }

    "show no tokens card to anyone but root, and refuse their create, creating nothing" in {
      withCsrfApp { app =>
        setUp()
        val omar = profile(app, as = otherEmail)
        status(omar) === OK
        contentAsString(omar) must not(contain("api-tokens"))
        status(post(app, "/users/tokens", token(app), create, as = otherEmail)) === FORBIDDEN
        ApiTokenJdbc.findByUser(userId(otherEmail)) must beEmpty
      }
    }
  }

  "root revoking another user's API tokens" should {
    val rootEmail = "root@example.com"
    val adminEmail = "adam@example.com"

    def setUpWithRoot(): Unit = {
      setUp(tinaRoot = false)
      User.create(User(rootEmail, rootEmail, password = Some(services.PasswordHasher.default.hash("secret")), isRoot = true))
      val admin = User.create(User(adminEmail, adminEmail, password = Some(services.PasswordHasher.default.hash("secret"))))
      UserContestJdbc.createMembership(admin.getId, 10, User.ADMIN_ROLE)
    }

    def page(app: Application, ownerId: Long, as: String): Future[Result] =
      route(app, FakeRequest(GET, s"/admin/users/$ownerId/tokens")
        .withSession(Secured.UserName -> as, Secured.CurrentContestId -> "10")).get

    def tokenLog[T](f: => T): (T, Seq[String]) = {
      val logger = org.slf4j.LoggerFactory.getLogger("services.ApiTokenService").asInstanceOf[ch.qos.logback.classic.Logger]
      val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
      appender.start()
      logger.addAppender(appender)
      import scala.jdk.CollectionConverters._
      try (f, appender.list.asScala.toList.map(_.getFormattedMessage))
      finally logger.detachAppender(appender)
    }

    "list them for root, linked from their account, and revoke one, logged with ids only" in {
      withCsrfApp { app =>
        setUpWithRoot()
        val omar = userId(otherEmail)
        val plain = ApiTokenService.create(omar, "omar-cli", Some(90))
        val kept = ApiTokenService.create(omar, "omar-ci", Some(90))
        val id = ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).get.id

        val edit = route(app, FakeRequest(GET, s"/users/edit/$omar").withSession(Secured.UserName -> rootEmail)).get
        contentAsString(edit) must contain(routes.ApiTokenAdminController.tokens(omar).url)
        val listed = page(app, omar, rootEmail)
        status(listed) === OK
        contentAsString(listed) must contain("omar-cli")
        contentAsString(listed) must contain(routes.ApiTokenAdminController.revoke(omar, id).url)

        val (result, lines) = tokenLog { val r = post(app, s"/admin/users/$omar/tokens/$id/revoke", token(app), as = rootEmail); status(r); r }
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(routes.ApiTokenAdminController.tokens(omar).url)
        ApiTokenService.authenticate(plain) must beNone
        ApiTokenService.authenticate(kept) must beSome
        val line = lines.find(_.startsWith("api_token_revoked")).getOrElse("")
        line must contain(s"userId=$omar")
        line must contain(s"tokenIds=$id")
        Seq(rootEmail, otherEmail, "omar-cli", plain.take(11)).filter(line.contains) must beEmpty
      }
    }

    "revoke all of them" in {
      withCsrfApp { app =>
        setUpWithRoot()
        val omar = userId(otherEmail)
        val plains = Seq("a", "b", "c").map(n => ApiTokenService.create(omar, n, Some(90)))
        val tina = ApiTokenService.create(userId(email), "tina-cli", Some(90))
        val result = post(app, s"/admin/users/$omar/tokens/revoke", token(app), as = rootEmail)
        status(result) === SEE_OTHER
        flash(result).get("api-tokens-revoked") === Some("3")
        plains.flatMap(ApiTokenService.authenticate) must beEmpty
        ApiTokenService.authenticate(tina) must beSome
      }
    }

    "refuse anyone but root, a contest admin and the owner included, changing nothing" in {
      withCsrfApp { app =>
        setUpWithRoot()
        val omar = userId(otherEmail)
        val plain = ApiTokenService.create(omar, "omar-cli", Some(90))
        val id = ApiTokenJdbc.findByHash(ApiTokenService.hash(plain)).get.id
        forall(Seq(adminEmail, email, otherEmail)) { as =>
          status(page(app, omar, as)) === FORBIDDEN
          status(post(app, s"/admin/users/$omar/tokens/$id/revoke", token(app), as = as)) === FORBIDDEN
          status(post(app, s"/admin/users/$omar/tokens/revoke", token(app), as = as)) === FORBIDDEN
        }
        // signed out: to the sign-in page; root without the CSRF token: refused
        val signedOut = route(app, FakeRequest(GET, s"/admin/users/$omar/tokens").withHeaders(ACCEPT -> "text/html")).get
        status(signedOut) === SEE_OTHER
        status(post(app, s"/admin/users/$omar/tokens/revoke", token(app), as = rootEmail, withToken = false)) === FORBIDDEN
        // a token under another user's id, or none: not found
        status(post(app, s"/admin/users/${userId(email)}/tokens/$id/revoke", token(app), as = rootEmail)) === NOT_FOUND
        status(post(app, s"/admin/users/$omar/tokens/9999/revoke", token(app), as = rootEmail)) === NOT_FOUND
        status(page(app, 9999, rootEmail)) === NOT_FOUND
        ApiTokenService.authenticate(plain) must beSome
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
