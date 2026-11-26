package services

import org.specs2.mutable.Specification
import services.TopNCandidates._

/** The cut picker's candidate cuts and valid values (regional winners plan §10.4, §10.9): C1, C2, C3 and C5
  * on rated lists (no C4 and no target, decided 2026-11-26), yes/no levels, ties at the window's edges and
  * across it, few candidates, the windows, at most
  * six candidates with one N's reasons merged, never N = 0 nor a value inside a tie, nothing above 25
  * for a region, and each reason's message arguments.
  */
class TopNCandidatesSpec extends Specification {

  /** Rated files of these scores (best first), their keys the score alone (ties by the score, decided
    * 2026-11-26).
    */
  private def rated(scores: Double*): (IndexedSeq[Seq[Double]], IndexedSeq[Double]) =
    (scores.map(s => Seq(s)).toIndexedSeq, scores.toIndexedSeq)

  private def ratedResult(window: Window, range: Double, scores: Double*): Result = {
    val (keys, s) = rated(scores: _*)
    compute(keys, s, Rated(range), window)
  }

  private def ns(r: Result): Seq[Int] = r.candidates.map(_.n)

  private def keysOf(r: Result, n: Int): Seq[String] = r.candidate(n).toSeq.flatMap(_.reasons.map(_.key))

  /** Yes/no files of these (approvals, rejections), best first. */
  private val describe: Seq[Double] => Msg = k => Msg("top.n.level.approvals", k.map(_.toInt))

  private def levels(window: Window, files: (Int, Int)*): Result =
    compute(files.map { case (a, r) => Seq(a.toDouble, r.toDouble) }.toIndexedSeq, IndexedSeq.fill(files.size)(0.0),
      Levels(describe), window)

  "the windows" should {
    "be the first 100 places for a main round (no target)" in {
      Window.main === Window(1, 100)
    }

    "be a region's [min(3, m), min(15, max(lo, ⌈m/3⌉))]" in {
      (Window.region(1) === Window(1, 1, region = true)) and (Window.region(7) === Window(3, 3, region = true)) and
        (Window.region(12) === Window(3, 4, region = true)) and (Window.region(55) === Window(3, 15, region = true)) and
        (Window.region(100) === Window(3, 15, region = true))
    }
  }

  "the valid values" should {
    val r = ratedResult(Window.main, 10, 9, 8, 8, 8, 7, 6, 6)

    "be the cuts between two different scores and all candidates, never inside a tie nor 0" in {
      (r.validUpTo(100) === Seq(1, 4, 5, 7)) and (r.isValid(0) must beFalse) and (r.isValid(2) must beFalse) and
        (r.isValid(3) must beFalse) and (r.isValid(6) must beFalse) and (r.isValid(8) must beFalse)
    }

    "never list a candidate inside a tie" in {
      r.candidates.map(_.n).forall(r.isValid) must beTrue
    }

    "refuse an N inside a tie with both boundaries, an N past all candidates, and any N of none" in {
      (r.refusal(3) must beSome(Msg(InTieKey, Seq(3, 2, 4, 1, 4)))) and
        (r.refusal(6) must beSome(Msg(InTieKey, Seq(6, 6, 7, 5, 7)))) and
        (r.refusal(4) must beNone) and (r.refusal(7) must beNone) and
        (r.refusal(8) must beSome(Msg(AboveAllKey, Seq(8, 7)))) and
        (compute(IndexedSeq.empty, IndexedSeq.empty, Rated(10), Window.main).refusal(1) must
          beSome(Msg(NoCandidatesKey)))
    }

    "accept exactly the valid values: the save's check refuses every other N (offered == allowed)" in {
      val shapes = Seq(r, ratedResult(Window.main, 10, 8, 8, 8, 7), ratedResult(Window.main, 10, 5, 5),
        ratedResult(Window.main, 10, 9, 8, 7), levels(Window.main, (3, 0), (3, 0), (2, 1), (2, 1), (1, 2)))
      foreach(shapes) { s =>
        foreach(-1 to s.m + 2)(n => s.refusal(n).isEmpty must_=== s.isValid(n)) and
          (s.validUpTo(s.m) must_=== (-1 to s.m + 2).filter(n => s.refusal(n).isEmpty))
      }
    }

    "offer only the tie's end when the tie starts at place 1 (no N = 0)" in {
      val tied = ratedResult(Window.main, 10, 8, 8, 8, 7)
      (tied.refusal(2) must beSome(Msg(InTieUpperKey, Seq(2, 1, 3, 3)))) and (tied.validUpTo(10) === Seq(3, 4)) and
        (tied.tieAt(1) must beSome(Tie(1, 3)))
    }

    "tie equal scores, whatever their sums (the score alone is the key)" in {
      val r = ratedResult(Window.main, 10, 7, 7, 6)
      (r.isValid(1) must beFalse) and (r.refusal(1) must beSome(Msg(InTieUpperKey, Seq(1, 1, 2, 2)))) and
        (r.validUpTo(10) === Seq(2, 3))
    }
  }

  "C1, a significant drop" should {
    "list the drops of 5% of the range and twice the median gap, the largest marked, with both scores" in {
      // gaps 0.1 except 8.7 → 6.0 (2.7) and 5.8 → 5.0 (0.8): both drops, the first the largest
      val r = ratedResult(Window.main, 10, 9, 8.9, 8.8, 8.7, 6.0, 5.9, 5.8, 5.0, 4.9, 4.8)
      (ns(r) === Seq(4, 7, 10)) and
        (r.candidate(4).get.reasons === Seq(Msg(LargestDropKey, Seq("8.7", "6")))) and
        (r.candidate(7).get.reasons === Seq(Msg(DropKey, Seq("5.8", "5"))))
    }

    "not list a gap below 5% of the range" in {
      val r = ratedResult(Window.main, 100, 9, 8.9, 8.8, 7.0, 6.9, 6.8, 6.7, 6.6, 6.5, 6.4, 6.3)
      (keysOf(r, 3) must not(contain(DropKey))) and (keysOf(r, 3) must not(contain(LargestDropKey)))
    }

    "keep all candidates and the largest drops, at most six, by N" in {
      // ten blocks of three files, 0.1 apart inside a block, about 9.8 between blocks
      val scores = (0 until 10).flatMap(k => Seq(0.0, 0.1, 0.2).map(d => 100.0 - 10 * k - d))
      val r = ratedResult(Window.main, 100, scores: _*)
      (r.candidates.size === MaxCandidates) and (ns(r) === ns(r).sorted) and (ns(r) must contain(30))
    }
  }

  "C3, all candidates" should {
    "be listed for a main round when all candidates fit in the window" in {
      // 6 files, window [1, 100]: N = 6 is all; the drop before the last file is one, at N = 5
      val r = ratedResult(Window.main, 10, 9, 8.9, 8.8, 8.7, 8.6, 2)
      (keysOf(r, 6) === Seq(AllKey)) and (r.candidate(6).get.reasons.head.args === Seq(6)) and
        (keysOf(r, 5) === Seq(LargestDropKey))
    }

    "be merged with another reason of the same N, in the order C1 to C5" in {
      // a region of 5: window [3, 3], the tie 3–5 ends at the last file
      val r = ratedResult(Window.region(5), 10, 9, 8, 7, 7, 7)
      (keysOf(r, 5) === Seq(AllKey, TieIncludedKey)) and (ns(r) === Seq(2, 5))
    }

    "not be listed when the candidates go past the window" in {
      val r = ratedResult(Window.main, 1000, (1 to 120).map(i => 1000.0 - i * 3): _*)
      ns(r) must not(contain(120))
    }

    "be listed for a region of up to 25 candidates, past its window" in {
      val r = ratedResult(Window.region(12), 10, (1 to 12).map(i => 10.0 - i * 0.2): _*)
      (r.window.hi === 4) and (keysOf(r, 12) must contain(AllKey))
    }

    "be the only value of a region of one file" in {
      val r = ratedResult(Window.region(1), 10, 7.5)
      (ns(r) === Seq(1)) and (keysOf(r, 1) === Seq(AllKey)) and (r.validUpTo(10) === Seq(1))
    }
  }

  "C2, close to the best (regions)" should {
    "be the files within 5% of the range of the best, moved to the end of a tie" in {
      // range 10: within 0.5 of 9.0 are 9.0, 8.8, 8.6, 8.5 and the tie of 8.5 goes on to place 5
      val r = ratedResult(Window.region(30), 10, Seq(9.0, 8.8, 8.6, 8.5, 8.5) ++ (1 to 25).map(i => 8.0 - i * 0.2): _*)
      r.candidate(5).toSeq.flatMap(_.reasons) must contain(Msg(CloseToBestKey, Seq(5, "0.5", "9")))
    }

    "not apply to a main round" in {
      val r = ratedResult(Window.main, 10, Seq(9.0, 8.8, 8.6) ++ (1 to 25).map(i => 8.0 - i * 0.2): _*)
      r.candidates.flatMap(_.reasons).map(_.key) must not(contain(CloseToBestKey))
    }
  }

  "no clear drop" should {
    "list no cut but all candidates: the admin picks from the list (no C4)" in {
      val r = ratedResult(Window.main, 100, (1 to 30).map(i => 90.0 - i): _*)
      (ns(r) === Seq(30)) and (keysOf(r, 30) === Seq(AllKey)) and (r.validUpTo(100) === (1 to 30))
    }
  }

  "C5, a tie at the window's edge" should {
    "list both boundaries of a tie across hi, even outside the window" in {
      // window [4, 5]; places 4–7 tie at 6.8
      val r = ratedResult(Window(4, 5), 10, 9, 8.5, 8, 6.8, 6.8, 6.8, 6.8, 6, 5.5, 5)
      (r.window === Window(4, 5)) and
        (r.candidate(3).get.reasons must contain(Msg(TieBeforeKey, Seq("6.8", 4, 7)))) and
        (r.candidate(7).get.reasons must contain(Msg(TieIncludedKey, Seq("6.8", 4, 7))))
    }

    "list both boundaries of a tie over the whole window (lo = hi = 3, s3 = s4)" in {
      val r = ratedResult(Window(3, 3, region = true), 10, 9, 8, 7, 7, 6)
      (ns(r) must contain(allOf(2, 4))) and (r.isValid(3) must beFalse)
    }

    "not offer the lower boundary 0 when places 1 to hi all tie" in {
      val r = ratedResult(Window(3, 3), 10, 7, 7, 7, 7, 7, 6)
      (ns(r) must not(contain(0))) and (keysOf(r, 5) must contain(TieIncludedKey))
    }
  }

  "yes/no levels" should {
    val files = Seq.fill(3)((3, 0)) ++ Seq.fill(4)((3, 1)) ++ Seq.fill(2)((2, 0)) ++ Seq.fill(5)((2, 1)) ++ Seq((1, 2))

    "list every level boundary in the window, naming the level and the next one with its files" in {
      val r = levels(Window.main, files: _*)
      (ns(r) === Seq(3, 7, 9, 14, 15)) and
        // places 1–3 tie at the window's start (place 1): its end is a candidate for that too
        (r.candidate(3).get.reasons === Seq(Msg(LevelKey, Seq(describe(Seq(3.0, 0.0)), 4, describe(Seq(3.0, 1.0)))),
          Msg(TieIncludedKey, Seq(describe(Seq(3.0, 0.0)), 1, 3)))) and
        (keysOf(r, 15) === Seq(AllKey))
    }

    "not use the drop or the closeness tests" in {
      val r = levels(Window.region(15), files: _*)
      r.candidates.flatMap(_.reasons).map(_.key).toSet must not(contain(anyOf(DropKey, LargestDropKey, CloseToBestKey)))
    }

    "keep the six boundaries nearest the window's middle when more are in the window" in {
      val many = (0 until 12).flatMap(level => Seq.fill(3)((20 - level, 0)))
      val r = levels(Window(15, 22), many: _*)
      // window [15, 22]: boundaries 15, 18, 21, and 24 ending the tie 22–24 at its end; a main round's
      // window: all candidates and the boundaries nearest the middle
      (ns(r) === Seq(15, 18, 21, 24)) and
        (levels(Window.main, many: _*).candidates.size === MaxCandidates)
    }

    "refuse an N inside a level" in {
      levels(Window.main, files: _*).refusal(5) must beSome(Msg(InTieKey, Seq(5, 4, 7, 3, 7)))
    }

    "name the tie's level for a tie across the window's edge" in {
      val r = levels(Window(4, 6), files: _*)
      r.candidate(7).toSeq.flatMap(_.reasons) must contain(Msg(TieIncludedKey, Seq(describe(Seq(3.0, 1.0)), 4, 7)))
    }
  }

  "a region" should {
    "never list a candidate above 25" in {
      val r = ratedResult(Window.region(80), 10, (1 to 80).map(i => if (i <= 30) 9.0 else 9.0 - i * 0.01): _*)
      ns(r).forall(_ <= RegionMax) must beTrue
    }
  }

  "the gallery" should {
    "end at the window's end plus five, at most at the last candidate" in {
      (ratedResult(Window(8, 12), 10, (1 to 40).map(i => 10.0 - i * 0.1): _*).galleryEnd === 17) and
        (ratedResult(Window.main, 10, (1 to 40).map(i => 10.0 - i * 0.1): _*).galleryEnd === 40)
    }
  }

  "a few candidates" should {
    "give N = m only, for two tied files" in {
      val r = ratedResult(Window.main, 10, 7, 7)
      (r.validUpTo(10) === Seq(2)) and (ns(r) must contain(2))
    }
  }

  "the reasons' scores" should {
    "be written with at most two decimals and a point" in {
      (fmt(8.4) === "8.4") and (fmt(7.0) === "7") and (fmt(6.666666) === "6.67") and (fmt(0.0) === "0")
    }
  }
}
