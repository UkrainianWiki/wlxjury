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
  * V85 changes nothing and warns. V99 adds `voted_at` as the index's last column (the vote-time
  * orders read only the index), keeping its name, and on 10.8+ does V85's swap too: after the
  * move it is V99 that runs again.
  *
  * V100 drops vote's single-column `vote_round_idx` and `vote_jury_id_idx` (prefixes of the
  * composite indexes, the gallery index among them), whose index-merge intersection 10.6 chose
  * over the gallery index.
  *
  * The examples follow the test server (`-Dwlxjury.test.mariadb-image`): the default 10.6
  * checks the no-op, 10.11 the swap. The re-run examples also check the start's backstop,
  * [[Migrations.galleryIndexState]].
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

  /** The index since V99: voted_at last. */
  private def indexV99(name: String, rate: String) = index(name, rate) :+ ((name, "voted_at", "A"))

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
  private def runV99(c: Connection): Seq[String] = MigrationScript.run(c, "V99__Vote_gallery_index_voted_at.sql")
  private def runV100(c: Connection): Seq[String] = MigrationScript.run(c, "V100__Vote_drop_single_column_indexes.sql")

  /** Each index of a vote table with its first column: (index, column). */
  private def leadingColumns(c: Connection, database: String): List[(String, String)] =
    Using.resource(c.prepareStatement(
      """SELECT INDEX_NAME, COLUMN_NAME FROM information_schema.STATISTICS
         WHERE TABLE_SCHEMA = ? AND TABLE_NAME = 'vote' AND SEQ_IN_INDEX = 1 ORDER BY INDEX_NAME""")) { st =>
      st.setString(1, database)
      Using.resource(st.executeQuery()) { rs =>
        Iterator.continually(rs).takeWhile(_.next()).map(r => (r.getString(1), r.getString(2))).toList
      }
    }

  private def state: Migrations.GalleryIndexState =
    Migrations.galleryIndexState(url(scratch), "root", SharedTestDb.password)

  /** The ALTER TABLE statements the session has run so far (EXECUTE IMMEDIATE's included). */
  private def alters(c: Connection): Long =
    Using.resource(c.createStatement())(st => Using.resource(st.executeQuery("SHOW SESSION STATUS LIKE 'Com_alter_table'")) { rs =>
      rs.next(); rs.getLong(2)
    })

  private val oldIndex   = s"ADD INDEX $ascending (jury_id, round_id, rate, monument_id, page_id)"
  private val oldIndexV99 = s"ADD INDEX $ascending (jury_id, round_id, rate, monument_id, page_id, voted_at)"
  private val newIndex   = s"ADD INDEX $descending (jury_id, round_id, rate DESC, monument_id, page_id)"
  private val newIndexV99 = s"ADD INDEX $descending (jury_id, round_id, rate DESC, monument_id, page_id, voted_at)"

  "V85" should {

    "leave the gallery index as the server can store it, with V99's voted_at last" in {
      val columns = Using.resource(root("wlxjury"))(galleryIndexes(_, "wlxjury"))
      val flagged = Migrations.galleryIndexState(SharedTestDb.jdbcUrl, "root", SharedTestDb.password)
      (flagged === Migrations.GalleryIndexState(leftAscending = false, withoutVotedAt = false)) and
        (if (SharedTestDb.descendingIndexKeys) columns === indexV99(descending, "D")
         else columns === indexV99(ascending, "A"))
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

  "V99" should {

    "add voted_at to production's ascending index, and change nothing when run again" in {
      // a vote table as production's 10.6 has it before V99: V82's ascending index
      scratchVote(oldIndex)
      Using.resource(root(scratch)) { c =>
        val before = state
        val altersBefore = alters(c)
        val warnings = runV99(c).filter(_.startsWith("V99"))
        val altered = alters(c) - altersBefore
        val first = galleryIndexes(c, scratch)
        val after = state
        val altersAgain = alters(c)
        val again = runV99(c)
        val alteredAgain = alters(c) - altersAgain
        (before.withoutVotedAt must beTrue) and (after.withoutVotedAt must beFalse) and
          (altered === 1L) and (alteredAgain === 0L) and (galleryIndexes(c, scratch) === first) and
          (after.leftAscending must beFalse) and
          (if (SharedTestDb.descendingIndexKeys)
             // 10.8+: V85's swap as well, the descending index alone
             (before.leftAscending must beTrue) and (first === indexV99(descending, "D")) and
               (warnings must beEmpty) and (again must beEmpty)
           else
             (first === indexV99(ascending, "A")) and (again === warnings) and
               (warnings must haveSize(1)) and (warnings.head must contain("needs MariaDB 10.8+")))
      }
    }

    "add voted_at to V85's descending-named index, under its name" in {
      // after V85 on 10.8+ (on 10.6 DESC is parsed and ignored: the descending name, rate ascending)
      scratchVote(newIndex)
      Using.resource(root(scratch)) { c =>
        val before = state
        runV99(c)
        val first = galleryIndexes(c, scratch)
        val altersAgain = alters(c)
        runV99(c)
        val rate = if (SharedTestDb.descendingIndexKeys) "D" else "A"
        (before.withoutVotedAt must beTrue) and (state.withoutVotedAt must beFalse) and
          (state.leftAscending must beFalse) and (first === indexV99(descending, rate)) and
          (alters(c) - altersAgain === 0L) and (galleryIndexes(c, scratch) === first)
      }
    }

    "with both gallery indexes, add voted_at to each before 10.8, keep the descending one alone on 10.8+" in {
      // before 10.8 the descending-named index is stored ascending: two ascending indexes
      scratchVote(newIndex, oldIndex)
      Using.resource(root(scratch)) { c =>
        val before = state
        runV99(c)
        val first = galleryIndexes(c, scratch)
        val altersAgain = alters(c)
        runV99(c)
        (before.withoutVotedAt must beTrue) and (state.withoutVotedAt must beFalse) and
          (state.leftAscending must beFalse) and
          (alters(c) - altersAgain === 0L) and (galleryIndexes(c, scratch) === first) and
          (if (SharedTestDb.descendingIndexKeys) first === indexV99(descending, "D")
           // by index name (stable: each index's columns stay in order), whatever the collation's order
           else first.sortBy(_._1) === (indexV99(ascending, "A") ++ indexV99(descending, "A")).sortBy(_._1))
      }
    }

    "run nothing where the index already has voted_at last" in {
      val done = if (SharedTestDb.descendingIndexKeys) newIndexV99 else oldIndexV99
      scratchVote(done)
      Using.resource(root(scratch)) { c =>
        val first = galleryIndexes(c, scratch)
        val altersBefore = alters(c)
        runV99(c)
        (alters(c) - altersBefore === 0L) and (galleryIndexes(c, scratch) === first) and
          (state === Migrations.GalleryIndexState(leftAscending = false, withoutVotedAt = false))
      }
    }

    "run nothing where neither gallery index exists" in {
      scratchVote("ADD INDEX vote_other_idx (round_id)")
      Using.resource(root(scratch)) { c =>
        val altersBefore = alters(c)
        val warnings = runV99(c)
        (alters(c) - altersBefore === 0L) and (galleryIndexes(c, scratch) must beEmpty) and (warnings must beEmpty)
      }
    }

    "on 10.8+, end with the descending index with voted_at alone, from every state, and repair V85 run again" in {
      if (!SharedTestDb.descendingIndexKeys) skipped("needs MariaDB 10.8+ (-Dwlxjury.test.mariadb-image)")
      val states = Seq(
        // V99 ran on 10.6, then the server move: the re-run
        "ascending with voted_at" -> Seq(oldIndexV99),
        // the target and the old index still there
        "target and old" -> Seq(newIndexV99, oldIndex),
        // V85 swapped part-way, V99 not yet
        "descending and old" -> Seq(newIndex, oldIndex),
        // the descending-named index built before 10.8 (a dump of a 10.6 database after V99)
        "ascending new with voted_at" -> Seq(s"ADD INDEX $descending (jury_id, round_id, rate, monument_id, page_id, voted_at)"),
        "ascending new and old with voted_at" ->
          Seq(s"ADD INDEX $descending (jury_id, round_id, rate, monument_id, page_id)", oldIndexV99))
      foreach(states) { case (name, indexes) =>
        scratchVote(indexes: _*)
        Using.resource(root(scratch)) { c =>
          val flaggedBefore = state
          val warnings = runV99(c)
          val after = galleryIndexes(c, scratch)
          val altersAgain = alters(c)
          val again = runV99(c)
          (flaggedBefore aka s"$name: flagged before" must not(beEqualTo(Migrations.GalleryIndexState(false, false)))) and
            (after aka s"$name: the indexes after" must_== indexV99(descending, "D")) and
            (state aka s"$name: flagged after" must_== Migrations.GalleryIndexState(false, false)) and
            ((warnings ++ again) aka s"$name: warnings" must beEmpty) and
            ((alters(c) - altersAgain) aka s"$name: ALTERs of a second run" must_== 0L)
        }
      }
      // V85 run again after V99 (the old instructions): it leaves the target alone, but from the
      // ascending index with voted_at it builds its own without; the start flags it, V99 repairs it
      scratchVote(newIndexV99)
      val targetKept = Using.resource(root(scratch)) { c => runV85(c); galleryIndexes(c, scratch) }
      scratchVote(oldIndexV99)
      Using.resource(root(scratch)) { c =>
        runV85(c)
        val afterV85 = galleryIndexes(c, scratch)
        val flagged = state
        runV99(c)
        (targetKept === indexV99(descending, "D")) and (afterV85 === index(descending, "D")) and
          (flagged === Migrations.GalleryIndexState(leftAscending = false, withoutVotedAt = true)) and
          (galleryIndexes(c, scratch) === indexV99(descending, "D")) and
          (state === Migrations.GalleryIndexState(false, false))
      }
    }

    "give a vote-time order's page from the index alone" in {
      insertImagesFor(1L to 40L: _*)
      DB.autoCommit { implicit s =>
        (1L to 40L).foreach { pageId =>
          val monumentId = f"${pageId % 3}%02d-101-${pageId}%04d"
          val votedAt = if (pageId % 4 == 0) "NULL" else s"'2026-11-${10 + pageId % 3} 12:00:${pageId % 60}.000'"
          SQL(s"""INSERT INTO vote (jury_id, round_id, page_id, rate, monument_id, voted_at)
                  VALUES (1, 1, $pageId, ${pageId % 3}, '$monumentId', $votedAt)""").update.apply()
        }
      }
      val gallery = SharedTestDb.voteGalleryIndex
      val extra = DB.readOnly { implicit s =>
        // the deferred join's inner query in sort=latest-vote (RoundList.ListOrder.LatestVote)
        val page = s"SELECT s.id, s.page_id FROM vote s FORCE INDEX ($gallery) " +
          "WHERE s.jury_id = 1 AND s.round_id = 1 AND s.page_id IS NOT NULL " +
          "ORDER BY (s.rate = 0), COALESCE(UNIX_TIMESTAMP(s.voted_at), 0) DESC, s.monument_id, s.page_id LIMIT 15"
        SQL(s"EXPLAIN $page").map(_.stringOpt("Extra").getOrElse("")).list.apply().mkString
      }
      // a covering read ("Using index"), not "Using index condition" with row reads
      extra must beMatching("(?s).*Using index(;.*|$)")
    }
  }

  "V100" should {

    val singles = Set("vote_round_idx", "vote_jury_id_idx")
    // the gallery index as V99 leaves it on this server
    def galleryDone = if (SharedTestDb.descendingIndexKeys) newIndexV99 else oldIndexV99

    "leave the migrated database without the single-column round and juror indexes, each column still leading one" in {
      Using.resource(root("")) { c =>
        val leading = leadingColumns(c, "wlxjury")
        ((leading.map(_._1).toSet intersect singles) must beEmpty) and
          (leading.collect { case (i, "round_id") => i } must not(beEmpty)) and
          (leading.collect { case (i, "jury_id") => i } must not(beEmpty))
      }
    }

    "drop both where they exist, keep the other indexes, and change nothing when run again" in {
      // a vote table as production's has it before V100
      scratchVote(galleryDone, "ADD INDEX vote_round_idx (round_id)", "ADD INDEX vote_jury_id_idx (jury_id)")
      Using.resource(root(scratch)) { c =>
        val before = leadingColumns(c, scratch)
        val gallery = galleryIndexes(c, scratch)
        val altersBefore = alters(c)
        val warnings = runV100(c)
        val altered = alters(c) - altersBefore
        val after = leadingColumns(c, scratch)
        val altersAgain = alters(c)
        runV100(c)
        ((before.map(_._1).toSet intersect singles) === singles) and (altered === 1L) and (warnings must beEmpty) and
          (after === before.filterNot(i => singles(i._1))) and (galleryIndexes(c, scratch) === gallery) and
          (after.collect { case (i, "round_id") => i } must not(beEmpty)) and
          (after.collect { case (i, "jury_id") => i } must not(beEmpty)) and
          (alters(c) - altersAgain === 0L) and (leadingColumns(c, scratch) === after)
      }
    }

    "drop the one left where the other is already gone" in {
      scratchVote(galleryDone, "ADD INDEX vote_jury_id_idx (jury_id)")
      Using.resource(root(scratch)) { c =>
        val altersBefore = alters(c)
        runV100(c)
        val leading = leadingColumns(c, scratch)
        (alters(c) - altersBefore === 1L) and ((leading.map(_._1).toSet intersect singles) must beEmpty) and
          (leading.collect { case (i, "jury_id") => i } must not(beEmpty))
      }
    }
  }
}
