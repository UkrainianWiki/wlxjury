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

  /** What adding `jurorId` would do now: the refusal if any, else the files to give (every file
    * to every juror) or each ticked donor's share (N > 0). `donors`: those ticked, by default the
    * round's active jurors (D1: the stopped ones listed unticked). Plain reads (the POST checks
    * again under the round's lock).
    */
  def preview(round: Round, jurorId: Long, donors: Option[Seq[Long]] = None): Preview = DB.readOnly { implicit session =>
    val juror = User.findById(jurorId).filter(_ => eligibleJuror(round.contestId, jurorId).isDefined)
    val busy = JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId))
    val run = RoundRun(round.active, round.distributing, round.freezeCount,
      activeBeforeFreeze = false)
    val j = jury(round)
    val ticked = donors.getOrElse(j.jurors.filter(_.active).map(_.user.getId)).distinct.filterNot(_ == jurorId)
    val refused = busy.map(j => JobError(BusyKey, Seq(round.number.toString)))
      .orElse(if (round.inReview) reviewRefusal(round, jurorId) else refusal(round, run, jurorId, ticked, frozenByUs = false))
    // the donors' files the receiver could take (not those of pages they hold), and the receiver's own
    val unrated = unratedCounts(round.getId, j.jurors.map(_.user.getId).filterNot(_ == jurorId), receiver = Some(jurorId)) ++
      unratedCounts(round.getId, Seq(jurorId))
    val share =
      if (refused.isDefined || round.distribution == 0 || round.inReview) Map.empty[Long, Long]
      else quotas(unrated.getOrElse(jurorId, 0L), ticked.map(d => d -> unrated.getOrElse(d, 0L)))
    val files =
      if (refused.isDefined || round.inReview) 0L
      else if (round.distribution == 0) toGiveCount(round.getId, jurorId)
      else share.values.sum
    Preview(round, juror, refused, files, j, signature(j.jurors), ticked, unrated, share,
      restart = j.jurors.exists(r => r.user.id.contains(jurorId) && !r.active),
      topUp = j.jurors.exists(r => r.user.id.contains(jurorId) && r.active))
  }

  /** Queues the add of `jurorId` by `by`, if the round's jury is still `shownJury` (the page's
    * [[signature]]) and nothing refuses it, all under the round's lock; the job runs once this
    * has committed.
    */
  def add(round: Round, jurorId: Long, by: User, shownJury: String, donors: Seq[Long] = Nil): Outcome = {
    val ticked = donors.distinct.filterNot(_ == jurorId).sorted
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
            // held in review (P3): only the juror's round_user row; the review's Distribute gives
            // them their share (plan §5)
            case None if fresh.inReview =>
              reviewRefusal(fresh, jurorId) match {
                case Some(error) => Refused(error)
                case None =>
                  sql"""INSERT INTO round_user (user_id, round_id, role, active) VALUES ($jurorId, ${fresh.getId}, ${User.JURY_ROLE}, TRUE)
                        ON DUPLICATE KEY UPDATE active = TRUE""".update()
                  AddedToReview
              }
            case None =>
              refusal(fresh, run, jurorId, ticked, frozenByUs = false) match {
                case Some(error) => Refused(error)
                case None if signature(jury(fresh).jurors) != shownJury => JuryChanged
                case None =>
                  jobs.enqueue(NewJob(
                    kind = Kind,
                    contestId = Some(fresh.contestId),
                    subjectType = Some(RoundDistributionHandler.SubjectType),
                    subjectId = fresh.id,
                    dedupeKey = Some(RoundDistributionHandler.dedupeKey(fresh.getId)),
                    params = Json.obj("roundId" -> fresh.getId, "add" -> jurorId, "donors" -> ticked,
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
          s"jurorId=$jurorId donors=${ticked.mkString(",")} jobId=${job.id} state=queued")
        jobs.started(job.id)
        Queued(JobJdbc.findById(job.id).getOrElse(job))
      case AddedToReview =>
        adminLog.info(s"action=juror_add accountId=${by.getId} contestId=${round.contestId} roundId=${round.getId} " +
          s"jurorId=$jurorId state=review")
        AddedToReview
      case other => other
    }
  }

  /** Why adding `jurorId` to a round held in review is refused, if it is: a legacy round, or not an
    * active jury member of the contest. Nothing moves, so nothing else refuses it.
    */
  private def reviewRefusal(round: Round, jurorId: Long)(implicit session: DBSession): Option[JobError] =
    if (!hasRoundUsers(round.getId)) Some(JobError(LegacyKey, Seq(round.number.toString)))
    else if (eligibleJuror(round.contestId, jurorId).isEmpty) Some(JobError(NotJurorKey, Seq(jurorId.toString)))
    else None
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

  /** What the add page shows for the juror chosen.
    *
    * @param files   what the juror gets: every file they don't have (every file to every juror), or
    *                the sum of the donors' shares
    * @param donors  the jurors ticked as donors (N > 0)
    * @param unrated each round juror's (and the new juror's) unrated files
    * @param share   each donor's share (water-filling, [[RoundRebalanceHandler.quotas]])
    * @param restart the juror is a stopped juror of the round, started again by the add
    * @param topUp   the juror is already an active juror of the round: the add gives only what they
    *                lack (N = 0), or a share counting their own unrated files (N > 0)
    */
  final case class Preview(round: Round, juror: Option[User], refusal: Option[JobError], files: Long, jury: Jury,
      signature: String, donors: Seq[Long] = Nil, unrated: Map[Long, Long] = Map.empty, share: Map[Long, Long] = Map.empty,
      restart: Boolean = false, topUp: Boolean = false) {

    /** The ticked donors' unrated files. */
    def donorsUnrated: Long = donors.map(unrated.getOrElse(_, 0L)).sum
  }

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
  /** Added to a round held in review: its round_user row only (P3). */
  case object AddedToReview extends Outcome
}
