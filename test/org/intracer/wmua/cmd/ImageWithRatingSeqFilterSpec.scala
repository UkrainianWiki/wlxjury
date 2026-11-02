package org.intracer.wmua.cmd

import db.scalikejdbc.Round
import org.intracer.wmua.{BinaryOrder, Image, ImageWithRating, Vote}
import org.specs2.mutable.Specification

class ImageWithRatingSeqFilterSpec extends Specification {

  val round = Some(Round(Some(5), 1, Some("Round"), 3))
  import ImageWithRatingSeqFilter._

  "ImageWithRatingSeqFilter" should {
    "funGenerators" in {
      (funGenerators(round, includeRegionIds = Set("01")) === Seq(IncludeRegionIds(Set("01")))) and
        (funGenerators(round, excludeRegionIds = Set("02")) === Seq(ExcludeRegionIds(Set("02")))) and
        (funGenerators(round, includePageIds = Set(1)) === Seq(IncludePageIds(Set(1)))) and
        (funGenerators(round, excludePageIds = Set(2)) === Seq(ExcludePageIds(Set(2)))) and
        (funGenerators(round, includeTitles = Set("image1")) === Seq(IncludeTitles(Set("image1")))) and
        (funGenerators(round, excludeTitles = Set("image2")) === Seq(ExcludeTitles(Set("image2")))) and
        (funGenerators(round, includeJurorId = Set(3)) === Seq(IncludeJurorId(Set(3)))) and
        (funGenerators(round, excludeJurorId = Set(4)) === Seq(ExcludeJurorId(Set(4)))) and
        (funGenerators(round, selectTopByRating = Some(10)) === Seq(SelectTopByRating(10, round.get))) and
        (funGenerators(round, selectTopByRating = Some(10), topOrder = Some(BinaryOrder.Difference)) ===
          Seq(SelectTopByRating(10, round.get, Some(BinaryOrder.Difference)))) and
        (funGenerators(round, selectedAtLeast = Some(2)) === Seq(SelectedAtLeast(2))) and
        (funGenerators(round, rejectedBy = Some(2)) === Seq(RejectedByLessThan(2)))
    }

    "rank the top-N after the other filters, of the files they leave" in {
      val gens = funGenerators(round, selectTopByRating = Some(1), selectedAtLeast = Some(2), mpxAtLeast = Some(3),
        includeRegionIds = Set("01"), mediaType = Some("image"))
      (gens.size === 5) and (gens.last === SelectTopByRating(1, round.get))
    }

    "apply rejected-by before the top-N, which ranks only the files it keeps" in {
      val gens = funGenerators(round, selectTopByRating = Some(1), rejectedBy = Some(2))
      (gens === Seq(RejectedByLessThan(2), SelectTopByRating(1, round.get)))
    }
  }

  "a yes/no round's top-N" should {
    val binary = Round(Some(5), 1, Some("Round"), 3, rates = Round.binaryRound)

    /** A file of five jurors' votes, as the grouped query reads it. */
    def file(pageId: Long, approvals: Int, rejections: Int) =
      ImageWithRating(Image(pageId, s"File:$pageId.jpg"), Seq(Vote(pageId, 0, 0, approvals - rejections)), 5,
        approvalsFromDb = Some(approvals), rejectionsFromDb = Some(rejections))

    // A: 3 approvals, 2 rejections; B: 2 and 0; C: 3 and 0; D as B
    val files = Seq(file(4, 2, 0), file(1, 3, 2), file(3, 3, 0), file(2, 2, 0))

    "be picked by the order, ties by page id, as the SQL ranking picks it" in {
      def top(n: Int, order: BinaryOrder) = SelectTopByRating(n, binary, Some(order))()(files).map(_.pageId)
      (top(4, BinaryOrder.ApprovalsThenRejections) === Seq(3, 1, 2, 4)) and
        (top(4, BinaryOrder.Difference) === Seq(3, 2, 4, 1)) and
        (top(4, BinaryOrder.ApprovalsOnly) === Seq(1, 3, 2, 4)) and
        (top(1, BinaryOrder.Difference) === Seq(3))
    }

    "be from the candidates \"selected by\" read, each with its approvals as its rate" in {
      val candidates = Seq(file(1, 3, 2), file(2, 0, 1), file(3, 0, 5))
      (DistributeImages.binaryCandidates(candidates, rated = false).map(i => (i.pageId, i.rateSum)) ===
        Seq((1, 3), (2, 0), (3, 0))) and
        (DistributeImages.binaryCandidates(candidates, rated = true).map(_.pageId) === Seq(1)) and
        (SelectedAtLeast(3)()(DistributeImages.binaryCandidates(candidates, rated = true)).map(_.pageId) === Seq(1))
    }

    "keep the files rejected by fewer than N jurors" in {
      val candidates = Seq(file(1, 3, 2), file(2, 0, 1), file(3, 0, 5), file(4, 2, 0))
      def kept(n: Int) = RejectedByLessThan(n)()(candidates).map(_.pageId)
      (kept(1) === Seq(4)) and (kept(2) === Seq(2, 4)) and (kept(3) === Seq(1, 2, 4)) and (kept(6) === Seq(1, 2, 3, 4))
    }

    "rank the top-N of the files rejected-by keeps" in {
      // by approvals, file 1 (3 approvals, 2 rejections) would lead; rejected-by 2 drops it first
      val filters = funGenerators(Some(binary), selectTopByRating = Some(1), topOrder = Some(BinaryOrder.ApprovalsOnly),
        rejectedBy = Some(2))
      val picked = filters.foldLeft(files)((acc, gen) => gen()(acc)).map(_.pageId)
      picked === Seq(3)
    }
  }
}
