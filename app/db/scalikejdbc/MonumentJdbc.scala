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

  object replace {
    def into(support: SQLSyntaxSupport[?]): InsertSQLBuilder =
      InsertSQLBuilder(sqls"replace into ${support.table}")
  }

  def batchInsert(monuments: Seq[Monument]): Unit = {
    val column = MonumentJdbc.column
    DB localTx { implicit session =>
      val batchParams: Seq[Seq[Any]] = monuments.map(m =>
        Seq(
          m.id,
          m.name.take(512),
          m.description,
//        i.article,
          m.year.map(_.take(255)),
          m.city.map(_.take(255)),
          m.place,
          m.typ,
          m.subType,
          m.photo,
          m.gallery,
          m.page,
          m.contest,
          m.id.split("-").headOption.map(_.take(3))
        )
      )
      withSQL {
        replace
          .into(MonumentJdbc)
          .namedValues(
            column.id -> sqls.?,
            column.name -> sqls.?,
            column.description -> sqls.?,
            column.year -> sqls.?,
            column.city -> sqls.?,
            column.place -> sqls.?,
            column.typ -> sqls.?,
            column.subType -> sqls.?,
            column.photo -> sqls.?,
            column.gallery -> sqls.?,
            column.page -> sqls.?,
            column.contest -> sqls.?,
            sqls"adm0" -> sqls.?
          )
      }.batch(batchParams: _*).apply()
    }
  }

  def findAll(
      limit: Option[Int] = None
  )(implicit session: DBSession = autoSession): List[Monument] = withSQL {
    val q: PagingSQLBuilder[List[Monument]] = select
      .from(MonumentJdbc as c)
      //      .where.append(isNotDeleted)
      .orderBy(c.id)

    limit.fold(q)(l => q.limit(l))
  }.map(MonumentJdbc(c)).list()

  def find(id: String)(implicit session: DBSession = autoSession): Option[Monument] = withSQL {
    select.from(MonumentJdbc as c).where.eq(c.id, id) // .and.append(isNotDeleted)
  }.map(MonumentJdbc(c)).single()

}
