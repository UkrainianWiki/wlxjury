package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.SelectionQuery
import org.intracer.wmua.{Image, Selection}
import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.DBSession

class RegionStatSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  implicit val messages: MessageStub = MessageStub(Map.empty)

  private def image(pageId: Long, monumentId: String): Image =
    Image(pageId, s"File:Image$pageId.jpg", None, None, 640, 480, Some(monumentId))

  private def monument(id: String): Monument =
    new Monument(id = id, name = s"Monument $id", page = "Test")

  private def insertImages(images: Seq[Image])(implicit session: DBSession): Unit =
    imageDao.batchInsert(images)

  private def insertMonuments(monuments: Seq[Monument])(implicit session: DBSession): Unit =
    monumentDao.batchInsert(monuments)

  private def insertSelections(selections: Seq[Selection])(implicit session: DBSession): Unit =
    selectionDao.batchInsert(selections)

  private def selection(pageId: Long, userId: Long, roundId: Long, monumentId: Option[String] = None): Selection =
    Selection(pageId = pageId, juryId = userId, roundId = roundId, rate = 1, monumentId = monumentId)

  "byRegionStat" should {

    "return empty when no selections exist for the round" in new AutoRollbackDb {
      val regions = SelectionQuery(roundId = Some(999L)).byRegionStat()
      regions must_== Seq.empty
    }

    "return one region code for images all from the same region" in new AutoRollbackDb {
      val roundId = 10L
      val userId  = 1L
      val imgs = Seq(
        image(101L, "07-123-0001"),
        image(102L, "07-456-0002"),
        image(103L, "07-789-0003")
      )
      insertMonuments(imgs.map(i => monument(i.monumentId.get)))
      insertImages(imgs)
      insertSelections(imgs.map(i => selection(i.pageId, userId, roundId, i.monumentId)))

      val result = SelectionQuery(roundId = Some(roundId)).byRegionStat()
      result.map(_.id) must_== Seq("07")
    }

    "return two distinct region codes for images from different regions" in new AutoRollbackDb {
      val roundId = 20L
      val userId  = 2L
      val imgs = Seq(
        image(201L, "07-123-0001"),
        image(202L, "14-456-0002")
      )
      insertMonuments(imgs.map(i => monument(i.monumentId.get)))
      insertImages(imgs)
      insertSelections(imgs.map(i => selection(i.pageId, userId, roundId, i.monumentId)))

      val result = SelectionQuery(roundId = Some(roundId)).byRegionStat()
      result.map(_.id).sorted must_== Seq("07", "14")
    }

    "filter by userId — juror sees only their own regions" in new AutoRollbackDb {
      val roundId  = 30L
      val userId3  = 3L
      val userId4  = 4L
      val imgRegion07 = image(301L, "07-111-0001")
      val imgRegion14 = image(302L, "14-222-0002")
      insertMonuments(Seq(imgRegion07, imgRegion14).map(i => monument(i.monumentId.get)))
      insertImages(Seq(imgRegion07, imgRegion14))
      insertSelections(Seq(
        selection(imgRegion07.pageId, userId3, roundId, imgRegion07.monumentId),
        selection(imgRegion14.pageId, userId4, roundId, imgRegion14.monumentId)
      ))

      val resultUser3 = SelectionQuery(userId = Some(userId3), roundId = Some(roundId)).byRegionStat()
      resultUser3.map(_.id) must_== Seq("07")

      val resultUser4 = SelectionQuery(userId = Some(userId4), roundId = Some(roundId)).byRegionStat()
      resultUser4.map(_.id) must_== Seq("14")
    }

    "not include regions from a different round" in new AutoRollbackDb {
      val round1 = 40L
      val round2 = 41L
      val userId = 5L
      val imgRound1 = image(401L, "07-100-0001")
      val imgRound2 = image(402L, "14-200-0002")
      insertMonuments(Seq(imgRound1, imgRound2).map(i => monument(i.monumentId.get)))
      insertImages(Seq(imgRound1, imgRound2))
      insertSelections(Seq(
        selection(imgRound1.pageId, userId, round1, imgRound1.monumentId),
        selection(imgRound2.pageId, userId, round2, imgRound2.monumentId)
      ))

      val resultRound1 = SelectionQuery(roundId = Some(round1)).byRegionStat()
      resultRound1.map(_.id) must_== Seq("07")

      val resultRound2 = SelectionQuery(roundId = Some(round2)).byRegionStat()
      resultRound2.map(_.id) must_== Seq("14")
    }
  }
}
