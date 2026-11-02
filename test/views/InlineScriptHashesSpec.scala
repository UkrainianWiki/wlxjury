package views

import play.api.i18n.{Lang, Messages}
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import play.twirl.api.Html

import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.util.Base64

/** The inline scripts that the content security policy allows by their SHA-256
  * (conf/application.conf's `play.filters.csp.directives.script-src`): the layout's theme script
  * and the large views' section restore (views.NoInlineScriptsSpec lists why they stay inline).
  * This renders the
  * pages that hold them, hashes each inline script as a browser does and checks that its hash
  * is in the policy, and that the policy has no hash that no page renders. Any change to a
  * script's text fails here until its new hash replaces the old one.
  *
  * A browser hashes a script's text as parsed: HTML parsing turns CRLF and CR into LF. The
  * templates are checked out with LF everywhere (.gitattributes), so Windows and Linux builds
  * render the same bytes anyway.
  */
class InlineScriptHashesSpec extends PlaySpecification {

  private val scriptSrc: Seq[String] = security.ProductionFilters.cspDirectives.getOrElse("script-src", Nil)

  private val Script = """(?is)<script\b([^>]*)>(.*?)</script>""".r
  private val Src    = """(?i)(?<![\w-])src\s*=""".r
  private val Json   = """(?i)(?<![\w-])type\s*=\s*["']application/json["']""".r

  /** The text of each script element in `html` that a browser runs from the page itself: no
    * `src`, and not a JSON data block. */
  def inlineScripts(html: String): Seq[String] =
    Script.findAllMatchIn(html).toSeq
      .filter(m => Src.findFirstIn(m.group(1)).isEmpty && Json.findFirstIn(m.group(1)).isEmpty)
      .map(_.group(2))

  /** The CSP source expression for `script`, as a browser computes it. */
  def hash(script: String): String = {
    val parsed = script.replace("\r\n", "\n").replace('\r', '\n')
    "'sha256-" + Base64.getEncoder.encodeToString(
      MessageDigest.getInstance("SHA-256").digest(parsed.getBytes(UTF_8))) + "'"
  }

  private lazy val layout: String = {
    implicit val request: FakeRequest[_] = FakeRequest("GET", "/")
    implicit val messages: Messages = Helpers.stubMessagesApi().preferred(Seq(Lang("en")))
    contentAsString(views.html.layout("A title")(Html(""))(Html("<p>body</p>")))
  }

  private lazy val pages: Seq[(String, String)] = Seq(
    "layout"            -> layout,
    "large (binary)"    -> LargeViewRenders.large(LargeViewRenders.binaryRound, LargeViewRenders.middle),
    "large (rated)"     -> LargeViewRenders.large(LargeViewRenders.ratedRound, LargeViewRenders.middle),
    "contest file"      -> LargeViewRenders.contestFile(LargeViewRenders.middle)
  )

  "the content security policy's script-src" should {
    "allow scripts from this host and inline ones by hash only" in {
      scriptSrc must contain("'self'")
      scriptSrc.filterNot(_ == "'self'") must contain(beMatching("'sha256-[A-Za-z0-9+/]{43}='")).forall
    }

    "list the hash of every inline script that the layout and the large views render" in {
      forall(pages) { case (name, html) =>
        val scripts = inlineScripts(html)
        scripts must not(beEmpty)
        forall(scripts) { script =>
          (scriptSrc must contain(hash(script)))
            .updateMessage(_ => s"$name renders an inline script whose hash ${hash(script)} isn't in " +
              s"conf/application.conf's script-src: ${script.trim.take(80)}")
        }
      }
    }

    "list no hash that no page renders" in {
      val rendered = pages.flatMap { case (_, html) => inlineScripts(html) }.map(hash).toSet
      scriptSrc.filter(_.startsWith("'sha256-")).toSet must_== rendered
    }

    "match each script to its page: the theme script on every page, the restore in both large views" in {
      val theme   = inlineScripts(layout).map(hash)
      theme must haveSize(1)
      val restore = inlineScripts(LargeViewRenders.contestFile(LargeViewRenders.middle)).map(hash).filterNot(theme.contains)
      restore must haveSize(1)
      forall(pages) { case (_, html) => inlineScripts(html).map(hash) must contain(theme.head) }
      forall(pages.filter(_._1 != "layout")) { case (_, html) => inlineScripts(html).map(hash) must contain(restore.head) }
    }
  }

  "the hash" should {
    "be a browser's, over the parsed text (line ends as LF)" in {
      // the example of the CSP spec's hash-source: sha256 of "alert('Hello, world.');"
      hash("alert('Hello, world.');") must_== "'sha256-qznLcsROx4GACP2dm0UCKCzCG+HiZ1guq6ZZDob/Tng='"
      hash("a\r\nb") must_== hash("a\nb")
    }
  }
}
