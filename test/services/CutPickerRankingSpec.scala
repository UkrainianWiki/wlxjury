package services

import db.scalikejdbc._
import org.intracer.wmua.{AverageRule, BinaryOrder, ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.distribution.RoundDistributionHandler
import services.jobs.{JobError, JobJdbc, JobStatus}

/** The cut picker's ranking (regional winners plan §10.4, §10.9 "the preview's count equals the
  * fill's"): the files ranked by the distribution's own rank key, rows and per-image filters, so that a
  * top N of the fill takes exactly the picker's first N; ties by the score alone (decided 2026-11-26),
  * the score range of a rated round, yes/no levels by the round's order, filters (size, region, the
  * rounds left out, a re-apply's disqualified files) applied before ranking; the job's backstop.
  */
class CutPickerRankingSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** `n` images in the contest's category (ids 1..n; those in `small` 0.8 Mpx, the others 6 Mpx). */
  private def setUp(n: Int, jurors: Int, small: Set[Long] = Set.empty): (ContestJury, Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val images = (1 to n).map { i =>
      val (w, h) = if (small(i.toLong)) (1000, 800) else (3000, 2000)
      Image(i.toLong, s"File:Image$i.jpg", None, None, width = w, height = h, monumentId = Some(s"12-345-$i"),
        size = Some(500 * 1024), mediaType = Some("BITMAP"))
    }
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    (contest, createUsers("jury", (1 to jurors): _*).sortBy(_.getId))
  }

  private def prevRound(contest: ContestJury, rates: Rates, jurors: Seq[User],
      rule: AverageRule = AverageRule.Rated)(votes: (Long, Int, Int)*): Round = {
    val round = roundDao.create(Round(None, 1, Some("Prev"), contest.getId, rates = rates, active = false,
      averageRule = rule))
    round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
    votes.foreach { case (page, juror, rate) => VoteJdbc.create(page, rate, jurors(juror).getId, round.getId) }
    roundDao.findById(round.getId).get
  }

  private def next(contest: ContestJury, from: Round, extra: Round => Round = identity): Round =
    extra(Round(None, 0, Some("Next"), contest.getId, distribution = 1, rates = Round.ratesById(10), active = true,
      previous = Some(from.getId.toString)))

  private def files(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

  /** The picker's ranked files of `round` (the handler's ranking, every file kept whole), as the picker
    * ranks them: without the round's threshold.
    */
  private def ranked(round: Round, skipDqOf: Option[Long] = None): Seq[Long] =
    TestRoundServices.handler().topNRanking(round.copy(prevMinAvgRate = None).withFixedCategories.withCanonicalCategoryRule,
      keep = 1000, skipDqOf).get.files.map(_.file.pageId)

  /** A round's files `in` it (round_image), whatever their votes. */
  private def inRound(roundId: Long): Set[Long] =
    RoundImageJdbc.byRound(roundId).filter(_.status == RoundImageJdbc.Status.In).map(_.pageId).toSet

  private def job(roundId: Long) =
    JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
      .get(roundId).get

  /** Rated 1–10 by three jurors: files 1..8 with averages 9, 8, 8, 8, 7, 6, 6, 5 (ties 2–4 and 6–7). */
  private val ratedVotes: Seq[(Long, Int, Int)] =
    Seq(1L -> 9, 2L -> 8, 3L -> 8, 4L -> 8, 5L -> 7, 6L -> 6, 7L -> 6, 8L -> 5).flatMap { case (p, r) =>
      (0 to 2).map(j => (p, j, r))
    }

  "a rated source's ranking" should {

    "rank by the fill's key, ties by it, with the round's score range" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None).get
      (picker.m === 8) and (ranked(next(contest, prev)).take(2) === Seq(1L, 2L)) and
        (picker.result.validUpTo(100) === Seq(1, 4, 5, 7, 8)) and
        (picker.result.refusal(3).map(_.key) must beSome(TopNCandidates.InTieKey)) and
        (picker.levels must beFalse) and (picker.mixedScales must beFalse) and
        (CutPicker.range(prev, 3) === 10.0)
    }

    "be what the fill takes: a valid N takes the picker's first N files" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val picker = service.cutPicker(next(contest, prev), None).get
      val created = service.createNewRound(next(contest, prev, _.copy(topImages = Some(4))), jurors.map(_.getId))
      (picker.result.isValid(4) must beTrue) and (files(created.getId) === ranked(next(contest, prev)).take(4).toSet)
    }

    "apply the round's per-image filters before ranking (a minimum size)" in {
      val (contest, jurors) = setUp(8, 3, small = Set(1L))
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val sized = service.cutPicker(next(contest, prev, _.copy(minMpx = Some(2))), None).get
      (sized.m === 7) and (ranked(next(contest, prev, _.copy(minMpx = Some(2)))) must not(contain(1L))) and
        (sized.result.validUpTo(100) === Seq(3, 4, 6, 7))
    }

    "tie files of one score whatever their sums: N never cuts between them" in {
      // averages over the rated votes: file 1 8 (three votes, sum 24), file 2 8 (two votes, sum 16), file 3 7
      val (contest, jurors) = setUp(3, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)((1L, 0, 8), (1L, 1, 8), (1L, 2, 8), (2L, 0, 8),
        (2L, 1, 8), (3L, 0, 7), (3L, 1, 7), (3L, 2, 7))
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None).get
      (ranked(next(contest, prev)) === Seq(1L, 2L, 3L)) and (picker.result.validUpTo(10) === Seq(2, 3)) and
        (picker.result.refusal(1) must beSome(TopNCandidates.Msg(TopNCandidates.InTieUpperKey, Seq(1, 1, 2, 2))))
    }

    "be what the fill takes with a region filter" in {
      val (contest, jurors) = setUp(8, 3)
      DB.autoCommit(implicit s => sql"UPDATE images SET monument_id = '13-345-1' WHERE page_id IN (1, 5)".update())
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val regional = next(contest, prev, _.copy(regions = Some("12")))
      val picker = service.cutPicker(regional, None).get
      // 2–4 (8), 6–7 (6), 8 (5): valid 3, 5, 6
      val created = service.createNewRound(regional.copy(topImages = Some(3)), jurors.map(_.getId))
      (picker.m === 6) and (picker.result.validUpTo(10) === Seq(3, 5, 6)) and
        (files(created.getId) === ranked(regional).take(3).toSet) and
        (files(created.getId) === Set(2L, 3L, 4L))
    }

    "be what the fill takes with rounds left out" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      // another round of the best file only
      val other = service.createNewRound(next(contest, prev, _.copy(topImages = Some(1))), jurors.map(_.getId))
      val leftOut = next(contest, prev, _.copy(excludeRounds = Seq(other.getId)))
      val picker = service.cutPicker(leftOut, None).get
      // 2–4 (8), 5 (7), 6–7 (6), 8 (5): valid 3, 4, 6, 7
      val created = service.createNewRound(leftOut.copy(topImages = Some(4)), jurors.map(_.getId))
      (inRound(other.getId) === Set(1L)) and (picker.m === 7) and (picker.result.validUpTo(10) === Seq(3, 4, 6, 7)) and
        (files(created.getId) === ranked(leftOut).take(4).toSet) and
        (files(created.getId) === Set(2L, 3L, 4L, 5L))
    }

    "be what a re-apply takes, its round's disqualified files skipped" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val held = service.submit(next(contest, prev, _.copy(topImages = Some(4))), Nil, None, review = true).round
      DB.autoCommit { implicit s =>
        sql"UPDATE round_image SET status = 'dq', dq_code = 'manual', dq_at = NOW(3) WHERE round_id = ${held.getId} AND page_id = 1"
          .update()
      }
      val stored = roundDao.findById(held.getId).get
      val picker = service.cutPicker(stored, None, skipDqOf = held.id).get
      // without file 1: 2–4 (8), 5 (7), 6–7 (6), 8 (5): valid 3, 4, 6, 7
      service.reapplyFilters(stored.copy(topImages = Some(3)), Some(1L))
      (picker.m === 7) and (ranked(stored, held.id).take(3) === Seq(2L, 3L, 4L)) and
        (picker.result.validUpTo(10) === Seq(3, 4, 6, 7)) and
        (job(held.getId).status === JobStatus.Done) and
        (inRound(held.getId) === Set(2L, 3L, 4L))
    }

    "score a round scored by the sum on its jurors' range" in {
      val (contest, jurors) = setUp(4, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors, AverageRule.Sum)(
        (1L, 0, 9), (1L, 1, 9), (2L, 0, 9))
      CutPicker.range(prev.copy(distribution = 0), 3) === 30.0
    }
  }

  "a yes/no source's ranking" should {

    /** Three jurors: file 1 3 yes; 2–3 2 yes 1 no; 4 2 yes 0 no (one assigned); 5 1 yes 2 no. */
    def binaryVotes: Seq[(Long, Int, Int)] = Seq(
      (1L, 0, 1), (1L, 1, 1), (1L, 2, 1),
      (2L, 0, 1), (2L, 1, 1), (2L, 2, -1),
      (3L, 0, 1), (3L, 1, 1), (3L, 2, -1),
      (4L, 0, 1), (4L, 1, 1),
      (5L, 0, 1), (5L, 1, -1), (5L, 2, -1))

    "rank by levels of the round's order, the level boundaries the valid values" in {
      val (contest, jurors) = setUp(5, 3)
      val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
      val service = TestRoundServices.roundService()
      val default = service.cutPicker(next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.Default))),
        None).get
      val approvals = service.cutPicker(next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.ApprovalsOnly))),
        None).get
      // approvals then rejections: 1 (3, 0), 4 (2, 0), 2–3 (2, 1), 5 (1, 2)
      (default.levels must beTrue) and
        (ranked(next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.Default)))) === Seq(1L, 4L, 2L, 3L, 5L)) and
        (default.result.validUpTo(10) === Seq(1, 2, 4, 5)) and
        // approvals only: 1 (3), then 2, 3, 4 (2) tie, 5 (1)
        (approvals.result.validUpTo(10) === Seq(1, 4, 5))
    }

    "be what the fill takes for a valid N" in {
      val (contest, jurors) = setUp(5, 3)
      val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
      val service = TestRoundServices.roundService()
      val round = next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.Default)))
      val picker = service.cutPicker(round, None).get
      val created = service.createNewRound(round.copy(topImages = Some(2)), jurors.map(_.getId))
      (picker.result.isValid(2) must beTrue) and (files(created.getId) === ranked(round).take(2).toSet)
    }

    "use the default order when none is given, as a save stores it" in {
      val (contest, jurors) = setUp(5, 3)
      val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None).get
      picker.ranking.order must beSome(BinaryOrder.Default: BinaryOrder)
    }
  }

  "the job's Rank step (the save's backstop)" should {
    /** How the round form's check records a top N (CutPicker.basis): its presence marks the job. */
    val Checked = play.api.libs.json.Json.obj("chosen" -> 1)

    "fail a new round's fill whose N cuts inside a tie, adding no file, with the form's message" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      // N = 3 inside the tie 2–4, checked by the form (its basis) before the votes changed (as if)
      val held = service.submit(next(contest, prev, _.copy(topImages = Some(3))), Nil, None, review = true,
        topImagesBasis = Some(Checked)).round
      val failed = job(held.getId)
      (failed.status === JobStatus.Failed) and
        (failed.error.map(JobError.decode) must beSome(JobError(TopNCandidates.InTieKey, Seq("3", "2", "4", "1", "4")))) and
        (inRound(held.getId) must beEmpty)
    }

    "read a tie to its end past the first N + 1 files, and let a valid N through" in {
      val (contest, jurors) = setUp(8, 3)
      // places 2–7 tie at 8
      val prev = prevRound(contest, Round.ratesById(10), jurors)(
        Seq(1L -> 9, 2L -> 8, 3L -> 8, 4L -> 8, 5L -> 8, 6L -> 8, 7L -> 8, 8L -> 5).flatMap { case (p, r) =>
          (0 to 2).map(j => (p, j, r))
        }: _*)
      val service = TestRoundServices.roundService()
      val tied = service.submit(next(contest, prev, _.copy(topImages = Some(2))), Nil, None, review = true,
        topImagesBasis = Some(Checked)).round
      val valid = service.submit(next(contest, prev, _.copy(name = Some("Valid"), topImages = Some(7))), Nil, None,
        review = true, topImagesBasis = Some(Checked)).round
      // not checked by the form (another path, or a round from before the picker): the fill cuts as before
      val unchecked = service.submit(next(contest, prev, _.copy(name = Some("Unchecked"), topImages = Some(2))), Nil,
        None, review = true).round
      (job(tied.getId).error.map(JobError.decode) must beSome(JobError(TopNCandidates.InTieKey,
        Seq("2", "2", "7", "1", "7")))) and
        (job(valid.getId).status === JobStatus.Done) and (inRound(valid.getId) === (1L to 7L).toSet) and
        (job(unchecked.getId).status === JobStatus.Done) and (inRound(unchecked.getId).size === 2)
    }
  }

  "a category filter's files" should {

    /** A category of files 2 to 8 (not 1), each read counted; or Commons failing. */
    final class Categories(fail: Boolean) extends org.intracer.wmua.cmd.DistributeImages(ImageJdbc) {
      @volatile var reads = 0
      override def categoryFileIds(category: Option[String]): Iterable[Long] =
        category.filter(_.trim.nonEmpty).fold[Iterable[Long]](Nil) { _ =>
          reads += 1
          if (fail) throw new java.io.IOException("Commons down") else (2L to 8L)
        }
    }

    "be read once for the picker's clicks and the save's check, then filter the ranking" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val categories = new Categories(fail = false)
      val service = TestRoundServices.roundService(TestRoundServices.handler(distributeImages = categories))
      val round = next(contest, prev, _.copy(category = Some("Category:Picked")))
      val first = service.cutPicker(round, None).get
      val again = service.cutPicker(round, Some(3)).get
      (categories.reads === 1) and (first.m === 7) and
        (again.result.keys === first.result.keys)
    }

    "be kept within the entries' and the ids' limits, the oldest dropped first" in {
      import RoundDistributionHandler.categoryIdsToEvict
      val cached = Seq(("b", 2L, 300), ("a", 1L, 300), ("c", 3L, 100))
      (categoryIdsToEvict(cached, 100, maxEntries = 20, maxTotal = 1000) === Nil) and
        (categoryIdsToEvict(cached, 400, maxEntries = 20, maxTotal = 1000) === Seq("a")) and
        (categoryIdsToEvict(cached, 700, maxEntries = 20, maxTotal = 1000) === Seq("a", "b")) and
        (categoryIdsToEvict(cached, 1, maxEntries = 3, maxTotal = 1000) === Seq("a")) and
        (categoryIdsToEvict(cached, 2000, maxEntries = 20, maxTotal = 1000) === Seq("a", "b", "c"))
    }

    "say Commons didn't give them, not fail as a server error" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService(TestRoundServices.handler(distributeImages = new Categories(fail = true)))
      service.cutPicker(next(contest, prev, _.copy(category = Some("Category:Down"))), Some(3)) must
        throwA[RoundDistributionHandler.CategoryFilesUnavailable]
    }
  }

  "a threshold kept for a cut" should {

    "take exactly the cut's files, as the top N would" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val picker = service.cutPicker(next(contest, prev), None).get
      // every valid cut: its threshold alone takes its first N files
      forall(picker.options) { n =>
        val (top, threshold) = picker.copy(keep = CutPicker.Keep.Threshold).stored(n)
        val created = service.createNewRound(next(contest, prev, _.copy(prevMinAvgRate = threshold, topImages = top)),
          jurors.map(_.getId))
        (top must beNone) and (files(created.getId) === ranked(next(contest, prev)).take(n).toSet)
      }
    }

    "be stored with its 4 places and large sums exactly (V116)" in {
      val (contest, _) = setUp(1, 1)
      val stored = Seq(BigDecimal("9.9091"), BigDecimal("123.4567"), BigDecimal("999999.9999")).map { x =>
        val r = roundDao.create(Round(None, 0, Some("Cut"), contest.getId, rates = Round.ratesById(10), prevMinAvgRate = Some(x)))
        roundDao.findById(r.getId).get.prevMinAvgRate
      }
      stored === Seq(Some(BigDecimal("9.9091")), Some(BigDecimal("123.4567")), Some(BigDecimal("999999.9999")))
    }
  }

  "the previous round's rating view" should {
    import controllers.{GalleryView, ListOrder, Pager, RoundList}

    /** The view of all the jurors' votes on `prev` by rate (a yes/no round's by `order`), as the cut link opens it. */
    def view(prev: Round, order: Option[BinaryOrder]): RoundList =
      RoundList(prev, 0, order = order.fold[ListOrder](ListOrder.ByRate)(ListOrder.Binary(_)), view = GalleryView.ByRate)

    /** The view's cuts are the picker's: at every N, a cut in one iff in the other, named alike, and its first
      * N files the picker's first N.
      */
    def sameCuts(round: Round, prev: Round) = {
      val picker = TestRoundServices.roundService().cutPicker(round, None).get
      val list = view(prev, picker.ranking.order)
      val counts = list.rankCounts()
      val shown = list.files(Pager(1, picker.m, 100)).map(_.pageId)
      (shown.size === picker.m) and
        forall(1 to picker.m + 1)(n => (n -> list.cutAt(n, counts).isDefined) === (n -> picker.result.isValid(n))) and
        forall(picker.options)(n => (n -> list.cutAt(n, counts)) === (n -> Some(picker.cut(n)))) and
        forall(picker.options)(n => (n -> shown.take(n).toSet) === (n -> ranked(round).take(n).toSet)) and
        (TestRoundServices.roundService().cutViewable(round, picker, None) must beTrue)
    }

    /** Four jurors, unrated votes (0) and a worst mark (-1): the three rules rank them differently. */
    val ruleVotes: Seq[(Long, Int, Int)] = Seq(
      1L -> Seq(9, 9, 9, 0), 2L -> Seq(8, 8, 8, 8), 3L -> Seq(10, 8, 0, 0), 4L -> Seq(6, 6, 6, 6),
      5L -> Seq(9, 9, 0, 0), 6L -> Seq(7, -1, 7, 7), 7L -> Seq(5, 0, 0, 0), 8L -> Seq(10, 10, 10, 9)
    ).flatMap { case (page, rates) => rates.zipWithIndex.map { case (r, j) => (page, j, r) } }

    "show the picker's cuts between the same files under each average rule" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        val (contest, jurors) = setUp(8, 4)
        val prev = prevRound(contest, Round.ratesById(10), jurors, rule)(ruleVotes: _*)
        sameCuts(next(contest, prev), prev)
      }
    }

    "show them for a criteria round (a vote's rate its criteria's sum)" in {
      forall(AverageRule.all) { rule =>
        SharedTestDb.truncateAll()
        val (contest, jurors) = setUp(8, 4)
        val prev = prevRound(contest, Round.ratesById(10), jurors, rule)(
          ruleVotes.map { case (p, j, r) => (p, j, if (r > 0) r * 3 - (p % 2).toInt else r) }: _*)
        DB.autoCommit(implicit s => sql"UPDATE round SET has_criteria = 1 WHERE id = ${prev.getId}".update())
        val criteria = roundDao.findById(prev.getId).get
        (criteria.hasCriteria must beTrue) and sameCuts(next(contest, criteria), criteria)
      }
    }

    "show them for a yes/no round under each order" in {
      val binaryVotes = Seq((1L, 0, 1), (1L, 1, 1), (1L, 2, 1), (2L, 0, 1), (2L, 1, 1), (2L, 2, -1), (3L, 0, 1), (3L, 1, 1),
        (3L, 2, -1), (4L, 0, 1), (4L, 1, 1), (5L, 0, 1), (5L, 1, -1), (5L, 2, -1), (6L, 0, -1), (6L, 1, 0), (7L, 0, 0))
      forall(BinaryOrder.all) { order =>
        SharedTestDb.truncateAll()
        val (contest, jurors) = setUp(7, 3)
        val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
        sameCuts(next(contest, prev, _.copy(topBinaryOrder = Some(order))), prev)
      }
    }

    "not be offered when the files differ: a deleted file with votes there, a re-apply's disqualified file" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val all = service.cutPicker(next(contest, prev), None).get.ranking
      val clean = RoundDistributionHandler.cutViewData(prev.getId, None, all)
      val held = service.submit(next(contest, prev, _.copy(topImages = Some(4))), Nil, None, review = true).round
      val beforeDq = RoundDistributionHandler.cutViewData(prev.getId, held.id, all)
      DB.autoCommit { implicit s =>
        sql"UPDATE round_image SET status = 'dq', dq_code = 'manual', dq_at = NOW(3) WHERE round_id = ${held.getId} AND page_id = 1"
          .update()
      }
      val afterDq = RoundDistributionHandler.cutViewData(prev.getId, held.id, all)
      DB.autoCommit(implicit s => sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id = 8".update())
      val picker = service.cutPicker(next(contest, prev), None).get
      (clean must beTrue) and (beforeDq must beTrue) and (afterDq must beFalse) and
        (RoundDistributionHandler.cutViewData(prev.getId, None, picker.ranking) must beFalse) and
        (picker.ranking.listed === 7) and (all.listed === 8) and
        (service.cutViewable(next(contest, prev), picker, None) must beFalse) and
        // the picker leaves the deleted file out, the view lists it
        (picker.m === 7) and (view(prev, None).rankCounts().map(_._2).sum === 8)
    }

    "count conflicts of interest (on by default) only when they leave a file out" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val round = next(contest, prev, _.copy(excludeCoi = true))
      val none = service.cutPicker(round, None).get
      // a juror of the contest who is file 1's author: a conflict of interest, left out by the picker
      DB.autoCommit(implicit s => sql"UPDATE images SET author = 'Coiauthor' WHERE page_id = 1".update())
      User.setWikiAccount(jurors.head.getId, Some("Coiauthor"))
      val some = service.cutPicker(round, None).get
      (none.m === 8) and (service.cutViewable(round, none, None) must beTrue) and (some.ranking.listed === 8) and
        (some.m === 7) and (service.cutViewable(round, some, None) must beFalse)
    }
  }

  "without previous rounds" should {
    "have no picker" in {
      val (contest, _) = setUp(2, 1)
      TestRoundServices.roundService().cutPicker(Round(None, 0, None, contest.getId), None) must beNone
    }
  }

  "the handler's ranking" should {
    "keep only the files asked for whole (the picker none), every file's key" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val ranking = TestRoundServices.handler().topNRanking(next(contest, prev), keep = 3).get
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None).get
      (ranking.files.size === 3) and (ranking.keys.size === 8) and (ranking.scores.head === 9.0) and
        (picker.ranking.files must beEmpty) and (picker.m === 8)
    }
  }

  // the sum round's handler score, as the fill's SQL computes it
  "a row's score" should {
    "be its sum, below 0 as 0, over its round's denominator" in {
      import RoundDistributionHandler.Denominator
      import services.distribution.DistributionSource.RankRow
      val row = RankRow(1L, 2L, rateSum = 21, count = 4, rated = 3, tally = None)
      (RoundDistributionHandler.rowScore(row, Denominator.PerImageRated) === 7.0) and
        (RoundDistributionHandler.rowScore(row, Denominator.PerImage) === 5.25) and
        (RoundDistributionHandler.rowScore(row, Denominator.Const(1)) === 21.0) and
        (RoundDistributionHandler.rowScore(row.copy(rateSum = -2), Denominator.Const(1)) === 0.0) and
        (RoundDistributionHandler.rowScore(row, Denominator.Const(0)) === 0.0)
    }
  }
}
