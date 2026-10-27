package controllers

import db.scalikejdbc.Round
import org.specs2.mutable.Specification
import play.api.test.FakeRequest

/** [[RoundList]]'s URLs and how a request names a list, without a database. */
class RoundListSpec extends Specification {

  private val round = Round(id = Some(7), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))

  private def parse(uri: String, view: GalleryView = GalleryView.Gallery, asUserId: Long = 3): RoundList =
    RoundList.of(round, asUserId, "all", view)(FakeRequest("GET", uri))

  "a list URL's filter" should {
    "be read from filter=" in {
      VoteFilter.of(FakeRequest("GET", "/?filter=unrated")) === VoteFilter.Unrated
      VoteFilter.of(FakeRequest("GET", "/?filter=rated")) === VoteFilter.Rated
      VoteFilter.of(FakeRequest("GET", "/?filter=selected")) === VoteFilter.Rate(1)
      VoteFilter.of(FakeRequest("GET", "/?filter=rejected")) === VoteFilter.Rate(-1)
      VoteFilter.of(FakeRequest("GET", "/?filter=4")) === VoteFilter.Rate(4)
      VoteFilter.of(FakeRequest("GET", "/?filter=all")) === VoteFilter.All
    }

    "be read from older URLs' rate= and rated=" in {
      VoteFilter.of(FakeRequest("GET", "/?rate=0")) === VoteFilter.Unrated
      VoteFilter.of(FakeRequest("GET", "/?rate=1")) === VoteFilter.Rate(1)
      VoteFilter.of(FakeRequest("GET", "/?rate=-1")) === VoteFilter.Rate(-1)
      VoteFilter.of(FakeRequest("GET", "/?rated=true")) === VoteFilter.Rated
      VoteFilter.of(FakeRequest("GET", "/?rated=false")) === VoteFilter.Unrated
    }

    "prefer filter= to rate=, and be all of the files without a valid one" in {
      VoteFilter.of(FakeRequest("GET", "/?filter=rated&rate=0")) === VoteFilter.Rated
      VoteFilter.of(FakeRequest("GET", "/?filter=nosuch")) === VoteFilter.All
      VoteFilter.of(FakeRequest("GET", "/")) === VoteFilter.All
    }

    "write each filter so it reads back" in {
      Seq(VoteFilter.All, VoteFilter.Unrated, VoteFilter.Rated, VoteFilter.Rate(1), VoteFilter.Rate(-1),
        VoteFilter.Rate(3)).map(f => VoteFilter.parse(f.param)) ===
        Seq(VoteFilter.All, VoteFilter.Unrated, VoteFilter.Rated, VoteFilter.Rate(1), VoteFilter.Rate(-1),
          VoteFilter.Rate(3)).map(Some(_))
    }
  }

  "a large view's way back" should {
    "be its module=, a gallery or by rate, else the gallery" in {
      GalleryView.of(FakeRequest("GET", "/?module=byrate")) === GalleryView.ByRate
      GalleryView.of(FakeRequest("GET", "/?module=gallery")) === GalleryView.Gallery
      GalleryView.of(FakeRequest("GET", "/?module=filelist")) === GalleryView.Gallery
      GalleryView.of(FakeRequest("GET", "/")) === GalleryView.Gallery
    }
  }

  "a list's URLs" should {
    val list = RoundList(round, 3, Some("80"), VoteFilter.Unrated, ListOrder.ByRate, GalleryView.ByRate)

    "keep its viewer, region, filter and order" in {
      list.pageUrl(2) === "/byrate/round/7/user/3/region/80/page/2?filter=unrated&sort=rate"
      list.atUrl(105) === "/byrate/round/7/user/3/region/80/pageid/105?filter=unrated&sort=rate#105"
    }

    "keep its view too in a large view's, its votes' and its comments'" in {
      list.largeUrl(105) === "/large/round/7/user/3/region/80/pageid/105?module=byrate&filter=unrated&sort=rate"
      list.voteUrl(105, 4) === "/large/round/7/region/80/pageid/105/select/4?module=byrate&filter=unrated&sort=rate"
      list.voteUrl(105, 4, Some(2)) ===
        "/large/round/7/region/80/pageid/105/select/4?module=byrate&filter=unrated&sort=rate&criteria=2"
      list.votePrefix(105) + 4 + list.voteQuery() === list.voteUrl(105, 4)
      list.commentUrl(105) === "/comment/region/80/pageid/105?round=7&module=byrate&filter=unrated&sort=rate"
    }

    "leave out the defaults: the gallery, all files and all regions" in {
      val gallery = RoundList(round, 3)
      gallery.pageUrl(1) === "/gallery/round/7/user/3/page/1?sort=rate"
      gallery.largeUrl(105) === "/large/round/7/user/3/pageid/105?sort=rate"
      gallery.atUrl(105) === "/gallery/round/7/user/3/region/all/pageid/105?sort=rate#105"
    }

    "be paths, never absolute URLs" in {
      Seq(list.pageUrl(1), list.atUrl(1), list.largeUrl(1), list.voteUrl(1, 1), list.commentUrl(1))
        .filterNot(u => u.startsWith("/") && !u.startsWith("//")) must beEmpty
    }

    "change one part at a time" in {
      list.withRegion("all").region must beNone
      list.withRegion("80").withFilter(VoteFilter.All).pageUrl(1) === "/byrate/round/7/user/3/region/80/page/1?sort=rate"
      list.withViewer(0).withView(GalleryView.FileList).pageUrl(1) ===
        "/filelist/round/7/user/0/region/80/page/1?filter=unrated&sort=rate"
      list.withView(GalleryView.Csv).pageUrl(1) must startWith("/query?module=csv&user=3")
    }
  }

  "a request's list" should {
    "be named by its path and query" in {
      val list = parse("/large/round/7/user/3/pageid/1?module=byrate&filter=rated&sort=rate", GalleryView.ByRate)
      list.filter === VoteFilter.Rated
      list.order === ListOrder.ByRate
      list.view === GalleryView.ByRate
      list.region must beNone
    }
  }
}
