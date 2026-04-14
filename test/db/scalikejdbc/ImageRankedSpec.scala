package db.scalikejdbc

import org.intracer.wmua.{Image, Selection}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

class ImageRankedSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  // helpers
  private def img(pageId: Long): Image =
    Image(pageId = pageId, title = s"File:Image$pageId.jpg", width = 100, height = 100)

  private def sel(pageId: Long, juryId: Long, roundId: Long, rate: Int): Selection =
    Selection(pageId = pageId, juryId = juryId, roundId = roundId, rate = rate)

  // -------------------------------------------------------------------------
  // byUserImageWithRatingRanked
  // -------------------------------------------------------------------------

  "byUserImageWithRatingRanked" should {

    "return empty for no selections" in {
      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must beEmpty
    }

    "rank a single image as rank=1" in {
      imageDao.batchInsert(Seq(img(101L)))
      selectionDao.batchInsert(Seq(sel(101L, juryId = 1L, roundId = 1L, rate = 3)))

      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must haveSize(1)
      result.head.rank must_== Some(1)
      result.head.pageId must_== 101L
    }

    "order two images: higher rate gets rank=1" in {
      imageDao.batchInsert(Seq(img(201L), img(202L)))
      selectionDao.batchInsert(Seq(
        sel(201L, juryId = 1L, roundId = 1L, rate = 5),
        sel(202L, juryId = 1L, roundId = 1L, rate = 2)
      ))

      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must haveSize(2)
      result(0).pageId must_== 201L
      result(0).rank must_== Some(1)
      result(1).pageId must_== 202L
      result(1).rank must_== Some(2)
    }

    "rank three images with distinct rates correctly" in {
      imageDao.batchInsert(Seq(img(301L), img(302L), img(303L)))
      // insert in mixed order to ensure ordering is by rank, not insertion order
      selectionDao.batchInsert(Seq(
        sel(302L, juryId = 1L, roundId = 1L, rate = 2),
        sel(301L, juryId = 1L, roundId = 1L, rate = 3),
        sel(303L, juryId = 1L, roundId = 1L, rate = 1)
      ))

      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must haveSize(3)
      result(0).pageId must_== 301L
      result(0).rank must_== Some(1)
      result(1).pageId must_== 302L
      result(1).rank must_== Some(2)
      result(2).pageId must_== 303L
      result(2).rank must_== Some(3)
    }

    "isolate by juryId — other juror's selections not included" in {
      imageDao.batchInsert(Seq(img(401L), img(402L)))
      selectionDao.batchInsert(Seq(
        sel(401L, juryId = 1L, roundId = 1L, rate = 4),
        sel(402L, juryId = 2L, roundId = 1L, rate = 5) // different juror
      ))

      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must haveSize(1)
      result.head.pageId must_== 401L
    }

    "isolate by roundId — other round's selections not included" in {
      imageDao.batchInsert(Seq(img(501L), img(502L)))
      selectionDao.batchInsert(Seq(
        sel(501L, juryId = 1L, roundId = 1L, rate = 4),
        sel(502L, juryId = 1L, roundId = 2L, rate = 5) // different round
      ))

      val result = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L)
      result must haveSize(1)
      result.head.pageId must_== 501L
    }

    "respects pageSize and offset" in {
      val pageIds = 601L to 605L
      imageDao.batchInsert(pageIds.map(img))
      // rates 5,4,3,2,1 — page 601 gets rank 1, page 605 gets rank 5
      selectionDao.batchInsert(pageIds.zipWithIndex.map { case (pid, idx) =>
        sel(pid, juryId = 1L, roundId = 1L, rate = 5 - idx)
      })

      val firstPage = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L, pageSize = 2, offset = 0)
      firstPage must haveSize(2)
      firstPage(0).rank must_== Some(1)
      firstPage(1).rank must_== Some(2)

      val secondPage = imageDao.byUserImageWithRatingRanked(userId = 1L, roundId = 1L, pageSize = 2, offset = 2)
      secondPage must haveSize(2)
      secondPage(0).rank must_== Some(3)
      secondPage(1).rank must_== Some(4)
    }
  }

  // -------------------------------------------------------------------------
  // byUserImageRangeRanked
  // -------------------------------------------------------------------------

  "byUserImageRangeRanked" should {

    "return empty for no selections" in {
      val result = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L)
      result must beEmpty
    }

    "rank a single image as rank=1, rank2=1" in {
      imageDao.batchInsert(Seq(img(701L)))
      selectionDao.batchInsert(Seq(sel(701L, juryId = 1L, roundId = 1L, rate = 3)))

      val result = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L)
      result must haveSize(1)
      result.head.rank must_== Some(1)
      result.head.rank2 must_== Some(1)
      result.head.pageId must_== 701L
    }

    "compute rank1 (DESC) and rank2 (ASC) correctly for two images" in {
      imageDao.batchInsert(Seq(img(801L), img(802L)))
      selectionDao.batchInsert(Seq(
        sel(801L, juryId = 1L, roundId = 1L, rate = 5),
        sel(802L, juryId = 1L, roundId = 1L, rate = 2)
      ))

      val result = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L)
      result must haveSize(2)

      // highest rate (5) → rank1=1 (best by DESC), rank2=1 (1 image has rate >= 5)
      val best = result.find(_.pageId == 801L).get
      best.rank must_== Some(1)
      best.rank2 must_== Some(1)

      // lowest rate (2) → rank1=2 (worst by DESC), rank2=2 (2 images have rate >= 2)
      val worst = result.find(_.pageId == 802L).get
      worst.rank must_== Some(2)
      worst.rank2 must_== Some(2)
    }

    "orders results by rank1 ascending" in {
      imageDao.batchInsert(Seq(img(901L), img(902L), img(903L)))
      selectionDao.batchInsert(Seq(
        sel(903L, juryId = 1L, roundId = 1L, rate = 1),
        sel(901L, juryId = 1L, roundId = 1L, rate = 3),
        sel(902L, juryId = 1L, roundId = 1L, rate = 2)
      ))

      val result = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L)
      result must haveSize(3)
      result.map(_.rank) must_== Seq(Some(1), Some(2), Some(3))
      result(0).pageId must_== 901L
      result(1).pageId must_== 902L
      result(2).pageId must_== 903L
    }

    "respects pageSize and offset" in {
      val pageIds = 1001L to 1004L
      imageDao.batchInsert(pageIds.map(img))
      // rates 4,3,2,1 → ranks 1,2,3,4
      selectionDao.batchInsert(pageIds.zipWithIndex.map { case (pid, idx) =>
        sel(pid, juryId = 1L, roundId = 1L, rate = 4 - idx)
      })

      val firstPage = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L, pageSize = 2, offset = 0)
      firstPage must haveSize(2)
      firstPage(0).rank must_== Some(1)
      firstPage(1).rank must_== Some(2)

      val secondPage = imageDao.byUserImageRangeRanked(userId = 1L, roundId = 1L, pageSize = 2, offset = 2)
      secondPage must haveSize(2)
      secondPage(0).rank must_== Some(3)
      secondPage(1).rank must_== Some(4)
    }
  }
}
