package controllers

import db.scalikejdbc._
import org.intracer.wmua.{AverageRule, ContestJury, CriteriaRate, Image, Vote}
import org.jsoup.Jsoup
import play.api.Application
import play.api.libs.json.JsValue
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.imports.DeletedFiles

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** Authors for staff: links from an author's name to their files in the contest and to their
  * Commons user page in the round's and the contest files page's large views, the round's file
  * list and the contest files page's conflicts of interest; the contest files page's author
  * filter (exact: "Ivan", "ivan" and "Iván" are three authors) with its removable chip; the
  * authors list of the contest and of a round, with the round's votes per author. All of it
  * only for the contest's admins, organizers and root: never for jurors, nor for another
  * contest's organizers.
  */
class AuthorsSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, binary: Round, rated: Round, criteria: Round, otherRound: Round,
      admin: User, organizer: User, juror: User, juror2: User, otherOrganizer: User, root: User)

  private val Petro = "Петро Грушко"

  /** Contest A's category: 1 and 5 (deleted on Commons) by "Ivan", 2 by "ivan", 3 by "Iván",
    * 4 by [[Petro]] (the organizer's wiki account `петро_Грушко`, normalized: a conflict of
    * interest), 6 by nobody, 7 by
    * "O'Brien". Its yes/no round: 1 one yes and two no, 2 rejected, 3 unrated, 4 approved
    * twice, 6 unrated, 7 rejected and unrated. Its rated round (5 stars, over the rated votes):
    * 1 rated 4 and 2, 2 unrated, 4 a ✗, 7 rated 2 and unrated. Its criteria round (10 stars, 2
    * criteria): 1 rated 10 + 10 by one juror and only partly by the other, 4 rated 8 + 6 and
    * 6 + 4. Contest B has an organizer and a round of its own.
    */
  private def fixture(): Fixture = {
    SharedTestDb.truncateAll()
    RegionCountCache.clear()
    val a = contestDao.create(None, "WLM", 2026, "Ukraine")
    val b = contestDao.create(None, "WLE", 2026, "Ukraine")
    def user(name: String, role: String, contest: Option[ContestJury], account: Option[String] = None) =
      userDao.create(User(name, s"${name.toLowerCase}@example.com", None, Set(role), contestId = contest.flatMap(_.id),
        wikiAccount = account))
    val admin = user("Admin", "admin", Some(a))
    // the wiki account as an admin may enter it: lowercase first letter, `_` for the space
    val organizer = user("Org", "organizer", Some(a), Some("петро_Грушко"))
    val juror = user("Juror", "jury", Some(a))
    val juror2 = user("Juror2", "jury", Some(a))
    val juror3 = user("Juror3", "jury", Some(a))
    val otherOrganizer = user("OrgB", "organizer", Some(b))
    val root = user("Root", "root", None)
    val authors = Seq(1L -> Some("Ivan"), 2L -> Some("ivan"), 3L -> Some("Iván"), 4L -> Some(Petro), 5L -> Some("Ivan"),
      6L -> None, 7L -> Some("O'Brien"))
    val images = authors.map { case (id, author) => Image(id, s"File:F$id.jpg", None, None, 640, 480, author = author) }
    imageDao.batchInsert(images)
    DB.localTx { implicit s =>
      val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Authors')".updateAndReturnGeneratedKey()
      sql"UPDATE contest_jury SET category_id = $categoryId WHERE id = ${a.getId}".update()
      images.foreach(i => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, ${i.pageId})".update())
      DeletedFiles.markDeleted(Seq(5L))
    }
    def img(id: Long) = images.find(_.pageId == id).get
    val binary = roundDao.create(Round(None, 1, Some("Yes-no"), contestId = a.getId, active = true))
    val rated = roundDao.create(Round(None, 2, Some("Stars"), contestId = a.getId, rates = Round.ratesById(5),
      active = true))
    val custom = CriterionJdbc.add("Composition", None).toOption.get
    val criteria = roundDao.create(Round(None, 3, Some("Criteria"), contestId = a.getId, rates = Round.ratesById(10),
      active = true, criterionIds = Seq(1L, custom.id)))
    val otherRound = roundDao.create(Round(None, 1, Some("B"), contestId = b.getId, active = true))
    Seq(binary, rated, criteria).foreach(r =>
      r.addUsers(Seq(juror, juror2).map(j => RoundUser(r.getId, j.getId, "jury", active = true))))
    def votes(round: Round, rates: (Long, User, Int)*): Unit =
      selectionDao.batchInsert(rates.map { case (id, j, rate) => Vote(img(id), j, round, rate) })
    binary.addUsers(Seq(RoundUser(binary.getId, juror3.getId, "jury", active = true)))
    votes(binary, (1L, juror, 1), (1L, juror2, -1), (1L, juror3, -1), (2L, juror, -1), (3L, juror, 0), (4L, juror, 1), (4L, juror2, 1),
      (6L, juror, 0), (7L, juror, -1), (7L, juror2, 0))
    votes(rated, (1L, juror, 4), (1L, juror2, 2), (2L, juror, 0), (4L, juror, -1), (7L, juror, 2), (7L, juror2, 0))
    votes(criteria, (1L, juror, 0), (1L, juror2, 0), (4L, juror, 0), (4L, juror2, 0))
    def rate(id: Long, j: User, rates: (Long, Int)*): Unit = {
      val vote = VoteJdbc.findBy(id, j.getId, criteria.getId).get
      rates.foreach { case (criterion, r) => CriteriaRate.updateRate(vote.getId, criterion, r) must beTrue }
    }
    rate(1L, juror, 1L -> 10, custom.id -> 10)
    rate(1L, juror2, 1L -> 2)
    rate(4L, juror, 1L -> 8, custom.id -> 6)
    rate(4L, juror2, 1L -> 6, custom.id -> 4)
    Fixture(a, binary, rated, roundDao.findById(criteria.getId).get, otherRound, admin, organizer, juror, juror2,
      otherOrganizer, root)
  }

  private def request(app: Application, viewer: User, path: String, json: Boolean = false, lang: String = "en")
      : Future[Result] =
    route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> viewer.email, Secured.CurrentContestId -> viewer.contestId.fold("")(_.toString))
      .withHeaders("Accept" -> (if (json) "application/json" else "text/html"), "Accept-Language" -> lang)
      .withCSRFToken).get

  private def get(app: Application, viewer: User, path: String, lang: String = "en"): (Int, String) = {
    val result = request(app, viewer, path, lang = lang)
    (status(result), contentAsString(result))
  }

  private def filesUrl(f: Fixture, author: Option[String] = None, deleted: Boolean = false): String =
    routes.GalleryController.contestFiles(f.contest.getId, deleted = deleted, author = author).url

  private def authorsUrl(f: Fixture, round: Option[Round] = None, sort: Option[String] = None): String =
    routes.AuthorsController.list(f.contest.getId, round.flatMap(_.id), sort).url

  /** The ids of the files the page's tiles show, in order. */
  private def tiles(page: String): List[Long] =
    """data-pageId="(\d+)"""".r.findAllMatchIn(page).map(_.group(1).toLong).toList

  /** The authors' links of a page: (name, its files' URL, its Commons URL; "" without one). */
  private def authorLinks(page: String): Seq[(String, String, String)] =
    Jsoup.parse(page).select(".author-links").asScala.toSeq.map { e =>
      (e.selectFirst("a.author-files").text, e.selectFirst("a.author-files").attr("href"),
        Option(e.selectFirst("a.author-commons")).fold("")(_.attr("href")))
    }

  /** Adds files to contest A's category. */
  private def addFiles(f: Fixture, files: Seq[Image]): Unit = {
    imageDao.batchInsert(files)
    DB.localTx { implicit s =>
      val categoryId = sql"SELECT category_id FROM contest_jury WHERE id = ${f.contest.getId}".map(_.long(1)).single().get
      files.foreach(i => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, ${i.pageId})".update())
    }
    RegionCountCache.clear()
  }

  /** The column heads of the authors list. */
  private def heads(page: String): Seq[String] =
    Jsoup.parse(page).select("#authors-table th").asScala.map(_.text).toSeq

  private def petroLink(f: Fixture) =
    (Petro, filesUrl(f, Some(Petro)), views.WikiLinks.commonsUserUrl(Petro))

  /** The authors list's rows: author -> its cells' texts from the files' count on. */
  private def rows(page: String): Seq[(String, Seq[String])] =
    Jsoup.parse(page).select("#authors-table tbody tr").asScala.toSeq.map { tr =>
      val cells = tr.select("td").asScala.toSeq.map(_.text)
      (cells(1).stripSuffix(" Commons ↗"), cells.drop(2))
    }

  "the author links" should {

    "be on the round's large view, the contest files large view, the round's file list and the conflicts of interest" in {
      testDbApp { app =>
        val f = fixture()
        val (_, large) = get(app, f.organizer, s"/large/round/${f.binary.getId}/user/0/pageid/4")
        val (_, contestLarge) = get(app, f.organizer, s"/contest/${f.contest.getId}/files/4")
        val (_, fileList) = get(app, f.organizer, s"/filelist/round/${f.binary.getId}/user/0/page/1")
        val (_, coi) = get(app, f.organizer, routes.GalleryController.contestFiles(f.contest.getId, coi = true).url)
        (authorLinks(large) === Seq(petroLink(f))) and
          (authorLinks(contestLarge) === Seq(petroLink(f))) and
          (authorLinks(fileList) must contain(petroLink(f))) and
          (authorLinks(fileList).map(_._1) must contain(allOf("Ivan", "ivan", "Iván", "O'Brien"))) and
          (Jsoup.parse(coi).select("#coi-people .author-links").asScala.map(_.selectFirst("a.author-files").text) ===
            Seq(Petro)) and
          (authorLinks(coi) === Seq(petroLink(f))) and (tiles(coi) === List(4L)) and
          (tiles(get(app, f.organizer, authorLinks(coi).head._2)._2) === List(4L))
      }
    }

    "open the Commons user page in a new tab, and link the files with the author as stored" in {
      testDbApp { app =>
        val f = fixture()
        val (_, large) = get(app, f.organizer, s"/contest/${f.contest.getId}/files/7")
        val commons = Jsoup.parse(large).selectFirst("#author a.author-commons")
        (commons.attr("href") === "https://commons.wikimedia.org/wiki/User:O%27Brien") and
          (commons.attr("target") === "_blank") and (commons.attr("rel") === "noopener") and
          (Jsoup.parse(large).selectFirst("#author a.author-files").attr("href") === filesUrl(f, Some("O'Brien")))
      }
    }

    "show a juror no author anywhere: large view, its JSON, file list" in {
      testDbApp { app =>
        val f = fixture()
        val r = f.binary.getId
        val u = f.juror.getId
        val (_, large) = get(app, f.juror, s"/large/round/$r/user/$u/pageid/4")
        val json = request(app, f.juror, s"/large/round/$r/user/$u/pageid/4", json = true)
        val (_, list) = get(app, f.juror, s"/filelist/round/$r/user/$u/page/1")
        val jsonAuthors = contentAsJson(json).as[Seq[JsValue]].map(v => (v \ "image" \ "author").asOpt[String])
        (large must not(contain("Грушко"))) and (large must not(contain("author-files"))) and
          (large must not(contain("commons.wikimedia.org/wiki/User:"))) and
          (jsonAuthors must contain(beNone).forall) and
          (list must not(contain("author-links"))) and (list must not(contain("Грушко"))) and
          (list must not(contain("O'Brien")))
      }
    }
  }

  "the contest files page's author filter" should {

    "list the author's files only, matched exactly: case and accents count" in {
      testDbApp { app =>
        val f = fixture()
        (tiles(get(app, f.organizer, filesUrl(f, Some("Ivan")))._2) === List(1L, 5L)) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("ivan")))._2) === List(2L)) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("Iván")))._2) === List(3L)) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("IVAN")))._2) === Nil) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("Ivan ")))._2) === Nil) and
          (tiles(get(app, f.organizer, filesUrl(f, Some(Petro)))._2) === List(4L)) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("Ivan"), deleted = true))._2) === List(5L))
      }
    }

    "show a chip with the author and the files' count, which removes the filter" in {
      testDbApp { app =>
        val f = fixture()
        val (code, page) = get(app, f.organizer, filesUrl(f, Some("Ivan")))
        val chip = Jsoup.parse(page).selectFirst("#author-filter")
        val remove = chip.selectFirst("#author-filter-remove").attr("href")
        val (_, all) = get(app, f.organizer, remove)
        (code === OK) and (chip.selectFirst(".author-chip").text must startWith("Author: Ivan (2)")) and
          (remove === filesUrl(f)) and (tiles(all) === List(1L, 2L, 3L, 4L, 5L, 6L, 7L)) and
          (all must not(contain("author-filter")))
      }
    }

    "keep the author in the large view and its neighbours" in {
      testDbApp { app =>
        val f = fixture()
        val (_, page) = get(app, f.organizer, filesUrl(f, Some("Ivan")))
        val largeUrl = """data-largeUrl="([^"]*)"""".r.findFirstMatchIn(page).get.group(1).replace("&amp;", "&")
        val (_, large) = get(app, f.organizer, largeUrl)
        val next = """href="([^"]*)" title="Next""".r.findFirstMatchIn(large).map(_.group(1).replace("&amp;", "&"))
        (largeUrl === routes.GalleryController.contestFileLarge(f.contest.getId, 1L, author = Some("Ivan")).url) and
          (next === Some(routes.GalleryController.contestFileLarge(f.contest.getId, 5L, author = Some("Ivan")).url))
      }
    }

    "refuse a juror and another contest's organizer, and let root in" in {
      testDbApp { app =>
        val f = fixture()
        (get(app, f.juror, filesUrl(f, Some("Ivan")))._1 === FORBIDDEN) and
          (get(app, f.otherOrganizer, filesUrl(f, Some("Ivan")))._1 === FORBIDDEN) and
          (tiles(get(app, f.root, filesUrl(f, Some("Ivan")))._2) === List(1L, 5L))
      }
    }
  }

  "the authors list" should {

    "list the contest's distinct authors (byte for byte) with their files, linked" in {
      testDbApp { app =>
        val f = fixture()
        val (code, page) = get(app, f.organizer, authorsUrl(f))
        val doc = Jsoup.parse(page)
        (code === OK) and
          (doc.selectFirst("#authors-summary").text === "Contest: 5 authors, 7 files; most by one author: 2 (28.6%)") and
          (doc.selectFirst("#authors-without").text === "Files without a stored author: 1") and
          (rows(page).map { case (a, cells) => a -> cells.head } ===
            Seq("Ivan" -> "2", "ivan" -> "1", "Iván" -> "1", "O'Brien" -> "1", Petro -> "1")) and
          (authorLinks(page) must contain(petroLink(f)))
      }
    }

    "sort by a column, and by the same one the other way" in {
      testDbApp { app =>
        val f = fixture()
        val (_, byName) = get(app, f.organizer, authorsUrl(f, sort = Some("name")))
        val (_, byNameDesc) = get(app, f.organizer, authorsUrl(f, sort = Some("-name")))
        val (_, byFiles) = get(app, f.organizer, authorsUrl(f, sort = Some("files")))
        val names = rows(byName).map(_._1)
        val toggle = Jsoup.parse(byName).selectFirst("th a[data-sort=name]").attr("href")
        (names === Seq("ivan", "Ivan", "Iván", "O'Brien", Petro)) and
          (rows(byNameDesc).map(_._1) === names.reverse) and
          (rows(byFiles).map(_._1).last === "Ivan") and
          (toggle === authorsUrl(f, sort = Some("-name"))) and
          (Jsoup.parse(byName).selectFirst("th[aria-sort]").text === "Author")
      }
    }

    "count each of a yes/no round's files once, by one outcome, with the authors' approval rates" in {
      testDbApp { app =>
        val f = fixture()
        val (_, page) = get(app, f.organizer, authorsUrl(f, Some(f.binary)))
        val doc = Jsoup.parse(page)
        val byAuthor = rows(page).toMap
        (doc.selectFirst("#authors-summary").text === "Round Yes-no: 5 authors, 6 files; most by one author: 1 (16.7%)") and
          // files, approved, rejected by all, not voted yet, approval rate
          // 1 yes and 2 no: approved, a third of the votes yes
          (byAuthor("Ivan") === Seq("1", "1 (100.0%)", "0 (0.0%)", "0 (0.0%)", "33.3%")) and
          (byAuthor("ivan") === Seq("1", "0 (0.0%)", "1 (100.0%)", "0 (0.0%)", "0.0%")) and
          // only a vote yet to come: not voted, no rate
          (byAuthor("Iván") === Seq("1", "0 (0.0%)", "0 (0.0%)", "1 (100.0%)", "—")) and
          // unanimous
          (byAuthor(Petro) === Seq("1", "1 (100.0%)", "0 (0.0%)", "0 (0.0%)", "100.0%")) and
          // a no and a vote yet to come: rejected by everyone who voted
          (byAuthor("O'Brien") === Seq("1", "0 (0.0%)", "1 (100.0%)", "0 (0.0%)", "0.0%")) and
          (heads(page) === Seq("N", "Author", "Files", "Approved", "Rejected by all", "Not voted yet", "Approval rate"))
      }
    }

    "add up an author's outcomes to their files, a file assigned to nobody not voted" in {
      testDbApp { app =>
        val f = fixture()
        // Ivan's deleted file 5 in the round without a vote row: assigned to nobody
        DB.autoCommit { implicit s =>
          sql"INSERT INTO round_image (round_id, page_id, status) VALUES (${f.binary.getId}, 5, 'in')".update()
        }
        val (_, page) = get(app, f.organizer, authorsUrl(f, Some(f.binary)))
        val cells = rows(page).toMap.apply("Ivan")
        def count(cell: String) = cell.takeWhile(_ != ' ').toInt
        (cells === Seq("2", "1 (50.0%)", "0 (0.0%)", "1 (50.0%)", "33.3%")) and
          (foreach(rows(page)) { case (_, c) => c.slice(1, 4).map(count).sum === c.head.toInt })
      }
    }

    "sort a round's outcome column by the authors' shares, ties by name" in {
      testDbApp { app =>
        val f = fixture()
        val (_, page) = get(app, f.organizer, authorsUrl(f, Some(f.binary), Some("-rejected")))
        rows(page).map(_._1) === Seq("ivan", "O'Brien", "Ivan", "Iván", Petro)
      }
    }

    "sort by the approval rate, the systematically rejected first, without a rate last either way" in {
      testDbApp { app =>
        val f = fixture()
        val (_, up) = get(app, f.organizer, authorsUrl(f, Some(f.binary), Some("approval")))
        val (_, down) = get(app, f.organizer, authorsUrl(f, Some(f.binary), Some("-approval")))
        val (_, byFiles) = get(app, f.organizer, authorsUrl(f, Some(f.binary)))
        val opened = Jsoup.parse(byFiles).selectFirst("th a[data-sort=approval]").attr("href")
        // opened from another column: the lowest first, the authors without a rate still last
        (opened === authorsUrl(f, Some(f.binary), Some("approval"))) and
          (rows(get(app, f.organizer, opened)._2).map(_._1) === Seq("ivan", "O'Brien", "Ivan", Petro, "Iván")) and
          (rows(up).map(_._1) === Seq("ivan", "O'Brien", "Ivan", Petro, "Iván")) and
          (rows(down).map(_._1) === Seq(Petro, "Ivan", "ivan", "O'Brien", "Iván"))
      }
    }

    "write the approval rate in the language's number format" in {
      testDbApp { app =>
        val f = fixture()
        rows(get(app, f.organizer, authorsUrl(f, Some(f.binary)), lang = "uk")._2).toMap.apply("Ivan").last === "33,3%"
      }
    }

    "show a rated round's rated files, worst marks and average stars, by its average rule" in {
      testDbApp { app =>
        val f = fixture()
        val (_, page) = get(app, f.organizer, authorsUrl(f, Some(f.rated), Some("-average")))
        val byAuthor = rows(page).toMap
        Round.updateRound(f.rated.getId, f.rated.copy(averageRule = AverageRule.Assigned))
        val (_, assigned) = get(app, f.organizer, authorsUrl(f, Some(f.rated)))
        // files, rated, ✗, average
        (byAuthor("Ivan") === Seq("1", "1 (100.0%)", "0 (0.0%)", "3.00")) and
          (byAuthor("O'Brien") === Seq("1", "1 (100.0%)", "0 (0.0%)", "2.00")) and
          (byAuthor(Petro) === Seq("1", "1 (100.0%)", "1 (100.0%)", "0.00")) and
          (byAuthor("ivan") === Seq("1", "0 (0.0%)", "0 (0.0%)", "—")) and
          (rows(page).map(_._1) === Seq("Ivan", "O'Brien", Petro, "ivan")) and
          (rows(assigned).toMap.apply("O'Brien").last === "1.00") and
          (heads(page) === Seq("N", "Author", "Files", "Rated", "✗ (worst mark)", "Average"))
      }
    }

    "average an AverageRule.Sum round's files over their rated votes, as their stars are drawn" in {
      testDbApp { app =>
        val f = fixture()
        Round.updateRound(f.rated.getId, f.rated.copy(averageRule = AverageRule.Sum))
        val byAuthor = rows(get(app, f.organizer, authorsUrl(f, Some(f.rated)))._2).toMap
        // Ivan's file: 4 + 2, a sum of 6, over its 2 rated votes
        (byAuthor("Ivan").last === "3.00") and (byAuthor("O'Brien").last === "2.00")
      }
    }

    "show the ✗ column only for a round that takes the worst mark or has one" in {
      testDbApp { app =>
        val f = fixture()
        DB.autoCommit { implicit s =>
          sql"UPDATE vote SET rate = 3 WHERE round_id = ${f.rated.getId} AND rate < 0".update()
        }
        val without = heads(get(app, f.organizer, authorsUrl(f, Some(f.rated)))._2)
        Round.updateRound(f.rated.getId, f.rated.copy(worstMark = true))
        val takes = heads(get(app, f.organizer, authorsUrl(f, Some(f.rated)))._2)
        (without === Seq("N", "Author", "Files", "Rated", "Average")) and
          (takes === Seq("N", "Author", "Files", "Rated", "✗ (worst mark)", "Average"))
      }
    }

    "count values still stored as wiki markup by the names they show, a credit template without a Commons link" in {
      testDbApp { app =>
        val f = fixture()
        addFiles(f, Seq(8L -> "[[User:Ivan|Ivan K]]", 9L -> "{{SergiyBobokPhotos}}", 10L -> "{{unknown|author}}").map {
          case (id, author) => Image(id, s"File:F$id.jpg", None, None, 640, 480, author = Some(author))
        })
        val (_, page) = get(app, f.organizer, authorsUrl(f, sort = Some("name")))
        val doc = Jsoup.parse(page)
        val links = authorLinks(page)
        (doc.selectFirst("#authors-summary").text === "Contest: 6 authors, 10 files; most by one author: 2 (20.0%)") and
          (doc.selectFirst("#authors-without").text === "Files without a stored author: 2") and
          (links must contain(("Ivan", filesUrl(f, Some("[[User:Ivan|Ivan K]]")),
            "https://commons.wikimedia.org/wiki/User:Ivan"))) and
          (links must contain(("SergiyBobokPhotos", filesUrl(f, Some("{{SergiyBobokPhotos}}")), ""))) and
          (tiles(get(app, f.organizer, filesUrl(f, Some("{{SergiyBobokPhotos}}")))._2) === List(9L))
      }
    }

    "write shares in the language's number format" in {
      testDbApp { app =>
        val f = fixture()
        Jsoup.parse(get(app, f.organizer, authorsUrl(f), lang = "uk")._2).selectFirst("#authors-summary").text must
          endWith("2 (28,6%)")
      }
    }

    "average a criteria round per criterion, over the votes rated in every criterion" in {
      testDbApp { app =>
        val f = fixture()
        val (_, page) = get(app, f.organizer, authorsUrl(f, Some(f.criteria)))
        val byAuthor = rows(page).toMap
        // Ivan's file: 10 + 10 by one juror (the other's partial vote unrated): 10 stars a
        // criterion; Петро's: (14 + 10) / 2 = 12, 6 stars a criterion. No ✗ in a criteria round.
        (byAuthor("Ivan") === Seq("1", "1 (100.0%)", "10.00")) and
          (byAuthor(Petro) === Seq("1", "1 (100.0%)", "6.00")) and
          (Jsoup.parse(page).select("#authors-table th").asScala.map(_.text).toSeq ===
            Seq("N", "Author", "Files", "Rated", "Average"))
      }
    }

    "be linked from the contest files page and the round's statistics" in {
      testDbApp { app =>
        val f = fixture()
        val (_, files) = get(app, f.organizer, filesUrl(f))
        val (_, stat) = get(app, f.organizer, s"/roundstat/${f.binary.getId}")
        (Jsoup.parse(files).selectFirst("#authors-link").attr("href") === authorsUrl(f)) and
          (Jsoup.parse(stat).selectFirst("#round-authors-link a").attr("href") === authorsUrl(f, Some(f.binary)))
      }
    }

    "refuse a juror and another contest's organizer; a round of another contest is not found" in {
      testDbApp { app =>
        val f = fixture()
        val (jurorCode, jurorPage) = get(app, f.juror, authorsUrl(f))
        (jurorCode === FORBIDDEN) and (jurorPage must not(contain("Ivan"))) and
          (get(app, f.juror, authorsUrl(f, Some(f.binary)))._1 === FORBIDDEN) and
          (get(app, f.otherOrganizer, authorsUrl(f))._1 === FORBIDDEN) and
          (get(app, f.organizer, authorsUrl(f, Some(f.otherRound)))._1 === NOT_FOUND) and
          (get(app, f.admin, authorsUrl(f))._1 === OK) and
          (get(app, f.root, authorsUrl(f))._1 === OK)
      }
    }
  }
}
