package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import org.intracer.wmua.{Image, ImageWithRating, Vote}
import org.jsoup.Jsoup
import play.api.mvc.{Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.jdk.CollectionConverters._

/** The by-rate gallery of a rated round: each file's rank and rate in its own block inside its
  * tile, every tile inside the gallery list, and no stray markup text (a mangled tag once left
  * `">` before each rank and closed the list after the first tile); of a binary round, its
  * approvals or a juror's votes, never stars. The juror's own list of an active rated round
  * without criteria gets stars to click (tile-votes.js) where any other has static ones.
  */
class GalleryByRateViewSpec extends PlaySpecification {

  private val juror = LargeViewRenders.juror
  private val round = LargeViewRenders.ratedRound

  private val files: Seq[ImageWithRating] = (0 until 4).map { i =>
    val pageId = 201L + i
    ImageWithRating(
      Image(pageId = pageId, title = s"File:Image $pageId.jpg",
        url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image_$pageId.jpg"),
        monumentId = Some(s"07-101-000$i")),
      votes = Seq(Vote(pageId, juror.getId, round.getId, i + 1))
    )
  }

  /** The files' ranks, as RoundList.ranks gives them: the last two tie. */
  private val ranks = Seq("4", "3", "1-2", "1-2")

  implicit val request: RequestHeader = FakeRequest("GET", "/byrate/round/1/user/1/page/1").withCSRFToken
  implicit val flash: Flash = Flash()
  implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request.asInstanceOf[play.api.mvc.Request[_]])

  private val list = RoundList(round, juror.getId, view = GalleryView.ByRate)

  private def checkTiles(html: String, count: Int) = {
    val doc = Jsoup.parse(html)
    val boxes = doc.select("li.gallerybox").asScala
    boxes must haveSize(count)
    forall(boxes) { box =>
      box.parent().tagName() === "ul"
      box.parent().hasClass("gallery") must beTrue
      box.select("div.rank-line").size() === 1
      box.select("div.rank-line b").text() must not(beEmpty)
    }
    // each file's rank, a range for files that tie
    doc.select("div.rank-line b").asScala.map(_.text) === Seq("4.", "3.", "1-2.", "1-2.")
    // no markup shown as text, nor control characters
    doc.body().text() must not(contain("\">"))
    html must not(contain("\u0001"))
  }

  "the by-rate gallery" should {
    "keep each tile, with its rank, inside the gallery list" in {
      val html = contentAsString(views.html.galleryByRate(
        juror, list, files, Pager(1, files.size), Nil, None, new RateDistribution(Map.empty), ranks))
      checkTiles(html, files.size)
    }

    "show no rank without ranks (a rated round's gallery in file order)" in {
      val html = contentAsString(views.html.galleryByRate(
        juror, list.withView(GalleryView.Gallery), files, Pager(1, files.size), Nil, None,
        new RateDistribution(Map.empty)))
      Jsoup.parse(html).select("div.rank-line b").asScala.map(_.text).distinct === Seq("")
    }
  }

  "a binary round's by-rate gallery" should {
    val binary = LargeViewRenders.binaryRound

    def tile(file: ImageWithRating, asUserId: Long) = {
      val html = contentAsString(views.html.galleryByRate(juror, RoundList(binary, asUserId, view = GalleryView.ByRate),
        Seq(file), Pager(1, 1), Nil, None, new RateDistribution(Map.empty)))
      Jsoup.parse(html).selectFirst("li.gallerybox .rate-stars")
    }

    def file(rates: Int*) = ImageWithRating(Image(pageId = 301L, title = "File:Binary.jpg"),
      votes = rates.zipWithIndex.map { case (rate, i) => Vote(301L, i + 1L, binary.getId, rate) })

    "show all the jurors' approvals and rejections as a check and a cross with their numbers" in {
      // as the query of all the jurors' votes has them: one row per file, their rates' sum
      // (2 approvals and a rejection give 1) and the approvals and rejections counted apart
      val grouped = ImageWithRating(Image(pageId = 301L, title = "File:Binary.jpg"),
        votes = Seq(Vote(301L, 0, 0, 1)), countFromDb = 3, approvalsFromDb = Some(2),
        rejectionsFromDb = Some(1))
      val rate = tile(grouped, asUserId = 0)
      val shown = rate.selectFirst("[aria-hidden=true]")
      (shown.select("i.bi-check-lg.wlx-vote-selected").size must_== 1) and
        (shown.select("i.bi-x-lg.wlx-vote-rejected").size must_== 1) and
        (shown.text must_== "2 1") and
        (rate.select("[class*=bi-star]").size must_== 0) and
        (rate.select("button").size must_== 0) and
        // screen readers read the text in its place (stub messages: the key)
        (rate.select(".visually-hidden").text must_== "binary.tally") and
        (rate.selectFirst(".wlx-approvals").attr("title") must_== "binary.tally")
    }

    "say the approvals and rejections in words" in {
      val en = LargeViewRenders.realMessages
      (en("binary.tally", 3, 2) must_== "3 approvals, 2 rejections") and
        (en("binary.tally", 1, 0) must_== "1 approval, 0 rejections") and
        (en("binary.tally", 1200, 1) must_== "1,200 approvals, 1 rejection")
    }

    "show a juror's vote: a check if selected, a cross if rejected, nothing if unrated" in {
      def marks(rate: Int) = {
        val r = tile(file(rate), asUserId = 1)
        (r.select(".bi-check-lg").size, r.select(".bi-x-lg").size, r.select("[class*=bi-star]").size,
          r.select(".visually-hidden").text)
      }
      (marks(1) must_== ((1, 0, 0, "selected"))) and
        (marks(-1) must_== ((0, 1, 0, "rejected"))) and
        (marks(0) must_== ((0, 0, 0, "")))
    }
  }

  "the by-rate stars" should {
    import db.scalikejdbc.Round

    def page(round: Round, asUserId: Long = juror.getId) = Jsoup.parse(contentAsString(views.html.galleryByRate(
      juror, RoundList(round, asUserId, view = GalleryView.ByRate), files, Pager(1, files.size), Nil, None,
      new RateDistribution(Map.empty), ranks)))

    "be the juror's to click in their own list of an active rated round" in {
      val doc = page(round)
      val list = doc.selectFirst("[data-stars-number]")
      list.attr("data-stars-number") === "5"
      list.attr("data-stars-half") === "false"
      // the texts by key (stub messages; their values in all four files: MessagesCompletenessSpec)
      list.attr("data-changed-title") === "byrate.changed.title"
      list.attr("data-csrf-token") must not(beEmpty)
      list.attr("data-vote-closed") === "vote.error.closed"
      // each file's static stars (shown until the script replaces them) in a holder with its vote
      val holders = doc.select(".wlx-byrate-stars").asScala
      holders.map(h => (h.attr("data-round-id"), h.attr("data-page-id"), h.attr("data-rate"))) ===
        files.map(f => ("1", f.pageId.toString, f.rate.toString))
      forall(holders)(_.select("i.wlx-star").size === 5)
      doc.select(".wlx-rate-text").asScala.map(_.text) === Seq("1", "2", "3", "4")
      val hint = doc.selectFirst(".wlx-byrate-hint")
      hint.hasAttr("hidden") must beTrue
      hint.text === "byrate.changed.hint"
      doc.select("script[src*=jquery.raty.js]").size === 1
      doc.select("script[src*=tile-votes.js]").size === 1
      checkTiles(doc.outerHtml, files.size)
    }

    "say that a vote's time follows it only in the juror's own list by vote time (tile-votes.js)" in {
      import controllers.ListOrder
      def votesList(order: ListOrder) = Jsoup.parse(contentAsString(views.html.galleryByRate(
        juror, RoundList(round, juror.getId, order = order, view = GalleryView.ByRate), files, Pager(1, files.size), Nil,
        None, new RateDistribution(Map.empty), ranks))).selectFirst("[data-stars-number]")
      (votesList(ListOrder.LatestVote).attr("data-vote-times") === "true") and
        (votesList(ListOrder.OldestVote).attr("data-vote-times") === "true") and
        (votesList(ListOrder.ByRate).hasAttr("data-vote-times") must beFalse) and
        (votesList(ListOrder.FileOrder).hasAttr("data-vote-times") must beFalse)
    }

    "show a rated round's result to an organiser as stars and the rate, without a tally" in {
      // grouped rows as all the jurors' list of a rated round has them: their rates' sum and
      // their rated votes (the rule's denominator), no tally drawn
      val grouped = files.map(f => f.copy(votes = Seq(Vote(f.pageId, 0, 0, f.rate * 3)), countFromDb = 3,
        approvalsFromDb = Some(if (f.rate > 0) 3 else 0)))
      val doc = Jsoup.parse(contentAsString(views.html.galleryByRate(
        juror, RoundList(round, 0, view = GalleryView.ByRate), grouped, Pager(1, grouped.size), Nil, None,
        new RateDistribution(Map.empty), ranks)(Flash(), LargeViewRenders.realMessages, request)))
      (doc.select(".wlx-approvals").size === 0) and
        (doc.select("[data-tally]").size === 0) and
        (doc.select(".rate-stars i.bi-check-lg, .rate-stars i.bi-x-lg").size === 0) and
        (doc.select(".rate-stars").asScala.map(_.text) === Seq("1.00 (3 / 3 jurors)", "2.00 (6 / 3 jurors)", "3.00 (9 / 3 jurors)", "4.00 (12 / 3 jurors)"))
    }

    "show a rated round's worst marks to an organiser as the icon and their number beside the score, the words for screen readers" in {
      // one file with two worst marks of three votes (its grades' sum 4, one rated vote with stars), one without
      val worst = files.head.copy(votes = Seq(Vote(files.head.pageId, 0, 0, 4)), countFromDb = 3,
        approvalsFromDb = Some(1), rejectionsFromDb = Some(2))
      val clean = files(1).copy(votes = Seq(Vote(files(1).pageId, 0, 0, 6)), countFromDb = 3,
        approvalsFromDb = Some(3), rejectionsFromDb = Some(0))
      val worstRound = round.copy(worstMark = true)
      val doc = Jsoup.parse(contentAsString(views.html.galleryByRate(
        juror, RoundList(worstRound, 0, view = GalleryView.ByRate), Seq(worst, clean), Pager(1, 2), Nil, None,
        new RateDistribution(Map.empty), ranks.take(2))(Flash(), LargeViewRenders.realMessages, request)))
      val marks = doc.select(".wlx-worst-marks").asScala
      (marks.map(_.select("> [aria-hidden]").text) === Seq("2")) and
        (marks.map(_.select("i.bi-x-lg.wlx-worst-mark[aria-hidden=true]").size) === Seq(1)) and
        (marks.map(_.select(".visually-hidden").text) === Seq("2 worst marks")) and
        (marks.map(_.attr("title")) === Seq("2 worst marks")) and
        (doc.text must not(contain("✗"))) and
        (doc.select(".rate-stars").asScala.head.text must startWith("1.33 (4 / 3 jurors)"))
    }

    "be in half stars in a half-star round" in {
      val list = page(LargeViewRenders.halfStarRound).selectFirst("[data-stars-number]")
      (list.attr("data-stars-number"), list.attr("data-stars-half")) === (("5", "true"))
    }

    "stay static elsewhere" in {
      val static = Seq(
        page(round, asUserId = LargeViewRenders.otherJurorId), // another juror's votes
        page(round, asUserId = 0), // all the jurors'
        page(round.copy(active = false)), // stopped
        page(LargeViewRenders.binaryRound),
        page(LargeViewRenders.criteriaRound),
        page(round.copy(rates = Round.commentsOnlyRound))
      )
      forall(static) { doc =>
        doc.select("[data-stars-number]").size === 0
        doc.select(".wlx-byrate-stars").size === 0
        doc.select(".wlx-rate-text").size === 0
        doc.select(".wlx-byrate-hint").size === 0
        doc.select("script[src*=jquery.raty.js]").size === 0
      }
    }

    "keep the static stars and rate in another juror's list of a rated round" in {
      val doc = page(round, asUserId = LargeViewRenders.otherJurorId)
      doc.select("li.gallerybox .rate-stars i.wlx-star").size === files.size * 5
      doc.select("li.gallerybox .rate-stars").asScala.map(_.text) === Seq("1", "2", "3", "4")
    }
  }
}
