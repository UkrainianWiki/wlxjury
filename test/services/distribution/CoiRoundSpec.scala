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
  * and "Leave out conflicts of interest now" ([[RoundConflicts.removeFromRound]]) takes
  * them out of a round that has them, with their votes.
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

  private def remove(roundId: Long, by: User) =
    RoundConflicts.removeFromRound(roundId, by, RoundDistributionHandler.dedupeKey(roundId))

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

  "leaving out conflicts of interest now" should {

    def criteriaRows(roundId: Long): Map[Long, Int] = DB.readOnly { implicit s =>
      sql"""SELECT s.page_id, COUNT(*) FROM criteria_rate c JOIN vote s ON s.id = c.vote_id
            WHERE s.round_id = $roundId GROUP BY s.page_id""".map(rs => rs.long(1) -> rs.int(2)).list().toMap
    }

    def asideRows(roundId: Long): Set[Long] = DB.readOnly { implicit s =>
      sql"SELECT page_id FROM vote_aside WHERE round_id = $roundId".map(_.long(1)).list().toSet
    }

    def addCriteria(roundId: Long): Unit = DB.autoCommit { implicit s =>
      sql"""INSERT INTO criteria_rate (vote_id, criterion_id, rate)
            SELECT s.id, 1, 1 FROM vote s WHERE s.round_id = $roundId""".update()
    }

    "remove the files with their votes, those moved aside and criteria rates, and nothing else" in {
      val (contest, jurors, _) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val other = svc.createNewRound(newRound(contest, excludeCoi = false, number = 2), jurors.map(_.getId))
      addCriteria(round.getId)
      // file 6 deleted on Commons: its votes moved aside
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(6L)))
      asideRows(round.getId) === Set(6L)
      // the round's files, those with votes moved aside too
      RoundImageCounts.get(Seq(round.getId))(ImageJdbc.imageCountByRounds) === Map(round.getId -> 10)

      val admin = User.findByEmail("juror@example.com").head
      val outcome = remove(round.getId, admin)

      outcome must beLike { case RoundConflicts.Outcome.Removed(files) => files.map(_._1).toSet === conflicts }
      pageIds(round.getId) === (1L to 10L).toSet -- conflicts
      asideRows(round.getId) must beEmpty
      criteriaRows(round.getId).keySet === (1L to 10L).toSet -- conflicts
      // no criteria rate is left without its vote, in vote or moved aside
      DB.readOnly { implicit s =>
        sql"""SELECT COUNT(*) FROM criteria_rate cr
              WHERE NOT EXISTS (SELECT 1 FROM vote s WHERE s.id = cr.vote_id)
                AND NOT EXISTS (SELECT 1 FROM vote_aside d WHERE d.id = cr.vote_id)""".map(_.long(1)).single()
      } === Some(0L)
      roundDao.findById(round.getId).get.excludeCoi must beTrue
      RoundImageCounts.get(Seq(round.getId))(ImageJdbc.imageCountByRounds) === Map(round.getId -> 7)
      // another round keeps its files
      pageIds(other.getId) === (1L to 10L).toSet - 6L
      // restoring the deleted file brings nothing back
      DB.localTx(implicit s => DeletedFiles.restore(Seq(6L)))
      pageIds(round.getId) must not(contain(6L))
    }

    "be refused for a round that is not open" in {
      val (contest, jurors, _) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      val admin = jurors.head
      sql"UPDATE round SET active = 0 WHERE id = ${round.getId}".update()(AutoSession)
      remove(round.getId, admin) === RoundConflicts.Outcome.NotOpen
      sql"UPDATE round SET active = 1, distributing = 1 WHERE id = ${round.getId}".update()(AutoSession)
      remove(round.getId, admin) === RoundConflicts.Outcome.NotOpen
      pageIds(round.getId) === (1L to 10L).toSet
      remove(12345L, admin) === RoundConflicts.Outcome.NotFound
    }

    "be refused while a distribution of the round is queued or running" in {
      val (contest, jurors, _) = setUp()
      val round = service.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      // "Distribute new files", queued: it reads the round when it starts
      DB.autoCommit { implicit s =>
        JobJdbc.insert(services.jobs.NewJob(RoundDistributionHandler.Kind, Some(contest.getId),
          Some(RoundDistributionHandler.SubjectType), round.id,
          Some(RoundDistributionHandler.dedupeKey(round.getId))), owner = None)
      }
      remove(round.getId, jurors.head) === RoundConflicts.Outcome.Distributing
      pageIds(round.getId) === (1L to 10L).toSet
      roundDao.findById(round.getId).get.excludeCoi must beFalse
    }

    "let \"Distribute new files\" add a file again once its author's role is gone" in {
      val (contest, jurors, board) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, excludeCoi = false), jurors.map(_.getId))
      remove(round.getId, jurors.head)
      pageIds(round.getId) must not(contain(2L))

      svc.distributeNewImages(round.getId) === 0 // the round now leaves them out
      UserContestJdbc.updateRole(board.getId, contest.getId, User.NO_ROLE)
      svc.distributeNewImages(round.getId) === 1
      pageIds(round.getId) must contain(2L)
    }
  }
}
