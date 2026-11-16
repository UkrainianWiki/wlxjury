package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.jobs.{JobJdbc, JobStatus}
import services.{RoundService, TestRoundServices}

/** A round held in review (P3; P3-5 of docs/plans/2026-11-16-round-editing.md): jurors optional until
  * Distribute, which picks them (refused with none, more per file than jurors, a change after a
  * partial distribution, or previous rounds' votes changed since the fill unless confirmed, D18);
  * "Re-apply filters": the round's `fill` files no longer passing deleted (D16, never with a vote),
  * new matches added, list and disqualified rows kept and never re-added, a top-N ranked again with
  * the disqualified skipped (D17), the round still in review, and a distribute after it giving the
  * same votes as one pass with the final filters. Jobs run inline; truncation, not AutoRollback.
  */
class RoundReviewJurorsReapplySpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Files 1-4 small (0.8 Mpx), 5-8 large (6 Mpx). */
  private def images(ids: Seq[Long]): Seq[Image] =
    ids.map(i => if (i <= 4) Image(i, s"File:Image$i.jpg", None, None, 1000, 800, Some(s"14-101-$i"))
                 else Image(i, s"File:Image$i.jpg", None, None, 3000, 2000, Some(s"14-101-$i")))

  /** Images 1..n in the contest's category, three jurors. */
  private def setUp(n: Int = 8): (ContestJury, Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val imgs = images((1 to n).map(_.toLong))
    imageDao.batchInsert(imgs)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, imgs)
    (contest, createUsers("jury", 1, 2, 3).sortBy(_.getId))
  }

  private def newRound(contest: ContestJury, name: String, distribution: Int = 0) =
    Round(None, 0, Some(name), contest.getId, distribution = distribution, active = true)

  private def pairs(roundId: Long): Set[(Long, Long)] = VoteJdbc.byRound(roundId).map(v => v.pageId -> v.juryId).toSet
  private def round(id: Long): Round = roundDao.findById(id).get
  private def job(roundId: Long) =
    JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId)).get(roundId).get
  private def jobCount(roundId: Long): Int =
    DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM job WHERE subject_id = $roundId".map(_.int(1)).single().get)
  private def rows(roundId: Long): Seq[(Long, String, String)] =
    RoundImageJdbc.byRound(roundId).map(r => (r.pageId, r.status, r.addedHow))
  private def roundUsers(roundId: Long): Seq[Long] = RoundUser.byRoundId(roundId).map(_.userId).sorted
  private def vote(roundId: Long, pageId: Long, jurorId: Long, rate: Int = 0): Unit =
    DB.localTx { implicit s => RoundWrites.lock(Seq(roundId)); VoteJdbc.create(pageId, rate, jurorId, roundId) }

  "a round created for review without jurors" should {

    "be filled and held; Distribute refused until jurors are chosen, then the same votes as one pass" in {
      val (contest, jurors) = setUp(6)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "No jury"), Nil, None, review = true).round.getId
      val held = round(r)
      val usersBefore = roundUsers(r)
      val refused = service.distributeReviewed(r, Some(1L))
      val refusedEmpty = service.distributeReviewed(r, Some(1L), jurors = Some(Nil))
      val jobsAfterRefusals = jobCount(r)
      val onePass = service.createNewRound(newRound(contest, "One pass", distribution = 2), jurors.map(_.getId)).getId
      val started = service.distributeReviewed(r, Some(1L), jurors = Some(jurors.map(_.getId)), distribution = Some(2))
      (held.inReview must beTrue) and (rows(r).size === 6) and (usersBefore must beEmpty) and
        (refused === RoundService.ReviewDistribution.Refused(RoundService.NoJurorsKey, Seq(held.number.toString))) and
        (refusedEmpty must beAnInstanceOf[RoundService.ReviewDistribution.Refused]) and
        (jobsAfterRefusals === 1) and
        (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (round(r).openToJurors must beTrue) and (round(r).distribution === 2) and
        (roundUsers(r) === jurors.map(_.getId)) and
        (pairs(r) === pairs(onePass)) and (pairs(r).size === 12) and
        (RoundImageInvariants.violations(r) must beEmpty)
    }

    "be refused with \"Distribute as soon as ready\" (D4): nothing created" in {
      val (contest, _) = setUp(2)
      val service = TestRoundServices.roundService()
      (service.submit(newRound(contest, "At once"), Nil, None, review = false) must throwA[IllegalArgumentException]) and
        (Round.findByContest(contest.getId) must beEmpty)
    }

    "refuse more jurors per file than jurors chosen" in {
      val (contest, jurors) = setUp(2)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), Nil, None, review = true).round.getId
      (service.distributeReviewed(r, Some(1L), jurors = Some(Seq(jurors.head.getId)), distribution = Some(2)) ===
        RoundService.ReviewDistribution.Refused(RoundService.TooManyPerFileKey, Seq("2", "1"))) and
        (round(r).inReview must beTrue) and (roundUsers(r) must beEmpty) and (round(r).distribution === 0)
    }

    "only fill on \"Distribute new files\", and take its first juror from the add page" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), Nil, None, review = true).round.getId
      val more = images(Seq(9L, 10L))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      val added = service.distributeNewImages(r)
      val admin = createUsers("admin", 9)(contest, AutoSession).head
      val rebalance = new RoundRebalanceService(TestRoundServices.inlineScheduler(TestRoundServices.handler()))
      val outcome = rebalance.add(round(r), jurors.head.getId, admin, RoundRebalanceService.signature(rebalance.jury(round(r)).jurors))
      (added === 2) and (round(r).inReview must beTrue) and (VoteJdbc.byRound(r) must beEmpty) and
        (outcome === RoundRebalanceService.AddedToReview) and (roundUsers(r) === Seq(jurors.head.getId))
    }
  }

  "the jurors of a round in review" should {

    "not change once a partial distribution gave votes; Distribute to its own jurors goes on" in {
      val (contest, jurors) = setUp(3)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      vote(r, 1L, jurors.head.getId)
      val refused = service.distributeReviewed(r, Some(1L), jurors = Some(Seq(jurors.head.getId)), distribution = Some(0))
      val kept = roundUsers(r)
      val started = service.distributeReviewed(r, Some(1L), jurors = Some(jurors.map(_.getId)), distribution = Some(0))
      (refused === RoundService.ReviewDistribution.Refused(RoundService.JurorsLockedKey, Seq(round(r).number.toString))) and
        (kept === jurors.map(_.getId)) and (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (round(r).openToJurors must beTrue)
    }
  }

  "re-applying filters" should {

    "remove the fill files no longer passing, keep list and disqualified rows, add new matches, stay in review" in {
      val (contest, jurors) = setUp(8)
      // windows of one file: the scan and the fill each take several steps
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      DB.autoCommit { implicit s =>
        sql"UPDATE round_image SET added_how = 'list' WHERE round_id = $r AND page_id = 1".update()
        sql"UPDATE round_image SET status = 'dq', dq_code = 'manual', dq_at = NOW(3) WHERE round_id = $r AND page_id = 2".update()
      }
      val narrowed = service.reapplyFilters(round(r).copy(minMpx = Some(1)), Some(1L))
      val narrowedJob = job(r)
      val afterNarrow = rows(r)
      val narrowedRound = round(r)
      val widened = service.reapplyFilters(round(r).copy(minMpx = None), Some(1L))
      val widenedJob = job(r)
      (narrowed must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (narrowedJob.status === JobStatus.Done) and
        ((narrowedJob.params \ "reapply").as[Boolean] must beTrue) and
        (afterNarrow === Seq((1L, "in", "list"), (2L, "dq", "fill")) ++ (5L to 8L).map(p => (p, "in", "fill"))) and
        (RoundDistributionHandler.stateOf(narrowedJob).removed === 2L) and
        (RoundDistributionHandler.stateOf(narrowedJob).imagesAdded === 0L) and
        (RoundDistributionHandler.stateOf(narrowedJob).removedTitles === Seq("File:Image3.jpg", "File:Image4.jpg")) and
        (narrowedRound.minMpx === Some(1)) and (narrowedRound.inReview must beTrue) and
        (widened must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (widenedJob.status === JobStatus.Done) and
        (RoundDistributionHandler.stateOf(widenedJob).imagesAdded === 2L) and
        (RoundDistributionHandler.stateOf(widenedJob).removed === 0L) and
        // the disqualified file never comes back
        (rows(r) === Seq((1L, "in", "list"), (2L, "dq", "fill"), (3L, "in", "fill"), (4L, "in", "fill")) ++
          (5L to 8L).map(p => (p, "in", "fill"))) and
        (round(r).inReview must beTrue) and (round(r).status === Some(Round.Status.Paused)) and
        (VoteJdbc.byRound(r) must beEmpty) and (RoundImageInvariants.violations(r) must beEmpty)
    }

    "be refused with a vote in the round, for a round not in review, and while a job of the round is queued" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val voted = service.submit(newRound(contest, "Voted"), jurors.map(_.getId), None, review = true).round.getId
      vote(voted, 1L, jurors.head.getId)
      val refusedVoted = service.reapplyFilters(round(voted).copy(minMpx = Some(1)), Some(1L))
      val open = service.createNewRound(newRound(contest, "Open"), jurors.map(_.getId)).getId
      val queued = service.submit(newRound(contest, "Queued"), jurors.map(_.getId), None, review = true).round.getId
      DB.localTx(implicit s => sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
        VALUES (${RoundDistributionHandler.Kind}, ${contest.getId}, 'round', $queued, ${RoundDistributionHandler.dedupeKey(queued)},
          'queued', '{"mode": "fill"}', '{}')""".update())
      (refusedVoted === RoundService.ReviewDistribution.Refused(RoundService.ReapplyVotedKey, Seq(round(voted).number.toString))) and
        (round(voted).minMpx must beNone) and (rows(voted).size === 4) and
        (service.reapplyFilters(round(open).copy(minMpx = Some(1)), Some(1L)) === RoundService.ReviewDistribution.NotInReview) and
        (round(open).minMpx must beNone) and
        (service.reapplyFilters(round(queued).copy(minMpx = Some(1)), Some(1L)) must
          beAnInstanceOf[RoundService.ReviewDistribution.Running]) and (round(queued).minMpx must beNone) and
        (service.reapplyFilters(round(voted).copy(previous = Some(voted.toString)), Some(1L)) ===
          RoundService.ReviewDistribution.Refused(RoundService.PreviousSelfKey, Seq(round(voted).number.toString)))
    }

    "rank a top-N again from scratch, the disqualified files skipped (D17)" in {
      val (contest, jurors) = setUp(6)
      val service = TestRoundServices.roundService()
      val prev = service.createNewRound(newRound(contest, "Prev").copy(rates = Round.binaryRound), jurors.map(_.getId)).getId
      // file 6: three yes; 5: two; 4: one; the rest no
      val yes = Map(6L -> 3, 5L -> 2, 4L -> 1)
      for (p <- 1L to 6L; (j, k) <- jurors.zipWithIndex)
        VoteJdbc.rate(p, j.getId, prev, if (k < yes.getOrElse(p, 0)) 1 else -1)
      val r = service.submit(newRound(contest, "Top").copy(previous = Some(prev.toString), topImages = Some(2)),
        jurors.map(_.getId), None, review = true).round.getId
      val first = rows(r).map(_._1)
      DB.autoCommit(implicit s => sql"UPDATE round_image SET status = 'dq', dq_code = 'manual', dq_at = NOW(3) WHERE round_id = $r AND page_id = 6".update())
      service.reapplyFilters(round(r), Some(1L))
      (first === Seq(5L, 6L)) and
        (rows(r) === Seq((4L, "in", "fill"), (5L, "in", "fill"), (6L, "dq", "fill"))) and
        (round(r).inReview must beTrue) and (round(prev).freezeCount === 0)
    }

    "give, distributed after, the same votes as one pass with the final filters" in {
      val (contest, jurors) = setUp(8)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R", distribution = 2), jurors.map(_.getId), None, review = true).round.getId
      service.reapplyFilters(round(r).copy(minMpx = Some(1)), Some(1L))
      service.distributeReviewed(r, Some(1L))
      val onePass = service.createNewRound(newRound(contest, "One pass", distribution = 2).copy(minMpx = Some(1)),
        jurors.map(_.getId)).getId
      (pairs(r) === pairs(onePass)) and (pairs(r).map(_._1) === (5L to 8L).toSet) and
        (round(r).openToJurors must beTrue) and (RoundImageInvariants.violations(r) must beEmpty)
    }
  }

  "previous rounds' votes changed since the fill (D18)" should {

    "be told, and refuse Distribute unless confirmed" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val prev = service.createNewRound(newRound(contest, "Prev"), jurors.map(_.getId)).getId
      val r = service.submit(newRound(contest, "Next").copy(previous = Some(prev.toString)), jurors.map(_.getId), None,
        review = true).round.getId
      val before = RoundService.prevChanged(round(r))
      VoteJdbc.rate(1L, jurors.head.getId, prev, 1)
      val after = RoundService.prevChanged(round(r))
      val refused = service.distributeReviewed(r, Some(1L))
      val confirmed = service.distributeReviewed(r, Some(1L), confirmPrevChanged = true)
      (before must beFalse) and (after must beTrue) and
        (refused === RoundService.ReviewDistribution.Refused(RoundService.PrevChangedKey, Seq(round(r).number.toString))) and
        (confirmed must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and (round(r).openToJurors must beTrue)
    }
  }

  // ── the independent review's cases (M1, M2, L1, L3, the vote mid-scan, the monument ids filter) ──

  /** A handler of small chunks with test hooks: a vote written in its first scan step (a juror's vote
    * arriving mid re-apply, through a partial distribute), a failure at its `failAt`th insert, and an
    * extra row written by someone else after its first insert (its count check then fails once).
    */
  private class HookedHandler(chunkRows: Int) extends RoundDistributionHandler(
      new org.intracer.wmua.cmd.DistributeImages(ImageJdbc), TestRoundServices.config(chunkRows), TestRoundServices.emptyCommons) {
    @volatile var voteBy: Option[Long] = None
    @volatile var failAt: Option[Int] = None
    @volatile var extraRow: Option[Long] = None
    private val inserts = new java.util.concurrent.atomic.AtomicInteger()
    override protected def beforePrune(round: Round, pageIds: Seq[Long])(implicit session: DBSession): Unit =
      for (juror <- voteBy; page <- pageIds.headOption) {
        voteBy = None
        VoteJdbc.create(page, 0, juror, round.getId)
      }
    override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit =
      failAt.foreach { n => if (inserts.incrementAndGet() == n) throw new RuntimeException(s"simulated failure at insert $n") }
    override protected def afterInsert(round: Round, rows: Seq[org.intracer.wmua.Vote])(implicit session: DBSession): Unit =
      extraRow.foreach { page =>
        extraRow = None
        sql"""INSERT INTO round_image (round_id, page_id, status, added_how, added_at)
              VALUES (${round.getId}, $page, 'in', 'manual', NOW(3))""".update()
      }
  }

  private def hooked(chunkRows: Int) = {
    val h = new HookedHandler(chunkRows)
    val scheduler = TestRoundServices.inlineScheduler(h)
    (h, scheduler, new RoundService(Round, scheduler, h))
  }

  "the review's fixes" should {

    "refuse a juror change from a page showing other jurors than the round has now (M1)" in {
      val (contest, jurors) = setUp(3)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.take(2).map(_.getId), None, review = true).round.getId
      val shown = RoundService.jurySignature(r)
      DB.autoCommit(implicit s =>
        sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${jurors(2).getId}, $r, 'jury', TRUE)".update())
      val stale = service.distributeReviewed(r, Some(1L), jurors = Some(jurors.take(2).map(_.getId)), shownJury = Some(shown))
      val fresh = service.distributeReviewed(r, Some(1L), jurors = Some(jurors.take(2).map(_.getId)),
        shownJury = Some(RoundService.jurySignature(r)))
      (stale === RoundService.ReviewDistribution.Refused(RoundService.JurorsChangedKey, Seq(round(r).number.toString))) and
        (fresh must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and
        (roundUsers(r) === jurors.take(2).map(_.getId))
    }

    "refuse Distribute after a re-apply that failed, until re-applied again (M2)" in {
      val (contest, jurors) = setUp(8)
      val (h, _, service) = hooked(chunkRows = 3)
      val r = service.submit(newRound(contest, "R").copy(minMpx = Some(1)), jurors.map(_.getId), None, review = true).round.getId
      h.failAt = Some(1)
      service.reapplyFilters(round(r).copy(minMpx = None), Some(1L))
      val failed = job(r)
      h.failAt = None
      val refused = service.distributeReviewed(r, Some(1L))
      service.reapplyFilters(round(r), Some(1L))
      val started = service.distributeReviewed(r, Some(1L))
      (failed.status === JobStatus.Failed) and
        (refused === RoundService.ReviewDistribution.Refused(RoundService.ReapplyUnfinishedKey, Seq(round(r).number.toString))) and
        (started must beAnInstanceOf[RoundService.ReviewDistribution.Started]) and (round(r).openToJurors must beTrue) and
        (VoteJdbc.byRound(r).map(_.pageId).toSet === (1L to 8L).toSet)
    }

    "stop a re-apply when a vote arrives during its scan, deleting no file with a vote" in {
      val (contest, jurors) = setUp(8)
      val (h, _, service) = hooked(chunkRows = 3)
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      h.voteBy = Some(jurors.head.getId)
      service.reapplyFilters(round(r).copy(minMpx = Some(1)), Some(1L))
      val failed = job(r)
      (failed.status === JobStatus.Failed) and (failed.failure.map(_.key) === Some(RoundDistributionHandler.ReapplyVotedKey)) and
        (RoundImageJdbc.find(r, 1L).map(_.status) === Some("in")) and (VoteJdbc.byRound(r).map(_.pageId) === Seq(1L))
    }

    "keep the files removed across a Retry after a failed count check (L1)" in {
      val (contest, jurors) = setUp(8)
      val (h, scheduler, service) = hooked(chunkRows = 3)
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      imageDao.batchInsert(Seq(Image(20L, "File:Image20.jpg", None, None, 3000, 2000, Some("14-101-20"))))
      h.extraRow = Some(20L)
      service.reapplyFilters(round(r).copy(minMpx = Some(1)), Some(1L))
      val failed = job(r)
      scheduler.retry(failed.id)
      scheduler.started(failed.id)
      val done = JobJdbc.findById(failed.id).get
      (failed.status === JobStatus.Failed) and (done.status === JobStatus.Done) and
        (RoundDistributionHandler.stateOf(done).removedTotal === 4L) and
        (rows(r).map(_._1) === (5L to 8L) :+ 20L) and (round(r).inReview must beTrue)
    }

    "remove the files \"Distribute new files\" added in review that no longer pass (L3)" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R"), jurors.map(_.getId), None, review = true).round.getId
      val more = Seq(Image(9L, "File:Image9.jpg", None, None, 1000, 800, Some("14-101-9")),
        Image(10L, "File:Image10.jpg", None, None, 3000, 2000, Some("14-101-10")))
      imageDao.batchInsert(more)
      CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, more)
      service.distributeNewImages(r)
      val added = rows(r).filter(_._3 == "new_files").map(_._1)
      service.reapplyFilters(round(r).copy(minMpx = Some(1)), Some(1L))
      (added === Seq(9L, 10L)) and (rows(r) === Seq((10L, "in", "new_files"))) and
        (RoundDistributionHandler.stateOf(job(r)).removed === 5L)
    }

    "read the monument ids filter with spaces after its commas" in {
      val (contest, jurors) = setUp(4)
      val service = TestRoundServices.roundService()
      val r = service.submit(newRound(contest, "R").copy(monuments = Some("14-101-1, 14-101-3 ,")), jurors.map(_.getId),
        None, review = true).round.getId
      (round(r).monumentIds === Seq("14-101-1", "14-101-3")) and (rows(r).map(_._1) === Seq(1L, 3L))
    }
  }
}
