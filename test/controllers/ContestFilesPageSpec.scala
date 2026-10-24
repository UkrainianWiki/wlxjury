package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.imports.DeletedFiles

/** The contest's files, those deleted on Commons too, and a round's deleted ones; and the
  * round statistics' count of them.
  */
class ContestFilesPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def image(id: Long, name: String) =
    Image(id, s"File:$name.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name.jpg"), None, 800, 600)

  private case class Fixture(contest: ContestJury, admin: User, organizer: User, juror: User, open: Long, finished: Long)

  /** Kept.jpg and Gone.jpg in the contest's category, voted on in an open and a finished
    * round; Gone.jpg deleted on Commons: its votes of the open round moved aside, those of
    * the finished one kept.
    */
  private def fixture(): Fixture = {
    implicit val contest: ContestJury = createContests(10).head
    // signed in by email: an address, else it is taken for a wiki account
    def user(role: String) = userDao.create(User(role, s"$role@example.com", None, Set(role), contestId = contest.id))
    val Seq(admin, organizer, juror) = Seq("admin", "organizer", "jury").map(user)
    val open = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = true)).getId
    val finished = roundDao.create(Round(None, 2, None, contest.getId, createdAt = now, active = false)).getId
    imageDao.batchInsert(Seq(image(1L, "Kept"), image(2L, "Gone")))
    for (pageId <- Seq(1L, 2L); round <- Seq(open, finished))
      selectionDao.create(pageId, rate = 1, juryId = juror.getId, roundId = round)
    DB.localTx { implicit s =>
      val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Test')".updateAndReturnGeneratedKey()
      sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${contest.getId}".update()
      Seq(1L, 2L).foreach(id => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, $id)".update())
      DeletedFiles.markDeleted(Seq(2L))
    }
    Fixture(contest, admin, organizer, juror, open, finished)
  }

  /** Adds files to the contest's category. */
  private def addFiles(contest: ContestJury, files: Seq[Image]): Unit = {
    imageDao.batchInsert(files)
    DB.localTx { implicit s =>
      val categoryId = sql"SELECT category_id FROM contest_jury WHERE id = ${contest.getId}".map(_.long(1)).single().get
      files.foreach(f => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, ${f.pageId})".update())
    }
  }

  private def get(path: String, user: User)(implicit app: Application): (Int, String) = {
    // with a CSRF token, for the forms of the round statistics
    val result = route(app, FakeRequest(GET, path).withSession(Secured.UserName -> user.email).withCSRFToken).get
    (status(result), contentAsString(result))
  }

  "the contest files page" should {

    "show all the contest's files, those deleted on Commons marked" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"/contest/${f.contest.getId}/files", f.admin)

        code === OK
        page must contain("All files (2)")
        page must contain("Deleted on Commons (1)")
        page must contain("Kept.jpg")
        page must contain("Gone.jpg")
        page must contain(s"""data-pageid="2"""")
        "Deleted on Commons \\d{4}-\\d{2}-\\d{2}".r.findAllIn(page).size === 1
        page must contain("/wikipedia/commons/thumb/a/ab/Gone.jpg/") // its cached thumbnails
      }
    }

    "link a file's page on Commons by its encoded title" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, Seq(image(3L, "Who is this?")))
        val (_, page) = get(s"/contest/${f.contest.getId}/files", f.admin)

        page must contain("https://commons.wikimedia.org/wiki/File%3AWho_is_this%3F.jpg")
      }
    }

    "page the files in page id order" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, (3L to 18L).map(id => image(id, s"File$id")))
        val (_, first) = get(s"/contest/${f.contest.getId}/files", f.admin)
        val (_, second) = get(s"/contest/${f.contest.getId}/files?page=2", f.admin)

        first must contain("All files (18)")
        first must contain("File15.jpg")
        first must not contain "File16.jpg"
        """data-pageid="(\d+)"""".r.findAllMatchIn(second).map(_.group(1).toLong).toList === List(16L, 17L, 18L)
        second must contain(s"""<li><a href="/contest/${f.contest.getId}/files">&laquo;</a></li>""") // to page 1
      }
    }

    "show only the files deleted on Commons" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"/contest/${f.contest.getId}/files?deleted=true", f.organizer)

        code === OK
        page must contain("Gone.jpg")
        page must not contain "Kept.jpg"
      }
    }

    "show a round's files deleted on Commons, and those still counted in it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (_, open) = get(s"/contest/${f.contest.getId}/files?round=${f.open}", f.admin)
        open must contain("Gone.jpg")
        open must not contain "Kept.jpg"
        open must not contain "still count"

        val (_, finished) = get(s"/contest/${f.contest.getId}/files?round=${f.finished}", f.admin)
        finished must contain("Gone.jpg")
        finished must contain("1 of them still count in this round")
        finished must not contain "ended before"
      }
    }

    "refuse a juror, and a round of another contest" in {
      testDbApp { implicit app =>
        val f = fixture()
        val other = roundDao.create(Round(None, 1, None, createContests(20).head.getId, createdAt = now, active = true)).getId

        val juror = route(app, FakeRequest(GET, s"/contest/${f.contest.getId}/files").withSession(Secured.UserName -> f.juror.email)).get
        status(juror) === SEE_OTHER
        redirectLocation(juror) must beSome(contain("/error"))

        val otherRound = route(app,
          FakeRequest(GET, s"/contest/${f.contest.getId}/files?round=$other").withSession(Secured.UserName -> f.admin.email)).get
        status(otherRound) === SEE_OTHER
        redirectLocation(otherRound).map(java.net.URLDecoder.decode(_, "UTF-8")) must beSome(contain("Round not found"))
      }
    }
  }

  "the round statistics" should {
    "count the round's files deleted on Commons, linking them for the contest's admins" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"/roundstat/${f.finished}", f.admin)

        code === OK
        page must contain("""id="deleted-files"""")
        page must contain(s"/contest/${f.contest.getId}/files?round=${f.finished}")
        page must contain("1 of them still count in this round")
      }
    }
  }
}
