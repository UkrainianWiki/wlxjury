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
    theme: Option[String] = None
) extends HasId
    with Ordered[User] {

  /** Without the password hash, should a user ever be logged or shown in a test failure. */
  override def toString: String =
    scala.runtime.ScalaRunTime._toString(copy(password = password.map(_ => "***")))

  def emailLo: String = email.trim.toLowerCase

  def currentContest: Option[Long] = contestId

  /** The user's roles in [[contestId]]: [[roles]] without root and the USER_ID_ marker. */
  def contestRoles: Set[String] =
    roles.filterNot(r => r == User.ROOT_ROLE || r.startsWith(User.USER_ID_PREFIX))

  /** The role for a round_user row or the edit form; contestRoles normally has one. */
  def contestRole: Option[String] = contestRoles.toSeq.sorted.headOption

  /** This user as a member of the membership's contest, with their role there. */
  def inContest(membership: UserContest): User =
    copy(
      contestId = Some(membership.contestId),
      roles = roles -- contestRoles ++ membership.roles
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
  val ORG_COM_ROLES = Set("organizer")
  val ADMIN_ROLE = "admin"
  val ROOT_ROLE = "root"
  val ADMIN_ROLES = Set(ADMIN_ROLE, ROOT_ROLE)
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
    * but `prejury` and "no"; root users count in every contest too.
    */
  val COI_ROLES: Set[String] = Set(ADMIN_ROLE, "organizer", JURY_ROLE) ++ NON_ACCESS_ROLES

  /** The roles the users page and its edit form offer, in their order. */
  val FORM_ROLES: Seq[String] = Seq(NO_ROLE, JURY_ROLE, "organizer", ADMIN_ROLE, BOARD_ROLE, AUDITOR_ROLE, STAFF_ROLE)

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
    * replaced by a new bcrypt hash on success, unless the password was changed meanwhile.
    * Throws [[PasswordHasher.Busy]] if no turn to check comes before `deadline`.
    */
  def login(username: String, password: String, deadline: Long = PasswordHasher.default.deadline()): Option[User] = {
    val user = byUserName(username)
    PasswordHasher.default.check(user.flatMap(_.password), password, deadline) match {
      case PasswordHasher.Accepted(rehash) =>
        for (u <- user; stored <- u.password) yield rehash.fold(u)(upgradeHash(u, stored, _)())
      case PasswordHasher.Rejected => None
    }
  }

  /** Stores a new password's bcrypt hash. */
  def setPassword(id: Long, password: String): Unit =
    setPasswordHash(id, PasswordHasher.default.hash(password))

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

  def byUserName(username: String): Option[User] = {
    val unameTrimmed = username.trim.toLowerCase
    val users = Constraints.emailAddress()(unameTrimmed) match {
      case Valid           => findByEmail(username)
      case Invalid(errors) => findByAccount(unameTrimmed)
    }
    users.headOption
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
      deletedAt   = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
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
    * (round_user), or voted in (selection, for legacy rounds without round_user rows).
    * DISTINCT round_id over idx_selection_jury_round_rate_mon_page is a loose index scan.
    */
  def contestsWorkedIn(userId: Long)(implicit session: DBSession = autoSession): Seq[Long] =
    sql"""SELECT r.contest_id FROM round_user ru JOIN rounds r ON r.id = ru.round_id
          WHERE ru.user_id = $userId
          UNION
          SELECT r.contest_id FROM (SELECT DISTINCT round_id FROM selection WHERE jury_id = $userId) s
          JOIN rounds r ON r.id = s.round_id""".map(_.long(1)).list()

  /** Whether the user is a juror of the round: with votes in it (selection, as
    * [[findByRoundSelection]] lists them, for legacy rounds without round_user rows) or
    * assigned to it (round_user, active or not).
    */
  def isRoundJuror(roundId: Long, userId: Long)(implicit session: DBSession = autoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM users u WHERE u.id = $userId AND (
            EXISTS(SELECT 1 FROM selection s WHERE s.round_id = $roundId AND s.jury_id = $userId) OR
            EXISTS(SELECT 1 FROM round_user ru WHERE ru.round_id = $roundId AND ru.user_id = $userId)))"""
      .map(_.boolean(1)).single().getOrElse(false)

  /** The round's jurors as [[isRoundJuror]] accepts them, ordered by id: with votes in it
    * or assigned to it (round_user, active or not). The galleries' juror menu, so that it
    * lists every juror an organiser may view the round as, also one without votes yet.
    */
  def findRoundJurorsToView(roundId: Long)(implicit session: DBSession = autoSession): Seq[User] = {
    val ids = sql"""SELECT DISTINCT jury_id FROM selection WHERE round_id = $roundId
                    UNION
                    SELECT user_id FROM round_user WHERE round_id = $roundId"""
      .map(_.long(1))
      .list()
    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
  }

  /** The users with selection rows in the round (its jurors), ordered by id.
    *
    * DISTINCT jury_id over idx_selection_round_jury_rate is a loose index scan, one
    * index dive per juror, instead of joining every selection row of the round (a few
    * hundred thousand in a large contest) to users. Not filtered by users.contest_id: a
    * user's contest can move on to a later contest. Not round_user either: for legacy
    * rounds it doesn't always match the selection rows.
    */
  def findByRoundSelection(roundId: Long): Seq[User] = {
    val ids = sql"SELECT DISTINCT jury_id FROM selection WHERE round_id = $roundId"
      .map(_.long(1))
      .list()
    if (ids.isEmpty) Nil else findAllBy(sqls.in(u.id, ids)).sortBy(_.id)
  }

  /** The round's jurors, ordered by id, for its edit page: [[findByRoundSelection]],
    * or, for a round without selection rows (its first distribution failed and was
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
    * jurors with selection rows in the round.
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
          column.createdAt   -> user.createdAt
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

  /** Updates the account; roles are per contest, set with [[UserContestJdbc.updateRole]]. */
  def updateUser(
      id: Long,
      fullname: String,
      wikiAccount: Option[String],
      email: String,
      lang: Option[String],
      sort: Option[Int]
  ): Unit =
    updateById(id)
      .withAttributes(
        "fullname"    -> fullname,
        "email"       -> storedEmail(email),
        "wikiAccount" -> wikiAccount,
        "lang"        -> lang,
        "sort"        -> sort
      )

  /** The user's colour mode ([[controllers.Theme]]): one of its three values only. */
  def updateTheme(id: Long, theme: controllers.Theme)(implicit session: DBSession = autoSession): Unit =
    updateById(id)
      .withAttributes("theme" -> theme.value)

  def setRoot(id: Long, root: Boolean): Unit =
    updateById(id)
      .withAttributes("isRoot" -> root)

  /** Stores a hash made by [[PasswordHasher.hash]] (to hash before writing anything else);
    * [[setPassword]] hashes and stores.
    */
  def setPasswordHash(id: Long, hash: String): Unit =
    quietly(s => sql"UPDATE users SET password = $hash WHERE id = $id".update.apply()(s))

  /** ScalikeJDBC's settings for a statement that binds a password hash: it logs neither the
    * statement (`loggingSQLAndTime`, including the slow-statement warning) nor its failure
    * (`loggingSQLErrors`), both of which print the bound values.
    */
  private val quietSettings: SettingsProvider = SettingsProvider.default.copy(
    loggingSQLAndTime = _.copy(enabled = false, warningEnabled = false),
    loggingSQLErrors = _ => false
  )

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
