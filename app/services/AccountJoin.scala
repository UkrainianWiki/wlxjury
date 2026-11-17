package services

import db.scalikejdbc.AccountJoinJdbc.{InRound, Pair}
import db.scalikejdbc._
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.libs.json.Json
import scalikejdbc._
import services.jobs.{Job, JobScheduler, NewJob, Progress}

import java.util.Locale

/** Joining two accounts (the account join plan's J-2, docs/plans/2026-11-03-account-join.md;
  * J1–J3 decided by the maintainer 2026-11-16): the preview, who may perform a join, and its start,
  * which queues the `account_join` job ([[AccountJoinHandler]]).
  *
  * '''Who:''' root, or an admin (active) of every contest each of the accounts is a member of or
  * worked in ([[UserController.canEditAccount]]'s set, the duplicates report's "may decide"
  * rule), none of them root. Checked on the page and again on the start under both accounts'
  * users rows.
  *
  * '''The kept account''' (J2): the performer chooses it, nothing is preselected; where only one
  * of the accounts has an email its owner proved (verified, not set by an admin), only that one
  * may be kept (the plan: "the account kept is the one with the verified email").
  *
  * '''Refused''' (each listed in the preview; the start reads all of it again under the users
  * rows and runs only the preview the performer saw: [[Preview.signature]]): a vote collision
  * (J1, [[AccountJoinJdbc.Pair]]); the joined account can't be retired by the performer
  * ([[AccountRetirement.permissionRefusal]]: its own account, root's, an admin account for an
  * admin, or no tie to the contest the join is done from); either account in another join; a
  * queued or running `round_rebalance` adding either; a round of either being distributed or
  * held in review (`distributing`, or a job holding its key); a round job in one of the joined
  * account's contests (the retirement's M4 rule, [[AccountRetirement.busyRefusal]]).
  *
  * A join from the duplicates report or the Edit user panel has no proof from the user (J3): it
  * moves no identity, the joined account's identities are unlinked (listed in the preview).
  */
object AccountJoin {

  private val accountLogger = Logger("security.account")

  /** The most accounts a join page takes (a legacy group has up to 11). */
  val MaxAccounts = 11

  /** The most collisions the preview lists (all are counted). */
  val CollisionsShown = 50

  /** The job's subject type and dedupe key (one active join per kept account). */
  val SubjectType = "account"
  def dedupeKey(keptId: Long): String = s"${AccountJoinJdbc.Kind}:user:$keptId"

  /** Why a join is refused: a message key, its arguments and the log's reason. */
  final case class Refusal(key: String, reason: String, args: Seq[String] = Nil)

  object Refusal {
    def collisions(n: Int): Refusal = Refusal("account.join.refused.collisions", "collisions", Seq(n.toString))
    def keptUnverified(verified: Long): Refusal =
      Refusal("account.join.refused.kept.unverified", "kept_unverified", Seq(verified.toString))
    def retire(joined: Long, r: AccountRetirement.Refusal): Refusal =
      Refusal("account.join.refused.retire", s"retire_${r.reason}", Seq(joined.toString, r.key))
    def inJoin(account: Long, job: Long): Refusal =
      Refusal("account.join.refused.in.join", "in_join", Seq(account.toString, job.toString))
    def rebalance(job: Long): Refusal = Refusal("account.join.refused.rebalance", "rebalance", Seq(job.toString))
    def distributing(round: String): Refusal = Refusal("account.join.refused.distributing", "distributing", Seq(round))
    def notTied(joined: Long): Refusal = Refusal("account.join.refused.not.tied", "not_tied", Seq(joined.toString))
    def roundJob(joined: Long): Refusal = Refusal("account.join.refused.round.job", "round_job", Seq(joined.toString))
  }

  /** One account as the preview shows it: its memberships, whether its email is proven by its
    * owner, its linked identities, live API tokens and links, comments, rows left as orphans, and
    * its votes and votes set aside in all rounds.
    */
  final case class Side(account: User, memberships: Seq[UserContest], verifiedEmail: Boolean, identities: Seq[UserIdentity],
      apiTokens: Long, links: Long, comments: Long, orphans: Long, votes: Long, aside: Long) {
    def id: Long = account.getId
    def name: String = Option(account.fullname).filter(_.trim.nonEmpty).getOrElse(s"#$id")
  }

  /** A contest of either account: each one's role and the role the kept account has after. */
  final case class MembershipRow(contestId: Long, contest: Option[ContestJury], kept: Option[String], joined: Option[String]) {
    def result: Option[String] = AccountJoinJdbc.mergedRole(kept, joined)
    def conflict: Boolean = kept.nonEmpty && joined.nonEmpty && kept != joined
    def label: String = contest.fold(s"#$contestId")(_.fullName)
  }

  /** A round of the joined account: its rows and the kept account's there, the unrated pairs that
    * merge and the collisions.
    */
  final case class RoundRow(roundId: Long, round: Option[Round], contest: Option[ContestJury], kept: InRound,
      joined: InRound, merges: Int, collisions: Int) {
    def label: String = round.fold(s"#$roundId")(r => s"${r.description} (#$roundId)")
    /** The round's `round_user` row after: active if either was (None: neither has one). */
    def resultActive: Option[Boolean] =
      if (kept.assigned.isEmpty && joined.assigned.isEmpty) None
      else Some(kept.assigned.contains(true) || joined.assigned.contains(true))
    /** One of the two was stopped in the round (the plan lists these). */
    def stoppedInOne: Boolean = kept.assigned.contains(false) || joined.assigned.contains(false)
  }

  /** A collision as listed: the pair, its round and its file's title. */
  final case class CollisionRow(pair: Pair, round: String, title: String)

  /** The conflict-of-interest change (CoI keys on `users.wiki_account`): the kept account keeps its
    * wiki account, or takes the joined one's if it has none. The name kept is verified only if the
    * account it came from verified it, or both have it and either verified it (review of #240):
    * a verified name never makes another name verified.
    */
  final case class Coi(kept: Option[String], keptVerified: Boolean, joined: Option[String], joinedVerified: Boolean) {
    private def key(n: String) = CoiMembers.normalize(n).toLowerCase(Locale.ROOT)
    def same: Boolean = kept.exists(k => joined.exists(j => key(k) == key(j)))
    def result: Option[String] = kept.orElse(joined)
    def resultVerified: Boolean = kept match {
      case Some(_) => keptVerified || same && joinedVerified
      case None    => joined.nonEmpty && joinedVerified
    }
    /** The joined account's name, leaving the CoI filter (the kept account has another). */
    def leaving: Option[String] = joined.filter(_ => kept.nonEmpty && !same)
    /** The joined account's name, taken by the kept account (which has none). */
    def taken: Option[String] = joined.filter(_ => kept.isEmpty)
    def changes: Boolean = leaving.nonEmpty || taken.nonEmpty || resultVerified != keptVerified
  }

  /** The preview of joining `joined` into `kept`, done from `contestId`, and what refuses it. */
  final case class Preview(contestId: Option[Long], kept: Side, joined: Side, memberships: Seq[MembershipRow],
      rounds: Seq[RoundRow], collisions: Seq[CollisionRow], collisionCount: Int, coi: Coi, refusals: Seq[Refusal]) {

    def mayStart: Boolean = refusals.isEmpty
    def merges: Int = rounds.map(_.merges).sum

    /** What the performer saw, as a digest: the start runs only if the preview is still this one. */
    def signature: String = {
      val text = Seq(
        s"k=${kept.id};j=${joined.id};c=${contestId.getOrElse("")}",
        memberships.map(m => s"${m.contestId}:${m.kept.getOrElse("-")}:${m.joined.getOrElse("-")}").mkString(","),
        rounds.map(r => s"${r.roundId}:${r.kept}:${r.joined}:${r.merges}:${r.collisions}").mkString(","),
        s"coi=${coi.kept.getOrElse("")}|${coi.keptVerified}|${coi.joined.getOrElse("")}|${coi.joinedVerified}",
        joined.identities.map(i => s"${i.provider}:${i.subject}").mkString(","),
        s"comments=${joined.comments};collisions=$collisionCount;refused=${refusals.map(_.reason).mkString(",")}"
      ).mkString("\n")
      // the project's one SHA-256 helper (RawSqlSpec: digests only there); a digest of what was shown
      ApiTokenService.hash(text).take(32)
    }
  }

  /** The performer's scope read from the database: None if the actor is gone; Some(None) for root;
    * else the contests they administer (an active admin membership).
    */
  private def scopeOf(actor: User)(implicit session: DBSession): Option[Option[Set[Long]]] =
    actor.id.flatMap(User.findById(_)).filter(_.deletedAt.isEmpty).map { fresh =>
      if (AccountSearch.isRoot(fresh)) None
      else Some(UserContestJdbc.findByUser(fresh.getId).filter(m => m.active && m.role == User.ADMIN_ROLE)
        .map(_.contestId).toSet)
    }

  /** The contests each account is a member of, worked in ([[User.contestsWorkedInBy]]), or has a
    * vote set aside or a comment in.
    */
  private def contestsOf(ids: Seq[Long])(implicit session: DBSession): Map[Long, Set[Long]] = {
    val memberships = UserContestJdbc.findByUsers(ids)
    val worked = User.contestsWorkedInBy(ids)
    // and what else the join moves: votes set aside, comments (the preview shows nothing beyond)
    val touched = AccountJoinJdbc.contestsTouchedBy(ids)
    ids.map(id => id -> (memberships.getOrElse(id, Nil).map(_.contestId) ++ worked.getOrElse(id, Nil) ++
      touched.getOrElse(id, Nil)).toSet).toMap
  }

  /** Whether `actor` may join the accounts (read again from the database): root, or (none of the
    * accounts root) an active admin of every contest each is in or worked in, each account in one
    * at least (none without ties).
    */
  def mayPerform(actor: User, accounts: Seq[User])(implicit session: DBSession = AutoSession): Boolean =
    scopeOf(actor) match {
      case None          => false
      case Some(None)    => true
      case Some(Some(adminOf)) =>
        val contests = contestsOf(accounts.map(_.getId))
        // and each account in one of them at least: an account with no ties passes "every contest
        // it is in" on an empty set (A-4's review), and joining into it would hand an admin's
        // member's roles to an account the admin knows nothing about, past the email path's rule
        accounts.forall { a =>
          val in = contests.getOrElse(a.getId, Set.empty)
          !AccountSearch.isRoot(a) && in.nonEmpty && in.forall(adminOf.contains)
        }
    }

  /** Of the accounts, those that may be kept (J2 with the plan's rule): every one, unless some have
    * an email their owner proved, then only those.
    */
  def keepable(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] = {
    val verified = ids.filter(AccountJoinJdbc.ownerVerifiedEmail(_)).toSet
    if (verified.isEmpty) ids.toSet else verified
  }

  private def side(u: User)(implicit session: DBSession): Side = {
    val id = u.getId
    Side(u, UserContestJdbc.findByUser(id), AccountJoinJdbc.ownerVerifiedEmail(id), UserIdentityJdbc.findByUser(id),
      AccountJoinJdbc.liveApiTokens(id), AccountJoinJdbc.liveLinks(id), AccountJoinJdbc.comments(id),
      AccountJoinJdbc.orphans(id),
      votes = sql"SELECT COUNT(*) FROM vote WHERE jury_id = $id".map(_.long(1)).single.apply().getOrElse(0L),
      aside = sql"SELECT COUNT(*) FROM vote_aside WHERE jury_id = $id".map(_.long(1)).single.apply().getOrElse(0L))
  }

  /** The preview of joining `joined` into `kept` for `actor`, done from `contestId`, with what
    * refuses it (the caller checked [[mayPerform]]). Plain reads, in the caller's session: the
    * start's, under both users rows.
    */
  def preview(actor: User, kept: User, joined: User, contestId: Option[Long])(implicit
      session: DBSession = AutoSession): Preview = {
    val (k, j) = (kept.getId, joined.getId)
    val joinedRounds = AccountJoinJdbc.roundsOf(j)
    val keptSide = side(kept)
    val joinedSide = side(joined)
    val keptIn = AccountJoinJdbc.inRounds(k, joinedRounds)
    val joinedIn = AccountJoinJdbc.inRounds(j, joinedRounds)
    val pairs = AccountJoinJdbc.pairs(k, j)
    val rounds = Round.findByIdsInAnyContest(joinedRounds).flatMap(r => r.id.map(_ -> r)).toMap
    val contestIds = (keptSide.memberships ++ joinedSide.memberships).map(_.contestId) ++ rounds.values.map(_.contestId)
    val contests =
      if (contestIds.isEmpty) Map.empty[Long, ContestJury]
      else ContestJuryJdbc.findAllBy(sqls.in(ContestJuryJdbc.defaultAlias.id, contestIds.distinct))
        .flatMap(c => c.id.map(_ -> c)).toMap
    val memberships = (keptSide.memberships ++ joinedSide.memberships).map(_.contestId).distinct.sorted.reverse.map { c =>
      MembershipRow(c, contests.get(c), keptSide.memberships.find(_.contestId == c).map(_.role),
        joinedSide.memberships.find(_.contestId == c).map(_.role))
    }
    val roundRows = joinedRounds.map { r =>
      val here = pairs.filter(_.roundId == r)
      RoundRow(r, rounds.get(r), rounds.get(r).flatMap(x => contests.get(x.contestId)), keptIn.getOrElse(r, InRound()),
        joinedIn.getOrElse(r, InRound()), here.count(_.merges), here.count(_.collides))
    }
    val collisions = pairs.filter(_.collides)
    val titles = {
      val pages = collisions.take(CollisionsShown).map(_.pageId).distinct
      if (pages.isEmpty) Map.empty[Long, String]
      else sql"SELECT page_id, title FROM images WHERE page_id IN ($pages)".map(rs => rs.long(1) -> rs.string(2))
        .list.apply().toMap
    }
    val collisionRows = collisions.take(CollisionsShown).map { p =>
      CollisionRow(p, roundRows.find(_.roundId == p.roundId).fold(s"#${p.roundId}")(_.label),
        titles.getOrElse(p.pageId, s"#${p.pageId}"))
    }
    val coi = Coi(kept.wikiAccount.map(_.trim).filter(_.nonEmpty), kept.wikiAccountVerified,
      joined.wikiAccount.map(_.trim).filter(_.nonEmpty), joined.wikiAccountVerified)
    val refusals = refusalsOf(actor, kept, joined, contestId, joinedRounds ++ AccountJoinJdbc.roundsOf(k),
      collisions.size)
    Preview(contestId, keptSide, joinedSide, memberships, roundRows, collisionRows, collisions.size, coi, refusals)
  }

  private def refusalsOf(actor: User, kept: User, joined: User, contestId: Option[Long], rounds: Seq[Long],
      collisions: Int)(implicit session: DBSession): Seq[Refusal] = {
    val (k, j) = (kept.getId, joined.getId)
    val keepableIds = keepable(Seq(k, j))
    Seq(
      Option.when(collisions > 0)(Refusal.collisions(collisions)),
      Option.when(!keepableIds.contains(k))(Refusal.keptUnverified(j)),
      AccountRetirement.permissionRefusal(actor, joined, contestId).map {
        // an admin retires only an account tied to the contest the join is done from (A-4): say so
        case AccountRetirement.NotAllowed if !AccountSearch.isRoot(actor) && !tiedTo(j, contestId) => Refusal.notTied(j)
        case r                                                                                   => Refusal.retire(j, r)
      },
      AccountJoinJdbc.holding(Seq(k, j)).headOption.map(h => Refusal.inJoin(if (h.accounts(k)) k else j, h.jobId)),
      AccountJoinJdbc.rebalancesAdding(Seq(k, j)).headOption.map(Refusal.rebalance),
      AccountJoinJdbc.distributing(rounds.distinct.sorted).headOption.map { r =>
        Refusal.distributing(Round.findByIdsInAnyContest(Seq(r)).headOption.fold(s"#$r")(x => s"${x.description} (#$r)"))
      },
      AccountRetirement.busyRefusal(j).map(_ => Refusal.roundJob(j))
    ).flatten
  }

  /** Whether the account is a member of the contest (any role) or has a live invitation into it. */
  private def tiedTo(accountId: Long, contestId: Option[Long])(implicit session: DBSession): Boolean =
    contestId.exists { c =>
      UserContestJdbc.find(accountId, c).nonEmpty ||
        AccountTokenJdbc.findByUser(accountId).exists(t => t.live && t.purpose == AccountTokenJdbc.Invitation &&
          t.contestId.contains(c))
    }

  // ── abandoning a failed join ───────────────────────────────────────────────

  /** Why a join can't be abandoned: not an account join, not failed, or failed before its first
    * round step (Cancel it instead).
    */
  final case class AbandonRefused(key: String)

  /** Root abandons a failed join after its first round step (Cancel is refused then): the job is
    * cancelled and both accounts are released (no longer held: both sign in, either is added to
    * rounds again); the rounds it moved stay with the kept account, which gets the joined account's
    * membership in their contests by the merge's rule (higher access wins, `no` loses: it votes what
    * it now holds; a Role change log row each); the rest stays with the joined account, not retired. In one transaction: the job's row, then both users rows (the lock order). An
    * `account_joined` change log row on both accounts says so (`abandoned #other`); logged on
    * `security.account` (`account_join_abandoned`). The caller checked that `root` is root.
    */
  def abandon(root: User, jobId: Long): Either[AbandonRefused, Job] = {
    val result = WriteTx.write { implicit session =>
      services.jobs.JobJdbc.lock(jobId) match {
        case Some(job) if job.kind != AccountJoinJdbc.Kind => Left(AbandonRefused("error.not.found"))
        case Some(job) if !job.isFailed                     => Left(AbandonRefused("account.join.abandon.not.failed"))
        case Some(job) if !AccountJoinHandler.stateOf(job).roundStepDone =>
          Left(AbandonRefused("account.join.abandon.cancel.instead"))
        case Some(job) =>
          val p = AccountJoinHandler.paramsOf(job)
          AccountJoinRequestJdbc.lockAccounts(Seq(p.keptId, p.joinedId))
          AccountJoinJdbc.lockAccountRows(Seq(p.keptId, p.joinedId))
          val before = AccountJoinHandler.stateOf(job)
          // the memberships the moved rounds need: the kept account votes what it now holds there
          val movedContests = AccountJoinJdbc.contestsOfRounds(before.rounds.take(before.next)).toSet
          val merged = AccountJoinJdbc.mergeMemberships(p.keptId, p.joinedId, Some(movedContests))
          merged.foreach { case (c, keptRole, _, role) =>
            AccountChangeJdbc.create(p.keptId, root.getId, Some(c), AccountChangeJdbc.Role, keptRole, Some(role),
              comment = Some(s"abandoned join #${p.joinedId}"))
          }
          val st = before.copy(abandonedBy = root.id, memberships = merged.size)
          services.jobs.JobJdbc.update(job.id, services.jobs.JobStatus.Cancelled, Json.toJsObject(st),
            services.jobs.JobScheduler.progressOf(job), job.error)
          AccountChangeJdbc.create(p.keptId, root.getId, p.contestId, AccountChangeJdbc.AccountJoined,
            comment = Some(s"abandoned #${p.joinedId}"))
          AccountChangeJdbc.create(p.joinedId, root.getId, p.contestId, AccountChangeJdbc.AccountJoined,
            comment = Some(s"abandoned #${p.keptId}"))
          Right((job, p, st, merged.map(_._1)))
        case None => Left(AbandonRefused("error.not.found"))
      }
    }
    result.map { case (job, p, st, contests) =>
      accountLogger.info(s"account_join_abandoned jobId=${job.id} keptAccountId=${p.keptId} joinedAccountId=${p.joinedId} " +
        s"byAccountId=${root.getId} votes=${st.votes} votesAside=${st.aside} rounds=${st.roundUsers} " +
        s"contestsMerged=${contests.mkString(",")}")
      services.jobs.JobJdbc.findById(job.id).getOrElse(job)
    }
  }

  // ── the start ──────────────────────────────────────────────────────────────

  sealed trait Outcome
  final case class Started(job: Job) extends Outcome
  /** Refused, or the preview changed since it was shown (`changed`): shown again. */
  final case class Refused(preview: Preview, changed: Boolean) extends Outcome
  /** An account doesn't exist or was retired. */
  case object Gone extends Outcome
  /** The actor may not join these accounts ([[mayPerform]]). */
  case object NotAllowed extends Outcome

  /** Starts joining `joinedId` into `keptId` for `actor`, done from `contestId` (None: root's full
    * report or an edit page outside a contest), if the preview is still the one shown
    * (`signature`) and nothing refuses it. Under both users rows (ascending id, the lock order) the
    * accounts and the actor's right are read again, the preview recomputed and the job queued
    * (its dedupe key: one active join per kept account; [[AccountJoinJdbc.holding]]: none for
    * either account). `notify`: the performer ticked "tell both owners by email" (never by
    * default). Logged on `security.account` by ids (`account_join_queued`).
    */
  def start(actor: User, contestId: Option[Long], keptId: Long, joinedId: Long, signature: String, notify: Boolean,
      jobs: JobScheduler): Outcome = {
    val outcome = WriteTx.write { implicit session =>
      val locked = AccountJoinRequestJdbc.lockAccounts(Seq(keptId, joinedId))
      val byId = locked.flatMap(u => u.id.map(_ -> u)).toMap
      (byId.get(keptId).filter(_.deletedAt.isEmpty), byId.get(joinedId).filter(_.deletedAt.isEmpty)) match {
        case (Some(kept), Some(joined)) if keptId != joinedId =>
          if (!mayPerform(actor, Seq(kept, joined))) NotAllowed
          else {
            val p = preview(actor, kept, joined, contestId)
            if (p.signature != signature) Refused(p, changed = true)
            else if (!p.mayStart) Refused(p, changed = false)
            else
              jobs.enqueue(NewJob(
                kind = AccountJoinJdbc.Kind,
                contestId = contestId,
                subjectType = Some(SubjectType),
                subjectId = Some(keptId),
                dedupeKey = Some(dedupeKey(keptId)),
                params = Json.obj("keptId" -> keptId, "joinedId" -> joinedId, "by" -> actor.getId,
                  "contestId" -> contestId, "notify" -> notify, "signature" -> signature),
                state = Json.toJsObject(AccountJoinHandler.JoinState()),
                progress = Progress(done = Some(0L), total = Some(p.rounds.size + 2L), unit = Some("steps")),
                createdBy = actor.id
              )) match {
                case Right(job) => Started(job)
                case Left(other) =>
                  Refused(p.copy(refusals = Seq(Refusal.inJoin(keptId, other.id))), changed = false)
              }
          }
        case _ => Gone
      }
    }
    outcome match {
      case Started(job) =>
        accountLogger.info(s"account_join_queued keptAccountId=$keptId joinedAccountId=$joinedId " +
          s"byAccountId=${actor.getId} contestId=${contestId.getOrElse("none")} jobId=${job.id} notify=$notify")
        jobs.started(job.id)
        Started(services.jobs.JobJdbc.findById(job.id).getOrElse(job))
      case Refused(p, changed) =>
        accountLogger.info(s"account_join_refused keptAccountId=$keptId joinedAccountId=$joinedId " +
          s"byAccountId=${actor.getId} contestId=${contestId.getOrElse("none")} " +
          s"reason=${if (changed) "changed" else p.refusals.map(_.reason).mkString(",")}")
        outcome
      case other => other
    }
  }
}
