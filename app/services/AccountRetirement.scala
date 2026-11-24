package services

import db.scalikejdbc.{AccountChangeJdbc, ApiTokenJdbc, User, UserContestJdbc, WriteTx}
import play.api.Logger
import scalikejdbc._
import services.distribution.{RoundDistributionHandler, RoundRebalanceHandler}
import services.jobs.JobStatus

/** Retiring an empty account (docs/plans/2026-11-16-existing-accounts.md, "Retiring an account",
  * PR A-4; decision A3 by the maintainer 2026-11-16: opt-in, an unticked checkbox with the Edit
  * user panel's "Add that account to this contest instead"). The first writer of
  * `users.deleted_at`; the account join's J-2 reuses [[retireIn]].
  *
  * '''Who and which account:''' the actor may change the account
  * ([[controllers.UserController.mayEditAccountNow]]: root, or an admin of every contest it is in
  * or worked in), it is neither the actor nor root, and, unless the actor is root, it holds no
  * active membership or live invitation with a role other than `jury` or `organizer`
  * ([[ShareLinkService.handOverRefusal]]'s rule): an admin account is retired by root only. And,
  * unless the actor is root, the account is tied to the contest the retirement is done from: a
  * membership there (in any role) or a live invitation into it (review: an account with no ties
  * anywhere passes the rules above on an empty set, and must not be retired by any admin).
  *
  * '''Empty:''' no `vote`, `vote_aside` or `vote_orphan` row (a criteria rate belongs to a vote
  * row, so none either), no comment, no `round_user` row, no linked identity, no usable API token.
  *
  * '''Not during a round job''' (plan M4, as the maintainer decided 2026-11-17): refused while
  * a `round_distribution` or `round_rebalance` job is queued or running in one of the account's
  * contests (its memberships and live invitations). A round held in review or whose creation
  * failed (`distributing = 1`, no job) doesn't refuse: every `round_user` insert, the review's
  * Distribute included, re-checks the account ([[User.lockLive]]).
  *
  * '''One transaction''' (the join plan's lock order: `users` → `user_contest` / `account_token`;
  * no round is locked): the account's users row locked ([[User.lockRow]]), the rules above read
  * again under it, its memberships deleted and its unused links (invitations, password and email
  * links) revoked, its emptiness read once more (a vote, an assignment or a comment that committed
  * first refuses, and nothing is written), then `deleted_at` set, its email and password cleared
  * (the address goes to the change log row's `old_value`: `users.email`'s unique key frees it for
  * the person's other account, and nobody signs in to this one again), its API tokens revoked and
  * its sessions ended (`auth_version` bumped). A `round_user` insert takes a shared lock on the
  * account's row first ([[User.lockLive]]), so it either committed before (refused here) or skips
  * the retired account.
  *
  * '''Logged:''' `account_change` rows of the field `account_retired`: on the retired account (its
  * old address; the account added instead in the comment) and on the account added instead (the
  * retired one in the comment); a `security.account` line by ids only (`account_retired
  * accountId= byAccountId= contestId= keptAccountId= memberships= links=`), and refusals
  * (`account_retire_refused accountId= byAccountId= reason=`). No mail.
  */
object AccountRetirement {

  private val accountLogger = Logger("security.account")

  /** Why an account is not retired: a message key (a sentence fragment, after "can't be
    * retired:") and the log's reason. `offered`: the panel says why, rather than offering nothing.
    */
  sealed abstract class Refusal(val key: String, val reason: String, val offered: Boolean)
  /** The actor may not change the account (another contest's, or the actor is no admin). */
  case object NotAllowed extends Refusal("account.retire.refused.not.allowed", "not_allowed", offered = false)
  case object OwnAccount extends Refusal("account.retire.refused.own", "own_account", offered = false)
  case object RootAccount extends Refusal("account.retire.refused.root", "root_account", offered = false)
  /** No such account, or retired already. */
  case object Gone extends Refusal("account.retire.refused.gone", "gone", offered = false)
  /** The account added instead is gone (retired meanwhile, say by the same panel the other way round). */
  case object KeptGone extends Refusal("account.retire.refused.kept.gone", "kept_gone", offered = false)
  /** An admin (an active admin membership or a live admin invitation): root only. */
  case object AdminAccount extends Refusal("account.retire.refused.admin", "admin_account", offered = true)
  case object HasVotes extends Refusal("account.retire.refused.votes", "votes", offered = true)
  case object HasRounds extends Refusal("account.retire.refused.rounds", "round_user", offered = true)
  case object HasComments extends Refusal("account.retire.refused.comments", "comments", offered = true)
  case object HasIdentity extends Refusal("account.retire.refused.identity", "identity", offered = true)
  case object HasApiToken extends Refusal("account.retire.refused.api.token", "api_token", offered = true)
  /** A round job queued or running in one of the account's contests (M4). */
  case object RoundBusy extends Refusal("account.retire.refused.round.busy", "round_job", offered = true)

  /** A retirement done: the account, its former address (None: it had none), and how many
    * memberships were deleted and unused links revoked.
    */
  final case class Retired(accountId: Long, oldEmail: Option[String], memberships: Int, links: Int)

  /** A refusal inside [[retireIn]]'s transaction: the caller rolls it back. */
  final class Refused(val refusal: Refusal) extends RuntimeException(refusal.reason, null, false, false)

  /** The round jobs that hand files to a round's jurors (M4). */
  val RoundJobKinds: Seq[String] = Seq(RoundDistributionHandler.Kind, RoundRebalanceHandler.Kind)

  private def isRoot(u: User): Boolean = u.isRoot || u.hasRole(User.ROOT_ROLE)

  /** Whether `actor` may retire `account` as stored, and the account is empty and not in a busy
    * contest: the first refusal if not. Plain reads (the panel's offer); [[retire]] reads all of it
    * again under the account's row lock.
    */
  def refusal(actor: User, account: User, contestId: Option[Long])(implicit
      session: DBSession = AutoSession): Option[Refusal] =
    permission(actor, account, contestId).orElse(busy(account.getId)).orElse(emptiness(account.getId))

  /** Who and which account only ([[permission]]), without the emptiness and the round job: the
    * account join's preview (J-2), which empties the account before it retires it.
    */
  def permissionRefusal(actor: User, account: User, contestId: Option[Long])(implicit
      session: DBSession = AutoSession): Option[Refusal] =
    permission(actor, account, contestId)

  /** The round job refusal only ([[busy]], M4): the account join's start and last step. */
  def busyRefusal(accountId: Long)(implicit session: DBSession = AutoSession): Option[Refusal] =
    busy(accountId)

  /** Who and which account: the actor read again from the database (a root flag removed, a
    * membership ended since the session was read).
    */
  private def permission(actor: User, account: User, contestId: Option[Long])(implicit
      session: DBSession): Option[Refusal] = {
    val current = actor.id.flatMap(User.findById(_)).filter(_.deletedAt.isEmpty)
    if (account.deletedAt.nonEmpty) Some(Gone)
    else if (current.isEmpty) Some(NotAllowed)
    else if (actor.id == account.id) Some(OwnAccount)
    else if (isRoot(account)) Some(RootAccount)
    // a series admin (a protected target, contest series plan §3.3): root's only
    else if (!current.exists(isRoot) && User.isProtectedTarget(account)) Some(RootAccount)
    else current.filterNot(isRoot).flatMap { a =>
      if (!controllers.UserController.mayEditAccountNow(a, account) || !tiedTo(account.getId, contestId))
        Some(NotAllowed)
      else ShareLinkService.handOverRefusal(a, account) match {
        case None                            => None
        case Some(ShareLinkService.TargetRole) => Some(AdminAccount)
        case Some(_)                         => Some(NotAllowed)
      }
    }
  }

  /** Whether the account is a member of the contest (in any role) or has a live invitation into it. */
  private def tiedTo(accountId: Long, contestId: Option[Long])(implicit session: DBSession): Boolean =
    contestId.exists { c =>
      sql"""SELECT EXISTS(SELECT 1 FROM user_contest WHERE user_id = $accountId AND contest_id = $c)
              OR EXISTS(SELECT 1 FROM account_token WHERE user_id = $accountId AND contest_id = $c
                AND purpose = ${db.scalikejdbc.AccountTokenJdbc.Invitation}
                AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3))"""
        .map(_.boolean(1)).single.apply().contains(true)
    }

  /** The account's contests: its memberships and live invitations. */
  private def contestsOf(accountId: Long)(implicit session: DBSession): Seq[Long] =
    (UserContestJdbc.findByUser(accountId).map(_.contestId) ++
      sql"""SELECT contest_id FROM account_token WHERE user_id = $accountId AND contest_id IS NOT NULL
            AND used_at IS NULL AND revoked_at IS NULL AND expires_at > NOW(3)"""
        .map(_.long(1)).list.apply()).distinct.sorted

  /** M4: a round job queued or running in the account's contests. A round's flags alone
    * (`distributing`: held in review, or a failed creation) don't refuse.
    */
  private def busy(accountId: Long)(implicit session: DBSession): Option[Refusal] = {
    val contests = contestsOf(accountId)
    if (contests.isEmpty) None
    else {
      val active = JobStatus.active.toSeq
      val held = sql"""SELECT EXISTS(SELECT 1 FROM job WHERE contest_id IN ($contests) AND kind IN ($RoundJobKinds)
                         AND status IN ($active))"""
        .map(_.boolean(1)).single.apply().contains(true)
      Option.when(held)(RoundBusy)
    }
  }

  /** The first thing the account has that makes it not empty, if any. `keepOrphans`: its
    * `vote_orphan` rows don't count (the account join's retirement: the join plan keeps them under
    * the retired account's id, outside every round's results).
    */
  private def emptiness(accountId: Long, keepOrphans: Boolean = false)(implicit session: DBSession): Option[Refusal] =
    sql"""SELECT
            EXISTS(SELECT 1 FROM vote WHERE jury_id = $accountId)
              OR EXISTS(SELECT 1 FROM vote_aside WHERE jury_id = $accountId)
              OR (NOT $keepOrphans AND EXISTS(SELECT 1 FROM vote_orphan WHERE jury_id = $accountId)),
            EXISTS(SELECT 1 FROM round_user WHERE user_id = $accountId),
            EXISTS(SELECT 1 FROM comment WHERE user_id = $accountId),
            EXISTS(SELECT 1 FROM user_identity WHERE user_id = $accountId AND unlinked_at IS NULL),
            EXISTS(SELECT 1 FROM api_token WHERE user_id = $accountId AND revoked_at IS NULL
              AND (expires_at IS NULL OR expires_at > NOW(3)))"""
      .map(rs => Seq(rs.boolean(1) -> HasVotes, rs.boolean(2) -> HasRounds, rs.boolean(3) -> HasComments,
        rs.boolean(4) -> HasIdentity, rs.boolean(5) -> HasApiToken))
      .single.apply().getOrElse(Nil)
      .collectFirst { case (true, refusal) => refusal }

  /** Retires the account `accountId` for `actor` (see the object's comment), in its own transaction
    * (retried on a deadlock); `contestId`: the contest it was done from (the change log rows'),
    * `keptAccountId`: the account added instead (its change log row, and the comments). Left, with
    * nothing written, if refused. Logged after the commit.
    */
  def retire(actor: User, accountId: Long, contestId: Option[Long], keptAccountId: Option[Long]): Either[Refusal, Retired] = {
    val result =
      try Right(WriteTx.write(implicit session => retireIn(actor, accountId, contestId, keptAccountId)))
      catch { case r: Refused => Left(r.refusal) }
    result match {
      case Right(done) =>
        accountLogger.info(s"account_retired accountId=$accountId byAccountId=${actor.getId} " +
          s"contestId=${contestId.getOrElse("none")} keptAccountId=${keptAccountId.getOrElse("none")} " +
          s"memberships=${done.memberships} links=${done.links}")
      case Left(refusal) =>
        accountLogger.info(s"account_retire_refused accountId=$accountId byAccountId=${actor.getId} " +
          s"contestId=${contestId.getOrElse("none")} reason=${refusal.reason}")
    }
    result
  }

  /** [[retire]] in the caller's transaction, which must take no lock after `users` that this
    * takes before it (the join plan's lock order: the job's row, then `users`, then `user_contest`
    * / `account_token`). Both users rows, the account's and the one added instead, are locked
    * first, in id order: of two crossed retirements (each account retired keeping the other) the
    * second finds its kept account retired ([[KeptGone]]). Throws [[Refused]] on a refusal, after
    * which the caller must roll back: the memberships and links may be gone already. Logs nothing
    * (the caller does, after its commit). `keepOrphans`: the account's `vote_orphan` rows stay
    * under its id and don't refuse (the account join, J-2, which has moved everything else).
    */
  def retireIn(actor: User, accountId: Long, contestId: Option[Long], keptAccountId: Option[Long],
      keepOrphans: Boolean = false)(implicit session: DBSession): Retired = {
    def refuse(r: Refusal): Nothing = throw new Refused(r)
    val locked = (accountId +: keptAccountId.toSeq.filter(_ != accountId)).sorted
      .flatMap(id => User.lockRow(id).map(id -> _)).toMap
    val account = locked.getOrElse(accountId, refuse(Gone))
    if (keptAccountId.exists(k => !locked.get(k).exists(_.deletedAt.isEmpty))) refuse(KeptGone)
    permission(actor, account, contestId).foreach(refuse)
    busy(accountId).foreach(refuse)
    emptiness(accountId, keepOrphans).foreach(refuse)
    val memberships = sql"DELETE FROM user_contest WHERE user_id = $accountId".update.apply()
    val links = sql"""UPDATE account_token SET revoked_at = NOW(3)
                      WHERE user_id = $accountId AND used_at IS NULL AND revoked_at IS NULL""".update.apply()
    // again, once the memberships and links are gone: a vote, an assignment or a comment that
    // committed meanwhile refuses, and the caller rolls all of it back
    emptiness(accountId, keepOrphans).foreach(refuse)
    val oldEmail = Option(account.email).map(_.trim).filter(_.nonEmpty)
    sql"""UPDATE users SET deleted_at = NOW(3), email = NULL, email_verified_at = NULL, email_set_by_admin_at = NULL,
            password = NULL, auth_version = ${User.BumpAuthVersion}
          WHERE id = $accountId""".update.apply()
    ApiTokenJdbc.revokeAll(accountId)
    // its series grants go with it (contest series plan §3.3, "Joins and retirement")
    series.SeriesAdmins.onRetire(actor, accountId)
    AccountChangeLog.record(actor, accountId, contestId, AccountChangeJdbc.AccountRetired, oldValue = oldEmail,
      comment = keptAccountId.map("#" + _))
    for (kept <- keptAccountId)
      AccountChangeLog.record(actor, kept, contestId, AccountChangeJdbc.AccountRetired, comment = Some("#" + accountId))
    Retired(accountId, oldEmail, memberships, links)
  }
}
