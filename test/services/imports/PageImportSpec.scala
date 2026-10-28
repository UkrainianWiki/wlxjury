package services.imports

import db.scalikejdbc.ImageJdbc.FileList
import db.scalikejdbc.{ImageJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestImportServices

/** Importing the files of a WLx contest's winners page ([[ImportSource.PageFiles]]) or a
  * list: each with its country, of the contest's country category it is in
  * ([[ContestCountries]]); of a page, only those in one, not the icons and logo it shows.
  */
class PageImportSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val winners = "Commons:Wiki Loves Earth 2026/Winners"
  private val root = "Category:Images from Wiki Loves Earth 2026"

  private def file(id: Long, title: String, countries: String*) =
    withCategories(id, title, (if (countries.isEmpty) Seq("Category:Green icons") else Seq(root)) ++
      countries.map(c => s"$root in $c"))

  private def withCategories(id: Long, title: String, categories: Seq[String]) =
    FakeFile(Image(id, s"File:$title", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$id"), None, 640, 480),
      categories = categories)

  /** The page's files, in page id order: the icons and the logo are the oldest. */
  private val files = Seq(
    file(1, "1 green.svg"), file(2, "2 green.svg"), file(3, "Logo.svg"),
    file(11, "Lake.jpg", "Armenia"), file(12, "Eagle flight.webm", "Armenia"),
    file(13, "Egret.jpg", "Bangladesh", "Bangladesh under assessment"), file(14, "Savè.jpg", "Benin")
  )

  /** The contest's files' countries, as its files page has them. */
  private def countries(c: ContestJury): Map[Long, String] = {
    val stored = contestDao.findById(c.getId).get
    ContestCountries.fileCountries(stored,
      ImageJdbc.categoryCountryLinks(stored.categoryId.get, ContestCountries.prefixes(stored)))
  }

  private def contest(): ContestJury =
    contestDao.create(Some(1), "Wiki Loves Earth", 2026, "International", images = Some(winners))

  /** The contest's files: page id -> its country category's country. */
  private def members(c: ContestJury): Map[Long, Option[String]] = {
    val categoryId = contestDao.findById(c.getId).get.categoryId.get
    DB.readOnly { implicit s =>
      sql"""SELECT m.page_id, c.title FROM category_members m
              LEFT JOIN (category_members cc JOIN category c ON c.id = cc.category_id AND c.title LIKE ${s"$root in %"})
                ON cc.page_id = m.page_id
            WHERE m.category_id = $categoryId"""
        .map(rs => rs.long(1) -> rs.stringOpt(2).map(_.stripPrefix(s"$root in "))).list().toMap
    }
  }

  "a winners page" should {

    "import the files of the contest's countries, with them, page by page" in {
      val c = contest()
      // two files a step: the first has only icons
      new TestImportServices(new FakeCommonsSource(files), pageSize = Some(2)).imageService.importFiles(c, winners, "")

      members(c) === Map(11L -> Some("Armenia"), 12L -> Some("Armenia"), 13L -> Some("Bangladesh"), 14L -> Some("Benin"))
      countries(c) === Map(11L -> "Armenia", 12L -> "Armenia", 13L -> "Bangladesh", 14L -> "Benin")
      val categoryId = contestDao.findById(c.getId).get.categoryId.get
      val armenia = FileList.Category(categoryId, deletedOnly = false, Some(ImageJdbc.CountryFilter(Seq(11L, 12L))))
      ImageJdbc.listFiles(armenia, None, 10, 0).map(_.image.pageId) === Seq(11L, 12L)
      ImageJdbc.fileRank(armenia, None, 12L) === 1
    }

    "give a part's files its country by the parent categories, and take the other prefix" in {
      val c = contest()
      val uk = s"$root in the United Kingdom"
      val parts = Seq(
        withCategories(21, "Mosedale.jpg", Seq(root, s"$root in England", uk)),
        withCategories(22, "Mountain Hare.jpg", Seq(root, s"$root in Scotland", uk)),
        withCategories(23, "Serengeti.jpg", Seq("Category:Wiki Loves Earth 2026 in Tanzania")),
        file(24, "Kinabalu.jpg", "Malaysia")
      )
      val commons = new FakeCommonsSource(parts, parents = Map(
        s"$root in England" -> Seq(root, uk), s"$root in Scotland" -> Seq(root, uk)))
      new TestImportServices(commons).imageService.importFiles(c, winners, "")

      countries(c) === Map(21L -> "the United Kingdom", 22L -> "the United Kingdom", 23L -> "Tanzania",
        24L -> "Malaysia")
    }

    "import all its files for a contest without country categories" in {
      val c = contest()
      val commons = new FakeCommonsSource(files.take(4), categoryCounts = Map(root -> 0L))
      new TestImportServices(commons).imageService.importFiles(c, winners, "")

      members(c) === Map(1L -> None, 2L -> None, 3L -> None, 11L -> None)
    }
  }

  "a pasted list" should {

    "give its files their countries, keeping those in none" in {
      val c = contest()
      val commons = new FakeCommonsSource(files)
      new TestImportServices(commons).imageService.importFiles(c, winners, "File:Lake.jpg\nFile:Logo.svg")

      members(c) === Map(11L -> Some("Armenia"), 3L -> None)
    }

    "leave a national contest's files as they were, without asking for their categories" in {
      val c = contestDao.create(Some(2), "Wiki Loves Earth", 2026, "Ukraine", images = Some("Category:X"))
      val commons = new FakeCommonsSource(files)
      new TestImportServices(commons).imageService.importFiles(c, "Category:X", "File:Lake.jpg\nFile:Logo.svg")

      members(c) === Map(11L -> None, 3L -> None)
      commons.categoryCalls.get === 0
    }

    "add a file's new country when imported again, never taking it out of a category" in {
      val c = contest()
      new TestImportServices(new FakeCommonsSource(files)).imageService.importFiles(c, winners, "File:Lake.jpg")
      val moved = files.map(f => if (f.image.pageId == 11L) f.copy(categories = Seq(s"$root in Georgia")) else f)
      new TestImportServices(new FakeCommonsSource(moved)).imageService.importFiles(c, winners, "File:Lake.jpg")

      val countries = DB.readOnly { implicit s =>
        sql"""SELECT c.title FROM category_members cc JOIN category c ON c.id = cc.category_id
              WHERE cc.page_id = 11 AND c.title LIKE ${s"$root in %"}""".map(_.string(1)).list()
      }
      countries.toSet === Set(s"$root in Armenia", s"$root in Georgia")
    }
  }
}
