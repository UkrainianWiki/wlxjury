package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew
import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{Selection, _}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.DBSession

class JurorImagesSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  private def contestImage(id: Long, contestId: Long) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def setUp(rates: Rates = Round.binaryRound)(implicit session: DBSession): (ContestJury, Round, User) = {
    val contest = contestDao.create(None, "WLE", 2015, "Ukraine", None, None, None)
    val round = roundDao.create(
      Round(None, 1, contestId = contest.getId, rates = rates, active = true)
    )
    val user = userDao.create(
      User("fullname", "email", None, Set("jury"), contestId = contest.id)
    )
    (contest, round, user)
  }

  private def createImages(
      number: Int,
      contestId: Long,
      startId: Int = 0
  )(implicit session: DBSession): Seq[Image] = {
    val images =
      (startId until number + startId).map(id => contestImage(id, contestId))
    imageDao.batchInsert(images)
    images
  }

  private def createSelection(
      images: Seq[Image],
      user: User,
      round: Round,
      rate: Int = 0
  )(implicit session: DBSession): Seq[Selection] = {
    val selections = images.zipWithIndex.map { case (image, _) =>
      Selection(image, user, round, rate)
    }
    selectionDao.batchInsert(selections)
    selections
  }

  "juror large view" should {

    "no images" in new AutoRollbackDb {
      val (contest, round, user) = setUp()
      def query(): SelectionQuery = ImageDbNew.SelectionQuery(
        userId = user.id,
        roundId = round.id
      )
      query().imageRank(1) === 0
    }

    "1 image" in new AutoRollbackDb {
      val (contest, round, user) = setUp()
      val images = createImages(1, contest.getId)
      createSelection(images, user, round)

      def query(): SelectionQuery = ImageDbNew.SelectionQuery(
        userId = user.id,
        roundId = round.id
      )

      val id = images.head.pageId
      query().imageRank(id) === 1
      query().imageRank(id + 1) === 0
    }

    "2 images" in new AutoRollbackDb {
      val (contest, round, user) = setUp()
      val images = createImages(2, contest.getId)
      createSelection(images, user, round)

      def query(): SelectionQuery = ImageDbNew.SelectionQuery(
        userId = user.id,
        roundId = round.id
      )

      query().imageRank(images.head.pageId) === 1
      query().imageRank(images.last.pageId) === 2
      query().imageRank(images.last.pageId + 1) === 0
    }
  }

  "juror" should {
    "see assigned images in binary round" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.binaryRound)
      val images = createImages(6, contest.getId)
      val slice = images.slice(0, 3)
      createSelection(slice, user, round, rate = 0)

      val query =
        ImageDbNew.SelectionQuery(userId = user.id, roundId = round.id)
      val result = query.list()

      result.size === 3
      result.map(_.image) === slice
      result.map(_.selection.size) === Seq(1, 1, 1)
      result.map(_.rate) === Seq(0, 0, 0)

      slice.zipWithIndex.map { case (image, index) =>
        query.imageRank(image.pageId) === index + 1
      }
    }

    "see images filtered by rate in binary round" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.binaryRound)
      val images = createImages(6, contest.getId)

      def range(rate: Int) = Seq(2 + rate * 2, 4 + rate * 2)
      def slice(rate: Int) = images.slice(range(rate).head, range(rate).last)

      for (rate <- -1 to 1) {
        createSelection(slice(rate), user, round, rate = rate)
      }

      for (rate <- -1 to 1) yield {
        val query = ImageDbNew.SelectionQuery(
          userId = user.id,
          roundId = round.id,
          rate = Some(rate)
        )
        val result = query.list()

        result.size === 2
        result.map(_.image) === slice(rate)
        result.map(_.selection.size) === Seq(1, 1)
        result.map(_.rate) === Seq(rate, rate)
      }

      for (
        rate <- -1 to 1;
        (image, index) <- slice(rate).zipWithIndex
      ) yield {
        val query = ImageDbNew.SelectionQuery(
          userId = user.id,
          roundId = round.id,
          rate = Some(rate)
        )
        query.imageRank(image.pageId) === index + 1
      }
    }

    "see images ordered by rate in binary round" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.binaryRound)
      val images = createImages(3, contest.getId)

      def range(rate: Int) = Seq(1 + rate, 2 + rate)
      def slice(rate: Int) = images.slice(range(rate).head, range(rate).last)

      for (rate <- -1 to 1) {
        createSelection(slice(rate), user, round, rate = rate)
      }

      val query: SelectionQuery = ImageDbNew.SelectionQuery(
        userId = user.id,
        roundId = round.id,
        order = Map("rate" -> -1)
      )
      val result = query.list()

      result.size === 3
      result.map(_.image) === slice(1) ++ slice(0) ++ slice(-1)
      result.map(_.selection.size) === Seq.fill(3)(1)
      result.map(_.rate) === Seq(1, 0, -1)

      images.zip(Seq(3, 2, 1)).map { case (image, index) =>
        query.imageRank(image.pageId) === index
      }
    }

    "see images ordered by rate in rated round" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.ratesById(10))
      val images = createImages(6, contest.getId)

      val selections = images.zipWithIndex.map { case (image, rate) =>
        Selection(image, user, round, rate)
      }
      selectionDao.batchInsert(selections)

      val query = ImageDbNew.SelectionQuery(
        userId = user.id,
        roundId = round.id,
        order = Map("rate" -> -1)
      )
      val result = query.list()

      result.size === 6
      result.map(_.image) === images.reverse
      result.map(_.selection.size) === Seq.fill(6)(1)
      result.map(_.rate) === (0 to 5).reverse

      images.zip((1 to 6).reverse).map { case (image, index) =>
        query.imageRank(image.pageId) === index
      }
    }
  }

  "organizer" should {
    "see rating by selection" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.binaryRound)
      implicit val implicitContest: ContestJury = contest
      val images = createImages(10, contest.getId)

      val jurors =
        Seq(user) ++ (1 to 3).map(contestUser(_)).map(userDao.create)

      def selectedBy(
          n: Int,
          image: Image,
          otherRate: Int = 0
      ): Seq[Selection] = {
        jurors.slice(0, n).map { juror =>
          Selection(image, juror, round, 1)
        } ++
          jurors.slice(n, jurors.size).map { juror =>
            Selection(image, juror, round, otherRate)
          }
      }

      val selectedByX = (0 to 3).flatMap(x => selectedBy(x, images(x)))
      selectionDao.batchInsert(selectedByX)

      val query = ImageDbNew.SelectionQuery(
        roundId = round.id,
        grouped = true,
        order = Map("rate" -> -1)
      )
      val result = query.list()

      result.size === 4
      val actual = result.map(_.image)
      val expected = Seq(
        images(3),
        images(2),
        images(1),
        images(0)
      )
      actual === expected
      result.map(_.selection.size) === Seq(1, 1, 1, 1)
      result.map(_.rate) === Seq(3, 2, 1, 0)

      images.slice(0, 4).zip((1 to 4).reverse).map { case (image, index) =>
        query.imageRank(image.pageId) === index
      }
    }

    "see details by selection rejected not accounted" in new AutoRollbackDb {
      val (contest, round, _) = setUp(rates = Round.binaryRound)
      implicit val implicitContest: ContestJury = contest
      val images = createImages(2, contest.getId)

      val jurors = (1 to 3).map(contestUser(_)).map(userDao.create)

      def rate(imageIndex: Int, jurorIndex: Int, r: Int) =
        Selection(images(imageIndex), jurors(jurorIndex), round, r)

      def rateN(imageIndex: Int, rates: Seq[Int]) =
        (0 to jurors.size - 1)
          .zip(rates)
          .map { case (j, r) =>
            rate(imageIndex, j, r)
          }

      val selections =
        rateN(0, Seq(1, -1, -1)) ++
          rateN(1, Seq(0, 0, 0))

      selectionDao.batchInsert(selections)

      val query =
        ImageDbNew.SelectionQuery(roundId = round.id, groupWithDetails = true)
      val result = query.list()

      result.size === 2
      result.map(_.image) === Seq(images(0), images(1))
      result.map(_.selection.size) === Seq(3, 3)
      result.map(_.rate) === Seq(1, 0)
    }

    "see details by selection" in new AutoRollbackDb {
      val (contest, round, user) = setUp(rates = Round.binaryRound)
      implicit val implicitContest: ContestJury = contest
      val images = createImages(10, contest.getId)

      val jurors =
        Seq(user) ++ (1 to 3).map(contestUser(_)).map(userDao.create)

      def selectedBy(
          n: Int,
          image: Image,
          otherRate: Int = 0
      ): Seq[Selection] = {
        jurors.slice(0, n).map { juror =>
          Selection(image, juror, round, 1)
        } ++
          jurors.slice(n, jurors.size).map { juror =>
            Selection(image, juror, round, otherRate)
          }
      }

      val selectedByX = (0 to 3).flatMap(x => selectedBy(x, images(x)))
      selectionDao.batchInsert(selectedByX)

      val query =
        ImageDbNew.SelectionQuery(roundId = round.id, groupWithDetails = true)
      val result = query.list()

      result.size === 4
      result.map(_.image) === Seq(
        images(3),
        images(2),
        images(1),
        images(0)
      )
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
