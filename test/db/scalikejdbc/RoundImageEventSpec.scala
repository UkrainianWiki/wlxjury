package db.scalikejdbc

import db.scalikejdbc.RoundImageEventJdbc.{Action, Change}
import db.scalikejdbc.RoundImageJdbc.DqCode
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

import java.sql.SQLException

/** `round_image_event` (V88, docs/plans/2026-11-02-round-images-two-step.md §15): its schema on
  * the test server (MariaDB 10.6 by default), and its rows written only in the action's own
  * transaction, after the `round_image` rows, with the round locked first: a failure rolls the
  * rows, the moved votes and the event back together.
  */
class RoundImageEventSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** A round with a juror's votes on files 1 and 2: the round and the juror. */
  private def setUp(): (Long, Long) = {
    implicit val contest: ContestJury = createContests(10).head
    val Seq(juror) = createUsers("jury", 1)
    val round = roundDao.create(Round(None, 1, None, contest.getId, active = false)).getId
    imageDao.batchInsert(Seq(1L, 2L).map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i"))))
    Seq(1L, 2L).foreach(p => selectionDao.create(p, rate = 1, juryId = juror.getId, roundId = round))
    (round, juror.getId)
  }

  private def voted(round: Long): Set[Long] = VoteJdbc.byRound(round).map(_.pageId).toSet

  private def status(round: Long, pageId: Long): Option[String] = RoundImageJdbc.find(round, pageId).map(_.status)

  "the round_image_event table (V88)" should {

    "be created as the migration says, on the test server" in {
      val create = DB.readOnly(implicit s => sql"SHOW CREATE TABLE round_image_event".map(_.string(2)).single()).get
      val version = DB.readOnly(implicit s => sql"SELECT VERSION()".map(_.string(1)).single()).get
      (create must contain("`id` bigint(20) unsigned NOT NULL AUTO_INCREMENT")) and
        (create must contain("`round_id` bigint(20) unsigned NOT NULL")) and
        (create must contain("`page_id` bigint(20) NOT NULL")) and
        (create must contain("`new_status` varchar(16) DEFAULT NULL")) and
        (create must contain("`created_at` timestamp(3) NOT NULL DEFAULT current_timestamp(3)")) and
        (create must contain("PRIMARY KEY (`id`)")) and
        (create must contain("KEY `round_image_event_round_page_idx` (`round_id`,`page_id`,`id`)")) and
        (create must contain("CONSTRAINT `round_image_event_round_id_fk` FOREIGN KEY (`round_id`) REFERENCES `round` (`id`) ON DELETE CASCADE")) and
        (create must contain("CONSTRAINT `round_image_event_action_chk`")) and
        (create must contain("CONSTRAINT `round_image_event_prior_status_chk`")) and
        (create must contain("CONSTRAINT `round_image_event_new_status_chk`")) and
        (create must contain("ENGINE=InnoDB")) and (create must contain("utf8mb4_general_ci")) and
        (version must not(beEmpty))
    }

    "refuse an unknown action, status or code" in {
      val (round, _) = setUp()
      def insert(action: String, prior: String, now: String) = DB.autoCommit { implicit s =>
        sql"""INSERT INTO round_image_event (round_id, page_id, action, code, prior_status, new_status)
              VALUES ($round, 1, $action, 'coi', $prior, $now)""".update()
      }
      (insert("removed", "in", "dq") must throwA[SQLException]) and
        // BINARY: the CHECKs are case-sensitive, under the table's case-insensitive collation
        (insert("Disqualify", "in", "dq") must throwA[SQLException]) and
        (insert("disqualify", "IN", "dq") must throwA[SQLException]) and
        (insert("disqualify", "in", "DQ") must throwA[SQLException]) and
        // the lower-case values pass
        (insert("disqualify", "in", "dq") === 1) and
        (insert("disqualify", "out", "dq") must throwA[SQLException]) and
        (insert("disqualify", "in", "gone") must throwA[SQLException]) and
        (DB.localTx { implicit s =>
          RoundWrites.lock(Seq(round))
          RoundImageEventJdbc.record(round, Action.Disqualify, "x" * 17, None, Seq(Change(1L, "in", Some("dq"))))
        } must throwA[IllegalArgumentException]) and
        (RoundImageEventJdbc.byRound(round).map(e => (e.action, e.priorStatus, e.newStatus)) ===
          Seq(("disqualify", "in", Some("dq"))))
    }

    "go with its round" in {
      val (round, _) = setUp()
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        RoundImageEventJdbc.record(round, Action.Disqualify, DqCode.Coi, None, Seq(Change(1L, "in", Some("dq"))))
      }
      RoundImageEventJdbc.byRound(round).size === 1
      DB.autoCommit(implicit s => sql"DELETE FROM round WHERE id = $round".update())
      RoundImageEventJdbc.byRound(round) must beEmpty
    }
  }

  "an event" should {

    "be refused without the round's lock (RoundWrites), writing nothing" in {
      val (round, _) = setUp()
      (DB.localTx { implicit s =>
        RoundImageEventJdbc.record(round, Action.Requalify, "mistaken_match", None, Seq(Change(1L, "dq", Some("in"))))
      } must throwA[IllegalStateException]) and
        (RoundImageEventJdbc.record(round, Action.Requalify, "mistaken_match", None,
          Seq(Change(1L, "dq", Some("in"))))(AutoSession) must throwA[IllegalStateException]("outside a transaction")) and
        (RoundImageEventJdbc.byRound(round) must beEmpty)
    }

    "fail with the disqualify it records: the rows and the moved votes roll back with it" in {
      val (round, _) = setUp()
      // an event the table refuses (its CHECK on the prior status), after the votes moved aside
      // and the rows marked `dq`, in the same transaction
      (DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L, 2L))
        RoundImageJdbc.disqualify(round, Map(1L -> "x", 2L -> "y"), DqCode.Coi, None, prior = Map(1L -> "in", 2L -> "bogus"))
      } must throwA[SQLException]) and
        (voted(round) === Set(1L, 2L)) and (status(round, 1L) === Some("in")) and (status(round, 2L) === Some("in")) and
        (RoundImageJdbc.disqualified(round) must beEmpty) and (RoundImageEventJdbc.byRound(round) must beEmpty) and
        (RoundImageInvariants.violations(round) must beEmpty)
    }

    "be refused, before anything is written, for a row to mark whose prior status isn't given" in {
      val (round, _) = setUp()
      (DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        val prior = RoundImageJdbc.statuses(round, Seq(1L))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L, 2L))
        // file 2's prior status missing: never guessed from the moved row's `deleted`
        RoundImageJdbc.disqualify(round, Map(1L -> "x", 2L -> "y"), DqCode.Coi, None, prior)
      } must throwA[IllegalArgumentException]("no prior status for pages 2")) and
        (voted(round) === Set(1L, 2L)) and (RoundImageJdbc.disqualified(round) must beEmpty) and
        (RoundImageEventJdbc.byRound(round) must beEmpty)
    }

    "roll back with its action when the transaction fails after it" in {
      val (round, _) = setUp()
      (DB.localTx[Unit] { implicit s =>
        RoundWrites.lock(Seq(round))
        val prior = RoundImageJdbc.statuses(round, Seq(1L))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L))
        RoundImageJdbc.disqualify(round, Map(1L -> "x"), DqCode.Coi, None, prior)
        RoundImageEventJdbc.byRound(round).map(e => (e.pageId, e.priorStatus, e.newStatus)) === Seq((1L, "in", Some("dq")))
        throw new IllegalStateException("after the event")
      } must throwA[IllegalStateException]("after the event")) and
        (voted(round) === Set(1L, 2L)) and (status(round, 1L) === Some("in")) and
        (RoundImageEventJdbc.byRound(round) must beEmpty)
    }

    "be committed with a requalify, which records only the rows that left dq" in {
      val (round, juror) = setUp()
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        val prior = RoundImageJdbc.statuses(round, Seq(1L))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L))
        RoundImageJdbc.disqualify(round, Map(1L -> "x"), DqCode.Coi, Some(juror), prior)
      } === 1
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        ImageJdbc.moveVotesBackInRound(round, Seq(1L))
        // file 2 isn't disqualified: no event for it; a second lift of file 1 finds nothing
        RoundImageJdbc.requalifyRows(round, Seq(1L, 2L), "conflict_ended", Some(juror))
        RoundImageJdbc.requalifyRows(round, Seq(1L), "conflict_ended", Some(juror))
      }
      (status(round, 1L) === Some("in")) and (voted(round) === Set(1L, 2L)) and
        (RoundImageEventJdbc.ofFile(round, 1L).map(e => (e.action, e.code, e.priorStatus, e.newStatus, e.by)) === Seq(
          ("disqualify", "coi", "in", Some("dq"), Some(juror)),
          ("requalify", "conflict_ended", "dq", Some("in"), Some(juror)))) and
        (RoundImageEventJdbc.ofFile(round, 2L) must beEmpty) and
        (RoundImageEventJdbc.latestDisqualify(round, Seq(1L, 2L))(AutoSession) must beEmpty) and
        (RoundImageInvariants.violations(round) must beEmpty)
    }

    "record a disqualified file with no votes left, requalified out of the round, as removed" in {
      val (round, _) = setUp()
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        ImageJdbc.moveVotesAsideInRound(round, Seq(1L))
        RoundImageJdbc.disqualify(round, Map(1L -> "x"), DqCode.Coi, None, Map(1L -> "in"))
      }
      // its votes gone (a hand edit): the row is kept `dq`, and a requalify removes it
      DB.autoCommit(implicit s => sql"DELETE FROM vote_aside WHERE round_id = $round AND page_id = 1".update())
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(round))
        RoundImageJdbc.requalifyRows(round, Seq(1L), "mistaken_match", None)
      }
      (RoundImageJdbc.find(round, 1L) must beNone) and
        (RoundImageEventJdbc.ofFile(round, 1L).map(e => (e.action, e.newStatus)) ===
          Seq(("disqualify", Some("dq")), ("requalify", None)))
    }
  }
}
