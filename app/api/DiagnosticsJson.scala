package api

import play.api.i18n.Messages
import play.api.libs.json.{Json, OFormat}
import services.diagnostics.{ContestHealth, RecentActivity}
import services.jobs.Job
import services.series.{ContestReuse, ReuseReport, ReuseScanHandler}

import java.time.Instant

/** The diagnostics endpoints' JSON (support diagnostics plan §3.4; D-1b): a contest's health
  * report (`GET /api/contests/{id}/health`) and the recent activity (`GET /api/diagnostics/recent`),
  * for `wlxjury-diag`. Ids, counts, statuses, message keys with their numeric arguments, and each
  * key's text in English; no email. Names (contests, rounds' numbers only, a job
  * creator as the health page shows it to the caller) are the app's own text: the CLI strips
  * control characters before printing them. The reuse dry-run report (the contest series plan's
  * CY-0, `wlxjury-diag reuse`): the latest scan's totals and contests (`GET /api/diagnostics/reuse`)
  * and one contest's clusters (`GET /api/diagnostics/reuse/{id}`), ids and counts only: no juror
  * or runner by name, a round by its id, number and category filter.
  */
object DiagnosticsJson {

  /** An outcome's code: pass, info, warn, fail. */
  def outcomeCode(outcome: ContestHealth.Outcome): String = outcome match {
    case ContestHealth.Outcome.Pass => "pass"
    case ContestHealth.Outcome.Info => "info"
    case ContestHealth.Outcome.Warn => "warn"
    case ContestHealth.Outcome.Fail => "fail"
  }

  private def time(at: Instant): String = at.toString

  final case class ContestRef(id: Long, name: Option[String], year: Option[Int], country: Option[String],
      retired: Boolean)

  final case class Hint(outcome: String, key: String, args: List[Long], text: String)
  final case class Check(code: String, title: String, outcome: String, hints: List[Hint])

  /** An import job as the health page lists it; `creator` only as the page names them to the
    * caller (a member of the contest; anyone else for root only), `creatorMember` whether they
    * are a member.
    */
  final case class Import(jobId: Long, status: String, mode: String, source: String, seen: Long, inserted: Long,
      linked: Long, missing: Long, notInCountries: Long, createdAt: String, finishedAt: Option[String],
      error: Option[String], reference: Option[String], creator: Option[String], creatorMember: Option[Boolean])

  final case class Round(id: Long, number: Long, status: String, files: Long, jurors: Int, distributing: Boolean,
      flagged: List[String])

  final case class Health(contest: ContestRef, outcome: String, files: Long, recentActivity: Boolean,
      checks: List[Check], imports: List[Import], rounds: List[Round])

  final case class JobLine(id: Long, kind: String, status: String, createdAt: String, finishedAt: Option[String],
      superseded: Boolean, error: Option[String], reference: Option[String], text: Option[String])

  final case class RoundChange(id: Long, number: Long, status: String, changedAt: String)

  final case class ContestActivity(contest: ContestRef, outcome: String, jobs: List[JobLine],
      roundChanges: List[RoundChange])

  final case class Recent(hours: Int, contests: List[ContestActivity], otherJobs: List[JobLine])

  def contestRef(contest: org.intracer.wmua.ContestJury): ContestRef =
    ContestRef(contest.getId, Some(contest.name), Some(contest.year), Some(contest.country), contest.retired)

  def health(report: ContestHealth.Report)(implicit messages: Messages): Health =
    Health(
      contest = contestRef(report.contest),
      outcome = outcomeCode(report.outcome),
      files = report.files,
      recentActivity = report.recentActivity,
      checks = report.checks.toList.map { c =>
        Check(c.code, messages(c.titleKey), outcomeCode(c.outcome),
          c.hints.toList.map(h => Hint(outcomeCode(h.outcome), h.key, h.args.toList, h.message)))
      },
      imports = report.imports.toList.map { i =>
        val failure = i.job.failure
        Import(i.job.id, i.job.status, i.params.mode, i.params.source, i.state.seen, i.state.inserted, i.state.linked,
          i.state.missing, i.state.notInCountries, time(i.job.createdAt), i.job.finishedAt.map(time),
          failure.map(_.key), failure.flatMap(_.reference), i.creator.flatMap(_.name), i.creator.map(_.member))
      },
      rounds = report.rounds.toList.map { r =>
        Round(r.round.getId, r.round.number, r.round.lifecycle, r.files, r.jurors, r.round.distributing,
          r.flagged.toList)
      }
    )

  def jobLine(fact: RecentActivity.JobFact)(implicit messages: Messages): JobLine = {
    val j: Job = fact.job
    val failure = j.failure
    JobLine(j.id, j.kind, j.status, time(j.createdAt), j.finishedAt.map(time), fact.superseded, failure.map(_.key),
      failure.flatMap(_.reference), failure.map(_.message))
  }

  def recent(report: RecentActivity.Report)(implicit messages: Messages): Recent =
    Recent(
      hours = report.hours,
      contests = report.contests.toList.map { a =>
        ContestActivity(
          a.contest.map(contestRef).getOrElse(ContestRef(a.contestId, None, None, None, retired = false)),
          outcomeCode(a.outcome),
          a.jobs.toList.map(jobLine),
          a.rounds.toList.map(r => RoundChange(r.roundId, r.number, r.status, time(r.changedAt)))
        )
      },
      otherJobs = report.otherJobs.toList.map(jobLine)
    )

  implicit val contestRefFormat: OFormat[ContestRef] = Json.format[ContestRef]
  implicit val hintFormat: OFormat[Hint] = Json.format[Hint]
  implicit val checkFormat: OFormat[Check] = Json.format[Check]
  implicit val importFormat: OFormat[Import] = Json.format[Import]
  implicit val roundFormat: OFormat[Round] = Json.format[Round]
  implicit val healthFormat: OFormat[Health] = Json.format[Health]
  implicit val jobLineFormat: OFormat[JobLine] = Json.format[JobLine]
  implicit val roundChangeFormat: OFormat[RoundChange] = Json.format[RoundChange]
  implicit val contestActivityFormat: OFormat[ContestActivity] = Json.format[ContestActivity]
  implicit val recentFormat: OFormat[Recent] = Json.format[Recent]

  // ── the reuse dry-run report (contest series plan §4.3, CY-0) ─────────────

  /** A scan's progress: its pass (`eras`, then `scan`), and the contests read in it of `total`. */
  final case class ReuseScan(jobId: Long, status: String, phase: String, done: Long, total: Long, createdAt: String,
      finishedAt: Option[String])

  final case class ReuseTotals(scanned: Int, clean: Int, verdicts: Map[String, Int], r4Hosts: Int, kinds: Map[String, Int],
      actions: Map[String, Int], mixed: Int, empty: Int, moves: Int, activeToMove: Int, filesToLink: Long, jurorsToAdd: Int,
      onlyMovedJurors: Int, unusedInSource: Long, rowsToMove: Long)

  /** The latest scan (its progress; none yet: all empty), and the latest finished scan's id
    * (`resultsOf`), totals, and the contests it reports (the clean ones only counted).
    */
  final case class Reuse(scan: Option[ReuseScan], resultsOf: Option[Long], totals: Option[ReuseTotals],
      contests: List[ReuseReport.Summary])

  final case class ReuseRound(id: Long, number: Long, status: String, active: Boolean, category: Option[String], files: Long,
      jurors: Int, votes: Long, comments: Long, jobs: Long)
  final case class ReuseNamed(identity: String, files: Long)
  final case class ReuseRoundSource(round: Long, source: String, identity: String)
  final case class ReuseShared(cluster: Int, jurors: Int)
  final case class ReuseSignals(s1Year: Option[Int], s2: List[ReuseNamed], s2ManyCountries: Boolean, s3Year: Option[Int],
      s4From: Option[Int], s4To: Option[Int], s5: List[String], s5r: List[ReuseRoundSource], s6: List[ReuseShared])
  final case class ReuseBuckets(matching: Long, foreign: Long, unclassified: Long)
  final case class ReuseTarget(seriesId: Option[Long], seriesName: String, seriesType: String, place: String, year: Int,
      contests: List[Long])

  /** A cluster's jurors by what a move would do with them (CA11's recommendation): added as Juror
    * in the target, or skipped as deleted, protected, of a conflicting role there or Juror there
    * already; how many have no membership in the host, and how many judge other clusters too.
    */
  final case class ReuseJurors(total: Int, toAdd: Int, deleted: Int, protectedAccounts: Int, conflict: Int, already: Int,
      notHostMembers: Int, inOtherClusters: Int)

  /** Who ran the cluster's rounds (CA23): how many, and how many are Admins of the host. */
  final case class ReuseRunners(total: Int, hostAdmins: Int)

  final case class ReuseCluster(index: Int, identity: String, kind: String, action: String, mixed: Boolean, empty: Boolean,
      disagreements: List[String], activeRounds: List[Long], target: Option[ReuseTarget], buckets: ReuseBuckets,
      signals: ReuseSignals, jurors: ReuseJurors, runners: ReuseRunners, votes: Long, comments: Long, jobs: Long,
      rounds: List[ReuseRound])

  final case class ReuseContest(contest: ContestRef, verdict: String, identity: String, national: Boolean, r4Host: Boolean,
      signs: List[String], eras: Boolean, moves: Int, jurorsToAdd: Int, onlyMovedJurors: Int, unusedInSource: Long,
      clusters: List[ReuseCluster])

  def reuse(scan: Option[Job], done: Option[Job]): Reuse = {
    val progress = scan.map { job =>
      val st = ReuseScanHandler.stateOf(job)
      val (_, read, total) = ReuseScanHandler.pass(st)
      ReuseScan(job.id, job.status, st.phase, read, total, time(job.createdAt), job.finishedAt.map(time))
    }
    done match {
    case None => Reuse(progress, None, None, Nil)
    case Some(job) =>
      val st = ReuseScanHandler.stateOf(job)
      val cs = st.contests
      def sum(f: ReuseReport.Summary => Map[String, Int]) = cs.flatMap(f(_).toSeq).groupMapReduce(_._1)(_._2)(_ + _)
      val totals = ReuseTotals(st.scanned, st.clean, cs.groupMapReduce(_.verdict)(_ => 1)(_ + _), cs.count(_.r4Host),
        sum(_.kinds), sum(_.actions), cs.map(_.mixed).sum, cs.map(_.empty).sum, cs.map(_.moves).sum,
        cs.map(_.activeToMove.size).sum, cs.map(_.filesToLink).sum, cs.map(_.jurorsToAdd).sum, cs.map(_.onlyMovedJurors).sum,
        cs.map(_.unusedInSource).sum, cs.map(_.rowsToMove).sum)
      Reuse(progress, Some(job.id), Some(totals), cs.toList)
    }
  }

  def reuseContest(r: ReuseReport.Report): ReuseContest =
    ReuseContest(
      contest = contestRef(r.contest),
      verdict = r.verdict,
      identity = r.identity.describe,
      national = r.national,
      r4Host = r.r4Host,
      signs = r.signs.map(ContestReuse.kindKey).distinct.map(_.stripPrefix("series.reuse.kind.")).toList,
      eras = r.eras,
      moves = r.moveCount,
      jurorsToAdd = r.jurorsToAdd,
      onlyMovedJurors = r.onlyMovedJurors,
      unusedInSource = r.unusedInSource,
      clusters = r.clusters.toList.map { c =>
        val sig = c.signals
        ReuseCluster(
          index = c.index, identity = c.identity.describe, kind = c.kind, action = c.action, mixed = c.mixed, empty = c.empty,
          disagreements = c.disagreements.toList.map(d => s"${d.signal}:${d.part}"),
          activeRounds = c.activeRounds.toList,
          target = c.target.map(t => ReuseTarget(t.seriesId, t.seriesName, t.seriesType.code, t.place.key, t.year, t.contests.toList)),
          buckets = ReuseBuckets(c.buckets.matching, c.buckets.foreign, c.buckets.unclassified),
          signals = ReuseSignals(sig.s1Year, sig.s2.toList.map { case (id, n) => ReuseNamed(id.describe, n) }, sig.s2ManyCountries,
            sig.s3Year, sig.s4Years.map(_._1), sig.s4Years.map(_._2), sig.s5.toList,
            sig.s5r.toList.map { case (round, source, id) => ReuseRoundSource(round, source, id.describe) },
            sig.s6.toList.map { case (i, n) => ReuseShared(i, n) }),
          jurors = ReuseJurors(c.jurors.size, c.jurors.count(_.skip.isEmpty), c.jurors.count(_.skip.contains("deleted")),
            c.jurors.count(_.skip.contains("protected")), c.jurors.count(_.skip.contains("conflict")),
            c.jurors.count(_.skip.contains("already")), c.jurors.count(_.hostRole.isEmpty), c.jurors.count(_.otherClusters.nonEmpty)),
          runners = ReuseRunners(c.runners.size, c.runners.count(_.hostRole.contains(db.scalikejdbc.User.ADMIN_ROLE))),
          votes = c.votes, comments = c.comments, jobs = c.jobs,
          rounds = c.rounds.toList.map(l => ReuseRound(l.id, l.number, l.status, l.active, l.category, l.files, l.jurors, l.votes,
            l.comments, l.jobs))
        )
      }
    )

  implicit val reuseScanFormat: OFormat[ReuseScan] = Json.format[ReuseScan]
  implicit val reuseTotalsFormat: OFormat[ReuseTotals] = Json.format[ReuseTotals]
  implicit val reuseFormat: OFormat[Reuse] = Json.format[Reuse]
  implicit val reuseRoundFormat: OFormat[ReuseRound] = Json.format[ReuseRound]
  implicit val reuseNamedFormat: OFormat[ReuseNamed] = Json.format[ReuseNamed]
  implicit val reuseRoundSourceFormat: OFormat[ReuseRoundSource] = Json.format[ReuseRoundSource]
  implicit val reuseSharedFormat: OFormat[ReuseShared] = Json.format[ReuseShared]
  implicit val reuseSignalsFormat: OFormat[ReuseSignals] = Json.format[ReuseSignals]
  implicit val reuseBucketsFormat: OFormat[ReuseBuckets] = Json.format[ReuseBuckets]
  implicit val reuseTargetFormat: OFormat[ReuseTarget] = Json.format[ReuseTarget]
  implicit val reuseJurorsFormat: OFormat[ReuseJurors] = Json.format[ReuseJurors]
  implicit val reuseRunnersFormat: OFormat[ReuseRunners] = Json.format[ReuseRunners]
  implicit val reuseClusterFormat: OFormat[ReuseCluster] = Json.format[ReuseCluster]
  implicit val reuseContestFormat: OFormat[ReuseContest] = Json.format[ReuseContest]
}
