package db.plans

import play.api.libs.json._

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** QueryPlanSpec's output in `target/query-plans/`: `summary.md` (CI appends it to the job's
  * summary), `catalog.jsonl` (every captured shape with its sample, contexts and plans: a CI
  * artifact, never committed, decision 7) and `timings.json`.
  */
object QueryPlanReport {

  val Dir: Path = Paths.get("target", "query-plans")

  private def write(name: String, text: String): Unit = {
    Files.createDirectories(Dir)
    Files.write(Dir.resolve(name), text.getBytes(UTF_8))
  }

  def skipped(reason: String): Unit =
    write("summary.md", s"## Query plans (QueryPlanSpec)\n\n**Skipped:** $reason\n")

  def failed(error: Throwable): Unit =
    write("summary.md", s"## Query plans (QueryPlanSpec)\n\n**Failed before the checks:** `${oneLine(error.toString)}`\n")

  private def oneLine(s: String) = s.replaceAll("\\s+", " ").replace("|", "\\|").take(400)

  private def fmt(s: Double) = f"$s%.1f s"

  private def mark(failures: Seq[String]) = if (failures.isEmpty) "pass" else s"**FAIL** (${failures.size})"

  def write(run: QueryPlanRun, db: Option[PlanDb]): Unit = {
    val startSeconds = db.fold(0.0)(_.startSeconds)
    val migrateSeconds = db.fold(0.0)(_.migrateSeconds)
    val t = run.timings
    val r1 = run.r1Failures
    val r3 = run.r3Failures
    val canary = run.canaryFailures
    val shapes = run.shapes
    val onVote = shapes.filter(s => PlanCheck.onVote(s.template))
    val noContext = shapes.filter(_.contexts.contains(StatementCapture.NoContext))
    val batches = shapes.filter(_.batch)
    val failedShapes = shapes.filter(_.failures > 0)
    val v100 = run.stage("v100")
    val canaryIntersects = run.stage("canary").count(p => run.intersects(p).nonEmpty)
    val realIntersects = run.stage("canary-real").count(p => run.intersects(p).nonEmpty)

    val md = new StringBuilder
    md ++= "## Query plans (QueryPlanSpec)\n\n"
    md ++= s"MariaDB ${run.serverVersion}; seed ${t.rows} vote rows (${PlanSeed.SeedMode.name}, ${PlanSeed.key()}); " +
      s"snapshot `${StatsSnapshot.file(run.server).getFileName}` taken ${run.snapshot.taken}, factor ${f"${run.snapshot.factor}%.3f"} " +
      s"to ${run.snapshot.productionRows} rows.\n\n"
    md ++= "| Check | Result |\n|---|---|\n"
    md ++= s"| R1: no index-merge intersection on vote | ${mark(r1)}: ${v100.size + run.stage("distribution").size} vote plans |\n"
    md ++= s"| R3: named groups' constant-bound vote access uses the expected key | ${mark(r3)}: ${run.named.size} groups |\n"
    md ++= s"| Canary: V100's indexes back, snapshot injected | ${mark(canary)}: $canaryIntersects plans with the intersection |\n"
    md ++= s"| Canary with real statistics (seed size, decision 2) | $realIntersects plans with the intersection; " +
      run.canaryReal.toSeq.sortBy(_._1).map { case (g, fired) => s"$g ${if (fired) "fires" else "doesn't"}" }.mkString(", ") + " |\n"
    md ++= s"| Statistics read back | ${run.injections.size} injections, all read back |\n\n"

    val failures = r1 ++ r3 ++ canary
    if (failures.nonEmpty) {
      md ++= "### Failures\n\n"
      failures.foreach(f => md ++= s"- ${oneLine(f)}\n")
      md ++= "\n"
    }

    md ++= "### Named groups\n\n| Group | Shapes | After V100 | Canary (snapshot) |\n|---|---|---|---|\n"
    run.named.foreach { n =>
      val now = run.shapesOf(n.group, "v100")
      val can = run.shapesOf(n.group, "canary")
      def keys(ps: Seq[PlanCheck.Plan]) = ps.flatMap(p => PlanCheck.voteRows(p, run.voteIndexes ++ PlanSeed.CanaryIndexes.map(_._1))
        .map(r => s"${r.accessType} ${Option(r.key).getOrElse("-")}")).distinct.mkString("<br>")
      md ++= s"| `${n.group}` | ${now.size} | ${keys(now)} | ${if (n.canary) keys(can) else "—"} |\n"
    }

    md ++= s"\n### Capture\n\n${shapes.size} shapes (${onVote.size} on vote or round_image) in ${run.scenarios.size + 1} scenarios; " +
      s"${noContext.size} with statements outside any scenario (\"no context\"); ${batches.size} batches without a sample; " +
      s"${failedShapes.size} with failed statements.\n\n"
    md ++= "### Times\n\n| Step | Time |\n|---|---|\n"
    md ++= s"| container start | ${fmt(startSeconds)} |\n| migrations | ${fmt(migrateSeconds)} |\n"
    md ++= s"| seed: inserts | ${fmt(t.insertSeconds)} |\n| seed: index build | ${fmt(t.indexSeconds)} |\n| seed: ANALYZE | ${fmt(t.analyzeSeconds)} |\n"
    run.seconds.foreach { case (k, v) => md ++= s"| $k | ${fmt(v)} |\n" }
    md ++= s"\n`optimizer_switch`: `${run.optimizerSwitch}`\n"
    write("summary.md", md.toString)
    // one line in the test log too
    println(f"[query-plans] R1 ${if (r1.isEmpty) "pass" else s"FAIL ${r1.size}"}, R3 ${if (r3.isEmpty) "pass" else s"FAIL ${r3.size}"}, " +
      f"canary ${if (canary.isEmpty) "pass" else s"FAIL ${canary.size}"} ($canaryIntersects plans; real statistics $realIntersects); " +
      f"${shapes.size} shapes; start $startSeconds%.1fs, migrations $migrateSeconds%.1fs, insert ${t.insertSeconds}%.1fs, " +
      f"index build ${t.indexSeconds}%.1fs, " + run.seconds.map { case (k, v) => f"$k $v%.1fs" }.mkString(", "))

    val catalog = shapes.map { s =>
      def plan(stage: String) = run.plans.get(stage).flatMap(_.get(s.id)).map(p => Json.obj(
        "sql" -> p.sql, "error" -> p.error, "unbound" -> p.unbound,
        "rows" -> p.rows.map(r => Json.obj("id" -> r.id, "select_type" -> r.selectType, "table" -> r.table, "type" -> r.accessType,
          "possible_keys" -> Option(r.possibleKeys), "key" -> Option(r.key), "ref" -> Option(r.ref), "rows" -> Option(r.rows),
          "extra" -> Option(r.extra)))))
      Json.obj(
        "group" -> s.group, "hash" -> s.hash, "count" -> s.count, "failures" -> s.failures, "batch" -> s.batch,
        "contexts" -> JsObject(s.contexts.toSeq.map { case (k, v) => k -> JsNumber(v) }),
        "sql" -> s.template, "sample" -> s.sample.map(_.map(v => String.valueOf(unwrap(v)))),
        "plans" -> JsObject(Seq("v100", "canary-real", "canary", "distribution").flatMap(st => plan(st).map(st -> _)))
      ).toString
    }
    write("catalog.jsonl", catalog.mkString("", "\n", "\n"))
    write("timings.json", Json.prettyPrint(Json.obj(
      "server" -> run.serverVersion, "voteRows" -> t.rows, "seedMode" -> PlanSeed.SeedMode.name, "fillerRounds" -> PlanSeed.FillerRounds,
      "containerStart" -> startSeconds, "migrations" -> migrateSeconds, "insert" -> t.insertSeconds, "indexBuild" -> t.indexSeconds, "analyze" -> t.analyzeSeconds,
      "steps" -> JsObject(run.seconds.toSeq.map { case (k, v) => k -> JsNumber(BigDecimal(v)) }),
      "canaryReal" -> JsObject(run.canaryReal.toSeq.map { case (k, v) => k -> JsBoolean(v) }))))
  }

  private def unwrap(v: Any): Any = v match {
    case b: scalikejdbc.ParameterBinderWithValue => unwrap(b.value)
    case Some(x) => unwrap(x)
    case None => null
    case x => x
  }
}
