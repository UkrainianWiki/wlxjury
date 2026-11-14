package controllers

import db.scalikejdbc._
import org.intracer.wmua.{CommentJdbc, ContestJury, Image, Vote}
import org.jsoup.Jsoup
import org.scalawiki.wlx.dto.Monument
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._

import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** A round's lists by author through the router (`author=`, the round filters proposal v4, PR-4):
  * for staff the chip, the files, the tabs within it, the large view's chip and moves, the author
  * links of round pages opening the round's list; for jurors `author=` silently ignored
  * everywhere, with no author in any page; and the refusals, which are the gallery's as without it.
  */
class RoundAuthorFilterSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val A = "O'Brien"
  private val Q = "author=" + URLEncoder.encode(A, UTF_8) // author=O%27Brien
  private val M = "07-101-0001"

  private case class Fixture(contest: ContestJury, round: Round, juror: User, juror2: User, organizer: User,
      admin: User, otherOrganizer: User, root: User) {
    def list(view: String, user: Long, query: String = ""): String = s"/$view/round/${round.getId}/user/$user/page/1$query"
    def gallery(user: Long, query: String = ""): String = list("gallery", user, query)
    def large(user: Long, pageId: Long, query: String = ""): String =
      s"/large/round/${round.getId}/user/$user/pageid/$pageId$query"
  }

  /** A contest of monuments with an open yes/no round. Authors: 1, 2, 3 and 7 by [[A]], 4 by
    * "o'brien" (another author: case counts), 5 by "Ivan", 6 by nobody. Monument [[M]] (a row in
    * the monument list): 1, 2, 4, 5, 7; 3 of 07-101-0002. The juror's votes: 1 unrated, 2 selected,
    * 3, 4, 5, 6 unrated; the second juror's: 1 and 7 unrated. A comment on 3 in the contest. The
    * contest's category has all seven files (its authors list). Another contest has an organizer of
    * its own.
    */
  private def fixture(): Fixture = {
    implicit val contest: ContestJury = createContests(10).head
    val other = createContests(20).head
    def user(name: String, role: String, c: Option[ContestJury] = Some(contest)) =
      userDao.create(User(name, s"$name@example.com", None, Set(role), contestId = c.flatMap(_.id)))
    val (juror, juror2) = (user("juror", "jury"), user("juror2", "jury"))
    val (organizer, admin) = (user("organizer", "organizer"), user("admin", "admin"))
    val otherOrganizer = user("orgb", "organizer", Some(other))
    val root = user("root", "root", None)
    DB.autoCommit { implicit s =>
      sql"UPDATE contest_jury SET monument_id_template = 'UkrainianMonument' WHERE id = ${contest.getId}".update()
      MonumentJdbc.batchInsert(Seq(Monument(page = "Lists", id = M, name = "Castle")))
    }
    val authors = Map(1L -> A, 2L -> A, 3L -> A, 7L -> A, 4L -> "o'brien", 5L -> "Ivan")
    val monuments = Map(1L -> M, 2L -> M, 4L -> M, 5L -> M, 7L -> M, 3L -> "07-101-0002")
    val images = (1L to 7L).map(id =>
      id -> Image(id, s"File:Image$id.jpg", None, None, 640, 480, monuments.get(id), author = authors.get(id))).toMap
    imageDao.batchInsert(images.values.toSeq.sortBy(_.pageId))
    val round = roundDao.create(Round(None, 1, Some("Main"), contest.getId, rates = Round.binaryRound, active = true))
    round.addUsers(Seq(juror, juror2).map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    def votes(u: User, rates: (Long, Int)*) = rates.map { case (p, r) => Vote(images(p), u, round, r) }
    selectionDao.batchInsert(
      votes(juror, 1L -> 0, 2L -> 1, 3L -> 0, 4L -> 0, 5L -> 0, 6L -> 0) ++ votes(juror2, 1L -> 0, 7L -> 0))
    DB.localTx { implicit s =>
      val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Round authors')".updateAndReturnGeneratedKey()
      sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${contest.getId}".update()
      images.keys.foreach(id => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, $id)".update())
    }
    CommentJdbc.create(juror.getId, "juror", round.getId, contest.id, 3L, "a comment")
    Fixture(contest, round, juror, juror2, organizer, admin, otherOrganizer, root)
  }

  private def get(path: String, user: User, script: Boolean = false)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> user.email, Secured.CurrentContestId -> user.contestId.fold("")(_.toString))
      .withHeaders((ACCEPT -> (if (script) "application/json" else "text/html")) +:
        (if (script) Seq("X-Requested-With" -> "XMLHttpRequest") else Nil): _*)).get

  private def page(path: String, user: User)(implicit app: Application): String = {
    val result = get(path, user)
    status(result) === OK
    contentAsString(result)
  }

  /** The files a list page shows, by the large views it links, in order. */
  private def files(page: String): List[Long] =
    """/large/round/\d+/user/\d+/(?:region/[^/]+/)?pageid/(\d+)""".r.findAllMatchIn(page).map(_.group(1).toLong)
      .toList.distinct

  /** The files a file list shows, by their titles. */
  private def rows(page: String): List[Long] =
    """File:Image(\d+)\.jpg""".r.findAllMatchIn(page).map(_.group(1).toLong).toList.distinct

  private def href(page: String, id: String): Option[String] =
    Option(Jsoup.parse(page).getElementById(id)).map(_.attr("href"))

  /** Nothing of an author in a juror's page: no name, no filter, no author link or chip. The
    * page's echoes of the request's own URL (the language and theme forms' and the account menu's
    * `returnTo`, the sign-in URL of a vote) are left out: they repeat what the juror sent,
    * nothing the tool knows.
    */
  private def noAuthor(page: String) = {
    val built = page.replaceAll("""returnTo=[^"&\s]*""", "").replaceAll("""name="returnTo" value="[^"]*"""", "")
    (built must not(contain("Brien"))) and (built must not(contain("author="))) and
      (built must not(contain("author%3D"))) and (built must not(contain("author-filter"))) and
      (built must not(contain("author-links"))) and (built must not(contain("Ivan")))
  }

  "an organizer's lists of one author" should {

    "list only the author's files under a chip, in the gallery, by rate, file list, comments report and CSV" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.gallery(f.juror.getId, s"?$Q&sort=file"), f.organizer)
        files(body) === List(1L, 2L, 3L)
        // a chip in the bar's filter group: the name (whole in its title, cut if long), no count
        // (D9 of the navigation proposal v3)
        val chip = Jsoup.parse(body).selectFirst(".wlx-nav-filters #author-filter")
        chip.selectFirst(".wlx-chip-text").text === "Author: O'Brien"
        chip.selectFirst(".wlx-truncate").attr("title") === "O'Brien"
        href(body, "author-filter-remove") must beSome(f.gallery(f.juror.getId, "?sort=file"))
        // the list's links keep it: the large views, the filters, the views
        body must contain(s"$Q&amp;sort=file")
        // all the jurors' votes
        val all = page(f.gallery(0, s"?$Q"), f.organizer)
        files(all).sorted === List(1L, 2L, 3L, 7L)
        Jsoup.parse(all).selectFirst(".wlx-nav-filters #author-filter .wlx-chip-text").text === "Author: O'Brien"
        files(page(f.list("byrate", 0, s"?$Q"), f.organizer)).sorted === List(1L, 2L, 3L, 7L)
        val fileList = page(f.list("filelist", 0, s"?$Q"), f.organizer)
        rows(fileList).sorted === List(1L, 2L, 3L, 7L)
        fileList must contain("""id="author-filter"""")
        // the comments report: the author's files with comments
        val report = page(f.list("comments", 0, s"?$Q"), f.organizer)
        files(report) === List(3L)
        report must contain("""id="author-filter"""")
        // the CSV of the list
        val csv = contentAsString(get(s"/query?module=csv&user=0&round=${f.round.getId}&$Q", f.organizer))(
          defaultAwaitTimeout, app.materializer)
        csv must contain("Image1.jpg")
        csv must not(contain("Image5.jpg"))
        csv must not(contain("Image4.jpg"))
      }
    }

    "keep it in the filter menu, and combine with the monument and comments filters" in {
      testDbApp { implicit app =>
        val f = fixture()
        // the organizers' filter menu of a juror's votes (no counts: a juror's own tabs, which
        // count within it, are RoundListAuthorSpec's: a staff list is never a juror's own)
        val ofJuror = page(f.gallery(f.juror.getId, s"?$Q&sort=file"), f.organizer)
        ofJuror must contain(f.gallery(f.juror.getId, s"?filter=unrated&amp;$Q&amp;sort=file"))
        files(page(f.gallery(f.juror.getId, s"?filter=unrated&$Q&sort=file"), f.organizer)) === List(1L, 3L)
        // with the monument: 1 and 2 (3 is of another monument)
        val both = page(f.gallery(f.juror.getId, s"?$Q&monument=$M&sort=file"), f.organizer)
        files(both) === List(1L, 2L)
        both must contain("""id="monument-filter"""")
        // both chips in the filter group, the monument's first
        Jsoup.parse(both).select(".wlx-nav-filters .wlx-filter-chip .wlx-chip-text").eachText.toArray.toSeq ===
          Seq("Monument: " + M, "Author: O'Brien")
        // with comments: 3 only; all three: none
        files(page(f.gallery(f.juror.getId, s"?$Q&comments=1&sort=file"), f.organizer)) === List(3L)
        files(page(f.gallery(f.juror.getId, s"?$Q&monument=$M&comments=1&sort=file"), f.organizer)) === Nil
        // the region menu stays, its regions counted within the author
        val regions = contentAsJson(get(s"/regions/round/${f.round.getId}/user/0/region/all?$Q", f.organizer, script = true))
        (regions \ "all" \ "url").as[String] must contain(Q)
        (regions \ "regions").as[Seq[play.api.libs.json.JsObject]].map(r => ((r \ "id").as[String], (r \ "count").as[Int])) ===
          Seq("07" -> 4)
      }
    }

    "show the large view's chip, move and stay within it, and link the author's list from the author card" in {
      testDbApp { implicit app =>
        val f = fixture()
        val body = page(f.large(0, 2L, s"?$Q&sort=file"), f.organizer)
        val doc = Jsoup.parse(body)
        doc.selectFirst("#author-filter").text must contain("Author: O'Brien")
        href(body, "author-filter-remove") must beSome(f.large(0, 2L, "?sort=file"))
        doc.select("[data-nav=up], [data-nav=down]").asScala.map(_.attr("href")).toList must
          contain(allOf(f.large(0, 1L, s"?$Q&sort=file"), f.large(0, 7L, s"?$Q&sort=file")))
        // the author card: the name opens this list with the author, an icon the contest files page
        doc.selectFirst("#author a.author-round-files").attr("href") === f.gallery(0, s"?$Q&sort=file")
        doc.selectFirst("#author a.author-files").attr("href") ===
          routes.GalleryController.contestFiles(f.contest.getId, author = Some(A)).url
        // without the filter the card's name opens the current list with the author
        Jsoup.parse(page(f.large(f.juror.getId, 5L, "?filter=unrated&sort=file"), f.organizer))
          .selectFirst("#author a.author-round-files").attr("href") ===
          f.gallery(f.juror.getId, "?filter=unrated&author=Ivan&sort=file")
        // a file by another author: the filtered list's first page
        val outside = get(f.large(0, 5L, s"?$Q&sort=file"), f.organizer)
        status(outside) === SEE_OTHER
        redirectLocation(outside) must beSome(f.gallery(0, s"?$Q&sort=file"))
      }
    }

    "link a round's file list and authors list to the round's list of the author, the contest's to the contest files" in {
      testDbApp { implicit app =>
        val f = fixture()
        val fileList = Jsoup.parse(page(f.list("filelist", 0, "?sort=rate"), f.organizer))
        val obrien = fileList.select("td.file-author a.author-round-files").asScala.find(_.text == A).get
        obrien.attr("href") must startWith(f.list("filelist", 0, s"?$Q&sort="))
        // two links in a row: no contest files icon
        fileList.select("td.file-author a.author-files").asScala must beEmpty
        val roundAuthors = Jsoup.parse(page(routes.AuthorsController.list(f.contest.getId, f.round.id, None).url, f.organizer))
        val row = roundAuthors.select(".author-links").asScala.find(_.selectFirst("a.author-round-files").text == A).get
        row.selectFirst("a.author-round-files").attr("href") === f.gallery(0, s"?$Q&sort=rate")
        row.selectFirst("a.author-files").attr("href") === routes.GalleryController.contestFiles(f.contest.getId, author = Some(A)).url
        // the round list it opens: all the jurors' files by the author
        files(page(f.gallery(0, s"?$Q&sort=rate"), f.organizer)).sorted === List(1L, 2L, 3L, 7L)
        val contestAuthors = Jsoup.parse(page(routes.AuthorsController.list(f.contest.getId, None, None).url, f.organizer))
        contestAuthors.select("a.author-round-files").asScala must beEmpty
        contestAuthors.select("a.author-files").asScala.map(_.attr("href")) must
          contain(routes.GalleryController.contestFiles(f.contest.getId, author = Some(A)).url)
      }
    }

    "keep it for the contest's admin and root, and refuse another contest's organizer as without it" in {
      testDbApp { implicit app =>
        val f = fixture()
        files(page(f.gallery(0, s"?$Q"), f.admin)).sorted === List(1L, 2L, 3L, 7L)
        files(page(f.gallery(0, s"?$Q"), f.root)).sorted === List(1L, 2L, 3L, 7L)
        status(get(f.gallery(0, s"?$Q"), f.otherOrganizer)) === FORBIDDEN
        status(get(f.large(0, 1L, s"?$Q"), f.otherOrganizer)) === FORBIDDEN
        status(get(f.list("filelist", 0, s"?$Q"), f.otherOrganizer)) === FORBIDDEN
        status(get(s"/regions/round/${f.round.getId}/user/0/region/all?$Q", f.otherOrganizer, script = true)) === FORBIDDEN
        // a user who isn't a juror of the round: the existing refusal
        status(get(f.gallery(f.admin.getId, s"?$Q"), f.organizer)) === status(get(f.gallery(f.admin.getId), f.organizer))
      }
    }
  }

  "a juror's `author=`" should {

    "be ignored in every list: the same files as without it, and no author in the page" in {
      testDbApp { implicit app =>
        val f = fixture()
        val u = f.juror.getId
        foreach(Seq("gallery", "byrate", "filelist", "comments")) { view =>
          val plain = page(f.list(view, u, "?sort=file"), f.juror)
          val withAuthor = page(f.list(view, u, s"?$Q&sort=file"), f.juror)
          (files(withAuthor) === files(plain)) and (rows(withAuthor) === rows(plain)) and noAuthor(withAuthor)
        }
        // file order: no monument id first
        files(page(f.gallery(u, s"?$Q&sort=file"), f.juror)) === List(6L, 1L, 2L, 4L, 5L, 3L)
        // its tabs count all their files
        page(f.gallery(u, s"?$Q&sort=file"), f.juror) must contain("All (6)")
        // the region menu's links
        val regions = contentAsString(get(s"/regions/round/${f.round.getId}/user/$u/region/all?$Q", f.juror, script = true))
        regions must not(contain("author"))
      }
    }

    "be ignored in the large view, its moves and its votes, which go on by paths without it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val u = f.juror.getId
        val large = page(f.large(u, 2L, s"?$Q&sort=file"), f.juror)
        noAuthor(large)
        Jsoup.parse(large).select("[data-nav=up], [data-nav=down]").asScala.map(_.attr("href")).toList must
          contain(allOf(f.large(u, 1L, "?sort=file"), f.large(u, 4L, "?sort=file")))
        // a file by another author is still in their list
        status(get(f.large(u, 5L, s"?$Q&sort=file"), f.juror)) === OK
        val vote = route(app, FakeRequest(POST, s"/large/round/${f.round.getId}/pageid/2/select/-1?$Q&sort=file")
          .withSession(Secured.UserName -> f.juror.email).withCSRFToken).get
        redirectLocation(vote) must beSome(f.large(u, 4L, "?sort=file"))
        VoteJdbc.findBy(2L, u, f.round.getId).map(_.rate) must beSome(-1)
        // a comment's way back
        val comment = route(app, FakeRequest(POST,
          s"/comment/region/all/pageid/2?round=${f.round.getId}&user=$u&$Q&sort=file")
          .withSession(Secured.UserName -> f.juror.email).withFormUrlEncodedBody("id" -> "0", "text" -> "hi").withCSRFToken).get
        redirectLocation(comment) must beSome(f.large(u, 2L, "?sort=file") + "#comments")
      }
    }

    "be ignored in their CSV of their own votes" in {
      testDbApp { implicit app =>
        val f = fixture()
        def csv(query: String) = {
          val result = get(s"/query?module=csv&user=${f.juror.getId}&round=${f.round.getId}$query", f.juror)
          status(result) === OK
          contentAsString(result)(defaultAwaitTimeout, app.materializer)
        }
        val plain = csv("")
        (plain must contain("Image5.jpg")) and (csv(s"&$Q") === plain) and (csv(s"&$Q") must not(contain("Brien")))
      }
    }

    "be ignored for an organizer of another contest who is a juror of this one, in this contest's round" in {
      testDbApp { implicit app =>
        val f = fixture()
        // the other contest's organizer, a juror here too (their session's contest is the other one)
        val u = f.otherOrganizer
        UserContestJdbc.createMembership(u.getId, f.contest.getId, "jury") must beTrue
        f.round.addUsers(Seq(RoundUser(f.round.getId, u.getId, "jury", active = true)))
        val authors = Map(1L -> A, 2L -> A, 5L -> "Ivan")
        selectionDao.batchInsert(Seq(1L, 2L, 5L).map(id => Vote(
          Image(id, s"File:Image$id.jpg", None, None, 640, 480, if (id == 3L) Some("07-101-0002") else Some(M),
            author = authors.get(id)), u, f.round, 0)))
        val plain = page(f.gallery(u.getId, "?sort=file"), u)
        val withAuthor = page(f.gallery(u.getId, s"?$Q&sort=file"), u)
        val large = page(f.large(u.getId, 2L, s"?$Q&sort=file"), u)
        (files(plain) === List(1L, 2L, 5L)) and (files(withAuthor) === files(plain)) and noAuthor(withAuthor) and
          noAuthor(large) and
          // as organizer of their own contest they still filter its rounds: none here, so nothing to list
          (status(get(f.gallery(0, s"?$Q"), u)) === FORBIDDEN)
      }
    }

    "refuse another juror's list and all the jurors' as without it" in {
      testDbApp { implicit app =>
        val f = fixture()
        status(get(f.gallery(f.juror2.getId, s"?$Q"), f.juror)) === FORBIDDEN
        status(get(f.gallery(0, s"?$Q"), f.juror)) === FORBIDDEN
        status(get(f.large(f.juror2.getId, 7L, s"?$Q"), f.juror)) === FORBIDDEN
      }
    }
  }
}
