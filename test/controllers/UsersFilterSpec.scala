package controllers

import db.scalikejdbc.{PlayTestDb, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.collection.mutable
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Root's all-users page's Type, Country, Year and Role menus (the maintainer's decision of
  * 2026-11-19, [[UsersFilter]]): the accounts each choice lists, alone and together, by an active
  * membership in a matching contest with the chosen role in that same contest; every account by
  * default, those without a membership too; the counts; International and a country text the tool
  * doesn't know; each menu's links keeping the others; no menus on a contest's users page; the
  * page's statements not growing with the accounts and contests; and the page still root's only.
  */
class UsersFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val WLM = "Wiki Loves Monuments"
  private val WLE = "Wiki Loves Earth"
  private val Page = "/admin/allUsers"

  private case class Fixture(
      wlmUa2024: ContestJury,
      wlmUa2025: ContestJury,
      wleAm2025: ContestJury,
      wleIntl2025: ContestJury,
      wleAnimals2025: ContestJury,
      root: User
  )

  /** Accounts, in the order created (the page's order):
    *  - Root: root, no membership;
    *  - Ann: admin of WLM Ukraine 2024, juror of WLE Armenia 2025;
    *  - Bob: organizer of WLM Ukraine 2025;
    *  - Cat: juror of WLE International 2025;
    *  - Dan: juror of WLE "Ukraine, Animals" 2025 (a country text the tool doesn't know);
    *  - Eve: no membership;
    *  - Fay: "no role" in WLM Ukraine 2025 and board of WLE Armenia 2025 (inactive memberships);
    *  - Gus: admin of WLE International 2025, juror of WLM Ukraine 2025.
    */
  private def fixture(): Fixture = {
    val wlmUa2024 = contestDao.create(None, WLM, 2024, "Ukraine")
    val wlmUa2025 = contestDao.create(None, WLM, 2025, "Ukraine")
    val wleAm2025 = contestDao.create(None, WLE, 2025, "Armenia")
    val wleIntl2025 = contestDao.create(None, WLE, 2025, "International")
    val wleAnimals2025 = contestDao.create(None, WLE, 2025, "Ukraine, Animals")
    val root = User.create(User("Root", "root@example.com", roles = Set(User.ROOT_ROLE), password = Some("hash")))
    def account(name: String, memberships: (ContestJury, String)*): User = {
      val u = User.create(User(name, s"${name.toLowerCase}@example.com", password = Some("hash")))
      memberships.foreach { case (c, role) => UserContestJdbc.createMembership(u.getId, c.getId, role) }
      u
    }
    account("Ann", wlmUa2024 -> "admin", wleAm2025 -> "jury")
    account("Bob", wlmUa2025 -> "organizer")
    account("Cat", wleIntl2025 -> "jury")
    account("Dan", wleAnimals2025 -> "jury")
    account("Eve")
    account("Fay", wlmUa2025 -> User.NO_ROLE, wleAm2025 -> User.BOARD_ROLE)
    account("Gus", wleIntl2025 -> "admin", wlmUa2025 -> "jury")
    Fixture(wlmUa2024, wlmUa2025, wleAm2025, wleIntl2025, wleAnimals2025, root)
  }

  private val Everyone = Seq("Root", "Ann", "Bob", "Cat", "Dan", "Eve", "Fay", "Gus")

  private def get(app: Application, viewer: Option[User], path: String): Future[Result] = {
    val base = FakeRequest(GET, path)
    route(app, viewer.fold(base)(u => base.withSession(Secured.UserName -> u.email)).withCSRFToken).get
  }

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, Some(viewer), path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** The listed accounts' names, in order. */
  private def names(doc: Document): Seq[String] =
    doc.select("div.table-responsive > table.table-striped > tbody > tr").asScala.toSeq
      .map(_.select("> td").get(1).text.trim)

  private def listed(app: Application, f: Fixture, query: String): Seq[String] =
    names(page(app, f.root, Page + query))

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

  private val Menus = Seq("users-type-menu", "users-country-menu", "users-year-menu", "users-role-menu")

  "root's all-users page [R-03]" should {

    "list every account by default, those without a membership too, in the page's order, all menus on All" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, Page)
        names(doc) === Everyone
        toggle(doc, "users-type-menu") === "Type: All"
        toggle(doc, "users-country-menu") === "Country: All countries"
        toggle(doc, "users-year-menu") === "Year: All years"
        toggle(doc, "users-role-menu") === "Role: All"
        active(doc, "users-year-menu") === "All years"
        // in the navbar's filter group; the contest grid gone, the duplicates report kept
        doc.select(Menus.map(m => s"ul.wlx-nav-filters #$m").mkString(", ")).size === 4
        doc.select("a[href^=/contests?]").size === 0
        doc.select("a.duplicates-link").attr("href") must not(beEmpty)
        doc.select("#users-filter-none").size === 0
        // an unknown year or role is the default
        listed(app, f, "?year=last&role=root") === Everyone
        listed(app, f, "?year=all") === Everyone
      }
    }

    "count each choice's accounts within the other menus' choices" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, Page)
        items(doc, "users-type-menu") === Seq("All" -> Some(8), WLE -> Some(4), WLM -> Some(3))
        items(doc, "users-country-menu") === Seq("All countries" -> Some(8), "International" -> Some(2),
          "Armenia" -> Some(1), "Ukraine" -> Some(3), "Ukraine, Animals" -> Some(1))
        items(doc, "users-year-menu") === Seq("All years" -> Some(8), "2025" -> Some(5), "2024" -> Some(1))
        items(doc, "users-role-menu") ===
          Seq("All" -> Some(8), "Admin" -> Some(2), "Organizer" -> Some(1), "Juror" -> Some(4))

        val chosen = page(app, f.root, s"$Page?year=2025&role=admin")
        items(chosen, "users-type-menu") === Seq("All" -> Some(1), WLE -> Some(1))
        items(chosen, "users-year-menu") === Seq("All years" -> Some(2), "2025" -> Some(1), "2024" -> Some(1))
        items(chosen, "users-role-menu") ===
          Seq("All" -> Some(5), "Admin" -> Some(1), "Organizer" -> Some(1), "Juror" -> Some(4))
        // the items of 0 left out
        items(chosen, "users-country-menu") === Seq("All countries" -> Some(1), "International" -> Some(1))

        // each item's link lists what its count says
        for (menu <- Menus; doc <- Seq(doc, chosen); (item, count) <- items(doc, menu))
          names(page(app, f.root, href(doc, menu, item))).size aka s"$menu $item" must_== count.get
        ok
      }
    }

    "filter by each menu alone" in {
      testDbApp { implicit app =>
        val f = fixture()
        listed(app, f, "?contestType=Wiki+Loves+Earth") === Seq("Ann", "Cat", "Dan", "Gus")
        listed(app, f, "?contestType=Wiki+Loves+Monuments") === Seq("Ann", "Bob", "Gus")
        listed(app, f, "?country=ua") === Seq("Ann", "Bob", "Gus")
        listed(app, f, "?country=Armenia") === Seq("Ann")
        listed(app, f, "?year=2024") === Seq("Ann")
        listed(app, f, "?year=2025") === Seq("Ann", "Bob", "Cat", "Dan", "Gus")
        listed(app, f, "?role=admin") === Seq("Ann", "Gus")
        listed(app, f, "?role=organizer") === Seq("Bob")
        listed(app, f, "?role=jury") === Seq("Ann", "Cat", "Dan", "Gus")
        // the toggles name the choice
        val doc = page(app, f.root, s"$Page?role=jury")
        toggle(doc, "users-role-menu") === "Role: Juror"
        active(doc, "users-role-menu") === "Juror"
      }
    }

    "filter by the menus together, the role in the same contest as the other choices" in {
      testDbApp { implicit app =>
        val f = fixture()
        // Ann is an admin in 2024 and a juror in 2025: not an admin of a 2025 contest
        listed(app, f, "?year=2025&role=admin") === Seq("Gus")
        listed(app, f, "?year=2024&role=admin") === Seq("Ann")
        listed(app, f, "?year=2025&role=jury") === Seq("Ann", "Cat", "Dan", "Gus")
        listed(app, f, "?contestType=Wiki+Loves+Monuments&role=jury") === Seq("Gus")
        listed(app, f, "?contestType=Wiki+Loves+Monuments&role=admin") === Seq("Ann")
        listed(app, f, "?contestType=Wiki+Loves+Earth&country=am&role=admin") must beEmpty
        listed(app, f, "?contestType=Wiki+Loves+Monuments&country=ua&year=2025") === Seq("Bob", "Gus")
        listed(app, f, "?contestType=Wiki+Loves+Monuments&country=ua&year=2025&role=organizer") === Seq("Bob")
        listed(app, f, "?country=international&role=jury") === Seq("Cat")
        // inactive memberships ("no role", board) never match: Fay only on the default page
        listed(app, f, "?contestType=Wiki+Loves+Monuments&country=ua&year=2025").contains("Fay") must beFalse
        listed(app, f, "?country=Armenia") === Seq("Ann")
      }
    }

    "count no Audit committee or Staff membership, list a root by its memberships, and no deleted account" in {
      testDbApp { implicit app =>
        val f = fixture()
        def account(name: String, roles: Set[String], memberships: (ContestJury, String)*): User = {
          val u = User.create(User(name, s"${name.toLowerCase}@example.com", roles = roles, password = Some("hash")))
          memberships.foreach { case (c, role) => UserContestJdbc.createMembership(u.getId, c.getId, role) }
          u
        }
        account("Hal", Set.empty, f.wlmUa2025 -> User.AUDITOR_ROLE)
        account("Ida", Set.empty, f.wleIntl2025 -> User.STAFF_ROLE)
        account("Jo", Set(User.ROOT_ROLE), f.wleAm2025 -> "jury") // a root who is also a juror
        val kim = account("Kim", Set.empty, f.wleIntl2025 -> "jury")
        DB.autoCommit { implicit s => sql"UPDATE users SET deleted_at = NOW() WHERE id = ${kim.getId}".update.apply() }

        // by default every live account: Kim, deleted, isn't listed
        listed(app, f, "") === Everyone ++ Seq("Hal", "Ida", "Jo")
        // Audit committee and Staff never match a filter
        listed(app, f, "?contestType=Wiki+Loves+Monuments&country=ua&year=2025") === Seq("Bob", "Gus")
        listed(app, f, "?country=international") === Seq("Cat", "Gus")
        // a root by its memberships only: Jo as a juror in Armenia; Root, in no contest, under All only
        listed(app, f, "?role=jury") === Seq("Ann", "Cat", "Dan", "Gus", "Jo")
        listed(app, f, "?country=Armenia") === Seq("Ann", "Jo")
        listed(app, f, "?role=admin") === Seq("Ann", "Gus")
        items(page(app, f.root, Page), "users-role-menu") ===
          Seq("All" -> Some(11), "Admin" -> Some(2), "Organizer" -> Some(1), "Juror" -> Some(5))
        // a deleted account neither listed nor counted on a filtered page
        val doc = page(app, f.root, s"$Page?country=international&role=jury")
        names(doc) === Seq("Cat")
        items(doc, "users-role-menu") === Seq("All" -> Some(2), "Admin" -> Some(1), "Juror" -> Some(1))
        items(doc, "users-country-menu").find(_._1 == "International") must beSome("International" -> Some(1))
      }
    }

    "keep the chosen item and All in a menu, also at 0, and leave out the other items of 0" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, s"$Page?country=international&role=organizer")
        names(doc) === Nil
        items(doc, "users-role-menu") ===
          Seq("All" -> Some(2), "Admin" -> Some(1), "Organizer" -> Some(0), "Juror" -> Some(1))
        active(doc, "users-role-menu") === "Organizer"
        toggle(doc, "users-role-menu") === "Role: Organizer"
        items(doc, "users-country-menu") === Seq("All countries" -> Some(1), "International" -> Some(0), "Ukraine" -> Some(1))
        active(doc, "users-country-menu") === "International"
        items(doc, "users-type-menu") === Seq("All" -> Some(0))
        items(doc, "users-year-menu") === Seq("All years" -> Some(0))
      }
    }

    "list International and a country text the tool doesn't know as their own entries" in {
      testDbApp { implicit app =>
        val f = fixture()
        listed(app, f, "?country=international") === Seq("Cat", "Gus")
        listed(app, f, "?country=+INTERNATIONAL+") === Seq("Cat", "Gus")
        listed(app, f, "?country=Ukraine%2C+Animals") === Seq("Dan")
        listed(app, f, "?country=ukraine,%C2%A0animals") === Seq("Dan")
        // Ukraine, by its code or a name, isn't "Ukraine, Animals"
        listed(app, f, "?country=Ukraine") === Seq("Ann", "Bob", "Gus")
        val doc = page(app, f.root, s"$Page?country=Ukraine%2C+Animals")
        toggle(doc, "users-country-menu") === "Country: Ukraine, Animals"
        href(doc, "users-country-menu", "International") === s"$Page?country=international"
      }
    }

    "keep the other menus' choices in each menu's links" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(app, f.root, s"$Page?contestType=Wiki+Loves+Earth&year=2025&role=jury")
        href(doc, "users-type-menu", "All") === s"$Page?year=2025&role=jury"
        href(doc, "users-type-menu", WLM) === s"$Page?contestType=Wiki+Loves+Monuments&year=2025&role=jury"
        href(doc, "users-country-menu", "International") ===
          s"$Page?contestType=Wiki+Loves+Earth&country=international&year=2025&role=jury"
        href(doc, "users-country-menu", "Ukraine, Animals") ===
          s"$Page?contestType=Wiki+Loves+Earth&country=Ukraine%2C+Animals&year=2025&role=jury"
        href(doc, "users-year-menu", "All years") === s"$Page?contestType=Wiki+Loves+Earth&role=jury"
        // 2024 has no WLE juror (left out there): from WLM's admins, where it has one
        href(page(app, f.root, s"$Page?contestType=Wiki+Loves+Monuments&year=2025&role=admin"), "users-year-menu", "2024") ===
          s"$Page?contestType=Wiki+Loves+Monuments&year=2024&role=admin"
        href(doc, "users-role-menu", "All") === s"$Page?contestType=Wiki+Loves+Earth&year=2025"
        href(doc, "users-role-menu", "Admin") === s"$Page?contestType=Wiki+Loves+Earth&year=2025&role=admin"
        // from the default page: the defaults stay implicit
        val plain = page(app, f.root, Page)
        href(plain, "users-year-menu", "All years") === Page
        href(plain, "users-role-menu", "All") === Page
        href(plain, "users-role-menu", "Organizer") === s"$Page?role=organizer"
        // plain links: no script needed
        doc.select(Menus.map(m => s"#$m a.dropdown-item[onclick]").mkString(", ")).size === 0
      }
    }

    "say when no account matches, with a link to all of them" in {
      testDbApp { implicit app =>
        val f = fixture()
        val none = page(app, f.root, s"$Page?contestType=Wiki+Loves+Folklore&role=jury")
        names(none) must beEmpty
        toggle(none, "users-type-menu") === "Type: Wiki Loves Folklore"
        none.select("#users-filter-none").text must contain("No accounts match these choices.")
        none.select("#users-filter-reset").attr("href") === Page
      }
    }

    "run as many statements for many accounts and contests as for a few, in a bounded time" in {
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
        val filtered = s"$Page?contestType=Wiki+Loves+Earth&country=ua&year=2020&role=jury"
        statements(Page) // warm-up: templates, campaign configs
        val (few, _) = statements(Page)
        val (fewFiltered, _) = statements(filtered)

        // production's size and more: 3,000 accounts, 630 contests, 9,000 memberships
        val countries = Seq("Ukraine", "Armenia", "Germany", "France", "Poland", "Spain", "Italy", "Sweden",
          "Estonia", "Ghana", "Nepal", "India", "Brazil", "Mexico", "International")
        val more = for {
          year <- 2012 to 2025
          name <- Seq(WLM, WLE, "Wiki Loves Folklore")
          country <- countries
        } yield ContestJury(None, name, year, country, None)
        contestDao.batchInsert(more)
        DB.localTx { implicit tx =>
          (1 to 3000).grouped(500).foreach { batch =>
            val rows = batch.map(i => sqls"(${s"Person $i"}, ${s"p$i@example.org"})")
            sql"INSERT INTO users (fullname, email) VALUES ${sqls.csv(rows: _*)}".update.apply()
          }
          val contestIds = sql"SELECT id FROM contest_jury ORDER BY id".map(_.long(1)).list.apply().toVector
          val userIds = sql"SELECT id FROM users WHERE email LIKE 'p%@example.org' ORDER BY id".map(_.long(1)).list.apply()
          val roles = Seq("jury", "jury", "organizer", "admin", "no")
          val memberships = userIds.zipWithIndex.flatMap { case (id, i) =>
            (0 until 3).map(k => (id, contestIds((i * 7 + k * 101) % contestIds.size), roles((i + k) % roles.size)))
          }.distinctBy(m => (m._1, m._2))
          memberships.grouped(1000).foreach { batch =>
            val rows = batch.map { case (u, c, r) => sqls"($u, $c, $r)" }
            sql"INSERT INTO user_contest (user_id, contest_id, role) VALUES ${sqls.csv(rows: _*)}".update.apply()
          }
        }
        statements(Page) // warm-up of the new campaigns
        statements(filtered)
        // the median of 5 requests each, all of them printed
        val defaultRuns = (1 to 5).map(_ => statements(Page))
        val filteredRuns = (1 to 5).map(_ => statements(filtered))
        val many = defaultRuns.head._1
        val manyFiltered = filteredRuns.head._1
        def median(runs: Seq[(Seq[String], Long)]) = runs.map(_._2).sorted.apply(runs.size / 2)
        val millis = median(defaultRuns)
        println(s"[UsersFilterSpec] 3008 accounts, ${more.size + 5} contests: default median ${millis} ms " +
          s"(${defaultRuns.map(_._2).mkString(", ")}), filtered median ${median(filteredRuns)} ms " +
          s"(${filteredRuns.map(_._2).mkString(", ")}); ${many.size} statements (${few.size} for 8 accounts)")
        many.size === few.size
        manyFiltered.size === fewFiltered.size
        names(Jsoup.parse(contentAsString(get(app, Some(f.root), Page)))).size === 3008
        millis must be_<(10000L)
      }
    }
  }

  "a contest's users page" should {

    "show no menus" in {
      testDbApp { implicit app =>
        val f = fixture()
        val admin = User.create(User("Admin", "admin@example.com", password = Some("hash")))
        UserContestJdbc.createMembership(admin.getId, f.wlmUa2025.getId, "admin")
        for (viewer <- Seq(f.root, admin)) {
          val plain = page(app, viewer, s"/admin/users?contestId=${f.wlmUa2025.getId}")
          val doc = page(app, viewer, s"/admin/users?contestId=${f.wlmUa2025.getId}&role=jury&year=2024&country=am")
          doc.select(Menus.map("#" + _).mkString(", ")).size aka viewer.fullname must_== 0
          // its own members, the filter's params ignored: the same list as without them
          names(plain) must containAllOf(Seq("Bob", "Gus"))
          names(doc) aka viewer.fullname must_== names(plain)
        }
        ok
      }
    }
  }

  "the all-users page" should {

    "stay root's only, with the menus' params too" in {
      testDbApp { implicit app =>
        val f = fixture()
        def member(name: String, role: String) =
          userDao.create(User(name, s"${name.toLowerCase}@example.org", None, Set(role), contestId = f.wlmUa2025.id))
        val people = Seq(member("Admin", "admin"), member("Org", "organizer"), member("Juror", "jury"))
        for (u <- people; path <- Seq(Page, s"$Page?contestType=Wiki+Loves+Earth&country=international&year=2025&role=admin")) {
          val result = get(app, Some(u), path)
          status(result) aka s"${u.fullname} $path" must_== FORBIDDEN
          contentAsString(result) must not contain "users-role-menu"
        }
        val signedOut = get(app, None, s"$Page?role=admin")
        status(signedOut) must_== SEE_OTHER
        redirectLocation(signedOut).getOrElse("") must startWith("/auth")
      }
    }
  }
}
