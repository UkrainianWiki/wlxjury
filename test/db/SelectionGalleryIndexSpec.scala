package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

/** V61 stores selection's rate descending in the juror gallery's index, so the page order
  * (rate DESC, monument_id ASC, page_id ASC) is read from the index with no sort. On
  * MariaDB before 10.8 the key would silently be ascending: this guards the server version
  * as much as the migration.
  */
class SelectionGalleryIndexSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val index = "idx_selection_jury_round_ratedesc_mon_page"

  "V61" should {

    "replace V48's gallery index with one whose rate is descending" in {
      val columns = DB.readOnly { implicit s =>
        sql"""SELECT INDEX_NAME, COLUMN_NAME, COLLATION FROM information_schema.STATISTICS
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'selection'
                AND INDEX_NAME IN ($index, 'idx_selection_jury_round_rate_mon_page')
              ORDER BY INDEX_NAME, SEQ_IN_INDEX"""
          .map(rs => (rs.string(1), rs.string(2), rs.string(3))).list.apply()
      }
      columns === List(
        (index, "jury_id", "A"),
        (index, "round_id", "A"),
        (index, "rate", "D"),
        (index, "monument_id", "A"),
        (index, "page_id", "A")
      )
    }

    "give a gallery page in its order without a filesort" in {
      insertImagesFor(1L to 40L: _*)
      DB.autoCommit { implicit s =>
        (1L to 40L).foreach { pageId =>
          val monumentId = f"${pageId % 3}%02d-101-${pageId}%04d"
          sql"""INSERT INTO selection (jury_id, round_id, page_id, rate, monument_id)
                VALUES (1, 1, $pageId, ${pageId % 5}, $monumentId), (2, 1, $pageId, 0, $monumentId)"""
            .update.apply()
        }
      }
      val order = "ORDER BY rate DESC, s.monument_id ASC, s.page_id ASC"
      val (extra, ids) = DB.readOnly { implicit s =>
        val page = s"SELECT s.id, s.rate, s.monument_id, s.page_id FROM selection s FORCE INDEX ($index) " +
          s"WHERE s.jury_id = 1 AND s.round_id = 1 $order LIMIT 15 OFFSET 5"
        val extra = SQL(s"EXPLAIN $page").map(_.stringOpt("Extra").getOrElse("")).list.apply().mkString
        val rows = SQL(page).map(rs => (rs.int(2), rs.string(3), rs.long(4))).list.apply()
        (extra, rows)
      }
      extra must not(contain("filesort"))
      ids must haveSize(15)
      ids === ids.sortBy { case (rate, monumentId, pageId) => (-rate, monumentId, pageId) }
    }
  }
}
