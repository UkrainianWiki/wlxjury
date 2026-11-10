package services.distribution

import controllers.EditRound
import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{AverageRule, BinaryOrder, ContestJury, Image, RejectedMode}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestRoundServices

/** A round from several previous rounds (triage R196-1), on both paths: the in-memory one
  * (DistributeImages) and the streamed job (RoundDistributionHandler, its candidate and ranking
  * queries).
  *  1. The previous rounds share one rating, the same scale and as many criteria
  *     (`Round.sameRateType`): the form and both paths refuse a 1-5 with a 1-10 round, a
  *     comments-only with a rated one, and a criteria round with a plain one of its scale.
  *  2. Each file is evaluated in each previous round it is in, on its own: by that round's
  *     rule, jurors and votes, for every filter together. A file qualifies if it qualifies in one
  *     of them (the user's rule of 2026-11-10: "if the image is qualified by one of the rounds,
  *     it should also qualify from two"); a top-N ranks it by its best round. Parallel juries
  *     (disjoint files, different juror counts and rules) are then each scored in their own round.
  */
class PreviousRoundsMergeSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val di = new DistributeImages(ImageJdbc)

  /** `n` images in the contest's category, ids 1..n, and `jurors` jurors of the contest. */
  private def setUp(n: Int, jurors: Int): (ContestJury, Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val images = (1 to n).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, width = 1000, height = 800,
      monumentId = Some(s"12-345-$i"), size = Some(500 * 1024), mediaType = Some("BITMAP")))
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    (contest, createUsers("jury", (1 to jurors): _*).sortBy(_.getId))
  }

  /** A stopped previous round of `jurors`, with exactly the votes `(page, juror, rate)` given. */
  private def prevRound(contest: ContestJury, name: String, rates: Rates, jurors: Seq[User],
      rule: AverageRule = AverageRule.Rated, criteria: Seq[Long] = Nil)(votes: (Long, User, Int)*): Round = {
    val round = roundDao.create(Round(None, 1, Some(name), contest.getId, rates = rates, active = false,
      averageRule = rule, criterionIds = criteria))
    round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
    votes.foreach { case (page, juror, rate) => VoteJdbc.create(page, rate, juror.getId, round.getId) }
    roundDao.findById(round.getId).get
  }

  private def next(contest: ContestJury, rates: Rates, from: Round*): Round =
    Round(None, 0, Some("Next"), contest.getId, distribution = 1, rates = rates, active = true,
      previous = Some(from.map(_.getId).mkString(",")))

  private def pageIds(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

  /** The files the in-memory path selects for `template` (on an empty probe round of the same
    * settings), and those the job distributes to a round created from it.
    */
  private def both(contest: ContestJury, jurors: Seq[User], template: Round): (Set[Long], Set[Long]) = {
    val probe = roundDao.create(template.copy(number = 99, name = Some("probe")))
    val prevRounds = RoundDistributionHandler.previousRounds(probe)
    val inMemory = di.imagesByRound(probe, prevRounds).map(_.pageId).toSet
    val created = TestRoundServices.roundService(TestRoundServices.handler()).createNewRound(template, jurors.map(_.getId))
    (inMemory, pageIds(created.getId))
  }

  /** The votes of file `page` by `jurors`, in order, of `rates` (0: assigned, not rated). */
  private def votesOf(page: Long, jurors: Seq[User], rates: Int*): Seq[(Long, User, Int)] =
    jurors.zip(rates).map { case (juror, rate) => (page, juror, rate) }

  "previous rounds of different ratings (item 1)" should {

    "be refused unless they share one rating, the scale included" in {
      val (contest, jurors) = setUp(2, 2)
      val five = prevRound(contest, "1-5", Round.ratesById(5), jurors)()
      val fiveToo = prevRound(contest, "1-5 too", Round.ratesById(5), jurors)()
      val ten = prevRound(contest, "1-10", Round.ratesById(10), jurors)()
      val comments = prevRound(contest, "Comments", Round.commentsOnlyRound, jurors)()
      val binary = prevRound(contest, "Yes/no", Round.binaryRound, jurors)()
      val binaryToo = prevRound(contest, "Yes/no too", Round.binaryRound, jurors)()
      (Round.sameRateType(Seq(five, fiveToo)) must beTrue) and
        (Round.sameRateType(Seq(binary, binaryToo)) must beTrue) and
        (Round.sameRateType(Seq(five, ten)) must beFalse) and
        (Round.sameRateType(Seq(comments, five)) must beFalse) and
        (Round.sameRateType(Seq(binary, five)) must beFalse)
    }

    "be refused unless they have as many criteria" in {
      val (contest, jurors) = setUp(2, 2)
      // a criteria round's vote is the sum of its criteria: up to 40 of 4 criteria of 1-10
      val four = prevRound(contest, "4 criteria", Round.ratesById(10), jurors, criteria = Seq(1L, 2L, 3L, 4L))()
      val fourToo = prevRound(contest, "4 criteria too", Round.ratesById(10), jurors, criteria = Seq(5L, 6L, 7L, 8L))()
      val three = prevRound(contest, "3 criteria", Round.ratesById(10), jurors, criteria = Seq(1L, 2L, 3L))()
      val plain = prevRound(contest, "Plain", Round.ratesById(10), jurors)()
      val probe = roundDao.create(next(contest, Round.ratesById(10), four, plain).copy(number = 99))
      (four.hasCriteria must beTrue) and
        (Round.sameRateType(Seq(four, fourToo)) must beTrue) and
        (Round.sameRateType(Seq(four, plain)) must beFalse) and
        (Round.sameRateType(Seq(four, three)) must beFalse) and
        (RoundDistributionHandler.previousRounds(probe) must throwA[IllegalArgumentException]) and
        (di.imagesByRound(probe, Seq(four, plain)) must throwA[IllegalArgumentException](message = "same rating"))
    }

    "be refused by the round form, with its message" in {
      val (contest, jurors) = setUp(2, 2)
      val five = prevRound(contest, "1-5", Round.ratesById(5), jurors)()
      val ten = prevRound(contest, "1-10", Round.ratesById(10), jurors)()
      val fiveToo = prevRound(contest, "1-5 too", Round.ratesById(5), jurors)()
      val fiveCriteria = prevRound(contest, "1-5 criteria", Round.ratesById(5), jurors, criteria = Seq(1L, 2L))()
      def errors(prev: Round*) = EditRound.editRoundForm.bind(Map(
        "number" -> "0", "name" -> "Next", "contest" -> contest.getId.toString, "roles" -> "jury",
        "distribution" -> "0", "rates" -> "5", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
        "jurors[0]" -> jurors.head.getId.toString) ++
        prev.zipWithIndex.map { case (r, i) => s"previousRound[$i]" -> r.getId.toString }).errors.map(_.message)
      (errors(five, ten) must contain("error.previous.rounds.mixed.type")) and
        (errors(five, fiveCriteria) must contain("error.previous.rounds.mixed.type")) and
        (errors(five, fiveToo) must not(contain("error.previous.rounds.mixed.type")))
    }

    "be refused by both distribution paths" in {
      val (contest, jurors) = setUp(2, 2)
      val five = prevRound(contest, "1-5", Round.ratesById(5), jurors)((1L, jurors.head, 3))
      val ten = prevRound(contest, "1-10", Round.ratesById(10), jurors)((2L, jurors.head, 7))
      val probe = roundDao.create(next(contest, Round.ratesById(5), five, ten).copy(number = 99))
      (RoundDistributionHandler.previousRounds(probe) must throwA[IllegalArgumentException]) and
        (di.imagesByRound(probe, Seq(five, ten)) must throwA[IllegalArgumentException](message = "same rating"))
    }
  }

  "parallel juries: disjoint files, different juror counts and score rules" should {

    /** Round A: two jurors, `rated` (over the jurors who rated); files 1-3. Round B: three
      * jurors, `assigned` (over the assigned, unrated as 0); files 4-6. Each file's score in its
      * own round: 1: 8, 2: 4, 3: 6.5 | 4: 3, 5: 6, 6: 17/3 = 5.67. By round A's rule for all (the
      * behaviour before): 4: 9, 5: 6, 6: 8.5.
      */
    def juries() = {
      val (contest, jurors) = setUp(6, 5)
      val Seq(j1, j2, j3, j4, j5) = jurors
      val a = prevRound(contest, "A", Round.ratesById(10), Seq(j1, j2), AverageRule.Rated)(
        (1L, j1, 8), (1L, j2, 0), (2L, j1, 4), (2L, j2, 4), (3L, j1, 6), (3L, j2, 7))
      val b = prevRound(contest, "B", Round.ratesById(10), Seq(j3, j4, j5), AverageRule.Assigned)(
        (4L, j3, 9), (4L, j4, 0), (4L, j5, 0), (5L, j3, 6), (5L, j4, 6), (5L, j5, 6),
        (6L, j3, 9), (6L, j4, 8), (6L, j5, 0))
      (contest, jurors, a, b)
    }

    "score each file in its own round for a minimum score, on both paths" in {
      val (contest, jurors, a, b) = juries()
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b)
        .copy(prevMinAvgRate = Some(BigDecimal(6))))
      (inMemory === Set(1L, 3L, 5L)) and (streamed === inMemory)
    }

    "rank each file by its score in its own round for a top-N, on both paths" in {
      val (contest, jurors, a, b) = juries()
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b).copy(topImages = Some(3)))
      (inMemory === Set(1L, 3L, 5L)) and (streamed === inMemory)
    }
  }

  "a file in several previous rounds (item 2: qualified by one round, qualified from two)" should {

    "qualify by a minimum score in one of its rounds, on both paths" in {
      val (contest, jurors) = setUp(4, 5)
      val Seq(j1, j2, j3, j4, j5) = jurors
      // files 1-4 in both rounds: A of two jurors, `rated`; B of three, `assigned`. Scores in
      // A | B: 1: 8 | 3; 2: 4 | 7; 3: 5 | 4; 4: 6.5 | 6
      val a = prevRound(contest, "A", Round.ratesById(10), Seq(j1, j2), AverageRule.Rated)(
        votesOf(1L, Seq(j1, j2), 8, 0) ++ votesOf(2L, Seq(j1, j2), 4, 4) ++ votesOf(3L, Seq(j1, j2), 5, 5) ++
          votesOf(4L, Seq(j1, j2), 6, 7): _*)
      val b = prevRound(contest, "B", Round.ratesById(10), Seq(j3, j4, j5), AverageRule.Assigned)(
        votesOf(1L, Seq(j3, j4, j5), 9, 0, 0) ++ votesOf(2L, Seq(j3, j4, j5), 7, 7, 7) ++
          votesOf(3L, Seq(j3, j4, j5), 4, 4, 4) ++ votesOf(4L, Seq(j3, j4, j5), 6, 6, 6): _*)
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b)
        .copy(prevMinAvgRate = Some(BigDecimal(6))))
      // 1 passes in A only, 2 in B only, 4 in both; 3 in neither
      (inMemory === Set(1L, 2L, 4L)) and (streamed === inMemory)
    }

    "rank by its best round for a top-N, against files of one round, on both paths" in {
      val (contest, jurors) = setUp(3, 2)
      // 1: 9 in A, 2 in B (5.5 pooled); 2: 7, in A only; 3: 8, in B only
      val a = prevRound(contest, "A", Round.ratesById(10), jurors)(
        votesOf(1L, jurors, 9, 9) ++ votesOf(2L, jurors, 7, 7): _*)
      val b = prevRound(contest, "B", Round.ratesById(10), jurors)(
        votesOf(1L, jurors, 2, 2) ++ votesOf(3L, jurors, 8, 8): _*)
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b).copy(topImages = Some(2)))
      (inMemory === Set(1L, 3L)) and (streamed === inMemory)
    }

    "not add up its sums in two `sum` rounds, on both paths" in {
      val (contest, jurors) = setUp(2, 2)
      // 1: a sum of 10 in A and in B (20 added up); 2: 15, in A only
      val a = prevRound(contest, "A", Round.ratesById(10), jurors, AverageRule.Sum)(
        votesOf(1L, jurors, 5, 5) ++ votesOf(2L, jurors, 8, 7): _*)
      val b = prevRound(contest, "B", Round.ratesById(10), jurors, AverageRule.Sum)(votesOf(1L, jurors, 6, 4): _*)
      val (topIn, topStreamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b)
        .copy(name = Some("Top"), topImages = Some(1)))
      val (minIn, minStreamed) = both(contest, jurors, next(contest, Round.ratesById(10), a, b)
        .copy(name = Some("Min"), prevMinAvgRate = Some(BigDecimal(12))))
      (topIn === Set(2L)) and (topStreamed === topIn) and (minIn === Set(2L)) and (minStreamed === minIn)
    }

    /** Two yes/no rounds, juror 1 in both: R1 of jurors 1 and 2, files 1-7; R2 of jurors 1 and 3,
      * files 1-6. Approvals R1 | R2: 1: 1 | 1 (juror 1 twice); 2: 1 | 1 (jurors 2, 3); 3: 2 | 0.
      * Rejections R1 | R2: 4: 1 | 1 (juror 1 twice); 5: 1 | 1 (jurors 2, 3); 6: 2 | 0; 7: 2, in R1
      * only.
      */
    def binaryRounds() = {
      val (contest, jurors) = setUp(7, 3)
      val Seq(j1, j2, j3) = jurors
      val r1 = prevRound(contest, "R1", Round.binaryRound, Seq(j1, j2))(
        votesOf(1L, Seq(j1, j2), 1, 0) ++ votesOf(2L, Seq(j1, j2), 0, 1) ++ votesOf(3L, Seq(j1, j2), 1, 1) ++
          votesOf(4L, Seq(j1, j2), -1, 0) ++ votesOf(5L, Seq(j1, j2), 0, -1) ++ votesOf(6L, Seq(j1, j2), -1, -1) ++
          votesOf(7L, Seq(j1, j2), -1, -1): _*)
      val r2 = prevRound(contest, "R2", Round.binaryRound, Seq(j1, j3))(
        votesOf(1L, Seq(j1, j3), 1, 0) ++ votesOf(2L, Seq(j1, j3), 0, 1) ++ votesOf(3L, Seq(j1, j3), 0, 0) ++
          votesOf(4L, Seq(j1, j3), -1, 0) ++ votesOf(5L, Seq(j1, j3), 0, -1) ++ votesOf(6L, Seq(j1, j3), 0, 0): _*)
      (contest, jurors, r1, r2)
    }

    "count the approvals of each round on its own for \"selected by\", on both paths" in {
      val (contest, jurors, r1, r2) = binaryRounds()
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2).copy(prevSelectedBy = Some(2)))
      // 1 and 2: one approval in each round, two added up; 3: two in R1
      (inMemory === Set(3L)) and (streamed === inMemory)
    }

    "count the rejections of each round on its own for \"rejected by\", both modes, on both paths" in {
      val (contest, jurors, r1, r2) = binaryRounds()
      val (excludeIn, excludeStreamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2)
        .copy(name = Some("Exclude"), prevRejectedBy = Some(2), prevRejectedMode = RejectedMode.Exclude))
      val (onlyIn, onlyStreamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2)
        .copy(name = Some("Only"), prevRejectedBy = Some(2), prevRejectedMode = RejectedMode.Only))
      // 4 and 5: their rejections split across the rounds, none reaching 2 in one: kept; 6 kept
      // by R2, where it has none; 7: two rejections in its only round
      (excludeIn === Set(1L, 2L, 3L, 4L, 5L, 6L)) and (excludeStreamed === excludeIn) and
        (onlyIn === Set(6L, 7L)) and (onlyStreamed === onlyIn)
    }

    "rank by its best round's standing for an ordered yes/no top-N, on both paths" in {
      val (contest, jurors, r1, r2) = binaryRounds()
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2)
        .copy(topImages = Some(1), topBinaryOrder = Some(BinaryOrder.Default)))
      // 1, 2 and 3 have two approvals added up and no rejection (1 first by page id); only 3 has
      // two in one round
      (inMemory === Set(3L)) and (streamed === inMemory)
    }

    "count a rated round's worst marks in each round on its own, on both paths" in {
      val (contest, jurors) = setUp(4, 3)
      val Seq(j1, j2, j3) = jurors
      val a = prevRound(contest, "A", Round.ratesById(5), Seq(j1, j2))(
        votesOf(1L, Seq(j1, j2), -1, 3) ++ votesOf(2L, Seq(j1, j2), -1, -1) ++ votesOf(3L, Seq(j1, j2), 4, 4) ++
          votesOf(4L, Seq(j1, j2), -1, -1): _*)
      val b = prevRound(contest, "B", Round.ratesById(5), Seq(j1, j3))(
        votesOf(1L, Seq(j1, j3), -1, 2) ++ votesOf(2L, Seq(j1, j3), 5, 4) ++ votesOf(3L, Seq(j1, j3), 3, 3): _*)
      Seq(a, b).foreach(r => sql"UPDATE round SET worst_mark = TRUE WHERE id = ${r.getId}".update()(AutoSession))
      val prev = Seq(a, b).map(r => roundDao.findById(r.getId).get)
      val (excludeIn, excludeStreamed) = both(contest, jurors, next(contest, Round.ratesById(5), prev: _*)
        .copy(name = Some("Exclude"), prevRejectedBy = Some(2)))
      val (onlyIn, onlyStreamed) = both(contest, jurors, next(contest, Round.ratesById(5), prev: _*)
        .copy(name = Some("Only"), prevRejectedBy = Some(2), prevRejectedMode = RejectedMode.Only))
      // 1: one ✗ in each round; 2: two in A, none in B; 4: two in its only round
      (excludeIn === Set(1L, 2L, 3L)) and (excludeStreamed === excludeIn) and
        (onlyIn === Set(2L, 4L)) and (onlyStreamed === onlyIn)
    }

    "score a legacy yes/no top-N (no order stored) by each round's own jurors, on both paths" in {
      val (contest, jurors) = setUp(2, 5)
      val Seq(j1, j2, _, _, _) = jurors
      // max(approvals - rejections, 0) over the round's jurors: 1: 1 / 2 in R1; 2: 2 / 5 in R2
      // (1 over R1's two jurors)
      val r1 = prevRound(contest, "R1", Round.binaryRound, Seq(j1, j2))(votesOf(1L, Seq(j1, j2), 1, 0): _*)
      val r2 = prevRound(contest, "R2", Round.binaryRound, jurors)(votesOf(2L, jurors, 1, 1, 0, 0, 0): _*)
      // a new round gets an order (Round.withExplicitTopOrder): a round of before V72 has none,
      // and its "Distribute new files" ranks by the legacy rule
      val legacy = roundDao.create(next(contest, Round.binaryRound, r1, r2).copy(topImages = Some(1)))
      legacy.addUsers(jurors.map(u => RoundUser(legacy.getId, u.getId, "jury", active = true)))
      val inMemory = di.imagesByRound(legacy, RoundDistributionHandler.previousRounds(legacy)).map(_.pageId).toSet
      TestRoundServices.roundService(TestRoundServices.handler()).distributeNewImages(legacy.getId)
      (legacy.topBinaryOrder must beNone) and (inMemory === Set(1L)) and (pageIds(legacy.getId) === inMemory)
    }
  }
}
