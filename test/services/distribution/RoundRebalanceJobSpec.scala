package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, CriteriaRate, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import play.api.Configuration
import play.api.libs.json.Json
import scalikejdbc._
import services.jobs._
import services.{GalleryService, RoundService, TestRoundServices}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._

/** Adding a juror to a running round (`round_rebalance`, docs/plans/2026-11-16-round-editing.md
  * §5, PR 1a): a round where every file goes to every juror. The job's steps, the pause and its
  * release in every path, the refusals in both directions, Retry after a failed step, Cancel,
  * and votes cast while it runs.
  */
class RoundRebalanceJobSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterEach {

  sequential

  private implicit val autoSession: DBSession = AutoSession

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val schedulers = mutable.Buffer.empty[JobScheduler]
  override protected def after: Any = { schedulers.foreach(_.stop()); schedulers.clear() }

  /** A rebalance handler whose "give" steps can fail once, or run code in their transaction or
    * after their commit.
    */
  private class TestRebalance(chunkRows: Int)
      extends RoundRebalanceHandler(Configuration("jobs.roundRebalance.chunkRows" -> chunkRows)) {
    val gives = new AtomicInteger()
    @volatile var failAtGive: Option[Int] = None
    @volatile var atGive: Option[(Int, () => Unit)] = None
    @volatile var afterGive: Option[(Int, () => Unit)] = None
    @volatile var sleepMs = 0L

    override protected def beforeGive(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit = {
      val n = gives.incrementAndGet()
      if (sleepMs > 0) Thread.sleep(sleepMs)
      atGive.filter(_._1 == n).foreach(_._2())
      if (failAtGive.contains(n)) { failAtGive = None; throw new RuntimeException(s"simulated failure in give $n") }
    }

    override def step(job: Job, ctx: StepContext): StepResult = {
      val before = gives.get
      val result = super.step(job, ctx)
      if (gives.get > before) afterGive.filter(_._1 == gives.get).foreach { case (_, f) => afterGive = None; ctx.afterCommit(f()) }
      result
    }
  }

  private def inline(h: RoundRebalanceHandler): (RoundRebalanceService, JobScheduler) = {
    val s = TestRoundServices.inlineScheduler(h)
    (new RoundRebalanceService(s), s)
  }

  private val roundService: RoundService = TestRoundServices.roundService()

  // ── fixture ────────────────────────────────────────────────────────────────

  private def images(n: Int): Seq[Image] = (1 to n).map { i =>
    Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, monumentId = Some(s"12-345-$i"))
  }

  /** A contest of `n` files in its category, `jurors` jurors in a round created and distributed
    * with every file to every juror (open), one more contest juror (`y`) outside it, and its admin.
    */
  private def setUp(n: Int, jurors: Int = 3, rates: Rates = Round.binaryRound, criteria: Seq[Long] = Nil)
      : (ContestJury, Round, Seq[User], User, User) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images(n)
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    val js = createUsers("jury", 1 to jurors).sortBy(_.getId)
    val y = createUsers("jury", 100).head
    val admin = createUsers("admin", 200).head
    val round = roundService.createNewRound(
      Round(None, 0, Some("R"), contest.getId, distribution = 0, rates = rates, active = true, criterionIds = criteria),
      js.map(_.getId))
    (contest, Round.findById(round.getId).get, js, y, admin)
  }

  private def rows(roundId: Long, jurorId: Long): Seq[(Long, Int)] =
    sql"SELECT page_id, rate FROM vote WHERE round_id = $roundId AND jury_id = $jurorId ORDER BY page_id"
      .map(rs => rs.long(1) -> rs.int(2)).list()

  private def allVotes(roundId: Long): Seq[(Long, Long, Int)] =
    sql"SELECT page_id, jury_id, rate FROM vote WHERE round_id = $roundId ORDER BY page_id, jury_id"
      .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list()

  private def flags(roundId: Long): (Boolean, Int) =
    sql"SELECT active, freeze_count FROM round WHERE id = $roundId".map(rs => rs.boolean(1) -> rs.int(2)).single().get

  private def roundUser(roundId: Long, userId: Long): Option[Boolean] =
    sql"SELECT active FROM round_user WHERE round_id = $roundId AND user_id = $userId".map(_.boolean(1)).single()

  private def origins(roundId: Long): Seq[(Long, String, Option[Long])] =
    sql"SELECT page_id, added_how, added_by_job_id FROM round_image WHERE round_id = $roundId ORDER BY page_id"
      .map(rs => (rs.long(1), rs.string(2), rs.longOpt(3))).list()

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
    sql"SELECT id FROM job WHERE kind = ${RoundRebalanceHandler.Kind}".map(_.long(1)).list()
      .flatMap(JobJdbc.findById(_))

  // ── the add ────────────────────────────────────────────────────────────────

  "adding a juror to a round of every file to every juror" should {

    "give them every file of the round, unrated, keep every vote and origin, and reopen the round" in {
      val (_, round, js, y, admin) = setUp(7)
      val r = round.getId
      VoteJdbc.rate(1, js.head.getId, r, 1)
      VoteJdbc.rate(2, js(1).getId, r, -1)
      val before = allVotes(r)
      val originsBefore = origins(r)
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 3))
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val done = JobJdbc.findById(job.id).get
      (done.status === JobStatus.Done) and
        (rows(r, y.getId) === (1L to 7L).map(_ -> 0)) and
        (allVotes(r).filterNot(_._2 == y.getId) === before) and
        (origins(r) === originsBefore) and
        (RoundRebalanceHandler.filesGiven(done) === 7L) and
        (flags(r) === (true -> 0)) and
        (roundUser(r, y.getId) must beSome(true)) and
        (RoundImageInvariants.violations(r) must beEmpty) and
        (Round.isPaused(r) must beFalse)
    }

    "keep a rated round's stars and ✗ and a criteria round's partial votes, giving only unrated rows" in {
      val rated = {
        val (_, round, js, y, admin) = setUp(4, rates = Round.ratesById(5))
        val r = round.getId
        sql"UPDATE round SET worst_mark = TRUE WHERE id = $r".update()
        VoteJdbc.rate(1, js.head.getId, r, 4)
        VoteJdbc.rate(2, js.head.getId, r, -1)
        val before = allVotes(r)
        implicit val (service, _) = inline(new TestRebalance(chunkRows = 2))
        queued(service.add(Round.findById(r).get, y.getId, admin, signature(round)))
        (rows(r, y.getId) === (1L to 4L).map(_ -> 0)) and (allVotes(r).filterNot(_._2 == y.getId) === before) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
      SharedTestDb.truncateAll()
      val criteria = {
        val (_, round, js, y, admin) = setUp(3, rates = Round.ratesById(10), criteria = Seq(1L, 2L))
        val r = round.getId
        val vote = VoteJdbc.findBy(1, js.head.getId, r).get
        CriteriaRate.updateRate(vote.getId, 1L, 7) must beTrue
        def rates() = sql"SELECT vote_id, criterion_id, rate FROM criteria_rate ORDER BY vote_id, criterion_id"
          .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list()
        val ratesBefore = rates()
        implicit val (service, _) = inline(new TestRebalance(chunkRows = 2))
        queued(service.add(round, y.getId, admin, signature(round)))
        (rows(r, y.getId) === (1L to 3L).map(_ -> 0)) and (rates() === ratesBefore) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
      rated and criteria
    }

    "leave out files deleted on Commons, and files whose votes are aside" in {
      val (_, round, _, y, admin) = setUp(5)
      val r = round.getId
      sql"UPDATE images SET deleted_at = NOW() WHERE page_id = 2".update()
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 10))
      queued(service.add(round, y.getId, admin, signature(round)))
      rows(r, y.getId).map(_._1) === Seq(1L, 3L, 4L, 5L)
    }

    "pause the round for its jurors while it runs: in their menus, the paused page, no vote" in {
      val (_, round, js, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      RoundUser.setActive(r, js(2).getId, active = false)
      val seen = mutable.Buffer.empty[(Boolean, Boolean, Boolean, Boolean)]
      val juror = User.findInContest(js.head.getId, round.contestId).get
      val stopped = User.findInContest(js(2).getId, round.contestId).get
      h.atGive = Some(2 -> (() => {
        // read on other connections: the pause committed with the "stop" step
        val fresh = Round.findById(r).get
        seen += ((Round.isPaused(r), Round.activeRounds(juror).exists(_.id.contains(r)),
          new GalleryService().pausedFor(juror, fresh), Round.activeRounds(stopped).exists(_.id.contains(r))))
      }))
      implicit val (service, _) = inline(h)
      queued(service.add(round, y.getId, admin, signature(round)))
      (seen.toSeq === Seq((true, true, true, false))) and (flags(r) === (true -> 0)) and
        (Round.activeRounds(juror).exists(_.id.contains(r)) must beTrue) and
        (Round.activeRounds(stopped).exists(_.id.contains(r)) must beFalse)
    }
  }

  "the job's failure, Retry and Cancel" should {

    "release the round on a failed step, and Retry gives the same result as an uninterrupted run" in {
      val (_, round, _, y, admin) = setUp(9)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(3)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val failed = JobJdbc.findById(job.id).get
      val atFailure = (failed.status === JobStatus.Failed) and (flags(r) === (true -> 0)) and
        (rows(r, y.getId).size === 4) and (roundUser(r, y.getId) must beSome(true))
      val retried = scheduler.retry(job.id).toOption.get
      atFailure and (retried.status === JobStatus.Done) and (rows(r, y.getId) === (1L to 9L).map(_ -> 0)) and
        (flags(r) === (true -> 0)) and (RoundRebalanceHandler.filesGiven(retried) === 9L) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "release the round when a freeze by another job comes between steps, failing the next step" in {
      val (_, round, _, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.afterGive = Some(1 -> (() => DB.localTx { s => Round.freeze(Seq(r))(s) }))
      implicit val (service, _) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val failed = JobJdbc.findById(job.id).get
      val result = (failed.status === JobStatus.Failed) and
        (failed.failure.map(_.key) must beSome(RoundRebalanceHandler.FrozenKey)) and
        (flags(r)._2 === 1) // the other freeze only
      DB.localTx { s => Round.release(Seq(r), close = false)(s) }
      result and (flags(r) === (true -> 0))
    }

    "release the round on Cancel; the juror stays started with the files given, and adding them again tops up" in {
      val (_, round, _, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(2)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      scheduler.cancel(job.id)
      val cancelled = JobJdbc.findById(job.id).get
      (cancelled.status === JobStatus.Cancelled) and (flags(r) === (true -> 0)) and
        (roundUser(r, y.getId) must beSome(true)) and (rows(r, y.getId).size === 2) and {
          // added again, already active: a top-up, the rest only
          val again = queued(service.add(Round.findById(r).get, y.getId, admin, signature(Round.findById(r).get)))
          (JobJdbc.findById(again.id).map(_.status) must beSome(JobStatus.Done)) and
            (rows(r, y.getId) === (1L to 6L).map(_ -> 0)) and (roundUser(r, y.getId) must beSome(true)) and
            (RoundImageInvariants.violations(r) must beEmpty)
        }
    }

    "stop again on Cancel a juror the job gave nothing, as they were before" in {
      val (_, round, _, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(1)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val failed = (roundUser(r, y.getId) must beSome(true)) and (rows(r, y.getId) must beEmpty)
      scheduler.cancel(job.id)
      failed and (roundUser(r, y.getId) must beSome(false)) and (flags(r) === (true -> 0))
    }

    "start again on Retry a juror stopped by hand while the job was failed" in {
      val (_, round, _, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(2)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      RoundRebalanceService.setJurorActive(r, y.getId, active = false) must beTrue
      val retried = scheduler.retry(job.id).toOption.get
      (retried.status === JobStatus.Done) and (roundUser(r, y.getId) must beSome(true)) and
        (rows(r, y.getId).size === 6) and
        // the state before is the first stop's: no row
        ((retried.state \ "jurorWasActive").toOption must beNone)
    }

    "rebaseline its count check on Retry by what changed while it was failed" in {
      val (_, round, _, y, admin) = setUp(6)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(2)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      // one of the juror's given rows gone meanwhile (by hand: a move aside keeps the count)
      sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${y.getId} AND page_id = 1".update()
      val retried = scheduler.retry(job.id).toOption.get
      (retried.status === JobStatus.Done) and (rows(r, y.getId).map(_._1) === (2L to 6L))
    }

    "keep started on Cancel a juror given files before a failed count check and its Retry" in {
      val (_, round, _, y, admin) = setUp(4)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 10)
      h.afterGive = Some(1 -> (() => sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${y.getId} AND page_id = 4"
        .update(): Unit))
      implicit val (service, _) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      // Retry queued (a scheduler that doesn't run it: no step commits), then Cancel
      val idle = new JobScheduler(Seq(h), JobScheduler.Settings())
      val requeued = idle.retry(job.id)
      val cancelled = idle.cancel(job.id)
      (JobJdbc.findById(job.id).flatMap(_.failure).isEmpty must beTrue) and (requeued must beRight) and
        (cancelled.map(_.status) must beSome(JobStatus.Cancelled)) and (RoundRebalanceHandler.filesGiven(cancelled.get) === 0L) and
        (roundUser(r, y.getId) must beSome(true)) and (rows(r, y.getId).size === 3) and (flags(r) === (true -> 0))
    }

    "fail its count check if the juror's rows don't add up, releasing the round; Retry gives again" in {
      val (_, round, _, y, admin) = setUp(4)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 10)
      // a row of the new juror's deleted behind the job's back, after its give
      h.afterGive = Some(1 -> (() => sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${y.getId} AND page_id = 4"
        .update(): Unit))
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val failed = JobJdbc.findById(job.id).get
      val atFailure = (failed.failure.map(_.key) must beSome(RoundRebalanceHandler.VerifyKey)) and (flags(r) === (true -> 0))
      val retried = scheduler.retry(job.id).toOption.get
      atFailure and (retried.status === JobStatus.Done) and (rows(r, y.getId).map(_._1) === Seq(1L, 2L, 3L, 4L))
    }
  }

  "the refusals" should {

    "refuse a round not running, a legacy round, one frozen by another job, one with a job, nothing queued" in {
      val (contest, round, js, y, admin) = setUp(3)
      val r = round.getId
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 10))
      Round.setActive(r, active = false)
      val stoppedKey = refusedKey(service.add(Round.findById(r).get, y.getId, admin, signature(round)))
      Round.setActive(r, active = true)
      DB.localTx { s => Round.freeze(Seq(r))(s) }
      val frozenKey = refusedKey(service.add(Round.findById(r).get, y.getId, admin, signature(round)))
      DB.localTx { s => Round.release(Seq(r), close = false)(s) }
      // a legacy round: votes, no round_user rows
      val legacy = roundDao.create(Round(None, 2, Some("Legacy"), contest.getId, active = true))
      VoteJdbc.create(1, 0, js.head.getId, legacy.getId)
      val legacyKey = refusedKey(service.add(legacy, y.getId, admin, "|"))
      // N > 0: not yet (PR 1b)
      sql"UPDATE round SET distribution = 1 WHERE id = $r".update()
      val nKey = refusedKey(service.add(Round.findById(r).get, y.getId, admin, signature(round)))
      sql"UPDATE round SET distribution = 0 WHERE id = $r".update()
      // another job holds the round's key: a queued distribution
      JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(contest.getId), Some("round"), Some(r),
        Some(RoundDistributionHandler.dedupeKey(r))), owner = None)(AutoSession)
      val busy = service.add(Round.findById(r).get, y.getId, admin, signature(round))
      (stoppedKey must beSome(RoundRebalanceHandler.NotRunningKey)) and
        (frozenKey must beSome(RoundRebalanceHandler.FrozenKey)) and
        (legacyKey must beSome(RoundRebalanceHandler.LegacyKey)) and
        (nKey must beSome(RoundRebalanceHandler.DistributionKey)) and
        (busy must beAnInstanceOf[RoundRebalanceService.Busy]) and
        (rebalanceJobs() must beEmpty) and (roundUser(r, y.getId) must beNone) and (flags(r) === (true -> 0))
    }

    "refuse a juror of another contest, not a juror, an inactive member or unknown" in {
      val (_, round, js, y, admin) = setUp(3)
      val r = round.getId
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 10))
      val other = {
        implicit val c: ContestJury = createContests(20).head
        createUsers("jury", 300).head
      }
      val organizer = { implicit val c: ContestJury = ContestJuryJdbc.findById(10L).get; createUsers("organizer", 301).head }
      sql"UPDATE user_contest SET role = 'no' WHERE user_id = ${y.getId}".update()
      val keys = Seq(other.getId, organizer.getId, y.getId, 99999L)
        .map(id => refusedKey(service.add(round, id, admin, signature(round))))
      (keys === Seq.fill(4)(Some(RoundRebalanceHandler.NotJurorKey))) and (rebalanceJobs() must beEmpty)
    }

    "show the page again if the jury changed since it was shown" in {
      val (_, round, js, y, admin) = setUp(3)
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 10))
      val shown = signature(round)
      RoundUser.setActive(round.getId, js.head.getId, active = false)
      (service.add(round, y.getId, admin, shown) === RoundRebalanceService.JuryChanged) and (rebalanceJobs() must beEmpty)
    }

    "while a rebalance holds the round: refuse a round reading it, Start/Stop, and not retry it by Distribute new files" in {
      val (contest, round, js, y, admin) = setUp(3)
      val r = round.getId
      // a scheduler that never runs its jobs: the add stays queued, holding the round's key
      val idle = new JobScheduler(Seq(new TestRebalance(10)), JobScheduler.Settings())
      val service = new RoundRebalanceService(idle)
      val job = queued(service.add(round, y.getId, admin, service.preview(round, 0L).signature))
      val next = Round(None, 0, Some("Next"), contest.getId, distribution = 0, active = true, previous = Some(r.toString))
      val created = scala.util.Try(roundService.createNewRound(next, js.map(_.getId)))
      val startStop = RoundRebalanceService.setJurorActive(r, js.head.getId, active = false)
      val newFiles = roundService.distributeNewFiles(r)
      (created.failed.toOption.collect { case e: RoundDistributionHandler.ExcludeRoundsRefused => e.refusal.key }
        must beSome(RoundDistributionHandler.PreviousRebalancing)) and
        (startStop must beFalse) and (roundUser(r, js.head.getId) must beSome(true)) and
        (newFiles must beAnInstanceOf[RoundService.AlreadyRunning]) and (newFiles.job.id === job.id) and
        (Round.findByContest(contest.getId).size === 1)
    }

    "top up an active juror who lacks files (stopped while new files were distributed): only those" in {
      val (contest, round, js, _, admin) = setUp(4)
      val r = round.getId
      val late = js.head
      RoundRebalanceService.setJurorActive(r, late.getId, active = false) must beTrue
      val more = (5 to 6).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, monumentId = Some(s"12-345-$i")))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      roundService.distributeNewImages(r)
      RoundRebalanceService.setJurorActive(r, late.getId, active = true) must beTrue
      VoteJdbc.rate(1, late.getId, r, 1)
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 10))
      val fresh = Round.findById(r).get
      val preview = service.preview(fresh, late.getId)
      val job = queued(service.add(fresh, late.getId, admin, signature(fresh)))
      (preview.topUp must beTrue) and (preview.files === 2L) and
        (RoundRebalanceHandler.filesGiven(JobJdbc.findById(job.id).get) === 2L) and
        (rows(r, late.getId) === Seq(1L -> 1, 2L -> 0, 3L -> 0, 4L -> 0, 5L -> 0, 6L -> 0)) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "refuse a next round's distribution Retry, and its resume by Distribute new files, while a previous round is rebalanced" in {
      val (contest, round, js, y, admin) = setUp(3)
      val r = round.getId
      // the next round, its distribution failed (its freeze of round r released)
      val next = roundDao.create(Round(None, 2, Some("Next"), contest.getId, distribution = 0, active = false,
        previous = Some(r.toString), distributing = true))
      next.addUsers(js.map(j => RoundUser(next.getId, j.getId, "jury", active = true)))
      val failedId = JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(contest.getId), Some("round"), next.id,
        Some(RoundDistributionHandler.dedupeKey(next.getId)), params = Json.obj("roundId" -> next.getId,
          "completingCreation" -> true), state = Json.obj("phase" -> "distribute")), owner = None)
      sql"UPDATE job SET status = 'failed' WHERE id = $failedId".update()
      // a juror being added to round r: queued, holding its key
      val idle = new JobScheduler(Seq(new TestRebalance(10)), JobScheduler.Settings())
      val rebalance = new RoundRebalanceService(idle)
      queued(rebalance.add(round, y.getId, admin, rebalance.preview(round, 0L).signature))
      val distributions = TestRoundServices.inlineScheduler(TestRoundServices.handler())
      val retry = distributions.retry(failedId)
      val resume = scala.util.Try(roundService.distributeNewFiles(next.getId))
      (retry must beLeft(JobScheduler.RetryRefused(RoundDistributionHandler.PreviousRebalancing, round.description))) and
        (JobJdbc.findById(failedId).map(_.status) must beSome(JobStatus.Failed)) and
        (resume.failed.toOption.collect { case e: RoundDistributionHandler.ExcludeRoundsRefused => e.refusal.key }
          must beSome(RoundDistributionHandler.PreviousRebalancing)) and
        (flags(r)._2 === 0)
    }

    "not retry a failed rebalance by Distribute new files" in {
      val (_, round, _, y, admin) = setUp(4)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 2)
      h.failAtGive = Some(1)
      implicit val (service, _) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round)))
      val start = roundService.distributeNewFiles(r)
      (JobJdbc.findById(job.id).map(_.status) must beSome(JobStatus.Failed)) and
        (start.job.kind === RoundDistributionHandler.Kind) and (start.job.id !== job.id)
    }
  }

  "a round left frozen with no job" should {

    "be flagged, and root's Release reopen it, taking it off a failed job's state" in {
      val (contest, round, _, _, _) = setUp(2)
      val r = round.getId
      DB.localTx { s => Round.freeze(Seq(r))(s) }
      // a failed job whose cleanup didn't run: still lists the round
      val failedId = JobJdbc.insert(NewJob(RoundRebalanceHandler.Kind, Some(contest.getId), Some("round"), Some(r), None,
        params = Json.obj("roundId" -> r, "add" -> 1), state = Json.obj("frozen" -> Seq(r))), owner = None)(AutoSession)
      sql"UPDATE job SET status = 'failed' WHERE id = $failedId".update()
      val flagged = RoundFreezes.leftFrozen(Round.findByContest(contest.getId))
      val all = RoundFreezes.allLeftFrozen()
      val released = RoundFreezes.release(r)
      (flagged === Map(r -> 1)) and (all === Map(r -> 1)) and (released === 1) and (flags(r) === (true -> 0)) and
        ((JobJdbc.findById(failedId).get.state \ "frozen").as[Seq[Long]] must beEmpty) and
        (RoundFreezes.release(r) === 0) and (RoundFreezes.leftFrozen(Round.findByContest(contest.getId)) must beEmpty)
    }

    "not be flagged while a job holds it" in {
      val (contest, round, _, y, admin) = setUp(2)
      val r = round.getId
      val idle = new JobScheduler(Seq(new TestRebalance(10)), JobScheduler.Settings())
      val service = new RoundRebalanceService(idle)
      val job = queued(service.add(round, y.getId, admin, service.preview(round, 0L).signature))
      // as its "stop" step leaves it
      DB.localTx { s => Round.freeze(Seq(r))(s) }
      sql"UPDATE job SET state = ${Json.stringify(Json.obj("frozen" -> Seq(r)))} WHERE id = ${job.id}".update()
      (RoundFreezes.leftFrozen(Round.findByContest(contest.getId)) must beEmpty) and (RoundFreezes.release(r) === 0) and
        (Round.isPaused(r) must beTrue)
    }
  }

  "votes cast while the job runs" should {

    "all be kept with their juror, none duplicated, no deadlock" in {
      val (_, round, js, y, admin) = setUp(40)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 4)
      h.sleepMs = 30
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = 1, pollInterval = 20.millis,
        heartbeatInterval = 200.millis, lease = 60.seconds))
      schedulers += s
      s.start()
      val service = new RoundRebalanceService(s)
      val job = queued(service.add(round, y.getId, admin, service.preview(round, 0L).signature))
      val cast = mutable.Map.empty[(Long, Long), Int]
      val voters = (js :+ y).map { juror =>
        new Thread(() => {
          val rnd = new scala.util.Random(juror.getId)
          val deadline = System.currentTimeMillis() + 3000
          while (System.currentTimeMillis() < deadline) {
            val page = 1L + rnd.nextInt(40)
            val rate = rnd.nextInt(3) - 1
            if (VoteJdbc.rate(page, juror.getId, r, rate) == 1) cast.synchronized(cast((page, juror.getId)) = rate)
          }
        })
      }
      voters.foreach(_.start())
      voters.foreach(_.join())
      val done = s.await(job.id, 60.seconds)
      val stored = allVotes(r).map { case (p, j, rate) => (p, j) -> rate }
      (done.status === JobStatus.Done) and
        (stored.map(_._1).distinct.size === stored.size) and
        (stored.count(_._1._2 == y.getId) === 40) and
        (cast.toSeq.forall { case (k, rate) => stored.toMap.get(k).contains(rate) } must beTrue) and
        (flags(r) === (true -> 0)) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }
}
