package services.diagnostics

import db.scalikejdbc.{ContestJuryJdbc, DbTime}
import org.intracer.wmua.ContestJury
import scalikejdbc._
import services.jobs.{Job, JobJdbc, JobStatus}

import java.time.{Duration, Instant}

/** What happened lately across the contests (support diagnostics plan §3.4, `wlxjury-diag
  * recent`; D-1b), from the app's own rows: jobs created, finished or still running in the
  * window, and rounds whose status changed in it, grouped by contest, the worst first. Root's
  * only (`GET /api/diagnostics/recent`): it spans every contest, retired ones included, and the
  * jobs of no contest. The app reads no log file: the CLI adds the denials it finds in the log;
  * a later `support_event` (D-3) would add them here.
  */
object RecentActivity {

  /** The window's bounds in hours: an hour to 30 days (the app log's retention). */
  val MinHours = 1
  val MaxHours = 30 * 24
  val DefaultHours = 24

  /** The most jobs and round changes read; the window's newest first. */
  val Limit = 500

  /** A job of the window. `superseded`: a later job of the same kind and subject followed it. */
  final case class JobFact(job: Job, superseded: Boolean)

  /** A round whose status changed in the window: its id, contest, number, status and when. */
  final case class RoundChange(roundId: Long, contestId: Long, number: Long, status: String, changedAt: Instant)

  /** A contest's activity, and the worst of it ([[ContestHealth.Outcome]]): a failed job not
    * followed by a later one of its subject fails; a job queued or running for longer than
    * `slowMinutes` warns; a cancelled or superseded failed job is a note.
    */
  final case class ContestActivity(contest: Option[ContestJury], contestId: Long, jobs: Seq[JobFact],
      rounds: Seq[RoundChange], outcome: ContestHealth.Outcome)

  /** The window's activity: the contests' (the worst first, then the latest activity) and the jobs
    * of no contest.
    */
  final case class Report(hours: Int, contests: Seq[ContestActivity], otherJobs: Seq[JobFact])

  def report(hours: Int, slowMinutes: Int, now: Instant = Instant.now())(implicit
      session: DBSession = AutoSession): Report = {
    require(hours >= MinHours && hours <= MaxHours, s"hours out of range: $hours")
    val jobs = facts(JobJdbc.recent(hours, Limit))
    val rounds = roundChanges(hours)
    val ids = (jobs.flatMap(_.job.contestId) ++ rounds.map(_.contestId)).distinct
    val contests =
      if (ids.isEmpty) Map.empty[Long, ContestJury]
      else ContestJuryJdbc.findAllBy(sqls.in(ContestJuryJdbc.defaultAlias.id, ids)).flatMap(c => c.id.map(_ -> c)).toMap
    val byContest = ids.map { id =>
      val its = jobs.filter(_.job.contestId.contains(id))
      ContestActivity(contests.get(id), id, its, rounds.filter(_.contestId == id), outcome(its, slowMinutes, now))
    }
    def latest(a: ContestActivity) =
      (a.jobs.map(j => j.job.finishedAt.getOrElse(j.job.createdAt)) ++ a.rounds.map(_.changedAt)).maxOption
        .getOrElse(Instant.EPOCH)
    val ordered = byContest.sortBy(a => (-a.outcome.rank, -latest(a).toEpochMilli, a.contestId))
    Report(hours, ordered, jobs.filter(_.job.contestId.isEmpty))
  }

  /** The jobs, each marked superseded if a later job (a higher id) of the same kind and subject
    * is among them.
    */
  def facts(jobs: Seq[Job]): Seq[JobFact] = {
    def subject(j: Job) = (j.kind, j.contestId, j.subjectType, j.subjectId)
    val newest = jobs.groupBy(subject).view.mapValues(_.map(_.id).max).toMap
    jobs.map(j => JobFact(j, newest(subject(j)) != j.id))
  }

  def outcome(jobs: Seq[JobFact], slowMinutes: Int, now: Instant): ContestHealth.Outcome = {
    import ContestHealth.Outcome._
    def slow(j: Job) = j.isActive && Duration.between(j.createdAt, now).toMinutes >= slowMinutes
    if (jobs.exists(f => f.job.isFailed && !f.superseded)) Fail
    else if (jobs.exists(f => slow(f.job))) Warn
    else if (jobs.exists(f => f.job.isFailed || f.job.status == JobStatus.Cancelled)) Info
    else Pass
  }

  private def roundChanges(hours: Int)(implicit session: DBSession): Seq[RoundChange] =
    sql"""SELECT id, contest_id, number, status, ${DbTime.epoch("status_changed_at")} FROM round
          WHERE status_changed_at >= NOW(3) - INTERVAL $hours HOUR
          ORDER BY status_changed_at DESC, id DESC LIMIT $Limit"""
      .map(rs => RoundChange(rs.long("id"), rs.long("contest_id"), rs.long("number"),
        rs.stringOpt("status").getOrElse("unknown"), DbTime.instant(rs, "status_changed_at")))
      .list()
}
