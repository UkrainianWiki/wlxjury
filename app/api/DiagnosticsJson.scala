package api

import play.api.i18n.Messages
import play.api.libs.json.{Json, OFormat}
import services.diagnostics.{ContestHealth, RecentActivity}
import services.jobs.Job

import java.time.Instant

/** The diagnostics endpoints' JSON (support diagnostics plan §3.4; D-1b): a contest's health
  * report (`GET /api/contests/{id}/health`) and the recent activity (`GET /api/diagnostics/recent`),
  * for `wlxjury-diag`. Ids, counts, statuses, message keys with their numeric arguments, and each
  * key's text in English; no email. Names (contests, rounds' numbers only, a job
  * creator as the health page shows it to the caller) are the app's own text: the CLI strips
  * control characters before printing them. A later report (the contest series plan's CY-0,
  * `wlxjury-diag reuse`) adds its own classes here and an endpoint under `/api/diagnostics`.
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
}
