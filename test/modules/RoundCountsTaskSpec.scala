package modules

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.Configuration
import services.counts.RoundCountsRecompute

import java.util.concurrent.atomic.AtomicInteger
import scala.concurrent.duration._

/** The counts' timer (P2a, [[RoundCountsTimer]]): real ticks in the background bring a round's
  * counts in line; a tick that throws, also an error beyond NonFatal, doesn't stop the next
  * one; and `roundCounts.window` must be at least 1.
  */
class RoundCountsTaskSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Polls `done` every 50 ms, `timeout` at most. */
  private def eventually(timeout: FiniteDuration)(done: => Boolean): Boolean = {
    val deadline = timeout.fromNow
    var ok = done
    while (!ok && deadline.hasTimeLeft()) { Thread.sleep(50); ok = done }
    ok
  }

  "the counts' timer" should {
    "recount a dirty round in the background, with real ticks" in {
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val r = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      imageDao.batchInsert(Seq(Image(1L, "File:Image1.jpg", None, None, 640, 480, Some("07-101-0001"))))
      VoteJdbc.batchInsert(jurors.map(j => Vote(1L, j.getId, r, 1)))
      val dirtyBefore = RoundCounts.isClean(r)
      val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false))
      val timer = new RoundCountsTimer(100.millis, () => { recompute.runOnce(); () }, name = "round-counts-spec")
      val clean = try eventually(15.seconds)(RoundCounts.isClean(r)) finally timer.stop(1.second)
      (dirtyBefore must beFalse) and (clean must beTrue) and
        (RoundCounts.of(r, 1L) === Some(RoundCounts.Counts(2, 2, 2, 0, 2))) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "run the next tick after one that throws, an Exception or an Error that isn't the VM's" in {
      val calls = new AtomicInteger()
      val ran = new AtomicInteger()
      val timer = new RoundCountsTimer(50.millis, () => calls.incrementAndGet() match {
        case 1 => throw new IllegalStateException("tick 1")
        case 2 => throw new NoClassDefFoundError("tick 2") // a LinkageError: not NonFatal
        case _ => ran.incrementAndGet(); ()
      }, name = "round-counts-spec")
      val later = try eventually(10.seconds)(ran.get >= 2) finally timer.stop(1.second)
      (later must beTrue) and (calls.get must be_>=(4))
    }

    "rethrow a VirtualMachineError from a tick" in {
      RoundCountsTimer.guarded(throw new StackOverflowError("deep")) must throwA[StackOverflowError]
    }
  }

  "roundCounts.window" should {
    "be refused below 1, and default to 2,000" in {
      def settings(window: Int) = RoundCountsRecompute.Settings.from(Configuration("roundCounts.window" -> window))
      (settings(0) must throwA[IllegalArgumentException]) and (settings(-5) must throwA[IllegalArgumentException]) and
        (settings(1).window === 1) and (RoundCountsRecompute.Settings.from(Configuration.empty).window === 2000)
    }
  }
}
