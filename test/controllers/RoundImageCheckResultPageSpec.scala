package controllers

import db.scalikejdbc._
import play.api.libs.json.Json
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.distribution.{ImageCheckState, RoundImageCheckHandler}
import services.jobs.{JobJdbc, JobStatus, NewJob, Progress}

/** The image check job's progress and outcome on the page that started it: root's all-rounds
  * check on the rounds page (root only), a round's on its edit page (its admin), as a
  * summary line when done and as the polled job status otherwise.
  */
class RoundImageCheckResultPageSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val contestId = 10L

  private def member(email: String, role: String): User =
    User.create(User(email, email, None, Set(role), Some(services.PasswordHasher.default.hash("secret")), Some(contestId)))

  private def setUp(): Round = {
    createContests(contestId)
    member("admin@example.com", "admin")
    User.create(User("root@example.com", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
    roundDao.create(Round(None, 1, Some("R"), contestId, active = true))
  }

  private def jobWith(newJob: NewJob, status: String, state: ImageCheckState): Long = DB.localTx { implicit s =>
    val id = JobJdbc.insert(newJob, None)
    JobJdbc.update(id, status, Json.toJsObject(state), Progress(Some(state.rounds), Some(2000L), Some("rounds")), None)
    id
  }

  private def page(app: play.api.Application, email: String, path: String): String =
    contentAsString(route(app, FakeRequest(GET, path)
      .withSession(Secured.UserName -> email, Secured.CurrentContestId -> contestId.toString).withCSRFToken).get)

  private val root = User("root@example.com", "root@example.com", Some(1L), Set(User.ROOT_ROLE), Some("hash"))

  "the rounds page" should {

    "show root the latest all-rounds check's summary, and a running one as a job status" in {
      testDbApp { app =>
        setUp()
        val done = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Done,
          ImageCheckState(rounds = 1823L, added = 5L, statusFixed = 2L, skipped = Seq(7L)))
        val doneText = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        val clean = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Done, ImageCheckState(rounds = 1823L))
        val cleanText = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        val running = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Running, ImageCheckState(rounds = 40L))
        val runningText = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        (doneText must contain(s"Image check (job $done, ")) and
          (doneText must contain("1,823 rounds checked, 5 rows added, 2 statuses fixed, 1 round skipped (distribution running)")) and
          (cleanText must contain(s"Image check (job $clean, ")) and
          (cleanText must contain("1,823 rounds checked, no mismatches")) and
          (runningText must contain(s"""data-src="/admin/jobs/$running"""")) and
          (runningText must not(contain("rounds checked")))
      }
    }

    "show a failed or cancelled all-rounds check as its status with the job link, not as a summary" in {
      testDbApp { app =>
        setUp()
        val failed = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Failed, ImageCheckState(rounds = 40L))
        val failedText = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        val cancelled = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Cancelled, ImageCheckState(rounds = 40L))
        val cancelledText = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        (failedText must contain("""data-status="failed"""")) and
          (failedText must contain(s"""href="/admin/jobs/$failed"""")) and
          (failedText must not(contain("rounds checked"))) and
          (cancelledText must contain("""data-status="cancelled"""")) and
          (cancelledText must contain(s"""href="/admin/jobs/$cancelled"""")) and
          (cancelledText must not(contain("rounds checked")))
      }
    }

    "show a done check whose state can't be read as its status, not as no mismatches" in {
      testDbApp { app =>
        setUp()
        val id = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Done, ImageCheckState())
        DB.autoCommit(implicit s => sql"""UPDATE job SET state = '{"rounds": "many"}' WHERE id = $id""".update())
        val text = page(app, "root@example.com", s"/admin/rounds?contestId=$contestId")
        (text must not(contain("no mismatches"))) and (text must contain(s"""href="/admin/jobs/$id""""))
      }
    }

    "show no all-rounds check when none ever ran" in {
      testDbApp { app =>
        setUp()
        page(app, "root@example.com", s"/admin/rounds?contestId=$contestId") must not(contain("image-check-status"))
      }
    }

    "not show a contest's admin the all-rounds check" in {
      testDbApp { app =>
        setUp()
        val done = jobWith(RoundImageCheckHandler.allJob(root), JobStatus.Done, ImageCheckState(rounds = 1823L))
        val text = page(app, "admin@example.com", s"/admin/rounds?contestId=$contestId")
        (text must not(contain("rounds checked"))) and (text must not(contain(s"job $done")))
      }
    }
  }

  "the edit round page" should {

    "show the admin the round's latest check: a summary when done, the job status when running" in {
      testDbApp { app =>
        val round = setUp()
        val path = s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}"
        val done = jobWith(RoundImageCheckHandler.roundJob(round.getId, contestId, root), JobStatus.Done,
          ImageCheckState(rounds = 1L, removed = 3L, orphansArchived = 1L))
        val doneText = page(app, "admin@example.com", path)
        val running = jobWith(RoundImageCheckHandler.roundJob(round.getId, contestId, root), JobStatus.Running,
          ImageCheckState())
        val runningText = page(app, "admin@example.com", path)
        (doneText must contain(s"Image check (job $done, ")) and
          (doneText must contain("3 rows removed, 1 orphaned criteria rate archived")) and
          (doneText must not(contain("rounds checked"))) and
          (runningText must contain(s"""data-src="/admin/jobs/$running"""")) and
          (runningText must not(contain("rows removed")))
      }
    }

    "show a done round check that skipped the round" in {
      testDbApp { app =>
        val round = setUp()
        val done = jobWith(RoundImageCheckHandler.roundJob(round.getId, contestId, root), JobStatus.Done,
          ImageCheckState(rounds = 0L, skipped = Seq(round.getId)))
        val text = page(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}")
        (text must contain(s"Image check (job $done, ")) and
          (text must contain("1 round skipped (distribution running)")) and (text must not(contain("no mismatches")))
      }
    }

    "show a failed check's status with its job link" in {
      testDbApp { app =>
        val round = setUp()
        val failed = jobWith(RoundImageCheckHandler.roundJob(round.getId, contestId, root), JobStatus.Failed,
          ImageCheckState())
        val text = page(app, "admin@example.com", s"/admin/rounds/edit?contestId=$contestId&id=${round.getId}")
        (text must contain(s"""data-status="failed"""")) and (text must contain(s"""href="/admin/jobs/$failed""""))
      }
    }
  }
}
