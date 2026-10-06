package org.intracer.wmua.cmd

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** A distribution writes images x jurors selection rows. The driver doesn't split one
  * JDBC batch to fit the server's max_allowed_packet, so a single batch of them fails
  * with "Socket error" once it outgrows it. The test container keeps testcontainers'
  * max_allowed_packet of 1M, where that happens between 20k and 30k rows (production's
  * 16M: roughly 400k rows, e.g. 40k images x 10 jurors).
  */
class DistributeImagesBatchSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private lazy val distributor = new DistributeImages(ImageJdbc)

  "distributeImages" should {

    "insert a distribution larger than max_allowed_packet in one batch" in {
      implicit val contest: ContestJury = createContests(10).head
      val packet = DB.readOnly(implicit s => sql"SELECT @@max_allowed_packet".map(_.long(1)).single().get)
      val round = roundDao.create(Round(None, 1, None, contest.getId, distribution = 0, createdAt = now))
      val jurors = createUsers("jury", (1 to 10): _*)
      val images = (1 to 4000).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(s"12-345-$i")))
      imageDao.batchInsert(images)

      distributor.distributeImages(round, images, jurors) === 40000
      SelectionJdbc.imageCountByRound(round.getId) === 4000
      DB.readOnly(implicit s =>
        sql"SELECT COUNT(*) FROM selection WHERE round_id = ${round.getId}".map(_.int(1)).single().get) === 40000
      packet must be_<=(1024L * 1024) // the container's limit the batch must fit
    }

    "insert nothing when a chunk fails" in {
      implicit val contest: ContestJury = createContests(10).head
      val round = roundDao.create(Round(None, 1, None, contest.getId, distribution = 0, createdAt = now))
      val jurors = createUsers("jury", (1 to 2): _*)
      val images = (1 to 6000).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, None))
      // a row already there makes the last chunk violate the unique index
      selectionDao.create(images.last.pageId, rate = 0, juryId = jurors.sorted.last.getId, roundId = round.getId)

      distributor.distributeImages(round, images, jurors) must throwA[Exception]
      DB.readOnly(implicit s =>
        sql"SELECT COUNT(*) FROM selection WHERE round_id = ${round.getId}".map(_.int(1)).single().get) === 1
    }
  }
}
