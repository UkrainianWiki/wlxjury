package controllers

import db.scalikejdbc.{ContestJuryJdbc, ListRepairJdbc, PlayTestDb, SharedTestDb, User}
import org.intracer.wmua.ContestJury
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test._
import services.{FileListRepair, FileListRepairFixture}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Root's "Repair file list" pages (FileListRepairController, V114) and the files gallery's repair
  * filter: every route refused to a juror, an organizer and an admin of the contest (403, a denial
  * logged on `security.authz`) and signed out (the login page; nothing written); the gallery's
  * filter refused to the contest's admin and organizer, who may open the gallery; root sees the
  * overview, previews, confirms (a stale preview refused), undoes and renames; the gallery shows
  * exactly the files that stay or leave.
  */
class FileListRepairControllerSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(method: String, path: String, by: Option[User], form: (String, String)*)(implicit
      app: Application): Future[Result] = {
    val base = FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
    val withSession = by.fold(base)(u => base.withSession(Seq(Secured.UserName -> u.email) ++
      u.contestId.map(Secured.CurrentContestId -> _.toString): _*))
    route(app, withSession.withFormUrlEncodedBody(form: _*).withCSRFToken).get
  }

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
  private def member(role: String, contest: ContestJury) =
    User.create(User(role, s"$role${System.nanoTime()}@example.org", None, Set(role), Some("hash"), contest.id))

  private def fixture(): FileListRepairFixture.F = {
    SharedTestDb.truncateAll()
    insertImagesFor((1L to 22L): _*)
    FileListRepairFixture.create(1L to 22L)
  }

  private def insertImagesFor(ids: Long*): Unit =
    db.scalikejdbc.ImageJdbc.batchInsert(ids.map(id => org.intracer.wmua.Image(id, s"File:Image$id.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image$id.jpg"), None, 640, 480, None)))

  private def galleryFiles(html: String): Seq[Long] =
    Jsoup.parse(html).select("#gallery img").asScala.map(_.attr("data-pageId").toLong).toSeq.sorted

  "the repair pages" should {

    "refuse everyone but root, logging the denial and writing nothing; signed out, the login page" in {
      testDbApp { implicit app =>
        val f = fixture()
        val root = rootUser
        // real repairs first (A to D, by root), so that a refused Undo and Rename have something to undo and rename
        val repairs = Seq(f.a, f.b, f.c, f.d).map { c =>
          val p = FileListRepair.plan(c.getId, None).toOption.get
          FileListRepair.confirm(c.getId, p.counted, p.hash, root.getId).toOption.get
        }
        val pf = FileListRepair.plan(f.f.getId, None).toOption.get
        val gets = Seq("/admin/file-lists", s"/admin/contests/${f.f.getId}/file-list/repair")
        val posts = Seq(
          s"/admin/contests/${f.f.getId}/file-list/repair" ->
            (pf.counted.map("round" -> _.toString) ++ Seq("shown" -> pf.counted.mkString(","), "hash" -> pf.hash)),
          s"/admin/file-lists/repairs/${repairs.head.id}/undo" -> Nil,
          s"/admin/file-lists/${f.blank}/rename" -> Nil)

        val signedOut = gets.map(path => send(GET, path, None))
        val signedOutPosts = posts.map { case (path, form) => send(POST, path, None, form: _*) }

        val refused = for (role <- Seq("jury", "organizer", "admin")) yield {
          val user = member(role, f.a)
          val (statuses, logs) = LogCapture("security.authz") {
            gets.map(path => status(send(GET, path, Some(user)))) ++
              posts.map { case (path, form) => status(send(POST, path, Some(user), form: _*)) }
          }
          (role, statuses.distinct, logs("security.authz").count(_.startsWith("authorization_denied")))
        }
        val title = scalikejdbc.DB.readOnly(implicit s =>
          scalikejdbc.SQL(s"SELECT title FROM category WHERE id = ${f.blank}").map(_.string(1)).single.apply())
        (signedOut.map(status(_)).distinct === Seq(SEE_OTHER)) and
          (signedOut.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue) and
          (signedOutPosts.map(status(_)).distinct === Seq(SEE_OTHER)) and
          (signedOutPosts.flatMap(redirectLocation(_)).forall(_.startsWith("/auth")) must beTrue) and
          (refused === Seq("jury", "organizer", "admin").map(r => (r, Seq(FORBIDDEN), gets.size + posts.size))) and
          // nothing written: F keeps its list, A's repair isn't undone, the untitled row keeps its title
          (ListRepairJdbc.recent(10).map(r => (r.id, r.undone)) === repairs.reverse.map(r => (r.id, false))) and
          (ContestJuryJdbc.findById(f.f.getId).flatMap(_.categoryId) must beSome(f.titled)) and
          (title must beSome("")) and
          (gets.map(path => status(send(GET, path, Some(root)))).distinct === Seq(OK)) and
          (status(send(GET, "/admin/contests/999999/file-list/repair", Some(root))) === NOT_FOUND) and
          // a missing repair or row: the 404 page
          (status(send(POST, "/admin/file-lists/repairs/999999/undo", Some(root))) === NOT_FOUND) and
          (status(send(POST, "/admin/file-lists/999999/rename", Some(root))) === NOT_FOUND)
      }
    }

    "refuse the files gallery's repair filter to anyone but root, logged; show root exactly the files that stay or leave" in {
      testDbApp { implicit app =>
        val f = fixture()
        val id = f.a.getId
        val stays = FileListRepair.GalleryFilter("stays", Seq(f.a1, f.a2)).param
        val leaves = FileListRepair.GalleryFilter("leaves", Seq(f.a1, f.a2)).param
        def enc(v: String) = java.net.URLEncoder.encode(v, "UTF-8")
        val paths = Seq(s"/contest/$id/files?repair=${enc(stays)}", s"/contest/$id/files/${f.file(1)}?repair=${enc(stays)}")
        val json = Seq(s"/contest/$id/files/countries?repair=${enc(stays)}", s"/contest/$id/files/regions?repair=${enc(stays)}")
        // an empty filter is the filter too
        val empty = Seq(s"/contest/$id/files?repair=", s"/contest/$id/files/${f.file(1)}?repair=")
        val refused = for (role <- Seq("organizer", "admin")) yield {
          val user = member(role, f.a)
          val (statuses, logs) = LogCapture("security.authz") {
            val plain = status(send(GET, s"/contest/$id/files", Some(user)))
            (plain, (paths ++ empty).map(p => status(send(GET, p, Some(user)))),
              json.map(p => status(route(app, FakeRequest(GET, p).withHeaders("X-Requested-With" -> "XMLHttpRequest")
                .withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> id.toString)).get)))
          }
          (statuses, logs("security.authz").count(l => l.startsWith("authorization_denied") && l.contains("file-list-repair")))
        }
        val root = rootUser
        val stayed = send(GET, s"/contest/$id/files?repair=${enc(stays)}", Some(root))
        val left = send(GET, s"/contest/$id/files?repair=${enc(leaves)}", Some(root))
        val large = send(GET, s"/contest/$id/files/${f.file(2)}?repair=${enc(stays)}", Some(root))
        val countries = route(app, FakeRequest(GET, s"/contest/$id/files/countries?repair=${enc(leaves)}")
          .withHeaders("X-Requested-With" -> "XMLHttpRequest").withSession(Secured.UserName -> root.email)).get
        (refused === Seq.fill(2)(((OK, Seq.fill(4)(FORBIDDEN), Seq(FORBIDDEN, FORBIDDEN)), 6))) and
          (status(stayed) === OK) and (galleryFiles(contentAsString(stayed)) === f.range(1, 5)) and
          (Jsoup.parse(contentAsString(stayed)).select("#repair-side").attr("data-side") === "stays") and
          (status(left) === OK) and (galleryFiles(contentAsString(left)) === f.range(6, 20)) and
          (status(large) === OK) and (contentAsString(large) must contain(s"repair=${enc(stays)}")) and
          (status(countries) === OK) and (contentAsString(countries) must contain("Ghana")) and
          (status(send(GET, s"/contest/$id/files?repair=bogus", Some(root))) === NOT_FOUND)
      }
    }

    "let root preview, confirm, refuse a stale preview, undo and rename" in {
      testDbApp { implicit app =>
        val f = fixture()
        val root = rootUser
        val overview = Jsoup.parse(contentAsString(send(GET, "/admin/file-lists", Some(root))))
        val listed = overview.select("tr.list-repair-contest").asScala.map(_.attr("data-contest").toLong).toSet
        val own = overview.select("tr.list-repair-own-contest").asScala.map(_.attr("data-contest").toLong).toSeq

        val page = Jsoup.parse(contentAsString(send(GET, s"/admin/contests/${f.a.getId}/file-list/repair", Some(root))))
        val ticked = page.select("#list-repair-rounds input[name=round][checked]").asScala.map(_.attr("value").toLong).toSeq
        val hash = page.select("#list-repair-confirm input[name=hash]").attr("value")
        val counts = Seq("now", "stays", "leaves", "new").map(k => page.select(s"#list-repair-$k").attr("data-count").toInt)

        // the preview with every round ticked counts the whole list
        val all = Jsoup.parse(contentAsString(send(GET,
          s"/admin/contests/${f.a.getId}/file-list/repair?chosen=true&round=${f.a1}&round=${f.aWhole}", Some(root))))

        val shown = page.select("#list-repair-confirm input[name=shown]").attr("value")
        val path = s"/admin/contests/${f.a.getId}/file-list/repair"
        // a round ticked without Recount: refused, and Recount shows the ticked rounds
        val unrecounted = send(POST, path, Some(root),
          (ticked :+ f.aWhole).map("round" -> _.toString) ++ Seq("shown" -> shown, "hash" -> hash, "action" -> "confirm"): _*)
        status(unrecounted)
        val recount = send(POST, path, Some(root), Seq(f.a1, f.aWhole).map("round" -> _.toString) :+ ("action" -> "recount"): _*)
        status(recount)
        // a stale hash is refused
        val stale = send(POST, path, Some(root),
          ticked.map("round" -> _.toString) ++ Seq("shown" -> shown, "hash" -> ("0" * 64), "action" -> "confirm"): _*)
        status(stale)
        val confirmed = send(POST, path, Some(root),
          ticked.map("round" -> _.toString) ++ Seq("shown" -> shown, "hash" -> hash, "action" -> "confirm"): _*)
        // each answered before the next (the actions run on the blocking pool)
        val (staleStatus, confirmedStatus) = (status(stale), status(confirmed))
        val repair = ListRepairJdbc.recent(1).head
        val undone = send(POST, s"/admin/file-lists/repairs/${repair.id}/undo", Some(root))
        status(undone)
        val refusedRename = send(POST, s"/admin/file-lists/${f.blank}/rename", Some(root))

        (listed === Set(f.a, f.b, f.c, f.d, f.f).map(_.getId)) and (own === Seq(f.e.getId)) and
          (ticked === Seq(f.a1, f.a2)) and (counts === Seq(20, 5, 15, 5)) and
          (page.select(s"tr.list-repair-round[data-round=${f.aWhole}]").attr("data-mixed") === "true") and
          (page.select("#list-repair-stays-gallery").attr("href") must contain("repair=stays")) and
          (all.select("#list-repair-stays").attr("data-count") === "20") and
          (Seq(staleStatus, confirmedStatus) === Seq(SEE_OTHER, SEE_OTHER)) and
          (shown === s"${f.a1},${f.a2}") and
          (flash(unrecounted).get(FileListRepairController.ErrorFlash) must beSome("list.repair.recount.first")) and
          (redirectLocation(recount).exists(l => l.startsWith(path) && l.contains(s"round=${f.a1}") &&
            l.contains(s"round=${f.aWhole}") && l.contains("chosen=true")) must beTrue) and
          (flash(stale).get(FileListRepairController.ErrorFlash) must beSome("list.repair.changed")) and
          (flash(confirmed).get(FileListRepairController.DoneFlash) must beSome("list.repair.done")) and
          (repair.filesAdded === 5) and
          (flash(undone).get(FileListRepairController.DoneFlash) must beSome("list.repair.undo.done")) and
          (ContestJuryJdbc.findById(f.a.getId).flatMap(_.categoryId) must beSome(f.blank)) and
          (flash(refusedRename).get(FileListRepairController.ErrorFlash) must beSome("list.repair.rename.in.use"))
      }
    }
  }
}
