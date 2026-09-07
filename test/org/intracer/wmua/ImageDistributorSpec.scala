package org.intracer.wmua

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.RoundService

class ImageDistributorSpec extends Specification with TestDb with Mockito
    with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  val (contest1, contest2) = (10, 20)

  lazy val di = new DistributeImages(ImageJdbc)
  lazy val roundService = new RoundService(di, Round)

  private def image(id: Long) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def createJurors(
      jurorsNum: Int,
      preJurors: Boolean = true,
      orgCom: Boolean = true,
      otherContestId: Option[Long] = Some(20),
      start: Int = 1
  )(implicit contest: ContestJury) = {

    val jurors = (start until jurorsNum + start).map(contestUser(_))
    val dbJurors = jurors.map(userDao.create)

    val preJurors = (start until jurorsNum + start).map(i => contestUser(i + 100, "prejury"))
    preJurors.foreach(userDao.create)

    if (start == 1) {
      val orgCom = contestUser(200, "organizer")
      userDao.create(orgCom)

      val otherContestJurors =
        (1 to jurorsNum).map(i => contestUser(i + 300, "jury")(contest.copy(id = otherContestId)))
      otherContestJurors.foreach(userDao.create)
    }

    dbJurors
  }

  private def createImages(number: Int, contest1: Long, contest2: Long) = {
    val images1 = (101 to 100 + number).map(id => image(id))
    val images2 = (100 + number + 1 to 100 + number * 2).map(id => image(id))

    imageDao.batchInsert(images1 ++ images2)

    CategoryLinkJdbc.addToCategory(
      ContestJuryJdbc.findById(contest1).flatMap(_.categoryId).get,
      images1
    )
    CategoryLinkJdbc.addToCategory(
      ContestJuryJdbc.findById(contest2).flatMap(_.categoryId).get,
      images2
    )

    images1
  }

  "ImageDistributor" should {

    "first round 1 juror to image, one juror" in {
      locally {
        val distribution = 1

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(9, contest1, contest2)

        val oneJuror = createJurors(1)
        oneJuror.size === 1
        val juryIds = oneJuror.map(_.getId)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundService.createNewRound(round, juryIds)

        val selection1 = selectionDao.findAll()
        selection1.size === 9

        selection1.map(_.roundId).toSet === Set(dbRound.getId)
        selection1.map(_.rate).toSet === Set(0)
        selection1.map(_.pageId) === images.map(_.pageId)
        selection1.map(_.juryId).toSet === juryIds.toSet

        val roundWithJurors = roundDao.findById(dbRound.getId).get
        roundWithJurors.users === oneJuror
      }
    }

    "create first round 1 juror to image" in {
      locally {
        val distribution = 1

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(9, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundDao.create(round)

        val dbJurors = createJurors(3)
        dbJurors.size === 3
        val juryIds = dbJurors.map(_.getId)

        di.distributeImages(dbRound, dbJurors, Nil)

        val selection = selectionDao.findAll()

        selection.size === 9

        selection.map(_.roundId).toSet === Set(dbRound.getId)
        selection.map(_.rate).toSet === Set(0)
        selection.map(_.pageId) === images.map(_.pageId)
        selection.map(_.juryId) === juryIds ++ juryIds ++ juryIds
      }
    }

    "first round 1 juror to image, add jurors" in {
      locally {
        val distribution = 1

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(9, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundDao.create(round)

        val firstJuror = createJurors(1)
        di.distributeImages(dbRound, firstJuror, Nil)

        val selection1 = selectionDao.findAll()
        selection1.size === 9

        val moreJurors = createJurors(2, start = 2)
        val allJurors = firstJuror ++ moreJurors
        val allJuryIds = allJurors.map(_.getId)

        di.distributeImages(dbRound, allJurors, Nil, removeUnrated = true)

        val selection2 = selectionDao.findAll()
        selection2.size === 9

        selection2.map(_.roundId).toSet === Set(dbRound.getId)
        selection2.map(_.rate).toSet === Set(0)
        selection2.map(_.pageId) === images.map(_.pageId)
        selection2.map(_.juryId) === allJuryIds ++ allJuryIds ++ allJuryIds
      }
    }

    "first round 1 juror to image, rate and add jurors" in {
      locally {
        val distribution = 1

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(9, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundDao.create(round)

        val firstJuror = createJurors(1)
        val juryIds1 = firstJuror.map(_.getId)

        di.distributeImages(dbRound, firstJuror, Nil)

        SelectionJdbc.rate(images(0).pageId, juryIds1(0), dbRound.getId, 1)
        SelectionJdbc.rate(images(1).pageId, juryIds1(0), dbRound.getId, -1)

        val moreJurors = createJurors(2, start = 2)

        val allJurors = firstJuror ++ moreJurors
        val allJuryIds = allJurors.map(_.getId)

        di.distributeImages(dbRound, allJurors, Nil)

        val selection2 = selectionDao.findAll()

        selection2.size === 9

        selection2.map(_.roundId).toSet === Set(dbRound.getId)
        selection2.map(_.rate).toSet === Set(0)
        selection2.map(_.pageId) === images.map(_.pageId)
        selection2.map(_.juryId) === allJuryIds ++ allJuryIds ++ allJuryIds
      }
    }.pendingUntilFixed

    "create first round 2 jurors to image" in {
      locally {
        val distribution = 2
        val numImages = 9
        val numJurors = 3

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(numImages, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundDao.create(round)

        val dbJurors = createJurors(numJurors)

        di.distributeImages(dbRound, dbJurors, Nil)

        val selection = selectionDao.findAll()

        selection.size === numImages * distribution

        selection.map(_.roundId).toSet === Set(dbRound.getId)
        selection.map(_.rate).toSet === Set(0)

        val jurorsPerImage = selection.groupBy(_.pageId).values.map(_.size)
        jurorsPerImage.size === numImages
        jurorsPerImage.toSet === Set(2)

        val imagesPerJuror = selection.groupBy(_.juryId).values.map(_.size)
        imagesPerJuror.size === numJurors
        imagesPerJuror.toSet === Set(distribution * numImages / numJurors)
      }
    }

    "create second round 1 juror to image in the first" in {
      locally {
        val distribution = 1

        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(9, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.binaryRound,
          active = true
        )
        val dbRound = roundDao.create(round)

        val dbJurors = createJurors(3)
        val juryIds = dbJurors.map(_.getId)

        di.distributeImages(dbRound, dbJurors, Nil)

        val selection = selectionDao.findAll()
        val byJuror = selection.groupBy(_.juryId)

        val selectedPageIds = (for (
          (juryId, numSelected) <- juryIds.zipWithIndex;
          i <- 0 until numSelected
        ) yield {
          val pageId = byJuror(juryId)(i).pageId
          SelectionJdbc.rate(pageId, juryId, dbRound.getId, 1)
          pageId
        }).toSet

        val round2 = Round(
          None,
          2,
          Some("Round 2"),
          contest1,
          Set("jury"),
          0,
          Round.ratesById(10),
          active = true,
          prevSelectedBy = Some(1)
        )
        val dbRound2 = roundDao.create(round2)

        di.distributeImages(dbRound2, dbJurors, Seq(dbRound))

        val secondRoundPageIds = selectionDao
          .findAll()
          .filter(_.roundId == dbRound2.getId)
          .map(_.pageId)
          .toSet

        secondRoundPageIds === selectedPageIds
      }
    }

    "create second round 2 jurors to image in the first" in {
      locally {

        val distribution = 2
        val numImages = 2
        val numJurors = 2
        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(numImages, contest1, contest2)

        val round = Round(
          None,
          1,
          Some("Round 1"),
          contest1,
          Set("jury"),
          distribution,
          Round.ratesById(10),
          active = true
        )
        val dbRound = roundDao.create(round)

        val dbJurors = createJurors(numJurors)
        val juryIds = dbJurors.map(_.getId)

        di.distributeImages(dbRound, dbJurors, Nil)

        SelectionJdbc.rate(images(0).pageId, juryIds(0), dbRound.getId, 1)
        SelectionJdbc.rate(images(0).pageId, juryIds(1), dbRound.getId, -1)

        SelectionJdbc.rate(images(1).pageId, juryIds(0), dbRound.getId, 0)
        SelectionJdbc.rate(images(1).pageId, juryIds(1), dbRound.getId, -1)

        val round2 = Round(
          None,
          2,
          Some("Round 2"),
          contest1,
          Set("jury"),
          0,
          Round.ratesById(10),
          active = true,
          prevSelectedBy = Some(1)
        )
        val dbRound2 = roundDao.create(round2)

        di.distributeImages(dbRound2, dbJurors, Seq(dbRound))

        val selection2 = selectionDao.findAll().filter(_.roundId == dbRound2.getId)

        selection2.map(_.pageId).toSet === Set(images(0).pageId)
      }
    }

    "create a round from two previous rounds using the union of their selected images" in {
      locally {
        val numImages = 4
        val numJurors = 2
        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(numImages, contest1, contest2)

        def binaryRound(number: Long) =
          roundDao.create(
            Round(
              None,
              number,
              Some(s"Round $number"),
              contest1,
              Set("jury"),
              0,
              Round.binaryRound,
              active = true
            )
          )

        val dbRoundA = binaryRound(1)
        val dbRoundB = binaryRound(2)

        val dbJurors = createJurors(numJurors)
        val juryIds = dbJurors.map(_.getId)

        di.distributeImages(dbRoundA, dbJurors, Nil)
        di.distributeImages(dbRoundB, dbJurors, Nil)

        // image 0 is selected only in round A, image 2 only in round B
        SelectionJdbc.rate(images(0).pageId, juryIds(0), dbRoundA.getId, 1)
        SelectionJdbc.rate(images(2).pageId, juryIds(0), dbRoundB.getId, 1)

        val dbRound3 = roundDao.create(
          Round(
            None,
            3,
            Some("Round 3"),
            contest1,
            Set("jury"),
            0,
            Round.ratesById(10),
            active = true,
            previous = Some(s"${dbRoundA.getId},${dbRoundB.getId}"),
            prevSelectedBy = Some(1)
          )
        )

        di.distributeImages(dbRound3, dbJurors, Seq(dbRoundA, dbRoundB))

        val round3PageIds =
          selectionDao.findAll().filter(_.roundId == dbRound3.getId).map(_.pageId).toSet

        round3PageIds === Set(images(0).pageId, images(2).pageId)
      }
    }

    "count selections across previous rounds cumulatively and not duplicate the image" in {
      locally {
        val numImages = 4
        val numJurors = 2
        implicit val contest = createContests(contest1, contest2).head
        val images = createImages(numImages, contest1, contest2)

        def binaryRound(number: Long) =
          roundDao.create(
            Round(
              None,
              number,
              Some(s"Round $number"),
              contest1,
              Set("jury"),
              0,
              Round.binaryRound,
              active = true
            )
          )

        val dbRoundA = binaryRound(1)
        val dbRoundB = binaryRound(2)

        val dbJurors = createJurors(numJurors)
        val juryIds = dbJurors.map(_.getId)

        di.distributeImages(dbRoundA, dbJurors, Nil)
        di.distributeImages(dbRoundB, dbJurors, Nil)

        // image 0: selected by one juror in round A and by another juror in round B
        SelectionJdbc.rate(images(0).pageId, juryIds(0), dbRoundA.getId, 1)
        SelectionJdbc.rate(images(0).pageId, juryIds(1), dbRoundB.getId, 1)
        // image 1: selected once, only in round A
        SelectionJdbc.rate(images(1).pageId, juryIds(0), dbRoundA.getId, 1)

        val dbRound3 = roundDao.create(
          Round(
            None,
            3,
            Some("Round 3"),
            contest1,
            Set("jury"),
            0,
            Round.ratesById(10),
            active = true,
            previous = Some(s"${dbRoundA.getId},${dbRoundB.getId}"),
            prevSelectedBy = Some(2)
          )
        )

        di.distributeImages(dbRound3, dbJurors, Seq(dbRoundA, dbRoundB))

        val round3 = selectionDao.findAll().filter(_.roundId == dbRound3.getId)

        // image 0 reaches the threshold of 2 only by combining both rounds; image 1 doesn't
        round3.map(_.pageId).toSet === Set(images(0).pageId)
        // distributed once per juror, not once per previous round the image came from
        round3.count(_.pageId == images(0).pageId) === numJurors
      }
    }

    "reject previous rounds of mixed rate type" in {
      locally {
        implicit val contest = createContests(contest1, contest2).head
        createImages(4, contest1, contest2)

        val binary = roundDao.create(
          Round(None, 1, Some("bin"), contest1, Set("jury"), 0, Round.binaryRound, active = true)
        )
        val rated = roundDao.create(
          Round(None, 2, Some("rated"), contest1, Set("jury"), 0, Round.ratesById(10), active = true)
        )
        val next = roundDao.create(
          Round(
            None,
            3,
            Some("next"),
            contest1,
            Set("jury"),
            0,
            Round.ratesById(10),
            active = true,
            previous = Some(s"${binary.getId},${rated.getId}")
          )
        )

        di.imagesByRound(next, Seq(binary, rated)) must throwA[IllegalArgumentException]
      }
    }
  }
}
