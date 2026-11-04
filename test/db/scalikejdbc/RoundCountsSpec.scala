package db.scalikejdbc

import db.scalikejdbc.RoundCounts.{Counts, RecountStep}
import org.intracer.wmua.{ContestJury, CriteriaRate, Image, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.counts.RoundCountsRecompute
import services.distribution.RoundDistributionHandler

import java.time.ZonedDateTime

/** round_image's counts (V89, P2a; docs/plans/2026-11-02-round-images-two-step.md §16): the
  * marks a juror's vote and a bulk writer leave, and the background recompute that brings the
  * counts in line with the votes (invariant 5), its windows and its generation logic.
  */
class RoundCountsSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Two files per window: a round of a few files takes several. */
  private def recompute = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false, window = 2))

  private def images(ids: Long*): Seq[Image] = {
    val imgs = ids.map(i => Image(i, s"File:Image$i.jpg", None, None, 640, 480, Some(s"07-101-000$i")))
    imageDao.batchInsert(imgs)
    imgs
  }

  private def entries(roundId: Long): Seq[Long] =
    DB.readOnly(implicit s => sql"SELECT page_id FROM round_image_dirty WHERE round_id = $roundId ORDER BY page_id"
      .map(_.long(1)).list())

  private def gen(roundId: Long): (Long, Long) = RoundCounts.state(roundId).map(s => s.gen -> s.genDone).get

  private def counts(roundId: Long, pageId: Long): Counts = RoundCounts.of(roundId, pageId).get

  /** Jurors with new emails (email<i>): unique in the database across the rounds of a test. */
  private var lastJuror = 0
  private def newJurors(n: Int)(implicit contest: ContestJury): Seq[User] = {
    val ids = (lastJuror + 1) to (lastJuror + n)
    lastJuror += n
    createUsers("jury", ids: _*)
  }

  /** A round with `jurors` jurors' votes (`rates(page)(juror)`) on the files. */
  private def round(rates: Map[Long, Seq[Int]], ratesOf: Rates = Round.binaryRound, criteria: Seq[Long] = Nil,
      halfStar: Boolean = false)(implicit contest: ContestJury): (Long, Seq[User]) = {
    val jurorCount = rates.values.map(_.size).max
    val jurors = newJurors(jurorCount)
    val r = roundDao.create(Round(None, 1, None, contest.getId, rates = ratesOf, active = true,
      criterionIds = criteria, halfStar = halfStar)).getId
    images(rates.keys.toSeq.sorted: _*)
    VoteJdbc.batchInsert(rates.toSeq.flatMap { case (p, rs) => rs.zip(jurors).map { case (rate, j) => Vote(p, j.getId, r, rate) } })
    (r, jurors)
  }

  "V89's schema" should {
    "add the counts to round_image, the generations to round, and round_image_dirty, on the test server" in {
      def create(t: String) = DB.readOnly(implicit s => sql"SHOW CREATE TABLE ${SQLSyntax.createUnsafely(t)}"
        .map(_.string(2)).single()).get
      // the server the run is on (10.6 by default, 10.11 with -Dwlxjury.test.mariadb-image)
      println(s"[RoundCountsSpec] server ${DB.readOnly(implicit s => sql"SELECT VERSION()".map(_.string(1)).single()).get}")
      val ri = create("round_image")
      val r = create("round")
      val dirty = create("round_image_dirty")
      forall(Seq("assigned", "voted", "approvals", "rejections", "rate_sum")) { c =>
        ri must contain(s"`$c` int(11) NOT NULL DEFAULT 0")
      } and (r must contain("`counts_gen` bigint(20) unsigned NOT NULL DEFAULT 1")) and
        (r must contain("`counts_gen_done` bigint(20) unsigned NOT NULL DEFAULT 0")) and
        (dirty must contain("PRIMARY KEY (`round_id`,`page_id`)")) and (dirty must not(contain("FOREIGN KEY"))) and
        (dirty must contain("ENGINE=InnoDB")) and (dirty must contain("utf8mb4_general_ci"))
    }

    "make every round dirty until its first recount, new ones too" in {
      implicit val contest: ContestJury = createContests(10).head
      val r = roundDao.create(Round(None, 1, None, contest.getId)).getId
      (gen(r) === (1L -> 0L)) and (RoundCounts.roundsToRecount() === Seq(r)) and (RoundCounts.isClean(r) must beFalse)
    }
  }

  "a juror's vote" should {
    "mark its file, not the round, when its rate changes" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1, j2)) = round(Map(1L -> Seq(0, 0), 2L -> Seq(0, 0)))
      recompute.untilClean()
      val before = gen(r)
      (VoteJdbc.rate(1L, j1.getId, r, 1) === 1) and (entries(r) === Seq(1L)) and (gen(r) === before) and
        // the same rate again changes no count: no mark (the entry above cleared first)
        { recompute.untilClean(); entries(r) must beEmpty } and
        (VoteJdbc.rate(1L, j1.getId, r, 1) === 1) and (entries(r) must beEmpty) and
        // a second juror's vote on the file: one entry
        (VoteJdbc.rate(2L, j1.getId, r, -1) === 1) and (VoteJdbc.rate(2L, j2.getId, r, 1) === 1) and
        (entries(r) === Seq(2L)) and (gen(r) === before)
    }

    "mark nothing when it is refused (a juror stopped on the round, a file not assigned)" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1)) = round(Map(1L -> Seq(0)))
      recompute.untilClean()
      DB.autoCommit(implicit s => sql"INSERT INTO round_user (round_id, user_id, role, active) VALUES ($r, ${j1.getId}, 'jury', 0)".update())
      (VoteJdbc.rate(1L, j1.getId, r, 1) === 0) and (VoteJdbc.rate(9L, j1.getId, r, 1) === 0) and (entries(r) must beEmpty)
    }

    "of a criterion mark its file only once the vote's rate changes (all its criteria rated, U5)" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1)) = round(Map(1L -> Seq(0)), ratesOf = Round.ratesById(10), criteria = Seq(1L, 2L))
      recompute.untilClean()
      val vote = VoteJdbc.findBy(1L, j1.getId, r).get.getId
      (CriteriaRate.updateRate(vote, 1L, 4) must beTrue) and (entries(r) must beEmpty) and
        (CriteriaRate.updateRate(vote, 2L, 6) must beTrue) and (entries(r) === Seq(1L)) and {
          recompute.untilClean()
          counts(r, 1L) === Counts(1, 1, 1, 0, 10)
        } and
        // back to partly rated: rate 0, unrated again
        (CriteriaRate.updateRate(vote, 2L, 0) must beTrue) and (entries(r) === Seq(1L)) and {
          recompute.untilClean()
          (counts(r, 1L) === Counts(1, 0, 0, 0, 0)) and (RoundImageInvariants.violations(r) must beEmpty)
        }
    }
  }

  "a bulk writer" should {
    "bump its round's generation in its own transaction, the round's created_at kept" in {
      implicit val contest: ContestJury = createContests(10).head
      val created = ZonedDateTime.now.minusDays(3).withNano(0)
      val jurors = newJurors(2)
      val r1 = roundDao.create(Round(None, 1, None, contest.getId, createdAt = created)).getId
      val r2 = roundDao.create(Round(None, 2, None, contest.getId, createdAt = created)).getId
      images(1L, 2L)
      def createdAt(r: Long) = DB.readOnly(implicit s => sql"SELECT created_at FROM round WHERE id = $r"
        .map(_.timestamp(1).toInstant).single()).get
      val at = createdAt(r1)
      VoteJdbc.batchInsert(Seq(1L, 2L).flatMap(p => jurors.map(j => Vote(p, j.getId, r1, 0))))
      val afterInsert = gen(r1)._1
      TestVoteWrites.removeImage(2L, r1)
      val afterRemove = gen(r1)._1
      TestVoteWrites.setRound(1L, r1, r2)
      (afterInsert === 2L) and (afterRemove === 3L) and (gen(r1)._1 === 4L) and (gen(r2)._1 === 2L) and
        (createdAt(r1) === at) and (createdAt(r2) === at) and (entries(r1) must beEmpty)
    }

    "be refused the bump without its round lock" in {
      implicit val contest: ContestJury = createContests(10).head
      val r = roundDao.create(Round(None, 1, None, contest.getId)).getId
      DB.localTx(implicit s => RoundCounts.markRounds(Seq(r))) must throwA[IllegalStateException]
    }
  }

  "the recompute" should {
    "give every row its votes' counts (invariant 5): binary, rated, half stars, criteria, files aside" in {
      implicit val contest: ContestJury = createContests(10).head
      val (binary, _) = round(Map(1L -> Seq(1, 1, -1), 2L -> Seq(-1, 0, 0), 3L -> Seq(0, 0, 0), 4L -> Seq(1, 0, 1),
        5L -> Seq(-1, -1, -1)))
      val (rated, _) = round(Map(11L -> Seq(5, 3, 0), 12L -> Seq(1, 0, 0), 13L -> Seq(0, 0, 0)), ratesOf = Round.ratesById(5))
      val (half, _) = round(Map(21L -> Seq(7, 10, 0), 22L -> Seq(1, 1, 3)), ratesOf = Round.ratesById(10), halfStar = true)
      val (criteria, _) = round(Map(31L -> Seq(9, 0), 32L -> Seq(0, 0)), ratesOf = Round.ratesById(10), criteria = Seq(1L, 2L))
      // a file deleted on Commons in the binary round: its votes aside, its row `deleted`
      DB.localTx { implicit s =>
        sql"UPDATE images SET deleted_at = NOW() WHERE page_id = 5".update()
        RoundWrites.lock(Seq(binary))
        ImageJdbc.archiveVotes(Seq(5L), Set(binary))
      }
      val summary = recompute.untilClean()
      forall(Seq(binary, rated, half, criteria)) { r =>
        (RoundCounts.isClean(r) must beTrue) and (RoundCounts.drift(r) must beEmpty) and
          (RoundImageInvariants.violations(r) must beEmpty)
      } and (summary.roundsRecounted === 4) and (summary.windows must be_>(4)) and
        (counts(binary, 1L) === Counts(3, 3, 2, 1, 1)) and (counts(binary, 2L) === Counts(3, 1, 0, 1, -1)) and
        (counts(binary, 3L) === Counts(3, 0, 0, 0, 0)) and (counts(binary, 4L) === Counts(3, 2, 2, 0, 2)) and
        (RoundImageJdbc.find(binary, 5L).map(_.status) === Some("deleted")) and (counts(binary, 5L) === Counts.Zero) and
        (counts(rated, 11L) === Counts(3, 2, 2, 0, 8)) and (counts(rated, 13L) === Counts(3, 0, 0, 0, 0)) and
        (counts(half, 21L) === Counts(3, 2, 2, 0, 17)) and (counts(half, 22L) === Counts(3, 3, 3, 0, 5)) and
        (counts(criteria, 31L) === Counts(2, 1, 1, 0, 9)) and (RoundCounts.roundsToRecount() must beEmpty)
    }

    "report a count changed by hand once the round is clean (invariant 5), and repair it at the next recount" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, _) = round(Map(1L -> Seq(1, 0), 2L -> Seq(0, 0)))
      recompute.untilClean()
      DB.autoCommit(implicit s => sql"UPDATE round_image SET assigned = 9 WHERE round_id = $r AND page_id = 2".update())
      val reported = RoundImageInvariants.violations(r)
      DB.localTx { implicit s => RoundWrites.lock(Seq(r)); RoundCounts.markRounds(Seq(r)) }
      val whileDirty = RoundImageInvariants.violations(r)
      recompute.untilClean()
      (reported === Seq(RoundImageInvariants.Violation(5, r, 2L))) and (whileDirty must beEmpty) and
        (counts(r, 2L) === Counts(2, 0, 0, 0, 0)) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "leave the round dirty when a bulk write lands between two windows of its recount, and recount it again" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, _) = round(Map(1L -> Seq(1, 0), 2L -> Seq(0, 0), 3L -> Seq(0, 1), 4L -> Seq(0, 0)))
      val key = RoundDistributionHandler.dedupeKey(r)
      val first = WriteTx.write(implicit s => RoundCounts.roundWindow(r, None, None, key, limit = 2))
      val RecountStep.Ran(w1, from) = first: @unchecked
      // a third juror's vote inserted on file 1, already counted, and on file 4, not yet
      val j3 = newJurors(1).head
      VoteJdbc.batchInsert(Seq(Vote(1L, j3.getId, r, 1), Vote(4L, j3.getId, r, -1)))
      var step = WriteTx.write(implicit s => RoundCounts.roundWindow(r, Some(from), w1.next, key, limit = 2))
      while (step.isInstanceOf[RecountStep.Ran] && step.asInstanceOf[RecountStep.Ran].window.next.isDefined) {
        val RecountStep.Ran(w, g) = step: @unchecked
        step = WriteTx.write(implicit s => RoundCounts.roundWindow(r, Some(g), w.next, key, limit = 2))
      }
      val afterPass = gen(r)
      val staleFile1 = counts(r, 1L)
      val file4 = counts(r, 4L)
      recompute.untilClean()
      // the insert of round() bumped the generation to 2; the third juror's to 3
      (from === 2L) and (w1.next === Some(2L)) and (afterPass === (3L -> 2L)) and (RoundCounts.isClean(r) must beTrue) and
        // file 1 was counted before the insert; file 4 after it
        (staleFile1 === Counts(2, 1, 1, 0, 1)) and (file4 === Counts(3, 1, 0, 1, -1)) and
        (counts(r, 1L) === Counts(3, 2, 2, 0, 2)) and (RoundCounts.drift(r) must beEmpty)
    }

    "not lose a vote cast between two windows of a recount: its file stays marked" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1, _)) = round(Map(1L -> Seq(0, 0), 2L -> Seq(0, 0), 3L -> Seq(0, 0)))
      val key = RoundDistributionHandler.dedupeKey(r)
      val RecountStep.Ran(w1, from) = WriteTx.write(implicit s => RoundCounts.roundWindow(r, None, None, key, limit = 2)): @unchecked
      VoteJdbc.rate(1L, j1.getId, r, 1) === 1
      val RecountStep.Ran(w2, _) = WriteTx.write(implicit s => RoundCounts.roundWindow(r, Some(from), w1.next, key, limit = 2)): @unchecked
      val afterPass = (counts(r, 1L), entries(r), RoundCounts.isClean(r))
      recompute.untilClean()
      (w2.next must beNone) and (afterPass === ((Counts(2, 0, 0, 0, 0), Seq(1L), false))) and
        (counts(r, 1L) === Counts(2, 1, 1, 0, 1)) and (entries(r) must beEmpty) and (RoundCounts.isClean(r) must beTrue)
    }

    "wait for a round being distributed, but count its files marked by votes" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, Seq(j1)) = round(Map(1L -> Seq(0), 2L -> Seq(0)))
      DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 1 WHERE id = $r".update())
      VoteJdbc.rate(1L, j1.getId, r, 1)
      val summary = recompute.untilClean()
      val whileDistributing = (gen(r), counts(r, 1L), counts(r, 2L), entries(r))
      DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 0 WHERE id = $r".update())
      recompute.untilClean()
      (summary.skipped === 1) and (whileDistributing === ((2L -> 0L, Counts(1, 1, 1, 0, 1), Counts.Zero, Nil))) and
        (counts(r, 2L) === Counts(1, 0, 0, 0, 0)) and (RoundCounts.isClean(r) must beTrue)
    }

    "drop the entries of a round that no longer exists, and recount an empty round" in {
      implicit val contest: ContestJury = createContests(10).head
      val empty = roundDao.create(Round(None, 1, None, contest.getId)).getId
      DB.autoCommit(implicit s => sql"INSERT INTO round_image_dirty (round_id, page_id) VALUES (999, 1), (999, 2)".update())
      recompute.untilClean()
      (entries(999L) must beEmpty) and (RoundCounts.isClean(empty) must beTrue) and (gen(empty) === (1L -> 1L))
    }

    "take its budget: a tick stops between windows, the next goes on from there" in {
      implicit val contest: ContestJury = createContests(10).head
      val (r, _) = round((1L to 9L).map(_ -> Seq(1)).toMap)
      val slow = new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false, window = 2,
        budget = scala.concurrent.duration.Duration.Zero))
      val firstTick = slow.runOnce()
      val dirtyAfterFirst = RoundCounts.isClean(r)
      var ticks = 1
      while (!RoundCounts.isClean(r) && ticks < 20) { slow.runOnce(); ticks += 1 }
      // a zero budget still starts nothing: every tick runs no window
      (firstTick.windows === 0) and (dirtyAfterFirst must beFalse) and
        (new RoundCountsRecompute(RoundCountsRecompute.Settings(background = false, window = 2,
          budget = scala.concurrent.duration.DurationInt(1).minute)).runOnce().roundsRecounted === 1) and
        (RoundCounts.drift(r) must beEmpty)
    }
  }
}
