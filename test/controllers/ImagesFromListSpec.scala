package controllers

import db.scalikejdbc.{CategoryJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.JuryTestHelpers
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestImportServices
import services.imports.{CommonsLogin, ImportSource, MwCommonsSource}

/** Importing a pasted list of files, over the live Commons: only with -Dintegration=true. */
class ImagesFromListSpec extends Specification with JuryTestHelpers with TestDb
    with BeforeAll with BeforeEach {

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  val categoryDao = CategoryJdbc

  val categoryName = "Category:Category Name"
  val contestId = 10

  "contest" should {

    "import from list" in {
      if (!sys.props.get("integration").contains("true"))
        skipped("reads the live Wikimedia Commons: run with -Dintegration=true")
      val list = resourceAsString("ArmeniaImageList.txt")
      val uniqueNormalizedNames = ImportSource.titles(list)

      val contest = contestDao.create(Some(contestId), "WLE", 2020, "Armenia", Some(categoryName))

      val commons = new MwCommonsSource(Global.commons, CommonsLogin.anonymous(Global.commons))
      new TestImportServices(commons).imageService.importFiles(contest, categoryName, list)

      val dbCategories = categoryDao.findAll()

      val images = imageDao.findByCategory(dbCategories.last.id)

      // a file renamed on Commons since the list was made: imported by the title it redirects to
      val renamed = "File:-1 Բնություն S-N 01.jpg" -> "File:Մեծ Պառնի գյուղի շրջակայքը 16-05-2020.jpg"
      val missingImages = uniqueNormalizedNames.toSet -- images.map(_.title).toSet
      missingImages === Set(renamed._1)
      images.map(_.title) must contain(renamed._2)

      images.size === uniqueNormalizedNames.length
    }
  }
}
