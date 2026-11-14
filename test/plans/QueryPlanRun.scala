package plans

import plans.PlanCheck.{Ids, Named, Plan, Rules}
import plans.PlanSeed.{Corner, Timings}
import plans.StatementCapture.Shape
import plans.StatsSnapshot.Injected

import java.sql.Connection
import scala.collection.mutable
import scala.util.Using

/** One QueryPlanSpec run (docs/plans/2026-11-13-query-plan-checks.md, §5 P1), in order:
  *  1. its own container, every migration, the seed; ANALYZE;
  *  2. the snapshot injected and read back; the juror scenarios captured, and the organizer's
  *     round statistics (PlanScenarios.organizer);
  *  3. the snapshot injected again, every captured vote shape EXPLAINed rebound to the corner: R1, R3;
  *  4. the canary: V100's four indexes back, ANALYZE, the named shapes EXPLAINed with the real
  *     statistics (recorded: the seed size's measure), the snapshot injected and read back, the
  *     shapes EXPLAINed again: the canary groups must show the intersection;
  *  5. V100 itself run again, ANALYZE, the snapshot injected;
  *  6. last, the distribution captured, injected again, its vote shapes EXPLAINed: R1.
  */
final class QueryPlanRun(val rules: Rules, val server: String) {

  val seconds: mutable.LinkedHashMap[String, Double] = mutable.LinkedHashMap.empty
  var serverVersion = ""
  var optimizerSwitch = ""
  var corner: Corner = _
  var timings: Timings = _
  var snapshot: StatsSnapshot = _
  val injections: mutable.ArrayBuffer[(String, Injected)] = mutable.ArrayBuffer.empty
  var scenarios: Seq[String] = Nil
  /** Shape id -> its plan, per stage: "v100", "canary-real", "canary", "distribution". */
  val plans: mutable.Map[String, mutable.LinkedHashMap[String, Plan]] = mutable.LinkedHashMap.empty
  var shapes: Seq[Shape] = Nil
  var voteIndexes: Set[String] = Set.empty

  def ids: Ids = corner.ids

  private def timed[T](what: String)(body: => T): T = {
    val t0 = System.nanoTime()
    try body finally seconds(what) = (System.nanoTime() - t0) / 1e9
  }

  def named: Seq[Named] = rules.named

  /** Shapes on vote (or round_image) with a sample to EXPLAIN. */
  def voteShapes(filter: Shape => Boolean = _ => true): Seq[Shape] =
    StatementCapture.all.filter(s => PlanCheck.onVote(s.template) && PlanCheck.explainable(s.template) && filter(s))

  private def explainAll(stage: String, of: Seq[Shape]): Unit = {
    val stagePlans = plans.getOrElseUpdate(stage, mutable.LinkedHashMap.empty)
    of.filter(_.sample.isDefined).foreach(s => stagePlans(s.id) = PlanCheck.explain(s, ids))
  }

  /** Every EXPLAIN batch's preparation, the same each time: purge done (the
    * `innodb_max_purge_lag_wait = 0` wait), ANALYZE, the snapshot injected, read back.
    *
    * Without the purge wait, the batch right after the vote scenario got other plans than the
    * same batch 5 s later, with the same injected statistics (P1, the schema with V100's
    * indexes: 4 shapes with the intersection, then 16), most likely as a range's row estimate
    * dives into index pages that still held the votes' delete-marked entries.
    */
  private def inject(c: Connection, stage: String): Injected = {
    PlanDb.purged(c)
    PlanSeed.analyze(c)
    val injected = StatsSnapshot.inject(c, snapshot)
    injections += stage -> injected
    require(injected.ok, s"the statistics read back differ from the snapshot ($stage):\n" + injected.readBack.mkString("\n"))
    injected
  }

  def run(db: PlanDb, snapshotResource: String): Unit = Using.resource(db.root()) { c =>
    try runIn(c, snapshotResource)
    finally {
      StatementCapture.uninstall()
      shapes = StatementCapture.all
    }
  }

  private def runIn(c: Connection, snapshotResource: String): Unit = {
    serverVersion = PlanDb.string(c, "SELECT VERSION()")
    optimizerSwitch = PlanDb.string(c, "SELECT @@optimizer_switch")
    val (seeded, t) = timed("seed (total)")(PlanSeed.seed(c))
    corner = seeded
    timings = t
    snapshot = StatsSnapshot.read(snapshotResource)
    voteIndexes = PlanSeed.voteIndexes(c)

    timed("scenarios") {
      inject(c, "before the scenarios")
      StatementCapture.reset()
      StatementCapture.install()
      scenarios = PlanScenarios.juror(corner) ++ PlanScenarios.organizer(corner)
    }
    val jurorShapes = voteShapes()
    timed("explain (V100)") {
      inject(c, "before the EXPLAINs")
      explainAll("v100", jurorShapes)
    }
    timed("canary") {
      PlanSeed.addCanaryIndexes(c)
      PlanSeed.analyze(c)
      voteIndexes = PlanSeed.voteIndexes(c)
      explainAll("canary-real", jurorShapes)
      inject(c, "canary")
      explainAll("canary", jurorShapes)
      PlanSeed.runV100(c)
      PlanSeed.analyze(c)
      voteIndexes = PlanSeed.voteIndexes(c)
      inject(c, "after the canary (V100 again)")
    }
    timed("distribution") {
      val before = StatementCapture.all.map(_.id).toSet
      PlanScenarios.distribution(corner)
      inject(c, "after the distribution")
      explainAll("distribution", voteShapes(s => !before(s.id) || s.contexts.contains(PlanScenarios.DistributionScenario)))
    }
  }

  // ---- verdicts ----------------------------------------------------------------------------

  def stage(name: String): Seq[Plan] = plans.get(name).toSeq.flatMap(_.values)

  def intersects(plan: Plan): Seq[PlanCheck.Row] =
    PlanCheck.voteRows(plan, voteIndexes ++ PlanSeed.CanaryIndexes.map(_._1)).filter(_.intersect) ++
      plan.rows.filter(r => r.intersect && r.keys.exists(_.startsWith("round_image")))

  /** The plans R1 checks: after V100, the juror scenarios' and the distribution's. */
  def r1Plans: Seq[Plan] = (stage("v100") ++ stage("distribution")).distinctBy(_.shape.id)

  /** Plans unbound with the corner's ids, EXPLAINed with their sample's. */
  def withSample: Seq[Plan] = r1Plans.filter(_.withSample)

  /** Plans unbound with the sample's ids too: R1 can't check them. */
  def unbound: Seq[Plan] = r1Plans.filter(_.unbound)

  /** R1: no intersection on vote (or round_image), after V100, juror scenarios and distribution;
    * a vote statement that can't be EXPLAINed, or whose plan stays unbound (unless allowed in
    * rules.json's `unboundAllow`), fails too: R1 can't see its plan.
    */
  def r1Failures: Seq[String] =
    r1Plans.filterNot(p => rules.r1Allow.contains(p.shape.group)).flatMap { p =>
      p.error.map(e => s"${p.shape.id}: not EXPLAINed ($e)").toSeq ++
        Option.when(p.unbound && !rules.unboundAllow.contains(p.shape.group))(
          s"${p.shape.id}: unbound with the corner's and the sample's values: ${p.summary}") ++
        intersects(p).map(r => s"${p.shape.id}: index-merge intersection: ${r.line}")
    }

  def shapesOf(group: String, stageName: String): Seq[Plan] = stage(stageName).filter(_.shape.group == group)

  /** R3: each named group is captured, bound, and its constant-bound vote access uses an
    * expected key for this server.
    */
  def r3Failures: Seq[String] = named.flatMap { n =>
    val expected = n.expect.getOrElse(server, Nil)
    val ps = shapesOf(n.group, "v100")
    if (ps.isEmpty) Seq(s"${n.group}: not captured (no scenario ran it, or its caller changed)")
    else if (expected.isEmpty) Seq(s"${n.group}: no expected key for MariaDB $server in rules.json")
    else {
      val checked = ps.filterNot(_.unbound).flatMap(p =>
        PlanCheck.voteRows(p, voteIndexes).filter(PlanCheck.constantBound(p, _)).map(p -> _))
      if (checked.isEmpty) Seq(s"${n.group}: no constant-bound single-table vote access in ${ps.size} shape(s): " +
        ps.map(_.summary).mkString("; "))
      else checked.collect { case (p, r) if !r.keys.forall(expected.contains) =>
        s"${p.shape.id}: key ${Option(r.key).getOrElse("none")} (${r.accessType}), expected ${expected.mkString(" or ")}"
      }
    }
  }

  /** The canary: with V100's four indexes back and the snapshot injected, each canary group has
    * a shape whose plan shows the intersection.
    */
  def canaryFailures: Seq[String] = named.filter(_.canary).flatMap { n =>
    val ps = shapesOf(n.group, "canary")
    Option.when(!ps.exists(p => intersects(p).nonEmpty))(
      s"${n.group}: no index-merge intersection with V100's indexes back (${ps.size} shape(s)): " +
        ps.map(_.summary).mkString("; "))
  }

  /** The canary with the real statistics (no injection): the seed size's measure (decision 2). */
  def canaryReal: Map[String, Boolean] =
    named.filter(_.canary).map(n => n.group -> shapesOf(n.group, "canary-real").exists(p => intersects(p).nonEmpty)).toMap
}
