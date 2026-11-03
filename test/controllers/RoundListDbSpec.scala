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
