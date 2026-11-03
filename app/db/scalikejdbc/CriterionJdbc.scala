package db.scalikejdbc

import org.intracer.wmua.Criterion
import scalikejdbc._

/** The global criteria catalogue (table `criterion`, V83). Entries are added, never changed
  * or removed by the app: rounds of every contest and their rates refer to them.
  */
object CriterionJdbc {

  private def criterion(rs: WrappedResultSet): Criterion =
    Criterion(rs.long("id"), rs.string("message_key"), rs.string("fallback_name"))

  /** The catalogue, by id. */
  def all()(implicit session: DBSession = AutoSession): Seq[Criterion] =
    sql"SELECT id, message_key, fallback_name FROM criterion ORDER BY id".map(criterion).list()

  def findById(id: Long)(implicit session: DBSession = AutoSession): Option[Criterion] =
    sql"SELECT id, message_key, fallback_name FROM criterion WHERE id = $id".map(criterion).single()

  /** Those of `ids` that are in the catalogue. */
  def existing(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    if (ids.isEmpty) Set.empty
    else sql"SELECT id FROM criterion WHERE id IN (${ids.distinct})".map(_.long(1)).list().toSet

  sealed trait AddRefusal { def messageKey: String }
  object AddRefusal {
    case object Full extends AddRefusal { val messageKey = "criteria.catalogue.full" }
    case object Exists extends AddRefusal { val messageKey = "criteria.catalogue.exists" }
  }

  /** Adds a criterion named `fallbackName` (trimmed, 1 to [[Criterion.MaxNameLength]]
    * characters: the caller checks) under a key of its own ([[Criterion.customKey]]), unless
    * the catalogue has [[Criterion.MaxEntries]] or one of that name already (the unique key
    * on fallback_name, case-insensitive as its collation). The limit is a bound, not exact:
    * the count is read without a lock (a lock on the catalogue would hold up every criterion
    * vote's foreign key check), so adds at the same moment may pass it by one each.
    */
  def add(fallbackName: String, createdBy: Option[Long]): Either[AddRefusal, Criterion] =
    DB.localTx { implicit session => addIn(fallbackName, createdBy) }

  /** [[add]] in the caller's transaction. */
  def addIn(fallbackName: String, createdBy: Option[Long])(implicit session: DBSession): Either[AddRefusal, Criterion] = {
    val count = sql"SELECT COUNT(*) FROM criterion".map(_.int(1)).single().getOrElse(0)
    if (count >= Criterion.MaxEntries) Left(AddRefusal.Full)
    else
      try {
        // a key unique until the id is known
        val pending = s"criterion.pending.${java.util.UUID.randomUUID()}"
        val id = sql"""INSERT INTO criterion (message_key, fallback_name, created_by)
                       VALUES ($pending, $fallbackName, $createdBy)""".updateAndReturnGeneratedKey()
        val key = Criterion.customKey(id)
        sql"UPDATE criterion SET message_key = $key WHERE id = $id".update()
        Right(Criterion(id, key, fallbackName))
      } catch {
        case e: java.sql.SQLIntegrityConstraintViolationException
            if Option(e.getMessage).exists(_.contains("criterion_fallback_name_uidx")) =>
          Left(AddRefusal.Exists)
      }
  }
}

/** A round's criteria, in order (table `round_criterion`, V83): set when the round is created,
  * changed by its form. `round.has_criteria` is kept equal to "has rows here".
  */
object RoundCriterion {

  /** The round's criteria, by position. */
  def ofRound(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Criterion] =
    sql"""SELECT c.id, c.message_key, c.fallback_name FROM round_criterion rc
          JOIN criterion c ON c.id = rc.criterion_id
          WHERE rc.round_id = $roundId ORDER BY rc.position"""
      .map(rs => Criterion(rs.long(1), rs.string(2), rs.string(3))).list()

  /** The ids of the round's criteria, by position. */
  def idsOf(roundId: Long)(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT criterion_id FROM round_criterion WHERE round_id = $roundId ORDER BY position"
      .map(_.long(1)).list()

  def countOf(roundId: Long)(implicit session: DBSession = AutoSession): Int =
    sql"SELECT COUNT(*) FROM round_criterion WHERE round_id = $roundId".map(_.int(1)).single().getOrElse(0)

  /** Inserts the round's criteria (a new round has none yet) and sets has_criteria, in the
    * caller's transaction.
    */
  def insert(roundId: Long, criterionIds: Seq[Long])(implicit session: DBSession): Unit =
    if (criterionIds.nonEmpty) {
      sql"INSERT INTO round_criterion (round_id, position, criterion_id) VALUES (?, ?, ?)"
        .batch(criterionIds.zipWithIndex.map { case (id, i) => Seq(roundId, i + 1, id) }: _*)
        .apply()
      sql"UPDATE round SET has_criteria = 1 WHERE id = $roundId".update(): Unit
    }

  /** A refused change of a round's criteria. */
  sealed trait Refusal { def messageKey: String }
  object Refusal {
    /** After the first vote: criteria added, removed, moved or replaced by one the round has. */
    case object Voted extends Refusal { val messageKey = "error.criteria.voted" }
    case object NotFound extends Refusal { val messageKey = "error.round.not.found" }
  }

  /** Whether the round has a vote: a rate other than 0, or a criterion rate, in vote or in
    * vote_aside; read with a lock (the caller has locked the round's votes).
    */
  private def voted(roundId: Long)(implicit session: DBSession): Boolean =
    Seq(sqls"vote", sqls"vote_aside").exists { table =>
      sql"SELECT 1 FROM $table v WHERE v.round_id = $roundId AND v.rate <> 0 LIMIT 1 LOCK IN SHARE MODE"
        .map(_.int(1)).single().isDefined ||
      sql"""SELECT 1 FROM $table v STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
            WHERE v.round_id = $roundId LIMIT 1 LOCK IN SHARE MODE""".map(_.int(1)).single().isDefined
    }

  /** Changes the round's criteria to `criterionIds` (checked by the round form: none, or 2 to
    * 10 of the catalogue, each once). Before the round's first vote any change is taken (none:
    * no longer a criteria round). After it only a criterion's replacement by one the round
    * hasn't, in its place, as a renaming (U10): its rates are moved to the new criterion; a
    * criterion added, removed or moved is refused, nothing written.
    *
    * One transaction, locking in the order of every vote write: the round, its votes (vote,
    * then vote_aside: the criterion votes wait, [[org.intracer.wmua.CriteriaRate.updateRate]]),
    * then round_criterion and criteria_rate.
    *
    * @return whether anything changed, or the refusal
    */
  def update(roundId: Long, criterionIds: Seq[Long]): Either[Refusal, Boolean] =
    DbErrors.retryOnDeadlock(DB.localTx { implicit session => updateIn(roundId, criterionIds) })

  /** [[update]] in the caller's transaction. */
  def updateIn(roundId: Long, criterionIds: Seq[Long])(implicit session: DBSession): Either[Refusal, Boolean] =
    if (sql"SELECT id FROM round WHERE id = $roundId FOR UPDATE".map(_.long(1)).single().isEmpty)
      Left(Refusal.NotFound)
    else {
      val stored = idsOf(roundId)
      if (stored == criterionIds) Right(false)
      else {
        VoteJdbc.lockVotes(sqls"vote", sqls"v.round_id = $roundId")
        VoteJdbc.lockVotes(sqls"vote_aside", sqls"v.round_id = $roundId")
        if (!voted(roundId)) {
          sql"DELETE FROM round_criterion WHERE round_id = $roundId".update()
          if (criterionIds.isEmpty) sql"UPDATE round SET has_criteria = 0 WHERE id = $roundId".update()
          else insert(roundId, criterionIds)
          Right(true)
        } else {
          val renamed = stored.zip(criterionIds).filter { case (from, to) => from != to }
          val sameShape = stored.size == criterionIds.size && renamed.forall { case (_, to) => !stored.contains(to) }
          if (!sameShape) Left(Refusal.Voted)
          else {
            renamed.foreach { case (from, to) =>
              sql"""UPDATE round_criterion SET criterion_id = $to
                    WHERE round_id = $roundId AND criterion_id = $from""".update()
              Seq(sqls"vote", sqls"vote_aside").foreach { table =>
                sql"""UPDATE $table v STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id
                      SET cr.criterion_id = $to
                      WHERE v.round_id = $roundId AND cr.criterion_id = $from""".update()
              }
            }
            Right(true)
          }
        }
      }
    }
}
