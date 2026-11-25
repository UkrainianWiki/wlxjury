package db.scalikejdbc

import play.api.libs.json.Json
import scalikejdbc._

import java.time.Instant

/** A confirmed repair of a contest's file list (table `list_repair`, V114; services.FileListRepair).
  *
  * @param oldCategoryId    the contest's list before the repair
  * @param sourceCategoryId the row the files came from: the old list, or the shared row a contest
  *                         already on its own row had its rounds' files left in
  * @param newCategoryId    the contest's own row, its list since
  * @param roundsCounted    the rounds whose files were counted
  * @param filesAdded       how many files the repair put into the own row ([[ListRepairJdbc.addedFiles]])
  * @param listHash         SHA-256 of the own row's sorted page ids just after the repair
  */
final case class ListRepair(
    id: Long,
    contestId: Long,
    oldCategoryId: Option[Long],
    sourceCategoryId: Long,
    newCategoryId: Long,
    roundsCounted: Seq[Long],
    filesAdded: Int,
    listHash: String,
    createdBy: Long,
    createdAt: Instant,
    undoneBy: Option[Long],
    undoneAt: Option[Instant]
) {
  def undone: Boolean = undoneAt.isDefined
}

/** The `list_repair` and `list_repair_file` tables (V114): each repair with the files it added, for
  * its Undo. Times from the database clock.
  */
object ListRepairJdbc {

  private val columns =
    sqls"""id, contest_id, old_category_id, source_category_id, new_category_id, rounds_counted, files_added,
           list_hash, created_by, ${DbTime.epoch("created_at")}, undone_by, ${DbTime.epoch("undone_at")}"""

  private def extract(rs: WrappedResultSet): ListRepair =
    ListRepair(
      id = rs.long("id"),
      contestId = rs.long("contest_id"),
      oldCategoryId = rs.longOpt("old_category_id"),
      sourceCategoryId = rs.long("source_category_id"),
      newCategoryId = rs.long("new_category_id"),
      roundsCounted = scala.util.Try(Json.parse(rs.string("rounds_counted")).as[Seq[Long]]).getOrElse(Nil),
      filesAdded = rs.int("files_added"),
      listHash = rs.string("list_hash"),
      createdBy = rs.long("created_by"),
      createdAt = DbTime.instant(rs, "created_at"),
      undoneBy = rs.longOpt("undone_by"),
      undoneAt = DbTime.instantOpt(rs, "undone_at")
    )

  /** A new repair, its files and outcome recorded next ([[recordAdded]], [[setOutcome]]); its id. */
  def create(contestId: Long, oldCategoryId: Option[Long], sourceCategoryId: Long, newCategoryId: Long,
      roundsCounted: Seq[Long], createdBy: Long)(implicit session: DBSession): Long = {
    val rounds = Json.stringify(Json.toJson(roundsCounted.sorted))
    sql"""INSERT INTO list_repair (contest_id, old_category_id, source_category_id, new_category_id, rounds_counted,
          files_added, list_hash, created_by, created_at)
          VALUES ($contestId, $oldCategoryId, $sourceCategoryId, $newCategoryId, $rounds, 0, '', $createdBy, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()
  }

  /** Records as the repair's added files those of the source row that the rounds show and the own
    * row lacks (services.FileListRepair.Plan.added); how many.
    */
  def recordAdded(repairId: Long, sourceCategoryId: Long, newCategoryId: Long, rounds: Seq[Long])(implicit
      session: DBSession): Int =
    if (rounds.isEmpty) 0
    else
      sql"""INSERT INTO list_repair_file (repair_id, page_id)
            SELECT $repairId, m.page_id FROM category_members m
            WHERE m.category_id = $sourceCategoryId
              AND EXISTS (SELECT 1 FROM round_image ri WHERE ri.page_id = m.page_id AND ri.round_id IN ($rounds))
              AND NOT EXISTS (SELECT 1 FROM category_members w WHERE w.category_id = $newCategoryId AND w.page_id = m.page_id)"""
        .update.apply()

  /** Puts the repair's added files into its own row. */
  def linkAdded(repairId: Long, newCategoryId: Long)(implicit session: DBSession): Unit = {
    sql"""INSERT IGNORE INTO category_members (category_id, page_id)
          SELECT $newCategoryId, page_id FROM list_repair_file WHERE repair_id = $repairId""".update.apply()
    ()
  }

  def setOutcome(repairId: Long, filesAdded: Int, listHash: String)(implicit session: DBSession): Unit = {
    sql"UPDATE list_repair SET files_added = $filesAdded, list_hash = $listHash WHERE id = $repairId".update.apply()
    ()
  }

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[ListRepair] =
    sql"SELECT $columns FROM list_repair WHERE id = $id".map(extract).single.apply()

  /** The repair, its row locked until the transaction ends. */
  def lock(id: Long)(implicit session: DBSession): Option[ListRepair] =
    sql"SELECT $columns FROM list_repair WHERE id = $id FOR UPDATE".map(extract).single.apply()

  /** The contest's repairs, the newest first. */
  def findByContest(contestId: Long)(implicit session: DBSession = AutoSession): Seq[ListRepair] =
    sql"SELECT $columns FROM list_repair WHERE contest_id = $contestId ORDER BY id DESC".map(extract).list.apply()

  /** The latest repairs of every contest, the newest first, at most `limit`. */
  def recent(limit: Int)(implicit session: DBSession = AutoSession): Seq[ListRepair] =
    sql"SELECT $columns FROM list_repair ORDER BY id DESC LIMIT $limit".map(extract).list.apply()

  /** The repairs not undone. */
  def live()(implicit session: DBSession = AutoSession): Seq[ListRepair] =
    sql"SELECT $columns FROM list_repair WHERE undone_at IS NULL ORDER BY id".map(extract).list.apply()

  /** The files the repair added to the own row, by page id. */
  def addedFiles(repairId: Long)(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT page_id FROM list_repair_file WHERE repair_id = $repairId ORDER BY page_id".map(_.long(1)).list.apply()

  def markUndone(id: Long, by: Long)(implicit session: DBSession): Unit = {
    sql"UPDATE list_repair SET undone_at = NOW(3), undone_by = $by WHERE id = $id AND undone_at IS NULL".update.apply()
    ()
  }
}
