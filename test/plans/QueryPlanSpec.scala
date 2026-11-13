package plans

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAfterAll

/** Query plans of the juror's statements, from the code that runs (docs/plans/2026-11-13-query-plan-checks.md,
  * P1, the D spec): a seeded corner of production's shape (the one that made MariaDB 10.6 choose
  * vote's round ∩ juror intersection before V100), a committed statistics snapshot scaled to
  * production's size, the juror's pages captured as they run, every vote statement EXPLAINed
  * rebound to the corner.
  *
  *  - **R1**: no index-merge intersection on vote;
  *  - **R3**: the named groups' constant-bound single-table vote access uses its expected key
  *    (test/resources/plans/rules.json);
  *  - **the canary**: with V100's four indexes back (and ANALYZE, the snapshot injected and read
  *    back) the canary groups show the intersection; so the check can see what V100 fixed.
  *
  * In its own forked JVM (build.sbt, `Test / testGrouping`) with its own container
  * ([[PlanDb]]), thrown away with it; in any other JVM it skips, saying why. MariaDB 10.6 only
  * (decision 9 = C): on another server it skips, saying why. In package `plans`, not `db`, so
  * `testOnly db.*` doesn't run it; run it alone with `testOnly plans.QueryPlanSpec`. The report goes to `target/query-plans/` ([[QueryPlanReport]]).
  *
  * Regenerate the snapshot after changing the seed: `Test/runMain plans.QueryPlanTool snapshot`.
  */
class QueryPlanSpec extends Specification with BeforeAfterAll {

  sequential

  private val skip = PlanDb.skipReason()
  private var db: Option[PlanDb] = None
  /** The run, once, in beforeAll: a failure (Docker, the seed, a read-back) is kept, never retried. */
  private var outcome: Either[Throwable, QueryPlanRun] = Left(new IllegalStateException("not run"))

  override def beforeAll(): Unit = {
    QueryPlanReport.clear()
    skip match {
      case Some(reason) => QueryPlanReport.skipped(reason)
      case None =>
        outcome =
          try {
            val started = PlanDb.start()
            db = Some(started)
            val run = new QueryPlanRun(PlanCheck.rules(), PlanDb.SupportedServer)
            run.run(started, StatsSnapshot.resource(PlanDb.SupportedServer))
            Right(run)
          } catch { case e: Throwable => Left(e) }
    }
  }

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

    // a read-back that differs stops the run (QueryPlanRun.inject): it fails here
    "run on a seeded database, the statistics snapshot injected and read back before each EXPLAIN batch" in
      check(_ => Nil)

    "R1: use no index-merge intersection on vote, every vote statement EXPLAINed and bound" in check(_.r1Failures)

    "R3: read vote by the expected key in the named groups" in check(_.r3Failures)

    "show the intersection with V100's four indexes back (the canary)" in check(_.canaryFailures)
  }
}
