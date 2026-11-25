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

  /** A title naming no category: empty, whitespace or underscores only (NULL too). Every import
    * with no source once found or added the row of the empty title: one row of mixed files,
    * the category of unrelated contests.
    */
  def isBlank(title: String): Boolean = title == null || title.replace('_', ' ').isBlank

  /** The id of the category of `title`, added if there is none: in the app only a contest's own
    * (services.ImageService.ownCategory; security.CategoryOwnershipGuardSpec). A blank title is
    * refused ([[isBlank]]): it would find, or add, one row for unrelated contests.
    */
  def findOrInsert(title: String)(implicit session: DBSession = AutoSession): Long = {
    require(!isBlank(title), "a category needs a title")
    // a title saved with underscores is the same category as its normalised form
    val exact = where("title" -> title).apply().headOption
    exact.orElse(where(sqls"REPLACE(title, '_', ' ') = ${title.replace('_', ' ')}").orderBy(sqls"id").apply().headOption).map(_.id).getOrElse {
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

  /** Whether contest `contestId`'s own list may be started from category `id` ([[copyIgnore]]):
    * it exists, has a title ([[CategoryJdbc.isBlank]]) and no other contest has it. Another
    * contest has it as its category, or as its source (`images`, the title with or without
    * `Category:`), or by V24's name for it ("Images from <name> <year> in <country>"): a contest
    * that left a shared row for its own still has its source. A blank one holds the files of every
    * unnamed import; another contest's holds its files too (a source set by title once gave a 2026
    * contest a 2020 category).
    */
  def mayCopyFrom(id: Long, contestId: Long)(implicit session: DBSession): Boolean =
    sql"""SELECT c.title, (
            SELECT COUNT(*) FROM contest_jury cj
            WHERE cj.id <> $contestId
              AND (cj.category_id = c.id
                OR REPLACE(TRIM(LEADING 'Category:' FROM TRIM(cj.images)), '_', ' ') = t.name
                OR CONCAT('Images from ', cj.name, ' ', cj.year, ' in ', cj.country) = t.name))
          FROM category c
          JOIN (SELECT REPLACE(TRIM(LEADING 'Category:' FROM TRIM(title)), '_', ' ') AS name
                FROM category WHERE id = $id) t
          WHERE c.id = $id"""
      .map(rs => (rs.stringOpt(1), rs.long(2))).single.apply()
      .exists { case (title, others) => !CategoryJdbc.isBlank(title.orNull) && others == 0 }

  /** Puts the pages of category `from` in contest `contestId`'s own category `to` too, skipping
    * the links that exist; nothing if `from` may not be copied ([[mayCopyFrom]]).
    * @return whether it copied
    */
  def copyIgnore(from: Long, to: Long, contestId: Long)(implicit session: DBSession): Boolean =
    mayCopyFrom(from, contestId) && {
      sql"""INSERT IGNORE INTO category_members (category_id, page_id)
            SELECT $to, page_id FROM category_members WHERE category_id = $from""".update.apply()
      true
    }

  /** Puts each page in its country category (`(page id, category title)`, a contest's,
    * services.imports.ContestCountries), adding the category if there is none. Never takes a
    * page out of a category: a contest that ran in the jury tool may have a country's
    * category, or one named after it, as its files. Idempotent.
    * @return the categories of the pages, which may have joined them
    */
  def addCountries(countries: Seq[(Long, String)])(implicit session: DBSession): Seq[Long] =
    if (countries.isEmpty) Nil
    else {
      // a blank title names no country: never the one row of unrelated files (CategoryJdbc.isBlank)
      val ids = countries.map(_._2).distinct.filterNot(CategoryJdbc.isBlank).map { title =>
        sql"INSERT IGNORE INTO category (title) VALUES ($title)".update.apply()
        title -> sql"SELECT id FROM category WHERE title = $title".map(_.long(1)).single.apply().get
      }.toMap
      val links = countries.collect { case (pageId, title) if ids.contains(title) => ids(title) -> pageId }.distinct
      sql"INSERT IGNORE INTO category_members (category_id, page_id) VALUES (?, ?)"
        .batch(links.map { case (c, p) => Seq(c, p) }: _*)
        .apply()
      ids.values.toSeq
    }

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