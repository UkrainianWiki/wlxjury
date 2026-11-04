package db.scalikejdbc

import db.scalikejdbc.RoundCounts.{Counts, RecountStep}
import org.intracer.wmua.{ContestJury, CriteriaRate, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.counts.RoundCountsRecompute
import services.distribution.RoundDistributionHandler

import java.util.concurrent.atomic.{AtomicBoolean, AtomicInteger}
import java.util.concurrent.{ConcurrentLinkedQueue, CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._
import scala.util.Random

/** The recompute against juror votes (P2a, V89; docs/plans/2026-11-02-round-images-two-step.md
  * §16): a window waits for a vote that has marked its file, then reads it; a vote waits for a
  * window holding its file's entry, and leaves its mark for the next pass; and 20 jurors voting
  * on one file while the recompute runs lose no vote (invariant 5 afterwards), with no failure
  * beyond the deadlocks the vote's retry absorbs. Waits observed in INNODB_TRX (no sleeps).
  */
class RoundCountsLockSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false, window = 2))

  /** As RoundImageLockSpec's: "LOCK WAIT" once a transaction waits for a lock, 20 s at most. */
  private def lockWait(waiter: Future[_]): String = {
    val url = DB.readOnly(implicit s => s.connection.getMetaData.getURL).takeWhile(_ != '?')
    val root = java.sql.DriverManager.getConnection(url, "root", SharedTestDb.password)
    try {
      def transactions = {
        val rs = root.createStatement().executeQuery(
          "SELECT trx_mysql_thread_id, trx_state, trx_query FROM information_schema.INNODB_TRX")
        Iterator.continually(rs).takeWhile(_.next()).map(r => s"${r.getLong(1)} ${r.getString(2)} ${r.getString(3)}").toList
      }
      val deadline = 20.seconds.fromNow
      var outcome = Option.empty[String]
      while (outcome.isEmpty) {
        val now = transactions
        if (now.exists(_.contains(" LOCK WAIT "))) outcome = Some("LOCK WAIT")
        else if (waiter.isCompleted) outcome = Some(s"done without waiting: ${waiter.value}")
        else if (deadline.isOverdue()) outcome = Some(s"no lock wait in 20 s: $now")
        else Thread.sleep(200)
      }
      outcome.get
    } finally root.close()
  }

  private def withPool[A](threads: Int = 2)(body: ExecutionContext => A): A = {
    val pool = Executors.newFixedThreadPool(threads)
    try body(ExecutionContext.fromExecutor(pool))
    finally pool.shutdownNow(): Unit
  }

  private def images(ids: Long*): Seq[Image] = {
    val imgs = ids.map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i")))
    imageDao.batchInsert(imgs)
    imgs
  }

  private def entries(roundId: Long): Seq[Long] =
    DB.readOnly(implicit s => sql"SELECT page_id FROM round_image_dirty WHERE round_id = $roundId ORDER BY page_id"
      .map(_.long(1)).list())

  /** A binary round of files 1 and 2, each with an unrated vote of each juror, its counts clean. */
  private def setUp(jurorCount: Int)(implicit contest: ContestJury): (Long, Seq[User]) = {
    val jurors = createUsers("jury", (1 to jurorCount): _*)
    val r = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
    images(1L, 2L)
    VoteJdbc.batchInsert(Seq(1L, 2L).flatMap(p => jurors.map(j => Vote(p, j.getId, r, 0))))
    recompute.untilClean()
    (r, jurors)
  }

  "a recompute window" should {
    "wait for a vote that has marked its file, and read it once it commits" in withPool() { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1, j2)) = setUp(2)
      VoteJdbc.rate(1L, j2.getId, r, -1) // the file's entry, committed
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // juror 1's vote, its entry's key locked (shared: the entry is there), not committed
      val vote = Future(DB.localTx { implicit s =>
        val n = VoteJdbc.rate(1L, j1.getId, r, 1)
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        n
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val window = Future(WriteTx.write(implicit s => RoundCounts.fileWindow(r)))
      (lockWait(window) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(vote, 30.seconds) === 1) and (Await.result(window, 30.seconds).cleared === 1) and
          (RoundCounts.of(r, 1L) === Some(Counts(2, 2, 1, 1, 0))) and (entries(r) must beEmpty) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }

    "of a full recount wait for a vote whose new entry it meets, and read it" in withPool() { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1)) = setUp(1)
      DB.localTx { implicit s => RoundWrites.lock(Seq(r)); RoundCounts.markRounds(Seq(r)) }
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      val vote = Future(DB.localTx { implicit s =>
        val n = VoteJdbc.rate(2L, j1.getId, r, 1)
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        n
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val window = Future(WriteTx.write(implicit s =>
        RoundCounts.roundWindow(r, None, None, RoundDistributionHandler.dedupeKey(r))))
      (lockWait(window) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(vote, 30.seconds) === 1) and
          (Await.result(window, 30.seconds) must beLike { case RecountStep.Ran(w, _) => w.cleared === 1 }) and
          (RoundCounts.of(r, 2L) === Some(Counts(1, 1, 1, 0, 1))) and (RoundCounts.isClean(r) must beTrue) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
    }
  }

  "a juror's vote" should {
    "wait for a window holding its file's entry, and leave its mark for the next pass" in withPool() { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1, j2)) = setUp(2)
      VoteJdbc.rate(1L, j2.getId, r, 1)
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // a window that has locked the entry, counted juror 2's vote and deleted the entry, not committed
      val window = Future(DB.localTx { implicit s =>
        val w = RoundCounts.fileWindow(r)
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        w
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val vote = Future(VoteJdbc.rate(1L, j1.getId, r, -1))
      (lockWait(vote) === "LOCK WAIT") and {
        go.countDown()
        val w = Await.result(window, 30.seconds)
        (Await.result(vote, 30.seconds) === 1) and (w.cleared === 1) and
          // the window read the votes before juror 1's: its file is marked again
          (RoundCounts.of(r, 1L) === Some(Counts(2, 1, 1, 0, 1))) and (entries(r) === Seq(1L)) and {
            recompute.untilClean()
            (RoundCounts.of(r, 1L) === Some(Counts(2, 2, 1, 1, 0))) and (RoundImageInvariants.violations(r) must beEmpty)
          }
      }
    }
  }

  /** Server deadlocks so far (the status counter, global). */
  private def deadlocks(): Long =
    DB.readOnly(implicit s => sql"SHOW GLOBAL STATUS LIKE 'Innodb_deadlocks'".map(_.long(2)).single()).getOrElse(0L)

  "20 jurors voting on one file while the recompute runs" should {
    "lose no vote: the counts equal the votes afterwards, with no failure but retried deadlocks" in {
      implicit val contest: ContestJury = createContests(10).head
      // 24 connections, enough for 20 voters, the recompute and the checks at once
      ConnectionPool.singleton(HikariTestPool(SharedTestDb.jdbcUrl, SharedTestDb.username, SharedTestDb.password,
        name = "counts-concurrency", minimumIdle = 2, maximumPoolSize = 24))
      try withPool(22) { implicit ec =>
        val (binary, jurors) = setUp(20)
        val criteriaRound = roundDao.create(Round(None, 2, None, contest.getId, rates = Round.ratesById(10),
          criterionIds = Seq(1L, 2L), active = true)).getId
        VoteJdbc.batchInsert(jurors.map(j => Vote(1L, j.getId, criteriaRound, 0)))
        recompute.untilClean()
        val criteriaVotes = jurors.map(j => VoteJdbc.findBy(1L, j.getId, criteriaRound).get.getId)
        val before = deadlocks()
        val failures = new ConcurrentLinkedQueue[String]()
        val votesCast = new AtomicInteger()
        val stop = new AtomicBoolean(false)
        val ticks = new AtomicInteger()
        val background = Future {
          while (!stop.get()) { recompute.runOnce(); ticks.incrementAndGet() }
        }
        val voters = jurors.zip(criteriaVotes).zipWithIndex.map { case ((j, criteriaVote), i) =>
          Future {
            val rnd = new Random(1000 + i)
            (1 to 40).foreach { _ =>
              try {
                if (rnd.nextBoolean()) VoteJdbc.rate(1L, j.getId, binary, rnd.nextInt(3) - 1)
                else CriteriaRate.updateRate(criteriaVote, 1L + rnd.nextInt(2), rnd.nextInt(11))
                votesCast.incrementAndGet()
              } catch { case e: Throwable => failures.add(s"${e.getClass.getSimpleName}: ${e.getMessage}") }
            }
          }
        }
        Await.result(Future.sequence(voters), 2.minutes)
        stop.set(true)
        Await.result(background, 1.minute)
        val during = ticks.get()
        recompute.untilClean()
        val serverDeadlocks = deadlocks() - before
        // shown in the spec's output for the plan's results (§16)
        println(s"[RoundCountsLockSpec] votes ${votesCast.get}, recompute ticks during them $during, " +
          s"server deadlocks $serverDeadlocks, failures ${failures.size}")
        (failures.asScala.toList must beEmpty) and (votesCast.get === 800) and (during must be_>(0)) and
          forall(Seq(binary, criteriaRound)) { r =>
            (RoundCounts.isClean(r) must beTrue) and (RoundCounts.drift(r) must beEmpty) and
              (RoundImageInvariants.violations(r) must beEmpty)
          } and
          (RoundCounts.of(binary, 1L).map(_.assigned) === Some(20)) and
          (RoundCounts.of(criteriaRound, 1L).map(_.assigned) === Some(20))
      } finally SharedTestDb.reregisterDefault()
    }
  }
}
