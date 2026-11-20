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

/** The Rounds page's and the round statistics page's Status, Type and Order menus (the maintainer's
  * decisions of 2026-11-18): the rounds each choice lists and in what order, the defaults (the page
  * as before), unknown values as the defaults, the counts, the filter carried by the links between
  * the two pages (the navbar's page links, the table's links, the statistics page's round menu, the
  * Start/Stop forms, the round form), the current round kept on the statistics page when filtered
  * out; the rounds' look (the Active and type badges, inactive in grey) there and in the galleries'
  * round menu, which has no filter menus; and no round a viewer could not see before.
  */
class RoundsFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(
      a: ContestJury,
      b: ContestJury,
      pre: Round, // 1: yes/no, stopped
      stars: Round, // 2: 1-5 rating, active
      criteria: Round, // 3: 1-10 rating, stopped
      comments: Round, // 4: comments only, active
      fin: Round, // 5: yes/no, active
      roundB: Round, // contest B's yes/no round, active
      admin: User,
      organizer: User,
      juror: User,
      adminB: User
  )

  private def fixture(): Fixture = {
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    val b = contestDao.create(None, "WLE", 2026, "Ukraine")
    def round(c: ContestJury, n: Int, name: String, rates: Rates, active: Boolean) =
      roundDao.create(Round(None, n, Some(name), contestId = c.getId, rates = rates, active = active))
    val pre = round(a, 1, "Pre", Round.binaryRound, active = false)
    val stars = round(a, 2, "Stars", Round.rateRounds.find(_.id == 5).get, active = true)
    val criteria = round(a, 3, "Criteria", Round.rateRounds.find(_.id == 10).get, active = false)
    val comments = round(a, 4, "Comments", Round.commentsOnlyRound, active = true)
    val fin = round(a, 5, "Final", Round.binaryRound, active = true)
    val roundB = round(b, 1, "Other contest", Round.binaryRound, active = true)
    def user(name: String, role: String, c: ContestJury) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = c.id))
    val admin = user("Admin", "admin", a)
    val organizer = user("Org", "organizer", a)
    val juror = user("Juror", "jury", a)
    val adminB = user("AdminB", "admin", b)
    val images = (1L to 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    for (r <- Seq(pre, stars, fin)) {
      r.addUsers(Seq(RoundUser(r.getId, juror.getId, "jury", active = true)))
      selectionDao.batchInsert(images.map(i => Vote(i, juror, r, 0)))
    }
    Fixture(a, b, pre, stars, criteria, comments, fin, roundB, admin, organizer, juror, adminB)
  }

  private def get(app: Application, viewer: User, path: String): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email).withCSRFToken).get

  private def page(app: Application, viewer: User, path: String): Document = {
    val result = get(app, viewer, path)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  private def rounds(f: Fixture, query: String = "") = s"/admin/rounds?contestId=${f.a.getId}$query"

  /** The Rounds table's rounds, by name, in order. */
  private def rows(doc: Document): Seq[String] =
    doc.select("tbody tr.round-row .round-stat-link").asScala.toSeq.map(_.text)

  /** The statistics page's round menu, by name, in order. */
  private def statMenu(doc: Document): Seq[String] =
    doc.select("#round-stat-rounds .page-link").asScala.toSeq.map { a =>
      val c = a.clone(); c.select(".wlx-round-spoken").remove(); c.text.trim
    }

  /** A round's icon slot (snippets/roundIcon) in `e`: its type, whether grey (inactive), its icons
    * and its tooltip; and the text after the name for screen readers.
    */
  private def icon(e: org.jsoup.nodes.Element): (String, Boolean, List[String], String) = {
    val slot = e.selectFirst(".wlx-round-icon[aria-hidden=true]")
    (slot.attr("data-type"), slot.hasClass("wlx-round-icon-inactive"),
      slot.select("i").asScala.toList.flatMap(_.classNames.asScala.find(_.startsWith("wlx-round-"))), slot.attr("title"))
  }
  private def spoken(e: org.jsoup.nodes.Element): String =
    Option(e.selectFirst(".wlx-round-spoken.visually-hidden")).fold("")(_.text)

  private def current(doc: Document, menuId: String): String =
    NavMenus.items(doc, menuId).find(_.active).map(_.name).getOrElse("")

  private def counts(doc: Document, menuId: String): Seq[(String, Option[Int])] =
    NavMenus.items(doc, menuId).map(i => i.name -> i.count)

  "the Rounds page [A-28]" should {

    "list all rounds oldest first with the menus at their defaults, its links without params" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, rounds(f))
        rows(doc) === Seq("Pre", "Stars", "Criteria", "Comments", "Final")
        current(doc, "rounds-status-menu") === "All"
        current(doc, "rounds-type-menu") === "All"
        current(doc, "rounds-order-menu") === "Oldest first"
        counts(doc, "rounds-status-menu") === Seq("All" -> Some(5), "Active" -> Some(3), "Not active" -> Some(2))
        counts(doc, "rounds-type-menu") === Seq("All" -> Some(5), "Yes/No" -> Some(2), "Rated" -> Some(2))
        // the Type menu's items a legend of the round icons, coloured; All's slot empty
        doc.select("#rounds-type-menu .dropdown-item").asScala.toList.map(icon) === List(
          ("none", false, Nil, ""), ("binary", false, List("wlx-round-yes", "wlx-round-no"), ""),
          ("rated", false, List("wlx-round-star"), ""))
        NavMenus.items(doc, "rounds-order-menu").map(_.name) === Seq("Oldest first", "Newest first", "Active first")
        NavMenus.items(doc, "rounds-order-menu").forall(_.count.isEmpty) must beTrue
        // in the navbar's groups: Status and Type with the filters, Order in the order group
        doc.select("nav ul.wlx-nav-filters > #rounds-status-menu").size === 1
        doc.select("nav ul.wlx-nav-filters > #rounds-type-menu").size === 1
        doc.select("nav ul.wlx-nav-order > #rounds-order-menu").size === 1
        // each toggle shows its menu's name before its choice, as the region menu's ("Region: All regions")
        doc.selectFirst("#rounds-status-menu > button").text === "Status: All"
        doc.selectFirst("#rounds-type-menu > button").text === "Type: All"
        doc.selectFirst("#rounds-order-menu > button").text === "Order: Oldest first"
        doc.select("#rounds-status-menu > button > .wlx-nav-label").text === "Status:"
        // no params in any link at the default
        doc.select("#menu-statistics").attr("href") must not(contain("status="))
        doc.select("#menu-rounds").attr("href") === s"/admin/rounds?contestId=${f.a.getId}"
        doc.select("a.round-stat-link").asScala.map(_.attr("href")).toList ===
          List(f.pre, f.stars, f.criteria, f.comments, f.fin).map(r => s"/roundstat/${r.getId}")
        doc.select("form[id^=set-active-form-] input[name=status]").size === 0
        // the Start/Stop buttons as they were
        doc.select("form[id^=set-active-form-] button.btn-outline-secondary").size === 5
      }
    }

    "show the type icons before the names, inactive rows grey, the round form's link kept on the number" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, rounds(f))
        def row(r: Round) = doc.selectFirst(s"tbody tr.round-row[data-round=${r.getId}]")
        icon(row(f.pre)) === (("binary", true, List("wlx-round-yes", "wlx-round-no"), "Yes/No, Inactive"))
        icon(row(f.stars)) === (("rated", false, List("wlx-round-star"), "Rated, Active"))
        icon(row(f.criteria)) === (("rated", true, List("wlx-round-star"), "Rated, Inactive"))
        icon(row(f.comments)) === (("none", false, Nil, ""))
        icon(row(f.fin)) === (("binary", false, List("wlx-round-yes", "wlx-round-no"), "Yes/No, Active"))
        spoken(row(f.fin)) === ", Yes/No, Active"
        spoken(row(f.pre)) === ", Yes/No"
        spoken(row(f.comments)) === ", Active"
        // the slot before the name, the name its statistics link; no "Active" badge any more
        row(f.fin).selectFirst(".wlx-round-named > .wlx-round-icon + a.round-stat-link") must not(beNull)
        doc.select("tbody .badge").size === 0
        Seq(f.pre, f.criteria).forall(r => row(r).hasClass("wlx-round-inactive")) must beTrue
        Seq(f.stars, f.comments, f.fin).exists(r => row(r).hasClass("wlx-round-inactive")) must beFalse
        // no strikethrough
        doc.select("tbody s, tbody del, tbody .text-decoration-line-through").size === 0
        row(f.pre).selectFirst("a.round-edit-link").attr("href") ===
          s"/admin/rounds/edit?id=${f.pre.getId}&contestId=${f.a.getId}"
        // the rating type column unchanged
        row(f.criteria).select("td").get(3).text === "1-10 rating"
      }
    }

    "filter by status and type, counting each menu within the other's choice, and carry the filter" in {
      testDbApp { app =>
        val f = fixture()
        val active = page(app, f.admin, rounds(f, "&status=active"))
        rows(active) === Seq("Stars", "Comments", "Final")
        current(active, "rounds-status-menu") === "Active"
        counts(active, "rounds-type-menu") === Seq("All" -> Some(3), "Yes/No" -> Some(1), "Rated" -> Some(1))
        counts(active, "rounds-status-menu") === Seq("All" -> Some(5), "Active" -> Some(3), "Not active" -> Some(2))
        // every link to the statistics and back keeps it
        active.select("#menu-statistics").attr("href") must endWith("status=active")
        active.select("#menu-rounds").attr("href") === s"/admin/rounds?contestId=${f.a.getId}&status=active"
        active.select("a.round-stat-link").asScala.forall(_.attr("href").endsWith("?status=active")) must beTrue
        active.select("a.round-edit-link").asScala.forall(_.attr("href").endsWith("&status=active")) must beTrue
        active.select("form[id^=set-active-form-] input[name=status]").asScala.map(_.attr("value")).toSet === Set("active")
        // a menu's choice keeps the other menus'
        NavMenus.items(active, "rounds-type-menu").find(_.name == "Rated").flatMap(_.href) ===
          Some(s"/admin/rounds?contestId=${f.a.getId}&status=active&type=rated")
        NavMenus.items(active, "rounds-status-menu").find(_.name == "All").flatMap(_.href) ===
          Some(s"/admin/rounds?contestId=${f.a.getId}")

        val rated = page(app, f.admin, rounds(f, "&type=rated"))
        rows(rated) === Seq("Stars", "Criteria")
        counts(rated, "rounds-status-menu") === Seq("All" -> Some(2), "Active" -> Some(1), "Not active" -> Some(1))
        rows(page(app, f.admin, rounds(f, "&type=binary&status=inactive"))) === Seq("Pre")
        rows(page(app, f.admin, rounds(f, "&type=binary"))) === Seq("Pre", "Final")
      }
    }

    "order the rounds newest first, or the active first with each group oldest first" in {
      testDbApp { app =>
        val f = fixture()
        rows(page(app, f.admin, rounds(f, "&order=newest"))) === Seq("Final", "Comments", "Criteria", "Stars", "Pre")
        val activeFirst = page(app, f.admin, rounds(f, "&order=active"))
        rows(activeFirst) === Seq("Stars", "Comments", "Final", "Pre", "Criteria")
        current(activeFirst, "rounds-order-menu") === "Active first"
        rows(page(app, f.admin, rounds(f, "&order=newest&status=inactive"))) === Seq("Criteria", "Pre")
      }
    }

    "take unknown values as the defaults, never an error page" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, rounds(f, "&status=bogus&type=%3Cscript%3E&order=sideways"))
        rows(doc) === Seq("Pre", "Stars", "Criteria", "Comments", "Final")
        current(doc, "rounds-status-menu") === "All"
        current(doc, "rounds-type-menu") === "All"
        current(doc, "rounds-order-menu") === "Oldest first"
        doc.select("a.round-stat-link").asScala.exists(_.attr("href").contains("?")) must beFalse
        doc.select("#rounds-status-menu a, #menu-statistics, #menu-rounds").asScala.exists(_.attr("href").contains("bogus")) must beFalse
      }
    }

    "say when no round matches, with a link to all rounds keeping the order" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.adminB, s"/admin/rounds?contestId=${f.b.getId}&type=rated&order=newest")
        rows(doc) === Nil
        // the chosen Rated kept in its menu at 0, as Status's All; Not active and Active (0) left out
        counts(doc, "rounds-type-menu") === Seq("All" -> Some(1), "Yes/No" -> Some(1), "Rated" -> Some(0))
        current(doc, "rounds-type-menu") === "Rated"
        counts(doc, "rounds-status-menu") === Seq("All" -> Some(0))
        doc.select("#rounds-filter-none").size === 1
        doc.selectFirst("#rounds-filter-reset").attr("href") === s"/admin/rounds?contestId=${f.b.getId}&order=newest"
        // a contest without rounds: no such note
        page(app, f.adminB, s"/admin/rounds?contestId=${f.b.getId}").select("#rounds-filter-none").size === 0
      }
    }

    "come back to the same filtered list after Start/Stop" in {
      testDbApp { app =>
        val f = fixture()
        def post(fields: (String, String)*) =
          route(app, FakeRequest(POST, "/admin/setround")
            .withFormUrlEncodedBody(Seq("currentId" -> f.fin.getId.toString, "setActive" -> "false") ++ fields: _*)
            .withSession(Secured.UserName -> f.admin.email).withCSRFToken).get
        val filtered = post("status" -> "active", "order" -> "newest")
        status(filtered) === SEE_OTHER
        redirectLocation(filtered) === Some(s"/admin/rounds?contestId=${f.a.getId}&status=active&order=newest")
        roundDao.findById(f.fin.getId).map(_.active) must beSome(false)
        val plain = post("status" -> "nonsense")
        redirectLocation(plain) === Some(s"/admin/rounds?contestId=${f.a.getId}")
      }
    }

    "keep the filter through the round form: its action and Cancel" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, s"/admin/rounds/edit?id=${f.stars.getId}&contestId=${f.a.getId}&status=active&type=rated")
        doc.selectFirst("form#round-form").attr("action") === "/admin/rounds/save?status=active&type=rated"
        doc.select("a.btn").asScala.find(_.text == "Cancel").map(_.attr("href")) ===
          Some(s"/admin/rounds?contestId=${f.a.getId}&status=active&type=rated")
        // without it, as before
        page(app, f.admin, s"/admin/rounds/edit?id=${f.stars.getId}&contestId=${f.a.getId}")
          .selectFirst("form#round-form").attr("action") === "/admin/rounds/save"
      }
    }

    "keep the filter when the round form is saved, and in the form shown again after an invalid save" in {
      testDbApp { app =>
        val f = fixture()
        def save(number: String) = route(app, FakeRequest(POST, "/admin/rounds/save?status=active&type=rated")
          .withSession(Secured.UserName -> f.admin.email)
          .withFormUrlEncodedBody("id" -> f.stars.getId.toString, "number" -> number, "name" -> "Stars renamed",
            "contest" -> f.a.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "5",
            "minMpx" -> "", "minSize" -> "", "mediaType" -> "all", "jurors[0]" -> f.juror.getId.toString)
          .withCSRFToken).get
        val saved = save("2")
        status(saved) === SEE_OTHER
        flash(saved).get("error") must beNone
        redirectLocation(saved) === Some(s"/admin/rounds?contestId=${f.a.getId}&status=active&type=rated")
        roundDao.findById(f.stars.getId).flatMap(_.name) must beSome("Stars renamed")
        val invalid = save("not a number")
        status(invalid) === BAD_REQUEST
        val form = Jsoup.parse(contentAsString(invalid))
        form.selectFirst("form#round-form").attr("action") === "/admin/rounds/save?status=active&type=rated"
        form.select("a.btn").asScala.find(_.text == "Cancel").map(_.attr("href")) ===
          Some(s"/admin/rounds?contestId=${f.a.getId}&status=active&type=rated")
      }
    }

    "keep the filter on Add round and the new round form's Cancel" in {
      testDbApp { app =>
        val f = fixture()
        val list = page(app, f.admin, rounds(f, "&status=inactive&order=newest"))
        val add = list.selectFirst("#add-round").attr("href")
        add === s"/admin/rounds/edit?contestId=${f.a.getId}&status=inactive&order=newest"
        val form = page(app, f.admin, add)
        form.select("a.btn").asScala.find(_.text == "Cancel").map(_.attr("href")) ===
          Some(s"/admin/rounds?contestId=${f.a.getId}&status=inactive&order=newest")
        form.selectFirst("form#round-form").attr("action") === "/admin/rounds/save?status=inactive&order=newest"
        page(app, f.admin, rounds(f)).selectFirst("#add-round").attr("href") === s"/admin/rounds/edit?contestId=${f.a.getId}"
      }
    }
  }

  "the round statistics page [O-07]" should {

    "show the same menus, narrowing and ordering its round menu, the links keeping the filter" in {
      testDbApp { app =>
        val f = fixture()
        val all = page(app, f.organizer, s"/roundstat/${f.fin.getId}")
        statMenu(all) === Seq("Pre", "Stars", "Criteria", "Comments", "Final")
        counts(all, "rounds-status-menu") === Seq("All" -> Some(5), "Active" -> Some(3), "Not active" -> Some(2))
        current(all, "rounds-order-menu") === "Oldest first"
        all.select("#round-stat-rounds .page-link").asScala.map(_.attr("href")).toList ===
          List(f.pre, f.stars, f.criteria, f.comments, f.fin).map(r => s"/roundstat/${r.getId}")

        val newest = page(app, f.organizer, s"/roundstat/${f.fin.getId}?order=newest&type=rated")
        statMenu(newest) === Seq("Final", "Criteria", "Stars")
        newest.select("#round-stat-rounds .page-link").asScala.map(_.attr("href")).toList ===
          List(f.fin, f.criteria, f.stars).map(r => s"/roundstat/${r.getId}?type=rated&order=newest")
        newest.select("#menu-statistics").attr("href") must endWith("?type=rated&order=newest")
        // Final is yes/no, outside "rated": Active opens the one active rated round, Stars
        NavMenus.items(newest, "rounds-status-menu").find(_.name == "Active").flatMap(_.href) ===
          Some(s"/roundstat/${f.stars.getId}?status=active&type=rated&order=newest")
      }
    }

    "keep the current round, selected in its place and marked, when opened with a filter that leaves it out; no redirect" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.organizer, s"/roundstat/${f.stars.getId}?status=inactive")
        status(result) === OK
        val doc = Jsoup.parse(contentAsString(result))
        statMenu(doc) === Seq("Pre", "Stars", "Criteria")
        doc.select("#round-stat-rounds .page-item.active").attr("data-round") === f.stars.getId.toString
        doc.select("#round-stat-rounds .page-link[aria-current=page]").size === 1
        // outside the filter: a dashed outline (its class), a tooltip and the words for screen readers
        val chip = doc.selectFirst(s"#round-stat-rounds .page-item[data-round=${f.stars.getId}] > .page-link")
        chip.hasClass("wlx-round-outside") must beTrue
        // the tooltip on the item, not the link: its description would repeat its name's words
        chip.parent.attr("title") === "Not in the chosen filter"
        chip.hasAttr("title") must beFalse
        chip.select(".wlx-round-outside-note.visually-hidden").text === ", Not in the chosen filter"
        doc.select("#round-stat-rounds .wlx-round-outside").size === 1
        doc.select("#round-stat-rounds [title=Not in the chosen filter]").size === 1
        // other rounds match: no note
        doc.select("#rounds-filter-none").size === 0
        // the counts as before: each item the rounds its choice lists
        counts(doc, "rounds-status-menu") === Seq("All" -> Some(5), "Active" -> Some(3), "Not active" -> Some(2))
        counts(doc, "rounds-type-menu") === Seq("All" -> Some(2), "Yes/No" -> Some(1), "Rated" -> Some(1))
        // a round the filter keeps: not marked
        val kept = page(app, f.organizer, s"/roundstat/${f.pre.getId}?status=inactive")
        kept.select("#round-stat-rounds .wlx-round-outside, #round-stat-rounds [title=Not in the chosen filter]").size === 0
        kept.select("#round-stat-rounds .wlx-round-outside-note").size === 0
      }
    }

    "link each Status, Type and Order item to this round if its filter keeps it, else to the first round it lists [2026-11-19]" in {
      testDbApp { app =>
        val f = fixture()
        def href(doc: Document, menu: String, name: String) =
          NavMenus.items(doc, menu).find(_.name == name).flatMap(_.href).getOrElse("")
        def stat(r: Round, q: String = "") = s"/roundstat/${r.getId}$q"

        // Final (yes/no, active): the choices that keep it stay on it, the order's too
        val fin = page(app, f.organizer, stat(f.fin))
        href(fin, "rounds-status-menu", "Active") === stat(f.fin, "?status=active")
        href(fin, "rounds-status-menu", "All") === stat(f.fin)
        href(fin, "rounds-type-menu", "Yes/No") === stat(f.fin, "?type=binary")
        RoundsFilter.Order.values.map(o => href(fin, "rounds-order-menu", o match {
          case RoundsFilter.Order.Oldest => "Oldest first"
          case RoundsFilter.Order.Newest => "Newest first"
          case RoundsFilter.Order.ActiveFirst => "Active first"
        })) === Seq(stat(f.fin), stat(f.fin, "?order=newest"), stat(f.fin, "?order=active"))
        // a choice that leaves it out: the first round it lists, oldest first (Not active: Pre, Criteria)
        href(fin, "rounds-status-menu", "Not active") === stat(f.pre, "?status=inactive")
        href(fin, "rounds-type-menu", "Rated") === stat(f.stars, "?type=rated")

        // in each order, from Comments (comments only: never yes/no or rated)
        def typeLinks(order: String) = {
          val doc = page(app, f.organizer, stat(f.comments, order))
          (href(doc, "rounds-type-menu", "Yes/No"), href(doc, "rounds-type-menu", "Rated"))
        }
        // Yes/No lists Pre (stopped) and Final (active); Rated Stars (active) and Criteria (stopped)
        typeLinks("") === ((stat(f.pre, "?type=binary"), stat(f.stars, "?type=rated")))
        typeLinks("?order=newest") === ((stat(f.fin, "?type=binary&order=newest"), stat(f.criteria, "?type=rated&order=newest")))
        typeLinks("?order=active") === ((stat(f.fin, "?type=binary&order=active"), stat(f.stars, "?type=rated&order=active")))

        // exactly one match: Not active within Yes/No is Pre alone
        val binary = page(app, f.organizer, stat(f.fin, "?type=binary"))
        href(binary, "rounds-status-menu", "Not active") === stat(f.pre, "?status=inactive&type=binary")
        // and following it lands there, selected, not marked
        val landed = page(app, f.organizer, stat(f.pre, "?status=inactive&type=binary"))
        statMenu(landed) === Seq("Pre")
        landed.select("#round-stat-rounds .page-item.active").attr("data-round") === f.pre.getId.toString
        landed.select("#round-stat-rounds .wlx-round-outside").size === 0

        // a round opened outside its filter: even the Order items open the first round listed
        val outside = page(app, f.organizer, stat(f.comments, "?type=binary"))
        href(outside, "rounds-order-menu", "Oldest first") === stat(f.pre, "?type=binary")
        href(outside, "rounds-order-menu", "Newest first") === stat(f.fin, "?type=binary&order=newest")
        href(outside, "rounds-order-menu", "Active first") === stat(f.fin, "?type=binary&order=active")
        // and the counts are the rounds each choice lists, as before
        counts(outside, "rounds-type-menu") === Seq("All" -> Some(5), "Yes/No" -> Some(2), "Rated" -> Some(2))
        counts(outside, "rounds-status-menu") === Seq("All" -> Some(2), "Active" -> Some(1), "Not active" -> Some(1))
      }
    }

    "stay on the round and say so when no round matches, with a link to all rounds keeping the order" in {
      testDbApp { app =>
        val f = fixture()
        // contest B: one round, yes/no and active; the choices of no round not offered (count 0)
        val doc = page(app, f.adminB, s"/roundstat/${f.roundB.getId}?order=newest")
        counts(doc, "rounds-type-menu") === Seq("All" -> Some(1), "Yes/No" -> Some(1))
        counts(doc, "rounds-status-menu") === Seq("All" -> Some(1), "Active" -> Some(1))
        doc.select("#rounds-filter-none").size === 0

        // opened with such a choice (a link kept from another contest's): the choice still in its
        // menu, selected at 0, and All at 0 too, the other items of 0 left out
        val none = page(app, f.adminB, s"/roundstat/${f.roundB.getId}?type=rated&order=newest")
        counts(none, "rounds-type-menu") === Seq("All" -> Some(1), "Yes/No" -> Some(1), "Rated" -> Some(0))
        current(none, "rounds-type-menu") === "Rated"
        none.selectFirst("#rounds-type-menu > button").text === "Type: Rated"
        counts(none, "rounds-status-menu") === Seq("All" -> Some(0))
        current(none, "rounds-status-menu") === "All"
        statMenu(none) === Seq("Other contest")
        none.select("#round-stat-rounds .page-item.active .wlx-round-outside").size === 1
        none.select("#rounds-filter-none").text must startWith("No rounds match these choices.")
        none.selectFirst("#rounds-filter-reset").attr("href") === s"/roundstat/${f.roundB.getId}?order=newest"
      }
    }

    "show the rounds' type icons and the inactive ones in grey in its round menu" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/roundstat/${f.fin.getId}")
        def link(r: Round) = doc.selectFirst(s"#round-stat-rounds .page-item[data-round=${r.getId}] > .page-link")
        link(f.pre).hasClass("wlx-round-inactive") must beTrue
        link(f.stars).hasClass("wlx-round-inactive") must beFalse
        icon(link(f.stars)) === (("rated", false, List("wlx-round-star"), "Rated, Active"))
        icon(link(f.pre)) === (("binary", true, List("wlx-round-yes", "wlx-round-no"), "Yes/No, Inactive"))
        spoken(link(f.stars)) === ", Rated, Active"
        // the icons first, then the name
        link(f.stars).child(0).hasClass("wlx-round-icon") must beTrue
        // a comments-only round's chip without the empty slot (its name and "Active" for screen readers)
        link(f.comments).select(".wlx-round-icon").size === 0
        spoken(link(f.comments)) === ", Active"
        link(f.stars).select(".badge").size === 0
      }
    }

    "keep the filter when a juror is paused or resumed from the statistics" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, s"/roundstat/${f.stars.getId}?status=active&order=newest")
        val form = doc.selectFirst(s"form#set-active-form-${f.juror.getId}")
        form.select("input[type=hidden][name=status]").attr("value") === "active"
        form.select("input[type=hidden][name=order]").attr("value") === "newest"
        form.select("input[name=type]").size === 0
        def post(fields: (String, String)*) =
          route(app, FakeRequest(POST, "/round/setrounduser")
            .withFormUrlEncodedBody(Seq("parentId" -> f.stars.getId.toString, "currentId" -> f.juror.getId.toString,
              "setActive" -> "false") ++ fields: _*)
            .withSession(Secured.UserName -> f.admin.email).withCSRFToken).get
        val paused = post("status" -> "active", "order" -> "newest")
        status(paused) === SEE_OTHER
        redirectLocation(paused) === Some(s"/roundstat/${f.stars.getId}?status=active&order=newest")
        redirectLocation(post()) === Some(s"/roundstat/${f.stars.getId}")
        // unknown values are the defaults: a plain statistics page, never another host or path
        redirectLocation(post("status" -> "//evil.example", "order" -> "x")) === Some(s"/roundstat/${f.stars.getId}")
        // without a filter, the form has no such fields
        page(app, f.admin, s"/roundstat/${f.stars.getId}").select(s"form#set-active-form-${f.juror.getId} input[name=status]").size === 0
      }
    }

    "carry the filter from the current round's statistics link to the round" in {
      testDbApp { app =>
        val f = fixture()
        // the organizer's current round: the contest's last active one, Final
        val result = get(app, f.organizer, s"/roundstat?contestId=${f.a.getId}&status=active&order=active")
        status(result) === SEE_OTHER
        redirectLocation(result) === Some(s"/roundstat/${f.fin.getId}?status=active&order=active")
        val plain = get(app, f.organizer, s"/roundstat?contestId=${f.a.getId}")
        redirectLocation(plain) === Some(s"/roundstat/${f.fin.getId}")
      }
    }

    "open from the current round's statistics link the first round the filter lists when it leaves the current one out [2026-11-19]" in {
      testDbApp { app =>
        val f = fixture()
        def target(q: String) = redirectLocation(get(app, f.organizer, s"/roundstat?contestId=${f.a.getId}$q"))
        // Final is active and yes/no: Not active opens the oldest stopped round, or the newest
        target("&status=inactive") === Some(s"/roundstat/${f.pre.getId}?status=inactive")
        target("&status=inactive&order=newest") === Some(s"/roundstat/${f.criteria.getId}?status=inactive&order=newest")
        target("&type=rated&order=active") === Some(s"/roundstat/${f.stars.getId}?type=rated&order=active")
        target("&type=rated&order=newest") === Some(s"/roundstat/${f.criteria.getId}?type=rated&order=newest")
        // one match: opened
        target("&status=inactive&type=binary") === Some(s"/roundstat/${f.pre.getId}?status=inactive&type=binary")
        // a filter that keeps it: Final
        target("&type=binary&order=newest") === Some(s"/roundstat/${f.fin.getId}?type=binary&order=newest")
        // none matches (contest B has one yes/no round): its current round, which then says so
        redirectLocation(get(app, f.adminB, s"/roundstat?contestId=${f.b.getId}&type=rated")) ===
          Some(s"/roundstat/${f.roundB.getId}?type=rated")
        // the Rounds page's Statistics link goes there, with its filter
        page(app, f.admin, rounds(f, "&status=inactive")).select("#menu-statistics").attr("href") ===
          s"/roundstat?contestId=${f.a.getId}&status=inactive"
      }
    }

    "take unknown values as the defaults" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/roundstat/${f.fin.getId}?status=1&type=&order=%00")
        statMenu(doc) === Seq("Pre", "Stars", "Criteria", "Comments", "Final")
      }
    }
  }

  "the filter [A-28]" should {

    "list no round beyond the viewer's unfiltered list, for any choice, on each page" in {
      testDbApp { app =>
        val f = fixture()
        val combos = for {
          st <- RoundsFilter.Status.values; k <- RoundsFilter.Kind.values; o <- RoundsFilter.Order.values
        } yield RoundsFilter(st, k, o)
        def ids(doc: Document, sel: String) = doc.select(sel).asScala.map(_.attr("data-round")).toSet
        // the admin's Rounds page and the organizer's statistics round menu: each of the 27
        // choices lists a part of the page's own unfiltered list, and Active and Not active
        // together list all of it (the filter only narrows what the page already lists)
        val pages = Seq(
          (f.admin, (q: String) => rounds(f, if (q.isEmpty) "" else "&" + q.drop(1)), "tbody tr.round-row"),
          (f.organizer, (q: String) => s"/roundstat/${f.fin.getId}$q", "#round-stat-rounds .page-item"))
        forall(pages) { case (viewer, url, sel) =>
          val all = ids(page(app, viewer, url("")), sel)
          all === Set(f.pre, f.stars, f.criteria, f.comments, f.fin).map(_.getId.toString)
          forall(combos) { c =>
            ids(page(app, viewer, url(c.addTo(""))), sel).subsetOf(all) must beTrue
          }
          (ids(page(app, viewer, url("?status=active")), sel) ++ ids(page(app, viewer, url("?status=inactive")), sel) ++
            Set(f.fin.getId.toString)) === all
        }
        // the statistics page's Status, Type and Order items open only rounds the viewer may see
        // (contest B's round, the newest, never), for every choice, from a round no choice but All keeps
        val contestA = Set(f.pre, f.stars, f.criteria, f.comments, f.fin).map(_.getId.toString)
        def statRoundOf(href: String) = """/roundstat/(\d+)""".r.findFirstMatchIn(href).map(_.group(1)).getOrElse(href)
        forall(combos) { c =>
          val doc = page(app, f.organizer, s"/roundstat/${f.comments.getId}${c.addTo("")}")
          val targets = doc.select("#rounds-status-menu a.dropdown-item, #rounds-type-menu a.dropdown-item, #rounds-order-menu a.dropdown-item")
            .asScala.map(a => statRoundOf(a.attr("href"))).toSet
          targets.nonEmpty must beTrue
          targets.subsetOf(contestA) must beTrue
        }
        // a juror's gallery round menu: their active rounds (GalleryService.viewerRounds), which
        // the params don't change (no filter there)
        def jurorMenu(q: String) =
          page(app, f.juror, s"/gallery/round/${f.fin.getId}/user/${f.juror.getId}/page/1$q")
            .select("nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)").asScala.map(_.attr("href")).toList
        val baseline = jurorMenu("")
        def roundOf(href: String) = """/round/(\d+)/""".r.findFirstMatchIn(href).map(_.group(1)).get
        baseline must not(beEmpty)
        baseline.map(roundOf).toSet === Round.activeRounds(f.juror).map(_.getId.toString).toSet
        baseline.map(roundOf).toSet === Set(f.stars, f.fin).map(_.getId.toString)
        jurorMenu("?status=inactive&type=binary&order=newest") === baseline
        jurorMenu("?status=active&type=rated") === baseline
        // the Rounds page stays the admins': an organizer or a juror is refused, filter or not
        status(get(app, f.organizer, rounds(f, "&status=active"))) === FORBIDDEN
        status(get(app, f.juror, rounds(f, "&status=active"))) === FORBIDDEN
        // and the statistics pages are not a juror's
        status(get(app, f.juror, s"/roundstat/${f.fin.getId}?status=active")) === FORBIDDEN
        // another contest's admin: refused on contest A's round statistics and Rounds page
        status(get(app, f.adminB, s"/roundstat/${f.fin.getId}?status=all")) === FORBIDDEN
        status(get(app, f.adminB, rounds(f, "&status=all"))) === FORBIDDEN
      }
    }
  }

  "the galleries' round menu [O-07]" should {

    "show each round's type icons and the inactive ones in grey, with no filter menus" in {
      testDbApp { app =>
        val f = fixture()
        // the organisers' view of all the jurors' votes: every round of the contest
        val doc = page(app, f.organizer, s"/gallery/round/${f.pre.getId}/user/0/page/1")
        val items = doc.select("nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)").asScala.toList
        items.size === 5
        def item(r: Round) = items.find(_.attr("href").contains(s"/round/${r.getId}/")).get
        item(f.pre).hasClass("wlx-round-inactive") must beTrue
        item(f.pre).hasClass("active") must beTrue
        item(f.fin).hasClass("wlx-round-inactive") must beFalse
        icon(item(f.fin)) === (("binary", false, List("wlx-round-yes", "wlx-round-no"), "Yes/No, Active"))
        icon(item(f.criteria)) === (("rated", true, List("wlx-round-star"), "Rated, Inactive"))
        icon(item(f.comments)) === (("none", false, Nil, ""))
        spoken(item(f.fin)) === ", Yes/No, Active"
        item(f.fin).child(0).hasClass("wlx-round-icon") must beTrue
        item(f.fin).select(".badge").size === 0
        // the menu scrolls inside itself, as the region menu (main.css)
        doc.select("#round-menu > ul.dropdown-menu.wlx-round-menu").size === 1
        doc.select("#rounds-status-menu, #rounds-type-menu, #rounds-order-menu").size === 0

        // a juror's round menu: the same look on their rounds
        val juror = page(app, f.juror, s"/gallery/round/${f.fin.getId}/user/${f.juror.getId}/page/1")
        val jurorItems = juror.select("nav .wlx-nav-filters .dropdown-item:has(.wlx-round-icon)").asScala.toList
        jurorItems.nonEmpty must beTrue
        jurorItems.forall(_.select(".wlx-round-icon i").size >= 1) must beTrue
        juror.select("#rounds-status-menu").size === 0
      }
    }
  }
}
