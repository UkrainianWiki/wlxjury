package views

import controllers.{GalleryView, Neighbourhood, RoundList}
import org.intracer.wmua.{ImageWithRating, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import play.api.i18n.Messages
import play.api.mvc.{AnyContentAsEmpty, Flash, Request}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}
import scala.jdk.CollectionConverters._

/** Every link that leaves the tool opens in a new tab, `target="_blank" rel="noopener"` (the
  * maintainer's browser check of 26.11.13: the full resolution and the monument's and the author's
  * links loaded in the same tab), and none of the tool's own links does; a text link among them
  * has the mark of its host after its text (snippets/externalMark: Commons' logo, Wikipedia's W,
  * Wikidata's logo or the outward arrow) and " (opens in a new tab)" for screen readers (the
  * maintainer's decision of 2026-11-19). The large views with the monument's box (its Wikipedia
  * links) and the author's (their Commons page) as an organizer sees them, and the templates'
  * literal `https://` links.
  */
class ExternalLinksViewSpec extends Specification {

  import LargeViewRenders._

  implicit val request: Request[AnyContentAsEmpty.type] = LargeViewRenders.request("/large/round/1/user/0/pageid/107")
  implicit val flash: Flash = Flash()
  implicit val messages: Messages = LargeViewRenders.realMessages

  private val organizer = juror.copy(roles = Set("organizer"))

  private val monument = Monument(id = "01-001", name = "[[Церква Покрови|Church]]", place = Some("[[Поділ]]"),
    city = Some("[[Київ]]"))

  /** The files, the middle one with an author (a Commons account) and the monument. */
  private val withDetails: Seq[ImageWithRating] = files().zipWithIndex.map { case (f, i) =>
    if (i != middle) f
    else f.copy(image = f.image.copy(author = Some("Jane Doe"), monumentId = Some(monument.id)))
  }

  /** Each link right (ExternalLinksCheck), at least `atLeast` off-site, its text links' sites `sites`. */
  private def checkLinks(doc: Document, atLeast: Int, sites: Set[String]) =
    (ExternalLinksCheck.problems(doc) must beEmpty) and (ExternalLinksCheck.external(doc).size must be_>=(atLeast)) and
      (ExternalLinksCheck.sites(doc) must_== sites)

  "a round's large view for all the jurors, with the monument and the author" should {
    "open every off-site link in a new tab, marked for its site, and its own links in this one" in {
      val list = RoundList(ratedRound, 0, view = GalleryView.Gallery)
      val html = views.html.large.large(organizer, list, Neighbourhood(withDetails, middle, middle), Some(monument), Nil,
        Seq(Vote(pageIdAt(middle), 2, 1, 4) -> juror.copy(id = Some(2), fullname = "Juror 2"))).body
      val doc = Jsoup.parse(html)
      // the boxes are there: the monument's Wikipedia links, the author's Commons page
      (doc.select("#monument-card a[href^=https://uk.wikipedia.org/]").size must_== 3) and
        (doc.select("#author a.author-commons").size must_== 1) and
        (doc.select("#monument-page").attr("href") must startWith("/monuments/id/01-001")) and
        // Wikipedia (the monument), Commons (the author, the full resolution's file)
        checkLinks(doc, 6, Set("wikipedia", "commons"))
    }
  }

  "a contest file's large view" should {
    "open every off-site link in a new tab, marked for its site, and its own links in this one" in {
      // the title's Commons page, the full resolution, the image
      checkLinks(Jsoup.parse(contestFile(middle)), 3, Set("commons"))
    }
  }

  "the templates" should {
    "give each literal https:// link a new tab with noopener" in {
      // a quoted URL, or one in an @if's branches (users.scala.html: `href= @if(...) { "https://..." }`)
      val Tag = """(?s)<a\b[^>]*?href\s*=\s*(?:@if\([^)]*\)\s*\{\s*)?["']https?://[^>]*>""".r
      val files = Files.walk(Paths.get("app", "views")).iterator().asScala
        .filter(p => p.toString.endsWith(".html") || p.toString.endsWith(".scala")).toSeq
      val missing = for {
        p <- files
        text = new String(Files.readAllBytes(p), UTF_8)
        tag <- Tag.findAllIn(text).toSeq
        if !(tag.contains("_blank") && tag.contains("noopener"))
      } yield s"$p: $tag"
      (files.size must be_>(50)) and (missing must beEmpty)
    }
  }

  "a link's text with its mark (ExternalLinks.withMark, marked)" should {
    val tail = "<T/>"
    def nowrap(inner: String) = s"""<span class="wlx-ext-nowrap">$inner$tail</span>"""

    "keep only the last character with the mark, so the rest of a long word may wrap" in {
      val long = "File:Київ-Печерська-лавра-вид-2025.jpg"
      (ExternalLinks.withMark("abc", tail) must_== "ab" + nowrap("c")) and
        (ExternalLinks.withMark(long, tail) must_== long.dropRight(1) + nowrap("g")) and
        (ExternalLinks.withMark("Full resolution: <nobr>1 x 2</nobr>; 3 Mpx", tail) must_==
          "Full resolution: <nobr>1 x 2</nobr>; 3 Mp" + nowrap("x"))
    }

    "keep an entity or a surrogate pair whole, drop trailing white space, and hold the mark alone after a tag or for no text" in {
      val emoji = new String(Character.toChars(0x1F600))
      (ExternalLinks.withMark("Tom &amp;", tail) must_== "Tom " + nowrap("&amp;")) and
        (ExternalLinks.withMark("Commons ", tail) must_== "Common" + nowrap("s")) and
        (ExternalLinks.withMark("Commons \n  ", tail) must_== "Common" + nowrap("s")) and
        (ExternalLinks.withMark("", tail) must_== nowrap("")) and
        (ExternalLinks.withMark("<b>x</b>", tail) must_== "<b>x</b>" + nowrap("")) and
        (ExternalLinks.withMark("a" + emoji, tail) must_== "a" + nowrap(emoji))
    }

    "escape a plain text, its last character an entity kept whole" in {
      val html = ExternalLinks.marked("<script>", "https://example.org/").body
      (html must startWith("&lt;script<span class=\"wlx-ext-nowrap\">&gt;<span class=\"wlx-ext-tail\">")) and
        (html must not(contain("<script>")))
    }
  }

  "a link's mark" should {
    "reject a mark followed by content, including after a hidden wrapper" in {
      val html = views.html.snippets.helpLink("tracks").body
      val valid = Jsoup.parseBodyFragment(html)
      val misplaced = Jsoup.parseBodyFragment(html)
      misplaced.selectFirst("a").append("<span>suffix</span>")
      (ExternalLinksCheck.problems(valid) must beEmpty) and
        (ExternalLinksCheck.problems(misplaced) must not(beEmpty))
    }
    "be chosen by the link's host" in {
      (ExternalLinks.site("https://commons.wikimedia.org/wiki/File:A.jpg") must_== ExternalLinks.Commons) and
        (ExternalLinks.site("https://upload.wikimedia.org/wikipedia/commons/a/ab/A.jpg") must_== ExternalLinks.Commons) and
        (ExternalLinks.site("https://uk.wikipedia.org/wiki/Київ") must_== ExternalLinks.Wikipedia) and
        (ExternalLinks.site("https://www.wikidata.org/wiki/Q1") must_== ExternalLinks.Wikidata) and
        (ExternalLinks.site("https://upload.wikimedia.org/wikipedia/uk/a/ab/A.jpg") must_== ExternalLinks.Other) and
        (ExternalLinks.site("https://heritage.example.org/list") must_== ExternalLinks.Other) and
        (ExternalLinks.site("not a url") must_== ExternalLinks.Other) and
        (ExternalLinks.site(null) must_== ExternalLinks.Other)
    }

    "draw each site's mark, the logos defined once per page and used by each mark" in {
      def mark(url: String) = Jsoup.parseBodyFragment(views.html.snippets.externalMark(url).body).selectFirst(".wlx-ext-mark")
      val logos = Seq("https://commons.wikimedia.org/", "https://www.wikidata.org/").map(u => views.html.snippets.externalMark(u).body)
      val symbols = Jsoup.parseBodyFragment(views.html.snippets.externalLogos().body)
      (forall(logos)(h => (h.length must be_<(400)) and (h must not(contain("<path"))))) and
        (symbols.select("symbol#wlx-logo-commons path").size must_== 1) and
        (symbols.select("symbol#wlx-logo-wikidata path").size must_== 1) and
        (contestFile(middle).split("id=\"wlx-logo-commons\"").length must_== 2) and
        (mark("https://commons.wikimedia.org/").attr("data-site") must_== "commons") and
        (mark("https://www.wikidata.org/").attr("data-site") must_== "wikidata") and
        (mark("https://en.wikipedia.org/").select("i.bi-wikipedia").size must_== 1) and
        (mark("https://example.org/").select("i.bi-box-arrow-up-right").size must_== 1)
    }

    "carry the words for screen readers in each language" in {
      forall(Seq("uk", "ru", "fr")) { lang =>
        val source = play.api.i18n.Messages.UrlMessageSource(new java.io.File(s"conf/messages.$lang").toURI.toURL)
        val msgs = play.api.i18n.Messages.parse(source, s"messages.$lang").fold(e => throw e, identity)
        val m: Messages = play.api.i18n.MessagesImpl(play.api.i18n.Lang(lang),
          new play.api.i18n.DefaultMessagesApi(Map(lang -> msgs)))
        val hidden = Jsoup.parseBodyFragment(views.html.snippets.externalMark("https://example.org/")(m).body)
          .select(".visually-hidden").text
        (hidden must not(contain("link.new.tab"))) and (hidden must startWith("(")) and (hidden must endWith(")"))
      }
    }
  }
}
