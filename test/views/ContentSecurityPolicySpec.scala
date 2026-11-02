package views

import org.specs2.mutable.Specification
import security.ProductionFilters

import java.io.File
import scala.jdk.CollectionConverters._

/** The production content security policy (conf/application.conf's `play.filters.csp`, as Play's
  * CSPFilter builds the header; the tests' own config runs without one): scripts from this host
  * and the inline ones by hash (InlineScriptHashesSpec), never 'unsafe-inline' or 'unsafe-eval';
  * styles from this host and inline (unitegallery's
  * markup has style attributes); fonts from this host only, and images also as data: URIs,
  * which Bootstrap 5's and Tom Select's CSS use for select arrows, checkbox ticks and btn-close;
  * requests to this host and the Commons API, for the category fields' suggestions; no
  * plugins, no `<base>` elsewhere, forms to this host only, and no framing.
  */
class ContentSecurityPolicySpec extends Specification {

  private val csp = ProductionFilters.cspHeader

  private def directives: Seq[Seq[String]] = csp.split(";").toSeq.map(_.trim.split("\\s+").toSeq)

  private def directive(name: String): Seq[String] =
    directives.find(_.headOption.contains(name)).map(_.tail).getOrElse(Nil)

  "the production content security policy" should {
    "name each directive once (a browser ignores a repeated one)" in {
      val names = directives.map(_.head)
      names.distinct must_== names
    }

    "have exactly these directives: none of Play's own (reference.conf), none added unnoticed" in {
      directives.map(_.head) must containTheSameElementsAs(Seq("default-src", "script-src", "style-src", "img-src",
        "media-src", "connect-src", "object-src", "base-uri", "form-action", "frame-ancestors"))
    }

    "be sent by Play's CSPFilter, not the deprecated key, and use no nonce" in {
      val release = ProductionFilters.release
      release.getIsNull("play.filters.headers.contentSecurityPolicy") must beTrue
      release.getStringList("play.filters.enabled").asScala must contain("play.filters.csp.CSPFilter")
      release.getBoolean("play.filters.csp.reportOnly") must beFalse
      release.getBoolean("play.filters.csp.nonce.enabled") must beFalse
      csp must not(contain("nonce"))
      csp must not(contain("strict-dynamic"))
    }

    "be no wider than the policy before it moved to play.filters.csp (S7, P11)" in {
      // the policy as play.filters.headers.contentSecurityPolicy had it (B5); script-src's hashes
      // follow the inline scripts (InlineScriptHashesSpec)
      val before = Map(
        "default-src" -> Seq("'self'"),
        "style-src" -> Seq("'self'", "'unsafe-inline'"),
        "img-src" -> Seq("'self'", "data:", "https://*"),
        "media-src" -> Seq("'self'", "https://*"),
        "connect-src" -> Seq("'self'", "https://commons.wikimedia.org"),
        "object-src" -> Seq("'none'"),
        "base-uri" -> Seq("'self'"),
        "form-action" -> Seq("'self'"),
        "frame-ancestors" -> Seq("'none'")
      )
      forall(before.toSeq) { case (name, sources) =>
        directive(name).aka(name) must containTheSameElementsAs(sources)
      }
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

    "need no 'unsafe-eval' for this app's scripts: none calls eval, new Function or a string timer" in {
      // unitegallery looked its theme up with eval(name) until B5 (now window[name]); the
      // webjars' and Swagger UI's bundles are checked by the screenshots (--csp), which log
      // any violation
      val evalLike = """(?<![\w$.])eval\s*\(|\bnew\s+Function\b|(?<![\w$.])Function\s*\(|set(Timeout|Interval)\s*\(\s*["'`]""".r
      val files = new File("public/javascripts").listFiles().toSeq.filter(_.getName.endsWith(".js"))
      files must not(beEmpty)
      forall(files) { f =>
        val text = new String(java.nio.file.Files.readAllBytes(f.toPath), java.nio.charset.StandardCharsets.UTF_8)
        evalLike.findFirstIn(text).aka(f.getName) must beNone
      }
    }

    "allow styles from this host and inline (style attributes in unitegallery's markup)" in {
      directive("style-src") must containTheSameElementsAs(Seq("'self'", "'unsafe-inline'"))
    }

    "allow images from data: URIs (Bootstrap's and Tom Select's inline SVGs)" in {
      directive("img-src") must contain("data:")
      directive("img-src") must contain("'self'")
      directive("img-src") must not(contain("blob:"))
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
