package services.jobs

import play.api.{Configuration, Logging}
import scalikejdbc.DB

import services.jobs.FetchingJobHandler.Step

import java.util.concurrent._
import java.util.concurrent.atomic.AtomicInteger
import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Runs [[Job]]s in the background: one step at a time, taking turns, on a small pool of
  * its own threads, separate from Play's dispatchers, so jobs never hold a request thread.
  *
  *  - '''Claiming and the lease.''' Every `pollInterval` the scheduler claims queued jobs of
  *    the kinds it has handlers for, and running jobs whose heartbeat is older than the
  *    `lease` (their instance died). It renews the heartbeat of the jobs it holds every
  *    `heartbeatInterval`; each step renews it too. On a graceful stop it hands its running
  *    jobs back to the queue, so the next instance takes them at once; after a crash they
  *    are taken over when the lease expires. So is a job the scheduler dropped without
  *    recording why (a step or fetch that crashed on a database error): it holds it no
  *    more, so its heartbeat stops. Each step checks, under the job's row lock,
  *    that this instance still owns the job, so two instances never run the same job.
  *  - '''Turns.''' A worker runs one step (one chunk) of one job, then the job goes back in
  *    line. The next step goes to the contest that has had the least step time, and
  *    within it to the job that has had the least ("virtual time", as in a fair-share
  *    scheduler): a job whose steps are cheap gets more of them, so a small job finishes
  *    in seconds while a big one runs, and one contest's many jobs don't starve another
  *    contest's. A job or contest that arrives starts level with the least served one
  *    present, so it neither waits behind the others nor monopolizes the workers.
  *  - '''Limits.''' At most `workers` steps run at once overall, and at most the kind's
  *    concurrency limit of one kind (e.g. 2 distributions, 1 Commons import).
  *  - '''The fetch lane.''' A handler that calls an external API before each step
  *    (a [[FetchingJobHandler]], e.g. a page of Commons files) has it called on
  *    a pool of its own (`fetchWorkers`), within the kind's limit; the job takes its turn
  *    for a step once the fetch is done. A slow API thus never holds one of the few
  *    workers that write to the database, and only step time counts in the turns.
  *  - '''Cancel and retry''' go through the job's row lock: a cancel waits for the
  *    running step (a chunk, short) to commit, then the job stops.
  *
  * In `inline` mode (tests, scripts) there are no threads: a job is claimed when it is
  * created and run to its end by the thread that calls [[started]].
  */
class JobScheduler(handlerList: Seq[JobHandler], val settings: JobScheduler.Settings) extends Logging {

  import JobScheduler._

  /** This app instance, as the owner of the jobs it runs. */
  val instanceId: String = {
    val host = scala.util.Try(java.net.InetAddress.getLocalHost.getHostName).getOrElse("host")
    s"$host-${ProcessHandle.current().pid()}-${java.util.UUID.randomUUID().toString.take(8)}".take(64)
  }

  private val handlers: Map[String, JobHandler] = handlerList.map(h => h.kind -> h).toMap

  def handler(kind: String): Option[JobHandler] = handlers.get(kind)

  private def inline: Boolean = settings.mode == Mode.Inline

  // ── background machinery ───────────────────────────────────────────────────

  private final class Held(val id: Long, val kind: String, val contestId: Option[Long], var vtime: Long) {
    var inFlight = false
    /** On the fetch lane now. */
    var fetching = false
    /** What a fetch got, as the step that writes it: the step may run. */
    var fetched: Option[Step] = None
  }

  private val lock = new Object
  private val held = mutable.LinkedHashMap.empty[Long, Held]
  /** Step time (ns) per contest with held jobs: the contests' virtual time. */
  private val contestTime = mutable.Map.empty[Option[Long], Long]
  private val inFlightByKind = mutable.Map.empty[String, Int].withDefaultValue(0)
  private var inFlightTotal = 0
  private val fetchingByKind = mutable.Map.empty[String, Int].withDefaultValue(0)
  private var fetchingTotal = 0

  private def external(h: Held): Boolean = handlers.get(h.kind).exists(_.isInstanceOf[FetchingJobHandler[_]])

  /** Adds a claimed job, level with the least served job, and its contest level with the
    * least served contest (caller holds `lock`).
    */
  private def hold(id: Long, kind: String, contestId: Option[Long]): Unit = {
    val start = if (held.isEmpty) 0L else held.values.map(_.vtime).min
    if (!contestTime.contains(contestId))
      contestTime(contestId) = if (contestTime.isEmpty) 0L else contestTime.values.min
    held.put(id, new Held(id, kind, contestId, start))
  }

  private def release(h: Held): Unit = {
    held.remove(h.id)
    if (!held.values.exists(_.contestId == h.contestId)) contestTime.remove(h.contestId)
  }
  @volatile private var stopping = false
  @volatile private var lastPollWarning = 0L

  private var workers: ExecutorService = _
  private var fetchers: ExecutorService = _
  private var timer: ScheduledExecutorService = _

  def start(): Unit =
    if (!inline && timer == null) {
      workers = Executors.newFixedThreadPool(settings.workers, threadFactory("job-worker"))
      fetchers = Executors.newFixedThreadPool(settings.fetchWorkers, threadFactory("job-fetcher"))
      timer = Executors.newSingleThreadScheduledExecutor(threadFactory("job-scheduler"))
      val poll = settings.pollInterval.toMillis
      timer.scheduleWithFixedDelay(() => this.poll(), poll, poll, TimeUnit.MILLISECONDS)
      val hb = settings.heartbeatInterval.toMillis
      timer.scheduleWithFixedDelay(() => heartbeat(), hb, hb, TimeUnit.MILLISECONDS)
      logger.info(
        s"Job scheduler $instanceId: ${settings.workers} workers, ${settings.fetchWorkers} fetchers, " +
          s"limits ${settings.kindLimits}, " +
          s"kinds ${handlers.keys.mkString(", ")}"
      )
    }

  /** Stops taking steps, lets the running ones finish, and hands this instance's running
    * jobs back to the queue.
    */
  def stop(): Unit =
    if (timer != null) {
      stopping = true
      timer.shutdownNow()
      fetchers.shutdownNow() // an interrupted fetch is redone by whoever resumes the job
      workers.shutdown()
      if (!workers.awaitTermination(settings.lease.toSeconds / 2 max 1, TimeUnit.SECONDS))
        logger.warn(s"Job scheduler $instanceId: steps still running at shutdown")
      try {
        val n = JobJdbc.requeueOwned(instanceId)
        if (n > 0) logger.info(s"Job scheduler $instanceId: $n running jobs handed back to the queue")
      } catch { case NonFatal(e) => logger.warn(s"Job scheduler $instanceId: could not requeue its jobs", e) }
      timer = null
    }

  /** Claims what is claimable now instead of at the next poll (a job was just created). */
  def wake(): Unit = if (!inline && timer != null && !stopping) timer.execute(() => poll())

  private[jobs] def poll(): Unit =
    if (!stopping && tableReady())
      try {
        val ids = JobJdbc.claimable(handlers.keys.toSeq, settings.lease.toSeconds, limit = 200)
        val known = lock.synchronized(held.keySet.toSet)
        ids.filterNot(known.contains).foreach { id =>
          if (JobJdbc.claim(id, instanceId, settings.lease.toSeconds))
            JobJdbc.findById(id).foreach { j =>
              lock.synchronized(hold(id, j.kind, j.contestId))
            }
        }
        dispatch()
      } catch {
        // e.g. the job table not migrated yet (Flyway's apply page): once a minute, not every poll
        case NonFatal(e) =>
          val now = System.currentTimeMillis()
          if (now - lastPollWarning > 60000) {
            lastPollWarning = now
            logger.warn(s"Job scheduler $instanceId: poll failed: ${describe(e)}")
          }
      }

  @volatile private var tableExists = false

  /** The job table exists: until its migration has run (Flyway's apply page), the
    * scheduler waits instead of failing a query every poll.
    */
  private def tableReady(): Boolean =
    tableExists || {
      tableExists =
        try JobJdbc.tableExists()
        catch { case NonFatal(_) => false }
      tableExists
    }

  private def heartbeat(): Unit =
    try { if (!stopping && tableReady()) JobJdbc.heartbeat(instanceId, heldJobs.toSeq) }
    catch { case NonFatal(e) => logger.warn(s"Job scheduler $instanceId: heartbeat failed", e) }

  private def limit(kind: String): Int = settings.kindLimits.getOrElse(kind, settings.defaultKindLimit)

  private def dispatch(): Unit = lock.synchronized {
    dispatchFetches()
    var more = true
    while (more && !stopping && inFlightTotal < settings.workers) {
      val ready = held.values.filter(h =>
        !h.inFlight && !h.fetching && (h.fetched.isDefined || !external(h)) && inFlightByKind(h.kind) < limit(h.kind))
      if (ready.isEmpty) more = false
      else {
        val h = ready.minBy(h => (contestTime.getOrElse(h.contestId, 0L), h.vtime, h.id))
        h.inFlight = true
        inFlightByKind(h.kind) += 1
        inFlightTotal += 1
        try workers.execute(() => work(h))
        catch {
          case _: RejectedExecutionException =>
            h.inFlight = false; inFlightByKind(h.kind) -= 1; inFlightTotal -= 1; more = false
        }
      }
    }
  }

  /** Sends the jobs that need a fetch before their next step to the fetch lane, in the
    * same order as steps, within the kinds' limits (caller holds `lock`).
    */
  private def dispatchFetches(): Unit = {
    var more = true
    while (more && !stopping && fetchingTotal < settings.fetchWorkers) {
      val ready = held.values.filter(h =>
        external(h) && !h.inFlight && !h.fetching && h.fetched.isEmpty && fetchingByKind(h.kind) < limit(h.kind))
      if (ready.isEmpty) more = false
      else {
        val h = ready.minBy(h => (contestTime.getOrElse(h.contestId, 0L), h.vtime, h.id))
        h.fetching = true
        fetchingByKind(h.kind) += 1
        fetchingTotal += 1
        try fetchers.execute(() => fetch(h))
        catch {
          case _: RejectedExecutionException =>
            h.fetching = false; fetchingByKind(h.kind) -= 1; fetchingTotal -= 1; more = false
        }
      }
    }
  }

  private def fetch(h: Held): Unit = {
    var step: Option[Step] = None
    try step = prepareStep(h.id)
    catch { case NonFatal(e) => logger.error(s"Job ${h.id}: fetch crashed", e) }
    finally
      lock.synchronized {
        h.fetching = false
        fetchingByKind(h.kind) -= 1
        fetchingTotal -= 1
        if (step.isDefined) h.fetched = step else release(h)
      }
    if (!stopping) dispatch()
  }

  private def work(h: Held): Unit = {
    val started = System.nanoTime()
    val again =
      try runStep(h.id, h.fetched)
      catch { case NonFatal(e) => logger.error(s"Job ${h.id}: step crashed", e); false }
    val took = System.nanoTime() - started
    lock.synchronized {
      h.inFlight = false
      h.fetched = None
      h.vtime += took
      contestTime(h.contestId) = contestTime.getOrElse(h.contestId, 0L) + took
      inFlightByKind(h.kind) -= 1
      inFlightTotal -= 1
      if (!again) release(h)
    }
    if (!stopping) dispatch()
  }

  /** The jobs this instance holds (claimed and not finished): for tests and diagnostics. */
  def heldJobs: Set[Long] = lock.synchronized(held.keySet.toSet)

  // ── the generic job API ────────────────────────────────────────────────────

  /** Creates a job in the caller's transaction, or returns its subject's active job
    * (Left) if it has one: the unique active dedupe key decides, also between instances.
    * Call [[started]] once the transaction has committed.
    */
  def enqueue(job: NewJob)(implicit session: scalikejdbc.DBSession): Either[Job, Job] = {
    require(handlers.contains(job.kind), s"no handler for job kind ${job.kind}")
    try {
      val id = JobJdbc.insert(job, owner = Option.when(inline)(instanceId))
      Right(JobJdbc.findById(id).get)
    } catch {
      case e: Throwable if JobJdbc.isDuplicateActive(e) =>
        // a locking read: the caller's transaction may have read before the active job committed
        Left(job.dedupeKey.flatMap(JobJdbc.lockActiveByDedupeKey).getOrElse(throw e))
    }
  }

  /** A job was created or queued again and its transaction committed: in the background
    * the scheduler takes it at once; inline it runs to its end now.
    */
  def started(jobId: Long): Unit = if (inline) runInline(jobId) else wake()

  /** Cancels a queued, running or failed job; waits for a running step to commit first.
    * @return the job afterwards, None if it doesn't exist
    */
  def cancel(jobId: Long): Option[Job] = {
    val ctx = DB.localTx { implicit s =>
      JobJdbc.lock(jobId).filter(j => j.isActive || j.isFailed).map { j =>
        val ctx = new StepContext(s)
        val state = handlers.get(j.kind).fold(j.state)(_.onCancelled(j, ctx))
        JobJdbc.update(jobId, JobStatus.Cancelled, state, progressOf(j), j.error)
        ctx
      }
    }
    ctx.foreach(runAfterCommit)
    handlers.values.foreach(_.forget(jobId))
    JobJdbc.findById(jobId)
  }

  /** Queues a failed job again; it resumes from its state.
    * @return the job, or why it can't be retried
    */
  def retry(jobId: Long): Either[String, Job] = {
    val result =
      try
        DB.localTx { implicit s =>
          JobJdbc.lock(jobId) match {
            case None                    => Left("no such job")
            case Some(j) if !j.isFailed  => Left(s"the job is ${j.status}, not failed")
            case Some(j) =>
              handlers.get(j.kind) match {
                case None => Left(s"no handler for job kind ${j.kind}")
                case Some(h) =>
                  val ctx = new StepContext(s)
                  JobJdbc.requeue(jobId, h.onRetry(j, ctx), Option.when(inline)(instanceId))
                  Right(ctx)
              }
          }
        }
      catch {
        case e: Throwable if JobJdbc.isDuplicateActive(e) =>
          Left("another job for the same subject is running")
      }
    result.map { ctx =>
      runAfterCommit(ctx)
      started(jobId)
      JobJdbc.findById(jobId).get
    }
  }

  /** Waits until the job is no longer queued or running (scripts and tests). */
  def await(jobId: Long, timeout: FiniteDuration = 30.minutes): Job = {
    val deadline = timeout.fromNow
    var job = JobJdbc.findById(jobId).getOrElse(throw new NoSuchElementException(s"Job $jobId not found"))
    while (job.isActive && deadline.hasTimeLeft()) {
      Thread.sleep(50)
      job = JobJdbc.findById(jobId).get
    }
    job
  }

  // ── running steps ──────────────────────────────────────────────────────────

  private def runInline(jobId: Long): Unit = {
    var again = true
    while (again) again = runStep(jobId)
  }

  /** Prepares the next step of a job this instance owns, outside any transaction: the
    * handler's [[JobHandler.prepare]], or a [[FetchingJobHandler]]'s fetch. A failure
    * fails the job, unless the scheduler is stopping (an interrupted fetch): whoever
    * resumes the job prepares it again.
    * @return the step to run, None if it may not run
    */
  private[jobs] def prepareStep(jobId: Long): Option[Step] = {
    val job = JobJdbc.findById(jobId) match {
      case Some(j) if j.status == JobStatus.Running && j.owner.contains(instanceId) => j
      case _ => handlers.values.foreach(_.forget(jobId)); return None
    }
    handlers.get(job.kind) match {
      case None => None
      case Some(h) =>
        try
          h match {
            case f: FetchingJobHandler[_] => Some(f.fetchStep(job))
            case _                        => h.prepare(job); Some(h.step)
          }
        catch {
          case e: Throwable if stopping && (NonFatal(e) || e.isInstanceOf[InterruptedException]) =>
            h.forget(jobId)
            None
          case NonFatal(e) =>
            logger.error(s"Job $jobId (${job.kind}): preparing failed", e)
            fail(jobId, describe(e))
            h.forget(jobId)
            None
        }
    }
  }

  /** Runs one step of a job this instance owns.
    * @param prepared the step, if the fetch lane has prepared it (else [[prepareStep]] does)
    * @return true if the job has more steps
    */
  private[jobs] def runStep(jobId: Long, prepared: Option[Step] = None): Boolean = {
    val step = prepared.orElse(prepareStep(jobId)) match {
      case Some(s) => s
      case None    => return false
    }
    val job = JobJdbc.findById(jobId) match {
      case Some(j) if j.status == JobStatus.Running && j.owner.contains(instanceId) => j
      case _ => handlers.values.foreach(_.forget(jobId)); return false
    }
    val h = handlers.get(job.kind) match {
      case Some(handler) => handler
      case None          => return false
    }

    var attempt = 0
    while (true) {
      try {
        val outcome = DB.localTx { implicit s =>
          JobJdbc.lock(jobId).filter(j => j.status == JobStatus.Running && j.owner.contains(instanceId)).map { j =>
            val ctx = new StepContext(s)
            val result = step(j, ctx)
            result match {
              case StepResult.Continue(state, p) => JobJdbc.update(jobId, JobStatus.Running, state, p, None)
              case StepResult.Done(state, p)     => JobJdbc.update(jobId, JobStatus.Done, state, p, None)
              case StepResult.Failed(error, state, p) =>
                val after = h.onFailed(j.copy(state = state), ctx)
                JobJdbc.update(jobId, JobStatus.Failed, after, p, Some(error))
            }
            (result, ctx)
          }
        }
        outcome match {
          case None =>
            h.forget(jobId)
            return false
          case Some((result, ctx)) =>
            runAfterCommit(ctx)
            result match {
              case _: StepResult.Continue => return true
              case f: StepResult.Failed =>
                logger.warn(s"Job $jobId (${job.kind}) failed: ${f.error}")
                h.forget(jobId)
                return false
              case _ =>
                h.forget(jobId)
                return false
            }
        }
      } catch {
        case NonFatal(e) if isTransient(e) && attempt < settings.transientRetries =>
          attempt += 1
          logger.warn(s"Job $jobId (${job.kind}): transient error, retrying the step ($attempt): ${e.getMessage}")
          Thread.sleep(100L * attempt)
        case NonFatal(e) =>
          logger.error(s"Job $jobId (${job.kind}): step failed", e)
          fail(jobId, describe(e))
          h.forget(jobId)
          return false
      }
    }
    false
  }

  /** Marks a job this instance runs as failed, with its handler's cleanup. */
  private def fail(jobId: Long, error: String): Unit =
    try {
      val ctx = DB.localTx { implicit s =>
        JobJdbc.lock(jobId).filter(j => j.status == JobStatus.Running && j.owner.contains(instanceId)).map { j =>
          val ctx = new StepContext(s)
          val state = handlers.get(j.kind).fold(j.state)(_.onFailed(j, ctx))
          JobJdbc.update(jobId, JobStatus.Failed, state, progressOf(j), Some(error.take(2000)))
          ctx
        }
      }
      ctx.foreach(runAfterCommit)
    } catch {
      case NonFatal(e) => logger.error(s"Job $jobId: could not record its failure ($error)", e)
    }

  private def runAfterCommit(ctx: StepContext): Unit =
    ctx.afterCommitActions.foreach { f =>
      try f()
      catch { case NonFatal(e) => logger.warn("Job: an after-commit action failed", e) }
    }
}

object JobScheduler {

  sealed trait Mode
  object Mode {
    case object Background extends Mode
    case object Inline extends Mode
  }

  final case class Settings(
      mode: Mode = Mode.Background,
      workers: Int = 3,
      fetchWorkers: Int = 2,
      kindLimits: Map[String, Int] = Map.empty,
      defaultKindLimit: Int = 1,
      pollInterval: FiniteDuration = 2.seconds,
      heartbeatInterval: FiniteDuration = 10.seconds,
      lease: FiniteDuration = 60.seconds,
      transientRetries: Int = 3
  )

  object Settings {

    /** `jobs.*` in application.conf. */
    def from(config: Configuration): Settings = {
      val c = config.getOptional[Configuration]("jobs").getOrElse(Configuration.empty)
      def dur(key: String, default: FiniteDuration) = c.getOptional[FiniteDuration](key).getOrElse(default)
      val d = Settings()
      Settings(
        mode = c.getOptional[String]("mode") match {
          case Some("inline") => Mode.Inline
          case _              => Mode.Background
        },
        workers = c.getOptional[Int]("workers").getOrElse(d.workers),
        fetchWorkers = c.getOptional[Int]("fetchWorkers").getOrElse(d.fetchWorkers),
        kindLimits = c
          .getOptional[Configuration]("concurrency")
          .map(cc => cc.subKeys.map(k => k -> cc.get[Int](k)).toMap)
          .getOrElse(Map.empty),
        defaultKindLimit = c.getOptional[Int]("defaultConcurrency").getOrElse(d.defaultKindLimit),
        pollInterval = dur("pollInterval", d.pollInterval),
        heartbeatInterval = dur("heartbeatInterval", d.heartbeatInterval),
        lease = dur("lease", d.lease)
      )
    }
  }

  def progressOf(j: Job): Progress = Progress(j.progressDone, j.progressTotal, j.progressUnit)

  def describe(e: Throwable): String =
    Option(e.getMessage).filter(_.nonEmpty).getOrElse(e.getClass.getSimpleName)

  /** A database error worth retrying the step for: a deadlock or a lock wait timeout. */
  def isTransient(e: Throwable): Boolean =
    Iterator.iterate(e)(_.getCause).takeWhile(_ != null).take(10).exists {
      case _: java.sql.SQLTransactionRollbackException => true
      case t =>
        Option(t.getMessage).exists(m => m.contains("Deadlock found") || m.contains("Lock wait timeout"))
    }

  private def threadFactory(prefix: String): ThreadFactory = {
    val n = new AtomicInteger()
    (r: Runnable) => {
      val t = new Thread(r, s"$prefix-${n.incrementAndGet()}")
      t.setDaemon(true)
      t
    }
  }
}
