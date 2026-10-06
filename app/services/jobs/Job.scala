package services.jobs

import play.api.libs.json.{JsObject, Json}
import scalikejdbc._

import java.time.Instant

/** A background job (table `job`, V60): long work done off the HTTP request threads in
  * small steps, each its own short transaction, by [[JobScheduler]]. Generic over kinds;
  * a [[JobHandler]] per kind does the work.
  *
  * @param params kind-specific inputs, fixed when the job is created
  * @param state  kind-specific resume cursor, written with each step's work
  */
final case class Job(
    id: Long,
    kind: String,
    contestId: Option[Long],
    subjectType: Option[String],
    subjectId: Option[Long],
    dedupeKey: Option[String],
    status: String,
    params: JsObject,
    state: JsObject,
    progressDone: Option[Long],
    progressTotal: Option[Long],
    progressUnit: Option[String],
    error: Option[String],
    attempts: Int,
    createdBy: Option[Long],
    owner: Option[String],
    createdAt: Instant,
    startedAt: Option[Instant],
    updatedAt: Option[Instant],
    finishedAt: Option[Instant],
    heartbeatAt: Option[Instant]
) {
  def isActive: Boolean = JobStatus.active.contains(status)
  def isFailed: Boolean = status == JobStatus.Failed
  def isDone: Boolean = status == JobStatus.Done
}

object JobStatus {
  val Queued = "queued"
  val Running = "running"
  val Failed = "failed"
  val Done = "done"
  val Cancelled = "cancelled"

  /** The statuses in which a job holds its subject (the unique active_dedupe_key). */
  val active: Set[String] = Set(Queued, Running)
}

/** What a step reports about the job's progress, e.g. 40,000 of 115,101 images. */
final case class Progress(done: Option[Long] = None, total: Option[Long] = None, unit: Option[String] = None)

/** A job to create: see [[JobScheduler.enqueue]]. */
final case class NewJob(
    kind: String,
    contestId: Option[Long] = None,
    subjectType: Option[String] = None,
    subjectId: Option[Long] = None,
    dedupeKey: Option[String] = None,
    params: JsObject = Json.obj(),
    state: JsObject = Json.obj(),
    progress: Progress = Progress(),
    createdBy: Option[Long] = None
)

/** The `job` table. Timestamps compared with the lease come from the database clock
  * (NOW(3)), so app instances with skewed clocks agree on whether a lease has expired.
  */
object JobJdbc {

  private val columns =
    sqls"""id, kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state,
           progress_done, progress_total, progress_unit, error, attempts, created_by, owner,
           created_at, started_at, updated_at, finished_at, heartbeat_at"""

  private def instant(rs: WrappedResultSet, col: String): Option[Instant] =
    rs.timestampOpt(col).map(_.toInstant)

  def extract(rs: WrappedResultSet): Job =
    Job(
      id = rs.long("id"),
      kind = rs.string("kind"),
      contestId = rs.longOpt("contest_id"),
      subjectType = rs.stringOpt("subject_type"),
      subjectId = rs.longOpt("subject_id"),
      dedupeKey = rs.stringOpt("dedupe_key"),
      status = rs.string("status"),
      params = Json.parse(rs.string("params")).as[JsObject],
      state = Json.parse(rs.string("state")).as[JsObject],
      progressDone = rs.longOpt("progress_done"),
      progressTotal = rs.longOpt("progress_total"),
      progressUnit = rs.stringOpt("progress_unit"),
      error = rs.stringOpt("error"),
      attempts = rs.int("attempts"),
      createdBy = rs.longOpt("created_by"),
      owner = rs.stringOpt("owner"),
      createdAt = rs.timestamp("created_at").toInstant,
      startedAt = instant(rs, "started_at"),
      updatedAt = instant(rs, "updated_at"),
      finishedAt = instant(rs, "finished_at"),
      heartbeatAt = instant(rs, "heartbeat_at")
    )

  /** Inserts the job, `queued`, or `running` for `owner` when it is claimed at once
    * (inline mode). Fails with a duplicate-key error on job_active_dedupe_uidx when its
    * subject already has an active job: see [[isDuplicateActive]].
    */
  def insert(job: NewJob, owner: Option[String])(implicit session: DBSession): Long = {
    val status = if (owner.isDefined) JobStatus.Running else JobStatus.Queued
    sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params,
                           state, progress_done, progress_total, progress_unit, created_by, owner,
                           started_at, heartbeat_at, attempts, updated_at)
          VALUES (${job.kind}, ${job.contestId}, ${job.subjectType}, ${job.subjectId}, ${job.dedupeKey},
                  $status, ${Json.stringify(job.params)}, ${Json.stringify(job.state)},
                  ${job.progress.done}, ${job.progress.total}, ${job.progress.unit}, ${job.createdBy},
                  $owner, IF($owner IS NULL, NULL, NOW(3)), IF($owner IS NULL, NULL, NOW(3)),
                  ${if (owner.isDefined) 1 else 0}, NOW(3))"""
      .updateAndReturnGeneratedKey()
  }

  /** The insert or status change failed because the job's subject already has an
    * active (queued or running) job.
    */
  def isDuplicateActive(e: Throwable): Boolean =
    e.isInstanceOf[java.sql.SQLIntegrityConstraintViolationException] &&
      Option(e.getMessage).exists(_.contains("job_active_dedupe_uidx"))

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[Job] =
    sql"SELECT $columns FROM job WHERE id = $id".map(extract).single()

  /** The job's row, locked until the end of the caller's transaction: every step, cancel
    * and retry goes through it, so they are serialized per job.
    */
  def lock(id: Long)(implicit session: DBSession): Option[Job] =
    sql"SELECT $columns FROM job WHERE id = $id FOR UPDATE".map(extract).single()

  def activeByDedupeKey(key: String)(implicit session: DBSession = AutoSession): Option[Job] =
    sql"SELECT $columns FROM job WHERE active_dedupe_key = $key".map(extract).single()

  /** The latest job of each subject, e.g. of each round of a contest. */
  def latestBySubjects(kind: String, subjectType: String, subjectIds: Seq[Long])(implicit
      session: DBSession = AutoSession
  ): Map[Long, Job] =
    if (subjectIds.isEmpty) Map.empty
    else
      sql"""SELECT $columns FROM job j WHERE j.id IN (
              SELECT MAX(id) FROM job WHERE kind = $kind AND subject_type = $subjectType
                AND subject_id IN ($subjectIds) GROUP BY subject_id)"""
        .map(extract)
        .list()
        .flatMap(j => j.subjectId.map(_ -> j))
        .toMap

  def activeOfKind(kind: String)(implicit session: DBSession = AutoSession): Seq[Job] =
    sql"SELECT $columns FROM job WHERE status IN (${JobStatus.active.toSeq}) AND kind = $kind ORDER BY id"
      .map(extract)
      .list()

  /** Jobs this instance may claim: queued ones, and running ones whose lease (heartbeat)
    * has expired, e.g. after the instance running them crashed.
    */
  def claimable(kinds: Seq[String], leaseSeconds: Long, limit: Int)(implicit
      session: DBSession = AutoSession
  ): Seq[Long] =
    if (kinds.isEmpty) Nil
    else
      sql"""SELECT id FROM job WHERE kind IN ($kinds) AND (status = ${JobStatus.Queued}
              OR (status = ${JobStatus.Running}
                  AND (heartbeat_at IS NULL OR heartbeat_at < NOW(3) - INTERVAL $leaseSeconds SECOND)))
            ORDER BY id LIMIT $limit"""
        .map(_.long(1))
        .list()

  /** Takes the job for `owner` if it is still claimable: true if this call got it. */
  def claim(id: Long, owner: String, leaseSeconds: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""UPDATE job SET status = ${JobStatus.Running}, owner = $owner, heartbeat_at = NOW(3),
                         started_at = COALESCE(started_at, NOW(3)), attempts = attempts + 1, updated_at = NOW(3)
          WHERE id = $id AND (status = ${JobStatus.Queued}
            OR (status = ${JobStatus.Running}
                AND (heartbeat_at IS NULL OR heartbeat_at < NOW(3) - INTERVAL $leaseSeconds SECOND)))"""
      .update() == 1

  /** Renews the lease of every job `owner` runs. */
  def heartbeat(owner: String)(implicit session: DBSession = AutoSession): Int =
    sql"""UPDATE job SET heartbeat_at = NOW(3)
          WHERE owner = $owner AND status = ${JobStatus.Running}"""
      .update()

  /** Hands `owner`'s running jobs back to the queue (a graceful shutdown): another
    * instance, or this one restarted, takes them at once instead of after the lease.
    */
  def requeueOwned(owner: String)(implicit session: DBSession = AutoSession): Int =
    sql"""UPDATE job SET status = ${JobStatus.Queued}, owner = NULL, updated_at = NOW(3)
          WHERE owner = $owner AND status = ${JobStatus.Running}"""
      .update()

  /** Writes a step's outcome: the new state and progress, and the status. */
  def update(
      id: Long,
      status: String,
      state: JsObject,
      progress: Progress,
      error: Option[String]
  )(implicit session: DBSession): Unit = {
    val terminal = !JobStatus.active.contains(status)
    sql"""UPDATE job SET status = $status, state = ${Json.stringify(state)},
                         progress_done = ${progress.done}, progress_total = ${progress.total},
                         progress_unit = COALESCE(${progress.unit}, progress_unit),
                         error = $error, updated_at = NOW(3), heartbeat_at = NOW(3),
                         finished_at = IF($terminal, NOW(3), NULL),
                         owner = IF($terminal, NULL, owner)
          WHERE id = $id"""
      .update()
  }

  /** A failed job queued again (Retry): `owner` claims it at once in inline mode. */
  def requeue(id: Long, state: JsObject, owner: Option[String])(implicit session: DBSession): Unit = {
    val status = if (owner.isDefined) JobStatus.Running else JobStatus.Queued
    sql"""UPDATE job SET status = $status, state = ${Json.stringify(state)}, error = NULL,
                         finished_at = NULL, owner = $owner, updated_at = NOW(3),
                         heartbeat_at = IF($owner IS NULL, NULL, NOW(3)),
                         attempts = attempts + IF($owner IS NULL, 0, 1)
          WHERE id = $id"""
      .update()
  }

  /** How many queued jobs are ahead of this one. */
  def queuePosition(id: Long)(implicit session: DBSession = AutoSession): Int =
    sql"SELECT COUNT(*) FROM job WHERE status = ${JobStatus.Queued} AND id < $id"
      .map(_.int(1))
      .single()
      .getOrElse(0)
}
