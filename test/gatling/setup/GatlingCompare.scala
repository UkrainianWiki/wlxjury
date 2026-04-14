package gatling.setup

import java.io.File
import scala.io.Source
import scala.sys.process._
import scala.util.Try

/** Cumulative per-index Gatling performance comparison.
 *
 *  Pass 0 — baseline: stashes ALL V45a–V45g migrations so MariaDB starts
 *            without any performance indexes, then runs `sbt Gatling/test`.
 *  Pass 1 — restore V45a, run simulations → record delta vs pass 0
 *  Pass 2 — restore V45b (on top of V45a), run simulations → record delta vs pass 1
 *  ...
 *  Pass 7 — all indexes applied → record delta vs pass 6
 *
 *  After all passes, prints an attribution table showing which index contributed
 *  what marginal latency change to each simulation, then writes to
 *  docs/gatling-comparison.txt.
 *
 *  Usage:
 *    sbt "Test/runMain gatling.setup.GatlingCompare"
 */
object GatlingCompare {

  // ── Domain ──────────────────────────────────────────────────────────────

  case class SimStats(
    sim:    String,
    total:  Int,
    okPct:  Double,
    mean:   Double,
    p50:    Long,
    p95:    Long,
    p99:    Long,
  )

  // ── Parsing ─────────────────────────────────────────────────────────────

  /** Parse a Gatling simulation.log and return per-simulation stats. */
  def parseLog(logFile: File): Option[SimStats] = {
    val sim = logFile.getParentFile.getName.replaceAll("-\\d{17,}$", "")
    val requests = Try {
      val src   = Source.fromFile(logFile)
      val lines = try src.getLines().toVector finally src.close()
      lines.flatMap { line =>
        val parts = line.split('\t')
        if (parts.length >= 6 && parts(0) == "REQUEST") {
          for {
            start   <- Try(parts(3).toLong).toOption
            end     <- Try(parts(4).toLong).toOption
            status  =  parts(5)
          } yield (end - start, status)
        } else None
      }
    }.getOrElse(Vector.empty)

    if (requests.isEmpty) return None

    val latencies = requests.map(_._1).sorted
    val total     = latencies.length
    val okCount   = requests.count(_._2 == "OK")
    val mean      = latencies.sum.toDouble / total
    def pct(p: Double): Long = latencies((total * p).toInt.min(total - 1))

    Some(SimStats(
      sim   = sim,
      total = total,
      okPct = 100.0 * okCount / total,
      mean  = mean,
      p50   = pct(0.50),
      p95   = pct(0.95),
      p99   = pct(0.99),
    ))
  }

  /** Collect all simulation.log files produced on or after `afterMs`. */
  def collectLogs(gatlingDir: File, afterMs: Long): Map[String, File] = {
    Option(gatlingDir.listFiles())
      .getOrElse(Array.empty)
      .flatMap { dir =>
        val log = new File(dir, "simulation.log")
        if (log.exists() && log.lastModified() >= afterMs) {
          val sim = dir.getName.replaceAll("-\\d{17,}$", "")
          Some(sim -> log)
        } else None
      }
      .toMap
  }

  // ── Reporting ────────────────────────────────────────────────────────────

  def formatReport(baseline: Map[String, SimStats], optimized: Map[String, SimStats]): String = {
    val sb     = new StringBuilder
    val w      = 72
    val sims   = (baseline.keySet ++ optimized.keySet).toSeq.sorted
    val header = "%-36s  %6s  %8s  %8s  %10s".format("Simulation / Metric", "mean", "p50", "p95", "p99")

    sb.append("=" * w).append("\n")
    sb.append("GATLING COMPARISON — Baseline vs V45 indexes\n")
    sb.append("=" * w).append("\n")
    sb.append(header).append("\n")
    sb.append("-" * w).append("\n")

for (sim <- sims) {
      (baseline.get(sim), optimized.get(sim)) match {
        case (Some(b), Some(o)) =>
          sb.append(f"\n  $sim%-36s\n")
          sb.append(f"  ${"baseline"}%-10s  mean=${b.mean.toLong}ms  p50=${b.p50}ms  p95=${b.p95}ms  p99=${b.p99}ms  ok=${b.okPct.toInt}%%\n")
          sb.append(f"  ${"optimized"}%-10s  mean=${o.mean.toLong}ms  p50=${o.p50}ms  p95=${o.p95}ms  p99=${o.p99}ms  ok=${o.okPct.toInt}%%\n")
          sb.append(f"  ${"delta"}%-10s  mean=${fmtDelta(b.mean, o.mean)}  p50=${fmtDelta(b.p50.toDouble, o.p50.toDouble)}  p95=${fmtDelta(b.p95.toDouble, o.p95.toDouble)}  p99=${fmtDelta(b.p99.toDouble, o.p99.toDouble)}\n")
        case (Some(b), None) =>
          sb.append(f"\n  $sim%-36s  baseline only (no optimized data)\n")
        case (None, Some(o)) =>
          sb.append(f"\n  $sim%-36s  optimized only (no baseline data)\n")
        case _ =>
      }
    }

    sb.append("\n").append("=" * w).append("\n")
    sb.toString()
  }

  private def fmtDelta(bv: Double, ov: Double): String = {
    val delta = ov - bv
    val sign  = if (delta < 0) "-" else "+"
    val pct   = if (bv != 0) math.abs(delta) * 100.0 / bv else 0.0
    f"${sign}${math.abs(delta).toLong}ms (${sign}${pct.toInt}%%)"
  }

  // ── Attribution reporting ─────────────────────────────────────────────

  /** Column headers for the attribution table (short sim names). */
  private val simColumns: Seq[(String, String)] = Seq(
    "jurorGallery"  -> "jurorGallerySimulation",
    "regionFilter"  -> "regionFilterSimulation",
    "aggRatings"    -> "aggregatedRatingsSimulation",
    "roundMgmt"     -> "roundManagementSimulation",
    "voting"        -> "votingSimulation",
  )

  /** Format percent delta between before and after stat maps for one metric.
   *  Returns "n/a" for < 1% absolute change (noise). */
  private def attrCell(
    prev: Map[String, SimStats],
    curr: Map[String, SimStats],
    simKey: String,
    metric: SimStats => Double,
  ): String = {
    for {
      p <- prev.get(simKey)
      c <- curr.get(simKey)
      bv = metric(p)
      cv = metric(c)
      if bv != 0
      pct = (cv - bv) * 100.0 / bv
      if math.abs(pct) >= 1.0
    } yield {
      val sign = if (pct < 0) "-" else "+"
      f"${sign}${math.abs(pct).toInt}%%"
    }
  }.getOrElse("n/a")

  private def formatAttributionTable(
    baseline: Map[String, SimStats],
    passes: Seq[(String, Map[String, SimStats])],
  ): String = {
    val sb = new StringBuilder
    val w  = 72

    // Column widths
    val idxW  = 34
    val colW  = 14  // "mean  p95" per sim (2 metrics)
    val votW  = 7   // voting only has mean

    // Build header
    sb.append("=" * (idxW + simColumns.size * colW + 2)).append("\n")
    sb.append("INDEX ATTRIBUTION TABLE\n")
    sb.append("=" * (idxW + simColumns.size * colW + 2)).append("\n")

    // Sim name row
    val simLine = new StringBuilder(" " * idxW + " | ")
    for ((short, _) <- simColumns) {
      simLine.append(f"$short%-14s")
    }
    sb.append(simLine.toString().stripTrailing()).append("\n")

    // Metrics row
    val metricLine = new StringBuilder(" " * idxW + " | ")
    for ((_, _) <- simColumns.init) {
      metricLine.append(f"${"mean p95"}%-14s")
    }
    metricLine.append(f"${"mean"}%-7s")
    sb.append(metricLine.toString().stripTrailing()).append("\n")

    // Separator
    sb.append("-" * idxW + "-|-" + simColumns.init.map(_ => "-" * 14).mkString("") + "-" * 7).append("\n")

    // One row per index pass
    val allPasses = ("baseline" -> baseline) +: passes
    for (i <- passes.indices) {
      val (label, curr) = passes(i)
      val prev          = allPasses(i)._2  // previous stats map

      val row = new StringBuilder(("%-" + idxW + "s | ").format(label))
      for ((_, simKey) <- simColumns.init) {
        val meanCell = attrCell(prev, curr, simKey, _.mean)
        val p95Cell  = attrCell(prev, curr, simKey, _.p95.toDouble)
        val cell     = f"$meanCell%-6s $p95Cell%-6s".stripTrailing()
        row.append(f"$cell%-14s")
      }
      // voting: mean only
      val (_, votSimKey) = simColumns.last
      val votCell = attrCell(prev, curr, votSimKey, _.mean)
      row.append(votCell)

      sb.append(row.toString().stripTrailing()).append("\n")
    }

    sb.append("=" * (idxW + simColumns.size * colW + 2)).append("\n")
    sb.toString()
  }

  // ── Index migration management ────────────────────────────────────────

  private val migrationsDir: File = new File("conf/db/migration/default")

  // Ordered list: (filename, human label)
  private val indexMigrations: Seq[(String, String)] = Seq(
    "V45a__idx_selection_jury_round.sql"      -> "idx_selection_jury_round",
    "V45b__idx_rounds_contest_active.sql"     -> "idx_rounds_contest_active",
    "V45c__idx_round_user_round_active.sql"   -> "idx_round_user_round_active",
    "V45d__idx_users_contest.sql"             -> "idx_users_contest",
    "V45e__idx_selection_page_jury_round.sql" -> "idx_selection_page_jury_round",
    "V45f__idx_selection_round_page.sql"      -> "idx_selection_round_page",
    "V45g__idx_users_wiki_account.sql"        -> "idx_users_wiki_account",
  )

  /** Stash V45x files not yet applied (rename to .bak). */
  private def stashFrom(fromIdx: Int): Unit =
    indexMigrations.drop(fromIdx).foreach { case (file, _) =>
      val f = new File(migrationsDir, file)
      if (f.exists()) f.renameTo(new File(migrationsDir, file + ".bak"))
    }

  /** Restore V45a..V45(upToIdx) from .bak (keep rest as .bak). */
  private def restoreUpTo(upToIdx: Int): Unit =
    indexMigrations.take(upToIdx).foreach { case (file, _) =>
      val bak = new File(migrationsDir, file + ".bak")
      if (bak.exists()) bak.renameTo(new File(migrationsDir, file))
    }

  /** Ensure all V45x files are restored to clean state. */
  private def restoreAll(): Unit = restoreUpTo(indexMigrations.size)

  // ── Orchestration ────────────────────────────────────────────────────────

  def main(args: Array[String]): Unit = {
    val root       = new File(System.getProperty("user.dir"))
    val gatlingDir = new File(root, "target/gatling")
    val reportOut  = new File(root, "docs/gatling-comparison.txt")

    def runPass(label: String): Map[String, SimStats] = {
      val beforeMs = System.currentTimeMillis()
      println(s"\n>>> Running $label pass …")
      val exitCode = Process(Seq("sbt", "Gatling/test"), root).!
      if (exitCode != 0) println(s"  WARNING: sbt exited with code $exitCode (some simulations may have failed)")
      val logs = collectLogs(gatlingDir, beforeMs)
      println(s"  $label: collected ${logs.size} simulation log(s): ${logs.keys.mkString(", ")}")
      logs.flatMap { case (sim, log) =>
        parseLog(log).map(sim -> _)
      }
    }

    // ── Pass 0: baseline (all indexes stashed) ──
    stashFrom(0)
    println("[setup] All V45a–V45g stashed — baseline will run without performance indexes")

    val baselineStats = runPass("baseline")

    // ── Passes 1–N: cumulative index restoration ──
    val indexPasses = scala.collection.mutable.ArrayBuffer.empty[(String, Map[String, SimStats])]

    for (i <- indexMigrations.indices) {
      val (_, label) = indexMigrations(i)
      restoreUpTo(i + 1)
      println(s"[setup] Restored indexes 0..$i (cumulative)")
      val stats = runPass(s"+ $label")
      indexPasses += (label -> stats)
    }

    // ── Restore everything to clean state ──
    restoreAll()
    println("[setup] All V45a–V45g restored")

    // ── Reports ──
    val optimizedStats = indexPasses.lastOption.map(_._2).getOrElse(Map.empty)
    val comparisonReport = formatReport(baselineStats, optimizedStats)
    val attributionReport = formatAttributionTable(baselineStats, indexPasses.toSeq)

    val fullReport = comparisonReport + "\n" + attributionReport
    println(fullReport)

    reportOut.getParentFile.mkdirs()
    val pw = new java.io.PrintWriter(reportOut)
    try pw.print(fullReport) finally pw.close()
    println(s"Report written to ${reportOut.getPath}")
  }
}
