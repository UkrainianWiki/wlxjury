package db.scalikejdbc.rewrite

import db.scalikejdbc.{ImageJdbc, RoundImageJdbc, VoteSql}
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.{AverageRule, BinaryOrder, ImageWithRating, Vote}
import scalikejdbc._

/** All the jurors' list of a round's files read from `round_image`'s counts (P2b,
  * docs/plans/2026-11-02-round-images-two-step.md §8 and §17): one row per file, no GROUP BY
  * of the round's votes. It gives what [[SelectionQuery]]'s grouped and file-list queries give
  * for `asUserId` 0 (`controllers.RoundList`): the same files, order, aggregates
  * (`ImageWithRating` with the rate sum, `countFromDb` = assigned, approvals, rejections),
  * counts, rank counts, positions and region counts (`controllers.GroupedListParitySpec`,
  * `RoundImageReadSpec`).
  *
  * '''Exact at a clean, unchanged generation.''' The counts are kept by dirty marks and a
  * background recompute (P2a, [[db.scalikejdbc.RoundCounts]]): a juror's vote marks its file in
  * `round_image_dirty`, a bulk writer bumps `round.counts_gen`. Each query here overlays the
  * marked files' counts read from their votes (`dv`, the files in `round_image_dirty`, usually a
  * few) on the stored ones, in the same statement. At READ COMMITTED a statement reads one
  * snapshot, and in a committed state with no bulk write after the last full recount a file's
  * stored counts equal its votes or the file is marked (a vote marks its file in its own
  * transaction; the recompute writes the counts and clears the marks it read in one): the
  * numbers are then the votes' exactly. A statement here doesn't check that itself: a bulk write
  * (a distribution chunk, `moveVotesBack`, the repair job's sync) changes votes without marks,
  * and one that commits before the statement's snapshot leaves stored counts behind. The caller
  * proves it around each statement (`controllers.RoundList.fromCounts`: the generation clean
  * before the request's first statement and unchanged after each), and reads the votes when it
  * can't. Exact consumers (the CSV, a top-N) read the votes regardless.
  *
  * Stopped jurors' votes count, as in the vote source (neither joins `round_user`). A rate is
  * never NULL (V91), so the Unrated filter `assigned > voted` is the vote source's `s.rate = 0`;
  * the overlay's COALESCEs are for a marked file without votes (its LEFT JOIN).
  *
  * @param order      the list's ORDER BY over the counts, translated from the vote source's
  *                   ([[RoundImageQuery.of]])
  * @param filter     the vote filter as a condition on the counts (None: every file)
  * @param withPageId a file listed whatever the filter: the large view's
  */
final case class RoundImageQuery(
    roundId: Long,
    order: Seq[(String, Int)],
    filter: Option[String] = None,
    withPageId: Option[Long] = None,
    regions: Set[String] = Set.empty,
    subRegions: Boolean = false,
    limit: Option[Limit] = None
) {

  import RoundImageQuery._

  private val i = ImageJdbc.syntax("i")

  private def raw(sql: String): SQLSyntax = SQLSyntax.createUnsafely(sql)

  /** A single sub-region (adm1) filter, which the monument table has (as [[SelectionQuery]]). */
  private def subRegionFilter: Boolean = subRegions && regions.size == 1 && regions.head.length > 2

  /** The region filter needs the monument table: several regions, or a single sub-region. */
  private def monumentJoin: Boolean = regions.size > 1 || subRegionFilter

  /** The round's files `in` it, with the counts of the files marked dirty read from their votes
    * (`dv`), and the monument table for the region filters that need it.
    */
  private def from: SQLSyntax =
    sqls"""FROM round_image ri
           LEFT JOIN (SELECT d.page_id, COUNT(s.page_id) AS assigned, COALESCE(SUM(${VoteSql.voted()}), 0) AS voted,
                             COALESCE(SUM(${VoteSql.positive()}), 0) AS approvals,
                             COALESCE(SUM(${VoteSql.rejected()}), 0) AS rejections,
                             COALESCE(SUM(s.rate), 0) AS rate_sum
                      FROM round_image_dirty d
                      LEFT JOIN vote s ON s.round_id = d.round_id AND s.page_id = d.page_id
                      WHERE d.round_id = $roundId
                      GROUP BY d.page_id) dv ON dv.page_id = ri.page_id""" +
      (if (monumentJoin) sqls" JOIN monument m ON m.id = ri.monument_id" else sqls"")

  /** The round's files with a vote (`assigned > 0`, as the vote source lists a file with a vote
    * row), in the list's filter (or the large view's file) and region.
    */
  private def where(withFilter: Boolean = true): SQLSyntax = {
    val voteFilter = filter.filter(_ => withFilter).map { cond =>
      withPageId.fold(raw(s"($cond)"))(id => sqls"(${raw(cond)} OR ri.page_id = $id)")
    }
    val region = regions.headOption.map { _ =>
      if (subRegionFilter) sqls.in(raw("m.adm1"), regions.toSeq)
      else if (regions.size > 1) sqls.in(raw("m.adm0"), regions.toSeq)
      else if (subRegions) sqls"ri.monument_id LIKE ${regions.head + "%"}"
      else sqls"ri.region = ${regions.head}"
    }
    val conditions = Seq(
      Some(sqls"ri.round_id = $roundId"),
      Some(sqls"ri.status = ${RoundImageJdbc.Status.In}"),
      Some(raw(s"$Assigned > 0")),
      voteFilter,
      region
    ).flatten
    sqls"WHERE ${SQLSyntax.join(conditions, sqls"AND")}"
  }

  private def orderSql(columns: Seq[(String, Int)]): SQLSyntax =
    if (columns.isEmpty) sqls""
    else raw(" ORDER BY " + columns.map { case (c, dir) => c + (if (dir < 0) " DESC" else " ASC") }.mkString(", "))

  private def limitSql: SQLSyntax =
    limit.fold(sqls"")(l => sqls" LIMIT ${l.pageSize.getOrElse(0)} OFFSET ${l.offset.getOrElse(0)}")

  /** A page of the list's files with their aggregates, in its order: the page's rows sorted and
    * paged on `round_image` alone (narrow rows), then only the page's images read and put back
    * in order by the order columns (o0, o1, ...), as [[SelectionQuery]]'s deferred pages do.
    */
  def list()(implicit session: DBSession = AutoSession): Seq[ImageWithRating] = {
    val keys = order.zipWithIndex.map { case ((expr, dir), n) => (expr, s"o$n", dir) }
    val keyColumns = raw(keys.map { case (expr, alias, _) => s", $expr AS $alias" }.mkString)
    val outerOrder = orderSql(keys.map { case (_, alias, dir) => s"k.$alias" -> dir })
    val aggregates = raw(s"$RateSum AS rs, $Assigned AS assigned, $Approvals AS approvals, $Rejections AS rejections")
    sql"""SELECT k.rs, k.assigned, k.approvals, k.rejections, ${i.result.*}
          FROM (SELECT ri.page_id AS pid, $aggregates $keyColumns $from ${where()} ${orderSql(order)} $limitSql) k
          STRAIGHT_JOIN images i ON i.page_id = k.pid $outerOrder"""
      .map { rs =>
        val image = ImageJdbc(i)(rs)
        ImageWithRating(image, Seq(Vote(image.pageId, juryId = 0, roundId = 0, rate = rs.int("rs"))),
          rs.int("assigned"), approvalsFromDb = Some(rs.int("approvals")), rejectionsFromDb = Some(rs.int("rejections")))
      }
      .list()
  }

  /** The page ids of a page of the list, in its order: a file list's, whose votes are then read
    * by page id ([[SelectionQuery.withVotes]]).
    */
  def pageIds()(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT ri.page_id $from ${where()} ${orderSql(order)} $limitSql".map(_.long(1)).list()

  /** The list's files. */
  def count()(implicit session: DBSession = AutoSession): Int =
    sql"SELECT COUNT(*) $from ${where()}".map(_.int(1)).single().getOrElse(0)

  /** The number of the list's files per value of `columns` (the vote source's vocabulary,
    * translated: [[RoundImageQuery.translate]]), in the columns' order: the list's rank counts.
    */
  def keyCounts(columns: Seq[(String, Int)])(implicit session: DBSession = AutoSession): Seq[(Seq[Long], Int)] = {
    val translated = columns.map { case (c, dir) =>
      translate(c).getOrElse(throw new IllegalArgumentException(s"no count column for $c")) -> dir
    }
    val keys = translated.indices.map(n => s"k$n")
    val select = raw(translated.zip(keys).map { case ((expr, _), k) => s"$expr AS $k" }.mkString(", "))
    sql"""SELECT $select, COUNT(*) $from ${where()}
          GROUP BY ${raw(keys.mkString(", "))} ${orderSql(keys.zip(translated.map(_._2)))}"""
      .map(rs => (keys.indices.map(n => rs.long(n + 1)), rs.int(keys.size + 1)))
      .list()
  }

  /** The file's rank (from 1) among the list's files, 0 if it isn't one: a window over the
    * round's narrow rows.
    */
  def imageRank(pageId: Long)(implicit session: DBSession = AutoSession): Int =
    sql"""SELECT t.ranked FROM (
            SELECT ri.page_id AS pid, ROW_NUMBER() OVER (${orderSql(order)}) AS ranked $from ${where()}) t
          WHERE t.pid = $pageId"""
      .map(_.int(1)).single().getOrElse(0)

  /** The number of the round's files in each top-level region that the filter lists (0 for a
    * region it lists none of), without the region filter or the large view's file; files without
    * a region left out (as [[SelectionQuery.regionCounts]]).
    */
  def regionCounts()(implicit session: DBSession = AutoSession): Map[String, Int] = {
    val all = copy(regions = Set.empty, withPageId = None)
    val hit = raw(filter.fold("1")(cond => s"($cond)"))
    sql"""SELECT ri.region, SUM($hit) ${all.from} ${all.where(withFilter = false)} AND ri.region IS NOT NULL
          GROUP BY ri.region ORDER BY NULL"""
      .map(rs => rs.string(1) -> rs.int(2)).list().toMap
  }
}

object RoundImageQuery {

  /** A file's counts: the stored ones, or those read from the votes of a file marked dirty. */
  val RateSum = "COALESCE(dv.rate_sum, ri.rate_sum)"
  val Assigned = "COALESCE(dv.assigned, ri.assigned)"
  val Voted = "COALESCE(dv.voted, ri.voted)"
  val Approvals = "COALESCE(dv.approvals, ri.approvals)"
  val Rejections = "COALESCE(dv.rejections, ri.rejections)"

  /** The average rules' terms over the counts. */
  val CountTerms: AverageRule.Terms = AverageRule.Terms(RateSum, Approvals, Assigned)

  /** The vote source's order and rank columns (over a file's grouped vote rows `s`:
    * `controllers.ListOrder`, [[BinaryOrder]], `ImageWithRating.RankKey`) and the same over the
    * counts: one vocabulary for either source.
    */
  private val vocabulary: Map[String, String] = Map(
    "rate"                  -> RateSum,
    "sum(s.rate)"           -> RateSum,
    BinaryOrder.Approvals   -> Approvals,
    BinaryOrder.Rejections  -> Rejections,
    "s.monument_id"         -> "ri.monument_id",
    "s.page_id"             -> "ri.page_id"
  ) ++ AverageRule.all.flatMap(rule => rule.keySql(AverageRule.VoteTerms).zip(rule.keySql(CountTerms)))

  def translate(column: String): Option[String] = vocabulary.get(column)

  /** The vote filter as a condition on a file's counts, as the vote source's grouped lists
    * filter files (decision 3 of docs/history/2026-11-01-binary-aggregate-votes.md: a file with a
    * matching vote): Unrated, a vote yet to come; Rated (or a binary round's selected), an
    * approval; a binary round's rejected, a rejection. None for a filter the counts can't tell
    * (one rate of a rated round): the vote source reads it.
    */
  private def fileFilter(rate: Option[Int], rated: Option[Boolean], binary: Boolean): Option[Option[String]] =
    (rate, rated) match {
      case (None, None)                => Some(None)
      case (Some(0), None)             => Some(Some(s"$Assigned > $Voted"))
      case (None, Some(false))         => Some(Some(s"$Assigned > $Voted"))
      case (None, Some(true))          => Some(Some(s"$Approvals > 0"))
      case (Some(1), None) if binary   => Some(Some(s"$Approvals > 0"))
      case (Some(-1), None) if binary  => Some(Some(s"$Rejections > 0"))
      case _                           => None
    }

  /** The list of all the jurors' votes `q` (no juror, no keyset) over the counts, if it can be:
    * its filter and its order columns, and `rankColumns` (the list's rank key, whose counts it
    * reads), all in the counts' vocabulary. None: the vote source reads it.
    */
  def of(q: SelectionQuery, binary: Boolean, rankColumns: Seq[String] = Nil): Option[RoundImageQuery] = {
    val order = q.order.map { case (c, dir) => translate(c).map(_ -> dir) }
    for {
      roundId <- q.roundId if q.userId.isEmpty && q.keyset.isEmpty && (q.grouped || q.groupWithDetails)
      filter <- fileFilter(q.rate, q.rated, binary)
      if order.forall(_.isDefined) && rankColumns.forall(translate(_).isDefined)
    } yield RoundImageQuery(roundId, order.flatten, filter, q.withPageId, q.regions, q.subRegions, q.limit)
  }
}
