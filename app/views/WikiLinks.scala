package views

import java.net.URLEncoder
import java.nio.charset.StandardCharsets

import play.twirl.api.{Html, HtmlFormat}

/** Wiki text with `[[Target]]` and `[[Target|label]]` links (a monument's name, place or city
  * from the wiki lists) as HTML: each link becomes an `<a>` to the article on `host`, and
  * every other character, the labels included, is escaped. scalawiki's
  * `Monument.wikiLinkToUrl` builds the same links but leaves the text around them, and the
  * labels, unescaped, so any wiki editor's markup would reach the page.
  */
object WikiLinks {

  private val Link = """\[\[([^\[\]|]+)(?:\|([^\[\]]*))?\]\]""".r

  def apply(wikiText: Option[String], host: String): Html = apply(wikiText.getOrElse(""), host)

  def apply(wikiText: String, host: String): Html = {
    val out = new StringBuilder
    var last = 0
    Link.findAllMatchIn(wikiText).foreach { m =>
      out ++= HtmlFormat.escape(wikiText.substring(last, m.start)).body
      val target = m.group(1).trim
      val label = Option(m.group(2)).getOrElse(m.group(1))
      val url = s"https://$host/wiki/" + URLEncoder.encode(target.replace(' ', '_'), StandardCharsets.UTF_8)
      out ++= s"""<a href="${HtmlFormat.escape(url).body}">${HtmlFormat.escape(label).body}</a>"""
      last = m.end
    }
    out ++= HtmlFormat.escape(wikiText.substring(last)).body
    Html(out.toString)
  }
}
