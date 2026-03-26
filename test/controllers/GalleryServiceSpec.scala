package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.GalleryService

class GalleryServiceSpec extends Specification with TestDb with BeforeAll with BeforeEach {

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

      val result = new GalleryService().getSortedImages(user.getId, None, round.id, "gallery")

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
        val result = new GalleryService().getSortedImages(user.getId, Some(rate), round.id, "gallery")

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

      val result = new GalleryService().getSortedImages(user.getId, None, round.id, "gallery")

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

      val result = new GalleryService().getSortedImages(user.getId, None, round.id, "gallery")

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

      val result = new GalleryService().getSortedImages(0, None, round.id, "gallery")

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

      val result = new GalleryService().getSortedImages(0, None, round.id, "filelist")

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

      val result = new GalleryService().getSortedImages(0, None, round.id, "filelist")

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
}
