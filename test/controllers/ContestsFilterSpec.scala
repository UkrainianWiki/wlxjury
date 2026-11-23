package controllers

import db.scalikejdbc.{PlayTestDb, User}
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc.GlobalSettings

import java.time.Year
import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The contests page's Type, Country, Year and Order menus (the maintainer's request of
  * 2026-11-19, [[ContestsFilter]]): the contests each choice lists, alone and together, in which
  * order; the default year, the latest of the contests' and not the calendar's; all years;
  * International; the old type-and-year links; the counts; each menu's links keeping the others;
  * the page's statements not growing with the contests; and the page still root's only.
  */
class ContestsFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val WLM = "Wiki Loves Monuments"
  private val WLE = "Wiki Loves Earth"

  private case class Fixture(
      wlmUa2023: ContestJury,
      wlmUa2024: ContestJury,
      wlmUa2025: ContestJury,
      wleUa2025: ContestJury,
      wleAm2025: ContestJury,
      wleIntl2025: ContestJury,
      root: User
  )

  /** Contests of 2023-2025 (none of the calendar year, 2026), added out of year order: WLM
    * Ukraine 2025 before 2024. WLE "International": of no one country.
    */
  private def fixture(): Fixture = {
    val wlmUa2023 = contestDao.create(None, WLM, 2023, "Ukraine")
    val wlmUa2025 = contestDao.create(None, WLM, 2025, "Ukraine")
    val wlmUa2024 = contestDao.create(None, WLM, 2024, "Ukraine")
    val wleUa2025 = contestDao.create(None, WLE, 2025, "Ukraine")
    val wleAm2025 = contestDao.create(None, WLE, 2025, "Armenia")
    val wleIntl2025 = contestDao.create(None, WLE, 2025, "International")
    val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    Fixture(wlmUa2023, wlmUa2024, wlmUa2025, wleUa2025, wleAm2025, wleIntl2025, root)
  }

  private def get(app: Application, viewer: Option[User], path: String): Future[Result] = {
    val base = FakeRequest(GET, path)
    route(app, viewer.fold(base)(u => base.withSession(Secured.UserName -> u.email)).withCSRFToken).get
  }

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, Some(viewer), path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** The list's contests, as "name year country", in order. */
  private def rows(doc: Document): Seq[String] =
    doc.select("table.wlx-contests-table tr.contest-row").asScala.toSeq
      .map(_.select("> td").asScala.take(3).map(_.text.trim).mkString(" "))

  private def row(c: ContestJury) = s"${c.name} ${c.country} ${c.year}"

  private def toggle(doc: Document, menu: String): String = doc.select(s"#$menu > button").text.trim

  /** A menu's items: text (without its count) -> count, in order. */
  private def items(doc: Document, menu: String): Seq[(String, Option[Int])] =
    doc.select(s"#$menu .dropdown-item").asScala.toSeq.map { a =>
      val count = Option(a.selectFirst(".wlx-menu-count")).map(_.text.trim.replaceAll("\\D", "").toInt)
      val text = Option(a.selectFirst("span:not(.wlx-menu-count)")).fold(a.text)(_.text).trim
      text -> count
    }

  private def href(doc: Document, menu: String, item: String): String =
    doc.select(s"#$menu .dropdown-item").asScala.find { a =>
      Option(a.selectFirst("span:not(.wlx-menu-count)")).fold(a.text)(_.text).trim == item
    }.map(_.attr("href")).getOrElse(sys.error(s"no $item in $menu"))

  private def active(doc: Document, menu: String): String =
    Option(doc.selectFirst(s"#$menu .dropdown-item.active[aria-current=page]"))
      .map(a => Option(a.selectFirst("span:not(.wlx-menu-count)")).fold(a.text)(_.text).trim).getOrElse("")

  "the contests page's menus" should {

    "list the latest year of the contests by default, not the calendar year, oldest first" in {
      testDbApp { implicit app =>
        val f = fixture()
        Year.now.getValue must be_>(2025)
        val doc = page(app, f.root, "/contests")
        toggle(doc, "contests-year-menu") === "Year: 2025"
        active(doc, "contests-year-menu") === "2025"
        toggle(doc, "contests-type-menu") === "Type: All"
        toggle(doc, "contests-country-menu") === "Country: All countries"
        toggle(doc, "contests-order-menu") === "Order: Oldest first"
        rows(doc) === Seq(f.wlmUa2025, f.wleUa2025, f.wleAm2025, f.wleIntl2025).map(row)
        // in the navbar's filter and order groups
        doc.select("ul.wlx-nav-filters #contests-type-menu, ul.wlx-nav-filters #contests-country-menu, " +
          "ul.wlx-nav-filters #contests-year-menu").size === 3
        doc.select("ul.wlx-nav-order #contests-order-menu").size === 1
        // the country menu scrolls inside it, as the galleries' region menu
        doc.select("#contests-country-menu ul.dropdown-menu.region-menu-list").size === 1
      }
    }

    "count each choice within the other menus' choices" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, "/contests")
        items(doc, "contests-type-menu") === Seq("All" -> Some(4), WLE -> Some(3), WLM -> Some(1))
        items(doc, "contests-country-menu") ===
          Seq("All countries" -> Some(4), "International" -> Some(1), "Armenia" -> Some(1), "Ukraine" -> Some(2))
        items(doc, "contests-year-menu") ===
          Seq("All years" -> Some(6), "2025" -> Some(4), "2024" -> Some(1), "2023" -> Some(1))
        items(doc, "contests-order-menu") === Seq("Oldest first" -> None, "Newest first" -> None, "By country" -> None)

        val wlm = page(app, f.root, "/contests?contestType=Wiki+Loves+Monuments&year=all")
        items(wlm, "contests-year-menu") ===
          Seq("All years" -> Some(3), "2025" -> Some(1), "2024" -> Some(1), "2023" -> Some(1))
        items(wlm, "contests-type-menu") === Seq("All" -> Some(6), WLE -> Some(3), WLM -> Some(3))
        // International and Armenia, with no WLM contest, left out
        items(wlm, "contests-country-menu") === Seq("All countries" -> Some(3), "Ukraine" -> Some(3))
      }
    }

    "keep the chosen item and All in a menu, also at 0, and leave out the other items of 0" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, "/contests?contestType=Wiki+Loves+Monuments&country=Armenia&year=all")
        rows(doc) === Nil
        items(doc, "contests-country-menu") === Seq("All countries" -> Some(3), "Armenia" -> Some(0), "Ukraine" -> Some(3))
        active(doc, "contests-country-menu") === "Armenia"
        toggle(doc, "contests-country-menu") === "Country: Armenia"
        items(doc, "contests-type-menu") === Seq("All" -> Some(1), WLE -> Some(1), WLM -> Some(0))
        active(doc, "contests-type-menu") === WLM
        items(doc, "contests-year-menu") === Seq("All years" -> Some(0))
        active(doc, "contests-year-menu") === "All years"
        // the order menu has no counts: all its items
        items(doc, "contests-order-menu").size === 3
      }
    }

    "filter by each menu alone" in {
      testDbApp { implicit app =>
        val f = fixture()
        rows(page(app, f.root, "/contests?contestType=Wiki+Loves+Earth")) ===
          Seq(f.wleUa2025, f.wleAm2025, f.wleIntl2025).map(row)
        rows(page(app, f.root, "/contests?country=Ukraine")) === Seq(f.wlmUa2025, f.wleUa2025).map(row)
        rows(page(app, f.root, "/contests?country=ukraine")) === Seq(f.wlmUa2025, f.wleUa2025).map(row)
        rows(page(app, f.root, "/contests?year=2024")) === Seq(row(f.wlmUa2024))
        rows(page(app, f.root, "/contests?order=newest")) === Seq(f.wleIntl2025, f.wleAm2025, f.wleUa2025, f.wlmUa2025).map(row)
      }
    }

    "list the international contests under International, not under a country" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, "/contests?country=international")
        rows(doc) === Seq(row(f.wleIntl2025))
        toggle(doc, "contests-country-menu") === "Country: International"
        active(doc, "contests-country-menu") === "International"
        items(doc, "contests-country-menu").map(_._1).count(_ == "International") === 1
        rows(page(app, f.root, "/contests?country=Armenia")) === Seq(row(f.wleAm2025))
      }
    }

    "list all years with year=all, in either order" in {
      testDbApp { implicit app =>
        val f = fixture()
        val all = page(app, f.root, "/contests?year=all")
        toggle(all, "contests-year-menu") === "Year: All years"
        rows(all) === Seq(f.wlmUa2023, f.wlmUa2024, f.wlmUa2025, f.wleUa2025, f.wleAm2025, f.wleIntl2025).map(row)
        val newest = page(app, f.root, "/contests?year=all&order=newest")
        toggle(newest, "contests-order-menu") === "Order: Newest first"
        rows(newest) === Seq(f.wleIntl2025, f.wleAm2025, f.wleUa2025, f.wlmUa2025, f.wlmUa2024, f.wlmUa2023).map(row)
      }
    }

    "order by country: the Country menu's names in the page's language, International last, by year and id in a country" in {
      testDbApp { implicit app =>
        val f = fixture()
        val wleUa2024 = contestDao.create(None, WLE, 2024, "UA") // Ukraine's other spelling, added last
        val wleSe2025 = contestDao.create(None, WLE, 2025, "Sweden") // Швеція: after Україна in Ukrainian
        val photo2023 = contestDao.create(None, "Photo contest", 2023, "") // International
        val odd2025 = contestDao.create(None, WLM, 2025, "Ukriane") // a text the tool doesn't know
        def ukPage(path: String) = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, path)
          .withSession(Secured.UserName -> f.root.email).withHeaders("Accept-Language" -> "uk").withCSRFToken).get))
        val ukraine = Seq(f.wlmUa2023, f.wlmUa2024, wleUa2024, f.wlmUa2025, f.wleUa2025)
        val international = Seq(photo2023, f.wleIntl2025)
        val en = page(app, f.root, "/contests?year=all&order=country")
        toggle(en, "contests-order-menu") === "Order: By country"
        items(en, "contests-order-menu").map(_._1) === Seq("Oldest first", "Newest first", "By country")
        rows(en) === (Seq(f.wleAm2025, wleSe2025) ++ ukraine ++ Seq(odd2025) ++ international).map(row)
        val uk = ukPage("/contests?year=all&order=country")
        toggle(uk, "contests-order-menu") === "Порядок: За країною"
        // Вірменія, Україна, Швеція; the Latin "Ukriane" by its text among them; International last
        val ukRows = rows(uk)
        ukRows.filterNot(_ == row(odd2025)) === (Seq(f.wleAm2025) ++ ukraine ++ Seq(wleSe2025) ++ international).map(row)
        ukRows.takeRight(2) === international.map(row)
        // the other menus' links keep it; the default stays oldest first
        href(en, "contests-year-menu", "2025") === "/contests?year=2025&order=country"
        toggle(page(app, f.root, "/contests"), "contests-order-menu") === "Order: Oldest first"
        // carried through Add
        val added = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody("name" -> WLM, "country" -> "Armenia", "year" -> "2019", "useGreeting" -> "true",
            "list.order" -> "country")
          .withCSRFToken).get
        redirectLocation(added) must beSome("/contests?year=2019&order=country")
      }
    }

    "combine the menus" in {
      testDbApp { implicit app =>
        val f = fixture()
        rows(page(app, f.root, "/contests?contestType=Wiki+Loves+Monuments&country=Ukraine&year=all")) ===
          Seq(f.wlmUa2023, f.wlmUa2024, f.wlmUa2025).map(row)
        rows(page(app, f.root, "/contests?contestType=Wiki+Loves+Monuments&country=Ukraine&year=all&order=newest")) ===
          Seq(f.wlmUa2025, f.wlmUa2024, f.wlmUa2023).map(row)
        rows(page(app, f.root, "/contests?contestType=Wiki+Loves+Earth&country=Ukraine")) === Seq(row(f.wleUa2025))
        rows(page(app, f.root, "/contests?contestType=Wiki+Loves+Earth&country=international&year=2025")) ===
          Seq(row(f.wleIntl2025))
      }
    }

    "keep the other menus' choices in each menu's links, the defaults left out" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, "/contests?contestType=Wiki+Loves+Monuments&country=Ukraine&year=all&order=newest")
        href(doc, "contests-year-menu", "2024") ===
          "/contests?contestType=Wiki+Loves+Monuments&country=ua&year=2024&order=newest"
        href(doc, "contests-type-menu", "All") === "/contests?country=ua&year=all&order=newest"
        // International has no WLM contest (left out there): from WLE's, where it has one
        href(page(app, f.root, "/contests?contestType=Wiki+Loves+Earth&country=Ukraine&year=all&order=newest"),
          "contests-country-menu", "International") ===
          "/contests?contestType=Wiki+Loves+Earth&country=international&year=all&order=newest"
        href(doc, "contests-order-menu", "Oldest first") === "/contests?contestType=Wiki+Loves+Monuments&country=ua&year=all"
        // from the default page: the default year stays implicit
        val plain = page(app, f.root, "/contests")
        href(plain, "contests-type-menu", WLE) === "/contests?contestType=Wiki+Loves+Earth"
        href(plain, "contests-year-menu", "All years") === "/contests?year=all"
        // each link opens what its count says
        rows(page(app, f.root, href(plain, "contests-type-menu", WLE))).size === 3
      }
    }

    "open the old type-and-year links as before" in {
      testDbApp { implicit app =>
        val f = fixture()
        rows(page(app, f.root, "/contests?contestType=Wiki%20Loves%20Monuments&year=2024")) === Seq(row(f.wlmUa2024))
        rows(page(app, f.root, "/contests?year=2023")) === Seq(row(f.wlmUa2023))
      }
    }

    "take an unknown year or order for the default, and say when nothing matches" in {
      testDbApp { implicit app =>
        val f = fixture()
        val odd = page(app, f.root, "/contests?year=last&order=sideways")
        toggle(odd, "contests-year-menu") === "Year: 2025"
        toggle(odd, "contests-order-menu") === "Order: Oldest first"
        rows(odd).size === 4

        val none = page(app, f.root, "/contests?contestType=Wiki+Loves+Folklore&order=newest")
        rows(none) must beEmpty
        toggle(none, "contests-type-menu") === "Type: Wiki Loves Folklore"
        none.select("#contests-filter-none").text must contain("No contests match these choices.")
        none.select("#contests-filter-reset").attr("href") === "/contests?year=all&order=newest"
      }
    }

    "list nothing and no menu items but All for no contests" in {
      testDbApp { implicit app =>
        val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
        val doc = page(app, root, "/contests")
        rows(doc) must beEmpty
        doc.select("#contests-filter-none").size === 0
        items(doc, "contests-year-menu") === Seq("All years" -> Some(0))
      }
    }

    "open the new contest's year after Add" in {
      testDbApp { implicit app =>
        val f = fixture()
        val saved = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody("name" -> WLM, "country" -> "Armenia", "year" -> "2020", "useGreeting" -> "true")
          .withCSRFToken).get
        status(saved) === SEE_OTHER
        redirectLocation(saved) must beSome("/contests?year=2020")
        rows(page(app, f.root, "/contests?year=2020")) === Seq(s"$WLM Armenia 2020")
      }
    }

    "carry the list's type, country and order through Add, dropping those the new contest isn't of" in {
      testDbApp { implicit app =>
        val f = fixture()
        val shown = page(app, f.root, "/contests?contestType=Wiki+Loves+Earth&country=Ukraine&year=all&order=newest")
        shown.select("input[type=hidden][name^=list.]").asScala.map(i => i.attr("name") -> i.attr("value")).toSet ===
          Set("list.contestType" -> WLE, "list.country" -> "ua", "list.order" -> "newest")
        def add(fields: (String, String)*) = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody(fields ++ Seq("useGreeting" -> "true"): _*)
          .withCSRFToken).get
        val carried = Seq("list.contestType" -> WLE, "list.country" -> "ua", "list.order" -> "newest")
        val same = add(Seq("name" -> WLE, "country" -> "UA", "year" -> "2019") ++ carried: _*)
        redirectLocation(same) must beSome("/contests?contestType=Wiki+Loves+Earth&country=ua&year=2019&order=newest")
        rows(page(app, f.root, redirectLocation(same).get)) === Seq(s"$WLE UA 2019")
        val other = add(Seq("name" -> WLM, "country" -> "Armenia", "year" -> "2019") ++ carried: _*)
        redirectLocation(other) must beSome("/contests?year=2019&order=newest")
        // carried values are parsed as the params are: an unknown order is the default, no year carried
        val odd = add("name" -> WLM, "country" -> "Armenia", "year" -> "2018", "list.order" -> "sideways", "list.year" -> "all")
        redirectLocation(odd) must beSome("/contests?year=2018")
      }
    }

    "keep the menus and the carried list on Add's error page" in {
      testDbApp { implicit app =>
        val f = fixture()
        val result = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody("name" -> "", "country" -> "Armenia", "year" -> "2019", "useGreeting" -> "true",
            "list.contestType" -> WLE, "list.order" -> "newest")
          .withCSRFToken).get
        status(result) === BAD_REQUEST
        val doc = Jsoup.parse(contentAsString(result))
        toggle(doc, "contests-type-menu") === s"Type: $WLE"
        toggle(doc, "contests-order-menu") === "Order: Newest first"
        toggle(doc, "contests-year-menu") === "Year: 2025"
        rows(doc) === Seq(f.wleIntl2025, f.wleAm2025, f.wleUa2025).map(row)
        // the carried list rendered again in the add form
        doc.select("input[type=hidden][name^=list.]").asScala.map(i => i.attr("name") -> i.attr("value")).toSet ===
          Set("list.contestType" -> WLE, "list.order" -> "newest")
      }
    }

    "echo a carried country escaped on Add's error page" in {
      testDbApp { implicit app =>
        val f = fixture()
        val evil = "\"><script>alert(1)</script>"
        val result = route(app, FakeRequest(POST, "/contests/save")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody("name" -> "", "country" -> "Armenia", "year" -> "2019", "useGreeting" -> "true",
            "list.country" -> evil)
          .withCSRFToken).get
        status(result) === BAD_REQUEST
        val html = contentAsString(result)
        html must not contain evil
        html must not contain "<script>alert(1)"
        val doc = Jsoup.parse(html)
        doc.select("input[type=hidden][name=list.country]").attr("value") === evil
        doc.select("script").asScala.exists(_.data.contains("alert(1)")) must beFalse
        rows(doc) must beEmpty
      }
    }

    "open the imported contests' year after Import, or all years if they are of several" in {
      val imported = new java.util.concurrent.atomic.AtomicReference[Seq[ContestJury]](Nil)
      val stub = new services.ContestService(null) {
        override def importContests(formContest: String, actorId: Option[Long]): Iterable[ContestJury] =
          imported.get.map(c => contestDao.create(None, c.name, c.year, c.country))
      }
      testDbAppWith(play.api.inject.bind[services.ContestService].toInstance(stub)) { implicit app =>
        val f = fixture()
        def run() = route(app, FakeRequest(POST, "/contests/import")
          .withSession(Secured.UserName -> f.root.email)
          .withFormUrlEncodedBody("source" -> "Category:Images from Wiki Loves Earth 2016")
          .withCSRFToken).get
        imported.set(Seq(ContestJury(None, WLE, 2016, "Armenia", None), ContestJury(None, WLE, 2016, "Ukraine", None)))
        val one = run()
        status(one) === SEE_OTHER
        redirectLocation(one) must beSome("/contests?year=2016")
        rows(page(app, f.root, "/contests?year=2016")) === Seq(s"$WLE Armenia 2016", s"$WLE Ukraine 2016")
        imported.set(Seq(ContestJury(None, WLE, 2014, "Armenia", None), ContestJury(None, WLE, 2015, "Armenia", None)))
        redirectLocation(run()) must beSome("/contests?year=all")
        imported.set(Nil) // all of them already there
        redirectLocation(run()) must beSome("/contests?year=all")
      }
    }

    "list a country text scalawiki doesn't know under its own name, and one country's spellings as one" in {
      testDbApp { implicit app =>
        val f = fixture()
        contestDao.create(None, WLE, 2025, "UA")
        contestDao.create(None, WLM, 2025, "Ukriane")
        contestDao.create(None, WLM, 2025, "Ukraine, Animals")
        contestDao.create(None, "Photo contest", 2025, "")
        val doc = page(app, f.root, "/contests")
        items(doc, "contests-country-menu") === Seq("All countries" -> Some(8), "International" -> Some(2),
          "Armenia" -> Some(1), "Ukraine" -> Some(3), "Ukraine, Animals" -> Some(1), "Ukriane" -> Some(1))
        href(doc, "contests-country-menu", "Ukraine") === "/contests?country=ua"
        href(doc, "contests-country-menu", "Ukraine, Animals") === "/contests?country=Ukraine%2C+Animals"
        rows(page(app, f.root, "/contests?country=ua")) === Seq(row(f.wlmUa2025), row(f.wleUa2025), s"$WLE UA 2025")
        rows(page(app, f.root, "/contests?country=Ukraine")).size === 3
        rows(page(app, f.root, "/contests?country=ukriane")) === Seq(s"$WLM Ukriane 2025")
        toggle(page(app, f.root, "/contests?country=ukriane"), "contests-country-menu") === "Country: Ukriane"
        rows(page(app, f.root, "/contests?country=Ukraine%2C+Animals")) === Seq(s"$WLM Ukraine, Animals 2025")
        rows(page(app, f.root, "/contests?country=international")) === Seq(row(f.wleIntl2025), "Photo contest  2025")
        // a known country's name in the page's language
        val uk = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, "/contests?country=ua")
          .withSession(Secured.UserName -> f.root.email).withHeaders("Accept-Language" -> "uk").withCSRFToken).get))
        toggle(uk, "contests-country-menu") must endWith("Україна")
      }
    }

    "place a known campaign by its country, International in any case and spacing, other texts by their words, a name in another language with its country" in {
      testDbApp { implicit app =>
        val f = fixture()
        contestDao.create(None, "Custom", 2025, "Whatever", campaign = Some("wle-UA")) // the campaign's country wins
        contestDao.create(None, WLM, 2025, "Україна")
        contestDao.create(None, WLM, 2025, "Украина")
        contestDao.create(None, "Photo contest", 2025, "INTERNATIONAL")
        contestDao.create(None, "Photo contest", 2025, " international ")
        contestDao.create(None, WLM, 2025, "Ukraine, Animals")
        contestDao.create(None, WLM, 2025, "ukraine,  animals")
        contestDao.create(None, WLM, 2025, " Ukraine,\u00A0Animals ")
        contestDao.create(None, WLM, 2025, "Ireland") // Ірландія in Ukrainian: І after У by code point
        val doc = page(app, f.root, "/contests")
        items(doc, "contests-country-menu") === Seq("All countries" -> Some(13), "International" -> Some(3),
          "Armenia" -> Some(1), "Ireland" -> Some(1), "Ukraine" -> Some(5), "Ukraine, Animals" -> Some(3))
        rows(page(app, f.root, "/contests?country=ua")) ===
          Seq(row(f.wlmUa2025), row(f.wleUa2025), "Custom Whatever 2025", s"$WLM Україна 2025", s"$WLM Украина 2025")
        rows(page(app, f.root, "/contests?country=%D0%A3%D0%BA%D1%80%D0%B0%D1%97%D0%BD%D0%B0")).size === 5
        rows(page(app, f.root, "/contests?country=+INTERNATIONAL+")).size === 3
        rows(page(app, f.root, "/contests?country=UKRAINE,%C2%A0%C2%A0animals")).size === 3
        // the page language's alphabetical order
        val uk = Jsoup.parse(contentAsString(route(app, FakeRequest(GET, "/contests")
          .withSession(Secured.UserName -> f.root.email).withHeaders("Accept-Language" -> "uk").withCSRFToken).get))
        val ukNames = items(uk, "contests-country-menu").map(_._1)
        ukNames must containAllOf(Seq("Вірменія", "Ірландія", "Україна")).inOrder
      }
    }

    "run as many statements for many contests as for a few, in a bounded time" in {
      testDbApp { implicit app =>
        val f = fixture()
        def statements(path: String): (Seq[String], Long) = {
          val seen = mutable.Buffer.empty[String]
          val previous = GlobalSettings.queryCompletionListener
          GlobalSettings.queryCompletionListener = (sql, params, ms) => { seen.synchronized(seen += sql); previous(sql, params, ms) }
          try {
            val start = System.nanoTime()
            val result = get(app, Some(f.root), path)
            status(result) === OK
            contentAsString(result)
            (seen.synchronized(seen.toList), (System.nanoTime() - start) / 1000000)
          } finally GlobalSettings.queryCompletionListener = previous
        }
        statements("/contests") // warm-up: templates, campaign configs
        val (few, _) = statements("/contests")
        val countries = Seq("Ukraine", "Armenia", "Germany", "France", "Poland", "Spain", "Italy", "Sweden",
          "Estonia", "Ghana", "Nepal", "India", "Brazil", "Mexico", "International")
        val more = for {
          year <- 2012 to 2025
          name <- Seq(WLM, WLE, "Wiki Loves Folklore")
          country <- countries
        } yield ContestJury(None, name, year, country, None)
        contestDao.batchInsert(more)
        statements("/contests?year=all") // warm-up of the new campaigns
        val (many, millis) = statements("/contests?year=all")
        val (manyDefault, defaultMillis) = statements("/contests")
        println(s"[ContestsFilterSpec] ${more.size + 6} contests: year=all ${millis} ms, default ${defaultMillis} ms; " +
          s"${many.size} statements (${few.size} for 6)")
        many.size === few.size
        manyDefault.size === few.size
        millis must be_<(5000L)
      }
    }
  }

  "the contests page" should {

    "stay root's only, with the menus' params too" in {
      testDbApp { implicit app =>
        val f = fixture()
        def member(name: String, role: String) =
          userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = f.wlmUa2025.id))
        val people = Seq(member("Admin", "admin"), member("Org", "organizer"), member("Juror", "jury"))
        for (u <- people; path <- Seq("/contests", "/contests?year=all&country=international&order=newest")) {
          val result = get(app, Some(u), path)
          status(result) aka s"${u.fullname} $path" must_== FORBIDDEN
          contentAsString(result) must not contain "contests-type-menu"
        }
        val signedOut = get(app, None, "/contests?year=all")
        status(signedOut) must_== SEE_OTHER
        redirectLocation(signedOut).getOrElse("") must startWith("/auth")
      }
    }
  }
}
