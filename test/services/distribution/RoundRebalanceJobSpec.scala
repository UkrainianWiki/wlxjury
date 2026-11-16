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
      // another job holds the round's key: a queued distribution
      JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(contest.getId), Some("round"), Some(r),
        Some(RoundDistributionHandler.dedupeKey(r))), owner = None)(AutoSession)
      val busy = service.add(Round.findById(r).get, y.getId, admin, signature(round))
      (stoppedKey must beSome(RoundRebalanceHandler.NotRunningKey)) and
        (frozenKey must beSome(RoundRebalanceHandler.FrozenKey)) and
        (legacyKey must beSome(RoundRebalanceHandler.LegacyKey)) and
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

  // ── PR 1b: N > 0, the donors' unrated files ────────────────────────────────

  "the water-filling quotas" should {

    "split equal work equally, sum exactly to what the new juror gets, and give nothing to one who has as much" in {
      val equal = RoundRebalanceHandler.quotas(0, Seq(1L -> 100L, 2L -> 100L, 3L -> 100L, 4L -> 100L))
      val uneven = RoundRebalanceHandler.quotas(0, Seq(1L -> 10L, 2L -> 7L, 3L -> 3L))
      val plan = RoundRebalanceHandler.quotas(0, Seq(1L -> 1930L, 2L -> 1910L, 3L -> 1820L, 4L -> 1780L, 5L -> 1680L))
      val withOwn = RoundRebalanceHandler.quotas(5, Seq(1L -> 11L, 2L -> 11L))
      (equal === Map(1L -> 20L, 2L -> 20L, 3L -> 20L, 4L -> 20L)) and
        // 3 doesn't give (below the level); 10 and 7 end at 6 and 6, the new juror at 5
        (uneven === Map(1L -> 4L, 2L -> 1L)) and
        // the plan's example: 9,120 unrated files over 5 jurors, 1,520 for the new juror: from A 410, B 390, ...
        (plan === Map(1L -> 410L, 2L -> 390L, 3L -> 300L, 4L -> 260L, 5L -> 160L)) and
        (withOwn.values.sum === 9L - 5L) and
        (RoundRebalanceHandler.quotas(50, Seq(1L -> 40L, 2L -> 50L)) must beEmpty) and
        (RoundRebalanceHandler.quotas(0, Nil) must beEmpty)
    }
  }

  /** A round of `n` files, each to `perFile` of `jurors` jurors, created and distributed. */
  private def setUpN(n: Int, perFile: Int, jurors: Int, rates: Rates = Round.binaryRound, criteria: Seq[Long] = Nil)
      : (Round, Seq[User], User, User) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images(n)
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    val js = createUsers("jury", 1 to jurors).sortBy(_.getId)
    val y = createUsers("jury", 100).head
    val admin = createUsers("admin", 200).head
    val round = roundService.createNewRound(
      Round(None, 0, Some("R"), contest.getId, distribution = perFile, rates = rates, active = true, criterionIds = criteria),
      js.map(_.getId))
    (Round.findById(round.getId).get, js, y, admin)
  }

  private def unrated(r: Long, ids: Seq[Long]): Map[Long, Long] = RoundRebalanceHandler.unratedCounts(r, ids)

  private def perPage(r: Long): Map[Long, Int] =
    sql"SELECT page_id, COUNT(*) FROM vote WHERE round_id = $r GROUP BY page_id".map(rs => rs.long(1) -> rs.int(2)).list().toMap

  private def pairsUnique(r: Long): Boolean =
    sql"SELECT COUNT(*) = COUNT(DISTINCT page_id, jury_id) FROM vote WHERE round_id = $r".map(_.boolean(1)).single().get

  "adding a juror to a round of N jurors per file" should {

    "take the donors' unrated files to an even level, every file keeping its N rows, rated votes staying" in {
      Seq(1, 2, 3).map { perFile =>
        SharedTestDb.truncateAll()
        val (round, js, y, admin) = setUpN(24, perFile, jurors = 4)
        val r = round.getId
        // some rated votes, which never move
        val rated = js.flatMap(j => rows(r, j.getId).take(2).map { case (p, _) => VoteJdbc.rate(p, j.getId, r, 1); (p, j.getId) })
        val pagesBefore = perPage(r)
        val originsBefore = origins(r)
        val unratedBefore = unrated(r, js.map(_.getId))
        val expected = RoundRebalanceHandler.quotas(0, unratedBefore.toSeq).values.sum
        implicit val (service, _) = inline(new TestRebalance(chunkRows = 5))
        val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
        val done = JobJdbc.findById(job.id).get
        val after = unrated(r, js.map(_.getId) :+ y.getId)
        val levels = js.map(j => after.getOrElse(j.getId, 0L)) :+ after.getOrElse(y.getId, 0L)
        (done.status === JobStatus.Done) and (RoundRebalanceHandler.filesGiven(done) === expected) and
          (rows(r, y.getId).size.toLong === expected) and (rows(r, y.getId).forall(_._2 == 0) must beTrue) and
          (perPage(r) === pagesBefore) and pairsUnique(r) and (origins(r) === originsBefore) and
          (rated.forall { case (p, j) => VoteJdbc.findBy(p, j, r).exists(_.rate == 1) } must beTrue) and
          (levels.max - levels.min must be_<=(1L)) and
          (flags(r) === (true -> 0)) and (RoundImageInvariants.violations(r) must beEmpty)
      }.reduce(_ and _)
    }

    "take from ticked donors only; a ticked stopped donor gives and stays stopped" in {
      val (round, js, y, admin) = setUpN(30, 1, jurors = 3)
      val r = round.getId
      val Seq(a, b, c) = js
      RoundUser.setActive(r, c.getId, active = false)
      val before = unrated(r, js.map(_.getId))
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
      // b unticked; a active and c stopped ticked
      queued(service.add(round, y.getId, admin, signature(round), Seq(a.getId, c.getId)))
      val after = unrated(r, js.map(_.getId))
      (after(b.getId) === before(b.getId)) and (after(a.getId) must be_<(before(a.getId))) and
        (after(c.getId) must be_<(before(c.getId))) and (roundUser(r, c.getId) must beSome(false)) and
        (roundUser(r, y.getId) must beSome(true)) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "never move a rated round's ✗ or a criteria round's partly rated vote" in {
      val worst = {
        val (round, js, y, admin) = setUpN(8, 1, jurors = 2, rates = Round.ratesById(5))
        val r = round.getId
        sql"UPDATE round SET worst_mark = TRUE WHERE id = $r".update()
        val xs = js.map(j => { val p = rows(r, j.getId).last._1; VoteJdbc.rate(p, j.getId, r, -1); (p, j.getId) })
        implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
        queued(service.add(Round.findById(r).get, y.getId, admin, signature(round), js.map(_.getId)))
        (xs.forall { case (p, j) => VoteJdbc.findBy(p, j, r).exists(_.rate == -1) } must beTrue) and
          (rows(r, y.getId).map(_._1).toSet.intersect(xs.map(_._1).toSet) must beEmpty)
      }
      SharedTestDb.truncateAll()
      val criteria = {
        val (round, js, y, admin) = setUpN(8, 1, jurors = 2, rates = Round.ratesById(10), criteria = Seq(1L, 2L))
        val r = round.getId
        // each juror's last file (the first a move would take) partly rated: one criterion only
        val partial = js.map { j =>
          val p = rows(r, j.getId).last._1
          CriteriaRate.updateRate(VoteJdbc.findBy(p, j.getId, r).get.getId, 1L, 6) must beTrue
          (p, j.getId)
        }
        implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
        queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
        (partial.forall { case (p, j) => VoteJdbc.findBy(p, j, r).isDefined } must beTrue) and
          (rows(r, y.getId).map(_._1).toSet.intersect(partial.map(_._1).toSet) must beEmpty) and
          (rows(r, y.getId).size === 2) and (RoundImageInvariants.violations(r) must beEmpty)
      }
      worst and criteria
    }

    "roll a step back when a file's rows change in it; Retry gives the same result as an uninterrupted run" in {
      val (round, js, y, admin) = setUpN(20, 1, jurors = 2)
      val r = round.getId
      val quota = RoundRebalanceHandler.quotas(0, unrated(r, js.map(_.getId)).toSeq)
      val h = new TestRebalance(chunkRows = 3) {
        val moves = new AtomicInteger()
        override protected def afterMove(roundId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit =
          // the second move: another juror's row of one of its files, written in the step
          if (moves.incrementAndGet() == 2)
            sql"INSERT INTO vote (page_id, rate, jury_id, round_id) VALUES (${pageIds.head}, 0, ${admin.getId}, $roundId)".update()(session)
      }
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
      val failed = JobJdbc.findById(job.id).get
      val atFailure = (failed.status === JobStatus.Failed) and (rows(r, y.getId).size === 3) and
        (VoteJdbc.byRound(r).count(_.juryId == admin.getId) === 0) and (flags(r) === (true -> 0))
      val retried = scheduler.retry(job.id).toOption.get
      atFailure and (retried.status === JobStatus.Done) and (rows(r, y.getId).size.toLong === quota.values.sum) and
        (perPage(r).values.forall(_ == 1) must beTrue) and (RoundImageInvariants.violations(r) must beEmpty) and
        (flags(r) === (true -> 0))
    }

    "start a stopped juror again with a fair share, their own unrated files counted" in {
      val (round, js, y, admin) = setUpN(30, 1, jurors = 3)
      val r = round.getId
      val stopped = js.head
      // the stopped juror rated 6 of their 10 files before being stopped
      rows(r, stopped.getId).take(6).foreach { case (p, _) => VoteJdbc.rate(p, stopped.getId, r, 1) }
      RoundUser.setActive(r, stopped.getId, active = false)
      val others = js.tail.map(_.getId)
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
      val preview = service.preview(Round.findById(r).get, stopped.getId)
      val before = unrated(r, js.map(_.getId))
      queued(service.add(round, stopped.getId, admin, signature(round), others))
      val after = unrated(r, js.map(_.getId))
      (preview.restart must beTrue) and (preview.donors === others) and (preview.refusal must beNone) and
        (roundUser(r, stopped.getId) must beSome(true)) and
        (after(stopped.getId) must be_>(before(stopped.getId))) and
        (after.values.max - after.values.min must be_<=(1L)) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "refuse a ticked donor who isn't a juror of the round" in {
      val (round, js, y, admin) = setUpN(6, 1, jurors = 2)
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
      (refusedKey(service.add(round, y.getId, admin, signature(round), Seq(js.head.getId, admin.getId))) must
        beSome(RoundRebalanceHandler.DonorKey)) and (rebalanceJobs() must beEmpty)
    }

    "keep every vote cast while it moves: a donor's on a moved file is refused, none lost or duplicated" in {
      val (round, js, y, admin) = setUpN(60, 2, jurors = 3)
      val r = round.getId
      val h = new TestRebalance(chunkRows = 3)
      h.sleepMs = 0
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = 1, pollInterval = 20.millis,
        heartbeatInterval = 200.millis, lease = 60.seconds))
      schedulers += s
      s.start()
      val service = new RoundRebalanceService(s)
      val job = queued(service.add(round, y.getId, admin, service.preview(round, 0L).signature, js.map(_.getId)))
      val cast = mutable.Map.empty[(Long, Long), Int]
      val voters = (js :+ y).map { juror =>
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
      val stored = allVotes(r).map { case (p, j, rate) => (p, j) -> rate }.toMap
      (done.status === JobStatus.Done) and pairsUnique(r) and (perPage(r).values.forall(_ == 2) must beTrue) and
        (cast.toSeq.filter(_._2 != 0).forall { case (k, rate) => stored.get(k).contains(rate) } must beTrue) and
        (flags(r) === (true -> 0)) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  // ── PR 1b, after review ───────────────────────────────────────────────────

  /** A move handler that fails its `failAt`th move (before its rows move), or runs code before its
    * locks or with its rows locked (once each).
    */
  private class MoveHooks(chunkRows: Int) extends TestRebalance(chunkRows) {
    val moves = new AtomicInteger()
    @volatile var failAt: Option[Int] = None
    @volatile var preLock: Option[Seq[Long] => Unit] = None
    @volatile var locked: Option[Seq[Long] => Unit] = None
    override protected def beforeLock(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit =
      preLock.foreach { f => preLock = None; f(voteIds) }
    override protected def beforeMove(roundId: Long, voteIds: Seq[Long])(implicit session: DBSession): Unit = {
      val n = moves.incrementAndGet()
      locked.foreach { f => locked = None; f(voteIds) }
      if (failAt.contains(n)) { failAt = None; throw new RuntimeException(s"simulated failure in move $n") }
    }
  }

  private def voteRow(id: Long): Option[(Long, Long, Int)] =
    sql"SELECT page_id, jury_id, rate FROM vote WHERE id = $id".map(rs => (rs.long(1), rs.long(2), rs.int(3))).single()

  "adding a juror to a round of N jurors per file, after review" should {

    "on Cancel keep a juror given files started, and stop one given nothing" in {
      val given = {
        val (round, js, y, admin) = setUpN(20, 1, jurors = 2)
        val r = round.getId
        val h = new MoveHooks(chunkRows = 2)
        h.failAt = Some(2)
        implicit val (service, scheduler) = inline(h)
        val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
        scheduler.cancel(job.id)
        (rows(r, y.getId).size === 2) and (roundUser(r, y.getId) must beSome(true)) and (flags(r) === (true -> 0)) and
          (RoundImageInvariants.violations(r) must beEmpty)
      }
      SharedTestDb.truncateAll()
      val nothing = {
        val (round, js, y, admin) = setUpN(20, 1, jurors = 2)
        val r = round.getId
        val h = new MoveHooks(chunkRows = 2)
        h.failAt = Some(1)
        implicit val (service, scheduler) = inline(h)
        val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
        scheduler.cancel(job.id)
        (rows(r, y.getId) must beEmpty) and (roundUser(r, y.getId) must beSome(false)) and (flags(r) === (true -> 0))
      }
      given and nothing
    }

    "keep started on Cancel a juror given files before a failed count check and its Retry" in {
      val (round, js, y, admin) = setUpN(20, 1, jurors = 2)
      val r = round.getId
      val h = new MoveHooks(chunkRows = 100) {
        @volatile var deleteAfter = true
        override def step(job: Job, ctx: StepContext): StepResult = {
          val before = moves.get
          val result = super.step(job, ctx)
          // after the move's commit: one of the juror's rows gone behind its back (the count check fails)
          if (moves.get > before && deleteAfter) {
            deleteAfter = false
            ctx.afterCommit(sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${y.getId} LIMIT 1".update(): Unit)
          }
          result
        }
      }
      implicit val (service, _) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
      val failed = JobJdbc.findById(job.id).get
      // Retry queued (no step commits), then Cancel
      val idle = new JobScheduler(Seq(h), JobScheduler.Settings())
      val requeued = idle.retry(job.id)
      val cancelled = idle.cancel(job.id)
      (failed.failure.map(_.key) must beSome(RoundRebalanceHandler.VerifyKey)) and (requeued must beRight) and
        (cancelled.map(_.status) must beSome(JobStatus.Cancelled)) and (RoundRebalanceHandler.filesGiven(cancelled.get) === 0L) and
        (roundUser(r, y.getId) must beSome(true)) and (rows(r, y.getId) must not(beEmpty)) and (flags(r) === (true -> 0))
    }

    "top up an active juror with a share counting their own unrated files" in {
      val (round, js, _, admin) = setUpN(30, 1, jurors = 3)
      val r = round.getId
      val Seq(a, b, c) = js
      // a has rated 6 of their 10: 4 unrated against 10 and 10
      rows(r, a.getId).take(6).foreach { case (p, _) => VoteJdbc.rate(p, a.getId, r, 1) }
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
      val preview = service.preview(round, a.getId)
      queued(service.add(round, a.getId, admin, signature(round), Seq(b.getId, c.getId)))
      val after = unrated(r, js.map(_.getId))
      (preview.topUp must beTrue) and (preview.files === 4L) and (after === Map(a.getId -> 8L, b.getId -> 8L, c.getId -> 8L)) and
        (roundUser(r, a.getId) must beSome(true)) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "not count or move a donor's rows of files deleted on Commons or that the receiver holds" in {
      val (round, js, y, admin) = setUpN(12, 2, jurors = 3)
      val r = round.getId
      val Seq(a, _, _) = js
      // a stopped juror of the round holding 3 files (the receiver), started again with a share
      RoundRebalanceService.setJurorActive(r, a.getId, active = false) must beTrue
      val held = rows(r, a.getId).map(_._1).toSet
      val others = js.tail.map(_.getId)
      val deleted = rows(r, others.head).map(_._1).find(p => !held(p)).get
      sql"UPDATE images SET deleted_at = NOW() WHERE page_id = $deleted".update()
      val counted = RoundRebalanceHandler.unratedCounts(r, others, receiver = Some(a.getId))
      val plain = RoundRebalanceHandler.unratedCounts(r, others)
      val before = others.map(o => o -> rows(r, o)).toMap
      implicit val (service, _) = inline(new TestRebalance(chunkRows = 100))
      queued(service.add(Round.findById(r).get, a.getId, admin, signature(round), others))
      val got = rows(r, a.getId).map(_._1).toSet -- held
      // the deleted file's rows count nowhere; the rows of files a holds don't count for a
      (plain(others.head) === before(others.head).size.toLong - 1) and
        others.map(o => counted(o) === plain(o) - before(o).count(p => held(p._1)).toLong).reduce(_ and _) and
        (got.contains(deleted) must beFalse) and (perPage(r).values.forall(_ == 2) must beTrue) and pairsUnique(r) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "rebaseline a donor's count on Retry by what changed while it was failed" in {
      val (round, js, y, admin) = setUpN(20, 1, jurors = 2)
      val r = round.getId
      val h = new MoveHooks(chunkRows = 2)
      h.failAt = Some(2)
      implicit val (service, scheduler) = inline(h)
      val job = queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
      // a rated row of a donor's gone meanwhile (by hand: a move aside keeps the count)
      val (page, _) = rows(r, js.head.getId).head
      VoteJdbc.rate(page, js.head.getId, r, 1)
      sql"DELETE FROM vote WHERE round_id = $r AND jury_id = ${js.head.getId} AND page_id = $page".update()
      val retried = scheduler.retry(job.id).toOption.get
      (retried.status === JobStatus.Done) and (flags(r) === (true -> 0))
    }

    "skip a row rated just before its lock, and refuse a donor's vote waiting on a moved row" in {
      val (round, js, y, admin) = setUpN(10, 1, jurors = 2)
      val r = round.getId
      val Seq(a, b) = js
      val h = new MoveHooks(chunkRows = 100)
      // before the locks: a's first candidate rated (committed, on its own connection)
      var ratedId = 0L
      h.preLock = Some(ids => {
        val id = ids.head
        val (page, juror, _) = voteRow(id).get
        require(VoteJdbc.rate(page, juror, r, 1) == 1)
        ratedId = id
      })
      // with the rows locked: b votes on one of them from another thread, waiting for the lock
      var waiting: Option[(Long, java.util.concurrent.Future[Int])] = None
      h.locked = Some(ids => {
        val id = ids.reverse.find(i => voteRow(i).exists(_._2 == b.getId)).get
        val (page, _, _) = voteRow(id).get
        val pool = java.util.concurrent.Executors.newSingleThreadExecutor()
        waiting = Some(page -> pool.submit(() => VoteJdbc.rate(page, b.getId, r, 1)))
        pool.shutdown()
        Thread.sleep(300) // it is blocked on the row's lock
      })
      implicit val (service, _) = inline(h)
      queued(service.add(round, y.getId, admin, signature(round), js.map(_.getId)))
      val (page, vote) = waiting.get
      val (ratedPage, ratedJuror, _) = voteRow(ratedId).get
      (vote.get(30, java.util.concurrent.TimeUnit.SECONDS) === 0) and
        (VoteJdbc.findBy(page, y.getId, r).map(_.rate) must beSome(0)) and (VoteJdbc.findBy(page, b.getId, r) must beNone) and
        (voteRow(ratedId).map(_._3) must beSome(1)) and (ratedJuror === a.getId) and
        (VoteJdbc.findBy(ratedPage, y.getId, r) must beNone) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }
}
