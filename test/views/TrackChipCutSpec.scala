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

    "tell apart three long names with one start, two of them cut around where the third differs" in {
      val (a, b, c) = ("a" * 75 + "X" + "a" * 24, "a" * 75 + "Y" + "a" * 24, "a" * 40 + "Z" + "a" * 59)
      val t = TrackChip.texts(Seq(a, b, c))
      // before: A and B each cut around position 40, where C differs, and read the same
      (TrackChip.distinct(a, Seq(b, c)) === TrackChip.distinct(b, Seq(a, c))) and
        (t.values.toSet.size === 3) and (t(a) must contain("X")) and (t(b) must contain("Y")) and
        (t(c) === TrackChip.distinct(c, Seq(a, b))) and
        t.values.forall(s => s.codePointCount(0, s.length) <= 32)
    }

    "always end with distinct texts, at worst the whole names" in {
      val names = (0 until 12).map(i => "x" * 40 + "y" * (i % 3) + "z" * 30 + i.toString)
      val t = TrackChip.texts(names)
      (t.keySet === names.toSet) and (t.values.toSet.size === names.size)
    }

    "widen a window that would read as another name's text, else show the clashing names whole" in {
      val (a, b, c) = ("a" * 75 + "X" + "a" * 24, "a" * 75 + "Y" + "a" * 24, "a" * 40 + "Z" + "a" * 59)
      // a name with "…" in it that reads as A's usual window: A's and B's must be wider
      val d = TrackChip.window(a, 75)
      val wide = TrackChip.texts(Seq(a, b, c, d))
      // with no wider window to try, A and B are shown whole
      val whole = TrackChip.texts(Seq(a, b, c, d), 4, _ => Seq(TrackChip.MaxLength))
      (d.codePointCount(0, d.length) <= 32) and (wide(d) === d) and (wide(a) !== d) and (wide(a) must contain("X")) and
        (wide(a).codePointCount(0, wide(a).length) > 32) and (wide.values.toSet.size === 4) and
        (whole(a) === a) and (whole(b) === b) and (whole(c) === wide(c)) and (whole(d) === d)
    }

    "show every name whole once the passes are used up" in {
      val (a, b, c) = ("a" * 75 + "X" + "a" * 24, "a" * 75 + "Y" + "a" * 24, "a" * 40 + "Z" + "a" * 59)
      val t = TrackChip.texts(Seq(a, b, c), 0, _ => Seq(TrackChip.MaxLength))
      t === Map(a -> a, b -> b, c -> c)
    }

    "keep each text as before where no two read the same" in {
      val wleCanada = Seq("Round 1 : Pre Selection / Cycle 1 : Pré-séléction", "Round 1 : Pre-selection / Cycle 1 : Pré-séléction")
      val jury = Seq("Images from Wiki Loves Earth 2026 in Somewhere - jury A - first round - final",
        "Images from Wiki Loves Earth 2026 in Somewhere - jury B - first round - final")
      val kyrgyzstan = Seq("Images from Wiki Loves Earth 2026 in Kyrgyzstan - Quality",
        "Images from Wiki Loves Earth 2026 in Kyrgyzstan - Quantity", "Quality", "Main")
      Seq(wleCanada, jury, kyrgyzstan, wleCanada ++ kyrgyzstan).forall { ns =>
        TrackChip.texts(ns) == ns.map(n => n -> TrackChip.distinct(n, ns.filter(_ != n))).toMap
      } must beTrue
    }
  }
}
