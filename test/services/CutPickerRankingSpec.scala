package services

import db.scalikejdbc._
import org.intracer.wmua.{AverageRule, BinaryOrder, ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.distribution.RoundDistributionHandler

/** The cut picker's ranking (regional winners plan §10.4, §10.9 "the preview's count equals the
  * fill's"): the files ranked by the distribution's own rank key, rows and per-image filters, so that a
  * top N of the fill takes exactly the picker's first N; ties by the rank key, the score range of a
  * rated round, yes/no levels by the round's order, filters (size, conflicts of interest) applied
  * before ranking.
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

  /** Rated 1–10 by three jurors: files 1..8 with averages 9, 8, 8, 8, 7, 6, 6, 5 (ties 2–4 and 6–7). */
  private val ratedVotes: Seq[(Long, Int, Int)] =
    Seq(1L -> 9, 2L -> 8, 3L -> 8, 4L -> 8, 5L -> 7, 6L -> 6, 7L -> 6, 8L -> 5).flatMap { case (p, r) =>
      (0 to 2).map(j => (p, j, r))
    }

  "a rated source's ranking" should {

    "rank by the fill's key, ties by it, with the round's score range" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None, None, None).get
      (picker.m === 8) and (picker.gallery.map(_.file.pageId).take(2) === Seq(1L, 2L)) and
        (picker.result.validUpTo(100) === Seq(1, 4, 5, 7, 8)) and
        (picker.result.refusal(3).map(_.key) must beSome(TopNCandidates.InTieKey)) and
        (picker.levels must beFalse) and (picker.mixedScales must beFalse) and
        (CutPicker.range(prev, 3) === 10.0)
    }

    "be what the fill takes: a valid N takes the picker's first N files" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val picker = service.cutPicker(next(contest, prev), None, None, None).get
      val created = service.createNewRound(next(contest, prev, _.copy(topImages = Some(4))), jurors.map(_.getId))
      files(created.getId) === picker.gallery.take(4).map(_.file.pageId).toSet
    }

    "apply the round's per-image filters before ranking (a minimum size)" in {
      val (contest, jurors) = setUp(8, 3, small = Set(1L))
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val service = TestRoundServices.roundService()
      val sized = service.cutPicker(next(contest, prev, _.copy(minMpx = Some(2))), None, None, None).get
      (sized.m === 7) and (sized.gallery.map(_.file.pageId) must not(contain(1L))) and
        (sized.result.validUpTo(100) === Seq(3, 4, 6, 7))
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
        None, None, None).get
      val approvals = service.cutPicker(next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.ApprovalsOnly))),
        None, None, None).get
      // approvals then rejections: 1 (3, 0), 4 (2, 0), 2–3 (2, 1), 5 (1, 2)
      (default.levels must beTrue) and (default.gallery.map(_.file.pageId) === Seq(1L, 4L, 2L, 3L, 5L)) and
        (default.result.validUpTo(10) === Seq(1, 2, 4, 5)) and
        // approvals only: 1 (3), then 2, 3, 4 (2) tie, 5 (1)
        (approvals.result.validUpTo(10) === Seq(1, 4, 5))
    }

    "be what the fill takes for a valid N" in {
      val (contest, jurors) = setUp(5, 3)
      val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
      val service = TestRoundServices.roundService()
      val round = next(contest, prev, _.copy(topBinaryOrder = Some(BinaryOrder.Default)))
      val picker = service.cutPicker(round, None, None, None).get
      val created = service.createNewRound(round.copy(topImages = Some(2)), jurors.map(_.getId))
      files(created.getId) === picker.gallery.take(2).map(_.file.pageId).toSet
    }

    "use the default order when none is given, as a save stores it" in {
      val (contest, jurors) = setUp(5, 3)
      val prev = prevRound(contest, Round.binaryRound, jurors)(binaryVotes: _*)
      val picker = TestRoundServices.roundService().cutPicker(next(contest, prev), None, None, None).get
      picker.ranking.order must beSome(BinaryOrder.Default: BinaryOrder)
    }
  }

  "without previous rounds" should {
    "have no picker" in {
      val (contest, _) = setUp(2, 1)
      TestRoundServices.roundService().cutPicker(Round(None, 0, None, contest.getId), None, None, None) must beNone
    }
  }

  "the handler's ranking" should {
    "keep only the gallery's files whole, every file's key" in {
      val (contest, jurors) = setUp(8, 3)
      val prev = prevRound(contest, Round.ratesById(10), jurors)(ratedVotes: _*)
      val ranking = TestRoundServices.handler().topNRanking(next(contest, prev), keep = 3).get
      (ranking.files.size === 3) and (ranking.keys.size === 8) and (ranking.scores.head === 9.0)
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
