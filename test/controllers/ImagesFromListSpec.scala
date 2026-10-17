package controllers

import db.scalikejdbc.{CategoryJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.JuryTestHelpers
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestImportServices
import services.imports.{ImportSource, MwCommonsSource}

/** Importing a pasted list of files, over Commons. */
class ImagesFromListSpec extends Specification with JuryTestHelpers with TestDb
    with BeforeAll with BeforeEach {

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  val categoryDao = CategoryJdbc

  val categoryName = "Category:Category Name"
  val contestId = 10

  "contest" should {

    "import from list" in {
      val list = resourceAsString("ArmeniaImageList.txt")
      val uniqueNormalizedNames = ImportSource.titles(list)

      val contest = contestDao.create(Some(contestId), "WLE", 2020, "Armenia", Some(categoryName))

      new TestImportServices(new MwCommonsSource(Global.commons)).imageService.importFiles(contest, categoryName, list)

      val dbCategories = categoryDao.findAll()

      val images = imageDao.findByCategory(dbCategories.last.id)

      val missingImages = uniqueNormalizedNames.toSet -- images.map(_.title).toSet
      missingImages === Set.empty

      images.size === uniqueNormalizedNames.length
    }
  }
}
