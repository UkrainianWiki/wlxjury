package db.scalikejdbc

import javax.mail.internet.InternetAddress
import org.intracer.wmua.HasId
import scalikejdbc.{DBSession, ResultName, WrappedResultSet, insert, select, sqls}

import java.time.ZonedDateTime
import play.api.data.validation.{Constraints, Invalid, Valid}
import play.api.libs.Codecs
import scalikejdbc._
import scalikejdbc.orm.CRUDMapper

import scala.util.Try

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
    userContests: Seq[UserContest] = Nil
) extends HasId
    with Ordered[User] {

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

  def canViewOrgInfo(round: Round): Boolean =
    hasRole("root") ||
      (contestId.contains(round.contestId) &&
        hasAnyRole(Set("organizer", "admin", "root")) ||
        (roles.contains("jury") && round.juryOrgView))

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
        Option[Int]
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
        user.sort
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
      sort: Option[Int]
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
      sort = sort
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

  def randomString(len: Int): String = {
    val rand = new scala.util.Random(System.nanoTime)
    val sb = new StringBuilder(len)
    val ab = "0123456789ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz"
    for (i <- 0 until len) {
      sb.append(ab(rand.nextInt(ab.length)))
    }
    sb.toString()
  }

  def login(username: String, password: String): Option[User] = {
    byUserName(username).filter(user => {
      val passwordTrimmed = password.trim
      val inputHash = hash(user, passwordTrimmed)
      val dbHash = user.password.get
      inputHash == dbHash
    })
  }

  def hash(user: User, password: String): String =
    sha1(password)

  def sha1(input: String): String =
    Codecs.sha1(input.getBytes)

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
      email       = rs.string(c.email),
      roles       = Set(USER_ID_PREFIX + id) ++ (if (root) Set(ROOT_ROLE) else Set.empty),
      isRoot      = root,
      password    = rs.stringOpt(c.password),
      lang        = rs.stringOpt(c.lang),
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
      password: String,
      roles: Set[String],
      contestId: Option[Long]
  ): User = create(fullname, email, password, roles, contestId, None, Some(ZonedDateTime.now))

  def create(
      fullname: String,
      email: String,
      password: String,
      roles: Set[String],
      contestId: Option[Long],
      lang: Option[String]
  ): User = create(fullname, email, password, roles, contestId, lang, Some(ZonedDateTime.now))

  def create(
      fullname: String,
      email: String,
      password: String,
      roles: Set[String],
      contestId: Option[Long],
      lang: Option[String],
      createdAt: Option[ZonedDateTime]
  ): User =
    create(
      User(
        fullname = fullname,
        email = email,
        password = Some(password),
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
    val id = withSQL {
      insert
        .into(User)
        .namedValues(
          column.fullname    -> user.fullname,
          column.email       -> user.email.trim.toLowerCase,
          column.wikiAccount -> user.wikiAccount,
          column.password    -> user.password,
          column.isRoot      -> root,
          column.lang        -> user.lang,
          column.sort        -> user.sort,
          column.createdAt   -> user.createdAt
        )
    }.updateAndReturnGeneratedKey()

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
        "email"       -> email,
        "wikiAccount" -> wikiAccount,
        "lang"        -> lang,
        "sort"        -> sort
      )

  def setRoot(id: Long, root: Boolean): Unit =
    updateById(id)
      .withAttributes("isRoot" -> root)

  def updateHash(id: Long, hash: String): Unit =
    updateById(id)
      .withAttributes("password" -> hash)

  def loadJurors(contestId: Long)(implicit session: DBSession = autoSession): Seq[User] =
    contestMembers(contestId, roles = Some(Seq(JURY_ROLE)))

  def loadJurors(contestId: Long, jurorIds: Seq[Long]): Seq[User] =
    contestMembers(contestId, roles = Some(Seq(JURY_ROLE)), ids = Some(jurorIds))

  def findByContestAndRoles(contestId: Long, roles: Seq[String])
                           (implicit session: DBSession = autoSession): Seq[User] =
    contestMembers(contestId, roles = Some(roles))

}
