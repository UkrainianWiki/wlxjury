package db.scalikejdbc.rewrite

import db.scalikejdbc.{ImageJdbc, SelectionJdbc}
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import scalikejdbc.{DBSession, _}

object ImageDbNew extends SQLSyntaxSupport[Image] {

  implicit def session: DBSession = autoSession

  override val tableName = "images"

  private val i = ImageJdbc.syntax("i")
  private val s = SelectionJdbc.s
  private val s1 = SelectionJdbc.syntax("s1")

  /** A file's top-level region: its monument id's prefix, as monument.adm0 is (MonumentJdbc)
    * and as a gallery's single-region filter matches it; NULL without a monument id.
    *
    * @param monumentId the monument id column, e.g. `s.monument_id`
    */
  def regionOf(monumentId: String): String = s"NULLIF(LEFT(SUBSTRING_INDEX($monumentId, '-', 1), 3), '')"

  case class Limit(
      pageSize: Option[Int] = None,
      offset: Option[Int] = None
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
      order: Seq[(String, Int)] = Seq.empty,
      subRegions: Boolean = false,
      // a file listed whatever the vote filter: the large view's, which a vote may have filtered out
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
      // byRegion stats, or a single sub-region (adm1, longer than a region's two characters)
      val needsMonumentJoin = regions.size > 1 || byRegion || subRegionFilter
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
      * The inner query reads selection alone: every selection row has its image (foreign
      * key FK_selection_page_id, V58), so it pages the same rows the plain query and
      * [[imageRank]] see when joining images.
      *
      * The inner query also returns the ORDER BY columns (as o0, o1, ...; from the same
      * index), and the page's rows are put back in order by those: an ORDER BY on k
      * alone sorts k's few rows before the join, while one on s.* columns copies the
      * joined rows, images' TEXT columns included, into an on-disk temporary table.
      */
    private def deferredJoinPage(columnsStr: String): SQLSyntax = {
      val keys = order.zipWithIndex.map { case ((name, dir), n) => (name, s"o$n", dir) }
      val keyColumns = SQLSyntax.createUnsafely(keys.map { case (name, alias, _) => s", $name as $alias" }.mkString)
      val innerOrder = SQLSyntax.createUnsafely(orderBy())
      val outerOrder = SQLSyntax.createUnsafely(orderSql(keys.map { case (_, alias, dir) => s"k.$alias" -> dir }))
      val page = SQLSyntax.createUnsafely(limitSql())
      val columns = SQLSyntax.createUnsafely(columnsStr)
      sqls"$columns from (select s.id$keyColumns from selection s ${where()} $innerOrder $page) k" +
        sqls" STRAIGHT_JOIN selection s on s.id = k.id" +
        sqls" STRAIGHT_JOIN images i on i.page_id = s.page_id $outerOrder"
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

    /** The regions of the round's (or the juror's) images, by id, without counts (the
      * contest's [[services.regions.RegionScheme]] names them). A top-level region is the
      * monument id's prefix, as monument.adm0 is (MonumentJdbc) and as the single-region
      * filter matches it (`s.monument_id like 'XX%'`): read from the selection rows
      * alone, so the regions show even when the monument list was never loaded. Only
      * sub-regions (adm1) need the monument table.
      */
    def byRegionStat()(implicit session: DBSession = autoSession): Map[String, Option[Int]] = {
      val (region, monumentJoin) =
        if (subRegions) (s"m.$regionColumn", "JOIN monument m ON m.id = s.monument_id")
        else (regionOf("s.monument_id"), "")
      val map = SQL(s"""SELECT DISTINCT $region
                       |FROM selection s
                       |$monumentJoin
                       |WHERE $region IS NOT NULL
                       |  ${userId.fold("") { id => s"AND s.jury_id = $id" }}
                       |  AND s.round_id = ${roundId.get}""".stripMargin)
        .map(rs => rs.string(1) -> Option.empty[Int])
        .list()
        .toMap
      map
    }

    /** The number of the query's images in each region, without its region filter: every
      * region of the juror's (or the round's) images, those the rate filter leaves out
      * counted 0, so a region's count is what its link lists. Files without a monument id
      * are left out. The vote filter's extra file (`withPageId`) isn't counted.
      *
      * A juror's: one scan of the juror's rows on (jury_id, round_id, rate, monument_id,
      * page_id) (V48), the juror having one row per image. The whole round's: its images
      * once each, from selection's (round_id, page_id, rate) index, joined to the images'
      * (page_id, monument_id) index (V66) for their regions.
      */
    def regionCounts()(implicit session: DBSession = autoSession): Map[String, Int] = {
      val conditions = rate.toSeq.map(r => s"s.rate = ${r.toInt}") ++
        rated.toSeq.map(r => if (r) "s.rate > 0" else "s.rate = 0")
      val hit = if (conditions.isEmpty) "1" else conditions.mkString("(", " AND ", ")")
      val sql = userId match {
        case Some(juror) =>
          s"""SELECT ${regionOf("s.monument_id")} AS region, SUM($hit) FROM selection s
             |WHERE s.jury_id = ${juror.toLong} AND s.round_id = ${roundId.get.toLong}
             |GROUP BY region ORDER BY NULL""".stripMargin
        case None =>
          s"""SELECT ${regionOf("i.monument_id")} AS region, SUM(p.hit)
             |FROM (SELECT s.page_id, MAX($hit) AS hit FROM selection s WHERE s.round_id = ${roundId.get.toLong}
             |      GROUP BY s.page_id) p
             |JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id
             |GROUP BY region ORDER BY NULL""".stripMargin
      }
      SQL(sql).map(rs => rs.stringOpt(1) -> rs.int(2)).list().collect { case (Some(region), n) => region -> n }.toMap
    }

    /** A juror's files in the round by top-level region (None: no monument id) and rate, all
      * of them whatever the query's filters: one scan of the juror's rows on (jury_id,
      * round_id, rate, monument_id, page_id) (V48), the juror having one row per file. The
      * tabs' counts, the region nav's and the list's total all come from it
      * ([[controllers.JurorCounts]]).
      */
    def regionRateCounts()(implicit session: DBSession = autoSession): Map[(Option[String], Int), Int] = {
      require(userId.isDefined && roundId.isDefined, "a juror's counts in a round")
      SQL(s"""SELECT ${regionOf("s.monument_id")} AS region, s.rate, COUNT(*) FROM selection s
             |WHERE s.jury_id = ${userId.get.toLong} AND s.round_id = ${roundId.get.toLong}
             |GROUP BY region, s.rate ORDER BY NULL""".stripMargin)
        .map(rs => (rs.stringOpt(1), rs.int(2)) -> rs.int(3))
        .list()
        .toMap
    }

    /** A single sub-region (adm1) filter, which the monument table has. */
    private def subRegionFilter: Boolean = subRegions && regions.size == 1 && regions.head.length > 2

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

      // the votes the list has; and the file `withPageId` too, whatever its vote
      val votes: Seq[SQLSyntax] = Seq(
        rate.map(r => sqls"s.rate = $r"),
        rated.map(r => if (r) sqls"s.rate > 0" else sqls"s.rate = 0")
      ).flatten
      val voteFilter = votes.headOption.map { _ =>
        val cond = SQLSyntax.join(votes, sqls"and")
        withPageId.fold(cond)(pageId => sqls"($cond or s.page_id = $pageId)")
      }

      val conditions: Seq[SQLSyntax] = Seq(
        userId.map(id => sqls"s.jury_id = $id"),
        roundId.map(id => sqls"s.round_id = $id"),
        voteFilter,
        regions.headOption.map { _ =>
          if (subRegionFilter) {
            sqls.in(col, regions.toSeq)
          } else if (regions.size > 1) {
            sqls.in(SQLSyntax.createUnsafely("m.adm0"), regions.toSeq)
          } else {
            // selection's denormalized copy of images.monument_id (V48), so a juror's
            // region gallery filters on the selection rows alone: the LIKE reads the index
            // range, regionOf makes it the region the counts and the nav have (`7` isn't
            // `71-...`, a three-character prefix such as WLE's free text lists its files)
            val region = regions.head
            val likeParam = region + "%"
            if (subRegions) sqls"s.monument_id like $likeParam"
            else sqls"s.monument_id like $likeParam and ${SQLSyntax.createUnsafely(regionOf("s.monument_id"))} = $region"
          }
        }
      ).flatten

      conditions.headOption.fold(sqls"") { _ =>
        sqls" where ${SQLSyntax.join(conditions, sqls"and")}"
      }
    }

    def orderBy(fields: Seq[(String, Int)] = order): String = orderSql(fields)

    private def orderSql(fields: Seq[(String, Int)]): String = {
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
