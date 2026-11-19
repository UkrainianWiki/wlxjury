package controllers

import db.scalikejdbc.User
import org.intracer.wmua.ContestJury

import java.util.Locale

/** The Type, Country, Year and Role menus of root's all-users page (the maintainer's decision of
  * 2026-11-19): which accounts are listed, by their contest memberships. Type, Country and Year
  * are the contests page's ([[ContestsFilter]]: its params, parsing and country rule), with all
  * years by default, not the latest; Role is one of [[UsersFilter.Roles]] or any. Kept in the URL
  * (`contestType`, `country`, `year`, `role`) only where not the default: a page with no params
  * lists every account, as before the menus. An unknown `year` or `role` is the default, never an
  * error; `year=all` is the default, as on the contests page.
  *
  * It only narrows a list it is given: who may see the page is decided before (root,
  * [[UserController.allUsers]]), never here.
  */
final case class UsersFilter(
    contests: ContestsFilter = UsersFilter.AllContests,
    role: Option[String] = None
) {

  import ContestsFilter._

  def withContests(c: ContestsFilter): UsersFilter = copy(contests = c)
  def withRole(r: Option[String]): UsersFilter = copy(role = r)

  /** Whether every menu is on All: the page lists every account, with a membership or not. */
  def isAll: Boolean =
    contests.kind.isEmpty && contests.country == Country.All && contests.year == Year.All && role.isEmpty

  /** The query params of the choices that aren't the default, in a fixed order (all years implicit). */
  def params: Seq[(String, String)] =
    contests.copy(order = Order.Oldest).params.filterNot(_ == (YearParam -> "all")) ++ role.map(UsersFilter.RoleParam -> _)

  /** `url` with this filter's params added; as it is for the default filter. */
  def addTo(url: String): String = ContestsFilter.addParams(url, params)
}

object UsersFilter {

  val RoleParam = "role"

  /** The Role menu's roles, in its order: the roles that give access to a contest. */
  val Roles: Seq[String] = Seq(User.ADMIN_ROLE, "organizer", User.JURY_ROLE)

  /** Type, Country and Year on All. */
  val AllContests: ContestsFilter = ContestsFilter.Default.withYear(ContestsFilter.Year.All)

  val Default: UsersFilter = UsersFilter()

  /** From the route's params, as the contests page's ([[ContestsFilter.of]]); a missing or unknown
    * year is all years, a missing or unknown role any.
    */
  def of(contestType: Option[String], year: Option[String], country: Option[String], role: Option[String]): UsersFilter = {
    val contests = ContestsFilter.of(contestType, year, country, None)
    UsersFilter(
      contests.year match {
        case ContestsFilter.Year.Latest => contests.withYear(ContestsFilter.Year.All)
        case _                          => contests
      },
      role.map(_.trim.toLowerCase(Locale.ROOT)).filter(Roles.contains)
    )
  }
}

/** Root's all-users page's list and menus (users.scala.html): the accounts the filter keeps, in
  * the order given; the Type, Country and Year menus' entries ([[contestsBar]]: the contests
  * page's, of all the contests); and each choice's count: the accounts it keeps within the other
  * menus' choices. An account is kept if every menu is on All, or if it has an active membership
  * ([[db.scalikejdbc.UserContest.active]]: not "no role", board, audit committee or staff) in a
  * contest of the chosen type, country and year, with the chosen role in that same contest.
  * Counted in memory from the memberships loaded with the accounts ([[User.withMemberships]]),
  * each contest's place resolved once.
  */
final case class UsersFilterBar(
    filter: UsersFilter,
    url: UsersFilter => String,
    users: Seq[User],
    contests: Seq[ContestJury],
    locale: Locale = Locale.ENGLISH
) extends ContestMenuCounts {

  import ContestsFilter._

  /** The Type, Country and Year menus' entries and choices; each choice the page with it, the role kept. */
  val contestsBar: ContestsFilterBar = ContestsFilterBar(filter.contests, c => url(filter.withContests(c)), contests, locale)

  private case class Membership(contest: ContestJury, place: Country, roles: Set[String])

  /** Each account with its active memberships, of the contests listed. */
  private val rows: Seq[(User, Seq[Membership])] = {
    val byId = contests.flatMap(c => c.id.map(_ -> c)).toMap
    val places = scala.collection.mutable.Map.empty[Long, Country]
    users.map { u =>
      u -> u.userContests.filter(_.active).flatMap { uc =>
        byId.get(uc.contestId).map(c => Membership(c, places.getOrElseUpdate(uc.contestId, placeOf(c)), uc.roles))
      }
    }
  }

  /** The year chosen, None for all of them. */
  private val year: Option[Int] = contestsBar.year

  private def keeps(memberships: Seq[Membership], kind: Option[String], country: Country, year: Option[Int],
      role: Option[String]): Boolean =
    (kind.isEmpty && country == Country.All && year.isEmpty && role.isEmpty) ||
      memberships.exists { m =>
        kind.forall(_ == m.contest.name.trim) && Country.matches(country, m.place) && year.forall(_ == m.contest.year) &&
          role.forall(m.roles.contains)
      }

  private def count(kind: Option[String], country: Country, year: Option[Int], role: Option[String]): Int =
    rows.count(r => keeps(r._2, kind, country, year, role))

  /** The accounts the filter keeps, in the order given. */
  val shown: Seq[User] =
    rows.collect { case (u, ms) if keeps(ms, filter.contests.kind, filter.contests.country, year, filter.role) => u }

  def countKind(k: Option[String]): Int = count(k, filter.contests.country, year, filter.role)
  def countCountry(c: Country): Int = count(filter.contests.kind, c, year, filter.role)
  def countYear(y: Option[Int]): Int = count(filter.contests.kind, filter.contests.country, y, filter.role)

  /** How many accounts a Role choice keeps, within the current type, country and year. */
  def countRole(r: Option[String]): Int = count(filter.contests.kind, filter.contests.country, year, r)
}
