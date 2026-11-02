package org.intracer.wmua

import db.scalikejdbc._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.imports.DeletedFiles

import java.sql.SQLException
import java.util.concurrent.{CountDownLatch, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}

/** A criteria round's votes ([[CriteriaRate]]): a criterion vote is one transaction that
  * locks the vote and sets its rate to the sum of its criteria's, and no vote delete or move
  * leaves criteria rates without their vote (`criteria_rate.selection` has no foreign key).
  *
  * Service-level: [[CriteriaRate.updateRate]] and the vote deletes open their own
  * transactions, so the tables are truncated before each example (CLAUDE.md).
  */
class CriteriaRateSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private var nextRateId = 0L
  // votes aside keep the ids they had in selection: theirs here are apart from selection's
  private var nextAsideId = 1000L

  private def round(contest: ContestJury, number: Int = 1): Round =
    roundDao.create(Round(None, number, contestId = contest.getId, rates = Round.ratesById(10), active = true))

  /** A vote with a rate per criterion (criteria 1, 2, ...), `rates` of them, and the vote's rate
    * their sum, as [[CriteriaRate.updateRate]] keeps it.
    */
  private def vote(pageId: Long, juror: User, round: Round, rates: Seq[Int] = Seq(0, 0),
      table: String = "selection"): Long = {
    insertImagesFor(pageId)
    val sum = rates.filter(_ > 0).sum
    val id = DB.autoCommit { implicit s =>
      if (table == "selection")
        sql"INSERT INTO selection (page_id, jury_id, round_id, rate) VALUES ($pageId, ${juror.getId}, ${round.getId}, $sum)"
          .updateAndReturnGeneratedKey.apply()
      else {
        nextAsideId += 1
        sql"""INSERT INTO selection_deleted (id, page_id, jury_id, round_id, rate)
              VALUES ($nextAsideId, $pageId, ${juror.getId}, ${round.getId}, $sum)""".update()
        nextAsideId
      }
    }
    addRates(id, rates)
    id
  }

  private def addRates(voteId: Long, rates: Seq[Int]): Unit = DB.autoCommit { implicit s =>
    rates.zipWithIndex.foreach { case (rate, i) =>
      nextRateId += 1
      sql"INSERT INTO criteria_rate (id, selection, criteria, rate) VALUES ($nextRateId, $voteId, ${i + 1}, $rate)"
        .update()
    }
  }

  /** The vote's criteria rates, by criterion. */
  private def ratesOf(voteId: Long): Map[Long, Int] =
    DB.readOnly { implicit s =>
      sql"SELECT criteria, rate FROM criteria_rate WHERE selection = $voteId".map(rs => rs.long(1) -> rs.int(2)).list()
    }.toMap

  private def rateOf(voteId: Long, table: String = "selection"): Option[Int] =
    DB.readOnly(implicit s => SQL(s"SELECT rate FROM $table WHERE id = ?").bind(voteId).map(_.int(1)).single())

  /** criteria_rate rows whose vote is in neither selection nor selection_deleted. */
  private def orphans: Long = DB.readOnly { implicit s =>
    sql"""SELECT COUNT(*) FROM criteria_rate cr
          WHERE NOT EXISTS (SELECT 1 FROM selection s WHERE s.id = cr.selection)
            AND NOT EXISTS (SELECT 1 FROM selection_deleted d WHERE d.id = cr.selection)"""
      .map(_.long(1)).single().get
  }

  /** Waits, 20 seconds at most, until a transaction waits for a lock (the specs run one at
    * a time on their own database): "LOCK WAIT", or what happened instead (`waiter` done, or
    * the transactions then). INNODB_TRX needs the PROCESS privilege, which the app's user
    * hasn't: read as the container's root (Testcontainers gives it the same password).
    */
  private def lockWait(waiter: Future[_]): String = {
    val root = java.sql.DriverManager.getConnection(SharedTestDb.jdbcUrl, "root", SharedTestDb.password)
    try {
      def transactions = {
        val rs = root.createStatement().executeQuery(
          "SELECT trx_mysql_thread_id, trx_state, trx_query FROM information_schema.INNODB_TRX")
        Iterator.continually(rs).takeWhile(_.next())
          .map(r => s"${r.getLong(1)} ${r.getString(2)} ${r.getString(3)}").toList
      }
      val deadline = 20.seconds.fromNow
      var outcome = Option.empty[String]
      while (outcome.isEmpty) {
        val now = transactions
        if (now.exists(_.contains(" LOCK WAIT "))) outcome = Some("LOCK WAIT")
        else if (waiter.isCompleted) outcome = Some(s"done without waiting: ${waiter.value}")
        else if (deadline.isOverdue()) outcome = Some(s"no lock wait in 20 s: $now")
        // InnoDB refreshes INNODB_TRX's cache only when it wasn't read for 100 ms: polled more
        // often, it shows the rows of the first read for good (a lock wait never seen)
        else Thread.sleep(200)
      }
      outcome.get
    } finally root.close()
  }

  private def ids(table: String): Set[Long] =
    DB.readOnly(implicit s => SQL(s"SELECT id FROM $table").map(_.long(1)).list()).toSet

  private def setUp(): (ContestJury, Round, Seq[User]) = {
    implicit val session: DBSession = AutoSession
    implicit val contest: ContestJury = createContests(10).head
    val jurors = createUsers(1, 2)
    (contest, round(contest), jurors)
  }

  "a criterion vote" should {

    "set the criterion and the vote's rate to the sum of its criteria's positive rates" in {
      val (_, round, Seq(juror, _)) = setUp()
      val id = vote(1, juror, round)

      CriteriaRate.updateRate(id, 1, 3) must beTrue
      CriteriaRate.updateRate(id, 2, 4) must beTrue
      ratesOf(id) === Map(1L -> 3, 2L -> 4)
      rateOf(id) === Some(7)
      // the stored rate again: found (the driver counts rows matched)
      CriteriaRate.updateRate(id, 2, 4) must beTrue
      CriteriaRate.updateRate(id, 1, 0) must beTrue
      rateOf(id) === Some(4)
    }

    "change nothing for a criterion the vote hasn't, or a vote gone" in {
      val (_, round, Seq(juror, _)) = setUp()
      val id = vote(1, juror, round, Seq(3, 4))

      CriteriaRate.updateRate(id, 3, 5) must beFalse
      CriteriaRate.updateRate(id + 1000, 1, 5) must beFalse
      ratesOf(id) === Map(1L -> 3, 2L -> 4)
      rateOf(id) === Some(7)
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM criteria_rate".map(_.long(1)).single()) === Some(2L)
    }

    "wait for another criterion's vote on the same vote, and then sum both" in {
      val (_, round, Seq(juror, _)) = setUp()
      val id = vote(1, juror, round)
      val pool = Executors.newFixedThreadPool(2)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      try {
        val firstVoted = new CountDownLatch(1)
        val commitFirst = new CountDownLatch(1)
        // criterion 1 voted, not committed yet
        val first = Future(DB.localTx { implicit s =>
          val voted = CriteriaRate.updateRateIn(id, 1, 3)
          firstVoted.countDown()
          commitFirst.await(30, TimeUnit.SECONDS)
          voted
        })
        firstVoted.await(30, TimeUnit.SECONDS) must beTrue
        val second = Future(CriteriaRate.updateRate(id, 2, 4))
        lockWait(second) === "LOCK WAIT"
        commitFirst.countDown()
        Await.result(first, 30.seconds) must beTrue
        Await.result(second, 30.seconds) must beTrue
        rateOf(id) === Some(7)
        ratesOf(id) === Map(1L -> 3, 2L -> 4)
      } finally pool.shutdownNow(): Unit
    }

    "wait for the vote's lock, held by a delete, and then change nothing, which the old code didn't" in {
      val (_, round, Seq(juror, _)) = setUp()
      val id = vote(1, juror, round, Seq(3, 4))
      val pool = Executors.newFixedThreadPool(2)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      try {
        val held = new CountDownLatch(1)
        val delete = new CountDownLatch(1)
        // the vote locked as SelectionJdbc.deleteVotes and ImageJdbc.moveVotesAside lock it,
        // then deleted with its rates
        val holder = Future(DB.localTx { implicit s =>
          sql"SELECT id FROM selection WHERE id = $id FOR UPDATE".map(_.long(1)).single()
          held.countDown()
          delete.await(30, TimeUnit.SECONDS)
          SelectionJdbc.deleteVotes(sqls"v.id = $id")
        })
        held.await(30, TimeUnit.SECONDS) must beTrue
        val voting = Future(CriteriaRate.updateRate(id, 1, 9))
        // the vote waits for the vote row, before writing its criterion: the old code's
        // UPDATE criteria_rate didn't wait for it, and wrote a rate of a vote being deleted
        lockWait(voting) === "LOCK WAIT"
        ratesOf(id) === Map(1L -> 3, 2L -> 4)
        delete.countDown()
        Await.result(holder, 30.seconds) === 1
        Await.result(voting, 30.seconds) must beFalse
        rateOf(id) must beNone
        ratesOf(id) must beEmpty
        orphans === 0
      } finally pool.shutdownNow(): Unit
    }

    "sum every vote's criteria right when its criteria are voted at once" in {
      val (_, round, Seq(juror, other)) = setUp()
      val votes = (1L to 10L).flatMap(p => Seq(vote(p, juror, round, Seq(0, 0, 0)), vote(p, other, round, Seq(0, 0, 0))))
      val pool = Executors.newFixedThreadPool(6)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      try {
        // three passes, one after another; in each, every criterion of every vote at once
        val voted = (1 to 3).flatMap { pass =>
          val results = for {
            id <- votes
            c  <- 1 to 3
          } yield Future(CriteriaRate.updateRate(id, c, (id.toInt + c + pass) % 10 + 1))
          Await.result(Future.sequence(results), 120.seconds)
        }
        voted.forall(identity) must beTrue
        forall(votes) { id =>
          val rates = ratesOf(id)
          rates === (1 to 3).map(c => c.toLong -> ((id.toInt + c + 3) % 10 + 1)).toMap
          rateOf(id) === Some(rates.values.sum)
        }
      } finally pool.shutdownNow(): Unit
    }

    "be retried once when it is a deadlock's victim, and only then" in {
      def deadlock = new SQLException("Deadlock found when trying to get lock", "40001", 1213)
      def lockWaitTimeout = new java.sql.SQLTransactionRollbackException("Lock wait timeout exceeded", "40001", 1205)
      var calls = 0
      DbErrors.retryOnDeadlock { calls += 1; if (calls == 1) throw deadlock else "voted" } === "voted"
      calls === 2

      calls = 0
      DbErrors.retryOnDeadlock[String] { calls += 1; throw deadlock } must throwA[SQLException]
      calls === 2

      calls = 0
      DbErrors.retryOnDeadlock[String] { calls += 1; throw lockWaitTimeout } must throwA[SQLException]
      calls === 1
      DbErrors.isDeadlock(new RuntimeException(deadlock)) must beTrue
      DbErrors.isDeadlock(lockWaitTimeout) must beFalse
      // the job scheduler's retries: a lock wait timeout too
      DbErrors.isTransient(lockWaitTimeout, lockWaitTimeout = true) must beTrue
      DbErrors.isTransient(lockWaitTimeout, lockWaitTimeout = false) must beFalse
      DbErrors.isTransient(new RuntimeException(deadlock), lockWaitTimeout = false) must beTrue
    }
  }

  "deleting or moving votes" should {

    "remove a file from a round with its votes' criteria rates, also those moved aside" in {
      val (_, round, Seq(juror, other)) = setUp()
      val removed = Seq(vote(1, juror, round, Seq(3, 4)), vote(1, other, round, Seq(5, 6)))
      val kept = vote(2, juror, round, Seq(1, 2))
      val aside = vote(3, juror, round, Seq(7, 8), table = "selection_deleted")

      SelectionJdbc.removeImage(1, round.getId)
      SelectionJdbc.removeImage(3, round.getId)

      ids("selection") === Set(kept)
      ids("selection_deleted") must beEmpty
      forall(removed :+ aside)(ratesOf(_) must beEmpty)
      ratesOf(kept) === Map(1L -> 1, 2L -> 2)
      orphans === 0
    }

    "remove a round's unrated votes with their criteria rates, also those moved aside" in {
      val (contest, round, Seq(juror, other)) = setUp()
      val unrated = Seq(vote(1, juror, round), vote(2, other, round), vote(3, juror, round, table = "selection_deleted"))
      val rated = Seq(vote(1, other, round, Seq(0, 2)), vote(4, juror, round, Seq(1, 0), table = "selection_deleted"))
      val another = this.round(contest, 2)
      val elsewhere = vote(1, juror, another)

      SelectionJdbc.removeUnrated(round.getId)

      ids("selection") === Set(rated.head, elsewhere)
      ids("selection_deleted") === Set(rated(1))
      forall(unrated)(ratesOf(_) must beEmpty)
      ratesOf(rated.head) === Map(1L -> 0, 2L -> 2)
      ratesOf(elsewhere) === Map(1L -> 0, 2L -> 0)
      orphans === 0
    }

    "move a file's votes to another round with their criteria rates, dropping those of a juror already there" in {
      val (contest, round, Seq(juror, other)) = setUp()
      val target = this.round(contest, 2)
      val moved = vote(1, juror, round, Seq(3, 4))
      val dropped = vote(1, other, round, Seq(5, 6))
      val there = vote(1, other, target, Seq(1, 1))

      SelectionJdbc.setRound(1, round.getId, target.getId)

      DB.readOnly(implicit s => sql"SELECT id, round_id FROM selection".map(rs => rs.long(1) -> rs.long(2)).list()).toMap ===
        Map(moved -> target.getId, there -> target.getId)
      ratesOf(moved) === Map(1L -> 3, 2L -> 4)
      ratesOf(there) === Map(1L -> 1, 2L -> 1)
      ratesOf(dropped) must beEmpty
      orphans === 0
    }

    "keep the criteria rates of votes moved aside and back for a file deleted and restored on Commons" in {
      val (_, round, Seq(juror, other)) = setUp()
      val votes = Seq(vote(1, juror, round, Seq(3, 4)), vote(1, other, round, Seq(0, 0)))

      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      ids("selection") must beEmpty
      ids("selection_deleted") === votes.toSet
      ratesOf(votes.head) === Map(1L -> 3, 2L -> 4)

      DB.localTx(implicit s => DeletedFiles.restore(Seq(1L)))
      ids("selection") === votes.toSet
      ids("selection_deleted") must beEmpty
      ratesOf(votes.head) === Map(1L -> 3, 2L -> 4)
      rateOf(votes.head) === Some(7)
      ratesOf(votes(1)) === Map(1L -> 0, 2L -> 0)
      orphans === 0
    }

    "drop the criteria rates of the votes a restore replaces or drops" in {
      val (_, round, Seq(juror, other)) = setUp()
      // aside: the juror's rated vote, the other's unrated one
      val asideRated = vote(1, juror, round, Seq(3, 4), table = "selection_deleted")
      val asideUnrated = vote(1, other, round, Seq(0, 0), table = "selection_deleted")
      // the file distributed again meanwhile: the juror's new vote unrated, the other's rated
      val newUnrated = vote(1, juror, round, Seq(0, 0))
      val newRated = vote(1, other, round, Seq(2, 0))

      DB.localTx(implicit s => ImageJdbc.restoreVotes(Seq(1L)))

      // the rated vote wins: the juror's moved back, the other's new one stays
      ids("selection") === Set(asideRated, newRated)
      ids("selection_deleted") must beEmpty
      ratesOf(asideRated) === Map(1L -> 3, 2L -> 4)
      ratesOf(newRated) === Map(1L -> 2, 2L -> 0)
      ratesOf(newUnrated) must beEmpty
      ratesOf(asideUnrated) must beEmpty
      orphans === 0
    }

    "drop the criteria rates of votes aside of a round deleted meanwhile" in {
      val (_, round, Seq(juror, _)) = setUp()
      val aside = vote(1, juror, round, Seq(3, 4), table = "selection_deleted")
      DB.autoCommit(implicit s => sql"DELETE FROM rounds WHERE id = ${round.getId}".update())

      DB.localTx(implicit s => ImageJdbc.restoreVotes(Seq(1L)))

      ids("selection") must beEmpty
      ids("selection_deleted") must beEmpty
      ratesOf(aside) must beEmpty
      orphans === 0
    }

    "drop the criteria rates of an older vote aside that moving the vote aside again replaces" in {
      val (_, round, Seq(juror, _)) = setUp()
      val older = vote(1, juror, round, Seq(3, 4), table = "selection_deleted")
      // distributed again meanwhile, and rated
      val newer = vote(1, juror, round, Seq(1, 2))

      DB.localTx(implicit s => ImageJdbc.archiveVotes(Seq(1L), Set(round.getId)))

      ids("selection") must beEmpty
      ids("selection_deleted") === Set(newer)
      ratesOf(newer) === Map(1L -> 1, 2L -> 2)
      ratesOf(older) must beEmpty
      orphans === 0
    }

    "lock a file's votes before those moved aside when restoring it, as every vote delete or move" in {
      val (_, round, Seq(juror, other)) = setUp()
      val aside = vote(1, juror, round, Seq(3, 4), table = "selection_deleted")
      val live = vote(1, other, round, Seq(1, 1))
      val pool = Executors.newFixedThreadPool(2)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      try {
        val held = new CountDownLatch(1)
        val lockAside = new CountDownLatch(1)
        // as RoundConflicts, SelectionJdbc.deleteVotes and ImageJdbc.moveVotesAside: the file's
        // votes first, then those moved aside
        val holder = Future(DB.localTx { implicit s =>
          sql"SET SESSION innodb_lock_wait_timeout = 10".execute.apply()
          try {
            sql"SELECT COUNT(*) FROM selection WHERE page_id = 1 FOR UPDATE".map(_.int(1)).single()
            held.countDown()
            lockAside.await(30, TimeUnit.SECONDS)
            sql"SELECT COUNT(*) FROM selection_deleted WHERE page_id = 1 FOR UPDATE".map(_.int(1)).single().get
          } finally sql"SET SESSION innodb_lock_wait_timeout = DEFAULT".execute.apply(): Unit
        })
        held.await(30, TimeUnit.SECONDS) must beTrue
        val restore = Future(DB.localTx(implicit s => ImageJdbc.restoreVotes(Seq(1L))))
        // the restore waits at the file's votes, holding nothing aside: the holder locks those
        // next without waiting (the restore that locked selection_deleted first deadlocked here)
        lockWait(restore) === "LOCK WAIT"
        lockAside.countDown()
        Await.result(holder, 30.seconds) === 1
        Await.result(restore, 30.seconds) === Seq(round.getId)
        ids("selection") === Set(aside, live)
        ids("selection_deleted") must beEmpty
        ratesOf(aside) === Map(1L -> 3, 2L -> 4)
        ratesOf(live) === Map(1L -> 1, 2L -> 1)
        orphans === 0
      } finally pool.shutdownNow(): Unit
    }

    "lock the rounds before the files' votes when restoring them, as leaving out conflicts of interest" in {
      val (_, round, Seq(juror, _)) = setUp()
      val aside = vote(1, juror, round, Seq(3, 4), table = "selection_deleted")
      val pool = Executors.newFixedThreadPool(2)
      implicit val ec: ExecutionContext = ExecutionContext.fromExecutor(pool)
      try {
        val held = new CountDownLatch(1)
        val removeVotes = new CountDownLatch(1)
        // RoundConflicts.removeFromRound's locks: the round, then the file's votes, deleted
        val holder = Future(DB.localTx { implicit s =>
          sql"SET SESSION innodb_lock_wait_timeout = 10".execute.apply()
          try {
            sql"SELECT id FROM rounds WHERE id = ${round.getId} FOR UPDATE".map(_.long(1)).single()
            held.countDown()
            removeVotes.await(30, TimeUnit.SECONDS)
            SelectionJdbc.deleteVotes(sqls"v.round_id = ${round.getId} AND v.page_id IN (${Seq(1L)})")
          } finally sql"SET SESSION innodb_lock_wait_timeout = DEFAULT".execute.apply(): Unit
        })
        held.await(30, TimeUnit.SECONDS) must beTrue
        val restore = Future(DB.localTx(implicit s => DeletedFiles.restore(Seq(1L))))
        // the restore waits at the round, holding none of the file's votes: the removal takes
        // them without waiting (the restore that read the round last, after locking the votes,
        // deadlocked here)
        lockWait(restore) === "LOCK WAIT"
        removeVotes.countDown()
        Await.result(holder, 30.seconds) === 1
        Await.result(restore, 30.seconds) must beEmpty
        ids("selection") must beEmpty
        ids("selection_deleted") must beEmpty
        ratesOf(aside) must beEmpty
        orphans === 0
      } finally pool.shutdownNow(): Unit
    }
  }
}
