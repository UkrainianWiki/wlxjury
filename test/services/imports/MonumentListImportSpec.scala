package services.imports

import db.scalikejdbc.{MonumentJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestImportServices
import services.jobs.{JobJdbc, JobStatus}

import scala.jdk.CollectionConverters._

/** The lists' half of "Update monuments", as a background job run inline over fake lists. */
class MonumentListImportSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def contest(name: String, year: Int = 2025, country: String = "Ukraine",
      template: Option[String] = Some("Monument Ukraine")): ContestJury =
    contestDao.create(None, name, year, country, monumentIdTemplate = template)

  private def m(id: String, name: String, page: String) = Monument(page = page, id = id, name = name)

  // a WLM special nomination whose pages are a navbar template (wlm_ua.conf), and one
  // whose page is a list itself
  private val castles = "Template:WLM замки і фортеці"
  private val libraries = "Вікіпедія:Вікі любить пам'ятки/Бібліотеки"
  private val wlmListTemplate = "Template:ВЛП-рядок"

  private def wlmLists(failOn: Option[String] = None) = {
    val source = new FakeMonumentListSource(
      embedding = Map(wlmListTemplate -> Seq("Lists A", "Lists B", "Lists C"), castles -> Seq("Castles 1")),
      monumentsOn = Map(
        "Lists A" -> Seq(m("01-101-0001", "Castle, as in the lists", "Lists A")),
        "Lists B" -> Seq(m("01-101-0002", "Church", "Lists B"), m("not-an-id", "Broken row", "Lists B")),
        "Lists C" -> Seq(m("01-101-0003", "Bridge", "Lists C")),
        "Castles 1" -> Seq(m("01-101-0001", "Castle, as in the nomination", "Castles 1"),
          m("80-361-0001", "Kyiv fortress", "Castles 1")),
        libraries -> Seq(m("80-361-0002", "Library", libraries))
      )
    )
    source.failOnPage = failOn
    source
  }

  private def name(id: String) = MonumentJdbc.find(id).map(_.name)

  "a monument list import" should {

    "upsert the monuments of the special nominations' lists, then of the contest's lists" in {
      val lists = wlmLists()
      val services = new TestImportServices(new FakeCommonsSource(Nil), lists = lists, listPageSize = 2)
      val created = services.imageService.updateMonuments(contest("Wiki Loves Monuments"), "Category:X")
      created.size === 2
      val job = JobJdbc.findById(created.head.toOption.get.id).get
      job.kind === MonumentListImportHandler.Kind
      job.status === JobStatus.Done

      name("01-101-0001") === Some("Castle, as in the lists") // the contest's lists win
      name("01-101-0002") === Some("Church")
      name("01-101-0003") === Some("Bridge")
      name("80-361-0001") === Some("Kyiv fortress") // only in a nomination's lists
      name("80-361-0002") === Some("Library")
      MonumentJdbc.find("not-an-id") must beNone
      MonumentListImportHandler.stateOf(job).monuments === 6L

      val parsed = lists.parsed.asScala.toSeq
      parsed must contain(("Castles 1", Some("WLM-рядок")), (libraries, Some("WLM-рядок")))
      parsed.filter(_._2.isEmpty) === Seq("Lists A" -> None, "Lists B" -> None, "Lists C" -> None)
      parsed.indexOf("Castles 1" -> Some("WLM-рядок")) must beLessThan(parsed.indexOf("Lists A" -> None))
    }

    "resume a failed import from its list pages" in {
      val lists = wlmLists(failOn = Some("Lists C"))
      val services = new TestImportServices(new FakeCommonsSource(Nil), lists = lists, listPageSize = 2)
      val id = services.imageService.updateMonuments(contest("Wiki Loves Monuments"), "Category:X").head.toOption.get.id

      val failed = JobJdbc.findById(id).get
      failed.status === JobStatus.Failed
      failed.error must beSome(contain("Wikipedia is down"))
      name("01-101-0002") === Some("Church")
      name("01-101-0003") must beNone

      services.scheduler.retry(id) must beRight
      JobJdbc.findById(id).get.status === JobStatus.Done
      name("01-101-0003") === Some("Bridge")
      lists.parsed.asScala.count(_._1 == "Lists A") === 1 // not read again
    }
  }

  "a monument list import's job" should {
    val handler = new TestImportServices(new FakeCommonsSource(Nil)).monumentLists

    "read only the contest's lists for WLE" in {
      val job = handler.newJob(contest("Wiki Loves Earth"), None).get
      job.params.as[MonumentImportParams].sources === Seq(ListSource("Template:ВЛЗ-рядок"))
    }

    "be one at a time for the contests sharing the lists" in {
      handler.newJob(contest("Wiki Loves Monuments", 2025), None).get.dedupeKey ===
        handler.newJob(contest("Wiki Loves Monuments", 2026), None).get.dedupeKey
    }

    "not exist for a contest without Ukrainian lists" in {
      handler.newJob(contest("Wiki Loves Monuments", country = "Armenia"), None) must beNone
      handler.newJob(contest("Wiki Loves Monuments", template = None), None) must beNone
      handler.newJob(contest("Wiki Loves Folklore"), None) must beNone
    }
  }
}

/** [[MwMonumentListSource]] against the lists on Wikipedia. */
class MwMonumentListSourceSpec extends Specification {

  "the monument lists on Wikipedia" should {
    "be listed a page at a time and parsed" in {
      val source = new MwMonumentListSource
      val wle = MonumentListImportHandler.listContest("wle_ua", 2025)
      val template = MonumentListImportHandler.templateTitle(wle.listTemplate.get)
      val (titles, next) = source.listPages(wle, template, play.api.libs.json.Json.obj(), 3)
      titles.size === 3
      next must beSome
      val (more, _) = source.listPages(wle, template, next.get, 3)
      more.toSet.intersect(titles.toSet) must beEmpty
      val monuments = source.monuments(wle, titles.take(1), None)
      monuments.count(_.id.matches(MonumentListImportHandler.ValidId)) must beGreaterThan(0)
    }
  }
}
