package services

import db.scalikejdbc.{AccountToken, AccountTokenJdbc, User, UserContestJdbc, UserIdentityJdbc}
import play.api.Logger
import scalikejdbc._

/** Shareable set-password links (the user, 2026-11-05): the same single-use link as a mailed
  * invitation or password link (V77), created for an admin or root to copy and pass on to the
  * person themselves (V94's `shared`). Emails get lost or land in spam; people expect a personal
  * contact.
  *
  * Who may create one ([[refusal]]):
  *  - root: for any account (an invitation never for a root account, as a mailed one);
  *  - an admin of the request's contest: for a member or pending invitee of it, other than
  *    themselves and root, whose every membership, contest worked in ([[User.contestsWorkedIn]])
  *    and live invitation is in a contest they are an admin of, and whose active roles (and the
  *    roles of its live invitations) are jury or organizer only. Never another admin, nor an
  *    account that is also in a contest this admin doesn't administer: the link's holder sets
  *    the account's password.
  *  - nobody else.
  * The same rule is checked again when the link is opened and used ([[creatorMayStillShare]]):
  * an account that has joined another contest since, or a creator no longer admin, refuses it.
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
  case object NotAdmin extends Refusal("share_link_not_admin", forbidden = true)
  case object OwnAccount extends Refusal("share_link_own_account", forbidden = true)
  case object TargetRoot extends Refusal("share_link_target_root", forbidden = true)
  case object TargetRole extends Refusal("share_link_target_role", forbidden = true)
  case object OtherContest extends Refusal("share_link_other_contest", forbidden = true)

  private def isRoot(user: User): Boolean = user.isRoot || user.hasRole(User.ROOT_ROLE)

  /** Why `actor` may not create a link of `purpose` (an invitation into `contestId`, or a
    * password link) for `account`, in the request's contest `contestId`; None if they may.
    * Read from the database, `actor`'s memberships too.
    */
  def refusal(actor: User, account: User, purpose: String, contestId: Option[Long])(implicit
      session: DBSession = AutoSession
  ): Option[Refusal] = {
    val memberships = UserContestJdbc.findByUser(account.getId)
    val invitations = AccountTokenJdbc.findByUser(account.getId).filter(t => t.live && t.purpose == AccountTokenJdbc.Invitation)
    val inContest = contestId.exists(c => memberships.exists(_.contestId == c) || invitations.exists(_.contestId.contains(c)))
    val invitation = purpose == AccountTokenJdbc.Invitation
    if (account.deletedAt.nonEmpty) Some(NotInContest)
    else if (invitation && !inContest) Some(NotInContest)
    else if (invitation && isRoot(account)) Some(TargetRoot)
    else if (isRoot(actor)) None
    else {
      val adminOf = UserContestJdbc.findByUser(actor.getId).filter(_.role == User.ADMIN_ROLE).map(_.contestId).toSet
      if (actor.deletedAt.nonEmpty || !contestId.exists(adminOf.contains)) Some(NotAdmin)
      else if (actor.id == account.id) Some(OwnAccount)
      else if (isRoot(account)) Some(TargetRoot)
      else if (!inContest) Some(NotInContest)
      else if (memberships.exists(m => m.active && !ShareableRoles.contains(m.role)) ||
        invitations.exists(_.role.exists(r => User.ACCESS_ROLES.contains(r) && !ShareableRoles.contains(r))))
        Some(TargetRole)
      else {
        val contests = memberships.map(_.contestId) ++ User.contestsWorkedIn(account.getId) ++
          invitations.flatMap(_.contestId)
        if (contests.forall(adminOf.contains)) None else Some(OtherContest)
      }
    }
  }

  /** [[refusal]], and the checks that are not about who asks: an invitation only for an account
    * that needs one (as a mailed one), and a name to sign in with.
    */
  def mayCreate(actor: User, account: User, purpose: String, contestId: Option[Long])(implicit
      session: DBSession = AutoSession
  ): Option[Refusal] =
    refusal(actor, account, purpose, contestId).orElse {
      if (purpose == AccountTokenJdbc.Invitation && account.emailVerified && account.hasUsablePassword) Some(NotNeeded)
      else if (loginName(account).isEmpty) Some(NoLogin)
      else None
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
    * invitation's into the contest), mailed ones too, so that only this one is live; an
    * invitation keeps the role a live one into the contest waited to give; a password link
    * unlinks the account's Google and Wikimedia identities, as root's mailed reset does (B1).
    * In one transaction, the account's row locked, checked again inside ([[mayCreate]]). The
    * link and its token, or why not.
    */
  def create(actor: User, accountId: Long, purpose: String, contestId: Option[Long]): Either[Refusal, (AccountToken, String)] = {
    require(AccountTokenJdbc.SetsPassword.contains(purpose), "a link that sets the password")
    val result = DB.localTx { implicit session =>
      User.lockRow(accountId).fold[Either[Refusal, (AccountToken, String, Int, Int)]](Left(NotInContest)) { account =>
        mayCreate(actor, account, purpose, contestId).toLeft {
          val invitation = purpose == AccountTokenJdbc.Invitation
          val pendingRole =
            if (!invitation) None
            else AccountTokenJdbc.findByUser(accountId)
              .find(t => t.live && t.purpose == purpose && t.contestId == contestId && t.role.isDefined)
              .flatMap(_.role)
          val revoked = AccountTokenJdbc.revokeUnused(accountId, Seq(purpose), if (invitation) contestId else None)
          val unlinked = if (invitation) 0 else UserIdentityJdbc.deleteByUser(accountId)
          val (id, token) = AccountTokenService.create(accountId, purpose, account.emailLo, contestId, pendingRole,
            actor.id, shared = true)
          (AccountTokenJdbc.find(id).get, token, revoked, unlinked)
        }
      }
    }
    result match {
      case Right((link, token, revoked, unlinked)) =>
        logger.info(
          s"account_link_shared purpose=$purpose tokenId=${link.id} accountId=$accountId byAccountId=${actor.getId}" +
            contestId.fold("")(c => s" contestId=$c") + s" linksRevoked=$revoked identitiesUnlinked=$unlinked")
        Right(link -> token)
      case Left(refused) =>
        logger.info(s"account_link_share_refused reason=${refused.reason} accountId=$accountId byAccountId=${actor.getId}")
        Left(refused)
    }
  }
}
