package controllers

import com.typesafe.config.ConfigFactory
import modules.BlockingExecutionContext
import org.apache.pekko.actor.ActorSystem
import org.specs2.mock.Mockito
import org.specs2.specification.AfterAll
import play.api.libs.json.Json
import play.api.mvc.request.RemoteConnection
import play.api.test.{FakeRequest, PlaySpecification}
import play.api.test.Helpers.stubControllerComponents
import services.ReadinessCheck

import scala.concurrent.Await
import scala.concurrent.duration._

class ReadinessControllerSpec extends PlaySpecification with Mockito with AfterAll {
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
  override def afterAll(): Unit = { Await.result(system.terminate(), 10.seconds); () }

  "readiness" should {
    "return the packaged build only when the actual schema is ready" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() returns Some((identity \ "schemaVersion").as[String])
      val result = new ReadinessController(stubControllerComponents(), check, ec).ready.apply(FakeRequest())
      status(result) mustEqual OK
      contentAsJson(result) mustEqual identity
      header(CACHE_CONTROL, result) must beSome("no-store")
    }
    "reject an old or missing schema" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() returns Some("53")
      val result = new ReadinessController(stubControllerComponents(), check, ec).ready.apply(FakeRequest())
      status(result) mustEqual SERVICE_UNAVAILABLE
    }
    "hide driver exceptions and credentials when the database is unavailable" in {
      val check = mock[ReadinessCheck]
      check.schemaVersion() throws new RuntimeException("password=private")
      val result = new ReadinessController(stubControllerComponents(), check, ec).ready.apply(FakeRequest())
      status(result) mustEqual SERVICE_UNAVAILABLE
      contentAsJson(result) mustEqual Json.obj("status" -> "unavailable")
      contentAsString(result) must not(contain("private"))
    }
    "answer only the server itself, without a DB query for anyone else" in {
      val check = mock[ReadinessCheck]
      val controller = new ReadinessController(stubControllerComponents(), check, ec)
      for (remote <- Seq("203.0.113.5", "2001:db8::5", "localhost.example.org")) {
        val request = FakeRequest().withConnection(RemoteConnection(remote, secure = false, clientCertificateChain = None))
        val result = controller.ready.apply(request)
        status(result) mustEqual NOT_FOUND
        contentAsString(result) must not(contain("schemaVersion"))
      }
      there was no(check).schemaVersion()
    }
    "treat IPv4 and IPv6 loopback as local" in {
      Seq("127.0.0.1", "127.0.1.1", "::1", "0:0:0:0:0:0:0:1").map { address =>
        ReadinessController.isLocal(FakeRequest().withConnection(
          RemoteConnection(address, secure = false, clientCertificateChain = None)))
      } must contain(beTrue).forall
    }
  }
}
