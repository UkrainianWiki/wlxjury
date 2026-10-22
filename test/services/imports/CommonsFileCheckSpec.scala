package services.imports

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.dto.Page
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

import java.util.concurrent.atomic.AtomicLong
import scala.concurrent.duration._
import scala.concurrent.{Await, Future}

/** [[CommonsFileCheck]] against the test database and a fake Commons. It writes in its own
  * transactions, so the tests commit their data and truncate it before each test.
  */
class CommonsFileCheckSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def stored(id: Long, name: String) =
    Image(id, s"File:$name.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name.jpg"), None,
      800, 600, Some(s"07-101-000$id"), Some(s"Description $id"), Some(s"Author $id"), Some(1000), Some("image/jpeg"),
      Some("BITMAP"))

  private def renamedTo(image: Image, name: String) =
    image.copy(title = s"File:$name.jpg", url = Some(s"https://upload.wikimedia.org/wikipedia/commons/c/cd/$name.jpg"),
      monumentId = None, description = None, author = None)

  /** The images, each voted on by two jurors in two rounds. Returns the round ids. */
  private def setUp(images: Image*): Seq[Long] = {
    implicit val contest: ContestJury = createContests(10).head
    val rounds = (1 to 2).map(n => roundDao.create(Round(None, n, None, contest.getId, createdAt = now)).getId)
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    imageDao.batchInsert(images)
    for (img <- images; r <- rounds; j <- jurors)
      selectionDao.create(img.pageId, rate = 1, juryId = j, roundId = r, monumentId = img.monumentId)
    rounds
  }

  private def selectionRows(table: String, pageId: Long): Int = DB.readOnly { implicit s =>
    SQL(s"SELECT COUNT(*) FROM $table WHERE page_id = ?").bind(pageId).map(_.int(1)).single().getOrElse(0)
  }

  private def deletedAt(pageId: Long): Option[java.time.ZonedDateTime] = DB.readOnly { implicit s =>
    sql"SELECT deleted_at FROM images WHERE page_id = $pageId".map(_.zonedDateTimeOpt(1)).single().flatten
  }

  private def await[A](f: Future[A]): A = Await.result(f, 10.seconds)

  private def mkCheck(source: CommonsSource, clock: AtomicLong = new AtomicLong(0)) =
    new CommonsFileCheck(source, batchWindow = 20.millis, cooldown = 1.hour, retryAfterError = 5.minutes,
      now = () => clock.get())

  "CommonsFileCheck" should {

    "store a renamed file's new title and url, keeping what came from its page text" in {
      val old = stored(1L, "Old_name")
      setUp(old)
      val check = mkCheck(new FakeCommonsSource(Seq(FakeFile(renamedTo(old, "New_name")))))

      val result = await(check.check(old))
      check.stop()

      result must beLike { case FileCheck.Changed(image) => image.title === "File:New_name.jpg" }
      val now = ImageJdbc.findById(1L).get
      (now.title, now.url) === ("File:New_name.jpg", Some("https://upload.wikimedia.org/wikipedia/commons/c/cd/New_name.jpg"))
      (now.monumentId, now.description, now.author) === (old.monumentId, old.description, old.author)
      ImageJdbc.findByFilename("Old_name.jpg") must beNone
      ImageJdbc.findByFilename("New_name.jpg").map(_.pageId) must beSome(1L)
      check.renamedFrom("File:Old_name.jpg").map(_.title) must beSome("File:New_name.jpg")
      selectionRows("selection", 1L) === 4
    }

    "mark a file Commons no longer has deleted, and move its votes aside" in {
      val gone = stored(1L, "Deleted")
      val kept = stored(2L, "Kept")
      val rounds = setUp(gone, kept)
      val check = mkCheck(new FakeCommonsSource(Seq(FakeFile(kept))))

      await(check.check(gone)) === FileCheck.Deleted
      check.stop()

      deletedAt(1L) must beSome
      selectionRows("selection", 1L) === 0
      selectionRows("selection_deleted", 1L) === 4
      deletedAt(2L) must beNone
      selectionRows("selection", 2L) === 4
      ImageJdbc.findByFilename("Deleted.jpg") must beNone
      ImageJdbc.imageCountByRounds(rounds) === rounds.map(_ -> 1).toMap
    }

    "change nothing for a file Commons has as stored" in {
      val same = stored(1L, "Same")
      setUp(same)
      val check = mkCheck(new FakeCommonsSource(Seq(FakeFile(same))))

      await(check.check(same)) === FileCheck.Unchanged
      check.stop()

      ImageJdbc.findById(1L).map(_.title) must beSome("File:Same.jpg")
      deletedAt(1L) must beNone
      selectionRows("selection", 1L) === 4
    }

    "ask about the files checked together in one request" in {
      val images = (1L to 3L).map(id => stored(id, s"File$id"))
      setUp(images: _*)
      val source = new FakeCommonsSource(images.map(FakeFile(_)))
      val check = mkCheck(source)

      import scala.concurrent.ExecutionContext.Implicits.global
      val results = await(Future.sequence(images.map(check.check)))
      check.stop()

      results === Seq.fill(3)(FileCheck.Unchanged)
      source.detailCalls.get === 1
    }

    "give the last answer within the cooldown, and ask again after it" in {
      val same = stored(1L, "Same")
      setUp(same)
      val source = new FakeCommonsSource(Seq(FakeFile(same)))
      val clock = new AtomicLong(0)
      val check = mkCheck(source, clock)

      await(check.check(same)) === FileCheck.Unchanged
      clock.set(59.minutes.toMillis)
      await(check.check(same)) === FileCheck.Unchanged
      source.detailCalls.get === 1

      clock.set(61.minutes.toMillis)
      await(check.check(same)) === FileCheck.Unchanged
      check.stop()
      source.detailCalls.get === 2
    }

    "change nothing when Commons can't be asked, and ask again after a while" in {
      val file = stored(1L, "File")
      setUp(file)
      val calls = new AtomicLong(0)
      val failing = new FakeCommonsSource(Nil) {
        override def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] = {
          calls.incrementAndGet()
          throw new RuntimeException("Commons is down")
        }
      }
      val clock = new AtomicLong(0)
      val check = mkCheck(failing, clock)

      await(check.check(file)) === FileCheck.Unknown
      deletedAt(1L) must beNone
      selectionRows("selection", 1L) === 4

      await(check.check(file)) === FileCheck.Unknown
      calls.get === 1
      clock.set(6.minutes.toMillis)
      await(check.check(file)) === FileCheck.Unknown
      check.stop()
      calls.get === 2
    }
  }
}
