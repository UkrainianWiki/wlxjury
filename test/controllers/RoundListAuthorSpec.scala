package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.test.FakeRequest

/** A round list's author filter ([[RoundList.author]], `author=`; the round filters proposal v4,
  * PR-4): only the files whose stored author is one value exactly, in the viewer's list, carried by
  * every link of the list, for those who may see authors only; and its statements, whose values are
  * all bound: the author here has a quote and a double quote.
  */
class RoundListAuthorSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** An author that would break SQL built as text. */
  private val A = "O'Brien \"X\""
  private val M1 = "07-101-0001"

  private case class Fixture(contest: ContestJury, round: Round, juror: User, juror2: User)

  /** A yes/no round. Authors: 1, 2, 3, 6 and 7 by [[A]]; 4 by A in lower case; 5 by "Other"; 8 by
    * nobody; 9 by A with a trailing space. Monuments: 1, 2, 4, 5, 7 [[M1]]; 3 07-101-0002; 6, 8, 9
    * none. The juror's votes: 1 selected, 2 unrated, 3 rejected, 4, 6, 8 and 9 unrated, 5 selected.
    * The second juror's: 2 selected, 7 unrated. A comment on 2 in the contest.
    */
  private def fixture(specialNomination: Option[String] = None): Fixture = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = Round.binaryRound, active = true,
      specialNomination = specialNomination))
    def juror(name: String) = userDao.create(User(name, s"$name@example.com", None, Set("jury"), contestId = contest.id))
    val (j1, j2) = (juror("juror1"), juror("juror2"))
    val authors = Map(1L -> A, 2L -> A, 3L -> A, 6L -> A, 7L -> A, 4L -> "o'brien \"x\"", 5L -> "Other", 9L -> (A + " "))
    val monuments = Map(1L -> M1, 2L -> M1, 4L -> M1, 5L -> M1, 7L -> M1, 3L -> "07-101-0002")
    val images = (1L to 9L).map(id =>
      id -> Image(id, s"File:Image$id.jpg", None, None, 640, 480, monuments.get(id), author = authors.get(id))).toMap
    imageDao.batchInsert(images.values.toSeq.sortBy(_.pageId))
    def votes(user: User, rates: (Long, Int)*) =
      selectionDao.batchInsert(rates.map { case (pageId, rate) => Vote(images(pageId), user, round, rate) })
    votes(j1, 1L -> 1, 2L -> 0, 3L -> -1, 4L -> 0, 5L -> 1, 6L -> 0, 8L -> 0, 9L -> 0)
    votes(j2, 2L -> 1, 7L -> 0)
    CommentJdbc.create(j1.getId, "juror1", round.getId, contest.id, 2L, "a comment")
    Fixture(contest, round, j1, j2)
  }

  private def ids(files: Seq[ImageWithRating]): Seq[Long] = files.map(_.pageId)

  private def juror(f: Fixture, filter: VoteFilter = VoteFilter.All, order: ListOrder = ListOrder.FileOrder) =
    RoundList(f.round, f.juror.getId, filter = filter, order = order)

  "a juror's list of one author" should {

    "list only their files by the author exactly (case and a trailing space count), with tabs and regions within it" in {
      val f = fixture()
      val list = juror(f).withAuthor(Some(A))
      // file order: no monument id first
      ids(list.files(Pager(1, 0))) === Seq(6L, 1L, 2L, 3L)
      list.count() === 4
      val counts = list.jurorCounts().get
      counts.rates === Map(1 -> 1, 0 -> 2, -1 -> 1)
      list.tabCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 2, -1 -> 1))
      counts.total(VoteFilter.Unrated, None) === 2
      counts.regions(VoteFilter.All) === Map("07" -> 3)
      ids(list.withFilter(VoteFilter.Unrated).files(Pager(1, 0))) === Seq(6L, 2L)
      // never the other juror's file by the author (7), nor the case or space variants (4, 9)
      ids(list.files(Pager(1, 0))) must not(contain(anyOf(7L, 4L, 9L)))
      juror(f).withAuthor(Some("o'brien \"x\"")).count() === 1
      juror(f).withAuthor(Some(A + " ")).count() === 1
      juror(f).withAuthor(Some("O'Brien")).count() === 0
    }

    "place a file within it, keyed, and none outside it" in {
      val f = fixture()
      val list = juror(f).withAuthor(Some(A))
      list.query.keyed must beTrue
      list.position(6L) must beSome(0)
      list.position(3L) must beSome(3)
      list.position(4L) must beNone
      list.around(5L) must beNone
      val hood = list.around(2L).get
      hood.file.pageId === 2L
      ids(hood.files) === Seq(6L, 1L, 2L, 3L)
      hood.next.map(_.pageId) must beSome(3L)
      // the author's last file goes on to the one before it
      list.around(3L).flatMap(_.next).map(_.pageId) must beSome(2L)
      // a vote that takes a file off the selected tab of the author: out of the list
      list.withFilter(VoteFilter.Rate(1)).voteMoved(1L, 0, -1) must beSome(MovedVote(1L, out = true, -1))
    }

    "combine with the monument and comments filters, each a hard condition" in {
      val f = fixture()
      val list = juror(f).withAuthor(Some(A))
      ids(list.withMonument(Some(M1)).files(Pager(1, 0))) === Seq(1L, 2L)
      list.withMonument(Some(M1)).jurorCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 1))
      ids(list.withComments(true).files(Pager(1, 0))) === Seq(2L)
      list.withComments(true).jurorCounts().map(_.total(VoteFilter.All, None)) must beSome(1)
      list.withMonument(Some("07-101-0002")).withComments(true).count() === 0
      // the monument line of a file counts within the author filter too
      val file = list.files(Pager(1, 0)).find(_.pageId == 1L).get
      list.monumentLine(file).map(_.count) must beSome(2)
    }

    "be read from the votes, never round_image's counts" in {
      val f = fixture()
      RoundList(f.round, 0, countsRead = true).withAuthor(Some(A)).countsQuery must beNone
      RoundList(f.round, 0, countsRead = true).countsQuery must beSome
    }

    "give a juror's tabs within it in a round of sub-regions (Віа Регіа), which has no juror counts" in {
      val f = fixture(Some("Віа Регіа"))
      val list = juror(f).withAuthor(Some(A))
      list.jurorCounts() must beNone
      list.tabCounts().map(_.rates) must beSome(Map(1 -> 1, 0 -> 2, -1 -> 1))
      juror(f).tabCounts() must beNone
    }
  }

  "all the jurors' list of one author" should {

    "list every juror's files by the author, counted and ranked" in {
      val f = fixture()
      val list = RoundList(f.round, 0).withAuthor(Some(A))
      ids(list.withOrder(ListOrder.FileOrder).files(Pager(1, 0))) === Seq(6L, 1L, 2L, 7L, 3L)
      list.count() === 5
      list.rankCounts().map(_._2).sum === 5
      list.withFilter(VoteFilter.Unrated).count() === 3 // 2 (the first juror's), 6, 7
      ids(list.withView(GalleryView.FileList).files(Pager(1, 0))).sorted === Seq(1L, 2L, 3L, 6L, 7L)
    }
  }

  "the statements of the author filter" should {

    "bind the author: byRegionStat, regionCounts and regionRateCounts, a juror's and all the jurors'" in {
      val f = fixture()
      val ofJuror = juror(f).withAuthor(Some(A)).query
      val ofAll = RoundList(f.round, 0).withAuthor(Some(A)).query
      ofJuror.byRegionStat() === Map("07" -> None)
      ofAll.byRegionStat() === Map("07" -> None)
      ofJuror.regionCounts() === Map("07" -> 3)
      ofJuror.copy(rate = Some(0)).regionCounts() === Map("07" -> 1)
      ofAll.regionCounts() === Map("07" -> 4)
      ofAll.copy(rate = Some(1)).regionCounts() === Map("07" -> 2) // 1, and 2 by the second juror
      ofJuror.regionRateCounts() ===
        Map((Some("07"), 1) -> 1, (Some("07"), 0) -> 1, (Some("07"), -1) -> 1, (None, 0) -> 1)
      // with the comments filter's join and the monument's condition too (all bound)
      ofJuror.copy(commentsIn = Some(f.round.contestId)).regionRateCounts().values.sum === 1
      ofJuror.copy(monumentId = Some(M1)).regionRateCounts() === Map((Some("07"), 1) -> 1, (Some("07"), 0) -> 1)
      // the file list's votes of the page ids, without the filter's condition again
      ofAll.copy(groupWithDetails = true, grouped = false).withVotes(Seq(2L)).flatMap(_.votes).map(_.juryId).sorted ===
        Seq(f.juror.getId, f.juror2.getId).sorted
    }

    "count without the filter as before" in {
      val f = fixture()
      juror(f).query.regionRateCounts().values.sum === 8
      juror(f).query.regionCounts() === Map("07" -> 5)
    }

    "match as the contest files page's author filter does" in {
      val f = fixture()
      _root_.scalikejdbc.DB.localTx { implicit s =>
        val categoryId = _root_.scalikejdbc.SQL("INSERT INTO category (title) VALUES ('Category:Authors')").updateAndReturnGeneratedKey.apply()
        (1L to 9L).foreach(id => _root_.scalikejdbc.SQL("INSERT INTO category_members (category_id, page_id) VALUES (?, ?)")
          .bind(categoryId, id).update.apply())
        Seq(A, A + " ", "o'brien \"x\"", "O'Brien").map(a => ImageJdbc.categoryAuthorFiles(categoryId, a)) ===
          Seq(Seq(1L, 2L, 3L, 6L, 7L), Seq(9L), Seq(4L), Nil)
      }
      // the same files, all the jurors' list of the round
      ids(RoundList(f.round, 0, order = ListOrder.FileOrder).withAuthor(Some(A)).files(Pager(1, 0))).sorted ===
        Seq(1L, 2L, 3L, 6L, 7L)
    }
  }

  "the author filter's parameter" should {

    val round = Round(id = Some(5), number = 1, contestId = 1, active = true, rates = Round.binaryRound)
    val list = RoundList(round, 7, region = Some("07"), filter = VoteFilter.Unrated, order = ListOrder.FileOrder)
      .withAuthor(Some(A))
    val encoded = "author=O%27Brien+%22X%22"

    "be kept by every URL of the list, the region too" in {
      list.region must beSome("07")
      Seq(list.pageUrl(2), list.atUrl(3), list.largeUrl(3), list.voteUrl(3, 1), list.regionsUrl, list.commentUrl(3),
        list.withFilter(VoteFilter.All).pageUrl(1), list.withOrder(ListOrder.LatestVote).pageUrl(1),
        list.withView(GalleryView.FileList).pageUrl(1), list.withViewer(0).pageUrl(1),
        list.withMonument(Some(M1)).pageUrl(1), list.withComments(true).recentComments.pageUrl(1)
      ).map(_.contains(encoded)) === Seq.fill(12)(true)
      list.voteQuery() === s"?filter=unrated&$encoded&sort=file"
      list.pageUrl(1) === s"/gallery/round/5/user/7/region/07/page/1?filter=unrated&$encoded&sort=file"
      list.withMonument(Some(M1)).pageUrl(1) ===
        s"/gallery/round/5/user/7/page/1?filter=unrated&monument=$M1&$encoded&sort=file"
      list.inRound(round.copy(id = Some(6)), 7, jurorOfRound = true).pageUrl(1) must contain(encoded)
      // the chip's ×: all authors
      list.withAuthor(None).pageUrl(1) === "/gallery/round/5/user/7/region/07/page/1?filter=unrated&sort=file"
    }

    "be the value as stored, ignored blank or longer than 255 characters" in {
      val plain = RoundList(round, 7)
      plain.withAuthor(Some(" Ivan ")).author must beSome(" Ivan ")
      plain.withAuthor(Some("   ")).author must beNone
      plain.withAuthor(Some("")).author must beNone
      plain.withAuthor(Some("x" * 255)).author must beSome("x" * 255)
      plain.withAuthor(Some("x" * 256)).author must beNone
      plain.withAuthor(None).author must beNone
    }

    "be read from a request only for those who may see the contest's authors, silently dropped for anyone else" in {
      def user(role: String, contest: Option[Long]) =
        User(role, s"$role@example.com", id = Some(7), roles = Set(role), contestId = contest)
      def of(viewer: User, query: String = s"?$encoded", asUserId: Long = 7) =
        RoundList.of(round, viewer, asUserId, "all", GalleryView.Gallery)(FakeRequest("GET", "/x" + query))
      of(user("organizer", Some(1))).author must beSome(A)
      of(user("admin", Some(1))).author must beSome(A)
      of(user("root", None)).author must beSome(A)
      of(user("organizer", Some(1)), asUserId = 0).author must beSome(A)
      // a juror, another contest's organizer: none, and nothing of it in any link
      of(user("jury", Some(1))).author must beNone
      of(user("jury", Some(1))).pageUrl(1) must not(contain("author"))
      of(user("jury", Some(1))).voteQuery() must not(contain("author"))
      of(user("organizer", Some(2))).author must beNone
      of(user("admin", Some(2))).author must beNone
      // blank or too long: none for staff too
      of(user("organizer", Some(1)), "?author=%20%20").author must beNone
      of(user("organizer", Some(1)), "?author=" + "x" * 256).author must beNone
      // a juror's own list and the large view's `module` (the comments report too)
      RoundList.of(round, user("jury", Some(1)), 7, "all", GalleryView.Comments)(
        FakeRequest("GET", s"/x?$encoded")).author must beNone
    }
  }
}
