package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{BinaryOrder, ContestJury, Image}
import org.intracer.wmua.cmd.DistributeImages
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestRoundServices

/** Saved filters must choose the same files on creation, count and redistribution. */
class RoundRedistributionFiltersSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def images(ids: Range.Inclusive): Seq[Image] = ids.map { id =>
    Image(id.toLong, s"File:Image$id.jpg", None, None,
      width = if (id % 3 == 0) 4000 else 1000,
      height = if (id % 3 == 0) 3000 else 800,
      monumentId = Some(if (id % 2 == 0) s"12-345-$id" else s"14-101-$id"),
      size = Some(if (id % 5 == 0) 3 * 1024 * 1024 else 500 * 1024),
      mediaType = Some(if (id % 7 == 0) "VIDEO" else "BITMAP"))
  }

  private def addImages(contest: ContestJury, ids: Range.Inclusive): Unit = {
    val added = images(ids)
    imageDao.batchInsert(added)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, added)
  }

  private def pageIds(roundId: Long): Set[Long] = SelectionJdbc.byRound(roundId).map(_.pageId).toSet

  "redistribution with saved round filters" should {
    "apply each image filter and their combination to newly imported files, preserving votes" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val categories = new DistributeImages(ImageJdbc) {
        override def categoryFileIds(category: Option[String]): Iterable[Long] = category match {
          case Some("Category:Included") => Seq(2L, 3L, 12L, 13L)
          case Some("Category:Excluded") => Seq(3L, 4L, 13L, 14L)
          case _ => Nil
        }
      }
      val service = TestRoundServices.roundService(new RoundDistributionHandler(
        categories, TestRoundServices.config(chunkRows = 3), TestRoundServices.emptyCommons))
      val template = Round(None, 0, Some("Filtered"), contest.getId, distribution = 1, active = true)
      val all = (1L to 20L).toSet
      val cases = Seq(
        (template, all),
        (template.copy(regions = Some("12")), Set[Long](2, 4, 6, 8, 10, 12, 14, 16, 18, 20)),
        (template.copy(monuments = Some("14-101-3,14-101-13")), Set[Long](3, 13)),
        (template.copy(minMpx = Some(5)), Set[Long](3, 6, 9, 12, 15, 18)),
        (template.copy(minImageSize = Some(1)), Set[Long](5, 10, 15, 20)),
        (template.copy(mediaType = Some("image")), all -- Set[Long](7, 14)),
        (template.copy(mediaType = Some("video")), Set[Long](7, 14)),
        (template.copy(category = Some("Category:Included")), Set[Long](2, 3, 12, 13)),
        (template.copy(excludeCategory = Some("Category:Excluded")), all -- Set[Long](3, 4, 13, 14)),
        (template.copy(category = Some("Category:Included"), excludeCategory = Some("Category:Excluded")), Set[Long](2, 12)),
        (template.copy(regions = Some("12"), minMpx = Some(5), mediaType = Some("image")), Set[Long](6, 12, 18))
      )
      val created = cases.map { case (settings, expected) =>
        val round = service.createNewRound(settings, jurors.map(_.getId))
        pageIds(round.getId) === expected.filter(_ <= 10)
        val vote = SelectionJdbc.byRound(round.getId).head
        SelectionJdbc.rate(vote.pageId, vote.juryId, round.getId, 1)
        (round, expected, SelectionJdbc.byRound(round.getId))
      }
      addImages(contest, 11 to 20)

      created.map { case (round, expected, original) =>
        val newCount = expected.count(_ > 10)
        service.newFilesCount(round.getId) === newCount.toLong
        service.distributeNewImages(round.getId) === newCount
        val actual = SelectionJdbc.byRound(round.getId)
        (pageIds(round.getId) === expected) and
          (actual.filter(_.pageId <= 10).toSet === original.toSet) and
          (service.newFilesCount(round.getId) === 0L) and
          (service.distributeNewImages(round.getId) === 0)
      }.reduce(_ and _)
    }

    "apply saved selection and rating thresholds when previous rounds gain files" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val binary = service.createNewRound(source, jurors.map(_.getId))
      val rated = service.createNewRound(source.copy(rates = Round.ratesById(5)), jurors.map(_.getId))
      def vote(round: Round): Unit = SelectionJdbc.byRound(round.getId).foreach { row =>
        val rate = if (round.isBinary) { if (row.pageId % 3 == 0) 1 else 0 }
                   else (row.pageId % 5 + 1).toInt
        SelectionJdbc.rate(row.pageId, row.juryId, round.getId, rate)
      }
      vote(binary)
      vote(rated)
      val cases = Seq(
        (source.copy(previous = Some(binary.getId.toString), prevSelectedBy = Some(2)),
          Set[Long](3, 6, 9, 12, 15, 18)),
        (source.copy(previous = Some(rated.getId.toString), prevMinAvgRate = Some(BigDecimal(4))),
          Set[Long](3, 4, 8, 9, 13, 14, 18, 19))
      )
      val created = cases.map { case (settings, expected) =>
        val round = service.createNewRound(settings, jurors.map(_.getId))
        pageIds(round.getId) === expected.filter(_ <= 10)
        (round, expected)
      }
      addImages(contest, 11 to 20)
      Seq(binary, rated).foreach { round =>
        service.distributeNewImages(round.getId)
        vote(round)
      }

      created.map { case (round, expected) =>
        service.newFilesCount(round.getId) === expected.count(_ > 10).toLong
        service.distributeNewImages(round.getId) === expected.count(_ > 10)
        (pageIds(round.getId) === expected) and (service.newFilesCount(round.getId) === 0L)
      }.reduce(_ and _)
    }

    "pick a yes/no round's top-N by the round's binary order, with and without \"selected by\"" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2, 3, 4, 5)
      addImages(contest, 1 to 3)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val binary = service.createNewRound(source, jurors.map(_.getId))
      // A (1): 3 approvals, 2 rejections; B (2): 2 approvals, 3 unrated; C (3): 1 approval
      val votes = Map(1L -> Seq(1, 1, 1, -1, -1), 2L -> Seq(1, 1, 0, 0, 0), 3L -> Seq(1, 0, 0, 0, 0))
      for ((pageId, rates) <- votes; (juror, rate) <- jurors.zip(rates))
        SelectionJdbc.rate(pageId, juror.getId, binary.getId, rate)

      val top1 = source.copy(previous = Some(binary.getId.toString), topImages = Some(1))
      def order(o: BinaryOrder) = Some(o)
      val cases = Seq(
        (top1, Set(1L)), // the default: approvals, then fewest rejections
        (top1.copy(prevSelectedBy = Some(1)), Set(1L)),
        (top1.copy(topBinaryOrder = order(BinaryOrder.Difference)), Set(2L)),
        (top1.copy(topBinaryOrder = order(BinaryOrder.Difference), prevSelectedBy = Some(1)), Set(2L)),
        (top1.copy(topBinaryOrder = order(BinaryOrder.ApprovalsOnly), topImages = Some(2)), Set(1L, 2L)),
        // "selected by" leaves out the files before the ranking
        (top1.copy(topBinaryOrder = order(BinaryOrder.Difference), prevSelectedBy = Some(3)), Set(1L)),
        // no top-N: every file, whatever the order
        (source.copy(previous = Some(binary.getId.toString), topBinaryOrder = order(BinaryOrder.Difference)),
          Set(1L, 2L, 3L))
      )
      val inMemory = new DistributeImages(ImageJdbc)
      cases.zipWithIndex.map { case ((settings, expected), i) =>
        // a new round's submission stores the default order unless another is chosen
        val submitted = settings.withExplicitTopOrder(prevBinary = true)
        val reference = inMemory.imagesByRound(roundDao.create(submitted.copy(number = 99)), Seq(binary)).map(_.pageId).toSet
        Round.setActive(binary.getId, active = true)
        val round = service.createNewRound(settings, jurors.map(_.getId))
        Round.setActive(binary.getId, active = true)
        val job = service.distributionJob(round.getId).get
        ((i, pageIds(round.getId), reference) === ((i, expected, expected))) and
          // stored with the round, recorded with its distribution
          (roundDao.findById(round.getId).get.topBinaryOrder === submitted.topBinaryOrder) and
          (submitted.topBinaryOrder.isDefined === settings.topImages.isDefined) and
          ((job.params \ "settings" \ "topBinaryOrder").asOpt[String] === submitted.topBinaryOrder.map(_.param)) and
          (service.newFilesCount(round.getId) === 0L)
      }.reduce(_ and _)
    }

    "store and record no binary order for a top-N from a rated previous round" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 3)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true, rates = Round.ratesById(5))
      val rated = service.createNewRound(source, jurors.map(_.getId))
      for ((pageId, rate) <- Seq(1L -> 2, 2L -> 5, 3L -> 3); juror <- jurors)
        SelectionJdbc.rate(pageId, juror.getId, rated.getId, rate)
      Round.setActive(rated.getId, active = true)
      val top = source.copy(previous = Some(rated.getId.toString), topImages = Some(1),
        topBinaryOrder = Some(BinaryOrder.Difference))
      val round = service.createNewRound(top, jurors.map(_.getId))
      val job = service.distributionJob(round.getId).get
      (pageIds(round.getId) === Set(2L)) and
        (roundDao.findById(round.getId).get.topBinaryOrder must beNone) and
        ((job.params \ "settings" \ "topBinaryOrder").asOpt[String] must beNone)
    }

    "pick the top-N of a round from before V72 (no order stored) by the legacy rate-sum rule" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2, 3, 4, 5)
      addImages(contest, 1 to 3)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val binary = service.createNewRound(source, jurors.map(_.getId))
      // A (1): 3 approvals, 2 rejections (sum 1); B (2): 2 approvals (sum 2); C (3): 1 approval (sum 1)
      val votes = Map(1L -> Seq(1, 1, 1, -1, -1), 2L -> Seq(1, 1, 0, 0, 0), 3L -> Seq(1, 0, 0, 0, 0))
      for ((pageId, rates) <- votes; (juror, rate) <- jurors.zip(rates))
        SelectionJdbc.rate(pageId, juror.getId, binary.getId, rate)

      // as rounds stored before V72 are: a top-N and no order, filled by "Distribute new files"
      val old = source.copy(previous = Some(binary.getId.toString), topImages = Some(1), number = 2)
      val cases = Seq(
        (old, Set(2L)), // the rate sum, where the default order picks A
        (old.copy(prevSelectedBy = Some(1)), Set(1L)), // the approvals
        (old.copy(topImages = Some(2)), Set(1L, 2L)) // ties (A, C: 1) by page id
      )
      val inMemory = new DistributeImages(ImageJdbc)
      cases.zipWithIndex.map { case ((settings, expected), i) =>
        val round = roundDao.create(settings)
        round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
        val reference = inMemory.imagesByRound(round, Seq(binary)).map(_.pageId).toSet
        service.distributeNewImages(round.getId)
        val job = service.distributionJob(round.getId).get
        ((i, pageIds(round.getId), reference) === ((i, expected, expected))) and
          (roundDao.findById(round.getId).get.topBinaryOrder must beNone) and
          ((job.params \ "settings" \ "topBinaryOrder").asOpt[String] must beSome(BinaryOrder.LegacyParam)) and
          (service.newFilesCount(round.getId) === 0L)
      }.reduce(_ and _)
    }

    "ignore \"selected by at least 0\" in a round with no previous round" in {
      // V73 sets such a 0 to NULL: it must distribute as no filter does, on every path
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("No previous"), contest.getId, distribution = 1, active = true)
      val rounds = Seq(None, Some(0)).map(by => service.createNewRound(source.copy(prevSelectedBy = by), jurors.map(_.getId)))
      def rows(round: Round) = SelectionJdbc.byRound(round.getId).map(s => (s.pageId, s.juryId)).toSet
      val created = rounds.map(rows)
      addImages(contest, 11 to 20)
      val inMemory = new DistributeImages(ImageJdbc)
      val reference = rounds.map(r => inMemory.imagesByRound(r, Nil).map(_.pageId).toSet)
      val counts = rounds.map(r => service.newFilesCount(r.getId))
      val added = rounds.map(r => service.distributeNewImages(r.getId))
      (roundDao.findById(rounds(1).getId).get.prevSelectedBy must beSome(0)) and
        (created.map(_.map(_._1)) === Seq.fill(2)((1L to 10L).toSet)) and
        (created(1) === created(0)) and
        (reference === Seq.fill(2)((11L to 20L).toSet)) and
        (counts === Seq(10L, 10L)) and
        (added === Seq(10, 10)) and
        (rows(rounds(1)) === rows(rounds(0)))
    }
  }
}
