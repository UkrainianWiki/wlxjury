package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.libs.json.JsObject
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.imports.DeletedFiles

/** The contest files page's monument filter (`monument=`, decision D8 of 2026-11-13), and the
  * page refused to the contest's jurors (the maintainer's decision of 2026-11-14, reversing D9):
  * they browse within their round, whose lists take the monument filter.
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

    "refuse the contest's juror: page, large view, regions and countries" in {
      testDbApp { implicit app =>
        val f = fixture()
        // the pages: the 403 page with its reference, no file of the contest named, and the
        // suggestion of the juror's round's gallery; the session's contest not switched
        Seq(f.base, s"${f.base}?monument=07-101-0001", s"${f.base}/1", s"${f.base}/1?monument=07-101-0001").map { path =>
          val result = route(app, request(path, f.juror)).get
          val page = contentAsString(result)
          (path, status(result), page.contains("Reference"), tiles(page), (1 to 6).exists(n => page.contains(s"F$n.jpg")),
            page.contains(s"/gallery/round/${f.round}/user/${f.juror.getId}/page/1"),
            session(result).get(Secured.CurrentContestId))
        } === Seq(f.base, s"${f.base}?monument=07-101-0001", s"${f.base}/1", s"${f.base}/1?monument=07-101-0001")
          .map(path => (path, FORBIDDEN, true, Nil, false, true, None))
        // the menus, a script's: JSON 403 with a reference, no region or file
        Seq(s"${f.base}/regions", s"${f.base}/countries", s"${f.base}/regions?monument=07-101-0001").map { path =>
          val result = script(path, f.juror)
          (status(result), (contentAsJson(result) \ "error").asOpt[String], (contentAsJson(result) \ "reference").asOpt[String].nonEmpty,
            contentAsString(result).contains("regions"))
        } === Seq.fill(3)((FORBIDDEN, Some("forbidden"), true, false))
      }
    }

    "refuse a juror of several contests another of theirs, without switching the session's contest" in {
      testDbApp { implicit app =>
        val f = fixture()
        val both = stranger() // a juror of contest 30, the session's
        UserContestJdbc.createMembership(both.getId, f.contest.getId, "jury")
        val result = route(app, request(f.base, both).withSession(Secured.UserName -> both.email,
          Secured.CurrentContestId -> "30")).get
        status(result) === FORBIDDEN
        tiles(contentAsString(result)) === Nil
        session(result).get(Secured.CurrentContestId) must not(beSome(f.contest.getId.toString))
        val regions = route(app, request(s"${f.base}/regions", both).withSession(Secured.UserName -> both.email,
          Secured.CurrentContestId -> "30").withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        status(regions) === FORBIDDEN
        session(regions).get(Secured.CurrentContestId) must not(beSome(f.contest.getId.toString))
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
