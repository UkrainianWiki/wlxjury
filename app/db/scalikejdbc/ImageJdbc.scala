package db.scalikejdbc

import db.ImageRepo
import db.scalikejdbc.rewrite.ImageDbNew
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

  /** The stored images with these titles, but those deleted on Commons (V63): a new upload
    * under the same name is another file, with another page id. Compared case-sensitively,
    * as [[findByFilename]] does (the general_ci index finds the candidates, BINARY picks).
    */
  def findByTitles(titles: Seq[String])(implicit session: DBSession = AutoSession): Seq[Image] =
    titles.distinct.grouped(1000).toSeq.flatMap { chunk =>
      sql"""SELECT ${i.result.*} FROM images i
            WHERE i.title IN ($chunk) AND BINARY i.title IN ($chunk) AND i.deleted_at IS NULL"""
        .map(rs => ImageJdbc(i)(rs)).list()
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
  def update(image: Image): Unit = {
    DB.localTx { implicit session => updateIn(image) }
    RegionCountCache.invalidateMonumentIds()
  }

  /** [[update]] in the caller's transaction: the caller invalidates
    * [[RegionCountCache.invalidateMonumentIds]] after it commits. */
  def updateIn(image: Image)(implicit session: DBSession): Unit = {
    updateById(image.pageId).withAttributes(
      fileAttributes(image) ++ Seq("monumentId" -> image.monumentId, "description" -> image.description): _*
    )
    syncSelectionMonumentId(image.pageId, image.monumentId)
  }

  /** What Commons says of the file itself, not of its page text: what an update writes
    * (with pageUrl, which reading an image back doesn't return), and so what the import
    * ([[services.ImageService.changedImages]]) and a file check
    * ([[services.imports.CommonsFileCheck]]) compare.
    */
  def fileInfo(image: Image): Seq[(String, Any)] =
    Seq(
      "title" -> image.title,
      "url" -> image.url,
      "width" -> image.width,
      "height" -> image.height,
      "size" -> image.size,
      "mime" -> image.mime,
      "mediaType" -> image.mediaType
    )

  private def fileAttributes(image: Image): Seq[(String, Any)] = fileInfo(image) :+ ("pageUrl" -> image.pageUrl)

  /** @param pageId
    * @param width
    * @param height
    */
  def updateResolution(pageId: Long, width: Int, height: Int): Unit =
    updateById(pageId).withAttributes("width" -> width, "height" -> height)

  /** Sets the image's monument id, and its selection rows' copy of it. */
  def updateMonumentId(pageId: Long, monumentId: String): Unit = {
    DB.localTx { implicit session => updateMonumentIdIn(pageId, monumentId) }
    RegionCountCache.invalidateMonumentIds()
  }

  /** [[updateMonumentId]] in the caller's transaction: the caller invalidates
    * [[RegionCountCache.invalidateMonumentIds]] after it commits. */
  def updateMonumentIdIn(pageId: Long, monumentId: String)(implicit session: DBSession): Unit = {
    updateById(pageId).withAttributes("monumentId" -> monumentId)
    syncSelectionMonumentId(pageId, Some(monumentId))
  }

  /** selection.monument_id (V48) is a copy of images.monument_id that the galleries
    * sort and filter by regions on; it must follow the image, also in the votes moved
    * aside (V63), which may come back. A page_id lookup on the unique
    * (page_id, jury_id, round_id) index; rows already right aren't rewritten.
    */
  private def syncSelectionMonumentId(pageId: Long, monumentId: Option[String])(implicit
      session: DBSession
  ): Unit =
    Seq(sqls"selection", sqls"selection_deleted").foreach { table =>
      sql"""UPDATE $table SET monument_id = $monumentId
            WHERE page_id = $pageId AND NOT (monument_id <=> $monumentId)""".update()
    }

  /** The stored file of that name, unless it was deleted on Commons (V63): a new upload
    * under the same name is another file, with another page id. Compared case-sensitively,
    * as [[findRenamed]] does: Commons titles differ by case after the first letter (the
    * general_ci index finds the candidates, BINARY picks the one).
    */
  def findByFilename(filename: String)(implicit session: DBSession = AutoSession): Option[Image] = {
    val title = s"File:$filename"
    findBy(sqls.eq(column.title, title).and(sqls"BINARY title = $title AND deleted_at IS NULL"))
  }

  /** Stores what Commons now says of the file itself (after a rename or a new version):
    * its title, url, size and type. Its monument id, description and author, which come
    * from the page text, stay as they are.
    */
  def updateFileInfo(image: Image)(implicit session: DBSession): Unit =
    updateById(image.pageId).withAttributes(fileAttributes(image): _*)

  /** The file stored under a former title (V63), renamed on Commons since, unless it was
    * deleted there.
    */
  def findRenamed(filename: String)(implicit session: DBSession = AutoSession): Option[Image] =
    sql"""SELECT ${i.result.*} FROM image_renames r JOIN images i ON i.page_id = r.page_id
          WHERE r.title = ${s"File:$filename"} AND i.deleted_at IS NULL"""
      .map(ImageJdbc(i))
      .single()

  /** The file of that name deleted on Commons (V63), the latest marked if several were. */
  def findDeletedByFilename(filename: String)(implicit session: DBSession = AutoSession): Option[Image] = {
    val title = s"File:$filename"
    sql"""SELECT ${i.result.*} FROM images i
          WHERE i.title = $title AND BINARY i.title = $title AND i.deleted_at IS NOT NULL
          ORDER BY i.deleted_at DESC LIMIT 1"""
      .map(ImageJdbc(i))
      .single()
  }

  /** The file a thumbnail of `filename` shows: the stored file of that name
    * ([[findByFilename]]), else the one renamed from it ([[findRenamed]]), else the one of
    * that name deleted on Commons ([[findDeletedByFilename]]), whose thumbnails the finished
    * rounds still show from the cache. Each looked up only if the one before finds none:
    * nearly every thumbnail is of a file stored under its name.
    *
    * @return the file, and whether it is stored under that name
    */
  def findForThumbnail(filename: String)(implicit session: DBSession = AutoSession): Option[(Image, Boolean)] =
    findByFilename(filename).map(_ -> true)
      .orElse(findRenamed(filename).map(_ -> false))
      .orElse(findDeletedByFilename(filename).map(_ -> true))

  /** Remembers a file's former title (V63), for [[findRenamed]]. */
  def recordRename(formerTitle: String, pageId: Long)(implicit session: DBSession): Unit =
    sql"REPLACE INTO image_renames (title, page_id) VALUES ($formerTitle, $pageId)".update()

  /** Marks the files deleted on Commons (V63). The image rows stay: deleting them would
    * cascade to the votes. Their votes are moved with [[archiveVotes]].
    */
  def setDeleted(pageIds: Seq[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty)
      sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id IN ($pageIds) AND deleted_at IS NULL".update()

  /** Whether the file is marked deleted on Commons (V63). */
  def isDeleted(pageId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"SELECT 1 FROM images WHERE page_id = $pageId AND deleted_at IS NOT NULL".map(_.int(1)).single().isDefined

  /** Of `pageIds`, those marked deleted, read as committed and locked in share mode until
    * the transaction ends: a restore ([[clearDeleted]]) waits for it, or it for the restore.
    */
  def lockDeleted(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else
      sql"""SELECT page_id FROM images WHERE page_id IN ($pageIds) AND deleted_at IS NOT NULL
            ORDER BY page_id LOCK IN SHARE MODE"""
        .map(_.long(1))
        .list()

  /** Locks the files' rows for update, in page id order, as a distribution's chunk locks
    * them (in share mode, for selection's foreign key): a transaction that updates several
    * files in more than one statement locks them all with this first.
    */
  def lockFiles(pageIds: Seq[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty)
      sql"SELECT page_id FROM images WHERE page_id IN (${pageIds.distinct}) ORDER BY page_id FOR UPDATE"
        .map(_.long(1))
        .list(): Unit

  /** Of `pageIds`, those marked deleted or with votes moved aside: those to restore. */
  def deletedOrArchived(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else
      sql"""SELECT page_id FROM images WHERE page_id IN ($pageIds) AND deleted_at IS NOT NULL
            UNION SELECT DISTINCT page_id FROM selection_deleted WHERE page_id IN ($pageIds)"""
        .map(_.long(1))
        .list()

  /** Clears the deleted mark of files Commons has again (an undeletion). */
  def clearDeleted(pageIds: Seq[Long])(implicit session: DBSession): Unit =
    if (pageIds.nonEmpty)
      sql"UPDATE images SET deleted_at = NULL WHERE page_id IN ($pageIds) AND deleted_at IS NOT NULL".update()

  /** The open rounds ([[Round.openCond]]) that `pageIds` have votes in, read as committed
    * and locked in share mode until the transaction ends. A file deleted on Commons leaves
    * these rounds only ([[archiveVotes]]); a round opened meanwhile ([[Round.setActiveGuarded]])
    * then either is open here, or waits for this transaction and sees the files deleted.
    * Call it before marking them deleted.
    */
  def lockOpenRoundsOf(pageIds: Seq[Long])(implicit session: DBSession): Set[Long] =
    if (pageIds.isEmpty) Set.empty
    else {
      val roundIds = sql"SELECT DISTINCT round_id FROM selection WHERE page_id IN ($pageIds)".map(_.long(1)).list()
      if (roundIds.isEmpty) Set.empty
      else
        sql"""SELECT id, ${Round.openCond(sqls"rounds")} FROM rounds WHERE id IN ($roundIds)
              ORDER BY id LOCK IN SHARE MODE"""
          .map(rs => rs.long(1) -> rs.booleanOpt(2).getOrElse(false))
          .list()
          .collect { case (id, true) => id }
          .toSet
    }

  /** Moves the selection rows of `pageIds` in the `roundIds` rounds to selection_deleted
    * (V63): every gallery, count and result reads selection, so they no longer show the
    * files. A row moved before (a file deleted, restored and deleted again) is replaced by
    * the current one.
    *
    * @return the rounds that lost rows (their cached image counts are stale)
    */
  def archiveVotes(pageIds: Seq[Long], roundIds: Set[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty || roundIds.isEmpty) Nil
    else moveVotesAside(sqls"page_id IN ($pageIds) AND round_id IN ($roundIds)")

  /** [[archiveVotes]] of the files in the round that were deleted on Commons: when the round
    * opens ([[Round.setActiveGuarded]]), or those a distribution added to it as they were
    * being marked deleted.
    *
    * From the files marked deleted (V64's index), few, to their votes in the round: not
    * from the round's votes, up to some 760k rows, nearly always to none.
    *
    * @return the files moved
    */
  def archiveDeletedVotesInRound(roundId: Long)(implicit session: DBSession): Seq[Long] = {
    val pageIds = sql"""SELECT DISTINCT STRAIGHT_JOIN s.page_id FROM images i JOIN selection s ON s.page_id = i.page_id
                        WHERE i.deleted_at IS NOT NULL AND s.round_id = $roundId"""
      .map(_.long(1))
      .list()
    if (pageIds.nonEmpty) moveVotesAside(sqls"round_id = $roundId AND page_id IN ($pageIds)")
    pageIds
  }

  /** Moves the selection rows `where` selects to selection_deleted.
    *
    * @return their rounds
    */
  private def moveVotesAside(where: SQLSyntax)(implicit session: DBSession): Seq[Long] = {
    // The latest rows, not the snapshot's, locked as the DELETE locks them: a shared lock
    // first would deadlock, on its upgrade, with a juror voting on one of them
    val roundIds = sql"SELECT DISTINCT round_id FROM selection WHERE $where FOR UPDATE"
      .map(_.long(1))
      .list()
    sql"REPLACE INTO selection_deleted SELECT * FROM selection WHERE $where".update()
    sql"DELETE FROM selection WHERE $where".update()
    roundIds
  }

  /** Moves back all the selection rows of `pageIds` that [[archiveVotes]] moved aside
    * ([[moveVotesBack]]).
    *
    * @return the rounds that gained rows (their cached image counts are stale)
    */
  def restoreVotes(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else moveVotesBack(sqls"d.page_id IN ($pageIds)")

  /** Moves the selection_deleted rows `where` selects (on `d`) back to selection, but those
    * of rounds deleted meanwhile, which are dropped. Where the file was distributed again to
    * the same juror in the round meanwhile, the juror's vote wins: the moved row replaces an
    * unrated new one, and gives way to a rated one (the juror voted again). Nothing `where`
    * selects stays aside.
    *
    * @return the rounds that gained rows
    */
  private def moveVotesBack(where: SQLSyntax)(implicit session: DBSession): Seq[Long] = {
    val sameVote = sqls"s.page_id = d.page_id AND s.jury_id = d.jury_id AND s.round_id = d.round_id"
    val roundExists = sqls"EXISTS (SELECT 1 FROM rounds r WHERE r.id = d.round_id)"
    val roundIds = sql"SELECT DISTINCT d.round_id FROM selection_deleted d WHERE $where FOR UPDATE"
      .map(_.long(1))
      .list()
    sql"""DELETE s FROM selection s JOIN selection_deleted d ON $sameVote
          WHERE $where AND COALESCE(s.rate, 0) = 0 AND COALESCE(d.rate, 0) <> 0""".update()
    sql"""INSERT INTO selection SELECT d.* FROM selection_deleted d
          WHERE $where AND $roundExists AND NOT EXISTS (SELECT 1 FROM selection s WHERE $sameVote)""".update()
    sql"DELETE d FROM selection_deleted d WHERE $where".update()
    roundIds
  }

  def findByContestId(contestId: Long)(implicit session: DBSession = AutoSession): List[Image] =
    ContestJuryJdbc.findById(contestId).map(findByContest).getOrElse(Nil)

  def findByContest(contest: ContestJury)(implicit session: DBSession = AutoSession): List[Image] = {
    contest.categoryId.map(findByCategory).getOrElse(Nil)
  }

  def countByContest(contest: ContestJury): Long =
    contest.categoryId.map(ImageJdbc.countByCategory).getOrElse(0L)

  /** The category's images, but those deleted on Commons (V63). */
  def findByCategory(categoryId: Long)(implicit session: DBSession = AutoSession): List[Image] =
    withSQL {
      select
        .from[Image](ImageJdbc as i)
        .innerJoin(CategoryLinkJdbc as cl)
        .on(i.pageId, cl.pageId)
        .where
        .eq(cl.categoryId, categoryId)
        .and
        .isNull(i.column("deleted_at"))
    }.map(ImageJdbc(i)).list()

  /** The number of [[findByCategory]]'s images: those deleted on Commons are left out. */
  def countByCategory(categoryId: Long): Long = {
    import sqls.{distinct, count => _count}

    withSQL {
      select(_count(distinct(i.pageId)))
        .from(ImageJdbc as i)
        .innerJoin(CategoryLinkJdbc as cl)
        .on(i.pageId, cl.pageId)
        .where
        .eq(cl.categoryId, categoryId)
        .and
        .isNull(i.column("deleted_at"))
    }.map(_.int(1)).single().get
  }

  /** A stored file, with when it was marked deleted on Commons (V63), if it was. */
  final case class StoredFile(image: Image, deletedAt: Option[java.time.ZonedDateTime])

  /** The files a contest files page lists ([[controllers.GalleryController.contestFiles]]),
    * each in its order: a category's files, all in page id order or only those deleted on
    * Commons, the latest deleted first; or a round's files deleted on Commons, likewise.
    * Each also in one region ([[ImageDbNew.regionOf]] of the file's monument id); a
    * category's also of one country ([[CountryFilter]]).
    */
  sealed trait FileList

  /** The files of a country, by page id (services.imports.ContestCountries.fileCountries). */
  final case class CountryFilter(pageIds: Seq[Long])

  object FileList {

    /** [[categoryFiles]] */
    final case class Category(categoryId: Long, deletedOnly: Boolean, country: Option[CountryFilter] = None)
        extends FileList

    /** [[roundDeletedFiles]] */
    final case class RoundDeleted(roundId: Long) extends FileList
  }

  /** The list's files from `offset` on, in the region or all of them. */
  def listFiles(list: FileList, region: Option[String], limit: Int, offset: Int)(implicit
      session: DBSession = AutoSession
  ): Seq[StoredFile] =
    list match {
      case FileList.Category(categoryId, deletedOnly, country) =>
        categoryFiles(categoryId, deletedOnly, limit, offset, region, country)
      case FileList.RoundDeleted(roundId)             => roundDeletedFiles(roundId, limit, offset, region)
    }

  /** The number of the list's files in each region, those without one left out: for all a
    * category's files, from the category's index and the images' (page_id, monument_id)
    * index (V66) alone; for the others, from the few files marked deleted (V64's index).
    */
  def regionCounts(list: FileList)(implicit session: DBSession = AutoSession): Map[String, Int] = {
    val query = list match {
      case FileList.Category(categoryId, false, country) =>
        sql"""SELECT ${regionOf(sqls"r")} AS region, COUNT(*) FROM ${categoryMembers(categoryId, Some(sqls"r"), country)}
              GROUP BY region"""
      case _ =>
        sql"SELECT ${regionOf(sqls"i")} AS region, COUNT(*) ${deletedFiles(list)} GROUP BY region"
    }
    query.map(rs => rs.stringOpt(1) -> rs.int(2)).list().collect { case (Some(region), n) => region -> n }.toMap
  }

  /** The number of the category's files in each region, and of those without one: from the
    * category's index and the images' (page_id, monument_id) index (V66) alone, as
    * [[regionCounts]] reads them. Every member has an image (FK_category_member_page_id, V65),
    * so their total is the category's number of files. Cached by [[RegionCountCache]].
    */
  def categoryRegionCounts(categoryId: Long)(implicit session: DBSession = AutoSession): RegionCountCache.Counts = {
    val rows = sql"""SELECT ${regionOf(sqls"r")} AS region, COUNT(*) FROM ${categoryMembers(categoryId, Some(sqls"r"))}
                     GROUP BY region ORDER BY NULL"""
      .map(rs => rs.stringOpt(1) -> rs.int(2)).list()
    RegionCountCache.Counts(rows.collect { case (Some(region), n) => region -> n }.toMap,
      rows.collect { case (None, n) => n }.sum)
  }

  /** The links of the category's files to the categories whose titles start with one of
    * `prefixes` (a contest's country categories, services.imports.ContestCountries):
    * `(page id, category id, title)`. From the category's members, each looking up its
    * categories by the members' page_id index (FK_category_member_page_id): for a contest's
    * few hundred files, not the countries' many.
    */
  def categoryCountryLinks(categoryId: Long, prefixes: Seq[String])(implicit
      session: DBSession = AutoSession
  ): Seq[(Long, Long, String)] =
    if (prefixes.isEmpty) Nil
    else {
      val titled = sqls.joinWithOr(prefixes.map(p => sqls"LEFT(c.title, ${p.length}) = $p"): _*)
      sql"""SELECT cc.page_id, c.id, c.title FROM category_members m
              STRAIGHT_JOIN category_members cc ON cc.page_id = m.page_id AND cc.category_id <> m.category_id
              STRAIGHT_JOIN category c ON c.id = cc.category_id
            WHERE m.category_id = $categoryId AND ($titled)"""
        .map(rs => (rs.long(1), rs.long(2), rs.string(3)))
        .list()
    }

  /** The number of the category's files deleted on Commons, of the country or all of them,
    * from the files marked deleted (V64's index).
    */
  def categoryDeletedCount(categoryId: Long, country: Option[CountryFilter] = None)(implicit
      session: DBSession = AutoSession
  ): Int =
    sql"""SELECT COUNT(*) ${deletedFiles(FileList.Category(categoryId, deletedOnly = true, country))}"""
      .map(_.int(1)).single().getOrElse(0)

  /** The number of the list's files, in the region or all of them, before the file `pageId`
    * in the list's order: its position, if it is in the list, as [[listFiles]] pages them.
    */
  def fileRank(list: FileList, region: Option[String], pageId: Long)(implicit session: DBSession = AutoSession): Int = {
    val query = list match {
      case FileList.Category(categoryId, false, country) =>
        val r = region.map(_ => sqls"r")
        sql"""SELECT COUNT(*) FROM ${categoryMembers(categoryId, r, country)} ${inRegion(r, region)}
              AND m.page_id < $pageId"""
      case _ =>
        val deletedAt = sqls"(SELECT f.deleted_at FROM images f WHERE f.page_id = $pageId)"
        sql"""SELECT COUNT(*) ${deletedFiles(list)} ${inRegion(Some(sqls"i"), region)}
              AND (i.deleted_at > $deletedAt OR i.deleted_at = $deletedAt AND i.page_id < $pageId)"""
    }
    query.map(_.int(1)).single().getOrElse(0)
  }

  /** The category's files, those deleted on Commons too ([[findByCategory]] leaves them
    * out), or only those: in page id order, or the latest deleted first. In the region
    * (its id, [[ImageDbNew.regionOf]]) and of the country (its category), or all of them.
    *
    * All of them are paged over the category's index alone (in a region, with the images'
    * (page_id, monument_id) index, V66), then joined to their images (every member has one:
    * FK_category_member_page_id, which production has since V65): a deep page doesn't read
    * the images it skips. The deleted ones are found from the few files marked deleted
    * (V64's index).
    */
  def categoryFiles(
      categoryId: Long,
      deletedOnly: Boolean,
      limit: Int,
      offset: Int,
      region: Option[String] = None,
      country: Option[CountryFilter] = None
  )(implicit session: DBSession = AutoSession): Seq[StoredFile] =
    (if (deletedOnly)
       sql"""SELECT ${i.result.*}, i.deleted_at AS file_deleted_at
             ${deletedFiles(FileList.Category(categoryId, deletedOnly, country))} ${inRegion(Some(sqls"i"), region)}
             ORDER BY $LatestDeletedFirst LIMIT $limit OFFSET $offset"""
     else {
       val r = region.map(_ => sqls"r")
       sql"""SELECT ${i.result.*}, i.deleted_at AS file_deleted_at
             FROM (SELECT m.page_id FROM ${categoryMembers(categoryId, r, country)} ${inRegion(r, region)}
                   ORDER BY m.page_id LIMIT $limit OFFSET $offset) cm
             JOIN images i ON i.page_id = cm.page_id
             ORDER BY cm.page_id"""
     })
      .map(storedFile)
      .list()

  /** The round's files deleted on Commons: with votes of the round moved aside (V63), or
    * still counted in it (a round not open when they were deleted keeps them,
    * [[Round.openCond]]), the latest deleted first; in the region, or all of them. Found
    * from the few files marked deleted (V64's index).
    */
  def roundDeletedFiles(roundId: Long, limit: Int, offset: Int, region: Option[String] = None)(implicit
      session: DBSession = AutoSession
  ): Seq[StoredFile] =
    sql"""SELECT ${i.result.*}, i.deleted_at AS file_deleted_at
          ${deletedFiles(FileList.RoundDeleted(roundId))} ${inRegion(Some(sqls"i"), region)}
          ORDER BY $LatestDeletedFirst LIMIT $limit OFFSET $offset"""
      .map(storedFile)
      .list()

  /** The number of [[roundDeletedFiles]], and of those still counted in the round: with
    * votes in selection, which its galleries and results read.
    */
  def roundDeletedFileCounts(roundId: Long)(implicit session: DBSession = AutoSession): (Int, Int) =
    sql"""SELECT COUNT(*), COALESCE(SUM(${hasVotes(sqls"selection", roundId)}), 0) FROM images i
          WHERE i.deleted_at IS NOT NULL AND ${inRound(roundId)}"""
      .map(rs => rs.int(1) -> rs.int(2))
      .single()
      .getOrElse((0, 0))

  /** `... WHERE` of the category's members (`m`), in their (category_id, page_id) index's
    * order, of the country if there is one (in its category too, by the same index); with
    * `images`, joined to their images as that alias for their monument ids, which the
    * images' (page_id, monument_id) index (V66) has: neither reads an image row.
    */
  private def categoryMembers(categoryId: Long, images: Option[SQLSyntax], country: Option[CountryFilter] = None)
      : SQLSyntax = {
    val members = images.fold(sqls"category_members m WHERE m.category_id = $categoryId") { r =>
      sqls"""category_members m STRAIGHT_JOIN images $r FORCE INDEX ($PageMonumentIndex) ON $r.page_id = m.page_id
             WHERE m.category_id = $categoryId"""
    }
    country.fold(members)(c => sqls"$members AND ${ofCountry(c, sqls"m")}")
  }

  /** The images' (page_id, monument_id) index (V66). */
  private val PageMonumentIndex = sqls"images_page_monument_idx"

  /** `FROM ... WHERE` of the list's files marked deleted (`i`), found from the few of them
    * (V64's index); not for all a category's files.
    */
  private def deletedFiles(list: FileList): SQLSyntax =
    list match {
      case FileList.Category(categoryId, _, country) =>
        val inCountry = country.fold(sqls"")(c => sqls"AND ${ofCountry(c, sqls"i")}")
        sqls"""FROM images i STRAIGHT_JOIN category_members cm ON cm.category_id = $categoryId AND cm.page_id = i.page_id
               WHERE i.deleted_at IS NOT NULL $inCountry"""
      case FileList.RoundDeleted(roundId) =>
        sqls"FROM images i WHERE i.deleted_at IS NOT NULL AND ${inRound(roundId)}"
    }

  /** The file of `alias` (its page_id) is of the country: one of its files. */
  private def ofCountry(country: CountryFilter, alias: SQLSyntax): SQLSyntax =
    if (country.pageIds.isEmpty) sqls"FALSE" else sqls"$alias.page_id IN (${country.pageIds})"

  /** The region of the monument id of `images`' file ([[ImageDbNew.regionOf]]). */
  private def regionOf(images: SQLSyntax): SQLSyntax =
    SQLSyntax.createUnsafely(ImageDbNew.regionOf(s"${images.value}.monument_id"))

  /** `AND` the file of `images` is in the region, if there is one. */
  private def inRegion(images: Option[SQLSyntax], region: Option[String]): SQLSyntax =
    (images, region) match {
      case (Some(alias), Some(id)) => sqls"AND ${regionOf(alias)} = $id"
      case _                       => sqls""
    }

  /** The file `i` has votes in the round, counted or moved aside. */
  private def inRound(roundId: Long): SQLSyntax =
    sqls"(${hasVotes(sqls"selection", roundId)} OR ${hasVotes(sqls"selection_deleted", roundId)})"

  private def hasVotes(table: SQLSyntax, roundId: Long): SQLSyntax =
    sqls"EXISTS (SELECT 1 FROM $table v WHERE v.round_id = $roundId AND v.page_id = i.page_id)"

  private val LatestDeletedFirst = sqls"i.deleted_at DESC, i.page_id"

  private def storedFile(rs: WrappedResultSet): StoredFile =
    StoredFile(ImageJdbc(i)(rs), rs.zonedDateTimeOpt("file_deleted_at"))

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
      order = Seq("rate" -> -1),
      rated = rated,
      limit = Some(Limit(pageSize = Some(pageSize), offset = Some(offset)))
    ).list()

}
