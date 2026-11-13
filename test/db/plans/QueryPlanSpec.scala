package db.plans

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

import scala.util.Try

/** Query plans of the juror's statements, from the code that runs (docs/plans/2026-11-13-query-plan-checks.md,
  * P1, the D spec): a seeded corner of production's shape (the one that made MariaDB 10.6 choose
  * vote's round ∩ juror intersection before V100), a committed statistics snapshot scaled to
  * production's size, the juror's pages captured as they run, every vote statement EXPLAINed
  * rebound to the corner.
  *
  *  - **R1**: no index-merge intersection on vote;
  *  - **R3**: the named groups' constant-bound single-table vote access uses its expected key
  *    (test/resources/db/plans/rules.json);
  *  - **the canary**: with V100's four indexes back (and ANALYZE, the snapshot injected and read
  *    back) the canary groups show the intersection; so the check can see what V100 fixed.
  *
  * In its own forked JVM (build.sbt, `Test / testGrouping`) with its own container
  * ([[PlanDb]]), thrown away with it. MariaDB 10.6 only (decision 9 = C): on another server it
  * skips, saying why. The report goes to `target/query-plans/` ([[QueryPlanReport]]).
  *
  * Regenerate the snapshot after changing the seed: `Test/runMain db.plans.QueryPlanTool snapshot`.
  */
class QueryPlanSpec extends Specification with BeforeAfterAll {

  sequential

  private val skip = PlanDb.skipReason()
  private var db: Option[PlanDb] = None
  private lazy val outcome: Try[QueryPlanRun] = Try {
    val started = PlanDb.start()
    db = Some(started)
    val run = new QueryPlanRun(PlanCheck.rules(), PlanDb.SupportedServer)
    run.run(started, StatsSnapshot.file(PlanDb.SupportedServer))
    run
  }

  override def beforeAll(): Unit = skip.foreach(QueryPlanReport.skipped)

  override def afterAll(): Unit =
    try if (skip.isEmpty) outcome.fold(QueryPlanReport.failed, QueryPlanReport.write(_, db))
    finally db.foreach(_.stop())

  private def check(failures: QueryPlanRun => Seq[String]): org.specs2.execute.Result =
    skip match {
      case Some(reason) => skipped(reason)
      case None =>
        outcome.fold(e => failure(s"the run failed: $e"), run => {
          val f = failures(run)
          if (f.isEmpty) success else failure(f.mkString("\n", "\n", ""))
        })
    }

  "The juror's statements on a production-shaped corner" should {

    "run on a seeded database with the statistics snapshot injected and read back" in
      check(run => run.injections.collect { case (stage, i) if !i.ok => s"$stage: ${i.readBack.mkString("; ")}" }.toSeq)

    "R1: use no index-merge intersection on vote" in check(_.r1Failures)

    "R3: read vote by the expected key in the named groups" in check(_.r3Failures)

    "show the intersection with V100's four indexes back (the canary)" in check(_.canaryFailures)
  }
}
