package db.plans

import scala.util.Using

/** QueryPlanSpec's commands, each on a container of its own, stopped at the end:
  *
  * {{{
  * Test/runMain db.plans.QueryPlanTool snapshot
  *     # takes the statistics snapshot of the seed (PlanSeed's sizes) and writes
  *     # test/resources/db/plans/vote-stats-<server>.json; refuses an implausible one
  * Test/runMain db.plans.QueryPlanTool measure 12,25 indexes-after,indexes-before
  *     # the seed size's measure (decision 2): per filler size and seed mode, the seed's times
  *     # and whether the canary groups show the intersection with real statistics
  * }}}
  */
object QueryPlanTool {

  def main(args: Array[String]): Unit = {
    PlanDb.skipReason().foreach { r => System.err.println(r); sys.exit(2) }
    args.headOption match {
      case Some("snapshot") => snapshot()
      case Some("measure") =>
        val sizes = args.lift(1).fold(Seq(PlanSeed.FillerRounds))(_.split(",").toSeq.map(_.toInt))
        val modes = args.lift(2).fold(Seq[PlanSeed.Mode](PlanSeed.SeedMode))(_.split(",").toSeq.map {
          case PlanSeed.IndexesBefore.name => PlanSeed.IndexesBefore
          case PlanSeed.IndexesAfter.name => PlanSeed.IndexesAfter
          case other => sys.error(s"unknown seed mode $other")
        })
        for (size <- sizes; mode <- modes) measure(size, mode)
      case _ =>
        System.err.println("usage: QueryPlanTool snapshot | measure [fillerRounds,...] [indexes-after,indexes-before]")
        sys.exit(2)
    }
    sys.exit(0)
  }

  private def withDb[T](body: (PlanDb, Double) => T): T = {
    val db = PlanDb.start()
    try body(db, db.startSeconds) finally db.stop()
  }

  def snapshot(): Unit = withDb { (db, _) =>
    Using.resource(db.root()) { c =>
      val version = PlanDb.string(c, "SELECT VERSION()")
      val (_, t) = PlanSeed.seed(c)
      val (snap, problems) = StatsSnapshot.take(c, PlanDb.SupportedServer, version)
      if (problems.nonEmpty) sys.error("the snapshot is not plausible, not written:\n" + problems.mkString("\n"))
      val file = StatsSnapshot.file(PlanDb.SupportedServer)
      StatsSnapshot.write(snap, file)
      println(f"[query-plans] wrote $file: ${t.rows} rows, factor ${snap.factor}%.4f, ${snap.indexes.size} indexes")
    }
  }

  def measure(fillerRounds: Int, mode: PlanSeed.Mode): Unit = withDb { (db, start) =>
    Using.resource(db.root()) { c =>
      val rules = PlanCheck.rules()
      val (corner, t) = PlanSeed.seed(c, mode, fillerRounds)
      val ids = PlanCheck.Ids(corner.jurorId, corner.roundId, corner.pageId, corner.contestId)
      StatementCapture.reset()
      StatementCapture.install()
      PlanScenarios.juror(corner)
      StatementCapture.uninstall()
      val canaryGroups = rules.named.filter(_.canary).map(_.group).toSet
      val shapes = StatementCapture.all.filter(s => canaryGroups(s.group) && PlanCheck.onVote(s.template) && s.sample.isDefined)
      val t0 = System.nanoTime()
      PlanSeed.addCanaryIndexes(c)
      PlanSeed.analyze(c)
      val canarySeconds = (System.nanoTime() - t0) / 1e9
      val indexes = PlanSeed.voteIndexes(c)
      val fired = shapes.groupBy(_.group).map { case (g, ss) =>
        g -> ss.map(PlanCheck.explain(_, ids)).exists(p => PlanCheck.voteRows(p, indexes).exists(_.intersect))
      }
      // the high-sample snapshot's plausibility at this size (not written)
      PlanSeed.runV100(c)
      val (_, problems) = StatsSnapshot.take(c, PlanDb.SupportedServer, PlanDb.string(c, "SELECT VERSION()"),
        PlanSeed.key(fillerRounds))
      println(f"[query-plans] measure filler=$fillerRounds%d mode=${mode.name} rows=${t.rows}%d start=$start%.1fs " +
        f"insert=${t.insertSeconds}%.1fs index=${t.indexSeconds}%.1fs analyze=${t.analyzeSeconds}%.1fs " +
        f"canaryAlterAnalyze=$canarySeconds%.1fs canaryReal=${fired.toSeq.sortBy(_._1).map { case (g, f) => s"$g:$f" }.mkString(",")} " +
        s"snapshotProblems=${problems.size}${if (problems.nonEmpty) problems.mkString(" [", "; ", "]") else ""}")
    }
  }
}
