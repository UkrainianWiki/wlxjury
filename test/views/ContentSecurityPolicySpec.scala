package views

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

import java.io.File

/** The production content security policy (conf/application.conf; the tests' own config runs
  * without one): scripts, styles and fonts from this host only, and images also as data: URIs,
  * which Bootstrap 5's and Tom Select's CSS use for select arrows, checkbox ticks and btn-close.
  */
class ContentSecurityPolicySpec extends Specification {

  private val csp = ConfigFactory.parseFile(new File("conf/application.conf"))
    .getString("play.filters.headers.contentSecurityPolicy")

  private def directive(name: String): Seq[String] =
    csp.split(";").map(_.trim.split("\\s+").toSeq).find(_.headOption.contains(name)).map(_.tail).getOrElse(Nil)

  "the production content security policy" should {
    "allow scripts, styles and fonts from this host only" in {
      val defaultSrc = directive("default-src")
      defaultSrc must contain("'self'")
      defaultSrc.filterNot(_.startsWith("'")) must beEmpty
      directive("script-src") must beEmpty
      directive("style-src") must beEmpty
      directive("font-src") must beEmpty
    }

    "allow images from data: URIs (Bootstrap's and Tom Select's inline SVGs)" in {
      directive("img-src") must contain("data:")
      directive("img-src") must contain("'self'")
    }
  }
}
