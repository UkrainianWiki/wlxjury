package services

import db.scalikejdbc.{AccountJoinRequestJdbc, CoiMembers, ContestJuryJdbc, User, UserContest, UserContestJdbc}
import org.intracer.wmua.ContestJury
import play.api.Logger
import scalikejdbc.{AutoSession, DB, DBSession}

import java.util.Locale
import scala.collection.mutable

/** The duplicates report (the account join plan's J-1, read-only): groups of non-deleted accounts
  * that share a normalised wiki account (as [[CoiMembers.normalize]] stores names, compared in
  * any letter case, as the column's collation does) or a verified email (one account's proven
  * address on another's Google or Wikimedia sign-in,
  * [[db.scalikejdbc.AccountJoinRequestJdbc.sharedVerifiedEmails]]; root's only, while that reading
  * of the plan waits for the maintainer: an admin's report has the wiki-name groups). Per contest for its admins
  * (the groups with an account that is a member of it or worked in it), in full for root. Nothing
  * else pairs accounts: not a name, not a shared round (one person with several juror accounts in
  * a screening round is normal), and nothing here suggests a join by itself.
  *
  * What an admin sees of an account follows the search's rules (accounts plan A1,
  * [[AccountSearch]]): its email in full only within their contests (else masked, as an address
  * typed into its name or wiki account), its contests only among those they administer, root
  * accounts never.
  *
  * "Keep separate" ([[keepSeparate]]) records pairs kept apart (V106's `kept_apart`), which hides
  * them for good: a pair kept apart never links a group again. It is a performer's decision, as a
  * join is: root, or an admin of every contest each account of the group is in or worked in
  * ([[controllers.UserController.canEditAccount]]'s set). Nothing is mailed, ever.
  */
object DuplicateAccounts {

  private val accountLogger = Logger("security.account")

  sealed abstract class Criterion(val key: String)
  /** The same wiki account, normalised, in any letter case. */
  case object SameWikiAccount extends Criterion("wiki")
  /** One account's verified address on another's sign-in. */
  case object SameEmail extends Criterion("email")

  /** A group as found: its criterion, the shared name or address (the lowest id's), its accounts'
    * ids (ascending, at least two) and its pairs kept apart that the group's other pairs still
    * link (both accounts in it).
    */
  final case class Found(criterion: Criterion, shared: String, ids: Seq[Long], keptApart: Set[(Long, Long)])

  /** An account of a group as the actor may see it ([[AccountSearch.Found]]'s rules): its name and
    * wiki account (an address typed into them masked unless its email is shown in full), its email
    * (None: it has none), whether that is the full address and is verified, its memberships in
    * the contests the actor administers (root: all), and whether this page links its edit page.
    */
  final case class Shown(
      account: User,
      name: String,
      wikiAccount: Option[String],
      email: Option[String],
      emailFull: Boolean,
      contests: Seq[(Long, Option[ContestJury], Set[String])],
      editable: Boolean)

  /** A group of the report: as [[Found]], the shared name or address as shown, its accounts, and
    * whether the actor may keep its accounts apart.
    */
  final case class Group(found: Found, shared: String, accounts: Seq[Shown], mayKeepSeparate: Boolean) {
    def id: String = s"${found.criterion.key}-${found.ids.mkString("-")}"
    def nameOf(accountId: Long): String = accounts.find(_.account.id.contains(accountId)).fold(s"#$accountId")(_.name)
  }

  final case class Report(groups: Seq[Group]) {
    def accounts: Int = groups.flatMap(_.found.ids).distinct.size
  }

  /** A wiki account's key: normalised as stored names are, compared in any letter case. */
  def wikiKey(name: String): String = CoiMembers.normalize(name).toLowerCase(Locale.ROOT)

  /** The groups, before any actor's scope: root accounts among them only if `withRoot`. A group
    * is a set of accounts with one key; its pairs kept apart are taken out and what the others
    * still link stays a group (one account kept apart from all the others leaves it; two kept
    * apart from each other stay in it while a third links them).
    */
  def find(withRoot: Boolean, withEmail: Boolean)(implicit session: DBSession = AutoSession): Seq[Found] = {
    val byWiki = AccountJoinRequestJdbc.wikiAccounts(withRoot)
      .map { case (id, name) => (wikiKey(name), id, name) }
      .filter(_._1.nonEmpty)
      .groupBy(_._1).values.toSeq
      .collect { case rows if rows.map(_._2).distinct.size > 1 =>
        val sorted = rows.sortBy(_._2)
        (SameWikiAccount: Criterion, CoiMembers.normalize(sorted.head._3), sorted.map(_._2).distinct)
      }
    val byEmail = (if (withEmail) AccountJoinRequestJdbc.sharedVerifiedEmails(withRoot) else Nil)
      .groupBy(_._1.toLowerCase(Locale.ROOT)).values.toSeq
      .map(rows => (SameEmail: Criterion, rows.head._1, (rows.map(_._2) ++ rows.map(_._3)).distinct.sorted))
    val candidates = byWiki ++ byEmail
    val kept = AccountJoinRequestJdbc.keptApartAmong(candidates.flatMap(_._3).distinct)
    candidates.flatMap { case (criterion, shared, ids) =>
      linked(ids, kept).map(component => Found(criterion, shared, component,
        kept.filter { case (a, b) => component.contains(a) && component.contains(b) }))
    }.sortBy(f => (f.criterion.key == SameEmail.key, f.shared.toLowerCase(Locale.ROOT), f.ids.head))
  }

  /** The parts of `ids` (every pair linked) that stay linked without the pairs `kept` apart: each
    * of two accounts or more, ascending.
    */
  private def linked(ids: Seq[Long], kept: Set[(Long, Long)]): Seq[Seq[Long]] = {
    val left = mutable.LinkedHashSet(ids.sorted: _*)
    val parts = Seq.newBuilder[Seq[Long]]
    while (left.nonEmpty) {
      val start = left.head
      left -= start
      val part = mutable.ListBuffer(start)
      var i = 0
      while (i < part.size) {
        val a = part(i)
        val next = left.filterNot(b => kept.contains(AccountJoinRequestJdbc.pair(a, b))).toSeq
        left --= next
        part ++= next
        i += 1
      }
      if (part.size > 1) parts += part.toSeq.sorted
    }
    parts.result()
  }

  /** The contests each account is a member of or worked in ([[User.contestsWorkedInBy]]). */
  private def contestsOf(ids: Seq[Long], memberships: Map[Long, Seq[UserContest]])(implicit
      session: DBSession): Map[Long, Set[Long]] = {
    val worked = User.contestsWorkedInBy(ids)
    ids.map(id => id -> (memberships.getOrElse(id, Nil).map(_.contestId) ++ worked.getOrElse(id, Nil)).toSet).toMap
  }

  /** Whether `actor` may keep the accounts apart: root, or (none of them root) an admin of every
    * contest each of them is in or worked in.
    */
  private def mayDecide(actor: User, accounts: Seq[User], contests: Map[Long, Set[Long]]): Boolean =
    AccountSearch.administered(actor) match {
      case None => true
      case Some(adminOf) =>
        accounts.forall(a => !a.isRoot && contests.getOrElse(a.getId, Set.empty).forall(adminOf.contains))
    }

  /** The report `actor` sees: for `contestId`, the groups with an account that is a member of it
    * or worked in it (the caller checked that the actor administers it, or is root); None, every
    * group (root only: the caller checked). Logged at INFO on `security.account` by ids only
    * (`duplicates_report byAccountId= contestId= groups= accounts=`).
    */
  def report(actor: User, contestId: Option[Long])(implicit session: DBSession = AutoSession): Report = {
    val report = build(actor, contestId)
    accountLogger.info(s"duplicates_report byAccountId=${actor.getId} contestId=${contestId.getOrElse("none")} " +
      s"groups=${report.groups.size} accounts=${report.accounts}")
    report
  }

  private def build(actor: User, contestId: Option[Long])(implicit session: DBSession): Report = {
    val adminOf = AccountSearch.administered(actor)
    val root = adminOf.isEmpty
    require(root || contestId.nonEmpty, "the full report is root's")
    // the email criterion is root's only for now (an interpretation the maintainer hasn't confirmed)
    val found = find(withRoot = root, withEmail = root)
    val ids = found.flatMap(_.ids).distinct
    val memberships = UserContestJdbc.findByUsers(ids)
    val contests = contestsOf(ids, memberships)
    val inScope = contestId.fold(found)(c => found.filter(_.ids.exists(id => contests.getOrElse(id, Set.empty)(c))))
    val shownIds = inScope.flatMap(_.ids).distinct
    val users = User.findByIds(shownIds).flatMap(u => u.id.map(_ -> u)).toMap
    val within = User.withinActor(shownIds, adminOf)
    val contestIds = shownIds.flatMap(id => memberships.getOrElse(id, Nil)).map(_.contestId).distinct
      .filter(c => adminOf.forall(_.contains(c)))
    val contestById =
      if (contestIds.isEmpty) Map.empty[Long, ContestJury]
      else ContestJuryJdbc.findAllBy(scalikejdbc.sqls.in(ContestJuryJdbc.defaultAlias.id, contestIds))
        .flatMap(c => c.id.map(_ -> c)).toMap
    def shown(id: Long, user: User): Shown = {
      val full = root || within(id)
      def text(t: String) = if (full) t else AccountSearch.maskEmailsIn(t)
      val email = Option(user.emailLo).filter(_.nonEmpty)
      Shown(
        user,
        name = text(Option(user.fullname).getOrElse("")),
        wikiAccount = user.wikiAccount.filter(_.trim.nonEmpty).map(text),
        email = email.map(e => if (full) e else UserService.maskEmail(e)),
        emailFull = email.nonEmpty && full,
        contests = memberships.getOrElse(id, Nil).filter(m => adminOf.forall(_.contains(m.contestId)))
          .sortBy(-_.contestId).map(m => (m.contestId, contestById.get(m.contestId), m.roles)),
        editable = contestId.fold(root)(c => memberships.getOrElse(id, Nil).exists(_.contestId == c)))
    }
    val groups = inScope.flatMap { f =>
      val accounts = f.ids.flatMap(id => users.get(id).map(shown(id, _)))
      Option.when(accounts.size == f.ids.size) {
        val full = root || f.ids.forall(within)
        val shared = f.criterion match {
          // root's only: the address in full (also when its owner is kept apart from the others)
          case SameEmail => if (root) f.shared else UserService.maskEmail(f.shared)
          case SameWikiAccount => if (full) f.shared else AccountSearch.maskEmailsIn(f.shared)
        }
        Group(f, shared, accounts, mayDecide(actor, accounts.map(_.account), contests))
      }
    }
    Report(groups)
  }

  sealed trait Outcome
  /** Recorded: `pairs` new pairs kept apart (0: they were already). */
  final case class KeptApart(pairs: Int) extends Outcome
  /** The accounts are no longer one group of the actor's report (a pair kept apart meanwhile, an
    * account retired, a membership changed): nothing recorded, the report is shown again.
    */
  case object Changed extends Outcome
  /** The actor may not keep these accounts apart ([[mayDecide]]). */
  case object NotAllowed extends Outcome

  /** "Keep separate" on a group of `actor`'s report (for `contestId`; None: root's full report):
    * with `apartFrom`, that account kept apart from each of the others of `accounts`; without, every
    * pair of `accounts` (at least two, all in one group of the report as it is now). Under the
    * accounts' users rows (ascending id, the join plan's lock order), the accounts are checked
    * again (none deleted, still in one group as the groups are found again, the actor still a
    * performer for them) and the pairs recorded in one transaction. Logged at INFO on
    * `security.account` by ids only (`accounts_kept_apart byAccountId= contestId=
    * pairs=a:b,...`). Nothing is mailed.
    */
  def keepSeparate(actor: User, contestId: Option[Long], accounts: Seq[Long], apartFrom: Option[Long]): Outcome = {
    val posted = accounts.distinct.sorted
    if (posted.size < 2 || apartFrom.exists(a => !posted.contains(a))) Changed
    else {
      val group = build(actor, contestId)(AutoSession).groups.find(g => posted.forall(g.found.ids.contains))
      group match {
        case None                            => Changed
        case Some(g) if !g.mayKeepSeparate   => NotAllowed
        case Some(g) =>
          val pairs = (apartFrom match {
            case Some(one) => posted.filter(_ != one).map(AccountJoinRequestJdbc.pair(one, _))
            case None      => posted.combinations(2).map { case Seq(a, b) => AccountJoinRequestJdbc.pair(a, b) }.toSeq
          }).filterNot(g.found.keptApart.contains)
          val outcome: Either[Outcome, Seq[(Long, Long)]] = DB.localTx { implicit tx =>
            val locked = AccountJoinRequestJdbc.lockAccounts(posted)
            // the groups again, under the locks: the posted accounts must still be in one
            val root = AccountSearch.administered(actor).isEmpty
            if (locked.size != posted.size || locked.exists(_.deletedAt.nonEmpty) ||
                !find(withRoot = root, withEmail = root).exists(f => posted.forall(f.ids.contains))) Left(Changed)
            else if (!mayDecide(actor, locked, contestsOf(posted, UserContestJdbc.findByUsers(posted)))) Left(NotAllowed)
            else Right(pairs.filter { case (a, b) => AccountJoinRequestJdbc.keepApart(a, b, actor.getId, contestId) })
          }
          outcome.fold[Outcome](identity, { written =>
            accountLogger.info(s"accounts_kept_apart byAccountId=${actor.getId} " +
              s"contestId=${contestId.getOrElse("none")} pairs=${written.map { case (a, b) => s"$a:$b" }.mkString(",")}")
            KeptApart(written.size)
          })
      }
    }
  }
}
