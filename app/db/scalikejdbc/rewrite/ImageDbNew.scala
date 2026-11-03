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

      if (count && regions.isEmpty && !byRegion) {
        val countExpr = SQLSyntax.createUnsafely("COUNT(DISTINCT s.page_id)")
        sqls"select $countExpr from selection s ${where()}"
      } else if (count) {
        sqls"select count(t.pi_on_i) from ($mainSql) t"
      } else if (noLimit || byRegion) {
        mainSql
      } else {
        sqls"$mainSql ${SQLSyntax.createUnsafely(limitSql())}"
      }
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

    def byRegionStat()(implicit messages: Messages, session: DBSession = autoSession): Seq[Region] = {
      val map = SQL(s"""SELECT DISTINCT m.$regionColumn
                       |FROM selection s
                       |JOIN monument m ON m.id = s.monument_id
                       |WHERE m.$regionColumn IS NOT NULL
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
            val likeParam = regions.head + "%"
            sqls"i.monument_id like $likeParam"
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
