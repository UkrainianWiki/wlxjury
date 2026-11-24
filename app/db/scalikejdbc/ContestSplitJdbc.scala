package db.scalikejdbc

import play.api.libs.json.{JsObject, Json}
import scalikejdbc._

import java.time.Instant

/** A split of a reused contest (V113, contest series plan §4.4–§4.5; CY-4): root's confirmed plan,
  * its job, and its Undo.
  */
final case class ContestSplitRow(
    id: Long,
    contestId: Long,
    jobId: Option[Long],
    plan: JsObject,
    planHash: String,
    dumpRef: Option[String],
    batchId: Option[Long],
    createdBy: Long,
    createdAt: Instant,
    undoneAt: Option[Instant],
    undoneBy: Option[Long]
) {
  def undone: Boolean = undoneAt.isDefined
}

/** What a split changed (`contest_split_item`), with the old and new values Undo reads. */
final case class ContestSplitItem(
    id: Long,
    splitId: Long,
    moveIndex: Int,
    kind: String,
    subjectId: Option[Long],
    contestId: Option[Long],
    oldValue: JsObject,
    newValue: JsObject
)

object ContestSplitJdbc {

  object Kind {
    val Series = "series"
    val Contest = "contest"
    val ContestExisting = "contest_existing"
    val Paused = "paused"
    val Membership = "membership"
    val Skipped = "skipped"
    val Round = "round"
    val ExcludeCut = "exclude_cut"
    val Comments = "comments"
    val Jobs = "jobs"
    val CurrentRound = "current_round"
    val Files = "files"
    val All: Seq[String] =
      Seq(Series, Contest, ContestExisting, Paused, Membership, Skipped, Round, ExcludeCut, Comments, Jobs, CurrentRound, Files)
  }

  val DumpRefMaxLength = 255

  private val columns =
    sqls"""id, contest_id, job_id, plan, plan_hash, dump_ref, batch_id, created_by, ${DbTime.epoch("created_at")},
           ${DbTime.epoch("undone_at")}, undone_by"""

  private def json(s: Option[String]): JsObject =
    s.flatMap(t => scala.util.Try(Json.parse(t).as[JsObject]).toOption).getOrElse(Json.obj())

  private def extract(rs: WrappedResultSet): ContestSplitRow =
    ContestSplitRow(
      id = rs.long("id"),
      contestId = rs.long("contest_id"),
      jobId = rs.longOpt("job_id"),
      plan = json(rs.stringOpt("plan")),
      planHash = rs.string("plan_hash"),
      dumpRef = rs.stringOpt("dump_ref"),
      batchId = rs.longOpt("batch_id"),
      createdBy = rs.long("created_by"),
      createdAt = DbTime.instant(rs, "created_at"),
      undoneAt = DbTime.instantOpt(rs, "undone_at"),
      undoneBy = rs.longOpt("undone_by")
    )

  def create(contestId: Long, plan: JsObject, planHash: String, dumpRef: Option[String], createdBy: Long)(implicit
      session: DBSession): Long =
    sql"""INSERT INTO contest_split (contest_id, plan, plan_hash, dump_ref, created_by, created_at)
          VALUES ($contestId, ${Json.stringify(plan)}, $planHash, ${dumpRef.map(_.take(DumpRefMaxLength))}, $createdBy, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()

  def setJob(id: Long, jobId: Long)(implicit session: DBSession): Unit = {
    sql"UPDATE contest_split SET job_id = $jobId WHERE id = $id".update.apply()
    ()
  }

  def setBatch(id: Long, batchId: Long)(implicit session: DBSession): Unit = {
    sql"UPDATE contest_split SET batch_id = $batchId WHERE id = $id AND batch_id IS NULL".update.apply()
    ()
  }

  def setUndone(id: Long, by: Long)(implicit session: DBSession): Int =
    sql"UPDATE contest_split SET undone_at = NOW(3), undone_by = $by WHERE id = $id AND undone_at IS NULL".update.apply()

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[ContestSplitRow] =
    sql"SELECT $columns FROM contest_split WHERE id = $id".map(extract).single.apply()

  /** The split, locked `FOR UPDATE` (Undo, after its job's row: never two Undos at once). */
  def lock(id: Long)(implicit session: DBSession): Option[ContestSplitRow] =
    sql"SELECT $columns FROM contest_split WHERE id = $id FOR UPDATE".map(extract).single.apply()

  def findByJob(jobId: Long)(implicit session: DBSession = AutoSession): Option[ContestSplitRow] =
    sql"SELECT $columns FROM contest_split WHERE job_id = $jobId".map(extract).single.apply()

  /** The contest's splits, as the source, the newest first. */
  def findByContest(contestId: Long, limit: Int = 20)(implicit session: DBSession = AutoSession): Seq[ContestSplitRow] =
    sql"SELECT $columns FROM contest_split WHERE contest_id = $contestId ORDER BY id DESC LIMIT $limit"
      .map(extract).list.apply()

  /** The splits that moved rounds into the contest (not undone), the newest first. */
  def findIntoContest(contestId: Long, limit: Int = 20)(implicit session: DBSession = AutoSession): Seq[ContestSplitRow] =
    sql"""SELECT $columns FROM contest_split WHERE undone_at IS NULL AND id IN (
            SELECT split_id FROM contest_split_item WHERE kind IN (${Kind.Contest}, ${Kind.ContestExisting})
              AND subject_id = $contestId) ORDER BY id DESC LIMIT $limit""".map(extract).list.apply()

  def addItem(splitId: Long, moveIndex: Int, kind: String, subjectId: Option[Long], contestId: Option[Long],
      oldValue: JsObject = Json.obj(), newValue: JsObject = Json.obj())(implicit session: DBSession): Long = {
    require(Kind.All.contains(kind), "not a split item kind")
    def text(v: JsObject) = Option.when(v.fields.nonEmpty)(Json.stringify(v))
    sql"""INSERT INTO contest_split_item (split_id, move_index, kind, subject_id, contest_id, old_value, new_value, created_at)
          VALUES ($splitId, $moveIndex, $kind, $subjectId, $contestId, ${text(oldValue)}, ${text(newValue)}, NOW(3))"""
      .updateAndReturnGeneratedKey.apply()
  }

  def items(splitId: Long)(implicit session: DBSession = AutoSession): Seq[ContestSplitItem] =
    sql"""SELECT id, split_id, move_index, kind, subject_id, contest_id, old_value, new_value FROM contest_split_item
          WHERE split_id = $splitId ORDER BY id"""
      .map(rs => ContestSplitItem(rs.long(1), rs.long(2), rs.int(3), rs.string(4), rs.longOpt(5), rs.longOpt(6),
        json(rs.stringOpt(7)), json(rs.stringOpt(8))))
      .list.apply()

  /** Where a round a split (not undone) moved out of `fromContest` is now: §4.6's redirect of a link
    * that names the old contest. None if no split moved it out of there, or it is back.
    */
  def movedRound(roundId: Long, fromContest: Long)(implicit session: DBSession = AutoSession): Option[Long] =
    sql"""SELECT r.contest_id FROM contest_split_item i
            JOIN contest_split s ON s.id = i.split_id
            JOIN round r ON r.id = i.subject_id
          WHERE i.kind = ${Kind.Round} AND i.subject_id = $roundId AND s.contest_id = $fromContest
            AND s.undone_at IS NULL AND r.contest_id <> $fromContest
          LIMIT 1""".map(_.long(1)).single.apply()
}
