package db

import _root_.scalikejdbc._
import db.scalikejdbc.{SharedTestDb, TestDb}
import org.intracer.wmua.Criterion
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}

import java.nio.file.{Files, Paths}

/** V83: the criteria catalogue (criterion) with today's criteria under their ids, each
  * criteria round's criteria (round_criterion) from its votes' rates, duplicate rates
  * archived, and criteria_rate's keys; run on copies of the tables (t_...) as they were before
  * it, as other specs use the shared ones meanwhile.
  */
class CriteriaCatalogueMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val tables = "criteria_rate_orphan|criteria_rate|round_criterion|criterion|round|vote_aside|vote"

  private val v83: Seq[String] = {
    val text = new String(
      Files.readAllBytes(Paths.get("conf/db/migration/default/V83__Criteria_catalogue.sql")), "UTF-8")
    text.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";\\s*\n").map(_.trim).filter(_.nonEmpty).toSeq
      .map(_.replaceAll(s"\\b(FROM|JOIN|EXISTS|INTO|TABLE|REFERENCES|UPDATE)(\\s+)($tables)\\b", "$1$2t_$3")
        .replaceAll("\\bCONSTRAINT\\s+(\\w+)", "CONSTRAINT t_$1")
        // step 0's information_schema checks
        .replace("TABLE_NAME = 'criteria_rate'", "TABLE_NAME = 't_criteria_rate'"))
  }

  /** The reverse of V83, as the plan's rollback SQL (C2 results) has it, on the copies. */
  private val rollback: Seq[String] = Seq(
    """ALTER TABLE t_criteria_rate
         DROP FOREIGN KEY t_criteria_rate_criterion_id_fk,
         DROP INDEX criteria_rate_criterion_id_idx,
         DROP INDEX criteria_rate_vote_criterion_uidx,
         ADD KEY criteria_rate_vote_id_idx (vote_id),
         CHANGE COLUMN criterion_id criteria INT NOT NULL,
         MODIFY vote_id INT NOT NULL,
         MODIFY id BIGINT UNSIGNED NOT NULL,
         DROP PRIMARY KEY,
         ADD UNIQUE KEY id (id)""",
    "DROP TABLE t_round_criterion",
    "DROP TABLE t_criterion",
    "ALTER TABLE t_criteria_rate_orphan DROP COLUMN reason"
  )

  private val copies = Seq("t_criteria_rate_orphan", "t_criteria_rate", "t_round_criterion", "t_criterion",
    "t_round", "t_vote", "t_vote_aside")

  private def dropCopies(): Unit = DB.autoCommit { implicit s =>
    sql"SET FOREIGN_KEY_CHECKS = 0".execute.apply()
    copies.foreach(t => SQL(s"DROP TABLE IF EXISTS $t").execute.apply())
    sql"SET FOREIGN_KEY_CHECKS = 1".execute.apply()
  }

  /** The tables as V83 finds them (after V82). */
  private def createCopies(): Unit = DB.autoCommit { implicit s =>
    sql"""CREATE TABLE t_criteria_rate (
            id BIGINT UNSIGNED NOT NULL, vote_id INT NOT NULL, criteria INT NOT NULL, rate INT NOT NULL,
            UNIQUE KEY id (id), KEY criteria_rate_vote_id_idx (vote_id)
          ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci""".execute.apply()
    sql"""CREATE TABLE t_criteria_rate_orphan (
            archive_id BIGINT UNSIGNED NOT NULL AUTO_INCREMENT, id BIGINT UNSIGNED NOT NULL,
            vote_id INT NOT NULL, criteria INT NOT NULL, rate INT NOT NULL,
            archived_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
            PRIMARY KEY (archive_id), KEY criteria_rate_orphan_vote_id_idx (vote_id)
          ) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci""".execute.apply()
    sql"CREATE TABLE t_round LIKE round".execute.apply()
    sql"CREATE TABLE t_vote LIKE vote".execute.apply()
    sql"CREATE TABLE t_vote_aside LIKE vote_aside".execute.apply()
  }

  private def run(statements: Seq[String]): Unit =
    DB.autoCommit { implicit s => statements.foreach(st => SQL(st).execute.apply()) }

  private def query[A](q: String)(f: WrappedResultSet => A): List[A] = DB.readOnly(implicit s => SQL(q).map(f).list())

  private def showCreate(table: String): String =
    DB.readOnly(implicit s => SQL(s"SHOW CREATE TABLE $table").map(_.string(2)).single().get)

  /** Rounds 1-4 (1, 2 and 4 with has_criteria = 1, 2 without rates, 3 without has_criteria),
    * their votes (vote 5 moved aside), and the rates: criterion 12 unknown to the catalogue;
    * vote 1 rates criterion 6 twice (4 and 1), vote 2 criterion 7 twice (2 and 2); the votes'
    * rates counted the duplicates, as the old code summed them.
    */
  private def fixture(): Unit = DB.autoCommit { implicit s =>
    sql"""INSERT INTO t_round (id, number, contest_id, rates, has_criteria) VALUES
          (1, 1, 1, 10, 1), (2, 2, 1, 10, 1), (3, 3, 1, 10, NULL), (4, 4, 1, 10, 1)""".update()
    sql"""INSERT INTO t_vote (id, page_id, jury_id, round_id, rate) VALUES
          (1, 1, 10, 1, 8), (2, 2, 10, 1, 4), (3, 1, 10, 3, 5), (4, 1, 10, 4, 1)""".update()
    sql"INSERT INTO t_vote_aside (id, page_id, jury_id, round_id, rate) VALUES (5, 2, 10, 4, 5)".update()
    sql"""INSERT INTO t_criteria_rate (id, vote_id, criteria, rate) VALUES
          (1, 1, 7, 3), (2, 1, 6, 4), (3, 2, 6, 0), (4, 2, 7, 2), (5, 2, 7, 2), (6, 1, 6, 1),
          (7, 3, 1, 5), (8, 4, 6, 1), (9, 5, 12, 5)""".update()
  }

  private def checkMigrated() = {
    // the catalogue: today's criteria under their ids, and 12 under its own
    (query("SELECT id, message_key FROM t_criterion WHERE id <= 9 ORDER BY id")(rs => rs.long(1) -> rs.string(2)) ===
      Criterion.Shipped) and
      (query("SELECT id, message_key, fallback_name FROM t_criterion WHERE id > 9")(rs =>
        (rs.long(1), rs.string(2), rs.string(3))) === List((12L, "criterion.legacy.12", "Criterion 12"))) and
      (query("SELECT fallback_name FROM t_criterion WHERE id = 7")(_.string(1)) === List("Корисність для Вікіпедії")) and
      // the criteria rounds' criteria, in id order; 6-9 for the one without rates, none for round 3
      (query("SELECT round_id, position, criterion_id FROM t_round_criterion ORDER BY round_id, position")(rs =>
        (rs.long(1), rs.int(2), rs.long(3))) === List(
        (1L, 1, 6L), (1L, 2, 7L),
        (2L, 1, 6L), (2L, 2, 7L), (2L, 3, 8L), (2L, 4, 9L),
        (4L, 1, 6L), (4L, 2, 12L))) and
      // the duplicates archived, the best of each kept, and their votes' rates recomputed
      (query("SELECT id, vote_id, criterion_id, rate FROM t_criteria_rate ORDER BY id")(rs =>
        (rs.long(1), rs.long(2), rs.long(3), rs.int(4))) === List(
        (1L, 1L, 7L, 3), (2L, 1L, 6L, 4), (3L, 2L, 6L, 0), (4L, 2L, 7L, 2), (7L, 3L, 1L, 5), (8L, 4L, 6L, 1),
        (9L, 5L, 12L, 5))) and
      (query("SELECT id, vote_id, criteria, rate, reason FROM t_criteria_rate_orphan ORDER BY id")(rs =>
        (rs.long(1), rs.long(2), rs.long(3), rs.int(4), rs.string(5))) ===
        List((5L, 2L, 7L, 2, "duplicate"), (6L, 1L, 6L, 1, "duplicate"))) and
      (query("SELECT id, rate FROM t_vote ORDER BY id")(rs => rs.long(1) -> rs.int(2)) ===
        List(1L -> 7, 2L -> 2, 3L -> 5, 4L -> 1)) and
      (query("SELECT id, rate FROM t_vote_aside")(rs => rs.long(1) -> rs.int(2)) === List(5L -> 5))
  }

  "V83" should {

    "run on the copies only" in {
      v83 must haveSize(18)
      forall(v83)(_ must not(beMatching(s"(?s).*\\b(FROM|JOIN|EXISTS|INTO|TABLE|REFERENCES|UPDATE)\\s+($tables)\\b.*")))
      forall(v83)(_ must not(contain("TABLE_NAME = 'criteria_rate'")))
    }

    "fill the catalogue and the rounds' criteria from the rates, archive duplicates, and key criteria_rate" in {
      dropCopies()
      try {
        createCopies()
        fixture()
        run(v83)
        checkMigrated()

        val created = showCreate("t_criteria_rate")
        created must contain("PRIMARY KEY (`id`)")
        created must contain("UNIQUE KEY `criteria_rate_vote_criterion_uidx` (`vote_id`,`criterion_id`)")
        created must contain("FOREIGN KEY (`criterion_id`) REFERENCES `t_criterion` (`id`)")
        created must contain("`vote_id` bigint(20) unsigned NOT NULL")
        // ids from the counter now, one rate per vote and criterion, of a criterion of the catalogue
        DB.autoCommit { implicit s =>
          sql"INSERT INTO t_criteria_rate (vote_id, criterion_id, rate) VALUES (3, 2, 1)".updateAndReturnGeneratedKey.apply()
        } === 10L
        DB.autoCommit(implicit s => sql"INSERT INTO t_criteria_rate (vote_id, criterion_id, rate) VALUES (3, 2, 4)".update.apply()) must
          throwA[java.sql.SQLException]
        DB.autoCommit(implicit s => sql"INSERT INTO t_criteria_rate (vote_id, criterion_id, rate) VALUES (3, 999, 4)".update.apply()) must
          throwA[java.sql.SQLException]
        // a round's criteria go with it
        DB.autoCommit(implicit s => sql"DELETE FROM t_round WHERE id = 2".update.apply())
        query("SELECT COUNT(*) FROM t_round_criterion WHERE round_id = 2")(_.int(1)) === List(0)
      } finally dropCopies()
    }

    "do the same when run again after stopping before its last statement" in {
      dropCopies()
      try {
        createCopies()
        fixture()
        run(v83.init)
        run(v83.init)
        run(Seq(v83.last))
        checkMigrated()
      } finally dropCopies()
    }

    "stop before any change at a criterion or vote id below 1, or at criteria_rate keys it doesn't know" in {
      def stopped(change: String, rows: Int, counts: String) = {
        dropCopies()
        try {
          createCopies()
          fixture()
          DB.autoCommit(implicit s => SQL(change).update.apply())
          val message = "V83 stopped, nothing changed: criteria_rate has " + counts
          (run(v83) must throwA[java.sql.SQLException](message = java.util.regex.Pattern.quote(message))) and
            (query("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 't_criterion'")(
              _.int(1)) === List(0)) and
            (query("SELECT COUNT(*) FROM t_criteria_rate")(_.int(1)) === List(rows))
        } finally dropCopies()
      }
      stopped("INSERT INTO t_criteria_rate (id, vote_id, criteria, rate) VALUES (20, 1, 0, 3)", 10,
        "1 criteria<=0, 0 vote_id<=0, PRIMARY 0, id key 1, vote_id key 1") and
        stopped("INSERT INTO t_criteria_rate (id, vote_id, criteria, rate) VALUES (20, 0, 6, 3)", 10,
          "0 criteria<=0, 1 vote_id<=0, PRIMARY 0, id key 1, vote_id key 1") and
        stopped("ALTER TABLE t_criteria_rate ADD PRIMARY KEY (id)", 9,
          "0 criteria<=0, 0 vote_id<=0, PRIMARY 1, id key 1, vote_id key 1") and
        stopped("ALTER TABLE t_criteria_rate DROP INDEX criteria_rate_vote_id_idx", 9,
          "0 criteria<=0, 0 vote_id<=0, PRIMARY 0, id key 1, vote_id key 0")
    }

    "be undone by the plan's rollback SQL" in {
      dropCopies()
      try {
        createCopies()
        val before = Seq("t_criteria_rate", "t_criteria_rate_orphan").map(showCreate)
        run(v83)
        run(rollback)
        Seq("t_criteria_rate", "t_criteria_rate_orphan").map(showCreate) === before
      } finally dropCopies()
    }

    "have left the migrated database with the catalogue and criteria_rate's keys" in {
      DB.readOnly(implicit s => sql"SELECT id, message_key FROM criterion WHERE id <= 9 ORDER BY id"
        .map(rs => rs.long(1) -> rs.string(2)).list()) === Criterion.Shipped
      showCreate("criteria_rate") must contain("PRIMARY KEY (`id`)")
      showCreate("round_criterion") must contain("REFERENCES `round` (`id`) ON DELETE CASCADE")
    }
  }
}
