package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.test.FakeRequest

/** A round list's monument filter ([[RoundList.monument]], `monument=`; the round filters
  * proposal v4, PR-2): only the files of one monument id exactly, in the viewer's list, carried by
  * every link of the list; and its statements, whose values are all bound: the monument id here has
  * a quote and a double quote.
  */
class RoundListMonumentSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** A monument id that would break SQL built as text. */
  private val M = "07-101-0'0\"1"

  private case class Fixture(round: Round, juror: User, juror2: User)

  /** A yes/no round. The juror's files: 1 selected, 2 unrated, 3 rejected and 6 unrated of
    * monument M; 4 unrated of 07-101-0002; 5 selected of 14-101-0001; 8 selected and 9 unrated of
    * 07-101-0003; 10 unrated without a monument id. The second juror's: 2 selected, 3 unrated and
    * 7 unrated of M.
    */
  private def fixture(specialNomination: Option[String] = None): Fixture = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = Round.binaryRound, active = true,
      specialNomination = specialNomination))
    def juror(name: String) = userDao.create(User(name, s"$name@example.com", None, Set("jury"), contestId = contest.id))
    val (j1, j2) = (juror("juror1"), juror("juror2"))
    val monuments = Map(1L -> M, 2L -> M, 3L -> M, 6L -> M, 7L -> M, 4L -> "07-101-0002", 5L -> "14-101-0001",
      8L -> "07-101-0003", 9L -> "07-101-0003")
    val images = (1L to 10L).map(id => id -> Image(id, s"File:Image$id.jpg", None, None, 640, 480, monuments.get(id))).toMap
    imageDao.batchInsert(images.values.toSeq.sortBy(_.pageId))
    // each vote with its file's monument id, as the distribution writes it (vote.monument_id, V48)
    def votes(user: User, rates: (Long, Int)*) =
      selectionDao.batchInsert(rates.map { case (pageId, rate) => Vote(images(pageId), user, round, rate) })
    votes(j1, 1L -> 1, 2L -> 0, 3L -> -1, 4L -> 0, 5L -> 1, 6L -> 0, 8L -> 1, 9L -> 0, 10L -> 0)
    votes(j2, 2L -> 1, 3L -> 0, 7L -> 0)
    Fixture(round, j1, j2)
  }

  private def ids(files: Seq[ImageWithRating]): Seq[Long] = files.map(_.pageId)

  private def juror(f: Fixture, filter: VoteFilter = VoteFilter.All, order: ListOrder = ListOrder.FileOrder) =
    RoundList(f.round, f.juror.getId, filter = filter, order = order)

  "a juror's list of one monument" should {

    "list only their files of it, counted, with their tabs and region counts within it" in {
      val f = fixture()
      val list = juror(f).withMonument(Some(M))
      ids(list.files(Pager(1, 0))) === Seq(1L, 2L, 3L, 6L)
      list.count() === 4
      val counts = list.jurorCounts().get
      counts.rates === Map(1 -> 1, 0 -> 2, -1 -> 1)
      counts.total(VoteFilter.Unrated, None) === 2
      counts.regions(VoteFilter.All) === Map("07" -> 4)
      ids(list.withFilter(VoteFilter.Unrated).files(Pager(1, 0))) === Seq(2L, 6L)
      // never the other juror's file of the monument (7)
      ids(list.withFilter(VoteFilter.Unrated).files(Pager(1, 0))) must not contain 7L
    }

    "place a file within it, keyed, and none outside it" in {
      val f = fixture()
      val list = juror(f).withMonument(Some(M))
      list.query.keyed must beTrue
      list.position(6L) must beSome(3)
      list.position(4L) must beNone
      list.around(4L) must beNone
      val hood = list.around(6L).get
      ids(hood.files) === Seq(2L, 3L, 6L)
      // the monument's last file goes on to the one before it
      hood.next.map(_.pageId) must beSome(3L)
      // a vote that takes a file off the selected tab of the monument: out of the list
      list.withFilter(VoteFilter.Rate(1)).voteMoved(1L, 0, -1) must beSome(MovedVote(1L, out = true, -1))
    }

    "be read from the votes, never round_image's counts" in {
      val f = fixture()
      RoundList(f.round, 0, countsRead = true).withMonument(Some(M)).countsQuery must beNone
    }
  }

  "all the jurors' list of one monument" should {

    "list every juror's files of it, counted and ranked" in {
      val f = fixture()
      val list = RoundList(f.round, 0).withMonument(Some(M))
      ids(list.withOrder(ListOrder.FileOrder).files(Pager(1, 0))) === Seq(1L, 2L, 3L, 6L, 7L)
      list.count() === 5
      list.rankCounts().map(_._2).sum === 5
      list.withFilter(VoteFilter.Unrated).count() === 4 // 2, 3 (the second juror's), 6, 7
    }
  }

  "the statements of the monument filter" should {

    "bind the monument id: byRegionStat, regionCounts and regionRateCounts, a juror's and all the jurors'" in {
      val f = fixture()
      val ofJuror = juror(f).withMonument(Some(M)).query
      val ofAll = RoundList(f.round, 0).withMonument(Some(M)).query
      ofJuror.byRegionStat() === Map("07" -> None)
      ofAll.byRegionStat() === Map("07" -> None)
      ofJuror.regionCounts() === Map("07" -> 4)
      ofJuror.copy(rate = Some(0)).regionCounts() === Map("07" -> 2)
      ofAll.regionCounts() === Map("07" -> 5)
      ofAll.copy(rate = Some(1)).regionCounts() === Map("07" -> 2) // 1, and 2 by the second juror
      ofJuror.regionRateCounts() === Map((Some("07"), 1) -> 1, (Some("07"), 0) -> 2, (Some("07"), -1) -> 1)
      // with the comments filter's join too (its values bound as well)
      ofJuror.copy(commentsIn = Some(f.round.contestId), commentsRound = f.round.id, commentsBy = Some(1L))
        .regionRateCounts().values.sum === 0
      // a prefix or another case is another monument
      juror(f).withMonument(Some("07-101-0'0")).count() === 0
      juror(f).withMonument(Some("07-101-0'0\"1")).count() === 4
    }

    "count without the filter as before" in {
      val f = fixture()
      val q = juror(f).query
      q.regionRateCounts().values.sum === 9
      q.regionCounts() === Map("07" -> 7, "14" -> 1)
      RoundList(f.round, 0).query.byRegionStat().keySet === Set("07", "14")
    }

    "give a juror's tabs within it in a round of sub-regions (Віа Регіа), which has no juror counts" in {
      val f = fixture(Some("Віа Регіа"))
      val list = juror(f).withMonument(Some(M))
      list.jurorCounts() must beNone
      list.tabCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 2, -1 -> 1))
      juror(f).tabCounts() must beNone // no filter: all the juror's files, as before
    }
  }

  "the large view's monument line" should {

    "link the list with the monument while it has another file, else no link (the card leaves the gallery out)" in {
      val f = fixture()
      def file(id: Long) = juror(f).files(Pager(1, 0)).find(_.pageId == id).get
      val all = juror(f)
      all.monumentLine(file(1L)) must beSome(MonumentLine(M, 4, Some(all.withMonument(Some(M)).pageUrl(1))))
      // the only file of its monument, in the list
      all.monumentLine(file(4L)) must beSome(MonumentLine("07-101-0002", 1, None))
      // on the unrated tab: 8 (selected, off the tab) and 9 (unrated) of 07-101-0003
      val unrated = juror(f, VoteFilter.Unrated)
      unrated.monumentLine(file(8L)).map(l => (l.count, l.url.isDefined)) must beSome((1, true))
      unrated.monumentLine(file(9L)) must beSome(MonumentLine("07-101-0003", 1, None))
      // none of the monument's files on the tab: no line
      unrated.monumentLine(file(5L)) must beNone
      // none without a monument id, nor while the list already has a monument
      all.monumentLine(file(10L)) must beNone
      all.withMonument(Some(M)).monumentLine(file(1L)) must beNone
    }

    "keep the list's viewer, view, tab and order in its link" in {
      val f = fixture()
      val list = RoundList(f.round, f.juror.getId, filter = VoteFilter.Unrated, order = ListOrder.LatestVote,
        view = GalleryView.ByRate, comments = true)
      list.withMonument(Some(M)).pageUrl(1) ===
        s"/byrate/round/${f.round.getId}/user/${f.juror.getId}/page/1?filter=unrated&comments=1&monument=07-101-0%270%221&sort=latest-vote"
    }
  }

  "the monument filter's parameter" should {

    val round = Round(id = Some(5), number = 1, contestId = 1, active = true, rates = Round.binaryRound)
    val list = RoundList(round, 7, region = Some("07"), filter = VoteFilter.Unrated, order = ListOrder.FileOrder)
      .withMonument(Some(M))
    val encoded = "monument=07-101-0%270%221"

    "be kept by every URL of the list, in all regions" in {
      list.region must beNone
      Seq(list.pageUrl(2), list.atUrl(3), list.largeUrl(3), list.voteUrl(3, 1), list.regionsUrl, list.commentUrl(3),
        list.withFilter(VoteFilter.All).pageUrl(1), list.withOrder(ListOrder.LatestVote).pageUrl(1),
        list.withView(GalleryView.FileList).pageUrl(1), list.withViewer(0).pageUrl(1)).map(_.contains(encoded)) ===
        Seq.fill(10)(true)
      list.voteQuery() === s"?filter=unrated&$encoded&sort=file"
      list.pageUrl(1) === s"/gallery/round/5/user/7/page/1?filter=unrated&$encoded&sort=file"
      // the same monument in another round
      list.inRound(round.copy(id = Some(6)), 7, jurorOfRound = true).pageUrl(1) must contain(encoded)
      // the chip's ×: all monuments, the region all of them
      list.withMonument(None).pageUrl(1) === "/gallery/round/5/user/7/page/1?filter=unrated&sort=file"
    }

    "be trimmed, and ignored blank or longer than 255 characters" in {
      val plain = RoundList(round, 7)
      plain.withMonument(Some("  07-101-0001 ")).monument must beSome("07-101-0001")
      plain.withMonument(Some("   ")).monument must beNone
      plain.withMonument(Some("x" * 255)).monument must beSome("x" * 255)
      plain.withMonument(Some("x" * 256)).monument must beNone
      plain.withMonument(None).monument must beNone
    }

    "be read from a request, dropping the region of its path" in {
      val viewer = User("j", "j@example.com", id = Some(7), roles = Set("jury"), contestId = Some(1))
      def of(query: String, region: String = "07") =
        RoundList.of(round, viewer, 7, region, GalleryView.Gallery)(FakeRequest("GET", "/x" + query))
      of(s"?$encoded").monument must beSome(M)
      of(s"?$encoded").region must beNone
      of("?monument=%2007-101-0001%20").monument must beSome("07-101-0001")
      of("?monument=").monument must beNone
      of("?monument=").region must beSome("07")
      of("?monument=" + "x" * 256).monument must beNone
      of("?monument=" + "x" * 256).region must beSome("07")
    }
  }
}
