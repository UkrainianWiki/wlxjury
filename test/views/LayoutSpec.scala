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
  // a script or stylesheet from another host (images and links to Commons are fine)
  private val external = """(?s).*<(script|link)[^>]*(src|href)="(https?:)?//.*"""
  private val webjar = """(?:src|href)="/webjars/([^"]+)"""".r

  "the layout" should {
    val html = page(views.html.layout("A title")(Html("<script>var head;</script>"))(Html("<p>body</p>")))

    "load no script or stylesheet from another host" in {
      html must not(beMatching(external))
    }

    "serve Bootstrap, Bootstrap Icons and jQuery from their webjars on the classpath" in {
      val files = webjar.findAllMatchIn(html).map(_.group(1)).toList
      files must containAllOf(Seq(
        "bootstrap/5.3.8/css/bootstrap.min.css",
        "bootstrap-icons/1.13.1/font/bootstrap-icons.min.css",
        "jquery/3.7.1/jquery.min.js",
        "bootstrap/5.3.8/js/bootstrap.bundle.min.js"
      ))
      forall(files) { f =>
        getClass.getClassLoader.getResource("META-INF/resources/webjars/" + f) must not(beNull)
        AppRoutes.actionFor("GET", "/webjars/" + f) must beSome("controllers.WebJars.at")
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

    "set Bootstrap's colour mode: light by default, dark if asked" in {
      html must contain("""data-bs-theme="light"""")
      page(views.html.layout("t", theme = "dark")(Html(""))(Html(""))) must contain("""data-bs-theme="dark"""")
      page(views.html.layout("t", theme = "\"><x")(Html(""))(Html(""))) must contain("""data-bs-theme="light"""")
    }
  }

  "the shells" should {
    "load no script or stylesheet from another host" in {
      val pages = Seq(
        page(views.html.main("gallery")(Html(""))),
        page(views.html.mainAdmin("users")(Html(""))),
        LargeViewRenders.large(LargeViewRenders.ratedRound, LargeViewRenders.middle),
        LargeViewRenders.contestFile(LargeViewRenders.middle)
      )
      forall(pages) { p =>
        p must contain("/webjars/jquery/")
        p must not(beMatching(external))
      }
    }

    "localise their title key once" in {
      page(views.html.mainAdmin("users")(Html(""))) must contain("<title>Users</title>")
      page(views.html.main("gallery")(Html(""))) must contain("<title>Gallery</title>")
    }
  }
}
