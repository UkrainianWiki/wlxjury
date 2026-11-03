package db.scalikejdbc

import db.scalikejdbc.ImageJdbc.FileList
import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

/** The contest files page's lists ([[ImageJdbc.FileList]]): a category's files, all or
  * deleted on Commons, and a round's deleted ones; in a region, and their regions' counts;
  * and a file's place in a list, which its large view pages from.
  */
class ContestFilesDbSpec extends Specification with BeforeAll with TestDb {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private def image(id: Long, monumentId: Option[String]) =
    Image(id, s"File:Image$id.jpg", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image$id.jpg"), None,
      800, 600, monumentId)

  /** Files 1 to 12 in a category: in region 07 the odd ones but 11, in 14 the even ones but
    * 12, in 123 (its prefix's first three characters) 11, and 12 without a monument id; and
    * file 13 of region 07 in another category. Deleted on Commons: 2 (14) and 3 (07) a day
    * apart, 3 the later, and 5 (07) with 3; 13 too.
    *
    * @return the category's id
    */
  private def categoryFixture()(implicit session: DBSession): Long = {
    def region(id: Long) =
      if (id == 11) Some("123-456-0001") else if (id == 12) None
      else Some(f"${if (id % 2 == 1) "07" else "14"}-101-$id%04d")
    imageDao.batchInsert((1L to 12L).map(id => image(id, region(id))) :+ image(13L, Some("07-101-0013")))
    val categoryId = sql"INSERT INTO category (title) VALUES ('Category:Files')".updateAndReturnGeneratedKey()
    val other = sql"INSERT INTO category (title) VALUES ('Category:Other')".updateAndReturnGeneratedKey()
    (1L to 12L).foreach(id => sql"INSERT INTO category_members (category_id, page_id) VALUES ($categoryId, $id)".update())
    sql"INSERT INTO category_members (category_id, page_id) VALUES ($other, 13)".update()
    sql"UPDATE images SET deleted_at = '2026-10-01 10:00:00' WHERE page_id = 2".update()
    sql"UPDATE images SET deleted_at = '2026-10-02 10:00:00' WHERE page_id IN (3, 5, 13)".update()
    categoryId
  }

  private def ids(files: Seq[ImageJdbc.StoredFile]): Seq[Long] = files.map(_.image.pageId)

  "a category's files" should {

    "be paged in page id order, all or in a region" in new AutoRollbackDb {
      val category = FileList.Category(categoryFixture(), deletedOnly = false)

      ids(ImageJdbc.listFiles(category, None, 5, 0)) === Seq(1L, 2L, 3L, 4L, 5L)
      ids(ImageJdbc.listFiles(category, None, 5, 10)) === Seq(11L, 12L)
      ids(ImageJdbc.listFiles(category, Some("07"), 3, 0)) === Seq(1L, 3L, 5L)
      ids(ImageJdbc.listFiles(category, Some("07"), 3, 3)) === Seq(7L, 9L)
      ids(ImageJdbc.listFiles(category, Some("14"), 10, 0)) === Seq(2L, 4L, 6L, 8L, 10L)
      ids(ImageJdbc.listFiles(category, Some("123"), 10, 0)) === Seq(11L)
      ids(ImageJdbc.listFiles(category, Some("12"), 10, 0)) must beEmpty
      // deleted ones marked
      ImageJdbc.listFiles(category, Some("07"), 2, 0).map(_.deletedAt.isDefined) === Seq(false, true)
    }

    "count their regions, those without a monument id left out" in new AutoRollbackDb {
      val categoryId = categoryFixture()

      ImageJdbc.regionCounts(FileList.Category(categoryId, deletedOnly = false)) ===
        Map("07" -> 5, "14" -> 5, "123" -> 1)
      ImageJdbc.regionCounts(FileList.Category(categoryId, deletedOnly = true)) === Map("07" -> 2, "14" -> 1)
    }

    "be only those deleted on Commons, the latest deleted first, all or in a region" in new AutoRollbackDb {
      val deleted = FileList.Category(categoryFixture(), deletedOnly = true)

      ids(ImageJdbc.listFiles(deleted, None, 10, 0)) === Seq(3L, 5L, 2L)
      ids(ImageJdbc.listFiles(deleted, None, 2, 1)) === Seq(5L, 2L)
      ids(ImageJdbc.listFiles(deleted, Some("07"), 10, 0)) === Seq(3L, 5L)
      ids(ImageJdbc.listFiles(deleted, Some("14"), 10, 0)) === Seq(2L)
      ImageJdbc.listFiles(deleted, None, 10, 0).forall(_.deletedAt.isDefined) must beTrue
    }

    "place a file in the list, as it is paged" in new AutoRollbackDb {
      val categoryId = categoryFixture()
      val all = FileList.Category(categoryId, deletedOnly = false)
      val deleted = FileList.Category(categoryId, deletedOnly = true)

      ImageJdbc.fileRank(all, None, 1L) === 0
      ImageJdbc.fileRank(all, None, 9L) === 8
      ImageJdbc.fileRank(all, Some("07"), 9L) === 4
      ImageJdbc.fileRank(all, Some("14"), 10L) === 4
      ImageJdbc.fileRank(deleted, None, 3L) === 0
      ImageJdbc.fileRank(deleted, None, 5L) === 1
      ImageJdbc.fileRank(deleted, None, 2L) === 2
      ImageJdbc.fileRank(deleted, Some("07"), 5L) === 1
      ImageJdbc.fileRank(deleted, Some("14"), 2L) === 0
    }

    "be read from the indexes alone in a region: the category's and images' (page_id, monument_id)" in
      new AutoRollbackDb {
        val categoryId = categoryFixture()
        // the plan of the region page's and counts' member scan (ImageJdbc.categoryMembers)
        val plan = sql"""EXPLAIN SELECT m.page_id FROM category_members m
                           STRAIGHT_JOIN images r FORCE INDEX (images_page_monument_idx) ON r.page_id = m.page_id
                         WHERE m.category_id = $categoryId AND r.monument_id LIKE '07%'
                         ORDER BY m.page_id LIMIT 5 OFFSET 2"""
          .map(rs => (rs.string("table"), rs.stringOpt("key"), rs.stringOpt("Extra").getOrElse("")))
          .list()

        plan.map(_._2) === Seq(Some("cm_category_page"), Some("images_page_monument_idx"))
        plan.forall(_._3.contains("Using index")) must beTrue
        plan.exists(_._3.contains("filesort")) must beFalse
      }
  }

  "a round's files deleted on Commons" should {

    "be listed, counted by region and placed, in a region or all" in new AutoRollbackDb {
      categoryFixture()
      implicit val contest: org.intracer.wmua.ContestJury = createContests(10).head
      val juror = userDao.create(contestUser(1))
      val roundId = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = false)).getId
      // 2 and 3 voted on, 3's votes moved aside; 5 not in the round; 4 not deleted
      Seq(2L, 3L, 4L).foreach(id => selectionDao.create(id, rate = 1, juryId = juror.getId, roundId = roundId))
      ImageJdbc.archiveVotes(Seq(3L), Set(roundId))
      val round = FileList.RoundDeleted(roundId)

      ids(ImageJdbc.listFiles(round, None, 10, 0)) === Seq(3L, 2L)
      ids(ImageJdbc.listFiles(round, Some("14"), 10, 0)) === Seq(2L)
      ids(ImageJdbc.listFiles(round, Some("07"), 10, 0)) === Seq(3L)
      ImageJdbc.regionCounts(round) === Map("07" -> 1, "14" -> 1)
      ImageJdbc.fileRank(round, None, 2L) === 1
      ImageJdbc.fileRank(round, Some("14"), 2L) === 0
    }
  }
}
