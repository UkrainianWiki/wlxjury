package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{BinaryOrder, ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc.DBSession
import services.jobs.JobStatus
import services.{RoundService, TestRoundServices}

import java.io.FileReader
import java.util.{Properties, UUID}
import scala.jdk.CollectionConverters._

/** "Leave out files that are in round(s)" (V95), the maintainer's use case of 2026-11-10: round
  * Q-00-1 takes the files of Q-00 that didn't pass into Q-01 (Q-00's rejected and unvoted files),
  * of 4 Mpx and up, in a real region (all the regions selected: the special nominations' monument
  * id prefix and files without a monument id are left out), 1 juror per file of 4, yes/no. On every
  * path: the creation (the SQL windows and candidates; the top-N's ranking; the contest's category
  * without a previous round), "Count new files", "Distribute new files", a resubmit after a failure,
  * and the in-memory reference.
  */
class ExcludeRoundsSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** The round form's regions for a Ukrainian contest, all selected (its "select all"). */
  private val allRegions: Seq[String] = {
    val p = new Properties
    val reader = new FileReader("conf/KOATUU.properties")
    try p.load(reader) finally reader.close()
    p.stringPropertyNames.asScala.toSeq.sorted
  }

  /** A special nomination's monument id prefix: no region of the form's. */
  private val NominationPrefix = "99"

  /** File n: small (1000×800) if n % 7 == 0, else 12 Mpx; a special nomination's monument id if
    * n % 5 == 0, none if n % 5 == 4, else one in Volyn (07) or Vinnytsia (05).
    */
  private def images(ids: Range.Inclusive): Seq[Image] = ids.map { id =>
    val small = id % 7 == 0
    val monument = id % 5 match {
      case 0 => Some(s"$NominationPrefix-141-$id")
      case 4 => None
      case _ => Some(if (id % 2 == 0) s"07-101-$id" else s"05-101-$id")
    }
    Image(id.toLong, s"File:Image$id.jpg", None, None, width = if (small) 1000 else 4000,
      height = if (small) 800 else 3000, monumentId = monument)
  }

  private def addImages(contest: ContestJury, ids: Range.Inclusive): Unit = {
    val added = images(ids)
    imageDao.batchInsert(added)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, added)
  }

  /** Q-00's votes, the same by each of its jurors: n % 3 == 0 selected, 1 rejected, 2 unvoted. */
  private def vote(q00: Round): Unit = VoteJdbc.byRound(q00.getId).foreach { row =>
    val rate = (row.pageId % 3).toInt match { case 0 => 1; case 1 => -1; case _ => 0 }
    VoteJdbc.rate(row.pageId, row.juryId, q00.getId, rate)
  }

  /** Q-00-1's files of `ids`: not selected in Q-00 (so not in Q-01), 4 Mpx and up, in a real region. */
  private def q001Files(ids: Range.Inclusive): Set[Long] =
    ids.filter(id => id % 3 != 0 && id % 7 != 0 && id % 5 != 0 && id % 5 != 4).map(_.toLong).toSet

  private def pageIds(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

  private def roundFiles(roundId: Long): Set[Long] = RoundImageJdbc.byRound(roundId).map(_.pageId).toSet

  private case class Setup(contest: ContestJury, jurors: Seq[User], service: RoundService, q00: Round, q01: Round) {
    def q001Settings: Round =
      Round(None, 0, Some("Q-00-1"), contest.getId, distribution = 1, active = true,
        previous = Some(q00.getId.toString), excludeRounds = Seq(q01.getId), minMpx = Some(4),
        regions = Some(allRegions.mkString(",")))

    /** Q-00 and Q-01 take the files imported since, Q-00's are voted. */
    def newFiles(ids: Range.Inclusive): Unit = {
      addImages(contest, ids)
      service.distributeNewImages(q00.getId)
      vote(q00)
      service.distributeNewImages(q01.getId)
    }
  }

  /** Q-00 of the contest's files 1-10, two jurors each, voted; Q-01 its selected files. */
  private def setUp(handler: RoundDistributionHandler = TestRoundServices.handler(chunkRows = 3)): Setup = {
    implicit val contest: ContestJury = createContests(1).head
    val jurors = createUsers("jury", 1, 2, 3, 4)
    addImages(contest, 1 to 10)
    val service = TestRoundServices.roundService(handler)
    val q00 = service.createNewRound(Round(None, 0, Some("Q-00"), contest.getId, active = true), jurors.take(2).map(_.getId))
    vote(q00)
    val q01 = service.createNewRound(
      Round(None, 0, Some("Q-01"), contest.getId, distribution = 1, active = true, previous = Some(q00.getId.toString),
        prevSelectedBy = Some(1)), jurors.take(2).map(_.getId))
    Setup(contest, jurors, service, q00, q01)
  }

  "the round's rounds to leave out" should {

    "leave Q-00-1 exactly Q-00's rejected and unvoted files, with min Mpx and the regions on top, on every path" in {
      val s = setUp()
      val inMemory = new DistributeImages(ImageJdbc)
      val q001 = s.service.createNewRound(s.q001Settings, s.jurors.map(_.getId))
      val created = pageIds(q001.getId)
      val createdRows = VoteJdbc.byRound(q001.getId).size
      val stored = roundDao.findById(q001.getId).get
      val settings = s.service.distributionJob(q001.getId).get.params \ "settings" \ "excludeRounds"
      // the same settings without the round to leave out: Q-01's (selected) files come back
      val control = s.service.createNewRound(s.q001Settings.copy(name = Some("control"), excludeRounds = Nil),
        s.jurors.map(_.getId))

      s.newFiles(11 to 20)
      val reference = inMemory.imagesByRound(stored, Seq(s.q00)).map(_.pageId).toSet
      val count = s.service.newFilesCount(q001.getId)
      val added = s.service.distributeNewImages(q001.getId)

      (allRegions must not(contain(NominationPrefix))) and
        (pageIds(s.q01.getId) === Set[Long](3, 6, 9, 12, 15, 18)) and
        (created === Set[Long](1, 2, 8)) and (created === q001Files(1 to 10)) and
        (pageIds(control.getId) === Set[Long](1, 2, 3, 6, 8)) and
        (stored.excludeRounds === Seq(s.q01.getId)) and
        (settings.asOpt[Seq[Long]] must beSome(Seq(s.q01.getId))) and
        // 1 juror of the 4 per file
        (createdRows === created.size) and
        (reference === Set[Long](11, 13, 16, 17)) and (reference === q001Files(11 to 20)) and
        (count === 4L) and (added === 4) and
        (pageIds(q001.getId) === q001Files(1 to 20)) and
        (s.service.newFilesCount(q001.getId) === 0L) and
        (RoundImageInvariants.violations(q001.getId) must beEmpty)
    }

    "leave a file out whatever its status in the round left out (a file disqualified there too)" in {
      val s = setUp()
      // file 1 (rejected in Q-00) added to Q-01 by hand, then disqualified there: still Q-01's
      val q01Rows = VoteJdbc.byRound(s.q01.getId)
      scalikejdbc.DB.localTx { implicit session =>
        RoundWrites.lock(Seq(s.q01.getId))
        DistributeImages.insertRows(Seq(org.intracer.wmua.Vote(1L, q01Rows.head.juryId, s.q01.getId, 0)))
        RoundImageJdbc.disqualify(s.q01.getId, Map(1L -> "test"), RoundImageJdbc.DqCode.Coi, None,
          RoundImageJdbc.statuses(s.q01.getId, Seq(1L)))
      }
      val q001 = s.service.createNewRound(s.q001Settings, s.jurors.map(_.getId))
      (RoundImageJdbc.find(s.q01.getId, 1L).map(_.status) must beSome(RoundImageJdbc.Status.Dq)) and
        (pageIds(q001.getId) === Set[Long](2, 8))
    }

    "rank a top-N among the files left after them" in {
      val s = setUp()
      val top = s.q001Settings.copy(topImages = Some(2), topBinaryOrder = Some(BinaryOrder.Default))
      val withExclusion = s.service.createNewRound(top, s.jurors.map(_.getId))
      val without = s.service.createNewRound(top.copy(name = Some("control"), excludeRounds = Nil), s.jurors.map(_.getId))
      // left: 1 (two rejections), 2 and 8 (none); without, the selected 3 and 6 rank first
      (pageIds(withExclusion.getId) === Set[Long](2, 8)) and (pageIds(without.getId) === Set[Long](3, 6)) and
        (s.service.newFilesCount(withExclusion.getId) === 0L)
    }

    "leave them out of the contest's files, with no previous round" in {
      val s = setUp()
      val round = s.service.createNewRound(
        Round(None, 0, Some("Rest"), s.contest.getId, distribution = 1, active = true, excludeRounds = Seq(s.q01.getId)),
        s.jurors.map(_.getId))
      val created = pageIds(round.getId)
      s.newFiles(11 to 20)
      val inMemory = new DistributeImages(ImageJdbc)
      val reference = inMemory.imagesByRound(roundDao.findById(round.getId).get, Nil).map(_.pageId).toSet
      val count = s.service.newFilesCount(round.getId)
      val added = s.service.distributeNewImages(round.getId)
      (created === (1L to 10L).toSet -- Set(3L, 6L, 9L)) and
        (reference === (11L to 20L).toSet -- Set(12L, 15L, 18L)) and (count === 7L) and (added === 7) and
        (pageIds(round.getId) === (1L to 20L).toSet -- Set(3L, 6L, 9L, 12L, 15L, 18L))
    }

    "keep them through a resubmit that resumes a failed creation" in {
      val failing = new FailingHandler(failures = 1)
      val s = setUp(failing)
      val form = Some(UUID.randomUUID().toString)
      failing.arm()
      val first = s.service.submit(s.q001Settings, s.jurors.map(_.getId), form)
      val failed = roundDao.findById(first.round.getId).get
      val again = s.service.submit(s.q001Settings, s.jurors.map(_.getId), form)
      (first.job.map(_.status) must beSome(JobStatus.Failed)) and (failed.excludeRounds === Seq(s.q01.getId)) and
        (again must beAnInstanceOf[RoundService.Resubmitted]) and (again.round.id === first.round.id) and
        (pageIds(first.round.getId) === Set[Long](1, 2, 8)) and
        (roundFiles(first.round.getId) === Set[Long](1, 2, 8))
    }

    "refuse another contest's round, a round that doesn't exist and the round itself, creating nothing" in {
      val s = setUp()
      val other = roundDao.create(Round(None, 1, Some("Other"), createContests(2).head.getId))
      val before = roundDao.findByContest(s.contest.getId).size
      def refused(excluded: Seq[Long]) =
        s.service.submit(s.q001Settings.copy(excludeRounds = excluded), s.jurors.map(_.getId)) must
          throwA[IllegalArgumentException](message = "must be other rounds of contest")
      // a stored round that leaves out its own files (only SQL makes one): its distribution refused
      val self = roundDao.create(s.q001Settings.copy(number = 9, excludeRounds = Nil))
      self.addUsers(s.jurors.map(u => RoundUser(self.getId, u.getId, "jury", active = true)))
      Round.updateById(self.getId).withAttributes("excludeRounds" -> self.getId.toString)
      refused(Seq(other.getId)) and refused(Seq(s.q01.getId, other.getId + 1000)) and
        (roundDao.findByContest(s.contest.getId).size === before + 1) and
        (roundDao.findById(self.getId).get.excludeRounds === Seq(self.getId)) and
        (s.service.distributeNewFiles(self.getId) must throwA[IllegalArgumentException](message = "its own files")) and
        (RoundDistributionHandler.excludedRoundsError(s.q001Settings.copy(id = Some(7), excludeRounds = Seq(7)), Seq(7L))
          must beSome(RoundDistributionHandler.ExcludeRoundsSelf)) and
        (RoundDistributionHandler.excludedRoundsError(s.q001Settings, Seq(s.q01.getId)) must beNone) and
        (RoundDistributionHandler.excludedRoundsError(s.q001Settings, Nil)
          must beSome(RoundDistributionHandler.ExcludeRoundsOther))
    }
  }

  /** Fails the first `failures` chunks it inserts once armed (after the setup's rounds). */
  private class FailingHandler(failures: Int)
      extends RoundDistributionHandler(new DistributeImages(ImageJdbc), TestRoundServices.config(3), TestRoundServices.emptyCommons) {
    @volatile private var remaining = 0
    def arm(): Unit = remaining = failures

    override protected def beforeInsert(round: Round, images: Seq[Image])(implicit session: DBSession): Unit =
      if (remaining > 0) {
        remaining -= 1
        throw new RuntimeException("simulated distribution failure")
      }
  }
}
