package db.scalikejdbc

import db.ImageRepo
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua._
import org.scalawiki.dto.Page
import scalikejdbc._
import scalikejdbc.interpolation.SQLSyntax.distinct
import scalikejdbc.orm.CRUDMapper
import scalikejdbc.orm.logging.LoggerProvider

object ImageJdbc extends CRUDMapper[Image]
  with ImageRepo
  with LoggerProvider {

  implicit def session: DBSession = autoSession

  override val tableName = "images"

  override val primaryKeyFieldName = "page_id"

  val i = ImageJdbc.syntax("i")
  val s = SelectionJdbc.s
  val s1 = SelectionJdbc.syntax("s1")
  val s2 = SelectionJdbc.syntax("s2")
  val s3 = SelectionJdbc.syntax("s3")
  val c = CriteriaRate.c
  val cl = CategoryLinkJdbc.cl

  def fromPage(page: Page, contest: ContestJury): Option[Image] = {
    try {
      for (imageInfo <- page.images.headOption)
        yield Image(
          page.id.get,
          page.title,
          imageInfo.url,
          imageInfo.pageUrl,
          imageInfo.width.get,
          imageInfo.height.get,
          None,
          size = imageInfo.size.map(_.toInt),
          mime = imageInfo.mime,
          mediaType = imageInfo.mediaType
        )
    } catch {
      case e: Throwable =>
        logger.error(
          s"Error getting image info for contest ${contest.id},  page id: ${page.id}, page title: ${page.title}",
          e
        )
        throw e
    }
  }

  override lazy val defaultAlias = createAlias("i")

  override def extract(rs: WrappedResultSet, c: ResultName[Image]): Image = Image(
    pageId = rs.long(c.pageId),
    title = rs.string(c.title),
    url = rs.stringOpt(c.url),
    pageUrl = None,
    width = rs.int(c.width),
    height = rs.int(c.height),
    monumentId = rs.stringOpt(c.monumentId),
    description = rs.stringOpt(c.description),
    size = rs.intOpt(c.size),
    author = rs.stringOpt(c.author),
    mime = rs.stringOpt(c.mime),
    mediaType = rs.stringOpt(c.mediaType)
  )

  def apply(c: ResultName[Image])(rs: WrappedResultSet): Image = extract(rs, c)

  def batchInsert(images: Seq[Image])(implicit session: DBSession = AutoSession): Unit = {
    val column = ImageJdbc.column
    val batchParams: Seq[Seq[Any]] = images.map(i =>
      Seq(
        i.pageId,
        i.title,
        i.url,
        i.pageUrl,
        i.width,
        i.height,
        i.monumentId,
        i.description,
        i.size,
        i.author,
        i.mime,
        i.mediaType
      )
    )
    withSQL {
      insert
        .into(ImageJdbc)
        .namedValues(
          column.pageId -> sqls.?,
          column.title -> sqls.?,
          column.url -> sqls.?,
          column.pageUrl -> sqls.?,
          column.width -> sqls.?,
          column.height -> sqls.?,
          column.monumentId -> sqls.?,
          column.description -> sqls.?,
          column.size -> sqls.?,
          column.author -> sqls.?,
          column.mime -> sqls.?,
          column.mediaType -> sqls.?
        )
    }.batch(batchParams: _*).apply()
  }

  /** Inserts the images, skipping those already stored (e.g. by another contest's import
    * of a shared file), so an import page repeated after a crash or a retry is a no-op.
    */
  def insertIgnore(images: Seq[Image])(implicit session: DBSession): Unit =
    if (images.nonEmpty) {
      val params = images.map(i =>
        Seq(i.pageId, i.title, i.url, i.pageUrl, i.width, i.height, i.monumentId, i.description, i.size,
          i.author, i.mime, i.mediaType))
      sql"""INSERT IGNORE INTO images (page_id, title, url, page_url, width, height, monument_id, description,
                                       size, author, mime, media_type)
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""".batch(params: _*).apply()
    }

  /** The stored images among `pageIds`, each with whether it is in the category. */
  def findWithCategoryLink(pageIds: Seq[Long], categoryId: Long)(implicit session: DBSession): Seq[(Image, Boolean)] =
    if (pageIds.isEmpty) Nil
    else
      sql"""SELECT ${i.result.*}, cm.page_id IS NOT NULL AS linked FROM images i
            LEFT JOIN category_members cm ON cm.page_id = i.page_id AND cm.category_id = $categoryId
            WHERE i.page_id IN ($pageIds)"""
        .map(rs => (ImageJdbc(i)(rs), rs.boolean("linked")))
        .list()

  /** The stored images' monument ids, by page id (NULL ones as None). */
  def monumentIds(pageIds: Seq[Long])(implicit session: DBSession): Map[Long, Option[String]] =
    if (pageIds.isEmpty) Map.empty
    else
      sql"SELECT page_id, monument_id FROM images WHERE page_id IN ($pageIds)"
        .map(rs => rs.long(1) -> rs.stringOpt(2))
        .list()
        .toMap

  /** Updates the image, and its selection rows' copy of its monument id. */
  def update(image: Image): Unit = DB.localTx { implicit session => updateIn(image) }

  /** [[update]] in the caller's transaction. */
  def updateIn(image: Image)(implicit session: DBSession): Unit = {
    updateById(image.pageId).withAttributes(
      "title" -> image.title,
      "url" -> image.url,
      "pageUrl" -> image.pageUrl,
      "width" -> image.width,
      "height" -> image.height,
      "monumentId" -> image.monumentId,
      "description" -> image.description,
      "size" -> image.size,
      "mime" -> image.mime,
      "mediaType" -> image.mediaType
    )
    syncSelectionMonumentId(image.pageId, image.monumentId)
  }

  /** @param pageId
    * @param width
    * @param height
    */
  def updateResolution(pageId: Long, width: Int, height: Int): Unit =
    updateById(pageId).withAttributes("width" -> width, "height" -> height)

  /** Sets the image's monument id, and its selection rows' copy of it. */
  def updateMonumentId(pageId: Long, monumentId: String): Unit = DB.localTx { implicit session =>
    updateMonumentIdIn(pageId, monumentId)
  }

  /** [[updateMonumentId]] in the caller's transaction. */
  def updateMonumentIdIn(pageId: Long, monumentId: String)(implicit session: DBSession): Unit = {
    updateById(pageId).withAttributes("monumentId" -> monumentId)
    syncSelectionMonumentId(pageId, Some(monumentId))
  }

  /** selection.monument_id (V48) is a copy of images.monument_id that the galleries
    * sort and filter by regions on; it must follow the image. A page_id lookup on the
    * unique (page_id, jury_id, round_id) index; rows already right aren't rewritten.
    */
  private def syncSelectionMonumentId(pageId: Long, monumentId: Option[String])(implicit
      session: DBSession
  ): Unit =
    sql"""UPDATE selection SET monument_id = $monumentId
          WHERE page_id = $pageId AND NOT (monument_id <=> $monumentId)""".update()

  /** The stored file of that name, unless it was deleted on Commons (V63): a new upload
    * under the same name is another file, with another page id.
    */
  def findByFilename(filename: String)(implicit session: DBSession = AutoSession): Option[Image] =
    findBy(sqls.eq(column.title, s"File:$filename").and(sqls"deleted_at IS NULL"))

  /** Stores what Commons now says of the file itself (after a rename or a new version):
    * its title, url, size and type. Its monument id, description and author, which come
    * from the page text, stay as they are.
    */
  def updateFileInfo(image: Image)(implicit session: DBSession): Unit =
    updateById(image.pageId).withAttributes(
      "title" -> image.title,
      "url" -> image.url,
      "pageUrl" -> image.pageUrl,
      "width" -> image.width,
      "height" -> image.height,
      "size" -> image.size,
      "mime" -> image.mime,
      "mediaType" -> image.mediaType
    )

  /** Marks the files deleted on Commons, and moves their selection rows to
    * selection_deleted (V63): every gallery, count and result reads selection, so they no
    * longer show them. The image rows stay: deleting them would cascade to the votes.
    *
    * @return the rounds that lost rows (their cached image counts are stale)
    */
  def markDeleted(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else {
      sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id IN ($pageIds) AND deleted_at IS NULL".update()
      val roundIds = sql"SELECT DISTINCT round_id FROM selection WHERE page_id IN ($pageIds)"
        .map(_.longOpt(1))
        .list()
        .flatten
      sql"INSERT IGNORE INTO selection_deleted SELECT * FROM selection WHERE page_id IN ($pageIds)".update()
      sql"DELETE FROM selection WHERE page_id IN ($pageIds)".update()
      roundIds
    }

  def findByContestId(contestId: Long)(implicit session: DBSession = AutoSession): List[Image] =
    ContestJuryJdbc.findById(contestId).map(findByContest).getOrElse(Nil)

  def findByContest(contest: ContestJury)(implicit session: DBSession = AutoSession): List[Image] = {
    contest.categoryId.map(findByCategory).getOrElse(Nil)
  }

  def countByContest(contest: ContestJury): Long =
    contest.categoryId.map(ImageJdbc.countByCategory).getOrElse(0L)

  def findByCategory(categoryId: Long)(implicit session: DBSession = AutoSession): List[Image] =
    withSQL {
      select
        .from[Image](ImageJdbc as i)
        .innerJoin(CategoryLinkJdbc as cl)
        .on(i.pageId, cl.pageId)
        .where
        .eq(cl.categoryId, categoryId)
    }.map(ImageJdbc(i)).list()

  def countByCategory(categoryId: Long): Long = {
    import sqls.{distinct, count => _count}

    withSQL {
      select(_count(distinct(i.pageId)))
        .from(ImageJdbc as i)
        .innerJoin(CategoryLinkJdbc as cl)
        .on(i.pageId, cl.pageId)
        .where
        .eq(cl.categoryId, categoryId)
    }.map(_.int(1)).single().get
  }

  def findByMonumentId(monumentId: String): List[Image] =
    where("monumentId" -> monumentId)
      .orderBy(i.pageId)
      .apply()

  def existingIds(ids: Set[Long]): List[Long] = {
    import sqls.distinct
    withSQL {
      select(distinct(i.pageId)).from(ImageJdbc as i).where.in(i.pageId, ids.toSeq)
    }.map(_.long(1)).list()
  }

  def rateDistribution(userId: Long, roundId: Long): Map[Int, Int] =
    sql"""SELECT s.rate, count(1)
  FROM selection s
  WHERE
  s.jury_id = $userId
  AND s.round_id = $roundId
  GROUP BY rate""".map(rs => rs.int(1) -> rs.int(2)).list().toMap

  /** Distinct images per round, for the given rounds (rounds without images are left
    * out). A loose scan of idx_selection_round_page_rate: no join through rounds, which
    * prevented it. Callers go through [[RoundImageCounts]].
    */
  def imageCountByRounds(roundIds: Seq[Long]): Map[Long, Int] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"""SELECT s.round_id, COUNT(DISTINCT s.page_id) FROM selection s
            WHERE s.round_id IN ($roundIds) GROUP BY s.round_id"""
        .map(rs => rs.long(1) -> rs.int(2))
        .list()
        .toMap

  def byRound(roundId: Long): List[Image] = withSQL {
    select(distinct(i.result.*))
      .from(ImageJdbc as i)
      .innerJoin(SelectionJdbc as s)
      .on(i.pageId, s.pageId)
      .where
      .eq(s.roundId, roundId)
  }.map { rs =>
    ImageJdbc(i)(rs)
  }.list()

  def byRoundMerged(
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0,
      rated: Option[Boolean] = None
  ): Seq[ImageWithRating] =
    SelectionQuery(
      roundId = Some(roundId),
      grouped = true,
      order = Map("rate" -> -1),
      rated = rated,
      limit = Some(Limit(pageSize = Some(pageSize), offset = Some(offset)))
    ).list()

}
