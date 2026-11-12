package services

import db.scalikejdbc.{AccountToken, AccountTokenJdbc, User, UserContest, UserContestJdbc}
import play.api.Logger
import scalikejdbc._

/** Shareable set-password links (the user, 2026-11-05): the same single-use link as a mailed
  * invitation or password link (V77), created for an admin or root to copy and pass on to the
  * person themselves (V94's `shared`). Emails get lost or land in spam; people expect a personal
  * contact.
  *
  * Who may create one ([[refusal]]):
  *  - root: for any account (an invitation never for a root account, as a mailed one);
  *  - an admin of the request's contest: for a member or pending invitee of it (a reset: an
  *    active member only, a pending invitee gets the invitation), other than themselves and
  *    root, whose every membership (inactive ones too), contest worked in
  *    ([[User.contestsWorkedIn]]) and live invitation is in a contest they are an admin of, and
  *    whose active roles (and the roles of its live invitations) are jury or organizer only.
  *    Never another admin, nor an account that is also in a contest this admin doesn't
  *    administer: the link's holder sets the account's password.
  *  - nobody else.
  * Not for an account without an email while Wikimedia sign-in is offered: it signs in with its
  * wiki account there (a password would let the link's holder squat the wiki user's name).
  * The same rule is checked again when the link is opened and used ([[creatorMayStillShare]]),
  * the account's row locked: an account that has joined another contest since, or a creator no
  * longer admin, refuses it. Accepting a shared link un-verifies the account's email and wiki
  * account ([[AccountTokenService]]): nobody proved either.
  *
  * Log lines (`security.account`) name accounts and links by id only.
  */
object ShareLinkService {

  private val logger = Logger("security.account")

  /** The active roles a contest admin may create a link for. */
  val ShareableRoles: Set[String] = Set(User.JURY_ROLE, "organizer")

  /** Why no link is created; `forbidden` ones are authorization denials (403). */
  sealed abstract class Refusal(val reason: String, val forbidden: Boolean)
  /** Not a member or pending invitee of the contest, or deleted (404). */
  case object NotInContest extends Refusal("not_in_contest", forbidden = false)
  /** An invitation for an account whose email is verified and has a usable password (400). */
  case object NotNeeded extends Refusal("invitation_not_needed", forbidden = false)
  /** Without an email and a wiki account that names it alone: nobody could sign in (400). */
  case object NoLogin extends Refusal("no_login_name", forbidden = false)
  /** Without an email while Wikimedia sign-in is offered: it signs in with Wikimedia (400). */
  case object SignsInWithWikimedia extends Refusal("signs_in_with_wikimedia", forbidden = false)
  case object NotAdmin extends Refusal("share_link_not_admin", forbidden = true)
  case object OwnAccount extends Refusal("share_link_own_account", forbidden = true)
  case object TargetRoot extends Refusal("share_link_target_root", forbidden = true)
  case object TargetRole extends Refusal("share_link_target_role", forbidden = true)
  case object OtherContest extends Refusal("share_link_other_contest", forbidden = true)
  /** A reset by an admin for an account without an active membership of the contest. */
  case object NotActiveMember extends Refusal("share_link_reset_not_active_member", forbidden = true)

  private def isRoot(user: User): Boolean = user.isRoot || user.hasRole(User.ROOT_ROLE)

  /** What the rule reads about an account: its memberships, live invitations and contests worked in. */
  final case class Ties(memberships: Seq[UserContest], invitations: Seq[AccountToken], workedIn: Seq[Long])

  private def tiesOf(accountId: Long)(implicit session: DBSession): Ties =
    Ties(UserContestJdbc.findByUser(accountId),
      AccountTokenJdbc.findByUser(accountId).filter(t => t.live && t.purpose == AccountTokenJdbc.Invitation),
      User.contestsWorkedIn(accountId))

  /** The contests `actor` is an admin of now, from the database. */
  private def adminContests(actor: User)(implicit session: DBSession): Set[Long] =
    UserContestJdbc.findByUser(actor.getId).filter(_.role == User.ADMIN_ROLE).map(_.contestId).toSet

  /** The rule itself, on what was read: `adminOf` is `actor`'s admin contests. */
  private def refusalOf(actor: User, adminOf: => Set[Long], account: User, ties: Ties, purpose: String,
      contestId: Option[Long]): Option[Refusal] = {
    val Ties(memberships, invitations, workedIn) = ties
    lazy val admins = adminOf // read once, and not for root
    val member =contestId.exists(c => memberships.exists(_.contestId == c))
    val inContest = member || contestId.exists(c => invitations.exists(_.contestId.contains(c)))
    val invitation = purpose == AccountTokenJdbc.Invitation
    if (account.deletedAt.nonEmpty) Some(NotInContest)
    else if (invitation && !inContest) Some(NotInContest)
    else if (invitation && isRoot(account)) Some(TargetRoot)
    else if (isRoot(actor)) None
    else if (actor.deletedAt.nonEmpty || !contestId.exists(admins.contains)) Some(NotAdmin)
    else if (actor.id == account.id) Some(OwnAccount)
    else if (isRoot(account)) Some(TargetRoot)
    else if (!inContest) Some(NotInContest)
    else if (memberships.exists(m => m.active && !ShareableRoles.contains(m.role)) ||
      invitations.exists(_.role.exists(r => User.ACCESS_ROLES.contains(r) && !ShareableRoles.contains(r))))
      Some(TargetRole)
    else if ((memberships.map(_.contestId) ++ workedIn ++ invitations.flatMap(_.contestId)).exists(c => !admins.contains(c)))
      Some(OtherContest)
    // a reset only for an active member: a pending invitee gets the invitation's link
    else if (!invitation && !contestId.exists(c => memberships.exists(m => m.contestId == c && m.active)))
      Some(NotActiveMember)
    else None
  }

  /** Why `actor` may not create a link of `purpose` (an invitation into `contestId`, or a
    * password link) for `account`, in the request's contest `contestId`; None if they may.
    * Read from the database, `actor`'s memberships too.
    */
  def refusal(actor: User, account: User, purpose: String, contestId: Option[Long])(implicit
      session: DBSession = AutoSession
  ): Option[Refusal] =
    refusalOf(actor, adminContests(actor), account, tiesOf(account.getId), purpose, contestId)

  /** The checks that are not about who asks: an invitation only for an account that needs one
    * (as a mailed one), no password for an account without an email where Wikimedia sign-in is
    * offered, and a name to sign in with.
    */
  private def needRefusal(account: User, purpose: String, wikimediaSignIn: Boolean): Option[Refusal] =
    if (purpose == AccountTokenJdbc.Invitation && account.emailVerified && account.hasUsablePassword) Some(NotNeeded)
    else if (account.emailLo.isEmpty && wikimediaSignIn) Some(SignsInWithWikimedia)
    else if (loginName(account).isEmpty) Some(NoLogin)
    else None

  /** Why `actor` may not hand `account` a set-password link at all, in any contest: the rule's
    * part about whose account it is (root, a role the actor may not share, a tie outside the
    * actor's admin contests, the actor's own); None for root. For an invitation not tied to a
    * contest yet ([[UserService.invitationRefused]]).
    */
  def handOverRefusal(actor: User, account: User)(implicit session: DBSession = AutoSession): Option[Refusal] =
    if (isRoot(actor)) None
    else {
      val Ties(memberships, invitations, workedIn) = tiesOf(account.getId)
      lazy val admins = adminContests(actor)
      if (actor.deletedAt.nonEmpty) Some(NotAdmin)
      else if (actor.id == account.id) Some(OwnAccount)
      else if (isRoot(account)) Some(TargetRoot)
      else if (memberships.exists(m => m.active && !ShareableRoles.contains(m.role)) ||
        invitations.exists(_.role.exists(r => User.ACCESS_ROLES.contains(r) && !ShareableRoles.contains(r))))
        Some(TargetRole)
      else if ((memberships.map(_.contestId) ++ workedIn ++ invitations.flatMap(_.contestId)).exists(c => !admins.contains(c)))
        Some(OtherContest)
      else None
    }

  /** [[refusal]], then the checks that are not about who asks. */
  def mayCreate(actor: User, account: User, purpose: String, contestId: Option[Long], wikimediaSignIn: Boolean)(implicit
      session: DBSession = AutoSession
  ): Option[Refusal] =
    refusal(actor, account, purpose, contestId).orElse(needRefusal(account, purpose, wikimediaSignIn))

  /** The accounts `actor` may create a link of `purpose` for in the contest ([[mayCreate]]), in a
    * few queries for the whole list (the users page's icons; the POST checks again).
    */
  def mayCreateFor(actor: User, accounts: Seq[User], purpose: String, contestId: Option[Long], wikimediaSignIn: Boolean)(
      implicit session: DBSession = AutoSession): Set[Long] =
    if (accounts.isEmpty) Set.empty
    else {
      val ids = accounts.map(_.getId)
      val memberships = UserContestJdbc.findByUsers(ids)
      val invitations = AccountTokenJdbc.liveInvitationsOf(ids)
      val workedIn = User.contestsWorkedInBy(ids)
      lazy val adminOf = adminContests(actor)
      accounts.filter { account =>
        val ties = Ties(memberships.getOrElse(account.getId, Nil), invitations.getOrElse(account.getId, Nil),
          workedIn.getOrElse(account.getId, Nil))
        refusalOf(actor, adminOf, account, ties, purpose, contestId)
          .orElse(needRefusal(account, purpose, wikimediaSignIn)).isEmpty
      }.map(_.getId).toSet
    }

  /** The name the account signs in with: its email, else its wiki account if that names it
    * alone ([[controllers.OAuthController.loginName]]).
    */
  def loginName(account: User): Option[String] = controllers.OAuthController.loginName(account)

  /** Whether the shared link's creator could still create it for `account` now: the link is
    * refused otherwise ([[AccountTokenService.check]]).
    */
  def creatorMayStillShare(link: AccountToken, account: User)(implicit session: DBSession): Boolean =
    link.createdBy.flatMap(id => User.findById(id)(session)).filter(_.deletedAt.isEmpty).exists { creator =>
      val stillAllowed = refusal(creator, account, link.purpose, link.contestId).isEmpty
      if (!stillAllowed)
        logger.warn(s"shared_link_refused reason=creator_not_allowed tokenId=${link.id} accountId=${account.getId}")
      stillAllowed
    }

  /** Creates the link, shown once: revokes the account's unused links of the purpose (an
    * invitation's into the contest), mailed ones too (root's mailed reset included), so that
    * only this one is live; an invitation keeps the role a live one into the contest waited to
    * give. The account's identities are unlinked only when the link is used
    * ([[AccountTokenService.acceptPasswordLink]]): creating one and never handing it over cuts
    * nobody's sign-in. In one transaction, the account's row locked, checked again inside
    * ([[mayCreate]]). The link, its token and the account, or why not.
    */
  def create(actor: User, accountId: Long, purpose: String, contestId: Option[Long], wikimediaSignIn: Boolean)
      : Either[Refusal, (AccountToken, String, User)] = {
    require(AccountTokenJdbc.SetsPassword.contains(purpose), "a link that sets the password")
    val result = DB.localTx { implicit session =>
      User.lockRow(accountId).toRight(NotInContest).flatMap { account =>
        mayCreate(actor, account, purpose, contestId, wikimediaSignIn).toLeft(()).flatMap { _ =>
          val invitation = purpose == AccountTokenJdbc.Invitation
          val pendingRole =
            if (!invitation) None
            else AccountTokenJdbc.findByUser(accountId)
              .find(t => t.live && t.purpose == purpose && t.contestId == contestId && t.role.isDefined)
              .flatMap(_.role)
          val revoked = AccountTokenJdbc.revokeUnused(accountId, Seq(purpose), if (invitation) contestId else None)
          val (id, token) = AccountTokenService.create(accountId, purpose, account.emailLo, contestId, pendingRole,
            actor.id, shared = true)
          // the account change log, in the same transaction: a link shown, never mailed; in the
          // contest only if the account is in it (root may name any)
          val logContest = contestId.filter(c => invitation || UserContestJdbc.find(accountId, c).nonEmpty)
          AccountChangeLog.record(actor, accountId, logContest, db.scalikejdbc.AccountChangeJdbc.ShareLink,
            newValue = Some(purpose), mailSent = Some(false))
          AccountTokenJdbc.find(id).toRight(NotInContest).map(link => (link, token, account, revoked))
        }
      }
    }
    result match {
      case Right((link, token, account, revoked)) =>
        logger.info(
          s"account_link_shared purpose=$purpose tokenId=${link.id} accountId=$accountId byAccountId=${actor.getId}" +
            contestId.fold("")(c => s" contestId=$c") + s" linksRevoked=$revoked")
        Right((link, token, account))
      case Left(refused) =>
        logger.info(s"account_link_share_refused reason=${refused.reason} accountId=$accountId byAccountId=${actor.getId}")
        Left(refused)
    }
  }
}
