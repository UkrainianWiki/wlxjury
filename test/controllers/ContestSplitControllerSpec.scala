package controllers

import db.scalikejdbc.{ApiTokenScope, ContestSplitJdbc, PlayTestDb, Round, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import scalikejdbc._
import services.series.ReuseFixtures

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Root's split pages (contest series plan §4.4–§4.6, CY-4): root only (signed out: the login page,
  * nothing queued; a juror, an organizer, the source's admin and a target's admin: 403, logged; a
  * read-only API token: no session, the login page), CSRF on both POSTs; root's confirmation from the
  * page's form runs the split (jobs inline in the tests), the split's page shows it, the old
  * contest-qualified links lead to the moved round, the source's rounds page notes the move, a
  * juror's first page of a moved round switches the session to the target, and Undo reverses it.
  */
class ContestSplitControllerSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(method: String, path: String, by: Option[User], form: Seq[(String, String)] = Nil,
      headers: Seq[(String, String)] = Nil)(implicit app: Application): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders((ACCEPT -> "text/html") +: headers: _*)
    val withSession = by.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      u.contestId.map(Secured.CurrentContestId -> _.toString): _*))
    route(app, withSession.withFormUrlEncodedBody(form: _*).withCSRFToken).get
  }

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
  private def member(role: String, contest: ContestJury) = {
    val u = User.create(User(role, s"$role${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id))
    UserContestJdbc.createMembership(u.getId, contest.getId, role)
    u
  }

  private def splits: Long = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM contest_split".map(_.long(1)).single.apply().getOrElse(0L))
  private def contestOf(r: Round): Long =
    DB.readOnly(implicit s => sql"SELECT contest_id FROM round WHERE id = ${r.getId}".map(_.long(1)).single.apply().get)

  /** The confirmation page's form as a browser would send it: its hidden fields, its checked boxes and
    * radios, and `extra`.
    */
  private def formOf(page: String, extra: Seq[(String, String)] = Nil): Seq[(String, String)] = {
    val doc = Jsoup.parse(page)
    val inputs = doc.select("#split-form input").asScala.toSeq
    inputs.filter(i => i.attr("type") == "hidden" && i.attr("name") != "csrfToken").map(i => i.attr("name") -> i.attr("value")) ++
      inputs.filter(i => (i.attr("type") == "checkbox" || i.attr("type") == "radio") && i.hasAttr("checked") && !i.hasAttr("disabled"))
        .map(i => i.attr("name") -> i.attr("value")) ++ extra
  }

  "the split pages" should {

    "refuse everyone but root, queuing nothing; signed out or with an API token, the login page" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val n = ReuseFixtures.nepal466()
        val c = n.contest.getId
        val gets = Seq(s"/admin/contests/$c/split", "/admin/splits/1")
        val posts = Seq(s"/admin/contests/$c/split", "/admin/splits/1/undo")
        val signedOut = gets.map(p => send(GET, p, None))
        signedOut.map(status(_)).distinct === Seq(SEE_OTHER)
        signedOut.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue
        posts.map(p => status(send(POST, p, None))).forall(_ != OK) must beTrue
        // a read-only API token (D-1b) is no session: the pages aren't the API
        val root = rootUser
        val token = services.ApiTokenService.create(root.getId, "diag", Some(90), scope = ApiTokenScope.LocalRead)
        val withToken = send(GET, s"/admin/contests/$c/split", None, headers = Seq(AUTHORIZATION -> s"Bearer $token"))
        status(withToken) === SEE_OTHER
        redirectLocation(withToken).exists(_.startsWith("/auth")) must beTrue
        status(send(POST, s"/admin/contests/$c/split", None, headers = Seq(AUTHORIZATION -> s"Bearer $token"))) must be_!=(OK)
        splits === 0L
        for (user <- Seq(member("jury", n.contest), member("organizer", n.contest), member("admin", n.contest))) {
          val (results, logs) = LogCapture("security.authz") {
            (gets.map(p => status(send(GET, p, Some(user)))), posts.map(p => status(send(POST, p, Some(user)))))
          }
          results._1.distinct === Seq(FORBIDDEN)
          results._2.distinct === Seq(FORBIDDEN)
          logs("security.authz").count(_.contains("requiredRoles=\"root\"")) === 4
        }
        splits === 0L
      }
    }

    "run root's confirmation, redirect the old links, note it, switch a juror's session, and undo it" in {
      testDbApp { implicit app =>
        db.scalikejdbc.SharedTestDb.truncateAll()
        val n = ReuseFixtures.nepal466()
        val c = n.contest.getId
        val root = rootUser
        val page = contentAsString(send(GET, s"/admin/contests/$c/split", Some(root)))
        val doc = Jsoup.parse(page)
        doc.select("section.split-cluster").asScala.map(_.attr("data-mode")).toSet ===
          Set("stay", "move", "empty", "choose", "never")
        doc.select(s"input[name=pause][value=${n.bhutan.getId}]").hasAttr("checked") must beFalse
        // the runner listed, not ticked (CA23)
        doc.select("input[name^=a]").asScala.map(i => i.attr("value") -> i.hasAttr("checked")) === Seq(n.admin.getId.toString -> false)

        // without pausing the active round: refused, nothing queued
        val refused = send(POST, s"/admin/contests/$c/split", Some(root), formOf(page))
        status(refused) === SEE_OTHER
        redirectLocation(refused) must beSome(s"/admin/contests/$c/split")
        flash(refused).get("error").getOrElse("") must contain(s"Round ${n.bhutan.getId} is active")
        splits === 0L

        val done = send(POST, s"/admin/contests/$c/split", Some(root), formOf(page, Seq("pause" -> n.bhutan.getId.toString,
          "dump" -> "op-2026-11-20")))
        status(done) === SEE_OTHER
        val splitId = DB.readOnly(implicit s => sql"SELECT MAX(id) FROM contest_split".map(_.long(1)).single.apply().get)
        redirectLocation(done) must beSome(s"/admin/splits/$splitId")
        val t2021 = contestOf(n.r2021.head)
        t2021 must be_!=(c)
        val splitPage = Jsoup.parse(contentAsString(send(GET, s"/admin/splits/$splitId", Some(root))))
        splitPage.select("section.split-move").size === 3
        splitPage.select("#split-dump-ref").text() === "op-2026-11-20"
        splitPage.select("#split-undo-form").size === 1

        // a target's admin isn't root: denied there too
        val targetAdmin = member("admin", db.scalikejdbc.ContestJuryJdbc.findById(t2021).get)
        status(send(GET, s"/admin/splits/$splitId", Some(targetAdmin))) === FORBIDDEN
        status(send(POST, s"/admin/splits/$splitId/undo", Some(targetAdmin))) === FORBIDDEN

        // §4.6: the old contest-qualified links lead to the moved round
        val y1 = n.r2021.head.getId
        val files = send(GET, s"/contest/$c/files?round=$y1", Some(root))
        status(files) === SEE_OTHER
        redirectLocation(files).get must startWith(s"/contest/$t2021/files")
        redirectLocation(files).get must contain(s"round=$y1")
        val edit = send(GET, s"/admin/rounds/edit?id=$y1&contestId=$c", Some(root))
        redirectLocation(edit) must beSome(s"/admin/rounds/edit?id=$y1&contestId=$t2021")
        val cache = send(GET, s"/contest/$c/round/$y1/images/cache/status", Some(root), headers = Seq("X-Requested-With" -> "XMLHttpRequest"))
        redirectLocation(cache) must beSome(s"/contest/$t2021/round/$y1/images/cache/status")
        // a round that didn't move: no redirect
        status(send(GET, s"/contest/$c/files?round=${n.own.head.getId}", Some(root))) === OK
        // the source's rounds page notes the move
        val rounds = Jsoup.parse(contentAsString(send(GET, s"/admin/rounds?contestId=$c", Some(root))))
        rounds.select(".split-note[data-out=true]").size === 3

        // a juror's first page of a moved round switches the session to the target
        val juror = n.juror.copy(contestId = Some(c))
        val gallery = send(GET, s"/gallery/round/$y1/user/${n.juror.getId}/page/1", Some(juror))
        session(gallery).get(Secured.CurrentContestId) must beSome(t2021.toString)

        // Undo
        val undone = send(POST, s"/admin/splits/$splitId/undo", Some(root))
        status(undone) === SEE_OTHER
        flash(undone).get("success").getOrElse("") must contain("Rounds back: 4")
        contestOf(n.r2021.head) === c
        Jsoup.parse(contentAsString(send(GET, s"/admin/splits/$splitId", Some(root)))).select("#split-undone").size === 1
        // the old link no longer redirects
        status(send(GET, s"/contest/$c/files?round=$y1", Some(root))) === OK
        status(send(GET, "/admin/splits/999999", Some(root))) === NOT_FOUND
        status(send(GET, "/admin/contests/999999/split", Some(root))) === NOT_FOUND
      }
    }

    "refuse both POSTs without the CSRF token" in {
      controllers.CsrfTestApp.withCsrfApp { app =>
        implicit val a: Application = app
        db.scalikejdbc.SharedTestDb.truncateAll()
        val n = ReuseFixtures.nepal466()
        val root = rootUser
        val sessionToken = controllers.CsrfTestApp.token(app)
        def post(path: String) = route(app, FakeRequest(POST, path)
          .withHeaders(COOKIE -> "PLAY_SESSION=x", ACCEPT -> "text/html")
          .withSession(Secured.UserName -> root.email, "csrfToken" -> sessionToken)
          .withFormUrlEncodedBody("hash" -> "x")).get
        status(post(s"/admin/contests/${n.contest.getId}/split")) === FORBIDDEN
        status(post("/admin/splits/1/undo")) === FORBIDDEN
        splits === 0L
      }
    }
  }
}
