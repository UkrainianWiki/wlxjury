package views

import com.typesafe.config.ConfigFactory
import play.api.i18n.{Lang, Messages}
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import play.twirl.api.Html

import java.io.File

/** The shared page shell (layout.scala.html): frameworks served from this host (webjars),
  * main.css after them, and titles localised once, as every shell wraps it.
  */
class LayoutSpec extends PlaySpecification {

  implicit val request: FakeRequest[_] = FakeRequest("GET", "/")
  implicit val messages: Messages =
    Helpers.stubMessagesApi(Map("en" -> Map("users" -> "Users", "gallery" -> "Gallery"))).preferred(Seq(Lang("en")))

  private def page(html: Html) = contentAsString(html)
  private val webjar = """(?:src|href)="/webjars/([^"]+)"""".r

  "the layout" should {
    val html = page(views.html.layout("A title")(Html("<script>var head;</script>"))(Html("<p>body</p>")))

    "load nothing from another host" in {
      html must not(beMatching("""(?s).*(src|href)="(https?:)?//.*"""))
    }

    "serve Bootstrap, Font Awesome and jQuery from their webjars on the classpath" in {
      val files = webjar.findAllMatchIn(html).map(_.group(1)).toList
      files must containAllOf(Seq(
        "bootstrap/3.3.7-1/css/bootstrap.min.css",
        "font-awesome/4.6.3/css/font-awesome.min.css",
        "jquery/3.2.1/jquery.min.js",
        "bootstrap/3.3.7-1/js/bootstrap.min.js"
      ))
      forall(files) { f =>
        getClass.getClassLoader.getResource("META-INF/resources/webjars/" + f) must not(beNull)
        AppRoutes.actionFor("GET", "/webjars/" + f) must beSome("controllers.Assets.at")
      }
    }

    "load main.css after the framework's CSS and the shell's own head after both" in {
      val bootstrap = html.indexOf("bootstrap.min.css")
      val main      = html.indexOf("stylesheets/main.css")
      bootstrap must be_>=(0)
      main must be_>(bootstrap)
      html.indexOf("var head;") must be_>(main)
    }

    "show the title as given" in {
      html must contain("<title>A title</title>")
    }
  }

  "the shells" should {
    "localise their title key once" in {
      page(views.html.mainAdmin("users")(Html(""))) must contain("<title>Users</title>")
      page(views.html.main("gallery")(Html(""))) must contain("<title>Gallery</title>")
    }
  }

  "the content security policy" should {
    "allow scripts, styles and fonts from this host only" in {
      val csp = ConfigFactory.parseFile(new File("conf/application.conf"))
        .getString("play.filters.headers.contentSecurityPolicy")
      val defaultSrc = csp.split(";").map(_.trim).find(_.startsWith("default-src")).get
      defaultSrc must not contain "http"
      defaultSrc must startWith("default-src 'self'")
    }
  }
}
