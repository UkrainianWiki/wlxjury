package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** [[RoundList.jurorCounts]]: the one query over a juror's files gives what the three queries
  * it replaces gave on the same data — the list's total ([[RoundList.count]]), the region nav's
  * counts and regions ([[db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery.regionCounts]],
  * `byRegionStat`) and the tabs ([[ImageJdbc.rateDistribution]]) — for every filter, with and
  * without a region, in a binary and a rated round, for each juror.
  */
class JurorCountsSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val filters: Seq[VoteFilter] =
    Seq(VoteFilter.All, VoteFilter.Unrated, VoteFilter.Rated) ++ (-1 to 5).filter(_ != 0).map(VoteFilter.Rate)

  /** Files 1-12 in regions 07, 14, 99 (a three-character id 991), none; two jurors with
    * different votes on them; a third juror with no files.
    */
  private def setUp(rates: Rates): (Round, Seq[User]) = {
    val contest = contestDao.create(None, "WLM", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, contestId = contest.getId, rates = rates, active = true))
    val jurors = (1 to 3).map(n => userDao.create(User(s"juror$n", s"juror$n@example.org", None, Set("jury"),
      contestId = contest.id)))
    val monuments = Map(1 -> Some("07-101-0001"), 2 -> Some("07-101-0002"), 3 -> Some("07-102-0003"),
      4 -> Some("14-101-0004"), 5 -> Some("14-101-0005"), 6 -> Some("991-101-0006"), 7 -> Some("99-101-0007"),
      8 -> None, 9 -> None, 10 -> Some("07-103-0010"), 11 -> Some("14-102-0011"), 12 -> Some(""))
    imageDao.batchInsert(monuments.toSeq.sortBy(_._1).map { case (id, m) =>
      Image(id.toLong, s"File:Image$id.jpg", None, None, 640, 480, m)
    })
    val possible = if (rates == Round.binaryRound) Seq(-1, 0, 1) else 0 to rates.maxRate
    def vote(juror: User, page: Int, i: Int) =
      Selection(page.toLong, juror.getId, round.getId, possible(i % possible.size), monumentId = monuments(page))
    selectionDao.batchInsert((1 to 12).map(p => vote(jurors(0), p, p)) ++ (1 to 9).map(p => vote(jurors(1), p, p * 7 + 1)))
    (round, jurors)
  }

  private def sameAsBefore(round: Round, juror: User) = {
    val list = RoundList(round, juror.getId)
    val counts = list.jurorCounts().get
    val all = list.query.copy(regions = Set.empty)
    val regions = all.byRegionStat().keySet
    val totals = for (filter <- filters; region <- Seq(None, Some("07"), Some("14"), Some("99"), Some("991"), Some("55")))
      yield (filter, region, list.withFilter(filter).copy(region = region).count(), counts.total(filter, region))
    val regionCounts = filters.map(f => (f, list.withFilter(f).query.copy(regions = Set.empty).regionCounts(),
      counts.regions(f)))
    (totals.map(t => (t._1, t._2, t._4)) === totals.map(t => (t._1, t._2, t._3))) and
      (regionCounts.map(r => r._1 -> r._3) === regionCounts.map(r => r._1 -> r._2)) and
      (counts.regions(VoteFilter.All).keySet === regions) and
      (counts.rates === ImageJdbc.rateDistribution(juror.getId, round.getId))
  }

  "a juror's counts" should {

    "be the list's totals, region counts and tabs in a binary round" in {
      val (round, jurors) = setUp(Round.binaryRound)
      jurors.map(sameAsBefore(round, _)).reduce(_ and _)
    }

    "be the list's totals, region counts and tabs in a rated round" in {
      val (round, jurors) = setUp(Round.rateRounds.find(_.maxRate == 5).get)
      jurors.map(sameAsBefore(round, _)).reduce(_ and _)
    }

    "be none for all the jurors' votes together or sub-regions" in {
      val (round, jurors) = setUp(Round.binaryRound)
      RoundList(round, 0).jurorCounts() must beNone
      RoundList(round.copy(specialNomination = Some("Віа Регіа")), jurors.head.getId).jurorCounts() must beNone
    }
  }
}
