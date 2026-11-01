package org.intracer.wmua.cmd

import db.scalikejdbc.Round
import org.intracer.wmua.{BinaryOrder, Image, ImageWithRating, Selection}
import org.specs2.mutable.Specification

class ImageWithRatingSeqFilterSpec extends Specification {

  val round = Some(Round(Some(5), 1, Some("Round"), 3))
  import ImageWithRatingSeqFilter._

  "ImageWithRatingSeqFilter" should {
    "funGenerators" in {

      funGenerators(round, includeRegionIds = Set("01")) === Seq(IncludeRegionIds(Set("01")))
      funGenerators(round, excludeRegionIds = Set("02")) === Seq(ExcludeRegionIds(Set("02")))

      funGenerators(round, includePageIds = Set(1)) === Seq(IncludePageIds(Set(1)))
      funGenerators(round, excludePageIds = Set(2)) === Seq(ExcludePageIds(Set(2)))

      funGenerators(round, includeTitles = Set("image1")) === Seq(IncludeTitles(Set("image1")))
      funGenerators(round, excludeTitles = Set("image2")) === Seq(ExcludeTitles(Set("image2")))

      funGenerators(round, includeJurorId = Set(3)) === Seq(IncludeJurorId(Set(3)))
      funGenerators(round, excludeJurorId = Set(4)) === Seq(ExcludeJurorId(Set(4)))

      funGenerators(round, selectTopByRating = Some(10)) === Seq(SelectTopByRating(10, round.get))
      funGenerators(round, selectTopByRating = Some(10), topOrder = Some(BinaryOrder.Difference)) ===
        Seq(SelectTopByRating(10, round.get, Some(BinaryOrder.Difference)))
      funGenerators(round, selectedAtLeast = Some(2)) === Seq(SelectedAtLeast(2))
    }

    "rank the top-N after the other filters, of the files they leave" in {
      val gens = funGenerators(round, selectTopByRating = Some(1), selectedAtLeast = Some(2), mpxAtLeast = Some(3),
        includeRegionIds = Set("01"), mediaType = Some("image"))
      (gens.size === 5) and (gens.last === SelectTopByRating(1, round.get))
    }
  }

  "a yes/no round's top-N" should {
    val binary = Round(Some(5), 1, Some("Round"), 3, rates = Round.binaryRound)

    /** A file of five jurors' votes, as the grouped query reads it. */
    def file(pageId: Long, approvals: Int, rejections: Int) =
      ImageWithRating(Image(pageId, s"File:$pageId.jpg"), Seq(Selection(pageId, 0, 0, approvals - rejections)), 5,
        approvalsFromDb = Some(approvals), rejectionsFromDb = Some(rejections))

    // A: 3 approvals, 2 rejections; B: 2 and 0; C: 3 and 0; D as B
    val files = Seq(file(4, 2, 0), file(1, 3, 2), file(3, 3, 0), file(2, 2, 0))

    "be picked by the order, ties by page id, as the SQL ranking picks it" in {
      def top(n: Int, order: BinaryOrder) = SelectTopByRating(n, binary, Some(order))()(files).map(_.pageId)
      top(4, BinaryOrder.ApprovalsThenRejections) === Seq(3, 1, 2, 4)
      top(4, BinaryOrder.Difference) === Seq(3, 2, 4, 1)
      top(4, BinaryOrder.ApprovalsOnly) === Seq(1, 3, 2, 4)
      top(1, BinaryOrder.Difference) === Seq(3)
    }

    "be from the candidates \"selected by\" read, each with its approvals as its rate" in {
      val candidates = Seq(file(1, 3, 2), file(2, 0, 1), file(3, 0, 5))
      DistributeImages.binaryCandidates(candidates, None).map(i => (i.pageId, i.rateSum)) === Seq((1, 3), (2, 0), (3, 0))
      DistributeImages.binaryCandidates(candidates, Some(true)).map(_.pageId) === Seq(1)
      // a stored 0: the files with an unrated vote
      DistributeImages.binaryCandidates(candidates, Some(false)).map(_.pageId) === Seq(2)
      SelectedAtLeast(3)()(DistributeImages.binaryCandidates(candidates, Some(true))).map(_.pageId) === Seq(1)
    }
  }
}
