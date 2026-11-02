package views

import org.intracer.wmua.Image
import org.jsoup.Jsoup
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import play.api.i18n.{Lang, Messages}
import play.api.mvc.Flash
import play.api.test.{FakeRequest, Helpers}

/** A monument's name, place and city come from the wiki lists, which anyone can edit: their
  * `[[links]]` become links, and any other markup is shown as text ([[WikiLinks]]).
  */
class WikiLinksSpec extends Specification {

  private val host = "uk.wikipedia.org"

  "WikiLinks" should {
    "turn [[Target]] and [[Target|label]] into links to the article" in {
      WikiLinks("в [[Київ]]і та [[Львів|Львові]]", host).body ===
        "в <a href=\"https://uk.wikipedia.org/wiki/%D0%9A%D0%B8%D1%97%D0%B2\">Київ</a>і та " +
        "<a href=\"https://uk.wikipedia.org/wiki/%D0%9B%D1%8C%D0%B2%D1%96%D0%B2\">Львові</a>"
    }

    "replace spaces in the target with underscores and encode it" in {
      WikiLinks("[[St Mary's \"x\"|a]]", host).body ===
        "<a href=\"https://uk.wikipedia.org/wiki/St_Mary%27s_%22x%22\">a</a>"
    }

    "escape the text around the links and the labels" in {
      val body = WikiLinks("<script>alert(1)</script> [[A|<img src=x onerror=alert(1)>]] & \"q\"", host).body
      body must not(contain("<script"))
      body must not(contain("<img"))
      body === "&lt;script&gt;alert(1)&lt;/script&gt; " +
        "<a href=\"https://uk.wikipedia.org/wiki/A\">&lt;img src=x onerror=alert(1)&gt;</a> &amp; &quot;q&quot;"
    }

    "leave text without links, or an unclosed link, as escaped text" in {
      WikiLinks(None, host).body === ""
      WikiLinks(Some("plain"), host).body === "plain"
      WikiLinks("[[open <b>", host).body === "[[open &lt;b&gt;"
    }
  }

  "the large view's monument box" should {
    "show the monument's fields as text and links only" in {
      implicit val messages: Messages = Helpers.stubMessages()
      implicit val flash: Flash = Flash()
      implicit val request: play.api.mvc.Request[Any] = FakeRequest()
      val monument = Monument(id = "01-001", name = "[[Церква|<b>Church</b>]]<script>x()</script>",
        place = Some("<img src=x onerror=y()>"), city = Some("[[Київ]] --> <i>"), typ = Some("--><script>z()</script>"))
      val html = views.html.large.monumentInfo(Image(pageId = 1, title = "File:1.jpg"), Some(monument)).body
      val doc = Jsoup.parseBodyFragment(html)
      doc.select("script, img, b, i").size === 0
      doc.select("a").eachAttr("href").toArray.toSeq === Seq(
        "https://uk.wikipedia.org/wiki/%D0%9A%D0%B8%D1%97%D0%B2",
        "https://uk.wikipedia.org/wiki/%D0%A6%D0%B5%D1%80%D0%BA%D0%B2%D0%B0")
      doc.text must contain("<script>x()</script>")
      html must not(contain("z()"))
    }
  }
}
