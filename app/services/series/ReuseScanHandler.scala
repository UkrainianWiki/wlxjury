package services.series

import db.scalikejdbc.User
import play.api.{Configuration, Logging}
import play.api.libs.json._
import scalikejdbc._
import services.jobs._

import javax.inject.{Inject, Singleton}

/** The resume cursor and the results of a `contest_reuse_scan` job (`job.state`).
  *
  * @param phase    "eras" (each contest's era sample, [[ReuseReport.eraSample]]), then "scan" (each
  *                 contest's report, [[ReuseReport.report]])
  * @param cursor   the last contest id a committed step read
  * @param total    the contests to read (counted at the first step; contests added meanwhile are
  *                 read too, the progress capped at it)
  * @param read     contests the eras pass has read (with or without a sample)
  * @param samples  the era samples read so far
  * @param eras     the years' centres, once every sample is read
  * @param scanned  contests scanned
  * @param clean    of them, those with nothing to report ([[ReuseReport.Verdict.Clean]])
  * @param contests the summaries of the others
  */
final case class ReuseScanState(
    phase: String = ReuseScanHandler.Phase.Eras,
    cursor: Long = 0L,
    total: Long = 0L,
    read: Long = 0L,
    samples: Seq[ReuseReport.EraSample] = Nil,
    eras: Seq[ReuseReport.YearCentre] = Nil,
    scanned: Int = 0,
    clean: Int = 0,
    contests: Seq[ReuseReport.Summary] = Nil
)

object ReuseScanState {
  implicit val sampleFormat: OFormat[ReuseReport.EraSample] = Json.format[ReuseReport.EraSample]
  implicit val centreFormat: OFormat[ReuseReport.YearCentre] = Json.format[ReuseReport.YearCentre]
  implicit val format: OFormat[ReuseScanState] = Json.using[Json.WithDefaultValues].format[ReuseScanState]
}

/** The reuse dry run over every contest (contest series plan §4.3, CY-0), as a [[Job]] of kind
  * `contest_reuse_scan`, root's (no contest): one contest per step, first each contest's era
  * sample (S1, S3), then each contest's report, whose summary it keeps in its state, the cache that
  * root's `/admin/contests/reuse`, `GET /api/diagnostics/reuse` and the series proposals read once
  * it is done (never a running, failed or cancelled scan's). A step only reads, and writes nothing but
  * the job's own row (its state and progress, as every step does). One scan at a time (its dedupe key).
  */
@Singleton
class ReuseScanHandler @Inject() (config: Configuration) extends JobHandler with Logging {

  import ReuseScanHandler._

  private val settings = ReuseReport.Settings(config)

  val kind: String = Kind

  override def title(job: Job): String = "Scanning contests for reuse"

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("phase" -> st.phase, "scanned" -> st.scanned, "clean" -> st.clean, "reported" -> st.contests.size)
  }

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val st0 = stateOf(job)
    val st = if (st0.total == 0L) st0.copy(total = sql"SELECT COUNT(*) FROM contest_jury".map(_.long(1)).single.apply().getOrElse(0L)) else st0
    val next = sql"SELECT MIN(id) FROM contest_jury WHERE id > ${st.cursor}".map(_.longOpt(1)).single.apply().flatten
      .flatMap(id => db.scalikejdbc.ContestJuryJdbc.findById(id))
    (st.phase, next) match {
      case (Phase.Eras, Some(contest)) =>
        continue(st.copy(cursor = contest.getId, read = st.read + 1,
          samples = st.samples ++ ReuseReport.eraSample(contest, settings)))
      case (Phase.Eras, None) =>
        continue(st.copy(phase = Phase.Scan, cursor = 0L, eras = ReuseReport.Eras.of(st.samples).years, samples = Nil))
      case (_, Some(contest)) =>
        val report = ReuseReport.report(contest, ReuseReport.Eras(st.eras), settings)
        val summary = ReuseReport.summary(report)
        val clean = summary.verdict == ReuseReport.Verdict.Clean
        continue(st.copy(cursor = contest.getId, scanned = st.scanned + 1, clean = st.clean + (if (clean) 1 else 0),
          contests = if (clean) st.contests else st.contests :+ summary))
      case (_, None) =>
        logger.info(s"contest_reuse_scan job ${job.id}: ${st.scanned} contests scanned, ${st.clean} clean, " +
          st.contests.groupBy(_.verdict).toSeq.sortBy(_._1).map { case (v, cs) => s"${cs.size} $v" }.mkString(", "))
        StepResult.Done(Json.toJsObject(st), progress(st))
    }
  }

  private def continue(st: ReuseScanState) = StepResult.Continue(Json.toJsObject(st), progress(st))

  /** Two passes over the contests, the eras, then the reports: each counted in contests, capped at
    * the total (`ReuseScanHandler.pass` gives one pass's).
    */
  private def progress(st: ReuseScanState) =
    Progress(done = Some(ReuseScanHandler.pass(st) match { case (1, n, _) => n; case (_, n, t) => t + n }),
      total = Some(st.total * 2), unit = Some("contests"))
}

object ReuseScanHandler {

  val Kind = "contest_reuse_scan"

  object Phase {
    val Eras = "eras"
    val Scan = "scan"
  }

  private val log = play.api.Logger(classOf[ReuseScanHandler])

  /** One scan at a time. */
  val DedupeKey: String = s"$Kind:all"

  /** The job's state; a state that doesn't parse (a job of an older release) is logged at WARN and
    * read as a new scan's.
    */
  def stateOf(job: Job): ReuseScanState =
    job.state.validate[ReuseScanState].fold(
      errors => {
        log.warn(s"contest_reuse_scan job ${job.id}: its state doesn't parse (${errors.map(_._1.toString).mkString(", ")}); " +
          "read as empty")
        ReuseScanState()
      },
      identity)

  /** A scan of every contest: no contest (root's). */
  def newJob(by: User): NewJob =
    NewJob(kind = Kind, dedupeKey = Some(DedupeKey), state = Json.toJsObject(ReuseScanState()),
      progress = Progress(unit = Some("contests")), createdBy = by.id)

  /** The scan's pass (1, the eras; 2, the reports), the contests it has read in it and the total. */
  def pass(st: ReuseScanState): (Int, Long, Long) =
    if (st.phase == Phase.Eras) (1, math.min(st.read, st.total), st.total)
    else (2, math.min(st.scanned.toLong, st.total), st.total)

  /** The latest scan, of any status: for its progress only (a running scan's summaries are partial). */
  def latest()(implicit session: DBSession = AutoSession): Option[Job] = JobJdbc.latestByDedupeKey(DedupeKey)

  /** The latest finished scan: the results the report, the API and the series proposals read, never
    * a running, failed or cancelled scan's.
    */
  def latestDone()(implicit session: DBSession = AutoSession): Option[Job] = JobJdbc.latestDoneByDedupeKey(DedupeKey)

  /** The eras (S1, S3) of the latest finished scan; empty without one. */
  def latestEras()(implicit session: DBSession = AutoSession): ReuseReport.Eras =
    ReuseReport.Eras(latestDone().map(stateOf).map(_.eras).getOrElse(Nil))
}
