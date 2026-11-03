package db.scalikejdbc

import scalikejdbc._
import org.intracer.wmua.{Category, CategoryLink, Image}
import scalikejdbc.orm.CRUDMapper

object CategoryJdbc extends CRUDMapper[Category] {

  implicit def session: DBSession = autoSession

  override val tableName = "category"

  override val primaryKeyFieldName = "id"

  override lazy val defaultAlias = createAlias("cat")

  val c = CategoryJdbc.syntax("cat")

  override def extract(rs: WrappedResultSet, c: ResultName[Category]): Category = Category(
    id = rs.long(c.id),
    title = rs.string(c.title)
  )

  def findOrInsert(title: String)(implicit session: DBSession = AutoSession): Long = {
    where("title" -> title).apply().headOption.map(_.id).getOrElse {
      createWithAttributes("title" -> title)
    }
  }
}

object CategoryLinkJdbc extends SQLSyntaxSupport[CategoryLink] {
  val cl = CategoryLinkJdbc.syntax("cl")
  override val tableName = "category_members"

  def addToCategory(categoryId: Long, images: Seq[Image])(implicit session: DBSession = AutoSession): Unit = {
    val categoryLinks = images.map { image => CategoryLink(categoryId, image.pageId) }

    batchInsert(categoryLinks)
  }

  /** Puts the pages in the category, skipping the links that exist (unique
    * cm_category_page), so an import page repeated after a crash or a retry is a no-op.
    */
  def linkIgnore(categoryId: Long, pageIds: Seq[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty)
      sql"INSERT IGNORE INTO category_members (category_id, page_id) VALUES (?, ?)"
        .batch(pageIds.map(id => Seq(categoryId, id)): _*)
        .apply()

  /** Puts the pages of category `from` in category `to` too, skipping the links that exist. */
  def copyIgnore(from: Long, to: Long)(implicit session: DBSession): Unit =
    sql"""INSERT IGNORE INTO category_members (category_id, page_id)
          SELECT $to, page_id FROM category_members WHERE category_id = $from""".update.apply()

  def batchInsert(links: Seq[CategoryLink])(implicit session: DBSession = AutoSession): Unit = {
    val column = CategoryLinkJdbc.column

    val batchParams: Seq[Seq[Any]] = links.map(link => Seq(
      link.categoryId,
      link.pageId
    ))

    withSQL {
      insert.into(CategoryLinkJdbc).namedValues(
        column.categoryId -> sqls.?,
        column.pageId -> sqls.?
      )
    }.batch(batchParams: _*).apply()
  }
}