package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** The guards against contests sharing one list of files (a category): a blank title names no
  * category; setting a contest's source never looks a category up by its title (only an import
  * gives a contest its category, its own); and a list is never started from a blank-titled
  * category or one of several contests. In production every import with no source once shared
  * the row of the empty title (category 248, 1428 files of 10 contests), and a source set by
  * title gave a 2026 contest a 2020 list.
  */
class SharedCategoryGuardsSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  override protected def before: Any = SharedTestDb.truncateAll()

  private def blankCategory(title: String = ""): Long =
    DB.autoCommit(implicit s => sql"INSERT INTO category (title) VALUES ($title)".updateAndReturnGeneratedKey.apply())

  private def members(categoryId: Long): Seq[Long] =
    DB.readOnly(implicit s => sql"SELECT page_id FROM category_members WHERE category_id = $categoryId ORDER BY page_id"
      .map(_.long(1)).list.apply())

  private def ownCategory(contestId: Long): Long = CategoryJdbc.findOrInsert(_root_.services.ImageService.ownCategory(contestId))

  private def categoryOf(title: String): Long = CategoryJdbc.findOrInsert(title)

  private def categories: Int = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM category".map(_.int(1)).single.apply().get)

  "CategoryJdbc.findOrInsert" should {

    "refuse a blank title, adding no row" in {
      forall(Seq("", " ", " \t", "\n", "_", " _ ")) { title =>
        CategoryJdbc.findOrInsert(title) must throwA[IllegalArgumentException]
      }
      CategoryJdbc.findOrInsert(null) must throwA[IllegalArgumentException]
      categories === 0
    }

    "refuse it even with a blank-titled row there to find" in {
      blankCategory()
      CategoryJdbc.findOrInsert("") must throwA[IllegalArgumentException]
      categories === 1
    }

    "find or add a contest's own category, #contest N" in {
      val own = ownCategory(7)
      CategoryJdbc.findById(own).map(_.title) must beSome("#contest 7")
      ownCategory(7) === own
      categories === 1
    }
  }

  "ContestJuryJdbc.setImagesSource" should {

    "set the source's text only: no category by its title, and none for an empty source" in {
      val bySource = CategoryJdbc.findOrInsert("Category:Images from Wiki Loves Earth 2020 in Malaysia")
      blankCategory()
      val contests = Seq(contestDao.create(None, "Wiki Loves Earth", 2026, "Malaysia"), contestDao.create(None, "WLE", 2017, "Ukraine"))
      contestDao.setImagesSource(contests(0).getId, Some("Category:Images from Wiki Loves Earth 2020 in Malaysia"))
      contestDao.setImagesSource(contests(1).getId, Some(""))
      contests.map(c => contestDao.findById(c.getId).get).map(c => c.images -> c.categoryId) ===
        Seq(Some("Category:Images from Wiki Loves Earth 2020 in Malaysia") -> None, Some("") -> None)
      categories === 2 // the source's and the blank one: none added
      bySource must be_>(0L)
    }

    "keep the contest's own category" in {
      val contest = contestDao.create(None, "WLE", 2026, "Ukraine")
      val own = ownCategory(contest.getId)
      contestDao.setCategory(contest.getId, own)
      contestDao.setImagesSource(contest.getId, Some("Category:Other"))
      contestDao.setImagesSource(contest.getId, Some(""))
      contestDao.findById(contest.getId).get.categoryId === Some(own)
    }
  }

  "CategoryLinkJdbc.copyIgnore" should {

    "copy from a titled category of one contest" in {
      insertImagesFor(1L, 2L, 3L)
      val from = CategoryJdbc.findOrInsert("Category:Mine")
      DB.autoCommit(implicit s => CategoryLinkJdbc.linkIgnore(from, Seq(1L, 2L, 3L)))
      val contest = contestDao.create(None, "WLE", 2026, "Ukraine", categoryId = Some(from))
      val own = ownCategory(contest.getId)
      DB.localTx(implicit s => CategoryLinkJdbc.copyIgnore(from, own, contest.getId)) must beTrue
      members(own) === Seq(1L, 2L, 3L)
    }

    "copy nothing from a blank-titled category, nor from one of several contests" in {
      insertImagesFor(1L, 2L, 3L)
      val blank = blankCategory(" ")
      val shared = CategoryJdbc.findOrInsert("Category:Images from Wiki Loves Earth 2020 in Malaysia")
      Seq(blank, shared).foreach(c => DB.autoCommit(implicit s => CategoryLinkJdbc.linkIgnore(c, Seq(1L, 2L, 3L))))
      val a = contestDao.create(None, "WLE", 2017, "Ukraine", categoryId = Some(blank))
      contestDao.create(None, "WLE", 2020, "Malaysia", categoryId = Some(shared))
      val b = contestDao.create(None, "WLE", 2026, "Malaysia", categoryId = Some(shared))
      val (ownA, ownB) = (ownCategory(a.getId), ownCategory(b.getId))
      DB.localTx(implicit s => CategoryLinkJdbc.copyIgnore(blank, ownA, a.getId)) must beFalse
      DB.localTx(implicit s => CategoryLinkJdbc.copyIgnore(shared, ownB, b.getId)) must beFalse
      DB.localTx(implicit s => CategoryLinkJdbc.copyIgnore(404L, ownB, b.getId)) must beFalse // no such category
      members(ownA) must beEmpty
      members(ownB) must beEmpty
    }

    // a category (V24's title, or a source's), and what another contest has of it: as the old code
    // would have given it the category (as its category, its source's very title, V24's name)
    val v24 = "Images from WLE 2020 in Malaysia"
    val source = "Category:Images from WLE 2020 in Malaysia"
    Fragment.foreach(Seq(
      ("as its category", v24, (c: Long) => contestDao.setCategory(c, categoryOf(v24)), false),
      ("as its source", source, (c: Long) => contestDao.setImagesSource(c, Some(source)), false),
      ("as its source, with underscores", source,
        (c: Long) => contestDao.setImagesSource(c, Some("Category:Images_from_WLE_2020_in_Malaysia")), false),
      ("by V24's name for it", v24, (_: Long) => (), false),
      ("by V24's name for it, the title with a lowercase category: prefix", "category:" + v24, (_: Long) => (), false),
      // a legacy contest on its unshared V24 row: the other's source set a category of its own title
      ("only as a source naming the V24 row, with Category:", v24, (c: Long) => contestDao.setImagesSource(c, Some(source)), true),
      ("not at all: only another source", v24, (c: Long) => contestDao.setImagesSource(c, Some("Category:Other")), true)
    )) { case (how, title, give, copies) =>
      s"${if (copies) "copy" else "copy nothing"} when another contest has the category ${how}" in {
        val category = categoryOf(title)
        val contest = contestDao.create(None, "WLE", 2026, "Malaysia", categoryId = Some(category))
        val (name, year) = if (how.startsWith("by V24")) ("WLE", 2020) else ("WLM", 2019)
        val other = contestDao.create(None, name, year, "Malaysia")
        give(other.getId)
        DB.readOnly(implicit s => CategoryLinkJdbc.mayCopyFrom(category, contest.getId)) === copies
      }
    }
  }
}
