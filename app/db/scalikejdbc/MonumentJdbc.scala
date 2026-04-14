package db.scalikejdbc

import org.scalawiki.wlx.dto.Monument
import scalikejdbc._

object MonumentJdbc extends SQLSyntaxSupport[Monument] {

  override val tableName = "monument"

  private val c = MonumentJdbc.syntax("c")

  def apply(c: SyntaxProvider[Monument])(rs: WrappedResultSet): Monument = apply(c.resultName)(rs)

  def apply(c: ResultName[Monument])(rs: WrappedResultSet): Monument = new Monument(
    id = rs.string(c.id),
    name = rs.string(c.name),
    description = rs.stringOpt(c.description),
    article = None,
    year = rs.stringOpt(c.year),
    city = rs.stringOpt(c.city),
    place = rs.stringOpt(c.place),
    user = rs.stringOpt(c.user),
    area = rs.stringOpt(c.area),
    lat = rs.stringOpt(c.lat),
    lon = rs.stringOpt(c.lon),
    typ = rs.stringOpt(c.typ),
    subType = rs.stringOpt(c.subType),
    photo = rs.stringOpt(c.photo),
    gallery = rs.stringOpt(c.gallery),
    resolution = rs.stringOpt(c.resolution),
    page = rs.string(c.page),
    contest = rs.longOpt(c.contest)
  )

  // ── SQL builders ──────────────────────────────────────────────────────────

  private object insertIgnore {
    def into(support: SQLSyntaxSupport[?]): InsertSQLBuilder =
      InsertSQLBuilder(sqls"insert ignore into ${support.table}")
  }

  private object insertInto {
    def into(support: SQLSyntaxSupport[?]): InsertSQLBuilder =
      InsertSQLBuilder(sqls"insert into ${support.table}")
  }

  // Appended after VALUES (…) for the upsert path.
  // column.* references render as bare DB column names (camelCase → snake_case).
  private lazy val upsertSuffix: SQLSyntax = {
    val c = MonumentJdbc.column
    sqls"""ON DUPLICATE KEY UPDATE
      ${c.name} = VALUES(${c.name}),
      ${c.description} = VALUES(${c.description}),
      ${c.year} = VALUES(${c.year}),
      ${c.city} = VALUES(${c.city}),
      ${c.place} = VALUES(${c.place}),
      ${c.typ} = VALUES(${c.typ}),
      ${c.subType} = VALUES(${c.subType}),
      ${c.photo} = VALUES(${c.photo}),
      ${c.gallery} = VALUES(${c.gallery}),
      ${c.page} = VALUES(${c.page}),
      ${c.contest} = VALUES(${c.contest}),
      adm0 = VALUES(adm0)"""
  }

  // ── Shared helpers ─────────────────────────────────────────────────────────

  private def toBatchParams(monuments: Seq[Monument]): Seq[Seq[Any]] =
    monuments.map { m =>
      Seq(
        m.id,
        m.name.take(512),
        m.description,
//      m.article,
        m.year.map(_.take(255)),
        m.city.map(_.take(255)),
        m.place,
        m.typ.map(_.take(255)),
        m.subType.map(_.take(255)),
        m.photo,
        m.gallery,
        m.page,
        m.contest,
        m.id.split("-").headOption.map(_.take(3))
      )
    }

  private def columnDefs: Seq[(SQLSyntax, ParameterBinder)] = {
    val c = MonumentJdbc.column
    Seq(
      c.id          -> sqls.?,
      c.name        -> sqls.?,
      c.description -> sqls.?,
      c.year        -> sqls.?,
      c.city        -> sqls.?,
      c.place       -> sqls.?,
      c.typ         -> sqls.?,
      c.subType     -> sqls.?,
      c.photo       -> sqls.?,
      c.gallery     -> sqls.?,
      c.page        -> sqls.?,
      c.contest     -> sqls.?,
      sqls"adm0"    -> sqls.?
    )
  }

  // ── Public API ─────────────────────────────────────────────────────────────

  /** Upsert monuments — inserts new rows, updates all columns on duplicate id.
   *  Use for production data refresh (MonumentService.updateLists).
   *  More efficient than REPLACE INTO: no delete step, no auto-increment side-effects.
   */
  def batchInsert(monuments: Seq[Monument])(implicit session: DBSession = AutoSession): Unit =
    withSQL {
      insertInto
        .into(MonumentJdbc)
        .namedValues(columnDefs: _*)
        .append(upsertSuffix)
    }.batch(toBatchParams(monuments): _*).apply()

  /** Insert monuments, ignoring rows with duplicate id.
   *  Use when the target table is known to be empty (e.g., Gatling fixture load).
   *  Avoids the update overhead of ON DUPLICATE KEY UPDATE when no conflicts exist.
   */
  def batchInsertFresh(monuments: Seq[Monument])(implicit session: DBSession = AutoSession): Unit =
    withSQL {
      insertIgnore
        .into(MonumentJdbc)
        .namedValues(columnDefs: _*)
    }.batch(toBatchParams(monuments): _*).apply()

  def findAll(
      limit: Option[Int] = None
  )(implicit session: DBSession = autoSession): List[Monument] = withSQL {
    val q: PagingSQLBuilder[List[Monument]] = select
      .from(MonumentJdbc as c)
      .orderBy(c.id)

    limit.fold(q)(l => q.limit(l))
  }.map(MonumentJdbc(c)).list()

  def find(id: String)(implicit session: DBSession = autoSession): Option[Monument] = withSQL {
    select.from(MonumentJdbc as c).where.eq(c.id, id)
  }.map(MonumentJdbc(c)).single()

}
