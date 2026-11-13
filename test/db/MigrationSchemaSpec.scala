package db

import org.specs2.mutable.Specification

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Files

/** docs/db/practices.md 2.1 and 2.7, for the migrations from V83 on (V82, P0, renamed the old
  * names; V83 is the first written to the rule):
  *  - an index or constraint a migration names starts with its table's name and ends by kind,
  *    `_idx`, `_uidx` (unique), `_fk` (foreign key) or `_chk` (check);
  *  - every unique key, foreign key and check has a name (no `UNIQUE (…)`, `col … UNIQUE`,
  *    `FOREIGN KEY … REFERENCES` or `CHECK (…)` without `CONSTRAINT name` or `KEY name`), so
  *    that a later migration and the code can refer to it on every server;
  *  - a table it creates (not `… LIKE`, not `TEMPORARY`) states the InnoDB engine and a
  *    `utf8mb4` default charset in its table options.
  * It checks the table prefix and the suffix, not the `<cols>` part or singular table names.
  * A name only dropped (`DROP CONSTRAINT legacy_name`) isn't checked. A source scan: no
  * database needed. Comments (`--`, `#`, `/* */`) are skipped; string literals are read (V85's
  * dynamic DDL is in them).
  */
class MigrationSchemaSpec extends Specification {

  import MigrationSchemaSpec._

  /** (migration file, the name or `unnamed`) of a reviewed exception, with why. */
  private val Allowed: Map[(String, String), String] = Map.empty

  private def migrations: Seq[File] =
    new File("conf/db/migration/default").listFiles().toSeq
      .filter(f => MigrationNamingSpec.version(f.getName).exists(_ >= From))
      .sortBy(_.getName)

  private def text(f: File) = new String(Files.readAllBytes(f.toPath), UTF_8)

  private def found: Seq[(String, Problem)] = migrations.flatMap(f => problems(text(f)).map(f.getName -> _))

  "every migration from V83" should {
    "name its keys and constraints <table>_…_idx, _uidx, _fk or _chk, and create InnoDB utf8mb4 tables" in {
      migrations must not(beEmpty)
      found.filterNot { case (file, p) => Allowed.contains(file -> p.subject) }
        .map { case (file, p) => s"$file: $p" } must beEmpty
    }

    "allow no exception that no longer occurs" in {
      (Allowed.keySet -- found.map { case (file, p) => file -> p.subject }.toSet) must beEmpty
    }

    "be read: V83, V84 and V87's names are found" in {
      val read = migrations.filter(f => Seq("V83__", "V84__", "V87__").exists(f.getName.startsWith))
        .flatMap(f => names(text(f))).map(_.name).toSet
      read must containAllOf(Seq("criterion_message_key_uidx", "round_criterion_round_id_fk",
        "criteria_rate_criterion_id_idx", "round_image_page_id_idx", "round_image_status_chk",
        "user_identity_user_fk", "account_token_purpose_chk"))
    }
  }

  "the lint" should {
    "flag names without their table or suffix" in {
      val sql =
        """-- KEY commented_out (x) is not read
          |# KEY hash_comment (x) neither
          |/* KEY block_comment (x) */
          |CREATE TABLE IF NOT EXISTS thing (
          |  id    BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
          |  key   VARCHAR(100) NOT NULL,
          |  owner_id BIGINT UNSIGNED NOT NULL,
          |  PRIMARY KEY (id),
          |  UNIQUE KEY thing_key_uidx (`key`),
          |  KEY idx_thing_owner (owner_id),
          |  UNIQUE KEY thing_owner_idx (owner_id, `key`),
          |  CONSTRAINT thing_owner_id_fk FOREIGN KEY (owner_id) REFERENCES users (id),
          |  CONSTRAINT fk_thing_owner FOREIGN KEY (owner_id) REFERENCES users (id),
          |  CONSTRAINT thing_key_chk
          |    CHECK (BINARY `key` <> '' AND `key` <> 'it''s')
          |) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_general_ci;
          |ALTER TABLE vote ADD INDEX IF NOT EXISTS vote_round_x_idx (round_id), ADD KEY round_x_idx (round_id),
          |  RENAME INDEX old_name TO vote_round_y_idx, RENAME INDEX older TO vote_round_y_key,
          |  ADD CONSTRAINT vote_x_fk FOREIGN KEY IF NOT EXISTS (page_id) REFERENCES images (page_id),
          |  DROP CONSTRAINT chk_legacy_name, ALGORITHM = INPLACE, LOCK = NONE;
          |CREATE UNIQUE INDEX thing_a_uidx ON thing (id);
          |CREATE INDEX thing_b ON thing (id);
          |INSERT INTO thing (id) VALUES (1) ON DUPLICATE KEY UPDATE id = id;""".stripMargin
      problems(sql).map(_.toString) === Seq(
        "thing: idx_thing_owner (index)",
        "thing: thing_owner_idx (unique index)",
        "thing: fk_thing_owner (foreign key)",
        "vote: round_x_idx (index)",
        "vote: vote_round_y_key (renamed index)",
        "thing: thing_b (index)")
    }

    "flag unnamed unique keys, foreign keys and checks" in {
      val sql =
        """CREATE TABLE t (
          |  id INT NOT NULL,
          |  email VARCHAR(190) NOT NULL UNIQUE,
          |  code VARCHAR(10) UNIQUE KEY,
          |  n INT CHECK (n > 0),
          |  PRIMARY KEY (id),
          |  UNIQUE (email, code),
          |  UNIQUE KEY (code),
          |  FOREIGN KEY (id) REFERENCES users (id),
          |  CHECK (n < 10),
          |  CONSTRAINT t_n_chk CHECK (n <> 5),
          |  CONSTRAINT t_email_code_uidx UNIQUE (email, code)
          |) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
          |ALTER TABLE t ADD UNIQUE (n), ADD FOREIGN KEY (n) REFERENCES users (id), ADD CHECK (n <> 7);""".stripMargin
      problems(sql).map(_.toString) === Seq(
        "t: unnamed (unique key)",
        "t: unnamed (unique key)",
        "t: unnamed (check)",
        "t: unnamed (unique key)",
        "t: unnamed (unique key)",
        "t: unnamed (foreign key)",
        "t: unnamed (check)",
        "t: unnamed (unique key)",
        "t: unnamed (foreign key)",
        "t: unnamed (check)")
    }

    "read table options in their forms, past strings and comments, and skip temporary and LIKE tables" in {
      val sql =
        """CREATE TABLE a (id INT NOT NULL, PRIMARY KEY (id)) ENGINE InnoDB DEFAULT CHARACTER SET utf8mb4;
          |CREATE OR REPLACE TABLE b (id INT NOT NULL COMMENT 'one; two (three)', PRIMARY KEY (id))
          |  ENGINE=InnoDB CHARSET utf8mb4 COMMENT = 'x; y';
          |CREATE TABLE c (id INT NOT NULL, name VARCHAR(9) CHARACTER SET utf8mb4, PRIMARY KEY (id)) ENGINE = MyISAM;
          |CREATE OR REPLACE TABLE d (id INT NOT NULL, KEY bad_name (id)) -- ENGINE = InnoDB CHARSET = utf8mb4
          |;
          |CREATE TEMPORARY TABLE tmp_e (id INT NOT NULL, KEY tmp_e_id_idx (id), KEY wrong (id));
          |CREATE TABLE f LIKE a;""".stripMargin
      problems(sql).map(_.toString) === Seq(
        "c: InnoDB engine (table options)",
        "c: utf8mb4 charset (table options)",
        "d: bad_name (index)",
        "d: InnoDB engine (table options)",
        "d: utf8mb4 charset (table options)",
        "tmp_e: wrong (index)")
    }

    "read a qualified table name and ALTER TABLE IF EXISTS" in {
      val sql =
        """CREATE TABLE db.t (id INT NOT NULL, KEY t_id_idx (id), KEY wrong (id)) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4;
          |ALTER TABLE IF EXISTS t ADD CONSTRAINT t_id_chk CHECK (id > 0), ADD KEY t_x (id);
          |ALTER TABLE `db`.`u` ADD INDEX u_id_idx (id);
          |CREATE INDEX u_x_idx ON db.u (id);""".stripMargin
      problems(sql).map(_.toString) === Seq("t: wrong (index)", "t: t_x (index)")
    }

    "not read a comment's or a default's text as DDL" in {
      val sql =
        """CREATE TABLE t (id INT NOT NULL, kind VARCHAR(9) NOT NULL DEFAULT 'UNIQUE', PRIMARY KEY (id))
          |  ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='UNIQUE';
          |CREATE TABLE m (id INT NOT NULL COMMENT 'CHECK (x)', PRIMARY KEY (id)) ENGINE=MyISAM
          |  COMMENT='ENGINE=InnoDB DEFAULT CHARSET=utf8mb4';""".stripMargin
      problems(sql).map(_.toString) === Seq("m: InnoDB engine (table options)", "m: utf8mb4 charset (table options)")
    }
  }
}

object MigrationSchemaSpec {

  /** The first migration written to the naming rule (P0's V82 renamed the old names). */
  val From = 83

  final case class Named(table: String, name: String, kind: String)

  /** What's wrong: `subject` is the name, `unnamed`, or the missing table option. */
  final case class Problem(table: String, subject: String, what: String) {
    override def toString = s"$table: $subject ($what)"
  }

  private val Suffix = Map(
    "index" -> Seq("_idx"),
    "unique index" -> Seq("_uidx"),
    "foreign key" -> Seq("_fk"),
    "check" -> Seq("_chk"),
    "unique constraint" -> Seq("_uidx"),
    "renamed index" -> Seq("_idx", "_uidx"))

  private val Id = """`?(\w+)`?"""
  // a table, perhaps qualified by its database (db.t): the last name is the table's
  private val TableId = """(?:`?\w+`?\s*\.\s*)?""" + Id
  // groups: 1 CREATE (else ALTER), 2 TEMPORARY, 3 the table
  private val TableContext = ("""(?i)\b(?:(CREATE)\s+(?:OR\s+REPLACE\s+)?(TEMPORARY\s+)?TABLE(?:\s+IF\s+NOT\s+EXISTS)?|""" +
    """ALTER\s+(?:ONLINE\s+)?(?:IGNORE\s+)?TABLE(?:\s+IF\s+EXISTS)?)\s+""" + TableId).r
  // `KEY name (col`, `UNIQUE INDEX IF NOT EXISTS name (col`: a column list starts with a
  // letter, so a column named `key VARCHAR(100)` isn't one, nor `PRIMARY KEY (id)` (no name),
  // nor `CREATE INDEX name ON t (…)` (ON before the list: CreateIndex)
  private val KeyDef = ("""(?i)\b(UNIQUE\s+)?(?:KEY|INDEX)\s+(?:IF\s+NOT\s+EXISTS\s+)?""" + Id + """\s*\(\s*`?[a-z_]""").r
  private val CreateIndex = ("""(?i)\bCREATE\s+(?:OR\s+REPLACE\s+)?(UNIQUE\s+)?INDEX\s+(?:IF\s+NOT\s+EXISTS\s+)?""" +
    Id + """\s+ON\s+""" + TableId).r
  private val Constraint = ("""(?i)\bCONSTRAINT\s+""" + Id + """\s+(FOREIGN\s+KEY|CHECK|UNIQUE|PRIMARY)""").r
  private val Rename = ("""(?i)\bRENAME\s+(?:INDEX|KEY)\s+`?\w+`?\s+TO\s+""" + Id).r

  private val Unique = """(?i)\bUNIQUE\b""".r
  private val ForeignKey = """(?i)\bFOREIGN\s+KEY\b""".r
  private val Check = """(?i)\bCHECK\s*\(""".r
  private val NamedBefore = """(?i)[\s\S]*\bCONSTRAINT\s+`?\w+`?\s+""".r
  private val CreateBefore = """(?i)[\s\S]*\bCREATE\s+(?:OR\s+REPLACE\s+)?""".r
  private val NamedKeyAfter = """(?i)\s+(?:KEY|INDEX)\s+(?:IF\s+NOT\s+EXISTS\s+)?`?\w+`?\s*\([\s\S]*""".r

  /** Where the string literal starting at `i` (a quote) ends, past its closing quote. */
  private def literalEnd(s: String, i: Int): Int = {
    val q = s(i)
    var j = i + 1
    while (j < s.length && s(j) != q) j += (if (s(j) == '\\') 2 else 1)
    // a doubled quote ('') continues the literal
    if (j + 1 < s.length && s(j + 1) == q) literalEnd(s, j + 1) else math.min(j + 1, s.length)
  }

  /** The SQL with `--` (followed by a blank), `#` and `/* */` comments outside string
    * literals blanked (offsets kept).
    */
  def stripComments(sql: String): String = {
    val out = new StringBuilder(sql)
    def blank(from: Int, to: Int): Unit = (from until to).foreach(k => if (out(k) != '\n') out(k) = ' ')
    var i = 0
    while (i < sql.length) {
      val c = sql(i)
      if (c == '\'' || c == '"' || c == '`') i = literalEnd(sql, i)
      else if (c == '#' || (sql.startsWith("--", i) && (i + 2 == sql.length || sql(i + 2).isWhitespace))) {
        val end = sql.indexOf('\n', i) match { case -1 => sql.length; case e => e }
        blank(i, end); i = end
      } else if (sql.startsWith("/*", i)) {
        val end = sql.indexOf("*/", i + 2) match { case -1 => sql.length; case e => e + 2 }
        blank(i, end); i = end
      } else i += 1
    }
    out.toString
  }

  /** A string that is a value (`COMMENT 'x'`, `COMMENT = 'x'`, `DEFAULT 'x'`), not DDL. */
  private val ValueLiteral = """(?i)\b(?:COMMENT|DEFAULT)\s*=?\s*(?=['"])""".r

  /** The SQL with string values blanked (offsets kept): a table's or column's comment or
    * default holds text, which the lint must not read as DDL (`COMMENT='UNIQUE'`,
    * `COMMENT='ENGINE=InnoDB'`). Other literals stay: V85's dynamic DDL is in strings.
    */
  def blankValues(sql: String): String = {
    val out = new StringBuilder(sql)
    ValueLiteral.findAllMatchIn(sql).foreach { m =>
      (m.end until literalEnd(sql, m.end)).foreach(k => if (out(k) != '\n') out(k) = ' ')
    }
    out.toString
  }

  /** The end of the statement from `from`: its `;` outside string literals, or the end. */
  private def statementEnd(s: String, from: Int): Int = {
    var i = from
    while (i < s.length && s(i) != ';') i = if (s(i) == '\'' || s(i) == '"') literalEnd(s, i) else i + 1
    i
  }

  /** The closing parenthesis of the one opening at `open`, outside literals; -1 if none. */
  private def closing(s: String, open: Int, end: Int): Int = {
    var depth = 0
    var i = open
    var found = -1
    while (i < end && found < 0) {
      s(i) match {
        case '\'' | '"' => i = literalEnd(s, i) - 1
        case '(' => depth += 1
        case ')' => depth -= 1; if (depth == 0) found = i
        case _ =>
      }
      i += 1
    }
    found
  }

  private def tables(s: String) = TableContext.findAllMatchIn(s).map(m => m.start -> m.group(3)).toList

  private def tableAt(ts: List[(Int, String)], pos: Int) = ts.takeWhile(_._1 < pos).lastOption.map(_._2).getOrElse("?")

  /** The indexes and constraints the SQL names, each with the table of the `CREATE TABLE` or
    * `ALTER TABLE` it is in (dynamic SQL in string literals included: V85's `EXECUTE IMMEDIATE`).
    */
  def names(sql: String): Seq[Named] = named(blankValues(stripComments(sql))).map(_._2)

  private def named(s: String): Seq[(Int, Named)] = {
    val ts = tables(s)
    val keys = KeyDef.findAllMatchIn(s).map(m =>
      m.start -> Named(tableAt(ts, m.start), m.group(2), if (m.group(1) != null) "unique index" else "index"))
    val created = CreateIndex.findAllMatchIn(s).map(m =>
      m.start -> Named(m.group(3), m.group(2), if (m.group(1) != null) "unique index" else "index"))
    val constraints = Constraint.findAllMatchIn(s).flatMap { m =>
      val kind = m.group(2).toUpperCase.split("\\s+").head match {
        case "FOREIGN" => Some("foreign key")
        case "CHECK" => Some("check")
        case "UNIQUE" => Some("unique constraint")
        case _ => None
      }
      kind.map(k => m.start -> Named(tableAt(ts, m.start), m.group(1), k))
    }
    val renamed = Rename.findAllMatchIn(s).map(m => m.start -> Named(tableAt(ts, m.start), m.group(1), "renamed index"))
    (keys ++ created ++ constraints ++ renamed).toSeq
  }

  private def unnamed(s: String): Seq[(Int, Problem)] = {
    val ts = tables(s)
    def before(pos: Int) = s.substring(math.max(0, pos - 200), pos)
    def after(pos: Int) = s.substring(pos, math.min(s.length, pos + 200))
    def problem(kind: String)(m: scala.util.matching.Regex.Match) = m.start -> Problem(tableAt(ts, m.start), "unnamed", kind)
    val uniques = Unique.findAllMatchIn(s).filterNot { m =>
      NamedBefore.matches(before(m.start)) || CreateBefore.matches(before(m.start)) || NamedKeyAfter.matches(after(m.end))
    }.map(problem("unique key"))
    val foreign = ForeignKey.findAllMatchIn(s).filterNot(m => NamedBefore.matches(before(m.start))).map(problem("foreign key"))
    val checks = Check.findAllMatchIn(s).filterNot(m => NamedBefore.matches(before(m.start))).map(problem("check"))
    (uniques ++ foreign ++ checks).toSeq
  }

  private def tableOptions(s: String): Seq[(Int, Problem)] =
    TableContext.findAllMatchIn(s).filter(m => m.group(1) != null && m.group(2) == null).toSeq.flatMap { m =>
      val end = statementEnd(s, m.end)
      val rest = s.substring(m.end, end)
      if ("""(?i)^\s+LIKE\b""".r.findFirstIn(rest).isDefined) Nil
      else {
        val open = s.indexOf('(', m.end)
        val body = if (open >= 0 && open < end) closing(s, open, end) else -1
        val options = if (body >= 0) s.substring(body + 1, end) else rest
        Seq(
          """(?i)\bENGINE\s*=?\s*InnoDB\b""" -> "InnoDB engine",
          """(?i)\b(?:CHARSET|CHARACTER\s+SET)\s*=?\s*utf8mb4\b""" -> "utf8mb4 charset"
        ).collect { case (pattern, what) if pattern.r.findFirstIn(options).isEmpty =>
          end -> Problem(m.group(3), what, "table options")
        }
      }
    }

  /** Every problem, in the order they appear (a table's options at the end of its statement). */
  def problems(sql: String): Seq[Problem] = {
    val s = blankValues(stripComments(sql))
    val badNames = named(s).filterNot { case (_, n) =>
      val name = n.name.toLowerCase
      name.startsWith(n.table.toLowerCase + "_") && Suffix(n.kind).exists(name.endsWith)
    }.map { case (pos, n) => pos -> Problem(n.table, n.name, n.kind) }
    (badNames ++ unnamed(s) ++ tableOptions(s)).sortBy(_._1).map(_._2)
  }
}
