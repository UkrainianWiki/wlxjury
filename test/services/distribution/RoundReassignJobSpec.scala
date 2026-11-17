package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, CriteriaRate, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import play.api.Configuration
import scalikejdbc._
import services.imports.DeletedFiles
import services.jobs._
import services.{RoundService, TestRoundServices}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._

/** Reassigning a juror's unrated files in a running round (`round_rebalance` with `params.from`,
  * docs/plans/2026-11-16-round-editing.md §7, §11 "Reassign (3)"): the quotas, the receivers (D1),
  * unrated files only, the files left waiting and their opt-in top-up, the rows set aside, a restore
  * caught by "verify", the refusals, Cancel and Retry, and votes cast while it moves.
  */
class RoundReassignJobSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterEach {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val schedulers = mutable.Buffer.empty[JobScheduler]
  override protected def after: Any = { schedulers.foreach(_.stop()); schedulers.clear() }

  /** A rebalance handler whose reassign moves can fail once, run code with their rows locked, or
    * before "verify".
    */
  private class Hooks(chunkRows: Int) extends RoundRebalanceHandler(Configuration("jobs.roundRebalance.chunkRows" -> chunkRows)) {
    val moves = new AtomicInteger()
    @volatile var failAt: Option[Int] = None
    @volatile var locked: Option[Seq[Long] => Unit] = None
    @volatile var atVerify: Option[() => Unit] = None
    @volatile var afterStep: Option[(String, () => Unit)] = None
    @volatile var sleepMs = 0L
    override def step(job: Job, ctx: StepContext): StepResult = {
      val phase = RoundRebalanceHandler.reassignStateOf(job).phase
      val result = super.step(job, ctx)
      afterStep.filter(_._1 == phase).foreach { case (_, f) => afterStep = None; ctx.afterCommit(f()) }
      result
    }
    override protected def beforeReassign(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit = {
      val n = moves.incrementAndGet()
      if (sleepMs > 0) Thread.sleep(sleepMs)
      locked.foreach { f => locked = None; f(voteIds) }
      if (failAt.contains(n)) { failAt = None; throw new RuntimeException(s"simulated failure in move $n") }
    }
    override protected def beforeReassignVerify(roundId: Long)(implicit session: DBSession): Unit =
      atVerify.foreach { f => atVerify = None; f() }
  }

  private def inline(h: RoundRebalanceHandler): (RoundRebalanceService, JobScheduler) = {
    val s = TestRoundServices.inlineScheduler(h)
    (new RoundRebalanceService(s), s)
  }

  private val roundService: RoundService = TestRoundServices.roundService()

  private def images(n: Int): Seq[Image] = (1 to n).map { i =>
    Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, monumentId = Some(s"12-345-$i"))
  }

  /** A contest of `n` files, `jurors` jurors in a round created and distributed with each file to
    * `perFile` jurors (0: every juror), open; its admin.
    */
  private def setUp(n: Int, perFile: Int, jurors: Int, rates: Rates = Round.binaryRound, criteria: Seq[Long] = Nil)
      : (Round, Seq[User], User) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images(n)
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    val js = createUsers("jury", 1 to jurors).sortBy(_.getId)
    val admin = createUsers("admin", 200).head
    val round = roundService.createNewRound(
      Round(None, 0, Some("R"), contest.getId, distribution = perFile, rates = rates, active = true, criterionIds = criteria),
      js.map(_.getId))
    (Round.findById(round.getId).get, js, admin)
  }

  private def rows(r: Long, jurorId: Long): Seq[(Long, Int)] =
    sql"SELECT page_id, rate FROM vote WHERE round_id = $r AND jury_id = $jurorId ORDER BY page_id"
      .map(rs => rs.long(1) -> rs.int(2)).list()

  private def asideRows(r: Long, jurorId: Long): Seq[Long] =
    sql"SELECT page_id FROM vote_aside WHERE round_id = $r AND jury_id = $jurorId ORDER BY page_id".map(_.long(1)).list()

  private def unrated(r: Long, ids: Seq[Long]): Map[Long, Long] = RoundRebalanceHandler.unratedCounts(r, ids)

  private def perPage(r: Long): Map[Long, Int] =
    sql"SELECT page_id, COUNT(*) FROM vote WHERE round_id = $r GROUP BY page_id".map(rs => rs.long(1) -> rs.int(2)).list().toMap

  private def pairsUnique(r: Long): Boolean =
    sql"SELECT COUNT(*) = COUNT(DISTINCT page_id, jury_id) FROM vote WHERE round_id = $r".map(_.boolean(1)).single().get

  private def roundFiles(r: Long): Seq[(Long, String)] =
    sql"SELECT page_id, status FROM round_image WHERE round_id = $r ORDER BY page_id".map(rs => rs.long(1) -> rs.string(2)).list()

  private def origins(r: Long): Seq[(Long, String, Option[Long])] =
    sql"SELECT page_id, added_how, added_by_job_id FROM round_image WHERE round_id = $r ORDER BY page_id"
      .map(rs => (rs.long(1), rs.string(2), rs.longOpt(3))).list()

  private def flags(r: Long): (Boolean, Int) =
    sql"SELECT active, freeze_count FROM round WHERE id = $r".map(rs => rs.boolean(1) -> rs.int(2)).single().get

  private def roundUser(r: Long, userId: Long): Option[Boolean] =
    sql"SELECT active FROM round_user WHERE round_id = $r AND user_id = $userId".map(_.boolean(1)).single()

  private def signature(round: Round)(implicit s: RoundRebalanceService) = s.preview(round, 0L).signature

  private def queued(outcome: RoundRebalanceService.Outcome): Job = outcome match {
    case RoundRebalanceService.Queued(job) => job
    case other                            => throw new IllegalStateException(s"not queued: $other")
  }

  private def refusedKey(outcome: RoundRebalanceService.Outcome): Option[String] = outcome match {
    case RoundRebalanceService.Refused(e) => Some(e.key)
    case _                                => None
  }

  private def rebalanceJobs(): Seq[Job] =
    sql"SELECT id FROM job WHERE kind = ${RoundRebalanceHandler.Kind}".map(_.long(1)).list().flatMap(JobJdbc.findById(_))

  /** x's files that y holds too, in vote. */
  private def sharedWith(r: Long, x: User, y: User): Seq[Long] = {
    val ys = rows(r, y.getId).map(_._1).toSet
    rows(r, x.getId).map(_._1).filter(ys)
  }

  /** A row of `juror` for the page set aside (as a deletion or disqualification left it), unrated. */
  private def asideFor(r: Long, page: Long, juror: User): Unit = DB.localTx { implicit autoSession =>
    VoteJdbc.create(page, 0, juror.getId, r)
    sql"INSERT INTO vote_aside SELECT * FROM vote WHERE round_id = $r AND page_id = $page AND jury_id = ${juror.getId}".update()
    sql"DELETE FROM vote WHERE round_id = $r AND page_id = $page AND jury_id = ${juror.getId}".update()
  }

  private def stateOf(jobId: Long): ReassignState = RoundRebalanceHandler.reassignStateOf(JobJdbc.findById(jobId).get)

  // ── the quotas ────────────────────────────────────────────────────────────

  "the reassign's quotas (water-filling from below)" should {

    "split equally over equal work, fill the least loaded first, and sum exactly to the files" in {
      val equal = RoundReassign.fillQuotas(340, Seq(1L -> 0L, 2L -> 0L, 3L -> 0L, 4L -> 0L))
      val uneven = RoundReassign.fillQuotas(10, Seq(1L -> 0L, 2L -> 4L, 3L -> 20L))
      val remainder = RoundReassign.fillQuotas(7, Seq(1L -> 5L, 2L -> 5L, 3L -> 5L))
      (equal === Map(1L -> 85L, 2L -> 85L, 3L -> 85L, 4L -> 85L)) and
        // 1 and 2 end at 7 each; 3 is above the level and gets nothing
        (uneven === Map(1L -> 7L, 2L -> 3L, 3L -> 0L)) and
        (remainder.values.sum === 7L) and (remainder.values.max - remainder.values.min must be_<=(1L)) and
        (RoundReassign.fillQuotas(0, Seq(1L -> 3L)) === Map(1L -> 0L)) and
        (RoundReassign.fillQuotas(5, Nil) must beEmpty)
    }

    "give a file to the eligible receiver with the most quota left, and leave it waiting when every one has it" in {
      val (choices, left) = RoundReassign.allocate(Seq(10L, 11L, 12L),
        Map(10L -> Set(1L), 12L -> Set(1L, 2L)), Seq(1L, 2L), Map(1L -> 2L, 2L -> 1L))
      (choices === Seq(10L -> Some(2L), 11L -> Some(1L), 12L -> None)) and (left === Map(1L -> 1L, 2L -> 0L))
    }
  }

  // ── the reassign ─────────────────────────────────────────────────────────

  "reassigning a juror's unrated files in a round of N jurors per file" should {

    "move only their unrated files to an even level, keep their votes, stop them; every file keeps N rows" in {
      Seq(1, 2, 3).map { perFile =>
        SharedTestDb.truncateAll()
        val (round, js, admin) = setUp(30, perFile, jurors = 5)
        val r = round.getId
        val x = js.head
        val others = js.tail.map(_.getId)
        // X rated some (✓ and ✗), others rated some too
        val xRated = rows(r, x.getId).take(4).zipWithIndex.map { case ((p, _), i) =>
          VoteJdbc.rate(p, x.getId, r, if (i % 2 == 0) 1 else -1); p -> (if (i % 2 == 0) 1 else -1)
        }
        js.tail.foreach(j => rows(r, j.getId).take(1).foreach { case (p, _) => VoteJdbc.rate(p, j.getId, r, 1) })
        val pagesBefore = perPage(r)
        val originsBefore = origins(r)
        val xUnrated = unrated(r, Seq(x.getId)).getOrElse(x.getId, 0L)
        implicit val (service, _) = inline(new Hooks(chunkRows = 3))
        val preview = service.reassignPreview(round, x.getId)
        val job = queued(service.reassign(round, x.getId, admin, signature(round), others))
        val done = JobJdbc.findById(job.id).get
        val st = RoundRebalanceHandler.reassignStateOf(done)
        val after = unrated(r, others)
        val levels = others.map(after.getOrElse(_, 0L))
        (done.status === JobStatus.Done) and (preview.refusal must beNone) and
          (preview.given === st.given.map { case (k, v) => k.toLong -> v }) and (preview.waiting === st.waiting) and
          (st.moved + st.waiting === xUnrated) and (st.waiting === 0L) and
          (rows(r, x.getId).map(_._1) === xRated.map(_._1).sorted) and
          (xRated.forall { case (p, rate) => VoteJdbc.findBy(p, x.getId, r).exists(_.rate == rate) } must beTrue) and
          (perPage(r) === pagesBefore) and pairsUnique(r) and (origins(r) === originsBefore) and
          // even, but where a receiver already has a file (N > 1) it goes to the next
          (levels.max - levels.min must be_<=(perFile.toLong)) and
          (roundUser(r, x.getId) must beSome(false)) and (flags(r) === (true -> 0)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }.reduce(_ and _)
    }

    "never move a rated round's ✗ or a criteria round's partly rated vote" in {
      val worst = {
        val (round, js, admin) = setUp(8, 1, jurors = 2, rates = Round.ratesById(5))
        val r = round.getId
        val x = js.head
        sql"UPDATE round SET worst_mark = TRUE WHERE id = $r".update()
        val Seq(p1, p2) = rows(r, x.getId).take(2).map(_._1)
        VoteJdbc.rate(p1, x.getId, r, -1)
        VoteJdbc.rate(p2, x.getId, r, 4)
        implicit val (service, _) = inline(new Hooks(chunkRows = 10))
        queued(service.reassign(Round.findById(r).get, x.getId, admin, signature(round), Seq(js(1).getId)))
        (rows(r, x.getId) === Seq(p1 -> -1, p2 -> 4)) and (RoundImageInvariants.violations(r) must beEmpty)
      }
      SharedTestDb.truncateAll()
      val criteria = {
        val (round, js, admin) = setUp(6, 1, jurors = 2, rates = Round.ratesById(10), criteria = Seq(1L, 2L))
        val r = round.getId
        val x = js.head
        val p = rows(r, x.getId).head._1
        val vote = VoteJdbc.findBy(p, x.getId, r).get
        CriteriaRate.updateRate(vote.getId, 1L, 7) must beTrue
        implicit val (service, _) = inline(new Hooks(chunkRows = 10))
        queued(service.reassign(round, x.getId, admin, signature(round), Seq(js(1).getId)))
        (rows(r, x.getId).map(_._1) === Seq(p)) and
          (sql"SELECT rate FROM criteria_rate WHERE vote_id = ${vote.getId} AND criterion_id = 1".map(_.int(1)).single() must beSome(7)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
      worst and criteria
    }

    "start ticked stopped receivers again (D1) and give unticked ones nothing" in {
      val (round, js, admin) = setUp(24, 1, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, c) = js
      RoundUser.setActive(r, b.getId, active = false)
      RoundUser.setActive(r, c.getId, active = false)
      val before = rows(r, c.getId)
      implicit val (service, _) = inline(new Hooks(chunkRows = 100))
      val preview = service.reassignPreview(Round.findById(r).get, x.getId, Some(Seq(a.getId, b.getId)))
      // a active and b stopped ticked; c stopped, unticked
      queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      (preview.restarted === Seq(b.getId)) and (roundUser(r, b.getId) must beSome(true)) and
        (roundUser(r, c.getId) must beSome(false)) and (rows(r, c.getId) === before) and
        (rows(r, b.getId).size must be_>(before.size)) and (rows(r, x.getId) must beEmpty) and
        (roundUser(r, x.getId) must beSome(false))
    }

    "leave a file every receiver has waiting: the file stays in; the opt-in top-up gives it a juror" in {
      val (round, js, admin) = setUp(16, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, c) = js
      implicit val (service, _) = inline(new Hooks(chunkRows = 100))
      val few = refusedKey(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId)))
      // files of x and a that b holds aside: every ticked receiver (a, b) has them
      val held = sharedWith(r, x, a).take(2)
      held.foreach(p => asideFor(r, p, b))
      val preview = service.reassignPreview(round, x.getId, Some(Seq(a.getId, b.getId)))
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val st = stateOf(job.id)
      val waitingCount = RoundReassign.waitingCount(Round.findById(r).get)
      val statusesWaiting = held.map(p => roundFiles(r).find(_._1 == p).map(_._2))
      // the top-up, ticked with "Distribute new files": c, who lacks them, gets them
      val start = roundService.distributeNewFiles(r, topUpWaiting = true)
      val after = perPage(r)
      (few must beSome(RoundRebalanceHandler.FewReceiversKey)) and (held.size === 2) and
        (preview.waiting === 2L) and (st.waiting === 2L) and (waitingCount must beSome(2L)) and
        (statusesWaiting === Seq(Some("in"), Some("in"))) and
        (start must beAnInstanceOf[RoundService.Started]) and
        (held.forall(p => after.get(p).contains(2)) must beTrue) and
        (held.forall(p => rows(r, c.getId).exists(_._1 == p)) must beTrue) and
        (RoundReassign.waitingCount(Round.findById(r).get) must beSome(0L)) and
        (rows(r, x.getId) must beEmpty) and pairsUnique(r) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "not top up waiting files without the tick" in {
      val (round, js, admin) = setUp(16, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, _) = js
      sharedWith(r, x, a).take(1).foreach(p => asideFor(r, p, b))
      implicit val (service, _) = inline(new Hooks(chunkRows = 100))
      queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val waiting = RoundReassign.waitingCount(Round.findById(r).get)
      roundService.distributeNewFiles(r)
      (waiting must beSome(1L)) and (RoundReassign.waitingCount(Round.findById(r).get) must beSome(1L))
    }

    "give their unrated rows set aside to receivers, so a restore or requalify brings the file to the receiver" in {
      val (round, js, admin) = setUp(10, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      val Seq(p1, p2, p3) = rows(r, x.getId).take(3).map(_._1)
      VoteJdbc.rate(p3, x.getId, r, 1)
      // p1 and p3 deleted on Commons (their votes aside); p2 set aside as if disqualified
      DB.localTx { implicit autoSession => DeletedFiles.markDeleted(Seq(p1, p3)) }
      DB.localTx { implicit autoSession =>
        RoundWrites.lock(Seq(r))
        ImageJdbc.moveVotesAsideInRound(r, Seq(p2))
        sql"UPDATE round_image SET status = 'dq', dq_code = 'manual', dq_at = NOW(3) WHERE round_id = $r AND page_id = $p2".update()
      }
      implicit val (service, _) = inline(new Hooks(chunkRows = 100))
      val preview = service.reassignPreview(round, x.getId)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val st = stateOf(job.id)
      val asideAfter = asideRows(r, x.getId)
      DB.localTx { implicit autoSession => DeletedFiles.restore(Seq(p1, p3)) }
      // requalified while the round is stopped (the gate), then started again
      Round.setActive(r, active = false)
      val requalified = RoundConflicts.requalify(r, Seq(p2), RoundConflicts.RequalifyReason.MistakenMatch, admin,
        RoundDistributionHandler.dedupeKey(r))
      Round.setActive(r, active = true)
      val holderOf = (p: Long) => Seq(a, b).filter(j => rows(r, j.getId).exists(_._1 == p)).map(_.getId)
      (preview.files.unratedAside === 2L) and (preview.asideGiven === 2L) and (st.asideMoved === 2L) and
        // the rated row aside stays with x, and comes back to x
        (asideAfter === Seq(p3)) and (rows(r, x.getId) === Seq(p3 -> 1)) and
        (requalified === RoundConflicts.Outcome.Requalified(Seq(p2))) and
        (holderOf(p1).size === 1) and (holderOf(p2).size === 1) and
        (perPage(r).get(p1) must beSome(1)) and (perPage(r).get(p2) must beSome(1)) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "catch in verify a file restored between steps below the cursor, and give it to a receiver" in {
      val (round, js, admin) = setUp(10, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      val p = rows(r, x.getId).head._1
      // x's row of p aside (deleted on Commons) before the job; restored after its "move" passed it
      DB.localTx { implicit autoSession => DeletedFiles.markDeleted(Seq(p)) }
      val h = new Hooks(chunkRows = 100)
      h.afterStep = Some(RoundRebalanceHandler.Phase.Move -> (() => DB.localTx { implicit autoSession => DeletedFiles.restore(Seq(p)) }))
      implicit val (service, _) = inline(h)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val st = stateOf(job.id)
      (JobJdbc.findById(job.id).get.status === JobStatus.Done) and (st.repassed must beTrue) and
        (rows(r, x.getId) must beEmpty) and (asideRows(r, x.getId) must beEmpty) and (perPage(r).get(p) must beSome(1)) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  "reassigning in a round where every file goes to every juror" should {

    "give nothing: remove the juror's unrated rows, keep their votes and the others' rows" in {
      val (round, js, admin) = setUp(6, 0, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      VoteJdbc.rate(1, x.getId, r, 1)
      val aBefore = rows(r, a.getId)
      implicit val (service, _) = inline(new Hooks(chunkRows = 2))
      val preview = service.reassignPreview(round, x.getId)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Nil))
      val st = stateOf(job.id)
      (preview.refusal must beNone) and (preview.given must beEmpty) and
        (st.removed === 5L) and (st.moved === 0L) and (rows(r, x.getId) === Seq(1L -> 1)) and
        (rows(r, a.getId) === aBefore) and (roundUser(r, x.getId) must beSome(false)) and
        (RoundReassign.waitingCount(Round.findById(r).get) must beNone) and
        (roundFiles(r).forall(_._2 == "in") must beTrue) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  // ── refusals ─────────────────────────────────────────────────────────────

  "the reassign's refusals" should {

    "refuse a round not running, frozen by another job, legacy, with a job, nothing queued" in {
      val (round, js, admin) = setUp(6, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      implicit val (service, _) = inline(new Hooks(chunkRows = 10))
      val sig = signature(round)
      Round.setActive(r, active = false)
      val stopped = refusedKey(service.reassign(Round.findById(r).get, x.getId, admin, sig, Seq(a.getId, b.getId)))
      val stoppedPreview = service.reassignPreview(Round.findById(r).get, x.getId).refusal.map(_.key)
      Round.setActive(r, active = true)
      DB.localTx { s => Round.freeze(Seq(r))(s) }
      val frozen = refusedKey(service.reassign(Round.findById(r).get, x.getId, admin, sig, Seq(a.getId, b.getId)))
      DB.localTx { s => Round.release(Seq(r), close = false)(s) }
      val legacy = roundDao.create(Round(None, 2, Some("Legacy"), round.contestId, active = true, distribution = 1))
      VoteJdbc.create(1, 0, x.getId, legacy.getId)
      val legacyKey = refusedKey(service.reassign(legacy, x.getId, admin, "|", Seq(a.getId)))
      JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(round.contestId), Some("round"), Some(r),
        Some(RoundDistributionHandler.dedupeKey(r))), owner = None)(AutoSession)
      val busy = service.reassign(Round.findById(r).get, x.getId, admin, sig, Seq(a.getId, b.getId))
      (stopped must beSome(RoundRebalanceHandler.ReassignNotRunningKey)) and
        (stoppedPreview must beSome(RoundRebalanceHandler.ReassignNotRunningKey)) and
        (frozen must beSome(RoundRebalanceHandler.FrozenKey)) and
        (legacyKey must beSome(RoundRebalanceHandler.ReassignLegacyKey)) and
        (busy must beAnInstanceOf[RoundRebalanceService.Busy]) and
        (rebalanceJobs() must beEmpty) and (roundUser(r, x.getId) must beSome(true)) and (flags(r) === (true -> 0))
    }

    "refuse fewer receivers than N, an inactive member, another contest's juror, a non-juror of the round, x not in it" in {
      val (round, js, admin) = setUp(8, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, c) = js
      implicit val (service, _) = inline(new Hooks(chunkRows = 10))
      val sig = signature(round)
      val other = { implicit val k: ContestJury = createContests(20).head; createUsers("jury", 300).head }
      val outside = { implicit val k: ContestJury = ContestJuryJdbc.findById(round.contestId).get; createUsers("jury", 301).head }
      val few = refusedKey(service.reassign(round, x.getId, admin, sig, Seq(a.getId)))
      val otherKey = refusedKey(service.reassign(round, x.getId, admin, sig, Seq(a.getId, other.getId)))
      val outsideKey = refusedKey(service.reassign(round, x.getId, admin, sig, Seq(a.getId, outside.getId)))
      val notInRound = refusedKey(service.reassign(round, outside.getId, admin, sig, Seq(a.getId, b.getId)))
      sql"UPDATE user_contest SET role = 'no' WHERE user_id = ${c.getId}".update()
      val inactive = refusedKey(service.reassign(round, x.getId, admin, sig, Seq(a.getId, c.getId)))
      val changed = { RoundUser.setActive(r, b.getId, active = false); service.reassign(round, x.getId, admin, sig, Seq(a.getId, b.getId)) }
      (few must beSome(RoundRebalanceHandler.FewReceiversKey)) and
        (otherKey must beSome(RoundRebalanceHandler.ReceiverKey)) and
        (outsideKey must beSome(RoundRebalanceHandler.ReceiverKey)) and
        (notInRound must beSome(RoundRebalanceHandler.FromKey)) and
        (inactive must beSome(RoundRebalanceHandler.ReceiverKey)) and
        (changed === RoundRebalanceService.JuryChanged) and
        (rebalanceJobs() must beEmpty) and (roundUser(r, x.getId) must beSome(true))
    }

    "while it holds the round: refuse Start/Stop and a round reading it; a freeze between steps fails it, releasing the round" in {
      val (round, js, admin) = setUp(6, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      val idle = new JobScheduler(Seq(new Hooks(10)), JobScheduler.Settings())
      val service = new RoundRebalanceService(idle)
      val job = queued(service.reassign(round, x.getId, admin, service.preview(round, 0L).signature, Seq(a.getId, b.getId)))
      val startStop = RoundRebalanceService.setJurorActive(r, a.getId, active = false)
      val next = Round(None, 0, Some("Next"), round.contestId, distribution = 0, active = true, previous = Some(r.toString))
      val created = scala.util.Try(roundService.createNewRound(next, js.map(_.getId)))
      idle.cancel(job.id)
      // a freeze by another job between steps
      val h = new Hooks(chunkRows = 1)
      h.afterStep = Some(RoundRebalanceHandler.Phase.Move -> (() => DB.localTx { s => Round.freeze(Seq(r))(s) }))
      implicit val (inlineService, scheduler) = inline(h)
      val failed = queued(inlineService.reassign(Round.findById(r).get, x.getId, admin, inlineService.preview(round, 0L).signature,
        Seq(a.getId, b.getId)))
      val failedJob = JobJdbc.findById(failed.id).get
      (startStop must beFalse) and (roundUser(r, a.getId) must beSome(true)) and
        (created.failed.toOption.collect { case e: RoundDistributionHandler.ExcludeRoundsRefused => e.refusal.key }
          must beSome(RoundDistributionHandler.PreviousRebalancing)) and
        (failedJob.status === JobStatus.Failed) and
        // released by the job; the other job's freeze stays
        (flags(r)._2 === 1)
    }
  }

  // ── failure, Retry, Cancel ───────────────────────────────────────────────

  "the reassign's failure, Retry and Cancel" should {

    "release the round on a failed step; Retry gives the same result as an uninterrupted run" in {
      def run(failAt: Option[Int]): (Map[Long, Int], Seq[(Long, Long)], Long) = {
        SharedTestDb.truncateAll()
        val (round, js, admin) = setUp(20, 1, jurors = 3)
        val r = round.getId
        val Seq(x, a, b) = js
        val h = new Hooks(chunkRows = 3)
        h.failAt = failAt
        implicit val (service, scheduler) = inline(h)
        val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
        val afterFail = flags(r)
        if (failAt.isDefined) {
          JobJdbc.findById(job.id).get.status must_=== JobStatus.Failed
          afterFail must_=== (true -> 0)
          scheduler.retry(job.id)
        }
        val holders = sql"SELECT page_id, jury_id FROM vote WHERE round_id = $r ORDER BY page_id".map(rs => rs.long(1) -> rs.long(2)).list()
        (perPage(r), holders, JobJdbc.findById(job.id).get.status match { case JobStatus.Done => 1L; case _ => 0L })
      }
      val plain = run(None)
      val retried = run(Some(2))
      (retried === plain) and (plain._3 === 1L)
    }

    "on Cancel before any row moved put the juror and restarted receivers back; after, keep them" in {
      val nothing = {
        val (round, js, admin) = setUp(20, 1, jurors = 3)
        val r = round.getId
        val Seq(x, a, b) = js
        RoundUser.setActive(r, b.getId, active = false)
        val h = new Hooks(chunkRows = 2)
        h.failAt = Some(1)
        implicit val (service, scheduler) = inline(h)
        val job = queued(service.reassign(Round.findById(r).get, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
        scheduler.cancel(job.id)
        (roundUser(r, x.getId) must beSome(true)) and (roundUser(r, b.getId) must beSome(false)) and (flags(r) === (true -> 0))
      }
      SharedTestDb.truncateAll()
      val some = {
        val (round, js, admin) = setUp(20, 1, jurors = 3)
        val r = round.getId
        val Seq(x, a, b) = js
        RoundUser.setActive(r, b.getId, active = false)
        val xBefore = rows(r, x.getId).size
        val h = new Hooks(chunkRows = 2)
        h.failAt = Some(2)
        implicit val (service, scheduler) = inline(h)
        val job = queued(service.reassign(Round.findById(r).get, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
        scheduler.cancel(job.id)
        (roundUser(r, x.getId) must beSome(false)) and (roundUser(r, b.getId) must beSome(true)) and
          (rows(r, x.getId).size === xBefore - 2) and
          (flags(r) === (true -> 0)) and (RoundImageInvariants.violations(r) must beEmpty)
      }
      nothing and some
    }
  }

  // ── votes during the move ────────────────────────────────────────────────

  "votes cast while the reassign moves" should {

    "all be kept with their juror: the stopped juror's vote on a moved file refused, none lost or duplicated" in {
      val (round, js, admin) = setUp(60, 2, jurors = 4)
      val r = round.getId
      val x = js.head
      val h = new Hooks(chunkRows = 3)
      h.sleepMs = 20
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = 1, pollInterval = 20.millis,
        heartbeatInterval = 200.millis, lease = 60.seconds))
      schedulers += s
      s.start()
      val service = new RoundRebalanceService(s)
      val job = queued(service.reassign(round, x.getId, admin, service.preview(round, 0L).signature, js.tail.map(_.getId)))
      val cast = mutable.Map.empty[(Long, Long), Int]
      val voters = js.map { juror =>
        new Thread(() => {
          val rnd = new scala.util.Random(juror.getId)
          val deadline = System.currentTimeMillis() + 3000
          while (System.currentTimeMillis() < deadline) {
            val page = 1L + rnd.nextInt(60)
            val rate = rnd.nextInt(3) - 1
            if (VoteJdbc.rate(page, juror.getId, r, rate) == 1) cast.synchronized(cast((page, juror.getId)) = rate)
          }
        })
      }
      voters.foreach(_.start())
      voters.foreach(_.join())
      val done = s.await(job.id, 60.seconds)
      val stored = sql"SELECT page_id, jury_id, rate FROM vote WHERE round_id = $r".map(rs => (rs.long(1), rs.long(2)) -> rs.int(3))
        .list().toMap
      (done.status === JobStatus.Done) and pairsUnique(r) and (perPage(r).values.forall(_ == 2) must beTrue) and
        // every vote that returned success with a rate is kept with its juror (x's rated ones too)
        (cast.toSeq.filter(_._2 != 0).forall { case (k, rate) => stored.get(k).contains(rate) } must beTrue) and
        (stored.toSeq.forall { case ((_, j), rate) => j != x.getId || rate != 0 } must beTrue) and
        (roundFiles(r).size === 60) and (flags(r) === (true -> 0)) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  // ── review fixes (2026-11-17) ────────────────────────────────────────────

  /** One vote row deleted from each of `pages` (the last assigned), the files' rows synced: they wait
    * for a juror.
    */
  private def makeWaiting(r: Long, pages: Seq[Long]): Unit = DB.localTx { implicit autoSession =>
    RoundWrites.lock(Seq(r))
    pages.foreach(p => sql"DELETE FROM vote WHERE round_id = $r AND page_id = $p ORDER BY id DESC LIMIT 1".update())
    RoundImageJdbc.syncPairs(pages.map(r -> _))
  }

  private def waitingNow(r: Long): Long = RoundReassign.waitingCount(Round.findById(r).get).getOrElse(0L)

  "the round form's “Also give out the files waiting for a juror”" should {

    /** A round of 16 files, 2 jurors each of 4, two files waiting; a new file in the contest's category
      * (17: "Distribute new files" adds it) and a stored one outside it (18: only a pasted list adds it).
      * Then "Distribute new files" as the form sends it, the top-up ticked: whether 17 and 18 are in the
      * round, the files still waiting, and the job queued.
      */
    def save(newFiles: Boolean, list: Boolean): (Boolean, Boolean, Long, Long, Job) = {
      SharedTestDb.truncateAll()
      val (round, js, _) = setUp(16, 2, jurors = 4)
      val r = round.getId
      makeWaiting(r, rows(r, js.head.getId).take(2).map(_._1))
      val waiting = waitingNow(r)
      val fresh = Image(17L, "File:Image17.jpg", None, None, 640, 480, monumentId = Some("12-345-17"))
      val listed = Image(18L, "File:Image18.jpg", None, None, 640, 480, monumentId = Some("12-345-18"))
      imageDao.batchInsert(Seq(fresh, listed))
      CategoryLinkJdbc.addToCategory(contestDao.findById(round.contestId).get.categoryId.get, Seq(fresh))
      val start = roundService.distributeNewFiles(r, titles = if (list) Seq(listed.title) else Nil, newFiles = newFiles,
        topUpWaiting = true)
      val files = roundFiles(r).map(_._1).toSet
      (files(17L), files(18L), waiting, waitingNow(r), start.job)
    }

    "ticked alone, give out only the waiting files: no new file filled, a distribute queued, no fill" in {
      val (fresh, listed, before, after, job) = save(newFiles = false, list = false)
      (fresh must beFalse) and (listed must beFalse) and (before === 2L) and (after === 0L) and
        (RoundDistributionHandler.distributeMode(job) must beTrue) and (RoundDistributionHandler.topUpWaiting(job) must beTrue) and
        (JobJdbc.findById(job.id).map(_.status) must beSome(JobStatus.Done)) and
        (RoundImageInvariants.all() must beEmpty)
    }

    "with a pasted list, add the list only (not the new files) and give out the waiting files" in {
      val (fresh, listed, before, after, job) = save(newFiles = false, list = true)
      (fresh must beFalse) and (listed must beTrue) and (before === 2L) and (after === 0L) and
        (RoundDistributionHandler.listOnly(job) must beTrue) and (RoundImageInvariants.all() must beEmpty)
    }

    "with “Distribute new files”, do both" in {
      val (fresh, listed, before, after, job) = save(newFiles = true, list = false)
      (fresh must beTrue) and (listed must beFalse) and (before === 2L) and (after === 0L) and
        (RoundDistributionHandler.listOnly(job) must beFalse) and (RoundImageInvariants.all() must beEmpty)
    }
  }

  "the reassign's review fixes" should {

    "leave with the juror an unrated row of a file deleted on Commons whose votes aren't set aside yet" in {
      val (round, js, admin) = setUp(12, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      val p = rows(r, x.getId).head._1
      val xUnrated = rows(r, x.getId).size.toLong
      // marked deleted, its votes not moved yet (the moment between the two)
      sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id = $p".update()
      implicit val (service, _) = inline(new Hooks(chunkRows = 2))
      val preview = service.reassignPreview(Round.findById(r).get, x.getId)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val st = stateOf(job.id)
      (preview.files.unrated === xUnrated - 1) and (preview.moved === xUnrated - 1) and
        (JobJdbc.findById(job.id).map(_.status) must beSome(JobStatus.Done)) and
        (st.moved === xUnrated - 1) and (rows(r, x.getId).map(_._1) === Seq(p)) and
        (roundUser(r, x.getId) must beSome(false))
    }

    "fail verify naming a file it left waiting whose round_image row is gone before it" in {
      val (round, js, admin) = setUp(16, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, _) = js
      val held = sharedWith(r, x, a).take(1)
      held.foreach(p => asideFor(r, p, b))
      val h = new Hooks(chunkRows = 100)
      h.afterStep = Some(RoundRebalanceHandler.Phase.Aside -> (() => DB.localTx { implicit autoSession =>
        RoundWrites.lock(Seq(r))
        sql"DELETE FROM round_image WHERE round_id = $r AND page_id IN ($held)".update()
      }))
      implicit val (service, _) = inline(h)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val failed = JobJdbc.findById(job.id).get
      (failed.status === JobStatus.Failed) and
        (failed.failure.map(_.key) must beSome(RoundRebalanceHandler.InvariantsKey)) and
        (failed.failure.map(_.args) must beSome(Seq(round.number.toString, "1", held.head.toString))) and
        (flags(r) === (true -> 0))
    }

    "pass verify with a violation the round had before it (a complete baseline)" in {
      val (round, js, admin) = setUp(16, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, c) = js
      // a file of b's, not x's, marked deleted with its votes still there (invariants 1 and 3)
      val broken = rows(r, b.getId).map(_._1).filterNot(p => rows(r, x.getId).exists(_._1 == p)).head
      sql"UPDATE round_image SET status = 'deleted' WHERE round_id = $r AND page_id = $broken".update()
      val before = RoundImageInvariants.violations(r, only = Set(1, 2, 3)).map(_.pageId).distinct
      implicit val (service, _) = inline(new Hooks(chunkRows = 100))
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId, c.getId)))
      val st = stateOf(job.id)
      (before === Seq(broken)) and (JobJdbc.findById(job.id).map(_.status) must beSome(JobStatus.Done)) and
        (st.checks.violationsBefore.flatMap(_.get("3")) must beSome(ViolationBaseline(1L, Seq(broken)))) and
        (rows(r, x.getId) must beEmpty)
    }

    "pass verify when a file it left waiting is deleted on Commons before it, the file then deleted in the round" in {
      val (round, js, admin) = setUp(16, 2, jurors = 4)
      val r = round.getId
      val Seq(x, a, b, _) = js
      val held = sharedWith(r, x, a).take(1)
      held.foreach(p => asideFor(r, p, b))
      val h = new Hooks(chunkRows = 100)
      // after the last move, before verify: the waiting file deleted on Commons. The round, paused by the
      // job, is still open, so its votes go aside and its row becomes `deleted`
      h.afterStep = Some(RoundRebalanceHandler.Phase.Aside -> (() =>
        DB.localTx { implicit autoSession => DeletedFiles.markDeleted(held) }))
      implicit val (service, _) = inline(h)
      val job = queued(service.reassign(round, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val done = JobJdbc.findById(job.id).get
      (done.status === JobStatus.Done) and (stateOf(job.id).checks.waitingPages === held) and
        (roundFiles(r).find(_._1 == held.head).map(_._2) must beSome("deleted")) and
        (perPage(r).get(held.head) must beNone) and (flags(r) === (true -> 0)) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "say on Cancel part-way to run it again for the rest; nothing to say before a row moved" in {
      val (round, js, admin) = setUp(20, 1, jurors = 3)
      val r = round.getId
      val Seq(x, a, b) = js
      val h = new Hooks(chunkRows = 2)
      h.failAt = Some(2)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.reassign(Round.findById(r).get, x.getId, admin, signature(round), Seq(a.getId, b.getId)))
      val failed = JobJdbc.findById(job.id).get
      val cancelled = scheduler.cancel(job.id).get
      // the same job as if in a round where every file goes to every juror
      val all = roundDao.create(Round(None, 2, Some("All"), round.contestId, distribution = 0, active = true))
      val inAll = failed.copy(params = failed.params ++ play.api.libs.json.Json.obj("roundId" -> all.getId))
      (h.cancelledNote(failed.copy(state = play.api.libs.json.Json.toJsObject(ReassignState()))) must beNone) and
        (h.cancelledNote(cancelled).map(_.key) must beSome(RoundRebalanceHandler.CancelledPartwayKey)) and
        (h.cancelledNote(cancelled).map(_.args) must beSome(Seq("2", x.getId.toString))) and
        // files only left waiting count; rows removed (every file to every juror) have their own message
        (h.cancelledNote(failed.copy(state = play.api.libs.json.Json.toJsObject(ReassignState(movedAny = true, waiting = 3))))
          .map(e => e.key -> e.args) must beSome(RoundRebalanceHandler.CancelledPartwayKey -> Seq("3", x.getId.toString))) and
        // N > 0: unrated rows aside removed count too
        (h.cancelledNote(failed.copy(state = play.api.libs.json.Json.toJsObject(ReassignState(movedAny = true, given = Map("1" -> 1L),
          asideRemoved = 2)))).map(e => e.key -> e.args) must beSome(RoundRebalanceHandler.CancelledPartwayKey -> Seq("3", x.getId.toString))) and
        // every file to every juror: the removed message, by the round's distribution, also with only rows aside removed
        (h.cancelledNote(inAll.copy(state = play.api.libs.json.Json.toJsObject(ReassignState(movedAny = true, removed = 4,
          asideRemoved = 1)))).map(e => e.key -> e.args) must beSome(RoundRebalanceHandler.CancelledRemovedKey -> Seq("5", x.getId.toString))) and
        (h.cancelledNote(inAll.copy(state = play.api.libs.json.Json.toJsObject(ReassignState(movedAny = true, asideRemoved = 2))))
          .map(e => e.key -> e.args) must beSome(RoundRebalanceHandler.CancelledRemovedKey -> Seq("2", x.getId.toString))) and
        (roundUser(r, x.getId) must beSome(false))
    }
  }
}
