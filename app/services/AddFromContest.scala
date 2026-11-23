package services

import db.scalikejdbc.{ContestJuryJdbc, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.i18n.Messages

import scala.util.control.NonFatal

/** "Add people from another of your contests" on the users page (asked for by the maintainer on
  * 2026-11-18; the contest series plan's CA6 picker reuses it): the members of a contest the actor
  * administers, listed as the users page's search shows accounts ([[AccountSearch.describe]]: root
  * accounts to root only, deleted accounts never; every address in full, the source being one of
  * the actor's contests), none ticked, each with the role they have there. The actor ticks some and adds them, each
  * as the search's "Add to this contest" adds an account by id ([[UserService.addAccountToContest]]:
  * it joins if it may take the role, otherwise an invitation, mailed only if asked), recorded in the
  * account change log with the source contest ([[UserService.fromContestComment]]), which makes the
  * owner's notice in the app ([[AccountNotices]]).
  *
  * The source: one of the contests the actor holds an active Admin membership of, other than the
  * current one; root: any other contest. Anything else is a 404, as is an id that is not a member
  * of the source (nothing is added then). An account known only by a wiki account nobody verified
  * (no email) may be added too (the maintainer, 2026-11-18): A-3's confirmation is about matching a
  * typed name to the wrong account, and here the admin picks the account itself, a member of their
  * own contest; it gets what [[UserService.addAccountToContest]] gives any account, and the result
  * says when its owner needs a link to sign in.
  */
object AddFromContest {

  private val accountLogger = Logger("security.account")

  /** The most people one add takes. The add runs on the request, one person after another (a few
    * transactions each): if a larger list is ever needed, it should become a job ([[services.jobs]])
    * instead of raising this.
    */
  val MaxPeople = 200

  /** The role filters of the member list, in their order: none is all. */
  val RoleFilters: Seq[String] = Seq(User.JURY_ROLE, "organizer", User.ADMIN_ROLE)

  /** The contests `actor` may add people from into `contestId`, the newest first: those they
    * administer (an active Admin membership of a contest not retired), root every contest not
    * retired; never `contestId` itself, nor a retired contest (contest series plan §3.1).
    */
  def sources(actor: User, contestId: Long): Seq[ContestJury] = {
    val all = AccountSearch.administered(actor) match {
      case None      => ContestJuryJdbc.findAll()
      case Some(ids) =>
        val others = ids.filterNot(_ == contestId)
        if (others.isEmpty) Nil
        else ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, others))
    }
    all.filterNot(c => c.id.contains(contestId) || c.retired).sortBy(c => (-c.year, -c.id.getOrElse(0L)))
  }

  /** Whether `actor` has any contest to add people from into `contestId` (the users page's link). */
  def hasSources(actor: User, contestId: Long): Boolean =
    AccountSearch.administered(actor).forall(_.exists(_ != contestId))

  /** The source contest `sourceId` if `actor` may add people from it into `contestId`: not a
    * retired one.
    */
  def source(actor: User, contestId: Long, sourceId: Long): Option[ContestJury] =
    if (sourceId == contestId || !AccountSearch.administered(actor).forall(_.contains(sourceId))) None
    else ContestJuryJdbc.findById(sourceId).filterNot(_.retired)

  /** A member of the source as the list shows them: as the search describes the account, their
    * role in the source, and why they can't be ticked, if so (`blocked`: a message key).
    */
  final case class Row(found: AccountSearch.Found, sourceRole: String, blocked: Option[String]) {
    def id: Long = found.account.getId
  }

  /** Of the accounts `ids`, those with a live invitation into `contestId` (sent or not). */
  private def invitedInto(contestId: Long, ids: Seq[Long]): Set[Long] =
    db.scalikejdbc.AccountTokenJdbc.liveInvitationsOf(ids).collect {
      case (id, tokens) if tokens.exists(_.contestId.contains(contestId)) => id
    }.toSet

  /** The source's members as `actor` may see them ([[AccountSearch.describe]]), by name, with
    * whether each is in `contestId` already, or invited into it (a live invitation: an add would
    * revoke it and send another). Only those of `role`, if given. Logged at INFO on
    * `security.account` (`add_from_list byAccountId= contestId= sourceContestId= rows=`).
    */
  def members(actor: User, contestId: Long, source: ContestJury, role: Option[String]): Seq[Row] = {
    val inSource = User.contestMembers(source.getId, roles = role.map(Seq(_)))
    val roleOf = inSource.flatMap(u => u.contestRole.map(u.getId -> _)).toMap
    val found = AccountSearch.describe(actor, Some(contestId), inSource)
    val invited = invitedInto(contestId, found.filter(_.member.isEmpty).map(_.account.getId))
    val rows = found
      .map { f =>
        val blocked =
          if (f.member.nonEmpty) Some("users.add.from.member")
          else if (invited.contains(f.account.getId)) Some("users.add.from.invited")
          else None
        Row(f, roleOf.getOrElse(f.account.getId, User.NO_ROLE), blocked)
      }
      .sortBy(r => (r.found.name.toLowerCase, r.id))
    accountLogger.info(s"add_from_list byAccountId=${actor.getId} contestId=$contestId sourceContestId=${source.getId} " +
      s"rows=${rows.size}")
    rows
  }

  /** Why a ticked person was not added. */
  sealed abstract class Skip(val key: String, val logKey: String)
  object Skip {
    case object Member      extends Skip("users.add.from.skipped.member", "already_member")
    case object Invited     extends Skip("users.add.from.skipped.invited", "already_invited")
    case object NoEmail     extends Skip("users.add.from.skipped.no.email", "no_email")
    case object NotPossible extends Skip("users.add.from.skipped.not.possible", "not_possible")
    case object InJoin      extends Skip("users.add.from.skipped.joining", "in_join")
    case object Retired     extends Skip("users.add.from.skipped.retired", "retired")
    case object Failed      extends Skip("users.add.from.skipped.failed", "failed")
    val All: Seq[Skip] = Seq(Member, Invited, NoEmail, NotPossible, InJoin, Retired, Failed)
  }

  /** What an add did: how many joined (a membership, a member that still needs an invitation to set
    * a password included), how many got an invitation that gives the role once accepted, those
    * skipped by reason; of the invitations, how many weren't mailed (the box unticked), how many
    * mails failed, and how many members can get none from this admin; of those who joined, how many
    * have neither an email nor a password (`needLink`: they sign in once someone shares a link; not
    * counted while Wikimedia sign-in is on), and of those, how many this admin may not create a
    * link for (`rootLink`); how many joined or were invited as Admins (`admins`); the reference of
    * a failure.
    */
  final case class Outcome(joined: Int, invited: Int, skipped: Map[Skip, Int], notMailed: Int, mailFailed: Int,
      noInvitation: Int, reference: Option[String], needLink: Int = 0, rootLink: Int = 0, admins: Int = 0) {
    def skippedCount: Int = skipped.values.sum

    /** The users page's message: the counts, the reasons, the mails, and the owners' notice. */
    def message(implicit messages: Messages): String = {
      val reasons = Skip.All.flatMap(s => skipped.get(s).filter(_ > 0).map { n =>
        if (s == Skip.Failed) messages(s.key, n, reference.getOrElse("")) else messages(s.key, n)
      })
      (Seq(messages("users.add.from.result", joined, invited, skippedCount)) ++
        Option.when(admins > 0)(messages("users.add.from.admins", admins)) ++
        Option.when(reasons.nonEmpty)(messages("users.add.from.reasons", reasons.mkString("; "))) ++
        Option.when(notMailed > 0)(messages("users.invitations.not.mailed", notMailed)) ++
        Option.when(mailFailed > 0)(messages("users.invitations.not.sent", mailFailed)) ++
        Option.when(noInvitation > 0)(messages("users.invitations.not.possible", noInvitation)) ++
        Option.when(needLink > 0)(messages("users.add.from.share.link", needLink)) ++
        Option.when(rootLink > 0)(messages("users.add.from.share.link.root", rootLink)) ++
        Option.when(joined + invited > 0)(messages("users.add.from.notice"))).mkString(" ")
    }
  }

  /** The ids ticked are not all members of the source that `actor` may see: nothing added. */
  final class NotMembers extends NoSuchElementException("not members of the source contest")

  /** One person's add: skipped (why), or joined or invited, with what the summary counts. */
  private final case class Done(skip: Option[Skip], joined: Boolean = false, invited: Boolean = false,
      notMailed: Boolean = false, mailFailed: Boolean = false, noInvitation: Boolean = false,
      needLink: Boolean = false, rootLink: Boolean = false, admin: Boolean = false)

  /** Adds the people `picks` (account id, role) of `source` to `contest`, one at a time, each in
    * its own transactions ([[UserService.addAccountToContest]], recorded with
    * [[UserService.fromContestComment]]); "Send an invitation email" is `sendInvitation` (it also
    * mails the contest's greeting to those who join at once, as the add row does), the owner's mail
    * never (their notice is in the app). Throws [[NotMembers]], adding nobody, unless every id is a
    * member of the source that `actor` may see (not deleted; not root, unless `actor` is);
    * [[UserService.RoleNotAssignable]] for a role that is not a contest role. A person who is a
    * member already, or has a live invitation into the contest, is skipped (another add would
    * revoke that invitation and mail a new one). `wikimediaSignIn`: Wikimedia sign-in is on, so a
    * member without an email or a password can sign in without a link. Each person's add and what
    * the summary reads of it are one step: a failure (logged with a reference) skips that person
    * only, and the summary and its log line are written all the same. Logged at INFO on
    * `security.account` by ids and counts (`add_from_contest byAccountId= contestId= sourceContestId=
    * requested= joined= invited= skipped= sendInvitation= needLink= admins=`, and each skip's
    * reason).
    */
  def add(userService: UserService, actor: User, source: ContestJury, contest: ContestJury, picks: Seq[(Long, String)],
      sendInvitation: Boolean, wikimediaSignIn: Boolean = false)(implicit messages: Messages,
      linkHost: LinkHost = LinkHost.None): Outcome = {
    if (!User.mayAssignRoles(actor, picks.map(_._2).toSet)) throw new UserService.RoleNotAssignable
    val ids = picks.map(_._1).distinct
    val seen = AccountSearch.describe(actor, contest.id, User.contestMembers(source.getId, ids = Some(ids)))
      .map(f => f.account.getId -> f.account).toMap
    if (ids.exists(id => !seen.contains(id))) {
      accountLogger.info(s"add_from_contest_refused byAccountId=${actor.getId} contestId=${contest.getId} " +
        s"sourceContestId=${source.getId} requested=${ids.size} reason=not_member")
      throw new NotMembers
    }
    val comment = UserService.fromContestComment(source.getId)
    val cId = contest.getId
    lazy val reference = controllers.Secured.newReference()
    var failed = false

    def logFailure(id: Long, e: Throwable, step: String): Unit = {
      failed = true
      accountLogger.error(s"add_from_contest_failed ref=$reference accountId=$id byAccountId=${actor.getId} " +
        s"contestId=$cId step=$step error=${e.getClass.getName}" + LogSafe.causesField(e), LogSafe.withoutMessages(e))
    }

    /** What the summary counts of an add that returned `added`: `member`, whether the account is a
      * member now. Reads nothing.
      */
    def counted(added: UserService.Added, role: String, member: Boolean): Done = {
      import UserService._
      val mail = added match {
        case Invited(_, m) => Some(m)
        case _             => None
      }
      val counts = Done(None, notMailed = mail.contains(Mail.NotAsked), mailFailed = mail.contains(Mail.Failed),
        noInvitation = member && mail.contains(Mail.NotPossible), admin = role == User.ADMIN_ROLE)
      confirmedOutcome(added, member) match {
        case "added"           => counts.copy(joined = true)
        case "invited"         => counts.copy(invited = true)
        case "already_member"  => Done(Some(Skip.Member))
        case "already_invited" => Done(Some(Skip.Invited))
        case "in_join"         => Done(Some(Skip.InJoin))
        case "not_possible"    => Done(Some(Skip.NotPossible))
        case "retired"         => Done(Some(Skip.Retired))
        case "no_email"        => Done(Some(Skip.NoEmail))
        case _                 => Done(Some(Skip.Failed))
      }
    }

    /** One person: the checks before the add (the fast path; the add checks a live invitation
      * again under the account's row lock), the add, then what the summary reads after it. A
      * failure of those reads after the add counts the person by what the add returned, without
      * the sign-in link's detail, with a reference in the log.
      */
    def addOne(id: Long, role: String): Done = {
      val checked =
        try {
          if (UserContestJdbc.find(id, cId).nonEmpty) Some(Done(Some(Skip.Member)))
          else if (invitedInto(cId, Seq(id)).nonEmpty) Some(Done(Some(Skip.Invited)))
          else None
        } catch {
          case NonFatal(e) => logFailure(id, e, "check"); Some(Done(Some(Skip.Failed)))
        }
      checked.getOrElse {
        val added =
          try Right(userService.addAccountToContest(actor, id, contest, role, sendInvitation, tellOwner = false,
            comment = comment, keepLiveInvitation = true).added)
          catch {
            // retired (or deleted) since the list was read
            case _: UserService.AccountNotFound => Left(Done(Some(Skip.Retired)))
            case NonFatal(e)                    => logFailure(id, e, "add"); Left(Done(Some(Skip.Failed)))
          }
        added.fold(identity, { a =>
          try {
            val done = counted(a, role, member = UserContestJdbc.find(a.user.getId, cId).nonEmpty)
            if (!done.joined) done
            else {
              // no email and no password: nobody signs in until someone shares a link
              val account = seen(id)
              val needLink = !wikimediaSignIn && UserService.isPlaceholder(account)
              done.copy(needLink = needLink, rootLink = needLink &&
                ShareLinkService.refusal(actor, account, db.scalikejdbc.AccountTokenJdbc.Invitation, contest.id).nonEmpty)
            }
          } catch {
            case NonFatal(e) =>
              logFailure(id, e, "summary")
              counted(a, role, member = a.isInstanceOf[UserService.JoinedContest])
          }
        })
      }
    }

    val done = picks.distinctBy(_._1).map { case (id, role) => addOne(id, role) }
    val skipped = done.flatMap(_.skip).groupBy(identity).view.mapValues(_.size).toMap
    val outcome = Outcome(done.count(_.joined), done.count(_.invited), skipped, done.count(_.notMailed),
      done.count(_.mailFailed), done.count(_.noInvitation), Option.when(failed)(reference), done.count(_.needLink),
      done.count(_.rootLink), done.count(d => d.admin && (d.joined || d.invited)))
    accountLogger.info(s"add_from_contest byAccountId=${actor.getId} contestId=$cId sourceContestId=${source.getId} " +
      s"requested=${ids.size} joined=${outcome.joined} invited=${outcome.invited} skipped=${outcome.skippedCount} " +
      s"sendInvitation=$sendInvitation needLink=${outcome.needLink} admins=${outcome.admins}" +
      Skip.All.flatMap(s => skipped.get(s).map(n => s" ${s.logKey}=$n")).mkString)
    outcome
  }
}
