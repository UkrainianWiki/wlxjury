package db.scalikejdbc.rewrite

import db.scalikejdbc.{ImageJdbc, VoteJdbc, VoteSql}
import org.intracer.wmua.{BinaryOrder, Image, ImageWithRating, Vote}
import scalikejdbc.{DBSession, _}

object ImageDbNew extends SQLSyntaxSupport[Image] {

  implicit def session: DBSession = autoSession

  override val tableName = "images"

  private val i = ImageJdbc.syntax("i")
  private val s = VoteJdbc.s
  private val s1 = VoteJdbc.syntax("s1")

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

  /** A file's place in a list's order, its values of the order's columns
    * ([[SelectionQuery.orderKey]]): a query with it lists the files ordered before the file
    * (`before`, nearest first) or the file and those after it, in the list's order.
    */
  case class Keyset(values: Seq[Option[Any]], before: Boolean)

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
      // only the files before, or from, a file's place in the order (the large view's neighbours)
      keyset: Option[Keyset] = None,
      // the vote filter picks files, those with a matching vote, and their counts and order
      // still use all their votes (HAVING on the grouped query): all the jurors' lists
      // (docs/history/2026-11-01-binary-aggregate-votes.md, decision 3); else it picks vote rows
      filesByVotes: Boolean = false,
      // a grouped query also counts each file's approvals and rejections apart: a yes/no
      // round's tally (ImageWithRating.approvals), which no rated round's page reads
      tally: Boolean = false,
      // a rated round's votes: a grouped row's rate is the sum of its grades (a ✗, -1, as 0:
      // VoteSql.grade), and the rated filter is any vote, a ✗ included (VoteSql.voted); a yes/no
      // round's (false) keeps the sum of its rates and its approvals as the rated
      grades: Boolean = false,
      // only the files with a comment in this contest (the toggle "Only with comments"): any
      // round's, any author's, as the file's large view shows them (CommentJdbc.findBySubjectAndContest)
      commentsIn: Option[Long] = None,
      // with [[commentsIn]], only the comments written in this round (the comments report's scope
      // "this round", controllers.CommentScope): a file is kept if one of them is on it
      commentsRound: Option[Long] = None,
      // with [[commentsIn]], only the comments by this account (`comment.user_id`; the comments
      // report's filter by its author, controllers.RoundList.commenter): a file is kept if one
      // of its comments in the scope is theirs
      commentsBy: Option[Long] = None,
      driver: String = "mysql"
  ) {

    /** The conditions on a comment `c` (after its contest's) of [[commentsRound]] and [[commentsBy]]. */
    private def commentScope: String = SelectionQuery.inRound(commentsRound, commentsBy)

    /** A grouped row's rate: the sum of the file's rates, or of a rated round's grades. */
    private def rateSum: String = SelectionQuery.rateSum(grades)

    private val reader: WrappedResultSet => ImageWithRating =
      if (grouped) Readers.groupedReader(tally) else Readers.rowReader

    private val regionColumn = if (subRegions) "adm1" else "adm0"

    /** The order rows are read in: the list's, reversed for the files before a file (nearest first). */
    private val sortOrder: Seq[(String, Int)] =
      if (keyset.exists(_.before)) order.map { case (column, dir) => (column, -dir) } else order

    def query(
        count: Boolean = false,
        idOnly: Boolean = false,
        noLimit: Boolean = false,
        byRegion: Boolean = false,
        ranked: Boolean = false,
        keyColumns: Seq[String] = Nil
    ): SQLSyntax = {

      val needsMonumentJoin = monumentJoin || byRegion

      // page ids alone (a file list's page, the CSV's keys, a file's rank): from vote,
      // without the images, unless the monument table's region needs them
      val idsOnSelection = idOnly && !count && !needsMonumentJoin

      val columnsStr: String =
        "select  " +
          (if (count || idOnly) {
             (if (idsOnSelection) "s.page_id as pi_on_i" else "i.page_id as pi_on_i") +
               keyColumns.zipWithIndex.map { case (expr, n) => s", $expr as k$n" }.mkString +
               (if (ranked)
                  s", ROW_NUMBER() over (${orderBy()}) ranked"
                else "") +
               (if (grouped)
                  s", $rateSum as rate, count(*) as rate_count"
                else "")
           } else {
             if (byRegion)
               s"m.$regionColumn, count(DISTINCT i.page_id)"
             else if (!grouped)
               sqls"""${i.result.*}, ${VoteJdbc.resultAll(s)} """.value
             else if (tally)
               sqls"""${SQLSyntax.createUnsafely(rateSum)} as rate, count(*) as rate_count,
                       ${SQLSyntax.createUnsafely(tallyColumns)}, ${i.result.*} """.value
             else
               sqls"""${SQLSyntax.createUnsafely(rateSum)} as rate, count(*) as rate_count, ${i.result.*} """.value
           })

      val groupByStr = if (byRegion) {
        s" group by m.$regionColumn"
      } else if (grouped) {
        " group by s.page_id"
      } else ""
      val havingSql = if (grouped && !byRegion) having else sqls""

      // SQL clause order: FROM/JOIN → WHERE → GROUP BY → ORDER BY
      val structureStr =
        columnsStr +
          (if (idsOnSelection) " from vote s" else join(monuments = needsMonumentJoin))

      val mainSql =
        sqls"${SQLSyntax.createUnsafely(structureStr)} ${where(count, withPage = idsOnSelection)} ${SQLSyntax.createUnsafely(groupByStr)} $havingSql ${SQLSyntax.createUnsafely(if (!(count || byRegion)) orderBy() else "")}"

      // A juror has at most one row per image in a round (unique page_id, jury_id,
      // round_id): with both fixed, the rows are the images.
      val oneRowPerImage = userId.isDefined && roundId.isDefined

      // Without a monument join every condition is on vote's own columns, so the
      // count needs no join to images. A region filter alone keeps the old row count
      // unless the rows are one per image anyway (a juror's in a round, or grouped).
      val countOnSelection =
        count && !byRegion && !needsMonumentJoin && (regions.isEmpty || oneRowPerImage || grouped)

      if (countOnSelection) {
        val countExpr = SQLSyntax.createUnsafely(
          if (oneRowPerImage) "COUNT(*)" else "COUNT(DISTINCT s.page_id)"
        )
        // the files with a matching vote: the same number with the filter on the rows
        sqls"select $countExpr from vote s ${where(votes = true, withPage = true)}"
      } else if (count) {
        sqls"select count(t.pi_on_i) from ($mainSql) t"
      } else if (noLimit || byRegion) {
        mainSql
      } else if (!idOnly && !grouped && limit.isDefined && !needsMonumentJoin) {
        deferredJoinPage(columnsStr)
      } else if (!idOnly && grouped && limit.isDefined) {
        deferredGroupedPage()
      } else {
        sqls"$mainSql ${SQLSyntax.createUnsafely(limitSql())}"
      }
    }

    /** One page of per-vote rows (a juror's gallery) as a deferred join: the inner
      * query sorts and pages vote ids alone, which an index on (jury_id, round_id,
      * ...) covers, since every secondary index includes the primary key; only the page's
      * rows are then read in full. Sorting the whole juror's rows with every column, as
      * the plain query does, costs a filesort of full rows: on MariaDB 10.6 the
      * mixed-direction ORDER BY (rate DESC, monument_id ASC, ...) can't be read from the
      * ascending index.
      *
      * The inner query reads vote alone, its rows with a page id: those have their image
      * (foreign key vote_page_id_fk, V58), so it pages the same rows the plain query and
      * [[imageRank]] see when joining images.
      *
      * The inner query also returns the ORDER BY columns (as o0, o1, ...; from the same
      * index), and the page's rows are put back in order by those: an ORDER BY on k
      * alone sorts k's few rows before the join, while one on s.* columns copies the
      * joined rows, images' TEXT columns included, into an on-disk temporary table.
      */
    private def deferredJoinPage(columnsStr: String): SQLSyntax = {
      val keys = sortOrder.zipWithIndex.map { case ((name, dir), n) => (name, s"o$n", dir) }
      val keyColumns = SQLSyntax.createUnsafely(keys.map { case (name, alias, _) => s", $name as $alias" }.mkString)
      val innerOrder = SQLSyntax.createUnsafely(orderBy())
      val outerOrder = SQLSyntax.createUnsafely(orderSql(keys.map { case (_, alias, dir) => s"k.$alias" -> dir }))
      val page = SQLSyntax.createUnsafely(limitSql())
      val columns = SQLSyntax.createUnsafely(columnsStr)
      sqls"$columns from (select s.id$keyColumns from vote s ${where(withPage = true)} $innerOrder $page) k" +
        sqls" STRAIGHT_JOIN vote s on s.id = k.id" +
        sqls" STRAIGHT_JOIN images i on i.page_id = s.page_id $outerOrder"
    }

    /** One page of grouped rows (all the jurors' votes on each file: the by-rate gallery, the
      * large view's files around one) as a deferred join: the inner query groups, orders and
      * pages the files' votes on vote alone (joined to the images only for the monument
      * table's regions), with their aggregates and the ORDER BY columns (o0, o1, ...); only
      * the page's images are then read, and the page put back in order by those columns.
      * Grouping the round's votes with every image column, as one query did before B12,
      * carried them all through the temporary table and its sort.
      *
      * A vote row with a page id has its image (vote_page_id_fk, V58); one without
      * (`page_id` is nullable) is left out, as the join left it out, so it pages the files the
      * one query paged. The aggregates come first, in the grouped reader's order.
      */
    private def deferredGroupedPage(): SQLSyntax = {
      // the select's alias `rate` isn't a column of the inner select list: its sum
      val keys = sortOrder.zipWithIndex.map { case ((name, dir), n) =>
        (if (name == "rate") rateSum else name, s"o$n", dir)
      }
      val aggregates = Seq("rate", "rate_count") ++ (if (tally) Seq("approvals", "rejections") else Nil)
      val inner = SQLSyntax.createUnsafely(
        s"select s.page_id as pid, $rateSum as rate, count(*) as rate_count" +
          (if (tally) s", $tallyColumns" else "") +
          keys.map { case (expr, alias, _) => s", $expr as $alias" }.mkString +
          (if (monumentJoin) join(monuments = true) else " from vote s")
      )
      val groupBy = SQLSyntax.createUnsafely(" group by s.page_id")
      val page = SQLSyntax.createUnsafely(s"${orderBy()} ${limitSql()}")
      val outer = SQLSyntax.createUnsafely(s"select ${aggregates.map("k." + _).mkString(", ")}, ")
      val outerOrder = SQLSyntax.createUnsafely(orderSql(keys.map { case (_, alias, dir) => s"k.$alias" -> dir }))
      sqls"$outer ${i.result.*} from ($inner ${where(withPage = true)} $groupBy $having $page) k" +
        sqls" STRAIGHT_JOIN images i on i.page_id = k.pid $outerOrder"
    }

    def list()(implicit session: DBSession = autoSession): Seq[ImageWithRating] =
      if (groupWithDetails) withVotes(sql"${files.query(idOnly = true)}".map(_.long(1)).list())
      else sql"${query()}".map(reader).list()

    /** The files of a list with their votes (a file list, CSV), as the list's grouped query
      * orders and pages them: one row per file.
      */
    private def files: SelectionQuery = copy(grouped = true, groupWithDetails = false)

    /** All the list's files' page ids with their values of `columns` (aggregates of a file's
      * votes, over `s`), in its order, from one read: the CSV ranks its files by these and
      * reads their votes a chunk at a time ([[withVotes]]).
      */
    def pageKeys(columns: Seq[String])(implicit session: DBSession = autoSession): Seq[(Long, Seq[Long])] =
      sql"${files.query(idOnly = true, noLimit = true, keyColumns = columns)}"
        .map(rs => rs.long(1) -> columns.indices.map(n => rs.long(n + 2)))
        .list()

    /** The files of `pageIds` with their votes the list has (its juror's, or all the jurors':
      * all their votes with [[filesByVotes]], else those of its vote filter), in the order of
      * `pageIds`: two queries by page id, whatever the round's size.
      */
    def withVotes(pageIds: Seq[Long])(implicit session: DBSession = autoSession): Seq[ImageWithRating] =
      if (pageIds.isEmpty) Nil
      else {
        val images = sql"select ${i.result.*} from images i where i.page_id in ($pageIds)"
          .map(ImageJdbc(i)(_)).list().map(image => image.pageId -> image).toMap
        val conditions = copy(regions = Set.empty, keyset = None, withPageId = None, commentsIn = None).where()
        val inPage = if (conditions.value.isEmpty) sqls"where" else sqls"$conditions and"
        val votes = sql"select ${VoteJdbc.resultAll(s)} from vote s $inPage s.page_id in ($pageIds) order by s.id"
          .map(VoteJdbc(s)(_)).list().groupBy(_.pageId)
        pageIds.flatMap(id => images.get(id).map(ImageWithRating(_, votes.getOrElse(id, Nil))))
      }

    /** The number of the list's files per value of `columns`, aggregates of a file's votes
      * (over `s`), in the order of the columns' directions: what a page's ranks are counted
      * from among the whole list ([[org.intracer.wmua.ImageWithRating.Ranks]]).
      */
    def keyCounts(columns: Seq[(String, Int)])(implicit session: DBSession = autoSession): Seq[(Seq[Long], Int)] = {
      val keys = columns.indices.map(n => s"k$n")
      val select = columns.zip(keys).map { case ((expr, _), k) => s"$expr as $k" }.mkString(", ")
      val from = if (monumentJoin) join(monuments = true) else " from vote s"
      val inner = sqls"${SQLSyntax.createUnsafely(s"select $select $from")} ${where(withPage = true)} group by s.page_id $having"
      val outer = SQLSyntax.createUnsafely(
        s"select ${keys.mkString(", ")}, count(*) from (") +
        inner +
        SQLSyntax.createUnsafely(s") t group by ${keys.mkString(", ")}${orderSql(keys.zip(columns.map(_._2)))}")
      sql"$outer".map(rs => (keys.indices.map(n => rs.long(n + 1)), rs.int(keys.size + 1))).list()
    }

    /** The list's files: a file list's or CSV's too, one per file whatever its votes. */
    def count()(implicit session: DBSession = autoSession): Int = {
      val counted = if (groupWithDetails) files else this
      sql"${counted.query(count = true)}".map(_.int(1)).single().getOrElse(0)
    }

    /** The number of comments on the list's files that the comments filter keeps them by
      * ([[commentsIn]], in [[commentsRound]] if given): the comments report's total, of all its
      * pages. 0 without the filter. The list's page ids, unordered, joined to their comments on
      * comment's (contest_id, room) index (V101): a file's comments read once. An IN on the ids
      * instead had MariaDB 10.6 read the list's files again for each of the contest's comments:
      * on production's copy, round 693 (321 files, 1,542 comments) without V101 18.8 s, the join
      * 1.5 s; with V101 15-22 ms and 9-12 ms.
      */
    def commentCount()(implicit session: DBSession = autoSession): Int =
      commentsIn.fold(0) { contestId =>
        val ids = copy(order = Nil, limit = None, withPageId = None, keyset = None).query(idOnly = true, noLimit = true)
        val inRound = SQLSyntax.createUnsafely(commentScope)
        sql"select count(*) from ($ids) t straight_join comment c on c.contest_id = $contestId and c.room = t.pi_on_i$inRound"
          .map(_.int(1)).single().getOrElse(0)
      }

    def imageRank(pageId: Long)(implicit session: DBSession = autoSession): Int = {
      val inner = query(ranked = true, idOnly = true, noLimit = true)
      sql"${imageRankSql(pageId, inner)}".map(_.int(1)).single().getOrElse(0)
    }

    /** Whether [[orderKey]] and [[countBefore]] place a file: a juror's list in a round, on
      * the vote rows alone (no grouping, no monument table).
      */
    def keyed: Boolean =
      userId.isDefined && roundId.isDefined && !grouped && !groupWithDetails && order.nonEmpty && !monumentJoin

    /** The file's values of the list's order columns, from the juror's row of it in the round
      * (in the list's region), whatever the vote and comments filters: None if the juror has no
      * such file.
      */
    def orderKey(pageId: Long)(implicit session: DBSession = autoSession): Option[Keyset] = {
      require(keyed, "a juror's list in a round")
      val columns = SQLSyntax.createUnsafely(order.map(_._1).mkString(", "))
      val conditions = copy(rate = None, rated = None, withPageId = None, commentsIn = None).where()
      sql"select $columns from vote s $conditions and s.page_id = $pageId"
        .map(rs => Keyset((1 to order.size).map(i => Option(rs.any(i))), before = true))
        .single()
    }

    /** The number of the list's files ordered before the file at `key`: its index in the list,
      * or where it would be if the filter leaves it out. One count over the juror's rows.
      */
    def countBefore(key: Keyset)(implicit session: DBSession = autoSession): Int =
      sql"select count(*) from vote s ${copy(withPageId = None, keyset = Some(key.copy(before = true))).where(withPage = true)}"
        .map(_.int(1)).single().getOrElse(0)

    /** The rows ordered before a file's values of the order columns: on each column in turn,
      * equal on those before it and before on it (`<` ascending, `>` descending). MariaDB
      * sorts NULL first ascending and last descending, which the conditions follow, and none
      * of them is NULL, so NOT of it is the file and the rows after it.
      */
    private def orderedBefore(values: Seq[Option[Any]]): SQLSyntax = {
      val columns = order.zip(values)
      def column(name: String) = SQLSyntax.createUnsafely(name)
      val terms = columns.indices.map { i =>
        val equal = columns.take(i).map { case ((name, _), value) =>
          value.fold(sqls"${column(name)} is null")(v => sqls"${column(name)} = $v")
        }
        val ((name, dir), value) = columns(i)
        val c = column(name)
        val step = (dir, value) match {
          case (1, Some(v))  => sqls"(${c} is null or ${c} < $v)"
          case (1, None)     => sqls"false"
          case (_, Some(v))  => sqls"(${c} is not null and ${c} > $v)"
          case (_, None)     => sqls"${c} is not null"
        }
        sqls"(${SQLSyntax.join(equal :+ step, sqls"and")})"
      }
      sqls"(${SQLSyntax.join(terms, sqls"or")})"
    }

    /** The regions of the round's (or the juror's) images, by id, without counts (the
      * contest's [[services.regions.RegionScheme]] names them). A top-level region is the
      * monument id's prefix, as monument.adm0 is (MonumentJdbc) and as the single-region
      * filter matches it (`s.monument_id like 'XX%'`): read from the vote rows
      * alone, so the regions show even when the monument list was never loaded. Only
      * sub-regions (adm1) need the monument table. The whole round's: from its files `in` it
      * (round_image, V84: one row per file, its region stored), not its every juror's votes.
      */
    def byRegionStat()(implicit session: DBSession = autoSession): Map[String, Option[Int]] = {
      val sql = userId match {
        case Some(id) =>
          val (region, monumentJoin) =
            if (subRegions) (s"m.$regionColumn", "JOIN monument m ON m.id = s.monument_id")
            else (regionOf("s.monument_id"), "")
          s"""SELECT DISTINCT $region
             |FROM vote s
             |$monumentJoin
             |WHERE $region IS NOT NULL
             |  AND s.jury_id = ${id.toLong}
             |  AND s.round_id = ${roundId.get.toLong}""".stripMargin
        case None =>
          val (region, monumentJoin) =
            if (subRegions) (s"m.$regionColumn", "JOIN monument m ON m.id = ri.monument_id")
            else ("ri.region", "")
          s"""SELECT DISTINCT $region
             |FROM round_image ri
             |$monumentJoin
             |WHERE $region IS NOT NULL
             |  AND ri.round_id = ${roundId.get.toLong} AND ri.status = 'in'""".stripMargin
      }
      SQL(sql)
        .map(rs => rs.string(1) -> Option.empty[Int])
        .list()
        .toMap
    }

    /** The number of the query's images in each region, without its region filter: every
      * region of the juror's (or the round's) images, those the rate filter leaves out
      * counted 0, so a region's count is what its link lists. Files without a monument id
      * are left out. The vote filter's extra file (`withPageId`) isn't counted. With the
      * comments filter ([[commentsIn]]) only commented files count, the others as 0.
      *
      * A juror's: one scan of the juror's rows on (jury_id, round_id, rate, monument_id,
      * page_id) (V48), the juror having one row per image. The whole round's: its images
      * once each, from vote's (round_id, page_id, rate) index, joined to the images'
      * (page_id, monument_id) index (V66) for their regions.
      */
    def regionCounts()(implicit session: DBSession = autoSession): Map[String, Int] = {
      val conditions = rate.toSeq.map(r => s"s.rate = ${r.toInt}") ++
        rated.toSeq.map(ratedCondition(_).value)
      val hit = if (conditions.isEmpty) "1" else conditions.mkString("(", " AND ", ")")
      val sql = userId match {
        case Some(juror) =>
          s"""SELECT ${regionOf("s.monument_id")} AS region, SUM($hit${commentedHit}) FROM vote s
             |${commentedJoin("s.page_id")}
             |WHERE s.jury_id = ${juror.toLong} AND s.round_id = ${roundId.get.toLong}
             |GROUP BY region ORDER BY NULL""".stripMargin
        case None =>
          s"""SELECT ${regionOf("i.monument_id")} AS region, SUM(p.hit${commentedHit})
             |FROM (SELECT s.page_id, MAX($hit) AS hit FROM vote s WHERE s.round_id = ${roundId.get.toLong}
             |      GROUP BY s.page_id) p
             |JOIN images i FORCE INDEX (images_page_monument_idx) ON i.page_id = p.page_id
             |${commentedJoin("p.page_id")}
             |GROUP BY region ORDER BY NULL""".stripMargin
      }
      SQL(sql).map(rs => rs.stringOpt(1) -> rs.int(2)).list().collect { case (Some(region), n) => region -> n }.toMap
    }

    /** A juror's files in the round by top-level region (None: no monument id) and rate, all
      * of them whatever the query's filters: one scan of the juror's rows on (jury_id,
      * round_id, rate, monument_id, page_id) (V48), the juror having one row per file. The
      * tabs' counts, the region nav's and the list's total all come from it
      * ([[controllers.JurorCounts]]). With the comments filter ([[commentsIn]]) each count is of
      * the commented files, 0 for none, every region and rate kept.
      */
    def regionRateCounts()(implicit session: DBSession = autoSession): Map[(Option[String], Int), Int] = {
      require(userId.isDefined && roundId.isDefined, "a juror's counts in a round")
      val count = if (commentsIn.isEmpty) "COUNT(*)" else s"SUM(1${commentedHit})"
      SQL(s"""SELECT ${regionOf("s.monument_id")} AS region, s.rate, $count FROM vote s
             |${commentedJoin("s.page_id")}
             |WHERE s.jury_id = ${userId.get.toLong} AND s.round_id = ${roundId.get.toLong} AND s.page_id IS NOT NULL
             |GROUP BY region, s.rate ORDER BY NULL""".stripMargin)
        .map(rs => (rs.stringOpt(1), rs.int(2)) -> rs.int(3))
        .list()
        .toMap
    }

    /** With the comments filter, the commented files of the contest joined to a file's rows
      * (`pageId`), for the counts that keep every region and rate of the list's files, those
      * without a comment counted 0 ([[commentedHit]]); "" without it.
      */
    private def commentedJoin(pageId: String): String =
      commentsIn.fold("")(contestId =>
        s"LEFT JOIN (SELECT DISTINCT c.room FROM comment c WHERE c.contest_id = ${contestId.toLong}" +
          s"$commentScope) cm ON cm.room = $pageId")

    /** A factor of a count's term: 1 for a commented file, else 0 ([[commentedJoin]]); "" without it. */
    private def commentedHit: String = commentsIn.fold("")(_ => " * (cm.room IS NOT NULL)")

    /** A single sub-region (adm1) filter, which the monument table has. */
    private def subRegionFilter: Boolean = subRegions && regions.size == 1 && regions.head.length > 2

    /** The region filter needs the monument table: several regions (an IN on m.adm0), or a
      * single sub-region (adm1, longer than a region's two characters).
      */
    private def monumentJoin: Boolean = regions.size > 1 || subRegionFilter

    private val imagesJoinSelection =
      """ from vote s
        |STRAIGHT_JOIN images i
        |on i.page_id = s.page_id""".stripMargin

    def join(monuments: Boolean): String = {
      imagesJoinSelection + (if (monuments)
                               "\n join monument m on i.monument_id = m.id"
                             else "")
    }

    /** The vote filter's condition, over a vote row or (`perFile`) over a file's grouped rows:
      * one matching vote; and the file `withPageId` too, whatever its votes.
      */
    private def voteFilter(perFile: Boolean): Option[SQLSyntax] = {
      def matching(cond: SQLSyntax) = if (perFile) sqls"sum($cond) > 0" else cond
      val votes: Seq[SQLSyntax] = Seq(
        rate.map(r => matching(sqls"s.rate = $r")),
        rated.map(r => matching(ratedCondition(r)))
      ).flatten
      votes.headOption.map { _ =>
        val cond = SQLSyntax.join(votes, sqls"and")
        withPageId.fold(cond)(pageId => sqls"($cond or s.page_id = $pageId)")
      }
    }

    /** The comments filter's condition on a vote row ([[commentsIn]]): a file with a comment in
      * the contest, a semi-join on comment's (contest_id, room) index (V101); and the file
      * `withPageId` too, whatever its comments, as [[voteFilter]] keeps it. That file has comments
      * unless its URL was typed with the toggle on, so the OR, which keeps MariaDB from the
      * semi-join (a review of 2026-11-13, all the jurors' large view of a round of production's
      * copy: a file's rank 63 ms with it, 2.4 ms without), is added only for a file without them ([[pageWithoutComments]]).
      */
    private def commentsFilter: Option[SQLSyntax] =
      commentsIn.map { contestId =>
        val commented = SelectionQuery.commented(contestId, commentsRound, commentsBy)
        pageWithoutComments.fold(commented)(pageId => sqls"($commented or s.page_id = $pageId)")
      }

    /** The file `withPageId` if it has no comment in the contest ([[commentsIn]]): one read of
      * comment's (contest_id, room) index, once per query.
      */
    private lazy val pageWithoutComments: Option[Long] =
      for {
        contestId <- commentsIn
        pageId    <- withPageId
        if sql"select 1 from comment c where c.contest_id = $contestId and c.room = $pageId${
          SQLSyntax.createUnsafely(commentScope)} limit 1"
          .map(_.int(1)).single().isEmpty
      } yield pageId

    /** The `rated` filter's condition on a vote row: rated, or unrated. Rated is a yes/no
      * round's yes, and a rated round's any vote, stars or a ✗ ([[grades]]).
      */
    private def ratedCondition(rated: Boolean): SQLSyntax =
      if (!rated) VoteSql.unrated() else if (grades) VoteSql.voted() else VoteSql.positive()

    /** A grouped row's tally: its approvals and rejections apart ([[BinaryOrder]]'s columns). */
    private def tallyColumns: String =
      s"${BinaryOrder.Approvals} as approvals, ${BinaryOrder.Rejections} as rejections"

    /** A grouped query's HAVING: with [[filesByVotes]], the files of the vote filter. */
    private def having: SQLSyntax =
      voteFilter(perFile = true).filter(_ => filesByVotes).fold(sqls"")(cond => sqls"having $cond")

    /** @param votes with the vote filter on the rows: without it for [[filesByVotes]], whose
      *              HAVING has it ([[having]])
      * @param withPage only rows with a page id: a query on vote alone, without the join
      *              to images that leaves out the rest (`vote.page_id` is nullable)
      */
    def where(count: Boolean = false, votes: Boolean = !filesByVotes, withPage: Boolean = false): SQLSyntax = {
      val col = SQLSyntax.createUnsafely(s"m.$regionColumn")

      val conditions: Seq[SQLSyntax] = Seq(
        userId.map(id => sqls"s.jury_id = $id"),
        roundId.map(id => sqls"s.round_id = $id"),
        voteFilter(perFile = false).filter(_ => votes),
        keyset.map(k => if (k.before) orderedBefore(k.values) else sqls"not ${orderedBefore(k.values)}"),
        regions.headOption.map { _ =>
          if (subRegionFilter) {
            sqls.in(col, regions.toSeq)
          } else if (regions.size > 1) {
            sqls.in(SQLSyntax.createUnsafely("m.adm0"), regions.toSeq)
          } else {
            // vote's denormalized copy of images.monument_id (V48), so a juror's
            // region gallery filters on the vote rows alone: the LIKE reads the index
            // range, regionOf makes it the region the counts and the nav have (`7` isn't
            // `71-...`, a three-character prefix such as WLE's free text lists its files)
            val region = regions.head
            val likeParam = region + "%"
            if (subRegions) sqls"s.monument_id like $likeParam"
            else sqls"s.monument_id like $likeParam and ${SQLSyntax.createUnsafely(regionOf("s.monument_id"))} = $region"
          }
        },
        commentsFilter,
        Option.when(withPage)(sqls"s.page_id is not null")
      ).flatten

      conditions.headOption.fold(sqls"") { _ =>
        sqls" where ${SQLSyntax.join(conditions, sqls"and")}"
      }
    }

    def orderBy(fields: Seq[(String, Int)] = sortOrder): String = orderSql(fields)

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
          votes = Seq(VoteJdbc(s)(rs))
        )

      /** A grouped row: the file, its rates' sum and its vote rows (`COUNT(*)`, as
        * `round_image.assigned` counts them), and with `tally` its approvals and rejections
        * (columns 3 and 4). A group has a row, and a rate is never NULL (V91).
        */
      def groupedReader(tally: Boolean)(rs: WrappedResultSet): ImageWithRating = {
        val image = ImageJdbc(i)(rs)
        val sum = rs.int(1)
        val count = rs.int(2)
        ImageWithRating(
          image,
          votes = Seq(Vote(image.pageId, juryId = 0, roundId = 0, rate = sum)),
          count,
          approvalsFromDb = Option.when(tally)(rs.int(3)),
          rejectionsFromDb = Option.when(tally)(rs.int(4))
        )
      }

    }

  }

  object SelectionQuery {

    /** The sum of a file's vote rows `s`: of its rates (a yes/no round's approvals less its
      * rejections), or of a rated round's grades, a ✗ as 0 (`grades`). The text names the same
      * column in every list's order and rank vocabulary (`RoundImageQuery.translate`).
      */
    def rateSum(grades: Boolean): String = if (grades) VoteSql.GradeSum else VoteSql.RateSum

    /** A vote row's file has a comment in the contest (in the round `roundId`, by the account
      * `userId`, if given): what [[SelectionQuery.commentsIn]], [[SelectionQuery.commentsRound]]
      * and [[SelectionQuery.commentsBy]] keep.
      */
    def commented(contestId: Long, roundId: Option[Long] = None, userId: Option[Long] = None): SQLSyntax =
      sqls"s.page_id in (select c.room from comment c where c.contest_id = $contestId${
        SQLSyntax.createUnsafely(inRound(roundId, userId))})"

    /** Conditions on a comment `c` (after its contest's): written in the round, by the account
      * (`user_id`), each if given. Both read on the rows comment's (contest_id, room) index
      * (V101) finds: at most a contest's comments (~2,000 on production's copy).
      */
    def inRound(roundId: Option[Long], userId: Option[Long] = None): String =
      roundId.fold("")(r => s" and c.round_id = ${r.toLong}") + userId.fold("")(u => s" and c.user_id = ${u.toLong}")

    /** A file's (a vote row `s`'s) latest comment in the contest, in the round and by the account
      * (`userId`, the comments report's filter by author) if given:
      * its id, which grows as comments are written (their stored times have mixed formats,
      * [[org.intracer.wmua.Comment.parseTime]]); 0 for none, so it is never NULL, as a large
      * view's place in the order needs ([[Keyset]]). An order column ([[controllers.ListOrder.LatestComment]]):
      * a read of comment's (contest_id, room) index (V101) per file.
      */
    def latestComment(contestId: Long, roundId: Option[Long], userId: Option[Long] = None): String =
      s"(SELECT COALESCE(MAX(c.id), 0) FROM comment c WHERE c.contest_id = ${contestId.toLong}" +
        s" AND c.room = s.page_id${inRound(roundId, userId)})"
  }

}
