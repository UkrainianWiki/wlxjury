package db.scalikejdbc

import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.{AutoSession, DBSession}

class ImageSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  private def image(id: Long) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id"))

  private def addToContest(contestId: Long, images: Seq[Image])
      (implicit session: DBSession = AutoSession): Unit =
    CategoryLinkJdbc.addToCategory(
      ContestJuryJdbc.findById(contestId).flatMap(_.categoryId).get,
      images
    )

  "fresh database" should {

    "be empty" in new AutoRollbackDb {
      imageDao.findAll() === Nil
    }

    "insert image" in new AutoRollbackDb {
      val id = 10
      val contestId = 20
      createContests(contestId)

      val image =
        Image(id, "File:Image.jpg", None, None, 640, 480, Some("12-345-6789"))
      imageDao.batchInsert(Seq(image))
      addToContest(contestId, Seq(image))

      imageDao.findById(id) === Some(image)
      imageDao.findAll() === Seq(image)
    }

    "find by contest " in new AutoRollbackDb {
      val (contest1, contest2) = (10, 20)
      createContests(contest1, contest2)

      val images1 = (11 to 19).map(id => image(id))
      val images2 = (21 to 29).map(id => image(id))

      imageDao.batchInsert(images1 ++ images2)
      addToContest(contest1, images1)
      addToContest(contest2, images2)

      imageDao.findByContestId(10) === images1
      imageDao.findByContestId(20) === images2
    }

    "contests can share images" in new AutoRollbackDb {
      val (contest1, contest2) = (10, 20)
      createContests(contest1, contest2)

      val images1 = (11 to 19).map(id => image(id))
      val images2 = (21 to 29).map(id => image(id))
      val commonImages = (31 to 39).map(id => image(id))

      imageDao.batchInsert(images1 ++ images2 ++ commonImages)
      addToContest(contest1, images1)
      addToContest(contest2, images2)
      addToContest(contest1, commonImages)
      addToContest(contest2, commonImages)

      imageDao.findByContestId(10) === images1 ++ commonImages
      imageDao.findByContestId(20) === images2 ++ commonImages
    }
  }
}
