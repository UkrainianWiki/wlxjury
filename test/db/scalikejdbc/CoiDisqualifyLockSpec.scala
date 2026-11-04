package db.scalikejdbc

import db.scalikejdbc.RoundImageJdbc.{AddedHow, Origin}
import org.intracer.wmua.{ContestJury, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.distribution.RoundDistributionHandler
import services.jobs.{JobJdbc, NewJob}

import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** Disqualifying conflicts of interest (P1b, [[RoundConflicts]]) against the writers it meets
  * on a stopped round: a juror's vote already in flight (row-level, never the round) and a
  * distribution chunk (the round first). Each pair ends with both committed and the round's
  * rows consistent, one having waited for the other (INNODB_TRX's LOCK WAIT, no sleeps).
  */
class CoiDisqualifyLockSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** As RoundImageLockSpec's: waits, 20 seconds at most, until a transaction waits for a lock. */
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

  /** Files 1, 2 and 4 by the board member, 3 by nobody; a stopped round with a juror's
    * votes on 1-3: the round, the juror, the admin.
    */
  private def setUp(): (Long, User, User) = {
    implicit val contest: ContestJury = createContests(10).head
    imageDao.batchInsert((1L to 4L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i"),
      author = Option.when(i != 3)("Board member"))))
    val juror = User.create(User("Juror", "juror@example.com", None, Set("jury"), Some("h"), contest.id))
    val admin = User.create(User("Admin", "admin@example.com", None, Set("admin"), Some("h"), contest.id))
    User.create(User("Board", "", None, Set(User.BOARD_ROLE), Some("h"), contest.id, wikiAccount = Some("Board member")))
    val round = roundDao.create(Round(None, 1, Some("R"), contest.getId, active = false)).getId
    (1L to 3L).foreach(p => selectionDao.create(p, rate = 0, juryId = juror.getId, roundId = round))
    (round, juror, admin)
  }

  private def key(round: Long) = RoundDistributionHandler.dedupeKey(round)

  private def asideRate(round: Long, pageId: Long): Option[Int] = DB.readOnly { implicit s =>
    sql"SELECT rate FROM vote_aside WHERE round_id = $round AND page_id = $pageId".map(_.int(1)).single()
  }

  "a requalify" should {

    "wait for a restore from Commons holding the file (round → images → vote), and both commit" in withPool { implicit ec =>
      val (round, juror, admin) = setUp()
      RoundConflicts.disqualify(round, admin, key(round))
      // file 1 deleted on Commons, then found again by an import
      DB.localTx(implicit s => services.imports.DeletedFiles.markDeleted(Seq(1L)))
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      // DeletedFiles.restore in an import step: the file's row for update, its votes locked
      val restore = Future(DB.localTx { implicit s =>
        val restored = services.imports.DeletedFiles.restore(Seq(1L))
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
        restored
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val back = Future(RoundConflicts.requalify(round, Seq(1L), RoundConflicts.RequalifyReason.ConflictEnded, admin,
        key(round)))
      (lockWait(back) === "LOCK WAIT") and {
        go.countDown()
        (Await.result(restore, 30.seconds) must beEmpty) and
          (Await.result(back, 30.seconds) === RoundConflicts.Outcome.Requalified(Seq(1L))) and
          (ImageJdbc.isDeleted(1L) must beFalse) and
          (VoteJdbc.findBy(1L, juror.getId, round).map(_.rate) === Some(0)) and
          (RoundImageJdbc.find(round, 1L).map(_.status) === Some("in")) and
          (RoundImageInvariants.violations(round) must beEmpty)
      }
    }
  }

  "a disqualify" should {

    "wait for a juror's vote in flight on the file, move the voted row aside, and a requalify bring the rate back" in
      withPool { implicit ec =>
        val (round, juror, admin) = setUp()
        val held = new CountDownLatch(1)
        val go = new CountDownLatch(1)
        // VoteJdbc.rate in its transaction: the vote row by its primary key, then the rate
        val vote = Future(DB.localTx { implicit s =>
          val rated = VoteJdbc.rate(1L, juror.getId, round, 5)
          held.countDown()
          go.await(30, TimeUnit.SECONDS)
          rated
        })
        held.await(30, TimeUnit.SECONDS) must beTrue
        val dq = Future(RoundConflicts.disqualify(round, admin, key(round)))
        (lockWait(dq) === "LOCK WAIT") and {
          go.countDown()
          (Await.result(vote, 30.seconds) === 1) and
            (Await.result(dq, 30.seconds) must beLike {
              case RoundConflicts.Outcome.Disqualified(files) => files.map(_._1) === Seq(1L, 2L)
            }) and
            (asideRate(round, 1L) === Some(5)) and (RoundImageInvariants.violations(round) must beEmpty) and
            // the event (V88), in the disqualify's transaction after the rows: was `in`
            (RoundImageEventJdbc.ofFile(round, 1L).map(e => (e.action, e.priorStatus, e.newStatus, e.by)) ===
              Seq(("disqualify", "in", Some("dq"), admin.id))) and
            (RoundConflicts.requalify(round, Seq(1L), RoundConflicts.RequalifyReason.MistakenMatch, admin, key(round)) ===
              RoundConflicts.Outcome.Requalified(Seq(1L))) and
            (VoteJdbc.findBy(1L, juror.getId, round).map(_.rate) === Some(5)) and
            (RoundImageEventJdbc.ofFile(round, 1L).map(e => (e.action, e.code, e.newStatus)) ===
              Seq(("disqualify", "coi", Some("dq")), ("requalify", "mistaken_match", Some("in")))) and
            (RoundImageInvariants.violations(round) must beEmpty)
        }
      }

    "leave a juror's vote that comes after it nothing to change" in {
      val (round, juror, admin) = setUp()
      RoundConflicts.disqualify(round, admin, key(round))
      (VoteJdbc.rate(1L, juror.getId, round, 5) === 0) and (asideRate(round, 1L) === Some(0)) and
        (RoundImageInvariants.violations(round) must beEmpty)
    }

    "wait for a distribution chunk holding the round, then see its file: disqualified with the others" in
      withPool { implicit ec =>
        val (round, juror, admin) = setUp()
        val held = new CountDownLatch(1)
        val go = new CountDownLatch(1)
        // a chunk's vote insert: the round for update first (RoundWrites), then the votes, then round_image
        val chunk = Future(DB.localTx { implicit s =>
          VoteJdbc.batchInsert(Seq(Vote(4L, juror.getId, round, 0)), Origin(AddedHow.NewFiles, Some(1L)))
          held.countDown()
          go.await(30, TimeUnit.SECONDS)
        })
        held.await(30, TimeUnit.SECONDS) must beTrue
        val dq = Future(RoundConflicts.disqualify(round, admin, key(round)))
        (lockWait(dq) === "LOCK WAIT") and {
          go.countDown()
          Await.result(chunk, 30.seconds)
          (Await.result(dq, 30.seconds) must beLike {
            case RoundConflicts.Outcome.Disqualified(files) => files.map(_._1) === Seq(1L, 2L, 4L)
          }) and
            (RoundImageJdbc.byRound(round).map(r => r.pageId -> r.status) ===
              Seq(1L -> "dq", 2L -> "dq", 3L -> "in", 4L -> "dq")) and
            // the chunk's file too, recorded as `in` before
            (RoundImageEventJdbc.byRound(round).map(e => e.pageId -> e.priorStatus).sorted ===
              Seq(1L -> "in", 2L -> "in", 4L -> "in")) and
            (RoundImageInvariants.violations(round) must beEmpty)
        }
      }

    "wait for a distribution chunk whose job is active, then refuse, writing nothing" in withPool { implicit ec =>
      val (round, juror, admin) = setUp()
      DB.autoCommit { implicit s =>
        JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(10L), Some(RoundDistributionHandler.SubjectType),
          Some(round), Some(key(round))), owner = None)
      }
      val held = new CountDownLatch(1)
      val go = new CountDownLatch(1)
      val chunk = Future(DB.localTx { implicit s =>
        VoteJdbc.batchInsert(Seq(Vote(4L, juror.getId, round, 0)), Origin(AddedHow.NewFiles, Some(1L)))
        held.countDown()
        go.await(30, TimeUnit.SECONDS)
      })
      held.await(30, TimeUnit.SECONDS) must beTrue
      val dq = Future(RoundConflicts.disqualify(round, admin, key(round)))
      (lockWait(dq) === "LOCK WAIT") and {
        go.countDown()
        Await.result(chunk, 30.seconds)
        (Await.result(dq, 30.seconds) === RoundConflicts.Outcome.Distributing) and
          (RoundImageJdbc.disqualified(round) must beEmpty) and (RoundImageEventJdbc.byRound(round) must beEmpty) and
          (VoteJdbc.byRound(round).map(_.pageId).toSet === Set(1L, 2L, 3L, 4L)) and
          (RoundImageInvariants.violations(round) must beEmpty)
      }
    }
  }
}
