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
    active: Option[Boolean] = Some(true)
) extends HasId
    with Ordered[User] {

  def emailLo: String = email.trim.toLowerCase

  def currentContest: Option[Long] = contestId

  def hasRole(role: String): Boolean = roles.contains(role)

  def hasAnyRole(otherRoles: Set[String]): Boolean = roles.intersect(otherRoles).nonEmpty

  def sameContest(other: User): Boolean = isInContest(other.contestId)

  def isInContest(refContestId: Option[Long]): Boolean =
    (for (c <- contestId; oc <- refContestId) yield c == oc)
      .getOrElse(false)

  def isAdmin(refContestId: Option[Long]): Boolean =
    hasRole(User.ADMIN_ROLE) && isInContest(refContestId) ||
      hasRole(User.ROOT_ROLE)

  def canEdit(otherUser: User): Boolean =
    isAdmin(otherUser.contestId) ||
      id == otherUser.id

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
        Some(user.roles.toSeq.head),
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

  override def extract(rs: WrappedResultSet, c: scalikejdbc.ResultName[User]): User = User(
    id = Some(rs.int(c.id)),
    fullname = rs.string(c.fullname),
    email = rs.string(c.email),
    roles = rs.string(c.roles).split(",").map(_.trim).toSet ++ Set("USER_ID_" + rs.int(c.id)),
    contestId = rs.longOpt(c.contestId),
    password = Some(rs.string(c.password)),
    lang = rs.stringOpt(c.lang),
    wikiAccount = rs.stringOpt(c.wikiAccount),
    sort = rs.intOpt(c.sort),
    createdAt = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
  )

  def apply(c: ResultName[User])(rs: WrappedResultSet): User = new User(
    id = Some(rs.int(c.id)),
    fullname = rs.string(c.fullname),
    email = rs.string(c.email),
    roles = rs.string(c.roles).split(",").map(_.trim).toSet ++ Set("USER_ID_" + rs.int(c.id)),
    contestId = rs.longOpt(c.contestId),
    password = Some(rs.string(c.password)),
    lang = rs.stringOpt(c.lang),
    wikiAccount = rs.stringOpt(c.wikiAccount),
    sort = rs.intOpt(c.sort),
    createdAt = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime)
  )

  def findByContest(contest: Long): Seq[User] =
    where("contestId" -> contest).orderBy(u.id).apply()

  def findByRoundSelection(roundId: Long): Seq[User] = withSQL {
    import SelectionJdbc.s

    select(u.result.*)
      .from(User as u)
      .join(SelectionJdbc as s)
      .on(u.id, s.juryId)
      .where
      .eq(s.roundId, roundId)
      .groupBy(u.id)
      .orderBy(u.id)
  }.map(User(u)).list()

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
      contestId: Option[Long] = None,
      lang: Option[String] = None,
      createdAt: Option[ZonedDateTime] = Some(ZonedDateTime.now)
  ): User = {
    val id = withSQL {
      insert
        .into(User)
        .namedValues(
          column.fullname -> fullname,
          column.email -> email.trim.toLowerCase,
          column.password -> password,
          column.roles -> roles.headOption.getOrElse("jury"),
          column.contestId -> contestId,
          column.lang -> lang,
          column.createdAt -> createdAt
        )
    }.updateAndReturnGeneratedKey()

    User(
      id = Some(id),
      fullname = fullname,
      email = email,
      password = Some(password),
      roles = roles ++ Set("USER_ID_" + id),
      contestId = contestId,
      createdAt = createdAt
    )
  }

  def create(user: User): User = {
    val id = withSQL {
      insert
        .into(User)
        .namedValues(
          column.fullname -> user.fullname,
          column.email -> user.email.trim.toLowerCase,
          column.wikiAccount -> user.wikiAccount,
          column.password -> user.password,
          column.roles -> user.roles.headOption.getOrElse(""),
          column.contestId -> user.contestId,
          column.lang -> user.lang,
          column.sort -> user.sort,
          column.createdAt -> user.createdAt
        )
    }.updateAndReturnGeneratedKey()

    user.copy(id = Some(id), roles = user.roles ++ Set("USER_ID_" + id))
  }

  def updateUser(
      id: Long,
      fullname: String,
      wikiAccount: Option[String],
      email: String,
      roles: Set[String],
      lang: Option[String],
      sort: Option[Int]
  ): Unit =
    updateById(id)
      .withAttributes(
        "fullname" -> fullname,
        "email" -> email,
        "wikiAccount" -> wikiAccount,
        "roles" -> roles.head,
        "lang" -> lang,
        "sort" -> sort
      )

  def updateHash(id: Long, hash: String): Unit =
    updateById(id)
      .withAttributes("password" -> hash)

  def loadJurors(contestId: Long): Seq[User] = {
    findAllBy(sqls.in(User.u.roles, Seq("jury")).and.eq(User.u.contestId, contestId))
  }

  def loadJurors(contestId: Long, jurorIds: Seq[Long]): Seq[User] = {
    findAllBy(
      sqls
        .in(User.u.id, jurorIds)
        .and
        .in(User.u.roles, Seq("jury"))
        .and
        .eq(User.u.contestId, contestId)
    )
  }

}
