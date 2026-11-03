package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.cmd.{DistributeImages, SelectTopByRating}
import org.intracer.wmua.{ContestJury, Image, ImageWithRating, Selection}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import scalikejdbc._
import services.jobs.{JobJdbc, JobScheduler, JobStatus}
import services.{GalleryService, RoundService, TestRoundServices}

import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
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
      extends RoundDistributionHandler(di, TestRoundServices.config(chunkRows)) {
    val chunks = new AtomicInteger()
    @volatile var failBeforeChunk: Option[Int] = None
    @volatile var failAfterInsertOfChunk: Option[Int] = None
    @volatile var crashAtChunk: Option[Int] = None
    @volatile var sleepMs = 0L
    val watchedActive: mutable.Buffer[Boolean] = mutable.Buffer.empty

    override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = {
      val n = chunks.incrementAndGet()
      watchedActive.synchronized(watchedActive ++= watch.flatMap(id => Round.findById(id).map(_.active)))
      if (sleepMs > 0) Thread.sleep(sleepMs)
      if (crashAtChunk.contains(n)) { crashAtChunk = None; throw new InterruptedException("simulated crash") }
      if (failBeforeChunk.contains(n)) { failBeforeChunk = None; throw new RuntimeException(s"simulated failure in chunk $n") }
    }

    override protected def afterInsert(round: Round, rows: Seq[Selection])(implicit session: DBSession): Unit =
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

  /** A previous round with every image to every juror, and random rates. */
  private def ratedRound(contest: ContestJury, jurors: Seq[User], rates: Rates, seed: Int, name: String): Round = {
    val round = roundDao.create(Round(None, 1, Some(name), contest.getId, rates = rates, active = true))
    round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
    di.distributeImages(round, jurors, Nil)
    val random = new Random(seed)
    SelectionJdbc.byRound(round.getId).foreach { s =>
      val rate = if (rates == Round.binaryRound) random.nextInt(3) - 1 else random.nextInt(rates.maxRate + 1)
      SelectionJdbc.rate(s.pageId, s.juryId, round.getId, rate)
    }
    round
  }

  private def pageIds(roundId: Long): Set[Long] = SelectionJdbc.byRound(roundId).map(_.pageId).toSet

  private def jobOf(roundId: Long) = {
    val latest = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
    latest(roundId)
  }

  private def stateOf(roundId: Long) = RoundDistributionHandler.stateOf(jobOf(roundId))

  /** For distribution x: the image at position i (by page id) has jurors (i + j) % n. */
  private def rotationHolds(roundId: Long, jurors: Seq[User], x: Int) = {
    val rows = SelectionJdbc.byRound(roundId)
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
      SelectionJdbc.byRound(created.getId).size === 20
      pageIds(created.getId) === imgs.map(_.pageId).toSet
      val job = jobOf(created.getId)
      job.status === JobStatus.Done
      job.progressDone === Some(20L)
      job.progressTotal === Some(20L)
      stateOf(created.getId).imagesAdded === 20
      RoundImageCounts.get(Seq(created.getId))(ImageJdbc.imageCountByRounds)(created.getId) === 20
      rotationHolds(created.getId, jurors, 1) must beTrue
    }

    "fail at a chunk, keep the committed chunks, and resume from the cursor without duplicates" in {
      val (contest, _, jurors) = setUp(20, 3)
      val h = new TestHandler(chunkRows = 3) // 3 images per chunk
      h.failBeforeChunk = Some(3)
      val service = inlineService(h)
      val submitted = service.submit(newRound(contest, "R1"), jurors.map(_.getId), Some(java.util.UUID.randomUUID().toString))
      submitted.job.map(_.status) === Some(JobStatus.Failed)
      val roundId = submitted.round.getId
      SelectionJdbc.byRound(roundId).size === 6
      stateOf(roundId).cursor === 6L
      stateOf(roundId).position === 6L
      jobOf(roundId).error must beSome(contain("simulated failure in chunk 3"))

      service.distributeNewImages(roundId) === 20 // resumes the failed job: 6 + 14 by it
      SelectionJdbc.byRound(roundId).size === 20
      SelectionJdbc.byRound(roundId).map(s => (s.pageId, s.juryId)).distinct.size === 20
      rotationHolds(roundId, jurors, 1) must beTrue // the rotation went on across the failure
      roundDao.findById(roundId).get.active must beTrue
    }

    "write criteria rates in the chunk transaction, and roll them back with it" in {
      val (c, _, jurors) = setUp(12, 2)
      implicit val contest: ContestJury = c
      val round = roundDao.create(newRound(contest, "Criteria", distribution = 2).copy(number = 1))
      sql"UPDATE rounds SET has_criteria = 1 WHERE id = ${round.getId}".update()(AutoSession)
      round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
      val h = new TestHandler(chunkRows = 8) // 4 images, 8 rows per chunk
      h.failAfterInsertOfChunk = Some(2)
      val service = inlineService(h)
      service.distributeNewImages(round.getId) must throwA[RoundService.RoundDistributionFailed]

      def criteriaRows = DB.readOnly(implicit s => sql"""SELECT COUNT(*) FROM criteria_rate c JOIN selection s ON s.id = c.selection
                                WHERE s.round_id = ${round.getId}""".map(_.int(1)).single().get)
      SelectionJdbc.byRound(round.getId).size === 8 // chunk 1 only
      criteriaRows === 8 * DistributeImages.CriteriaIds.size // chunk 2's rolled back with its rows

      service.distributeNewImages(round.getId) === 12
      SelectionJdbc.byRound(round.getId).size === 24
      criteriaRows === 24 * DistributeImages.CriteriaIds.size
      DB.readOnly(implicit s => sql"SELECT COUNT(DISTINCT id) = COUNT(*) FROM criteria_rate".map(_.boolean(1)).single()) ===
        Some(true)
    }

    "keep a juror deactivated before a retry out of the rest of the distribution" in {
      val (contest, _, jurors) = setUp(12, 3)
      val h = new TestHandler(chunkRows = 3)
      h.failBeforeChunk = Some(2)
      val service = inlineService(h)
      val roundId = service.submit(newRound(contest, "R1"), jurors.map(_.getId), None).round.getId
      RoundUser.setActive(roundId, jurors.last.getId, active = false)
      service.distributeNewImages(roundId)
      SelectionJdbc.byRound(roundId).filter(_.pageId > 3).map(_.juryId).toSet === jurors.init.map(_.getId).toSet
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
      SelectionJdbc.byRound(prev.getId).foreach(s => SelectionJdbc.rate(s.pageId, s.juryId, prev.getId, 1))
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
      SelectionJdbc.byRound(roundId).map(_.pageId).distinct.size.toLong === added // kept, no more after
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
      SelectionJdbc.byRound(prev.getId).foreach(s => SelectionJdbc.rate(s.pageId, s.juryId, prev.getId, 1))
      val dying = new TestHandler(chunkRows = 3)
      dying.crashAtChunk = Some(3)
      val next = newRound(contest, "Next").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1))
      inlineService(dying).submit(next, jurors.map(_.getId), None) must throwA[InterruptedException]
      val roundId = roundDao.findByContest(contest.getId).find(_.name.contains("Next")).get.getId
      jobOf(roundId).status === JobStatus.Running // its instance "died" holding it
      SelectionJdbc.byRound(roundId).size === 6
      roundDao.findById(prev.getId).get.active must beFalse // still frozen
      sql"UPDATE job SET heartbeat_at = NOW(3) - INTERVAL 5 MINUTE".update()(AutoSession)

      val (_, scheduler) = backgroundService(new TestHandler(chunkRows = 3))
      val done = scheduler.await(jobOf(roundId).id, 20.seconds)
      done.status === JobStatus.Done
      done.owner must beNone
      done.attempts === 2
      SelectionJdbc.byRound(roundId).size === 20
      SelectionJdbc.byRound(roundId).map(_.pageId).distinct.size === 20
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
      SelectionJdbc.byRound(ids(0)).size === 80
      pageIds(ids(1)) === imgs.filter(_.monumentId.exists(_.startsWith("12"))).map(_.pageId).toSet
      SelectionJdbc.byRound(ids(2)).size === 160
      pageIds(ids(3)) === imgs.filter(_.isVideo).map(_.pageId).toSet
      // balanced: per-juror counts differ by at most one
      val perJuror = SelectionJdbc.byRound(ids(0)).groupBy(_.juryId).values.map(_.size)
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
      val optional = ratedRound(contest, jurors, Round.ratesById(10), seed = 14, "Optional")
      sql"UPDATE rounds SET optional_rate = 1 WHERE id = ${optional.getId}".update()(AutoSession)
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
        from(optional).copy(prevMinAvgRate = Some(BigDecimal(3)))
      )
      cases.map { template =>
        val (got, expected) = both(contest, jurors, template)
        got === expected
      }.reduce(_ and _)
    }

    "pick the same top-N as the in-memory filter, ties included" in {
      val (contest, _, jurors) = setUp(60, 3)
      val rated = ratedRound(contest, jurors, Round.ratesById(5), seed = 21, "Rated")
      val binary = ratedRound(contest, jurors, Round.binaryRound, seed = 22, "Binary")

      /** The in-memory filter over the previous round's grouped images, in a deterministic
        * input order (rate sum descending, then page id), after the per-image filters.
        */
      def reference(prev: Round, template: Round, n: Int): Set[Long] = {
        val probe = roundDao.create(template.copy(number = 99))
        val candidates = ImageJdbc.byRoundMerged(prev.getId, rated = template.prevSelectedBy.map(_ > 0))
          .sortBy(i => (-i.selection.head.rate, i.pageId))
        val filtered = di.perImageFilters(probe, Seq(prev), Nil, Nil)(candidates)
        SelectTopByRating(n, prev)().apply(filtered).map(_.pageId).toSet
      }

      def ties(prev: Round, n: Int): Boolean = { // the data must have ties at the cut
        val totals = ImageJdbc.byRoundMerged(prev.getId).map(_.totalRate(prev)).sorted(Ordering[Double].reverse)
        totals.lift(n - 1) == totals.lift(n)
      }

      val cases = Seq(
        (rated, newRound(contest, "Top").copy(previous = Some(rated.getId.toString), topImages = Some(10))),
        (rated, newRound(contest, "Top region").copy(previous = Some(rated.getId.toString), topImages = Some(7),
          regions = Some("12"))),
        (binary, newRound(contest, "Top binary").copy(previous = Some(binary.getId.toString), topImages = Some(12)))
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
}
