package controllers

import db.scalikejdbc._
import org.intracer.wmua._
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Round lineage slice A (docs/plans/2026-11-10-round-lineage.md §8.1): the round form's track label
  * (decision 4: offered to the contest's admins, saved on a new or an existing round at any time with
  * `created_at` kept, "Main" in any case or language, kept by a client without the field, refused
  * unchanged to a juror, an organizer and another contest's admin); the Track chip on the Rounds page,
  * the round tabs and the galleries' round menu grouped by track (not a juror's); the Track menu on Rounds and
  * Statistics (`track=`, an unknown one all rounds); each track's own chip colour (two tracks of the
  * contest on one hash token moved apart), the same on every page and in the Track menu's swatches; the Rounds page's hint for a merge alone on Main
  * and its warning for a missing previous round. Organizers see the chips and the menu read only.
  */
class RoundTrackSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val Prefix = "Category:WLM 2026 in Ukraine - "

  private case class Fixture(
      contest: ContestJury,
      q00: Round, qn00: Round, q01: Round, merge: Round,
      admin: User, organizer: User, juror: User, adminB: User)

  private def fixture(): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    TestDb.setSource(contest.getId, Some("Category:Images from Wiki Loves Monuments 2026 in Ukraine"))
    val other = contestDao.create(None, "WLE", 2026, "Ukraine")
    def round(n: Int, name: String, previous: Seq[Round] = Nil, category: Option[String] = None, active: Boolean = false) =
      roundDao.create(Round(None, n, Some(name), contest.getId, active = active, category = category,
        previous = Option.when(previous.nonEmpty)(previous.map(_.getId).mkString(","))))
    val q00 = round(1, "Q-00", category = Some(Prefix + "Quality"))
    val qn00 = round(2, "Qn-00", category = Some(Prefix + "Quantity"))
    val q01 = round(3, "Q-01", Seq(q00))
    val merge = round(4, "Q-02", Seq(q01, qn00), active = true)
    def user(name: String, role: String, c: ContestJury) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = c.id))
    val admin = user("Admin", "admin", contest)
    val organizer = user("Org", "organizer", contest)
    val juror = user("Juror", "jury", contest)
    val adminB = user("AdminB", "admin", other)
    val images = (1L to 2L).map(id => Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(s"12-345-$id")))
    imageDao.batchInsert(images)
    merge.addUsers(Seq(RoundUser(merge.getId, juror.getId, "jury", active = true)))
    selectionDao.batchInsert(images.map(i => Vote(i, juror, merge, 0)))
    Fixture(contest, q00, qn00, q01, merge, admin, organizer, juror, adminB)
  }

  private def get(app: Application, viewer: User, path: String, lang: String = "en"): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email)
      .withHeaders(ACCEPT_LANGUAGE -> lang).withCSRFToken).get

  private def page(app: Application, viewer: User, path: String, lang: String = "en"): Document = {
    val result = get(app, viewer, path, lang)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** The round form as the edit page posts it, with `extra` fields. */
  private def save(app: Application, viewer: User, round: Option[Round], contestId: Long,
      extra: (String, String)*): Future[Result] =
    route(app, FakeRequest(POST, "/admin/rounds/save")
      .withSession(Secured.UserName -> viewer.email)
      .withFormUrlEncodedBody(
        (Seq("number" -> round.fold("0")(_.number.toString), "name" -> round.flatMap(_.name).getOrElse("New"),
          "contest" -> contestId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1", "minMpx" -> "",
          "minSize" -> "", "mediaType" -> "all") ++ round.map("id" -> _.getId.toString) ++
          Option.when(round.isEmpty)("submitToken" -> java.util.UUID.randomUUID().toString))
          .filterNot(f => extra.exists(_._1 == f._1)) ++ extra: _*)
      .withCSRFToken).get

  private def label(r: Round): Option[String] = roundDao.findById(r.getId).flatMap(_.track)

  private def chips(doc: Document, selector: String): Map[String, String] =
    doc.select(selector).asScala.toSeq.flatMap { e =>
      Option(e.selectFirst(".wlx-track")).map { chip =>
        val c = e.clone(); c.select(".wlx-round-spoken, .wlx-track, .wlx-round-icon").remove()
        c.text.trim -> chip.ownText.trim
      }
    }.toMap

  private def rowChips(doc: Document) = chips(doc, "tbody tr.round-row .wlx-round-named")

  private def rows(doc: Document): Seq[String] =
    doc.select("tbody tr.round-row .round-stat-link").asScala.toSeq.map(_.text)

  /** The Track menu's items: (text, count, href). */
  private def trackMenu(doc: Document): Seq[(String, String, String)] =
    doc.select("#rounds-track-menu .dropdown-item").asScala.toSeq.map { a =>
      val text = a.select("span").asScala.find(s => !s.hasClass("wlx-menu-count") && !s.hasClass("wlx-track-swatch"))
        .fold("")(_.text)
      (text, a.select(".wlx-menu-count").text, a.attr("href"))
    }

  /** The build identity's version, as the test classpath has it (build.sbt's `version`). */
  private lazy val buildVersion: String = {
    val stream = getClass.getClassLoader.getResourceAsStream("wlxjury-build.json")
    try (play.api.libs.json.Json.parse(stream) \ "version").as[String] finally stream.close()
  }

  "the admin guide's link (views.Guide)" should {

    "point at the guide as of a version tag" in {
      (views.Guide.admin("a-tracks", "26.11.17.1") ===
        "https://github.com/UkrainianWiki/wlxjury/blob/26.11.17.1/docs/user/admin-guide.md#a-tracks") and
        (views.Guide.versionOf("""{"buildId":"26.11.17.1-02474aa68d91","version":"26.11.17.1","schemaVersion":"115"}""") ===
          Some("26.11.17.1")) and
        (views.Guide.versionOf("{}") must beNone) and (views.Guide.versionOf("not json") must beNone)
    }

    "point a snapshot's link at master, whose tag doesn't exist" in {
      val snapshot = views.Guide.versionOf("""{"buildId":"26.11.18-SNAPSHOT-0123456789ab","version":"26.11.18-SNAPSHOT"}""")
      (snapshot must beNone) and
        (views.Guide.admin("a-tracks", snapshot.getOrElse(views.Guide.Fallback)) ===
          "https://github.com/UkrainianWiki/wlxjury/blob/master/docs/user/admin-guide.md#a-tracks")
    }

    "take the build's version when it is a release's, else master" in {
      val release = buildVersion.matches("""\d+(\.\d+)+""")
      (buildVersion must not(beEmpty)) and
        (views.Guide.version === (if (release) buildVersion else "master"))
    }
  }

  /** The round tabs' links' names (without what their icons say) and titles. */
  private def tabTitles(doc: Document, bar: String): Map[String, String] =
    doc.select(s"$bar .page-item[data-round] > .page-link").asScala.toSeq.map { a =>
      val c = a.clone(); c.select(".wlx-round-spoken, .wlx-round-icon").remove()
      c.text.trim -> a.attr("title")
    }.toMap

  /** The round tabs' groups: each label and its tabs' names, in order. */
  private def groups(doc: Document, bar: String): Seq[(String, Seq[String])] =
    doc.select(s"$bar .wlx-round-tabs-group").asScala.toSeq.map { g =>
      g.selectFirst(".wlx-round-tabs-label").text -> g.select(".page-link").asScala.toSeq.map { a =>
        val c = a.clone(); c.select(".wlx-round-spoken, .wlx-round-icon").remove(); c.text.trim
      }
    }

  "the round tabs (views.RoundTabs)" should {

    "be grouped or not as the Order menu says, the choice kept in the tabs' and menus' links" in {
      testDbApp { app =>
        val f = fixture()
        val ungrouped = page(app, f.organizer, s"/roundstat/${f.merge.getId}?group=none")
        val toggle = ungrouped.getElementById("rounds-group-track")
        (ungrouped.select("#round-stat-rounds .wlx-round-tabs-group").size === 0) and
          (ungrouped.select("#round-stat-rounds .page-link").asScala.map(_.attr("href")).forall(_.contains("group=none")) must beTrue) and
          (toggle.attr("data-grouped") === "false") and (toggle.attr("href") must contain("group=track")) and
          (page(app, f.organizer, s"/roundstat/${f.merge.getId}").getElementById("rounds-group-track").attr("data-grouped") === "true") and
          // the Rounds page's table has no tabs, and its Order menu no "Group by track"
          (page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}").getElementById("rounds-group-track") must beNull)
      }
    }

    "be the authors page's too: \"Whole contest\" first, then the rounds grouped by track, the grouping kept" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/contest/${f.contest.getId}/authors")
        val flat = page(app, f.organizer, s"/contest/${f.contest.getId}/authors?group=none")
        (doc.select("#authors-rounds .wlx-round-tabs-lead .page-link").text === "Whole contest") and
          (groups(doc, "#authors-rounds") === Seq("Main" -> Seq("Q-02"), "Quality" -> Seq("Q-00", "Q-01"),
            "Quantity" -> Seq("Qn-00"))) and
          (doc.select("#authors-rounds .wlx-track").size === 0) and
          (flat.select("#authors-rounds .wlx-round-tabs-group").size === 0) and
          (flat.select("#authors-rounds .page-link").asScala.map(_.attr("href")).forall(_.contains("group=none")) must beTrue)
      }
    }

    "not group by default where every track has one round, the toggle off; ungrouped tabs say their track" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
        TestDb.setSource(contest.getId, Some("Category:Images from Wiki Loves Monuments 2026 in Ukraine"))
        val org = userDao.create(User("OrgS", "orgs@example.com", None, Set("organizer"), contestId = contest.id))
        val q = roundDao.create(Round(None, 1, Some("Q-00"), contest.getId, category = Some(Prefix + "Quality")))
        roundDao.create(Round(None, 2, Some("Qn-00"), contest.getId, category = Some(Prefix + "Quantity"), active = true))
        val doc = page(app, org, s"/roundstat/${q.getId}")
        val toggle = doc.getElementById("rounds-group-track")
        def notes(d: Document) = d.select("#round-stat-rounds .wlx-round-track-note").asScala.map(_.text).toSeq
        (doc.select("#round-stat-rounds .page-link").size === 2) and
          (doc.select("#round-stat-rounds .wlx-round-tabs-group").size === 0) and
          // offered, off
          (toggle.attr("data-grouped") === "false") and (toggle.text must endWith(", off")) and
          (toggle.select(".bi-check2.invisible").size === 1) and
          // ungrouped, each tab's track is said after its name, not only in its tooltip
          (notes(doc) === Seq(", track Quality", ", track Quantity")) and
          // grouped on request: the labels show the tracks, the tabs say them no more
          (notes(page(app, org, s"/roundstat/${q.getId}?group=track")) === Nil)
      }
    }

    "label each group's list by its track, and the rounds named after themselves as other rounds, for screen readers" in {
      testDbApp { app =>
        val f = fixture()
        // a round alone on a track named after it: no label of its own, in a last group
        roundDao.create(Round(None, 5, Some("Solo"), f.contest.getId))
        def otherOf(d: Document, sel: String, label: String, list: String) = d.select(s"$sel $label").asScala.toSeq.map { g =>
          val name = g.selectFirst(".visually-hidden")
          (name.text, g.selectFirst(list).attr("aria-labelledby") == name.id, g.select(".page-link, .dropdown-item").size)
        }
        val stat = page(app, f.organizer, s"/roundstat/${f.merge.getId}")
        val authors = page(app, f.organizer, s"/contest/${f.contest.getId}/authors")
        val gallery = page(app, f.organizer, s"/gallery/round/${f.q00.getId}/user/0/page/1")
        val labelled = stat.select("#round-stat-rounds .wlx-round-tabs-group").asScala.toSeq.map { g =>
          g.selectFirst("ul").attr("aria-labelledby") == g.selectFirst(".wlx-round-tabs-label").id
        }
        (groups(stat, "#round-stat-rounds").map(_._1) === Seq("Main", "Quality", "Quantity")) and
          (labelled === Seq(true, true, true)) and
          (stat.select("#round-stat-rounds [id]").asScala.map(_.id).toSet.size ===
            stat.select("#round-stat-rounds [id]").size) and
          (otherOf(stat, "#round-stat-rounds", ".wlx-round-tabs-other", "ul") === Seq(("Other rounds", true, 1))) and
          (otherOf(authors, "#authors-rounds", ".wlx-round-tabs-other", "ul") === Seq(("Other rounds", true, 1))) and
          (otherOf(gallery, "#round-menu", ".wlx-menu-other", "ul") === Seq(("Other rounds", true, 1))) and
          (gallery.select("#round-menu .dropdown-divider").size === 1) and
          (page(app, f.organizer, s"/roundstat/${f.merge.getId}", "uk")
            .select("#round-stat-rounds .wlx-round-tabs-other > .visually-hidden").text === "Інші раунди")
      }
    }

    "not group a contest with one track, and offer no grouping there" in {
      testDbApp { app =>
        val f = fixture()
        val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Nepal")
        val org = userDao.create(User("OrgN", "orgn@example.com", None, Set("organizer"), contestId = contest.id))
        val first = roundDao.create(Round(None, 1, Some("WLM 2026 Primary"), contest.getId))
        val second = roundDao.create(Round(None, 2, Some("Round 2"), contest.getId, previous = Some(first.getId.toString)))
        val doc = page(app, org, s"/roundstat/${second.getId}")
        (doc.select("#round-stat-rounds .page-link").size === 2) and
          (doc.select("#round-stat-rounds .wlx-round-tabs-group").size === 0) and
          (doc.getElementById("rounds-group-track") must beNull) and
          (doc.select("#round-stat-rounds .page-link[title]").size === 0)
      }
    }
  }

  "the round form's track label" should {

    "be offered to an admin, with the contest's labels and Main, and the round's automatic track in its help" in {
      testDbApp { app =>
        val f = fixture()
        Round.setTrack(f.q01.getId, Some("Second look"))
        val doc = page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}&id=${f.q01.getId}")
        val input = doc.getElementById("trackLabel")
        // no native maxlength (it counts UTF-16 units, the server code points)
        (input must not(beNull)) and (input.hasAttr("maxlength") must beFalse) and (input.attr("value") === "Second look") and
          (doc.select("datalist#round-track-labels option").eachAttr("value").asScala.toSeq === Seq("Main", "Second look")) and
          (doc.select("input[name=trackShown][type=hidden]").attr("value") === "true") and
          // the automatic track in the field (Q-01's from its previous round), the help one short line
          (input.attr("placeholder") === "Automatic: Quality, from its previous rounds") and
          (page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}&id=${f.qn00.getId}").getElementById("trackLabel")
            .attr("placeholder") === "Automatic: Quantity") and
          (doc.getElementById("trackLabel_help").ownText.trim ===
            "Rounds with the same label share a track. Type Main on the main competition's first round.") and
          // and the guide's Tracks section at this version, in a new tab, an anchor the guide has (HelpLinks)
          (doc.select("#trackLabel_help a[data-help-topic=tracks]").attr("href") ===
            s"https://github.com/UkrainianWiki/wlxjury/blob/${views.Guide.version}/docs/user/tracks.md#a-track-label") and
          (doc.select("#trackLabel_help a[data-help-topic=tracks]").attr("target") === "_blank") and
          (doc.select("#trackLabel_help a[data-help-topic=tracks]").text must startWith("More about tracks")) and
          (views.ExternalLinksCheck.newTab(doc.selectFirst("#trackLabel_help a[data-help-topic=tracks]")) must beTrue) and
          (views.ExternalLinksCheck.marked(doc.selectFirst("#trackLabel_help a[data-help-topic=tracks]")) must beTrue) and
          (new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("docs/user/tracks.md")), "UTF-8") must
            contain("<a id=\"a-tracks\"></a>")) and
          // a new round: its automatic track comes once saved
          (page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}").getElementById("trackLabel")
            .attr("placeholder") === "Automatic") and {
            // Main as the page's language names it
            Round.setTrack(f.q00.getId, Some("Main"))
            page(app, f.admin, s"/admin/rounds/edit?contestId=${f.contest.getId}&id=${f.q00.getId}", "uk")
              .getElementById("trackLabel").attr("value") === "Основний"
          }
      }
    }

    "be saved on an existing round, open or stopped, keeping created_at; Main in any case or language" in {
      testDbApp { app =>
        val f = fixture()
        val created = java.sql.Timestamp.valueOf("2020-01-02 03:04:05")
        DB.autoCommit(implicit s => sql"UPDATE round SET created_at = $created".update())
        def createdAt(r: Round) = DB.readOnly(implicit s =>
          sql"SELECT created_at FROM round WHERE id = ${r.getId}".map(_.timestamp(1)).single())
        (status(save(app, f.admin, Some(f.merge), f.contest.getId, "trackLabel" -> "  Final   line ", "trackShown" -> "true")) ===
          SEE_OTHER) and (label(f.merge) === Some("Final   line")) and (createdAt(f.merge) === Some(created)) and
          (status(save(app, f.admin, Some(f.q00), f.contest.getId, "trackLabel" -> "main", "trackShown" -> "true")) === SEE_OTHER) and
          (label(f.q00) === Some("Main")) and
          (status(save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> "Основний", "trackShown" -> "true")) ===
            SEE_OTHER) and (label(f.q01) === Some("Main")) and
          // a client without the field keeps it; an empty field clears it
          (status(save(app, f.admin, Some(f.q01), f.contest.getId)) === SEE_OTHER) and (label(f.q01) === Some("Main")) and
          (status(save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> "", "trackShown" -> "true")) === SEE_OTHER) and
          (label(f.q01) must beNone) and (createdAt(f.q01) === Some(created)) and
          // longer than 64 characters: refused, the form again
          (status(save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> "x" * 65, "trackShown" -> "true")) ===
            BAD_REQUEST) and (label(f.q01) must beNone)
      }
    }

    "count code points, not UTF-16 units: 64 emoji saved; over 64 refused with the field's label and link at the top" in {
      testDbApp { app =>
        val f = fixture()
        val emoji = new String(Character.toChars(0x1F600))
        val saved = status(save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> emoji * 64, "trackShown" -> "true"))
        val refused = save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> ("y" * 65), "trackShown" -> "true")
        val doc = Jsoup.parse(contentAsString(refused))
        val line = doc.select("#round-form-errors li").asScala.find(_.text.contains("64"))
        (saved === SEE_OTHER) and (label(f.q01) === Some(emoji * 64)) and (status(refused) === BAD_REQUEST) and
          (line.map(_.text) must beSome(startWith("Track label (optional): "))) and
          (line.map(_.select("a").attr("href")) must beSome("#trackLabel_field")) and
          (doc.getElementById("trackLabel_field") must not(beNull)) and (label(f.q01) === Some(emoji * 64))
      }
    }

    "store and log a change of case or accent alone" in {
      testDbApp { app =>
        val f = fixture()
        val steps = Seq("Second look", "second look", "Étape", "Etape").map { l =>
          status(save(app, f.admin, Some(f.q01), f.contest.getId, "trackLabel" -> l, "trackShown" -> "true")) -> label(f.q01)
        }
        val logged = ContestChangeJdbc.findByRound(f.contest.getId, f.q01.getId, 50)
          .filter(_.kind == ContestChangeJdbc.RoundTrackChanged).reverse
          .map(c => (c.detail \ "changed" \ "track" \ "to").as[String])
        (steps === Seq(SEE_OTHER -> Some("Second look"), SEE_OTHER -> Some("second look"), SEE_OTHER -> Some("Étape"),
          SEE_OTHER -> Some("Etape"))) and (logged === Seq("Second look", "second look", "Étape", "Etape"))
      }
    }

    "keep the Rounds page's Track filter through a save, whatever the label" in {
      testDbApp { app =>
        val f = fixture()
        def saved(l: String) = route(app, FakeRequest(POST, "/admin/rounds/save?track=category%3AQuality")
          .withSession(Secured.UserName -> f.admin.email)
          .withFormUrlEncodedBody("id" -> f.q01.getId.toString, "number" -> "3", "name" -> "Q-01",
            "contest" -> f.contest.getId.toString, "roles" -> "jury", "distribution" -> "0", "rates" -> "1", "minMpx" -> "",
            "minSize" -> "", "mediaType" -> "all", "trackLabel" -> l, "trackShown" -> "true")
          .withCSRFToken).get
        val back = s"/admin/rounds?contestId=${f.contest.getId}&track=category%3AQuality"
        forall(Seq("main", "", "Second look")) { l =>
          redirectLocation(saved(l)) must beSome(back)
        } and (label(f.q01) === Some("Second look"))
      }
    }

    "be stored with a new round" in {
      testDbApp { app =>
        val f = fixture()
        (status(save(app, f.admin, None, f.contest.getId, "name" -> "Q-03", "trackLabel" -> "Second look",
          "trackShown" -> "true", "previousRound[0]" -> f.merge.getId.toString)) === SEE_OTHER) and
          (roundDao.findByContest(f.contest.getId).find(_.name.contains("Q-03")).flatMap(_.track) === Some("Second look"))
      }
    }

    "be refused, unchanged, to a juror, an organizer and another contest's admin, who get no form" in {
      testDbApp { app =>
        val f = fixture()
        Round.setTrack(f.q00.getId, Some("Main"))
        val refused = Seq(f.juror, f.organizer, f.adminB).map { u =>
          status(save(app, u, Some(f.q00), f.contest.getId, "trackLabel" -> "Hijacked", "trackShown" -> "true"))
        }
        val forms = Seq(f.juror, f.organizer, f.adminB).map { u =>
          status(get(app, u, s"/admin/rounds/edit?contestId=${f.contest.getId}&id=${f.q00.getId}"))
        }
        (refused === Seq(FORBIDDEN, FORBIDDEN, FORBIDDEN)) and (forms === Seq(FORBIDDEN, FORBIDDEN, FORBIDDEN)) and
          (label(f.q00) === Some("Main")) and
          // another contest's admin posting their own contest with this round: refused too
          (status(save(app, f.adminB, Some(f.q00), f.adminB.currentContest.get, "trackLabel" -> "X", "trackShown" -> "true")) ===
            FORBIDDEN) and (label(f.q00) === Some("Main"))
      }
    }
  }

  "a label change in the round's settings log" should {

    def entries(f: Fixture, r: Round): Seq[ContestChange] =
      ContestChangeJdbc.findByRound(f.contest.getId, r.getId, 50)
        .filter(c => ContestChangeJdbc.RoundSettingKinds.contains(c.kind)).reverse

    def changed(c: ContestChange): Map[String, (String, String)] =
      (c.detail \ "changed").as[play.api.libs.json.JsObject].fields.map { case (k, v) =>
        k -> ((v \ "from").get.toString, (v \ "to").get.toString)
      }.toMap

    "be an entry of its own, from and to, with who; none when the label is unchanged" in {
      testDbApp { app =>
        val f = fixture()
        // one save after another: each awaited before the next is sent (sent at once, they ran
        // concurrently, and their entries came in any order)
        val codes = Seq[() => Future[Result]](
          () => save(app, f.admin, Some(f.q00), f.contest.getId, "trackLabel" -> "Main", "trackShown" -> "true"),
          // the same label again: nothing
          () => save(app, f.admin, Some(f.q00), f.contest.getId, "trackLabel" -> "main", "trackShown" -> "true"),
          // a name and a label: two entries
          () => save(app, f.admin, Some(f.q00), f.contest.getId, "name" -> "Q-00 first", "trackLabel" -> "",
            "trackShown" -> "true")
        ).map(send => status(send()))
        val logged = entries(f, f.q00)
        (codes === Seq(SEE_OTHER, SEE_OTHER, SEE_OTHER)) and
          (logged.map(c => (c.kind, c.actorId, (c.detail \ "path").as[String])) === Seq(
            (ContestChangeJdbc.RoundTrackChanged, f.admin.getId, "edit"),
            (ContestChangeJdbc.RoundChanged, f.admin.getId, "edit"),
            (ContestChangeJdbc.RoundTrackChanged, f.admin.getId, "edit"))) and
          (changed(logged.head) === Map("track" -> ("null", "\"Main\""))) and
          (changed(logged(1)).keySet === Set("name")) and
          (changed(logged(2)) === Map("track" -> ("\"Main\"", "null")))
      }
    }

    "show on the round's statistics to an organizer, read only, Main in the page's language" in {
      testDbApp { app =>
        val f = fixture()
        status(save(app, f.admin, Some(f.merge), f.contest.getId, "trackLabel" -> "Main", "trackShown" -> "true")) === SEE_OTHER
        val lines = page(app, f.organizer, s"/roundstat/${f.merge.getId}", "uk")
          .select("#round-setting-changes li.round-setting-change").eachText().asScala.toSeq
        (lines.size === 1) and (lines.head must contain("Мітка треку")) and (lines.head must contain("Основний"))
      }
    }
  }

  "the Rounds page" should {

    "show each round's Track chip, the Track menu in the tracks' order, and the hint for a merge alone on Main" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}")
        (rowChips(doc) === Map("Q-00" -> "Quality", "Qn-00" -> "Quantity", "Q-01" -> "Quality", "Q-02" -> "Main")) and
          (doc.select(".wlx-track[data-track=main]").hasClass("wlx-track-main") must beTrue) and
          (doc.select(".wlx-track").first().attr("title") must startWith("Track: ")) and
          (trackMenu(doc).map(t => (t._1, t._2)) === Seq("All tracks" -> "4", "Main" -> "1", "Quality" -> "2",
            "Quantity" -> "1")) and
          (doc.getElementById("rounds-main-hint").text must contain("Round Q-02 joins rounds of several tracks")) and {
            // labelled Main: the hint goes, the main rounds on Main
            Round.setTrack(f.q00.getId, Some("Main"))
            val labelled = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}")
            (labelled.getElementById("rounds-main-hint") must beNull) and
              (rowChips(labelled) === Map("Q-00" -> "Main", "Qn-00" -> "Quantity", "Q-01" -> "Main", "Q-02" -> "Main"))
          }
      }
    }

    "give each track its own colour, the same in the Track menu's swatches as in the chips" in {
      testDbApp { app =>
        val f = fixture()
        // five tracks: Main, its feeders Quality and Quantity, a category root and a nomination
        roundDao.create(Round(None, 5, Some("F-00"), f.contest.getId, category = Some(Prefix + "Film")))
        roundDao.create(Round(None, 6, Some("Kha-00"), f.contest.getId, specialNomination = Some("Харків")))
        def colour(e: Element): String =
          e.classNames.asScala.filter(c => c.startsWith("wlx-track-") && c != "wlx-track-swatch").mkString(" ")
        def chipColours(doc: Document, selector: String): Map[String, String] =
          doc.select(s"$selector .wlx-track").asScala.toSeq.map(c => c.attr("data-track") -> colour(c)).toMap
        val doc = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}")
        val chipsByTrack = chipColours(doc, "tbody tr.round-row")
        val swatches = doc.select("#rounds-track-menu .dropdown-item").asScala.toSeq.flatMap { a =>
          Option(a.selectFirst(".wlx-track-swatch")).map(s =>
            java.net.URLDecoder.decode(a.attr("href").replaceAll(".*[?&]track=([^&]*).*", "$1"), "UTF-8") -> colour(s))
        }.toMap
        // Quality and Quantity share a hash token: Quality (first by name) keeps it, Quantity takes
        // the next free one, after Film's and Харків's, which keep theirs
        (chipsByTrack === Map("main" -> "wlx-track-main", "category:Quality" -> "wlx-track-2",
          "category:Quantity" -> "wlx-track-5", "category:Film" -> "wlx-track-3", "nomination:Харків" -> "wlx-track-4")) and
          (chipsByTrack.values.toSet.size === 5) and (swatches === chipsByTrack)
      }
    }

    "list a track's rounds, carry the choice in its links, and list all rounds for an unknown track" in {
      testDbApp { app =>
        val f = fixture()
        val quality = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}&track=category%3AQuality")
        val unknown = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}&track=label%3ANope")
        (rows(quality) === Seq("Q-00", "Q-01")) and
          (quality.select("tbody .round-stat-link").first().attr("href") must contain("track=category%3AQuality")) and
          (quality.select("#rounds-track-menu .dropdown-item.active").text must contain("Quality")) and
          (rows(unknown) === Seq("Q-00", "Qn-00", "Q-01", "Q-02"))
      }
    }

    "warn of a previous round that no longer exists; a root named after itself has its chip as its later rounds do" in {
      testDbApp { app =>
        val f = fixture()
        val orphan = roundDao.create(Round(None, 5, Some("Orphan"), f.contest.getId, previous = Some("987654")))
        val next = roundDao.create(Round(None, 6, Some("Orphan 2"), f.contest.getId, previous = Some(orphan.getId.toString)))
        // one of two previous rounds gone: its track comes from the other, so it starts none of its own
        val half = roundDao.create(Round(None, 7, Some("Half"), f.contest.getId, previous = Some(s"${f.q00.getId},987653")))
        val doc = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}")
        (doc.select(s".wlx-round-missing-previous[data-round=${orphan.getId}]").text must
          contain("Previous round 987654 no longer exists: this round starts a track of its own.")) and
          (doc.select(s".wlx-round-missing-previous[data-round=${half.getId}]").text ===
            "Previous round 987653 no longer exists: it is left out when this round's track is chosen.") and
          (rowChips(doc).get("Orphan") === Some("Orphan")) and (rowChips(doc).get("Orphan 2") === Some("Orphan"))
      }
    }

    "show a chip on every round of a contest with two tracks, a first round named after itself too, but none for a round alone on a track named after it" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Earth", 2026, "Testland")
        val admin = userDao.create(User("AdminT", "admint@example.com", None, Set("admin"), contestId = contest.id))
        val first = roundDao.create(Round(None, 1, Some("First round"), contest.getId))
        roundDao.create(Round(None, 2, Some("Second round"), contest.getId, previous = Some(first.getId.toString)))
        val third = roundDao.create(Round(None, 3, Some("Photo round"), contest.getId))
        roundDao.create(Round(None, 4, Some("Photo final"), contest.getId, previous = Some(third.getId.toString)))
        roundDao.create(Round(None, 5, Some("Video round"), contest.getId))
        val doc = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
        // no Main track: the first round's track reads Main (option C, 2026-11-26)
        (rowChips(doc) === Map("First round" -> "Main", "Second round" -> "Main",
          "Photo round" -> "Photo round", "Photo final" -> "Photo round")) and
          (doc.select("#rounds-track-menu").size === 1)
      }
    }

    "call the first round's track Main in a contest without one, in the chips and the Track menu, its key and old links kept (WLA Thatch Ireland 2026)" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves A Future for Thatch", 2026, "Ireland",
          Some("Category:Images from Wiki Loves A Future for Thatch 2026"))
        val admin = userDao.create(User("AdminI", "admini@example.com", None, Set("admin"), contestId = contest.id))
        val organizer = userDao.create(User("OrgI", "orgi@example.com", None, Set("organizer"), contestId = contest.id))
        def round(n: Int, name: String, previous: Option[Round] = None, active: Boolean = false) =
          roundDao.create(Round(None, n, Some(name), contest.getId, active = active, previous = previous.map(_.getId.toString)))
        val r1 = round(1, "Round 1")
        round(2, "2")
        val r3 = round(3, "Photography, Round 2", Some(r1))
        round(4, "Photography, Final Round 3", Some(r3))
        val repeat = round(15, "Heritage, Repeat Round 1")
        val last = round(16, "Heritage, Repeat Round 2", Some(repeat), active = true)
        val path = s"/admin/rounds?contestId=${contest.getId}"
        val doc = page(app, admin, path)
        val uk = page(app, admin, path, "uk")
        val repeatTrack = "Heritage, Repeat Round 1"
        // the track's key stays its name's: old links and the menu's links keep it
        val old = page(app, admin, s"$path&track=name%3ARound%201")
        val stat = page(app, organizer, s"/roundstat/${last.getId}")
        (rowChips(doc) === Map("Round 1" -> "Main", "Photography, Round 2" -> "Main", "Photography, Final Round 3" -> "Main",
          repeatTrack -> repeatTrack, "Heritage, Repeat Round 2" -> repeatTrack)) and
          // its own colour, not Main's (display only)
          (doc.select(".wlx-track[data-track=main]").size === 0) and
          (doc.select(".wlx-track.wlx-track-main").size === 0) and
          // first in the Track menu, as Main is; "2", alone on a track named after it, left out
          (trackMenu(doc).map(t => (t._1, t._2)) === Seq("All tracks" -> "6", "Main" -> "3", repeatTrack -> "2")) and
          (trackMenu(doc)(1)._3 must contain("track=name%3ARound+1")) and
          (rowChips(uk).get("Round 1") === Some("Основний")) and
          (rows(old) === Seq("Round 1", "Photography, Round 2", "Photography, Final Round 3")) and
          (old.select("#rounds-track-menu .dropdown-item.active").text must contain("Main")) and
          (page(app, admin, s"$path&track=main").select("tbody tr.round-row").size === 6) and
          (trackMenu(stat).map(_._1).take(2) === Seq("All tracks", "Main")) and
          // the round tabs' groups: the track shown as Main first and labelled Main, as in the Track menu
          (groups(stat, "#round-stat-rounds").map(_._1) === Seq("Main", repeatTrack)) and {
            // the round form's automatic track keeps its own name: Main copied into the label would
            // make a real Main of it and split the track
            def placeholder(r: Round, lang: String = "en") =
              page(app, admin, s"/admin/rounds/edit?contestId=${contest.getId}&id=${r.getId}", lang)
                .getElementById("trackLabel").attr("placeholder")
            (placeholder(r1) === "Automatic: Round 1") and
              (placeholder(r3) === "Automatic: Round 1, from its previous rounds") and
              (placeholder(r3, "uk") must not(contain("Основний"))) and
              (placeholder(r3) must not(contain("Main")))
          }
      }
    }

    "show a category-first contest's chips and Track menu as before, nothing called Main" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Testland",
          Some("Category:Images from Wiki Loves Monuments 2026 in Testland"))
        val admin = userDao.create(User("AdminC", "adminc@example.com", None, Set("admin"), contestId = contest.id))
        def round(n: Int, name: String, previous: Option[Round] = None, category: Option[String] = None) =
          roundDao.create(Round(None, n, Some(name), contest.getId, category = category, previous = previous.map(_.getId.toString)))
        val q00 = round(1, "Q-00", category = Some(Prefix + "Quality"))
        round(2, "Q-01", Some(q00))
        round(3, "Qn-00", category = Some(Prefix + "Quantity"))
        val video = round(4, "Video round")
        round(5, "Video final", Some(video))
        val doc = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
        (rowChips(doc) === Map("Q-00" -> "Quality", "Q-01" -> "Quality", "Qn-00" -> "Quantity",
          "Video round" -> "Video round", "Video final" -> "Video round")) and
          (trackMenu(doc).map(t => (t._1, t._2)) === Seq("All tracks" -> "5", "Quality" -> "2", "Quantity" -> "1",
            "Video round" -> "2"))
      }
    }

    "show a real-Main contest's name tracks by their own names, Main only for the real one" in {
      testDbApp { app =>
        val f = fixture()
        val r1 = roundDao.create(Round(None, 5, Some("Round 1"), f.contest.getId))
        roundDao.create(Round(None, 6, Some("Round 2"), f.contest.getId, previous = Some(r1.getId.toString)))
        val doc = page(app, f.admin, s"/admin/rounds?contestId=${f.contest.getId}")
        (rowChips(doc) === Map("Q-00" -> "Quality", "Qn-00" -> "Quantity", "Q-01" -> "Quality", "Q-02" -> "Main",
          "Round 1" -> "Round 1", "Round 2" -> "Round 1")) and
          (doc.select(".wlx-track[data-track=main]").size === 1) and
          (trackMenu(doc).map(t => (t._1, t._2)) === Seq("All tracks" -> "6", "Main" -> "1", "Quality" -> "2",
            "Quantity" -> "1", "Round 1" -> "2"))
      }
    }

    "show no chips and no Track menu where every round is alone on a track named after it (WLM Serbia 2026)" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Serbia",
          Some("Category:Prejury selection for Wiki Loves Monuments 2026 in Serbia/Second round"))
        val admin = userDao.create(User("AdminS", "admins@example.com", None, Set("admin"), contestId = contest.id))
        val organizer = userDao.create(User("OrgS", "orgs@example.com", None, Set("organizer"), contestId = contest.id))
        val rounds = Seq("Гласање за најбољу фотографију: 1", "РУНДА 2", "РУНДА ДРУГА", "Вики воли споменике 2026. - РУНДА 2")
          .zipWithIndex.map { case (n, i) => roundDao.create(Round(None, i + 1L, Some(n), contest.getId, active = i == 3)) }
        val list = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
        val stat = page(app, organizer, s"/roundstat/${rounds.last.getId}")
        val gallery = page(app, organizer, s"/gallery/round/${rounds.last.getId}/user/0/page/1")
        (list.select("tbody tr.round-row").size === 4) and (list.select(".wlx-track").size === 0) and
          (list.select("#rounds-track-menu").size === 0) and (stat.select(".wlx-track").size === 0) and
          (stat.select("#rounds-track-menu").size === 0) and (gallery.select(".wlx-track").size === 0)
      }
    }

    "drop only the chips of rounds alone on a track named after them (WLE Kyrgyzstan 2026)" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Earth", 2026, "Kyrgyzstan",
          Some("Category:Images from Wiki Loves Earth 2026 in Kyrgyzstan"))
        val admin = userDao.create(User("AdminK", "admink@example.com", None, Set("admin"), contestId = contest.id))
        def round(n: Int, name: String, category: Option[String] = None, media: Option[String] = None,
            previous: Option[Round] = None) =
          roundDao.create(Round(None, n, Some(name), contest.getId, category = category, mediaType = media,
            previous = previous.map(_.getId.toString)))
        round(1, "Wiki Loves Earth 2026 in Kyrgyzstan 1 round", Some("Category:Images from Wiki Loves Earth 2026 in Kyrgyzstan"))
        round(2, "Wiki Loves Earth 2026 in Kyrgyzstan 2 round",
          Some("Category:Quality images from Wiki Loves Earth 2026 in Kyrgyzstan"), Some("image"))
        round(3, "Wiki Loves Earth 2026 in Kyrgyzstan real round", Some("Category:Images from Wiki Loves Earth 2026 in Kyrgyzstan"))
        round(5, "шорт лист Wiki Loves Earth 2026 in Kyrgyzstan", Some("Category:Commons:Wiki Loves Earth 2026 in Kyrgyzstan/Long list"))
        val longList = round(6, "6", Some("Category:Wiki Loves Earth 2026 in Kyrgyzstan long list"), Some("image"))
        round(9, "Топ 10 раунд", previous = Some(longList))
        val doc = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
        // the three rounds alone on tracks named after them lose their chips; Quality (a category's
        // track, of one round) and the long list keep theirs, and the Track menu stays
        (rowChips(doc) === Map("Wiki Loves Earth 2026 in Kyrgyzstan 2 round" -> "Quality", "6" -> "long list",
          "Топ 10 раунд" -> "long list")) and (doc.select("#rounds-track-menu").size === 1) and
          // the Track menu lists only the tracks that show chips; the others' rounds under All tracks
          (trackMenu(doc).map(t => (t._1, t._2)) === Seq("All tracks" -> "6", "long list" -> "2", "Quality" -> "1")) and {
            // an old link to a track left out still filters, the menu showing it chosen
            val old = page(app, admin, s"/admin/rounds?contestId=${contest.getId}&track=name%3A1%20round")
            (rows(old) === Seq("Wiki Loves Earth 2026 in Kyrgyzstan 1 round")) and
              (old.select("#rounds-track-menu .dropdown-item.active").text must contain("1 round"))
          }
      }
    }

    "show no chips and no Track menu in a contest with one track (WLM Nepal 2026, WLE Slovakia 2026, WLE international)" in {
      testDbApp { app =>
        def contestOf(name: String, country: String, images: String, rounds: Seq[(String, Boolean, Option[String], Option[String])]) = {
          val contest = contestDao.create(None, name, 2026, country, Some(images))
          val admin = userDao.create(User(s"Admin $country", s"admin-${country.toLowerCase}@example.com", None, Set("admin"),
            contestId = contest.id))
          val organizer = userDao.create(User(s"Org $country", s"org-${country.toLowerCase}@example.com", None, Set("organizer"),
            contestId = contest.id))
          val created = rounds.zipWithIndex.foldLeft(Seq.empty[Round]) { case (done, ((n, fromFirst, category, media), i)) =>
            done :+ roundDao.create(Round(None, i + 1L, Some(n), contest.getId, category = category, mediaType = media,
              active = i == rounds.size - 1,
              previous = Option.when(fromFirst)(done.head.getId.toString)))
          }
          (contest, admin, organizer, created)
        }
        val shapes = Seq(
          contestOf("Wiki Loves Monuments", "Nepal", "Category:Images from Wiki Loves Monuments 2026 in Nepal",
            Seq(("WLM 2026 Primary", false, None, None), ("Round 2", true, None, None))),
          contestOf("Wiki Loves Earth", "Slovakia", "Category:Images from Wiki Loves Earth 2026 in Slovakia",
            Seq(("First round", false, Some("Category:Images from Wiki Loves Earth 2026 in Slovakia"), None),
              ("Second round", true, None, Some("image")), ("Jury round", true, None, None), ("Final round", true, None, Some("image")))),
          contestOf("Wiki Loves Earth", "International", "Commons:Wiki Loves Earth 2026/Winners",
            Seq(("1 round: main category", false, None, Some("image")))))
        forall(shapes) { case (contest, admin, organizer, rounds) =>
          val list = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
          val stat = page(app, organizer, s"/roundstat/${rounds.last.getId}")
          val gallery = page(app, organizer, s"/gallery/round/${rounds.last.getId}/user/0/page/1")
          (list.select("tbody tr.round-row").size === rounds.size) and
            (list.select(".wlx-track").size === 0) and (list.select("#rounds-track-menu").size === 0) and
            (stat.select(".wlx-track").size === 0) and (stat.select("#rounds-track-menu").size === 0) and
            (gallery.select(".wlx-track").size === 0) and (gallery.select("#round-menu .dropdown-header").size === 0)
        }
      }
    }
  }

  "the round statistics" should {

    "show the organizer the round tabs grouped by track, each track in its tab's tooltip, and the Track menu, read only" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/roundstat/${f.merge.getId}")
        val main = page(app, f.organizer, s"/roundstat/${f.merge.getId}?track=main")
        // compact tabs: no chip; the track in the link's tooltip
        (doc.select("#round-stat-rounds .wlx-track").size === 0) and
          (tabTitles(doc, "#round-stat-rounds") === Map("Q-00" -> "Track: Quality", "Qn-00" -> "Track: Quantity",
            "Q-01" -> "Track: Quality", "Q-02" -> "Track: Main")) and
          // grouped by default (the contest shows tracks): Main, then its feeders as they join
          (groups(doc, "#round-stat-rounds") === Seq("Main" -> Seq("Q-02"), "Quality" -> Seq("Q-00", "Q-01"),
            "Quantity" -> Seq("Qn-00"))) and
          (trackMenu(doc).map(_._1) === Seq("All tracks", "Main", "Quality", "Quantity")) and
          (trackMenu(doc).find(_._1 == "Quality").get._3 must contain(s"/roundstat/${f.q00.getId}?track=category%3AQuality")) and
          (main.select("#round-stat-rounds .page-link").size === 1) and
          // read only: no label field, no round form link
          (doc.getElementById("trackLabel") must beNull) and (doc.select("a[href*=/admin/rounds/edit]").size === 0)
      }
    }

    "open the first round of a track from the current round's statistics" in {
      testDbApp { app =>
        val f = fixture()
        val result = get(app, f.organizer, s"/roundstat?contestId=${f.contest.getId}&track=category%3AQuantity")
        (status(result) === SEE_OTHER) and
          (redirectLocation(result) must beSome(s"/roundstat/${f.qn00.getId}?track=category%3AQuantity"))
      }
    }

    "leave a track the contest doesn't have out of the current round's statistics link" in {
      testDbApp { app =>
        val f = fixture()
        redirectLocation(get(app, f.organizer, s"/roundstat?contestId=${f.contest.getId}&track=label%3ANope")) must
          beSome(s"/roundstat/${f.merge.getId}")
      }
    }
  }

  "the galleries' round menu" should {

    "group an organizer's rounds by track under dropdown headers, without chips, as the round tabs; a juror's not" in {
      testDbApp { app =>
        val f = fixture()
        val org = page(app, f.organizer, s"/gallery/round/${f.q00.getId}/user/0/page/1")
        val flat = page(app, f.organizer, s"/gallery/round/${f.q00.getId}/user/0/page/1?group=none")
        val juror = page(app, f.juror, s"/gallery/round/${f.merge.getId}/user/${f.juror.getId}/page/1")
        def name(a: Element): String = { val c = a.clone(); c.select(".wlx-round-spoken, .wlx-round-icon").remove(); c.text.trim }
        def menuGroups(d: Document): Seq[(String, Seq[String])] =
          d.select("#round-menu .wlx-menu-group").asScala.toSeq.map { g =>
            val header = g.selectFirst(".dropdown-header")
            header.text -> g.select(".dropdown-item").asScala.toSeq.map(name)
          }
        val labelled = org.select("#round-menu .wlx-menu-group").asScala.toSeq
          .forall(g => g.selectFirst("ul").attr("aria-labelledby") == g.selectFirst("h6.dropdown-header").id)
        (menuGroups(org) === Seq("Main" -> Seq("Q-02"), "Quality" -> Seq("Q-00", "Q-01"), "Quantity" -> Seq("Qn-00"))) and
          (labelled must beTrue) and
          (org.select("#round-menu .wlx-track").size === 0) and
          // the current round marked
          (org.select("#round-menu .dropdown-item.active[aria-current=page]").asScala.map(name) === Seq("Q-00")) and
          // ?group=none: one flat list in the menu's order, still no chips
          (flat.select("#round-menu .dropdown-header").size === 0) and
          (flat.select("#round-menu .dropdown-item").asScala.map(name) === Seq("Q-00", "Qn-00", "Q-01", "Q-02")) and
          (flat.select("#round-menu .wlx-track").size === 0) and
          (juror.select("#round-menu .dropdown-header").size === 0) and
          (juror.select("#round-menu .wlx-track").size === 0) and (juror.select(".wlx-track").size === 0) and
          (juror.select("#rounds-track-menu").size === 0)
      }
    }
  }
}
