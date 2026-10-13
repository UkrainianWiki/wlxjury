package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** selection.monument_id is a copy of images.monument_id that the galleries sort and
  * filter regions on: changing an image's monument id must change it too. The updates
  * run in their own transaction, so the tests commit their data.
  */
class ImageMonumentIdSyncSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def image(id: Long, monumentId: String) =
    Image(id, s"File:Image$id.jpg", None, None, 640, 480, Some(monumentId))

  /** Two images, each rated by two jurors in two rounds. Returns (juror ids, round ids). */
  private def setUp(): (Seq[Long], Seq[Long]) = {
    implicit val contest: ContestJury = createContests(10).head
    val rounds = (1 to 2).map(n => roundDao.create(Round(None, n, None, contest.getId, createdAt = now)).getId)
    val jurors = createUsers("jury", 1, 2).map(_.getId)
    val images = Seq(image(1L, "07-101-0001"), image(2L, "07-101-0002"))
    imageDao.batchInsert(images)
    for (img <- images; r <- rounds; j <- jurors)
      selectionDao.create(img.pageId, rate = 0, juryId = j, roundId = r, monumentId = img.monumentId)
    (jurors, rounds)
  }

  private def selectionMonumentIds(pageId: Long): Seq[Option[String]] =
    SelectionJdbc.findAll().filter(_.pageId == pageId).map(_.monumentId)

  private def regionGallery(juror: Long, round: Long, region: String): Seq[Long] =
    SelectionQuery(userId = Some(juror), roundId = Some(round), regions = Set(region),
      order = Map("s.page_id" -> 1)).list().map(_.image.pageId)

  "ImageJdbc.updateMonumentId" should {
    "update the image's selection rows in every round, and only its own" in {
      val (jurors, rounds) = setUp()
      ImageJdbc.updateMonumentId(1L, "14-101-0001")

      ImageJdbc.findById(1L).flatMap(_.monumentId) === Some("14-101-0001")
      selectionMonumentIds(1L) === Seq.fill(4)(Some("14-101-0001"))
      selectionMonumentIds(2L) === Seq.fill(4)(Some("07-101-0002"))

      // the region gallery and its count follow the new monument id
      regionGallery(jurors.head, rounds.head, "14") === Seq(1L)
      regionGallery(jurors.head, rounds.head, "07") === Seq(2L)
      SelectionQuery(userId = Some(jurors.head), roundId = Some(rounds.head), regions = Set("14")).count() === 1
    }
  }

  "ImageJdbc.update" should {
    "update the selection rows when the monument id changes" in {
      setUp()
      val changed = ImageJdbc.findById(2L).get.copy(monumentId = Some("26-101-0002"), width = 800)
      ImageJdbc.update(changed)
      ImageJdbc.findById(2L).map(i => (i.monumentId, i.width)) === Some((Some("26-101-0002"), 800))
      selectionMonumentIds(2L) === Seq.fill(4)(Some("26-101-0002"))
      selectionMonumentIds(1L) === Seq.fill(4)(Some("07-101-0001"))
    }

    "clear the selection rows' copy when the image loses its monument id" in {
      setUp()
      ImageJdbc.update(ImageJdbc.findById(1L).get.copy(monumentId = None))
      selectionMonumentIds(1L) === Seq.fill(4)(None)
    }
  }
}
