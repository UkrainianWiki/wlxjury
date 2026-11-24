package services

import db.scalikejdbc._
import play.api.Logger
import play.api.libs.json._
import scalikejdbc._
import services.jobs._

import javax.inject.{Inject, Singleton}

/** The `account_join` job (the account join plan's J-2): joins the account `params.joinedId` into
  * `params.keptId` for the performer `params.by`, done from `params.contestId`, queued by
  * [[AccountJoin.start]] (dedupe key `account_join:user:<kept id>`; its subject is the kept account).
  *
  * Every step is one transaction under the join plan's lock order: the job's row (the scheduler),
  * both accounts' users rows (ascending id), then `user_contest` / `account_token`, then a round
  * (`FOR UPDATE`, [[RoundWrites]]) and its rows (`vote` → `round_user` → `criteria_rate` →
  * `vote_aside` → `round_image`, [[AccountJoinJdbc.moveRound]]). Steps:
  *  1. "start": both accounts' sessions ended (`auth_version`), their API tokens revoked, the
  *     joined account's rounds listed (its `vote`, `vote_aside` and `round_user` rows). From now on
  *     the joined account can't sign in ([[controllers.LoginResults.signedIn]]) and so can't vote,
  *     and neither account is added to a round ([[AccountJoinJdbc.joining]], checked under the
  *     round lock where `round_user` rows are inserted);
  *  2. "rounds": one round per step: the round locked; if a job holds its key or it is frozen, the
  *     job fails without writing ([[RoundBusyKey]], Retry once that job ends); the rows moved
  *     ([[AccountJoinJdbc.moveRound]]); a collision found now (it can't come from votes, which are
  *     refused) fails the job with nothing written for the round; `RoundImageInvariants.violations`
  *     read before and after, and a new one rolls the round back (to a savepoint) and fails the job;
  *  3. "finish": under the users rows, every round where the joined account still has a row (a
  *     vote that committed after its round's step) swept the same way; its comments given to the
  *     kept account; its memberships merged into the kept account's ([[AccountJoinJdbc.mergeMemberships]]);
  *     its identities unlinked (J3); the kept account's wiki account and verification set by the
  *     CoI rule ([[AccountJoin.Coi]]); both accounts' unused links revoked; `account_joined` change
  *     log rows on both; the join recorded (`account_join_request`, `done`); then the joined account
  *     retired ([[AccountRetirement.retireIn]], from the join's contest, keeping its `vote_orphan`
  *     rows). A refusal of the retirement rolls the step back (to a savepoint) and fails the job.
  *
  * Steps are idempotent: a retried step finds what committed. '''Cancel''' only before the first
  * round step commits ([[onCancelled]] refuses after: moved rows are not moved back); a failed job
  * is retried. The audit is a `security.account` line when done (`account_joined keptAccountId=
  * joinedAccountId= byAccountId= contestId= requestId= ...` with counts), and the mail to both
  * addresses only if the performer ticked it ([[AccountJoinMail]], after the commit).
  */
@Singleton
class AccountJoinHandler @Inject() (mail: AccountJoinMail) extends JobHandler {

  import AccountJoinHandler._

  private val accountLogger = Logger("security.account")

  val kind: String = AccountJoinJdbc.Kind

  override def title(job: Job): String = {
    val p = paramsOf(job)
    s"Joining account #${p.joinedId} into #${p.keptId}"
  }

  override def details(job: Job): JsObject = {
    val p = paramsOf(job)
    val st = stateOf(job)
    Json.obj("keptId" -> p.keptId, "joinedId" -> p.joinedId, "phase" -> st.phase, "cancellable" -> !st.roundStepDone,
      "votesMoved" -> st.votes, "votesAsideMoved" -> st.aside, "assignmentsMerged" -> (st.mergedVotes + st.mergedAside),
      "roundsMoved" -> st.roundUsers)
  }

  /** Hook for tests: runs in a round step's transaction, the round locked, before its rows move. */
  protected def beforeRound(roundId: Long)(implicit session: DBSession): Unit = ()

  /** Hook for tests: runs in the last step's transaction, the users rows locked, before the sweep. */
  protected def beforeFinish()(implicit session: DBSession): Unit = ()

  /** Its performer's rule over both accounts (the joined one only while it is not retired). */
  override def mayManage(job: Job, user: User): Boolean = {
    val p = paramsOf(job)
    AccountJoin.mayPerform(user, User.findByIds(Seq(p.keptId, p.joinedId)).filter(_.deletedAt.isEmpty))
  }

  override def onCancelled(job: Job, ctx: StepContext): JsObject =
    if (stateOf(job).roundStepDone) throw JobScheduler.CancelRefusal(CancelRefusedKey)
    else job.state

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val p = paramsOf(job)
    val st = stateOf(job)
    val locked = AccountJoinRequestJdbc.lockAccounts(Seq(p.keptId, p.joinedId))
    if (locked.size != 2 || locked.exists(_.deletedAt.nonEmpty))
      return failed(st, JobError(GoneKey, Seq(p.keptId.toString, p.joinedId.toString)))
    st.phase match {
      case Phase.Start  => start(p, st)
      case Phase.Rounds => roundStep(p, st)
      case _            => finish(job, p, st, ctx)
    }
  }

  private def progress(st: JoinState, done: Long): Progress =
    Progress(done = Some(done), total = Some(st.rounds.size + 2L), unit = Some("steps"))

  private def continue(st: JoinState, done: Long): StepResult =
    StepResult.Continue(Json.toJsObject(st), progress(st, done))

  private def failed(st: JoinState, error: JobError): StepResult =
    StepResult.Failed(error, Json.toJsObject(st), progress(st, st.next.toLong + (if (st.phase == Phase.Start) 0 else 1)))

  private def start(p: Params, st: JoinState)(implicit session: DBSession): StepResult = {
    val ids = Seq(p.keptId, p.joinedId).sorted
    AccountJoinJdbc.endSessions(ids)
    val tokens = ids.map(ApiTokenJdbc.revokeAll(_)).sum
    val rounds = AccountJoinJdbc.roundsOf(p.joinedId)
    val next = st.copy(phase = if (rounds.isEmpty) Phase.Finish else Phase.Rounds, rounds = rounds, next = 0,
      apiTokens = st.apiTokens + tokens)
    continue(next, 1)
  }

  /** The label of a round in a job error: its name and id, or its id. */
  private def label(roundId: Long)(implicit session: DBSession): String =
    Round.findById(roundId).fold(s"#$roundId")(r => s"${r.description} (#$roundId)")

  private def savepoint[A](f: => Either[JobError, A])(implicit session: DBSession): Either[JobError, A] = {
    val conn = session.connection
    val sp = conn.setSavepoint()
    val result =
      try f
      catch { case e: Throwable => conn.rollback(sp); throw e }
    if (result.isLeft) conn.rollback(sp) else conn.releaseSavepoint(sp)
    result
  }

  /** Moves the round's rows (the round locked by the caller, if it exists), with the invariants
    * checked before and after: a collision or a new violation is Left, everything rolled back.
    */
  private def moveChecked(roundId: Long, p: Params, exists: Boolean)(implicit
      session: DBSession): Either[JobError, AccountJoinJdbc.Moved] = {
    val before = if (exists) RoundImageInvariants.violations(roundId).toSet else Set.empty
    AccountJoinJdbc.moveRound(roundId, p.keptId, p.joinedId) match {
      case Left(collisions) =>
        Left(JobError(CollisionKey, Seq(label(roundId), collisions.size.toString)))
      case Right(moved) =>
        val added = if (exists) RoundImageInvariants.violations(roundId).toSet -- before else Set.empty
        if (added.nonEmpty) {
          accountLogger.warn(s"account_join_invariants roundId=$roundId keptAccountId=${p.keptId} " +
            s"joinedAccountId=${p.joinedId} violations=${added.map(v => s"${v.invariant}:${v.pageId}").mkString(",")}")
          Left(JobError(InvariantKey, Seq(label(roundId))))
        } else Right(moved)
    }
  }

  private def roundStep(p: Params, st: JoinState)(implicit session: DBSession): StepResult = {
    if (st.next >= st.rounds.size) return continue(st.copy(phase = Phase.Finish), st.rounds.size + 1L)
    val roundId = st.rounds(st.next)
    val exists = AccountJoinJdbc.existing(Seq(roundId)).nonEmpty
    if (exists) {
      RoundWrites.lock(Seq(roundId))
      if (AccountJoinJdbc.roundBusy(roundId)) return failed(st, JobError(RoundBusyKey, Seq(label(roundId))))
    }
    beforeRound(roundId)
    savepoint(moveChecked(roundId, p, exists)) match {
      case Left(error) => failed(st, error)
      case Right(m) =>
        val next = st.copy(next = st.next + 1, roundStepDone = true, votes = st.votes + m.votes, aside = st.aside + m.aside,
          mergedVotes = st.mergedVotes + m.mergedVotes, mergedAside = st.mergedAside + m.mergedAside,
          roundUsers = st.roundUsers + m.roundUser)
        continue(if (next.next >= next.rounds.size) next.copy(phase = Phase.Finish) else next, next.next + 1L)
    }
  }

  /** Who finishes the join: its performer if they may still join the two accounts and retire the
    * joined one from the join's contest, else the user whose Retry queued it again (read again here,
    * under the users rows), else none. Read from the database.
    */
  private def finisher(p: Params, st: JoinState)(implicit session: DBSession): Option[User] = {
    val accounts = Seq(p.keptId, p.joinedId).flatMap(User.findById(_))
    val joined = accounts.find(_.id.contains(p.joinedId))
    def qualifies(u: User) =
      AccountJoin.mayPerform(u, accounts) && joined.exists(j => AccountRetirement.permissionRefusal(u, j, p.contestId).isEmpty)
    (Seq(p.by) ++ st.retriedBy.toSeq).distinct.iterator.flatMap(User.findById(_)).filter(_.deletedAt.isEmpty)
      .find(qualifies)
  }

  override def onRetry(job: Job, ctx: StepContext): JsObject =
    Json.toJsObject(stateOf(job).copy(retriedBy = ctx.by.orElse(stateOf(job).retriedBy)))

  private def finish(job: Job, p: Params, st: JoinState, ctx: StepContext)(implicit session: DBSession): StepResult = {
    // the accounts' memberships and links next, after their users rows and before any round (the
    // lock order): the merge and the retirement write them
    AccountJoinJdbc.lockAccountRows(Seq(p.keptId, p.joinedId))
    beforeFinish()
    // the sweep's rounds, locked in id order after the users rows; a busy one fails before any write
    val remaining = AccountJoinJdbc.remainingRounds(p.joinedId)
    val existing = AccountJoinJdbc.existing(remaining)
    RoundWrites.lock(remaining.filter(existing))
    remaining.filter(existing).find(AccountJoinJdbc.roundBusy) match {
      case Some(busy) => return failed(st, JobError(RoundBusyKey, Seq(label(busy))))
      case None       =>
    }
    if (AccountRetirement.busyRefusal(p.joinedId).nonEmpty)
      return failed(st, JobError(RetireBusyKey, Seq(p.joinedId.toString)))
    // the performer, or (a Retry after the performer lost the right) the user who retried
    val actor = finisher(p, st).getOrElse(return failed(st, JobError(PerformerKey, Seq(p.by.toString))))
    val by = actor.getId
    val outcome = savepoint {
      val swept = remaining.foldLeft[Either[JobError, AccountJoinJdbc.Moved]](Right(AccountJoinJdbc.Moved())) {
        case (Right(acc), r) => moveChecked(r, p, existing(r)).map(acc + _)
        case (left, _)       => left
      }
      swept.flatMap { sweptRows =>
        val kept = User.findById(p.keptId).get
        val joined = User.findById(p.joinedId).get
        val comments = AccountJoinJdbc.moveComments(p.keptId, p.joinedId)
        val memberships = AccountJoinJdbc.mergeMemberships(p.keptId, p.joinedId)
        val identities = UserIdentityJdbc.deleteByUser(p.joinedId)
        // the joined account's series grants move to the kept account, or are revoked where it
        // holds one on the same series (contest series plan §3.3, "Joins and retirement")
        series.SeriesAdmins.onJoin(actor, p.keptId, p.joinedId)
        val coi = AccountJoin.Coi(kept.wikiAccount.map(_.trim).filter(_.nonEmpty), kept.wikiAccountVerified,
          joined.wikiAccount.map(_.trim).filter(_.nonEmpty), joined.wikiAccountVerified)
        if (coi.result != coi.kept || coi.resultVerified != coi.keptVerified) {
          AccountJoinJdbc.setWikiAccount(p.keptId, coi.result, coi.resultVerified)
          if (coi.result != coi.kept)
            AccountChangeJdbc.create(p.keptId, by, p.contestId, AccountChangeJdbc.WikiAccount, coi.kept, coi.result,
              comment = Some(s"#${p.joinedId}"))
        }
        val links = AccountJoinJdbc.revokeLinks(Seq(p.keptId))
        AccountChangeJdbc.create(p.keptId, by, p.contestId, AccountChangeJdbc.AccountJoined,
          mailSent = Some(p.mailOwners), comment = Some(s"#${p.joinedId}"))
        AccountChangeJdbc.create(p.joinedId, by, p.contestId, AccountChangeJdbc.AccountJoined,
          mailSent = Some(p.mailOwners), comment = Some(s"#${p.keptId}"))
        val requestId = AccountJoinJdbc.recordDone(p.keptId, p.joinedId, by, p.contestId)
        try {
          val retired = AccountRetirement.retireIn(actor, p.joinedId, p.contestId, Some(p.keptId), keepOrphans = true)
          Right((sweptRows, comments, memberships.size, identities, links + retired.links, requestId, retired, kept))
        } catch {
          case r: AccountRetirement.Refused => Left(JobError(RetireRefusedKey, Seq(p.joinedId.toString, r.refusal.key)))
        }
      }
    }
    outcome match {
      case Left(error) => failed(st, error)
      case Right((swept, comments, memberships, identities, links, requestId, retired, kept)) =>
        val done = st.copy(phase = Phase.Done, votes = st.votes + swept.votes, aside = st.aside + swept.aside,
          mergedVotes = st.mergedVotes + swept.mergedVotes, mergedAside = st.mergedAside + swept.mergedAside,
          roundUsers = st.roundUsers + swept.roundUser, comments = comments, memberships = memberships,
          identities = identities, links = links, requestId = Some(requestId), finishedBy = Some(by))
        ctx.afterCommit {
          accountLogger.info(s"account_joined keptAccountId=${p.keptId} joinedAccountId=${p.joinedId} " +
            s"byAccountId=$by startedBy=${p.by} contestId=${p.contestId.getOrElse("none")} requestId=$requestId jobId=${job.id} " +
            s"votes=${done.votes} votesAside=${done.aside} merged=${done.mergedVotes + done.mergedAside} " +
            s"rounds=${done.roundUsers} memberships=$memberships comments=$comments identitiesUnlinked=$identities " +
            s"apiTokens=${done.apiTokens} links=$links")
          if (p.mailOwners) mail.send(kept, retired.oldEmail, actor)
        }
        StepResult.Done(Json.toJsObject(done), progress(done, done.rounds.size + 2L))
    }
  }
}

object AccountJoinHandler {

  object Phase {
    val Start = "start"
    val Rounds = "rounds"
    val Finish = "finish"
    val Done = "done"
  }

  /** The job's resume cursor (`job.state`).
    *
    * @param rounds        the joined account's rounds, listed by "start"
    * @param next          the next of them to move
    * @param roundStepDone a round step committed: Cancel is refused from now on
    * @param retriedBy     who last retried it (finishes it if the performer no longer may)
    * @param finishedBy    who it was finished for (the performer, or the user who retried)
    * @param abandonedBy   root, who abandoned it after a round step ([[AccountJoin.abandon]])
    */
  final case class JoinState(
      phase: String = Phase.Start,
      rounds: Seq[Long] = Nil,
      next: Int = 0,
      roundStepDone: Boolean = false,
      votes: Long = 0,
      aside: Long = 0,
      mergedVotes: Long = 0,
      mergedAside: Long = 0,
      roundUsers: Long = 0,
      apiTokens: Int = 0,
      comments: Int = 0,
      memberships: Int = 0,
      identities: Int = 0,
      links: Int = 0,
      requestId: Option[Long] = None,
      retriedBy: Option[Long] = None,
      finishedBy: Option[Long] = None,
      abandonedBy: Option[Long] = None
  )

  object JoinState {
    implicit val format: OFormat[JoinState] = Json.using[Json.WithDefaultValues].format[JoinState]
  }

  final case class Params(keptId: Long, joinedId: Long, by: Long, contestId: Option[Long], mailOwners: Boolean)

  def paramsOf(job: Job): Params = {
    val j = job.params
    Params((j \ "keptId").as[Long], (j \ "joinedId").as[Long], (j \ "by").as[Long], (j \ "contestId").asOpt[Long],
      (j \ "notify").asOpt[Boolean].getOrElse(false))
  }

  def stateOf(job: Job): JoinState = job.state.asOpt[JoinState].getOrElse(JoinState())

  val GoneKey = "job.error.join.gone"
  val RoundBusyKey = "job.error.join.round.busy"
  val CollisionKey = "job.error.join.collision"
  val InvariantKey = "job.error.join.invariant"
  val RetireBusyKey = "job.error.join.retire.busy"
  val RetireRefusedKey = "job.error.join.retire.refused"
  val PerformerKey = "job.error.join.performer"
  val CancelRefusedKey = "job.cancel.join.started"
}

/** The mail of a join, only if the performer ticked "Tell both owners by email" (the maintainer's
  * standing rule: never a mail without an explicit, unticked-by-default choice): to the kept
  * account's address and to the joined account's former one, from [[UserService.systemSender]],
  * the other address masked, no link. Failures are logged (`account_join_mail_failed`), never
  * fail the join.
  */
@Singleton
class AccountJoinMail @Inject() (userService: UserService, messagesApi: play.api.i18n.MessagesApi) {

  private val accountLogger = Logger("security.account")

  /** Mails both owners; whether each mail went out (kept, joined). */
  def send(kept: User, joinedOldEmail: Option[String], by: User): (Boolean, Boolean) = {
    val keptEmail = Option(kept.emailLo).filter(_.nonEmpty)
    implicit val messages: play.api.i18n.Messages =
      messagesApi.preferred(kept.lang.map(play.api.i18n.Lang(_)).toSeq)
    val byName = Option(by.fullname).filter(_.trim.nonEmpty).getOrElse(messages("account.join.mail.by.unnamed"))
    def masked(e: Option[String]) = e.fold(messages("email.none"))(UserService.maskEmail)
    def mailTo(address: Option[String], body: String): Boolean =
      address.exists { a =>
        try {
          userService.sendMail.sendMail(userService.systemSender, kept.copy(email = a), messages("account.join.mail.subject"), body)
          true
        } catch {
          case scala.util.control.NonFatal(e) =>
            accountLogger.warn(s"account_join_mail_failed keptAccountId=${kept.getId} error=${e.getClass.getName}")
            false
        }
      }
    val toKept = mailTo(keptEmail, messages("account.join.mail.kept", masked(joinedOldEmail), byName))
    val toJoined = mailTo(joinedOldEmail, messages("account.join.mail.joined", masked(keptEmail), byName))
    accountLogger.info(s"account_join_mail keptAccountId=${kept.getId} kept=${if (toKept) "sent" else "not_sent"} " +
      s"joined=${if (toJoined) "sent" else "not_sent"}")
    (toKept, toJoined)
  }
}
