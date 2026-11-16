package services

import db.scalikejdbc.{ContestJuryJdbc, User, UserContestJdbc}
import org.intracer.wmua.{ContestJury, LoginText}
import play.api.Logger

/** The users page's "Find an existing account" (accounts plan A-1, A1 decided by the maintainer
  * 2026-11-16): root and a contest's admins find every non-deleted account, by any part of its
  * name or wiki account, or by its whole email address. By a part of the address only within the
  * contests the actor administers (root: every account), so that the masked addresses can't be
  * rebuilt one character at a time. A result shows the address in full only for an account in
  * the actor's contests (a member or a live invitee, as their users pages show it) and to root;
  * otherwise masked ([[UserService.maskEmail]]). Never shown to an admin: the account's other
  * contests (not even a count), its identities or sign-in times.
  */
object AccountSearch {

  /** The least length of a search's text (after trimming): a name or wiki account by any part of
    * at least this many characters.
    */
  val MinLength = 3

  /** The longest text searched for: an email address's longest. */
  val MaxLength = 254

  /** The most results a search shows. */
  val MaxResults = 20

  private val accountLogger = Logger("security.account")

  /** An email-looking part of a text: what [[maskEmailsIn]] masks. */
  private val EmailLike = """[^\s@<>()\[\],;:"']+@[^\s@<>()\[\],;:"']+""".r

  /** `text` with each email-looking part masked ([[UserService.maskEmail]]): a name or wiki
    * account someone typed an address into shows no more of it than the masked email (L3).
    */
  def maskEmailsIn(text: String): String =
    EmailLike.replaceAllIn(text, m => scala.util.matching.Regex.quoteReplacement(UserService.maskEmail(m.matched)))

  /** A found account: its name and wiki account as shown (an address typed into them masked unless
    * its email is shown in full), its email as the actor may see it (None: it has none), whether that is the
    * full address; its memberships the actor may see (in the contests they administer; root: all),
    * each with its contest if it still exists; its role in the searched contest if a member; and
    * whether the edit page would open it for the actor ([[controllers.UserController.editableUser]]).
    */
  final case class Found(
      account: User,
      name: String,
      wikiAccount: Option[String],
      email: Option[String],
      emailFull: Boolean,
      contests: Seq[(Long, Option[ContestJury], Set[String])],
      member: Option[Set[String]],
      editable: Boolean)

  sealed trait Outcome
  /** The text is shorter than [[MinLength]] or longer than [[MaxLength]]: nothing searched. */
  case object Unsearchable extends Outcome
  /** At most [[MaxResults]] accounts; `more`: others matched too. */
  final case class Results(found: Seq[Found], more: Boolean) extends Outcome

  /** Whether the actor is root: every account is within their contests. */
  def isRoot(actor: User): Boolean = actor.isRoot || actor.hasRole(User.ROOT_ROLE)

  /** The contests the actor administers (an active admin membership); None for root, who
    * administers every contest.
    */
  def administered(actor: User): Option[Seq[Long]] =
    if (isRoot(actor)) None
    else Some(actor.userContests.filter(m => m.active && m.roles.contains(User.ADMIN_ROLE)).map(_.contestId).distinct)

  /** `accounts` as `actor` may see them ([[Found]], the search's rule), with their membership of
    * `contestId` if given: the Edit user form's panel of the other accounts with a wiki account
    * (accounts plan A-2) shows them so. Root accounts are left out unless `actor` is root (L1).
    */
  def describe(actor: User, contestId: Option[Long], accounts: Seq[User]): Seq[Found] = {
    val shown = accounts.filter(a => a.deletedAt.isEmpty && (isRoot(actor) || !(a.isRoot || a.hasRole(User.ROOT_ROLE))))
    val within = User.withinActor(shown.map(_.getId), administered(actor))
    described(actor, contestId, shown.map(a => a -> within.contains(a.getId)))
  }

  /** Each account with whether it is within the actor's contests, as a [[Found]]. */
  private def described(actor: User, contestId: Option[Long], rows: Seq[(User, Boolean)]): Seq[Found] = {
    val adminOf = administered(actor)
    val ids = rows.map(_._1.getId)
    val memberships = UserContestJdbc.findByUsers(ids)
    val contestIds = memberships.values.flatten.map(_.contestId).toSeq.distinct
      .filter(id => adminOf.forall(_.contains(id)))
    val contests = if (contestIds.isEmpty) Map.empty[Long, ContestJury]
      else ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, contestIds))
        .flatMap(c => c.id.map(_ -> c)).toMap
    val root = adminOf.isEmpty
    rows.map { case (account, within) =>
      val own = memberships.getOrElse(account.getId, Nil)
      val member = contestId.flatMap(c => own.find(_.contestId == c)).map(_.roles)
      val email = Option(account.emailLo).filter(_.nonEmpty)
      val full = root || within
      def shown(text: String) = if (full) text else maskEmailsIn(text)
      Found(
        account,
        name = shown(Option(account.fullname).getOrElse("")),
        wikiAccount = account.wikiAccount.filter(_.trim.nonEmpty).map(shown),
        email.map(e => if (full) e else UserService.maskEmail(e)),
        emailFull = email.nonEmpty && full,
        contests = own.filter(m => adminOf.forall(_.contains(m.contestId))).sortBy(-_.contestId)
          .map(m => (m.contestId, contests.get(m.contestId), m.roles)),
        member = member,
        editable = root || member.nonEmpty)
    }
  }

  /** The accounts `actor` finds by `text` from the users page of `contestId`, which the caller
    * checked they administer (or are root). Each search is logged at INFO on `security.account`
    * by ids only (`account_search byAccountId= contestId= results= length=`), never its text, so
    * that probing shows up.
    */
  def search(actor: User, contestId: Long, text: String): Outcome = {
    val searched = LoginText.clean(text)
    val outcome =
      if (searched.length < MinLength || searched.length > MaxLength) Unsearchable
      else {
        val rows = User.searchAccounts(searched, administered(actor), MaxResults + 1)
        Results(described(actor, Some(contestId), rows.take(MaxResults)), more = rows.size > MaxResults)
      }
    val count = outcome match {
      case Results(found, _) => found.size
      case Unsearchable      => 0
    }
    accountLogger.info(s"account_search byAccountId=${actor.getId} contestId=$contestId results=$count " +
      s"length=${searched.length}")
    outcome
  }
}
