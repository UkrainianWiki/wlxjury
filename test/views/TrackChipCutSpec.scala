package views

import org.specs2.mutable.Specification

/** The Track chip's text (views.TrackChip.cut): a name of 32 characters or fewer whole; a longer one
  * its start and its end with "…" between, cut at words, never only a beginning that other tracks' names share.
  */
class TrackChipCutSpec extends Specification {

  "a chip's text" should {

    "be the whole name up to 32 characters" in {
      (TrackChip.cut("Quality") === "Quality") and (TrackChip.cut("x" * 32) === "x" * 32)
    }

    "keep a long name's start and end, 32 characters with the ellipsis" in {
      val a = TrackChip.cut("Images from Wiki Loves Earth 2026 in Kyrgyzstan - Quality")
      val b = TrackChip.cut("Images from Wiki Loves Earth 2026 in Kyrgyzstan - Quantity")
      (a === "Images…Kyrgyzstan - Quality") and (a.codePointCount(0, a.length) <= 32) and
        (b must endWith("Quantity")) and (a !== b)
    }

    "count characters, not UTF-16 units" in {
      val cut = TrackChip.cut("Пам'ятки дерев'яної архітектури України 😀 і ще")
      (cut must contain("…")) and (cut.codePointCount(0, cut.length) <= 32)
    }
  }
}
