package services.oauth

import db.scalikejdbc.{User, UserIdentity, UserIdentityJdbc}
import scalikejdbc._

import java.sql.SQLIntegrityConstraintViolationException

/** Why a Google or Wikimedia identity does not sign anyone in; the value is a message key. */
sealed abstract class LinkError(val messageKey: String)

object LinkError {

  /** No account matches it: sign-in never creates one, an organizer adds jurors. */
  case object NoAccount extends LinkError("oauth.no.account")

  /** Several accounts match it (the same wiki account on two of them). */
  case object Ambiguous extends LinkError("oauth.ambiguous")

  /** Linking it to an account: it is linked to another one already. */
  case object LinkedToOther extends LinkError("oauth.linked.other")
}

/** Finds the account a Google or Wikimedia identity signs in to.
  *
  * An identity signed in with before (user_identity) keeps its account. On its first
  * sign-in it is linked to the one existing account that has its verified email or, for a
  * Wikimedia one, its username as the wiki account: the same organizer-entered data that
  * the email and wiki-mail invitations with a password already trust.
  */
object IdentityLinker {

  def resolve(profile: ExternalProfile): Either[LinkError, User] =
    UserIdentityJdbc.find(profile.provider, profile.subject) match {
      case Some(identity) =>
        active(User.findById(identity.userId)).toRight(LinkError.NoAccount).map { user =>
          UserIdentityJdbc.touch(identityOf(profile, user.getId))
          user
        }
      case None =>
        candidates(profile) match {
          case Seq(user) => link(user.getId, profile).map(_ => user)
          case Seq()     => Left(LinkError.NoAccount)
          case _         => Left(LinkError.Ambiguous)
        }
    }

  /** Links the identity to the user (who signed in and asked to), or records a sign-in if
    * it is theirs already.
    */
  def link(userId: Long, profile: ExternalProfile): Either[LinkError, Unit] =
    UserIdentityJdbc.find(profile.provider, profile.subject) match {
      case Some(identity) if identity.userId == userId =>
        Right(UserIdentityJdbc.touch(identityOf(profile, userId)))
      case Some(_) =>
        Left(LinkError.LinkedToOther)
      case None =>
        try Right(UserIdentityJdbc.create(identityOf(profile, userId)))
        catch {
          // linked meanwhile, by a concurrent sign-in
          case _: SQLIntegrityConstraintViolationException => Left(LinkError.LinkedToOther)
        }
    }

  /** The existing accounts an identity signed in with for the first time may be. */
  private[oauth] def candidates(profile: ExternalProfile): Seq[User] = {
    val byEmail = profile.verifiedEmail.toSeq.flatMap(User.findByEmail).filter(_.deletedAt.isEmpty)
    profile.provider match {
      case OAuthProviders.Wikimedia =>
        val byAccount = profile.username.map(wikiAccountUsers).getOrElse(Nil)
        if (byAccount.nonEmpty) byAccount else byEmail
      case _ =>
        byEmail
    }
  }

  /** Accounts whose wiki account is the username, as organizers may have typed it: any
    * case (the column's collation), with underscores or a "User:" prefix.
    */
  private def wikiAccountUsers(username: String): Seq[User] = {
    val name = canonicalWikiName(username)
    if (name.isEmpty) Nil
    else {
      val u = User.u
      User.findAllBy(
        sqls"REPLACE(TRIM(${u.wikiAccount}), '_', ' ') IN (${name}, ${"User:" + name})"
          .and(User.isNotDeleted)
      )
    }
  }

  /** A username as MediaWiki shows it: spaces, not underscores, and a capital first letter. */
  private[oauth] def canonicalWikiName(username: String): String = {
    val name = username.replace('_', ' ').trim.replaceAll("\\s+", " ").replaceFirst("(?i)^user:\\s*", "")
    name.headOption.fold("")(first => s"${first.toUpper}${name.tail}")
  }

  private def active(user: Option[User]): Option[User] = user.filter(_.deletedAt.isEmpty)

  private def identityOf(profile: ExternalProfile, userId: Long): UserIdentity =
    UserIdentity(
      provider = profile.provider,
      subject  = profile.subject,
      userId   = userId,
      email    = profile.email.map(_.trim.toLowerCase).filter(_.nonEmpty),
      username = profile.username
    )
}
