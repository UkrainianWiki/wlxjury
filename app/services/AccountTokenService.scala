package services

import db.scalikejdbc.{AccountToken, AccountTokenJdbc, ApiTokenJdbc, User, UserContestJdbc, UserIdentityJdbc}
import org.intracer.wmua.LoginText
import play.api.Logger
import scalikejdbc._

import java.security.SecureRandom
import java.sql.SQLIntegrityConstraintViolationException
import java.util.Base64
import scala.util.control.NonFatal

/** The links mailed to an account (V77, [[AccountTokenJdbc]]): an invitation and root's
  * password link set the password and prove the email; an email change's link proves the new
  * address. A link holds 32 random bytes; only their SHA-256 is stored. Single use, revocable,
  * expiring, bound to the account and to the address it was sent to. An invitation or a password
  * link may instead be shared (V94, [[ShareLinkService]]): shown once to the admin or root who
  * created it, to pass on; accepting one proves no email.
  *
  * Log lines (`security.account`) name accounts and links by id only: never an email, a name
  * or a token.
  */
object AccountTokenService {

  private val logger = Logger("security.account")

  /** How long a link is good for, by purpose. */
  val InvitationValidSeconds: Long = 7L * 24 * 3600
  val PasswordResetValidSeconds: Long = 7L * 24 * 3600
  val EmailChangeValidSeconds: Long = 24L * 3600
  /** As long as the chooser's choice waits in the session (F5). */
  val OAuthChooseValidSeconds: Long = 15L * 60

  def validSeconds(purpose: String): Long = purpose match {
    case AccountTokenJdbc.EmailChange   => EmailChangeValidSeconds
    case AccountTokenJdbc.PasswordReset => PasswordResetValidSeconds
    case AccountTokenJdbc.OAuthChoose   => OAuthChooseValidSeconds
    case _                              => InvitationValidSeconds
  }

  private val random = new SecureRandom()

  /** 32 random bytes, base64url without padding: 43 characters. */
  private val Format = "[A-Za-z0-9_-]{43}".r

  def generate(): String = {
    val bytes = new Array[Byte](32)
    random.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)
  }

  def hash(token: String): String = ApiTokenService.hash(token)

  /** A new link of the account, sent to `email` (or, `shared`, shown to its creator to pass on,
    * the account's address as it is now); its id and the token, to be mailed or shown once.
    */
  def create(
      userId: Long,
      purpose: String,
      email: String,
      contestId: Option[Long] = None,
      role: Option[String] = None,
      createdBy: Option[Long] = None,
      shared: Boolean = false
  )(implicit session: DBSession = AutoSession): (Long, String) = {
    val token = generate()
    val id = AccountTokenJdbc.create(userId, purpose, hash(token), LoginText.email(email), contestId, role, createdBy,
      validSeconds(purpose), shared)
    logger.info(
      s"account_token_created purpose=$purpose tokenId=$id accountId=$userId" +
        createdBy.fold("")(c => s" byAccountId=$c") + contestId.fold("")(c => s" contestId=$c") +
        role.fold("")(r => s" role=$r") + (if (shared) " shared=true" else ""))
    (id, token)
  }

  /** Why a link is refused: logged, never shown (every refusal shows the same page, N7). */
  sealed abstract class Refusal(val reason: String)
  case object Malformed extends Refusal("malformed")
  case object Unknown extends Refusal("unknown")
  case object WrongPurpose extends Refusal("purpose")
  case object NotLive extends Refusal("used_revoked_or_expired")
  case object AccountGone extends Refusal("account_missing")
  case object OtherAddress extends Refusal("email_changed")
  case object AddressTaken extends Refusal("email_taken")
  /** A shared link whose creator may no longer create it for the account ([[ShareLinkService]]). */
  case object CreatorNotAllowed extends Refusal("shared_link_creator_not_allowed")
  /** A shared link of an account without an email while Wikimedia sign-in is offered. */
  case object SignsInWithWikimedia extends Refusal("shared_link_signs_in_with_wikimedia")

  private def refused(refusal: Refusal, token: Option[AccountToken]): Left[Refusal, Nothing] = {
    val line = s"account_token_refused reason=${refusal.reason}" + token.fold("")(t => s" tokenId=${t.id} accountId=${t.userId}")
    // an unknown or malformed one is anyone's guess: INFO; a real link refused: WARN
    if (token.isEmpty) logger.info(line) else logger.warn(line)
    Left(refusal)
  }

  /** The link and its account, if `token` is a live link of one of `purposes` whose account
    * exists (not deleted) and, for a link that sets the password, still has the address it
    * was sent to. `wikimediaSignIn`: Wikimedia sign-in is offered, so a shared link of an account
    * without an email is refused ([[ShareLinkService]]: it signs in with Wikimedia).
    */
  def check(token: String, purposes: Seq[String], wikimediaSignIn: Boolean = false)(implicit
      session: DBSession = AutoSession
  ): Either[Refusal, (AccountToken, User)] =
    if (!Format.matches(token)) {
      logger.debug("account_token_refused reason=malformed")
      Left(Malformed)
    } else
      AccountTokenJdbc.findByHash(hash(token)) match {
        case None                                         => refused(Unknown, None)
        case Some(t) if !purposes.contains(t.purpose)     => refused(WrongPurpose, Some(t))
        case Some(t)                                      => checked(t, wikimediaSignIn)
      }

  private def checked(t: AccountToken, wikimediaSignIn: Boolean = false)(implicit
      session: DBSession): Either[Refusal, (AccountToken, User)] =
    if (!t.live) refused(NotLive, Some(t))
    else
      User.findById(t.userId)(session).filter(_.deletedAt.isEmpty) match {
        case None => refused(AccountGone, Some(t))
        case Some(user) if AccountTokenJdbc.BoundToEmail.contains(t.purpose) && user.emailLo != t.email =>
          refused(OtherAddress, Some(t))
        // a shared link only while its creator could still create it for the account: a root
        // account, an admin, or a member of another contest since is no longer theirs to give
        case Some(user) if t.shared && !ShareLinkService.creatorMayStillShare(t, user) =>
          refused(CreatorNotAllowed, Some(t))
        // nor, without an email, once Wikimedia sign-in is offered (configured since it was created)
        case Some(user) if t.shared && wikimediaSignIn && user.emailLo.isEmpty =>
          refused(SignsInWithWikimedia, Some(t))
        case Some(user) => Right(t -> user)
      }

  /** Accepts an invitation or a password link, in one transaction, the link's row locked:
    * the password becomes `passwordHash` (the old one stops working), and what accepting a link
    * proves follows ([[proveEmail]]; a shared link proves no email). The account, as stored now.
    */
  def acceptPasswordLink(token: String, passwordHash: String, wikimediaSignIn: Boolean = false)
      : Either[Refusal, (AccountToken, User)] =
    if (!Format.matches(token)) Left(Malformed)
    else {
      // quiet: the statements bind the password hash, which must not reach the SQL log
      val result = User.quietTx { implicit session =>
        // the account's row first, then the link's (the order of every transaction that locks
        // both: ShareLinkService.create, UserService.sendChooseLink, acceptByProvider,
        // confirmEmailChange; UserService.addToContest's the account's row alone), before
        // the checks read its memberships: an add into another contest (UserService.addToContest,
        // which takes the account's lock too) commits before them, and READ COMMITTED reads it,
        // or waits for this acceptance
        AccountTokenJdbc.findByHash(hash(token)).foreach(t => User.lockRow(t.userId))
        AccountTokenJdbc.lockByHash(hash(token)) match {
          case None                                                         => refused(Unknown, None)
          case Some(t) if !AccountTokenJdbc.SetsPassword.contains(t.purpose) => refused(WrongPurpose, Some(t))
          case Some(t) =>
            checked(t, wikimediaSignIn).flatMap { case (link, user) =>
              proveEmail(link, user, Some(passwordHash), emailProven = !link.shared)
                .map { case (proven, line) => (link, proven, line) }
            }
        }
      }
      // the acceptance's line after the commit (the write-path batch's rule)
      result.map { case (link, proven, line) => logger.info(line); link -> proven }
    }

  /** Accepts the account's live invitation or password link by a Google or Wikimedia sign-in
    * whose provider verified `verifiedEmail` (the user's decision 1, review B3): for an account
    * without a password only, whose current email, the link's address and `verifiedEmail` are
    * the same. In one transaction, the account's row and then the links' locked: what accepting
    * the link proves follows ([[proveEmail]], without a password), then `link` (the identity's
    * row, in the same transaction). The account as stored now; refused, changing nothing, without
    * such a link. Run again on a deadlock ([[db.scalikejdbc.WriteTx.retry]]): the body decides from
    * what it reads inside, and its log lines come after the commit.
    */
  def acceptByProvider[A](userId: Long, verifiedEmail: String)(
      link: (User, DBSession) => Either[A, Unit]): Either[Option[A], User] = {
    final class Abort(val error: Option[Any]) extends RuntimeException(null, null, false, false)
    try {
      val (proven, line) = db.scalikejdbc.WriteTx.write { implicit session =>
        val email = verifiedEmail.trim.toLowerCase
        // the account's row, then its links' (acceptPasswordLink's order)
        val user  = User.lockRow(userId).filter(_.deletedAt.isEmpty)
        val links = AccountTokenJdbc.lockLive(userId, AccountTokenJdbc.SetsPassword, email)
        (links.headOption, user) match {
          case (Some(t), Some(u)) if u.password.isEmpty && u.emailLo == email && t.email == email =>
            // the provider verified the address: proven, whether the link was mailed or shared
            val (proven, line) = proveEmail(t, u, None, emailProven = true).fold(_ => throw new Abort(None), identity)
            // the identity in the same transaction: a refusal undoes the proof too
            link(proven, session).fold(e => throw new Abort(Some(e)), _ => (proven, line))
          case _ => throw new Abort(None)
        }
      }
      logger.info(line)
      Right(proven)
    } catch {
      case a: Abort =>
        if (a.error.isEmpty) logger.info(s"account_link_by_provider_refused reason=no_live_link accountId=$userId")
        Left(a.error.map(_.asInstanceOf[A]))
    }
  }

  /** What accepting `link` proves, shared by [[acceptPasswordLink]] and [[acceptByProvider]] (and
    * its writes, [[applyProof]], by [[setPasswordByAdmin]]) so that they can't drift, in the caller's transaction with the link's row locked: the email is
    * verified, the password becomes `passwordHash` if given, `auth_version` goes up (every
    * session of the account ends), every API token of the account is revoked, its Google and
    * Wikimedia identities are unlinked (B1: the account may have changed hands; a provider's
    * sign-in links its own after this), the account's pending invitations sent to this address
    * give their roles if whoever sent each may still give it in that contest ([[mayStillGrant]]),
    * and every other unused link of the account, of any purpose (an email change a former holder
    * of the password asked for included, M1), is revoked. The account, as stored now, and the
    * acceptance's log line, for the caller to write after the commit.
    *
    * `emailProven` false (a shared link set the password: its holder got it from the link's
    * creator, not from the address): the email and the wiki account become unverified, since
    * the password's holder proved neither (otherwise another contest's admin could add the
    * account directly, [[User.mayTakeAccessRole]], and the address's owner link a Google sign-in
    * to it): a later add from another contest mails an invitation, whose acceptance sets the
    * password again and verifies the email. A pending invitation gives its role only if the
    * link's creator may give it in that contest too (the others are revoked with the rest).
    */
  private def proveEmail(link: AccountToken, user: User, passwordHash: Option[String], emailProven: Boolean)(implicit
      session: DBSession
  ): Either[Refusal, (User, String)] = {
    val pending = AccountTokenJdbc.pendingInvitationsOf(user.getId, link.email)
    if (!AccountTokenJdbc.markUsed(link.id)) refused(NotLive, Some(link))
    else {
      val proof = applyProof(user, Some(link.id), link.createdBy, pending, passwordHash, emailProven)
      val by = if (passwordHash.isEmpty) "provider" else if (link.shared) "shared_link" else "link"
      val line =
        s"account_link_accepted purpose=${link.purpose} tokenId=${link.id} accountId=${user.getId} by=$by " +
          s"emailProven=$emailProven ${proof.counts}"
      Right(proof.user -> line)
    }
  }

  /** What [[applyProof]] changed: the account as stored now, and the counts for the log line. */
  private final case class Proof(user: User, apiTokensRevoked: Int, identitiesUnlinked: Int, linksRevoked: Int,
      memberships: Seq[String]) {

    /** The counts' part of both log lines, the same in each: counts and contest:role pairs only. */
    def counts: String =
      s"apiTokensRevoked=$apiTokensRevoked identitiesUnlinked=$identitiesUnlinked linksRevoked=$linksRevoked " +
        s"memberships=${memberships.mkString(",")}"
  }

  /** The writes of [[proveEmail]] once its link is used, shared with [[setPasswordByAdmin]] (which
    * uses no link: `usedLinkId` None, `createdBy` the admin or root who set the password), in the
    * caller's transaction with the account's row locked: `pending` are the account's pending
    * invitations, read before the link was marked used.
    */
  private def applyProof(user: User, usedLinkId: Option[Long], createdBy: Option[Long], pending: Seq[AccountToken],
      passwordHash: Option[String], emailProven: Boolean)(implicit session: DBSession): Proof = {
    (passwordHash, emailProven) match {
      case (Some(h), true) =>
        sql"""UPDATE users SET password = $h, email_verified_at = NOW(3), auth_version = ${User.BumpAuthVersion}
              WHERE id = ${user.getId}""".update.apply()
      case (Some(h), false) =>
        sql"""UPDATE users SET password = $h, email_verified_at = NULL, wiki_account_verified_at = NULL,
                auth_version = ${User.BumpAuthVersion}
              WHERE id = ${user.getId}""".update.apply()
      case (None, _) =>
        sql"""UPDATE users SET email_verified_at = NOW(3), auth_version = ${User.BumpAuthVersion}
              WHERE id = ${user.getId}""".update.apply()
    }
    val revokedApiCount = ApiTokenJdbc.revokeAll(user.getId)
    val unlinked = UserIdentityJdbc.deleteByUser(user.getId)
    val roles = pending.flatMap { p =>
      val grantable = p.contestId.exists(c => emailProven || mayGrant(createdBy, c))
      val used = usedLinkId.contains(p.id) || grantable && AccountTokenJdbc.markUsed(p.id)
      for {
        contestId <- p.contestId
        role <- p.role
        if used && grantable
        if mayStillGrant(p, contestId) || { logger.warn(
          s"invitation_role_dropped reason=inviter_no_longer_admin tokenId=${p.id} accountId=${user.getId} " +
            s"contestId=$contestId"); false }
      } yield {
        UserContestJdbc.setMembership(user.getId, contestId, role)
        s"$contestId:$role"
      }
    }
    val revokedLinks = AccountTokenJdbc.revokeUnused(user.getId, AccountTokenJdbc.AllPurposes)
    Proof(User.findById(user.getId)(session).get, revokedApiCount, unlinked, revokedLinks, roles)
  }

  /** Sets another account's password from its edit page (the maintainer, 2026-11-10: while new
    * ways of signing in are tried, admins and root keep setting it, as before S2): exactly where
    * `actor` may create a shared password link for the account in `contestId`
    * ([[ShareLinkService.mayCreate]]: root for anyone; an admin only for an active juror or
    * organizer whose every tie is in their contests, never root, another admin or themselves),
    * and with what using that link does ([[applyProof]], the email unproven): the password
    * becomes `passwordHash`, the email and the wiki account become unverified, `auth_version`
    * goes up (every session ends), API tokens are revoked, Google and Wikimedia identities
    * unlinked, pending invitations `actor` may give give their roles, and every other unused link
    * is revoked. In one quiet transaction (the statements bind the hash), the account's row locked
    * and the rule checked again inside; the log line after the commit. The account as stored now.
    */
  def setPasswordByAdmin(actor: User, accountId: Long, contestId: Option[Long], passwordHash: String,
      wikimediaSignIn: Boolean): Either[ShareLinkService.Refusal, User] = {
    val result = User.quietTx { implicit session =>
      User.lockRow(accountId).toRight(ShareLinkService.NotInContest).flatMap { account =>
        ShareLinkService.mayCreate(actor, account, AccountTokenJdbc.PasswordReset, contestId, wikimediaSignIn)
          .toLeft(())
          .map { _ =>
            // the invitations a shared link sent to the account's address would find
            val pending = AccountTokenJdbc.pendingInvitationsOf(accountId, account.emailLo)
            applyProof(account, None, actor.id, pending, Some(passwordHash), emailProven = false)
          }
      }
    }
    result match {
      case Right(proof) =>
        logger.info(
          s"password_set_by_admin accountId=$accountId byAccountId=${actor.getId}" +
            contestId.fold("")(c => s" contestId=$c") + s" emailProven=false sessionsEnded=true ${proof.counts}")
        Right(proof.user)
      case Left(refused) =>
        logger.info(s"password_set_by_admin_refused reason=${refused.reason} accountId=$accountId " +
          s"byAccountId=${actor.getId}")
        Left(refused)
    }
  }

  /** Uses a live chooser link (`oauth_choose`) of the account: its row locked, marked used; the
    * account, if the link is live, the account's and its address still the same.
    */
  def useChooseLink(token: String): Either[Refusal, User] =
    if (!Format.matches(token)) Left(Malformed)
    else
      DB.localTx { implicit session =>
        AccountTokenJdbc.lockByHash(hash(token)) match {
          case None                                                   => refused(Unknown, None)
          case Some(t) if t.purpose != AccountTokenJdbc.OAuthChoose => refused(WrongPurpose, Some(t))
          case Some(t) =>
            checked(t).flatMap { case (link, user) =>
              if (!AccountTokenJdbc.markUsed(link.id)) refused(NotLive, Some(link)) else Right(user)
            }
        }
      }

  /** Whether the invitation's sender may still give its role in the contest (L4): root, or an
    * admin of that contest now (adding people is theirs: [[controllers.UserController]]'s add
    * row and import). An invitation is the sender's act, not the contest's: one sent by an admin
    * who has been removed since gives no role.
    */
  private def mayStillGrant(invitation: AccountToken, contestId: Long)(implicit session: DBSession): Boolean =
    mayGrant(invitation.createdBy, contestId)

  /** Whether the account `senderId` may give a role in the contest now: root, or its admin. */
  private def mayGrant(senderId: Option[Long], contestId: Long)(implicit session: DBSession): Boolean =
    senderId.flatMap(id => User.findById(id)(session)).filter(_.deletedAt.isEmpty).exists { sender =>
      sender.isRoot || UserContestJdbc.find(sender.getId, contestId).exists(_.role == User.ADMIN_ROLE)
    }

  /** Confirms an email change: the account's email becomes the link's address, verified, and
    * `auth_version` goes up. Refused, changing nothing, if another account has that address
    * now.
    */
  def confirmEmailChange(token: String): Either[Refusal, (String, User)] =
    if (!Format.matches(token)) Left(Malformed)
    else
      try {
        var confirmed = Option.empty[String] // logged after the commit
        val result = DB.localTx { implicit session =>
          // the account's row first, then the link's: every transaction's order (acceptPasswordLink),
          // so that a share or an acceptance on the same account never deadlocks with this one
          AccountTokenJdbc.findByHash(hash(token)).foreach(t => User.lockRow(t.userId))
          AccountTokenJdbc.lockByHash(hash(token)) match {
            case None                                                  => refused(Unknown, None)
            case Some(t) if t.purpose != AccountTokenJdbc.EmailChange => refused(WrongPurpose, Some(t))
            case Some(t) =>
              checked(t).flatMap { case (link, user) =>
                val taken = sql"SELECT COUNT(*) FROM users WHERE email = ${link.email} AND id <> ${user.getId}"
                  .map(_.long(1)).single.apply().getOrElse(0L) > 0
                if (taken) refused(AddressTaken, Some(link))
                else if (!AccountTokenJdbc.markUsed(link.id)) refused(NotLive, Some(link))
                else {
                  // a new address: auth_version + 1, every session of the account ends
                  User.setEmail(user.getId, link.email, verified = true)
                  // a password link sent to the old address must not set the password any more
                  AccountTokenJdbc.revokeUnused(user.getId, AccountTokenJdbc.SetsPassword :+ AccountTokenJdbc.EmailChange)
                  confirmed = Some(s"email_change_confirmed tokenId=${link.id} accountId=${user.getId}")
                  Right(user.email -> User.findById(user.getId)(session).get)
                }
              }
          }
        }
        confirmed.foreach(line => logger.info(line))
        result
      } catch {
        // another account took the address meanwhile (the unique key)
        case e: SQLIntegrityConstraintViolationException =>
          logger.warn(s"account_token_refused reason=${AddressTaken.reason} error=${e.getClass.getName}")
          Left(AddressTaken)
        case NonFatal(e) if Option(e.getCause).exists(_.isInstanceOf[SQLIntegrityConstraintViolationException]) =>
          logger.warn(s"account_token_refused reason=${AddressTaken.reason} error=${e.getClass.getName}")
          Left(AddressTaken)
      }
}
