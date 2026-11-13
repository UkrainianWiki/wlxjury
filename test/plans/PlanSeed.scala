package plans

import db.scalikejdbc.{ContestJuryJdbc, Round, RoundUser, User}
import plans.PlanDb.{execute, long, rows}

import java.sql.Connection

/** QueryPlanSpec's data (docs/plans/2026-11-13-query-plan-checks.md, §3.3–3.4): only **the
  * corner** and **filler**, on the real schema (every migration), in contests of their own.
  *
  * The corner is production's r759 shape, which made MariaDB 10.6 pick vote's round ∩ juror
  * index-merge intersection before V100: a juror (an account of its own) with votes in 4 past
  * rounds, and the only juror of a round of 20k files. The filler is other rounds with other
  * jurors, so the round and the juror are each a small share of the table.
  *
  * The seed is built the same way each time (fixed sizes, ids from a fresh database), so a
  * snapshot taken of it (QueryPlanTool snapshot) fits every run; [[key]] ties the two.
  */
object PlanSeed {

  val Images = 40000
  val PastRounds = 4
  val PastRoundFiles = 15000
  /** The corner round's files: page ids above `Images - CornerFiles`. */
  val CornerFiles = 20000
  val FillerJurors = 7
  val FillerRoundFiles = 40000

  /** The seed's size (decision 2): filler rounds of [[FillerRoundFiles]] votes each. 25 gives
    * ~1M filler rows (1.08M in all), 12 ~0.5M (0.56M). The smallest whose canary fires with real
    * statistics (the plan's §3.4 measurements, P1's in §3.5); adjust after the first CI run and
    * regenerate the snapshot (QueryPlanTool snapshot).
    */
  val FillerRounds: Int = sys.props.get("queryPlans.fillerRounds").map(_.toInt).getOrElse(25)

  sealed abstract class Mode(val name: String)
  /** Insert with every index in place. */
  case object IndexesBefore extends Mode("indexes-before")
  /** Insert with only the primary and the unique key (the foreign key's), then build the other
    * secondary indexes (a sorted build).
    */
  case object IndexesAfter extends Mode("indexes-after")

  val SeedMode: Mode = sys.props.get("queryPlans.seedMode") match {
    case Some(IndexesBefore.name) => IndexesBefore
    case _ => IndexesAfter
  }

  def voteRows(fillerRounds: Int = FillerRounds): Long =
    PastRounds.toLong * PastRoundFiles + CornerFiles + fillerRounds.toLong * FillerRoundFiles

  /** What a snapshot was taken of: a snapshot of another seed doesn't fit. */
  def key(fillerRounds: Int = FillerRounds): String =
    s"images=$Images corner=${PastRounds}x$PastRoundFiles+$CornerFiles filler=${fillerRounds}x$FillerRoundFiles/$FillerJurors"

  /** V100's four single-column indexes: the canary adds them back. */
  val CanaryIndexes: Seq[(String, String)] =
    Seq("vote_rate_idx" -> "rate", "vote_round_idx" -> "round_id", "vote_jury_id_idx" -> "jury_id", "vote_page_id_idx" -> "page_id")

  final case class Corner(contestId: Long, juror: User, round: Round, pastRounds: Seq[Long], fillerRounds: Seq[Long]) {
    def jurorId: Long = juror.getId
    def roundId: Long = round.getId
    /** A file of the corner round, in its middle (rebinding's page_id). */
    def pageId: Long = Images - CornerFiles / 2
    /** For rebinding: an IN list's page ids are the corner round's files in page order. A list
      * longer than the round's [[CornerFiles]] files wraps around and repeats them (the
      * application's lists are a page or a chunk, far shorter): still bound, fewer distinct values.
      */
    def ids: PlanCheck.Ids = PlanCheck.Ids(jurorId, roundId, pageId, contestId, k => Images - CornerFiles + 1 + (k % CornerFiles))
  }

  final case class Timings(rows: Long, insertSeconds: Double, indexSeconds: Double, analyzeSeconds: Double) {
    def seedSeconds: Double = insertSeconds + indexSeconds
  }

  private def seconds(t0: Long) = (System.nanoTime() - t0) / 1e9

  /** Creates the contests, accounts, rounds and votes; returns the corner and the timings. */
  def seed(c: Connection, mode: Mode = SeedMode, fillerRounds: Int = FillerRounds): (Corner, Timings) = {
    val contest = ContestJuryJdbc.create(None, "Query plans: corner", 2026, "Corner")
    ContestJuryJdbc.setImagesSource(contest.getId, Some("Category:Query plan corner"))
    val fillerContest = ContestJuryJdbc.create(None, "Query plans: filler", 2026, "Filler")

    val juror = User.create(User("Corner juror", "corner-juror@plans.test", None, Set("jury"), contestId = contest.id))
    val fillerJurors = (1 to FillerJurors).map(i =>
      User.create(User(s"Filler juror $i", s"filler-juror-$i@plans.test", None, Set("jury"), contestId = fillerContest.id)))

    def round(contestId: Long, number: Int, name: String, active: Boolean, jurors: Seq[User]): Round = {
      val r = Round.create(Round(None, number, Some(name), contestId, rates = Round.ratesById(5), active = active))
      r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
      r
    }
    val past = (1 to PastRounds).map(n => round(contest.getId, n, s"Past round $n", active = false, Seq(juror)))
    val corner = round(contest.getId, PastRounds + 1, "Corner round", active = true, Seq(juror))
    val filler = (0 until fillerRounds).map(k =>
      round(fillerContest.getId, k + 1, s"Filler round ${k + 1}", active = false, Seq(fillerJurors(k % FillerJurors))))

    execute(c, s"""INSERT INTO images (page_id, contest, title, width, height, size, mime, media_type, monument_id)
      SELECT seq, ${contest.getId}, CONCAT('File:Query plan ', seq, '.jpg'), 4000, 3000, 2000000, 'image/jpeg', 'image',
        CONCAT(LPAD(seq % 27 + 1, 2, '0'), '-101-', LPAD(seq, 5, '0'))
      FROM seq_1_to_$Images""")

    val definitions = secondaryIndexes(c)
    val keep = Set("vote_page_jury_round_uidx") // vote_page_id_fk's index: it can't be dropped
    if (mode == IndexesAfter)
      execute(c, "ALTER TABLE vote " + definitions.map(_._1).filterNot(keep).map("DROP INDEX " + _).mkString(", "))

    // a rated vote's time: one per page, in May 2026
    def votes(jurorId: Long, roundId: Long, pages: String, rate: String): Unit =
      execute(c, s"""INSERT INTO vote (jury_id, round_id, page_id, rate, monument_id, created_at, voted_at)
        SELECT $jurorId, $roundId, i.page_id, $rate, i.monument_id, '2026-05-01 00:00:00',
          IF($rate = 0, NULL, TIMESTAMP '2026-05-01 00:00:00' + INTERVAL i.page_id SECOND)
        FROM images i WHERE $pages ORDER BY i.page_id""")

    val t0 = System.nanoTime()
    past.foreach(r => votes(juror.getId, r.getId, s"i.page_id <= $PastRoundFiles", "i.page_id % 6"))
    // the corner round: one file in ten rated, so its tabs and orders have both
    votes(juror.getId, corner.getId, s"i.page_id > ${Images - CornerFiles}", "IF(i.page_id % 10 = 0, i.page_id % 5 + 1, 0)")
    filler.zipWithIndex.foreach { case (r, k) =>
      votes(fillerJurors(k % FillerJurors).getId, r.getId, s"i.page_id <= $FillerRoundFiles", "i.page_id % 3")
    }
    // comments on one corner file in a hundred, in the corner's contest (the gallery's comment
    // toggle and badges, stack/128)
    execute(c, s"""INSERT INTO comment (user_id, username, round_id, contest_id, room, body, created_at)
      SELECT ${juror.getId}, 'Corner juror', ${corner.getId}, ${contest.getId}, seq, 'A comment', '2026-05-01T00:00:00+02:00'
      FROM seq_${Images - CornerFiles + 100}_to_${Images}_step_100""")
    val inserted = seconds(t0)

    val t1 = System.nanoTime()
    if (mode == IndexesAfter) {
      execute(c, "ALTER TABLE vote " + definitions.filterNot(d => keep(d._1)).map("ADD " + _._2).mkString(", "))
      val rebuilt = secondaryIndexes(c)
      require(rebuilt == definitions, s"vote's indexes after the seed differ from the migrated ones: $rebuilt vs $definitions")
    }
    val indexed = seconds(t1)

    val t2 = System.nanoTime()
    analyze(c)
    val analyzed = seconds(t2)

    val rowsNow = long(c, "SELECT COUNT(*) FROM vote")
    require(rowsNow == voteRows(fillerRounds), s"seeded $rowsNow vote rows, expected ${voteRows(fillerRounds)}")
    val jurorWithRole = User.findById(juror.getId).get
    (Corner(contest.getId, jurorWithRole.copy(roles = Set("jury"), contestId = contest.id), Round.findById(corner.getId).get,
      past.map(_.getId), filler.map(_.getId)), Timings(rowsNow, inserted, indexed, analyzed))
  }

  def analyze(c: Connection): Unit = rows(c, "ANALYZE TABLE vote")(_ => ())

  /** vote's secondary indexes as `INDEX name (columns)` clauses, by name, from the server. */
  def secondaryIndexes(c: Connection): Seq[(String, String)] =
    rows(c, "SHOW INDEX FROM vote") { rs =>
      (rs.getString("Key_name"), rs.getInt("Non_unique") == 0, rs.getString("Column_name"),
        Option(rs.getString("Sub_part")), rs.getString("Collation"))
    }.filterNot(_._1 == "PRIMARY").foldLeft(Vector.empty[(String, Vector[(Boolean, String, Option[String], String)])]) {
      // SHOW INDEX lists the keys in the table's order, each key's columns in its order
      case (acc, (name, unique, col, sub, collation)) =>
        acc.lastOption match {
          case Some((`name`, cols)) => acc.init :+ (name -> (cols :+ ((unique, col, sub, collation))))
          case _ => acc :+ (name -> Vector((unique, col, sub, collation)))
        }
    }.map { case (name, cols) =>
      val columns = cols.map { case (_, col, sub, collation) =>
        s"`$col`${sub.fold("")(s => s"($s)")}${if (collation == "D") " DESC" else ""}"
      }
      name -> s"${if (cols.head._1) "UNIQUE " else ""}INDEX `$name` (${columns.mkString(", ")})"
    }

  /** vote's index names, PRIMARY included. */
  def voteIndexes(c: Connection): Set[String] =
    rows(c, "SELECT DISTINCT INDEX_NAME FROM information_schema.STATISTICS WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'")(
      _.getString(1)).toSet

  /** The canary: V100's four indexes back (as V100's undo builds them), those not there already. */
  def addCanaryIndexes(c: Connection): Unit =
    execute(c, "ALTER TABLE vote " + CanaryIndexes.map { case (i, col) => s"ADD INDEX IF NOT EXISTS $i ($col)" }.mkString(", ") +
      ", ALGORITHM = INPLACE, LOCK = NONE")

  /** V100 itself, which drops them again. */
  def runV100(c: Connection): Unit = { db.MigrationScript.run(c, "V100__Vote_drop_single_column_indexes.sql"); () }
}
