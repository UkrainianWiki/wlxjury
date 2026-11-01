package org.intracer.wmua

import org.specs2.mutable.Specification

class RankSpec extends Specification {

  import ImageWithRating.rank

  "rank" should {

    "rank natural sequence" in {
      rank(Seq(3, 2, 1)) === Seq("1", "2", "3")
    }

    "rank sparse sequence" in {
      rank(Seq(10, 5, 1)) === Seq("1", "2", "3")
    }

    "rank ties" in {
      rank(Seq(10, 5, 5, 1)) === Seq("1", "2-3", "2-3", "4")
    }
  }

  "ranks counted per key" should {

    "rank a page among the whole list without reading the rest" in {
      // the list: 2 files of key 3, 4 of key 2, 1 of key 0, in its order
      val ranks = ImageWithRating.Ranks(Seq(3 -> 2, 2 -> 4, 0 -> 1))
      Seq(2, 2, 0).map(ranks(_)) === Seq("3-6", "3-6", "7")
      ranks(3) === "1-2"
      ranks(1) === "" // no file of it
    }

    "be the ranks of the whole list" in {
      val keys = Seq(Seq(2, 0), Seq(1, 0), Seq(1, 0), Seq(1, 1))
      keys.map(ImageWithRating.Ranks.of(keys)(_)) === Seq("1", "2-3", "2-3", "4")
    }
  }
}
