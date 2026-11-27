package views

import controllers.{GalleryView, ListOrder, RoundList, VoteFilter}
import db.scalikejdbc.Round
import org.intracer.wmua.{AverageRule, BinaryOrder}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.specs2.mutable.Specification
import play.api.libs.json.Json
import play.api.test.Helpers
import services.CutPicker
import services.TopNCandidates.Msg
import services.distribution.RoundDistributionHandler.TopNRanking

import scala.jdk.CollectionConverters._

/** The round form's one cut control (round/edit/topN; decided 2026-11-26), as rendered and as the picker
  * computes it: every valid cut as an "N / score" pair and nothing else (offered == allowed), the
  * candidate cuts labelled, "No top N" always; what a rated source's round keeps (the threshold by default,
  * or N) and what it stores (never both); a held cut preselected or explained (an N inside a tie, a
  * threshold no file reaches, a legacy round's both, no file matching); the link to the rating view per
  * cut; a round whose filters aren't edited shows its cut read only; and the rating view's cut
  * ([[RoundList.cutAt]]).
  */
class CutPickerViewSpec extends Specification {
  implicit val messages: play.api.i18n.Messages = Helpers.stubMessages()

  private val prev = Round(Some(1), 1, Some("Rated"), 1, rates = Round.ratesById(10))

  /** A rated ranking of these scores, best first. */
  private def ranking(scores: Double*): TopNRanking =
    TopNRanking(Seq(prev), None, Nil, scores.map(s => Seq(s)).toIndexedSeq, scores.toIndexedSeq)

  /** Ties at 2–4 and 6–7: valid values 1, 4, 5, 7, 8. */
  private val tied = ranking(9, 8, 8, 8, 7, 6, 6, 5)

  private def picker(r: TopNRanking = tied): CutPicker = CutPicker.build(r, if (r.order.isDefined) Nil else Seq(10.0), None)

  private val levels = TopNRanking(Seq(prev.copy(rates = Round.binaryRound)), Some(BinaryOrder.Default), Nil,
    IndexedSeq(Seq(3.0, 0.0), Seq(2.0, 1.0), Seq(2.0, 1.0), Seq(1.0, 2.0)), IndexedSeq.fill(4)(0.0))

  private def form(topImages: Option[Int], rate: Option[BigDecimal] = None) =
    controllers.EditRound.editRoundForm.fill(controllers.EditRound(
      new Round(id = None, number = 2, contestId = 1, previous = Some("1"), topImages = topImages, prevMinAvgRate = rate),
      Seq(1L), None))

  private def render(p: Option[CutPicker], topImages: Option[Int] = None, noEdit: Boolean = false,
      rate: Option[BigDecimal] = None): Document =
    Jsoup.parse(views.html.round.edit.topN(form(topImages, rate), p, noEdit, "/admin/rounds/cut-list").body)

  private def values(page: Document, css: String, attr: String): Seq[String] =
    page.select(css).asScala.toSeq.map(_.attr(attr))

  "the control" should {
    "offer exactly the valid cuts (offered == allowed), candidates first, and no top N, nothing else chosen" in {
      val p = picker()
      val page = render(Some(p))
      val offered = values(page, "select#topImages option", "value").filter(_.nonEmpty).map(_.toInt)
      (offered.sorted === Seq(1, 4, 5, 7, 8)) and
        forall(-1 to 10)(n => offered.contains(n) === p.result.refusal(n).isEmpty) and
        (values(page, "select#topImages > option", "value") === Seq("")) and
        (values(page, "select#topImages optgroup[label=top.n.candidates] option", "value") ===
          p.candidateOptions.map(_.toString)) and
        (values(page, "select#topImages optgroup[label=top.n.cut.others] option", "value") ===
          p.otherOptions.map(_.toString)) and
        (values(page, "select#topImages option[selected]", "value") === Seq("")) and
        (page.select("select#topImages[required]").size === 0) and
        (page.select("input[name=topImages][type=number], input[name=minAvgRate]").size === 0) and
        // the compact box: no gallery
        (page.select("#cut-gallery, .cut-file, [name=cutShow], [name=cutPick]").size === 0)
    }

    "name each cut by N and its threshold, a yes/no cut by N and its lowest level, and label the candidates" in {
      val p = picker()
      (p.cut(4) === Msg(CutPicker.ScoreCutKey, Seq(4, "8.0"))) and (p.cut(1) === Msg(CutPicker.ScoreCutKey, Seq(1, "9.0"))) and
        (p.cut(8) === Msg(CutPicker.ScoreCutKey, Seq(8, "5.0"))) and
        (picker(levels).cut(1) === Msg(CutPicker.LevelCutKey, Seq(1, Msg("top.n.level.approvals", Seq(3, 0))))) and
        (picker(levels).threshold(1) must beNone) and
        forall(p.candidateOptions)(n => p.optionText(n) must startWith(CutPicker.ScoreCutKey + " · ")) and
        forall(p.otherOptions)(n => p.optionText(n) === CutPicker.ScoreCutKey)
    }

    "find the shortest threshold that keeps file N and leaves out file N + 1, up to 4 places" in {
      (CutPicker.threshold(8.0, Some(7.0)) === Some(BigDecimal(8))) and
        (CutPicker.threshold(109.0 / 11, Some(9.9)) === Some(BigDecimal("9.909"))) and
        (CutPicker.threshold(98.0 / 99, Some(97.0 / 98)) === Some(BigDecimal("0.9898"))) and
        (CutPicker.threshold(26.0 / 3, Some(8.5)) === Some(BigDecimal("8.6"))) and
        (CutPicker.threshold(7.2, Some(7.1)) === Some(BigDecimal("7.2"))) and
        (CutPicker.threshold(5.0, None) === Some(BigDecimal(5))) and
        // 4 places don't separate averages of thousands of votes: that cut is kept as N
        (CutPicker.threshold(37501.0 / 30000, Some(1.25)) must beNone) and
        (CutPicker.fmtThreshold(BigDecimal(8)) === "8.0") and (CutPicker.fmtThreshold(BigDecimal("9.9090")) === "9.909")
    }

    "take a posted threshold rounded to 4 places in range, and none of an extreme exponent or scale, at once" in {
      import controllers.EditRound.threshold
      val started = System.nanoTime()
      val extreme = Seq("1e100000000", "1e-100000000", "-1e100000000", "abc", "1000000", "-0.0001").map(threshold)
      ((System.nanoTime() - started) / 1000000 must beLessThan(2000L)) and (extreme.flatten must beEmpty) and
        (threshold(" 7.25 ") === Some(BigDecimal("7.2500"))) and (threshold("9.90909") === Some(BigDecimal("9.9091"))) and
        (threshold("-0.00004") === Some(BigDecimal("0.0000"))) and (threshold("999999.99994") === Some(BigDecimal("999999.9999")))
    }

    "keep the threshold by default for a rated source, and N for a yes/no one" in {
      val page = render(Some(picker()))
      (values(page, "input[type=radio][name=cutKeep]", "value") === Seq("threshold", "top")) and
        (values(page, "input[type=radio][name=cutKeep][checked]", "value") === Seq("threshold")) and
        (page.select("#cutKeep_help").text must contain("top.n.keep.help")) and
        (render(Some(picker(levels))).select("input[name=cutKeep]").size === 0)
    }
  }

  "what the round stores" should {
    "be the threshold or the top N, never both" in {
      val p = picker()
      (p.copy(keep = CutPicker.Keep.Threshold).stored(4) === ((None, Some(BigDecimal(8))))) and
        (p.copy(keep = CutPicker.Keep.Top).stored(4) === ((Some(4), None))) and
        (picker(levels).copy(keep = CutPicker.Keep.Threshold).stored(1) === ((Some(1), None))) and
        // a legacy round's threshold kept while it takes the same files; else the cut's own
        (p.copy(heldThreshold = Some(BigDecimal("7.5"))).stored(4) === ((None, Some(BigDecimal("7.5"))))) and
        (p.copy(heldThreshold = Some(BigDecimal("7.5"))).stored(5) === ((None, Some(BigDecimal(7))))) and
        (p.countAtLeast(BigDecimal("7.5")) === 4) and (p.countAtLeast(BigDecimal(10)) === 0) and
        (p.countAtLeast(BigDecimal(0)) === 8) and
        ((p.basis(4, Some(BigDecimal(8))) \ "threshold").as[BigDecimal] === BigDecimal(8)) and
        ((p.basis(4) \ "threshold").toOption must beNone)
    }
  }

  "a held cut" should {
    "preselect a valid held N, kept as N unless posted otherwise" in {
      val held = picker().holding(Some(4), None, None)
      val page = render(Some(held), Some(4))
      (held.chosenValid === Some(4)) and (held.keep === CutPicker.Keep.Top) and (held.pick must beNone) and
        (values(page, "select#topImages option[selected]", "value") === Seq("4")) and
        (values(page, "input[name=cutKeep][checked]", "value") === Seq("top")) and
        (picker().holding(Some(4), None, Some(CutPicker.Keep.Threshold)).keep === CutPicker.Keep.Threshold)
    }

    "come with the page only after a save refused its cut: not loaded again as the form opens, its count to announce" in {
      val page = render(Some(picker()))
      (page.select("#top-n[data-cut-loaded]").size === 1) and
        (page.select("#top-n-box > div[data-cut-announce]").attr("data-cut-announce") === "top.n.cuts.available") and
        (page.select("#top-n-box > div").attr("data-cut-count") === "5") and
        (page.selectFirst("#topImages_choose_help").ownText() === "top.n.choose.help") and
        (page.select("#topImages_choose_help a[data-help-topic=top-files]").size === 1) and
        (page.select("select#topImages").attr("aria-describedby") must contain("topImages_choose_help"))
    }

    "explain a held N inside a tie and post it again from the placeholder, never dropping it" in {
      val held = picker().holding(Some(3), None, None)
      val page = render(Some(held), Some(3))
      (held.pick.map(_.msg.key) === Some("top.n.in.tie")) and
        (values(page, "select#topImages option", "value").take(2) === Seq("3", "")) and
        (values(page, "select#topImages option", "value").drop(2).sorted === Seq("1", "4", "5", "7", "8")) and
        (page.select("select#topImages option[selected]").text === "top.n.choose.placeholder") and
        (values(page, "select#topImages option[selected]", "value") === Seq("3")) and
        (page.select("select#topImages[required]").size === 0) and
        (page.select("#top-n-held").text === "top.n.in.tie")
    }

    "preselect the cut a held threshold keeps now, kept as a threshold" in {
      val held = picker().holding(None, Some(BigDecimal("7.5")), None)
      val page = render(Some(held), rate = Some(BigDecimal("7.5")))
      (held.chosenValid === Some(4)) and (held.keep === CutPicker.Keep.Threshold) and
        (values(page, "select#topImages option[selected]", "value") === Seq("4")) and
        (values(page, "input[name=cutHeldRate]", "value") === Seq("7.5"))
    }

    "explain a held threshold no file reaches, and require a pick" in {
      val held = picker().holding(None, Some(BigDecimal("9.5")), None)
      val page = render(Some(held))
      (held.pick.map(_.msg) === Some(Msg(CutPicker.HeldThresholdNoneKey, Seq("9.5")))) and
        (values(page, "select#topImages option[selected]", "value") === Seq("")) and
        (page.select("select#topImages[required]").size === 1)
    }

    "explain a legacy round's top N and threshold, and require a pick" in {
      val held = picker().holding(Some(5), Some(BigDecimal("7.5")), None)
      (held.pick.map(_.msg) === Some(Msg(CutPicker.HeldBothKey, Seq(5, "7.5", 4)))) and (held.chosenValid must beNone) and
        (render(Some(held), Some(5)).select("select#topImages[required]").size === 1)
    }

    "ignore a yes/no source's threshold, as its fill does" in {
      val held = picker(levels).holding(None, Some(BigDecimal(1)), None)
      (held.pick must beNone) and (held.chosen must beNone) and (held.heldThreshold must beNone)
    }

    "keep a held N when no file matches the filters, with \"No top N\" to pick" in {
      val none = picker(ranking()).holding(Some(37), None, None)
      val page = render(Some(none), Some(37))
      (none.pick.map(_.msg.key) === Some("top.n.no.candidates")) and
        (values(page, "select#topImages option", "value") === Seq("37", "")) and
        (values(page, "select#topImages option[selected]", "value") === Seq("37")) and
        (page.select("#topImages_help").text === "top.n.no.candidates") and
        (page.select("input[name=cutKeep]").size === 0)
    }
  }

  "the rating view's link" should {
    "follow each cut, hidden without one, and appear only with links" in {
      val p = picker().copy(links = Map(1 -> "/r/1", 4 -> "/r/4", 5 -> "/r/5", 7 -> "/r/7", 8 -> "/r/8"))
      val page = render(Some(p))
      val chosen = render(Some(p.holding(Some(4), None, None)), Some(4))
      (values(page, "select#topImages option[data-cut-href]", "data-cut-href").sorted ===
        Seq("/r/1", "/r/4", "/r/5", "/r/7", "/r/8")) and
        (page.select("a#top-n-cut-link[hidden][target=_blank][rel=noopener]").size === 1) and
        (chosen.select("a#top-n-cut-link:not([hidden])").attr("href") === "/r/4") and
        (render(Some(picker())).select("#top-n-cut-link").size === 0)
    }

    "open the previous round's rating view at the cut's page, in the picker's order" in {
      (CutPicker.viewUrl(7, None, 88) === "/byrate/round/7/user/0/page/6?sort=rate&cut=88#cut") and
        (CutPicker.viewUrl(7, Some(BinaryOrder.Difference), 15) === "/byrate/round/7/user/0/page/1?sort=difference&cut=15#cut") and
        (CutPicker.viewUrl(7, None, 16) must contain("/page/2?")) and {
          // the page the rating view puts file N on, by its own page size
          val size = RoundList(prev, 0, view = GalleryView.ByRate).pageSize
          forall(Seq(size, size + 1, 3 * size + 2, 1000)) { n =>
            CutPicker.viewUrl(7, None, n) must contain(s"/page/${controllers.Pager.pageOf(n - 1, size)}?")
          } and (CutPicker.viewUrl(7, None, size + 1) must contain("/page/2?"))
        }
    }
  }

  "the cut viewable in the rating view (the settings half)" should {
    val next = new Round(id = None, number = 2, contestId = 1, previous = Some("1"))
    "need one rated or yes/no previous round and no filter the rating view doesn't apply" in {
      (CutPicker.viewable(next, tied) must beTrue) and
        (CutPicker.viewable(next.copy(previous = Some("1,3")), tied.copy(prevRounds = Seq(prev, prev.copy(id = Some(3))))) must
          beFalse) and
        forall(Seq[Round => Round](
          _.copy(regions = Some("80")), _.copy(monuments = Some("80-361-0001")), _.copy(category = Some("Category:A")),
          _.copy(excludeRounds = Seq(5L)), _.copy(minMpx = Some(2)),
          _.copy(minImageSize = Some(1)), _.copy(mediaType = Some("video")), _.copy(specialNomination = Some("X")),
          _.copy(prevSelectedBy = Some(1)), _.copy(prevRejectedBy = Some(1))
        ))(f => CutPicker.viewable(f(next), tied) must beFalse) and
        // the threshold is the cut itself, not a filter; conflicts of interest (on by default) count only if
        // they leave a file out, which the data half checks
        (CutPicker.viewable(next.copy(prevMinAvgRate = Some(BigDecimal(7))), tied) must beTrue) and
        (CutPicker.viewable(next.copy(excludeCoi = true), tied) must beTrue) and
        (CutPicker.viewable(next, levels) must beTrue) and
        // a yes/no round ranked without an order (the legacy rule) is not the view's order
        (CutPicker.viewable(next, levels.copy(order = None)) must beFalse) and
        // a comments-only round has no ratings
        (CutPicker.viewable(next, tied.copy(prevRounds = Seq(prev.copy(rates = Round.ratesById(0))))) must beFalse)
    }
  }

  "the rating view's cut" should {
    def list(round: Round, order: ListOrder = ListOrder.ByRate) = RoundList(round, 0, order = order, view = GalleryView.ByRate)
    val rated = prev.copy(averageRule = AverageRule.Rated)
    val k = AverageRule.KeyScale
    // averages 9, 8 (three files, two sums: one score), 7: the rank key is (average, sum)
    val counts = Seq(Seq(9 * k, 27L) -> 1, Seq(8 * k, 24L) -> 1, Seq(8 * k, 16L) -> 2, Seq(7 * k, 21L) -> 1)
    "fall only between two scores, named as the control names it" in {
      (list(rated).cutAt(1, counts) === Some(Msg(CutPicker.ScoreCutKey, Seq(1, "9.0")))) and
        (list(rated).cutAt(4, counts) === Some(Msg(CutPicker.ScoreCutKey, Seq(4, "8.0")))) and
        (list(rated).cutAt(5, counts) === Some(Msg(CutPicker.ScoreCutKey, Seq(5, "7.0")))) and
        // inside the tie of 8 (between two sums), nor 0, nor past the end
        forall(Seq(0, 2, 3, 6, -1))(n => list(rated).cutAt(n, counts) must beNone)
    }

    "use a sum round's sum and a yes/no round's level" in {
      val sum = prev.copy(averageRule = AverageRule.Sum)
      val binary = prev.copy(rates = Round.binaryRound)
      (list(sum).cutAt(1, Seq(Seq(30L) -> 1, Seq(25L) -> 2)) === Some(Msg(CutPicker.ScoreCutKey, Seq(1, "30.0")))) and
        (list(binary, ListOrder.Binary(BinaryOrder.ApprovalsOnly)).cutAt(2, Seq(Seq(3L) -> 2, Seq(1L) -> 1)) ===
          Some(Msg(CutPicker.LevelCutKey, Seq(2, Msg("top.n.level.yes", Seq(3))))))
    }

    "show nothing on any other list" in {
      forall(Seq(
        list(rated).copy(asUserId = 5), list(rated).copy(region = Some("80")), list(rated).copy(filter = VoteFilter.Unrated),
        list(rated).copy(comments = true), list(rated).copy(monument = Some("80-1")), list(rated).copy(view = GalleryView.Gallery),
        list(rated, ListOrder.FileOrder), list(prev.copy(rates = Round.binaryRound), ListOrder.ByRate)
      ))(l => l.cutAt(1, counts) must beNone)
    }
  }

  "a cut before the control is loaded" should {
    "show the form's top N, threshold or both, carried as hidden fields, to be loaded in place; no \"Choose N\"" in {
      val page = render(None, Some(37), rate = Some(BigDecimal("7.25")))
      (values(page, "#topImages_field input[type=hidden][name=topImages]", "value") === Seq("37")) and
        (values(page, "#topImages_field input[type=hidden][name=minAvgRate]", "value") === Seq("7.25")) and
        (page.select("#topImages span").asScala.map(_.text).toSeq === Seq(CutPicker.TopCutKey, "top.n.held.threshold")) and
        (page.select("#topImages-choose, [formaction]").size === 0) and
        (page.select("#top-n[data-cut-url=/admin/rounds/cut-list]:not([data-cut-loaded])").size === 1) and
        (page.select("#top-n").attr("data-cut-updating") === "top.n.updating") and
        (Jsoup.parse(page.select("#top-n noscript").html()).text === "top.n.needs.js") and
        (page.select("#top-n-status[role=status][aria-live=polite]").size === 1) and
        (page.select("#top-n-box > div[data-cut-announce]").size === 1) and
        (page.select("#topImages_choose_help a[data-help-topic=top-files]").size === 1) and
        (page.select("select#topImages").size === 0) and
        (render(None).select("#topImages").text === "top.n.none")
    }

    "keep the posting mode and held threshold when a refresh cannot rank the files" in {
      val posted = form(Some(4))
      val unavailable = posted.copy(data = posted.data ++ Map(
        CutPicker.KeepField -> CutPicker.Keep.Threshold.param, CutPicker.HeldThresholdField -> "7.25"))
        .withError("topImages", "top.n.commons.unavailable")
      val box = Jsoup.parseBodyFragment(views.html.round.edit.topNBox(unavailable, None).body)
      (values(box, "input[type=hidden][name=topImages]", "value") === Seq("4")) and
        (values(box, s"input[type=hidden][name=${CutPicker.KeepField}]", "value") === Seq("threshold")) and
        (values(box, s"input[type=hidden][name=${CutPicker.HeldThresholdField}]", "value") === Seq("7.25"))
    }

    "show a started round's top N and threshold read only (#225's locked look), nothing submitted" in {
      val both = render(None, Some(3), noEdit = true, rate = Some(BigDecimal("7.5")))
      val one = render(None, None, noEdit = true, rate = Some(BigDecimal("7.5")))
      (both.select("input#topImages.wlx-locked[disabled]").attr("value") === "3") and
        (both.select("input#minAvgRate.wlx-locked[disabled]").attr("value") === "7.5") and
        (both.select("input#topImages").attr("aria-describedby") must contain("round-filters-locked")) and
        (both.select("#topImages-choose, #top-n, noscript").size === 0) and
        (both.select("input:not([disabled])").size === 0) and
        (one.select("input#topImages").size === 0) and (one.select("input#minAvgRate[disabled]").size === 1) and
        (render(None, None, noEdit = true).select("input").size === 0)
    }
  }

  "a stored choice" should {
    "be told apart from the files ranked now: another count, another key at its N" in {
      val p = picker()
      val basis = p.basis(4)
      (p.differsFrom(basis) must beFalse) and (p.differsFrom(basis ++ Json.obj("files" -> 9)) must beTrue) and
        (p.differsFrom(basis ++ Json.obj("key" -> Seq(7.0))) must beTrue) and
        ((basis \ "how").as[String] === "list") and ((p.basis(8) \ "how").as[String] === "candidate") and
        (render(Some(p.copy(changedSince = true))).select("#top-n-changed").size === 1)
    }
  }

  "the settings log's basis" should {
    "say how a cut was chosen, reading only the picker's keys, and a threshold's files when saved" in {
      val candidate = Json.obj("how" -> "candidate", "reasons" -> Seq(
        Json.obj("key" -> "top.n.reason.drop", "args" -> Seq("7.2", "6.1")), Json.obj("key" -> "secret.key", "args" -> Seq.empty[String])))
      (RoundChangeText.basis(candidate) === "top.n.basis.candidate") and
        (RoundChangeText.basis(Json.obj("how" -> "list")) === "top.n.basis.list") and
        (RoundChangeText.basis(Json.obj("how" -> "list", "chosen" -> 88, "threshold" -> 8)) ===
          "top.n.basis.threshold; top.n.basis.list")
    }
  }
}
