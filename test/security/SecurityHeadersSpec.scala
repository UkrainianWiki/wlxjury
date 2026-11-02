package security

import com.typesafe.config.ConfigFactory
import db.scalikejdbc.PlayTestDb
import play.api.Application
import play.api.mvc.Result
import play.api.test.{FakeRequest, PlaySpecification}

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files
import scala.concurrent.duration.Duration
import scala.concurrent.{Await, Future}
import scala.jdk.CollectionConverters._

/** G6 (security batch S7, P4, P17): real responses through the router and the release's filters
  * (`conf/application.conf`, [[ProductionFilters]]) carry the security headers: the content
  * security policy (Play's CSPFilter, exactly the configured one), `X-Frame-Options`,
  * `X-Content-Type-Options`, `Referrer-Policy`, `Permissions-Policy`, `X-XSS-Protection` and
  * `X-Permitted-Cross-Domain-Policies`. A page, a token link's page (which keeps its
  * `no-referrer`), a redirect, JSON errors, Swagger UI (`/api/docs`) with its scripts and image, a
  * webjar, a CSRF refusal and a bad Host's 400 all have them: the header filters run outermost.
  * A server error and a request Play can't parse, which Play's server answers outside the filters
  * (through the error handler), have them too: checked through a running server.
  */
class SecurityHeadersSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val release = ProductionFilters.release

  private val permissionsPolicy = release.getString("wlxjury.headers.permissionsPolicy")

  /** A page, with a browser's Accept header. */
  private def get(app: Application, path: String, headers: (String, String)*): Future[Result] =
    route(app, FakeRequest(GET, path).withHeaders((ACCEPT -> BrowserAccept) +: headers: _*)).get

  private val BrowserAccept = "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8"

  /** A file a page loads (a script, a style sheet, an image), as a browser asks for it. */
  private def file(app: Application, path: String): Future[Result] =
    route(app, FakeRequest(GET, path).withHeaders(ACCEPT -> "*/*")).get

  /** The security headers every response has; `referrer` is the Referrer-Policy expected. */
  private def secured(result: Future[Result], referrer: String = "same-origin") = {
    header("Content-Security-Policy", result) must beSome(ProductionFilters.cspHeader)
    header("Content-Security-Policy-Report-Only", result) must beNone
    header("X-Frame-Options", result) must beSome("DENY")
    header("X-Content-Type-Options", result) must beSome("nosniff")
    header("Referrer-Policy", result) must beSome(referrer)
    header("Permissions-Policy", result) must beSome(permissionsPolicy)
    header("X-XSS-Protection", result) must beSome("0")
    header("X-Permitted-Cross-Domain-Policies", result) must beSome("master-only")
  }

  /** `path`, following redirects (at most three), and the path it ends at. */
  private def follow(app: Application, path: String, hops: Int = 3): (String, Future[Result]) = {
    val result = get(app, path)
    redirectLocation(result) match {
      case Some(location) if hops > 0 && status(result) / 100 == 3 =>
        secured(result)
        val next = new java.net.URI("http://localhost" + path).resolve(location)
        next.getHost must_== "localhost"
        follow(app, next.getRawPath + Option(next.getRawQuery).fold("")("?" + _), hops - 1)
      case _ => (path, result)
    }
  }

  "the release's header settings" should {
    "run the header filters first, then the allowed hosts, the sessions and CSRF" in {
      release.getStringList("play.filters.enabled").asScala.toSeq === Seq(
        "modules.AppSecurityHeadersFilter", "play.filters.csp.CSPFilter", "play.filters.hosts.AllowedHostsFilter",
        "modules.SessionLifetimeFilter", "play.filters.csrf.CSRFFilter")
      release.getStringList("play.filters.disabled").isEmpty must beTrue
    }

    "turn the browser features no page uses off, and allow none" in {
      val features = permissionsPolicy.split(",").toSeq.map(_.trim)
      forall(Seq("camera", "microphone", "geolocation", "payment", "usb", "display-capture")) { feature =>
        features must contain(s"$feature=()")
      }
      forall(features)(_ must beMatching("""[a-z-]+=\(\)"""))
    }

    "send no referrer to other sites, and leave Play's other headers as they are" in {
      release.getString("play.filters.headers.referrerPolicy") === "same-origin"
      release.getString("play.filters.headers.frameOptions") === "DENY"
      release.getString("play.filters.headers.contentTypeOptions") === "nosniff"
      release.getBoolean("play.filters.headers.allowActionSpecificHeaders") must beFalse
    }

    "be mirrored by the tests' config, so that the screenshot runs send the same headers" in {
      val tests = ConfigFactory.parseFile(new File("test/resources/application.conf"))
      tests.getString("play.filters.headers.referrerPolicy") === release.getString("play.filters.headers.referrerPolicy")
      tests.getString("wlxjury.headers.permissionsPolicy") === permissionsPolicy
      tests.getString("play.filters.headers.xssProtection") === release.getString("play.filters.headers.xssProtection")
      tests.getStringList("play.filters.enabled").asScala.toSeq ===
        release.getStringList("play.filters.enabled").asScala.toSeq.filterNot(_ == "play.filters.csp.CSPFilter")
    }

    "have no route that goes without the policy or the CSRF check (nocsp, nocsrf)" in {
      val routes = new String(Files.readAllBytes(new File("conf/routes").toPath), UTF_8)
      routes.linesIterator.filter(_.trim.startsWith("+")).filter(l => l.contains("nocsp") || l.contains("nocsrf")).toSeq must beEmpty
    }
  }

  "a response through the release's filters" should {
    "carry the headers: a page" in {
      ProductionFilters.withSharedApp { app =>
        val page = get(app, "/auth")
        status(page) === OK
        contentType(page) must beSome("text/html")
        secured(page)
      }
    }

    "carry the headers, with its own no-referrer: a mailed link's page, whose URL holds the token" in {
      ProductionFilters.withSharedApp { app =>
        val page = get(app, "/invitation/not-a-token")
        status(page) === NOT_FOUND
        secured(page, referrer = "no-referrer")
      }
    }

    "carry the headers: a redirect" in {
      ProductionFilters.withSharedApp { app =>
        val result = get(app, "/")
        status(result) / 100 === 3
        secured(result)
      }
    }

    "carry the headers: JSON errors" in {
      ProductionFilters.withSharedApp { app =>
        val api = route(app, FakeRequest(GET, "/api/contests")).get
        status(api) === UNAUTHORIZED
        contentType(api) must beSome("application/json")
        secured(api)

        val script = get(app, "/users/edit/1", "X-Requested-With" -> "XMLHttpRequest")
        status(script) === UNAUTHORIZED
        contentType(script) must beSome("application/json")
        secured(script)
      }
    }

    "carry the headers: Swagger UI's page, its scripts, styles and image (P17)" in {
      ProductionFilters.withSharedApp { app =>
        // Tapir streams the files
        implicit val materializer: org.apache.pekko.stream.Materializer = app.materializer
        val (path, page) = follow(app, "/api/docs")
        status(page) === OK
        contentType(page) must beSome("text/html")
        secured(page)
        val html = contentAsString(page)

        // no inline script (script-src has only this host and the app's own hashes), no inline
        // event handler; every script, style and icon from this host, by a relative URL
        val scripts = """(?is)<script\b([^>]*)>(.*?)</script>""".r.findAllMatchIn(html).toSeq
        scripts must not(beEmpty)
        forall(scripts)(_.group(2).trim must beEmpty)
        html must not(beMatching("""(?is).*\son[a-z]+\s*=.*"""))
        val urls = """(?i)\s(?:src|href)\s*=\s*"([^"]+)"""".r.findAllMatchIn(html).map(_.group(1)).toSeq
        urls must not(beEmpty)
        forall(urls)(_ must not(beMatching("""(?i)^(https?:|//|data:|javascript:).*""")))

        val base = new java.net.URI("http://localhost" + path)
        forall(urls) { url =>
          val resolved = base.resolve(url)
          val result = file(app, resolved.getRawPath)
          status(result) aka url must_== OK
          secured(result)
        }
        forall(Seq("swagger-initializer.js", "favicon-32x32.png", "docs.yaml")) { name =>
          val result = file(app, base.resolve(name).getRawPath)
          status(result) aka name must_== OK
          secured(result)
        }
        contentType(file(app, base.resolve("favicon-32x32.png").getRawPath)) must beSome("image/png")
        // the initializer turns the validator badge (an image from validator.swagger.io) off
        contentAsString(file(app, base.resolve("swagger-initializer.js").getRawPath)) must contain("validatorUrl: null")
      }
    }

    "carry the headers: a webjar's file" in {
      ProductionFilters.withSharedApp { app =>
        val result = file(app, "/webjars/jquery/3.7.1/jquery.min.js")
        status(result) === OK
        secured(result)
      }
    }

    "carry the headers: a CSRF refusal (a cookieless sign-in without the token, N6)" in {
      ProductionFilters.withSharedApp { app =>
        val result = route(app, FakeRequest(POST, "/auth").withHeaders(ACCEPT -> "text/html")
          .withFormUrlEncodedBody("login" -> "a@example.com", "password" -> "x")).get
        status(result) === FORBIDDEN
        secured(result)
      }
    }

    "carry the headers: a request for another host" in {
      ProductionFilters.withSharedApp { app =>
        val result = get(app, "/auth", HOST -> "evil.example.com")
        status(result) === BAD_REQUEST
        secured(result)
      }
    }
  }

  "a request for another host" should {
    "get its 400 before any session work, with an old or an expired session cookie" in {
      ProductionFilters.withSharedApp { app =>
        // a session from before S3 (its upgrade reads the database) and one past its absolute limit
        val old = Seq(controllers.Secured.UserName -> "juror@example.com")
        val expired = Seq(controllers.Secured.UserName -> "juror@example.com", controllers.Secured.AccountId -> "1",
          controllers.Secured.AuthVersion -> "0", controllers.SessionLifetime.IssuedAt -> "1",
          controllers.SessionLifetime.LastSeen -> "1")
        val (results, lines) = authLog {
          Seq(old, expired).map { session =>
            val result = route(app, FakeRequest(POST, "/auth")
              .withHeaders(HOST -> "evil.example.com", ACCEPT -> BrowserAccept)
              .withSession(session: _*).withFormUrlEncodedBody("login" -> "x", "password" -> "y")).get
            Await.result(result, Duration(10, "seconds"))
          }
        }
        forall(results) { result =>
          result.header.status === BAD_REQUEST
          result.newSession must beNone
          result.newCookies must beEmpty
        }
        lines must beEmpty
        // the same expired session for this host is signed out: the sessions' filter runs and logs it
        val (_, here) = authLog {
          status(route(app, FakeRequest(GET, "/auth").withHeaders(ACCEPT -> BrowserAccept).withSession(expired: _*)).get)
        }
        here must contain(startWith("session_expired"))
      }
    }
  }

  "a response Play's server makes outside the filters" should {
    "carry the headers: a server error" in {
      import play.api.routing.sird.{GET => Get, _}
      val action = play.api.test.Helpers.stubControllerComponents().actionBuilder { _ =>
        throw new IllegalStateException("a failure the error handler answers")
      }
      val boom = play.api.routing.Router.from { case Get(p"/boom") => action }
      ProductionFilters.withAppWith(Map.empty, play.api.inject.bind[play.api.routing.Router].toInstance(boom)) { app =>
        served(app) { port =>
          val (code, headers) = raw(port, "GET /boom HTTP/1.1")
          code === INTERNAL_SERVER_ERROR
          securedRaw(headers)
        }
      }
    }

    "carry the headers: a request Play can't parse" in {
      // an app of its own: the server's stop stops the app it serves
      ProductionFilters.withApp { app =>
        served(app) { port =>
          val (code, headers) = raw(port, "GET /%zz HTTP/1.1")
          code === BAD_REQUEST
          securedRaw(headers)
        }
      }
    }
  }

  /** The lines the `security.auth` logger (the sessions' filter) writes while `f` runs. */
  private def authLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.auth").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    val level = logger.getLevel
    logger.setLevel(ch.qos.logback.classic.Level.DEBUG)
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally {
      logger.detachAppender(appender)
      logger.setLevel(level)
    }
  }

  /** `body` with `app` served by Play's server (Netty) on a free port. */
  private def served[T](app: Application)(body: Int => T): T = {
    val port = { val s = new java.net.ServerSocket(0); try s.getLocalPort finally s.close() }
    val server = play.api.test.TestServer(port, app)
    server.start()
    try body(port)
    finally server.stop()
  }

  /** The status and headers (names in lower case) of `requestLine` sent as raw HTTP/1.1. */
  private def raw(port: Int, requestLine: String): (Int, Map[String, String]) = {
    val socket = new java.net.Socket("localhost", port)
    try {
      socket.setSoTimeout(30000)
      val out = socket.getOutputStream
      val crlf = "\r\n"
      out.write((requestLine + crlf + s"Host: localhost:$port" + crlf + s"Accept: $BrowserAccept" + crlf +
        "Connection: close" + crlf + crlf).getBytes(UTF_8))
      out.flush()
      val lines = scala.io.Source.fromInputStream(socket.getInputStream, "UTF-8").getLines().takeWhile(_.nonEmpty).toList
      val code = lines.head.split(" ")(1).toInt
      val headers = lines.tail.map { l =>
        val i = l.indexOf(':')
        l.take(i).trim.toLowerCase -> l.drop(i + 1).trim
      }.toMap
      (code, headers)
    } finally socket.close()
  }

  /** [[secured]] for a raw response's headers. */
  private def securedRaw(headers: Map[String, String]) = {
    headers.get("content-security-policy") must beSome(ProductionFilters.cspHeader)
    headers.get("x-frame-options") must beSome("DENY")
    headers.get("x-content-type-options") must beSome("nosniff")
    headers.get("referrer-policy") must beSome("same-origin")
    headers.get("permissions-policy") must beSome(permissionsPolicy)
    headers.get("x-xss-protection") must beSome("0")
    headers.get("x-permitted-cross-domain-policies") must beSome("master-only")
  }
}
