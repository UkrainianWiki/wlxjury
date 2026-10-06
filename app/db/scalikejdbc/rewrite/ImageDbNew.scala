package db.scalikejdbc.rewrite

import _root_.play.api.i18n.Messages
import db.scalikejdbc.{ImageJdbc, SelectionJdbc}
import org.intracer.wmua.{Image, ImageWithRating, Region, Selection}
import org.scalawiki.wlx.dto.Country.Ukraine
import scalikejdbc.{DBSession, _}

object ImageDbNew extends SQLSyntaxSupport[Image] {

  implicit def session: DBSession = autoSession

  override val tableName = "images"

  private val i = ImageJdbc.syntax("i")
  private val s = SelectionJdbc.s
  private val s1 = SelectionJdbc.syntax("s1")

  case class Limit(
      pageSize: Option[Int] = None,
      offset: Option[Int] = None,
      startPageId: Option[Long] = None
  )

  case class SelectionQuery(
      userId: Option[Long] = None,
      roundId: Option[Long] = None,
      rate: Option[Int] = None,
      rated: Option[Boolean] = None,
      regions: Set[String] = Set.empty,
      limit: Option[Limit] = None,
      grouped: Boolean = false,
      groupWithDetails: Boolean = false,
      order: Map[String, Int] = Map.empty,
      subRegions: Boolean = false,
      withPageId: Option[Long] = None,
      driver: String = "mysql"
  ) {

    private val reader: WrappedResultSet => ImageWithRating =
      if (grouped) Readers.groupedReader else Readers.rowReader

    private val regionColumn = if (subRegions) "adm1" else "adm0"

    def query(
        count: Boolean = false,
        idOnly: Boolean = false,
        noLimit: Boolean = false,
        byRegion: Boolean = false,
        ranked: Boolean = false
    ): SQLSyntax = {

      val columnsStr: String =
        "select  " +
          (if (count || idOnly) {
             "i.page_id as pi_on_i" +
               (if (ranked)
                  s", ROW_NUMBER() over (${orderBy()}) ranked"
                else "") +
               (if (grouped)
                  ", sum(s.rate) as rate, count(s.rate) as rate_count"
                else "")
           } else {
             if (byRegion)
               s"m.$regionColumn, count(DISTINCT i.page_id)"
             else if (!grouped)
               sqls"""${i.result.*}, ${s.result.*} """.value
             else
               sqls"""sum(s.rate) as rate, count(s.rate) as rate_count, ${i.result.*} """.value
           })

      val groupByStr = if (byRegion) {
        s" group by m.$regionColumn"
      } else if (grouped) {
        " group by s.page_id"
      } else ""

      // SQL clause order: FROM/JOIN → WHERE → GROUP BY → ORDER BY
      // Monument join needed when: multiple regions (IN clause on m.adm0),
      // byRegion stats, or a single full-region-code (length > 2) that references m.adm0/m.adm1
      val needsMonumentJoin = regions.size > 1 || byRegion || regions.headOption.exists(_.length > 2)
      val structureStr =
        columnsStr +
          join(monuments = needsMonumentJoin)

      val mainSql =
        sqls"${SQLSyntax.createUnsafely(structureStr)} ${where(count)} ${SQLSyntax.createUnsafely(groupByStr + (if (!(count || byRegion)) orderBy() else ""))}"

      // A juror has at most one row per image in a round (unique page_id, jury_id,
      // round_id): with both fixed, the rows are the images.
      val oneRowPerImage = userId.isDefined && roundId.isDefined

      // Without a monument join every condition is on selection's own columns, so the
      // count needs no join to images. A region filter alone keeps the old row count
      // unless the rows are one per image anyway (a juror's in a round, or grouped).
      val countOnSelection =
        count && !byRegion && !needsMonumentJoin && (regions.isEmpty || oneRowPerImage || grouped)

      if (countOnSelection) {
        val countExpr = SQLSyntax.createUnsafely(
          if (oneRowPerImage) "COUNT(*)" else "COUNT(DISTINCT s.page_id)"
        )
        sqls"select $countExpr from selection s ${where()}"
      } else if (count) {
        sqls"select count(t.pi_on_i) from ($mainSql) t"
      } else if (noLimit || byRegion) {
        mainSql
      } else if (!idOnly && !grouped && limit.isDefined && !needsMonumentJoin) {
        deferredJoinPage(columnsStr)
      } else {
        sqls"$mainSql ${SQLSyntax.createUnsafely(limitSql())}"
      }
    }

    /** One page of per-selection rows (a juror's gallery) as a deferred join: the inner
      * query sorts and pages selection ids alone, which an index on (jury_id, round_id,
      * ...) covers, since every secondary index includes the primary key; only the page's
      * rows are then read in full. Sorting the whole juror's rows with every column, as
      * the plain query does, costs a filesort of full rows: on MariaDB 10.6 the
      * mixed-direction ORDER BY (rate DESC, monument_id ASC, ...) can't be read from the
      * ascending index.
      *
      * The inner query joins images too (a primary key lookup, from the index alone), so
      * it pages only the rows that have an image, as the plain query and [[imageRank]]
      * do: a selection row whose image was deleted would otherwise shift the offsets.
      */
    private def deferredJoinPage(columnsStr: String): SQLSyntax = {
      val order = SQLSyntax.createUnsafely(orderBy())
      val page = SQLSyntax.createUnsafely(limitSql())
      val columns = SQLSyntax.createUnsafely(columnsStr)
      sqls"$columns from (select s.id from selection s" +
        sqls" STRAIGHT_JOIN images i on i.page_id = s.page_id ${where()} $order $page) k" +
        sqls" STRAIGHT_JOIN selection s on s.id = k.id" +
        sqls" STRAIGHT_JOIN images i on i.page_id = s.page_id $order"
    }

    def list()(implicit session: DBSession = autoSession): Seq[ImageWithRating] = {
      postProcessor(sql"${query()}".map(reader).list())
    }

    def count()(implicit session: DBSession = autoSession): Int = {
      sql"${query(count = true)}".map(_.int(1)).single().getOrElse(0)
    }

    def imageRank(pageId: Long)(implicit session: DBSession = autoSession): Int = {
      val inner = query(ranked = true, idOnly = true, noLimit = true)
      sql"${imageRankSql(pageId, inner)}".map(_.int(1)).single().getOrElse(0)
    }

    /** The regions of the round's (or the juror's) images. A top-level region is the
      * monument id's prefix, as monument.adm0 is (MonumentJdbc) and as the single-region
      * filter matches it (`s.monument_id like 'XX%'`): read from the selection rows
      * alone, so the regions show even when the monument list was never loaded. Only
      * sub-regions (adm1) need the monument table.
      */
    def byRegionStat()(implicit messages: Messages, session: DBSession = autoSession): Seq[Region] = {
      val (region, monumentJoin) =
        if (subRegions) (s"m.$regionColumn", "JOIN monument m ON m.id = s.monument_id")
        else ("NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, '-', 1), 3), '')", "")
      val map = SQL(s"""SELECT DISTINCT $region
                       |FROM selection s
                       |$monumentJoin
                       |WHERE $region IS NOT NULL
                       |  ${userId.fold("") { id => s"AND s.jury_id = $id" }}
                       |  AND s.round_id = ${roundId.get}""".stripMargin)
        .map(rs => rs.string(1) -> None)
        .list()
        .toMap
      regions(map, subRegions)
    }

    def regions(byRegion: Map[String, Option[Int]], subRegions: Boolean = false)(implicit
        messages: Messages
    ): Seq[Region] = {
      val regions = byRegion.keys
        .filterNot(_ == null)
        .map { id =>
          val adm = Ukraine.byMonumentId(id)
          val name =
            if (messages.isDefinedAt(id)) messages(id)
            else adm.map(_.name).getOrElse("Unknown")
          Region(id, name, byRegion(id))
        }
        .toSeq
        .sortBy(_.id)

      if (subRegions) {
        val kyivPictures =
          regions.filter(_.id.startsWith("80-")).map(_.count.getOrElse(0)).sum
        val withoutKyivRegions = regions.filterNot(_.id.startsWith("80-"))
        val unsorted =
          withoutKyivRegions ++ Seq(Region("80", messages("80"), Some(kyivPictures)))
        unsorted.sortBy(_.name)
      } else {
        regions.sortBy(_.id)
      }
    }

    private val imagesJoinSelection =
      """ from selection s
        |STRAIGHT_JOIN images i
        |on i.page_id = s.page_id""".stripMargin

    def join(monuments: Boolean): String = {
      imagesJoinSelection + (if (monuments)
                               "\n join monument m on i.monument_id = m.id"
                             else "")
    }

    def where(count: Boolean = false): SQLSyntax = {
      val col = SQLSyntax.createUnsafely(s"m.$regionColumn")

      val conditions: Seq[SQLSyntax] = Seq(
        userId.map(id => sqls"s.jury_id = $id"),
        roundId.map(id => sqls"s.round_id = $id"),
        rate.map(r => sqls"s.rate = $r"),
        rated.map { r =>
          val ratedCond: SQLSyntax = if (r) sqls"s.rate > 0" else sqls"s.rate = 0"
          withPageId.fold(ratedCond) { pageId =>
            sqls"($ratedCond or s.page_id = $pageId)"
          }
        },
        regions.headOption.map { _ =>
          if (regions.headOption.exists(_.length > 2)) {
            sqls.in(col, regions.toSeq)
          } else if (regions.size > 1) {
            sqls.in(SQLSyntax.createUnsafely("m.adm0"), regions.toSeq)
          } else {
            // selection's denormalized copy of images.monument_id (V48), so a juror's
            // region gallery filters on the selection rows alone
            val likeParam = regions.head + "%"
            sqls"s.monument_id like $likeParam"
          }
        }
      ).flatten

      conditions.headOption.fold(sqls"") { _ =>
        sqls" where ${SQLSyntax.join(conditions, sqls"and")}"
      }
    }

    def orderBy(fields: Map[String, Int] = order): String = {
      val dirMap = Map(1 -> "asc", -1 -> "desc")

      fields.headOption
        .map { _ =>
          " order by " + fields
            .map { case (name, dir) =>
              name + " " + dirMap(dir)
            }
            .mkString(", ")
        }
        .getOrElse("")
    }

    def limitSql(): String = limit
      .map { l =>
        s" LIMIT ${l.pageSize.getOrElse(0)} OFFSET ${l.offset.getOrElse(0)}"
      }
      .getOrElse("")

    val postProcessor: Seq[ImageWithRating] => Seq[ImageWithRating] =
      if (groupWithDetails) groupedWithDetails else identity

    private def groupedWithDetails(images: Seq[ImageWithRating]): Seq[ImageWithRating] =
      images
        .groupBy(_.image.pageId)
        .map { case (id, imagesWithId) =>
          new ImageWithRating(
            imagesWithId.head.image,
            imagesWithId.flatMap(_.selection)
          )
        }
        .toSeq
        .sortBy(-_.selection.map(_.rate).filter(_ > 0).sum)

    def imageRankSql(pageId: Long, innerSql: SQLSyntax): SQLSyntax = {
      if (driver == "mysql") {
        sqls"SELECT ranked, pi_on_i FROM ($innerSql) t WHERE pi_on_i = $pageId"
      } else {
        sqls"SELECT rank FROM (SELECT rownum as rank, t.pi_on_i as page_id FROM ($innerSql) t) t2 WHERE page_id = $pageId"
      }
    }

    object Readers {

      def rowReader(rs: WrappedResultSet): ImageWithRating =
        ImageWithRating(
          image = ImageJdbc(i)(rs),
          selection = Seq(SelectionJdbc(s)(rs))
        )

      def groupedReader(rs: WrappedResultSet): ImageWithRating = {
        val image = ImageJdbc(i)(rs)
        val sum = rs.intOpt(1).getOrElse(0)
        val count = rs.intOpt(2).getOrElse(0)
        ImageWithRating(
          image,
          selection = Seq(Selection(image.pageId, juryId = 0, roundId = 0, rate = sum)),
          count
        )
      }

    }

  }

}
