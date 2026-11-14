package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import db.scalikejdbc.User
import org.intracer.wmua.{Image, ImageWithRating, Vote}
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
      votes = jurors.map(j => Vote(pageId, j.getId, round.getId, if (pageId == 301L) 1 else -1)))
  }

  private def render(asUser: User, wrapInTable: Boolean, region: String = "all",
                     pager: Pager = Pager(1, 2, RoundList.FileListPageSize), files: Seq[ImageWithRating] = files,
                     jurors: Seq[User] = jurors): Document = Jsoup.parse(contentAsString(
    views.html.fileList(juror, RoundList(round, Option(asUser).fold(0L)(_.getId), view = GalleryView.FileList)
      .withRegion(region), asUser,
      files, files.indices.map(i => (i + 1).toString), jurors, Nil, None, pager, "wiki", wrapInTable,
      new RateDistribution(Map.empty))))

  private def block(doc: Document) = doc.selectFirst("[contenteditable=true]")

  "the file list" should {

    "start its copy block with a heading saying what it lists, in a column" in {
      forall(Seq[(User, Boolean)]((null, true), (juror.copy(fullname = "<b>Juror</b>"), false))) { case (asUser, table) =>
        val doc = render(asUser, table)
        val copy = block(doc)
        val heading = copy.child(0)
        (copy.parent.hasClass("col-12") must beTrue) and (copy.parent.parent.hasClass("row") must beTrue) and
          (heading.tagName must_== "h2") and (heading.hasClass("h5") must beTrue) and
          (heading.text must_== s"round Round <one> · ${Option(asUser).fold("jurors.all")(u => s"juror: ${u.fullname}")}") and
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
        s"round Round <one> · jurors.all · region.menu: ${controllers.KOATUU.regions("80")}"
    }

    "show all the jurors' votes as a table, its header and footer as wide as its rows" in {
      val table = block(render(null, wrapInTable = true)).selectFirst("table")
      val widths = (table.select("thead th").size, table.select("tfoot th").size,
        table.select("tbody tr").asScala.map(_.select("td").size).distinct.toList)
      widths must_== ((6, 6, List(6)))
    }

    "show each juror's vote as a check, a cross or a dot, blank where not theirs, under a legend" in {
      val third = User("Juror 3", "j3@example.org", id = Some(3L))
      // juror 1 selected, juror 2 rejected, juror 3 yet to vote; juror 4 not assigned the file
      val file = ImageWithRating(Image(pageId = 303L, title = "File:Image 303.jpg", width = 2000, height = 1000),
        votes = Seq(Vote(303L, 1, round.getId, 1), Vote(303L, 2, round.getId, -1),
          Vote(303L, 3, round.getId, 0)))
      val copy = block(render(null, wrapInTable = true, files = Seq(file),
        jurors = jurors :+ third :+ User("Juror 4", "j4@example.org", id = Some(4L))))
      val cells = copy.select("tbody tr td").asScala.toList
      val votes = cells.takeRight(4)
      (votes.map(_.select(".visually-hidden").text) must_== List("selected", "rejected", "unrated", "")) and
        (votes.map(_.select("[aria-hidden=true]").size) must_== List(1, 1, 1, 0)) and
        (votes(0).select("i.bi-check-lg.wlx-vote-selected").size must_== 1) and
        (votes(1).select("i.bi-x-lg.wlx-vote-rejected").size must_== 1) and
        (votes(2).select(".wlx-vote-unrated").text must_== "·") and
        (votes(3).text must_== "") and
        // all the jurors' votes together: a check and a cross with their numbers, read as text
        (cells(2).selectFirst("[aria-hidden=true]").text must_== "1 1") and
        (cells(2).select(".visually-hidden").text must_== "binary.tally") and
        (copy.select("thead th").get(2).text must_== "overall.votes") and
        // the legend before the table, after the heading
        (copy.child(1).hasClass("wlx-votes-legend") must beTrue) and
        (copy.child(1).text must_== "selected rejected · unrated filelist.legend.not.assigned") and
        (copy.child(1).select("[aria-hidden=true]").size must_== 3)
    }

    "show a juror's own list without the legend" in {
      block(render(juror, wrapInTable = false)).select(".wlx-votes-legend").size must_== 0
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
