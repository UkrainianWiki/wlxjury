package db.scalikejdbc

import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.{Image, Selection}
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class ImageDbNewDbSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  // Fixed IDs chosen to avoid collision with other specs sharing the same container
  private val userId  = 9001L
  private val roundId = 9002L

  private def img(pageId: Long, monumentId: String): Image =
    Image(pageId, s"File:Image$pageId.jpg", None, None, 640, 480, Some(monumentId))

  private def sel(pageId: Long, rate: Int, monumentId: String): Selection =
    Selection(pageId = pageId, juryId = userId, roundId = roundId, rate = rate,
              monumentId = Some(monumentId))

  // "rate" (unqualified): in grouped queries refers to the sum(s.rate) alias;
  // in per-juror queries it unambiguously refers to s.rate.
  private val galleryOrder = Map("rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1)

  "SelectionQuery.list" should {

    // Images:      A(pageId=1, monument=13-001, rate=1)
    //              B(pageId=2, monument=13-002, rate=1)
    //              C(pageId=3, monument=01-001, rate=0)
    // ORDER BY rate DESC, monument_id ASC, page_id ASC:
    //   rate=1, mon=13-001 → pageId=1  (A)
    //   rate=1, mon=13-002 → pageId=2  (B)
    //   rate=0, mon=01-001 → pageId=3  (C)
    "return images in rate DESC, monument_id ASC, page_id ASC order" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(1L, "13-001"), img(2L, "13-002"), img(3L, "01-001")))
      selectionDao.batchInsert(Seq(sel(1L, 1, "13-001"), sel(2L, 1, "13-002"), sel(3L, 0, "01-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(1L, 2L, 3L)
    }

    // Two images with rate=1; C (mon=01-001) < A (mon=13-001) alphabetically
    // so C must appear first when sorted monument_id ASC
    "sort by monument_id ASC when rates are equal" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(10L, "13-001"), img(11L, "01-001")))
      selectionDao.batchInsert(Seq(sel(10L, 1, "13-001"), sel(11L, 1, "01-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(11L, 10L)
    }

    // Two images same rate and monument_id → tie-break by page_id ASC
    "sort by page_id ASC as tie-breaker within same rate and monument" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(20L, "13-001"), img(21L, "13-001")))
      selectionDao.batchInsert(Seq(sel(20L, 1, "13-001"), sel(21L, 1, "13-001")))

      val result = SelectionQuery(userId = Some(userId), roundId = Some(roundId),
                                  order = galleryOrder).list()

      result.map(_.image.pageId) === Seq(20L, 21L)
    }

    "respect LIMIT and OFFSET" in new AutoRollbackDb {
      val images = (30L to 34L).map(id => img(id, s"13-0${id}"))
      imageDao.batchInsert(images)
      selectionDao.batchInsert(images.map(i => sel(i.pageId, 1, i.monumentId.get)))

      val result = SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        order   = Map("s.page_id" -> 1),
        limit   = Some(Limit(pageSize = Some(3), offset = Some(0)))
      ).list()

      result.map(_.image.pageId) === Seq(30L, 31L, 32L)
    }

    "return empty list when no selections exist for the round" in new AutoRollbackDb {
      val result = SelectionQuery(userId = Some(userId), roundId = Some(99999L)).list()
      result === Nil
    }
  }

  "SelectionQuery.count" should {

    "return the number of matching selections" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(40L, "13-001"), img(41L, "13-002"), img(42L, "07-001")))
      selectionDao.batchInsert(Seq(sel(40L, 1, "13-001"), sel(41L, 0, "13-002"), sel(42L, 1, "07-001")))

      SelectionQuery(userId = Some(userId), roundId = Some(roundId)).count() === 3
    }

    "agree with list().size" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(50L, "13-001"), img(51L, "07-001")))
      selectionDao.batchInsert(Seq(sel(50L, 1, "13-001"), sel(51L, 1, "07-001")))

      val q = SelectionQuery(userId = Some(userId), roundId = Some(roundId))
      q.count() === q.list().size
    }

    "return 0 when no selections exist" in new AutoRollbackDb {
      SelectionQuery(userId = Some(userId), roundId = Some(99998L)).count() === 0
    }
  }
}
