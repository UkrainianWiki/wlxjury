package services

import db.scalikejdbc.User
import play.api.Logger

/** The Edit user form's panel when a save is refused because another account already has the
  * wiki account typed (`wiki.account.taken`; accounts plan A-2, section c, A1 decided by the
  * maintainer 2026-11-16). It shows the other accounts as the users page's search would
  * ([[AccountSearch.describe]]: the address in full only within the actor's contests and to root,
  * their contests the actor administers with the roles there), with what each did there (votes,
  * comments, rounds: counts within the contests the actor administers only; root: all), and
  * offers, for each, **Add that account to this contest instead** (A-1's add by id,
  * [[UserService.addAccountToContest]]), and for all of them **Keep them separate** (the form
  * again with the wiki account as it was; nothing stored). None is preselected.
  *
  * Production has legacy groups of up to 11 accounts with one wiki account (the V61 merge), so
  * there may be several; at most [[MaxShown]] are shown. A root account is never shown to an
  * admin (A-1's L1); the panel says that some aren't shown.
  *
  * Not here yet: "Retire the account being edited" (A-4, decision A3: an unticked checkbox with
  * the add) and "Join the two accounts" (J-2: a link to its preview, once built).
  */
object WikiTakenPanel {

  /** The most other accounts shown: a legacy group has up to 11 accounts, so 10 others. */
  val MaxShown = 10

  private val accountLogger = Logger("security.account")

  /** What an account did in the contests the actor administers (root: in all). */
  final case class Activity(votes: Long, comments: Long, rounds: Long) {
    def isEmpty: Boolean = votes == 0 && comments == 0 && rounds == 0
  }

  /** An account with the wiki account, as the actor sees it, with its activity. */
  final case class Other(found: AccountSearch.Found, activity: Activity)

  /** The panel: the wiki account typed, the other accounts shown, whether some are not shown, and
    * where an add goes: the contest the edit page acts in, if the actor administers it (None: no
    * add, as on root's edit of an account in no contest), the role the form had (preselected in
    * each add's select), and the edited account's wiki account as stored (Keep them separate puts
    * it back).
    */
  final case class Panel(
      wikiAccount: String,
      others: Seq[Other],
      notShown: Boolean,
      contestId: Option[Long],
      role: String,
      storedWikiAccount: Option[String],
      root: Boolean)

  /** The panel for `actor`'s refused save of `edited` (the account as the page shows it, in its
    * contest) with `wikiAccount`, which `others` (the non-deleted accounts with it,
    * [[User.otherAccountsWithWikiAccount]]) have. `role`: the form's. Logged at INFO on
    * `security.account` by ids only (`wiki_taken_panel byAccountId= accountId= contestId= shown=
    * notShown=`), as a search is.
    */
  def build(actor: User, edited: User, wikiAccount: String, others: Seq[User], role: Option[String]): Panel = {
    val adminOf = AccountSearch.administered(actor)
    // the add goes to the page's contest, if the actor administers it (checked again on the POST)
    val contestId = edited.contestId.filter(c => adminOf.forall(_.contains(c)))
    val found = AccountSearch.describe(actor, edited.contestId, others)
    val shown = found.take(MaxShown)
    val activity = User.activityIn(shown.map(_.account.getId), adminOf)
    val notShown = found.size > shown.size || others.size > found.size
    accountLogger.info(s"wiki_taken_panel byAccountId=${actor.getId} accountId=${edited.getId} " +
      s"contestId=${edited.contestId.getOrElse("none")} shown=${shown.size} notShown=$notShown")
    Panel(
      wikiAccount,
      shown.map { f =>
        val (votes, comments, rounds) = activity.getOrElse(f.account.getId, (0L, 0L, 0L))
        Other(f, Activity(votes, comments, rounds))
      },
      notShown,
      contestId,
      role.filter(User.isContestRole).getOrElse(User.JURY_ROLE),
      edited.wikiAccount.filter(_.trim.nonEmpty),
      AccountSearch.isRoot(actor)
    )
  }
}
