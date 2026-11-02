package security

import org.specs2.mutable.Specification

/** G5 (Step 4, N8): raw HTML only where reviewed. In `app/` a Twirl `Html(…)` or
  * `HtmlFormat.raw` skips escaping; in `public/javascripts/` `.innerHTML`, `.html(`,
  * `insertAdjacentHTML` and `document.write` parse markup. Each file that has one is listed
  * with what it inserts. The vendored libraries (unitegallery, jquery.raty, jquery.hotkeys)
  * are excluded by name: what the app gives them is escaped for their `.html()` sinks
  * (`gallery.scala.html`'s caption and description, GalleryDescriptionViewSpec).
  */
class RawHtmlSinksSpec extends Specification {

  private val Vendored = Set("unitegallery.js", "jquery.raty.js", "jquery.hotkeys.js")

  private val Allowed: Map[String, String] = Map(
    "app/views/bs/FieldView.scala" -> "attributes: names matched by AttrName, values HtmlFormat.escape'd",
    "app/views/GalleryTiles.scala" -> "data-* attributes: booleans and HtmlFormat.escape'd JSON of numbers",
    "app/views/JsTexts.scala" -> "a JSON data block of message texts, '<' escaped",
    "app/views/WikiLinks.scala" -> "monument wikitext as links: text and hrefs escaped (WikiLinksSpec)",
    "app/views/contestFiles.scala.html" -> "constant class attributes of a tab",
    "app/views/large/contestFile.scala.html" -> "Messages(full.resolution) with numbers (resolution, megapixels)",
    "app/views/large/fullSizeUrl.scala.html" -> "Messages(full.resolution) with numbers (resolution, megapixels)",
    "app/views/large/navigationButtons.scala.html" -> "titles: Messages texts with constant key hints (&lt;Left&gt;)",
    "app/views/large/fileKeyboardNav.scala.html" -> "an empty default",
    "app/views/main.scala.html" -> "empty defaults",
    "app/views/mainAdmin.scala.html" -> "empty defaults",
    "app/views/navbar.scala.html" -> "empty defaults",
    "app/views/pageLinks.scala.html" -> "constant entities (&laquo; &lsaquo; &rsaquo; &raquo;)",
    "public/javascripts/cache-status.js" -> "formatCacheStatus: numbers and constant English text"
  )

  /** `Html(` not preceded by a name part or a dot (`Accepts.Html()`, `setHtml(`), and `HtmlFormat.raw`. */
  private val TwirlRaw = """(?<![\w.])Html\(|HtmlFormat\.raw""".r
  private val DomRaw = """\.innerHTML\b|\.outerHTML\b|\.html\(|insertAdjacentHTML|document\.write""".r

  private def sinks: Set[String] = {
    val app = SourceScan.hits(SourceScan.files("app", ".scala", ".html"), TwirlRaw)
    val js = SourceScan.hits(
      SourceScan.files("public/javascripts", ".js").filterNot(s => Vendored.exists(v => s.path.endsWith("/" + v))),
      DomRaw)
    (app ++ js).keySet
  }

  "raw HTML sinks" should {
    "be only in the reviewed files" in {
      (sinks -- Allowed.keySet) must beEmpty
    }
  }
}
