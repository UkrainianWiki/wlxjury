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

  "SelectionQuery.list with region filter" should {

    // Single short region → LIKE branch: i.monument_id like '07%'
    "return only images whose monument_id starts with the given region prefix" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(200L, "07-001"), img(201L, "07-002"), img(202L, "08-001")))
      selectionDao.batchInsert(Seq(sel(200L, 1, "07-001"), sel(201L, 1, "07-002"), sel(202L, 1, "08-001")))

      val result = SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        regions = Set("07"),
        order = Map("s.page_id" -> 1)
      ).list()

      result.map(_.image.pageId) === Seq(200L, 201L)
    }

    "return no images when region matches nothing" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(210L, "07-001")))
      selectionDao.batchInsert(Seq(sel(210L, 1, "07-001")))

      SelectionQuery(
        userId = Some(userId), roundId = Some(roundId),
        regions = Set("99")
      ).list() === Nil
    }
  }

  "SQL injection via regions parameter" should {

    "treat injection payload as literal data, return empty result (not an exception)" in new AutoRollbackDb {
      // Before fix: WHERE i.monument_id like 'uk'%' → SQL syntax error (exception)
      // After fix:  WHERE i.monument_id like ?      bind: ["uk'%"] → 0 results, no error
      SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        regions = Set("uk'")
      ).list() === Nil
    }

    "multi-region injection payload is treated as data, not executed SQL" in new AutoRollbackDb {
      val payload = "uk' UNION SELECT 1,2,3,4,5,6,7,8,9,10,11,12,13,14--"
      SelectionQuery(
        userId  = Some(userId),
        roundId = Some(roundId),
        regions = Set(payload, "de")
      ).list() === Nil
    }
  }

  "a juror's gallery page (deferred join)" should {

    // rates and monuments chosen so every ORDER BY column decides some pair
    val rows = Seq(
      (300L, 1, "13-002"), (301L, 0, "01-001"), (302L, 1, "13-001"), (303L, -1, "07-001"),
      (304L, 1, "13-001"), (305L, 0, "07-002"), (306L, 1, "07-003"), (307L, -1, "01-002")
    )
    // rate DESC, monument_id ASC, page_id ASC
    val expectedOrder = Seq(306L, 302L, 304L, 300L, 301L, 305L, 307L, 303L)

    def insertRows()(implicit session: scalikejdbc.DBSession): Unit = {
      imageDao.batchInsert(rows.map { case (p, _, m) => img(p, m) })
      selectionDao.batchInsert(rows.map { case (p, r, m) => sel(p, r, m) })
    }

    def page(size: Int, offset: Int, regions: Set[String] = Set.empty) = SelectionQuery(
      userId = Some(userId), roundId = Some(roundId), regions = regions,
      order = galleryOrder, limit = Some(Limit(pageSize = Some(size), offset = Some(offset)))
    )

    "page ids through the selection rows alone, then join the page's rows" in {
      val sql = page(3, 0).query().value
      sql must contain("from (select s.id from selection s")
      sql must contain("STRAIGHT_JOIN images i")
    }

    "keep the gallery order across pages" in new AutoRollbackDb {
      insertRows()
      val pages = (0 until 3).map(n => page(3, n * 3).list().map(_.image.pageId))
      pages.flatten === expectedOrder
      pages.map(_.size) === Seq(3, 3, 2)
    }

    "return the same images and selections as the unpaged query" in new AutoRollbackDb {
      insertRows()
      val all = SelectionQuery(userId = Some(userId), roundId = Some(roundId), order = galleryOrder).list()
      page(8, 0).list() === all
      all.map(_.image.pageId) === expectedOrder
    }

    "filter a region by selection's monument id" in new AutoRollbackDb {
      insertRows()
      page(2, 0, Set("13")).list().map(_.image.pageId) === Seq(302L, 304L)
      page(2, 2, Set("13")).list().map(_.image.pageId) === Seq(300L)
      page(2, 0, Set("13")).count() === 3
    }

    "skip a selection row whose image is gone, like the image rank does" in new AutoRollbackDb {
      insertRows()
      // an orphan row that would sort first: rate 1, the lowest monument id
      selectionDao.batchInsert(Seq(sel(399L, 1, "00-001")))
      val pages = (0 until 3).map(n => page(3, n * 3).list().map(_.image.pageId))
      pages.flatten === expectedOrder
      pages.map(_.size) === Seq(3, 3, 2)
      // imageRank (the large view's navigation) agrees with the pages' positions
      expectedOrder.zipWithIndex.map { case (p, i) => page(3, 0).imageRank(p) -> (i + 1) }
        .forall { case (rank, pos) => rank == pos } must beTrue
    }

    "not apply to the organizer's grouped view" in {
      val sql = SelectionQuery(roundId = Some(roundId), grouped = true, order = galleryOrder,
        limit = Some(Limit(pageSize = Some(3), offset = Some(0)))).query().value
      sql must not(contain("select s.id from selection s"))
    }
  }

  "SelectionQuery.count of a juror's images" should {

    "count rows without DISTINCT, the juror having one row per image" in {
      SelectionQuery(userId = Some(userId), roundId = Some(roundId)).query(count = true).value must
        contain("COUNT(*)")
      SelectionQuery(roundId = Some(roundId)).query(count = true).value must
        contain("COUNT(DISTINCT s.page_id)")
    }

    "count distinct images when the round isn't fixed" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(410L, "07-001"), img(411L, "07-002")))
      // the juror has image 410 in two rounds
      selectionDao.batchInsert(Seq(sel(410L, 1, "07-001"), sel(411L, 0, "07-002"),
        sel(410L, 0, "07-001").copy(roundId = roundId + 1)))
      val q = SelectionQuery(userId = Some(userId))
      q.query(count = true).value must contain("COUNT(DISTINCT s.page_id)")
      q.count() === 2
      SelectionQuery(userId = Some(userId), regions = Set("07")).count() === 3 // row count, as before
    }

    "count a region on selection alone, matching the list" in new AutoRollbackDb {
      imageDao.batchInsert(Seq(img(400L, "07-001"), img(401L, "07-002"), img(402L, "08-001")))
      selectionDao.batchInsert(Seq(sel(400L, 1, "07-001"), sel(401L, 0, "07-002"), sel(402L, 1, "08-001")))
      val q = SelectionQuery(userId = Some(userId), roundId = Some(roundId), regions = Set("07"))
      q.query(count = true).value must not(contain("images"))
      q.count() === 2
      q.count() === q.list().size
    }
  }
}
