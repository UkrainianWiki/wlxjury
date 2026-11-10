package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.cmd.DistributeImages
import org.intracer.wmua.{BinaryOrder, ContestJury, Image, RejectedMode}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.Json
import scalikejdbc.{DB, DBSession}
import services.jobs.{Job, JobError, JobJdbc, JobScheduler, JobStatus, NewJob, Progress}
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
      // the form submitted again with other rounds to leave out (none): the stored ones apply
      val again = s.service.submit(s.q001Settings.copy(excludeRounds = Nil), s.jurors.map(_.getId), form)
      (first.job.map(_.status) must beSome(JobStatus.Failed)) and (failed.excludeRounds === Seq(s.q01.getId)) and
        (roundDao.findById(first.round.getId).get.excludeRounds === Seq(s.q01.getId)) and
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
        (refusalKey(s.q001Settings.copy(id = Some(7), excludeRounds = Seq(7))) must beSome(H.ExcludeRoundsSelf)) and
        (refusalKey(s.q001Settings) must beNone) and
        (refusalKey(s.q001Settings, lookup = _ => Nil, tellMissing = false) must beSome(H.ExcludeRoundsOther))
    }

    "check the length and the ids before any lookup" in {
      val round = Round(None, 0, None, 1, excludeRounds = Seq.fill(40)(1234567L))
      var looked = false
      val refusal = H.excludedRoundsError(round, _ => { looked = true; Nil }, _ => false)
      (refusal.map(_.key) must beSome(H.ExcludeRoundsOther)) and (looked must beFalse) and
        (H.excludedRoundsError(round.copy(excludeRounds = Seq(-1L)), _ => { looked = true; Nil }, _ => false)
          .map(_.key) must beSome(H.ExcludeRoundsOther)) and (looked must beFalse)
    }

    "refuse a previous round of the round, which would leave it empty" in {
      val s = setUp()
      (s.service.submit(s.q001Settings.copy(excludeRounds = Seq(s.q00.getId)), s.jurors.map(_.getId)) must
        throwA[H.ExcludeRoundsRefused].like { case e: H.ExcludeRoundsRefused =>
          e.refusal.key === H.ExcludeRoundsPrevious }) and
        (refusalKey(s.q001Settings.copy(excludeRounds = Seq(s.q01.getId, s.q00.getId))) must beSome(H.ExcludeRoundsPrevious))
    }

    "refuse a round being distributed (being created, or with a queued or running job), its files still coming" in {
      val s = setUp()
      Round.updateById(s.q01.getId).withAttributes("distributing" -> true)
      val creating = s.service.submit(s.q001Settings, s.jurors.map(_.getId)) must
        throwA[H.ExcludeRoundsRefused].like { case e: H.ExcludeRoundsRefused =>
          (e.refusal.key === H.ExcludeRoundsBusy) and (e.refusal.args === Seq("Q-01")) }
      // "Count new files" only reads: it counts by Q-01's files as they are, busy or not
      val counted = roundDao.create(s.q001Settings.copy(number = 9))
      counted.addUsers(s.jurors.map(u => RoundUser(counted.getId, u.getId, "jury", active = true)))
      val count = s.service.newFilesCount(counted.getId)
      Round.updateById(s.q01.getId).withAttributes("distributing" -> false)
      creating and (count === 3L) and (refusalKey(s.q001Settings, busy = _.id.contains(s.q01.getId)) must beSome(H.ExcludeRoundsBusy)) and
        (H.distributingNow(roundDao.findById(s.q01.getId).get) must beFalse)
    }

    "refuse a round to leave out with an active job (queued), at the service and in the job, as a reason not a crash" in {
      val h = TestRoundServices.handler(chunkRows = 3)
      val s = setUp(h)
      // a queued distribution of Q-01 (another instance's, or one queued between the checks)
      val q01Job = DB.localTx(implicit session => JobJdbc.insert(distributionJob(h, s.q01), owner = None))
      val atService = s.service.submit(s.q001Settings, s.jurors.map(_.getId)) must
        throwA[H.ExcludeRoundsRefused].like { case e: H.ExcludeRoundsRefused =>
          (e.refusal.key === H.ExcludeRoundsBusy) and (e.refusal.args === Seq("Q-01")) }
      // Q-00-1 stored (as by a run that passed the service's check before Q-01's job was queued),
      // its job run now: the job's check refuses it, the job page says why
      val q001 = roundDao.create(s.q001Settings.copy(number = 9))
      q001.addUsers(s.jurors.map(u => RoundUser(q001.getId, u.getId, "jury", active = true)))
      val job = runJob(h, q001)
      (roundDao.findById(s.q01.getId).get.distributing must beFalse) and
        (H.distributingNow(roundDao.findById(s.q01.getId).get) must beTrue) and atService and
        (job.status === JobStatus.Failed) and (job.failure must beSome(JobError(H.ExcludeRoundsBusy, Seq("Q-01")))) and
        (roundFiles(q001.getId) must beEmpty) and
        (JobJdbc.findById(q01Job).map(_.status) must beSome(JobStatus.Queued))
    }

    "refuse distributing a round while a round leaving out its files has an active job: new files, resume, Retry" in {
      val h = TestRoundServices.handler(chunkRows = 3)
      val s = setUp(h)
      // Q-01's distribution failed (its job page offers a Retry)
      val q01Failed = DB.localTx { implicit session =>
        val id = JobJdbc.insert(distributionJob(h, s.q01), owner = None)
        JobJdbc.update(id, JobStatus.Failed, JobJdbc.findById(id).get.state, Progress(), Some(JobError.crashed("ab12cd34").encode))
        id
      }
      val q01Files = roundFiles(s.q01.getId)
      // Q-00-1 created with a scheduler that runs nothing here: its creation's job stays queued
      val parked = new RoundService(Round, new JobScheduler(Seq(h), JobScheduler.Settings()), h)
      val q001 = parked.submit(s.q001Settings, s.jurors.map(_.getId))
      def excluderBusy(call: => Any) = call must throwA[H.ExcludeRoundsRefused].like { case e: H.ExcludeRoundsRefused =>
        (e.refusal.key === H.ExcludeRoundsExcluderBusy) and (e.refusal.args === Seq("Q-00-1")) }
      // "Distribute new files" on Q-01 resumes its failed job: refused, the job left failed
      val resumed = excluderBusy(s.service.distributeNewFiles(s.q01.getId))
      val stillFailed = JobJdbc.findById(q01Failed).map(_.status)
      // the job page's Retry isn't the service's: the job refuses itself as it runs
      val retried = TestRoundServices.inlineScheduler(h).retry(q01Failed)
      val after = JobJdbc.findById(q01Failed).get
      // and a new distribution of Q-01 (its latest job not failed): refused before anything is queued
      DB.localTx(implicit session => JobJdbc.update(q01Failed, JobStatus.Done, after.state, Progress(), None))
      val fresh = excluderBusy(s.service.distributeNewFiles(s.q01.getId))
      (q001.job.map(_.status) must beSome(JobStatus.Queued)) and resumed and (stillFailed must beSome(JobStatus.Failed)) and
        (retried must beRight) and (after.status === JobStatus.Failed) and
        (after.failure must beSome(JobError(H.ExcludeRoundsExcluderBusy, Seq("Q-00-1")))) and fresh and
        (JobJdbc.activeByDedupeKey(H.dedupeKey(s.q01.getId)) must beNone) and
        (roundFiles(s.q01.getId) === q01Files) and
        (H.excluderBusy(roundDao.findById(s.q01.getId).get).flatMap(_.id) === q001.round.id)
    }

    "say that a stored round's round to leave out no longer exists (deleted by SQL), not that it is another contest's" in {
      val s = setUp()
      val other = roundDao.create(Round(None, 1, Some("Other"), createContests(2).head.getId))
      def stored(excluded: Long): Long = {
        val round = roundDao.create(s.q001Settings.copy(number = 9, excludeRounds = Nil))
        round.addUsers(s.jurors.map(u => RoundUser(round.getId, u.getId, "jury", active = true)))
        Round.updateById(round.getId).withAttributes("excludeRounds" -> excluded.toString)
        round.getId
      }
      val gone = other.getId + 1000
      (s.service.distributeNewFiles(stored(gone)) must throwA[H.ExcludeRoundsRefused].like {
        case e: H.ExcludeRoundsRefused =>
          (e.refusal.key === H.ExcludeRoundsMissing) and (e.refusal.args === Seq(gone.toString)) and
            (e.getMessage must contain("no longer exists"))
      }) and
        (s.service.distributeNewFiles(stored(other.getId)) must throwA[H.ExcludeRoundsRefused].like {
          case e: H.ExcludeRoundsRefused => e.refusal.key === H.ExcludeRoundsOther
        }) and
        // a new round's posted ids say nothing of other contests
        (refusalKey(s.q001Settings.copy(excludeRounds = Seq(gone)), tellMissing = false) must beSome(H.ExcludeRoundsOther))
    }

    "read the votes of the previous round with an exclusion: rejected by at least 1, only those" in {
      val s = setUp()
      // a round holding two of Q-00's rejected files (1, 4, 7, 10), whatever their votes there
      val held = roundDao.create(Round(None, 9, Some("Held"), s.contest.getId))
      Seq(1L, 4L).foreach(p => VoteJdbc.create(p, 0, s.jurors.head.getId, held.getId))
      val only = Round(None, 0, Some("Rejected"), s.contest.getId, distribution = 1, active = true,
        previous = Some(s.q00.getId.toString), prevRejectedBy = Some(1), prevRejectedMode = RejectedMode.Only,
        excludeRounds = Seq(held.getId))
      val round = s.service.createNewRound(only, s.jurors.map(_.getId))
      val control = s.service.createNewRound(only.copy(name = Some("control"), excludeRounds = Nil), s.jurors.map(_.getId))
      val job = s.service.distributionJob(round.getId).get
      (pageIds(round.getId) === Set[Long](7, 10)) and (pageIds(control.getId) === Set[Long](1, 4, 7, 10)) and
        // the vote-based count (previousCount) leaves them out too: the job's total
        (job.progressTotal must beSome(2L)) and (s.service.newFilesCount(round.getId) === 0L)
    }

    "rank a rated top-N among the files left after them" in {
      val s = setUp()
      val rated = s.service.createNewRound(Round(None, 0, Some("Rated"), s.contest.getId, active = true,
        rates = Round.ratesById(5)), s.jurors.take(2).map(_.getId))
      VoteJdbc.byRound(rated.getId).foreach(v => VoteJdbc.rate(v.pageId, v.juryId, rated.getId, (v.pageId % 5 + 1).toInt))
      // 4 and 9 score 5, 3 and 8 score 4
      val held = roundDao.create(Round(None, 9, Some("Held"), s.contest.getId))
      Seq(4L, 9L).foreach(p => VoteJdbc.create(p, 0, s.jurors.head.getId, held.getId))
      val top = Round(None, 0, Some("Top"), s.contest.getId, distribution = 1, active = true,
        rates = Round.ratesById(5), previous = Some(rated.getId.toString), topImages = Some(2),
        excludeRounds = Seq(held.getId))
      val round = s.service.createNewRound(top, s.jurors.map(_.getId))
      val control = s.service.createNewRound(top.copy(name = Some("control"), excludeRounds = Nil), s.jurors.map(_.getId))
      (pageIds(round.getId) === Set[Long](3, 8)) and (pageIds(control.getId) === Set[Long](4, 9)) and
        (s.service.newFilesCount(round.getId) === 0L)
    }
  }

  private val H = RoundDistributionHandler

  /** [[RoundDistributionHandler.excludedRoundsError]]'s key for `round`, by default with the
    * rounds as stored and nothing being distributed.
    */
  private def refusalKey(round: Round, lookup: Seq[Long] => Seq[Round] = Round.findByIdsInAnyContest,
      busy: Round => Boolean = _ => false, tellMissing: Boolean = true): Option[String] =
    H.excludedRoundsError(round, lookup, busy, tellMissing).map(_.key)

  /** A distribution job of the round as the service queues it (its round id and initial state). */
  private def distributionJob(h: RoundDistributionHandler, round: Round)(implicit session: DBSession): NewJob =
    NewJob(kind = H.Kind, contestId = Some(round.contestId), subjectType = Some(H.SubjectType), subjectId = round.id,
      dedupeKey = Some(H.dedupeKey(round.getId)), params = Json.obj("roundId" -> round.getId),
      state = h.initialState(round), progress = Progress(unit = Some("images")))

  /** Runs a distribution job of the round to its end, inline, past the service's checks. */
  private def runJob(h: RoundDistributionHandler, round: Round): Job = {
    val scheduler = TestRoundServices.inlineScheduler(h)
    val id = DB.localTx(implicit session => JobJdbc.insert(distributionJob(h, round), owner = Some(scheduler.instanceId)))
    scheduler.started(id)
    JobJdbc.findById(id).get
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
