package controllers

import org.specs2.mutable.Specification

class PagerSpec extends Specification {

  "pager" should {

    "show the items of the page, the last one part full" in {
      Pager(page = 1, count = 5712).first === 1
      Pager(page = 1, count = 5712).last === 15
      Pager(page = 4, count = 5712).first === 46
      Pager(page = 4, count = 5712).last === 60
      Pager(page = 381, count = 5712).first === 5701
      Pager(page = 381, count = 5712).last === 5712
      Pager(page = 1, count = 0).first === 0
      Pager(page = 1, count = 0).last === 0
    }

    "link the previous and the next page only where there is one" in {
      Pager(page = 1, count = 40).hasPrev must beFalse
      Pager(page = 1, count = 40).hasNext must beTrue
      Pager(page = 3, count = 40).hasPrev must beTrue
      Pager(page = 3, count = 40).hasNext must beFalse
      Pager(page = 1, count = 15).display must beFalse
      Pager(page = 0, count = 40).display must beFalse
    }

    "count its pages, a last one part full" in {
      Pager(page = 1, count = 0).pages === 0
      Pager(page = 1, count = 15).pages === 1
      Pager(page = 1, count = 16).pages === 2
    }

    "give a list's page URL with {page} in place of the number, for its page box" in {
      Pager.urlTemplate(p => s"/gallery/round/7/user/3/page/$p?sort=file") === "/gallery/round/7/user/3/page/{page}?sort=file"
      Pager.urlTemplate(p => s"/contest/7/files?page=$p&region=07") === "/contest/7/files?page={page}&region=07"
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
