package views

import org.specs2.mutable.Specification

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The templates hold no script code, so that the content security policy can do without
  * 'unsafe-inline' (docs/plans/2026-11-01-open-followups.md, section 5 and 2.4): every
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

  private val Script     = "(?s)<script\\b([^>]*)>(.*?)</script>".r
  private val Restore    = "if (window.wlxLargeSections) wlxLargeSections.restore(document);"
  private val RestoreIn  = Set("app/views/large/large.scala.html", "app/views/large/contestFile.scala.html")
  private val Handler    = "(?i)<[a-z][^>]*?\\son[a-z]+\\s*=".r

  /** (file, kind) of each script element without a src: "theme", "restore", "json" or "code". */
  private lazy val inlineScripts: Seq[(String, String)] =
    for {
      (file, text) <- templates
      m            <- Script.findAllMatchIn(text).toSeq
      attrs = m.group(1)
      if !attrs.contains("src=")
    } yield file -> {
      val body = m.group(2).trim
      if (attrs.contains("type=\"application/json\"")) "json"
      else if (file == "app/views/layout.scala.html" && body.contains("prefers-color-scheme")) "theme"
      else if (RestoreIn(file) && body == Restore) "restore"
      else "code"
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

    "have no inline event handler" in {
      templates.flatMap { case (file, text) => Handler.findAllIn(text).map(h => s"$file: $h") } must beEmpty
    }

    "load no script that is gone, and no setup the shells used to hold" in {
      val all = templates.map(_._2).mkString("\n")
      (all must not(contain("var contestId"))) and
        (all must not(contain("voteLarge("))) and
        (all must not(contain("pollCacheStatus(")))
    }
  }
}
