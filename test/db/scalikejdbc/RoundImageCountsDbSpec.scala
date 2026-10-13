package db.scalikejdbc

import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.RoundService

/** The cached per-round image counts follow every write that changes a round's
  * images, without the cache being cleared by hand. The writes and the counts use
  * autoSession, so the tests commit their data (emptying the database before each).
  */
class RoundImageCountsDbSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private lazy val distributor = new DistributeImages(ImageJdbc)
  private lazy val roundService = new RoundService(distributor, Round)

  private def count(roundId: Long): Int =
    RoundImageCounts.get(Seq(roundId))(ImageJdbc.imageCountByRounds)(roundId)

  private def image(id: Long) = Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def setUp(images: Int, jurors: Int = 2): (ContestJury, Round, Seq[Image], Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val round = roundDao.create(
      Round(None, 1, Some("R1"), contest.getId, distribution = 0, active = true, createdAt = now))
    val imgs = (1 to images).map(i => image(i.toLong))
    imageDao.batchInsert(imgs)
    (contest, round, imgs, createUsers("jury", (1 to jurors): _*))
  }

  "imageCountByRounds" should {
    "count distinct images per round and leave out rounds without images" in {
      val (contest, round, images, jurors) = setUp(3)
      val empty = roundDao.create(Round(None, 2, None, contest.getId, createdAt = now))
      distributor.distributeImages(round, images, jurors)
      ImageJdbc.imageCountByRounds(Seq(round.getId, empty.getId)) === Map(round.getId -> 3)
      ImageJdbc.imageCountByRounds(Nil) === Map.empty
    }
  }

  "RoundImageCounts" should {

    "follow image distribution" in {
      val (_, round, images, jurors) = setUp(3)
      count(round.getId) === 0
      distributor.distributeImages(round, images.take(2), jurors)
      count(round.getId) === 2
      distributor.distributeImages(round, images.drop(2), jurors)
      count(round.getId) === 3
    }

    "follow distributing new files to a round" in {
      val (contest, _, images, jurors) = setUp(4)
      CategoryLinkJdbc.addToCategory(ContestJuryJdbc.findById(contest.getId).get.categoryId.get, images.take(2))
      val created = roundService.createNewRound(
        Round(None, 0, Some("R2"), contest.getId, distribution = 1, active = true, createdAt = now),
        jurors.map(_.getId))
      count(created.getId) === 2

      CategoryLinkJdbc.addToCategory(ContestJuryJdbc.findById(contest.getId).get.categoryId.get, images.drop(2))
      roundService.distributeNewImages(created.getId) === 2
      count(created.getId) === 4
    }

    "follow removing an image and removing unrated images" in {
      val (_, round, images, jurors) = setUp(3)
      distributor.distributeImages(round, images, jurors)
      count(round.getId) === 3
      SelectionJdbc.removeImage(images.head.pageId, round.getId)
      count(round.getId) === 2
      SelectionJdbc.rate(images(1).pageId, jurors.head.getId, round.getId, 1)
      SelectionJdbc.removeUnrated(round.getId)
      count(round.getId) === 1
    }

    "follow moving an image to another round" in {
      val (contest, round, images, jurors) = setUp(2)
      val other = roundDao.create(Round(None, 2, None, contest.getId, createdAt = now))
      distributor.distributeImages(round, images, jurors)
      count(round.getId) === 2
      count(other.getId) === 0
      SelectionJdbc.setRound(images.head.pageId, round.getId, other.getId)
      count(round.getId) === 1
      count(other.getId) === 1
    }

    "follow merging rounds" in {
      val (contest, round, images, jurors) = setUp(3)
      val other = roundDao.create(Round(None, 2, None, contest.getId, createdAt = now))
      distributor.distributeImages(round, images.take(1), jurors)
      distributor.distributeImages(other, images.drop(1), jurors)
      count(round.getId) === 1
      count(other.getId) === 2
      roundService.mergeRounds(contest.getId, targetRoundId = round.getId, sourceRoundId = other.getId)
      count(round.getId) === 3
      count(other.getId) === 0
    }

    "not change on a vote" in {
      val (_, round, images, jurors) = setUp(2)
      distributor.distributeImages(round, images, jurors)
      count(round.getId) === 2
      SelectionJdbc.rate(images.head.pageId, jurors.head.getId, round.getId, 1)
      count(round.getId) === ImageJdbc.imageCountByRounds(Seq(round.getId))(round.getId)
    }
  }
}
