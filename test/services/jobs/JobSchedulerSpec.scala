package services.jobs

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterEach, BeforeAll, BeforeEach}
import play.api.libs.json.{JsObject, Json}
import scalikejdbc._

import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.{ConcurrentHashMap, CopyOnWriteArrayList}
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
