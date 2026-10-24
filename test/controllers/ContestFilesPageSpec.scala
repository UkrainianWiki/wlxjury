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

  /** The ids of the files the page's tiles show, in order. */
  private def tiles(page: String): List[Long] =
    """data-pageId="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  /** Makes the contest one of monuments, whose files pages have regions, as its galleries do. */
  private def byMonuments(contest: ContestJury): Unit =
    DB.autoCommit { implicit s =>
      sql"UPDATE contest_jury SET monument_id_template = 'UkrainianMonument' WHERE id = ${contest.getId}".update()
    }

  /** Files 3 to 8 in the contest's category: the odd ones in region 07, the even ones in 14;
    * 5 and 6 deleted on Commons.
    */
  private def addRegionFiles(f: Fixture): Unit = {
    addFiles(f.contest, (3L to 8L).map(id => image(id, s"File$id").copy(monumentId = Some(s"${if (id % 2 == 1) "07" else "14"}-101-000$id"))))
    DB.localTx { implicit s => DeletedFiles.markDeleted(Seq(5L, 6L)) }
  }

  /** The href of the large view's button of that title. */
  private def button(page: String, title: String): Option[String] =
    s"""href="([^"]*)" title="$title""".r.findFirstMatchIn(page).map(_.group(1).replace("&amp;", "&"))

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
        tiles(page) === List(1L, 2L)
        // the deleted file's mark (unitegallery.js shows it on its tile)
        """data-deleted="Deleted on Commons \d{4}-\d{2}-\d{2}"""".r.findAllIn(page).size === 1
        page must contain("/wikipedia/commons/thumb/a/ab/Gone.jpg/") // its cached thumbnail
      }
    }

    "be laid out as a round's gallery: dark, the pager in the navbar, justified tiles opening the large view" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, (3L to 18L).map(id => image(id, s"File$id")))
        val (_, page) = get(s"/contest/${f.contest.getId}/files", f.admin)

        page must contain("""<body class="gallery">""")
        page must contain("""<div id="gallery" class="gallery mw-gallery-traditional" style="display:none;">""")
        page must contain("tiles_justified_row_height: 200") // main.scala.html's unitegallery
        // the pager in the navbar, above the page's content
        page.indexOf("""id="pagenav"""") must be_>(page.indexOf("<nav class=\"navbar"))
        page.indexOf("""id="pagenav"""") must be_<(page.indexOf("contest-files-head"))
        page must contain(s"""data-largeUrl="/contest/${f.contest.getId}/files/1"""")
        page must not contain "data-rate=" // a tile opens its large view's url as it is
      }
    }

    "link a deleted file's page on Commons by its encoded title" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, Seq(image(3L, "Who is this?")))
        DB.localTx { implicit s => DeletedFiles.markDeleted(Seq(3L)) }
        val (_, page) = get(s"/contest/${f.contest.getId}/files", f.admin)

        page must contain("""data-commonsUrl="https://commons.wikimedia.org/wiki/File%3AWho_is_this%3F.jpg"""")
        page must contain("""data-commonsUrl="https://commons.wikimedia.org/wiki/File%3AGone.jpg"""")
        """data-commonsUrl=""".r.findAllIn(page).size === 2 // a kept file's tile opens its large view only
      }
    }

    "page the files in page id order" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, (3L to 18L).map(id => image(id, s"File$id")))
        val (_, first) = get(s"/contest/${f.contest.getId}/files", f.admin)
        val (_, second) = get(s"/contest/${f.contest.getId}/files?page=2", f.admin)

        first must contain("All files (18)")
        tiles(first) === (1L to 15L).toList
        tiles(second) === List(16L, 17L, 18L)
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
        open must contain(s"/roundstat/${f.open}")

        val (_, finished) = get(s"/contest/${f.contest.getId}/files?round=${f.finished}", f.admin)
        finished must contain("Gone.jpg")
        finished must contain("1 of them still count in this round")
        finished must not contain "ended before"
      }
    }

    "show the regions with their files' counts, and a region's files" in {
      testDbApp { implicit app =>
        val f = fixture()
        byMonuments(f.contest)
        addRegionFiles(f)
        val base = s"/contest/${f.contest.getId}/files"

        val (_, all) = get(base, f.admin)
        all must contain("""id="regionnav"""")
        all must contain(s"""href="$base?region=07"""")
        """\(3\)\s+</a>""".r.findAllIn(all).size === 2 // 07 and 14: three files each
        tiles(all) === (1L to 8L).toList

        val (_, region) = get(s"$base?region=07", f.admin)
        tiles(region) === List(3L, 5L, 7L)
        region must contain(s"""data-largeUrl="/contest/${f.contest.getId}/files/3?region=07"""")

        val (_, deleted) = get(s"$base?deleted=true&region=14", f.admin)
        tiles(deleted) === List(6L)
        deleted must contain(s"""href="$base?deleted=true&amp;region=07"""") // the deleted files' regions
      }
    }

    "show a round's deleted files in a region" in {
      testDbApp { implicit app =>
        val f = fixture()
        byMonuments(f.contest)
        addRegionFiles(f)
        // 5 and 6 were deleted after votes in the finished round, which keeps them
        DB.localTx { implicit s =>
          sql"UPDATE images SET deleted_at = NULL WHERE page_id IN (5, 6)".update()
          Seq(5L, 6L).foreach(id => selectionDao.create(id, rate = 1, juryId = f.juror.getId, roundId = f.finished))
          sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id IN (5, 6)".update()
        }
        val base = s"/contest/${f.contest.getId}/files?round=${f.finished}"

        val (_, all) = get(base, f.admin)
        tiles(all).sorted === List(2L, 5L, 6L)
        val (_, region) = get(s"$base&region=07", f.admin)
        tiles(region) === List(5L)
      }
    }

    "be the contest's one files entry of the menu, linking the import and cache for admins only" in {
      testDbApp { implicit app =>
        val f = fixture()
        val id = f.contest.getId
        val entry = s"""<a class="brand" id="menu-contest-files" href="/contest/$id/files">Contest files</a>"""
        val importLink = s"""id="import-files"[^>]*href="/contest/$id/images"""".r

        val (_, admin) = get(s"/contest/$id/files", f.admin)
        admin must contain(entry)
        admin must not contain "Browse files"
        admin must not contain s"""class="brand" href="/contest/$id/images""""
        importLink.findFirstIn(admin) must beSome

        val (_, organizer) = get(s"/contest/$id/files", f.organizer)
        organizer must contain(entry)
        organizer must not contain s"/contest/$id/images"
        organizer must not contain "import-files"

        // from the gallery of a round too, the entry only
        val (_, gallery) = get(s"/gallery/round/${f.open}/user/${f.admin.getId}/page/1", f.admin)
        gallery must contain(entry)
        gallery must not contain s"""href="/contest/$id/images""""

        // the import and cache page links back to the files
        val (code, images) = get(s"/contest/$id/images", f.admin)
        code === OK
        images must contain(s"""<a id="browse-files" href="/contest/$id/files">&larr; Contest files</a>""")
        images must contain(entry)

        val organizerImages = route(app,
          FakeRequest(GET, s"/contest/$id/images").withSession(Secured.UserName -> f.organizer.email)).get
        status(organizerImages) === SEE_OTHER
        redirectLocation(organizerImages) must beSome(contain("/error"))
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

  "a contest file's large view" should {

    "show the file read-only, with its monument, author and page on Commons" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, Seq(image(3L, "Who is this?").copy(author = Some("Some Author"))))
        val (code, page) = get(s"/contest/${f.contest.getId}/files/3", f.organizer)

        code === OK
        page must contain("""<body class="gallery"""")
        page must contain("""id="large_image"""")
        page must contain("""href="https://commons.wikimedia.org/wiki/File%3AWho_is_this%3F.jpg" target="_blank"""")
        page must contain("Some Author")
        page must not contain "Special:Undelete" // not deleted
        // no rating: no votes, buttons or keys
        page must not contain "rate-image"
        page must not contain "/select/"
        page must not contain "thumbs-up"
      }
    }

    "go to the previous and next files of the list, by buttons and keys" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, (3L to 8L).map(id => image(id, s"File$id")))
        val files = s"/contest/${f.contest.getId}/files"
        val (_, page) = get(s"$files/4", f.admin)

        button(page, "Previous image") must beSome(s"$files/3")
        button(page, "Next image") must beSome(s"$files/5")
        button(page, "Back to gallery") must beSome(files)
        page must contain(s"""'down', function (e) { return wlxGo(e, "$files/5"); }""")
        page must contain(s"""'up', function (e) { return wlxGo(e, "$files/3"); }""")
        page must contain(s"""'esc', function (e) { return wlxGo(e, "$files"); }""")
        // the files around it: 2 (Gone.jpg) to 6
        """href="([^"]*)" title="File:File\d\.jpg"""".r.findAllMatchIn(page).map(_.group(1)).toList ===
          List(3L, 4L, 5L, 6L).map(id => s"$files/$id")
        page must contain(s"""href="$files/2" title="File:Gone.jpg"""")

        val (_, first) = get(s"$files/1", f.admin)
        first must contain("""class="btn btn-default btn-sm active disabled" href="#" title="Previous image""")
        first must not contain "'up', function"
      }
    }

    "go back to the gallery page the file is on" in {
      testDbApp { implicit app =>
        val f = fixture()
        addFiles(f.contest, (3L to 18L).map(id => image(id, s"File$id")))
        val files = s"/contest/${f.contest.getId}/files"

        button(get(s"$files/16", f.admin)._2, "Back to gallery") must beSome(s"$files?page=2")
        button(get(s"$files/15", f.admin)._2, "Back to gallery") must beSome(files)
      }
    }

    "stay in the list's region, and in the deleted files" in {
      testDbApp { implicit app =>
        val f = fixture()
        byMonuments(f.contest)
        addRegionFiles(f)
        val files = s"/contest/${f.contest.getId}/files"

        val (_, region) = get(s"$files/5?region=07", f.admin)
        button(region, "Previous image") must beSome(s"$files/3?region=07")
        button(region, "Next image") must beSome(s"$files/7?region=07")
        button(region, "Back to gallery") must beSome(s"$files?region=07")

        // the latest deleted first: 5 and 6 (deleted together, in page id order), then 2
        val (_, deleted) = get(s"$files/6?deleted=true", f.admin)
        button(deleted, "Previous image") must beSome(s"$files/5?deleted=true")
        button(deleted, "Next image") must beSome(s"$files/2?deleted=true")
      }
    }

    "link a deleted file's page and its undeletion on Commons, showing its cached thumbnail" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (code, page) = get(s"/contest/${f.contest.getId}/files/2?deleted=true", f.admin)

        code === OK
        """Deleted on Commons \d{4}-\d{2}-\d{2}""".r.findFirstIn(page) must beSome
        page must contain("""href="https://commons.wikimedia.org/wiki/File%3AGone.jpg" target="_blank"""")
        page must contain("""href="https://commons.wikimedia.org/wiki/Special:Undelete/File%3AGone.jpg" target="_blank"""")
        // from the cache, not from Wikimedia, which no longer has it
        page must contain("""src="/wikipedia/commons/thumb/a/ab/Gone.jpg/""")
        page must not contain "upload.wikimedia.org/wikipedia/commons/thumb/a/ab/Gone.jpg"
        page must contain("wlx-deleted")
      }
    }

    "go to the files page for a file not in the list, and refuse a juror" in {
      testDbApp { implicit app =>
        val f = fixture()
        val files = s"/contest/${f.contest.getId}/files"

        val notDeleted = route(app, FakeRequest(GET, s"$files/1?deleted=true").withSession(Secured.UserName -> f.admin.email)).get
        status(notDeleted) === SEE_OTHER
        redirectLocation(notDeleted) must beSome(s"$files?deleted=true")

        val juror = route(app, FakeRequest(GET, s"$files/1").withSession(Secured.UserName -> f.juror.email)).get
        status(juror) === SEE_OTHER
        redirectLocation(juror) must beSome(contain("/error"))
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
