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

  def findOrInsert(title: String): Long = {
    where("title" -> title).apply().headOption.map(_.id).getOrElse {
      createWithAttributes("title" -> title)
    }
  }
}

object CategoryLinkJdbc extends SQLSyntaxSupport[CategoryLink] {
  val cl = CategoryLinkJdbc.syntax("cl")
  override val tableName = "category_members"

  def addToCategory(categoryId: Long, images: Seq[Image]): Unit = {
    val categoryLinks = images.map { image => CategoryLink(categoryId, image.pageId) }

    batchInsert(categoryLinks)
  }

  def batchInsert(links: Seq[CategoryLink]): Unit = {
    val column = CategoryLinkJdbc.column

    DB localTx { implicit session =>
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
}