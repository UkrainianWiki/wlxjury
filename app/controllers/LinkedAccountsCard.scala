package controllers

import db.scalikejdbc.UserIdentity

/** The own profile's linked Google and Wikimedia accounts (linkedAccounts.scala.html,
  * [[OAuthController]]): the user's identities, each with Unlink, and the providers this host
  * offers to link another with, each link carrying `linkNonce` (M3: the link start accepts only
  * this page's). Shown only if there is either.
  */
final case class LinkedAccountsCard(identities: Seq[UserIdentity], providers: Seq[String], linkNonce: String = "") {

  def nonEmpty: Boolean = identities.nonEmpty || providers.nonEmpty

  /** The providers to show, in the sign-in page's order, then any linked one no longer offered. */
  def rows: Seq[String] = (providers ++ identities.map(_.provider)).distinct
}
