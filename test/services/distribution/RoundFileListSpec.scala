package services.distribution

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc.DB
import services.imports.{DeletedFiles, FakeCommonsSource, FakeFile, ImportSource}
import services.jobs.{JobJdbc, JobStatus}
import services.{RoundService, TestRoundServices}

/** "Add files": a pasted list of files added to a round by its distribution job, whatever
  * the round's filters: the stored files by title, the others from Commons.
  */
class RoundFileListSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def image(id: Long) =
    Image(id, s"File:Image $id.jpg", Some(s"url$id"), Some(s"pageUrl$id"), 640, 480, Some(s"12-345-$id"))

  /** On Commons only: not stored yet. */
  private val onCommons = image(100)

  private def service: RoundService =
    TestRoundServices.roundService(TestRoundServices.handler(commons = new FakeCommonsSource(Seq(FakeFile(onCommons)))))

  /** The contest's 10 stored images, in its category, and 2 jurors. */
  private def setUp(): (ContestJury, Seq[User]) = {
    implicit val contest: ContestJury = createContests(10).head
    val images = (1L to 10L).map(image)
    imageDao.batchInsert(images)
    CategoryLinkJdbc.addToCategory(contestDao.findById(contest.getId).get.categoryId.get, images)
    (contest, createUsers("jury", 1, 2))
  }

  /** A round whose filters choose none of the contest's images (none has 100 Mpx), or all. */
  private def newRound(contest: ContestJury, filtered: Boolean = true) =
    Round(None, 0, Some("R"), contest.getId, distribution = 0, active = true,
      minMpx = if (filtered) Some(100) else None)

  private def pageIds(roundId: Long): Set[Long] = SelectionJdbc.byRound(roundId).map(_.pageId).toSet

  private def jobOf(roundId: Long) = {
    val latest = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, Seq(roundId))
    latest(roundId)
  }

  private val list = Seq(
    "File:Image_3.jpg",                                        // stored
    "https://commons.wikimedia.org/wiki/File:Image_4.jpg",     // stored
    "Файл:Image 100.jpg",                                      // on Commons only
    "File:Missing.jpg"                                         // nowhere
  ).mkString("\n")

  "a round's file list" should {

    "add the listed files to an existing round, whatever its filters" in {
      val (contest, jurors) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest), jurors.map(_.getId))
      pageIds(round.getId) must beEmpty

      svc.distributeNewFiles(round.getId, titles = ImportSource.titles(list), newFiles = false)

      pageIds(round.getId) === Set(3L, 4L, 100L)
      SelectionJdbc.byRound(round.getId).size === 6 // to both jurors
      ImageJdbc.findByTitles(Seq(onCommons.title)).map(_.pageId) === Seq(100L)
      // the round's, not the contest's
      imageDao.findByContest(contestDao.findById(contest.getId).get).map(_.pageId) must not(contain(100L))

      val job = jobOf(round.getId)
      job.status === JobStatus.Done
      val st = RoundDistributionHandler.stateOf(job)
      st.imagesAdded === 3L
      st.missing === 1L
      st.missingTitles === Seq("File:Missing.jpg")
      RoundDistributionHandler.titlesOf(job) === ImportSource.titles(list)
      RoundDistributionHandler.listOnly(job) must beTrue
    }

    "skip the listed files in the round already" in {
      val (contest, jurors) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest), jurors.map(_.getId))
      svc.distributeNewFiles(round.getId, titles = Seq("File:Image 3.jpg"), newFiles = false)
      svc.distributeNewFiles(round.getId, titles = ImportSource.titles(list), newFiles = false)

      pageIds(round.getId) === Set(3L, 4L, 100L)
      SelectionJdbc.byRound(round.getId).size === 6
      RoundDistributionHandler.stateOf(jobOf(round.getId)).imagesAdded === 2L
    }

    "add the list after the files the filters choose, with new files" in {
      val (contest, jurors) = setUp()
      val svc = service
      val round = svc.createNewRound(newRound(contest, filtered = false), jurors.map(_.getId))
      pageIds(round.getId) === (1L to 10L).toSet

      svc.distributeNewFiles(round.getId, titles = ImportSource.titles(list), newFiles = true)
      pageIds(round.getId) === (1L to 10L).toSet + 100L
      RoundDistributionHandler.stateOf(jobOf(round.getId)).imagesAdded === 1L
    }

    "tell stored titles apart by case, as Commons does" in {
      val (contest, jurors) = setUp()
      // another file than the stored File:Image 3.jpg
      val upper = Image(200, "File:Image 3.JPG", Some("url200"), Some("pageUrl200"), 640, 480, None)
      val svc = TestRoundServices.roundService(
        TestRoundServices.handler(commons = new FakeCommonsSource(Seq(FakeFile(upper)))))
      val round = svc.createNewRound(newRound(contest), jurors.map(_.getId))

      svc.distributeNewFiles(round.getId, titles = Seq(upper.title), newFiles = false)

      pageIds(round.getId) === Set(200L)
      RoundDistributionHandler.stateOf(jobOf(round.getId)).missing === 0L
    }

    "look up on Commons the titles of files deleted there" in {
      val (contest, jurors) = setUp()
      // File:Image 4.jpg uploaded again after its deletion: another page
      val reuploaded = image(4).copy(pageId = 300)
      val svc = TestRoundServices.roundService(
        TestRoundServices.handler(commons = new FakeCommonsSource(Seq(FakeFile(reuploaded)))))
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(3L, 4L)))
      val round = svc.createNewRound(newRound(contest), jurors.map(_.getId))

      svc.distributeNewFiles(round.getId, titles = Seq(image(3).title, image(4).title), newFiles = false)

      pageIds(round.getId) === Set(300L)
      val st = RoundDistributionHandler.stateOf(jobOf(round.getId))
      st.missing === 1L
      st.missingTitles === Seq(image(3).title)
    }

    "look the list up a Commons request per step" in {
      val (contest, jurors) = setUp()
      val onCommonsOnly = (101L to 105L).map(image)
      val commons = new FakeCommonsSource(onCommonsOnly.map(FakeFile(_)), maxIdsPerRequest = 2)
      val svc = TestRoundServices.roundService(TestRoundServices.handler(commons = commons))
      val round = svc.createNewRound(newRound(contest), jurors.map(_.getId))

      // the fake refuses a request of more than 2 files
      svc.distributeNewFiles(round.getId, titles = (image(1) +: onCommonsOnly).map(_.title), newFiles = false)

      pageIds(round.getId) === (101L to 105L).toSet + 1L
      commons.detailCalls.get === 3
      val job = jobOf(round.getId)
      job.status === JobStatus.Done
      RoundDistributionHandler.stateOf(job).imagesAdded === 6L
    }

    "fill a new round with the files its filters choose and the list" in {
      val (contest, jurors) = setUp()
      val submitted = service.submit(newRound(contest), jurors.map(_.getId), titles = ImportSource.titles(list))
      val created = Round.findById(submitted.round.getId).get

      created.distributing must beFalse
      pageIds(created.getId) === Set(3L, 4L, 100L)
      val job = jobOf(created.getId)
      job.status === JobStatus.Done
      RoundDistributionHandler.listOnly(job) must beFalse
      RoundDistributionHandler.stateOf(job).missing === 1L
    }
  }
}
