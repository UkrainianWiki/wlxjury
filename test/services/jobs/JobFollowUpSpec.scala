package services.jobs

import db.scalikejdbc.SharedTestDb
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.libs.json.Json
import scalikejdbc._

import java.util.concurrent.atomic.AtomicInteger

/** A step's follow-up job (StepResult.Done.followUp, P3: a round's distribute after its fill): queued
  * in the step's transaction once the job is done, its onFollowUpQueued run with it; when another
  * job holds its key, not queued and onFollowUpQueued not run, the job still done.
  */
class JobFollowUpSpec extends Specification with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val queuedCallbacks = new AtomicInteger()
  private val followUpsRun = new AtomicInteger()

  private class FirstHandler extends JobHandler {
    val kind = "first"
    def step(job: Job, ctx: StepContext): StepResult =
      StepResult.Done(job.state, Progress(), Some(NewJob(kind = "second", dedupeKey = Some("follow:1"))),
        onFollowUpQueued = _ => { queuedCallbacks.incrementAndGet(); () })
  }

  private class SecondHandler extends JobHandler {
    val kind = "second"
    def step(job: Job, ctx: StepContext): StepResult = { followUpsRun.incrementAndGet(); StepResult.Done(job.state, Progress()) }
  }

  private def scheduler = new JobScheduler(Seq(new FirstHandler, new SecondHandler),
    JobScheduler.Settings(mode = JobScheduler.Mode.Inline))

  private def kinds: List[(String, String)] =
    DB.readOnly(implicit s => sql"SELECT kind, status FROM job ORDER BY id".map(rs => rs.string(1) -> rs.string(2)).list())

  "a follow-up job" should {

    "be queued as its job ends, its callback run, and run" in {
      queuedCallbacks.set(0); followUpsRun.set(0)
      val s = scheduler
      val first = DB.localTx(implicit session => s.enqueue(NewJob(kind = "first", dedupeKey = Some("first:1")))).toOption.get
      s.started(first.id)
      (kinds === List("first" -> JobStatus.Done, "second" -> JobStatus.Done)) and (queuedCallbacks.get === 1) and
        (followUpsRun.get === 1)
    }

    "not be queued while another job holds its key: no callback, the job still done" in {
      queuedCallbacks.set(0); followUpsRun.set(0)
      val s = scheduler
      DB.autoCommit(implicit session => sql"""INSERT INTO job (kind, dedupe_key, status, params, state)
        VALUES ('second', 'follow:1', 'queued', '{}', '{}')""".update())
      val first = DB.localTx(implicit session => s.enqueue(NewJob(kind = "first", dedupeKey = Some("first:1")))).toOption.get
      s.started(first.id)
      (kinds === List("second" -> JobStatus.Queued, "first" -> JobStatus.Done)) and (queuedCallbacks.get === 0) and
        (followUpsRun.get === 0)
    }
  }
}
