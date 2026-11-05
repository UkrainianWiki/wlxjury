package services.distribution

import org.intracer.wmua.cmd.InMemoryDistribution._
import db.scalikejdbc._
import org.intracer.wmua.cmd.{DistributeImages, SelectTopByRating}
import org.intracer.wmua.{AverageRule, ContestJury, CriteriaRate, Image, ImageWithRating, Vote}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import scalikejdbc._
import services.imports.DeletedFiles
import play.api.libs.json.{JsObject, Json}
import services.jobs.{Job, JobError, JobJdbc, JobScheduler, JobStatus, StepContext}
import services.{GalleryService, RoundService, TestRoundServices}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
import scala.concurrent.{Await, Future, Promise}
import scala.util.Random

/** The background, chunked distribution (RoundDistributionHandler): the job's lifecycle,
  * resume, cancel and restart, the round's visibility, the previous rounds' freezing,
  * criteria rates, and equivalence with the in-memory path (DistributeImages).
  */
class RoundDistributionJobSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val schedulers = mutable.Buffer.empty[JobScheduler]
  override protected def after: Any = { schedulers.foreach(_.stop()); schedulers.clear() }

  private val di = new DistributeImages(ImageJdbc)

  /** A handler whose chunks can fail, be slowed down or "crash" (an exception the
    * scheduler doesn't catch, as if the JVM died mid-chunk), and which records whether
    * the watched rounds were active at each chunk.
    */
  private class TestHandler(chunkRows: Int, watch: => Seq[Long] = Nil)
      extends RoundDistributionHandler(di, TestRoundServices.config(chunkRows), TestRoundServices.emptyCommons) {
    val chunks = new AtomicInteger()
    @volatile var failBeforeChunk: Option[Int] = None
    @volatile var failAfterInsertOfChunk: Option[Int] = None
    @volatile var crashAtChunk: Option[Int] = None
    @volatile var sleepMs = 0L
    /** Runs before the inserts of that chunk, in its transaction. */
    @volatile var atChunk: Option[(Int, () => Unit)] = None
    val watchedActive: mutable.Buffer[Boolean] = mutable.Buffer.empty

    override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = {
      val n = chunks.incrementAndGet()
      watchedActive.synchronized(watchedActive ++= watch.flatMap(id => Round.findById(id).map(_.active)))
      if (sleepMs > 0) Thread.sleep(sleepMs)
      atChunk.filter(_._1 == n).foreach(_._2())
      if (crashAtChunk.contains(n)) { crashAtChunk = None; throw new InterruptedException("simulated crash") }
      if (failBeforeChunk.contains(n)) { failBeforeChunk = None; throw new RuntimeException(s"simulated failure in chunk $n") }
      atPage.filter { case (p, _) => images.exists(_.pageId == p) }.foreach { case (_, f) => atPage = None; f(session) }
      if (failBeforePage.exists(p => images.exists(_.pageId == p))) {
        failBeforePage = None; throw new RuntimeException(s"simulated failure in chunk $n")
      }
    }
    /** Fails the chunk that would add this file (once). */
    @volatile var failBeforePage: Option[Long] = None
    /** Runs, in its transaction, before the inserts of the chunk that would add this file (once). */
    @volatile var atPage: Option[(Long, DBSession => Unit)] = None
    /** Runs after a Retry's commit, before the resumed job's first step (once). */
    @volatile var afterRetry: Option[() => Unit] = None
    /** Runs before the job's preparation, after it was queued, and gives the job prepared (once). */
    @volatile var beforePrepare: Option[Job => Job] = None

    override def prepare(job: Job): Unit = {
      val prepared = beforePrepare.fold(job) { f => beforePrepare = None; f(job) }
      super.prepare(prepared)
    }

    override def onRetry(job: Job, ctx: StepContext): JsObject = {
      val state = super.onRetry(job, ctx)
      afterRetry.foreach { f => afterRetry = None; ctx.afterCommit(f()) }
      state
    }

    override protected def afterInsert(round: Round, rows: Seq[Vote])(implicit session: DBSession): Unit =
      if (failAfterInsertOfChunk.contains(chunks.get)) {
        failAfterInsertOfChunk = None
        throw new RuntimeException(s"simulated failure after the inserts of chunk ${chunks.get}")
      }
  }

  private def inlineService(h: RoundDistributionHandler) = TestRoundServices.roundService(h)

  private def backgroundService(h: RoundDistributionHandler, workers: Int = 2): (RoundService, JobScheduler) = {
    val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = workers,
      kindLimits = Map(RoundDistributionHandler.Kind -> workers),
      pollInterval = 50.millis, heartbeatInterval = 200.millis, lease = 60.seconds))
    schedulers += s
    s.start()
    (new RoundService(Round, s, h), s)
  }

  // ── fixture ────────────────────────────────────────────────────────────────

  /** `n` images in the contest's category, ids 1..n: two regions, sizes, a few videos. */
  private def images(n: Int): Seq[Image] = (1 to n).map { i =>
    val video = i % 7 == 0
    Image(i.toLong, if (video) s"File:Video$i.webm" else s"File:Image$i.jpg", None, None,
      width = if (i % 3 == 0) 4000 else 1000, height = if (i % 3 == 0) 3000 else 800,
      monumentId = Some(if (i % 2 == 0) s"12-345-$i" else s"14-101-$i"),
      size = Some(if (i % 5 == 0) 3 * 1024 * 1024 else 500 * 1024),
      mediaType = Some(if (video) "VIDEO" else "BITMAP"))
  }

  private def setUp(n: Int, jurors: Int): (ContestJury, Seq[Image], Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images(n)
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    (contest, imgs, createUsers("jury", (1 to jurors): _*).sortBy(_.getId))
  }

  private def newRound(contest: ContestJury, name: String, distribution: Int = 1, rates: Rates = Round.binaryRound) =
    Round(None, 0, Some(name), contest.getId, distribution = distribution, rates = rates, active = true)

  /** A previous round with every image to every juror, and random rates; with `worst`, a rated
    * round with the worst mark, its rates from -1 (✗) up.
    */
  private def ratedRound(contest: ContestJury, jurors: Seq[User], rates: Rates, seed: Int, name: String,
      worst: Boolean = false): Round = {
    val round = roundDao.create(Round(None, 1, Some(name), contest.getId, rates = rates, active = true, worstMark = worst))
    round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
    di.distributeImages(round, jurors, Nil)
    val random = new Random(seed)
    val votes = VoteJdbc.byRound(round.getId).map { s =>
      val rate =
        if (rates == Round.binaryRound) random.nextInt(3) - 1
        else if (worst) random.nextInt(rates.maxRate + 2) - 1
        else random.nextInt(rates.maxRate + 1)
      Seq(rate, s.pageId, s.juryId, round.getId)
    }
    // one batch in one transaction: VoteJdbc.rate per vote was ~1400 autocommits (9 s)
    val c = VoteJdbc.column
    DB.localTx { implicit session =>
      withSQL {
        update(VoteJdbc).set(c.rate -> sqls.?)
          .where.eq(c.pageId, sqls.?).and.eq(c.juryId, sqls.?).and.eq(c.roundId, sqls.?)
      }.batch(votes: _*).apply()
    }
    round
  }

  private def pageIds(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

  /** A file leaves the round (its votes and its round_image row, as VoteJdbc.removeImage takes them). */
  private def removeFromRound(roundId: Long, pageId: Long): Unit = DB.autoCommit { implicit s =>
    sql"DELETE FROM vote WHERE round_id = $roundId AND page_id = $pageId".update()
    sql"DELETE FROM round_image WHERE round_id = $roundId AND page_id = $pageId".update()
  }

  /** A round created with files 1-20, then files 101-106 added to the contest's category, for
    * "Distribute new files" (3 files a chunk).
    */
  private def newFilesRound() = {
    val (contest, imgs, jurors) = setUp(20, 3)
    val h = new TestHandler(chunkRows = 3)
    val service = inlineService(h)
    val roundId = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId)).getId
    jobOf(roundId).status === JobStatus.Done
    val more = (101 to 106).map(i => Image(i.toLong, s"File:More$i.jpg", None, None, 800, 600, Some(s"12-345-$i")))
    imageDao.batchInsert(more)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
    (contest, imgs, jurors, h, service, roundId)
  }

  private def jobOf(roundId: Long) = {
    val latest = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
    latest(roundId)
  }

  private def stateOf(roundId: Long) = RoundDistributionHandler.stateOf(jobOf(roundId))

  /** For distribution x: the image at position i (by page id) has jurors (i + j) % n. */
  private def rotationHolds(roundId: Long, jurors: Seq[User], x: Int) = {
    val rows = VoteJdbc.byRound(roundId)
    val byImage = rows.groupBy(_.pageId).toSeq.sortBy(_._1)
    byImage.zipWithIndex.forall { case ((_, sel), i) =>
      sel.map(_.juryId).toSet == (0 until x).map(j => jurors((i + j) % jurors.size).getId).toSet
    }
  }

  // ── lifecycle ──────────────────────────────────────────────────────────────

  "a round distribution job" should {

    "create a hidden round, distribute it in chunks, then show it to its jurors" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val created = inlineService(new TestHandler(chunkRows = 4)).createNewRound(newRound(contest, "R1"), jurors.map(_.getId))
      created.active must beTrue
      created.distributing must beFalse
      VoteJdbc.byRound(created.getId).size === 20
      pageIds(created.getId) === imgs.map(_.pageId).toSet
      val job = jobOf(created.getId)
      job.status === JobStatus.Done
      job.progressDone === Some(20L)
      job.progressTotal === Some(20L)
      stateOf(created.getId).imagesAdded === 20
      RoundImageCounts.get(Seq(created.getId))(ImageJdbc.imageCountByRounds)(created.getId) === 20
      rotationHolds(created.getId, jurors, 1) must beTrue
    }

    "move aside the votes of a file deleted on Commons while it ran, and still pass its count check" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3) // image 1 is in chunk 1
      val deletion = Promise[Seq[Long]]()
      // In its own transaction, as CommonsFileCheck does, while chunk 2 or 3 runs: either way
      // the result is the same
      h.atChunk = Some(2 -> (() =>
        deletion.completeWith(Future(DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))))(
          scala.concurrent.ExecutionContext.global))))
      val created = inlineService(h).createNewRound(newRound(contest, "R1"), jurors.map(_.getId))
      Await.result(deletion.future, 60.seconds)

      jobOf(created.getId).status === JobStatus.Done
      created.active must beTrue
      pageIds(created.getId) === imgs.map(_.pageId).toSet - 1L
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM vote_aside WHERE page_id = 1".map(_.int(1)).single()) ===
        Some(1)
      RoundImageCounts.get(Seq(created.getId))(ImageJdbc.imageCountByRounds)(created.getId) === 19
    }

    "move aside at its end the votes of a file it added as the file was marked deleted" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3) // image 1 is in chunk 1
      // After chunk 1 read its candidates, before it inserts them: the file has no votes yet
      h.atChunk = Some(1 -> (() =>
        Await.result(Future(DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))))(
          scala.concurrent.ExecutionContext.global), 60.seconds)))
      val created = inlineService(h).createNewRound(newRound(contest, "R1"), jurors.map(_.getId))

      jobOf(created.getId).status === JobStatus.Done
      pageIds(created.getId) === imgs.map(_.pageId).toSet - 1L
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM vote_aside WHERE page_id = 1".map(_.int(1)).single()) ===
        Some(1)
      RoundImageCounts.get(Seq(created.getId))(ImageJdbc.imageCountByRounds)(created.getId) === 19
    }

    "leave the votes of deleted files in a round that isn't open until it opens" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val service = inlineService(new TestHandler(chunkRows = 4))
      val roundId = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId)).getId
      Round.setActiveGuarded(roundId, active = false)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      val extra = images(22).drop(20)
      imageDao.batchInsert(extra)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, extra)

      service.distributeNewImages(roundId) === 2
      pageIds(roundId) === (imgs ++ extra).map(_.pageId).toSet

      Round.setActiveGuarded(roundId, active = true)
      pageIds(roundId) === (imgs ++ extra).map(_.pageId).toSet - 1L
    }

    "give a file restored on Commons while it ran its votes back, not new jurors" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val roundId = inlineService(new TestHandler(chunkRows = 4)).createNewRound(newRound(contest, "R1"), jurors.map(_.getId)).getId
      val voted = VoteJdbc.byRound(roundId).find(_.pageId == 20L).get
      VoteJdbc.rate(20L, voted.juryId, roundId, 1)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(20L)))
      val extra = images(26).drop(20)
      imageDao.batchInsert(extra)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, extra)

      val h = new TestHandler(chunkRows = 3) // image 20 is in chunk 7
      val restore = Promise[Seq[Long]]()
      // In its own transaction, as an import does, while the chunks run
      h.atChunk = Some(1 -> (() =>
        restore.completeWith(Future(DB.localTx(implicit s => DeletedFiles.restore(Seq(20L))))(
          scala.concurrent.ExecutionContext.global))))
      inlineService(h).distributeNewImages(roundId) === 6
      Await.result(restore.future, 60.seconds)

      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === (imgs ++ extra).map(_.pageId).toSet
      VoteJdbc.byRound(roundId).filter(_.pageId == 20L).map(s => (s.juryId, s.rate)) === Seq((voted.juryId, 1))
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM vote_aside".map(_.int(1)).single()) === Some(0)
      RoundImageCounts.get(Seq(roundId))(ImageJdbc.imageCountByRounds)(roundId) === 26
    }

    "not take the images it added that went missing before it failed as never added, on a retry" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3)
      // Committed with chunk 2: an image chunk 1 added goes missing
      // (its votes and its round_image row, V84, as VoteJdbc.removeImage takes them)
      h.atChunk = Some(2 -> (() => DB.autoCommit { implicit s =>
        sql"DELETE FROM vote WHERE page_id = 1".update()
        sql"DELETE FROM round_image WHERE page_id = 1".update()
      }))
      h.failBeforeChunk = Some(3)
      val service = inlineService(h)
      val roundId = service.submit(newRound(contest, "R1"), jurors.map(_.getId), None).round.getId
      jobOf(roundId).status === JobStatus.Failed
      pageIds(roundId) === (2L to 6L).toSet

      service.distributeNewImages(roundId) must throwA[RoundService.RoundDistributionFailed]
      jobOf(roundId).failure must beSome(_root_.services.jobs.JobError(_root_.services.jobs.JobError.CountMismatchKey, Seq(roundId.toString, "19", "20")))
      service.distributeNewImages(roundId) // the retry after a failed count check rescans
      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === imgs.map(_.pageId).toSet
    }

    "pass its count check on a retry after files were deleted while it ran and while it was failed" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3) // image 1 is in chunk 1
      val deletion = Promise[Seq[Long]]()
      // Waits for chunk 2 to commit, then runs before chunk 3, which fails
      h.atChunk = Some(2 -> (() =>
        deletion.completeWith(Future(DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L))))(
          scala.concurrent.ExecutionContext.global))))
      h.failBeforeChunk = Some(3)
      val service = inlineService(h)
      val submitted = service.submit(newRound(contest, "R1"), jurors.map(_.getId), Some(java.util.UUID.randomUUID().toString))
      Await.result(deletion.future, 60.seconds)
      val roundId = submitted.round.getId

      jobOf(roundId).status === JobStatus.Failed
      pageIds(roundId) === imgs.map(_.pageId).take(6).toSet - 1L // moved aside at once: a round being created is open
      val deletedWhileFailed = pageIds(roundId).min
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(deletedWhileFailed))) must contain(roundId)

      service.distributeNewImages(roundId) // resumes the failed job
      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === imgs.map(_.pageId).toSet - 1L - deletedWhileFailed
      roundDao.findById(roundId).get.active must beTrue
    }

    "pass its count check on a retry after a crash, when the round's other files changed while it was failed" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3)
      val service = inlineService(h)
      val roundId = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId)).getId
      jobOf(roundId).status === JobStatus.Done
      val more = (101 to 106).map(i => Image(i.toLong, s"File:More$i.jpg", None, None, 800, 600, Some(s"12-345-$i")))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)

      // "Distribute new files" crashes at the chunk of file 104, after adding some of the new ones
      h.failBeforePage = Some(104L)
      scala.util.Try(service.distributeNewImages(roundId))
      val failed = jobOf(roundId)
      failed.status === JobStatus.Failed
      failed.failure.map(_.key) must beSome(_root_.services.jobs.JobError.CrashedKey)
      val addedBefore = pageIds(roundId) -- imgs.map(_.pageId)
      addedBefore must not(beEmpty)
      addedBefore must not(contain(104L))

      // while it is failed, a file the creation added leaves the round: its count goes down by one
      DB.autoCommit { implicit s =>
        sql"DELETE FROM vote WHERE round_id = $roundId AND page_id = 1".update()
        sql"DELETE FROM round_image WHERE round_id = $roundId AND page_id = 1".update()
      }

      service.distributeNewImages(roundId) // resumes the failed job: its count check must pass
      val done = jobOf(roundId)
      done.id === failed.id
      done.status === JobStatus.Done
      done.error must beNone
      pageIds(roundId) === imgs.map(_.pageId).toSet - 1L ++ more.map(_.pageId)
      RoundImageInvariants.violations(roundId) must beEmpty
    }

    "rebaseline at the resumed job's first step: a change while it waited after the Retry passes too" in {
      val (contest, imgs, jurors, h, service, roundId) = newFilesRound()
      h.failBeforePage = Some(104L)
      scala.util.Try(service.distributeNewImages(roundId))
      jobOf(roundId).status === JobStatus.Failed
      // committed after the Retry's transaction, before its first step (as while it waits in the queue)
      h.afterRetry = Some(() => removeFromRound(roundId, 1L))
      service.distributeNewImages(roundId)
      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === imgs.map(_.pageId).toSet - 1L ++ (101L to 106L)
    }

    "fail its count check when one of its own files went missing and another's was added, though the total is right" in {
      val (contest, imgs, jurors, h, service, roundId) = newFilesRound()
      imageDao.batchInsert(Seq(Image(200L, "File:Other200.jpg", None, None, 800, 600, Some("12-345-200"))))
      // while it runs: file 101, which it added, leaves the round, and another path adds file 200
      h.atPage = Some(104L -> { implicit s =>
        sql"DELETE FROM vote WHERE round_id = $roundId AND page_id = 101".update()
        sql"DELETE FROM round_image WHERE round_id = $roundId AND page_id = 101".update()
        sql"""INSERT INTO round_image (round_id, page_id, status, added_how, added_at)
              VALUES ($roundId, 200, 'in', 'repair', NOW(3))""".update()
      })
      service.distributeNewImages(roundId) must throwA[RoundService.RoundDistributionFailed]
      val failed = jobOf(roundId)
      // the total: 20 + 6 - 1 + 1 = 26, as expected; its own rows: 5 of 6
      failed.failure must beSome(JobError(JobError.CountMismatchKey, Seq(roundId.toString, "26", "27")))
      // the retry after a failed count check rescans: file 101 is added again
      service.distributeNewImages(roundId)
      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === imgs.map(_.pageId).toSet ++ (101L to 106L)
    }

    "fail once, then pass on a Retry, when a file it added was removed and added again by another job" in {
      // Its own rows go down by one and the others' up by one: the exact check of its own rows
      // fails, though nothing is missing. The Retry rescans with fresh accounting: the file has
      // a row (the other job's), so it is skipped, and the check passes. No file lost or doubled.
      val (contest, imgs, jurors, h, service, roundId) = newFilesRound()
      h.atPage = Some(104L -> { implicit s =>
        sql"""UPDATE round_image SET added_how = 'repair', added_by_job_id = NULL
              WHERE round_id = $roundId AND page_id = 101""".update()
      })
      service.distributeNewImages(roundId) must throwA[RoundService.RoundDistributionFailed]
      jobOf(roundId).failure.map(_.key) must beSome(JobError.CountMismatchKey)
      service.distributeNewImages(roundId)
      jobOf(roundId).status === JobStatus.Done
      pageIds(roundId) === imgs.map(_.pageId).toSet ++ (101L to 106L)
      VoteJdbc.byRound(roundId).map(_.pageId).size === 26 // one vote each: none doubled
      RoundImageInvariants.violations(roundId) must beEmpty
    }

    "not resume a distribution given up after its attempts, from \"Distribute new files\" or the form again" in {
      val (contest, _, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3)
      val scheduler = TestRoundServices.inlineScheduler(h)
      val service = new RoundService(Round, scheduler, h)
      val token = java.util.UUID.randomUUID().toString
      val form = newRound(contest, "R1")
      val roundId = service.submit(form, jurors.map(_.getId), Some(token)).round.getId
      val job = jobOf(roundId)
      val givenUp = JobError.attemptsExhausted(5, "ref123")
      sql"UPDATE job SET status = 'failed', attempts = 5, error = ${givenUp.encode} WHERE id = ${job.id}".update()(AutoSession)

      service.distributeNewFiles(roundId) must beLike {
        case g: RoundService.GivenUp => (g.job.id === job.id) and (g.reference === "ref123")
      }
      service.submit(form, jurors.map(_.getId), Some(token)) must beLike {
        case r: RoundService.Resubmitted =>
          (r.resumed must beFalse) and (r.givenUp must beTrue) and (r.messageKey === "round.resubmitted.attempts") and
            (r.reference === "ref123")
      }
      val after = JobJdbc.findById(job.id).get
      after.status === JobStatus.Failed
      after.attempts === 5 // neither path granted it more runs
      after.failure must beSome(givenUp)

      scheduler.retry(job.id) must beRight // the job page's Retry: the count starts again
      val retried = JobJdbc.findById(job.id).get
      retried.status === JobStatus.Done
      retried.attempts === 1
    }

    "give back the claim of an ordinary (recorded) failure; an implicit resume takes one, without a reset" in {
      val (_, _, _, h, service, roundId) = newFilesRound()
      h.failBeforePage = Some(104L)
      scala.util.Try(service.distributeNewImages(roundId))
      val failed = jobOf(roundId)
      failed.status === JobStatus.Failed
      failed.attempts === 0 // its one claim ended with a recorded outcome: it doesn't count
      service.distributeNewImages(roundId)
      val done = JobJdbc.findById(failed.id).get
      done.status === JobStatus.Done
      done.attempts === 1 // the resume's claim
    }

    "fail at a chunk, keep the committed chunks, and resume from the cursor without duplicates" in {
      val (contest, _, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3) // 3 images per chunk
      h.failBeforeChunk = Some(3)
      val service = inlineService(h)
      val submitted = service.submit(newRound(contest, "R1"), jurors.map(_.getId), Some(java.util.UUID.randomUUID().toString))
      submitted.job.map(_.status) === Some(JobStatus.Failed)
      val roundId = submitted.round.getId
      VoteJdbc.byRound(roundId).size === 6
      stateOf(roundId).cursor === 6L
      stateOf(roundId).position === 6L
      val failed = jobOf(roundId)
      (failed.failure.map(_.key) must beSome(_root_.services.jobs.JobError.CrashedKey)) and
        (failed.failure.flatMap(_.reference) must beSome) and (failed.error must not(beSome(contain("simulated failure"))))

      service.distributeNewImages(roundId) === 20 // resumes the failed job: 6 + 14 by it
      VoteJdbc.byRound(roundId).size === 20
      VoteJdbc.byRound(roundId).map(s => (s.pageId, s.juryId)).distinct.size === 20
      rotationHolds(roundId, jurors, 1) must beTrue // the rotation went on across the failure
      roundDao.findById(roundId).get.active must beTrue
    }

    "write no criteria rates in a criteria round: its votes write them (V83)" in {
      val (c, _, jurors) = setUp(12, 2)
      implicit val contest: ContestJury = c
      val round = roundDao.create(newRound(contest, "Criteria", distribution = 2).copy(number = 1, criterionIds = Seq(6L, 7L)))
      round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
      val service = inlineService(new TestHandler(chunkRows = 8))

      service.distributeNewImages(round.getId) === 12
      VoteJdbc.byRound(round.getId).size === 24
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM criteria_rate".map(_.int(1)).single()) === Some(0)
      RoundCriterion.idsOf(round.getId) === Seq(6L, 7L)
      roundDao.findById(round.getId).map(_.hasCriteria) must beSome(true)
    }

    "keep a juror deactivated before a retry out of the rest of the distribution" in {
      val (contest, _, jurors) = setUp(12, 3)
      val h = new TestHandler(chunkRows = 3)
      h.failBeforeChunk = Some(2)
      val service = inlineService(h)
      val roundId = service.submit(newRound(contest, "R1"), jurors.map(_.getId), None).round.getId
      RoundUser.setActive(roundId, jurors.last.getId, active = false)
      service.distributeNewImages(roundId)
      VoteJdbc.byRound(roundId).filter(_.pageId > 3).map(_.juryId).toSet === jurors.init.map(_.getId).toSet
    }
  }

  // ── visibility and freezing ────────────────────────────────────────────────

  "a round being distributed" should {

    "stay hidden from its jurors, refuse to start and take no votes until its count check passes" in {
      val (c, _, jurors) = setUp(9, 3)
      implicit val contest: ContestJury = c
      val admin = createUsers("admin", 50).head
      val h = new TestHandler(chunkRows = 3)
      h.failBeforeChunk = Some(2)
      val service = inlineService(h)
      val roundId = service.submit(newRound(contest, "R1"), jurors.map(_.getId), None).round.getId
      val hidden = roundDao.findById(roundId).get
      hidden.distributing must beTrue
      hidden.active must beFalse
      hidden.openToJurors must beFalse
      Round.activeRounds(contest.getId).flatMap(_.id) must not(contain(roundId))
      val juror = jurors.head
      val gallery = new GalleryService
      gallery.isNotAuthorized(juror, Some(hidden.copy(juryOrgView = true)), contest.getId, Nil) must beTrue
      gallery.isNotAuthorized(admin, Some(hidden), contest.getId, Nil) must beFalse
      Round.setActiveGuarded(roundId, active = true) must beFalse
      roundDao.findById(roundId).get.active must beFalse

      service.distributeNewImages(roundId)
      val shown = roundDao.findById(roundId).get
      shown.distributing must beFalse
      shown.openToJurors must beTrue
      Round.activeRounds(contest.getId).flatMap(_.id) must contain(roundId)
    }

    "freeze its previous rounds for the whole job, restore them on failure and close them when created" in {
      val (contest, _, jurors) = setUp(12, 3)
      val prev = ratedRound(contest, jurors, Round.binaryRound, seed = 1, "Prev")
      val h = new TestHandler(chunkRows = 2, watch = Seq(prev.getId))
      h.failBeforeChunk = Some(2)
      val service = inlineService(h)
      val next = newRound(contest, "Next").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1))
      val roundId = service.submit(next, jurors.map(_.getId), None).round.getId
      roundDao.findById(prev.getId).get.active must beTrue // restored after the failure
      roundDao.findById(prev.getId).get.freezeCount === 0

      service.distributeNewImages(roundId)
      h.watchedActive.toSeq must not(contain(true)) // frozen during every chunk of both runs
      roundDao.findById(prev.getId).get.active must beFalse // a creation closes them
      roundDao.findById(prev.getId).get.freezeCount === 0
    }

    "restore the previous rounds after \"Distribute new files\"" in {
      val (contest, _, jurors) = setUp(10, 2)
      val prev = ratedRound(contest, jurors, Round.binaryRound, seed = 2, "Prev")
      val round = roundDao.create(newRound(contest, "Next").copy(number = 2, previous = Some(prev.getId.toString),
        prevSelectedBy = Some(1)))
      round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
      inlineService(TestRoundServices.handler()).distributeNewImages(round.getId)
      roundDao.findById(prev.getId).get.active must beTrue
    }

    "count nested freezes, so one distribution's end doesn't unfreeze a round another still reads" in {
      val (contest, _, _) = setUp(1, 1)
      val prev = roundDao.create(Round(None, 1, Some("Prev"), contest.getId, active = true))
      def active = roundDao.findById(prev.getId).get.active
      DB.localTx { implicit s => Round.freeze(Seq(prev.getId)); Round.freeze(Seq(prev.getId)) }
      active must beFalse
      DB.localTx(implicit s => Round.release(Seq(prev.getId), close = false))
      active must beFalse // the other distribution still reads it
      Round.setActiveGuarded(prev.getId, active = false) must beTrue // the organizer stops it meanwhile
      active must beFalse
      Round.setActiveGuarded(prev.getId, active = true) must beTrue // ... and starts it again
      active must beFalse // applies at the release
      DB.localTx(implicit s => Round.release(Seq(prev.getId), close = false))
      active must beTrue

      DB.localTx { implicit s => Round.freeze(Seq(prev.getId)); Round.freeze(Seq(prev.getId)) }
      DB.localTx(implicit s => Round.release(Seq(prev.getId), close = true)) // a creation completed
      DB.localTx(implicit s => Round.release(Seq(prev.getId), close = false))
      active must beFalse
      roundDao.findById(prev.getId).get.freezeCount === 0
    }
  }

  // ── background: cancel, restart, parallel jobs ─────────────────────────────

  "in the background, a round distribution" should {

    "stop between chunks when cancelled, keep its rows, stay hidden and restore the previous rounds" in {
      val (contest, _, jurors) = setUp(30, 2)
      val prev = ratedRound(contest, jurors, Round.binaryRound, seed = 3, "Prev")
      VoteJdbc.byRound(prev.getId).foreach(s => VoteJdbc.rate(s.pageId, s.juryId, prev.getId, 1))
      val h = new TestHandler(chunkRows = 2) // 2 images per chunk
      h.sleepMs = 100
      val (service, scheduler) = backgroundService(h)
      val next = newRound(contest, "Next").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1))
      val submitted = service.submit(next, jurors.map(_.getId), None)
      val roundId = submitted.round.getId
      eventually(50, 100.millis)(stateOf(roundId).imagesAdded must beGreaterThan(0L))
      roundDao.findById(prev.getId).get.active must beFalse

      val cancelled = scheduler.cancel(submitted.job.get.id).get
      cancelled.status === JobStatus.Cancelled
      Thread.sleep(400)
      val added = RoundDistributionHandler.imagesAdded(cancelled)
      added must beLessThan(30L)
      VoteJdbc.byRound(roundId).map(_.pageId).distinct.size.toLong === added // kept, no more after
      roundDao.findById(roundId).get.distributing must beTrue // a partial round is never shown
      roundDao.findById(roundId).get.active must beFalse
      roundDao.findById(prev.getId).get.active must beTrue
      roundDao.findById(prev.getId).get.freezeCount === 0

      // finishing it later: "Distribute new files" completes the creation
      service.distributeNewImages(roundId) === (30 - added).toInt
      roundDao.findById(roundId).get.openToJurors must beTrue
    }

    "be resumed by a scheduler after its instance died mid-job, from its committed chunks" in {
      val (contest, _, jurors) = setUp(20, 3)
      val prev = ratedRound(contest, jurors, Round.binaryRound, seed = 4, "Prev")
      VoteJdbc.byRound(prev.getId).foreach(s => VoteJdbc.rate(s.pageId, s.juryId, prev.getId, 1))
      val dying = new TestHandler(chunkRows = 3)
      dying.crashAtChunk = Some(3)
      val next = newRound(contest, "Next").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1))
      inlineService(dying).submit(next, jurors.map(_.getId), None) must throwA[InterruptedException]
      val roundId = roundDao.findByContest(contest.getId).find(_.name.contains("Next")).get.getId
      jobOf(roundId).status === JobStatus.Running // its instance "died" holding it
      VoteJdbc.byRound(roundId).size === 6
      roundDao.findById(prev.getId).get.active must beFalse // still frozen
      sql"UPDATE job SET heartbeat_at = NOW(3) - INTERVAL 5 MINUTE".update()(AutoSession)

      val (_, scheduler) = backgroundService(new TestHandler(chunkRows = 3))
      val done = scheduler.await(jobOf(roundId).id, 20.seconds)
      done.status === JobStatus.Done
      done.owner must beNone
      done.attempts === 2
      VoteJdbc.byRound(roundId).size === 20
      VoteJdbc.byRound(roundId).map(_.pageId).distinct.size === 20
      rotationHolds(roundId, jurors, 1) must beTrue
      roundDao.findById(roundId).get.openToJurors must beTrue
      roundDao.findById(prev.getId).get.active must beFalse
      roundDao.findById(prev.getId).get.freezeCount === 0
    }

    "complete several rounds distributed at once, each exactly" in {
      val (contest, imgs, jurors) = setUp(40, 4)
      val (service, scheduler) = backgroundService(new TestHandler(chunkRows = 10), workers = 2)
      val submitted = Seq(
        newRound(contest, "All", distribution = 2),
        newRound(contest, "Region 12", distribution = 1).copy(regions = Some("12")),
        newRound(contest, "Every juror", distribution = 0),
        newRound(contest, "Videos", distribution = 1).copy(mediaType = Some("video"))
      ).map(r => service.submit(r, jurors.map(_.getId), None))
      submitted.map(s => scheduler.await(s.job.get.id, 30.seconds).status).toSet === Set(JobStatus.Done)
      val ids = submitted.map(_.round.getId)
      VoteJdbc.byRound(ids(0)).size === 80
      pageIds(ids(1)) === imgs.filter(_.monumentId.exists(_.startsWith("12"))).map(_.pageId).toSet
      VoteJdbc.byRound(ids(2)).size === 160
      pageIds(ids(3)) === imgs.filter(_.isVideo).map(_.pageId).toSet
      // balanced: per-juror counts differ by at most one
      val perJuror = VoteJdbc.byRound(ids(0)).groupBy(_.juryId).values.map(_.size)
      (perJuror.max - perJuror.min) must beLessThanOrEqualTo(1)
    }
  }

  // ── equivalence with the in-memory path ────────────────────────────────────

  "the streamed selection" should {

    /** The image set the old in-memory path selects for `template` (on an empty round of
      * the same settings), and the one the job distributes, with small chunks.
      */
    def both(contest: ContestJury, jurors: Seq[User], template: Round, chunkRows: Int = 3): (Set[Long], Set[Long]) = {
      val probe = roundDao.create(template.copy(number = 99, name = Some("probe")))
      val prevRounds = RoundDistributionHandler.previousRounds(probe)
      val expected = di.imagesByRound(probe, prevRounds).map(_.pageId).toSet
      prevRounds.foreach(r => Round.setActive(r.getId, active = true))
      val created = inlineService(new TestHandler(chunkRows)).createNewRound(template, jurors.map(_.getId))
      prevRounds.foreach(r => Round.setActive(r.getId, active = true))
      (pageIds(created.getId), expected)
    }

    "match the old path for a first round and its per-image filters" in {
      val (contest, _, jurors) = setUp(50, 3)
      val filters = Seq[Round => Round](
        identity,
        _.copy(regions = Some("12")),
        _.copy(monuments = Some("14-101-3,14-101-5,12-345-8")),
        _.copy(minMpx = Some(5)),
        _.copy(minImageSize = Some(1)),
        _.copy(mediaType = Some("image")),
        _.copy(mediaType = Some("video")),
        _.copy(regions = Some("14"), minMpx = Some(5), mediaType = Some("image"))
      )
      filters.zipWithIndex.map { case (f, i) =>
        val (got, expected) = both(contest, jurors, f(newRound(contest, s"F$i")))
        (got === expected) and (expected must not(beEmpty))
      }.reduce(_ and _)
    }

    "match the old path for rounds from previous rounds" in {
      val (contest, _, jurors) = setUp(60, 3)
      val binary = ratedRound(contest, jurors, Round.binaryRound, seed = 11, "Binary")
      val binary2 = ratedRound(contest, jurors, Round.binaryRound, seed = 12, "Binary 2")
      val rated = ratedRound(contest, jurors, Round.ratesById(10), seed = 13, "Rated")
      // the score rules (V90): a sum round (an optionalRate round's since V90) is scored, ranked
      // and filtered by the sum; an assigned one by the average over its assigned jurors
      val sum = ratedRound(contest, jurors, Round.ratesById(10), seed = 14, "Sum")
      sql"UPDATE round SET average_rule = 'sum' WHERE id = ${sum.getId}".update()(AutoSession)
      val assigned = ratedRound(contest, jurors, Round.ratesById(10), seed = 15, "Assigned")
      sql"UPDATE round SET average_rule = 'assigned' WHERE id = ${assigned.getId}".update()(AutoSession)
      def from(rounds: Round*) = newRound(contest, "Next").copy(previous = Some(rounds.map(_.getId).mkString(",")))
      val cases = Seq(
        from(binary).copy(prevSelectedBy = Some(1)),
        from(binary).copy(prevSelectedBy = Some(2)),
        from(binary).copy(prevSelectedBy = Some(0)),
        from(binary),
        from(binary).copy(prevSelectedBy = Some(1), regions = Some("12")),
        from(binary, binary2).copy(prevSelectedBy = Some(1)),
        from(rated).copy(prevMinAvgRate = Some(BigDecimal(5))),
        from(rated).copy(prevSelectedBy = Some(1), prevMinAvgRate = Some(BigDecimal("6.5"))),
        // a minimum score: the sum
        from(sum).copy(prevMinAvgRate = Some(BigDecimal(12))),
        from(assigned).copy(prevMinAvgRate = Some(BigDecimal(4)))
      )
      // a sum round's minimum score is its files' rate sum, which its pages show
      val sums = VoteJdbc.byRound(sum.getId).groupBy(_.pageId).view.mapValues(_.map(_.rate).sum).toMap
      val (fromSum, _) = both(contest, jurors, from(sum).copy(prevMinAvgRate = Some(BigDecimal(12))))
      (fromSum === sums.collect { case (p, s) if s >= 12 => p }.toSet) and (fromSum must not(beEmpty)) and
        cases.map { template =>
          val (got, expected) = both(contest, jurors, template)
          got === expected
        }.reduce(_ and _)
    }

    "match the old path from a rated round with the worst mark: its ✗ as 0, as a vote, and as a rejection" in {
      val (contest, _, jurors) = setUp(60, 3)
      val worst = ratedRound(contest, jurors, Round.ratesById(5), seed = 31, "Worst", worst = true)
      def from(r: Round) = newRound(contest, "Next").copy(previous = Some(r.getId.toString))
      val votes = VoteJdbc.byRound(worst.getId).groupBy(_.pageId)
      val worstMarks = votes.view.mapValues(_.count(_.rate < 0)).toMap
      // the rated rule: the grades' sum (✗ as 0) over the votes other than 0, ✗ included
      val averages = votes.view.mapValues { vs =>
        val voted = vs.count(_.rate != 0)
        if (voted == 0) 0.0 else vs.map(v => math.max(v.rate, 0)).sum.toDouble / voted
      }.toMap
      val cases = Seq(
        from(worst).copy(prevRejectedBy = Some(1)),
        from(worst).copy(prevRejectedBy = Some(2)),
        from(worst).copy(prevMinAvgRate = Some(BigDecimal(2))),
        from(worst).copy(prevRejectedBy = Some(2), prevMinAvgRate = Some(BigDecimal("1.5"))),
        from(worst).copy(prevSelectedBy = Some(2), prevRejectedBy = Some(1))
      )
      val (noWorst, _) = both(contest, jurors, from(worst).copy(prevRejectedBy = Some(1)))
      val (atLeast2, _) = both(contest, jurors, from(worst).copy(prevMinAvgRate = Some(BigDecimal(2))))
      (worstMarks.values.exists(_ > 0) must beTrue) and (worstMarks.values.exists(_ == 0) must beTrue) and
        (noWorst === worstMarks.collect { case (p, 0) => p }.toSet) and
        (atLeast2 === averages.collect { case (p, a) if a >= 2 => p }.toSet) and (atLeast2 must not(beEmpty)) and
        cases.map { template =>
          val (got, expected) = both(contest, jurors, template)
          got === expected
        }.reduce(_ and _)
    }

    "pick the same top-N from a rated round with the worst mark as the in-memory filter, the rejected left out" in {
      val (contest, _, jurors) = setUp(60, 3)
      val worst = ratedRound(contest, jurors, Round.ratesById(5), seed = 32, "Worst", worst = true)
      val template = newRound(contest, "Top ✗").copy(previous = Some(worst.getId.toString), topImages = Some(8),
        prevRejectedBy = Some(2))
      val rejections = ImageJdbc.byRoundMerged(worst.getId, tally = true, grades = true).map(i => i.pageId -> i.rejections).toMap
      val probe = roundDao.create(template.copy(number = 99))
      val candidates = ImageJdbc.byRoundMerged(worst.getId, tally = true, grades = true)
        .filter(i => rejections(i.pageId) < 2).sortBy(i => (-i.votes.head.rate, i.pageId))
      val expected = SelectTopByRating(8, worst)().apply(di.perImageFilters(probe, Seq(worst), Nil, Nil)(candidates))
        .map(_.pageId).toSet
      Round.setActive(worst.getId, active = true)
      val created = inlineService(new TestHandler(chunkRows = 3)).createNewRound(template, jurors.map(_.getId))
      (pageIds(created.getId) === expected) and (expected.size === 8) and
        (pageIds(created.getId).forall(rejections(_) < 2) must beTrue)
    }

    "pick the same top-N as the in-memory filter, ties included" in {
      val (contest, _, jurors) = setUp(60, 3)
      val rated = ratedRound(contest, jurors, Round.ratesById(5), seed = 21, "Rated")
      val binary = ratedRound(contest, jurors, Round.binaryRound, seed = 22, "Binary")
      // the other score rules (V90): by the sum, by the average over the assigned jurors
      def withRule(r: Round, rule: AverageRule): Round = {
        sql"UPDATE round SET average_rule = ${rule.param} WHERE id = ${r.getId}".update()(AutoSession)
        Round.findById(r.getId).get
      }
      val sum = withRule(ratedRound(contest, jurors, Round.ratesById(5), seed = 23, "Sum"), AverageRule.Sum)
      val assigned = withRule(ratedRound(contest, jurors, Round.ratesById(5), seed = 24, "Assigned"), AverageRule.Assigned)

      /** The in-memory filter over the previous round's grouped images, in a deterministic
        * input order (rate sum descending, then page id), after the per-image filters.
        */
      def reference(prev: Round, template: Round, n: Int): Set[Long] = {
        val probe = roundDao.create(template.copy(number = 99))
        // a yes/no round's top-N reads the approvals and rejections, which tally counts
        val candidates = ImageJdbc.byRoundMerged(prev.getId, rated = Option.when(template.selectedByFilter.isDefined)(true),
            tally = true)
          .sortBy(i => (-i.votes.head.rate, i.pageId))
        val filtered = di.perImageFilters(probe, Seq(prev), Nil, Nil)(candidates)
        SelectTopByRating(n, prev)().apply(filtered).map(_.pageId).toSet
      }

      def ties(prev: Round, n: Int): Boolean = { // the data must have ties at the cut
        val totals = ImageJdbc.byRoundMerged(prev.getId, tally = true).map(_.totalRate(prev)).sorted(Ordering[Double].reverse)
        totals.lift(n - 1) == totals.lift(n)
      }

      val cases = Seq(
        (rated, newRound(contest, "Top").copy(previous = Some(rated.getId.toString), topImages = Some(10))),
        (rated, newRound(contest, "Top region").copy(previous = Some(rated.getId.toString), topImages = Some(7),
          regions = Some("12"))),
        (binary, newRound(contest, "Top binary").copy(previous = Some(binary.getId.toString), topImages = Some(12))),
        (sum, newRound(contest, "Top sum").copy(previous = Some(sum.getId.toString), topImages = Some(10))),
        (assigned, newRound(contest, "Top assigned").copy(previous = Some(assigned.getId.toString), topImages = Some(10)))
      )
      (cases.exists { case (prev, t) => ties(prev, t.topImages.get) } must beTrue) and
        cases.map { case (prev, template) =>
          val expected = reference(prev, template, template.topImages.get)
          Round.setActive(prev.getId, active = true)
          val created = inlineService(new TestHandler(chunkRows = 3)).createNewRound(template, jurors.map(_.getId))
          Round.setActive(prev.getId, active = true)
          (pageIds(created.getId) === expected) and (expected.size === template.topImages.get)
        }.reduce(_ and _)
    }

    "pick from a criteria round by its files' criteria sums, a partly rated vote unrated (U5, U7)" in {
      val (contest, _, jurors) = setUp(6, 2)
      val Seq(j1, j2) = jurors
      val prev = roundDao.create(Round(None, 1, Some("Criteria"), contest.getId, rates = Round.ratesById(10),
        active = true, criterionIds = Seq(1L, 2L)))
      prev.addUsers(jurors.map(u => RoundUser(prev.getId, u.getId, "jury", active = true)))
      di.distributeImages(prev, jurors, Nil)
      def rate(pageId: Long, juror: User, rates: Int*): Unit = {
        val vote = VoteJdbc.findBy(pageId, juror.getId, prev.getId).get
        rates.zipWithIndex.foreach { case (r, i) => CriteriaRate.updateRate(vote.getId, i + 1L, r) must beTrue }
      }
      // per juror, the sums: file 1 18 and 16 (17), file 2 12 and 12 (12), file 3 12 and a
      // partly rated vote, 0 (6; 11 if that vote counted its 10), file 4 4 and 4; 5 and 6 unrated
      rate(1, j1, 9, 9); rate(1, j2, 8, 8)
      rate(2, j1, 6, 6); rate(2, j2, 6, 6)
      rate(3, j1, 6, 6); rate(3, j2, 10)
      rate(4, j1, 2, 2); rate(4, j2, 2, 2)
      VoteJdbc.findBy(3, j2.getId, prev.getId).map(_.rate) must beSome(0)

      def created(template: Round): Set[Long] = {
        Round.setActive(prev.getId, active = true)
        val round = inlineService(new TestHandler(chunkRows = 3)).createNewRound(template, jurors.map(_.getId))
        pageIds(round.getId)
      }
      def from(name: String) = newRound(contest, name, rates = Round.ratesById(10)).copy(previous = Some(prev.getId.toString))

      def withRule(rule: AverageRule): Unit =
        sql"UPDATE round SET average_rule = ${rule.param} WHERE id = ${prev.getId}".update()(AutoSession): Unit

      // over the assigned jurors (the rule of the grouped views before V90): the partly rated
      // vote counts 0. "min average rate" 11 is a sum: per criterion (8.5 at most) no file would pass
      withRule(AverageRule.Assigned)
      created(from("Min").copy(prevMinAvgRate = Some(BigDecimal(11)))) === Set(1L, 2L)
      created(from("Min 6").copy(prevMinAvgRate = Some(BigDecimal(6)))) === Set(1L, 2L, 3L)
      created(from("Top").copy(topImages = Some(2))) === Set(1L, 2L)
      created(from("Top 3").copy(topImages = Some(3))) === Set(1L, 2L, 3L)
      // over the jurors who rated it (the default): file 3 is 12 over its one fully rated vote,
      // tied with file 2, whose sum (24) ranks it first
      withRule(AverageRule.Rated)
      created(from("Min rated").copy(prevMinAvgRate = Some(BigDecimal(11)))) === Set(1L, 2L, 3L)
      created(from("Top rated").copy(topImages = Some(2))) === Set(1L, 2L)
      // by the sum: 34, 24, 12, 8; the minimum is a minimum score
      withRule(AverageRule.Sum)
      created(from("Min sum").copy(prevMinAvgRate = Some(BigDecimal(20)))) === Set(1L, 2L)
      created(from("Top sum").copy(topImages = Some(3))) === Set(1L, 2L, 3L)
    }

    "rank by the rule recorded when the job was queued, and a job from before V90 by the assigned jurors" in {
      val (contest, _, jurors) = setUp(2, 3)
      val prev = roundDao.create(Round(None, 1, Some("Rated"), contest.getId, rates = Round.ratesById(10), active = true))
      prev.addUsers(jurors.map(u => RoundUser(prev.getId, u.getId, "jury", active = true)))
      di.distributeImages(prev, jurors, Nil)
      // file 1: 10 by one juror, two unrated (10 over the rated, 3.33 over the assigned); file 2:
      // 6 by all three (6 either way): its top-1 is file 1 by `rated`, file 2 by `assigned`
      VoteJdbc.byRound(prev.getId).foreach { v =>
        val rate = if (v.pageId == 2L) 6 else if (v.juryId == jurors.head.getId) 10 else 0
        VoteJdbc.rate(v.pageId, v.juryId, prev.getId, rate)
      }
      def withRule(rule: AverageRule): Unit =
        sql"UPDATE round SET average_rule = ${rule.param} WHERE id = ${prev.getId}".update()(AutoSession): Unit
      def top1(name: String, h: TestHandler): (Set[Long], Option[String]) = {
        Round.setActive(prev.getId, active = true)
        val template = newRound(contest, name, rates = Round.ratesById(10))
          .copy(previous = Some(prev.getId.toString), topImages = Some(1))
        val round = inlineService(h).createNewRound(template, jurors.map(_.getId))
        (pageIds(round.getId), (jobOf(round.getId).params \ "settings" \ "prevAverageRule").asOpt[String])
      }
      def stripRule(job: Job): Job = job.copy(params = job.params ++ Json.obj("settings" ->
        ((job.params \ "settings").as[JsObject] - "prevAverageRule")))

      // queued with `rated`; the source's rule changed to `assigned` before the job prepares
      val recorded = new TestHandler(chunkRows = 3)
      recorded.beforePrepare = Some { job => withRule(AverageRule.Assigned); job }
      val (byRecorded, recordedRule) = top1("Recorded", recorded)
      // a job without the key (queued before V90), from a source now `rated`
      withRule(AverageRule.Rated)
      val legacy = new TestHandler(chunkRows = 3)
      legacy.beforePrepare = Some(stripRule)
      val (byLegacy, _) = top1("Legacy", legacy)
      (recordedRule must beSome("rated")) and (byRecorded === Set(1L)) and
        (RoundDistributionHandler.averageRuleOf(stripRule(jobOf(roundDao.findByContest(contest.getId)
          .find(_.name.contains("Legacy")).get.getId))) must beNone) and
        (byLegacy === Set(2L))
    }

    "keep a top-N round at N on \"Distribute new files\"" in {
      val (contest, _, jurors) = setUp(30, 3)
      val rated = ratedRound(contest, jurors, Round.ratesById(5), seed = 31, "Rated")
      val service = inlineService(TestRoundServices.handler())
      val created = service.createNewRound(
        newRound(contest, "Top").copy(previous = Some(rated.getId.toString), topImages = Some(8)), jurors.map(_.getId))
      pageIds(created.getId).size === 8
      service.distributeNewImages(created.getId) === 0
      service.newFilesCount(created.getId) === 0L
    }

    "count the new files it would add" in {
      val (contest, imgs, jurors) = setUp(20, 2)
      val service = inlineService(TestRoundServices.handler())
      val created = service.createNewRound(newRound(contest, "R"), jurors.map(_.getId))
      val more = (101 to 105).map(i => Image(i.toLong, s"File:More$i.jpg", None, None, 800, 600, None))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      service.newFilesCount(created.getId) === 5L
      service.distributeNewImages(created.getId) === 5
      service.newFilesCount(created.getId) === 0L
      pageIds(created.getId).size === imgs.size + 5
    }
  }

  "a round distribution job's round_image rows (V84)" should {

    def rows(roundId: Long) = RoundImageJdbc.byRound(roundId)

    "be written with its votes: one per file, 'in', 'fill', the job's id; then 'new_files'" in {
      val (contest, imgs, jurors) = setUp(20, 3)
      val service = inlineService(new TestHandler(chunkRows = 4))
      val created = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId))
      val job = jobOf(created.getId)
      val more = (101 to 103).map(i => Image(i.toLong, s"File:More$i.jpg", None, None, 800, 600, Some(s"12-345-$i")))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      service.distributeNewImages(created.getId) === 3
      val second = jobOf(created.getId)
      val all = rows(created.getId)
      (all.map(_.pageId) === (imgs ++ more).map(_.pageId)) and
        (all.map(_.status).distinct === Seq(RoundImageJdbc.Status.In)) and
        (all.filter(_.pageId < 100).map(r => (r.addedHow, r.addedByJobId)).distinct === Seq(("fill", Some(job.id)))) and
        (all.filter(_.pageId > 100).map(r => (r.addedHow, r.addedByJobId)).distinct === Seq(("new_files", Some(second.id)))) and
        (all.find(_.pageId == 101).flatMap(_.region) === Some("12")) and
        (RoundImageInvariants.violations(created.getId) must beEmpty)
    }

    "add a pasted list's files as 'list'" in {
      val (contest, imgs, jurors) = setUp(6, 2)
      val service = inlineService(TestRoundServices.handler())
      val created = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId))
      imageDao.batchInsert(Seq(Image(200L, "File:Listed.jpg", None, None, 800, 600, None)))
      service.distributeNewFiles(created.getId, titles = Seq("File:Listed.jpg"), newFiles = false)
      (rows(created.getId).find(_.pageId == 200L).map(_.addedHow) === Some("list")) and
        (rows(created.getId).size === imgs.size + 1) and
        (RoundImageInvariants.violations(created.getId) must beEmpty)
    }

    "leave none of a failed chunk, and none twice on resume" in {
      val (contest, _, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3)
      h.failAfterInsertOfChunk = Some(3) // after its votes and rows are inserted, in its transaction
      val service = inlineService(h)
      val submitted = service.submit(newRound(contest, "R1"), jurors.map(_.getId), Some(java.util.UUID.randomUUID().toString))
      val roundId = submitted.round.getId
      (submitted.job.map(_.status) === Some(JobStatus.Failed)) and
        (rows(roundId).map(_.pageId) === pageIds(roundId).toSeq.sorted) and (rows(roundId).size === 6) and
        (RoundImageInvariants.violations(roundId) must beEmpty) and {
          service.distributeNewImages(roundId) === 20
          (rows(roundId).size === 20) and (RoundImageInvariants.violations(roundId) must beEmpty)
        }
    }

    "keep a file with a row and no votes out of the next distribution (the round_image anti-join)" in {
      val (contest, imgs, jurors) = setUp(6, 2)
      val service = inlineService(TestRoundServices.handler())
      val created = service.createNewRound(newRound(contest, "R1"), jurors.map(_.getId))
      // file 1's votes gone by hand, its row left: the anti-join reads round_image too
      DB.autoCommit(implicit s => sql"DELETE FROM vote WHERE round_id = ${created.getId} AND page_id = 1".update())
      (service.newFilesCount(created.getId) === 0L) and (service.distributeNewImages(created.getId) === 0) and
        (pageIds(created.getId) === imgs.map(_.pageId).toSet - 1L)
    }
  }
}
