package controllers

import org.specs2.mutable.Specification

class PagerSpec extends Specification {

  "pager" should {

    "list 2 page numbers" in {
      val pager = Pager(page = 1, count = 2 * Pager.pageSize)

      pager.pageNumbers === (1 to 2)
    }

    "list 10 page numbers" in {
      val pager = Pager(page = 1, count = 10 * Pager.pageSize)

      pager.pageNumbers === (1 to 10)
    }

    "list 100 page numbers, page 1" in {
      val pager = Pager(page = 1, count = 100 * Pager.pageSize)

      pager.pageNumbers === (1 to 9) ++ (1 to 10).map(_ * 10)
    }

    "list 100 page numbers, page 11" in {
      val pager = Pager(page = 11, count = 100 * Pager.pageSize)

      pager.pageNumbers === Seq(1) ++ (10 to 19) ++ (2 to 10).map(_ * 10)
    }

    "list 15 page numbers, page 15" in {
      val pager = Pager(page = 15, count = 15 * Pager.pageSize)

      pager.pageNumbers === Seq(1) ++ (10 to 15)
    }

    "list 95 page numbers, page 90" in {
      val pager = Pager(page = 90, count = 95 * Pager.pageSize)

      pager.pageNumbers === Seq(1) ++ (1 to 9).map(_ * 10) ++ (91 to 95)
    }

    "list 250 page numbers, page 1" in {
      val pager = Pager(page = 1, count = 250 * Pager.pageSize)

      pager.pageNumbers === (1 to 9) ++ (1 to 10).map(_ * 10) ++ Seq(200)
    }

    "list 250 page numbers, page 150" in {
      val pager = Pager(page = 150, count = 250 * Pager.pageSize)

      pager.pageNumbers === Seq(1) ++ (100 to 150 by 10) ++ (151 to 159) ++ (160 to 200 by 10)
    }

    "list 250 page numbers, page 245" in {
      val pager = Pager(page = 245, count = 250 * Pager.pageSize)

      pager.pageNumbers === Seq(1, 100) ++ (200 to 240 by 10) ++ (241 to 250)
    }

    "count its pages, a last one part full" in {
      Pager(page = 1, count = 0).pages === 0
      Pager(page = 1, count = 15).pages === 1
      Pager(page = 1, count = 16).pages === 2
    }

    "start a page at its first item" in {
      Pager(page = 1, count = 40).offset === 0
      Pager(page = 3, count = 40).offset === 30
      Pager(page = 0, count = 40).offset === 0
    }

    "put the 15th item on page 1 and the 16th on page 2" in {
      Pager.pageOf(0) === 1
      Pager.pageOf(14) === 1
      Pager.pageOf(15) === 2
    }
  }
}
