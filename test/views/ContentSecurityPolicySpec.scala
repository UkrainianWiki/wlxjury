package views

import com.typesafe.config.ConfigFactory
import org.specs2.mutable.Specification

import java.io.File

/** The production content security policy (conf/application.conf; the tests' own config runs
  * without one): scripts from this host and the inline ones by hash (InlineScriptHashesSpec),
  * never 'unsafe-inline' or 'unsafe-eval'; styles from this host and inline (unitegallery's
  * markup has style attributes); fonts from this host only, and images also as data: URIs,
  * which Bootstrap 5's and Tom Select's CSS use for select arrows, checkbox ticks and btn-close;
  * requests to this host and the Commons API, for the category fields' suggestions; no
  * plugins, no `<base>` elsewhere, forms to this host only, and no framing.
  */
class ContentSecurityPolicySpec extends Specification {

  private val csp = ConfigFactory.parseFile(new File("conf/application.conf"))
    .getString("play.filters.headers.contentSecurityPolicy")

  private def directives: Seq[Seq[String]] = csp.split(";").toSeq.map(_.trim.split("\\s+").toSeq)

  private def directive(name: String): Seq[String] =
    directives.find(_.headOption.contains(name)).map(_.tail).getOrElse(Nil)

  "the production content security policy" should {
    "name each directive once (a browser ignores a repeated one)" in {
      val names = directives.map(_.head)
      names.distinct must_== names
    }

    "default to this host only" in {
      directive("default-src") must_== Seq("'self'")
      directive("font-src") must beEmpty
    }

    "allow scripts from this host, and inline ones only by their hash" in {
      val scriptSrc = directive("script-src")
      scriptSrc must contain("'self'")
      scriptSrc must not(contain("'unsafe-inline'"))
      scriptSrc must not(contain("'unsafe-eval'"))
      scriptSrc must not(contain("'unsafe-hashes'"))
      scriptSrc.filterNot(_ == "'self'") must contain(startWith("'sha256-")).forall
      csp must not(contain("unsafe-eval"))
    }

    "allow styles from this host and inline (style attributes in unitegallery's markup)" in {
      directive("style-src") must containTheSameElementsAs(Seq("'self'", "'unsafe-inline'"))
    }

    "allow images from data: URIs (Bootstrap's and Tom Select's inline SVGs)" in {
      directive("img-src") must contain("data:")
      directive("img-src") must contain("'self'")
    }

    "allow requests to this host and the Commons API only (category-suggest.js)" in {
      directive("connect-src") must containTheSameElementsAs(Seq("'self'", "https://commons.wikimedia.org"))
    }

    "allow no plugins, no other base URL, forms to this host only, and no framing" in {
      directive("object-src") must_== Seq("'none'")
      directive("base-uri") must_== Seq("'self'")
      directive("form-action") must_== Seq("'self'")
      directive("frame-ancestors") must_== Seq("'none'")
    }
  }
}
