package views

import play.api.i18n.Messages
import play.twirl.api.{Html, HtmlFormat}

import java.net.URI
import scala.util.Try

/** Where a link that leaves the tool goes, for the mark after its text (snippets/externalMark,
  * the maintainer's decision of 2026-11-19): Wikimedia Commons (its pages and its files on
  * upload.wikimedia.org), a Wikipedia, Wikidata, or any other site; and a link's text with its
  * mark ([[marked]]).
  */
object ExternalLinks {

  sealed abstract class Site(val name: String)
  case object Commons extends Site("commons")
  case object Wikipedia extends Site("wikipedia")
  case object Wikidata extends Site("wikidata")
  case object Other extends Site("other")

  def site(url: String): Site = {
    val uri = Try(new URI(Option(url).getOrElse("").trim.replace(" ", "%20"))).toOption
    val host = uri.flatMap(u => Option(u.getHost)).getOrElse("").toLowerCase
    val path = uri.flatMap(u => Option(u.getRawPath)).getOrElse("")
    if (host == "commons.wikimedia.org" || host == "commons.m.wikimedia.org" ||
        (host == "upload.wikimedia.org" && path.startsWith("/wikipedia/commons/"))) Commons
    else if (host == "wikipedia.org" || host.endsWith(".wikipedia.org")) Wikipedia
    else if (host == "www.wikidata.org" || host == "wikidata.org" || host == "m.wikidata.org") Wikidata
    else Other
  }

  /** A link's text (plain, escaped here) followed by its mark (snippets/externalMark). */
  def marked(text: String, url: String)(implicit messages: Messages): Html = marked(HtmlFormat.escape(text), url)

  /** A link's content (HTML) followed by its mark: the content's last character and the mark in
    * one span that doesn't wrap (wlx-ext-nowrap, main.css), so the mark never starts a line of its
    * own (a no-break space alone didn't hold before an inline-block in Chromium, Firefox and
    * WebKit on Linux), while the rest of a long word (a file title without spaces) may still wrap
    * (overflow-wrap). Trailing white space is dropped first.
    */
  def marked(content: Html, url: String)(implicit messages: Messages): Html =
    Html(withMark(content.body, views.html.snippets.externalMark(url).body))

  private val TrailingEntity = """&#?[A-Za-z0-9]{1,10};\z""".r

  /** `html` (escaped text, tags) without its trailing white space, its last character (a whole
    * entity, `&amp;`, or a whole surrogate pair) and `tail` in a span that doesn't wrap; the span
    * holds `tail` alone after a tag or for empty content. */
  private[views] def withMark(html: String, tail: String): String = {
    val text = html.replaceAll("""\s+\z""", "")
    val start =
      if (text.isEmpty || text.endsWith(">")) text.length
      else TrailingEntity.findFirstMatchIn(text).map(_.start)
        .getOrElse(if (text.length >= 2 && Character.isLowSurrogate(text.last)) text.length - 2 else text.length - 1)
    text.substring(0, start) + """<span class="wlx-ext-nowrap">""" + text.substring(start) + tail + "</span>"
  }
}
