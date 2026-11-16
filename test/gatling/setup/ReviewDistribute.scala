package gatling.setup

import io.gatling.core.Predef._
import io.gatling.core.structure.ChainBuilder
import io.gatling.http.Predef._
import scalikejdbc._

import scala.concurrent.duration._

/** A new round's second step since the new-round form has no jurors (the maintainer's decision of
  * 2026-11-16, superseding D4's "Distribute as soon as the images are ready"): its fill ends in
  * review, then its jurors form posts Distribute (POST /admin/rounds/:id/distribute with the
  * jurors and the file distribution) and the distribute job runs. For a session whose `roundId`
  * is in review once its fill ended (`jobStatus` done); any other (an existing round's "Distribute
  * new files") goes on as it is. The step's time keeps counting from its `t0`: submit to the
  * distribution's end, as before.
  */
object ReviewDistribute {

  private implicit val session: DBSession = AutoSession

  private def inReview(roundId: Long): Boolean =
    sql"SELECT distributing = 1 AND status = 'paused' FROM round WHERE id = $roundId"
      .map(_.boolean(1)).single().getOrElse(false)

  private def latestJobId(roundId: Long): Option[Long] =
    sql"""SELECT MAX(id) FROM job WHERE kind = 'round_distribution' AND subject_type = 'round'
          AND subject_id = $roundId""".map(_.longOpt(1)).single().flatten

  def chain(title: String, jurorIds: Seq[Long], perFile: Int, timeout: FiniteDuration, pollMillis: Int): ChainBuilder =
    doIf(s => s("jobStatus").asOption[String].contains("done") && s("roundId").asOption[Long].exists(inReview)) {
      exec(http(s"$title: distribute").post("/admin/rounds/#{roundId}/distribute")
        .formParamSeq(Seq[(String, Any)]("jurorsShown" -> "true", "distribution" -> perFile.toString) ++
          jurorIds.zipWithIndex.map { case (id, i) => s"jurors[$i]" -> id.toString })
        .requestTimeout(timeout)
        .check(status.is(303)))
        .exec { s =>
          latestJobId(s("roundId").as[Long]).fold(s.markAsFailed)(id => s.set("jobId", id).set("jobStatus", "queued"))
        }
        .exitHereIfFailed
        .asLongAs(s => Set("queued", "running").contains(s("jobStatus").as[String]) &&
            System.currentTimeMillis() - s("t0").as[Long] < timeout.toMillis) {
          pause(pollMillis.millis)
            .exec(http("job status").get("/admin/jobs/#{jobId}")
              .check(status.is(200), jsonPath("$.status").saveAs("jobStatus")))
        }
    }
}
