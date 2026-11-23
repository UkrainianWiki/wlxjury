package db.scalikejdbc

import scalikejdbc._

import java.time.Instant

/** A contest series (table `series`, V109; contest series plan §3.1): one contest per year across
  * years, "Wiki Loves Monuments in Nepal". Root creates, renames and retires it, and assigns
  * contests to it ([[services.series.ContestSeries]]).
  *
  * @param contestType a scalawiki `ContestType` code ("wlm"), or None
  * @param country     a country name, "International", or None
  * @param campaign    the Commons campaign config code, if any
  */
final case class Series(
    id: Long,
    name: String,
    contestType: Option[String],
    country: Option[String],
    campaign: Option[String],
    createdBy: Option[Long],
    createdAt: Instant,
    retiredAt: Option[Instant],
    retiredBy: Option[Long]
) {
  def retired: Boolean = retiredAt.isDefined
  def live: Boolean = !retired
}

/** The `series` table (V109). Times from the database clock. Every write is root's, through
  * [[services.series.ContestSeries]], which logs it in `contest_change`.
  */
object SeriesJdbc {

  val NameMaxLength = 255

  private val columns =
    sqls"id, name, contest_type, country, campaign, created_by, ${DbTime.epoch("created_at")}, ${DbTime.epoch("retired_at")}, retired_by"

  private def extract(rs: WrappedResultSet): Series =
    Series(
      id = rs.long("id"),
      name = rs.string("name"),
      contestType = rs.stringOpt("contest_type"),
      country = rs.stringOpt("country"),
      campaign = rs.stringOpt("campaign"),
      createdBy = rs.longOpt("created_by"),
      createdAt = DbTime.instant(rs, "created_at"),
      retiredAt = DbTime.instantOpt(rs, "retired_at"),
      retiredBy = rs.longOpt("retired_by")
    )

  def findAll()(implicit session: DBSession = AutoSession): Seq[Series] =
    sql"SELECT $columns FROM series ORDER BY name, id".map(extract).list.apply()

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[Series] =
    sql"SELECT $columns FROM series WHERE id = $id".map(extract).single.apply()

  def findByIds(ids: Seq[Long])(implicit session: DBSession = AutoSession): Seq[Series] =
    if (ids.isEmpty) Nil
    else sql"SELECT $columns FROM series WHERE id IN (${ids.distinct})".map(extract).list.apply()

  /** The series, locked `FOR UPDATE` until the transaction ends: every creation, assignment, move,
    * retirement and un-retirement in it takes this lock first (plan §3.1), so two of them for one
    * year meet here rather than at the key.
    */
  def lock(id: Long)(implicit session: DBSession): Option[Series] =
    sql"SELECT $columns FROM series WHERE id = $id FOR UPDATE".map(extract).single.apply()

  /** A live series of this name, case aside (a second one is refused: the menus name series by it). */
  def liveByName(name: String)(implicit session: DBSession = AutoSession): Option[Series] =
    sql"SELECT $columns FROM series WHERE retired_at IS NULL AND LOWER(name) = LOWER($name) LIMIT 1"
      .map(extract).single.apply()

  def create(name: String, contestType: Option[String], country: Option[String], campaign: Option[String],
      createdBy: Long)(implicit session: DBSession): Long =
    sql"""INSERT INTO series (name, contest_type, country, campaign, created_by, created_at)
          VALUES ($name, $contestType, $country, $campaign, $createdBy, NOW(3))""".updateAndReturnGeneratedKey.apply()

  def rename(id: Long, name: String)(implicit session: DBSession): Int =
    sql"UPDATE series SET name = $name WHERE id = $id".update.apply()

  def setRetired(id: Long, by: Option[Long])(implicit session: DBSession): Int =
    by match {
      case Some(actor) => sql"UPDATE series SET retired_at = NOW(3), retired_by = $actor WHERE id = $id AND retired_at IS NULL".update.apply()
      case None        => sql"UPDATE series SET retired_at = NULL, retired_by = NULL WHERE id = $id".update.apply()
    }
}
