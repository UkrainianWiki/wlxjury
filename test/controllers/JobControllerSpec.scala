package controllers

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.read.ListAppender
import db.scalikejdbc.{PlayTestDb, User}
import modules.BlockingExecutionContext
import org.apache.pekko.stream.Materializer
import play.api.Application
import play.api.i18n.MessagesApi
import play.api.mvc.ControllerComponents
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.jobs._

import scala.jdk.CollectionConverters._

/** A failed job's status (N9): its JSON and the status line show a message key's text and a
  * logged reference, never the exception's text, which goes only to the log.
  */
class JobControllerSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val Secret = "Duplicate entry 'juror@example.com' for key 'users_email' at db-internal.example:3306"

  /** A job kind whose step crashes with a driver-like message. */
  private object Crashing extends JobHandler {
    val kind = "crashing"
    def step(job: Job, ctx: StepContext): StepResult = throw new java.sql.SQLException(Secret)
  }

  private def setup(app: Application) = {
    val contest = contestDao.create(None, "WLE", 2024, "Ukraine")
    val admin = userDao.create(User("Admin", "admin@example.com", None, Set("admin"), contestId = contest.id))
    val scheduler = services.TestRoundServices.inlineScheduler(Crashing)
    val controller = new JobController(
      app.injector.instanceOf[ControllerComponents], scheduler, new BlockingExecutionContext(app.actorSystem))
    (contest, admin, scheduler, controller)
  }

  private def statusRequest(jobId: Long, admin: User) =
    FakeRequest(GET, s"/admin/jobs/$jobId")
      .withSession(Secured.UserName -> admin.email, Secured.CurrentContestId -> admin.contestId.get.toString)
      .withHeaders("X-Requested-With" -> "XMLHttpRequest")
      .withCSRFToken

  private def schedulerLog[T](f: => T): (T, Seq[ILoggingEvent]) = {
    val logger = org.slf4j.LoggerFactory.getLogger(classOf[JobScheduler]).asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ListAppender[ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try (f, appender.list.asScala.toList)
    finally logger.detachAppender(appender)
  }

  "a failed job's status [A-06]" should {
    "show a message key and a logged reference, never the exception's text" in {
      testDbApp { app =>
        implicit val materializer: Materializer = app.materializer
        val (contest, admin, scheduler, controller) = setup(app)
        val (failed, logged) = schedulerLog {
          val job = scalikejdbc.DB.localTx { implicit s =>
            scheduler.enqueue(NewJob(Crashing.kind, contestId = contest.id)).toOption.get
          }
          scheduler.started(job.id)
          scheduler.await(job.id)
        }
        val reference = failed.failure.flatMap(_.reference).getOrElse("")

        val result = controller.status(failed.id).apply(statusRequest(failed.id, admin)).run()
        val json = contentAsJson(result)
        val view = contentAsString(views.html.snippets.jobStatus(failed, "/admin/rounds")(
          app.injector.instanceOf[MessagesApi].preferred(Seq.empty), FakeRequest().withCSRFToken))
        val line = logged.find(_.getFormattedMessage.contains("step failed")).get

        (failed.status === JobStatus.Failed) and (reference must not(beEmpty)) and
          (failed.error must beSome(not(contain("example")))) and
          (status(result) === OK) and
          ((json \ "status").as[String] === "failed") and
          ((json \ "errorKey").as[String] === JobError.CrashedKey) and
          ((json \ "errorReference").as[String] === reference) and
          ((json \ "error").as[String] must contain(reference)) and
          (contentAsString(result) must not(contain("example"))) and
          (view must contain(reference)) and (view must not(contain("example"))) and
          (line.getFormattedMessage must contain(s"ref=$reference")) and
          (line.getThrowableProxy.getMessage === Secret)
      }
    }

    "not show an older row's stored exception text" in {
      val legacy = JobError.decode(Secret)
      val json = services.jobs.JobError.decode("""{"key":"not.ours","args":["x"]}""")
      (legacy === JobError(JobError.UnknownKey)) and (json === JobError(JobError.UnknownKey)) and
        (JobError.decode(JobError.crashed("ab12cd34").encode) === JobError.crashed("ab12cd34"))
    }
  }
}
