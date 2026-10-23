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

  "findForThumbnail" should {
    def image(id: Long, name: String) = Image(pageId = id, title = s"File:$name",
      url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name"), width = 800, height = 600)

    "find the file stored under the name first, then the one renamed from it" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(image(1L, "Current.jpg"), image(2L, "Renamed.jpg")))
      ImageJdbc.recordRename("File:Current.jpg", 2L) // a later upload took the name over
      ImageJdbc.recordRename("File:Former.jpg", 2L)

      ImageJdbc.findForThumbnail("Current.jpg").map { case (i, direct) => (i.pageId, direct) } must beSome((1L, true))
      ImageJdbc.findForThumbnail("Former.jpg").map { case (i, direct) => (i.pageId, direct) } must beSome((2L, false))
      ImageJdbc.findForThumbnail("Unknown.jpg") must beNone
    }

    "tell former names apart by case, as Commons does" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(image(1L, "A.jpg"), image(2L, "B.jpg")))
      ImageJdbc.recordRename("File:Foo bar.jpg", 1L)
      ImageJdbc.recordRename("File:Foo Bar.jpg", 2L)

      ImageJdbc.findForThumbnail("Foo bar.jpg").map(_._1.pageId) must beSome(1L)
      ImageJdbc.findForThumbnail("Foo Bar.jpg").map(_._1.pageId) must beSome(2L)
    }
  }
}
