package db.scalikejdbc

import javax.mail.internet.InternetAddress
import org.intracer.wmua.HasId
import scalikejdbc.{DBSession, ResultName, WrappedResultSet, insert, select, sqls}

import java.time.ZonedDateTime
import play.api.data.validation.{Constraints, Invalid, Valid}
import play.api.Logger
import scalikejdbc._
import scalikejdbc.orm.CRUDMapper
import services.PasswordHasher

import scala.util.Try
import scala.util.control.NonFatal

case class User(
    fullname: String,
    email: String,
    id: Option[Long] = None,
    roles: Set[String] = Set.empty,
    password: Option[String] = None,
    contestId: Option[Long] = None,
    lang: Option[String] = None,
    createdAt: Option[ZonedDateTime] = None,
    deletedAt: Option[ZonedDateTime] = None,
    wikiAccount: Option[String] = None,
    hasWikiEmail: Boolean = false,
    accountValid: Boolean = true,
    sort: Option[Int] = None,
    active: Option[Boolean] = Some(true),
    isRoot: Boolean = false,
    userContests: Seq[UserContest] = Nil,
    /** users.theme (V71): light, dark or auto as chosen, None if never; read through
      * [[controllers.Theme.find]], which takes nothing else.
      */
    theme: Option[String] = None,
    /** users.email_verified_at (V77): when the email was proven (an accepted invitation or
      * password link, a confirmed email change); None: unverified.
      */
    emailVerifiedAt: Option[ZonedDateTime] = None,
    /** users.wiki_account_verified_at (V77): None, unverified, for every account until the
      * Commons OAuth login verifies one.
      */
    wikiAccountVerifiedAt: Option[ZonedDateTime] = None,
    /** users.auth_version (V77): a session signed in with another version is refused. */
    authVersion: Int = 0
) extends HasId
    with Ordered[User] {

  /** Without the password hash, should a user ever be logged or shown in a test failure. */
  override def toString: String =
    scala.runtime.ScalaRunTime._toString(copy(password = password.map(_ => "***")))

  def emailLo: String = email.trim.toLowerCase

  def emailVerified: Boolean = emailVerifiedAt.isDefined

  def wikiAccountVerified: Boolean = wikiAccountVerifiedAt.isDefined

  /** Whether an access role ([[User.ACCESS_ROLES]]) may be given to this account directly:
    * only if nobody but its email's owner can sign in to it. Root; a proven email; or no
    * password at all (an invitee who hasn't accepted yet, a wiki-only placeholder), which only
    * an accepted link sets. Otherwise whoever registered the email, or set the wiki account,
    * without owning it would get the role: the role goes through an invitation instead
    * ([[services.UserService.addToContest]]).
    */
  def mayTakeAccessRole: Boolean = isRoot || hasRole(User.ROOT_ROLE) || emailVerified || password.isEmpty

  /** Whether the account has a password its owner can sign in with: not none, and not a legacy
    * hash retired after its grace period ([[PasswordHasher.RetiredLegacy]], P8), which only a
    * link (an invitation, root's password link) replaces.
    */
  def hasUsablePassword: Boolean = password.exists(_ != PasswordHasher.RetiredLegacy)

  /** Whether the account's legacy hash was retired after its grace period (P8). */
  def passwordRetired: Boolean = password.contains(PasswordHasher.RetiredLegacy)

  def currentContest: Option[Long] = contestId

  /** The user's roles in [[contestId]]: [[roles]] without root and the USER_ID_ marker. */
  def contestRoles: Set[String] =
    roles.filterNot(r => r == User.ROOT_ROLE || r.startsWith(User.USER_ID_PREFIX))

  /** The role for a round_user row or the edit form; contestRoles normally has one. */
  def contestRole: Option[String] = contestRoles.toSeq.sorted.headOption

  /** This user as a member of the membership's contest, with their role there: only an
    * allowlisted one ([[User.ContestRoles]]), never root or a USER_ID_ marker, which come
    * only from the users row ([[User.extract]]).
    */
  def inContest(membership: UserContest): User =
    copy(
      contestId = Some(membership.contestId),
      roles = roles -- contestRoles ++ (membership.roles & User.ContestRoles)
    )

  def hasRole(role: String): Boolean = roles.contains(role)

  def hasAnyRole(otherRoles: Set[String]): Boolean = roles.intersect(otherRoles).nonEmpty

  def sameContest(other: User): Boolean = isInContest(other.contestId)

  def isInContest(refContestId: Option[Long]): Boolean =
    (for (c <- contestId; oc <- refContestId) yield c == oc)
      .getOrElse(false)

  /** Root, or one of `otherRoles` in the contest ([[controllers.Secured.contestPermission]]). */
  def hasContestRole(otherRoles: Set[String], refContestId: Option[Long]): Boolean =
    hasRole(User.ROOT_ROLE) || hasAnyRole(otherRoles) && isInContest(refContestId)

  def isAdmin(refContestId: Option[Long]): Boolean =
    hasRole(User.ADMIN_ROLE) && isInContest(refContestId) ||
      hasRole(User.ROOT_ROLE)

  /** Whether the user may browse the contest's files, those deleted on Commons too
    * ([[controllers.GalleryController.contestFiles]]): its admins and organizers.
    */
  def canViewContestFiles(refContestId: Long): Boolean =
    hasContestRole(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(refContestId))

  /** Whether the user may open a round's conflicts of interest page
    * ([[controllers.RoundConflictsController.page]]): the contest's admins and organizers (only
    * its admins disqualify or requalify). The round's statistics page links it for them.
    */
  def canViewRoundConflicts(refContestId: Long): Boolean =
    hasContestRole(User.RoundConflictsRoles, Some(refContestId))

  /** Whether the user may import the contest's files and cache them locally
    * ([[controllers.ImageController.images]]): its admins.
    */
  def canManageContestFiles(refContestId: Long): Boolean =
    hasContestRole(User.ADMIN_ROLES, Some(refContestId))

  /** Whether the user may see the round's organizer views (its statistics, the jurors'
    * votes): root, or in the round's contest an admin or organizer, or a juror when the
    * round lets its jurors see them ([[Round.juryOrgView]]).
    */
  def canViewOrgInfo(round: Round): Boolean =
    hasRole(User.ROOT_ROLE) ||
      contestId.contains(round.contestId) &&
      (hasAnyRole(User.ADMIN_ROLES ++ User.ORG_COM_ROLES) || hasRole(User.JURY_ROLE) && round.juryOrgView)

  /** Whether the user may vote in the round ([[controllers.Secured.VotePermission]]): their
    * role in the round's contest is jury. Only their own vote rows, a round juror's, change.
    */
  def mayVoteIn(round: Round): Boolean =
    hasAnyRole(User.VoterRoles) && contestId.contains(round.contestId)

  def description: String =
    Seq(fullname, wikiAccount.fold("")(u => "User:" + u), email).mkString(" / ")

  def sortOrBiasedId: Long = {
    sort
      .map(_.toLong)
      .orElse(id.map(_ + Int.MaxValue))
      .getOrElse(0L)
  }

  override def compare(that: User): Int = sortOrBiasedId.compareTo(that.sortOrBiasedId)
}

object User extends CRUDMapper[User] {
  val JURY_ROLE = "jury"
  val JURY_ROLES = Set(JURY_ROLE)
  /** Who may vote in a round of their contest ([[User.mayVoteIn]]): its jurors only. */
  val VoterRoles: Set[String] = JURY_ROLES
  val ORG_COM_ROLES = Set("organizer")
  val ADMIN_ROLE = "admin"
  val ROOT_ROLE = "root"
  val ADMIN_ROLES = Set(ADMIN_ROLE, ROOT_ROLE)
  /** Who may open a round's conflicts of interest page ([[User.canViewRoundConflicts]]). */
  val RoundConflictsRoles: Set[String] = ADMIN_ROLES ++ ORG_COM_ROLES
  val USER_ID_PREFIX = "USER_ID_"
  /** The edit form's "no role": a member of the contest without access to it. */
  val NO_ROLE = "no"

  /** The contest's board, audit committee and staff: roles that grant nothing in the tool
    * (in no permission set, and a membership with only one of them is not active,
    * [[UserContest.active]]), only there so that their files are conflicts of interest
    * ([[CoiMembers]]).
    */
  val BOARD_ROLE = "board"
  val AUDITOR_ROLE = "auditor"
  val STAFF_ROLE = "staff"
  val NON_ACCESS_ROLES: Set[String] = Set(BOARD_ROLE, AUDITOR_ROLE, STAFF_ROLE)

  /** Roles of a membership that is not the user's to open: none, or only roles without access. */
  val INACTIVE_ROLES: Set[String] = NON_ACCESS_ROLES + NO_ROLE

  /** The contest roles whose members' files are conflicts of interest ([[CoiMembers]]): all
    * but "no"; root users count in every contest too.
    */
  val COI_ROLES: Set[String] = Set(ADMIN_ROLE, "organizer", JURY_ROLE) ++ NON_ACCESS_ROLES

  /** The roles the users page and its edit form offer, in their order. */
  val FORM_ROLES: Seq[String] = Seq(NO_ROLE, JURY_ROLE, "organizer", ADMIN_ROLE, BOARD_ROLE, AUDITOR_ROLE, STAFF_ROLE)

  /** Every role a contest membership (user_contest.role) may hold: exactly [[FORM_ROLES]],
    * compared exactly (no trimming, no case folding, no lists). Root is not one (it is
    * users.is_root, set by root only), nor a USER_ID_ marker. The database holds the same
    * list in V76's CHECK constraint: adding a role needs a migration too.
    */
  val ContestRoles: Set[String] = FORM_ROLES.toSet

  /** Whether `role` may be stored as a contest membership's role ([[ContestRoles]]). */
  def isContestRole(role: String): Boolean = ContestRoles.contains(role)

  /** Whether `editor` may submit `roles` for a user (the edit form's, the add-user row's):
    * each one a contest role, or root from a root editor only.
    */
  def mayAssignRoles(editor: User, roles: Set[String]): Boolean =
    roles.forall(r => isContestRole(r) || r == ROOT_ROLE && editor.hasRole(ROOT_ROLE))

  /** The contest roles that let a member in: an account gets one only if
    * [[User.mayTakeAccessRole]], else through an invitation.
    */
  val ACCESS_ROLES: Set[String] = ContestRoles -- INACTIVE_ROLES

  /** The roles "Import users" adds people with: all of [[FORM_ROLES]] but "no". */
  val IMPORT_ROLES: Seq[String] = FORM_ROLES.filterNot(_ == NO_ROLE)

  /** users.email as stored: none (a user added by wiki account only) is NULL (V67), so any
    * number of such users fit the unique key.
    */
  def storedEmail(email: String): Option[String] = Option(email).map(_.trim.toLowerCase).filter(_.nonEmpty)
  val LANGS = Map("en" -> "English", "fr" -> "Français", "ru" -> "Русский", "uk" -> "Українська")

  def unapplyEdit(user: User): Option[
    (
        Long,
        String,
        Option[String],
        String,
        Option[String],
        Option[String],
        Option[Long],
        Option[String],
        Option[Int],
        Option[String]
    )
  ] = {
    Some(
      (
        user.getId,
        user.fullname,
        user.wikiAccount,
        user.email,
        None,
        if (user.hasRole(ROOT_ROLE)) Some(ROOT_ROLE) else user.contestRole,
        user.contestId,
        user.lang,
        user.sort,
        user.theme
      )
    )
  }

  def applyEdit(
      id: Long,
      fullname: String,
      wikiAccount: Option[String],
      email: String,
      password: Option[String],
      roles: Option[String],
      contest: Option[Long],
      lang: Option[String],
      sort: Option[Int],
      theme: Option[String]
  ): User = {
    new User(
      fullname,
      email.trim.toLowerCase,
      Some(id),
      roles.fold(Set.empty[String])(Set(_)),
      password,
      contest,
      lang,
      wikiAccount = wikiAccount,
      sort = sort,
      theme = theme
    )
  }

  val emailConstraint = Constraints.emailAddress

  def parseList(usersText: String): Seq[User] = {

    def fromUserName(str: String): Option[User] = {
      val withoutPrefix = str.replaceFirst("User:", "")
      Some(withoutPrefix).filter(_.trim.nonEmpty).map { _ =>
        User(
          id = None,
          contestId = None,
          fullname = "",
          email = "",
          wikiAccount = Some(withoutPrefix)
        )
      }
    }

    def fromInternetAddress(internetAddress: InternetAddress): Option[User] = {
      Constraints.emailAddress()(internetAddress.getAddress) match {
        case Valid =>
          Some(
            User(
              id = None,
              contestId = None,
              fullname = Option(internetAddress.getPersonal).getOrElse(""),
              email = internetAddress.getAddress
            )
          )
        case Invalid(_) => None
      }
    }

    usersText.split("[,|\n|]|,[ ]*\n").flatMap { str =>
      Try {
        InternetAddress.parse(str, false)
      }.toOption.flatMap(_.headOption.flatMap(fromInternetAddress)).orElse(fromUserName(str))
    }
  }

  implicit def session: DBSession = autoSession

  override val tableName = "users"

  val u = User.syntax("u")

  def isNotDeleted = sqls.isNull(u.deletedAt)

  private val passwordLogger = Logger("security.password")
  private val secureRandom = new java.security.SecureRandom()

  /** A generated password (a new user's, a reset's): letters and digits from a secure random. */
  def randomString(len: Int): String = {
    val rand = secureRandom
    val sb = new StringBuilder(len)
    val ab = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    for (i <- 0 until len) {
      sb.append(ab(rand.nextInt(ab.length)))
    }
    sb.toString()
  }

  /** The user with the login (an email, else a wiki account) and password, if they match
    * ([[PasswordHasher.check]]). A legacy SHA-1 hash, or a bcrypt hash of a lower cost, is
    * replaced by a new bcrypt hash on success, unless the password was changed meanwhile. A
    * legacy hash never matches after its grace period (P8, [[legacyGraceOver]]).
    * Throws [[PasswordHasher.Busy]] if no turn to check comes before `deadline`.
    */
  def login(username: String, password: String, deadline: Long = PasswordHasher.default.deadline()): Option[User] = {
    val user = byUserName(username, underscoresAsSpaces = true)
    val hash = user.flatMap(usableHash)
    PasswordHasher.default.check(hash, password, deadline) match {
      case PasswordHasher.Accepted(rehash) =>
        for (u <- user; stored <- hash) yield rehash.fold(u)(upgradeHash(u, stored, _)())
      case PasswordHasher.Rejected => None
    }
  }

  /** The account's stored hash to check a password against (a sign-in, the current password of
    * one's own change): none after the grace period if it is a legacy one (P8), whether or not
    * the retirement has replaced it yet.
    */
  def usableHash(user: User): Option[String] =
    user.password.filterNot(h => PasswordHasher.isLegacy(h) && legacyGraceOver())

  /** Whether legacy hashes no longer sign in ([[LegacyPasswordRetirementJdbc.over]]): read at
    * each legacy sign-in (rare: each one replaces its hash). Never without the grace period's
    * table or row (V81 undone); over if it can't be read otherwise: such a sign-in fails, as it
    * would after the grace period.
    */
  def legacyGraceOver(): Boolean =
    try LegacyPasswordRetirementJdbc.over()
    catch {
      case NonFatal(e) =>
        passwordLogger.warn(s"legacy_password_grace_unreadable error=${e.getClass.getSimpleName}")
        true
    }

  /** Stores a new password's bcrypt hash. */
  def setPassword(id: Long, password: String): Unit = {
    setPasswordHash(id, PasswordHasher.default.hash(password))
    ()
  }

  /** Replaces the stored hash `expected` (only it: not a password changed since it was read). */
  private[db] def replaceHash(id: Long, expected: String, hash: String): Boolean =
    quietly(s => sql"UPDATE users SET password = $hash WHERE id = $id AND password = $expected".update.apply()(s) == 1)

  /** The signed-in user after storing `hash` in place of `stored`; a failure to store it
    * (a lock timeout, a read-only database) doesn't fail the sign-in: the next one tries again.
    */
  private[db] def upgradeHash(user: User, stored: String, hash: String)(
      replace: (Long, String, String) => Boolean = replaceHash): User =
    try {
      if (replace(user.getId, stored, hash)) {
        passwordLogger.info(s"password hash upgraded: accountId=${user.getId}")
        user.copy(password = Some(hash))
      } else user
    } catch {
      case NonFatal(e) =>
        // the exception's text could hold the statement: only its class
        passwordLogger.warn(s"password hash not upgraded: accountId=${user.getId} error=${e.getClass.getName}")
        user
    }

  /** The account a login (a session's, or the sign-in form's) names: by email, trimmed and
    * lowercased (P18), else by wiki account, which must name exactly one account (N4: the
    * name is not unique; an ambiguous one names no one, rather than the oldest account with
    * it). Deleted accounts are never found (P14).
    */
  def byUserName(username: String, underscoresAsSpaces: Boolean = false): Option[User] = {
    val unameTrimmed = username.trim.toLowerCase
    val users = Constraints.emailAddress()(unameTrimmed) match {
      case Valid => findByEmail(unameTrimmed)
      // sign-in only: as typed, and with underscores as spaces (how names are stored since S2).
      // A session resolves the name exactly: an id-less session from before S2 must not start
      // naming another account once a near-duplicate name is renamed (S2 follow-up review)
      case Invalid(_) =>
        (if (underscoresAsSpaces) Seq(unameTrimmed, unameTrimmed.replace('_', ' ')).distinct else Seq(unameTrimmed))
          .flatMap(findByAccount)
    }
    users.filter(_.deletedAt.isEmpty).distinctBy(_.id) match {
      case Seq(one) => Some(one)
      case _        => None
    }
  }

  override lazy val defaultAlias = createAlias("u")

  /** A user row: no contest, and only the USER_ID_ marker and root in [[User.roles]]. A
    * contest and the role in it come from user_contest ([[User.inContest]]).
    */
  override def extract(rs: WrappedResultSet, c: scalikejdbc.ResultName[User]): User = {
    val id   = rs.long(c.id)
    val root = rs.boolean(c.isRoot)
    User(
      id          = Some(id),
      fullname    = rs.string(c.fullname),
      email       = rs.stringOpt(c.email).getOrElse(""),
      roles       = Set(USER_ID_PREFIX + id) ++ (if (root) Set(ROOT_ROLE) else Set.empty),
      isRoot      = root,
      password    = rs.stringOpt(c.password),
      lang        = rs.stringOpt(c.lang),
      theme       = rs.stringOpt(c.theme),
      wikiAccount = rs.stringOpt(c.wikiAccount),
      sort        = rs.intOpt(c.sort),
      createdAt   = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
      deletedAt   = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
      emailVerifiedAt       = rs.timestampOpt(c.emailVerifiedAt).map(_.toZonedDateTime),
      wikiAccountVerifiedAt = rs.timestampOpt(c.wikiAccountVerifiedAt).map(_.toZonedDateTime),
      authVersion = rs.int(c.authVersion)
    )
  }

  def apply(c: ResultName[User])(rs: WrappedResultSet): User = extract(rs, c)

  /** The contest's members, each with their role in it, ordered by id; only those with one
    * of `roles` if given, and of `ids` if given.
    */
  def contestMembers(
      contestId: Long,
      roles: Option[Seq[String]] = None,
      ids: Option[Seq[Long]] = None
  )(implicit session: DBSession = autoSession): Seq[User] =
    if (roles.exists(_.isEmpty) || ids.exists(_.isEmpty)) Nil
    else {
      val uc = UserContestJdbc.syntax("uc")
      withSQL {
        select(u.result.*, uc.result.*)
          .from(User as u)
          .join(UserContestJdbc as uc).on(u.id, uc.userId)
          .where(
            sqls.toAndConditionOpt(
              Some(sqls.eq(uc.contestId, contestId)),
              roles.map(sqls.in(uc.role, _)),
              ids.map(sqls.in(u.id, _))
            )
          )
          .orderBy(u.id)
      }.map { rs =>
        User(u.resultName)(rs).inContest(UserContestJdbc.extract(rs, uc.resultName))
      }.list()
        // the column compares case- and trailing-space-insensitively: keep exact matches only
        .filter(member => roles.forall(_.exists(member.contestRoles.contains)))
    }

  def findByContest(contestId: Long)(implicit session: DBSession = autoSession): Seq[User] =
    contestMembers(contestId)

  /** The user as a member of the contest, with their role there; None if not a member. */
  def findInContest(userId: Long, contestId: Long)(implicit session: DBSession = autoSession): Option[User] =
    contestMembers(contestId, ids = Some(Seq(userId))).headOption

  /** Users with all their memberships in [[User.userContests]]. */
  def withMemberships(users: Seq[User])(implicit session: DBSession = autoSession): Seq[User] = {
    val byUser = UserContestJdbc.findAll().groupBy(_.userId)
    users.map(user => user.copy(userContests = byUser.getOrElse(user.getId, Nil).sortBy(-_.contestId)))
  }

  /** The contests the user has a past in: a round they are or were assigned to
    * (round_user), or voted in (vote, for legacy rounds without round_user rows).
    * DISTINCT round_id over vote's (jury_id, round_id, rate, ...) index
    * (vote_jury_round_rate_mon_page_idx, or V85's vote_jury_round_ratedesc_mon_page_idx on
    * MariaDB 10.8+) is a loose index scan.
    */
  def contestsWorkedIn(userId: Long)(implicit session: DBSession = autoSession): Seq[Long] =
    sql"""SELECT r.contest_id FROM round_user ru JOIN round r ON r.id = ru.round_id
          WHERE ru.user_id = $userId
          UNION
          SELECT r.contest_id FROM (SELECT DISTINCT round_id FROM vote WHERE jury_id = $userId) s
          JOIN round r ON r.id = s.round_id""".map(_.long(1)).list()

  /** [[contestsWorkedIn]] of each of the users, in one query (the users page's batch). */
  def contestsWorkedInBy(userIds: Seq[Long])(implicit session: DBSession = autoSession): Map[Long, Seq[Long]] =
    if (userIds.isEmpty) Map.empty
    else {
      val ids = userIds.distinct
      sql"""SELECT ru.user_id, r.contest_id FROM round_user ru JOIN round r ON r.id = ru.round_id
            WHERE ru.user_id IN ($ids)
            UNION
            SELECT s.jury_id, r.contest_id FROM (SELECT DISTINCT jury_id, round_id FROM vote WHERE jury_id IN ($ids)) s
            JOIN round r ON r.id = s.round_id""".map(rs => rs.long(1) -> rs.long(2)).list()
        .groupMap(_._1)(_._2)
    }

  /** Whether the user is a juror of the round: with votes in it (vote, as
    * [[findByRoundSelection]] lists them, for legacy rounds without round_user rows) or
    * assigned to it (round_user, active or not).
    */
  def isRoundJuror(roundId: Long, userId: Long)(implicit session: DBSession = autoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM users u WHERE u.id = $userId AND (
            EXISTS(SELECT 1 FROM vote s WHERE s.round_id = $roundId AND s.jury_id = $userId) OR
            EXISTS(SELECT 1 FROM round_user ru WHERE ru.round_id = $roundId AND ru.user_id = $userId)))"""
      .map(_.boolean(1)).single().getOrElse(false)

  /** The round's jurors as [[isRoundJuror]] accepts them, ordered by id: with votes in it
    * or assigned to it (round_user, active or not). The galleries' juror menu, so that it
    * lists every juror an organiser may view the round as, also one without votes yet.
    */
  def findRoundJurorsToView(roundId: Long)(implicit session: DBSession = autoSession): Seq[User] = {
    val ids = sql"""SELECT DISTINCT jury_id FROM vote WHERE round_id = $roundId
                    UNION
                    SELECT user_id FROM round_user WHERE round_id = $roundId"""
      .map(_.long(1))
      .list()
    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
  }

  /** The users with vote rows in the round (its jurors), ordered by id.
    *
    * DISTINCT jury_id over vote_round_jury_rate_idx is a loose index scan, one
    * index dive per juror, instead of joining every vote row of the round (a few
    * hundred thousand in a large contest) to users. Not filtered by users.contest_id: a
    * user's contest can move on to a later contest. Not round_user either: for legacy
    * rounds it doesn't always match the vote rows.
    */
  def findByRoundSelection(roundId: Long): Seq[User] = {
    val ids = sql"SELECT DISTINCT jury_id FROM vote WHERE round_id = $roundId"
      .map(_.long(1))
      .list()
    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
  }

  /** The round's jurors, ordered by id, for its edit page: [[findByRoundSelection]],
    * or, for a round without vote rows (its first distribution failed and was
    * rolled back), its active jurors in round_user.
    */
  def findRoundJurors(roundId: Long): Seq[User] =
    findByRoundSelection(roundId) match {
      case Nil   => activeRoundJurors(roundId)
      case found => found
    }

  /** The jurors a distribution to the round gives images to, ordered by id: its active
    * jurors in round_user, so a juror stopped on the round gets no new images. Rounds
    * without round_user rows (created before round_user was filled) fall back to the
    * jurors with vote rows in the round.
    */
  def distributionJurors(roundId: Long): Seq[User] =
    if (RoundUser.byRoundId(roundId).isEmpty) findByRoundSelection(roundId)
    else activeRoundJurors(roundId)

  private def activeRoundJurors(roundId: Long): Seq[User] = {
    val ids = RoundUser.activeJurors(roundId).map(_.userId).distinct
    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
  }

  def countByEmail(id: Long, email: String): Long =
    countBy(
      sqls
        .eq(column.email, email)
        .and
        .ne(column.id, id)
    )

  def findByEmail(email: String): Seq[User] =
    where("email" -> email)
      .orderBy(u.id)
      .apply()

  def findByAccount(account: String): Seq[User] =
    where("wikiAccount" -> account)
      .orderBy(u.id)
      .apply()

  def create(
      fullname: String,
      email: String,
      passwordHash: String,
      roles: Set[String],
      contestId: Option[Long]
  ): User = create(fullname, email, passwordHash, roles, contestId, None, Some(ZonedDateTime.now))

  def create(
      fullname: String,
      email: String,
      passwordHash: String,
      roles: Set[String],
      contestId: Option[Long],
      lang: Option[String]
  ): User = create(fullname, email, passwordHash, roles, contestId, lang, Some(ZonedDateTime.now))

  def create(
      fullname: String,
      email: String,
      passwordHash: String,
      roles: Set[String],
      contestId: Option[Long],
      lang: Option[String],
      createdAt: Option[ZonedDateTime]
  ): User =
    create(
      User(
        fullname = fullname,
        email = email,
        password = Some(passwordHash),
        roles = roles,
        contestId = contestId,
        lang = lang,
        createdAt = createdAt
      )
    )

  /** Inserts the user and, when [[User.contestId]] and a contest role are set, their
    * membership in that contest. A root role sets users.is_root.
    */
  def create(user: User)(implicit session: DBSession = AutoSession): User = {
    // before anything is written: a contest role is an allowlisted one, nothing else
    require(user.contestRoles.forall(isContestRole), "not a contest role")
    val root = user.isRoot || user.hasRole(ROOT_ROLE)
    val insertUser = withSQL {
      insert
        .into(User)
        .namedValues(
          column.fullname    -> user.fullname,
          column.email       -> storedEmail(user.email),
          column.wikiAccount -> user.wikiAccount,
          column.password    -> user.password,
          column.isRoot      -> root,
          column.lang        -> user.lang,
          column.theme       -> user.theme.flatMap(controllers.Theme.find).map(_.value),
          column.sort        -> user.sort,
          column.createdAt   -> user.createdAt,
          column.emailVerifiedAt -> user.emailVerifiedAt,
          column.authVersion -> user.authVersion
        )
    }.updateAndReturnGeneratedKey
    // it binds the hash: never logged, unless a caller's own session (a test's) runs it
    val id = session match {
      case AutoSession => quietly(insertUser.apply()(_))
      case own         => insertUser.apply()(own)
    }

    for (contestId <- user.contestId; role <- user.contestRole)
      UserContestJdbc.createMembership(id, contestId, role)

    user.copy(
      id = Some(id),
      isRoot = root,
      roles = user.roles ++ Set(USER_ID_PREFIX + id) ++ (if (root) Set(ROOT_ROLE) else Set.empty)
    )
  }

  /** Updates the account's name, language and order; roles are per contest, set with
    * [[UserContestJdbc.updateRole]], the email with [[setEmail]], the wiki account with
    * [[setWikiAccount]].
    */
  def updateUser(
      id: Long,
      fullname: String,
      lang: Option[String],
      sort: Option[Int]
  ): Unit =
    updateById(id)
      .withAttributes(
        "fullname" -> fullname,
        "lang"     -> lang,
        "sort"     -> sort
      )

  /** A new account's auth version: random and non-zero, so that no session from before S2
    * (version 0) resolves to it, nor, most likely, any other stale one (H2).
    */
  def newAuthVersion(): Int = 1 + secureRandom.nextInt(1 << 30)

  /** The SQL that adds one to users.auth_version, back to 1 at Int's maximum (read as an Int;
    * never reached in practice, a bump a sign-in at most).
    */
  val BumpAuthVersion: SQLSyntax = sqls"IF(auth_version >= 2147483646, 1, auth_version + 1)"

  /** Sets the wiki account (None: none); a changed one is unverified, and the account's
    * sessions end (auth_version + 1): one signed in by the old name, or one from before S2 that
    * named the new one, must not act as this account (H2).
    */
  def setWikiAccount(id: Long, wikiAccount: Option[String])(implicit session: DBSession = autoSession): Unit =
    sql"""UPDATE users SET
            wiki_account_verified_at = IF(wiki_account <=> $wikiAccount, wiki_account_verified_at, NULL),
            auth_version = IF(wiki_account <=> $wikiAccount, auth_version, $BumpAuthVersion),
            wiki_account = $wikiAccount
          WHERE id = $id""".update.apply()

  /** Sets the email (blank: none), verified now if `verified`, else unverified. Only an
    * accepted link proves an address ([[services.AccountTokenService]]); an admin's fix of an
    * invitee's address leaves it unverified.
    */
  def setEmail(id: Long, email: String, verified: Boolean)(implicit session: DBSession = autoSession): Unit = {
    val stored = storedEmail(email)
    // a changed address ends the account's sessions (auth_version + 1): see setWikiAccount
    sql"""UPDATE users SET
            auth_version = IF(email <=> $stored, auth_version, $BumpAuthVersion),
            email = $stored, email_verified_at = IF($verified, NOW(3), NULL)
          WHERE id = $id""".update.apply()
  }

  /** The other non-deleted accounts with the wiki account (compared as the column's
    * collation does: case-insensitively).
    */
  def otherAccountsWithWikiAccount(id: Long, wikiAccount: String): Seq[User] =
    findByAccount(wikiAccount).filter(u => u.deletedAt.isEmpty && !u.id.contains(id))

  /** The longest user name MediaWiki takes. */
  val WikiAccountMaxLength = 85

  /** Characters MediaWiki refuses in a user name: those no title may hold, its
    * `$wgInvalidUsernameCharacters` (`@`, `:`, `=`), and `/` (a subpage).
    */
  private val InvalidWikiAccountChars: Set[Char] = "#<>[]|{}/@:=".toSet

  /** A wiki account name as MediaWiki stores it (N4): without a `User:` prefix, underscores as
    * spaces, runs of spaces as one, trimmed, the first letter upper case. Left: a message key
    * (an invalid character, a control character, too long). Right(None): blank.
    */
  def normalizeWikiAccount(input: String): Either[String, Option[String]] = {
    // one normalisation for names stored here and the CoI filter's keys (L5): CoiMembers.normalize
    // (title case of the first letter, as MediaWiki: Georgian Mkhedruli stays, `ǆ` becomes `ǅ`)
    val normalized = CoiMembers.normalize(input)
    if (normalized.isEmpty) Right(None)
    else if (normalized.exists(c => InvalidWikiAccountChars.contains(c) || Character.isISOControl(c)))
      Left("wiki.account.invalid")
    else if (normalized.codePointCount(0, normalized.length) > WikiAccountMaxLength) Left("wiki.account.invalid")
    else Right(Some(normalized))
  }


  /** The user's colour mode ([[controllers.Theme]]): one of its three values only. */
  def updateTheme(id: Long, theme: controllers.Theme)(implicit session: DBSession = autoSession): Unit =
    updateById(id)
      .withAttributes("theme" -> theme.value)

  def setRoot(id: Long, root: Boolean): Unit =
    updateById(id)
      .withAttributes("isRoot" -> root)

  /** Stores a hash made by [[PasswordHasher.hash]] (to hash before writing anything else);
    * [[setPassword]] hashes and stores. A new password ends every session of the account
    * (auth_version + 1, S3); the new version (None: no such account), for the session that
    * changed it to stay signed in with. API tokens stay (the user's decision: like MediaWiki's
    * bot passwords). A hash upgrade of the same password ([[replaceHash]]) ends none.
    */
  def setPasswordHash(id: Long, hash: String): Option[Int] =
    quietTx { s =>
      // named as the object's implicit autoSession, which it shadows here
      implicit val session: DBSession = s
      sql"UPDATE users SET password = $hash, auth_version = $BumpAuthVersion WHERE id = $id".update.apply()
      authVersionOf(id)
    }

  /** Ends every session of the account (auth_version + 1): "sign out everywhere", root's "end
    * sessions" (S3). The new version; None if there is no such account.
    */
  def endSessions(id: Long): Option[Int] =
    DB.localTx { s =>
      implicit val session: DBSession = s
      sql"UPDATE users SET auth_version = $BumpAuthVersion WHERE id = $id".update.apply()
      authVersionOf(id)
    }

  /** Marks the account's wiki account verified, if it is `wikiAccount` (as the column compares):
    * a Wikimedia sign-in by that very name proved it (services.oauth.IdentityLinker). The name
    * stays, so no session ends.
    */
  def verifyWikiAccount(id: Long, wikiAccount: String)(implicit session: DBSession = autoSession): Unit =
    sql"""UPDATE users SET wiki_account_verified_at = NOW(3)
          WHERE id = $id AND wiki_account = $wikiAccount AND wiki_account_verified_at IS NULL""".update.apply()

  /** Clears the wiki account's verification: the Wikimedia account that proved it was renamed
    * (M1), so the name may now be someone else's.
    */
  def unverifyWikiAccount(id: Long)(implicit session: DBSession = autoSession): Unit =
    sql"UPDATE users SET wiki_account_verified_at = NULL WHERE id = $id".update.apply()

  /** Why [[setVerifiedWikiAccount]] set nothing. */
  sealed abstract class WikiAccountRefusal(val messageKey: String)
  case object WikiAccountTaken extends WikiAccountRefusal("oauth.linked.wiki.taken")
  case object WikiAccountOther extends WikiAccountRefusal("oauth.wiki.ask.organizer")

  /** Sets the account's wiki account to `wikiAccount` (normalised, [[normalizeWikiAccount]]),
    * verified now: its holder linked that Wikimedia account from their own profile and confirmed
    * (B4). In one transaction, the account's row locked: only if its wiki account is empty or
    * already this name, and no other account has the name. A changed name ends the account's
    * sessions, as [[setWikiAccount]]; the new version, for the session that changed it to stay
    * signed in with.
    */
  def setVerifiedWikiAccount(id: Long, wikiAccount: String): Either[WikiAccountRefusal, Int] =
    DB.localTx { s =>
      implicit val session: DBSession = s
      val current = sql"SELECT wiki_account FROM users WHERE id = $id FOR UPDATE".map(_.stringOpt(1)).single.apply().flatten
        .map(_.trim).filter(_.nonEmpty)
      if (current.exists(c => normalizeWikiAccount(c).toOption.flatten.forall(_ != wikiAccount))) Left(WikiAccountOther)
      else if (sql"""SELECT EXISTS(SELECT 1 FROM users WHERE wiki_account = $wikiAccount AND id <> $id
                     AND deleted_at IS NULL)""".map(_.boolean(1)).single.apply().contains(true)) Left(WikiAccountTaken)
      else {
        // MariaDB assigns left to right: the version reads the old name, as in setWikiAccount
        sql"""UPDATE users SET
                wiki_account_verified_at = NOW(3),
                auth_version = IF(wiki_account <=> $wikiAccount, auth_version, $BumpAuthVersion),
                wiki_account = $wikiAccount
              WHERE id = $id""".update.apply()
        authVersionOf(id).toRight(WikiAccountOther)
      }
    }

  /** The account's row, locked until the transaction ends. */
  def lockRow(id: Long)(implicit session: DBSession): Option[User] =
    sql"SELECT ${u.result.*} FROM ${User as u} WHERE ${u.id} = $id FOR UPDATE".map(User(u.resultName)).single.apply()

  private def authVersionOf(id: Long)(implicit session: DBSession): Option[Int] =
    sql"SELECT auth_version FROM users WHERE id = $id".map(_.int(1)).single.apply()

  /** ScalikeJDBC's settings for a statement that binds a password hash: it logs neither the
    * statement (`loggingSQLAndTime`, including the slow-statement warning) nor its failure
    * (`loggingSQLErrors`), both of which print the bound values.
    */
  private val quietSettings: SettingsProvider = SettingsProvider.default.copy(
    loggingSQLAndTime = _.copy(enabled = false, warningEnabled = false),
    loggingSQLErrors = _ => false
  )

  /** The first account of an empty installation (P20, [[controllers.LoginController.setup]]): root,
    * its email taken as verified (the operator's). None, creating nothing, if any account exists.
    * Under a named lock, in auto-commit (the insert is committed before the lock is released):
    * of two concurrent setups, the second sees the first's account.
    */
  def createFirstRoot(email: String, passwordHash: String): Option[User] =
    quietly { s =>
      // named as the object's implicit autoSession, which it shadows here
      implicit val session: DBSession = s
      val locked = sql"SELECT GET_LOCK('wlxjury.setup', 10)".map(_.intOpt(1)).single.apply().flatten.contains(1)
      try {
        val empty = locked && sql"SELECT COUNT(*) FROM users".map(_.long(1)).single.apply().contains(0L)
        if (!empty) None
        else
          Some(create(User(fullname = "", email = email, password = Some(passwordHash), isRoot = true,
            createdAt = Some(ZonedDateTime.now), emailVerifiedAt = Some(ZonedDateTime.now),
            authVersion = newAuthVersion()))(s))
      } finally if (locked) sql"SELECT RELEASE_LOCK('wlxjury.setup')".map(_.intOpt(1)).single.apply()
    }

  /** `f` in a transaction with [[quietSettings]]: for statements that bind a password hash. */
  def quietTx[A](f: DBSession => A): A = {
    implicit val settings: SettingsProvider = quietSettings
    DB.localTx(f)
  }

  /** `f` in its own auto-commit session with [[quietSettings]]. */
  private[db] def quietly[A](f: DBSession => A): A =
    DB.autoCommit(f)(NoConnectionPoolContext, quietSettings)

  def loadJurors(contestId: Long)(implicit session: DBSession = autoSession): Seq[User] =
    contestMembers(contestId, roles = Some(Seq(JURY_ROLE)))

  def loadJurors(contestId: Long, jurorIds: Seq[Long]): Seq[User] =
    contestMembers(contestId, roles = Some(Seq(JURY_ROLE)), ids = Some(jurorIds))

  def findByContestAndRoles(contestId: Long, roles: Seq[String])
                           (implicit session: DBSession = autoSession): Seq[User] =
    contestMembers(contestId, roles = Some(roles))

}
