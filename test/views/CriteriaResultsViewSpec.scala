package views

import controllers.{GalleryView, Neighbourhood, Pager, RateDistribution, RoundList}
import db.scalikejdbc.{Round, User}
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.i18n.Messages
import play.api.mvc.{AnyContentAsEmpty, Flash, Request}
import play.api.test.PlaySpecification

import scala.jdk.CollectionConverters._

/** A criteria round's results as the pages draw them (C3): stars per criterion (the rate, a
  * sum, over the number of criteria: [[Round.perCriterion]]), each criterion's average in the
  * by-rate gallery, the file list and the large view for all the jurors, each juror's rates of
  * the criteria in the large view's list of votes; the rate labelled as a sum (U7); names by
  * their message or fallback text, escaped (fallback texts are entered by admins).
  */
class CriteriaResultsViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror
  /** 2 criteria of 10 stars. */
  private val round: Round = LargeViewRenders.ratedRound.copy(rates = Round.ratesById(10), hasCriteria = true,
    criteriaCount = 2)
  private val xss = "<script>alert(1)</script><img src=x onerror=alert(2)>"
  private val quality = Criterion(1, "criterion.test.quality", "Quality")
  private val hostile = Criterion(2, "criterion.custom.2", xss)
  private val criteria = Seq(quality, hostile)

  implicit val request: Request[AnyContentAsEmpty.type] = LargeViewRenders.request("/byrate/round/1/user/0/page/1")
  implicit val flash: Flash = Flash()
  implicit val messages: Messages = LargeViewRenders.realMessages

  /** File 301 rated by two jurors in both criteria: 8 + 6 and 6 + 9, so 29 over 2 jurors, 14.5
    * per juror, 7.25 per criterion; quality 7, the other 7.5. File 302 has no fully rated vote.
    */
  private val files: Seq[ImageWithRating] = Seq(
    ImageWithRating(Image(301, "File:Image 301.jpg"), Seq(Vote(301, 0, 1, 29)), countFromDb = 2),
    ImageWithRating(Image(302, "File:Image 302.jpg"), Seq(Vote(302, 0, 1, 0)), countFromDb = 2)
  )
  private val results = CriteriaResults(criteria, Map(301L -> Map(1L -> BigDecimal(7), 2L -> BigDecimal("7.5"))))

  private def allJurors(view: GalleryView) = RoundList(round, 0, view = view)

  private def noScript(doc: Document, html: String) = {
    html must not(contain("<script>alert"))
    html must not(contain("<img src=x"))
    doc.select("script:containsData(alert)").size === 0
    doc.select("img[onerror]").size === 0
  }

  "the by-rate gallery of a criteria round" should {
    lazy val html = contentAsString(views.html.galleryByRate(juror, allJurors(GalleryView.ByRate), files, Pager(1, 2),
      Nil, None, new RateDistribution(Map.empty), Seq("1", "2"), results))
    lazy val doc = Jsoup.parse(html)

    "draw the round's stars per criterion, which drew 20 stars for 2 criteria of 10 before" in {
      val tile = doc.getElementById("301")
      tile.select(".rate-stars i.wlx-star").size === 10
      // 14.5 per juror over 2 criteria: 7 full stars
      tile.select(".rate-stars i.bi-star-fill").size === 7
      // the rate is the jurors' average sum, with the sum and the jurors
      tile.select(".rate-stars").text() must contain("14.50 (29 / 2)")
    }

    "show each criterion's average, a dash where no juror rated every criterion" in {
      doc.getElementById("301").select(".wlx-criteria-averages li").asScala.map(_.text) ===
        Seq("Quality: 7.00", s"$xss: 7.50")
      doc.getElementById("302").select(".wlx-criteria-averages li").asScala.map(_.text) ===
        Seq("Quality: –", s"$xss: –")
    }

    "escape an admin's fallback name" in {
      noScript(doc, html)
    }
  }

  "the file list of a criteria round" should {
    lazy val html = contentAsString(views.html.fileList(juror, allJurors(GalleryView.FileList), null, files,
      Seq("1", "2"), Nil, Nil, None, Pager(1, 2), "wiki", wrapInTable = true, new RateDistribution(Map.empty),
      criteria = results))
    lazy val doc = Jsoup.parse(html)

    "have a column per criterion after the rate, labelled a sum" in {
      val heads = doc.select("thead th").asScala.map(_.text)
      heads.take(5) === Seq("Rank", "File", "Total rating (sum of the criteria)", "Quality", xss)
      doc.select("tfoot th").asScala.map(_.text).take(5) === heads.take(5)
    }

    "show each file's averages, empty where none" in {
      val rows = doc.select("tbody tr").asScala.map(_.select("td").asScala.map(_.text))
      rows.head.slice(2, 5) === Seq("14.50 (29 / 2)", "7.00", "7.50")
      rows(1).slice(3, 5) === Seq("", "")
    }

    "escape an admin's fallback name" in {
      noScript(doc, html)
    }

    "have no criteria columns in a round without criteria" in {
      val plain = round.copy(hasCriteria = false)
      val doc = Jsoup.parse(contentAsString(views.html.fileList(juror, RoundList(plain, 0, view = GalleryView.FileList),
        null, files, Seq("1", "2"), Nil, Nil, None, Pager(1, 2), "wiki", wrapInTable = true,
        new RateDistribution(Map.empty))))
      doc.select("thead th").asScala.map(_.text).take(4) === Seq("Rank", "File", "Total rating", "Mpx")
      doc.select(".wlx-criterion-average").size === 0
    }
  }

  "the large view of a criteria round for all the jurors" should {
    def render(votes: Seq[(Vote, User)] = Nil, jurorCriteria: JurorCriteria = JurorCriteria.empty) = {
      val list = RoundList(round, 0, view = GalleryView.ByRate)
      contentAsString(views.html.large.large(juror.copy(roles = Set("organizer")), list,
        Neighbourhood(files, 0, 0), None, Nil, votes,
        criteria.map(c => c -> results.of(301).toMap.apply(c).fold(0.0)(_.toDouble)), jurorCriteria))
    }

    "show the file's rate per criterion, then each criterion's average, read-only, which showed none before" in {
      val html = render()
      val doc = Jsoup.parse(html)
      doc.select(".wlx-criteria-overall").text() === "Overall, per criterion"
      // 7.25 per criterion
      doc.getElementById("rate-number").text() === "7.25"
      doc.getElementById("rate-image").attr("data-score") === "7.25"
      doc.select(".large-side .wlx-criterion-name").asScala.map(_.text) === Seq("Quality", xss)
      doc.getElementById("rate-number1").text() === "7"
      doc.getElementById("rate-number2").text() === "7.5"
      forall(Seq("rate-image", "rate-image1", "rate-image2")) { id =>
        doc.getElementById(id).attr("data-readonly") === "true"
        doc.getElementById(id).hasAttr("data-vote-prefix") must beFalse
      }
      noScript(doc, html)
    }

    "list each juror's vote with its stars per criterion and its rates of the criteria" in {
      val a = User("Juror A", "a@example.org", id = Some(11), roles = Set("jury"), contestId = Some(1))
      val b = User("<b>Juror B</b>", "b@example.org", id = Some(12), roles = Set("jury"), contestId = Some(1))
      val votes = Seq(Vote(301, 11, 1, 14, id = Some(91)) -> a, Vote(301, 12, 1, 0, id = Some(92)) -> b)
      val html = render(votes, JurorCriteria(criteria, Map(91L -> Map(1L -> 8, 2L -> 6), 92L -> Map(2L -> 3))))
      val doc = Jsoup.parse(html)
      val lines = doc.select(".large-line > ol > li").asScala
      lines must haveSize(2)
      // 14 over 2 criteria: 7 of 10 stars, the sum after them
      lines.head.select("i.wlx-star").size === 10
      lines.head.select("i.bi-star-fill").size === 7
      lines.head.select(".wlx-criteria-rates li").asScala.map(_.text) === Seq("Quality: 8", s"$xss: 6")
      // a partly rated vote: unrated (U5), its rated criterion shown
      lines(1).select("i.bi-star-fill").size === 0
      lines(1).select(".wlx-criteria-rates li").asScala.map(_.text) === Seq("Quality: –", s"$xss: 3")
      html must not(contain("<b>Juror B</b>"))
      noScript(doc, html)
    }
  }
}
