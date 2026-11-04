package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.imports.{DeletedFiles, FakeCommonsSource, FakeFile}
import services.jobs.{JobJdbc, JobStatus}
import services.{RoundService, TestRoundServices}

/** A round that leaves out conflicts of interest ([[Round.excludeCoi]]): its distribution
  * leaves out the files of the contest's members in [[User.COI_ROLES]] and of root users,
  * and disqualifying them ([[RoundConflicts.disqualify]], P1b) takes them out of a round that
  * has them, keeping their votes aside, until they are requalified ([[RoundConflicts.requalify]]).
  */
class CoiRoundSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** Files 1-10: 2 by the board member, 4 by a juror, 6 by a root user, 8 by someone else,
    * the rest without an author.
    */
  private def author(id: Long): Option[String] = id match {
    case 2 => Some("Board member")
    case 4 => Some("Juror one")
    case 6 => Some("Root user")
    case 8 => Some("Somebody")
    case _ => None
  }

  private val conflicts = Set(2L, 4L, 6L)

  private def image(id: Long) =
    Image(id, s"File:Image $id.jpg", Some(s"url$id"), None, 640, 480, Some(s"12-345-$id"), author = author(id))

  /** On Commons only: by the staff member. */
  private val onCommons =
    FakeFile(Image(100, "File:Image 100.jpg", Some("url100"), None, 640, 480),
      text = Some("{{Information|author=[[User:Staffer|Staffer]]}}"))

  private def service: RoundService =
    TestRoundServices.roundService(TestRoundServices.handler(commons = new FakeCommonsSource(Seq(onCommons))))

  private def setUp(): (ContestJury, Seq[User], User) = {
    implicit val contest: ContestJury = createContests(10).head
    val images = (1L to 10L).map(image)
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    val jurors = Seq(
      User.create(User("Juror", "juror@example.com", None, Set("jury"), Some("h"), contest.id,
        wikiAccount = Some("Juror one"))),
      User.create(User("Juror 2", "juror2@example.com", None, Set("jury"), Some("h"), contest.id))
    )
    val board = User.create(User("Board", "", None, Set("board"), Some("h"), contest.id,
      wikiAccount = Some("Board_member")))
    User.create(User("Staff", "", None, Set("staff"), Some("h"), contest.id, wikiAccount = Some("Staffer")))
    User.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), Some("h"), None,
      wikiAccount = Some("Root user")))
    (contest, jurors, board)
  }

  private def newRound(contest: ContestJury, excludeCoi: Boolean, number: Long = 1) =
    Round(None, number, Some("R"), contest.getId, distribution = 0, active = true, excludeCoi = excludeCoi)

  private def pageIds(roundId: Long): Set[Long] = VoteJdbc.byRound(roundId).map(_.pageId).toSet

  private def jobOf(roundId: Long) =
    JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
      .apply(roundId)

  "a round that leaves out conflicts of interest" should {

    "leave them out of a first round, and pass its count check" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = true), jurors.map(_.getId))
      pageIds(round.getId) === (1L to 10L).toSet -- conflicts
      jobOf(round.getId).status === JobStatus.Done
      roundDao.findById(round.getId).get.excludeCoi must beTrue
    }

    "keep them without the flag" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      pageIds(round.getId) === (1L to 10L).toSet
    }

    "leave them out of a round from previous rounds, members added since too, and of a top-N" in {
      val (contest, jurors, board) = setUp()
      val svc = service
      val first = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      VoteJdbc.byRound(first.getId).foreach(s => VoteJdbc.rate(s.pageId, s.juryId, first.getId, 1))
      // file 8's author joins the contest's staff after round 1
      User.create(User("Somebody", "somebody@example.com", None, Set("staff"), Some("h"), contest.id,
        wikiAccount = Some("Somebody")))

      val next = svc.createNewRound(newRound(contest, excludeCoi = true, number = 2)
        .copy(previous = Some(first.getId.toString), prevSelectedBy = Some(1)), jurors.map(_.getId))
      pageIds(next.getId) === (1L to 10L).toSet -- conflicts - 8L

      // top 3, ranked after the filters: three eligible files
      val top = svc.createNewRound(newRound(contest, excludeCoi = true, number = 3)
        .copy(previous = Some(first.getId.toString), topImages = Some(3)), jurors.map(_.getId))
      pageIds(top.getId).size === 3
      pageIds(top.getId).intersect(conflicts + 8L) must beEmpty
      jobOf(top.getId).status === JobStatus.Done
    }

    "leave them out of a pasted list, reported in the job's details" in {
      val (contest, jurors, _) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = true).copy(minMpx = Some(100)), jurors.map(_.getId))
      pageIds(round.getId) must beEmpty

      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 2.jpg", "File:Image 3.jpg", "File:Image 100.jpg"),
        newFiles = false)

      pageIds(round.getId) === Set(3L)
      val job = jobOf(round.getId)
      job.status === JobStatus.Done
      val st = RoundDistributionHandler.stateOf(job)
      st.coiLeftOut === 2L
      st.coiTitles === Seq("File:Image 2.jpg (Board_member — board)", "File:Image 100.jpg (Staffer — staff)")
      // the fetched file's author, as an import stores it
      ImageJdbc.findById(100L).flatMap(_.author) === Some("Staffer")
    }

    "refresh a pasted list's stored files from Commons, their authors deciding" in {
      val (contest, jurors, _) = setUp()
      // 8 stored with an author Commons no longer names; 2 fetched without its text; 3 not on Commons
      val svc = TestRoundServices.roundService(TestRoundServices.handler(commons = new FakeCommonsSource(Seq(
        FakeFile(image(8), text = Some("{{Information|author=[[User:Board_member|B]]}}")),
        FakeFile(image(2), text = None)))))
      val round = svc.createNewRound(newRound(contest, excludeCoi = true).copy(minMpx = Some(100)), jurors.map(_.getId))

      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 8.jpg", "File:Image 2.jpg", "File:Image 3.jpg"),
        newFiles = false)

      ImageJdbc.findById(8L).flatMap(_.author) === Some("Board member")
      ImageJdbc.findById(2L).flatMap(_.author) === Some("Board member") // kept: no text
      pageIds(round.getId) === Set(3L)
      RoundDistributionHandler.stateOf(jobOf(round.getId)).coiTitles ===
        Seq("File:Image 2.jpg (Board_member — board)", "File:Image 8.jpg (Board_member — board)")
    }

    "take a pasted list's stored file by its title when Commons has another file of that name" in {
      val (contest, jurors, _) = setUp()
      // file 3 deleted on Commons and another uploaded under its name
      val svc = TestRoundServices.roundService(TestRoundServices.handler(commons = new FakeCommonsSource(Seq(
        FakeFile(image(3).copy(pageId = 300))))))
      val round = svc.createNewRound(newRound(contest, excludeCoi = true).copy(minMpx = Some(100)), jurors.map(_.getId))

      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 3.jpg"), newFiles = false)

      pageIds(round.getId) === Set(3L)
      ImageJdbc.findById(300L) must beNone
    }

    "add a pasted list's conflicts of interest to a round without the flag" in {
      val (contest, jurors, _) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false).copy(minMpx = Some(100)), jurors.map(_.getId))
      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 2.jpg"), newFiles = false)
      pageIds(round.getId) === Set(2L)
    }
  }

  "disqualifying conflicts of interest" should {

    def criteriaRates(roundId: Long): Set[(Long, Long, Int)] = DB.readOnly { implicit s =>
      sql"""SELECT c.vote_id, c.criterion_id, c.rate FROM criteria_rate c
            WHERE c.vote_id IN (SELECT id FROM vote WHERE round_id = $roundId
                                UNION SELECT id FROM vote_aside WHERE round_id = $roundId)"""
        .map(rs => (rs.long(1), rs.long(2), rs.int(3))).list().toSet
    }

    /** The round's votes as stored: id, page, juror, rate, in vote or aside. */
    def votes(table: SQLSyntax, roundId: Long): Set[(Long, Long, Long, Int)] = DB.readOnly { implicit s =>
      sql"SELECT id, page_id, jury_id, rate FROM $table WHERE round_id = $roundId"
        .map(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4))).list().toSet
    }

    def asidePages(roundId: Long): Set[Long] = votes(sqls"vote_aside", roundId).map(_._2)

    def addCriteria(roundId: Long): Unit = DB.autoCommit { implicit s =>
      sql"""INSERT INTO criteria_rate (vote_id, criterion_id, rate)
            SELECT s.id, 1, 1 + s.id % 5 FROM vote s WHERE s.round_id = $roundId""".update()
    }

    /** Every vote of the round rated, each with its own rate. */
    def rateAll(roundId: Long): Unit = DB.autoCommit { implicit s =>
      sql"UPDATE vote SET rate = 1 + (id * 7) % 9 WHERE round_id = $roundId".update()
    }

    def statuses(roundId: Long): Map[Long, String] = RoundImageJdbc.byRound(roundId).map(r => r.pageId -> r.status).toMap

    def stop(roundId: Long): Unit = Round.setActiveGuarded(roundId, active = false) must beTrue

    /** The file's events in the round (V88), oldest first: action, code, status before and after, by. */
    def history(roundId: Long, pageId: Long): Seq[(String, String, String, Option[String], Option[Long])] =
      RoundImageEventJdbc.ofFile(roundId, pageId).map(e => (e.action, e.code, e.priorStatus, e.newStatus, e.by))

    def disqualify(roundId: Long, by: User, only: Option[Set[Long]] = None, excludeCoi: Boolean = false) =
      RoundConflicts.disqualify(roundId, by, RoundDistributionHandler.dedupeKey(roundId), only, excludeCoi)

    def requalify(roundId: Long, pageIds: Seq[Long], by: User) =
      RoundConflicts.requalify(roundId, pageIds, RoundConflicts.RequalifyReason.MistakenMatch, by,
        RoundDistributionHandler.dedupeKey(roundId))

    "disqualify the files, keeping their votes and criteria rates aside, and nothing else" in {
      val (contest, jurors, _) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val other = svc.createNewRound(newRound(contest, excludeCoi = false, number = 2), jurors.map(_.getId))
      addCriteria(round.getId)
      // file 6 deleted on Commons while the round is open: its votes moved aside
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(6L)))
      asidePages(round.getId) === Set(6L)
      val before = votes(sqls"vote", round.getId) ++ votes(sqls"vote_aside", round.getId)
      val rates = criteriaRates(round.getId)
      stop(round.getId)

      val admin = jurors.head
      disqualify(round.getId, admin, excludeCoi = true) must beLike {
        case RoundConflicts.Outcome.Disqualified(files) => files.map(_._1).toSet === conflicts
      }

      pageIds(round.getId) === (1L to 10L).toSet -- conflicts
      asidePages(round.getId) === conflicts
      // every vote kept, with its id and rate; every criteria rate too
      (votes(sqls"vote", round.getId) ++ votes(sqls"vote_aside", round.getId)) === before
      criteriaRates(round.getId) === rates
      statuses(round.getId).filter(_._2 == "dq").keySet === conflicts
      RoundImageJdbc.disqualified(round.getId).map(r => (r.pageId, r.code, r.detail, r.by)) === Seq(
        (2L, Some("coi"), Some("Board_member — board"), admin.id),
        (4L, Some("coi"), Some("Juror one — jury"), admin.id),
        (6L, Some("coi"), Some("Root user — root"), admin.id))
      RoundImageJdbc.disqualified(round.getId).forall(_.at.isDefined) must beTrue
      roundDao.findById(round.getId).get.excludeCoi must beTrue
      RoundImageCounts.get(Seq(round.getId))(ImageJdbc.imageCountByRounds) === Map(round.getId -> 7)
      CoiMembers.roundFiles(round.getId, CoiMembers.forContest(contest.getId)) must beEmpty
      // another round keeps its files
      pageIds(other.getId) === (1L to 10L).toSet - 6L
      statuses(other.getId).get(6L) === Some("deleted")
      Seq(round, other).flatMap(r => RoundImageInvariants.violations(r.getId)) must beEmpty

      // restoring the deleted file brings back the other round's votes only
      DB.localTx(implicit s => DeletedFiles.restore(Seq(6L)))
      pageIds(round.getId) must not(contain(6L))
      statuses(round.getId).get(6L) === Some("dq")
      asidePages(round.getId) === conflicts
      statuses(other.getId).get(6L) === Some("in")
      // a second disqualify finds nothing new
      disqualify(round.getId, admin) === RoundConflicts.Outcome.Disqualified(Nil)
      Seq(round, other).flatMap(r => RoundImageInvariants.violations(r.getId)) must beEmpty
    }

    "requalify them with their votes, exact rates and criteria rates back" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      rateAll(round.getId)
      addCriteria(round.getId)
      val before = votes(sqls"vote", round.getId)
      val rates = criteriaRates(round.getId)
      val rowsBefore = RoundImageJdbc.byRound(round.getId)
      stop(round.getId)
      disqualify(round.getId, jurors.head)
      RoundImageInvariants.violations(round.getId) must beEmpty

      requalify(round.getId, conflicts.toSeq :+ 8L :+ 999L, jurors.head) === RoundConflicts.Outcome.Requalified(Seq(2L, 4L, 6L))

      votes(sqls"vote", round.getId) === before
      votes(sqls"vote_aside", round.getId) must beEmpty
      criteriaRates(round.getId) === rates
      RoundImageJdbc.byRound(round.getId) === rowsBefore
      RoundImageJdbc.disqualified(round.getId) must beEmpty
      DB.readOnly { implicit s =>
        sql"""SELECT COUNT(*) FROM round_image WHERE round_id = ${round.getId}
              AND (dq_code IS NOT NULL OR dq_detail IS NOT NULL OR dq_by IS NOT NULL OR dq_at IS NOT NULL)"""
          .map(_.long(1)).single()
      } === Some(0L)
      RoundImageCounts.get(Seq(round.getId))(ImageJdbc.imageCountByRounds) === Map(round.getId -> 10)
      RoundImageInvariants.violations(round.getId) must beEmpty
      // each recorded (V88): was `in`, disqualified, then requalified `in` again, by the admin
      conflicts.toSeq.sorted.map(p => history(round.getId, p)).distinct === Seq(Seq(
        ("disqualify", "coi", "in", Some("dq"), jurors.head.id),
        ("requalify", "mistaken_match", "dq", Some("in"), jurors.head.id)))
      RoundImageEventJdbc.byRound(round.getId).map(_.pageId).toSet === conflicts
      // still conflicts: a re-check offers them again (no overrides yet)
      CoiMembers.roundFiles(round.getId, CoiMembers.forContest(contest.getId)).map(_._1).toSet === conflicts
    }

    "requalify a file deleted on Commons meanwhile as it was, in with its votes, aside once the round opens" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      rateAll(r)
      val before = votes(sqls"vote", r)
      stop(r)
      // deleted on Commons while the round is stopped: a round that isn't open keeps its votes
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(3L)))
      statuses(r).get(3L) === Some("in")
      disqualify(r, jurors.head)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      statuses(r).get(2L) === Some("dq")

      requalify(r, Seq(2L, 4L), jurors.head) === RoundConflicts.Outcome.Requalified(Seq(2L, 4L))
      // as before the disqualify: in, with the votes, as file 3 (deleted, never disqualified)
      statuses(r).get(2L) === Some("in")
      statuses(r).get(4L) === Some("in")
      votes(sqls"vote", r) === before.filterNot(_._2 == 6L)
      RoundImageInvariants.violations(r) must beEmpty

      // the round opens: the deleted files' votes move aside, as for any deleted file
      Round.setActiveGuarded(r, active = true) must beTrue
      statuses(r).get(2L) === Some("deleted")
      statuses(r).get(3L) === Some("deleted")
      asidePages(r) === Set(2L, 3L, 6L)
      RoundImageInvariants.violations(r) must beEmpty
      DB.localTx(implicit s => DeletedFiles.restore(Seq(2L)))
      statuses(r).get(2L) === Some("in")
      RoundImageInvariants.violations(r) must beEmpty
    }

    "requalify a file deleted while the round was open as it was, deleted with its votes aside (V88's prior status)" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      val admin = jurors.head
      rateAll(r)
      val before = votes(sqls"vote", r).filter(_._2 == 2L)
      // deleted on Commons while the round is open: its votes aside, `deleted`
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      statuses(r).get(2L) === Some("deleted")
      stop(r)
      disqualify(r, admin)
      statuses(r).get(2L) === Some("dq")
      history(r, 2L) === Seq(("disqualify", "coi", "deleted", Some("dq"), admin.id))
      requalify(r, conflicts.toSeq.sorted, admin) === RoundConflicts.Outcome.Requalified(Seq(2L, 4L, 6L))
      // as before the disqualify (§14's known limitation, lifted): `deleted`, its votes aside
      // with their ids and rates, none in vote
      statuses(r).get(2L) === Some("deleted")
      votes(sqls"vote_aside", r).filter(_._2 == 2L) === before
      votes(sqls"vote", r).filter(_._2 == 2L) must beEmpty
      history(r, 2L) === Seq(("disqualify", "coi", "deleted", Some("dq"), admin.id),
        ("requalify", "mistaken_match", "dq", Some("deleted"), admin.id))
      // the other files, `in` before, are `in` with their votes
      statuses(r).filter { case (p, _) => p != 2L && conflicts(p) }.values.toSet === Set("in")
      RoundImageInvariants.violations(r) must beEmpty
      // the round's opening leaves it as it is; a restore from Commons brings the votes back
      Round.setActiveGuarded(r, active = true) must beTrue
      statuses(r).get(2L) === Some("deleted")
      DB.localTx(implicit s => DeletedFiles.restore(Seq(2L)))
      statuses(r).get(2L) === Some("in")
      votes(sqls"vote", r).filter(_._2 == 2L) === before
      RoundImageInvariants.violations(r) must beEmpty
    }

    "requalify a file deleted while the round was open, then restored on Commons while disqualified, as in with its votes" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      rateAll(r)
      val before = votes(sqls"vote", r).filter(_._2 == 2L)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      stop(r)
      disqualify(r, jurors.head)
      // restored while disqualified: its votes stay aside (P1b), the row `dq`
      DB.localTx(implicit s => DeletedFiles.restore(Seq(2L)))
      statuses(r).get(2L) === Some("dq")
      requalify(r, Seq(2L), jurors.head) === RoundConflicts.Outcome.Requalified(Seq(2L))
      // was `deleted`, but no longer deleted: `in` with its votes, as the restore would have made it
      statuses(r).get(2L) === Some("in")
      votes(sqls"vote", r).filter(_._2 == 2L) === before
      history(r, 2L).map(e => (e._1, e._3, e._4)) === Seq(("disqualify", "deleted", Some("dq")), ("requalify", "dq", Some("in")))
      RoundImageInvariants.violations(r) must beEmpty
    }

    "requalify a file disqualified before V88 (no event) as in, with its votes, as before V88" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      rateAll(r)
      val before = votes(sqls"vote", r).filter(_._2 == 2L)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      stop(r)
      disqualify(r, jurors.head)
      // a row disqualified before V88 has no event
      sql"DELETE FROM round_image_event WHERE round_id = $r".update()(AutoSession)
      requalify(r, Seq(2L), jurors.head) === RoundConflicts.Outcome.Requalified(Seq(2L))
      // the behaviour before V88: `in` with its votes; the round's opening moves them aside
      statuses(r).get(2L) === Some("in")
      votes(sqls"vote", r).filter(_._2 == 2L) === before
      history(r, 2L).map(e => (e._1, e._3, e._4)) === Seq(("requalify", "dq", Some("in")))
      RoundImageInvariants.violations(r) must beEmpty
      Round.setActiveGuarded(r, active = true) must beTrue
      statuses(r).get(2L) === Some("deleted")
      RoundImageInvariants.violations(r) must beEmpty
    }

    "restore each file's status from its latest disqualify over repeated disqualify and requalify" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      val admin = jurors.head
      rateAll(r)
      val before = votes(sqls"vote", r).filter(_._2 == 2L)
      stop(r)
      // 1: in -> dq -> in
      disqualify(r, admin, only = Some(Set(2L)))
      RoundConflicts.requalify(r, Seq(2L), RoundConflicts.RequalifyReason.ConflictEnded, admin,
        RoundDistributionHandler.dedupeKey(r)) === RoundConflicts.Outcome.Requalified(Seq(2L))
      statuses(r).get(2L) === Some("in")
      // 2: deleted on Commons while the round is open, then stopped: deleted -> dq -> deleted
      Round.setActiveGuarded(r, active = true) must beTrue
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(2L)))
      stop(r)
      disqualify(r, admin, only = Some(Set(2L)))
      requalify(r, Seq(2L), admin)
      statuses(r).get(2L) === Some("deleted")
      votes(sqls"vote_aside", r).filter(_._2 == 2L) === before
      // 3: disqualified again while deleted, restored on Commons meanwhile: deleted -> dq -> in
      disqualify(r, admin, only = Some(Set(2L)))
      statuses(r).get(2L) === Some("dq")
      DB.localTx(implicit s => DeletedFiles.restore(Seq(2L)))
      requalify(r, Seq(2L), admin)
      statuses(r).get(2L) === Some("in")
      votes(sqls"vote", r).filter(_._2 == 2L) === before
      history(r, 2L) === Seq(
        ("disqualify", "coi", "in", Some("dq"), admin.id),
        ("requalify", "conflict_ended", "dq", Some("in"), admin.id),
        ("disqualify", "coi", "deleted", Some("dq"), admin.id),
        ("requalify", "mistaken_match", "dq", Some("deleted"), admin.id),
        ("disqualify", "coi", "deleted", Some("dq"), admin.id),
        ("requalify", "mistaken_match", "dq", Some("in"), admin.id))
      // only file 2 has a history; the round's newest first
      RoundImageEventJdbc.byRound(r).map(_.pageId).toSet === Set(2L)
      RoundImageEventJdbc.byRound(r).map(_.id) === RoundImageEventJdbc.ofFile(r, 2L).map(_.id).reverse
      RoundImageInvariants.violations(r) must beEmpty
    }

    "record nothing for a refused action or a file already disqualified" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      // open: refused
      disqualify(r, jurors.head) === RoundConflicts.Outcome.Open
      RoundImageEventJdbc.byRound(r) must beEmpty
      stop(r)
      disqualify(r, jurors.head)
      RoundImageEventJdbc.byRound(r).size === conflicts.size
      // again: nothing new; a requalify of files not disqualified: nothing
      disqualify(r, jurors.head) === RoundConflicts.Outcome.Disqualified(Nil)
      requalify(r, Seq(8L), jurors.head) === RoundConflicts.Outcome.Requalified(Nil)
      RoundImageEventJdbc.byRound(r).size === conflicts.size
      // a requalify refused on an open round records nothing
      Round.setActiveGuarded(r, active = true) must beTrue
      requalify(r, conflicts.toSeq, jurors.head) === RoundConflicts.Outcome.Open
      RoundImageEventJdbc.byRound(r).map(_.action).toSet === Set("disqualify")
    }

    "disqualify only the files asked for that are conflicts" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      stop(round.getId)
      disqualify(round.getId, jurors.head, only = Some(Set(2L, 3L))) must beLike {
        case RoundConflicts.Outcome.Disqualified(files) => files.map(_._1) === Seq(2L)
      }
      statuses(round.getId).filter(_._2 == "dq").keySet === Set(2L)
      roundDao.findById(round.getId).get.excludeCoi must beFalse
    }

    "be refused, writing nothing, while the round is open, being created, frozen or being distributed (W7)" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      val admin = jurors.head
      def unchanged = (pageIds(r) === (1L to 10L).toSet) and (RoundImageJdbc.disqualified(r) must beEmpty) and
        (roundDao.findById(r).get.excludeCoi must beFalse)
      // open to its jurors
      (disqualify(r, admin) === RoundConflicts.Outcome.Open) and unchanged and {
        // being created
        sql"UPDATE round SET active = 0, distributing = 1 WHERE id = $r".update()(AutoSession)
        disqualify(r, admin) === RoundConflicts.Outcome.Distributing
      } and {
        // frozen by a next round's distribution, stopped before
        sql"UPDATE round SET distributing = 0, freeze_count = 1, active_before_freeze = 0 WHERE id = $r".update()(AutoSession)
        disqualify(r, admin) === RoundConflicts.Outcome.Frozen
      } and {
        // stopped, with "Distribute new files" queued: it reads the round when it starts
        sql"UPDATE round SET freeze_count = 0, active_before_freeze = NULL WHERE id = $r".update()(AutoSession)
        DB.autoCommit { implicit s =>
          JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, Some(contest.getId),
            Some(RoundDistributionHandler.SubjectType), round.id, Some(RoundDistributionHandler.dedupeKey(r))), owner = None)
        }
        disqualify(r, admin) === RoundConflicts.Outcome.Distributing
      } and unchanged and (disqualify(12345L, admin) === RoundConflicts.Outcome.NotFound) and
        (RoundImageInvariants.violations(r) must beEmpty) and {
        // the round form's flag on an open round: set under the lock, nothing disqualified
        sql"DELETE FROM job".update()(AutoSession)
        sql"UPDATE round SET active = 1 WHERE id = $r".update()(AutoSession)
        (disqualify(r, admin, excludeCoi = true) === RoundConflicts.Outcome.Open) and
          (roundDao.findById(r).get.excludeCoi must beTrue) and (RoundImageJdbc.disqualified(r) must beEmpty)
      }
    }

    "refuse requalifying while the round is open, keeping the files disqualified" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      stop(round.getId)
      disqualify(round.getId, jurors.head)
      Round.setActiveGuarded(round.getId, active = true) must beTrue
      (requalify(round.getId, conflicts.toSeq, jurors.head) === RoundConflicts.Outcome.Open) and
        (statuses(round.getId).filter(_._2 == "dq").keySet === conflicts) and
        (asidePages(round.getId) === conflicts) and (RoundImageInvariants.violations(round.getId) must beEmpty)
    }

    "never let \"Distribute new files\" or a pasted list add a disqualified file, also once its author's role is gone" in {
      val (contest, jurors, board) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      stop(round.getId)
      disqualify(round.getId, jurors.head)
      UserContestJdbc.updateRole(board.getId, contest.getId, User.NO_ROLE)
      svc.distributeNewImages(round.getId) === 0
      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 2.jpg"), newFiles = false)
      jobOf(round.getId).status === JobStatus.Done
      pageIds(round.getId) must not(contain(2L))
      statuses(round.getId).get(2L) === Some("dq")
      RoundImageInvariants.violations(round.getId) must beEmpty
      // requalified, it is back with its votes; not added twice
      requalify(round.getId, Seq(2L), jurors.head) === RoundConflicts.Outcome.Requalified(Seq(2L))
      pageIds(round.getId) must contain(2L)
      svc.distributeNewImages(round.getId) === 0
    }

    "keep a disqualified file disqualified against a vote insert, a sync and the repair job" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val r = round.getId
      stop(r)
      disqualify(r, jurors.head)
      // a vote insert for one is refused, with nothing written
      VoteJdbc.create(2L, 0, jurors.head.getId, r) must throwA[IllegalStateException]("disqualified")
      pageIds(r) must not(contain(2L))
      // a vote written around the DAO (an older release, a hand edit): the sync keeps it dq
      DB.autoCommit(implicit s => sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (4, ${jurors.head.getId}, $r, 0)".update())
      DB.localTx { implicit s => RoundWrites.lock(Seq(r)); RoundImageJdbc.sync(r, Seq(2L, 4L, 6L)) } === RoundImageJdbc.SyncResult()
      statuses(r).filter(_._2 == "dq").keySet === conflicts
      // a vote with no `in` row (1) of a `dq` row (3)
      RoundImageInvariants.violations(r).map(v => v.invariant -> v.pageId) === Seq(1 -> 4L, 3 -> 4L)
      // a dq row with no votes left stays, so the file never comes back
      DB.autoCommit(implicit s => sql"DELETE FROM vote_aside WHERE round_id = $r AND page_id = 6".update())
      // the repair job: keeps every dq row and reports the vote of one
      val check = new RoundImageCheckHandler()
      val scheduler = TestRoundServices.inlineScheduler(check, TestRoundServices.handler())
      val queued = DB.localTx(implicit s => scheduler.enqueue(RoundImageCheckHandler.roundJob(r, contest.getId, jurors.head))).toOption.get
      scheduler.started(queued.id)
      val details = check.details(JobJdbc.findById(queued.id).get)
      ((details \ "dqWithVotes").as[Long] === 1L) and ((details \ "removed").as[Long] === 0L) and
        ((details \ "statusFixed").as[Long] === 0L) and
        (statuses(r).filter(_._2 == "dq").keySet === conflicts) and
        (service.distributeNewImages(r) === 0)
    }
  }
}
