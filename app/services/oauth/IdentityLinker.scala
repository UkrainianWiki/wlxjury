package services.oauth

import db.scalikejdbc.{User, UserIdentity, UserIdentityJdbc}
import org.intracer.wmua.LoginText
import play.api.Logger
import scalikejdbc._
import services.AccountTokenService

import java.sql.SQLIntegrityConstraintViolationException

/** Why a Google or Wikimedia identity does not sign anyone in; `messageKey` is what the page
  * says, `reason` what the log line says.
  */
sealed abstract class LinkError(val messageKey: String, val reason: String)

object LinkError {

  /** No account matches it: sign-in never creates one; accounts come from invitations (S2). */
  case object NoAccount extends LinkError("oauth.no.account", "no_account")

  /** Several accounts match it (a wiki account and an email of two accounts), or its account has
    * no login name of its own.
    */
  case object Ambiguous extends LinkError("oauth.ambiguous", "ambiguous")

  /** The one account it matches may be held by someone else (S2): its email unverified while it
    * has a password, or no live invitation to prove it with, or its wiki account not a
    * placeholder's. Its holder signs in with the password (or accepts their invitation) and
    * links the identity from their profile.
    */
  case object Unverified extends LinkError("oauth.unverified", "unverified")

  /** The account with this wiki name has, or had, a Wikimedia identity: a by-name link could be a
    * renamed account's name taken by someone else (M1).
    */
  case object OtherWikimediaIdentity extends LinkError("oauth.wiki.other.identity", "other_wikimedia_identity")

  /** A Google sign-in whose address Google is not authoritative for (no Gmail address, no
    * Workspace account: M4): the person accepts the mailed invitation first, then links Google
    * from their profile.
    */
  case object EmailNotAuthoritative extends LinkError("oauth.google.email.not.proof", "email_not_authoritative")

  /** Several accounts have its Wikimedia username as their wiki account (duplicates): the user
    * chooses one ([[controllers.OAuthController]]'s chooser; root accounts are left out).
    */
  final case class Choose(accounts: Seq[User]) extends LinkError("oauth.ambiguous", "choose")

  /** It was unlinked (by its user, or as its account changed hands): a first sign-in never links
    * it again; only its holder's own profile does (review F2).
    */
  case object UnlinkedBefore extends LinkError("oauth.unlinked.before", "unlinked_before")

  /** Linking it to an account: it is linked to another one already. */
  case object LinkedToOther extends LinkError("oauth.linked.other", "linked_to_other")
}

/** Finds the account a Google or Wikimedia identity signs in to (S2's account rules and the
  * OAuth review's fixes, docs/oauth.md, docs/security/secure-coding.md §14).
  *
  * An identity signed in with before (user_identity) keeps its account, unless that is deleted.
  * On its first sign-in it is linked to the one existing account its provider names (root's too,
  * by the same rules: the user's decision on B2), and only if nobody else may hold that account:
  *
  *  - by email (provider-verified, and the provider authoritative for it: M4,
  *    [[ExternalProfile.emailAuthoritative]]): the account's email is verified; or the account has no
  *    password and a live invitation or password link to that same address, which the sign-in
  *    then accepts as following the link would, without a password
  *    ([[AccountTokenService.acceptByProvider]]: the user's decision 1, review B3);
  *  - by wiki account (the Wikimedia username): only a placeholder (no password, no email) whose
  *    wiki account no Wikimedia identity has or had (none linked, never verified: M1).
  *
  * Otherwise a [[LinkError]]: the holder signs in with the password and links from their profile.
  * A sign-in never creates an account.
  */
object IdentityLinker {

  private val log = Logger("security.account")

  def resolve(profile: ExternalProfile): Either[LinkError, User] =
    UserIdentityJdbc.findAny(profile.provider, profile.subject) match {
      case Some((identity, false)) => known(identity, profile)
      case Some((_, true))         => Left(LinkError.UnlinkedBefore)
      case None =>
        val byEmail   = emailUsers(profile)
        val byAccount = accountUsers(profile)
        val choosable = byAccount.filterNot(_.isRoot)
        if (byAccount.size > 1 && choosable.nonEmpty) Left(LinkError.Choose(choosable))
        else (byAccount ++ byEmail).distinctBy(_.id) match {
          case Seq() if profile.emailNotAuthoritative => Left(LinkError.EmailNotAuthoritative)
          case Seq() => Left(LinkError.NoAccount)
          case Seq(user) =>
            val email = byEmail.exists(_.id == user.id)
            val name  = byAccount.exists(_.id == user.id)
            if (email && user.emailVerified)
              link(user.getId, profile).map { _ =>
                verifyWikiAccount(user, profile)
                user
              }
            else if (email && user.password.isEmpty) byInvitation(user, profile)
            else if (name && hadWikimediaIdentity(user)) Left(LinkError.OtherWikimediaIdentity)
            else if (name && wikiPlaceholder(user))
              link(user.getId, profile).map { _ =>
                verifyWikiAccount(user, profile)
                user
              }
            else Left(LinkError.Unverified)
          case _ => Left(LinkError.Ambiguous)
        }
    }

  /** An identity signed in with before. */
  private def known(identity: UserIdentity, profile: ExternalProfile): Either[LinkError, User] =
    User.findById(identity.userId).filter(_.deletedAt.isEmpty) match {
      case None => Left(LinkError.NoAccount)
      case Some(user) =>
        renamedAway(user, identity, profile)
        UserIdentityJdbc.touch(identityOf(profile, user.getId))
        verifyWikiAccount(user, profile)
        // an invitee's own address, proven by the provider, with a live link: accepted as the
        // link would be (B3); the identity, which that unlinks, is linked again in its transaction
        val proven =
          if (!user.emailVerified && user.password.isEmpty && profile.verifiedEmail.contains(user.emailLo))
            AccountTokenService.acceptByProvider[LinkError](user.getId, user.emailLo) { (_, s) =>
              link(user.getId, profile, relink = true)(s)
            }.toOption
          else None
        Right(proven.orElse(User.findById(user.getId)).getOrElse(user))
    }

  /** A first sign-in by an invitee's own address (B3): only with a live invitation or password
    * link to it, which it accepts; the identity is linked in that transaction.
    */
  private def byInvitation(user: User, profile: ExternalProfile): Either[LinkError, User] =
    AccountTokenService.acceptByProvider[LinkError](user.getId, profile.verifiedEmail.getOrElse("")) { (_, s) =>
      link(user.getId, profile)(s)
    }.left.map(_.getOrElse(LinkError.Unverified))

  /** Whether the chooser may link the account without a proof: the same rule as a by-name first
    * sign-in (a never-linked placeholder, M1), never root.
    */
  def choosableDirectly(user: User): Boolean =
    !user.isRoot && wikiPlaceholder(user) && !hadWikimediaIdentity(user)

  /** Links the identity to the account chosen (directly, or after a proof of control) and marks
    * its wiki account, the identity's username, verified.
    */
  def linkChosen(user: User, profile: ExternalProfile): Either[LinkError, User] =
    if (user.isRoot || !sameWikiAccount(user, profile)) Left(LinkError.NoAccount)
    else
      link(user.getId, profile).map { _ =>
        if (!user.wikiAccountVerified) user.wikiAccount.foreach(User.verifyWikiAccount(user.getId, _))
        User.findById(user.getId).getOrElse(user)
      }

  /** A wiki-only placeholder: nobody can sign in to it and no invitation can reach anyone (H1). */
  private[oauth] def wikiPlaceholder(user: User): Boolean = user.password.isEmpty && user.emailLo.isEmpty

  /** Whether a Wikimedia identity has (one linked) or had (its wiki account verified: only a
    * Wikimedia identity verifies one) this account's wiki account (M1).
    */
  private[oauth] def hadWikimediaIdentity(user: User): Boolean =
    user.wikiAccountVerified || UserIdentityJdbc.hasProvider(user.getId, OAuthProviders.Wikimedia)

  /** M1: the linked Wikimedia account was renamed away from the verified wiki account: the name
    * is no longer proven (someone else may register it).
    */
  private def renamedAway(user: User, identity: UserIdentity, profile: ExternalProfile): Unit =
    if (profile.provider == OAuthProviders.Wikimedia && user.wikiAccountVerified) {
      def norm(n: Option[String]) = n.flatMap(User.normalizeWikiAccount(_).toOption.flatten)
      val account = norm(user.wikiAccount)
      if (account.nonEmpty && norm(identity.username) == account && wikiName(profile) != account) {
        User.unverifyWikiAccount(user.getId)
        log.warn(s"wiki_account_unverified reason=wikimedia_renamed accountId=${user.getId}")
      }
    }

  /** Links the identity to the user (who signed in and asked to, or [[resolve]]'s match), or
    * records a sign-in if it is theirs already. An unlinked one (a tombstone) is linked again only
    * if `relink`: from the user's own profile, or the identity signing in itself (F2).
    */
  def link(userId: Long, profile: ExternalProfile, relink: Boolean = false)(implicit
      session: DBSession = AutoSession
  ): Either[LinkError, Unit] =
    UserIdentityJdbc.findAny(profile.provider, profile.subject) match {
      case Some((identity, false)) if identity.userId == userId =>
        Right(UserIdentityJdbc.touch(identityOf(profile, userId)))
      case Some((_, false)) =>
        Left(LinkError.LinkedToOther)
      case Some((_, true)) =>
        if (relink && UserIdentityJdbc.relink(identityOf(profile, userId))) Right(()) else Left(LinkError.UnlinkedBefore)
      case None =>
        try Right(UserIdentityJdbc.create(identityOf(profile, userId)))
        catch {
          // linked meanwhile, by a concurrent sign-in (perhaps to this very user), or the
          // user is gone (the foreign key)
          case _: SQLIntegrityConstraintViolationException =>
            UserIdentityJdbc.find(profile.provider, profile.subject) match {
              case Some(identity) if identity.userId == userId => Right(())
              case Some(_)                                     => Left(LinkError.LinkedToOther)
              case None                                        => Left(LinkError.NoAccount)
            }
        }
    }

  /** A Wikimedia identity's username, as MediaWiki writes it ([[User.normalizeWikiAccount]]). */
  def wikiName(profile: ExternalProfile): Option[String] =
    profile.username.filter(_ => profile.provider == OAuthProviders.Wikimedia)
      .flatMap(User.normalizeWikiAccount(_).toOption.flatten)

  /** Whether the account's wiki account is the Wikimedia identity's username, exactly after the
    * first letter, as MediaWiki tells "FooBar" from "Foobar" and "José" from "Jose".
    */
  def sameWikiAccount(user: User, profile: ExternalProfile): Boolean =
    wikiName(profile).exists(name => user.wikiAccount.flatMap(User.normalizeWikiAccount(_).toOption.flatten).contains(name))

  /** A Wikimedia sign-in by the account's own wiki account proves it. */
  def verifyWikiAccount(user: User, profile: ExternalProfile): Unit =
    if (!user.wikiAccountVerified && sameWikiAccount(user, profile))
      user.wikiAccount.foreach(User.verifyWikiAccount(user.getId, _))

  /** Accounts with the provider-verified email, not deleted. */
  private def emailUsers(profile: ExternalProfile): Seq[User] =
    profile.verifiedEmail.toSeq.flatMap(User.findByEmail).filter(_.deletedAt.isEmpty)

  /** For a Wikimedia identity, the accounts whose wiki account is its username
    * ([[wikiAccountUsers]]).
    */
  private def accountUsers(profile: ExternalProfile): Seq[User] =
    wikiName(profile).map(wikiAccountUsers).getOrElse(Nil)

  /** Non-deleted accounts whose wiki account is the username, as organizers may have typed it
    * before S2 normalised names: with underscores, a "User:" prefix or a lower-case first letter.
    * The rest must match exactly ([[sameWikiAccount]]); the column's collation does not, so it
    * only narrows the rows down.
    */
  def wikiAccountUsers(username: String): Seq[User] =
    User.normalizeWikiAccount(username).toOption.flatten.fold(Seq.empty[User]) { name =>
      val u = User.u
      User
        .findAllBy(
          sqls"REPLACE(TRIM(${u.wikiAccount}), '_', ' ') IN (${name}, ${"User:" + name})"
            .and(User.isNotDeleted)
        )
        .filter(_.wikiAccount.flatMap(User.normalizeWikiAccount(_).toOption.flatten).contains(name))
    }

  private def identityOf(profile: ExternalProfile, userId: Long): UserIdentity =
    UserIdentity(
      provider  = profile.provider,
      subject   = profile.subject,
      userId    = userId,
      email     = profile.email.map(LoginText.email).filter(_.nonEmpty),
      username  = profile.username
    )
}
