package controllers

import db.scalikejdbc.{CategoryJdbc, ImageJdbc, Round, SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image, JuryTestHelpers}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.{ImageService, TestImportServices}
import scalikejdbc.{DB, scalikejdbcSQLInterpolationImplicitDef}
import services.imports.{CommonsLogin, DeletedFiles, FakeCommonsSource, FakeFile, ImageImportHandler, ImportState, MwCommonsSource}
import services.jobs.{Job, JobJdbc, JobStatus}

/** "Import files" and the files' half of "Update monuments", as background jobs run
  * inline, over a fake Commons (and once over the real one).
  */
class AppendImagesSpec extends Specification with JuryTestHelpers with TestDb with BeforeAll with BeforeEach {

  sequential
  stopOnFail

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  val category = "Category:Category Name"
  val contestId = 10
  val idTemplate = "MonumentId"

  def createContest(
      monumentIdTemplate: Option[String] = None,
      id: Option[Long] = Some(contestId),
      images: Option[String] = Some(category)
  ): ContestJury =
    contestDao.create(
      id = id,
      name = "WLE",
      year = 2015,
      country = "Ukraine",
      images = images,
      categoryId = None,
      currentRound = None,
      monumentIdTemplate = monumentIdTemplate
    )

  /** The contest's files, as stored. */
  def stored(contest: ContestJury): Seq[Image] =
    imageDao.findByContest(contestDao.findById(contest.getId).get).sortBy(_.pageId)

  /** Imports `images` as Commons has them (the file page's text has their description):
    * the job afterwards.
    */
  def importFiles(images: Seq[Image], contest: ContestJury, source: String = category, titles: String = "",
      pageSize: Option[Int] = Some(50)): Job =
    importFrom(new FakeCommonsSource(images.map(file)), contest, source, titles, pageSize)

  def importFrom(commons: FakeCommonsSource, contest: ContestJury, source: String = category, titles: String = "",
      pageSize: Option[Int] = Some(50)): Job = {
    val services = new TestImportServices(commons, pageSize)
    val job = services.imageService.importFiles(contest, source, titles).toOption.get
    JobJdbc.findById(job.id).get
  }

  "importFiles" should {

    "import nothing from an empty source" in {
      val contest = createContest()
      importFiles(Nil, contest).status === JobStatus.Done
      stored(contest) === Nil
    }

    "get one image with text" in {
      val images = Seq(image(id = 11).copy(description = Some("descr"), monumentId = None))
      val contest = createContest(Some("NaturalMonument"))
      importFiles(images, contest)
      stored(contest) === images
    }

    "get one image with descr and monumentId" in {
      val descr = s"descr. {{$idTemplate|12-345-11}}"
      val images = Seq(image(id = 11).copy(description = Some(descr)))
      val contest = createContest(Some(idTemplate))
      importFiles(images, contest)
      stored(contest) === images
    }

    "get several images in pages, with descr and monumentId" in {
      val images = (11 to 15).map(id => image(id).copy(description = Some(s"{{$idTemplate|12-345-$id}}")))
      val contest = createContest(Some(idTemplate))
      val job = importFiles(images, contest, pageSize = Some(2))
      stored(contest) === images
      val done = JobJdbc.findById(job.id).get
      done.status === JobStatus.Done
      done.progressDone === Some(5L)
      done.progressTotal === Some(5L)
      ImageImportHandler.stateOf(done).inserted === 5L
    }

    "get images from list, and report the missing ones" in {
      val images = (11 to 15).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      val list = (images.map(_.title) :+ "File:Missing.jpg").mkString(System.lineSeparator)
      val job = importFiles(images, contest, source = "", titles = list, pageSize = Some(2))
      stored(contest) === images
      val done = JobJdbc.findById(job.id).get
      ImageImportHandler.stateOf(done).missingTitles === Seq("File:Missing.jpg")
      ImageImportHandler.stateOf(done).missing === 1L
      ImageImportHandler.paramsOf(done).titleCount === 6
      ImageImportHandler.paramsOf(done).titles must beEmpty // not read back
      ImageImportHandler.titlesOf(done, 4, 10) === Seq(images(4).title, "File:Missing.jpg")
    }

    "add a source's and lists' files to the contest's own category, removing none" in {
      val inSource = (11 to 13).map(id => image(id).copy(monumentId = None))
      val listed = (21 to 22).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      importFiles(inSource, contest)
      stored(contest) === inSource

      importFiles(listed.take(1), contest, titles = listed(0).title)
      importFiles(listed.drop(1), contest, titles = listed(1).title)
      stored(contest) === inSource ++ listed // the lists join the source's files, and each other

      // the source again: its new files are added, the listed ones stay
      val more = inSource :+ image(14).copy(monumentId = None)
      importFiles(more, contest)
      stored(contest) === (more ++ listed).sortBy(_.pageId)

      val updated = contestDao.findById(contest.getId).get
      updated.images === Some(category) // a list doesn't change the source
      updated.categoryId === Some(CategoryJdbc.findOrInsert(ImageService.ownCategory(contest.getId)))
    }

    "not add to another contest with the same source" in {
      val inSource = (11 to 13).map(id => image(id).copy(monumentId = None))
      val listed = image(21).copy(monumentId = None)
      val contest = createContest()
      val other = createContest(id = Some(contestId + 1))
      importFiles(inSource, contest)
      importFiles(inSource, other)
      importFiles(Seq(listed), contest, source = "", titles = listed.title)
      stored(contest) === inSource :+ listed
      stored(other) === inSource
    }

    "start a contest's own category with the files of its shared one" in {
      val images = (11 to 13).map(id => image(id).copy(monumentId = None))
      val listed = image(21).copy(monumentId = None)
      val contest = createContest()
      // as before own categories: the contest's category is its source's, by title
      contestDao.setImagesSource(contest.getId, Some(category))
      val shared = CategoryJdbc.findOrInsert(category)
      imageDao.batchInsert(images)
      db.scalikejdbc.CategoryLinkJdbc.addToCategory(shared, images)
      stored(contest) === images

      importFiles(Seq(listed), contest, titles = listed.title)
      stored(contest) === images :+ listed
      imageDao.findByCategory(shared).map(_.pageId).sorted === images.map(_.pageId) // left as it was
    }

    "fetch a step's files in one request when the account has apihighlimits" in {
      val images = (1 to 120).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      val commons = new FakeCommonsSource(images.map(file), maxIdsPerRequest = 500)
      importFrom(commons, contest, pageSize = None).status === JobStatus.Done
      stored(contest) === images
      commons.listCalls.get === 1
      commons.detailCalls.get === 1
    }

    "fetch 50 files per request without apihighlimits" in {
      val images = (1 to 120).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      val commons = new FakeCommonsSource(images.map(file))
      importFrom(commons, contest, pageSize = None).status === JobStatus.Done
      stored(contest) === images
      commons.listCalls.get === 3
      commons.detailCalls.get === 3
    }

    "split a configured step larger than the account allows into requests it allows" in {
      val images = (1 to 120).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      val commons = new FakeCommonsSource(images.map(file))
      importFrom(commons, contest, pageSize = Some(200)).status === JobStatus.Done
      stored(contest) === images
      commons.listCalls.get === 1
      commons.detailCalls.get === 3
    }

    "refuse a page size that isn't positive" in {
      new ImageImportHandler(new FakeCommonsSource(Nil), play.api.Configuration("jobs.commonsImport.pageSize" -> 0)) must
        throwA[IllegalArgumentException]
    }

    "update images" in {
      def withId(region: String) = (11 to 15).map(id =>
        image(id).copy(description = Some(s"{{$idTemplate|$region-345-$id}}"), monumentId = Some(s"$region-345-$id")))
      val contest = createContest(Some(idTemplate))
      importFiles(withId("12"), contest)
      stored(contest) === withId("12")
      val job = importFiles(withId("22"), contest)
      stored(contest) === withId("22")
      ImageImportHandler.stateOf(JobJdbc.findById(job.id).get) must beLike { case st: ImportState =>
        (st.inserted, st.linked, st.updated) === ((0L, 0L, 5L))
      }
    }

    "restore a file deleted on Commons and undeleted since, with its votes" in {
      implicit val contest: ContestJury = createContest()
      val images = (11 to 13).map(image(_))
      importFiles(images, contest)
      val round = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = true)).getId
      val juror = createUsers("jury", 1).head.getId
      selectionDao.create(11L, rate = 1, juryId = juror, roundId = round)
      DB.localTx(implicit s => DeletedFiles.markDeleted(Seq(11L)))
      stored(contest).map(_.pageId) === Seq(12L, 13L)

      importFiles(images, contest)

      stored(contest).map(_.pageId) === Seq(11L, 12L, 13L)
      selectionDao.byRound(round).map(_.pageId) === Seq(11L)
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM selection_deleted".map(_.int(1)).single()) === Some(0)
    }

    "remember the former title of a file renamed on Commons" in {
      val contest = createContest()
      importFiles(Seq(image(11)), contest)
      importFiles(Seq(image(11).copy(title = "File:Renamed11.jpg")), contest)

      ImageJdbc.findByFilename("Image11.jpg") must beNone
      ImageJdbc.findRenamed("Image11.jpg").map(_.title) must beSome("File:Renamed11.jpg")
    }

    "link another contest's files without updating them" in {
      val images = (11 to 15).map(id => image(id).copy(description = Some(s"{{$idTemplate|12-345-$id}}")))
      val changed = images.map(i => i.copy(description = Some(s"{{$idTemplate|22-345-${i.pageId}}}")))
      val contest1 = createContest(id = Some(contestId + 1), images = Some(category + 1), monumentIdTemplate = Some(idTemplate))
      val contest2 = createContest(id = Some(contestId + 2), images = Some(category + 2), monumentIdTemplate = Some(idTemplate))

      importFiles(images, contest1, source = category + 1)
      val job = importFiles(changed, contest2, source = category + 2)

      stored(contest1) === images
      stored(contest2) === images // linked, not updated: as the whole-contest import did
      ImageImportHandler.stateOf(JobJdbc.findById(job.id).get) must beLike { case st: ImportState =>
        (st.inserted, st.linked, st.updated) === ((0L, 5L, 0L))
      }
    }

    "resume a failed import from its page, without duplicates" in {
      val images = (11 to 17).map(id => image(id).copy(monumentId = None))
      val contest = createContest()
      val commons = new FakeCommonsSource(images.map(file))
      commons.failListCall = 3
      val services = new TestImportServices(commons, pageSize = Some(2))
      val job = services.imageService.importFiles(contest, category, "").toOption.get

      val failed = JobJdbc.findById(job.id).get
      failed.status === JobStatus.Failed
      failed.error must beSome(contain("Commons is down"))
      ImageImportHandler.stateOf(failed).seen === 4L
      stored(contest).size === 4

      services.scheduler.retry(job.id) must beRight
      JobJdbc.findById(job.id).get.status === JobStatus.Done
      stored(contest) === images
      ImageJdbc.findAll().size === images.size
    }

    "set the contest's source" in {
      val contest = createContest(images = None)
      importFiles(Seq(image(11).copy(monumentId = None)), contest)
      contestDao.findById(contest.getId).get.images === Some(category)
    }
  }

  "updateMonuments" should {

    "set the stored files' valid monument ids from their text, where they differ" in {
      // ids as in the lists: dd-ddd-dddd
      val images = (11 to 14).map(id => image(id).copy(description = Some(s"{{$idTemplate|12-345-00$id}}")))
      val contest = createContest(Some(idTemplate))
      importFiles(images, contest)

      val texts = Map(
        11L -> s"{{$idTemplate|22-345-0011}}", // changed
        12L -> s"{{$idTemplate|12-345-0012}}", // the same
        13L -> s"{{$idTemplate|22-345-13}}", // not valid: kept
        14L -> "no template" // kept
      )
      val notStored = image(99).copy(description = Some(s"{{$idTemplate|22-345-0099}}"))
      val commons = new FakeCommonsSource(
        images.map(i => FakeFile(i, Some(texts(i.pageId)))) :+ file(notStored))
      val job = new TestImportServices(commons).imageService.updateMonuments(contest, category).last.toOption.get

      val done = JobJdbc.findById(job.id).get
      done.status === JobStatus.Done
      ImageImportHandler.stateOf(done).monumentIdsChanged === 1L
      stored(contest).map(i => i.pageId -> i.monumentId) === Seq(
        11L -> Some("22-345-0011"), 12L -> Some("12-345-0012"), 13L -> Some("12-345-0013"), 14L -> Some("12-345-0014"))
      ImageJdbc.findById(99L) must beNone
    }

    "do nothing for a contest without a monument id template" in {
      val contest = createContest()
      new TestImportServices(new FakeCommonsSource(Nil)).imageService.updateMonuments(contest, category) must beEmpty
    }
  }

  "importFiles over Commons" should {
    "get international images" in {
      val page = "Commons:Wiki Loves Earth 2019/Winners"
      val contest = createContest()
      val commons = new MwCommonsSource(Global.commons, CommonsLogin.anonymous(Global.commons))
      new TestImportServices(commons).imageService.importFiles(contest, page, "")
      stored(contest).size must be_>=(350)
    }
  }

  private def image(id: Long) =
    Image(
      pageId = id,
      title = s"File:Image$id.jpg",
      url = Some(s"url$id"),
      pageUrl = None,
      width = 640,
      height = 480,
      monumentId = Some(s"12-345-$id"),
      size = Some(1234)
    )

  private def file(i: Image) =
    FakeFile(i.copy(pageUrl = Some(s"pageUrl${i.pageId}")), Some(s"{{Information|description=${i.description.getOrElse("")}}}"))
}
