package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** A category saved with underscores is found by its normalised title (#111): no second row. */
class CategoryFindOrInsertSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  override protected def before: Any = SharedTestDb.truncateAll()

  "CategoryJdbc.findOrInsert" should {

    "reuse the row saved with underscores for the spaced title" in {
      val saved = CategoryJdbc.findOrInsert("Category:Images_from_Wiki_Loves_Earth")
      CategoryJdbc.findOrInsert("Category:Images from Wiki Loves Earth") === saved
      CategoryJdbc.findAll().size === 1
    }

    "reuse the row of the exact title, and insert a title it has none of" in {
      val saved = CategoryJdbc.findOrInsert("Category:Foo bar")
      CategoryJdbc.findOrInsert("Category:Foo bar") === saved
      CategoryJdbc.findOrInsert("Category:Other") !== saved
      CategoryJdbc.findAll().size === 2
    }
  }
}
