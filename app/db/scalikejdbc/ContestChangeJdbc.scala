package db.scalikejdbc

import play.api.libs.json.{JsObject, Json}
import scalikejdbc._

import java.time.Instant

/** One entry of the contest change log (table `contest_change`, V109; CA8): what root did to a
  * contest or a series.
  *
  * @param contestId the contest; None for a row about the series itself (its creation, renaming,
  *                  retirement)
  * @param seriesId  the contest's series at the time (assigned to, moved to, unassigned from)
  * @param kind      one of [[ContestChangeJdbc.Kinds]]
  * @param batchId   the bulk assignment it belongs to (the id of the batch's first row)
  * @param detail    old and new values, as JSON
  */
final case class ContestChange(
    id: Long,
    contestId: Option[Long],
    seriesId: Option[Long],
    actorId: Long,
    kind: String,
    batchId: Option[Long],
    detail: JsObject,
    comment: Option[String],
    createdAt: Instant
)

/** The `contest_change` table (V109). History for root to read; no rule reads it except "Undo
  * batch", which reverses a batch's assignments. Times from the database clock.
  */
object ContestChangeJdbc {

  val Created = "created"
  val Identity = "identity"
  val SeriesAssigned = "series_assigned"
  val SeriesUnassigned = "series_unassigned"
  val SeriesChanged = "series_changed"
  val SplitOut = "split_out"
  val SplitIn = "split_in"
  val SplitUndone = "split_undone"
  val Retired = "retired"
  val Unretired = "unretired"

  /** The kinds the check allows (V109); the split kinds are written by CY-4 only. */
  val Kinds: Seq[String] =
    Seq(Created, Identity, SeriesAssigned, SeriesUnassigned, SeriesChanged, SplitOut, SplitIn, SplitUndone, Retired, Unretired)

  val CommentMaxLength = 500

  private val columns =
    sqls"id, contest_id, series_id, actor_id, kind, batch_id, detail, comment, ${DbTime.epoch("created_at")}"

  private def extract(rs: WrappedResultSet): ContestChange =
    ContestChange(
      id = rs.long("id"),
      contestId = rs.longOpt("contest_id"),
      seriesId = rs.longOpt("series_id"),
      actorId = rs.long("actor_id"),
      kind = rs.string("kind"),
      batchId = rs.longOpt("batch_id"),
      detail = rs.stringOpt("detail").flatMap(d => scala.util.Try(Json.parse(d).as[JsObject]).toOption).getOrElse(Json.obj()),
      comment = rs.stringOpt("comment"),
      createdAt = DbTime.instant(rs, "created_at")
    )

  /** A new entry; its id. The comment is trimmed (blank: none) and cut to the column. */
  def create(
      contestId: Option[Long],
      seriesId: Option[Long],
      actorId: Long,
      kind: String,
      batchId: Option[Long] = None,
      detail: JsObject = Json.obj(),
      comment: Option[String] = None
  )(implicit session: DBSession): Long = {
    require(Kinds.contains(kind), "not a contest change kind")
    val text = comment.map(_.trim).filter(_.nonEmpty).map(_.take(CommentMaxLength))
    val json = Option.when(detail.fields.nonEmpty)(Json.stringify(detail))
    sql"""INSERT INTO contest_change (contest_id, series_id, actor_id, kind, batch_id, detail, comment, created_at)
          VALUES ($contestId, $seriesId, $actorId, $kind, $batchId, $json, $text, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()
  }

  /** Makes the entry the first of its own batch (batch_id = its id): a bulk assignment's first row. */
  def startBatch(id: Long)(implicit session: DBSession): Unit = {
    sql"UPDATE contest_change SET batch_id = $id WHERE id = $id".update.apply()
    ()
  }

  def findByContest(contestId: Long, limit: Int)(implicit session: DBSession = AutoSession): Seq[ContestChange] =
    sql"SELECT $columns FROM contest_change WHERE contest_id = $contestId ORDER BY id DESC LIMIT $limit"
      .map(extract).list.apply()

  def findBySeries(seriesId: Long, limit: Int)(implicit session: DBSession = AutoSession): Seq[ContestChange] =
    sql"SELECT $columns FROM contest_change WHERE series_id = $seriesId ORDER BY id DESC LIMIT $limit"
      .map(extract).list.apply()

  def findByBatch(batchId: Long)(implicit session: DBSession = AutoSession): Seq[ContestChange] =
    sql"SELECT $columns FROM contest_change WHERE batch_id = $batchId ORDER BY id".map(extract).list.apply()

  /** The latest change of each of the contests (its id), for an undo to tell what changed since. */
  def latestOf(contestIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, ContestChange] =
    if (contestIds.isEmpty) Map.empty
    else
      sql"""SELECT $columns FROM contest_change WHERE id IN (
              SELECT MAX(id) FROM contest_change WHERE contest_id IN (${contestIds.distinct}) GROUP BY contest_id)"""
        .map(extract).list.apply().flatMap(c => c.contestId.map(_ -> c)).toMap

  /** A bulk assignment: its id, who confirmed it and when, how many contests it assigned, and the
    * batch that undid it, if any.
    */
  final case class Batch(id: Long, actorId: Long, createdAt: Instant, contests: Int, undoneBy: Option[Long])

  /** The latest bulk assignments, the newest first, at most `limit`: the batches of
    * `series_assigned` rows, and of each the undo's batch (its rows' `undoOf`).
    */
  def recentBatches(limit: Int)(implicit session: DBSession = AutoSession): Seq[Batch] = {
    val batches = sql"""SELECT batch_id, MIN(actor_id), UNIX_TIMESTAMP(MIN(created_at)), COUNT(*) FROM contest_change
          WHERE batch_id IS NOT NULL AND kind = $SeriesAssigned GROUP BY batch_id ORDER BY batch_id DESC LIMIT $limit"""
      .map(rs => (rs.long(1), rs.long(2), DbTime.fromEpochSeconds(rs.bigDecimal(3)), rs.int(4))).list.apply()
    val undone = undoneBatches(batches.map(_._1))
    batches.map { case (id, actor, at, n) => Batch(id, actor, at, n, undone.get(id)) }
  }

  /** Of the batches, those undone: batch -> the undo's batch. */
  def undoneBatches(batchIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, Long] =
    if (batchIds.isEmpty) Map.empty
    else
      sql"""SELECT CAST(JSON_VALUE(detail, '$$.undoOf') AS UNSIGNED) AS undo_of, MIN(batch_id) FROM contest_change
            WHERE batch_id IS NOT NULL AND kind = $SeriesUnassigned AND detail IS NOT NULL
              AND JSON_VALUE(detail, '$$.undoOf') IN (${batchIds.map(_.toString)})
            GROUP BY undo_of""".map(rs => rs.long(1) -> rs.long(2)).list.apply().toMap
}
