package services.distribution

import controllers.EditRound
import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{AverageRule, ContestJury, Image, RejectedMode}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestRoundServices

/** A round from several previous rounds (triage R196-1), on both paths: the in-memory one
  * (DistributeImages) and the streamed job (RoundDistributionHandler, its candidate queries).
  *  1. The previous rounds share one rating, the same scale (`Round.sameRateType`): the form and
  *     both paths refuse a 1-5 with a 1-10 round, and a comments-only with a rated one.
  *  2. Parallel juries (disjoint files, different juror counts and score rules): each file is
  *     scored in its own previous round, by that round's rule, for a minimum score and a top-N.
  *  3. "Selected by at least N" and "rejected by at least N" count distinct jurors over the
  *     previous rounds: a juror of two of them once.
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
      rule: AverageRule = AverageRule.Rated)(votes: (Long, User, Int)*): Round = {
    val round = roundDao.create(Round(None, 1, Some(name), contest.getId, rates = rates, active = false,
      averageRule = rule))
    round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
    votes.foreach { case (page, juror, rate) => VoteJdbc.create(page, rate, juror.getId, round.getId) }
    round
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

    "be refused by the round form, with its message" in {
      val (contest, jurors) = setUp(2, 2)
      val five = prevRound(contest, "1-5", Round.ratesById(5), jurors)()
      val ten = prevRound(contest, "1-10", Round.ratesById(10), jurors)()
      val fiveToo = prevRound(contest, "1-5 too", Round.ratesById(5), jurors)()
      def errors(prev: Round*) = EditRound.editRoundForm.bind(Map(
        "number" -> "0", "name" -> "Next", "contest" -> contest.getId.toString, "roles" -> "jury",
        "distribution" -> "0", "rates" -> "5", "minMpx" -> "", "minSize" -> "", "mediaType" -> "all",
        "jurors[0]" -> jurors.head.getId.toString) ++
        prev.zipWithIndex.map { case (r, i) => s"previousRound[$i]" -> r.getId.toString }).errors.map(_.message)
      (errors(five, ten) must contain("error.previous.rounds.mixed.type")) and
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

  "parallel juries (item 2): disjoint files, different juror counts and score rules" should {

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

  "selected and rejected by at least N (item 3)" should {

    /** Two yes/no rounds of files 1-6, juror 1 in both: R1 of jurors 1 and 2, R2 of jurors 1
      * and 3. Approved by: 1 juror 1 twice; 2 jurors 2 and 3; 3 jurors 1 and 3. Rejected by: 4
      * juror 1 twice; 5 jurors 2 and 3. 6: no vote.
      */
    def rounds() = {
      val (contest, jurors) = setUp(6, 3)
      val Seq(j1, j2, j3) = jurors
      def votes(other: User, own: Map[Long, (Int, Int)]): Seq[(Long, User, Int)] =
        (1L to 6L).flatMap { p => val (r1, r2) = own.getOrElse(p, (0, 0)); Seq((p, j1, r1), (p, other, r2)) }
      val r1 = prevRound(contest, "R1", Round.binaryRound, Seq(j1, j2))(
        votes(j2, Map(1L -> (1, 0), 2L -> (0, 1), 3L -> (1, 0), 4L -> (-1, 0), 5L -> (0, -1))): _*)
      val r2 = prevRound(contest, "R2", Round.binaryRound, Seq(j1, j3))(
        votes(j3, Map(1L -> (1, 0), 2L -> (0, 1), 3L -> (0, 1), 4L -> (-1, 0), 5L -> (0, -1))): _*)
      (contest, jurors, r1, r2)
    }

    "count distinct approving jurors across the previous rounds, on both paths" in {
      val (contest, jurors, r1, r2) = rounds()
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2).copy(prevSelectedBy = Some(2)))
      // file 1: juror 1 alone, in both rounds: one juror, not two
      (inMemory === Set(2L, 3L)) and (streamed === inMemory)
    }

    "count distinct rejecting jurors across the previous rounds, both modes, on both paths" in {
      val (contest, jurors, r1, r2) = rounds()
      val (excludeIn, excludeStreamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2)
        .copy(name = Some("Exclude"), prevRejectedBy = Some(2), prevRejectedMode = RejectedMode.Exclude))
      val (onlyIn, onlyStreamed) = both(contest, jurors, next(contest, Round.binaryRound, r1, r2)
        .copy(name = Some("Only"), prevRejectedBy = Some(2), prevRejectedMode = RejectedMode.Only))
      // file 4: juror 1 alone, in both rounds: rejected by one juror
      (excludeIn === Set(1L, 2L, 3L, 4L, 6L)) and (excludeStreamed === excludeIn) and
        (onlyIn === Set(5L)) and (onlyStreamed === onlyIn)
    }

    "count a rated round's worst marks by distinct jurors too" in {
      val (contest, jurors) = setUp(3, 3)
      val Seq(j1, j2, j3) = jurors
      val a = prevRound(contest, "A", Round.ratesById(5), Seq(j1, j2))((1L, j1, -1), (1L, j2, 3), (2L, j1, -1), (2L, j2, -1),
        (3L, j1, 4), (3L, j2, 4))
      sql"UPDATE round SET worst_mark = TRUE WHERE id = ${a.getId}".update()(AutoSession)
      val b = prevRound(contest, "B", Round.ratesById(5), Seq(j1, j3))((1L, j1, -1), (1L, j3, 2), (2L, j1, 5), (2L, j3, 4),
        (3L, j1, 3), (3L, j3, 3))
      sql"UPDATE round SET worst_mark = TRUE WHERE id = ${b.getId}".update()(AutoSession)
      // file 1: juror 1's worst mark in both rounds, one juror; file 2: jurors 1 and 2
      val (inMemory, streamed) = both(contest, jurors, next(contest, Round.ratesById(5), roundDao.findById(a.getId).get,
        roundDao.findById(b.getId).get).copy(prevRejectedBy = Some(2)))
      (inMemory === Set(1L, 3L)) and (streamed === inMemory)
    }
  }
}
