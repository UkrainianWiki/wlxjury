package views

import db.scalikejdbc.Round
import org.intracer.wmua.{BinaryOrder, Image, ImageWithRating, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.specs2.mutable.Specification
import play.api.test.Helpers
import services.CutPicker
import services.distribution.DistributionSource.{RankRow, RankedFile}
import services.distribution.RoundDistributionHandler.TopNRanking

import scala.jdk.CollectionConverters._

/** The round form's top N (round/edit/topN) and the cut gallery (cutGallery), as rendered: the N list
  * of valid values only with nothing chosen, a stored N inside a tie asking for a choice, the
  * candidate cuts as buttons to the preview, ties bracketed and never split by a line, labelled lines
  * with "Cut here" at candidate cuts only, "Show more", yes/no levels; a round whose filters aren't
  * edited shows its N read only.
  */
class CutGalleryViewSpec extends Specification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val prev = Round(Some(1), 1, Some("Rated"), 1, rates = Round.ratesById(10))

  /** A rated ranking of these averages (three jurors each), best first. */
  private def ranking(scores: Double*): TopNRanking = {
    val files = scores.zipWithIndex.map { case (s, i) =>
      val page = i + 1L
      val sum = (s * 3).round
      val row = RankRow(page, 1L, sum, 3, 3, None)
      RankedFile(ImageWithRating(Image(page, s"File:Image$page.jpg", width = 3000, height = 2000),
        Seq(Vote(page, juryId = 0, roundId = 1L, rate = sum.toInt)), 3, approvalsFromDb = Some(3), votedFromDb = Some(3)), row)
    }
    TopNRanking(Seq(prev), None, files, scores.map(s => Seq(s, (s * 3).round.toDouble)).toIndexedSeq, scores.toIndexedSeq)
  }

  /** Ties at 2–4 and 6–7: valid values 1, 4, 5, 7, 8. */
  private val tied = ranking(9, 8, 8, 8, 7, 6, 6, 5)

  private def form(topImages: Option[Int]) = controllers.EditRound.editRoundForm.fill(controllers.EditRound(
    new Round(id = None, number = 2, contestId = 1, previous = Some("1"), topImages = topImages), Seq(1L), None))

  private def render(picker: Option[CutPicker], topImages: Option[Int] = None, noEdit: Boolean = false): Document =
    Jsoup.parse(views.html.round.edit.topN(form(topImages), picker, None, noEdit, "/admin/rounds/cut-preview").body)

  private def values(page: Document, css: String, attr: String): Seq[String] =
    page.select(css).asScala.toSeq.map(_.attr(attr))

  "the N list" should {
    "offer only valid values and no top N, nothing but the form's value chosen" in {
      val page = render(Some(CutPicker.build(tied, Seq(10.0), None, None, None)))
      (values(page, "select#topImages option", "value") === Seq("", "1", "4", "5", "7", "8")) and
        (values(page, "select#topImages option[selected]", "value") === Seq("")) and
        (page.select("select#topImages[required]").size === 0) and
        (page.select("input[name=topImages][type=number]").size === 0)
    }

    "keep a valid N chosen, with its preview" in {
      val page = render(Some(CutPicker.build(tied, Seq(10.0), None, Some(4), None)), Some(4))
      (values(page, "select#topImages option[selected]", "value") === Seq("4")) and
        (page.select("#top-n-preview").text must contain("top.n.preview"))
    }

    "ask for a choice for a stored N inside a tie, which it doesn't list" in {
      val page = render(Some(CutPicker.build(tied, Seq(10.0), None, Some(3), None)), Some(3))
      (values(page, "select#topImages option", "value") === Seq("", "", "1", "4", "5", "7", "8")) and
        (page.select("select#topImages option[selected]").text === "top.n.choose.placeholder") and
        (page.select("select#topImages[required]").size === 1)
    }

    "show the form's N and \"Choose N\" before the candidates are loaded" in {
      val page = render(None, Some(37))
      (values(page, "#topImages_field input[type=hidden][name=topImages]", "value") === Seq("37")) and
        (page.select("#topImages-choose[formaction=/admin/rounds/cut-preview][formnovalidate]").size === 1) and
        (page.select("select#topImages").size === 0)
    }

    "show a round's N read only when its filters aren't edited, nothing without one" in {
      val page = render(None, Some(3), noEdit = true)
      (page.select("input#topImages.wlx-locked[disabled]").attr("value") === "3") and (page.select("#topImages-choose").size === 0) and
        // the locked look of #225's helpers, labelled and described by the section's reason line
        (page.select("label[for=topImages]").size === 1) and
        (page.select("input#topImages").attr("aria-describedby") must contain("round-filters-locked")) and
        // nothing submits it
        (page.select("input[name=topImages]:not([disabled])").size === 0) and
        (render(None, None, noEdit = true).select("#topImages_field").size === 0)
    }
  }

  "the candidate cuts" should {
    "be submit buttons to the preview, one per candidate" in {
      val picker = CutPicker.build(tied, Seq(10.0), None, None, None)
      val page = render(Some(picker))
      (values(page, "#top-n-candidates button[name=cutPick]", "value") === picker.result.candidates.map(_.n.toString)) and
        (values(page, "#top-n-candidates button", "formaction").distinct === Seq("/admin/rounds/cut-preview"))
    }
  }

  "the cut gallery" should {
    "bracket ties and draw lines only at cut positions, labelled with \"Cut here\" at candidates" in {
      val picker = CutPicker.build(tied, Seq(10.0), None, None, None)
      val page = render(Some(picker))
      val candidates = picker.result.candidates.map(_.n).filter(_ < 8).map(_.toString)
      (values(page, "#cut-gallery li.cut-line", "data-cut") === Seq("1", "4", "5", "7")) and
        (values(page, "#cut-gallery li.cut-line-candidate", "data-cut") === candidates) and
        (values(page, "#cut-gallery li.cut-line-candidate button[name=cutPick]", "value") === candidates) and
        (values(page, "#cut-gallery li.cut-tied", "data-place") === Seq("2", "3", "4", "6", "7")) and
        (page.select("#cut-gallery .cut-tie-label").size === 2) and
        (page.select("#cut-gallery li.cut-file a[href]").first.attr("href") === "/large/round/1/user/0/pageid/1")
    }

    "end at the window's end plus five, with \"Show more\" and the end kept for the other buttons" in {
      val long = ranking((1 to 40).map(i => 10.0 - i * 0.1): _*)
      val page = render(Some(CutPicker.build(long, Seq(10.0), Some(10), None, None)))
      (page.select("#cut-gallery li.cut-file").size === 17) and
        (values(page, "#cut-gallery-more", "value") === Seq("40")) and
        (values(page, "input[name=cutShown]", "value") === Seq("17"))
    }

    "name yes/no levels" in {
      val levels = TopNRanking(Seq(prev.copy(rates = Round.binaryRound)), Some(BinaryOrder.Default),
        tied.files, IndexedSeq(Seq(3.0, 0.0), Seq(2.0, 1.0), Seq(2.0, 1.0)), IndexedSeq(0.0, 0.0, 0.0))
      val page = render(Some(CutPicker.build(levels, Nil, None, None, None)))
      (page.select("#cut-gallery-heading").text must startWith("top.n.gallery.levels")) and
        (page.select("#cut-gallery li.cut-file .cut-score").first.text === "top.n.level.approvals")
    }
  }

  "a stored choice" should {
    "be told apart from the files ranked now: another count, another key at its N" in {
      import play.api.libs.json.Json
      val picker = CutPicker.build(tied, Seq(10.0), None, None, None)
      val basis = picker.basis(4)
      (picker.differsFrom(basis) must beFalse) and (picker.differsFrom(basis ++ Json.obj("files" -> 9)) must beTrue) and
        (picker.differsFrom(basis ++ Json.obj("key" -> Seq(7.0, 21.0))) must beTrue) and
        ((basis \ "how").as[String] === "list") and ((picker.basis(8) \ "how").as[String] === "candidate") and
        (render(Some(picker.copy(changedSince = true))).select("#top-n-changed").size === 1)
    }
  }

  "the settings log's basis" should {
    "say how a top N was chosen, reading only the picker's keys" in {
      import play.api.libs.json.Json
      val candidate = Json.obj("how" -> "candidate", "target" -> 40, "reasons" -> Seq(
        Json.obj("key" -> "top.n.reason.drop", "args" -> Seq("7.2", "6.1")), Json.obj("key" -> "secret.key", "args" -> Seq.empty[String])))
      (RoundChangeText.basis(candidate) === "top.n.basis.candidate, top.n.basis.target") and
        (RoundChangeText.basis(Json.obj("how" -> "list")) === "top.n.basis.list")
    }
  }
}
