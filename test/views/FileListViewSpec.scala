package views

import controllers.{GalleryView, RateDistribution, RoundList}
import db.scalikejdbc.User
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import org.jsoup.Jsoup
import org.jsoup.nodes.{Comment, Document}
import play.api.mvc.{Flash, Request}
import play.api.test.PlaySpecification

import scala.jdk.CollectionConverters._

/** The file list (fileList.scala.html): a block to copy into a wiki page, whose first line is
  * the wiki comment saying what it lists, shown as escaped text so that it is copied with the
  * list; as a table (all the jurors' votes) or the files alone. In a column, so it has the
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

  private def render(asUser: User, wrapInTable: Boolean): Document = Jsoup.parse(contentAsString(
    views.html.fileList(juror, RoundList(round, Option(asUser).fold(0L)(_.getId), view = GalleryView.FileList), asUser,
      files, Seq("1", "2"), jurors, Nil, None, "wiki", wrapInTable, new RateDistribution(Map.empty))))

  private def block(doc: Document) = doc.selectFirst("[contenteditable=true]")

  "the file list" should {

    "start its copy block with the wiki comment, as escaped text, in a column" in {
      forall(Seq[(User, Boolean)]((null, true), (juror.copy(fullname = "<b>Juror</b>"), false))) { case (asUser, table) =>
        val doc = render(asUser, table)
        val copy = block(doc)
        val comment = s"<!-- round Round <one>, juror: ${Option(asUser).fold("all")(_.fullname)} . -->"
        (copy.parent.hasClass("col-12") must beTrue) and (copy.parent.parent.hasClass("row") must beTrue) and
          (copy.ownText must startWith(comment)) and
          // text, never markup: no comment node, no tag from the names
          (copy.getAllElements.asScala.flatMap(_.childNodes.asScala).collect { case c: Comment => c } must beEmpty) and
          (copy.select("b").size must_== 0) and
          // nothing before the block but the page's hidden title
          (copy.parent.children.size must_== 1)
      }
    }

    "show all the jurors' votes as a table, its header and footer as wide as its rows" in {
      val table = block(render(null, wrapInTable = true)).selectFirst("table")
      val widths = (table.select("thead th").size, table.select("tfoot th").size,
        table.select("tbody tr").asScala.map(_.select("td").size).distinct.toList)
      widths must_== ((6, 6, List(6)))
    }

    "show only the files, one per line, without the table" in {
      val copy = block(render(juror, wrapInTable = false))
      (copy.select("td, table").size must_== 0) and
        (copy.select("a").asScala.map(_.text) must_== files.map(_.title)) and
        // no megapixels or votes between the files
        (copy.text must_== s"<!-- round Round <one>, juror: ${juror.fullname} . --> File:Image 301.jpg File:Image 302.jpg")
    }
  }
}
