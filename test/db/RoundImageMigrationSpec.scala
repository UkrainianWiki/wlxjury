package db

import _root_.scalikejdbc._
import db.scalikejdbc.{RoundImageJdbc, SharedTestDb, TestDb}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import java.nio.file.{Files, Paths}

/** V84: round_image, a round's files, backfilled from vote ('in') and vote_aside ('deleted');
  * run on copies of the tables (t_...) as V84 finds them, as other specs use the shared ones
  * meanwhile. The backfill must equal the GROUP BY over the votes, and a rerun change nothing.
  */
class RoundImageMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val tables = "round_image|round|images|vote_aside|vote"

  private val v84: Seq[String] = {
    val text = new String(Files.readAllBytes(Paths.get("conf/db/migration/default/V84__Round_image.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replaceAll(s"\\b(FROM|JOIN|EXISTS|INTO|TABLE|REFERENCES|UPDATE)(\\s+)($tables)\\b", "$1$2t_$3")
        .replaceAll("\\bCONSTRAINT\\s+(\\w+)", "CONSTRAINT t_$1")
        .replaceAll("\\bUPDATE round_id = round_image\\.round_id", "UPDATE round_id = t_round_image.round_id"))
  }

  /** The plan's rollback SQL (P1 results), on the copies. */
  private val rollback = Seq("DROP TABLE t_round_image")

  private val copies = Seq("t_round_image", "t_vote_aside", "t_vote", "t_images", "t_round")

  private def dropCopies(): Unit = DB.autoCommit { implicit s =>
    sql"SET FOREIGN_KEY_CHECKS = 0".execute.apply()
    copies.foreach(t => SQL(s"DROP TABLE IF EXISTS $t").execute.apply())
    sql"SET FOREIGN_KEY_CHECKS = 1".execute.apply()
  }

  private def createCopies(): Unit = DB.autoCommit { implicit s =>
    sql"CREATE TABLE t_round LIKE round".execute.apply()
    sql"CREATE TABLE t_images LIKE images".execute.apply()
    sql"CREATE TABLE t_vote LIKE vote".execute.apply()
    sql"CREATE TABLE t_vote_aside LIKE vote_aside".execute.apply()
  }

  private def run(statements: Seq[String]): Unit =
    DB.autoCommit { implicit s => statements.foreach(st => SQL(st).execute.apply()) }

  private def query[A](q: String)(f: WrappedResultSet => A): List[A] = DB.readOnly(implicit s => SQL(q).map(f).list())

  private def showCreate(table: String): String =
    DB.readOnly(implicit s => SQL(s"SHOW CREATE TABLE $table").map(_.string(2)).single().get)

  /** Rounds 1 and 2; images 1-5 (5 deleted on Commons, 4 with no monument id); round 1's
    * votes of files 1-3 by two jurors (file 3's of juror 11 moved aside, so it is in), file 5
    * only aside; round 2's of file 1; votes of a round 9 that doesn't exist and of image 99
    * that doesn't, aside, which are left out.
    */
  private def fixture(): Unit = DB.autoCommit { implicit s =>
    sql"INSERT INTO t_round (id, number, contest_id, rates) VALUES (1, 1, 1, 1), (2, 2, 1, 1)".update()
    sql"""INSERT INTO t_images (page_id, title, monument_id, deleted_at) VALUES
          (1, 'File:1.jpg', '80-361-0001', NULL), (2, 'File:2.jpg', '46-101-0002', NULL),
          (3, 'File:3.jpg', '', NULL), (4, 'File:4.jpg', NULL, NULL), (5, 'File:5.jpg', '07-101-0005', NOW(3))""".update()
    sql"""INSERT INTO t_vote (id, page_id, jury_id, round_id, rate, monument_id) VALUES
          (1, 1, 10, 1, 1, '80-361-0001'), (2, 1, 11, 1, -1, '80-361-0001'), (3, 2, 10, 1, 0, '46-101-0002'),
          (4, 3, 10, 1, 1, ''), (5, 4, 10, 2, 0, NULL), (6, 1, 10, 2, 1, '80-361-0001'),
          (7, 2, 10, 9, 1, '46-101-0002')""".update()
    sql"""INSERT INTO t_vote_aside (id, page_id, jury_id, round_id, rate, monument_id) VALUES
          (8, 3, 11, 1, 1, ''), (9, 5, 10, 1, 1, '07-101-0005'), (10, 5, 11, 1, 0, '07-101-0005'),
          (11, 99, 10, 1, 1, NULL)""".update()
  }

  private def rows = query(
    "SELECT round_id, page_id, status, added_how, added_by_job_id, added_at, monument_id, region FROM t_round_image ORDER BY 1, 2")(rs =>
    (rs.long(1), rs.long(2), rs.string(3), rs.string(4), rs.longOpt(5), rs.timestampOpt(6), rs.stringOpt(7), rs.stringOpt(8)))

  /** The pairs V84 must have, by GROUP BY over the votes: in vote 'in', else aside 'deleted'. */
  private def expected = query(
    """SELECT p.round_id, p.page_id, IF(SUM(p.t = 'v') > 0, 'in', 'deleted')
       FROM (SELECT round_id, page_id, 'v' t FROM t_vote UNION ALL SELECT round_id, page_id, 'd' FROM t_vote_aside) p
       JOIN t_round r ON r.id = p.round_id JOIN t_images i ON i.page_id = p.page_id
       GROUP BY p.round_id, p.page_id ORDER BY 1, 2""")(rs => (rs.long(1), rs.long(2), rs.string(3)))

  private def withCopies[A](body: => A): A = {
    dropCopies()
    try { createCopies(); body }
    finally dropCopies()
  }

  "V84" should {

    "run on the copies only" in {
      v84 must haveSize(3)
      forall(v84)(_ must not(beMatching(s"(?s).*\\b(FROM|JOIN|EXISTS|INTO|TABLE|REFERENCES|UPDATE)\\s+($tables)\\b.*")))
    }

    "add every round's files: those with votes 'in', those only aside 'deleted', as legacy rows" in withCopies {
      fixture()
      run(v84)
      (rows.map(r => (r._1, r._2, r._3)) === expected) and
        (rows === List(
          (1L, 1L, "in", "legacy", None, None, Some("80-361-0001"), Some("80")),
          (1L, 2L, "in", "legacy", None, None, Some("46-101-0002"), Some("46")),
          (1L, 3L, "in", "legacy", None, None, Some(""), None),
          (1L, 5L, "deleted", "legacy", None, None, Some("07-101-0005"), Some("07")),
          (2L, 1L, "in", "legacy", None, None, Some("80-361-0001"), Some("80")),
          (2L, 4L, "in", "legacy", None, None, None, None)))
    }

    "change nothing when run again, and add only what is missing" in withCopies {
      fixture()
      run(v84)
      val first = rows
      run(v84)
      val second = rows
      run(Seq("DELETE FROM t_round_image WHERE round_id = 1 AND page_id = 2",
        "UPDATE t_round_image SET added_how = 'repair' WHERE round_id = 2"))
      run(v84)
      (second === first) and
        (rows === first.map(r => if (r._1 == 2L) r.copy(_4 = "repair") else r))
    }

    "store the region the galleries compute, for long and odd monument ids" in withCopies {
      run(v84)
      run(Seq(
        "INSERT INTO t_round (id, number, contest_id, rates) VALUES (1, 1, 1, 1)",
        "INSERT INTO t_images (page_id, title) VALUES (1, 'a'), (2, 'b'), (3, 'c'), (4, 'd')",
        s"INSERT INTO t_round_image (round_id, page_id, monument_id) VALUES (1, 1, '${"x" * 190}'), (1, 2, 'UA-12'), " +
          "(1, 3, '-'), (1, 4, '1234-5')"))
      query(s"""SELECT ri.region <=> ${db.scalikejdbc.rewrite.ImageDbNew.regionOf("ri.monument_id")}, ri.region
                FROM t_round_image ri ORDER BY ri.page_id""")(rs => rs.boolean(1) -> rs.stringOpt(2)) ===
        List(true -> Some("xxx"), true -> Some("UA"), true -> None, true -> Some("123"))
    }

    "key and constrain the table" in withCopies {
      run(v84)
      val create = showCreate("t_round_image")
      (create must contain("PRIMARY KEY (`round_id`,`page_id`)")) and
        (create must contain("KEY `round_image_page_id_idx` (`page_id`)")) and
        (create must contain("FOREIGN KEY (`round_id`) REFERENCES `t_round` (`id`) ON DELETE CASCADE")) and
        (create must contain("FOREIGN KEY (`page_id`) REFERENCES `t_images` (`page_id`) ON DELETE CASCADE")) and
        (create must contain("ENGINE=InnoDB")) and
        // the statuses and origins the app writes, nothing else, byte for byte
        (forall(RoundImageJdbc.Status.All) { st =>
          run(Seq("INSERT INTO t_round (id, number, contest_id, rates) VALUES (1, 1, 1, 1) ON DUPLICATE KEY UPDATE id = id",
            "INSERT IGNORE INTO t_images (page_id, title) VALUES (1, 'a')",
            s"REPLACE INTO t_round_image (round_id, page_id, status) VALUES (1, 1, '$st')")) must not(throwA[Exception])
        }) and
        (forall(RoundImageJdbc.AddedHow.All) { how =>
          run(Seq(s"REPLACE INTO t_round_image (round_id, page_id, added_how) VALUES (1, 1, '$how')")) must not(throwA[Exception])
        }) and
        (run(Seq("REPLACE INTO t_round_image (round_id, page_id, status) VALUES (1, 1, 'IN')")) must throwA[Exception]) and
        (run(Seq("REPLACE INTO t_round_image (round_id, page_id, status) VALUES (1, 1, 'gone')")) must throwA[Exception]) and
        (run(Seq("REPLACE INTO t_round_image (round_id, page_id, added_how) VALUES (1, 1, 'other')")) must throwA[Exception]) and
        // a row of no round or no image is refused; a deleted round or image takes its rows
        (run(Seq("INSERT INTO t_round_image (round_id, page_id) VALUES (7, 1)")) must throwA[Exception]) and
        (run(Seq("INSERT INTO t_round_image (round_id, page_id) VALUES (1, 7)")) must throwA[Exception]) and
        {
          run(Seq("DELETE FROM t_images WHERE page_id = 1"))
          query("SELECT COUNT(*) FROM t_round_image")(_.int(1)) === List(0)
        }
    }

    "be undone by the plan's rollback SQL, and run again after it" in withCopies {
      fixture()
      val before = copies.tail.map(showCreate)
      run(v84)
      val migrated = rows
      run(rollback)
      (query("SHOW TABLES LIKE 't\\_round\\_image'")(_.string(1)) must beEmpty) and
        (copies.tail.map(showCreate) === before) and
        {
          run(v84)
          rows === migrated
        }
    }
  }

  "the shared database's round_image" should {
    "be V84's table" in {
      val create = showCreate("round_image")
      (create must contain("CONSTRAINT `round_image_round_id_fk` FOREIGN KEY (`round_id`) REFERENCES `round` (`id`) ON DELETE CASCADE")) and
        (create must contain("CONSTRAINT `round_image_page_id_fk` FOREIGN KEY (`page_id`) REFERENCES `images` (`page_id`) ON DELETE CASCADE")) and
        (create must contain("`region` varchar(3) GENERATED ALWAYS AS")) and
        // the copy compares and joins with the image's monument id: the same collation
        (DB.readOnly(implicit s => sql"""SELECT TABLE_NAME, CHARACTER_SET_NAME, COLLATION_NAME FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA = DATABASE() AND COLUMN_NAME = 'monument_id' AND TABLE_NAME IN ('images', 'round_image')
            ORDER BY TABLE_NAME""".map(rs => (rs.string(1), rs.string(2), rs.string(3))).list()) === List(
          ("images", "utf8mb4", "utf8mb4_general_ci"), ("round_image", "utf8mb4", "utf8mb4_general_ci")))
    }
  }
}
