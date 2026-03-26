package db.scalikejdbc

import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class ImageJdbcSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  "findByFilename" should {
    "return the image whose title matches File:filename" in new AutoRollbackDb {
      val img = Image(pageId = 1L, title = "File:Corallus.jpg",
        url = Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Corallus.jpg"),
        width = 800, height = 600)
      imageDao.batchInsert(Seq(img))

      val found = ImageJdbc.findByFilename("Corallus.jpg")
      found must beSome
      found.get.title mustEqual "File:Corallus.jpg"
    }

    "return None when no image with that filename exists" in new AutoRollbackDb {
      ImageJdbc.findByFilename("nosuchfile.jpg") must beNone
    }
  }
}
