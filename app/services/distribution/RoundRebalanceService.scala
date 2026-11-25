package services.distribution

import db.scalikejdbc._
import play.api.libs.json.Json
import scalikejdbc._
import services.jobs._

import javax.inject.Inject

/** Adding a juror to a running or a stopped round (docs/plans/2026-11-16-round-editing.md, §5; D3
  * revised 2026-11-25): what the add
  * page shows ([[preview]]) and queueing the job ([[add]], a [[RoundRebalanceHandler]] job).
  */
class RoundRebalanceService @Inject() (jobs: JobScheduler) {

  import RoundRebalanceService._
  import RoundRebalanceHandler._

  /** The round's jurors as the page lists them, and the contest's jurors who may be added (an
    * active jury membership, not deleted, not in the round), by id. A NULL `round_user.active` (a
    * schema before V108) is stopped, as [[RoundRebalanceHandler.jurorState]] and the add's refusals take it.
    */
  def jury(round: Round)(implicit session: DBSession = AutoSession): Jury = {
    val rows = sql"SELECT user_id, COALESCE(active, FALSE) FROM round_user WHERE round_id = ${round.getId}"
      .map(rs => rs.long(1) -> rs.boolean(2)).list()
    val users = User.findByIds(rows.map(_._1)).map(u => u.getId -> u).toMap
    val jurors = rows.sortBy(_._1).flatMap { case (id, active) => users.get(id).map(JurorRow(_, active)) }
    val inRound = rows.map(_._1).toSet
    val candidates = User.loadJurors(round.contestId).filter(u => u.deletedAt.isEmpty && !inRound.contains(u.getId))
    Jury(jurors, candidates.sortBy(_.getId))
  }

  /** What adding `jurorId` would do now: the refusal if any (an active juror of the round among
    * them: [[alreadyJuror]]), else the files to give (every file to every juror) or each ticked
    * donor's share (N > 0). `donors`: those ticked, by default the
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
      .orElse(alreadyJuror(round, jurorId))
      .orElse(if (round.inReview) reviewRefusal(round, jurorId) else refusal(round, run, jurorId, ticked, frozenByUs = false))
    // the donors' files the receiver could take (not those of pages they hold), and the receiver's own
    val unrated = unratedCounts(round.getId, j.jurors.map(_.user.getId).filterNot(_ == jurorId), receiver = Some(jurorId)) ++
      unratedCounts(round.getId, Seq(jurorId))
    // the share (N > 0): the files waiting for a juror first, the rest from the donors, as the job's "stop"
    val split =
      if (refused.isDefined || round.distribution == 0 || round.inReview) None
      else Some(addSplit(unrated.getOrElse(jurorId, 0L), ticked.map(d => d -> unrated.getOrElse(d, 0L)),
        RoundReassign.waitingForCount(round.getId, round.distribution, jurorId)))
    val files =
      if (refused.isDefined || round.inReview) 0L
      else if (round.distribution == 0) toGiveCount(round.getId, jurorId)
      else split.fold(0L)(_.total)
    Preview(round, juror, refused, files, j, signature(j.jurors), ticked, unrated, split.fold(Map.empty[Long, Long])(_.fromDonors),
      restart = j.jurors.exists(r => r.user.id.contains(jurorId) && !r.active),
      waiting = split.fold(0L)(_.waiting), waitingLeft = split.fold(0L)(_.waitingLeft), fairShare = split.fold(0L)(_.share))
  }

  /** Queues the add of `jurorId` by `by`, if the round's jury is still `shownJury` (the page's
    * [[signature]]) and nothing refuses it, all under the round's lock; the job runs once this
    * has committed. An active juror of the round is refused ([[alreadyJuror]]), with nothing
    * written; a stopped one is started again only with `restart` (the page reached by the round
    * statistics' "Start and give a fair share", which shows that juror as its subject), refused
    * otherwise ([[RoundRebalanceHandler.StoppedJurorKey]]).
    */
  def add(round: Round, jurorId: Long, by: User, shownJury: String, donors: Seq[Long] = Nil,
      restart: Boolean = false): Outcome = {
    val ticked = donors.distinct.filterNot(_ == jurorId).sorted
    val outcome = WriteTx.write { implicit session =>
      val run = lockRun(round.getId)
      val fresh = Round.findById(round.getId)
      (run, fresh) match {
        case (Some(run), Some(fresh)) =>
          // read plainly under the round's lock (READ COMMITTED: the latest committed; a job of the
          // round is queued and ends under it), before an insert whose duplicate key would lock the
          // running job's row, which its step holds before the round
          lazy val already = alreadyJuror(fresh, jurorId)
          lazy val stoppedNoRestart = Option.when(!restart && jurorState(fresh.getId, jurorId).contains(false))(
            JobError(StoppedJurorKey, Seq(nameOf(jurorId), fresh.number.toString)))
          JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(fresh.getId)) match {
            case Some(active) => Busy(active)
            // already an active juror of the round: refused, nothing written (read under the round's
            // lock, which Start/Stop takes too)
            case None if already.isDefined => Refused(already.get)
            // a stopped juror only by the restart page (its subject), never by the select
            case None if stoppedNoRestart.isDefined => Refused(stoppedNoRestart.get)
            // held in review (P3): only the juror's round_user row; the review's Distribute gives
            // them their share (plan §5)
            case None if fresh.inReview =>
              reviewRefusal(fresh, jurorId) match {
                case Some(error) => Refused(error)
                // in an account join (J-2): refused before the users row's lock, which the join holds
                case None if joining(jurorId) => Refused(JobError(JoiningKey, Seq(jurorId.toString)))
                // the juror's users row share-locked and re-checked (A-4): retired meanwhile, refused
                case None if !liveJuror(jurorId) => Refused(JobError(NotJurorKey, Seq(jurorId.toString)))
                case None if joining(jurorId) => Refused(JobError(JoiningKey, Seq(jurorId.toString)))
                case None =>
                  sql"""INSERT INTO round_user (user_id, round_id, role, active) VALUES ($jurorId, ${fresh.getId}, ${User.JURY_ROLE}, TRUE)
                        ON DUPLICATE KEY UPDATE active = TRUE""".update()
                  AddedToReview
              }
            case None =>
              refusal(fresh, run, jurorId, ticked, frozenByUs = false) match {
                case Some(error) => Refused(error)
                case None if signature(jury(fresh).jurors) != shownJury => JuryChanged
                case None if joining(jurorId) => Refused(JobError(JoiningKey, Seq(jurorId.toString)))
                // checked again under the same lock by the job's "stop" phase, which inserts the row
                case None if !liveJuror(jurorId) => Refused(JobError(NotJurorKey, Seq(jurorId.toString)))
                case None if joining(jurorId) => Refused(JobError(JoiningKey, Seq(jurorId.toString)))
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

  /** What reassigning `from`'s unrated files would do now (§7): the refusal if any, the juror's files,
    * and for each receiver the files it gets, with the files that can't move (every receiver has
    * them: they wait for a juror). `receivers`: those ticked, by default the round's active jurors (D1:
    * the stopped ones listed unticked). The same choice of receiver per file as the job
    * ([[RoundReassign.allocate]], by vote id), from plain reads (the POST checks again under the
    * round's lock).
    */
  def reassignPreview(round: Round, from: Long, receivers: Option[Seq[Long]] = None): ReassignPreview = DB.readOnly {
    implicit session =>
      val j = jury(round)
      val others = j.jurors.filterNot(_.user.id.contains(from))
      // by default the active jurors (D1) who may receive: an active jury membership of the contest
      val ticked = receivers.getOrElse(others.filter(o => o.active && eligibleJuror(round.contestId, o.user.getId).isDefined)
        .map(_.user.getId)).distinct.filterNot(_ == from).sorted
      val busy = JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(round.getId))
      val run = RoundRun(round.active, round.distributing, round.freezeCount, activeBeforeFreeze = false)
      val refused = busy.map(_ => JobError(BusyKey, Seq(round.number.toString)))
        .orElse(reassignRefusal(round, run, from, ticked, frozenByUs = false, fromName = Some(nameOf(j, from))))
      val files = RoundReassign.jurorFiles(round.getId, from)
      val shares = round.distribution > 0
      val to = if (shares) ticked else Nil
      val unrated = unratedCounts(round.getId, others.map(_.user.getId))
      val (given, waiting, asideGiven) =
        if (!shares) (Map.empty[Long, Long], 0L, 0L)
        else {
          val pages = RoundReassign.unratedRows(round.getId, from, 0L, Int.MaxValue).map(_._2)
          val quota = RoundReassign.fillQuotas(pages.size.toLong, to.map(r => r -> unrated.getOrElse(r, 0L)))
          val (choices, left) = RoundReassign.allocate(pages, RoundReassign.holders(round.getId, pages, to), to, quota)
          val asidePages = sql"""SELECT v.page_id FROM vote_aside v WHERE v.round_id = ${round.getId} AND v.jury_id = $from
                                 AND ${RoundReassign.unrated(sqls"v")} ORDER BY v.id""".map(_.long(1)).list()
          val (asideChoices, _) = RoundReassign.allocate(asidePages, RoundReassign.holders(round.getId, asidePages, to), to, left)
          (choices.flatMap(_._2).groupMapReduce(identity)(_ => 1L)(_ + _), choices.count(_._2.isEmpty).toLong,
            asideChoices.count(_._2.isDefined).toLong)
        }
      ReassignPreview(round, j.jurors.find(_.user.id.contains(from)), refused, files, j, signature(j.jurors), ticked,
        unrated, given, waiting, asideGiven,
        restarted = ticked.filter(r => others.exists(o => o.user.id.contains(r) && !o.active)),
        noMembership = others.map(_.user.getId).filter(id => eligibleJuror(round.contestId, id).isEmpty).toSet,
        tooFewReceivers = !RoundReassign.enoughReceivers(from, RoundReassign.possibleReceivers(round.getId, round.contestId),
          round.distribution))
  }

  /** Queues the reassign of `from`'s unrated files by `by` to `receivers`, if the round's jury is still
    * `shownJury` (the page's [[signature]]) and nothing refuses it, all under the round's lock; the job
    * runs once this has committed (§7).
    */
  def reassign(round: Round, from: Long, by: User, shownJury: String, receivers: Seq[Long]): Outcome = {
    val ticked = receivers.distinct.filterNot(_ == from).sorted
    val outcome = WriteTx.write { implicit session =>
      (lockRun(round.getId), Round.findById(round.getId)) match {
        case (Some(run), Some(fresh)) =>
          // read plainly under the round's lock, as add does
          JobJdbc.activeByDedupeKey(RoundDistributionHandler.dedupeKey(fresh.getId)) match {
            case Some(active) => Busy(active)
            case None =>
              val to = if (fresh.distribution > 0) ticked else Nil
              val shown = jury(fresh)
              // nothing to reassign (0 unrated): refused, no job (the juror isn't stopped either)
              reassignRefusal(fresh, run, from, ticked, frozenByUs = false, fromName = Some(nameOf(shown, from))) match {
                case Some(error) => Refused(error)
                case None if signature(shown.jurors) != shownJury => JuryChanged
                case None =>
                  // outside an account join (J-2), read before and after the receivers' users rows are
                  // share-locked (A-4: retired meanwhile, refused); the job's "stop" checks again
                  val accounts = (to :+ from).distinct
                  def joining = AccountJoinJdbc.joining(accounts).toSeq.sorted.headOption
                  val live = if (joining.isEmpty) User.lockLive(to) else Set.empty[Long]
                  joining.map(a => Refused(JobError(JoiningKey, Seq(a.toString))))
                    .orElse(to.find(r => !live.contains(r)).map(r => Refused(JobError(ReceiverKey, Seq(r.toString, fresh.number.toString)))))
                    .orElse(joining.map(a => Refused(JobError(JoiningKey, Seq(a.toString)))))
                    .getOrElse(enqueueReassign(fresh, from, to, by))
              }
          }
        case _ => Refused(JobError(MissingKey, Seq(round.getId.toString)))
      }
    }
    outcome match {
      case Queued(job) =>
        adminLog.info(s"action=juror_reassign accountId=${by.getId} contestId=${round.contestId} roundId=${round.getId} " +
          s"jurorId=$from receivers=${ticked.mkString(",")} jobId=${job.id} state=queued")
        jobs.started(job.id)
        Queued(JobJdbc.findById(job.id).getOrElse(job))
      case other => other
    }
  }

  private def nameOf(j: Jury, id: Long): String =
    j.jurors.find(_.user.id.contains(id)).fold(id.toString)(_.user.fullname)

  private def enqueueReassign(fresh: Round, from: Long, to: Seq[Long], by: User)(implicit session: DBSession): Outcome =
    jobs.enqueue(NewJob(
      kind = Kind,
      contestId = Some(fresh.contestId),
      subjectType = Some(RoundDistributionHandler.SubjectType),
      subjectId = fresh.id,
      dedupeKey = Some(RoundDistributionHandler.dedupeKey(fresh.getId)),
      params = Json.obj("roundId" -> fresh.getId, "from" -> from, "receivers" -> to, "by" -> by.getId),
      state = Json.toJsObject(ReassignState()),
      progress = Progress(unit = Some("files")),
      createdBy = by.id
    )) match {
      case Right(job)  => Queued(job)
      case Left(other) => Busy(other)
    }

  /** Whether the juror's account is not deleted, read under a shared lock on its users row until
    * the transaction ends ([[User.lockLive]]): a retirement (A-4) either committed first (refused
    * here) or waits for this add and is then refused by its `round_user` row.
    */
  private def liveJuror(jurorId: Long)(implicit session: DBSession): Boolean =
    User.lockLive(Seq(jurorId)).contains(jurorId)

  /** Whether the juror's account is in an account join (J-2,
    * [[db.scalikejdbc.AccountJoinJdbc.joining]]): an add round-first would take its users row after
    * the round, against the join's order, so it is refused while the join holds the account. Read
    * plainly before the users row's shared lock ([[liveJuror]]: no wait on a join's step) and again
    * after it (a join's start takes that row exclusively: it committed before, or waits for this).
    */
  private def joining(jurorId: Long)(implicit session: DBSession): Boolean =
    AccountJoinJdbc.joining(Seq(jurorId)).nonEmpty

  /** The refusal of adding `jurorId` who is already an active juror of the round
    * ([[RoundRebalanceHandler.AlreadyJurorKey]], with their name): only a juror outside the round or a
    * stopped one ("Start and give a fair share…") is added.
    */
  private def alreadyJuror(round: Round, jurorId: Long)(implicit session: DBSession): Option[JobError] =
    Option.when(jurorState(round.getId, jurorId).contains(true))(
      JobError(AlreadyJurorKey, Seq(nameOf(jurorId), round.number.toString)))

  private def nameOf(jurorId: Long)(implicit session: DBSession): String =
    User.findById(jurorId).map(_.fullname).filter(_.nonEmpty).getOrElse(jurorId.toString)

  /** Why adding `jurorId` to a round held in review is refused, if it is: a legacy round (jurors only
    * by their votes), or not an active jury member of the contest. A round created for review with
    * no juror yet (no `round_user` row, no vote) isn't legacy: its first juror is added. Nothing
    * moves, so nothing else refuses it.
    */
  private def reviewRefusal(round: Round, jurorId: Long)(implicit session: DBSession): Option[JobError] =
    if (!hasRoundUsers(round.getId) && RoundImageJdbc.hasVotes(round.getId)) Some(JobError(LegacyKey, Seq(round.number.toString)))
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

  /** The round's jurors, and the contest's jurors not in it (the add page offers these and the
    * stopped jurors, never an active one).
    */
  final case class Jury(jurors: Seq[JurorRow], candidates: Seq[User])

  /** What the add page shows for the juror chosen.
    *
    * @param files   what the juror gets: every file they don't have (every file to every juror), or
    *                the sum of the donors' shares
    * @param donors  the jurors ticked as donors (N > 0)
    * @param unrated each round juror's (and the new juror's) unrated files
    * @param share   each donor's share (water-filling, [[RoundRebalanceHandler.quotas]]; with waiting files
    *                taken, the rest of the share, [[RoundRebalanceHandler.addSplit]])
    * @param restart the juror is a stopped juror of the round, started again by the add
    * @param waiting the files waiting for a juror the juror takes first, within the share (N > 0)
    * @param waitingLeft the files waiting for a juror they could take beyond the share: they keep waiting
    * @param fairShare the share before the waiting files (N > 0): 0 when no ticked donor has more unrated
    *                files than the juror (they then get every waiting file they may take, nothing else)
    */
  final case class Preview(round: Round, juror: Option[User], refusal: Option[JobError], files: Long, jury: Jury,
      signature: String, donors: Seq[Long] = Nil, unrated: Map[Long, Long] = Map.empty, share: Map[Long, Long] = Map.empty,
      restart: Boolean = false, waiting: Long = 0L, waitingLeft: Long = 0L,
      fairShare: Long = 0L) {

    /** The files the donors give (N > 0). */
    def fromDonors: Long = share.values.sum


    /** The ticked donors' unrated files. */
    def donorsUnrated: Long = donors.map(unrated.getOrElse(_, 0L)).sum
  }

  /** What the reassign page shows (§7).
    *
    * @param from       the juror whose unrated files go (None: not a juror of the round)
    * @param files      the juror's files: assigned, rated, unrated, set aside
    * @param receivers  the jurors ticked to receive files
    * @param unrated    each other round juror's unrated files now
    * @param given      per receiver, the files it gets (N > 0)
    * @param waiting    the files that can't move: every ticked receiver has them (N > 0)
    * @param asideGiven the juror's unrated files set aside that go to receivers too
    * @param restarted  ticked receivers who are stopped: started again
    * @param noMembership the round's other jurors with no active jury membership of the contest: they can't
    *                   receive files (listed unticked, marked)
    * @param tooFewReceivers too few other jurors could take the files ([[RoundReassign.enoughReceivers]]):
    *                   refused ([[RoundRebalanceHandler.AloneKey]]), no receivers to tick
    */
  final case class ReassignPreview(round: Round, from: Option[JurorRow], refusal: Option[JobError],
      files: RoundReassign.JurorFiles, jury: Jury, signature: String, receivers: Seq[Long], unrated: Map[Long, Long],
      given: Map[Long, Long], waiting: Long, asideGiven: Long, restarted: Seq[Long], noMembership: Set[Long] = Set.empty,
      tooFewReceivers: Boolean = false) {

    /** Files given in all (N > 0). */
    def moved: Long = given.values.sum
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
