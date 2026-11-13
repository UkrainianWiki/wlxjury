package db.scalikejdbc

import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestRoundServices

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** The write-path robustness batch (docs/plans/2026-11-03-write-path-simplification.md): the
  * juror vote by primary key and its locked checks (W1, h4), a round's activation against a
  * restore (h5), a distribution chunk's monument id copies against a monument change (h6), at
  * READ COMMITTED (the pools' level). Each pair ends with both committed, one having waited for
  * the other (`INNODB_TRX` `LOCK WAIT`), and the data right.
  */
class WritePathLockSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Waits, 20 s at most, until a transaction waits for a lock (as RoundImageLockSpec's). */
  private def lockWait(waiter: Future[_]): String = {
    val url = SharedTestDb.jdbcUrl.takeWhile(_ != '?')
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

  /** A transaction that runs `first`, waits for `go`, runs `then`, and commits. */
  private def held[A](first: DBSession => Unit, go: CountDownLatch, ready: CountDownLatch)(andThen: DBSession => A)(implicit
      ec: ExecutionContext
  ): Future[A] =
    Future(DB.localTx { implicit s =>
      first(s)
      ready.countDown()
      go.await(30, TimeUnit.SECONDS)
      andThen(s)
    })

  private def image(id: Long, monument: String = "07-101-0001") =
    Image(id, s"File:Image$id.jpg", Some(s"url$id"), None, 640, 480, Some(monument))

  "a juror's vote (W1, h4)" should {

    "wait for a writer holding its row by primary key, and see the vote gone" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val Seq(juror) = createUsers("jury", 1)
      val round = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      imageDao.batchInsert(Seq(image(1)))
      val vote = selectionDao.create(1L, rate = 0, juryId = juror.getId, roundId = round)
      val ready = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // a delete of the vote as the bulk writers do it: the clustered row first (by a range
      // of another index), then the delete, which needs its unique secondary entry
      val deleter = held({ implicit s => sql"SELECT id FROM vote WHERE round_id = $round FOR UPDATE".map(_.long(1)).list(); () }, go, ready) {
        implicit s => sql"DELETE FROM vote WHERE id = ${vote.getId}".update()
      }
      ready.await(30, TimeUnit.SECONDS) must beTrue
      val voting = Future(VoteJdbc.rate(1L, juror.getId, round, 1))
      (lockWait(voting) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(deleter, 30.seconds) === 1) and (Await.result(voting, 30.seconds) === 0) and
          (VoteJdbc.findBy(1L, juror.getId, round) must beNone)
      }
    }

    "rate the row that replaced its vote while it waited (a restore's moved-back vote)" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val Seq(juror) = createUsers("jury", 1)
      val round = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      imageDao.batchInsert(Seq(image(1)))
      val vote = selectionDao.create(1L, rate = 0, juryId = juror.getId, roundId = round)
      val ready = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // as ImageJdbc.moveVotesBack replaces an unrated new vote by the rated one moved back:
      // the row locked, deleted, and the moved row inserted with its own id
      val restorer = held({ implicit s => sql"SELECT id FROM vote WHERE round_id = $round FOR UPDATE".map(_.long(1)).list(); () }, go, ready) {
        implicit s =>
          sql"DELETE FROM vote WHERE id = ${vote.getId}".update()
          sql"""INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (1, ${juror.getId}, $round, 3)"""
            .updateAndReturnGeneratedKey()
      }
      ready.await(30, TimeUnit.SECONDS) must beTrue
      val voting = Future(VoteJdbc.rate(1L, juror.getId, round, 1))
      (lockWait(voting) === "LOCK WAIT") and {
        go.countDown()
        val restored = Await.result(restorer, 30.seconds)
        (restored !== vote.getId) and (Await.result(voting, 30.seconds) === 1) and
          (VoteJdbc.findBy(1L, juror.getId, round).map(v => v.getId -> v.rate) === Some(restored -> 1))
      }
    }

    "wait for the juror's stop on the round, and be refused" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val Seq(juror) = createUsers("jury", 1)
      val round = roundDao.create(Round(None, 1, None, contest.getId, active = true)).getId
      imageDao.batchInsert(Seq(image(1)))
      selectionDao.create(1L, rate = 0, juryId = juror.getId, roundId = round)
      sql"INSERT INTO round_user (round_id, user_id, role, active) VALUES ($round, ${juror.getId}, 'jury', TRUE)"
        .update.apply()(AutoSession)
      val ready = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      val stop = held({ implicit s => sql"UPDATE round_user SET active = FALSE WHERE round_id = $round".update(); () }, go, ready)(_ => ())
      ready.await(30, TimeUnit.SECONDS) must beTrue
      val voting = Future(VoteJdbc.rate(1L, juror.getId, round, 1))
      (lockWait(voting) === "LOCK WAIT") and {
        go.countDown()
        Await.result(stop, 30.seconds)
        (Await.result(voting, 30.seconds) === 0) and (VoteJdbc.findBy(1L, juror.getId, round).map(_.rate) === Some(0))
      }
    }
  }

  "a round's activation (h5)" should {
    "wait for a restore of a deleted file, and leave its votes in the round" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val round = roundDao.create(Round(None, 1, None, contest.getId, active = false)).getId
      imageDao.batchInsert(Seq(image(1)))
      jurors.foreach(j => selectionDao.create(1L, rate = 0, juryId = j.getId, roundId = round))
      // deleted on Commons while the round was closed: its votes stay until the round opens
      sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id = 1".update.apply()(AutoSession)
      val ready = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // DeletedFiles.restore's lock and clear, held
      val restore = held({ implicit s => ImageJdbc.lockFiles(Seq(1L)); ImageJdbc.clearDeleted(Seq(1L)) }, go, ready)(_ => ())
      ready.await(30, TimeUnit.SECONDS) must beTrue
      val opening = Future(Round.setActiveGuarded(round, active = true))
      (lockWait(opening) === "LOCK WAIT") and {
        go.countDown()
        Await.result(restore, 30.seconds)
        (Await.result(opening, 30.seconds) must beTrue) and
          (VoteJdbc.byRound(round).size === 2) and
          (RoundImageJdbc.find(round, 1L).map(_.status) === Some(RoundImageJdbc.Status.In)) and
          (RoundImageInvariants.violations(round) must beEmpty)
      }
    }
  }

  "a distribution chunk (h6)" should {
    "wait for a monument id change of its file, and copy the new id" in withPool { implicit ec =>
      implicit val contest: ContestJury = createContests(10).head
      val jurors = createUsers("jury", 1, 2)
      val images = (1L to 3L).map(i => image(i))
      imageDao.batchInsert(images)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
      val service = TestRoundServices.roundService()
      val round = service.createNewRound(Round(None, 1, Some("R"), contest.getId, distribution = 0, active = true),
        jurors.map(_.getId)).getId
      TestVoteWrites.removeImage(2L, round) // "Distribute new files" adds it back
      val ready = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // ImageJdbc.updateMonumentIdIn on file 2 (in no round now), held after the image's update
      val change = held({ implicit s =>
        RoundWrites.lockForFiles(Seq(2L))
        sql"UPDATE images SET monument_id = '46-101-0002' WHERE page_id = 2".update(); ()
      }, go, ready)(implicit s => ImageJdbc.updateMonumentIdIn(2L, "46-101-0002"))
      ready.await(30, TimeUnit.SECONDS) must beTrue
      val distribute = Future(service.distributeNewImages(round))
      (lockWait(distribute) === "LOCK WAIT") and {
        go.countDown()
        Await.result(change, 30.seconds)
        (Await.result(distribute, 60.seconds) === 1) and
          (VoteJdbc.byRound(round).filter(_.pageId == 2L).map(_.monumentId).distinct === Seq(Some("46-101-0002"))) and
          (RoundImageInvariants.violations(round) must beEmpty)
      }
    }
  }
}
