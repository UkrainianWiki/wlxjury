package services

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import java.util.UUID
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.Try

/** A round creation can outlast the browser's or proxy's wait, or fail half-way; the
  * organizer's resubmit of the same form (same submission token) must not create the
  * round a second time, and must finish the first one.
  */
class RoundCreationResubmitSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Fails its first `failures` distributions, and records whether the watched
    * (previous) rounds were active when a distribution started.
    */
  private class FlakyDistributor(failures: Int, watch: => Seq[Long] = Nil) extends DistributeImages(ImageJdbc) {
    @volatile private var remaining = failures
    val previousActive: mutable.Buffer[Boolean] = mutable.Buffer.empty

    override def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Int = {
      previousActive ++= watch.flatMap(id => Round.findById(id).map(_.active))
      if (remaining > 0) {
        remaining -= 1
        throw new RuntimeException("simulated distribution failure")
      }
      super.distributeImages(round, images, jurors)
    }
  }

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

  private def token() = Some(UUID.randomUUID().toString)

  private def rounds(contest: ContestJury) = roundDao.findByContest(contest.getId)

  private def selectionRows(roundId: Long) = SelectionJdbc.byRound(roundId)

  "submit" should {

    "not create a second round for a resubmit of the same form" in {
      val (contest, jurors) = setUp()
      val form = token()
      val first = service.submit(newRound(contest, "Round 1"), jurors, form)
      first must beAnInstanceOf[RoundService.Created]
      service.submit(newRound(contest, "Round 1"), jurors, form) must beLike {
        case r: RoundService.Resubmitted => (r.round.id === first.round.id) and (r.imagesAdded === 0)
      }
      rounds(contest).size === 1
      selectionRows(first.round.getId).size === 40
    }

    "create two rounds from two forms with the same settings (a split jury)" in {
      val (contest, jurors) = setUp()
      val a = service.submit(newRound(contest, "Round 1").copy(distribution = 1), jurors.take(2), token())
      val b = service.submit(newRound(contest, "Round 1").copy(distribution = 1), jurors.drop(2), token())
      rounds(contest).size === 2
      selectionRows(a.round.getId).map(_.juryId).toSet === jurors.take(2).toSet
      selectionRows(b.round.getId).map(_.juryId).toSet === jurors.drop(2).toSet
    }

    "not duplicate a round that is empty on purpose when its form is resubmitted" in {
      val (contest, jurors) = setUp()
      val form = token()
      val empty = newRound(contest, "Videos only").copy(mediaType = Some("video")) // the contest has none
      service.submit(empty, jurors, form).imagesAdded === 0
      service.submit(empty, jurors, form) must beAnInstanceOf[RoundService.Resubmitted]
      rounds(contest).size === 1
    }

    "resume a failed creation on a resubmit: the same round, filled, previous rounds frozen" in {
      val (contest, jurors) = setUp()
      val previous = service.createNewRound(newRound(contest, "Round 1"), jurors)
      Round.setActive(previous.getId, active = true)
      val flaky = new FlakyDistributor(failures = 1, watch = Seq(previous.getId))
      val flakyService = new RoundService(flaky, Round)
      val form = token()
      val next = newRound(contest, "Round 2").copy(previous = Some(previous.getId.toString))

      flakyService.submit(next, jurors, form) must throwA[RoundService.RoundDistributionFailed]
      val failed = rounds(contest).find(_.name.contains("Round 2")).get
      selectionRows(failed.getId) must beEmpty
      roundDao.findById(previous.getId).map(_.active) === Some(true) // restored after the failure

      flakyService.submit(next, jurors, form) must beLike {
        case r: RoundService.Resubmitted => (r.round.id === failed.id) and (r.imagesAdded === 20)
      }
      rounds(contest).size === 2 // the previous round and the resumed one: no stray round
      SelectionJdbc.imageCountByRound(failed.getId) === 20
      flaky.previousActive.toSeq === Seq(false, false) // frozen during both attempts
      roundDao.findById(previous.getId).map(_.active) === Some(false) // a creation leaves them frozen
    }

    "give no images to a juror stopped on the round before the retry" in {
      val (contest, jurors) = setUp()
      val flakyService = new RoundService(new FlakyDistributor(failures = 1), Round)
      val form = token()
      flakyService.submit(newRound(contest, "Round 1"), jurors, form) must
        throwA[RoundService.RoundDistributionFailed]
      val failed = rounds(contest).head
      RoundUser.setActive(failed.getId, jurors.last, active = false)

      flakyService.submit(newRound(contest, "Round 1"), jurors, form).imagesAdded === 20
      selectionRows(failed.getId).map(_.juryId).toSet === jurors.init.toSet
    }

    "create one fully distributed round from concurrent identical submits" in {
      val (contest, jurors) = setUp()
      implicit val ec: ExecutionContext = ExecutionContext.global
      val form = token()
      val attempts = (1 to 3).map(_ => Future(Try(service.submit(newRound(contest, "Round 1"), jurors, form))))
      val results = Await.result(Future.sequence(attempts), 1.minute)
      results.forall(_.isSuccess) must beTrue
      results.map(_.get).count(_.isInstanceOf[RoundService.Created]) === 1
      results.map(_.get.imagesAdded).sum === 20
      rounds(contest).size === 1
      SelectionJdbc.imageCountByRound(rounds(contest).head.getId) === 20
      selectionRows(rounds(contest).head.getId).size === 40
    }

    "fill a round once from a concurrent resubmit and \"Distribute new files\"" in {
      val (contest, jurors) = setUp()
      val form = token()
      new RoundService(new FlakyDistributor(failures = 1), Round)
        .submit(newRound(contest, "Round 1"), jurors, form) must throwA[RoundService.RoundDistributionFailed]
      val failed = rounds(contest).head

      implicit val ec: ExecutionContext = ExecutionContext.global
      val resubmit = Future(service.submit(newRound(contest, "Round 1"), jurors, form).imagesAdded)
      val newFiles = Future(service.distributeNewImages(failed.getId))
      Await.result(resubmit, 1.minute) + Await.result(newFiles, 1.minute) === 20
      rounds(contest).size === 1
      selectionRows(failed.getId).size === 40
    }

    "refuse a form token of another contest's round" in {
      val (contest, jurors) = setUp()
      val form = token()
      service.submit(newRound(contest, "Round 1"), jurors, form)
      implicit val other: ContestJury = createContests(11).head
      val otherJurors = createUsers("jury", 4).map(_.getId)
      service.submit(newRound(other, "Round 1"), otherJurors, form) must throwA[IllegalArgumentException]
      rounds(other) must beEmpty
    }

    "create a round each time without a token (scripts, the API)" in {
      val (contest, jurors) = setUp()
      service.createNewRound(newRound(contest, "Round 1").copy(distribution = 1), jurors)
      service.createNewRound(newRound(contest, "Round 1").copy(distribution = 1), jurors)
      rounds(contest).size === 2
    }

    "not take another contest's round as a previous round" in {
      val (contest, jurors) = setUp()
      val other = createContests(11).head
      val foreign = roundDao.create(Round(None, 1, Some("Foreign"), other.getId, active = true))
      service.createNewRound(
        newRound(contest, "Round 2").copy(previous = Some(foreign.getId.toString)),
        jurors
      ) must throwA[IllegalArgumentException]
      roundDao.findById(foreign.getId).map(_.active) === Some(true)
      roundDao.findByContest(contest.getId) must beEmpty
    }
  }

  "distributeNewImages" should {

    "fill a round whose first distribution failed" in {
      val (contest, jurors) = setUp()
      val failed = failedRound(contest, "Round 1", jurors)
      service.distributeNewImages(failed.getId) === 20
      SelectionJdbc.imageCountByRound(failed.getId) === 20
    }

    "restore the previous rounds' state afterwards" in {
      val (contest, jurors) = setUp()
      val previous = service.createNewRound(newRound(contest, "Round 1"), jurors)
      Round.setActive(previous.getId, active = true)
      val next = roundDao.create(
        newRound(contest, "Round 2").copy(number = 2, previous = Some(previous.getId.toString)))
      next.addUsers(jurors.map(id => RoundUser(next.getId, id, "jury", active = true)))
      service.distributeNewImages(next.getId) === 20
      roundDao.findById(previous.getId).map(_.active) === Some(true)
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
