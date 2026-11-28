package views

import org.jsoup.nodes.{Document, Element}

import scala.jdk.CollectionConverters._

/** The links of a rendered page that leave the tool (ExternalLinksViewSpec and the pages' own
  * specs): each opens in a new tab, `target="_blank" rel="noopener"`. Internal links stay in
  * the same tab by default. A text link leaving the tool has the mark of its host after its text
  * (snippets/externalMark: Commons' logo, Wikipedia's W, Wikidata's logo or the outward arrow) after a no-break space,
  * hidden from screen readers, then " (…opens in a new tab)" for them, all in one span that can't
  * be selected; no image's link or icon-only link and none of the tool's own has a mark.
  */
object ExternalLinksCheck {

  def offSite(href: String): Boolean = href.startsWith("http://") || href.startsWith("https://") || href.startsWith("//")

  /** A link's own text: without its mark and the words for screen readers after it. */
  def ownText(a: Element): String = {
    val c = a.clone(); c.select(".wlx-ext-tail").remove(); c.text.trim
  }

  /** A text link: one with words of its own, not an image's, a thumbnail's or only an icon. */
  def textLink(a: Element): Boolean = ownText(a).nonEmpty && a.select("img, video, picture").isEmpty

  def newTab(a: Element): Boolean = a.attr("target") == "_blank" && a.attr("rel").split(' ').contains("noopener")

  /** Its mark: the one for its host, hidden from screen readers, after a no-break space, then the
    * words for screen readers, in one span at the link's end, after the link's last character in a span
    * that doesn't wrap (views.ExternalLinks.marked). */
  def marked(a: Element): Boolean = {
    val tails = a.select(".wlx-ext-tail").asScala.toSeq
    val nowrap = Option(a.selectFirst(".wlx-ext-nowrap"))
    tails.size == 1 && nowrap.exists(n => n.children.last == tails.head && n.ownText.trim.nonEmpty) && {
      val tail = tails.head
      val site = ExternalLinks.site(a.attr("href")).name
      val mark = tail.children.asScala.find(_.hasClass("wlx-ext-mark"))
      tail.childNode(0).outerHtml == "&nbsp;" &&
        mark.exists(m => m.attr("data-site") == site && m.attr("aria-hidden") == "true" && (site match {
          case "commons" | "wikidata" => m.select(s"svg.wlx-ext-logo > use[href='#wlx-logo-$site']").size == 1
          case "wikipedia"            => m.select("i.bi.bi-wikipedia").size == 1
          case _                      => m.select("i.bi.bi-box-arrow-up-right").size == 1
        })) &&
        mark.flatMap(m => Option(m.nextElementSibling)).exists(h => h.hasClass("visually-hidden") && h.text.matches("""\(.+\)"""))
    }
  }

  /** The page's off-site links. */
  def external(doc: Document): Seq[Element] =
    doc.select("a[href]").asScala.toSeq.filter(a => offSite(a.attr("href")))

  /** The sites of the page's off-site text links (commons, wikipedia, wikidata, other). */
  def sites(doc: Document): Set[String] =
    external(doc).filter(textLink).map(a => ExternalLinks.site(a.attr("href")).name).toSet

  /** What is wrong with the page's links, each as its markup: empty if nothing. */
  def problems(doc: Document, ownNewTabs: Set[Element] = Set.empty): Seq[String] = {
    val links = doc.select("a[href]").asScala.toSeq
    val (out, own) = links.partition(a => offSite(a.attr("href")))
    val (newTabReferences, sameTabLinks) = own.partition(ownNewTabs)
    out.filterNot(newTab).map("not in a new tab: " + _.outerHtml) ++
      sameTabLinks.filter(_.attr("target") == "_blank").map("the tool's own link in a new tab: " + _.outerHtml) ++
      newTabReferences.filterNot(newTab).map("the tool's own link needs a new tab with noopener: " + _.outerHtml) ++
      out.filter(textLink).filterNot(marked).map("no mark or a wrong one: " + _.outerHtml) ++
      out.filterNot(textLink).filter(_.select(".wlx-ext-mark").size > 0).map("a mark on an image or icon link: " + _.outerHtml) ++
      own.filter(_.select(".wlx-ext-mark").size > 0).map("a mark on the tool's own link: " + _.outerHtml)
  }
}
