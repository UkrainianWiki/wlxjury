package db.plans

import db.plans.PlanDb.{execute, rows, update}
import play.api.libs.json._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.sql.Connection

/** vote's persistent statistics, as a committed snapshot (decisions 3 and 7; plan §4.3,
  * "Stability"): taken once from the seed with a high sample ([[take]], QueryPlanTool snapshot),
  * then injected in every run after the last ANALYZE and read back ([[inject]]).
  *
  * The snapshot keeps the values as sampled from the seed and **one** factor, production's
  * `n_rows` (3,345,551) over the seed's; the injection scales `n_rows`, the table's page sizes and
  * every index's `size` and `n_leaf_pages` by it. The `n_diff` values stay as sampled: with
  * `n_rows` scaled, each key's rows per value scale by the same factor, as if the seed were that
  * much bigger. Trusted only for constant-bound single-table vote access (R1, the canary, R3 on
  * those); production's own index statistics are P2's.
  *
  * Checked when taken and when injected: **coverage** (the snapshot has exactly vote's current
  * indexes plus the canary's, each with the statistics rows the server keeps for it: an UPDATE
  * of a missing row would silently leave the sampled value) and **plausibility**
  * (`1 < n_leaf_pages ≤ size`, `n_diff_pfxNN ≤ n_rows` and non-decreasing in NN).
  */
final case class StatsSnapshot(
    server: String,
    serverVersion: String,
    taken: String,
    seed: String,
    samplePages: Int,
    seededRows: Long,
    productionRows: Long,
    clusteredIndexSize: Long,
    indexes: Map[String, Map[String, Long]]
) {

  def factor: Double = productionRows.toDouble / seededRows

  def scaled(v: Long): Long = math.max(1L, math.round(v * factor))

  /** The values injected for an index: sizes scaled, n_diff as sampled. */
  def injected(index: String): Map[String, Long] =
    indexes(index).map {
      case (stat @ ("size" | "n_leaf_pages"), v) => stat -> scaled(v)
      case other => other
    }

  /** Problems of the values as stored; empty if plausible. */
  def implausible: Seq[String] = StatsSnapshot.implausible(seededRows, indexes)
}

object StatsSnapshot {

  /** Production's vote rows (26.11.4's rehearsal copy, 2026-11-13). Refresh at a release when
    * the vote count has moved by more than 25%.
    */
  val ProductionRows = 3345551L

  /** ANALYZE's sample for a snapshot: above the seed's leaf pages, so every page is read. */
  val SamplePages = 65535

  def file(server: String): Path = Paths.get("test", "resources", "db", "plans", s"vote-stats-$server.json")

  implicit val format: OFormat[StatsSnapshot] = Json.format[StatsSnapshot]

  def read(path: Path): StatsSnapshot = Json.parse(Files.readAllBytes(path)).as[StatsSnapshot]

  def write(snapshot: StatsSnapshot, path: Path): Unit = {
    Files.createDirectories(path.getParent)
    // indexes and statistics sorted by name, so a regenerated snapshot diffs line by line
    val indexes = JsObject(snapshot.indexes.toSeq.sortBy(_._1).map { case (index, stats) =>
      index -> JsObject(stats.toSeq.sortBy(_._1).map { case (stat, v) => stat -> JsNumber(v) })
    })
    val fields = Json.toJson(snapshot).as[JsObject].fields.map {
      case ("indexes", _) => "indexes" -> indexes
      case field => field
    }
    Files.write(path, (Json.prettyPrint(JsObject(fields)) + "\n").getBytes(UTF_8))
  }

  /** (index, stat) -> value of vote's rows in mysql.innodb_index_stats. */
  def indexStats(c: Connection): Map[String, Map[String, Long]] =
    rows(c, """SELECT index_name, stat_name, stat_value FROM mysql.innodb_index_stats
               WHERE database_name = DATABASE() AND table_name = 'vote'""")(rs =>
      (rs.getString(1), rs.getString(2), rs.getLong(3))).groupBy(_._1).map { case (i, s) => i -> s.map(r => r._2 -> r._3).toMap }

  def tableStats(c: Connection): (Long, Long, Long) =
    rows(c, """SELECT n_rows, clustered_index_size, sum_of_other_index_sizes FROM mysql.innodb_table_stats
               WHERE database_name = DATABASE() AND table_name = 'vote'""")(rs => (rs.getLong(1), rs.getLong(2), rs.getLong(3))).head

  def implausible(nRows: Long, indexes: Map[String, Map[String, Long]]): Seq[String] =
    indexes.toSeq.sortBy(_._1).flatMap { case (index, stats) =>
      val size = stats.get("size")
      val leaves = stats.get("n_leaf_pages")
      val diffs = stats.toSeq.filter(_._1.startsWith("n_diff_pfx")).sortBy(_._1).map(_._2)
      Seq(
        Option.when(size.isEmpty || leaves.isEmpty)(s"$index: no size or n_leaf_pages"),
        Option.when(leaves.exists(_ <= 1) && nRows > 0)(s"$index: n_leaf_pages ${leaves.getOrElse(0)} for a non-empty index"),
        Option.when(leaves.exists(l => size.exists(l > _)))(s"$index: n_leaf_pages ${leaves.get} > size ${size.get}"),
        Option.when(diffs.isEmpty)(s"$index: no n_diff_pfx"),
        Option.when(diffs.exists(_ > nRows))(s"$index: an n_diff_pfx above n_rows $nRows"),
        Option.when(diffs.zip(diffs.drop(1)).exists { case (a, b) => b < a })(s"$index: n_diff_pfx decreasing: ${diffs.mkString(", ")}")
      ).flatten
    }

  /** Takes the snapshot: vote with the canary's indexes (so the snapshot covers both states),
    * then two ANALYZEs:
    *  - the default sample, for each index's `n_leaf_pages`;
    *  - a sample above the index's pages, for everything else: n_diff of every page, exact.
    *    On 10.6 that full scan leaves `n_leaf_pages` at 1 (the plan's §3.4 anomaly, measured in
    *    P1: every index, PRIMARY included, once the sample covers the index), so it comes from
    *    the first.
    * Then the sample size put back and V100 run again. With the snapshot's coverage and
    * plausibility problems (none in one to commit).
    */
  def take(c: Connection, server: String, serverVersion: String, seedKey: String = PlanSeed.key()): (StatsSnapshot, Seq[String]) = {
    PlanSeed.addCanaryIndexes(c)
    PlanSeed.analyze(c)
    val leaves = indexStats(c).map { case (i, s) => i -> s.filter(_._1 == "n_leaf_pages") }
    execute(c, s"ALTER TABLE vote STATS_SAMPLE_PAGES = $SamplePages")
    PlanSeed.analyze(c)
    val (nRows, clustered, _) = tableStats(c)
    val indexes = indexStats(c).map { case (i, s) => i -> (s ++ leaves.getOrElse(i, Map.empty)) }
    execute(c, "ALTER TABLE vote STATS_SAMPLE_PAGES = DEFAULT")
    val snapshot = StatsSnapshot(server, serverVersion, java.time.LocalDate.now.toString, seedKey, SamplePages,
      nRows, ProductionRows, clustered, indexes)
    val problems = snapshot.implausible ++ coverage(snapshot, PlanSeed.voteIndexes(c), indexes)
    PlanSeed.runV100(c)
    PlanSeed.analyze(c)
    (snapshot, problems)
  }

  /** Coverage against the live server: the snapshot's indexes are exactly vote's present ones
    * plus the canary's (or present ones only, with the canary's added), and each present one
    * has the same statistics rows as the server keeps for it.
    */
  def coverage(snapshot: StatsSnapshot, present: Set[String], live: Map[String, Map[String, Long]]): Seq[String] = {
    val expected = present ++ PlanSeed.CanaryIndexes.map(_._1)
    val names = snapshot.indexes.keySet
    Seq(
      Option.when(names != expected)(
        s"the snapshot's indexes ${names.toSeq.sorted.mkString(", ")} are not vote's with the canary's: ${expected.toSeq.sorted.mkString(", ")}"),
    ).flatten ++ present.toSeq.sorted.flatMap { index =>
      val ours = snapshot.indexes.getOrElse(index, Map.empty).keySet
      val theirs = live.getOrElse(index, Map.empty).keySet
      Option.when(ours != theirs)(s"$index: the snapshot has ${ours.toSeq.sorted.mkString(",")}, the server ${theirs.toSeq.sorted.mkString(",")}")
    }
  }

  final case class Injected(nRows: Long, indexes: Seq[String], readBack: Seq[String]) {
    def ok: Boolean = readBack.isEmpty
  }

  /** Injects the snapshot over vote's present statistics (after the last ANALYZE), flushes the
    * table so the server loads them, and reads them back: the rows as written, and the table's
    * row count as the optimizer sees it (`information_schema.TABLES`), which only the injected
    * statistics give. Fails on a coverage or plausibility problem.
    */
  def inject(c: Connection, snapshot: StatsSnapshot, seed: String = PlanSeed.key()): Injected = {
    require(snapshot.seed == seed,
      s"the snapshot was taken of another seed (${snapshot.seed}, this one $seed): regenerate it (QueryPlanTool snapshot)")
    val present = PlanSeed.voteIndexes(c)
    val live = indexStats(c)
    val problems = snapshot.implausible ++ coverage(snapshot, present, live)
    require(problems.isEmpty, "the statistics snapshot doesn't fit:\n" + problems.mkString("\n"))

    val others = present.filterNot(_ == "PRIMARY").toSeq.map(i => snapshot.injected(i)("size")).sum
    val nRows = snapshot.scaled(snapshot.seededRows)
    val updated = update(c, s"""UPDATE mysql.innodb_table_stats SET n_rows = $nRows,
        clustered_index_size = ${snapshot.scaled(snapshot.clusteredIndexSize)}, sum_of_other_index_sizes = $others
        WHERE database_name = DATABASE() AND table_name = 'vote'""")
    require(updated <= 1, s"$updated table statistics rows")
    val misses = present.toSeq.sorted.flatMap { index =>
      snapshot.injected(index).toSeq.sorted.flatMap { case (stat, value) =>
        val n = update(c, s"""UPDATE mysql.innodb_index_stats SET stat_value = $value
            WHERE database_name = DATABASE() AND table_name = 'vote' AND index_name = '$index' AND stat_name = '$stat'""")
        Option.when(n != 1 && live(index)(stat) != value)(s"$index.$stat: $n rows updated")
      }
    }
    execute(c, "FLUSH TABLES vote")
    Injected(nRows, present.toSeq.sorted, misses ++ readBack(c, snapshot, present, nRows))
  }

  /** Differences between the snapshot and the server's statistics, as stored and as loaded. */
  def readBack(c: Connection, snapshot: StatsSnapshot, present: Set[String], nRows: Long): Seq[String] = {
    val stored = indexStats(c)
    val (storedRows, _, _) = tableStats(c)
    val loaded = rows(c, "SELECT TABLE_ROWS FROM information_schema.TABLES WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote'")(
      _.getLong(1)).head
    // the loaded cardinality of each index's first column: n_rows over the rows per value the
    // server derives from n_diff_pfx01, which a reload of other statistics would not give
    val cardinality = rows(c, """SELECT INDEX_NAME, CARDINALITY FROM information_schema.STATISTICS
        WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'vote' AND SEQ_IN_INDEX = 1""")(rs => rs.getString(1) -> rs.getLong(2)).toMap
    Seq(
      Option.when(storedRows != nRows)(s"innodb_table_stats.n_rows $storedRows, injected $nRows"),
      Option.when(loaded != nRows)(s"the server's loaded row count $loaded, injected $nRows: the statistics were not reloaded")
    ).flatten ++ present.toSeq.sorted.flatMap { index =>
      val want = snapshot.injected(index)
      val have = stored.getOrElse(index, Map.empty)
      val stats = want.toSeq.sorted.collect { case (stat, v) if !have.get(stat).contains(v) => s"$index.$stat: ${have.get(stat)} stored, $v injected" }
      val diff1 = want("n_diff_pfx01")
      val card = cardinality.getOrElse(index, -1L)
      // as 10.6 derives it (ha_innobase::info_low): rows per value n_rows / n_diff as a whole
      // number, halved ("we pretend index selectivity is 2 times better"), at least 1; SHOW
      // INDEX's cardinality is n_rows over that
      val perValue = math.max(1L, (if (diff1 == 0) nRows else (nRows.toDouble / diff1).toLong) / 2)
      val expected = nRows / perValue
      stats ++ Option.when(card != expected)(
        s"$index: loaded cardinality $card, $expected from the injected n_diff_pfx01 $diff1")
    }
  }
}
