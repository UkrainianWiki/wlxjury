package views

import org.jsoup.Jsoup
import play.api.libs.json.Json
import play.api.mvc.Request
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.jobs.{Job, JobError, JobStatus}

import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

import java.time.Instant

/** A background job's status (snippets/jobStatus) as first rendered: a label given by the page
  * (an import's title, "Image check (job 5)") shown once on its own line with no colon after it,
  * and the status text carrying only the progress, in job-status.js's words, never the title again.
  */
class JobStatusViewSpec extends PlaySpecification {

  implicit val request: Request[_] = FakeRequest("GET", "/admin/import").withCSRFToken
  // stub messages: a key renders as itself
  implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request)

  private val title = "Updating the monument ids of the files from Category:Images from WLM 2025"

  private def job(status: String, done: Option[Long], total: Option[Long], unit: Option[String],
      error: Option[String] = None) =
    Job(id = 42L, kind = "image_import", contestId = Some(1L), subjectType = None, subjectId = None, dedupeKey = None,
      status = status, params = Json.obj(), state = Json.obj(), progressDone = done, progressTotal = total,
      progressUnit = unit, error = error, attempts = 0, createdBy = None, owner = None, createdAt = Instant.EPOCH,
      startedAt = None, updatedAt = None, finishedAt = None, heartbeatAt = None)

  private def render(j: Job, label: Option[String], labelledBy: Option[String] = None) =
    Jsoup.parse(views.html.snippets.jobStatus(j, "/admin/import", label = label, labelledBy = labelledBy).body)
      .selectFirst(".job-status")

  /** Each `@snippets.jobStatus(` call in app/views, with its arguments up to the closing parenthesis. */
  private def partialCalls: Seq[(Path, String)] = {
    val files = Files.walk(Paths.get("app/views")).iterator().asScala.filter(_.toString.endsWith(".scala.html")).toList
    files.flatMap { f =>
      val text = new String(Files.readAllBytes(f), java.nio.charset.StandardCharsets.UTF_8)
      """@snippets\.jobStatus\(""".r.findAllMatchIn(text).map { m =>
        var depth = 1; var i = m.end
        while (depth > 0 && i < text.length) { text.charAt(i) match { case '(' => depth += 1; case ')' => depth -= 1; case _ => }; i += 1 }
        f -> text.substring(m.start, i)
      }
    }
  }

  "a job's status" should {

    "show its label once, and only the progress in the text" in {
      val el = render(job(JobStatus.Running, Some(1034L), Some(38248L), Some("files")), Some(title))
      (el.attr("data-labelled") must_== "true") and
        (el.select(".job-status-label").text must_== title) and
        (el.select(".job-status-text").text must_== "1,034 / 38,248 files (2%)…") and
        (el.text.split(java.util.regex.Pattern.quote(title), -1).length - 1 must_== 1) and
        (el.text must not(contain(title + ":")))
    }

    "show a count without a total, and Starting before any progress" in {
      val lists = render(job(JobStatus.Running, Some(260L), None, Some("list pages")), Some("Updating the monument lists"))
      val starting = render(job(JobStatus.Running, None, None, None), Some(title))
      (lists.select(".job-status-text").text must_== "260 list pages…") and
        (starting.select(".job-status-text").text must_== "Starting…")
    }

    "say where a cancelled job stopped, and leave the label out when the page gives none" in {
      val el = render(job(JobStatus.Cancelled, Some(10L), Some(40L), Some("images")), None)
      (el.hasAttr("data-labelled") must beFalse) and (el.select(".job-status-label").size must_== 0) and
        (el.select(".job-status-text").text must_== "Cancelled at 10 / 40 images (25%)")
    }

    "describe the status by its label's id, unique per job" in {
      val el = render(job(JobStatus.Running, Some(1034L), Some(38248L), Some("files")), Some(title))
      (el.select(".job-status-label").attr("id") must_== "job-status-label-42") and
        (el.attr("aria-describedby") must_== "job-status-label-42")
    }

    "take the page's own name of the job as its label, by id, without showing it again" in {
      val el = render(job(JobStatus.Running, Some(3L), Some(7L), Some("steps")), None, Some("account-join-title"))
      (el.attr("data-labelled") must_== "true") and (el.attr("aria-describedby") must_== "account-join-title") and
        (el.select(".job-status-label").size must_== 0) and
        (el.select(".job-status-text").text must_== "3 / 7 steps (42%)…")
    }

    "say where a failed job stopped and why, in the error's message" in {
      val failed = job(JobStatus.Failed, Some(10L), Some(40L), Some("images"),
        error = Some(JobError("job.error.test", reference = Some("ref-1")).encode))
      val noProgress = job(JobStatus.Failed, None, None, None, error = Some(JobError("job.error.test").encode))
      // stub messages: the key is the message
      (render(failed, Some(title)).select(".job-status-text").text must_== "Failed at 10 / 40 images (25%): job.error.test") and
        (render(noProgress, Some(title)).select(".job-status-text").text must_== "Failed: job.error.test")
    }

    "say a queued job is queued" in {
      render(job(JobStatus.Queued, None, None, None), Some(title)).select(".job-status-text").text must_== "Queued"
    }

    "be labelled on every page, so that the first render reads as job-status.js's poll does" in {
      // without a label the poll puts the job's title before the progress, which the first render can't
      val calls = partialCalls
      val unlabelled = calls.filterNot { case (_, call) => call.contains("label = ") || call.contains("labelledBy = ") }
      (calls.size must beGreaterThanOrEqualTo(7)) and (unlabelled.map(_._1.toString) must beEmpty)
    }
  }
}
