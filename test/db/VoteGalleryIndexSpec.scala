package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}

import java.sql.{Connection, DriverManager}
import scala.util.Using

/** V85 stores vote's rate descending in the juror gallery's index on MariaDB 10.8+, so the page
  * order (rate DESC, monument_id ASC, page_id ASC) is read from the index with no sort. Before
  * 10.8 (production's 10.6 until the server move) the key would silently be ascending: there
  * V85 changes nothing and warns, and its ALTER is run again after the move.
  *
  * The examples follow the test server (`-Dwlxjury.test.mariadb-image`): the default 10.6
  * checks the no-op, 10.11 the swap. The re-run example also checks the start's backstop,
  * [[Migrations.galleryIndexLeftAscending]].
  */
class VoteGalleryIndexSpec extends Specification with TestDb with BeforeAll with BeforeEach with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val ascending  = "vote_jury_round_rate_mon_page_idx"
  private val descending = "vote_jury_round_ratedesc_mon_page_idx"
  private val scratch    = "v85_rerun"

  override def afterAll(): Unit = Using.resource(root("")) { c =>
    Using.resource(c.createStatement())(_.execute(s"DROP DATABASE IF EXISTS $scratch"))
  }

  private def url(database: String): String =
    SharedTestDb.jdbcUrl.replaceFirst("/wlxjury(\\?|$)", s"/$database$$1")

  private def root(database: String): Connection = DriverManager.getConnection(url(database), "root", SharedTestDb.password)

  /** The gallery indexes of a vote table: (index, column, collation A/D). */
  private def galleryIndexes(c: Connection, database: String): List[(String, String, String)] =
    Using.resource(c.prepareStatement(
      """SELECT INDEX_NAME, COLUMN_NAME, COLLATION FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'vote' AND INDEX_NAME IN (?, ?)
         ORDER BY INDEX_NAME, SEQ_IN_INDEX""")) { st =>
      st.setString(1, database); st.setString(2, ascending); st.setString(3, descending)
      Using.resource(st.executeQuery()) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(r => (r.getString(1), r.getString(2), r.getString(3))).toList
      }
    }

  private def index(name: String, rate: String) = List(
    (name, "jury_id", "A"), (name, "round_id", "A"), (name, "rate", rate), (name, "monument_id", "A"), (name, "page_id", "A"))

  /** A scratch vote table (wlxjury.vote's columns and other indexes) with these gallery indexes,
    * as ALTER TABLE clauses, instead of the test database's. */
  private def scratchVote(indexes: String*): Unit = Using.resource(root("")) { c =>
    Using.resource(c.createStatement()) { st =>
      st.execute(s"DROP DATABASE IF EXISTS $scratch")
      st.execute(s"CREATE DATABASE $scratch")
      st.execute(s"CREATE TABLE $scratch.vote LIKE wlxjury.vote")
      st.execute(s"ALTER TABLE $scratch.vote DROP INDEX IF EXISTS $descending, DROP INDEX IF EXISTS $ascending")
      st.execute(s"ALTER TABLE $scratch.vote ${indexes.mkString(", ")}")
    }
  }

  private def runV85(c: Connection): Seq[String] = MigrationScript.run(c, "V85__Vote_gallery_index_rate_desc.sql")

  "V85" should {

    "leave the gallery index as the server can store it" in {
      val columns = Using.resource(root("wlxjury"))(galleryIndexes(_, "wlxjury"))
      if (SharedTestDb.descendingIndexKeys) columns === index(descending, "D")
      else columns === index(ascending, "A")
    }

    "be run again after the server move, and only then change the index" in {
      def leftAscending = Migrations.galleryIndexLeftAscending(url(scratch), "root", SharedTestDb.password)
      // a vote table as production's 10.6 has it after V82..V85: the ascending index
      scratchVote(s"ADD INDEX $ascending (jury_id, round_id, rate, monument_id, page_id)")
      Using.resource(root(scratch)) { c =>
        val flaggedBefore = leftAscending
        val warnings = runV85(c).filter(_.startsWith("V85"))
        val first = galleryIndexes(c, scratch)
        // a second run where the swap is done runs nothing: not even IF EXISTS notes
        val again = runV85(c)
        val second = galleryIndexes(c, scratch)
        if (SharedTestDb.descendingIndexKeys)
          (flaggedBefore must beTrue) and (leftAscending must beFalse) and
            (first === index(descending, "D")) and (second === first) and (warnings must beEmpty) and (again must beEmpty)
        else
          (flaggedBefore must beFalse) and (leftAscending must beFalse) and
            (first === index(ascending, "A")) and (second === first) and (again === warnings) and
            (warnings must haveSize(1)) and (warnings.head must contain("needs MariaDB 10.8+"))
      }
    }

    "on 10.8+, finish a part-way state and rebuild a descending-named index that is ascending" in {
      if (!SharedTestDb.descendingIndexKeys) skipped("needs MariaDB 10.8+ (-Dwlxjury.test.mariadb-image)")
      def leftAscending = Migrations.galleryIndexLeftAscending(url(scratch), "root", SharedTestDb.password)
      val oldIndex = s"ADD INDEX $ascending (jury_id, round_id, rate, monument_id, page_id)"
      val states = Seq(
        // the new index descending, the old one still there
        "both" -> Seq(s"ADD INDEX $descending (jury_id, round_id, rate DESC, monument_id, page_id)", oldIndex),
        // the new index ascending (built before 10.8), with and without the old one
        "ascending new and old" -> Seq(s"ADD INDEX $descending (jury_id, round_id, rate, monument_id, page_id)", oldIndex),
        "ascending new alone" -> Seq(s"ADD INDEX $descending (jury_id, round_id, rate, monument_id, page_id)"))
      foreach(states) { case (state, indexes) =>
        scratchVote(indexes: _*)
        Using.resource(root(scratch)) { c =>
          val flaggedBefore = leftAscending
          val warnings = runV85(c)
          val after = galleryIndexes(c, scratch)
          val again = runV85(c)
          (flaggedBefore aka s"$state: flagged before" must beTrue) and
            (after aka s"$state: the indexes after" must_== index(descending, "D")) and
            (leftAscending aka s"$state: flagged after" must beFalse) and
            ((warnings ++ again) aka s"$state: warnings" must beEmpty) and
            (galleryIndexes(c, scratch) aka s"$state: the indexes after a second run" must_== after)
        }
      }
    }

    "give a gallery page in its order, without a filesort on 10.8+" in {
      insertImagesFor(1L to 40L: _*)
      DB.autoCommit { implicit s =>
        (1L to 40L).foreach { pageId =>
          val monumentId = f"${pageId % 3}%02d-101-${pageId}%04d"
          sql"""INSERT INTO vote (jury_id, round_id, page_id, rate, monument_id)
                VALUES (1, 1, $pageId, ${pageId % 5}, $monumentId), (2, 1, $pageId, 0, $monumentId)"""
            .update.apply()
        }
      }
      val gallery = SharedTestDb.voteGalleryIndex
      val (extra, rows) = DB.readOnly { implicit s =>
        // ImageDbNew's deferred join's inner query, a juror's gallery page. FORCE INDEX: on a few
        // rows the optimizer may scan the table instead; the check is what the index can give.
        val page = s"SELECT s.id, s.rate, s.monument_id, s.page_id FROM vote s FORCE INDEX ($gallery) " +
          "WHERE s.jury_id = 1 AND s.round_id = 1 AND s.page_id IS NOT NULL " +
          "ORDER BY s.rate DESC, s.monument_id ASC, s.page_id ASC LIMIT 15 OFFSET 5"
        val extra = SQL(s"EXPLAIN $page").map(_.stringOpt("Extra").getOrElse("")).list.apply().mkString
        val rows = SQL(page).map(rs => (rs.int(2), rs.string(3), rs.long(4))).list.apply()
        (extra, rows)
      }
      val ordered = (rows must haveSize(15)) and
        (rows === rows.sortBy { case (rate, monumentId, pageId) => (-rate, monumentId, pageId) })
      if (SharedTestDb.descendingIndexKeys) ordered and (extra must not(contain("filesort")))
      else ordered and (extra must contain("filesort"))
    }
  }
}
