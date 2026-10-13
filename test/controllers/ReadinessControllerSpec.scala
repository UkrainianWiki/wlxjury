package controllers

import com.typesafe.config.ConfigFactory
import modules.BlockingExecutionContext
import org.apache.pekko.actor.ActorSystem
import org.specs2.mock.Mockito
import org.specs2.specification.AfterAll
import play.api.libs.json.Json
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
  }
}
