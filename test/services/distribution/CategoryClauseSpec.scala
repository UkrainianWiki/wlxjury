package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestRoundServices

/** Persisted category operations across creation, count, and subsequent distribution. */
class CategoryClauseSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential
  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val categoryA = Some("Category:A")
  private val categoryB = Some("Category:B")
  private val empty = Some("Category:Empty")
  private val all = (1L to 8L).toSet
  private val a = Set[Long](2, 4, 6, 8)
  private val b = Set[Long](3, 4, 7, 8)
  private val cases = Seq[(String, Option[String], Option[String], Option[String], Set[Long])](
    ("include A", categoryA, None, Some("include"), a),
    ("legacy exclude A", categoryA, None, Some("-1"), all -- a),
    ("legacy zero exclusion", categoryA, None, Some("0"), all -- a),
    ("intersection", categoryA, categoryB, Some("and"), Set[Long](4, 8)),
    ("union", categoryA, categoryB, Some("or"), Set[Long](2, 3, 4, 6, 7, 8)),
    ("minus", categoryA, categoryB, Some("minus"), Set[Long](2, 6)),
    ("implicit inclusion", categoryA, None, None, a),
    ("implicit exclusion", None, categoryB, None, all -- b),
    ("implicit minus", categoryA, categoryB, None, Set[Long](2, 6)),
    ("no category filter", None, None, None, all),
    ("empty inclusion", empty, None, Some("include"), Set.empty),
    ("empty exclusion", empty, None, Some("exclude"), all),
    ("empty intersection", categoryA, empty, Some("and"), Set.empty),
    ("union with an empty category", empty, categoryB, Some("or"), b),
    ("minus an empty category", categoryA, empty, Some("minus"), a),
    ("identical-category intersection", categoryA, categoryA, Some("and"), a),
    ("identical-category union", categoryA, categoryA, Some("or"), a),
    ("identical-category minus", categoryA, categoryA, Some("minus"), Set.empty)
  )

  private def add(contest: ContestJury, ids: Range.Inclusive): Unit = {
    val images = ids.map(id => Image(id.toLong, s"File:Category$id.jpg", None, None, 2000, 1000,
      Some(if (id % 2 == 0) "12-345-1" else "14-345-1")))
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
  }

  private def distributor = new DistributeImages(ImageJdbc) {
    override def categoryFileIds(category: Option[String]): Iterable[Long] = category match {
      case Some("Category:A") => a + 999L
      case Some("Category:B") => b + 999L
      case _ => Nil
    }
  }

  "category clauses" should {
    cases.foreach { case (name, first, second, clause, expected) =>
      s"preserve $name through reload, creation, count and redistribution" in {
        implicit val contest: ContestJury = createContests(1).head
        val jurors = createUsers("jury", 1, 2)
        add(contest, 1 to 4)
        if (name == "union") add(createContests(2).head, 999 to 999)
        val di = distributor
        val service = TestRoundServices.roundService(new RoundDistributionHandler(
          di, TestRoundServices.config(3), TestRoundServices.emptyCommons))
        val settings = Round(None, 0, Some(name), contest.getId, category = first,
          excludeCategory = second, categoryClause = clause, active = true)
        val reference = roundDao.create(settings.copy(number = 1))
        roundDao.findById(reference.getId).flatMap(_.categoryClause) === clause
        di.imagesByRound(reference).map(_.pageId).toSet === expected.filter(_ <= 4)
        val created = service.createNewRound(settings, jurors.map(_.getId))
        VoteJdbc.byRound(created.getId).map(_.pageId).toSet === expected.filter(_ <= 4)
        VoteJdbc.byRound(created.getId).headOption.foreach { vote =>
          VoteJdbc.rate(vote.pageId, vote.juryId, created.getId, 1)
        }
        val original = VoteJdbc.byRound(created.getId).toSet
        add(contest, 5 to 8)
        service.newFilesCount(created.getId) === expected.count(_ > 4).toLong
        service.distributeNewImages(created.getId) === expected.count(_ > 4)
        val after = VoteJdbc.byRound(created.getId)
        after.map(_.pageId).toSet === expected
        after.filter(_.pageId <= 4).toSet === original
        service.newFilesCount(created.getId) === 0L
        service.distributeNewImages(created.getId) === 0
      }
    }

    "apply other filters after a category union" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      add(contest, 1 to 8)
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        distributor, TestRoundServices.config(3), TestRoundServices.emptyCommons))
      val created = service.createNewRound(Round(None, 0, Some("Union and region"), contest.getId,
        category = categoryA, excludeCategory = categoryB, categoryClause = Some("or"), regions = Some("14")),
        jurors.map(_.getId))
      VoteJdbc.byRound(created.getId).map(_.pageId).toSet === Set[Long](3, 7)
    }

    "honor legacy exclusion when topping up an already correctly populated round" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      add(contest, 1 to 4)
      val round = roundDao.create(Round(None, 1, Some("Legacy"), contest.getId,
        category = categoryA, categoryClause = Some("-1"), active = true))
      round.addUsers(jurors.map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
      for (pageId <- Seq(1L, 3L); juror <- jurors) VoteJdbc.create(pageId, 1, juror.getId, round.getId)
      add(contest, 5 to 8)
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        distributor, TestRoundServices.config(3), TestRoundServices.emptyCommons))
      service.newFilesCount(round.getId) === 2L
      service.distributeNewImages(round.getId) === 2
      VoteJdbc.byRound(round.getId).map(_.pageId).toSet === Set[Long](1, 3, 5, 7)
    }

    "apply a category union before top-N ranking" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      add(contest, 1 to 4)
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        distributor, TestRoundServices.config(3), TestRoundServices.emptyCommons))
      val previous = service.createNewRound(Round(None, 0, Some("Rated"), contest.getId,
        rates = Round.ratesById(5)), jurors.map(_.getId))
      VoteJdbc.byRound(previous.getId).foreach { row =>
        VoteJdbc.rate(row.pageId, row.juryId, previous.getId, 6 - row.pageId.toInt)
      }
      val created = service.createNewRound(Round(None, 0, Some("Union top"), contest.getId,
        previous = Some(previous.getId.toString), topImages = Some(2),
        category = categoryA, excludeCategory = categoryB, categoryClause = Some("or")), jurors.map(_.getId))
      VoteJdbc.byRound(created.getId).map(_.pageId).toSet === Set[Long](2, 3)
    }

    "reject invalid script settings before creating any round" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        distributor, TestRoundServices.config(3), TestRoundServices.emptyCommons))
      val invalid = Round(None, 0, Some("Invalid"), contest.getId, category = categoryA)
      service.createNewRound(invalid.copy(categoryClause = Some("unexpected")), jurors.map(_.getId)) must
        throwA[IllegalArgumentException]
      service.createNewRound(invalid.copy(categoryClause = Some("and")), jurors.map(_.getId)) must
        throwA[IllegalArgumentException]
      roundDao.findByContest(contest.getId) must beEmpty
    }

    "store canonical named operations for new submissions and their job snapshots" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      add(contest, 1 to 4)
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        distributor, TestRoundServices.config(3), TestRoundServices.emptyCommons))
      val cases = Seq(
        (categoryA, None, Some("-1"), Some("exclude"), categoryA, None),
        (None, categoryB, None, Some("exclude"), categoryB, None),
        (categoryA, categoryB, None, Some("minus"), categoryA, categoryB))
      cases.map { case (first, second, input, operation, storedFirst, storedSecond) =>
        val created = service.createNewRound(Round(None, 0, Some("Canonical"), contest.getId,
          category = first, excludeCategory = second, categoryClause = input), jurors.map(_.getId))
        val saved = roundDao.findById(created.getId).get
        (saved.categoryClause === operation) and (saved.category === storedFirst) and
          (saved.excludeCategory === storedSecond) and
          ((service.distributionJob(saved.getId).get.params \ "settings" \ "categoryClause").asOpt[String] === operation)
      }.reduce(_ and _)
    }
  }
}
