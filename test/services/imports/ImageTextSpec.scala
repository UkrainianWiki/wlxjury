package services.imports

import org.intracer.wmua.Image
import org.specs2.mutable.Specification

class ImageTextSpec extends Specification {

  private val image = Image(11L, "File:Image11.jpg", None, None, 0, 0, None, None)

  "withText" should {

    "set the monument id, the description and the author" in {
      val descr = "descr. {{Monument Ukraine|12-345-0011}}"
      val text = s"{{Information|description=$descr|author=Author}}"
      ImageText.withText(image, Some(text), Some("Monument Ukraine")) ===
        image.copy(monumentId = Some("12-345-0011"), description = Some(descr), author = Some("Author"))
    }

    "set no monument id without a template, and an empty description without text" in {
      val text = "{{Information|description={{Monument Ukraine|12-345-0011}}}}"
      ImageText.withText(image, Some(text), None).monumentId must beNone
      ImageText.withText(image, None, Some("Monument Ukraine")) ===
        image.copy(monumentId = None, description = Some(""), author = None)
    }
  }

  "author" should {
    "tell a named author, an unknown one and one left to the uploader" in {
      ImageText.author("{{Information|author=[[User:Ilya|Ilya K]]}}") === ImageText.Author.Named("Ilya")
      ImageText.author("{{Information|author={{Yuriy Kvach}}}}") === ImageText.Author.Named("{{Yuriy Kvach}}")
      ImageText.author("{{Information|author={{unknown|author}}}}") === ImageText.Author.Unknown
      ImageText.author("{{Information|description=d}}") === ImageText.Author.Uploader
      ImageText.author("{{Зображення|Автор=Я}}") === ImageText.Author.Uploader
    }

    "store a name normalized, at most 255 characters" in {
      ImageText.storedAuthor("some_user") === Some("Some user")
      ImageText.storedAuthor("x" * 300).map(_.length) === Some(255)
      ImageText.storedAuthor("  ") must beNone
    }
  }

  "validMonumentId" should {
    "be the template's first parameter, if it is a valid id" in {
      ImageText.validMonumentId("{{Monument Ukraine|12-345-0011}}", "Monument Ukraine") === Some("12-345-0011")
      ImageText.validMonumentId("{{Monument Ukraine|12-345}}", "Monument Ukraine") must beNone
      ImageText.validMonumentId("{{Other|12-345-0011}}", "Monument Ukraine") must beNone
    }
  }
}
