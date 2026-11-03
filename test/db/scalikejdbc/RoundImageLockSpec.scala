package db.scalikejdbc

import db.scalikejdbc.RoundImageJdbc.{AddedHow, Origin}
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** round_image's writers in the lock order (`round → images → vote → criteria_rate →
  * vote_aside → round_image`) against the transactions that take a round or an image first:
  * each pair ends with both committed, one having waited for the other, no deadlock. With the
  * order broken (the round or the image locked after the votes) each pair deadlocked.
  */
class RoundImageLockSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Waits, 20 seconds at most, until a transaction waits for a lock: "LOCK WAIT", or what
    * happened instead (as CriteriaRateSpec's: INNODB_TRX read as the container's root, on the
    * pool's server, polled every 200 ms).
    */
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

  private def withPool[A](body: ExecutionContext => A): A = {
    val pool = Executors.newFixedThreadPool(2)
    try body(ExecutionContext.fromExecutor(pool))
    finally pool.shutdownNow(): Unit
  }

  private def images(ids: Long*): Seq[Image] = {
    val imgs = ids.map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i")))
    imageDao.batchInsert(imgs)
    imgs
  }

  "a distribution chunk's vote insert" should {
    "wait for a change of the round's criteria holding the round, and both commit" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val round = roundDao.create(Round(None, 1, Some("Criteria"), contest.getId, rates = Round.ratesById(10),
        criterionIds = Seq(1L, 2L), active = true))
      val votes = images(1L, 2L, 3L).flatMap(i => jurors.map(j => Vote(i.pageId, j.getId, round.getId, 0)))
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // RoundCriterion.update: the round for update, then (here once the chunk waits) its votes
      val change = Future(DB.localTx { implicit s =>
        sql"SELECT id FROM round WHERE id = ${round.getId} FOR UPDATE".map(_.long(1)).single()
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        RoundCriterion.updateIn(round.getId, Seq(2L, 1L))
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val chunk = Future(VoteJdbc.batchInsert(votes, Origin(AddedHow.Fill, Some(1L))))
      (lockWait(chunk) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(change, 30.seconds) must beRight(true)) and
          (Await.result(chunk, 30.seconds) === (())) and
          (RoundCriterion.idsOf(round.getId) === Seq(2L, 1L)) and
          (RoundImageJdbc.byRound(round.getId).map(_.pageId) === Seq(1L, 2L, 3L)) and
          (RoundImageInvariants.violations(round.getId) must beEmpty)
      }
    }
  }

  "moving a file to another round" should {
    "wait for a monument id change holding the image, and both commit" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val r1 = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      val r2 = roundDao.create(Round(None, 2, None, contest.getId, active = true)).getId
      images(1L)
      jurors.foreach(j => selectionDao.create(1L, rate = 0, juryId = j.getId, roundId = r1))
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // updateMonumentIdIn: the image's row, then (here once the move waits) its votes
      val change = Future(DB.localTx { implicit s =>
        sql"UPDATE images SET monument_id = '46-101-0001' WHERE page_id = 1".update()
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        ImageJdbc.updateMonumentIdIn(1L, "46-101-0001")
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val move = Future(VoteJdbc.setRound(1L, r1, r2))
      (lockWait(move) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(change, 30.seconds) === (())) and (Await.result(move, 30.seconds) === (())) and
          (RoundImageJdbc.find(r1, 1L) must beNone) and
          (RoundImageJdbc.find(r2, 1L).flatMap(_.monumentId) === Some("46-101-0001")) and
          (RoundImageInvariants.violations(r1) must beEmpty) and (RoundImageInvariants.violations(r2) must beEmpty)
      }
    }
  }
}
