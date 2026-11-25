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
  * the round statistics and the galleries' round menu (not a juror's); the Track menu on Rounds and
  * Statistics (`track=`, an unknown one all rounds); the Rounds page's hint for a merge alone on Main
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
          // and the admin guide's Tracks section at this version, in a new tab, an anchor the guide has
          (doc.select("#trackLabel_help a#round-track-guide").attr("href") ===
            s"https://github.com/UkrainianWiki/wlxjury/blob/${views.Guide.version}/docs/user/admin-guide.md#a-tracks") and
          (doc.select("#trackLabel_help a#round-track-guide").attr("target") === "_blank") and
          (doc.select("#trackLabel_help a#round-track-guide").text must startWith("More about tracks")) and
          (views.ExternalLinksCheck.newTab(doc.selectFirst("#trackLabel_help a#round-track-guide")) must beTrue) and
          (views.ExternalLinksCheck.marked(doc.selectFirst("#trackLabel_help a#round-track-guide")) must beTrue) and
          (new String(java.nio.file.Files.readAllBytes(java.nio.file.Paths.get("docs/user/admin-guide.md")), "UTF-8") must
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

    "show a chip on every round of a contest with two tracks, a root named after itself too" in {
      testDbApp { app =>
        val contest = contestDao.create(None, "Wiki Loves Earth", 2026, "Testland")
        val admin = userDao.create(User("AdminT", "admint@example.com", None, Set("admin"), contestId = contest.id))
        val first = roundDao.create(Round(None, 1, Some("First round"), contest.getId))
        roundDao.create(Round(None, 2, Some("Second round"), contest.getId, previous = Some(first.getId.toString)))
        roundDao.create(Round(None, 3, Some("Video round"), contest.getId))
        val doc = page(app, admin, s"/admin/rounds?contestId=${contest.getId}")
        (rowChips(doc) === Map("First round" -> "First round", "Second round" -> "First round",
          "Video round" -> "Video round")) and (doc.select("#rounds-track-menu").size === 1)
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
            (gallery.select(".wlx-track").size === 0)
        }
      }
    }
  }

  "the round statistics" should {

    "show the organizer the chips and the Track menu, read only, filtering the round menu" in {
      testDbApp { app =>
        val f = fixture()
        val doc = page(app, f.organizer, s"/roundstat/${f.merge.getId}")
        val main = page(app, f.organizer, s"/roundstat/${f.merge.getId}?track=main")
        (chips(doc, "#round-stat-rounds .page-link") === Map("Q-00" -> "Quality", "Qn-00" -> "Quantity",
          "Q-01" -> "Quality", "Q-02" -> "Main")) and
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

    "show the chips to an organizer, and none to a juror" in {
      testDbApp { app =>
        val f = fixture()
        val org = page(app, f.organizer, s"/gallery/round/${f.q00.getId}/user/0/page/1")
        val juror = page(app, f.juror, s"/gallery/round/${f.merge.getId}/user/${f.juror.getId}/page/1")
        def items(d: Document): Seq[Element] = d.select("#round-menu .dropdown-item").asScala.toSeq
        (items(org).map(i => Option(i.selectFirst(".wlx-track")).map(_.ownText)) ===
          Seq(Some("Quality"), Some("Quantity"), Some("Quality"), Some("Main"))) and
          (juror.select("#round-menu .wlx-track").size === 0) and (juror.select(".wlx-track").size === 0) and
          (juror.select("#rounds-track-menu").size === 0)
      }
    }
  }
}
