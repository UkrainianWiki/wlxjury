package controllers

import com.typesafe.config.ConfigFactory
import modules.BlockingExecutionContext
import org.apache.pekko.actor.ActorSystem
import org.specs2.mock.Mockito
import org.specs2.specification.AfterAll
import org.intracer.wmua.JuryTestHelpers
import org.scalawiki.MwBot
import play.api.libs.json.{JsObject, JsString, Json}
import play.api.mvc.request.RemoteConnection
import play.api.test.{FakeRequest, PlaySpecification}
import play.api.test.Helpers.stubControllerComponents
import services.ReadinessCheck
import services.imports.CommonsLogin

import java.time.Instant

import scala.concurrent.{Await, Future, Promise}
import scala.concurrent.duration._

class ReadinessControllerSpec extends PlaySpecification with Mockito with JuryTestHelpers with AfterAll {
  sequential
  private val system = ActorSystem("readiness-test", ConfigFactory.parseString("""
    blocking-dispatcher {
      type = Dispatcher
      executor = "thread-pool-executor"
      thread-pool-executor.fixed-pool-size = 1
    }
  """).withFallback(ConfigFactory.load()))
  private val ec = new BlockingExecutionContext(system)
  private val identity = {
    val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
    try Json.parse(stream) finally stream.close()
  }
  private val noAccount = CommonsLogin.anonymous(mock[MwBot])
  private val credentials = Some(CommonsLogin.Credentials("ExampleBot@jury", "secret"))
  private val anonymousInfo = """{"batchcomplete":"","query":{"userinfo":{"id":0,"name":"203.0.113.7","anon":""}}}"""
  private val botInfo = """{"batchcomplete":"","query":{"userinfo":{"id":678,"name":"ExampleBot","rights":["read","bot","apihighlimits"]}}}"""
  private val start = Instant.parse("2026-11-26T08:49:00.250Z")

  private def readyCheck() = {
    val check = mock[ReadinessCheck]
    check.schemaVersion() returns Some((identity \ "schemaVersion").as[String])
    check.isolation() returns "READ-COMMITTED"
    check
  }

  /** `/health/ready`'s answer with `login` as the Commons login. */
  private def ready(login: CommonsLogin) =
    new ReadinessController(stubControllerComponents(), readyCheck(), login, ec).ready.apply(FakeRequest())

  override def afterAll(): Unit = { Await.result(system.terminate(), 10.seconds); () }

  "readiness" should {
    "return the packaged build only when the actual schema is ready" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() returns Some((identity \ "schemaVersion").as[String])
      check.isolation() returns "READ-COMMITTED"
      val result = new ReadinessController(stubControllerComponents(), check, noAccount, ec).ready.apply(FakeRequest())
      status(result) mustEqual OK
      contentAsJson(result) mustEqual identity.as[JsObject] + ("commons" -> JsString("anonymous (no account configured)"))
      header(CACHE_CONTROL, result) must beSome("no-store")
    }
    "reject an old or missing schema" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() returns Some("53")
      check.isolation() returns "READ-COMMITTED"
      val result = new ReadinessController(stubControllerComponents(), check, noAccount, ec).ready.apply(FakeRequest())
      status(result) mustEqual SERVICE_UNAVAILABLE
    }
    "refuse a pool that doesn't run at READ COMMITTED (the write paths rely on it, W1)" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() returns Some((identity \ "schemaVersion").as[String])
      check.isolation() returns "REPEATABLE-READ"
      val result = new ReadinessController(stubControllerComponents(), check, noAccount, ec).ready.apply(FakeRequest())
      status(result) mustEqual SERVICE_UNAVAILABLE
      contentAsJson(result) mustEqual Json.obj("status" -> "unavailable")
    }
    "hide driver exceptions and credentials when the database is unavailable" in {
      val check = mock[ReadinessCheck]
      // the isolation check passes, so the request reaches the schema query that throws
      check.isolation() returns "READ-COMMITTED"
      check.schemaVersion() throws new RuntimeException("password=private")
      val result = new ReadinessController(stubControllerComponents(), check, noAccount, ec).ready.apply(FakeRequest())
      status(result) mustEqual SERVICE_UNAVAILABLE
      contentAsJson(result) mustEqual Json.obj("status" -> "unavailable")
      contentAsString(result) must not(contain("private"))
      there was one(check).schemaVersion()
    }
    "answer only the server itself, without a DB query for anyone else" in {
      val check = mock[ReadinessCheck]
      val controller = new ReadinessController(stubControllerComponents(), check, noAccount, ec)
      for (remote <- Seq("203.0.113.5", "2001:db8::5", "localhost.example.org")) {
        val request = FakeRequest().withConnection(RemoteConnection(remote, secure = false, clientCertificateChain = None))
        val result = controller.ready.apply(request)
        status(result) mustEqual NOT_FOUND
        contentAsString(result) must not(contain("schemaVersion"))
      }
      there was no(check).schemaVersion()
      there was no(check).isolation()
    }
    "say the Commons login is not checked yet before its first check" in {
      val result = ready(new CommonsLogin(mockBot(), credentials))
      status(result) mustEqual OK
      (contentAsJson(result) \ "commons").as[String] mustEqual "not checked yet"
    }
    "say the Commons login is logged in, without the account's name" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(botInfo)
      val login = new CommonsLogin(mw, credentials)
      login.ensure()
      val result = ready(login)
      status(result) mustEqual OK
      (contentAsJson(result) \ "commons").as[String] mustEqual "logged in, apihighlimits: yes"
      contentAsString(result) must not(contain("ExampleBot"))
    }
    "stay ready when Commons refuses the login, and say when it tries again" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymousInfo)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val login = new CommonsLogin(mw, credentials, now = () => 0L, clock = () => start)
      login.ensure()
      val result = ready(login)
      status(result) mustEqual OK
      val json = contentAsJson(result)
      (json \ "commons").as[String] mustEqual "refused at 2026-11-26T08:49:00Z, next try at 2026-11-26T08:54:00Z at the earliest"
      (json \ "build").as[String] mustEqual (identity \ "build").as[String]
      (json \ "schemaVersion").as[String] mustEqual (identity \ "schemaVersion").as[String]
      contentAsString(result) must (not(contain("ExampleBot")) and not(contain("secret")))
    }
    "not wait for the start-up Commons check" in {
      val mw = mockBot()
      val answer = Promise[String]()
      mw.post(CommonsLogin.UserInfoParams) returns answer.future
      val login = new CommonsLogin(mw, credentials)
      // on another pool: the spec's blocking pool has one thread, which readiness needs
      val checkEc = scala.concurrent.ExecutionContext.fromExecutorService(java.util.concurrent.Executors.newSingleThreadExecutor())
      try {
        val started = System.nanoTime()
        val done = modules.CommonsLoginStartupCheck.start(login)(checkEc)
        (System.nanoTime() - started).nanos must be_<(1.second)
        done.isCompleted must beFalse
        val result = ready(login)
        status(result) mustEqual OK
        (contentAsJson(result) \ "commons").as[String] mustEqual "not checked yet"
        answer.success(botInfo)
        Await.result(done, 5.seconds) mustEqual CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
        (contentAsJson(ready(login)) \ "commons").as[String] mustEqual "logged in, apihighlimits: yes"
      } finally {
        if (!answer.isCompleted) answer.success(botInfo)
        checkEc.shutdown()
      }
    }
    "treat IPv4 and IPv6 loopback as local" in {
      Seq("127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1").map { address =>
        ReadinessController.isLocal(FakeRequest().withConnection(
          RemoteConnection(address, secure = false, clientCertificateChain = None)))
      } must contain(beTrue).forall
    }
  }
}
