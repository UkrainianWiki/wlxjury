package services.distribution

import db.scalikejdbc._
import play.api.libs.json.Json
import scalikejdbc._
import services.jobs._

import javax.inject.Inject

/** Adding a juror to a running round (docs/plans/2026-11-16-round-editing.md, §5): what the add
  * page shows ([[preview]]) and queueing the job ([[add]], a [[RoundRebalanceHandler]] job).
  */
class RoundRebalanceService @Inject() (jobs: JobScheduler) {

  import RoundRebalanceService._
  import RoundRebalanceHandler._

  /** The round's jurors as the page lists them, and the contest's jurors who may be added (an
    * active jury membership, not deleted, not in the round), by id.
    */
  def jury(round: Round)(implicit session: DBSession = AutoSession): Jury = {
    val rows = RoundUser.byRoundId(round.getId)
    val users = User.findByIds(rows.map(_.userId)).map(u => u.getId -> u).toMap
    val jurors = rows.sortBy(_.userId).flatMap(ru => users.get(ru.userId).map(JurorRow(_, ru.active)))
    val inRound = rows.map(_.userId).toSet
    val candidates = User.loadJurors(round.contestId).filter(u => u.deletedAt.isEmpty && !inRound.contains(u.getId))
    Jury(jurors, candidates.sortBy(_.getId))
  }

  /** What adding `jurorId` would do now: the refusal if any, else the files to give. Plain reads
    * (the POST checks again under the round's lock).
    */
  def preview(round: Round, jurorId: Long): Preview = DB.readOnly { implicit session =>
    val juror = User.findById(jurorId).filter(_ => eligibleJuror(round.contestId, jurorId).isDefined)
    val busy = JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId))
    val run = RoundRun(round.active, round.distributing, round.freezeCount,
      activeBeforeFreeze = false)
    val refused = busy.map(j => JobError(BusyKey, Seq(round.number.toString)))
      .orElse(refusal(round, run, jurorId, frozenByUs = false))
    val j = jury(round)
    Preview(round, juror, refused, if (refused.isEmpty) toGiveCount(round.getId, jurorId) else 0L, j, signature(j.jurors),
      topUp = j.jurors.exists(r => r.user.id.contains(jurorId) && r.active))
  }

  /** Queues the add of `jurorId` by `by`, if the round's jury is still `shownJury` (the page's
    * [[signature]]) and nothing refuses it, all under the round's lock; the job runs once this
    * has committed.
    */
  def add(round: Round, jurorId: Long, by: User, shownJury: String): Outcome = {
    val outcome = WriteTx.write { implicit session =>
      val run = lockRun(round.getId)
      val fresh = Round.findById(round.getId)
      (run, fresh) match {
        case (Some(run), Some(fresh)) =>
          // read plainly under the round's lock (READ COMMITTED: the latest committed; a job of the
          // round is queued and ends under it), before an insert whose duplicate key would lock the
          // running job's row, which its step holds before the round
          JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(fresh.getId)) match {
            case Some(active) => Busy(active)
            case None =>
              refusal(fresh, run, jurorId, frozenByUs = false) match {
                case Some(error) => Refused(error)
                case None if signature(jury(fresh).jurors) != shownJury => JuryChanged
                case None =>
                  jobs.enqueue(NewJob(
                    kind = Kind,
                    contestId = Some(fresh.contestId),
                    subjectType = Some(RoundDistributionHandler.SubjectType),
                    subjectId = fresh.id,
                    dedupeKey = Some(RoundDistributionHandler.dedupeKey(fresh.getId)),
                    params = Json.obj("roundId" -> fresh.getId, "add" -> jurorId, "donors" -> Seq.empty[Long],
                      "by" -> by.getId),
                    state = Json.toJsObject(RebalanceState()),
                    progress = Progress(unit = Some("files")),
                    createdBy = by.id
                  )) match {
                    case Right(job)  => Queued(job)
                    case Left(other) => Busy(other)
                  }
              }
          }
        case _ => Refused(JobError(MissingKey, Seq(round.getId.toString)))
      }
    }
    outcome match {
      case Queued(job) =>
        adminLog.info(s"action=juror_add accountId=${by.getId} contestId=${round.contestId} roundId=${round.getId} " +
          s"jurorId=$jurorId jobId=${job.id} state=queued")
        jobs.started(job.id)
        Queued(JobJdbc.findById(job.id).getOrElse(job))
      case other => other
    }
  }
}

object RoundRebalanceService {

  /** Starts or stops a juror of the round (the round stat table's Start/Stop), under the round's
    * lock: refused while a job holds the round's key (a distribution, or a juror being added), as
    * that job reads the round's jurors as they were. A plain read of the key under the lock, as
    * [[RoundRebalanceService.add]] does.
    *
    * @return false if refused
    */
  def setJurorActive(roundId: Long, userId: Long, active: Boolean): Boolean =
    WriteTx.write { implicit session =>
      RoundRebalanceHandler.lockRun(roundId)
      if (JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(roundId)).isDefined) false
      else {
        sql"UPDATE round_user SET active = $active WHERE round_id = $roundId AND user_id = $userId".update()
        true
      }
    }

  /** A juror of the round, active or stopped. */
  final case class JurorRow(user: User, active: Boolean)

  /** The round's jurors, and the contest's jurors not in it. */
  final case class Jury(jurors: Seq[JurorRow], candidates: Seq[User])

  /** @param topUp the juror is already an active juror of the round: the add gives only what they
    *              lack
    */
  final case class Preview(round: Round, juror: Option[User], refusal: Option[JobError], files: Long, jury: Jury,
      signature: String, topUp: Boolean = false)

  /** The jury as the page showed it: active and stopped jurors' ids. A change between the page and
    * its POST shows the page again with fresh figures.
    */
  def signature(jurors: Seq[JurorRow]): String = {
    val (active, stopped) = jurors.partition(_.active)
    active.map(_.user.getId).sorted.mkString(",") + "|" + stopped.map(_.user.getId).sorted.mkString(",")
  }

  sealed trait Outcome
  final case class Queued(job: Job) extends Outcome
  final case class Refused(error: JobError) extends Outcome
  final case class Busy(job: Job) extends Outcome
  case object JuryChanged extends Outcome
}
