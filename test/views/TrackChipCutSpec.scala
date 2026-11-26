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

    "tell apart two names that would read the same (WLE Canada 2025)" in {
      val a = "Round 1 : Pre Selection / Cycle 1 : Pré-séléction"
      val b = "Round 1 : Pre-selection / Cycle 1 : Pré-séléction"
      val (ca, cb) = (TrackChip.distinct(a, Seq(b)), TrackChip.distinct(b, Seq(a)))
      (TrackChip.cut(a) === TrackChip.cut(b)) and (ca !== cb) and (ca === "Round 1 : Pre Selection / Cycle…") and
        (ca.codePointCount(0, ca.length) <= 32) and (cb.codePointCount(0, cb.length) <= 32) and
        // no clash: the usual cut
        (TrackChip.distinct(a, Seq("Quality")) === TrackChip.cut(a))
    }

    "tell apart names that differ only beyond the start, around their first difference" in {
      val a = "Images from Wiki Loves Earth 2026 in Somewhere - jury A - first round - final"
      val b = "Images from Wiki Loves Earth 2026 in Somewhere - jury B - first round - final"
      val (ca, cb) = (TrackChip.distinct(a, Seq(b)), TrackChip.distinct(b, Seq(a)))
      (TrackChip.cut(a) === TrackChip.cut(b)) and (TrackChip.prefix(a) === TrackChip.prefix(b)) and
        (ca === "…re - jury A - first round - fi…") and (cb must contain("jury B")) and
        (ca.codePointCount(0, ca.length) <= 32)
    }
  }
}
