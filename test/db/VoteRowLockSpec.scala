package db

import org.specs2.mutable.Specification
import security.SourceScan

/** docs/db/practices.md 7.3 (h4 of docs/plans/2026-11-03-write-path-simplification.md): a
  * single vote row is locked or written by its primary key. A locking read, `UPDATE` or
  * `DELETE` of `vote` or `vote_aside` that finds the row by the unique secondary key
  * (`page_id = ? AND jury_id = ?` or `jury_id IN (…)`), or a one-row upsert through that key
  * (`INSERT … VALUES … ON DUPLICATE KEY`, `INSERT IGNORE`, `REPLACE`), locks the secondary
  * entry and then the clustered row, the reverse of a delete or move that found it by
  * `round_id` or `page_id`: the one-row deadlock of break 10. Read the id with a plain read,
  * then `… WHERE id = ? FOR UPDATE` (`VoteJdbc.rateIn`).
  *
  * A tripwire for literal SQL of the pre-h4 shape, not full enforcement: it reads the SQL
  * literals (`sql"…"`, `sqls"…"`, `SQL("…")`) in `app/`; a table interpolated as `$table`, a
  * fragment built from parts, or a CRUDMapper/QueryDSL write isn't read.
  */
class VoteRowLockSpec extends Specification {

  /** File, with why its statement may lock by the unique key. */
  private val Allowed: Map[String, String] = Map.empty

  private def sources = SourceScan.files("app", ".scala")

  "a vote row" should {
    "be locked and written by its id, not by (page, juror)" in {
      val hits = for {
        source <- sources
        statement <- VoteRowLockSpec.byUniqueKey(source.text)
        if !Allowed.contains(source.path)
      } yield s"${source.path}: ${statement.replaceAll("\\s+", " ").take(160)}"
      hits must beEmpty
    }

    "be seen by the scan where it is locked by id (VoteJdbc.rateIn), so the check isn't vacuous" in {
      val voteJdbc = sources.filter(_.path.endsWith("app/db/scalikejdbc/VoteJdbc.scala"))
      voteJdbc must haveSize(1)
      VoteRowLockSpec.lockingOrWriting(voteJdbc.head.text).map(_.replaceAll("\\s+", " "))
        .exists(s => s.contains("FROM vote WHERE id = $id") && s.contains("FOR UPDATE")) must beTrue
    }
  }

  "the scan" should {
    "flag locking reads, writes and upserts of vote and vote_aside by page and juror only" in {
      val code =
        """val a = sql"UPDATE vote SET rate = $rate WHERE page_id = $pageId AND jury_id = $juryId AND round_id = $roundId"
          |val b = sql"SELECT id FROM vote WHERE page_id = $pageId AND jury_id = $juryId AND round_id = $roundId"
          |val c = sql"SELECT id FROM vote WHERE id = $id AND jury_id = $juryId AND round_id = $roundId FOR UPDATE"
          |val d = sql\"\"\"SELECT v.id FROM vote_aside v
          |            WHERE v.page_id = $p AND v.jury_id = $j
          |            LOCK IN SHARE MODE\"\"\"
          |val e = sql"DELETE FROM vote_orphan WHERE page_id = $p AND jury_id = $j"
          |val f = sqls"DELETE FROM vote WHERE jury_id = $j AND page_id IN ($ps)"
          |val g = SQL("DELETE FROM vote WHERE page_id = ? AND jury_id = ?")
          |val h = sql"UPDATE vote s SET s.rate = $r WHERE s.id = $id AND s.page_id = $p AND s.jury_id = $j"
          |val i = sql"DELETE FROM vote WHERE page_id = $p AND jury_id IN ($js)"
          |val j = sql\"\"\"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($p, $j, $r, $rate)
          |            ON DUPLICATE KEY UPDATE rate = VALUES(rate)\"\"\"
          |val k = sql"REPLACE INTO vote_aside (page_id, jury_id, round_id, rate) VALUES ($p, $j, $r, 0)"
          |val l = sql"INSERT IGNORE INTO vote (page_id, jury_id, round_id) VALUES (?, ?, ?)"
          |val m = sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (?, ?, ?, ?)"
          |val n = sql"REPLACE INTO vote_aside SELECT s.* FROM vote s WHERE s.round_id = $r"
          |val o = sql"UPDATE vote v JOIN round r ON r.id = $roundId SET v.rate = $rate WHERE v.page_id = $p AND v.jury_id = $j"
          |val q = sql"UPDATE vote AS v JOIN round r ON r.id = v.round_id SET v.rate = $rate WHERE v.id = $id AND v.page_id = $p AND v.jury_id = $j"
          |""".stripMargin.replace("\\\"", "\"")
      VoteRowLockSpec.byUniqueKey(code).map(_.trim.take(20)) === Seq(
        "UPDATE vote SET rate",
        "SELECT v.id FROM vot",
        "DELETE FROM vote WHE",
        "DELETE FROM vote WHE",
        "INSERT INTO vote (pa",
        "REPLACE INTO vote_as",
        "INSERT IGNORE INTO v",
        "UPDATE vote v JOIN r")
    }
  }
}

object VoteRowLockSpec {

  private val Literal = "(?s)(?:sqls?|SQL\\()\"\"\"(.*?)\"\"\"|(?:sqls?|SQL\\()\"((?:[^\"\\\\\\n]|\\\\.)*)\"".r
  private val VoteTable = """(?i)\b(?:FROM|UPDATE|JOIN|INTO)\s+(?:vote|vote_aside)\b""".r
  private val LocksOrWrites = """(?i)\bFOR\s+UPDATE\b|\bLOCK\s+IN\s+SHARE\s+MODE\b|^\s*(?:UPDATE|DELETE)\b""".r
  private val Bound = """\s*=\s*(?:\$|\?)"""
  private val PageId = ("""(?i)(?<![\w])(?:\w+\.)?page_id""" + Bound).r
  private val JuryId = ("""(?i)(?<![\w])(?:\w+\.)?jury_id(?:""" + Bound + """|\s+IN\s*\()""").r
  // the vote table and its alias (`FROM vote v`, `UPDATE vote_aside AS d`), if it has one
  private val VoteAlias = ("""(?i)\b(?:FROM|UPDATE|JOIN|INTO)\s+(vote|vote_aside)\b(?:\s+(?:AS\s+)?""" +
    """(?!(?:SET|WHERE|JOIN|ON|LEFT|RIGHT|INNER|CROSS|STRAIGHT_JOIN|USING|FOR|LOCK|ORDER|GROUP|LIMIT|SELECT|VALUES)\b)(\w+))?""").r

  /** An id predicate on the vote row itself: `id`, or the vote table's or its alias's `id`, not a
    * joined table's (`r.id = $roundId` selects a round, not the vote).
    */
  private def byVoteId(sql: String): Boolean = {
    val names = VoteAlias.findAllMatchIn(sql).flatMap(m => Seq(m.group(1)) ++ Option(m.group(2))).toSet
    val qualified = names.map(java.util.regex.Pattern.quote).mkString("|")
    ("""(?i)(?<![\w.])(?:(?:""" + qualified + """)\.)?id""" + Bound).r.findFirstIn(sql).isDefined
  }
  // a one-row upsert: INSERT … ON DUPLICATE KEY / INSERT IGNORE / REPLACE with VALUES, naming
  // the key's columns (an INSERT … SELECT is a structural writer's, under its round lock)
  private val Upsert = """(?i)^\s*(?:INSERT\s+IGNORE\s+INTO|INSERT\s+INTO|REPLACE\s+INTO)\s+(?:vote|vote_aside)\b""".r
  private val UpsertKind = """(?i)\bON\s+DUPLICATE\s+KEY\b|^\s*INSERT\s+IGNORE\b|^\s*REPLACE\b""".r
  private val Values = """(?i)\bVALUES\s*\(""".r
  private val Select = """(?i)\bSELECT\b""".r
  private val KeyColumns = """(?i)\bpage_id\b[\s\S]*\bjury_id\b|\bjury_id\b[\s\S]*\bpage_id\b""".r

  private def literals(code: String): Seq[String] =
    Literal.findAllMatchIn(code).map(m => Option(m.group(1)).getOrElse(m.group(2))).toSeq

  /** The SQL literals in `code` that lock or write `vote` or `vote_aside` rows. */
  def lockingOrWriting(code: String): Seq[String] =
    literals(code).filter(sql => VoteTable.findFirstIn(sql).isDefined && LocksOrWrites.findFirstIn(sql).isDefined)

  private def upsertByKey(sql: String): Boolean =
    Upsert.findFirstIn(sql).isDefined && UpsertKind.findFirstIn(sql).isDefined && Values.findFirstIn(sql).isDefined &&
      Select.findFirstIn(sql).isEmpty && KeyColumns.findFirstIn(sql).isDefined

  /** The SQL literals in `code` that lock, write or upsert a vote row by (page_id, jury_id)
    * without its id.
    */
  def byUniqueKey(code: String): Seq[String] =
    literals(code).filter { sql =>
      upsertByKey(sql) || (
        VoteTable.findFirstIn(sql).isDefined && LocksOrWrites.findFirstIn(sql).isDefined &&
          PageId.findFirstIn(sql).isDefined && JuryId.findFirstIn(sql).isDefined && !byVoteId(sql))
    }
}
