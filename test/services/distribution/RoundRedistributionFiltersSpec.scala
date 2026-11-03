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

  private def pageIds(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

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
        val vote = VoteJdbc.byRound(round.getId).head
        VoteJdbc.rate(vote.pageId, vote.juryId, round.getId, 1)
        (round, expected, VoteJdbc.byRound(round.getId))
      }
      addImages(contest, 11 to 20)

      created.map { case (round, expected, original) =>
        val newCount = expected.count(_ > 10)
        service.newFilesCount(round.getId) === newCount.toLong
        service.distributeNewImages(round.getId) === newCount
        val actual = VoteJdbc.byRound(round.getId)
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
      def vote(round: Round): Unit = VoteJdbc.byRound(round.getId).foreach { row =>
        val rate = if (round.isBinary) { if (row.pageId % 3 == 0) 1 else 0 }
                   else (row.pageId % 5 + 1).toInt
        VoteJdbc.rate(row.pageId, row.juryId, round.getId, rate)
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
        VoteJdbc.rate(pageId, juror.getId, binary.getId, rate)

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
        VoteJdbc.rate(pageId, juror.getId, rated.getId, rate)
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
        VoteJdbc.rate(pageId, juror.getId, binary.getId, rate)

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

    "read \"selected by at least 0\" as no filter: all files of a yes/no previous round" in {
      // the old form's way of saying no filter (V73 sets it to NULL), never "unrated votes only"
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val binary = service.createNewRound(source, jurors.map(_.getId))
      // approved (3, 6, 9), rejected (1, 4, 7, 10) and unrated (2, 5, 8) files
      def vote(): Unit = VoteJdbc.byRound(binary.getId).foreach { row =>
        val rate = (row.pageId % 3).toInt match { case 0 => 1; case 1 => -1; case _ => 0 }
        VoteJdbc.rate(row.pageId, row.juryId, binary.getId, rate)
      }
      vote()
      val next = source.copy(previous = Some(binary.getId.toString), distribution = 1)
      val rounds = Seq(None, Some(0), Some(1)).map(by =>
        service.createNewRound(next.copy(prevSelectedBy = by), jurors.map(_.getId)))
      val created = rounds.map(r => pageIds(r.getId))
      addImages(contest, 11 to 20)
      service.distributeNewImages(binary.getId)
      vote()
      val inMemory = new DistributeImages(ImageJdbc)
      val reference = rounds.map(r => inMemory.imagesByRound(r, Seq(binary)).map(_.pageId).toSet)
      val counts = rounds.map(r => service.newFilesCount(r.getId))
      val added = rounds.map(r => service.distributeNewImages(r.getId))
      (created === Seq((1L to 10L).toSet, (1L to 10L).toSet, Set(3L, 6L, 9L))) and
        (reference === Seq((11L to 20L).toSet, (11L to 20L).toSet, Set(12L, 15L, 18L))) and
        (counts === Seq(10L, 10L, 3L)) and
        (added === Seq(10, 10, 3)) and
        (rounds.map(r => pageIds(r.getId)) ===
          Seq((1L to 20L).toSet, (1L to 20L).toSet, Set(3L, 6L, 9L, 12L, 15L, 18L)))
    }

    "exclude the files rejected by at least N jurors, with \"selected by\" and a top-N, on every path" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2, 3, 4, 5)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val binary = service.createNewRound(source, jurors.map(_.getId))
      // the five jurors' votes by page id modulo 10 (file 10 + n votes as file n)
      val votes = Map(1 -> Seq(1, 1, 1, 0, 0), 2 -> Seq(1, -1, 0, 0, 0), 3 -> Seq(-1, -1, 0, 0, 0),
        4 -> Seq(0, 0, 0, 0, 0), 5 -> Seq(1, 1, -1, -1, 0), 6 -> Seq(1, 1, 1, 1, -1), 7 -> Seq(-1, 0, 0, 0, 0),
        8 -> Seq(1, 0, 0, 0, 0), 9 -> Seq(-1, -1, -1, 0, 0), 0 -> Seq(1, 1, 0, 0, 0))
      def vote(): Unit =
        for (row <- VoteJdbc.byRound(binary.getId); (juror, rate) <- jurors.zip(votes((row.pageId % 10).toInt))
             if juror.getId == row.juryId)
          VoteJdbc.rate(row.pageId, row.juryId, binary.getId, rate)
      vote()

      val next = source.copy(previous = Some(binary.getId.toString), distribution = 1, number = 2)
      val top = next.copy(topBinaryOrder = Some(BinaryOrder.Default))
      val cases = Seq(
        (next.copy(prevRejectedBy = Some(1)), Set[Long](1, 4, 8, 10)), // unrated and approved files stay
        (next.copy(prevRejectedBy = Some(2)), Set[Long](1, 2, 4, 6, 7, 8, 10)), // one rejection stays, two don't
        (next.copy(prevRejectedBy = Some(1), prevSelectedBy = Some(1)), Set[Long](1, 8, 10)),
        (next.copy(prevRejectedBy = Some(2), prevSelectedBy = Some(2)), Set[Long](1, 6, 10)),
        (top.copy(topImages = Some(2)), Set[Long](1, 6)),
        (top.copy(topImages = Some(2), prevRejectedBy = Some(1)), Set[Long](1, 10)), // ranked among those left
        (top.copy(topImages = Some(2), prevRejectedBy = Some(1), prevSelectedBy = Some(2)), Set[Long](1, 10))
      )
      val inMemory = new DistributeImages(ImageJdbc)
      val created = cases.zipWithIndex.map { case ((settings, expected), i) =>
        val round = roundDao.create(settings)
        round.addUsers(jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
        val reference = inMemory.imagesByRound(round, Seq(binary)).map(_.pageId).toSet
        val count = service.newFilesCount(round.getId)
        service.distributeNewImages(round.getId)
        (round, (i, pageIds(round.getId), reference, count) === ((i, expected, expected, expected.size.toLong)))
      }
      val job = service.distributionJob(created.head._1.getId).get

      addImages(contest, 11 to 20)
      service.distributeNewImages(binary.getId)
      vote()
      val newFiles = cases.zip(created).filter(_._1._1.topImages.isEmpty).map { case ((_, expected), (round, _)) =>
        val added = expected.map(_ + 10)
        val reference = inMemory.imagesByRound(round, Seq(binary)).map(_.pageId).toSet
        val count = service.newFilesCount(round.getId)
        val distributed = service.distributeNewImages(round.getId)
        (round.getId, reference, count, distributed, pageIds(round.getId)) ===
          ((round.getId, added, added.size.toLong, added.size, expected ++ added))
      }
      created.map(_._2).reduce(_ and _) and newFiles.reduce(_ and _) and
        ((job.params \ "settings" \ "prevRejectedBy").asOpt[Int] must beSome(1))
    }

    "ignore rejected-by from a rated previous round and with no previous round, on every path" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val rated = service.createNewRound(source.copy(rates = Round.ratesById(5)), jurors.map(_.getId))
      // rows with a negative rate, which rejected-by would count in a yes/no round
      def vote(): Unit = VoteJdbc.byRound(rated.getId).foreach { row =>
        VoteJdbc.rate(row.pageId, row.juryId, rated.getId, if (row.pageId % 2 == 0) -1 else 3)
      }
      vote()
      val fromRated = source.copy(previous = Some(rated.getId.toString), distribution = 1)
      val noPrevious = source.copy(distribution = 1)
      val pairs = Seq(fromRated, noPrevious).map(settings =>
        Seq(None, Some(1)).map(by => service.createNewRound(settings.copy(prevRejectedBy = by), jurors.map(_.getId))))
      val created = pairs.map(_.map(r => pageIds(r.getId)))
      val settings = pairs.map(_.map(r =>
        (service.distributionJob(r.getId).get.params \ "settings" \ "prevRejectedBy").asOpt[Int]))
      addImages(contest, 11 to 20)
      service.distributeNewImages(rated.getId)
      vote()
      val inMemory = new DistributeImages(ImageJdbc)
      val reference = pairs.map(_.map(r => inMemory.imagesByRound(r, RoundDistributionHandler.previousRounds(r))
        .map(_.pageId).toSet))
      val counts = pairs.map(_.map(r => service.newFilesCount(r.getId)))
      val added = pairs.map(_.map(r => service.distributeNewImages(r.getId)))
      (created === Seq.fill(2)(Seq.fill(2)((1L to 10L).toSet))) and
        (settings === Seq.fill(2)(Seq(None, None))) and
        (reference === Seq.fill(2)(Seq.fill(2)((11L to 20L).toSet))) and
        (counts === Seq.fill(2)(Seq(10L, 10L))) and
        (added === Seq.fill(2)(Seq(10, 10))) and
        (pairs.map(_.map(r => pageIds(r.getId))) === Seq.fill(2)(Seq.fill(2)((1L to 20L).toSet)))
    }

    "sum the rejections of two yes/no previous rounds, on every path" in {
      implicit val contest: ContestJury = createContests(1).head
      val jurors = createUsers("jury", 1, 2)
      addImages(contest, 1 to 10)
      val service = TestRoundServices.roundService(TestRoundServices.handler(chunkRows = 3))
      val source = Round(None, 0, Some("Source"), contest.getId, active = true)
      val first = service.createNewRound(source, jurors.map(_.getId))
      val second = service.createNewRound(source.copy(name = Some("Second")), jurors.map(_.getId))
      // juror 1 rejects the even files in the first round and those divisible by 3 in the
      // second: only 6, 12 and 18 have two rejections, one in each round
      def vote(): Unit = Seq(first -> 2, second -> 3).foreach { case (round, divisor) =>
        VoteJdbc.byRound(round.getId).foreach { row =>
          val rate = if (row.juryId == jurors.head.getId && row.pageId % divisor == 0) -1 else 1
          VoteJdbc.rate(row.pageId, row.juryId, round.getId, rate)
        }
      }
      vote()
      val next = source.copy(previous = Some(s"${first.getId},${second.getId}"), distribution = 1, number = 3)
      val cases = Seq(
        (next.copy(prevRejectedBy = Some(2)), (1L to 20L).toSet -- Set(6L, 12L, 18L)),
        (next.copy(prevRejectedBy = Some(1)), Set[Long](1, 5, 7, 11, 13, 17, 19))
      )
      val inMemory = new DistributeImages(ImageJdbc)
      val created = cases.map { case (settings, expected) =>
        val round = service.createNewRound(settings, jurors.map(_.getId))
        (round, expected, pageIds(round.getId), inMemory.imagesByRound(round, Seq(first, second)).map(_.pageId).toSet)
      }
      addImages(contest, 11 to 20)
      Seq(first, second).foreach(r => service.distributeNewImages(r.getId))
      vote()
      created.map { case (round, expected, before, beforeReference) =>
        val added = expected.filter(_ > 10)
        val reference = inMemory.imagesByRound(round, Seq(first, second)).map(_.pageId).toSet
        val count = service.newFilesCount(round.getId)
        val distributed = service.distributeNewImages(round.getId)
        (before === expected.filter(_ <= 10)) and (beforeReference === Set.empty[Long]) and
          (reference === added) and (count === added.size.toLong) and (distributed === added.size) and
          (pageIds(round.getId) === expected)
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
      def rows(round: Round) = VoteJdbc.byRound(round.getId).map(s => (s.pageId, s.juryId)).toSet
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
