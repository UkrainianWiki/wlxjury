package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.libs.json.JsObject
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.imports.DeletedFiles

/** The contest files page's monument filter (`monument=`, decision D8 of 2026-11-13), and the
  * page as the contest's jurors get it (decision D9): the files not deleted on Commons, without
  * tabs, deleted files, a round's, the conflicts of interest or any author.
  */
class ContestFilesMonumentSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val Author = "SecretAuthor"

  private case class Fixture(contest: ContestJury, admin: User, organizer: User, juror: User, round: Long) {
    def base: String = s"/contest/${contest.getId}/files"
  }

  /** In the contest's category (a contest of monuments): files 1-5 of region 07, 6 of region 14.
    * 1, 2 and 5 of monument 07-101-0001, 2 deleted on Commons; 3 of 07-101-0002; 4 of 07-101-000A;
    * 6 of 14-101-0001. 1 and 2 by the secret author, 3 by another. The juror has 1 and 3 in an
    * open round.
    */
  private def fixture(): Fixture = {
    implicit val contest: ContestJury = createContests(10).head
    def user(role: String) = userDao.create(User(role, s"$role@example.com", None, Set(role), contestId = contest.id))
    val Seq(admin, organizer, juror) = Seq("admin", "organizer", "jury").map(user)
    def image(id: Long, monument: String, author: Option[String] = None) =
      Image(id, s"File:F$id.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/F$id.jpg"), None, 800, 600,
        monumentId = Some(monument), author = author)
    imageDao.batchInsert(Seq(image(1, "07-101-0001", Some(Author)), image(2, "07-101-0001", Some(Author)),
      image(3, "07-101-0002", Some("HiddenOther")), image(4, "07-101-000A"), image(5, "07-101-0001"), image(6, "14-101-0001")))
    val round = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = true))
    round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
    Seq(1L, 3L).foreach(p => selectionDao.create(p, rate = 0, juryId = juror.getId, roundId = round.getId))
    DB.localTx { implicit s =>
      val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Test')".updateAndReturnGeneratedKey()
      sql"""UPDATE contest_jury SET category_id = $categoryId, monument_id_template = 'UkrainianMonument'
            WHERE id = ${contest.getId}""".update()
      (1L to 6L).foreach(id => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, $id)".update())
      DeletedFiles.markDeleted(Seq(2L))
    }
    RegionCountCache.clear()
    Fixture(contest, admin, organizer, juror, round.getId)
  }

  /** A juror of another contest. */
  private def stranger(): User =
    userDao.create(User("stranger", "stranger@example.com", None, Set("jury"), contestId = createContests(30).head.id))

  private def request(path: String, user: User) = FakeRequest(GET, path).withSession(Secured.UserName -> user.email)

  private def get(path: String, user: User)(implicit app: Application): (Int, String) = {
    val result = route(app, request(path, user)).get
    (status(result), contentAsString(result))
  }

  private def script(path: String, user: User)(implicit app: Application) =
    route(app, request(path, user).withHeaders("X-Requested-With" -> "XMLHttpRequest")).get

  /** The page without the request's own URL, which its forms and links keep to come back
    * (`returnTo`): what a juror typed in it is no data of the page's.
    */
  private def shown(page: String): String =
    page.replaceAll("""name="returnTo" value="[^"]*"""", "").replaceAll("""returnTo=[^"]*""", "")

  private def tiles(page: String): List[Long] =
    """data-pageId="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  /** The region menu's counts (region id -> files) of the page's list. */
  private def regionCounts(page: String, user: User)(implicit app: Application): (Map[String, Int], String) = {
    val url = """id="regionmenu" data-regions-url="([^"]*)"""".r.findFirstMatchIn(page).get.group(1).replace("&amp;", "&")
    val result = script(url, user)
    status(result) === OK
    val body = contentAsString(result)
    ((contentAsJson(result) \ "regions").as[Seq[JsObject]]
      .collect { case r if (r \ "count").asOpt[Int].exists(_ > 0) => (r \ "id").as[String] -> (r \ "count").as[Int] }
      .toMap, body)
  }

  "the contest files page's monument filter" should {

    "list the monument's files only, by its id exactly, with a chip that removes it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"${f.base}?monument=07-101-0001", f.organizer)
        code === OK
        tiles(page) === List(1L, 2L, 5L) // the deleted one too, for organizers
        page must contain("Monument: 07-101-0001 (3)")
        page must contain(s"""id="monument-filter-remove" class="link-light text-decoration-none ms-1" href="${f.base}"""")
        page must contain(s"""href="/monuments/id/07-101-0001?contest=${f.contest.getId}"""")
        page must contain(s"""data-largeUrl="${f.base}/1?monument=07-101-0001"""")

        // exactly: not by case (images.monument_id is utf8mb4_general_ci) or by prefix
        tiles(get(s"${f.base}?monument=07-101-000A", f.organizer)._2) === List(4L)
        tiles(get(s"${f.base}?monument=07-101-000a", f.organizer)._2) === Nil
        tiles(get(s"${f.base}?monument=07-101-000", f.organizer)._2) === Nil
        get(s"${f.base}?monument=07-101-000", f.organizer)._2 must contain("Monument: 07-101-000 (0)")
        // empty: no filter
        tiles(get(s"${f.base}?monument=", f.admin)._2) === (1L to 6L).toList
      }
    }

    "combine with the deleted files, the region, the author, and keep them in its links" in {
      testDbApp { implicit app =>
        val f = fixture()
        tiles(get(s"${f.base}?monument=07-101-0001&deleted=true", f.admin)._2) === List(2L)
        tiles(get(s"${f.base}?monument=07-101-0001&region=14", f.admin)._2) === Nil
        tiles(get(s"${f.base}?monument=07-101-0001&region=07", f.admin)._2) === List(1L, 2L, 5L)
        val (_, byAuthor) = get(s"${f.base}?author=$Author&monument=07-101-0001", f.admin)
        tiles(byAuthor) === List(1L, 2L)
        // each chip removes only its own filter
        byAuthor must contain(s"""id="monument-filter-remove" class="link-light text-decoration-none ms-1" href="${f.base}?author=$Author"""")
        byAuthor must contain(s"""id="author-filter-remove" class="link-light text-decoration-none ms-1" href="${f.base}?monument=07-101-0001"""")
        // the region menu counts the monument's files only
        regionCounts(byAuthor, f.admin)._1 === Map("07" -> 2)
        // the large view moves among them
        val (code, large) = get(s"${f.base}/1?author=$Author&monument=07-101-0001", f.admin)
        code === OK
        large must contain(s"${f.base}/2?author=$Author&amp;monument=07-101-0001")
      }
    }
  }

  "the contest files page for the contest's jurors" should {

    "show the files not deleted on Commons, without tabs, deleted files or authors" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(f.base, f.juror)
        code === OK
        tiles(page) === List(1L, 3L, 4L, 5L, 6L)
        page must contain("""<p id="file-count" class="mb-2">All files (5)</p>""")
        page must not contain "file-tabs"
        page must not contain "coi-tab"
        page must not contain "Deleted on Commons"
        page must not contain "authors-link"
        page must not contain "import-files"
        page must not contain "data-deleted"
        page must not contain Author
        page must not contain "HiddenOther"
        // the region menu's counts leave the deleted file out, and name no author
        val (counts, json) = regionCounts(page, f.juror)
        counts === Map("07" -> 4, "14" -> 1)
        json must not contain Author
        // the organizers' counts are the deleted file's too
        regionCounts(get(f.base, f.organizer)._2, f.organizer)._1 === Map("07" -> 5, "14" -> 1)
      }
    }

    "ignore the deleted files, a round's, the conflicts of interest and an author for them" in {
      testDbApp { implicit app =>
        val f = fixture()
        Seq("deleted=true", s"round=${f.round}", "coi=true", s"author=$Author", s"author=$Author&coi=true&deleted=true")
          .map { q =>
            val (code, page) = get(s"${f.base}?$q", f.juror)
            (q, code, tiles(page), shown(page).contains(Author), page.contains("author-filter"), page.contains("coi-people"))
          } === Seq("deleted=true", s"round=${f.round}", "coi=true", s"author=$Author", s"author=$Author&coi=true&deleted=true")
          .map(q => (q, OK, List(1L, 3L, 4L, 5L, 6L), false, false, false))
        // no author in the menus' JSON either, whatever is asked
        val regions = script(s"${f.base}/regions?author=$Author&coi=true", f.juror)
        status(regions) === OK
        contentAsString(regions) must not contain Author
      }
    }

    "filter by monument for them, without the deleted files" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"${f.base}?monument=07-101-0001&author=$Author", f.juror)
        code === OK
        tiles(page) === List(1L, 5L) // the author ignored, the deleted file left out
        page must contain("Monument: 07-101-0001 (2)")
        shown(page) must not contain Author
      }
    }

    "open a file's large view without its author, with its monument's link; not a deleted file's" in {
      testDbApp { implicit app =>
        val f = fixture()
        DB.autoCommit { implicit s =>
          MonumentJdbc.batchInsert(Seq(org.scalawiki.wlx.dto.Monument(page = "Lists", id = "07-101-0001", name = "Castle")))
        }
        val (code, large) = get(s"${f.base}/1", f.juror)
        code === OK
        large must not contain Author
        large must contain(s"""id="monument-page" href="/monuments/id/07-101-0001?contest=${f.contest.getId}"""")
        // the deleted file's view goes to the list
        val deleted = route(app, request(s"${f.base}/2", f.juror)).get
        status(deleted) === SEE_OTHER
        redirectLocation(deleted) must beSome(f.base)
        // the next file skips it
        large must contain(s"""href="${f.base}/3"""")
        large must not contain s"""href="${f.base}/2""""
      }
    }

    "count their regions from the category's cached counts, as a full count would, and page a region" in {
      testDbApp { implicit app =>
        val f = fixture()
        val before = RegionCountCache.loads.get
        val (_, page) = get(f.base, f.juror)
        val (counts, _) = regionCounts(page, f.juror)
        RegionCountCache.loads.get === before + 1 // the cache, read once, not a full count per menu
        val categoryId = f.contest.categoryId.orElse(ContestJuryJdbc.findById(f.contest.getId).flatMap(_.categoryId)).get
        counts === ImageJdbc.regionCounts(ImageJdbc.FileList.Category(categoryId, deletedOnly = false,
          excluded = ImageJdbc.categoryDeletedIds(categoryId)))
        counts === Map("07" -> 4, "14" -> 1)
        val (_, region) = get(s"${f.base}?region=07", f.juror)
        tiles(region) === List(1L, 3L, 4L, 5L)
      }
    }

    "count their regions the same with the cache switched off" in {
      implicit val cfg: Map[String, String] = Map("regions.countCache" -> "false")
      testDbApp { implicit app =>
        SharedTestDb.truncateAll() // a per-test app doesn't
        val f = fixture()
        regionCounts(get(f.base, f.juror)._2, f.juror)._1 === Map("07" -> 4, "14" -> 1)
      }
    }

    "refuse an inactive membership of the contest, no role or one without access, on the page, its menus and large view" in {
      testDbApp { implicit app =>
        val f = fixture()
        // a former juror ("no": the edit form's no role), and a board member (a role without access)
        val former = stranger()
        UserContestJdbc.createMembership(former.getId, f.contest.getId, User.NO_ROLE)
        val board = userDao.create(User("board", "board@example.com", None, Set("jury"),
          contestId = createContests(40).head.id))
        UserContestJdbc.createMembership(board.getId, f.contest.getId, User.BOARD_ROLE)
        Seq(former, board).flatMap { u =>
          Seq(status(route(app, request(f.base, u)).get), status(route(app, request(s"${f.base}/1", u)).get),
            status(script(s"${f.base}/regions", u)))
        } === Seq.fill(6)(FORBIDDEN)
      }
    }

    "let a juror of several contests open another of theirs, switching the session's contest to it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val both = stranger() // a juror of contest 30, the session's
        UserContestJdbc.createMembership(both.getId, f.contest.getId, "jury")
        val result = route(app, request(f.base, both).withSession(Secured.UserName -> both.email,
          Secured.CurrentContestId -> "30")).get
        status(result) === OK
        tiles(contentAsString(result)) === List(1L, 3L, 4L, 5L, 6L) // the juror's page: no deleted file
        session(result).get(Secured.CurrentContestId) must beSome(f.contest.getId.toString)
        // its menus, a script's request: allowed in that contest, the session left as it is
        val regions = route(app, request(s"${f.base}/regions", both).withSession(Secured.UserName -> both.email,
          Secured.CurrentContestId -> "30").withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        status(regions) === OK
        session(regions).get(Secured.CurrentContestId) must not(beSome(f.contest.getId.toString)) // still 30
      }
    }

    "refuse a juror of another contest, its page, menus and large view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val other = stranger()
        status(route(app, request(s"${f.base}?monument=07-101-0001", other)).get) === FORBIDDEN
        status(route(app, request(s"${f.base}/1", other)).get) === FORBIDDEN
        status(script(s"${f.base}/regions", other)) === FORBIDDEN
        status(script(s"${f.base}/countries", other)) === FORBIDDEN
      }
    }

    "leave the organizers' and admins' page as it was" in {
      testDbApp { implicit app =>
        val f = fixture()
        Seq(f.admin, f.organizer).map { u =>
          val (code, page) = get(f.base, u)
          (code, tiles(page), page.contains("file-tabs"), page.contains("All files (6)"), page.contains("Deleted on Commons (1)"))
        } === Seq.fill(2)((OK, (1L to 6L).toList, true, true, true))
        get(s"${f.base}?deleted=true", f.organizer)._2 must contain("data-deleted")
      }
    }
  }
}
