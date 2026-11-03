package views

import org.specs2.mutable.Specification

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The templates hold no script code, so that the content security policy can do without
  * 'unsafe-inline' (98a8a79e:docs/plans/2026-11-01-open-followups.md, section 5 and 2.4): every
  * `<script>` has a `src` (public/javascripts, reading the page's data-* attributes) or is a
  * JSON data block (`type="application/json"`, which no browser runs), and no element has an
  * inline `on…=` handler. The only inline scripts are:
  *  - the layout's theme script, which sets the colour mode before any stylesheet loads, so it
  *    must run before the first paint;
  *  - the large views' restore of their side sections (large-sections.js), right after the
  *    side column: Chrome paints frames before DOMContentLoaded (scripts/ui_checks.py saw
  *    them), so a restore then would show the sections in their default states first.
  * Their text never changes, so the policy can allow them by their hashes.
  */
class NoInlineScriptsSpec extends Specification {

  /** Each template's text, without its Twirl comments. */
  private lazy val templates: Seq[(String, String)] = {
    val walk = Files.walk(Paths.get("app", "views"))
    try
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && p.toString.endsWith(".scala.html"))
        .toList
        .map { (p: Path) =>
          p.toString.replace('\\', '/') ->
            new String(Files.readAllBytes(p), UTF_8).replaceAll("(?s)@\\*.*?\\*@", "")
        }
    finally walk.close()
  }

  private val Script    = """(?is)<script\b(.*?)>(.*?)</script>""".r
  /** A src attribute, not data-src. */
  private val Src       = """(?i)(?<![\w-])src\s*=""".r
  private val Json      = """(?i)(?<![\w-])type\s*=\s*["']application/json["']""".r
  /** An inline handler anywhere in the markup (outside script bodies): onerror="…", onClick='…',
    * onload=@…; the whole template is searched, as a tag may hold Twirl's `=>` or `>` before it. */
  private val Handler   = """(?i)(?<![\w-])on[a-z]+\s*=\s*["'@]""".r
  private val JsUrl     = """(?i)javascript:""".r
  private val Restore   = "if (window.wlxLargeSections) wlxLargeSections.restore(document);"
  private val RestoreIn = Set("app/views/large/large.scala.html", "app/views/large/contestFile.scala.html")

  /** The kind of each script element without a src in `text`: "theme", "restore", "json" or
    * "code". */
  def scriptKinds(file: String, text: String): Seq[String] =
    for {
      m <- Script.findAllMatchIn(text).toSeq
      attrs = m.group(1)
      if Src.findFirstIn(attrs).isEmpty
    } yield {
      val body = m.group(2).trim
      if (Json.findFirstIn(attrs).nonEmpty) "json"
      else if (file == "app/views/layout.scala.html" && body.contains("prefers-color-scheme")) "theme"
      else if (RestoreIn(file) && body == Restore) "restore"
      else "code"
    }

  /** The inline handlers and javascript: URLs in `text`'s markup, its scripts' bodies left out. */
  def inlineCode(text: String): Seq[String] = {
    val markup = Script.replaceAllIn(text, m => scala.util.matching.Regex.quoteReplacement(s"<script${m.group(1)}></script>"))
    (Handler.findAllIn(markup) ++ JsUrl.findAllIn(markup)).toSeq
  }

  /** (file, kind) of each script element without a src in the templates. */
  private lazy val inlineScripts: Seq[(String, String)] =
    templates.flatMap { case (file, text) => scriptKinds(file, text).map(file -> _) }

  "the checks themselves" should {

    "find the large image's onerror after Twirl's => in its tag, as before B3" in {
      val oldTag =
        """<img id="large_image" alt="@hood.file.title"
          |    src="@large.src" class="mw-100 mh-100"
          |    srcset="@large.srcset" @large.sizes.map { sizes => sizes="@sizes" }
          |    @large.fallback.map { fallback => data-fallback="@fallback" }
          |    onerror="var f = this.getAttribute('data-fallback'); if (f) { this.src = f; }">""".stripMargin
      (inlineCode(oldTag) must haveSize(1)) and
        (inlineCode("""<input type="button" onClick="document.forms['x'].submit();" />""") must haveSize(1)) and
        (inlineCode("""<body onload=@x>""") must haveSize(1))
    }

    "find a javascript: URL" in {
      inlineCode("""<a href="JavaScript:void(0)">x</a>""") must haveSize(1)
    }

    "not take data attributes, words or a script's body for handlers" in {
      inlineCode(
        """<img data-on-fail="x" data-failed-mark="large"><p>Done on time = yes</p>
          |<script src="/a.js"></script><script>el.onclick = f; x.onerror="y"</script>""".stripMargin) must beEmpty
    }

    "count a script with only a data-src as inline, and one with a src or JSON type as not" in {
      (scriptKinds("x", """<script data-src="/a.js">go()</script>""") must_== Seq("code")) and
        (scriptKinds("x", """<script src="/a.js"></script><script type="application/json">{}</script>""") must_==
          Seq("json")) and
        (scriptKinds("x", """<script SRC='/a.js' defer></script>""") must beEmpty)
    }
  }

  "the templates" should {

    "be read at all" in {
      templates.map(_._1) must contain(allOf("app/views/layout.scala.html", "app/views/editRound.scala.html"))
    }

    "render no script without a src, except the theme, the side sections' restore and JSON data" in {
      inlineScripts.filter(_._2 == "code").map(_._1) must beEmpty
    }

    "have the theme script in the layout only, and the restore in the two large views" in {
      (inlineScripts.filter(_._2 == "theme").map(_._1) must_== Seq("app/views/layout.scala.html")) and
        (inlineScripts.filter(_._2 == "restore").map(_._1).toSet must_== RestoreIn)
    }

    "keep the round form's texts as a JSON data block" in {
      inlineScripts must contain("app/views/editRound.scala.html" -> "json")
    }

    "have no inline event handler and no javascript: URL" in {
      templates.flatMap { case (file, text) => inlineCode(text).map(h => s"$file: $h") } must beEmpty
    }

    "load no script that is gone, and no setup the shells used to hold" in {
      val all = templates.map(_._2).mkString("\n")
      (all must not(contain("var contestId"))) and
        (all must not(contain("voteLarge("))) and
        (all must not(contain("pollCacheStatus(")))
    }
  }
}
