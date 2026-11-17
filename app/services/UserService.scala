package services

import controllers.Greeting
import db.scalikejdbc.{AccountChangeJdbc, AccountTokenJdbc, ContestJuryJdbc, User, UserContestJdbc, UserIdentityJdbc}
import org.intracer.wmua.{ContestJury, LoginText}
import play.api.{Configuration, Logger}
import play.api.i18n.Messages

import javax.inject.Inject
import scalikejdbc.DBSession

import scala.collection.immutable.ListMap
import scala.util.control.NonFatal

class UserService @Inject()(val sendMail: SMTPOrWikiMail,
                            configuration: Configuration) {

  import UserService._

  private val accountLogger = Logger("security.account")

  /** The setup token (P20, `wlxjury.setup.token`), if one of at least 32 characters is set. */
  def setupToken: Option[String] =
    configuration.getOptional[String]("wlxjury.setup.token").map(_.trim).filter(_.length >= 32)

  /** Adds the person in the edit form to its contest; see [[addToContest]]. A form naming a
    * contest that does not exist is refused ([[UserService.ContestNotFound]]), never added to the
    * creator's current contest instead.
    */
  def createNewUser(user: User, formUser: User, sendInvitation: Boolean = false)(
      implicit messages: Messages, linkHost: LinkHost = LinkHost.None): UserService.Added = {
    val contest: Option[ContestJury] = formUser.currentContest.map { id =>
      ContestJuryJdbc.findById(id).getOrElse(throw new ContestNotFound)
    }
    addToContest(user, formUser, contest, sendInvitation)
  }

  def createUser(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury],
      sendInvitation: Boolean = false)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): User =
    addToContest(creator, formUser, contestOpt, sendInvitation).user

  /** Adds the person to the contest (`contestOpt`, else the creator's). One account per person,
    * in as many contests as needed:
    *
    *  - nobody has the email (without an email: the wiki account): a new account, a member,
    *    without a password; for an access role and an email, an invitation sets it
    *    ([[NewAccount]]), mailed only if `sendInvitation` (the add form's "Send an invitation
    *    email", unticked by default: the admin may share a link instead);
    *  - an account has it and is a member already: nothing changes ([[AlreadyMember]]);
    *  - an account has it that may take the role ([[User.mayTakeAccessRole]], or a role without
    *    access): it joins, its password stays, and gets the contest's greeting if it sends one
    *    and `sendInvitation` ([[JoinedContest]]);
    *  - an unverified account with a password has the email: its owner gets an invitation (not
    *    mailed unless `sendInvitation`: it waits on the users page, to be sent or shared), and
    *    the account joins once they accept it, with a new password (O2: whoever registered an
    *    email without owning it gets nothing) ([[Invited]]);
    *  - an account has the wiki account (no email given), unverified: not added; the person is
    *    to be added by email ([[WikiAccountUnverified]]).
    *
    * Throws [[UserService.RoleNotAssignable]] (an `IllegalArgumentException`), changing
    * nothing, if `formUser`'s roles are not each a contest role ([[User.ContestRoles]]; root
    * is none); [[UserService.InvalidWikiAccount]] for a wiki account MediaWiki would refuse.
    * It never changes root: a new account is never root ([[createAccount]]), an existing one's
    * root is left as it is (the edit form's save refuses the Root field for a new user).
    * Each membership given, account created and invitation is in the account change log
    * ([[AccountChangeLog]]).
    */
  def addToContest(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury],
      sendInvitation: Boolean = false)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): UserService.Added = {
    if (!User.mayAssignRoles(creator, formUser.roles)) throw new RoleNotAssignable
    val wikiAccount = formUser.wikiAccount.fold[Option[String]](None) { name =>
      User.normalizeWikiAccount(name).fold(_ => throw new InvalidWikiAccount, identity)
    }
    val person = formUser.copy(wikiAccount = wikiAccount)
    // resolved once: the membership, the invitations, the greeting and a new account all name it
    // (root's add without a contest field goes to root's current contest, R225-2)
    val contest = contestOpt.orElse(creator.contestId.flatMap(ContestJuryJdbc.findById))
    val contestId = contest.flatMap(_.id).orElse(creator.contestId)
    val role = person.contestRole.getOrElse(User.JURY_ROLE)

    def create(): Added = {
      // root may give no contest role; anyone else's new user gets one
      val roles = if (creator.hasRole(User.ROOT_ROLE)) person.roles else Set(role)
      createAccount(creator, person.copy(contestId = contestId, roles = roles), contest, sendInvitation)
    }
    matchAccount(person) match {
      case Some((account, byEmail)) =>
        // an account retired between the match and its row lock (A-4) is none: a new account
        // (the retirement cleared its address, so the email no longer matches it)
        addExisting(creator, account, byEmail, contest, contestId, role, sendInvitation).getOrElse(create())

      case None => create()
    }
  }

  /** Adds the account picked by id from the users page's search (accounts plan A-1) to the
    * contest with the role: [[addToContest]]'s cases for an account matched by its email (the
    * actor picked this account, as when they type its address): it joins if it may take the role
    * ([[User.mayTakeAccessRole]]), otherwise the role waits for an invitation to its address
    * ([[Invited]]; [[Mail.NotPossible]] where this creator may send none), mailed only if
    * `sendInvitation`, from the tool's own sender ([[systemSender]]), never the admin's address.
    * The account change log records the role or the invitation with the comment
    * [[UserService.SearchComment]].
    *
    * Never silent, and never a mail nobody chose (the maintainer, 2026-11-16: "please do not send
    * emails without approval"): an add that gives a membership or an invitation is a notice in
    * the app for the account's owner ([[AccountNotices]], read from the change log row), and a
    * mail to them from [[systemSender]] only if `tellOwner` (the add form's "Tell its owner by
    * email", unticked by default); none without an email ([[SearchNotice.NoEmail]]). Logged on
    * `security.account` (`account_added_notice accountId= byAccountId= contestId=
    * outcome=sent|failed|not_sent|no_email inApp=true`).
    *
    * Throws [[UserService.AccountNotFound]], changing nothing, for an id no account has, a
    * deleted account, or a root account when `creator` isn't root (only root finds root, L1):
    * looked up once before the row lock, checked again under it;
    * [[UserService.RoleNotAssignable]] for a role that is not a contest role.
    */
  def addAccountToContest(creator: User, accountId: Long, contest: ContestJury, role: String,
      sendInvitation: Boolean = false, tellOwner: Boolean = false)(implicit
      messages: Messages, linkHost: LinkHost = LinkHost.None): SearchAdded = {
    if (!User.mayAssignRoles(creator, Set(role))) throw new RoleNotAssignable
    val creatorRoot = creator.isRoot || creator.hasRole(User.ROOT_ROLE)
    def gone(a: User) = a.deletedAt.nonEmpty || (!creatorRoot && (a.isRoot || a.hasRole(User.ROOT_ROLE)))
    val account = User.findById(accountId).filterNot(gone).getOrElse(throw new AccountNotFound)
    val cId = contest.getId
    val added = addExisting(creator, account, byEmail = true, Some(contest), contest.id, role, sendInvitation,
      comment = Some(SearchComment), mailFrom = Some(systemSender), goneIf = Some(gone))
      .getOrElse(throw new AccountNotFound)
    // what the add gave: a membership (direct, or one waiting for a password), or an invitation
    // that waits to give the role; nothing where none may be sent
    val invited = added match {
      case JoinedContest(_)             => Some(false)
      case Invited(_, Mail.NotPossible) => Option.when(UserContestJdbc.find(account.getId, cId).nonEmpty)(true)
      case Invited(_, Mail.Retired)     => None
      case Invited(_, _)                => Some(true)
      case _                            => None
    }
    val notice = invited.fold[SearchNotice](SearchNotice.NotNeeded) { inv =>
      val outcome =
        if (!tellOwner) SearchNotice.NotSent
        else addedNotice(creator, account, contest, role, inv)
      accountLogger.info(s"account_added_notice accountId=${account.getId} byAccountId=${creator.getId} " +
        s"contestId=$cId outcome=${outcome.key} inApp=true")
      outcome
    }
    SearchAdded(added, notice)
  }

  /** The mail telling an added account's owner (the add form's "Tell its owner by email"): from
    * [[systemSender]], to its address; none without one. `invited`: the role waits for an
    * invitation (or a password).
    */
  private def addedNotice(creator: User, account: User, contest: ContestJury, role: String, invited: Boolean)(implicit
      messages: Messages, linkHost: LinkHost): SearchNotice =
    if (account.emailLo.isEmpty) SearchNotice.NoEmail
    else {
      val subject = messages("account.added.notice.subject", contest.name)
      val body = messages("account.added.notice.body", contest.fullName, messages("role." + role),
        messages(AccountNotices.outcomeKey(role, invited)), AccountNotices.byText(creator.fullname),
        linkUrl(controllers.routes.LoginController.index.url))
      try {
        sendMail.sendMail(systemSender, account, subject, body)
        SearchNotice.Sent
      } catch {
        case NonFatal(e) =>
          accountLogger.warn(s"account_added_notice_failed accountId=${account.getId} contestId=${contest.getId} " +
            s"error=${e.getClass.getName}")
          SearchNotice.Failed
      }
    }

  /** [[addToContest]]'s cases once the account is found: `byEmail`, it was matched by its email
    * (or picked by id, [[addAccountToContest]]), else by its wiki account. `comment`: the account
    * change log's, for the role or the invitation; `mailFrom`: the sender of a mail it sends, if
    * not `creator`; `goneIf`: an account that it holds for gone under its row lock (deleted, say)
    * is refused ([[UserService.AccountNotFound]]), nothing written. None, nothing written: the
    * account was deleted (retired, A-4) before its row lock; the caller treats it as no account.
    */
  private def addExisting(
      creator: User,
      account: User,
      byEmail: Boolean,
      contest: Option[ContestJury],
      contestId: Option[Long],
      role: String,
      sendInvitation: Boolean,
      comment: Option[String] = None,
      mailFrom: Option[User] = None,
      goneIf: Option[User => Boolean] = None)(implicit messages: Messages, linkHost: LinkHost): Option[Added] = {
    val access = User.ACCESS_ROLES.contains(role)
    contestId.fold[Option[Added]](Some(AlreadyMember(account))) { cId =>
      // matched by email: whoever can sign in owns the address (or nobody can); matched by
      // wiki account only: only a verified wiki account says who holds it (H1), whatever
      // the account's email or password: a legacy self-set name, or an admin's typo
      def mayJoin(a: User) = !access || (if (byEmail) a.mayTakeAccessRole else a.wikiAccountVerified)
      User.findInContest(account.getId, cId) match {
        case Some(member) => Some(AlreadyMember(member))
        case None =>
          // decided and joined under the account's row lock, read again inside: a shared
          // link's acceptance, which takes the same lock before checking the account's
          // contests, either committed (this reads its password and unverified email) or
          // waits for this membership and then refuses its link (ShareLinkService). The lock
          // order is the acceptance's: the account's row, then its memberships and links
          val joined = scalikejdbc.DB.localTx { implicit session =>
            // an account picked by id and deleted meanwhile is none (checked here, not in
            // lockRow's SQL, which other callers share); so is any account deleted (retired,
            // A-4) meanwhile: None, nothing written
            val row = User.lockRow(account.getId).filterNot(l => l.deletedAt.nonEmpty || goneIf.exists(_(l)))
            if (goneIf.nonEmpty && row.isEmpty) throw new AccountNotFound
            if (row.isEmpty) None
            else Some(row.filter(mayJoin).map { locked =>
              val added = UserContestJdbc.createMembership(locked.getId, cId, role)
              if (added) AccountChangeLog.record(creator, locked.getId, Some(cId), AccountChangeJdbc.Role,
                newValue = Some(role), comment = comment)
              added -> locked
            })
          }
          joined.map {
            case Some((added, locked)) =>
              val member = User.findInContest(account.getId, cId).getOrElse(account)
              // no password, or a retired legacy one (P8): only an invitation lets its owner in
              val needsPassword = added && access && byEmail && !locked.hasUsablePassword &&
                !locked.isRoot && locked.emailLo.nonEmpty
              if (needsPassword)
                // no password to "use as before": the invitation sets one (none mailed unless
                // asked: the member's row offers to send it, or a link to share)
                Invited(member,
                  if (invitationRefused(creator, member)) Mail.NotPossible
                  else if (sendInvitation)
                    retiredMeanwhile(Mail.of(invite(creator, member, contest, None, comment = comment, from = mailFrom)))
                  else Mail.NotAsked)
              else {
                for (c <- contest if sendInvitation && added && c.greeting.use && mayBeGreeted(role))
                  sendMail(creator, member, c, messages("password.existing"), from = mailFrom)
                if (added) JoinedContest(member) else AlreadyMember(member)
              }
            case None if byEmail && account.emailLo.isEmpty =>
              // picked by id, an account with a password and no address: no invitation can reach it
              NoEmailToInvite(account)
            case None if byEmail && invitationRefused(creator, account) =>
              // an address an admin set, of an account this creator may not hand a password to
              Invited(account, Mail.NotPossible)
            case None if byEmail =>
              // the role waits for an invitation: kept unsent if no mail was asked for
              Invited(account, retiredMeanwhile {
                val sent = invite(creator, account, contest, Some(role), mail = sendInvitation, comment = comment,
                  from = mailFrom)
                if (sendInvitation) Mail.of(sent) else Mail.NotAsked
              })
            case None =>
              accountLogger.info(
                s"add_refused reason=wiki_account_unverified accountId=${account.getId} byAccountId=${creator.getId} contestId=$cId")
              WikiAccountUnverified(account)
          }
      }
    }
  }

  /** `invited`, or [[Mail.Retired]] if [[invite]] found the account retired (A-4) under its row
    * lock, after the join's transaction committed: nothing was created, and the page says so
    * rather than a 404 or a 500.
    */
  private def retiredMeanwhile(invited: => Mail): Mail =
    try invited
    catch { case _: AccountNotFound => Mail.Retired }

  /** Creates the account, without a password, with a membership in [[User.contestId]] if
    * set; for an access role and an email, an invitation sets the password: mailed if
    * `sendInvitation`, else none (the member's row offers to send one, or a link to share).
    * Fails on an email that already has an account. Never a root account, whoever creates it:
    * root is given on Edit user once its owner has proven the email (L2).
    */
  def createAccount(
      creator: User,
      formUser: User,
      contestOpt: Option[ContestJury],
      sendInvitation: Boolean = false)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): NewAccount = {
    val allowed = formUser.copy(roles = formUser.roles - User.ROOT_ROLE, isRoot = false)
    // no password: nobody signs in until the invitation is accepted (a password an organizer
    // chose, or one in a mail, would let someone else than the email's owner in)
    // a random, non-zero auth version: a session from before S2 (version 0, no account id) that
    // names this address or wiki account never resolves to the new account (H2)
    val createdUser = scalikejdbc.DB.localTx { implicit session =>
      val created = User.create(allowed.copy(password = None, emailVerifiedAt = None,
        authVersion = User.newAuthVersion()))
      AccountChangeLog.record(creator, created.getId, created.contestId, AccountChangeJdbc.AccountCreated,
        newValue = Some(created.emailLo).filter(_.nonEmpty).orElse(created.wikiAccount))
      for (contestId <- created.contestId; role <- created.contestRole)
        AccountChangeLog.record(creator, created.getId, Some(contestId), AccountChangeJdbc.Role, newValue = Some(role))
      created
    }
    val needsInvitation: Boolean = createdUser.emailLo.nonEmpty &&
      createdUser.contestRole.exists(User.ACCESS_ROLES.contains)
    val mail =
      if (!needsInvitation) Mail.NotNeeded
      else if (sendInvitation) retiredMeanwhile(Mail.of(invite(creator, createdUser, contestOpt, None)))
      else Mail.NotAsked
    NewAccount(createdUser, mail)
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
    val email = LoginText.email(formUser.email)
    // a deleted (retired, A-4) row is none: the retirement clears its address, so none is found
    // anyway; kept for a row deleted otherwise (addExisting checks it again under the row lock)
    if (email.nonEmpty) User.findByEmail(email).find(_.deletedAt.isEmpty).map(_ -> true)
    else
      formUser.wikiAccount.map(LoginText.clean).filter(_.nonEmpty)
        .flatMap(User.findByAccount(_).filter(_.deletedAt.isEmpty).headOption)
        .map(_ -> false)
  }

  /** Mails the account an invitation into the contest (if any), giving `role` once accepted
    * (None: the account is a member already); a newer invitation into the contest revokes the
    * older ones. Never to a root account, nor without an email. Whether the mail went out.
    * `mail` false: the invitation is kept unsent (`account_token.unsent`, V96), only for its
    * role to wait until the admin sends it or shares a link; its token is never shown. Logged in
    * the account change log, in the invitation's transaction, and whether it was mailed once the
    * mail is out: the mail is the last step that can fail the call, so an add or an import line
    * shown as failed sent none. Throws [[UserService.AccountNotFound]], nothing written, if the
    * account is deleted (retired) when its row is locked.
    */
  def invite(creator: User, account: User, contestOpt: Option[ContestJury], role: Option[String],
      mail: Boolean = true, comment: Option[String] = None, from: Option[User] = None)(implicit
      messages: Messages, linkHost: LinkHost = LinkHost.None
  ): Boolean = {
    require(!account.isRoot, "a root account is never invited")
    require(account.emailLo.nonEmpty, "an invitation needs an email")
    // callers check it first and say so; a missed one fails rather than hand the account over
    require(!invitationRefused(creator, account), "an invitation this creator may not send")
    val contestId = contestOpt.flatMap(_.id)
    // the invitation and its log entry together (#294): the entry is never missing; mailed or
    // not is set once the mail is out (until then NULL, shown as "not known").
    // Locks: the account's users row first, then its links, the order of a link's acceptance
    // (ShareLinkService) and of addToContest's join: in one transaction, the links' rows first
    // (revokeUnused) and the row after (the inserts' foreign keys) could deadlock with an
    // acceptance. No caller holds that row in another open transaction while it calls this
    // (addToContest's join commits first), or this would wait on it until
    // innodb_lock_wait_timeout; keep it so.
    val (tokenId, token, changeId) = scalikejdbc.DB.localTx { implicit session =>
      // a deleted (retired, A-4) account is never invited: retired after the caller read it (an
      // add's join committed first, then this), refused with nothing written
      if (!User.lockRow(account.getId).exists(_.deletedAt.isEmpty)) throw new AccountNotFound
      AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.Invitation), contestId)
      val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.Invitation, account.emailLo,
        contestId, role, creator.id, unsent = !mail, logCreated = false)
      val changeId = AccountChangeLog.record(creator, account.getId, contestId, AccountChangeJdbc.Invitation,
        newValue = role, mailSent = if (mail) None else Some(false), comment = comment)
      (tokenId, token, changeId)
    }
    // logged once it is committed: a rolled back link is never in the log
    AccountTokenService.logCreated(tokenId, account.getId, AccountTokenJdbc.Invitation, contestId, role, creator.id,
      unsent = !mail)
    val sent = mail && {
      val link = linkUrl(controllers.routes.LoginController.invitation(token).url)
      val (subject, body) = invitationText(creator, account, contestOpt, link)
      mailLink(from.getOrElse(creator), account, subject, body, AccountTokenJdbc.Invitation, tokenId)
    }
    // once the mail is out (a working link) the invitation stands: a flag that can't be written
    // doesn't fail the add or an import line (shown as failed, it would be imported again: a
    // second mail); the entry stays, without it, and the server log says so
    if (mail) changeId.foreach { id =>
      try recordMailSent(id, sent)
      catch {
        case NonFatal(e) =>
          accountLogger.error(s"account_change_mail_flag_not_recorded changeId=$id field=${AccountChangeJdbc.Invitation} " +
            s"accountId=${account.getId} byAccountId=${creator.getId} tokenId=$tokenId mailSent=$sent " +
            s"error=${e.getClass.getName}" + LogSafe.causesField(e), LogSafe.withoutMessages(e))
      }
    }
    sent
  }

  /** Sets whether the change log entry's mail went out (a separate step: tests make it fail). */
  protected def recordMailSent(changeId: Long, sent: Boolean): Unit =
    AccountChangeJdbc.setMailSent(changeId, sent)

  /** Whether `creator` may not send `account` an invitation (reviews of 2026-11-12): accepting one
    * sets the password, so an account that could sign in (a password, or a Google or Wikimedia
    * identity, linked or unlinked since) whose address a non-root admin set
    * (`users.email_set_by_admin_at`, [[User.emailSetByAdmin]]) gets one only from who may hand it
    * a set-password link anyway ([[ShareLinkService.handOverRefusal]]: root; an admin, for a
    * juror or organizer whose every tie is in their contests). Otherwise an admin could change a
    * co-admin's address to their own and invite it. An address its owner gave or proved, or root
    * set, is the owner's: the invitation reaches them. An invitee who never signed in is not
    * concerned: nobody's account is taken.
    */
  def invitationRefused(creator: User, account: User): Boolean =
    !(creator.isRoot || creator.hasRole(User.ROOT_ROLE)) && User.emailSetByAdmin(account.getId) &&
      (account.password.nonEmpty || UserIdentityJdbc.everLinked(account.getId)) &&
      ShareLinkService.handOverRefusal(creator, account).nonEmpty

  /** Sends the member (or a pending invitee) of the contest a new invitation: the role of a
    * pending one carried over. Whether the mail went out.
    */
  def reinvite(creator: User, account: User, contest: ContestJury)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): Boolean = {
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
  def sendPasswordLink(root: User, account: User)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): Boolean = {
    require(account.emailLo.nonEmpty, "a password link needs an email")
    AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.PasswordReset))
    val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.PasswordReset, account.emailLo,
      createdBy = root.id)
    val link = linkUrl(controllers.routes.LoginController.invitation(token).url)
    val days = AccountTokenService.PasswordResetValidSeconds / 86400
    val sent = mailLink(root, account, messages("password.link.subject"), messages("password.link.body", link, days),
      AccountTokenJdbc.PasswordReset, tokenId)
    AccountChangeLog.record(root, account.getId, None, AccountChangeJdbc.PasswordLink, mailSent = Some(sent))
    sent
  }

  /** Mails the account's verified address a short, single-use link that proves its holder is the
    * one choosing it in the OAuth account chooser ([[controllers.OAuthChooserController]]) for the
    * Wikimedia account `wikimediaName`; `path` makes the link's path of its token. False, sending
    * nothing, without a verified address, within a minute of the previous one, or after
    * [[ChooseLinksPerDay]] in a day: checked and created under the account's row lock (F5).
    */
  def sendChooseLink(account: User, wikimediaName: String, path: String => String)(implicit messages: Messages,
      linkHost: LinkHost = LinkHost.None): Boolean =
    if (!account.emailVerified || account.emailLo.isEmpty) false
    else {
      val created = scalikejdbc.DB.localTx { implicit session =>
        User.lockRow(account.getId)
        if (AccountTokenJdbc.createdSince(account.getId, AccountTokenJdbc.OAuthChoose, 60) ||
          AccountTokenJdbc.countSince(account.getId, AccountTokenJdbc.OAuthChoose, 86400) >= ChooseLinksPerDay) None
        else {
          AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.OAuthChoose))
          Some(AccountTokenService.create(account.getId, AccountTokenJdbc.OAuthChoose, account.emailLo, createdBy = account.id))
        }
      }
      created.fold(false) { case (tokenId, token) =>
        val minutes = AccountTokenService.OAuthChooseValidSeconds / 60
        mailLink(systemSender, account, messages("oauth.choose.mail.subject"),
          messages("oauth.choose.mail.body", linkUrl(path(token)), minutes, wikimediaName), AccountTokenJdbc.OAuthChoose, tokenId)
      }
    }

  /** Mails `newEmail` a link that makes it the account's email once followed. If another account
    * has that address, nothing is sent; the caller answers the same either way (N7).
    */
  def requestEmailChange(actor: User, account: User, newEmail: String)(implicit messages: Messages, linkHost: LinkHost = LinkHost.None): Unit = {
    val email = LoginText.email(newEmail)
    // one live link at a time, and a new one at most every EmailChangeInterval (L5)
    if (AccountTokenJdbc.createdSince(account.getId, AccountTokenJdbc.EmailChange, EmailChangeIntervalSeconds))
      accountLogger.info(s"email_change_requested accountId=${account.getId} byAccountId=${actor.getId} throttled=true")
    else {
      // the same work whether the address is free or not; the mail goes out after the answer
      // (N7: neither the answer nor its timing tells whether another account has the address).
      // A link to a taken address is created but never sent, and could not be confirmed.
      val taken = User.countByEmail(account.getId, email) > 0
      AccountTokenJdbc.revokeUnused(account.getId, Seq(AccountTokenJdbc.EmailChange))
      val (tokenId, token) = AccountTokenService.create(account.getId, AccountTokenJdbc.EmailChange, email,
        createdBy = actor.id)
      val link = linkUrl(controllers.routes.LoginController.emailConfirmPage(token).url)
      val hours = AccountTokenService.EmailChangeValidSeconds / 3600
      val subject = messages("email.change.subject")
      // what it is about (the maintainer, 2026-11-12): the account, the site, asked from its profile
      val body = messages("email.change.body", link, hours, siteName)
      accountLogger.info(s"email_change_requested accountId=${account.getId} byAccountId=${actor.getId} tokenId=$tokenId")
      mailInBackground {
        if (!taken) mailLink(actor, account.copy(email = email), subject, body, AccountTokenJdbc.EmailChange, tokenId)
      }
    }
  }

  /** Tells the account's former address that its email was changed (user decision): no link,
    * the new address masked, and whom to contact. Sent after the request's answer.
    */
  def notifyEmailChanged(account: User, oldEmail: String)(implicit messages: Messages): Unit =
    if (LoginText.clean(oldEmail).nonEmpty && LoginText.email(oldEmail) != account.emailLo) {
      val subject = messages("email.changed.subject")
      val body = messages("email.changed.body", UserService.maskEmail(account.emailLo))
      mailInBackground {
        // from the tool's own sender: the account's From would carry the new address
        try sendMail.sendMail(systemSender, account.copy(email = LoginText.email(oldEmail)), subject, body)
        catch {
          case NonFatal(e) =>
            accountLogger.warn(s"account_mail_failed purpose=email_changed accountId=${account.getId} error=${e.getClass.getName}")
        }
      }
    }

  /** Tells the account's former, verified address that `actor`, an admin or root, changed it
    * (the maintainer's decision of 2026-11-12): the new address masked, who changed it, and to
    * contact them if it is wrong; no link. Sent after the request's answer.
    */
  def notifyEmailChangedByAdmin(account: User, oldEmail: String, actor: User)(implicit messages: Messages): Unit =
    if (LoginText.clean(oldEmail).nonEmpty && LoginText.email(oldEmail) != account.emailLo) {
      val subject = messages("email.changed.subject")
      val newAddress = if (account.emailLo.isEmpty) messages("email.none") else UserService.maskEmail(account.emailLo)
      val body = messages("email.changed.by.admin.body", newAddress, actor.fullname, siteName)
      mailInBackground {
        try sendMail.sendMail(systemSender, account.copy(email = LoginText.email(oldEmail)), subject, body)
        catch {
          case NonFatal(e) =>
            accountLogger.warn(s"account_mail_failed purpose=email_changed_by_admin accountId=${account.getId} " +
              s"error=${e.getClass.getName}")
        }
      }
    }

  /** An admin's or root's change of another account's email (the maintainer's decision of
    * 2026-11-12), directly, without a confirmation link, in the caller's transaction (the
    * account's row locked first, decided inside): the new address (blank: none) is unverified,
    * the password stays (the person signs in with the new address and the same password),
    * every session ends (auth_version + 1, [[User.setEmail]]), the account's unused set-password
    * and email-change links go (they were bound to the old address), and its Google and Wikimedia
    * identities are unlinked (they may have been linked by the old address), as an invitee's
    * fixed address did before. Logged in the account change log, with whether the former address
    * gets a notice (only a verified one: [[notifyEmailChangedByAdmin]], sent by the caller after
    * the commit). Refused, a message key, writing nothing, if another account has the address.
    */
  def changeEmailByAdmin(actor: User, accountId: Long, newEmail: String, contestId: Option[Long],
      comment: Option[String], expectedOld: Option[String] = None)(implicit session: DBSession): Either[String, EmailChanged] = {
    val email = LoginText.email(newEmail)
    User.lockRow(accountId) match {
      case None => Left("error.user.not.found")
      // changed by someone else since the form was checked: not overwritten
      case Some(account) if expectedOld.exists(_ != account.emailLo) => Left("user.email.changed.meanwhile")
      case Some(_) if email.nonEmpty && User.countByEmail(accountId, email) > 0 => Left("error.email.already.exist")
      case Some(account) =>
        val old = account.emailLo
        val notifyFormer = old.nonEmpty && old != email && account.emailVerified
        val unlinked =
          if (old == email) 0
          else {
            // marked as set by an admin unless root set it (V96, [[invitationRefused]])
            User.setEmail(accountId, email, verified = false,
              byAdmin = !(actor.isRoot || actor.hasRole(User.ROOT_ROLE)))
            AccountTokenJdbc.revokeUnused(accountId, AccountTokenJdbc.SetsPassword :+ AccountTokenJdbc.EmailChange)
            AccountChangeLog.record(actor, accountId, contestId, AccountChangeJdbc.Email, Some(old), Some(email),
              mailSent = Some(notifyFormer), comment = comment)
            UserIdentityJdbc.deleteByUser(accountId, None)
          }
        Right(EmailChanged(old, old != email, notifyFormer, unlinked))
    }
  }

  /** The site's name in mails: the host of `wlxjury.host`. */
  def siteName: String = {
    val configured = configuration.get[String]("wlxjury.host")
    scala.util.Try(new java.net.URI(configured).getHost).toOption.flatMap(Option(_)).getOrElse(configured)
  }

  /** The tool's own sender, for mail no person sends (the old address's notice):
    * `wlxjury.mail.from`, else the SMTP user if it is an address, else `no-reply@` the host of
    * `wlxjury.host`.
    */
  def systemSender: User = {
    def address(v: String) = Some(v.trim).filter(a => play.api.data.validation.Constraints.emailAddress()(a) ==
      play.api.data.validation.Valid)
    val email = configuration.getOptional[String]("wlxjury.mail.from").flatMap(address)
      .orElse(configuration.getOptional[String]("play.mailer.user").flatMap(address))
      .getOrElse {
        val host = scala.util.Try(new java.net.URI(configuration.get[String]("wlxjury.host")).getHost).toOption
          .flatMap(Option(_)).getOrElse("localhost")
        s"no-reply@$host"
      }
    User(fullname = "Jury tool", email = email)
  }

  /** Runs `send` after the request's answer (the mail server's time not in it). */
  protected def mailInBackground(send: => Unit): Unit =
    scala.concurrent.Future(scala.concurrent.blocking(send))(scala.concurrent.ExecutionContext.global)

  /** The invitation's subject and text: the contest's greeting, if it uses one, with a fixed
    * text in place of `{{Password}}` and the link in a paragraph of the app's own after it; else
    * a standard text.
    */
  private def invitationText(creator: User, recipient: User, contestOpt: Option[ContestJury], link: String)(implicit
      messages: Messages
  ): (String, String) = {
    val days = AccountTokenService.InvitationValidSeconds / 86400
    contestOpt match {
      case Some(contest) if contest.greeting.use =>
        // the link only in the app's own paragraph, never inside the admin's text (M2):
        // {{Password}} there says where the password comes from
        val template = getGreeting(contest).text.getOrElse("")
        val filled = fillGreeting(template, contest, creator,
          recipient.copy(password = Some(messages("invitation.password.placeholder"))))
        (messages("welcome.subject", contest.name), filled + "\n\n" + messages("invitation.link", link, days))
      case Some(contest) =>
        (messages("invitation.subject", contest.name),
          messages("invitation.body", contest.name, creator.fullname, link, days))
      case None =>
        (messages("invitation.subject.tool"), messages("invitation.body.tool", link, days, creator.fullname))
    }
  }

  /** The full URL of an invitation's or a password link's page for `token` ([[linkUrl]]), as a
    * mail has it: a shared link's ([[ShareLinkService]]), shown once to its creator.
    */
  def invitationUrl(token: String)(implicit linkHost: LinkHost): String =
    linkUrl(controllers.routes.LoginController.invitation(token).url)

  /** The link's full URL (user decision): on the host the request came to, so that a juror of
    * jury.wlm.org.ua gets a link there, but only if that host is one of the configured
    * `wlxjury.allowedHosts`, named exactly (no suffix pattern, no port): a forged `Host` header
    * never chooses where a password link points. Otherwise, and without a request (a background
    * job), the configured `wlxjury.host`. The scheme is always `wlxjury.host`'s.
    */
  private[services] def linkUrl(path: String)(implicit linkHost: LinkHost): String = {
    val configured = configuration.get[String]("wlxjury.host").stripSuffix("/")
    val scheme = scala.util.Try(new java.net.URI(configured).getScheme).toOption.flatMap(Option(_)).getOrElse("https")
    val named = configuration.getOptional[String](modules.AllowedHosts.Key)
      .flatMap(v => scala.util.Try(modules.AllowedHosts.parse(v)).toOption).getOrElse(Nil)
      .filter(h => !h.startsWith(".") && !h.contains(":")).map(_.toLowerCase(java.util.Locale.ROOT))
    linkHost.host.map(_.toLowerCase(java.util.Locale.ROOT)).filter(named.contains)
      .fold(configured)(host => s"$scheme://$host") + path
  }

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
               password: String,
               from: Option[User] = None)(implicit messages: Messages): Unit = {
    val greeting = getGreeting(contest)
    val subject = messages("welcome.subject", contest.name)
    val message = fillGreeting(greeting.text.get,
                               contest,
                               creator,
                               recipient.copy(password = Some(password)))
    sendMail.sendMail(from.getOrElse(creator), recipient, subject, message)
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

/** The host a request came to, for the links mailed while serving it ([[UserService.linkUrl]]);
  * [[LinkHost.None]] without a request.
  */
final case class LinkHost(host: Option[String])

object LinkHost {
  val None: LinkHost = LinkHost(scala.None)
  def of(request: play.api.mvc.RequestHeader): LinkHost = LinkHost(Some(request.host))
}

object UserService {

  /** What [[UserService.addToContest]] did. */
  sealed trait Added { def user: User }

  /** What became of an invitation an add needed. */
  sealed abstract class Mail
  object Mail {
    /** None was needed: a role without access, root, or no email. */
    case object NotNeeded extends Mail
    /** One was needed, but no mail was asked for ("Send an invitation email" unticked). */
    case object NotAsked extends Mail
    case object Sent extends Mail
    /** The mail failed: it can be sent again. */
    case object Failed extends Mail
    /** None may be sent by this creator ([[UserService.invitationRefused]]): nothing created. */
    case object NotPossible extends Mail
    /** The account was retired (A-4) between the add's decision and the invitation: none created. */
    case object Retired extends Mail
    def of(sent: Boolean): Mail = if (sent) Sent else Failed
  }

  /** A new account, a member; `invitation`: whether its invitation was needed and went out. */
  case class NewAccount(user: User, invitation: Mail = Mail.NotNeeded) extends Added
  case class JoinedContest(user: User) extends Added
  case class AlreadyMember(user: User) extends Added

  /** An unverified account with a password had the email (it joins once its owner accepts), or
    * a member without a usable password joined: invited (`invitation`: mailed, failed, or not
    * asked for).
    */
  case class Invited(user: User, invitation: Mail) extends Added

  /** An admin's or root's change of another account's email ([[UserService.changeEmailByAdmin]]):
    * its former address, whether it changed, whether the former address gets a notice, and how
    * many identities were unlinked.
    */
  final case class EmailChanged(oldEmail: String, changed: Boolean, notifyFormer: Boolean, unlinked: Int)

  /** An add from the users page's search ([[UserService.addAccountToContest]]): what became of the
    * account, and of the mail to its owner.
    */
  final case class SearchAdded(added: Added, notice: SearchNotice)

  /** The mail to an added account's owner ([[UserService.addAccountToContest]]); the notice in the
    * app is there whenever the add gave something.
    */
  sealed abstract class SearchNotice(val key: String)
  object SearchNotice {
    /** Nothing given: no notice. */
    case object NotNeeded extends SearchNotice("not_needed")
    /** "Tell its owner by email" unticked: the notice in the app only. */
    case object NotSent extends SearchNotice("not_sent")
    case object Sent extends SearchNotice("sent")
    case object Failed extends SearchNotice("failed")
    /** Ticked, but the account has no email. */
    case object NoEmail extends SearchNotice("no_email")
  }

  /** Not added: the account picked by id ([[UserService.addAccountToContest]]) may not take the
    * role directly and has no email for an invitation.
    */
  case class NoEmailToInvite(user: User) extends Added

  /** Not added: the wiki account given names an account whose wiki account is unverified. */
  case class WikiAccountUnverified(user: User) extends Added

  /** A role [[UserService.addToContest]] refuses ([[User.mayAssignRoles]]); the edit form's
    * save maps it, and only it, to a 400.
    */
  class RoleNotAssignable extends IllegalArgumentException("not a contest role")

  /** The form names a contest that does not exist ([[UserService.createNewUser]]). */
  class ContestNotFound extends IllegalArgumentException("no such contest")

  /** A wiki account name MediaWiki would refuse ([[User.normalizeWikiAccount]]). */
  class InvalidWikiAccount extends IllegalArgumentException("not a valid wiki account")

  /** An account id that no account has, or a deleted account's ([[UserService.addAccountToContest]]):
    * the 404 of a missing resource.
    */
  class AccountNotFound extends NoSuchElementException("no such account")

  /** The account change log's comment on a role or an invitation given from the users page's
    * search ([[UserService.addAccountToContest]], accounts plan A-1).
    */
  val SearchComment = "from search"

  /** Whether a comment typed in the edit form would read as [[SearchComment]], which only the search
    * writes: the column compares case- and trailing-space-insensitively, so any case and spacing.
    */
  def isReservedComment(comment: String): Boolean =
    comment.trim.replaceAll("\\s+", " ").equalsIgnoreCase(SearchComment)

  /** An address with all but its first character before the `@` hidden: `a***@example.org`. */
  def maskEmail(email: String): String = email.indexOf('@') match {
    case at if at > 0 => email.take(1) + "***" + email.substring(at)
    case _            => "***"
  }

  /** The most account chooser links an account gets in a day (F5). */
  val ChooseLinksPerDay = 5

  /** The shortest time between two email-change links of an account. */
  val EmailChangeIntervalSeconds: Long = 60
}
