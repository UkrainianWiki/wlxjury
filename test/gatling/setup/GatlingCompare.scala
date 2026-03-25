package gatling.setup

import java.io.File
import java.nio.file.{Files, Paths, StandardCopyOption}
import scala.io.Source
import scala.sys.process._
import scala.util.Try

/** Two-pass Gatling performance comparison.
 *
 *  Pass 1 — baseline: temporarily stashes V45 migration so MariaDB starts
 *            without the performance indexes, then runs `sbt Gatling/test`.
 *  Pass 2 — optimized: restores V45 and runs `sbt Gatling/test` again.
 *
 *  After both passes, parses every simulation.log produced by each run and
 *  prints a side-by-side latency comparison to stdout, then writes it to
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

  // ── Orchestration ────────────────────────────────────────────────────────

  def main(args: Array[String]): Unit = {
    val root      = Paths.get(System.getProperty("user.dir")).toFile
    val migration = new File(root, "conf/db/migration/default/V45__Add_performance_indexes.sql")
    val stash     = new File(root, "conf/db/migration/default/V45__Add_performance_indexes.sql.bak")
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

    // ── Pass 1: baseline ──
    if (migration.exists()) {
      Files.move(migration.toPath, stash.toPath, StandardCopyOption.REPLACE_EXISTING)
      println(s"[setup] V45 stashed (baseline will run without performance indexes)")
    } else {
      println("[setup] V45 not found — running baseline as-is (indexes already absent)")
    }

    val baselineStats = try runPass("baseline") finally {
      // always restore V45, even if baseline run fails
      if (stash.exists()) {
        Files.move(stash.toPath, migration.toPath, StandardCopyOption.REPLACE_EXISTING)
        println(s"[setup] V45 restored for optimized pass")
      }
    }

    // ── Pass 2: optimized ──
    val optimizedStats = runPass("optimized")

    // ── Report ──
    val report = formatReport(baselineStats, optimizedStats)
    println(report)

    reportOut.getParentFile.mkdirs()
    val pw = new java.io.PrintWriter(reportOut)
    try pw.print(report) finally pw.close()
    println(s"Report written to ${reportOut.getPath}")
  }
}
