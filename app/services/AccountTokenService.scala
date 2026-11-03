package services

import db.scalikejdbc.{AccountToken, AccountTokenJdbc, ApiTokenJdbc, User, UserContestJdbc}
import play.api.Logger
import scalikejdbc._

import java.security.SecureRandom
import java.sql.SQLIntegrityConstraintViolationException
import java.util.Base64
import scala.util.control.NonFatal

/** The links mailed to an account (V77, [[AccountTokenJdbc]]): an invitation and root's
  * password link set the password and prove the email; an email change's link proves the new
  * address. A link holds 32 random bytes; only their SHA-256 is stored. Single use, revocable,
  * expiring, bound to the account and to the address it was sent to.
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

  def validSeconds(purpose: String): Long = purpose match {
    case AccountTokenJdbc.EmailChange   => EmailChangeValidSeconds
    case AccountTokenJdbc.PasswordReset => PasswordResetValidSeconds
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

  /** A new link of the account, sent to `email`; its id and the token, to be mailed once. */
  def create(
      userId: Long,
      purpose: String,
      email: String,
      contestId: Option[Long] = None,
      role: Option[String] = None,
      createdBy: Option[Long] = None
  )(implicit session: DBSession = AutoSession): (Long, String) = {
    val token = generate()
    val id = AccountTokenJdbc.create(userId, purpose, hash(token), email.trim.toLowerCase, contestId, role, createdBy,
      validSeconds(purpose))
    logger.info(
      s"account_token_created purpose=$purpose tokenId=$id accountId=$userId" +
        createdBy.fold("")(c => s" byAccountId=$c") + contestId.fold("")(c => s" contestId=$c") +
        role.fold("")(r => s" role=$r"))
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

  private def refused(refusal: Refusal, token: Option[AccountToken]): Left[Refusal, Nothing] = {
    val line = s"account_token_refused reason=${refusal.reason}" + token.fold("")(t => s" tokenId=${t.id} accountId=${t.userId}")
    // an unknown or malformed one is anyone's guess: INFO; a real link refused: WARN
    if (token.isEmpty) logger.info(line) else logger.warn(line)
    Left(refusal)
  }

  /** The link and its account, if `token` is a live link of one of `purposes` whose account
    * exists (not deleted) and, for a link that sets the password, still has the address it
    * was sent to.
    */
  def check(token: String, purposes: Seq[String])(implicit
      session: DBSession = AutoSession
  ): Either[Refusal, (AccountToken, User)] =
    if (!Format.matches(token)) {
      logger.debug("account_token_refused reason=malformed")
      Left(Malformed)
    } else
      AccountTokenJdbc.findByHash(hash(token)) match {
        case None                                         => refused(Unknown, None)
        case Some(t) if !purposes.contains(t.purpose)     => refused(WrongPurpose, Some(t))
        case Some(t)                                      => checked(t)
      }

  private def checked(t: AccountToken)(implicit session: DBSession): Either[Refusal, (AccountToken, User)] =
    if (!t.live) refused(NotLive, Some(t))
    else
      User.findById(t.userId)(session).filter(_.deletedAt.isEmpty) match {
        case None => refused(AccountGone, Some(t))
        case Some(user) if AccountTokenJdbc.SetsPassword.contains(t.purpose) && user.emailLo != t.email =>
          refused(OtherAddress, Some(t))
        case Some(user) => Right(t -> user)
      }

  /** Accepts an invitation or a password link, in one transaction, the link's row locked:
    * the password becomes `passwordHash` (the old one stops working), the email is verified,
    * `auth_version` goes up (every session of the account ends), every API token of the
    * account is revoked, the account's pending invitations sent to this address give their
    * roles if whoever sent each may still give it in that contest ([[mayStillGrant]]), and every
    * other unused link of the account, of any purpose (an email change a former holder of the
    * password asked for included, M1), is revoked. The account, as stored now.
    */
  def acceptPasswordLink(token: String, passwordHash: String): Either[Refusal, (AccountToken, User)] =
    if (!Format.matches(token)) Left(Malformed)
    else
      // quiet: the statements bind the password hash, which must not reach the SQL log
      User.quietTx { implicit session =>
        AccountTokenJdbc.lockByHash(hash(token)) match {
          case None                                                         => refused(Unknown, None)
          case Some(t) if !AccountTokenJdbc.SetsPassword.contains(t.purpose) => refused(WrongPurpose, Some(t))
          case Some(t) =>
            checked(t).flatMap { case (link, user) =>
              val pending = AccountTokenJdbc.pendingInvitationsOf(user.getId, link.email)
              if (!AccountTokenJdbc.markUsed(link.id)) refused(NotLive, Some(link))
              else {
                sql"""UPDATE users SET password = $passwordHash, email_verified_at = NOW(3),
                        auth_version = ${User.BumpAuthVersion}
                      WHERE id = ${user.getId}""".update.apply()
                val revokedApiCount = ApiTokenJdbc.revokeAll(user.getId)
                val roles = pending.flatMap { p =>
                  val used = p.id == link.id || AccountTokenJdbc.markUsed(p.id)
                  for {
                    contestId <- p.contestId
                    role <- p.role
                    if used
                    if mayStillGrant(p, contestId) || { logger.warn(
                      s"invitation_role_dropped reason=inviter_no_longer_admin tokenId=${p.id} accountId=${user.getId} " +
                        s"contestId=$contestId"); false }
                  } yield {
                    UserContestJdbc.setMembership(user.getId, contestId, role)
                    s"$contestId:$role"
                  }
                }
                val revokedLinks = AccountTokenJdbc.revokeUnused(user.getId, AccountTokenJdbc.AllPurposes)
                logger.info(
                  s"account_link_accepted purpose=${link.purpose} tokenId=${link.id} accountId=${user.getId} " +
                    s"apiTokensRevoked=$revokedApiCount linksRevoked=$revokedLinks memberships=${roles.mkString(",")}")
                Right(link -> User.findById(user.getId)(session).get)
              }
            }
        }
      }

  /** Whether the invitation's sender may still give its role in the contest (L4): root, or an
    * admin of that contest now (adding people is theirs: [[controllers.UserController]]'s add
    * row and import). An invitation is the sender's act, not the contest's: one sent by an admin
    * who has been removed since gives no role.
    */
  private def mayStillGrant(invitation: AccountToken, contestId: Long)(implicit session: DBSession): Boolean =
    invitation.createdBy.flatMap(id => User.findById(id)(session)).filter(_.deletedAt.isEmpty).exists { sender =>
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
