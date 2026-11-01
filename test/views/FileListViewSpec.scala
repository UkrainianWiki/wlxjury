package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import db.scalikejdbc.User
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Comment, Document}
import play.api.mvc.{Flash, Request}
import play.api.test.PlaySpecification

import scala.jdk.CollectionConverters._

/** The file list (fileList.scala.html): a block to copy into a wiki page, headed by what it
  * lists, inside the block so that it is copied with the list; as a table (all the jurors' votes) or the files alone. In a column, so it has the
  * page's gutter. No database or Play app.
  */
class FileListViewSpec extends PlaySpecification {

  import LargeViewRenders._

  implicit val req: Request[_] = LargeViewRenders.request("/filelist/round/1/user/0/page/1")
  implicit val flash: Flash = Flash()
  implicit val msgs: play.api.i18n.Messages = LargeViewRenders.messages(req)

  private val round = binaryRound.copy(name = Some("Round <one>"))
  private val jurors = Seq(1L, 2L).map(id => User(s"Juror $id", s"j$id@example.org", id = Some(id)))

  private val files = Seq(301L, 302L).map { pageId =>
    ImageWithRating(Image(pageId = pageId, title = s"File:Image $pageId.jpg", width = 2000, height = 1000),
      selection = jurors.map(j => Selection(pageId, j.getId, round.getId, if (pageId == 301L) 1 else -1)))
  }

  private def render(asUser: User, wrapInTable: Boolean, region: String = "all",
                     pager: Pager = Pager(1, 2, RoundList.FileListPageSize)): Document = Jsoup.parse(contentAsString(
    views.html.fileList(juror, RoundList(round, Option(asUser).fold(0L)(_.getId), view = GalleryView.FileList)
      .withRegion(region), asUser,
      files, Seq("1", "2"), jurors, Nil, None, pager, "wiki", wrapInTable, new RateDistribution(Map.empty))))

  private def block(doc: Document) = doc.selectFirst("[contenteditable=true]")

  "the file list" should {

    "start its copy block with a heading saying what it lists, in a column" in {
      forall(Seq[(User, Boolean)]((null, true), (juror.copy(fullname = "<b>Juror</b>"), false))) { case (asUser, table) =>
        val doc = render(asUser, table)
        val copy = block(doc)
        val heading = copy.child(0)
        (copy.parent.hasClass("col-12") must beTrue) and (copy.parent.parent.hasClass("row") must beTrue) and
          (heading.tagName must_== "h2") and (heading.hasClass("h5") must beTrue) and
          (heading.text must_== s"round Round <one> · juror: ${Option(asUser).fold("all")(_.fullname)}") and
          (Option(heading.nextElementSibling).forall(_.tagName != "br") must beTrue) and
          // text, never markup: no comment node, no tag from the names
          (copy.getAllElements.asScala.flatMap(_.childNodes.asScala).collect { case c: Comment => c } must beEmpty) and
          (copy.select("b").size must_== 0) and
          // nothing before the block but the page's hidden title
          (copy.parent.children.size must_== 1)
      }
    }

    "name the region in its heading when it has one" in {
      if (controllers.KOATUU.regions.isEmpty) controllers.KOATUU.load()
      block(render(null, wrapInTable = true, region = "80")).child(0).text must_==
        s"round Round <one> · juror: all · region.menu: ${controllers.KOATUU.regions("80")}"
    }

    "show all the jurors' votes as a table, its header and footer as wide as its rows" in {
      val table = block(render(null, wrapInTable = true)).selectFirst("table")
      val widths = (table.select("thead th").size, table.select("tfoot th").size,
        table.select("tbody tr").asScala.map(_.select("td").size).distinct.toList)
      widths must_== ((6, 6, List(6)))
    }

    "be a page of the list, with the pager in the navbar, linking the file list's pages" in {
      val doc = render(null, wrapInTable = true, pager = Pager(2, 2500, RoundList.FileListPageSize))
      val links = doc.select("nav #pagenav a[rel]").asScala.map(a => a.attr("rel") -> a.attr("href")).toMap
      (links("prev") must_== "/filelist/round/1/user/0/page/1?sort=rate") and
        (links("next") must_== "/filelist/round/1/user/0/page/3?sort=rate") and
        // a list of one page has none
        (render(null, wrapInTable = true).select("#pagenav").size must_== 0)
    }

    "show only the files, one per line, without the table" in {
      val copy = block(render(juror, wrapInTable = false))
      (copy.select("td, table").size must_== 0) and
        (copy.select("a").asScala.map(_.text) must_== files.map(_.title)) and
        // no megapixels or votes between the files
        (copy.text must_== s"round Round <one> · juror: ${juror.fullname} File:Image 301.jpg File:Image 302.jpg")
    }
  }
}
