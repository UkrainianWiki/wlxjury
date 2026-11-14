package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The navbar's Votes and Comments menus and its groups (the navigation proposal v3, D1-D9):
  * what each menu shows and opens for a juror and for the organisers, the region kept by a Votes
  * choice with counts in it (also in a round of sub-regions), no Votes menu in a comments-only
  * round, the file list's menus, the Pages menu by role, no "My votes" for staff (the
  * maintainer's decision of 2026-11-14), the groups' order, the menus' buttons and current
  * items, and what a juror's menus never link.
  */
class NavbarMenusSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      contest: ContestJury,
      round: Round,
      commentsOnly: Round,
      viaRegia: Round,
      juror: User,
      quiet: User,
      organizer: User,
      orgJuror: User,
      admin: User,
      root: User
  )

  /** A yes/no round with the juror on files 1-8: 1-4 in region 12 (selected, unrated, rejected,
    * unrated), 5-8 in region 14 (selected, selected, unrated, unrated); comments on 1 and 6. A
    * second juror on files 2 and 3 (no comments); an organizer without files, and one with two
    * unrated files of their own in the round. A comments-only round and a yes/no round of
    * sub-regions (Via Regia) with the juror on the same files and votes.
    */
  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    def round(n: Int, rates: Rates, nomination: Option[String] = None) =
      roundDao.create(Round(None, n, Some(s"Round $n"), contestId = a.getId, rates = rates, active = true,
        specialNomination = nomination))
    val r = round(1, Round.binaryRound)
    val commentsOnly = round(2, Round.commentsOnlyRound)
    val viaRegia = round(3, Round.binaryRound, Some("Віа Регіа"))
    def user(name: String, role: String) =
      userDao.create(User(name, s"${name.toLowerCase.replace(" ", "")}@example.com", None, Set(role), contestId = a.id))
    val juror = user("Juror", "jury")
    val quiet = user("Quiet Juror", "jury")
    val organizer = user("Org", "organizer")
    val orgJuror = user("Org Juror", "organizer")
    val images = (1L to 8L).map(id =>
      Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"${if (id <= 4) 12 else 14}-345-$id")))
    imageDao.batchInsert(images)
    val rates = Seq(1, 0, -1, 0, 1, 1, 0, 0)
    for (rnd <- Seq(r, commentsOnly, viaRegia)) {
      rnd.addUsers(Seq(RoundUser(rnd.getId, juror.getId, "jury", active = true)))
      selectionDao.batchInsert(images.zip(rates).map { case (i, rate) =>
        Vote(i, juror, rnd, if (rnd == commentsOnly) 0 else rate) })
    }
    r.addUsers(Seq(RoundUser(r.getId, quiet.getId, "jury", active = true)))
    selectionDao.batchInsert(Seq(Vote(images(1), quiet, r, 0), Vote(images(2), quiet, r, 1),
      Vote(images(0), orgJuror, r, 0), Vote(images(1), orgJuror, r, 0)))
    def comment(by: User, pageId: Long) =
      CommentJdbc.create(by.getId, by.fullname, r.getId, Some(a.getId), pageId, "a comment", "2026-11-12T14:05:00+02:00")
    comment(juror, 1)
    comment(organizer, 6)
    val admin = user("Admin", "admin")
    val root = userDao.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE)))
    Fixture(a, r, commentsOnly, viaRegia, juror, quiet, organizer, orgJuror, admin, root)
  }

  private def get(app: Application, viewer: User, path: String): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withCSRFToken).get

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, viewer, path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  private def gallery(r: Round, asUserId: Long, region: String = "all", query: String = "") =
    (if (region == "all") s"/gallery/round/${r.getId}/user/$asUserId/page/1"
     else s"/gallery/round/${r.getId}/user/$asUserId/region/$region/page/1") + query

  private def counts(doc: Document): Seq[(String, Option[Int])] = NavMenus.votes(doc).map(i => i.name -> i.count)

  /** The navbar's groups, by their class, in order. */
  private def groups(doc: Document): Seq[String] =
    doc.select("nav .wlx-nav-groups > ul").asScala.toSeq.map(_.classNames.asScala.find(_.startsWith("wlx-nav-")).get)

  "a juror's Votes menu" should {

    "show the current choice and its count, and on all files only how many are unrated (D1, D2)" in {
      testDbApp { app =>
        val f = fixture()
        val all = page(app, f.juror, gallery(f.round, f.juror.getId))
        counts(all) === Seq("All" -> Some(8), "Unrated" -> Some(4), "Selected" -> Some(3), "Rejected" -> Some(1))
        // no visible word: "Votes" read by screen readers and in the title
        NavMenus.button(all, "votes-menu") === Some("Votes: All (8) · 4 unrated")
        all.select("#votes-menu > button > .visually-hidden").text === "Votes:"
        all.selectFirst("#votes-menu > button").attr("title") === "Votes: All (8) · 4 unrated"
        forall(Seq("unrated" -> "Votes: Unrated (4)", "selected" -> "Votes: Selected (3)", "rejected" -> "Votes: Rejected (1)")) {
          case (filter, text) =>
            NavMenus.button(page(app, f.juror, gallery(f.round, f.juror.getId, query = s"?filter=$filter")), "votes-menu") ===
              Some(text)
        }
        // within the comments filter: the list's unrated
        NavMenus.button(page(app, f.juror, gallery(f.round, f.juror.getId, query = "?comments=1")), "votes-menu") ===
          Some("Votes: All (2) · 0 unrated")
      }
    }

    "keep the region, each choice counted in it (D8)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, gallery(f.round, f.juror.getId, "14"))
        counts(doc) === Seq("All" -> Some(4), "Unrated" -> Some(2), "Selected" -> Some(2), "Rejected" -> Some(0))
        NavMenus.button(doc, "votes-menu") === Some("Votes: All (4) · 2 unrated")
        forall(NavMenus.votes(doc).flatMap(_.href))(_ must startWith(s"/gallery/round/${f.round.getId}/user/${f.juror.getId}/region/14/page/1"))
        // each count is what its choice opens
        forall(NavMenus.votes(doc)) { item =>
          page(app, f.juror, item.href.get).select("#gallery img[data-pageId]").size aka item.name must_== item.count.get
        }
      }
    }

    "keep the region in a round of sub-regions too, one count per choice (D8, Via Regia)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, gallery(f.viaRegia, f.juror.getId, "12"))
        counts(doc) === Seq("All" -> Some(4), "Unrated" -> Some(2), "Selected" -> Some(1), "Rejected" -> Some(1))
        forall(NavMenus.votes(doc).flatMap(_.href))(_ must contain("/region/12/"))
        // each count is what its choice opens
        forall(NavMenus.votes(doc)) { item =>
          page(app, f.juror, item.href.get).select("#gallery img[data-pageId]").size aka item.name must_== item.count.get
        }
        // in all of them: all the juror's files
        counts(page(app, f.juror, gallery(f.viaRegia, f.juror.getId))) ===
          Seq("All" -> Some(8), "Unrated" -> Some(4), "Selected" -> Some(3), "Rejected" -> Some(1))
      }
    }

    "not be shown in a comments-only round, whose Comments menu stays (§6 S2)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, gallery(f.commentsOnly, f.juror.getId))
        doc.select("#votes-menu").size === 0
        doc.select("#comments-menu").size === 1
      }
    }

    "be on the file list, its choices and order staying in the file list (§2.7)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, s"/filelist/round/${f.round.getId}/user/${f.juror.getId}/page/1")
        counts(doc).map(_._2) === Seq(Some(8), Some(4), Some(3), Some(1))
        forall(NavMenus.votes(doc).flatMap(_.href))(_ must startWith("/filelist/"))
        doc.select("#comments-menu").size === 1
        forall(doc.select(".wlx-nav-order a.dropdown-item").asScala.map(_.attr("href")))(_ must startWith("/filelist/"))
      }
    }
  }

  "a juror's Comments menu" should {

    "grey out its items at 0, no count on the button (D3)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.quiet, gallery(f.round, f.quiet.getId))
        NavMenus.button(doc, "comments-menu") === Some("Comments")
        NavMenus.comments(doc) === Seq(
          NavMenus.Item("All files", None, Some(gallery(f.round, f.quiet.getId, query = "?sort=file")), active = true, disabled = false),
          NavMenus.Item("Only with comments", Some(0), None, active = false, disabled = true),
          NavMenus.Item("Recent comments list", Some(0), None, active = false, disabled = true))
        doc.select("#comments-menu span.dropdown-item.disabled[aria-disabled=true]").size === 2
      }
    }

    "count the report's files in the report, its list item current" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, s"/comments/round/${f.round.getId}/user/${f.juror.getId}/page/1")
        NavMenus.button(doc, "comments-menu") === Some("Comments list (2)")
        NavMenus.current(doc, "comments-menu").map(i => (i.name, i.count)) === Some(("Recent comments list", Some(2)))
        // the report's order in the bar's order group
        doc.select(".wlx-nav-order #comments-report-order").size === 1
      }
    }

    "never link another juror's list, the organisers' scope or their filter by author" in {
      testDbApp { app =>
        val f = fixture()
        val j = f.juror.getId
        val pages = Seq(gallery(f.round, j), gallery(f.round, j, query = "?comments=1"),
          s"/comments/round/${f.round.getId}/user/$j/page/1?scope=round&commenter=${f.organizer.getId}")
        forall(pages) { path =>
          val links = page(app, f.juror, path).select("#comments-menu a, #votes-menu a").asScala.map(_.attr("href"))
          (links must not(beEmpty)) and forall(links) { href =>
            (href must contain(s"/user/$j/")) and (href must not(contain("scope=round"))) and
              (href must not(contain("commenter=")))
          }
        }
      }
    }
  }

  "the organisers' bar" should {

    "have the same Votes menu, counted for one juror and not for all of them (D5)" in {
      testDbApp { app =>
        val f = fixture()
        val one = page(app, f.organizer, gallery(f.round, f.juror.getId))
        counts(one) === Seq("All" -> Some(8), "Unrated" -> Some(4), "Selected" -> Some(3), "Rejected" -> Some(1))
        NavMenus.button(one, "votes-menu") === Some("Votes: All (8) · 4 unrated")
        // the count always shown; the unrated read by screen readers only below 1700 px (main.css wlx-nav-suffix)
        one.select("#votes-menu > button .wlx-votes-count").text === "(8)"
        one.select("#votes-menu > button .wlx-nav-suffix").text === "· 4 unrated"
        one.select("nav").hasClass("wlx-nav-results") must beTrue
        // the juror's name cut short if long, whole in the title
        one.select("#juror-menu .wlx-truncate").text === "Juror"
        one.select("#juror-menu > button").attr("title") === "Juror"
        val all = page(app, f.organizer, gallery(f.round, 0))
        counts(all) === Seq("All" -> None, "Unrated" -> None, "Selected" -> None, "Rejected" -> None)
        NavMenus.button(all, "votes-menu") === Some("Votes: All")
      }
    }

    "count one juror's choices in the region, each what it opens (D5, D8)" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, gallery(f.round, f.juror.getId, "14"))
        counts(doc) === Seq("All" -> Some(4), "Unrated" -> Some(2), "Selected" -> Some(2), "Rejected" -> Some(0))
        forall(NavMenus.votes(doc)) { item =>
          (item.href.get must contain("/region/14/")) and
            (page(app, f.organizer, item.href.get).select("#gallery img[data-pageId]").size aka item.name must_== item.count.get)
        }
      }
    }

    "have no link to the staff's own votes, which the juror menu lists (no My votes until multi-role)" in {
      testDbApp { app =>
        val f = fixture()
        val own = page(app, f.orgJuror, gallery(f.round, 0))
        own.select("#menu-my-votes").size === 0
        own.select("#juror-menu a.dropdown-item").asScala.map(_.attr("href")).toList must
          contain(startWith(gallery(f.round, f.orgJuror.getId)))
        own.select("#votes-menu").size === 1
      }
    }

    "give staff the organisers' bar in their own gallery, opened from the juror menu" in {
      testDbApp { app =>
        val f = fixture()
        val href = page(app, f.orgJuror, gallery(f.round, 0)).select("#juror-menu a.dropdown-item").asScala
          .map(_.attr("href")).find(_.startsWith(gallery(f.round, f.orgJuror.getId))).get
        val own = page(app, f.orgJuror, href)
        (own.select("nav").hasClass("wlx-nav-results") must beTrue) and
          (NavMenus.current(own, "juror-menu").map(_.name) === Some("Org Juror")) and
          // their two unrated files, counted; the Comments menu and their order (by their rating)
          (counts(own) === Seq("All" -> Some(2), "Unrated" -> Some(2), "Selected" -> Some(0), "Rejected" -> Some(0))) and
          (NavMenus.button(own, "votes-menu") === Some("Votes: All (2) · 2 unrated")) and
          (own.select("#comments-menu").size === 1) and
          (own.select(".wlx-nav-order a.dropdown-item").asScala.map(_.text.trim).toList must contain("By my rating")) and
          (own.select(".wlx-nav-filters > li").size must be_>=(4))
      }
    }

    "group the bar: the page links, the filters, the order and view after a divider (§2)" in {
      testDbApp { app =>
        val f = fixture()
        val staff = page(app, f.organizer, gallery(f.round, f.juror.getId))
        groups(staff) === Seq("wlx-nav-links", "wlx-nav-filters", "wlx-nav-order")
        staff.select(".wlx-nav-filters").hasClass("border-start") must beTrue
        staff.select(".wlx-nav-order").hasClass("border-start") must beTrue
        staff.select(".wlx-nav-filters > li").asScala.map(li => Option(li.id).filter(_.nonEmpty).getOrElse(li.className)).toList must
          containTheSameElementsAs(List("nav-item dropdown", "juror-menu", "votes-menu", "comments-menu"))
        val juror = page(app, f.juror, gallery(f.round, f.juror.getId))
        groups(juror) === Seq("wlx-nav-filters", "wlx-nav-order")
        juror.select(".wlx-nav-groups > ul").asScala.map(_.attr("aria-label")).toList === List("Filters", "Sorting and view")
      }
    }
  }

  "the Pages menu" should {

    "list the pages each role may open, this page's current and on its button" in {
      testDbApp { app =>
        val f = fixture()
        def items(doc: Document) = doc.select("#pages-menu a.dropdown-item").asScala.map(_.id).toList
        val stat = s"/roundstat/${f.round.getId}"
        val org = page(app, f.organizer, gallery(f.round, f.juror.getId))
        val admin = page(app, f.admin, stat)
        val root = page(app, f.root, gallery(f.round, 0))
        (items(org) === List("menu-contest-files", "menu-statistics")) and
          (NavMenus.button(org, "pages-menu") === Some("Go to: Gallery")) and
          (org.select("#pages-menu [aria-current]").size === 0) and
          (items(admin) === List("menu-contest-files", "menu-users", "menu-rounds", "menu-statistics")) and
          (NavMenus.button(admin, "pages-menu") === Some("Go to: Statistics")) and
          (admin.select("#pages-menu a[aria-current=page]").asScala.map(_.id).toList === List("menu-statistics")) and
          (admin.selectFirst("#menu-statistics").attr("href") === stat) and
          (items(root).take(2) === List("menu-contests", "menu-contest-files")) and
          (items(root) must contain("menu-statistics")) and
          // the pages menu is the bar's first group, before the filters
          (org.select(".wlx-nav-groups > ul").first.select("#pages-menu").size === 1) and
          // the current round's statistics (no round in the URL) is the statistics page too
          {
            val current = get(app, f.admin, s"/roundstat?contestId=${f.contest.getId}")
            val landed = redirectLocation(current).getOrElse(s"/roundstat?contestId=${f.contest.getId}")
            page(app, f.admin, landed).select("#pages-menu a[aria-current=page]").asScala.map(_.id).toList ===
              List("menu-statistics")
          } and
          // a juror has none of these pages: no menu
          (page(app, f.juror, gallery(f.round, f.juror.getId)).select("#pages-menu, .wlx-nav-links").size === 0)
      }
    }
  }

  "the navbar's menus" should {

    "open from real buttons, their current items marked as this page (§2.4)" in {
      testDbApp { app =>
        val f = fixture()
        forall(Seq(f.juror -> gallery(f.round, f.juror.getId, "14"), f.organizer -> gallery(f.round, f.juror.getId))) {
          case (viewer, path) =>
            val doc = page(app, viewer, path)
            val toggles = doc.select("nav .wlx-nav-groups .dropdown-toggle").asScala.filterNot(_.hasClass("page-link"))
            (toggles must not(beEmpty)) and
              forall(toggles)(t => (t.tagName === "button") and (t.attr("type") === "button")) and
              forall(doc.select("nav .wlx-nav-groups ul.dropdown-menu .dropdown-item.active").asScala.filter(_.tagName == "a"))(
                _.attr("aria-current") === "page")
        }
      }
    }

    "show no author to a juror, whatever the URL asks" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.juror, gallery(f.round, f.juror.getId, query = "?author=Someone"))
        (doc.select("#author-filter").size === 0) and (doc.select("nav").text must not(contain("Someone"))) and
          forall(doc.select("nav a").asScala.map(_.attr("href")))(_ must not(contain("author=")))
      }
    }
  }
}
