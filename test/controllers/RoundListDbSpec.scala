package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** [[RoundList]]'s files, as a round's galleries list them. */
class RoundListDbSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def contestImage(id: Long) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def setUp(rates: Rates = Round.binaryRound): (ContestJury, Round, User) = {
    val contest = contestDao.create(None, "WLE", 2015, "Ukraine")
    val round = roundDao.create(
      Round(None, 1, contestId = contest.getId, rates = rates, active = true)
    )
    val user = userDao.create(
      User("fullname", "email", None, Set("jury"), contestId = contest.id)
    )
    (contest, round, user)
  }

  /** The list's first page; all of it for a file list. */
  private def files(round: Round, asUserId: Long, filter: VoteFilter = VoteFilter.All,
                    view: GalleryView = GalleryView.Gallery): Seq[ImageWithRating] =
    RoundList(round, asUserId, filter = filter, view = view).files(Pager(1, 0))

  private def createImages(number: Long, startId: Long = 0): Seq[Image] = {
    val images = (startId until number + startId).map(contestImage)
    imageDao.batchInsert(images)
    images
  }

  private def createSelection(
      images: Seq[Image],
      rate: Int,
      user: User,
      round: Round
  ): Seq[Selection] = {
    val selections = images.map(img => Selection(img, user, round, rate))
    selectionDao.batchInsert(selections)
    selections
  }

  "juror" should {
    "see assigned images in binary round" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(6)
      createSelection(images.slice(0, 3), 0, user, round)

      val result = files(round, user.getId)

      result.size === 3
      result.map(_.image) === images.slice(0, 3)
      result.map(_.selection.size) === Seq(1, 1, 1)
      result.map(_.rate) === Seq(0, 0, 0)
    }

    "see images filtered by rate in binary round" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(6)

      def range(rate: Int) = Seq(2 + rate * 2, 4 + rate * 2)
      def slice(rate: Int) = images.slice(range(rate).head, range(rate).last)

      for (rate <- -1 to 1) {
        createSelection(slice(rate), rate, user, round)
      }

      for (rate <- -1 to 1) yield {
        val result = files(round, user.getId, VoteFilter.ofRate(rate))

        result.size === 2
        result.map(_.image) === slice(rate)
        result.map(_.selection.size) === Seq(1, 1)
        result.map(_.rate) === Seq(rate, rate)
      }
    }

    "see images ordered by rate in binary round" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(6)

      def range(rate: Int) = Seq(2 + rate * 2, 4 + rate * 2)
      def slice(rate: Int) = images.slice(range(rate).head, range(rate).last)

      for (rate <- -1 to 1) {
        createSelection(slice(rate), rate, user, round)
      }

      val result = files(round, user.getId)

      result.size === 6
      result.map(_.image) === slice(1) ++ slice(0) ++ slice(-1)
      result.map(_.selection.size) === Seq.fill(6)(1)
      result.map(_.rate) === Seq(1, 1, 0, 0, -1, -1)
    }

    "see images ordered by rate in rated round" in {
      val (contest, round, user) = setUp(Round.ratesById(10))
      val images = createImages(6)

      val selections = images.zipWithIndex.map { case (image, rate) =>
        Selection(image, user, round, rate)
      }
      selectionDao.batchInsert(selections)

      val result = files(round, user.getId)

      result.size === 6
      result.map(_.image) === images.reverse
      result.map(_.selection.size) === Seq.fill(6)(1)
      result.map(_.rate) === (0 to 5).reverse
    }
  }

  "organizer" should {
    "see rating by selection" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(10)

      val jurors = Seq(user) ++ (1 to 3).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id))
      )

      def selectedBy(n: Int, image: Image, otherRate: Int = 0): Seq[Selection] = {
        jurors.slice(0, n).map(j => Selection(image, j, round, rate = 1)) ++
          jurors.slice(n, jurors.size).map(j => Selection(image, j, round, otherRate))
      }

      val selectedByX = (0 to 3).flatMap(x => selectedBy(x, images(x)))
      selectionDao.batchInsert(selectedByX)

      val result = files(round, 0)

      result.size === 4
      result.map(_.image) === Seq(images(3), images(2), images(1), images(0))
      result.map(_.selection.size) === Seq(1, 1, 1, 1)
      result.map(_.rate) === Seq(3, 2, 1, 0)
    }

    "see details by selection rejected not accounted" in {
      val (contest, round, _) = setUp(Round.binaryRound)
      val images = createImages(2)

      val jurors = (1 to 3).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id))
      )

      def rateN(imageIndex: Int, rates: Seq[Int]) =
        (0 until rates.size).zip(rates).map { case (j, r) =>
          Selection(images(imageIndex), jurors(j), round, r)
        }

      val selections =
        rateN(0, Seq(1, -1, -1)) ++
          rateN(1, Seq(0, 0, 0))
      selectionDao.batchInsert(selections)

      val result = files(round, 0, view = GalleryView.FileList)

      result.size === 2
      result.map(_.image) === Seq(images(0), images(1))
      result.map(_.selection.size) === Seq(3, 3)
      result.map(_.rate) === Seq(1, 0)
    }

    "see details by selection" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(10)

      val jurors = Seq(user) ++ (1 to 3).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id))
      )

      def selectedBy(n: Int, image: Image, otherRate: Int = 0): Seq[Selection] = {
        jurors.slice(0, n).map(j => Selection(image, j, round, rate = 1)) ++
          jurors.slice(n, jurors.size).map(j => Selection(image, j, round, otherRate))
      }

      val selectedByX = (0 to 3).flatMap(x => selectedBy(x, images(x)))
      selectionDao.batchInsert(selectedByX)

      val result = files(round, 0, view = GalleryView.FileList)

      result.size === 4
      result.map(_.image) === Seq(images(3), images(2), images(1), images(0))
      result.map(_.rate) === Seq(1, 1, 1, 0)
      result.map(_.selection.size) === Seq(4, 4, 4, 4)
      result.map(_.selection.map(_.rate)) === Seq(
        Seq(1, 1, 1, 0),
        Seq(1, 1, 0, 0),
        Seq(1, 0, 0, 0),
        Seq(0, 0, 0, 0)
      )
    }
  }

  "all the jurors' votes on a yes/no round" should {

    /** Five jurors' votes: A (page 0) 3 approvals and 2 rejections, B (1) 2 approvals and 3
      * unrated, C (2) 3 approvals and 2 unrated, D (3) 2 approvals and 3 unrated, as B.
      */
    def setUpABCD(): (Round, Seq[Long]) = {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(4)
      val jurors = user +: (1 to 4).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id)))
      val votes = Seq(Seq(1, 1, 1, -1, -1), Seq(1, 1, 0, 0, 0), Seq(1, 1, 1, 0, 0), Seq(0, 0, 0, 1, 1))
      selectionDao.batchInsert(images.zip(votes).flatMap { case (image, rates) =>
        jurors.zip(rates).map { case (j, r) => Selection(image, j, round, r) }
      })
      (round, images.map(_.pageId))
    }

    def list(round: Round, order: BinaryOrder, view: GalleryView = GalleryView.ByRate) =
      RoundList(round, 0, order = ListOrder.Binary(order), view = view)

    // A, B, C, D
    val expected = Seq(
      BinaryOrder.ApprovalsThenRejections -> Seq(2, 0, 1, 3), // C(3,0) A(3,2) B(2,0) D(2,0)
      BinaryOrder.Difference -> Seq(2, 1, 3, 0),              // C 3, B 2, D 2, A 1
      BinaryOrder.ApprovalsOnly -> Seq(0, 2, 1, 3)            // A 3, C 3, B 2, D 2
    )

    "list the files in each order, ties by page id, in the gallery, file list and large view" in {
      val (round, ids) = setUpABCD()
      forall(expected) { case (order, indices) =>
        val ordered = indices.map(ids)
        val byRate = list(round, order)
        (byRate.files(Pager(1, 4)).map(_.pageId) must_== ordered) and
          (ordered.map(byRate.position) must_== ordered.indices.map(Some(_))) and
          (list(round, order, GalleryView.FileList).files(Pager(1, 4, 1000)).map(_.pageId) must_== ordered)
      }
    }

    "rank a page of the file list among all the files, by the order's values" in {
      val (round, ids) = setUpABCD()
      def ranks(order: BinaryOrder, page: Int) = {
        val l = list(round, order, GalleryView.FileList)
        l.ranks(l.files(Pager(page, 4, 2)))
      }
      (ranks(BinaryOrder.ApprovalsThenRejections, 1) ++ ranks(BinaryOrder.ApprovalsThenRejections, 2)) ===
        Seq("1", "2", "3-4", "3-4")
      ranks(BinaryOrder.Difference, 2) === Seq("2-3", "4")
      ranks(BinaryOrder.ApprovalsOnly, 1) === Seq("1-2", "1-2")
    }

    "give the files their approvals and rejections" in {
      val (round, ids) = setUpABCD()
      val files = list(round, BinaryOrder.Default).files(Pager(1, 4))
      files.map(f => (f.pageId, f.approvals, f.rejections)) ===
        Seq((ids(2), 3, 0), (ids(0), 3, 2), (ids(1), 2, 0), (ids(3), 2, 0))
    }

    "filter the files by any of their votes, counted, ordered and ranked by all of them" in {
      val (round, ids) = setUpABCD()
      def filtered(filter: VoteFilter, view: GalleryView = GalleryView.ByRate) =
        list(round, BinaryOrder.Default, view).withFilter(filter)
      def shown(l: RoundList) = l.files(Pager(1, 4, 1000)).map(f => (f.pageId, f.approvals, f.rejections))
      val rejected = filtered(VoteFilter.Rate(-1))
      val unrated = filtered(VoteFilter.Unrated)
      // A, the only file with a rejection, with its approvals (not "✓ 0")
      (shown(rejected) must_== Seq((ids(0), 3, 2))) and
        (rejected.count() must_== 1) and
        // B, C and D have unrated votes: C (3 approvals) first, as in the list of all
        (shown(unrated) must_== Seq((ids(2), 3, 0), (ids(1), 2, 0), (ids(3), 2, 0))) and
        (unrated.count() must_== 3) and
        (Seq(ids(2), ids(1), ids(3)).map(unrated.position) must_== Seq(Some(0), Some(1), Some(2))) and
        // the selected: every file, in the order of all of them
        (shown(filtered(VoteFilter.Rate(1))).map(_._1) must_== Seq(2, 0, 1, 3).map(ids)) and
        // the file list: the same files, each with all its votes, ranked among the listed ones
        (filtered(VoteFilter.Rate(-1), GalleryView.FileList).files(Pager(1, 1, 1000)).map(_.selection.size)
          must_== Seq(5)) and
        (filtered(VoteFilter.Unrated, GalleryView.FileList).ranks(
          filtered(VoteFilter.Unrated, GalleryView.FileList).files(Pager(1, 3, 1000))) must_== Seq("1", "2-3", "2-3"))
    }
  }

  "all the jurors' votes on a rated round" should {

    "place a file in the large view where the by-rate gallery lists it, by the rate sum" in {
      val (contest, round, user) = setUp(Round.ratesById(5))
      val images = createImages(4)
      val jurors = user +: (1 to 2).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id)))
      // sums 3, 5, 6, 4; each file's first vote row ordered otherwise (1, 5, 2, 4), as the
      // selection column `rate` would order them in place of the sum
      val votes = Seq(Seq(1, 1, 1), Seq(5, 0, 0), Seq(2, 2, 2), Seq(4, 0, 0))
      selectionDao.batchInsert(images.zip(votes).flatMap { case (image, rates) =>
        jurors.zip(rates).map { case (j, r) => Selection(image, j, round, r) }
      })
      val byRate = RoundList(round, 0, view = GalleryView.ByRate)
      val ordered = Seq(2, 1, 3, 0).map(images(_).pageId)
      (byRate.files(Pager(1, 4)).map(_.pageId) must_== ordered) and
        (ordered.map(byRate.position) must_== ordered.indices.map(Some(_))) and
        // its large view's files to move among, previous and next as the gallery has them
        forall(ordered.indices) { i =>
          val hood = byRate.around(ordered(i)).get
          val from = math.max(0, i - Neighbourhood.Side)
          (hood.files.map(_.pageId) must_== ordered.slice(from, from + Neighbourhood.Window)) and
            (hood.index must_== i - from) and (hood.position must_== i)
        }
    }
  }

  "a ranked list (a file list, by rate)" should {

    /** Three jurors' votes on six files of a rated round, F0 and F1 in region 12, the others in
      * 14, with rates below 0: sums 3, 5, 6, 2 (3, -1 and 0), -1 and 2; F3 and F5 tie.
      */
    def setUpRated(): (Round, Seq[User], Seq[Long]) = {
      val (contest, round, user) = setUp(Round.ratesById(5))
      val images = (0 until 6).map(id =>
        Image(id.toLong, s"File:Image$id.jpg", None, None, 640, 480, Some(s"${if (id < 2) 12 else 14}-345-$id")))
      imageDao.batchInsert(images)
      monumentDao.batchInsert(images.flatMap(_.monumentId).map(id =>
        new org.scalawiki.wlx.dto.Monument(id = id, name = s"Monument $id", page = "Test")))
      val jurors = user +: (1 to 2).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id)))
      val votes = Seq(Seq(1, 1, 1), Seq(5, 0, 0), Seq(2, 2, 2), Seq(3, -1, 0), Seq(-1, 0, 0), Seq(2, 0, 0))
      selectionDao.batchInsert(images.zip(votes).flatMap { case (image, rates) =>
        jurors.zip(rates).map { case (j, r) => Selection(image, j, round, r) }
      })
      (round, jurors, images.map(_.pageId))
    }

    "be a file list, or by rate in any order but file order" in {
      val (round, _, _) = setUpRated()
      (Seq(GalleryView.FileList, GalleryView.ByRate, GalleryView.Gallery, GalleryView.Csv)
        .map(v => RoundList(round, 0, view = v).ranked) must_== Seq(true, true, false, false)) and
        (RoundList(round, 0, order = ListOrder.FileOrder, view = GalleryView.ByRate).ranked must beFalse)
    }

    "have as many files as count() has, in a rated round: each viewer, filter, region and view" in {
      val (round, jurors, _) = setUpRated()
      val lists = for {
        asUserId <- 0L +: jurors.map(_.getId)
        filter <- VoteFilter.ofRound(round) :+ VoteFilter.Rate(5)
        region <- Seq(None, Some("12"), Some("14"), Some("99"))
        view <- Seq(GalleryView.ByRate, GalleryView.FileList)
      } yield RoundList(round, asUserId, region, filter, view = view)
      (lists.map(_.rankCounts().map(_._2).sum) must_== lists.map(_.count())) and
        // empty lists among them (region 99)
        (lists.count(_.count() == 0) must be_>(0))
    }

    "have as many files as count() has in two regions, through the monument table" in {
      val (round, _, _) = setUpRated()
      forall(Seq(GalleryView.ByRate, GalleryView.FileList)) { view =>
        val list = RoundList(round, 0, view = view)
        val two = list.query.copy(regions = Set("12", "14"))
        (two.keyCounts(list.rankKey.columns).map(_._2).sum must_== two.count()) and (two.count() must_== 6)
      }
    }

    "rank a rated round's files by their rate sums, rates below 0 too, across pages and past the end" in {
      val (round, _, ids) = setUpRated()
      // F2 6, F1 5, F0 3, F3 and F5 2 (in file order), F4 -1
      val ordered = Seq(2, 1, 0, 3, 5, 4).map(ids)
      forall(Seq(GalleryView.ByRate, GalleryView.FileList)) { view =>
        val list = RoundList(round, 0, view = view)
        val counts = list.rankCounts()
        val pages = (1 to 4).map(p => list.files(Pager(p, 6, 2)))
        (pages.flatten.map(_.pageId) must_== ordered) and
          (pages.flatMap(list.ranks(_, counts)) must_== Seq("1", "2", "3", "4-5", "4-5", "6")) and
          (pages(3) must beEmpty) and (counts.map(_._2).sum must_== 6)
      }
    }

    "have as many files as count() has, in a yes/no round: each order, filter and view" in {
      val (contest, round, user) = setUp(Round.binaryRound)
      val images = createImages(4)
      val jurors = user +: (1 to 2).map(i =>
        userDao.create(User(s"fullname$i", s"email$i", None, Set("jury"), contestId = contest.id)))
      // approvals and rejections 2 1, 1 2, 0 3, 3 0; no unrated vote, so that filter lists none
      val votes = Seq(Seq(1, 1, -1), Seq(1, -1, -1), Seq(-1, -1, -1), Seq(1, 1, 1))
      selectionDao.batchInsert(images.zip(votes).flatMap { case (image, rates) =>
        jurors.zip(rates).map { case (j, r) => Selection(image, j, round, r) }
      })
      val lists = for {
        order <- BinaryOrder.all
        filter <- VoteFilter.ofRound(round)
        view <- Seq(GalleryView.ByRate, GalleryView.FileList)
      } yield RoundList(round, 0, filter = filter, order = ListOrder.Binary(order), view = view)
      (lists.map(_.rankCounts().map(_._2).sum) must_== lists.map(_.count())) and
        (lists.filter(_.filter == VoteFilter.Unrated).map(_.count()).distinct must_== Seq(0)) and
        // the last page, F2 last in each order, and past it
        forall(lists.filter(_.filter == VoteFilter.All)) { l =>
          val last = l.files(Pager(2, 4, 3))
          (last.map(_.pageId) must_== Seq(images(2).pageId)) and
            (l.ranks(last, l.rankCounts()) must_== Seq("4")) and
            (l.files(Pager(3, 4, 3)) must beEmpty)
        }
    }
  }

  "a file's place in a juror's list" should {

    /** A binary round with 20 of the juror's files, unrated but `selected`. */
    def setUp20(selected: Set[Int] = Set.empty): (Round, User, Seq[Image]) = {
      val (_, round, user) = setUp(Round.binaryRound)
      val images = createImages(20)
      images.zipWithIndex.foreach { case (image, i) =>
        createSelection(Seq(image), if (selected(i)) 1 else 0, user, round)
      }
      (round, user, images)
    }

    /** The list's files in order, all of them. */
    def ordered(list: RoundList): Seq[Long] = list.query.list().map(_.pageId)

    "be its index, from 0, and the 15th file on page 1, the 16th on page 2" in {
      val (round, user, _) = setUp20()
      val list = RoundList(round, user.getId)
      val order = ordered(list)
      order.indices.map(i => list.position(order(i))) === order.indices.map(Some(_))
      Pager.pageOf(list.position(order(14)).get) === 1
      Pager.pageOf(list.position(order(15)).get) === 2
    }

    "be where it would be for a file the filter leaves out, as one just voted for" in {
      val (round, user, images) = setUp20(selected = Set(7))
      val voted = images(7).pageId
      val unrated = RoundList(round, user.getId, filter = VoteFilter.Unrated)
      val order = ordered(unrated)
      order must not(contain(voted))
      // by rate, a selected file comes before the unrated ones; they keep their places
      unrated.position(voted) === Some(0)
      order.indices.map(i => unrated.position(order(i))) === order.indices.map(Some(_))
    }

    "be none for a file not in the juror's round" in {
      val (round, user, _) = setUp20()
      RoundList(round, user.getId).position(99999L) must beNone
      RoundList(round, user.getId).around(99999L) must beNone
    }
  }

  "a large view's files" should {

    def setUpN(n: Int): (Round, User, Seq[Long]) = {
      val (_, round, user) = setUp(Round.ratesById(5))
      val images = createImages(n)
      createSelection(images, 0, user, round)
      (round, user, RoundList(round, user.getId).query.list().map(_.pageId))
    }

    "be the file and two on each side" in {
      val (round, user, order) = setUpN(10)
      val hood = RoundList(round, user.getId).around(order(5)).get
      hood.files.map(_.pageId) === order.slice(3, 8)
      hood.index === 2
      hood.next.map(_.pageId) === Some(order(6))
    }

    "be five at the list's start, fewer at its end, going on back at its last file" in {
      val (round, user, order) = setUpN(10)
      val list = RoundList(round, user.getId)
      val first = list.around(order.head).get
      (first.index, first.files.map(_.pageId)) === (0, order.take(5))
      val last = list.around(order.last).get
      (last.index, last.files.map(_.pageId)) === (2, order.takeRight(3))
      last.next.map(_.pageId) === Some(order(8))
    }

    "keep a file the filter now leaves out, where its new rate puts it" in {
      val (round, user, order) = setUpN(10)
      SelectionJdbc.rate(order(5), user.getId, round.getId, 4)
      val hood = RoundList(round, user.getId, filter = VoteFilter.Unrated).around(order(5)).get
      // by rate, first: the unrated files after it
      hood.file.pageId === order(5)
      hood.files.map(_.pageId) === order(5) +: order.filterNot(_ == order(5)).take(4)
    }

    "show in the strip the five files after the file, past the ones it moves among" in {
      val (round, user, order) = setUpN(12)
      val list = RoundList(round, user.getId)
      val middle = list.around(order(5)).get
      (middle.files.map(_.pageId), middle.following.map(_.pageId)) === (order.slice(3, 8), order.slice(6, 11))
      list.around(order.head).get.following.map(_.pageId) === order.slice(1, 6)
      list.around(order(9)).get.following.map(_.pageId) === order.slice(10, 12)
      list.around(order.last).get.following must beEmpty
    }

    "show in the strip the files after one the filter now leaves out" in {
      val (round, user, order) = setUpN(10)
      SelectionJdbc.rate(order(5), user.getId, round.getId, 4)
      val hood = RoundList(round, user.getId, filter = VoteFilter.Unrated).around(order(5)).get
      hood.following.map(_.pageId) === order.filterNot(_ == order(5)).take(5)
    }
  }

  "a vote's move in a juror's list" should {

    def setUp10(): (Round, User, Seq[Long]) = {
      val (_, round, user) = setUp(Round.ratesById(5))
      createSelection(createImages(10), 0, user, round)
      (round, user, RoundList(round, user.getId).query.list().map(_.pageId))
    }

    /** The vote for the file, as the large view casts it: its place first, then the vote. */
    def vote(list: RoundList, pageId: Long, rate: Int): Option[MovedVote] = {
      val before = list.position(pageId).get
      SelectionJdbc.rate(pageId, list.asUserId, list.roundId, rate)
      list.voteMoved(pageId, before, rate)
    }

    "be none in file order with all the files" in {
      val (round, user, order) = setUp10()
      vote(RoundList(round, user.getId, order = ListOrder.FileOrder), order(5), 4) must beNone
    }

    "be a move by rate, and none for a vote that leaves the file in place" in {
      val (round, user, order) = setUp10()
      val byRate = RoundList(round, user.getId)
      vote(byRate, order(5), 4) === Some(MovedVote(order(5), out = false, 4))
      vote(byRate, order(5), 3) must beNone // still the first
    }

    "be out of a filtered list, but not out of the unrated files" in {
      val (round, user, order) = setUp10()
      val fileOrder = RoundList(round, user.getId, order = ListOrder.FileOrder)
      vote(fileOrder.withFilter(VoteFilter.Unrated), order(5), 4) must beNone
      vote(fileOrder.withFilter(VoteFilter.Rated), order(5), 0) === Some(MovedVote(order(5), out = true, 0))
    }

    "go through the flash and back" in {
      val moved = MovedVote(105, out = true, 4)
      MovedVote.of(play.api.mvc.Flash(Map(moved.flash))) === Some(moved)
      MovedVote.of(play.api.mvc.Flash(Map(MovedVote.Key -> "105:nosuch:4"))) must beNone
      MovedVote.of(play.api.mvc.Flash()) must beNone
    }
  }
}
