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

    "upsert the monuments of the contest's lists, then of the special nominations' lists" in {
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
      MonumentListImportHandler.stateOf(job).monuments === 5L

      val parsed = lists.parsed.asScala.toSeq
      parsed must contain(("Castles 1", Some("WLM-рядок")), (libraries, Some("WLM-рядок")))
      parsed.filter(_._2.isEmpty) === Seq("Lists A" -> None, "Lists B" -> None, "Lists C" -> None)
      parsed.indexOf("Lists C" -> None) must beLessThan(parsed.indexOf("Castles 1" -> Some("WLM-рядок")))
    }

    "resume a failed import from its list pages" in {
      val lists = wlmLists(failOn = Some("Lists C"))
      val services = new TestImportServices(new FakeCommonsSource(Nil), lists = lists, listPageSize = 2)
      val id = services.imageService.updateMonuments(contest("Wiki Loves Monuments"), "Category:X").head.toOption.get.id

      val failed = JobJdbc.findById(id).get
      failed.status === JobStatus.Failed
      (failed.failure.map(_.key) must beSome(_root_.services.jobs.JobError.CrashedKey)) and
        (failed.failure.flatMap(_.reference) must beSome) and (failed.error must not(beSome(contain("Wikipedia is down"))))
      name("01-101-0001") === Some("Castle, as in the lists") // no nomination's data while the lists are unfinished
      name("01-101-0002") === Some("Church")
      name("01-101-0003") must beNone
      name("80-361-0001") must beNone

      services.scheduler.retry(id) must beRight
      JobJdbc.findById(id).get.status === JobStatus.Done
      name("01-101-0003") === Some("Bridge")
      name("01-101-0001") === Some("Castle, as in the lists")
      name("80-361-0001") === Some("Kyiv fortress")
      lists.parsed.asScala.count(_._1 == "Lists A") === 1 // not read again
    }

    "store each row's Wikidata item (V102), normalized, and follow the list's changes" in {
      def withWikidata(id: String, value: Option[String]) =
        m(id, s"Monument $id", "Lists A").copy(otherParams = value.map("wikidata" -> _).toMap)
      def run(rows: Seq[Monument]) = {
        val lists = new FakeMonumentListSource(embedding = Map(wlmListTemplate -> Seq("Lists A")),
          monumentsOn = Map("Lists A" -> rows))
        val services = new TestImportServices(new FakeCommonsSource(Nil), lists = lists)
        services.imageService.updateMonuments(contest("Wiki Loves Monuments"), "Category:X")
          .map(r => JobJdbc.findById(r.toOption.get.id).get.status) must contain(be_==(JobStatus.Done)).forall
      }
      def wikidata(id: String) = MonumentJdbc.find(id).flatMap(MonumentJdbc.wikidataOf)

      run(Seq(withWikidata("01-101-0001", Some("Q42")), withWikidata("01-101-0002", Some(" q7 ")),
        withWikidata("01-101-0003", Some("")), withWikidata("01-101-0004", Some("https://www.wikidata.org/wiki/Q5")),
        withWikidata("01-101-0005", Some("Q0")), withWikidata("01-101-0006", None)))
      Seq("01-101-0001", "01-101-0002", "01-101-0003", "01-101-0004", "01-101-0005", "01-101-0006").map(wikidata) ===
        Seq(Some("Q42"), Some("Q7"), None, None, None, None)
      // the column as stored: only the normalized item
      scalikejdbc.DB.readOnly { implicit s =>
        scalikejdbc.SQL("SELECT id, wikidata FROM monument WHERE wikidata IS NOT NULL ORDER BY id")
          .map(rs => rs.string(1) -> rs.string(2)).list()
      } === List("01-101-0001" -> "Q42", "01-101-0002" -> "Q7")

      // imported again: a changed item replaces it, a removed one clears it
      run(Seq(withWikidata("01-101-0001", Some("Q43")), withWikidata("01-101-0002", None)))
      Seq("01-101-0001", "01-101-0002").map(wikidata) === Seq(Some("Q43"), None)
    }
  }

  "a monument list import of a nomination's own ids" should {
    // production's 92-232-4801 (2026-11-26): on the German heritage list of Zaporizhzhia Oblast
    // until 2025-10-29, then on the Bulgarian heritage list, its prefix's (wlm_ua.conf's idPrefix 92)
    val german = "Template:WLM Німецька спадщина"
    val bulgarian = "Template:WLM Болгарська спадщина"
    val germanPage = "Вікіпедія:Вікі любить пам'ятки/Німецька спадщина/Запорізька область"
    val bulgarianPage = "Вікіпедія:Вікі любить пам'ятки/Болгарська спадщина/Запорізька область"
    def page(id: String) = MonumentJdbc.find(id).map(_.page)

    def run(embedding: Map[String, Seq[String]], monumentsOn: Map[String, Seq[Monument]]) = {
      val lists = new FakeMonumentListSource(embedding = Map(wlmListTemplate -> Seq("Lists A")) ++ embedding,
        monumentsOn = monumentsOn)
      val services = new TestImportServices(new FakeCommonsSource(Nil), lists = lists)
      services.imageService.updateMonuments(contest("Wiki Loves Monuments", 2026), "Category:X")
        .map(r => JobJdbc.findById(r.toOption.get.id).get.status) must contain(be_==(JobStatus.Done)).forall
    }

    "store them from their own nomination's list, also when stored from another one before" in {
      // as a 2025 import stored them: from the German heritage list
      MonumentJdbc.batchInsert(Seq(m("92-232-4801", "Стара церква (old row)", germanPage),
        m("94-232-0001", "Кірха", germanPage), m("23-248-0004", "Церква", "Lists A")))

      run(Map(german -> Seq(germanPage), bulgarian -> Seq(bulgarianPage)), Map(
        "Lists A" -> Seq(m("23-248-0004", "Церква", "Lists A")),
        germanPage -> Seq(m("94-232-0001", "Кірха", germanPage)),
        bulgarianPage -> Seq(m("92-232-4801", "Стара церква", bulgarianPage),
          m("23-248-0004", "Церква, as in the nomination", bulgarianPage))))

      page("92-232-4801") === Some(bulgarianPage)
      name("92-232-4801") === Some("Стара церква")
      page("94-232-0001") === Some(germanPage)
      // a regional id keeps the contest list's data
      page("23-248-0004") === Some("Lists A")
      name("23-248-0004") === Some("Церква")
    }

    "keep them from their own list when another nomination's list has them too, whichever is read last" in {
      run(Map(german -> Seq(germanPage), bulgarian -> Seq(bulgarianPage)), Map(
        germanPage -> Seq(m("92-232-4801", "Стара церква, as on the German list", germanPage)),
        bulgarianPage -> Seq(m("92-232-4801", "Стара церква", bulgarianPage))))
      page("92-232-4801") === Some(bulgarianPage)

      // imported again: the German list, read before the Bulgarian one, doesn't take it over
      run(Map(german -> Seq(germanPage)), Map(
        germanPage -> Seq(m("92-232-4801", "Стара церква, as on the German list", germanPage))))
      page("92-232-4801") === Some(bulgarianPage)
      name("92-232-4801") === Some("Стара церква")
    }
  }

  "a monument list import's job" should {
    val handler = new TestImportServices(new FakeCommonsSource(Nil)).monumentLists

    "read only the contest's lists for WLE" in {
      val job = handler.newJob(contest("Wiki Loves Earth"), None).get
      job.params.as[MonumentImportParams].sources === Seq(ListSource("Template:ВЛЗ-рядок"))
    }

    "give each nomination's lists the id prefix of its own monuments" in {
      val sources = handler.newJob(contest("Wiki Loves Monuments", 2026), None).get.params.as[MonumentImportParams].sources
      sources must contain(ListSource("Template:WLM Болгарська спадщина", Some("WLM-рядок"), Some("92")))
      sources must contain(ListSource("Template:WLM Німецька спадщина", Some("WLM-рядок"), Some("94")))
      sources.head === ListSource("Template:ВЛП-рядок")
      // a job created before the prefix was stored reads as before: no home lists
      play.api.libs.json.Json.obj("page" -> "Template:WLM Болгарська спадщина", "listTemplate" -> "WLM-рядок")
        .as[ListSource].idPrefix must beNone
    }

    "be one at a time for the contests sharing the lists" in {
      handler.newJob(contest("Wiki Loves Monuments", 2025), None).get.dedupeKey ===
        handler.newJob(contest("Wiki Loves Monuments", 2026), None).get.dedupeKey
    }

    "not exist for a contest without Ukrainian lists" in {
      handler.newJob(contest("Wiki Loves Monuments", country = "Armenia"), None) must beNone
      handler.newJob(contest("Wiki Loves Folklore", template = None), None) must beNone
      handler.newJob(contest("Wiki Loves Folklore"), None) must beNone
    }

    "resolve the lists by the country code and prefer an explicit campaign" in {
      val c = contest("Wiki Loves Monuments", country = "ua", template = None)
      MonumentListImportHandler.listsOf(c) must beSome("wlm_ua")
      MonumentListImportHandler.listsOf(c.copy(campaign = Some("wle-UA"))) must beSome("wle_ua")
      MonumentListImportHandler.listsOf(c.copy(campaign = Some("wlm-de"))) must beNone
      MonumentListImportHandler.listsOf(c.copy(name = "Custom", campaign = Some("wle-UA"))) must beSome("wle_ua")
    }

    "exist for a Ukrainian contest without a template of its own: its campaign config has one" in {
      MonumentListImportHandler.listsOf(contest("Wiki Loves Monuments", template = None)) must beSome("wlm_ua")
      MonumentListImportHandler.listsOf(contest("Wiki Loves Earth", template = None)) must beSome("wle_ua")
      handler.newJob(contest("Wiki Loves Monuments", template = None), None) must beSome
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
