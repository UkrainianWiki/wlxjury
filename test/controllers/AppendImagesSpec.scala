package controllers

import db.scalikejdbc.{ImageJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image, JuryTestHelpers}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestImportServices
import services.imports.{FakeCommonsSource, FakeFile, ImageImportHandler, ImportState, MwCommonsSource}
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
      pageSize: Int = 50): Job = {
    val services = new TestImportServices(new FakeCommonsSource(images.map(file)), pageSize)
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
      val job = importFiles(images, contest, pageSize = 2)
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
      val job = importFiles(images, contest, source = "", titles = list, pageSize = 2)
      stored(contest) === images
      val done = JobJdbc.findById(job.id).get
      ImageImportHandler.stateOf(done).missingTitles === Seq("File:Missing.jpg")
      ImageImportHandler.stateOf(done).missing === 1L
      ImageImportHandler.paramsOf(done).titleCount === 6
      ImageImportHandler.paramsOf(done).titles must beEmpty // not read back
      ImageImportHandler.titlesOf(done, 4, 10) === Seq(images(4).title, "File:Missing.jpg")
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
      val services = new TestImportServices(commons, pageSize = 2)
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
      new TestImportServices(new MwCommonsSource(Global.commons)).imageService.importFiles(contest, page, "")
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
