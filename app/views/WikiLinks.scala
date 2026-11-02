package views

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import play.twirl.api.{Html, HtmlFormat}

import scala.util.matching.Regex

/** Wiki text with `[[Target]]` and `[[Target|label]]` links (a monument's name, place or city
  * from the wiki lists) as HTML: each link becomes an `<a>` to the article on `host`, and
  * every other character, the labels included, is escaped. scalawiki's
  * `Monument.wikiLinkToUrl` builds the same links but leaves the text around them, and the
  * labels, unescaped, so any wiki editor's markup would reach the page.
  *
  * Before escaping, the markup the lists use is turned into text ([[plainText]]), so it shows
  * as the wiki shows it rather than as `&nbsp;` or `<br />`.
  */
object WikiLinks {

  private val Link = """\[\[([^\[\]|]+)(?:\|([^\[\]]*))?\]\]""".r

  def apply(wikiText: Option[String], host: String): Html = apply(wikiText.getOrElse(""), host)

  def apply(wikiText: String, host: String): Html = {
    val text = withoutHidden(wikiText)
    val out = new StringBuilder
    var last = 0
    Link.findAllMatchIn(text).foreach { m =>
      out ++= escaped(text.substring(last, m.start))
      val target = decodeReferences(m.group(1)).replace(' ', ' ').trim
      val label = Option(m.group(2)).getOrElse(m.group(1))
      val url = s"https://$host/wiki/" + URLEncoder.encode(target.replace(' ', '_'), StandardCharsets.UTF_8)
      out ++= s"""<a href="${HtmlFormat.escape(url).body}">${escaped(label)}</a>"""
      last = m.end
    }
    out ++= escaped(text.substring(last))
    Html(out.toString)
  }

  private def escaped(wikiText: String): String = HtmlFormat.escape(plainText(wikiText)).body

  private val Comment = """(?s)<!--.*?(?:-->|\z)""".r
  private val Ref = """(?is)<ref\b[^>]*/>|<ref\b[^>]*>.*?</ref\s*>|\{\{\s*ref\+[^{}]*\}\}""".r
  private val NoWrap = """\{\{\s*(?:s|nowrap|comment)\s*\|([^{}|]*)(?:\|[^{}]*)?\}\}""".r
  private val NoWiki = """(?is)<nowiki\s*>(.*?)</nowiki\s*>|<nowiki\s*/>""".r

  /** Without what the wiki doesn't show in place: comments and references (`<ref>`,
    * `{{ref+|…}}`), with their content. `{{s|…}}`, `{{nowrap|…}}` and `{{comment|text|…}}`
    * become their (first) text, and so does `<nowiki>…</nowiki>`, its brackets as references,
    * so they make no link.
    */
  private def withoutHidden(wikiText: String): String = {
    val shown = Ref.replaceAllIn(Comment.replaceAllIn(wikiText, ""), "")
    val unwrapped = NoWrap.replaceAllIn(shown, m => Regex.quoteReplacement(m.group(1)))
    NoWiki.replaceAllIn(
      unwrapped,
      m => Regex.quoteReplacement(Option(m.group(1)).fold("")(_.replace("[", "&#91;").replace("]", "&#93;")))
    )
  }

  private val Break = """(?i)<br\s*/?\s*>""".r
  private val Formatting = """(?i)</?\s*(?:small|sup|sub|s|u|big|span)\b[^<>]*>""".r
  private val BoldItalic = "'{2,}".r

  /** The text the wiki shows for `wikiText`: a line break is a space; the formatting tags the
    * lists use (`<nowiki>`, `<small>`, `<sup>`, `<sub>`, …) and `''`/`'''` are dropped, their
    * text kept; character references are decoded. Any other tag stays text, and so does a
    * decoded `&lt;`, since the caller escapes the result.
    */
  private[views] def plainText(wikiText: String): String =
    decodeReferences(BoldItalic.replaceAllIn(Formatting.replaceAllIn(Break.replaceAllIn(wikiText, " "), ""), ""))

  private val Reference = """&(?:#(\d{1,7})|#[xX]([0-9a-fA-F]{1,6})|([a-zA-Z][a-zA-Z0-9]{1,31}));""".r

  private val Named: Map[String, String] = Map(
    "nbsp"   -> " ",
    "amp"    -> "&",
    "lt"     -> "<",
    "gt"     -> ">",
    "quot"   -> "\"",
    "apos"   -> "'",
    "ndash"  -> "–",
    "mdash"  -> "—",
    "minus"  -> "−",
    "hellip" -> "…",
    "laquo"  -> "«",
    "raquo"  -> "»",
    "bdquo"  -> "„",
    "ldquo"  -> "“",
    "rdquo"  -> "”",
    "lsquo"  -> "‘",
    "rsquo"  -> "’",
    "prime"  -> "′",
    "Prime"  -> "″",
    "deg"    -> "°",
    "times"  -> "×",
    "middot" -> "·",
    "sect"   -> "§",
    "numero" -> "№",
    "sup1"   -> "¹",
    "sup2"   -> "²",
    "sup3"   -> "³",
    "frac12" -> "½",
    "frac14" -> "¼",
    "frac34" -> "¾",
    "shy"    -> "­",
    "thinsp" -> " ",
    "ensp"   -> " ",
    "emsp"   -> " "
  )

  /** Decimal, hexadecimal and the [[Named]] references as characters; an unknown name, or a
    * code point that isn't a character, stays as written.
    */
  private[views] def decodeReferences(text: String): String =
    if (!text.contains('&')) text
    else
      Reference.replaceAllIn(
        text,
        m => {
          val decoded =
            if (m.group(1) != null) codePoint(m.group(1).toInt)
            else if (m.group(2) != null) codePoint(Integer.parseInt(m.group(2), 16))
            else Named.get(m.group(3))
          Regex.quoteReplacement(decoded.getOrElse(m.matched))
        }
      )

  private def codePoint(cp: Int): Option[String] =
    Option.when(cp > 0 && Character.isValidCodePoint(cp) && !(cp >= 0xd800 && cp <= 0xdfff))(
      new String(Character.toChars(cp))
    )
}
