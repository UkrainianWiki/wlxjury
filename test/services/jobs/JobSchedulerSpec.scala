package services.jobs

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import play.api.libs.json.{JsObject, JsValue, Json}
import scalikejdbc._

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList, CountDownLatch, TimeUnit}
import scala.collection.mutable
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** The generic job machinery, with a test handler: dedupe, lease and restart recovery,
  * per-kind limits, turns between contests, cancel, failure and retry.
  */
class JobSchedulerSpec extends Specification with BeforeAll with BeforeEach with AfterEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val schedulers = mutable.Buffer.empty[JobScheduler]
  override protected def after: Any = { schedulers.foreach(_.stop()); schedulers.clear() }

  /** Counts to `params.steps`, one per step, sleeping `params.sleepMs` in each; records
    * the steps running at once per kind, the order steps ran in, and its hooks' calls.
    */
  class CountingHandler(val kind: String) extends JobHandler {
    val running = new AtomicInteger()
    val maxRunning = new AtomicInteger()
    val stepsRun: CopyOnWriteArrayList[Long] = new CopyOnWriteArrayList[Long]()
    val failed = new CopyOnWriteArrayList[Long]()
    val cancelled = new CopyOnWriteArrayList[Long]()
    val retried = new CopyOnWriteArrayList[Long]()
    val failAt = new ConcurrentHashMap[Long, Int]()

    def step(job: Job, ctx: StepContext): StepResult = {
      val now = running.incrementAndGet()
      maxRunning.accumulateAndGet(now, math.max)
      try {
        stepsRun.add(job.id)
        Thread.sleep((job.params \ "sleepMs").asOpt[Long].getOrElse(0L))
        val done = (job.state \ "done").asOpt[Int].getOrElse(0) + 1
        if (failAt.get(job.id) == done) {
          failAt.remove(job.id)
          throw new RuntimeException(s"simulated failure at step $done")
        }
        val steps = (job.params \ "steps").as[Int]
        val state = Json.obj("done" -> done)
        val progress = Progress(Some(done.toLong), Some(steps.toLong), Some("steps"))
        if (done >= steps) StepResult.Done(state, progress) else StepResult.Continue(state, progress)
      } finally running.decrementAndGet()
    }

    override def onFailed(job: Job, ctx: StepContext): JsObject = { failed.add(job.id); job.state }
    override def onCancelled(job: Job, ctx: StepContext): JsObject = { cancelled.add(job.id); job.state }
    override def onRetry(job: Job, ctx: StepContext): JsObject = { retried.add(job.id); job.state }
  }

  /** Fetches page `state.page` before each step (waiting for `gate` first, as for a slow
    * API) and writes it; `params.steps` pages. Records its fetches and writes.
    */
  class PagingHandler(val kind: String) extends FetchingJobHandler[Int] {
    val fetches = new CopyOnWriteArrayList[Int]()
    val writes = new CopyOnWriteArrayList[Int]()
    @volatile var gate = new CountDownLatch(0)
    val failAtPage = new ConcurrentHashMap[Long, Int]()

    private def page(job: Job) = (job.state \ "page").asOpt[Int].getOrElse(0)

    def cursorOf(job: Job): JsValue = Json.toJson(page(job))

    def fetch(job: Job): Int = {
      gate.await(10, TimeUnit.SECONDS)
      val p = page(job)
      if (failAtPage.containsKey(job.id) && failAtPage.get(job.id) == p) throw new RuntimeException(s"API down at page $p")
      fetches.add(p)
      p
    }

    def write(job: Job, fetched: Int, ctx: StepContext): StepResult = {
      writes.add(fetched)
      val pages = (job.params \ "steps").as[Int]
      val state = Json.obj("page" -> (fetched + 1))
      val progress = Progress(Some(fetched + 1L), Some(pages.toLong), Some("pages"))
      if (fetched + 1 >= pages) StepResult.Done(state, progress) else StepResult.Continue(state, progress)
    }
  }

  private def background(handlers: Seq[JobHandler], workers: Int = 3, limits: Map[String, Int] = Map.empty,
      lease: FiniteDuration = 60.seconds): JobScheduler = {
    val s = new JobScheduler(handlers, JobScheduler.Settings(
      workers = workers, kindLimits = limits, defaultKindLimit = 3,
      pollInterval = 50.millis, heartbeatInterval = 200.millis, lease = lease))
    schedulers += s
    s
  }

  private def newJob(kind: String, steps: Int, sleepMs: Long = 0, contest: Long = 1, subject: Option[Long] = None) =
    NewJob(kind, contestId = Some(contest), subjectType = subject.map(_ => "thing"), subjectId = subject,
      dedupeKey = subject.map(id => s"$kind:thing:$id"), params = Json.obj("steps" -> steps, "sleepMs" -> sleepMs))

  private def enqueue(s: JobScheduler, job: NewJob): Job =
    DB.localTx(implicit session => s.enqueue(job)).fold(identity, identity)

  private def status(id: Long) = JobJdbc.findById(id).get.status

  "the active dedupe key" should {

    "admit one active job per subject; the second request gets the active one" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h)) // not started: the jobs stay queued
      val first = DB.localTx(implicit session => s.enqueue(newJob("count", 1, subject = Some(42))))
      val second = DB.localTx(implicit session => s.enqueue(newJob("count", 1, subject = Some(42))))
      first must beRight
      second must beLeft(first.toOption.get)
      DB.localTx(implicit session => s.enqueue(newJob("count", 1, subject = Some(43)))) must beRight
    }

    "admit a new job once the subject's job has finished or failed" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h))
      val first = enqueue(s, newJob("count", 1, subject = Some(42)))
      sql"UPDATE job SET status = 'done' WHERE id = ${first.id}".update()(AutoSession)
      val second = DB.localTx(implicit session => s.enqueue(newJob("count", 1, subject = Some(42))))
      second must beRight
      sql"UPDATE job SET status = 'failed' WHERE id = ${second.toOption.get.id}".update()(AutoSession)
      DB.localTx(implicit session => s.enqueue(newJob("count", 1, subject = Some(42)))) must beRight
    }

    "refuse a retry while another job of the subject is active" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h))
      val failed = enqueue(s, newJob("count", 1, subject = Some(7)))
      sql"UPDATE job SET status = 'failed' WHERE id = ${failed.id}".update()(AutoSession)
      enqueue(s, newJob("count", 1, subject = Some(7)))
      s.retry(failed.id) must beLeft
      status(failed.id) === JobStatus.Failed // its onRetry's writes rolled back with the requeue
    }
  }

  "a background scheduler" should {

    "run queued jobs to their end, recording state and progress" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h))
      s.start()
      val job = enqueue(s, newJob("count", 5))
      s.started(job.id)
      val done = s.await(job.id, 10.seconds)
      done.status === JobStatus.Done
      (done.state \ "done").as[Int] === 5
      done.progressDone === Some(5L)
      done.progressTotal === Some(5L)
      done.finishedAt must beSome
      done.owner must beNone
    }

    "keep each kind under its concurrency limit, and all steps under the workers" in {
      val a = new CountingHandler("kind_a")
      val b = new CountingHandler("kind_b")
      val s = background(Seq(a, b), workers = 3, limits = Map("kind_a" -> 1, "kind_b" -> 2))
      s.start()
      val jobs = (1 to 3).map(i => enqueue(s, newJob("kind_a", 4, sleepMs = 30, contest = i))) ++
        (1 to 3).map(i => enqueue(s, newJob("kind_b", 4, sleepMs = 30, contest = i)))
      s.wake()
      jobs.map(j => s.await(j.id, 20.seconds).status).toSet === Set(JobStatus.Done)
      a.maxRunning.get === 1
      b.maxRunning.get === 2
    }

    "give a small job of another contest turns while a big one runs" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h), workers = 1)
      s.start()
      val big = enqueue(s, newJob("count", 40, sleepMs = 20, contest = 1))
      s.wake()
      Thread.sleep(200)
      val small = enqueue(s, newJob("count", 3, sleepMs = 20, contest = 2))
      s.wake()
      s.await(small.id, 10.seconds).status === JobStatus.Done
      status(big.id) === JobStatus.Running // still going: the small one didn't wait for it
      s.await(big.id, 20.seconds).status === JobStatus.Done
    }

    "cancel a running job between steps: no further steps, its handler's cleanup runs" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h), workers = 1)
      s.start()
      val job = enqueue(s, newJob("count", 1000, sleepMs = 10))
      s.wake()
      Thread.sleep(300)
      val cancelled = s.cancel(job.id).get
      cancelled.status === JobStatus.Cancelled
      h.cancelled.asScala.toSeq === Seq(job.id)
      val stepsAtCancel = (cancelled.state \ "done").as[Int]
      Thread.sleep(300)
      h.stepsRun.asScala.count(_ == job.id) must beLessThanOrEqualTo(stepsAtCancel + 1)
      (JobJdbc.findById(job.id).get.state \ "done").as[Int] === stepsAtCancel
      s.heldJobs must not(contain(job.id))
    }

    "fail a job whose step throws, keep its committed state, and resume it on retry" in {
      val h = new CountingHandler("count")
      val s = background(Seq(h))
      s.start()
      val job = enqueue(s, newJob("count", 6))
      h.failAt.put(job.id, 4)
      s.started(job.id)
      val failed = s.await(job.id, 10.seconds)
      failed.status === JobStatus.Failed
      failed.error must beSome(contain("simulated failure at step 4"))
      (failed.state \ "done").as[Int] === 3 // the failed step rolled back
      h.failed.asScala.toSeq === Seq(job.id)

      s.retry(job.id) must beRight
      h.retried.asScala.toSeq === Seq(job.id)
      val done = s.await(job.id, 10.seconds)
      done.status === JobStatus.Done
      (done.state \ "done").as[Int] === 6
      done.error must beNone
    }

    "take over a running job whose lease expired (its instance died), but not a live one" in {
      val h = new CountingHandler("count")
      val dead = enqueue(background(Seq(h)), newJob("count", 3))
      val live = enqueue(background(Seq(h)), newJob("count", 3))
      sql"""UPDATE job SET status = 'running', owner = 'dead-instance', state = '{"done": 1}',
              heartbeat_at = NOW(3) - INTERVAL 120 SECOND WHERE id = ${dead.id}""".update()(AutoSession)
      sql"""UPDATE job SET status = 'running', owner = 'live-instance', heartbeat_at = NOW(3)
              WHERE id = ${live.id}""".update()(AutoSession)

      val s = background(Seq(h), lease = 60.seconds)
      s.start()
      val recovered = s.await(dead.id, 10.seconds)
      recovered.status === JobStatus.Done
      (recovered.state \ "done").as[Int] === 3 // resumed from its committed state
      h.stepsRun.asScala.count(_ == dead.id) === 2
      JobJdbc.findById(live.id).get.owner === Some("live-instance")
      status(live.id) === JobStatus.Running
    }

    "hand its running jobs back to the queue when stopped, for the next instance to resume" in {
      val h = new CountingHandler("count")
      val first = background(Seq(h), workers = 1)
      first.start()
      val job = enqueue(first, newJob("count", 1000, sleepMs = 10))
      first.wake()
      Thread.sleep(300)
      first.stop()
      val stopped = JobJdbc.findById(job.id).get
      stopped.status === JobStatus.Queued
      stopped.owner must beNone
      val doneBefore = (stopped.state \ "done").as[Int]
      doneBefore must beGreaterThan(0)

      val next = background(Seq(h), workers = 1)
      next.start()
      eventually(10, 100.millis)((JobJdbc.findById(job.id).get.state \ "done").as[Int] must beGreaterThan(doneBefore))
      JobJdbc.findById(job.id).get.owner === Some(next.instanceId)
    }

    "not touch the jobs of kinds it has no handler for" in {
      val h = new CountingHandler("count")
      val other = enqueue(background(Seq(new CountingHandler("other"))), newJob("other", 1))
      val s = background(Seq(h))
      s.start()
      Thread.sleep(300)
      status(other.id) === JobStatus.Queued
    }
  }

  "the fetch lane" should {

    "fetch off the workers: a waiting fetch doesn't hold up another kind's steps" in {
      val paging = new PagingHandler("paging")
      paging.gate = new CountDownLatch(1)
      val count = new CountingHandler("count")
      val s = background(Seq(paging, count), workers = 1)
      s.start()
      val p = enqueue(s, newJob("paging", 3))
      val c = enqueue(s, newJob("count", 5, contest = 2))
      s.wake()
      s.await(c.id, 10.seconds).status === JobStatus.Done
      status(p.id) === JobStatus.Running
      paging.writes.isEmpty must beTrue

      paging.gate.countDown()
      s.await(p.id, 10.seconds).status === JobStatus.Done
      paging.fetches.asScala.toSeq === Seq(0, 1, 2)
      paging.writes.asScala.toSeq === Seq(0, 1, 2)
    }

    "fail a job whose fetch fails, and resume it from that page on retry" in {
      val paging = new PagingHandler("paging")
      val s = background(Seq(paging))
      s.start()
      val p = enqueue(s, newJob("paging", 4))
      paging.failAtPage.put(p.id, 2)
      s.started(p.id)
      val failed = s.await(p.id, 10.seconds)
      failed.status === JobStatus.Failed
      failed.error must beSome(contain("API down at page 2"))
      (failed.state \ "page").as[Int] === 2

      paging.failAtPage.remove(p.id)
      s.retry(p.id) must beRight
      s.await(p.id, 10.seconds).status === JobStatus.Done
      paging.writes.asScala.toSeq === Seq(0, 1, 2, 3)
    }

    "write nothing when the job's cursor moved since its fetch" in {
      val paging = new PagingHandler("paging")
      val job = enqueue(background(Seq(paging)), newJob("paging", 3))
      paging.prepare(job) // page 0
      val moved = job.copy(state = Json.obj("page" -> 1))
      DB.localTx(s => paging.step(moved, new StepContext(s))) must beLike {
        case StepResult.Continue(state, _) => state === moved.state
      }
      paging.writes.isEmpty must beTrue
    }

    "fetch and write in the starting call when inline" in {
      val paging = new PagingHandler("paging")
      val s = new JobScheduler(Seq(paging), JobScheduler.Settings(mode = JobScheduler.Mode.Inline))
      val job = DB.localTx(implicit session => s.enqueue(newJob("paging", 3))).toOption.get
      s.started(job.id)
      status(job.id) === JobStatus.Done
      paging.writes.asScala.toSeq === Seq(0, 1, 2)
    }
  }

  "an inline scheduler" should {
    "claim a job when it is created and run it to its end in the starting call" in {
      val h = new CountingHandler("count")
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(mode = JobScheduler.Mode.Inline))
      val job = DB.localTx(implicit session => s.enqueue(newJob("count", 4))).toOption.get
      job.status === JobStatus.Running
      job.owner === Some(s.instanceId)
      s.started(job.id)
      status(job.id) === JobStatus.Done
    }
  }

  "isTransient" should {
    "recognize deadlocks and lock wait timeouts, also as causes" in {
      JobScheduler.isTransient(new java.sql.SQLTransactionRollbackException("x")) must beTrue
      JobScheduler.isTransient(new RuntimeException(new java.sql.SQLException("Lock wait timeout exceeded"))) must beTrue
      JobScheduler.isTransient(new RuntimeException("Duplicate entry")) must beFalse
    }
  }
}
