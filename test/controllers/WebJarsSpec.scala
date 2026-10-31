package controllers

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification
import play.api.http.{DefaultFileMimeTypes, DefaultHttpErrorHandler, FileMimeTypesConfiguration}
import play.api.test.{FakeRequest, Helpers}
import play.api.{Configuration, Environment, Mode}

import java.io.File
import java.util.Properties
import scala.concurrent.Await
import scala.concurrent.duration._

/** /webjars serves the webjars the pages load, cached for a year (their paths have their
  * versions), and no other webjar on the classpath.
  */
class WebJarsSpec extends Specification {

  // conf/application.conf's assets settings, as in production
  lazy val config = Configuration(
    ConfigFactory.parseFile(new File("conf/application.conf")).withOnlyPath("play.assets")
      .withFallback(ConfigFactory.defaultReference()).resolve()
  )
  lazy val env = Environment.simple(mode = Mode.Prod)
  lazy val meta = new DefaultAssetsMetadata(
    AssetsConfiguration.fromConfiguration(config, Mode.Prod),
    env.resource _,
    new DefaultFileMimeTypes(FileMimeTypesConfiguration(Map("js" -> "text/javascript", "css" -> "text/css")))
  )
  lazy val controller = new WebJars(new Assets(new DefaultHttpErrorHandler(), meta, env),
    new DefaultHttpErrorHandler(), Helpers.stubControllerComponents())

  def get(file: String) = Await.result(controller.at(file)(FakeRequest("GET", "/webjars/" + file)), 10.seconds)

  "the pages' webjars" should {
    "be served and cached for a year" in {
      forall(Seq("bootstrap/3.3.7-1/css/bootstrap.min.css", "bootstrap/3.3.7-1/js/bootstrap.min.js",
          "jquery/3.2.1/jquery.min.js", "font-awesome/4.6.3/css/font-awesome.min.css")) { file =>
        val result = get(file)
        result.header.status === 200
        result.header.headers.get("Cache-Control") must beSome("public, max-age=31536000, immutable")
      }
    }
  }

  "another webjar" should {
    "be a 404 although it is on the classpath" in {
      val props = Option(getClass.getClassLoader.getResourceAsStream("META-INF/maven/org.webjars/swagger-ui/pom.properties"))
      props must beSome
      val version = { val p = new Properties(); p.load(props.get); p.getProperty("version") }
      val file = s"swagger-ui/$version/index.html"
      getClass.getClassLoader.getResource("META-INF/resources/webjars/" + file) must not(beNull)
      get(file).header.status === 404
    }

    "be a 404 reached through a served one's path" in {
      WebJars.served("bootstrap/../swagger-ui/x/index.html") must beFalse
      WebJars.served("bootstrap/%2e%2e/swagger-ui/x/index.html") must beFalse
      WebJars.served("bootstrap%2F..%2Fswagger-ui/x/index.html") must beFalse
      get("bootstrap/../swagger-ui/x/index.html").header.status === 404
    }
  }
}
