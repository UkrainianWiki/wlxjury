package services

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/** A round creation can outlast the browser's or proxy's wait; the organizer's
  * resubmit must not create the round a second time.
  */
class RoundCreationResubmitSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private lazy val service = new RoundService(new DistributeImages(ImageJdbc), Round)

  private def setUp(): (ContestJury, Seq[Long]) = {
    implicit val contest: ContestJury = createContests(10).head
    val images = (1 to 20).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, None))
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    (contest, createUsers("jury", 1, 2, 3).map(_.getId))
  }

  private def newRound(contest: ContestJury, name: String) =
    Round(None, 0, Some(name), contest.getId, distribution = 2, active = true)

  "createNewRound" should {

    "refuse the same submission again" in {
      val (contest, jurors) = setUp()
      val first = service.createNewRound(newRound(contest, "Round 1"), jurors)
      service.createNewRound(newRound(contest, "Round 1"), jurors) must
        throwA[RoundService.DuplicateRound].like { case e: RoundService.DuplicateRound => e.existing.id === first.id }
      roundDao.findByContest(contest.getId).size === 1
    }

    "create a round with different settings" in {
      val (contest, jurors) = setUp()
      service.createNewRound(newRound(contest, "Round 1"), jurors)
      service.createNewRound(newRound(contest, "Round 1").copy(distribution = 1), jurors)
      service.createNewRound(newRound(contest, "Round 2"), jurors)
      roundDao.findByContest(contest.getId).size === 3
    }

    "create one round from concurrent identical submissions" in {
      val (contest, jurors) = setUp()
      implicit val ec: ExecutionContext = ExecutionContext.global
      val attempts = (1 to 3).map(_ => Future(Try(service.createNewRound(newRound(contest, "Round 1"), jurors))))
      val results = Await.result(Future.sequence(attempts), 1.minute)
      results.count(_.isSuccess) === 1
      results.flatMap(_.failed.toOption).forall(_.isInstanceOf[RoundService.DuplicateRound]) must beTrue
      roundDao.findByContest(contest.getId).size === 1
      SelectionJdbc.imageCountByRound(roundDao.findByContest(contest.getId).head.getId) === 20
    }

    "create the round again when the first creation's distribution failed" in {
      val (contest, jurors) = setUp()
      val failed = failedRound(contest, "Round 1", jurors)
      val created = service.createNewRound(newRound(contest, "Round 1"), jurors)
      created.id !== failed.id
      SelectionJdbc.imageCountByRound(created.getId) === 20
    }
  }

  "distributeNewImages" should {

    "fill a round whose first distribution failed" in {
      val (contest, jurors) = setUp()
      val failed = failedRound(contest, "Round 1", jurors)
      service.distributeNewImages(failed.getId) === 20
      SelectionJdbc.imageCountByRound(failed.getId) === 20
    }
  }

  /** What a failed creation leaves behind: the round row and its round_user rows, but
    * no selection rows (the distribution's transaction rolled back).
    */
  private def failedRound(contest: ContestJury, name: String, jurors: Seq[Long]): Round = {
    val round = roundDao.create(newRound(contest, name).copy(number = 1))
    round.addUsers(jurors.map(id => RoundUser(round.getId, id, "jury", active = true)))
    round
  }
}
