package services.imports

import db.scalikejdbc.{ImageJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.TestImportServices

/** The author an import stores (`images.author`), as scalawiki determines it: the user the
  * page's author links to, else its text without markup, else the original uploader (not a
  * bot); nobody for an unknown author. For every contest: its page text is fetched whether
  * or not the contest has a monument id template.
  */
class FileAuthorSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val category = "Category:Images from Wiki Loves Earth 2026"

  private def image(id: Long) = Image(id, s"File:Image $id.jpg", Some(s"url$id"), None, 640, 480)

  private def info(author: String) = s"{{Information|description=d|author=$author}}"

  private def file(id: Long, text: String, uploaders: String*) =
    FakeFile(image(id), text = Some(text), uploaders = uploaders)

  /** A contest without a monument id template (Wiki Loves Earth). */
  private def contest(): ContestJury = contestDao.create(Some(1), "Wiki Loves Earth", 2026, "Nowhere",
    images = Some(category))

  private def importFiles(c: ContestJury, commons: FakeCommonsSource): Unit =
    new TestImportServices(commons).imageService.importFiles(c, category, "")

  private def authors: Map[Long, Option[String]] =
    ImageJdbc.findAll().map(i => i.pageId -> i.author).toMap

  "an import" should {

    "store the author each page credits, as scalawiki reads it" in {
      val files = Seq(
        file(1, info("[[User:Ilya Korniyko|Ilya]]")),
        file(2, info("{{U|Some_user}}")),
        file(3, "{{Зображення|Опис=d|Автор=[[Користувач:Петро|Петро]]}}"),
        file(4, info("Taras Shevchenko")),
        file(5, info("[[:uk:Користувач:Ольга|Ольга]], [https://example.com site]")),
        file(6, info("{{Yuriy Kvach}}")),
        file(7, info("lowercase_name"))
      )
      val commons = new FakeCommonsSource(files,
        templates = Map("Yuriy Kvach" -> "Photo by [[User:Kvachyuriy|Yuriy Kvach]]"))
      importFiles(contest(), commons)

      authors === Map(
        1L -> Some("Ilya Korniyko"),
        2L -> Some("Some user"),
        3L -> Some("Петро"),
        4L -> Some("Taras Shevchenko"),
        5L -> Some("Ольга"),
        6L -> Some("Kvachyuriy"),
        7L -> Some("Lowercase name")
      )
    }

    "credit a file without an author, or by \"me\", to its original uploader, never a bot" in {
      val files = Seq(
        file(1, "{{Information|description=d}}", "Retoucher", "Original"),
        file(2, "{{Зображення|Опис=d|Автор=Я}}", "Petro"),
        file(3, "no template at all", "UploadBot"),
        file(4, info("{{unknown|author}}"), "Uploader4"),
        // a credit template that links to nobody: its uploader
        file(5, info("{{Somebody}}"), "Uploader5")
      )
      val commons = new FakeCommonsSource(files, templates = Map("Somebody" -> "A photographer"))
      importFiles(contest(), commons)

      authors === Map(1L -> Some("Original"), 2L -> Some("Petro"), 3L -> None, 4L -> None, 5L -> Some("Uploader5"))
    }

    "ask Commons nothing more for authors that link to users" in {
      val commons = new FakeCommonsSource(Seq(file(1, info("[[User:A|A]]")), file(2, info("[[User:B|B]]"))))
      importFiles(contest(), commons)
      commons.authorCalls.get === 0
    }

    "fill the author of a stored file when imported again, and keep it when no text comes" in {
      val c = contest()
      // imported before authors were resolved: the raw wikitext, or none
      importFiles(c, new FakeCommonsSource(Seq(FakeFile(image(1)), FakeFile(image(2)))))
      authors === Map(1L -> None, 2L -> None)

      importFiles(c, new FakeCommonsSource(Seq(file(1, info("[[User:Ilya|Ilya]]")), file(2, info("Plain")))))
      authors === Map(1L -> Some("Ilya"), 2L -> Some("Plain"))

      // a page that comes back without its text keeps what is stored
      importFiles(c, new FakeCommonsSource(Seq(FakeFile(image(1)), file(2, info("Other")))))
      authors === Map(1L -> Some("Ilya"), 2L -> Some("Other"))
    }

    "keep the monument id another contest's import set, in a contest without a template" in {
      val c = contest()
      // a file of the contest's that another contest's import stored with its monument id
      val shared = image(1).copy(monumentId = Some("12-345-0001"), description = Some("old"))
      imageDao.batchInsert(Seq(shared))
      db.scalikejdbc.CategoryLinkJdbc.addToCategory(
        db.scalikejdbc.CategoryJdbc.findOrInsert(services.ImageService.ownCategory(c.getId)), Seq(shared))
      importFiles(c, new FakeCommonsSource(Seq(file(1, info("[[User:Ilya|Ilya]]")))))
      val stored = ImageJdbc.findById(1L).get
      stored.monumentId === Some("12-345-0001")
      stored.author === Some("Ilya")
      stored.description === Some("d")
    }

    "keep at most 255 characters of an author" in {
      importFiles(contest(), new FakeCommonsSource(Seq(file(1, info("x" * 300)))))
      ImageJdbc.findById(1L).flatMap(_.author).map(_.length) === Some(255)
    }
  }
}
