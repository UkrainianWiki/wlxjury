package views

import org.jsoup.Jsoup
import play.api.libs.json.Json
import play.api.mvc.Request
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}
import services.jobs.{Job, JobStatus}

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

  private def job(status: String, done: Option[Long], total: Option[Long], unit: Option[String]) =
    Job(id = 42L, kind = "image_import", contestId = Some(1L), subjectType = None, subjectId = None, dedupeKey = None,
      status = status, params = Json.obj(), state = Json.obj(), progressDone = done, progressTotal = total,
      progressUnit = unit, error = None, attempts = 0, createdBy = None, owner = None, createdAt = Instant.EPOCH,
      startedAt = None, updatedAt = None, finishedAt = None, heartbeatAt = None)

  private def render(j: Job, label: Option[String]) =
    Jsoup.parse(views.html.snippets.jobStatus(j, "/admin/import", label = label).body).selectFirst(".job-status")

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
  }
}
