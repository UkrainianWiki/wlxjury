package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.Json
import scalikejdbc._
import services.imports.DeletedFiles
import services.jobs.{JobJdbc, JobStatus, NewJob, Progress}
import services.{RoundService, TestRoundServices}

/** The two-step round (P3 of docs/plans/2026-11-02-round-images-two-step.md, §6; P3-3 of
  * docs/plans/2026-11-16-round-editing.md): a fill that ends in review (the round `paused`,
  * hidden, its files in `round_image` with no votes, its previous rounds released), then the
  * review's Distribute (the same vote rows as one pass, the round opened); "Distribute as soon as
  * ready" (D4) and a job without a mode run both in one pass; "Distribute new files" on a round in
  * review only fills; Distribute refused unless the round is in review with no job; a file deleted
  * on Commons during the review is never distributed. Jobs run inline; truncation, not
  * AutoRollback (the service's own transactions).
  */
class RoundReviewFlowSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def images(ids: Seq[Long]): Seq[Image] =
    ids.map(i => Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"14-101-$i")))

  /** Images 1..n in the contest's category, three jurors. */
  private def setUp(n: Int): (ContestJury, Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images((1 to n).map(_.toLong))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    (contest, createUsers("jury", 1, 2, 3).sortBy(_.getId))
  }

  private def newRound(contest: ContestJury, name: String, distribution: Int = 2) =
    Round(None, 0, Some(name), contest.getId, distribution = distribution, active = true)

  private def pairs(roundId: Long): Set[(Long, Long)] = VoteJdbc.byRound(roundId).map(v => v.pageId -> v.juryId).toSet

  private def round(id: Long): Round = roundDao.findById(id).get

  private def jobsOf(roundId: Long) =
    DB.readOnly(implicit s => sql"SELECT id FROM job WHERE subject_type = 'round' AND subject_id = $roundId ORDER BY id"
      .map(_.long(1)).list()).flatMap(JobJdbc.findById(_))

  private def job(roundId: Long) =
    JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId)).get(roundId).get

  "a round created for review" should {

    "be filled with no votes, paused and hidden, its previous rounds not frozen" in {
      val (contest, jurors) = setUp(6)
      val service = TestRoundServices.roundService()
      val prev = service.createNewRound(newRound(contest, "Prev", distribution = 0), jurors.map(_.getId))
      VoteJdbc.byRound(prev.getId).foreach(v => VoteJdbc.rate(v.pageId, v.juryId, prev.getId, 1))
      val submitted = service.submit(newRound(contest, "Next").copy(previous = Some(prev.getId.toString),
        prevSelectedBy = Some(1)), jurors.map(_.getId), None, review = true)
      val r = round(submitted.round.getId)
      val rows = RoundImageJdbc.byRound(r.getId)
      (r.inReview must beTrue) and (r.status === Some(Round.Status.Paused)) and (r.distributing must beTrue) and
        (r.openToJurors must beFalse) and
        (rows.map(row => (row.pageId, row.status, row.addedHow)) === (1L to 6L).map(p => (p, "in", "fill"))) and
        (VoteJdbc.byRound(r.getId) must beEmpty) and
        (round(prev.getId).active must beTrue) and (round(prev.getId).freezeCount === 0) and
        ((job(r.getId).params \ "mode").as[String] === "fill") and
        ((job(r.getId).params \ "distributeAfter").as[Boolean] must beFalse) and
        (job(r.getId).status === JobStatus.Done) and
        (jurors.flatMap(j => Round.activeRounds(j.copy(contestId = contest.id))).map(_.getId) must not(contain(r.getId))) and
        (RoundImageInvariants.violations(r.getId) must beEmpty)
    }

    "get the same vote rows from Distribute as a round distributed in one pass, and open" in {
      val (contest, jurors) = setUp(7)
      val service = TestRoundServices.roundService()
      val reviewed = service.submit(newRound(contest, "Reviewed"), jurors.map(_.getId), None, review = true).round.getId
      val onePass = service.createNewRound(newRound(contest, "One pass"), jurors.map(_.getId)).getId
      val started = service.distributeReviewed(reviewed, Some(1L))
      val r = round(reviewed)
      (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (pairs(reviewed) === pairs(onePass)) and (pairs(reviewed).size === 14) and
        (r.openToJurors must beTrue) and (r.status === Some(Round.Status.Active)) and (r.inReview must beFalse) and
        ((job(reviewed).params \ "mode").as[String] === "distribute") and
        (RoundImageJdbc.byRound(reviewed).map(_.addedHow).distinct === Seq("fill")) and
        (RoundImageInvariants.violations(reviewed) must beEmpty) and
        // twice: nothing to distribute now
        (service.distributeReviewed(reviewed, Some(1L)) === RoundService.ReviewDistribution.NotInReview)
    }

    "only fill on \"Distribute new files\" while in review, and stay in review" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      val more = images(Seq(5L, 6L))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      service.distributeNewImages(r) === 2
      (round(r).inReview must beTrue) and (VoteJdbc.byRound(r) must beEmpty) and
        (RoundImageJdbc.countIn(r) === 6L) and ((job(r).params \ "fromReview").as[Boolean] must beTrue)
    }

    "never distribute a file deleted on Commons during the review" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      service.distributeReviewed(r, None)
      (RoundImageJdbc.find(r, 2L).map(_.status) === Some("deleted")) and
        (VoteJdbc.byRound(r).map(_.pageId).toSet === Set(1L, 3L, 4L)) and (round(r).openToJurors must beTrue) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  "a round leaving out a round in review" should {

    "not be refused as busy: the review's files change only by a job (§6.2)" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val reviewed = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      val excluding = service.createNewRound(newRound(contest, "X").copy(excludeRounds = Seq(reviewed)), jurors.map(_.getId))
      (round(excluding.getId).openToJurors must beTrue) and (VoteJdbc.byRound(excluding.getId) must beEmpty) and
        (RoundDistributionHandler.distributingNow(round(reviewed)) must beFalse)
    }
  }

  "Distribute" should {

    "be refused for a round not in review, and while a job of the round is queued" in {
      val (contest, jurors) = setUp(3)
      val service = TestRoundServices.roundService()
      val active = service.createNewRound(newRound(contest, "A"), jurors.map(_.getId)).getId
      val reviewed = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      // a job queued by hand (none runs: not started)
      DB.localTx(implicit s => sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
        VALUES (${RoundDistributionHandler.Kind}, 10, 'round', $reviewed, ${RoundDistributionHandler.dedupeKey(reviewed)},
          'queued', '{}', '{}')""".update())
      (service.distributeReviewed(active, None) === RoundService.ReviewDistribution.NotInReview) and
        (service.distributeReviewed(reviewed, None) must beAnInstanceOf[RoundService.ReviewDistribution.Running]) and
        (VoteJdbc.byRound(reviewed) must beEmpty) and (round(reviewed).inReview must beTrue)
    }
  }

  "a one-pass round" should {

    "be distributed at once with \"Distribute as soon as ready\" (D4), and by a job without a mode" in {
      val (contest, jurors) = setUp(5)
      val handler = TestRoundServices.handler()
      val scheduler = TestRoundServices.inlineScheduler(handler)
      val service = new RoundService(Round, scheduler, handler)
      val d4 = service.createNewRound(newRound(contest, "D4"), jurors.map(_.getId)).getId
      // a creation queued before P3: no mode, as a job of the old binary
      val old = roundDao.create(newRound(contest, "Old").copy(number = 2, active = false, distributing = true))
      old.addUsers(jurors.map(u => RoundUser(old.getId, u.getId, "jury", active = true)))
      val queued = DB.localTx { implicit s =>
        Round.lock(Seq(old.getId))
        scheduler.enqueue(NewJob(kind = RoundDistributionHandler.Kind, contestId = contest.id, subjectType = Some("round"),
          subjectId = old.id, dedupeKey = Some(RoundDistributionHandler.dedupeKey(old.getId)),
          params = Json.obj("roundId" -> old.getId, "completingCreation" -> true),
          state = handler.initialState(old), progress = Progress(unit = Some("images"))))
      }.toOption.get
      scheduler.started(queued.id)
      (jobsOf(d4).map(RoundDistributionHandler.modeOf) === Seq(Some("fill"), Some("distribute"))) and
        ((jobsOf(d4).head.params \ "distributeAfter").as[Boolean] must beTrue) and (round(d4).openToJurors must beTrue) and
        (round(d4).status === Some(Round.Status.Active)) and (pairs(d4).size === 10) and
        (round(old.getId).openToJurors must beTrue) and (pairs(old.getId) === pairs(d4))
    }
  }

  // ── review fixes: a distribute stopped part-way, reinstating, active rounds, equivalence ─────────

  /** A handler of small chunks whose `failAt`th insert (counted once armed) throws: a distribute
    * failing part-way.
    */
  private class FailingHandler(chunkRows: Int) extends RoundDistributionHandler(
      new org.intracer.wmua.cmd.DistributeImages(ImageJdbc), TestRoundServices.config(chunkRows), TestRoundServices.emptyCommons) {
    @volatile var failAt: Option[Int] = None
    private val inserts = new java.util.concurrent.atomic.AtomicInteger()
    override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit =
      failAt.foreach { n => if (inserts.incrementAndGet() == n) throw new RuntimeException(s"simulated failure at insert $n") }
    def arm(n: Int): Unit = { inserts.set(0); failAt = Some(n) }
  }

  private def freezeOf(roundId: Long): (Int, Option[Boolean], Boolean) = DB.readOnly { implicit s =>
    sql"SELECT freeze_count, active_before_freeze, active FROM round WHERE id = $roundId"
      .map(rs => (rs.int(1), rs.booleanOpt(2), rs.boolean(3))).single().get
  }

  private def failing(chunkRows: Int) = {
    val h = new FailingHandler(chunkRows)
    val scheduler = TestRoundServices.inlineScheduler(h)
    (h, scheduler, new RoundService(Round, scheduler, h))
  }

  /** A previous round with every image to every juror, all rated yes. */
  private def prevRound(contest: ContestJury, jurors: Seq[User], service: RoundService): Round = {
    val prev = service.createNewRound(newRound(contest, "Prev", distribution = 0), jurors.map(_.getId))
    VoteJdbc.byRound(prev.getId).foreach(v => VoteJdbc.rate(v.pageId, v.juryId, prev.getId, 1))
    prev
  }

  private def noDuplicates(roundId: Long, files: Int, perFile: Int) =
    (VoteJdbc.byRound(roundId).size === files * perFile) and (pairs(roundId).size === files * perFile)

  "a distribute stopped part-way" should {

    "fail and keep the round in review with its previous rounds released; Distribute again completes, no duplicates" in {
      val (contest, jurors) = setUp(10)
      val (h, _, service) = failing(chunkRows = 4) // 2 files per window, 2 jurors each
      val prev = prevRound(contest, jurors, service)
      val r = service.submit(newRound(contest, "R").copy(previous = Some(prev.getId.toString)), jurors.map(_.getId),
        None, review = true).round.getId
      h.arm(3)
      service.distributeReviewed(r, Some(1L))
      val failed = job(r)
      val partly = VoteJdbc.byRound(r).size
      val afterFailure = (round(r).inReview, round(prev.getId).active, round(prev.getId).freezeCount)
      h.failAt = None
      service.distributeReviewed(r, Some(1L))
      (failed.status === JobStatus.Failed) and (partly === 8) and (afterFailure === ((true, true, 0))) and
        (round(r).openToJurors must beTrue) and noDuplicates(r, 10, 2) and
        // closed by the creation, as before
        (round(prev.getId).active must beFalse) and (round(prev.getId).freezeCount === 0) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "resume on its Retry from its cursor, the previous rounds frozen again and closed at its end, no duplicates" in {
      val (contest, jurors) = setUp(10)
      val (h, scheduler, service) = failing(chunkRows = 4)
      val prev = prevRound(contest, jurors, service)
      val r = service.submit(newRound(contest, "R").copy(previous = Some(prev.getId.toString)), jurors.map(_.getId),
        None, review = true).round.getId
      h.arm(2)
      service.distributeReviewed(r, None)
      val failed = job(r)
      h.failAt = None
      scheduler.retry(failed.id)
      scheduler.started(failed.id)
      (JobJdbc.findById(failed.id).map(_.status) === Some(JobStatus.Done)) and (round(r).openToJurors must beTrue) and
        noDuplicates(r, 10, 2) and (round(prev.getId).active must beFalse) and (round(prev.getId).freezeCount === 0)
    }

    "when cancelled, leave the round in review (not cancelled); \"Distribute new files\" there then only fills" in {
      val (contest, jurors) = setUp(6)
      val (h, scheduler, service) = failing(chunkRows = 4)
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      h.arm(2)
      service.distributeReviewed(r, None)
      scheduler.cancel(job(r).id, Some(5L))
      h.failAt = None
      val partly = VoteJdbc.byRound(r).size
      // the failed or cancelled distribute is never retried from here: a fill, the round kept in review
      service.distributeNewFiles(r, Some(5L))
      (round(r).inReview must beTrue) and (round(r).status === Some(Round.Status.Paused)) and
        (VoteJdbc.byRound(r).size === partly) and (RoundDistributionHandler.fillOnly(job(r)) must beTrue) and
        (RoundDistributionHandler.fromReview(job(r)) must beTrue) and {
          service.distributeReviewed(r, None)
          (round(r).openToJurors must beTrue) and noDuplicates(r, 6, 2)
        }
    }
  }

  "\"Distribute new files\" on a cancelled creation" should {

    "reinstate it with its intent, by the admin who asked: a review creation held in review again" in {
      val (contest, jurors) = setUp(6)
      val (h, scheduler, service) = failing(chunkRows = 4)
      h.arm(2) // its fill fails part-way
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      scheduler.cancel(job(r).id, Some(5L))
      val cancelled = round(r).status
      h.failAt = None
      service.distributeNewFiles(r, Some(6L))
      val by = DB.readOnly(implicit s => sql"SELECT status_changed_by FROM round WHERE id = $r".map(_.longOpt(1)).single().flatten)
      (cancelled === Some(Round.Status.Cancelled)) and (round(r).inReview must beTrue) and
        (VoteJdbc.byRound(r) must beEmpty) and (RoundImageJdbc.countIn(r) === 6L) and (by === Some(6L)) and
        (RoundImageJdbc.byRound(r).map(_.addedHow).distinct === Seq("fill"))
    }

    "distribute after its fill a creation that asked for it (D4)" in {
      val (contest, jurors) = setUp(6)
      val (h, scheduler, service) = failing(chunkRows = 4)
      h.arm(2)
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = false).round.getId
      scheduler.cancel(job(r).id, Some(5L))
      h.failAt = None
      service.distributeNewFiles(r, Some(6L))
      (round(r).openToJurors must beTrue) and noDuplicates(r, 6, 2)
    }
  }

  "\"Distribute new files\" on a distributed round" should {

    "give out its files with no votes: one deleted during the review and restored since" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      service.distributeReviewed(r, None)
      val before = VoteJdbc.byRound(r).map(_.pageId).toSet
      DB.localTx(implicit s => DeletedFiles.restore(Seq(2L)))
      val restored = RoundImageJdbc.find(r, 2L).map(_.status)
      service.distributeNewImages(r)
      (before === Set(1L, 3L, 4L)) and (restored === Some("in")) and
        (VoteJdbc.byRound(r).filter(_.pageId == 2L).size === 2) and (round(r).openToJurors must beTrue) and
        (RoundImageJdbc.byRound(r).find(_.pageId == 2L).map(_.addedHow) === Some("fill")) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "write a round in review's new files as new_files (§6), not fill" in {
      val (contest, jurors) = setUp(3)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      val more = images(Seq(4L))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      service.distributeNewImages(r)
      RoundImageJdbc.find(r, 4L).map(_.addedHow) === Some("new_files")
    }
  }

  "Distribute of a round in review" should {

    "not be refused by the rounds it leaves out being distributed: it reads none" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val other = service.submit(newRound(contest, "Other"), jurors.map(_.getId), None, review = true).round.getId
      val r = service.submit(newRound(contest, "R").copy(excludeRounds = Seq(other)), jurors.map(_.getId), None,
        review = true).round.getId
      // the round it leaves out has a job queued now
      DB.localTx(implicit s => sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
        VALUES (${RoundDistributionHandler.Kind}, 10, 'round', $other, ${RoundDistributionHandler.dedupeKey(other)},
          'queued', '{}', '{}')""".update())
      (service.distributeReviewed(r, None) must beAnInstanceOf[RoundService.ReviewDistribution.Started])
    }
  }

  "fill and then distribute" should {

    var legacyNumber = 100

    /** A creation queued before P3 (no mode: one pass), with the same settings. */
    def legacy(h: RoundDistributionHandler, scheduler: services.jobs.JobScheduler, round: Round, jurors: Seq[User],
        titles: Seq[String] = Nil): Long = {
      legacyNumber += 1
      val created = roundDao.create(round.withExplicitTopOrder(round.previousIds.nonEmpty)
        .copy(number = legacyNumber, active = false, distributing = true))
      created.addUsers(jurors.map(u => RoundUser(created.getId, u.getId, "jury", active = true)))
      val queued = DB.localTx { implicit s =>
        Round.lock(Seq(created.getId))
        scheduler.enqueue(NewJob(kind = RoundDistributionHandler.Kind, contestId = Some(round.contestId),
          subjectType = Some("round"), subjectId = created.id, dedupeKey = Some(RoundDistributionHandler.dedupeKey(created.getId)),
          params = Json.obj("roundId" -> created.getId, "completingCreation" -> true) ++
            (if (titles.isEmpty) Json.obj() else Json.obj("titles" -> titles, "titleCount" -> titles.size)),
          state = h.initialState(created), progress = Progress(unit = Some("images"))))
      }.toOption.get
      scheduler.started(queued.id)
      created.getId
    }

    "give the same vote rows as one pass, over several windows, from previous rounds, by top-N and without conflicts of interest" in {
      val (contest, jurors) = setUp(12)
      User.create(User("Board", "board@example.com", None, Set(User.BOARD_ROLE), Some("hash"), contest.id,
        wikiAccount = Some("Board member")))
      DB.autoCommit(implicit s => sql"UPDATE images SET author = 'Board member' WHERE page_id = 5".update())
      val (h, scheduler, service) = failing(chunkRows = 4) // two files per window
      val prev = prevRound(contest, jurors, service)
      Seq(3, 7).foreach(p => VoteJdbc.byRound(prev.getId).filter(_.pageId == p).foreach(v =>
        VoteJdbc.rate(v.pageId, v.juryId, prev.getId, -1)))
      val settings = Seq(
        newRound(contest, "Windows"),
        newRound(contest, "Previous").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1)),
        newRound(contest, "Top").copy(previous = Some(prev.getId.toString), topImages = Some(6)),
        newRound(contest, "CoI").copy(excludeCoi = true)
      )
      forall(settings) { s =>
        val onePass = legacy(h, scheduler, s, jurors)
        val twoSteps = service.createNewRound(s, jurors.map(_.getId)).getId
        (pairs(twoSteps) === pairs(onePass)) and (pairs(twoSteps) must not(beEmpty)) and
          (round(twoSteps).openToJurors must beTrue) and (round(onePass).openToJurors must beTrue)
      } and (pairs(roundDao.findByContest(contest.getId).find(_.name.contains("CoI")).get.getId).map(_._1) must not(contain(5L)))
    }

    "with a pasted list, give every file its jurors, balanced, though in page order rather than the list's last" in {
      val (contest, jurors) = setUp(6)
      val listed = images(Seq(50L, 51L, 52L))
      imageDao.batchInsert(listed)
      val titles = listed.map(_.title)
      val (h, scheduler, service) = failing(chunkRows = 4)
      val onePass = legacy(h, scheduler, newRound(contest, "Old"), jurors, titles)
      val twoSteps = service.submit(newRound(contest, "New"), jurors.map(_.getId), None, titles = titles, review = false).round.getId
      val perJuror = VoteJdbc.byRound(twoSteps).groupBy(_.juryId).values.map(_.size)
      (pairs(twoSteps).map(_._1) === pairs(onePass).map(_._1)) and (pairs(twoSteps).size === 18) and
        (VoteJdbc.byRound(twoSteps).groupBy(_.pageId).values.map(_.size).toSet === Set(2)) and
        ((perJuror.max - perJuror.min) must beLessThanOrEqualTo(1))
    }
  }

  "the second review's fixes" should {

    "leave the previous rounds unfrozen and closed once across a D4 creation's fill and distribute" in {
      val (contest, jurors) = setUp(6)
      val (_, _, service) = failing(chunkRows = 4)
      val prev = prevRound(contest, jurors, service)
      val r = service.createNewRound(newRound(contest, "R").copy(previous = Some(prev.getId.toString)), jurors.map(_.getId))
      (freezeOf(prev.getId) === ((0, None, false))) and (round(r.getId).openToJurors must beTrue) and noDuplicates(r.getId, 6, 2)
    }

    "retry a D4 creation's failed distribute: held in review meanwhile, then given out once" in {
      val (contest, jurors) = setUp(6)
      val (h, scheduler, service) = failing(chunkRows = 4) // fill: 3 inserts, distribute: 3
      val prev = prevRound(contest, jurors, service)
      h.arm(5)
      val r = service.submit(newRound(contest, "R").copy(previous = Some(prev.getId.toString)), jurors.map(_.getId), None, review = false)
        .round.getId
      val failed = job(r)
      val held = (round(r).inReview, freezeOf(prev.getId))
      h.failAt = None
      scheduler.retry(failed.id)
      scheduler.started(failed.id)
      (RoundDistributionHandler.distributeMode(failed) must beTrue) and (failed.status === JobStatus.Failed) and
        (held === ((true, (0, None, true)))) and (round(r).openToJurors must beTrue) and noDuplicates(r, 6, 2) and
        (freezeOf(prev.getId) === ((0, None, false)))
    }

    "cancel a D4 creation's distribute: the round held in review, Distribute gives the rest once" in {
      val (contest, jurors) = setUp(6)
      val (h, scheduler, service) = failing(chunkRows = 4)
      h.arm(5)
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = false).round.getId
      scheduler.cancel(job(r).id, Some(5L))
      val held = (round(r).inReview, round(r).status)
      h.failAt = None
      service.distributeReviewed(r, None)
      (held === ((true, Some(Round.Status.Paused)))) and (round(r).openToJurors must beTrue) and noDuplicates(r, 6, 2)
    }

    "cancel a distributed round's distribute: the round stays open, \"Distribute new files\" gives the rest once" in {
      val (contest, jurors) = setUp(4)
      val (h, scheduler, service) = failing(chunkRows = 4)
      val r = service.createNewRound(newRound(contest, "R"), jurors.map(_.getId)).getId
      val more = images(Seq(5L, 6L, 7L, 8L))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      h.arm(4) // the fill: 2 inserts; its distribute's second window fails
      scala.util.Try(service.distributeNewImages(r))
      scheduler.cancel(job(r).id, Some(5L))
      val open = round(r).openToJurors
      h.failAt = None
      service.distributeNewImages(r)
      (open must beTrue) and noDuplicates(r, 8, 2) and (round(r).status === Some(Round.Status.Active))
    }

    "not freeze the previous rounds for a distribute that completes no creation (a list on a distributed round)" in {
      val (contest, jurors) = setUp(4)
      val seen = scala.collection.mutable.Buffer.empty[(Int, Option[Boolean], Boolean)]
      var prevId = 0L
      val h = new FailingHandler(4) {
        override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit =
          if (prevId > 0) seen += sql"SELECT freeze_count, active_before_freeze, active FROM round WHERE id = $prevId"
            .map(rs => (rs.int(1), rs.booleanOpt(2), rs.boolean(3))).single().get
      }
      val service = new RoundService(Round, TestRoundServices.inlineScheduler(h), h)
      val prev = prevRound(contest, jurors, service)
      val r = service.createNewRound(newRound(contest, "R").copy(previous = Some(prev.getId.toString)), jurors.map(_.getId)).getId
      Round.setActive(prev.getId, active = true) // started again
      val listed = images(Seq(60L))
      imageDao.batchInsert(listed)
      prevId = prev.getId
      service.distributeNewFiles(r, None, titles = listed.map(_.title), newFiles = false)
      (seen.toSeq.distinct === Seq((0, None, true))) and (VoteJdbc.byRound(r).count(_.pageId == 60L) === 2) and
        (freezeOf(prev.getId) === ((0, None, true)))
    }

    "go over the round once more when a file is restored below its cursor during the distribute" in {
      val (contest, jurors) = setUp(6)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 4))
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(1L)))
      // the distribute's windows: (2, 3), (4, 5), (6); at the second, in its transaction, file 1 is back
      val hooked = new FailingHandler(4) {
        private var n = 0
        override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = {
          n += 1
          if (n == 2) {
            sql"UPDATE images SET deleted_at = NULL WHERE page_id = 1".update()
            sql"UPDATE round_image SET status = 'in' WHERE round_id = $r AND page_id = 1".update()
          }
        }
      }
      val svc = new RoundService(Round, TestRoundServices.inlineScheduler(hooked), hooked)
      svc.distributeReviewed(r, None)
      (VoteJdbc.byRound(r).count(_.pageId == 1L) === 2) and noDuplicates(r, 6, 2) and
        (RoundDistributionHandler.stateOf(job(r)).rescanned must beTrue) and (round(r).openToJurors must beTrue)
    }

    "give out a requalified file with no votes on a stopped round's \"Distribute new files\"" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.createNewRound(newRound(contest, "R"), jurors.map(_.getId)).getId
      Round.setActiveGuarded(r, active = false)
      // file 2 disqualified, its votes moved aside, then gone (a hand edit)
      DB.localTx { implicit s =>
        RoundWrites.lock(Seq(r))
        ImageJdbc.moveVotesAsideInRound(r, Seq(2L))
        RoundImageJdbc.disqualify(r, Map(2L -> "x"), RoundImageJdbc.DqCode.Manual, None, Map(2L -> "in"))
      }
      DB.autoCommit(implicit s => sql"DELETE FROM vote_aside WHERE round_id = $r AND page_id = 2".update())
      val admin = User("Admin", "admin@example.com", id = Some(1L))
      val requalified = RoundConflicts.requalify(r, Seq(2L), RoundConflicts.RequalifyReason.MistakenMatch, admin,
        RoundDistributionHandler.dedupeKey(r))
      val status = RoundImageJdbc.find(r, 2L).map(_.status)
      service.distributeNewImages(r)
      (requalified must beAnInstanceOf[RoundConflicts.Outcome.Requalified]) and (status === Some("in")) and
        (VoteJdbc.byRound(r).count(_.pageId == 2L) === 2)
    }

    "add a juror to a round held in review: the round_user row only, then Distribute gives them a share" in {
      val (contest, jurors) = setUp(6)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R", distribution = 1), jurors.take(2).map(_.getId), None, review = true).round
      val added = jurors(2)
      val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(TestRoundServices.handler()))
      val shown = RoundRebalanceService.signature(rebalance.jury(r).jurors)
      val outcome = rebalance.add(r, added.getId, User("Admin", "admin@example.com", id = Some(1L)), shown)
      val jobsAfter = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM job WHERE subject_id = ${r.getId}".map(_.int(1)).single())
      service.distributeReviewed(r.getId, None)
      (outcome === RoundRebalanceService.AddedToReview) and (jobsAfter === Some(1)) and
        (RoundUser.activeJurors(r.getId).map(_.userId).toSet === jurors.map(_.getId).toSet) and
        (VoteJdbc.byRound(r.getId).count(_.juryId == added.getId) === 2) and noDuplicates(r.getId, 6, 1)
    }
  }

  "the Retry of a distribute that completes no creation" should {

    "leave the previous rounds unfrozen while the Retry runs and after" in {
      val (contest, jurors) = setUp(8)
      val seen = scala.collection.mutable.Buffer.empty[Int]
      var prevId = 0L
      @volatile var armed = false
      val h = new FailingHandler(4) {
        private var distributeWindows = 0
        override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit = {
          if (prevId > 0) seen += sql"SELECT freeze_count FROM round WHERE id = $prevId".map(_.int(1)).single().get
          // a distribute's window: files already in the round (a fill's are added after this)
          val distributing = images.nonEmpty && images.forall(i => RoundImageJdbc.find(round.getId, i.pageId).isDefined)
          if (armed && distributing) {
            distributeWindows += 1
            if (distributeWindows == 2) throw new RuntimeException("simulated distribute failure")
          }
        }
      }
      val scheduler = TestRoundServices.inlineScheduler(h)
      val service = new RoundService(Round, scheduler, h)
      val prev = prevRound(contest, jurors, service)
      def selectInPrev(rate: Int) = DB.autoCommit(implicit s =>
        sql"UPDATE vote SET rate = $rate WHERE round_id = ${prev.getId} AND page_id IN (5, 6, 7, 8)".update())
      selectInPrev(0) // files 1-4 selected in the previous round, so far
      val r = service.createNewRound(newRound(contest, "R").copy(previous = Some(prev.getId.toString), prevSelectedBy = Some(1)),
        jurors.map(_.getId)).getId
      Round.setActive(prev.getId, active = true)
      selectInPrev(1) // now files 5-8 too: "Distribute new files" takes them
      armed = true // the new files' distribute fails at its second window
      scala.util.Try(service.distributeNewImages(r))
      val failed = job(r)
      armed = false
      prevId = prev.getId // watched during the Retry
      scheduler.retry(failed.id)
      scheduler.started(failed.id)
      (RoundDistributionHandler.distributeMode(failed) must beTrue) and
        (JobJdbc.findById(failed.id).map(_.status) === Some(JobStatus.Done)) and
        (seen.toSeq.distinct === Seq(0)) and (freezeOf(prev.getId) === ((0, None, true))) and noDuplicates(r, 8, 2)
    }
  }
}
