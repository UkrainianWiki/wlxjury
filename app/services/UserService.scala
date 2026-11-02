package services

import controllers.Greeting
import db.scalikejdbc.{AccountTokenJdbc, ContestJuryJdbc, User, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.{Configuration, Logger}
import play.api.i18n.Messages

import javax.inject.Inject
import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

class UserService @Inject()(val sendMail: SMTPOrWikiMail,
                            configuration: Configuration) {

  import UserService._

  private val accountLogger = Logger("security.account")

  /** The setup token (P20, `wlxjury.setup.token`), if one of at least 32 characters is set. */
  def setupToken: Option[String] =
    configuration.getOptional[String]("wlxjury.setup.token").map(_.trim).filter(_.length >= 32)

  /** Adds the person in the edit form to its contest; see [[addToContest]]. */
  def createNewUser(user: User, formUser: User)(
      implicit messages: Messages): UserService.Added = {
    val contest: Option[ContestJury] =
      formUser.currentContest.flatMap(ContestJuryJdbc.findById)
    addToContest(user, formUser, contest)
  }

  def createUser(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): User =
    addToContest(creator, formUser, contestOpt).user

  /** Adds the person to the contest (`contestOpt`, else the creator's). One account per person,
    * in as many contests as needed:
    *
    *  - nobody has the email (without an email: the wiki account): a new account, a member,
    *    without a password; for an access role and an email, an invitation sets it
    *    ([[NewAccount]]);
    *  - an account has it and is a member already: nothing changes ([[AlreadyMember]]);
    *  - an account has it that may take the role ([[User.mayTakeAccessRole]], or a role without
    *    access): it joins, its password stays, and gets the contest's greeting if it sends one
    *    ([[JoinedContest]]);
    *  - an unverified account with a password has the email: its owner gets an invitation, and
    *    the account joins once they accept it, with a new password (O2: whoever registered an
    *    email without owning it gets nothing) ([[Invited]]);
    *  - an account has the wiki account (no email given), unverified: not added; the person is
    *    to be added by email ([[WikiAccountUnverified]]).
    *
    * Throws [[UserService.RoleNotAssignable]] (an `IllegalArgumentException`), changing
    * nothing, if `formUser`'s roles are not each a contest role ([[User.ContestRoles]]), or
    * root from a root creator; [[UserService.InvalidWikiAccount]] for a wiki account MediaWiki
    * would refuse.
    */
  def addToContest(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): UserService.Added = {
    if (!User.mayAssignRoles(creator, formUser.roles)) throw new RoleNotAssignable
    val wikiAccount = formUser.wikiAccount.fold[Option[String]](None) { name =>
      User.normalizeWikiAccount(name).fold(_ => throw new InvalidWikiAccount, identity)
    }
    val person = formUser.copy(wikiAccount = wikiAccount)
    val contestId = contestOpt.flatMap(_.id).orElse(creator.contestId)
    val role = person.contestRole.getOrElse(User.JURY_ROLE)
    val access = User.ACCESS_ROLES.contains(role)

    matchAccount(person) match {
      case Some((account, byEmail)) =>
        contestId.fold[Added](AlreadyMember(account)) { cId =>
          User.findInContest(account.getId, cId) match {
            case Some(member) => AlreadyMember(member)
            case None if !access || account.mayTakeAccessRole =>
              val added = UserContestJdbc.createMembership(account.getId, cId, role)
              val member = User.findInContest(account.getId, cId).getOrElse(account)
              for (contest <- contestOpt if added && contest.greeting.use && mayBeGreeted(role))
                sendMail(creator, member, contest, messages("password.existing"))
              if (added) JoinedContest(member) else AlreadyMember(member)
            case None if byEmail =>
              Invited(account, invite(creator, account, contestOpt, Some(role)))
            case None =>
              accountLogger.info(
                s"add_refused reason=wiki_account_unverified accountId=${account.getId} byAccountId=${creator.getId} contestId=$cId")
              WikiAccountUnverified(account)
          }
        }

      case None =>
        // root only from root ([[createAccount]]); anyone else's new user gets a contest role
        val roles = if (creator.hasRole(User.ROOT_ROLE)) person.roles else Set(role)
        createAccount(creator, person.copy(contestId = contestId, roles = roles), contestOpt)
    }
  }

  /** Creates the account, without a password, with a membership in [[User.contestId]] if
    * set; for an access role (or root) and an email, mails an invitation, whose acceptance
    * sets the password. Fails on an email that already has an account. Only a root creator
    * makes a root user.
    */
  def createAccount(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury])(implicit messages: Messages): NewAccount = {
    val allowed =
      if (creator.hasRole(User.ROOT_ROLE)) formUser
      else formUser.copy(roles = formUser.roles - User.ROOT_ROLE, isRoot = false)
    // no password: nobody signs in until the invitation is accepted (a password an organizer
    // chose, or one in a mail, would let someone else than the email's owner in)
    val createdUser = User.create(allowed.copy(password = None, emailVerifiedAt = None))
    val needsInvitation: Boolean = createdUser.emailLo.nonEmpty && !createdUser.isRoot &&
      createdUser.contestRole.exists(User.ACCESS_ROLES.contains)
    val sent =
      if (needsInvitation) Some(invite(creator, createdUser, contestOpt, None))
      // a new root user (root's form only) gets a password link: root accounts are never invited
      else if (createdUser.isRoot && createdUser.emailLo.nonEmpty) Some(sendPasswordLink(creator, createdUser))
      else None
    NewAccount(createdUser, sent)
  }

  /** Whether a new member with the role gets the contest's greeting: not the board, auditors or
    * staff, who have nothing to open ([[User.NON_ACCESS_ROLES]]).
    */
  private def mayBeGreeted(role: String): Boolean = !User.NON_ACCESS_ROLES.contains(role)

  /** The account a new user's email (or, without an email, wiki account) already has. */
  def existingAccount(formUser: User): Option[User] = matchAccount(formUser).map(_._1)

  /** The account the email names (true), or, without an email, an account the wiki account
    * names (false).
    */
  private def matchAccount(formUser: User): Option[(User, Boolean)] = {
    val email = formUser.email.trim.toLowerCase
    if (email.nonEmpty) User.findByEmail(email).headOption.map(_ -> true)
    else
      formUser.wikiAccount.map(_.trim).filter(_.nonEmpty)
        .flatMap(User.findByAccount(_).filter(_.deletedAt.isEmpty).headOption)
        .map(_ -> false)
  }

  /** Mails the account an invitation into the contest (if any), giving `role` once accepted
    * (None: the account is a member already); a newer invitation into the contest revokes the
    * older ones. Never to a root account, nor without an email. Whether the mail went out.
    */
  def invite(creator: User, account: User, contestOpt: Option[ContestJury], role: Option[String])(implicit
      messages: Messages
  ): Boolean = {
    require(!account.isRoot, "a root account is never invited")
    require(account.emailLo.nonEmpty, "an invitation needs an email")
    val contestId = contestOpt.flatMap(_.id)
    AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.Invitation), contestId)
    val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.Invitation, account.emailLo,
      contestId, role, creator.id)
    val link = linkUrl(controllers.routes.LoginController.invitation(token).url)
    val (subject, body) = invitationText(creator, account, contestOpt, link)
    mailLink(creator, account, subject, body, AccountTokenJdbc.Invitation, tokenId)
  }

  /** Sends the member (or a pending invitee) of the contest a new invitation: the role of a
    * pending one carried over. Whether the mail went out.
    */
  def reinvite(creator: User, account: User, contest: ContestJury)(implicit messages: Messages): Boolean = {
    val pendingRole = contest.id.flatMap { cId =>
      AccountTokenJdbc.findByUser(account.getId)
        .find(t => t.live && t.purpose == AccountTokenJdbc.Invitation && t.contestId.contains(cId) && t.role.isDefined)
        .flatMap(_.role)
    }
    invite(creator, account, Some(contest), pendingRole)
  }

  /** Root's "reset password" (P7): mails the account a link that sets a new password; the old
    * one works until the link is used, and nobody but the email's owner sees the new one.
    * Whether the mail went out.
    */
  def sendPasswordLink(root: User, account: User)(implicit messages: Messages): Boolean = {
    require(account.emailLo.nonEmpty, "a password link needs an email")
    AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.PasswordReset))
    val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.PasswordReset, account.emailLo,
      createdBy = root.id)
    val link = linkUrl(controllers.routes.LoginController.invitation(token).url)
    val days = AccountTokenService.PasswordResetValidSeconds / 86400
    mailLink(root, account, messages("password.link.subject"), messages("password.link.body", link, days),
      AccountTokenJdbc.PasswordReset, tokenId)
  }

  /** Mails `newEmail` a link that makes it the account's email once followed. If another account
    * has that address, nothing is sent; the caller answers the same either way (N7).
    */
  def requestEmailChange(actor: User, account: User, newEmail: String)(implicit messages: Messages): Unit = {
    val email = newEmail.trim.toLowerCase
    if (User.countByEmail(account.getId, email) > 0)
      accountLogger.info(s"email_change_requested accountId=${account.getId} byAccountId=${actor.getId} sent=false")
    else {
      AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.EmailChange))
      val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.EmailChange, email,
        createdBy = actor.id)
      val link = linkUrl(controllers.routes.LoginController.emailConfirmPage(token).url)
      val hours = AccountTokenService.EmailChangeValidSeconds / 3600
      mailLink(actor, account.copy(email = email), messages("email.change.subject"),
        messages("email.change.body", link, hours), AccountTokenJdbc.EmailChange, tokenId)
    }
  }

  /** The invitation's subject and text: the contest's greeting, if it uses one, with the link
    * in place of the password (appended if the greeting has no `{{Password}}`); else a
    * standard text.
    */
  private def invitationText(creator: User, recipient: User, contestOpt: Option[ContestJury], link: String)(implicit
      messages: Messages
  ): (String, String) = {
    val days = AccountTokenService.InvitationValidSeconds / 86400
    contestOpt match {
      case Some(contest) if contest.greeting.use =>
        val template = getGreeting(contest).text.getOrElse("")
        val filled = fillGreeting(template, contest, creator, recipient.copy(password = Some(link)))
        val body =
          if (template.contains("{{Password}}")) filled
          else filled + "\n\n" + messages("invitation.link", link, days)
        (messages("welcome.subject", contest.name), body)
      case Some(contest) =>
        (messages("invitation.subject", contest.name),
          messages("invitation.body", contest.name, creator.fullname, link, days))
      case None =>
        (messages("invitation.subject.tool"), messages("invitation.body.tool", link, days))
    }
  }

  /** The link's full URL: the configured host, never the request's. */
  private def linkUrl(path: String): String =
    configuration.get[String]("wlxjury.host").stripSuffix("/") + path

  /** Sends the mail with a link; a failure is logged (ids and the error's class only) and
    * answered with false: the link can be sent again.
    */
  private def mailLink(from: User, to: User, subject: String, body: String, purpose: String, tokenId: Long): Boolean =
    try {
      sendMail.sendMail(from, to, subject, body)
      true
    } catch {
      case NonFatal(e) =>
        accountLogger.warn(
          s"account_mail_failed purpose=$purpose tokenId=$tokenId accountId=${to.getId} error=${e.getClass.getName}")
        false
    }

  def sendMail(creator: User,
               recipient: User,
               contest: ContestJury,
               password: String)(implicit messages: Messages): Unit = {
    val greeting = getGreeting(contest)
    val subject = messages("welcome.subject", contest.name)
    val message = fillGreeting(greeting.text.get,
                               contest,
                               creator,
                               recipient.copy(password = Some(password)))
    sendMail.sendMail(creator, recipient, subject, message)
  }

  def getGreeting(contest: ContestJury): Greeting = {
    val defaultGreeting = configuration.get[String]("wlxjury.greeting")

    contest.greeting.text
      .fold(contest.greeting.copy(text = Some(defaultGreeting)))(_ =>
        contest.greeting)
  }

  def fillGreeting(template: String,
                   contest: ContestJury,
                   sender: User,
                   user: User): String = {
    variables(contest, sender, user).foldLeft(template) {
      case (s, (k, v)) =>
        s.replace(k, v)
    }
  }

  def variables(contest: ContestJury,
                sender: User,
                recipient: User): Map[String, String] = {
    val host = configuration.get[String]("wlxjury.host")

    ListMap(
      "{{ContestType}}" -> contest.name,
      "{{ContestYear}}" -> contest.year.toString,
      "{{ContestCountry}}" -> contest.country,
      "{{ContestCountry}}" -> contest.country,
      "{{JuryToolLink}}" -> host,
      "{{AdminName}}" -> sender.fullname,
      "{{RecipientName}}" -> recipient.fullname,
      "{{Login}}" -> recipient.email,
      "{{Password}}" -> recipient.password.getOrElse("")
    )
  }

}

object UserService {

  /** What [[UserService.addToContest]] did. */
  sealed trait Added { def user: User }

  /** A new account, a member; `invitationSent`: whether its invitation went out (None: none
    * was needed: a role without access, root, or no email).
    */
  case class NewAccount(user: User, invitationSent: Option[Boolean] = None) extends Added
  case class JoinedContest(user: User) extends Added
  case class AlreadyMember(user: User) extends Added

  /** An unverified account with a password had the email: invited (`sent`: the mail went out);
    * it joins once its owner accepts.
    */
  case class Invited(user: User, sent: Boolean) extends Added

  /** Not added: the wiki account given names an account whose wiki account is unverified. */
  case class WikiAccountUnverified(user: User) extends Added

  /** A role [[UserService.addToContest]] refuses ([[User.mayAssignRoles]]); the edit form's
    * save maps it, and only it, to a 400.
    */
  class RoleNotAssignable extends IllegalArgumentException("not a contest role")

  /** A wiki account name MediaWiki would refuse ([[User.normalizeWikiAccount]]). */
  class InvalidWikiAccount extends IllegalArgumentException("not a valid wiki account")
}
