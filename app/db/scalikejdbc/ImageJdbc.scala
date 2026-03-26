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
          mime = imageInfo.mime
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
    mime = rs.stringOpt(c.mime)
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
        i.mime
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
          column.mime -> sqls.?
        )
    }.batch(batchParams: _*).apply()
  }

  def update(image: Image): Unit =
    updateById(image.pageId).withAttributes(
      "title" -> image.title,
      "url" -> image.url,
      "pageUrl" -> image.pageUrl,
      "width" -> image.width,
      "height" -> image.height,
      "monumentId" -> image.monumentId,
      "description" -> image.description,
      "size" -> image.size,
      "mime" -> image.mime
    )

  /** @param pageId
    * @param width
    * @param height
    */
  def updateResolution(pageId: Long, width: Int, height: Int): Unit =
    updateById(pageId).withAttributes("width" -> width, "height" -> height)

  def updateMonumentId(pageId: Long, monumentId: String): Unit =
    updateById(pageId).withAttributes("monumentId" -> monumentId)

  def deleteImage(pageId: Long): Unit = deleteById(pageId)

  def findByFilename(filename: String)(implicit session: DBSession = AutoSession): Option[Image] =
    findBy(sqls.eq(column.title, s"File:$filename"))

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
  FROM images i
  JOIN selection s ON i.page_id = s.page_id
  WHERE
  s.jury_id = $userId
  AND s.round_id = $roundId
  GROUP BY rate""".map(rs => rs.int(1) -> rs.int(2)).list().toMap

  def roundsStat(contestId: Long, limit: Int): Seq[(Long, Int)] =
    sql"""SELECT r.id, count(DISTINCT(s.page_id))
          FROM rounds r
  JOIN selection s ON r.id = s.round_id
  WHERE
  r.contest_id = $contestId
  GROUP BY r.id LIMIT $limit
      """.map(rs => (rs.long(1), rs.int(2))).list()

  def byUserImageWithRating(
      userId: Long,
      roundId: Long,
      rate: Option[Int] = None,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0,
      startPageId: Option[Long] = None
  ): Seq[ImageWithRating] = withSQL {
    select
      .from[Image](ImageJdbc as i)
      .innerJoin(SelectionJdbc as s)
      .on(i.pageId, s.pageId)
      .where
      .eq(s.juryId, userId)
      .and
      .eq(s.roundId, roundId)
      .and(rate.map(r => sqls.eq(s.rate, r)))
      .and(startPageId.map(id => sqls.ge(i.pageId, id)))
      .orderBy(s.rate.desc, i.pageId.asc)
      .limit(pageSize)
      .offset(offset)
  }.map(rs => (ImageJdbc(i)(rs), SelectionJdbc(SelectionJdbc.s)(rs))).list().map { case (i, s) =>
    ImageWithRating(i, Seq(s))
  }

  def imageRank(pageId: Long, sql: String) = {
    sql"""SELECT rank
            FROM (SELECT @rownum :=@rownum + 1 'rank', page_id
            FROM (SELECT @rownum := 0) r, ($sql) t) t2
            WHERE page_id = $pageId;"""

  }

  def byUserImageWithRatingRanked(
      userId: Long,
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0
  )(implicit session: DBSession = AutoSession): Seq[ImageWithRating] =
    sql"""SELECT ranked.rn AS rank, ${i.result.*}, ${s1.result.*}
    FROM images i
    JOIN (
      SELECT s.*,
             ROW_NUMBER() OVER (ORDER BY s.rate DESC) AS rn
      FROM selection s
      WHERE s.jury_id = $userId AND s.round_id = $roundId
    ) AS ranked ON i.page_id = ranked.page_id
    JOIN selection AS s1 ON s1.page_id = ranked.page_id
                        AND s1.jury_id = $userId
                        AND s1.round_id = $roundId
    ORDER BY rank ASC
    LIMIT $pageSize
    OFFSET $offset"""
      .map(rs => (rs.int(1), ImageJdbc(i)(rs), SelectionJdbc(s1)(rs)))
      .list()
      .map { case (rank, i, s) =>
        ImageWithRating(i, Seq(s), rank = Some(rank))
      }

  def byUserImageRangeRanked(
      userId: Long,
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0
  )(implicit session: DBSession = AutoSession): Seq[ImageWithRating] =
    sql"""WITH ranked AS (
            SELECT s.*,
                   ROW_NUMBER() OVER (ORDER BY s.rate DESC) AS rank1,
                   ROW_NUMBER() OVER (ORDER BY s.rate DESC) AS rank2
            FROM selection s
            WHERE s.jury_id = $userId AND s.round_id = $roundId
          )
          SELECT ranked.rank1, ranked.rank2, ${i.result.*}, ${s1.result.*}
          FROM images i
          JOIN ranked ON i.page_id = ranked.page_id
          JOIN selection AS s1 ON s1.page_id = ranked.page_id
                              AND s1.jury_id = $userId
                              AND s1.round_id = $roundId
          ORDER BY rank1 ASC
          LIMIT $pageSize
          OFFSET $offset"""
      .map(rs => (rs.int(1), rs.int(2), ImageJdbc(i)(rs), SelectionJdbc(s1)(rs)))
      .list()
      .map { case (rank1, rank2, i, s) =>
        ImageWithRating(i, Seq(s), rank = Some(rank1), rank2 = Some(rank2))
      }

  def findImageWithRating: Seq[ImageWithRating] = withSQL {
    select
      .from(ImageJdbc as i)
      .innerJoin(SelectionJdbc as s)
      .on(i.pageId, s.pageId)
  }.map(rs => (ImageJdbc(i)(rs), SelectionJdbc(SelectionJdbc.s)(rs))).list().map { case (i, s) =>
    ImageWithRating(i, Seq(s))
  }

  def byUserImageWithCriteriaRating(userId: Long, roundId: Long): Seq[ImageWithRating] = withSQL {
    select(SQLSyntax.sum(c.rate), SQLSyntax.count(c.rate), i.result.*, s.result.*)
      .from(ImageJdbc as i)
      .innerJoin(SelectionJdbc as s)
      .on(i.pageId, s.pageId)
      .leftJoin(CriteriaRate as c)
      .on(s.id, c.selection)
      .where
      .eq(s.juryId, userId)
      .and
      .eq(s.roundId, roundId)
      .groupBy(s.id)
  }.map { rs =>
    (
      ImageJdbc(i)(rs),
      SelectionJdbc(s)(rs),
      rs.intOpt(1).getOrElse(0),
      rs.intOpt(2).getOrElse(0)
    )
  }.list()
    .map { case (img, selection, sum, criterias) =>
      if (criterias > 0)
        ImageWithRating(img, Seq(selection.copy(rate = sum)), criterias)
      else
        ImageWithRating(img, Seq(selection))
    }

  def byRating(rate: Int, roundId: Long): Seq[ImageWithRating] = withSQL {
    select
      .from(ImageJdbc as i)
      .innerJoin(SelectionJdbc as s)
      .on(i.pageId, s.pageId)
      .where
      .eq(s.rate, rate)
      .and
      .eq(s.roundId, roundId)
  }.map { rs =>
    (ImageJdbc(i)(rs), SelectionJdbc(s)(rs))
  }.list()
    .map { case (img, sel) =>
      ImageWithRating(img, Seq(sel))
    }

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

  def byRatingMerged(rate: Int, roundId: Long): Seq[ImageWithRating] = {
    val raw = ImageJdbc.byRating(rate, roundId)
    val merged = raw
      .groupBy(_.pageId)
      .view
      .mapValues(iws => new ImageWithRating(iws.head.image, iws.map(_.selection.head)))
    merged.values.toSeq
  }

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

  def byRoundSummed(
      roundId: Long,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0,
      startPageId: Option[Long] = None
  ): Seq[ImageWithRating] =
    SelectionQuery(
      roundId = Some(roundId),
      grouped = true,
      order = Map("rate" -> -1),
      limit =
        Some(Limit(pageSize = Some(pageSize), offset = Some(offset), startPageId = startPageId))
    ).list()

  def byRoundAndRateSummed(
      roundId: Long,
      rate: Int,
      pageSize: Int = Int.MaxValue,
      offset: Int = 0
  ): Seq[ImageWithRating] =
    SelectionQuery(
      roundId = Some(roundId),
      rate = Some(rate),
      grouped = true,
      order = Map("rate" -> -1),
      limit = Some(Limit(pageSize = Some(pageSize), offset = Some(offset)))
    ).list()
}
