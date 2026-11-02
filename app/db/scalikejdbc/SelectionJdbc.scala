package db.scalikejdbc

import java.time.ZonedDateTime
import org.intracer.wmua.Selection
import scalikejdbc._
import scalikejdbc.orm.CRUDMapper

object SelectionJdbc extends CRUDMapper[Selection] {

  implicit def session: DBSession = autoSession

  override val tableName = "selection"

  val s = SelectionJdbc.syntax("s")
  val u = User.u

  private def isNotDeleted = sqls.isNull(s.deletedAt)

  override lazy val defaultAlias = createAlias("s")

  override def extract(rs: WrappedResultSet, c: ResultName[Selection]): Selection = Selection(
    pageId     = rs.long(c.pageId),
    juryId     = rs.long(c.juryId),
    roundId    = rs.long(c.roundId),
    rate       = rs.int(c.rate),
    id         = rs.longOpt(c.id),
    createdAt  = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt  = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
    monumentId = rs.stringOpt(c.monumentId)
  )

  def apply(c: ResultName[Selection])(rs: WrappedResultSet): Selection =
    Selection(
      pageId     = rs.long(c.pageId),
      juryId     = rs.long(c.juryId),
      roundId    = rs.long(c.roundId),
      rate       = rs.int(c.rate),
      id         = rs.longOpt(c.id),
      createdAt  = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
      deletedAt  = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
      monumentId = rs.stringOpt(c.monumentId)
    )

  def create(
      pageId: Long,
      rate: Int,
      juryId: Long,
      roundId: Long,
      createdAt: Option[ZonedDateTime] = None,
      monumentId: Option[String] = None
  )(implicit session: DBSession = AutoSession): Selection = {
    val id = withSQL {
      insert
        .into(SelectionJdbc)
        .namedValues(
          column.pageId     -> pageId,
          column.rate       -> rate,
          column.juryId     -> juryId,
          column.roundId    -> roundId,
          column.monumentId -> monumentId
        )
    }.updateAndReturnGeneratedKey()

    Selection(
      pageId     = pageId,
      juryId     = juryId,
      roundId    = roundId,
      rate       = rate,
      id         = Some(id),
      createdAt  = createdAt,
      monumentId = monumentId
    )
  }

  def batchInsert(selections: Iterable[Selection])(implicit session: DBSession = AutoSession): Unit = {
    val column = SelectionJdbc.column
    val batchParams: Seq[Seq[Any]] =
      selections.map(i => Seq(i.pageId, i.rate, i.juryId, i.roundId, i.monumentId.orNull)).toSeq
    withSQL {
      insert
        .into(SelectionJdbc)
        .namedValues(
          column.pageId     -> sqls.?,
          column.rate       -> sqls.?,
          column.juryId     -> sqls.?,
          column.roundId    -> sqls.?,
          column.monumentId -> sqls.?
        )
    }.batch(batchParams: _*).apply()
  }

  def byRound(roundId: Long): Seq[Selection] =
    where("roundId" -> roundId).apply()

  def byRoundSelected(roundId: Long): Seq[Selection] =
    where("roundId" -> roundId)
      .where(sqls.ne(s.rate, 0))
      .apply()

  def byUser(user: User, roundId: Long): Seq[Selection] =
    where("juryId" -> user.id, "roundId" -> roundId).apply()

  def byUserSelected(user: User, roundId: Long): Seq[Selection] =
    where("juryId" -> user.id, "roundId" -> roundId)
      .where(sqls.ne(s.rate, 0))
      .apply()

  def byUserNotSelected(user: User, roundId: Long): Seq[Selection] =
    where("juryId" -> user.id, "roundId" -> roundId, "rate" -> 0).apply()

  /** Every juror's vote on the file in the round, with the juror, for the organisers' large
    * view: the highest rates first (a binary round's approvals), then the rejections, then the
    * jurors yet to vote; jurors of a rate by name. One query.
    */
  def byRoundAndImageWithJury(roundId: Long, imageId: Long): Seq[(Selection, User)] =
    withSQL {
      select
        .from(SelectionJdbc as s)
        .innerJoin(User as u)
        .on(u.id, s.juryId)
        .where
        .eq(s.roundId, roundId)
        .and
        .eq(s.pageId, imageId)
        .and
        .append(isNotDeleted)
    }.map(rs => (SelectionJdbc(s)(rs), User(u)(rs))).list()
      .sortBy { case (vote, juror) =>
        (if (vote.rate > 0) 0 else if (vote.rate < 0) 1 else 2, -vote.rate, juror.fullname, juror.getId)
      }

  /** Whether the file is in the round: has a selection row in it, `juryId`'s if given.
    * As the galleries and large view list a round's files, deleted rows count too.
    */
  def inRound(roundId: Long, pageId: Long, juryId: Option[Long]): Boolean = {
    val ofJuror = juryId.fold(sqls"")(id => sqls"AND jury_id = $id")
    sql"""SELECT EXISTS(SELECT 1 FROM selection WHERE round_id = $roundId AND page_id = $pageId $ofJuror)"""
      .map(_.boolean(1)).single().getOrElse(false)
  }

  def findBy(pageId: Long, juryId: Long, roundId: Long): Option[Selection] =
    where("pageId" -> pageId, "juryId" -> juryId, "roundId" -> roundId)
      .apply()
      .headOption

  def destroy(pageId: Long, juryId: Long, roundId: Long): Unit =
    updateBy(
      sqls
        .eq(s.pageId, pageId)
        .and
        .eq(s.juryId, juryId)
        .and
        .eq(s.roundId, roundId)
    ).withAttributes("rate" -> -1)

  /** Rates the juror's selection of the file in the round; the number of selections found
    * (0 or 1): MariaDB's driver counts the rows matched, not only those changed
    * (`useAffectedRows` off, its default), so a vote of the stored rate counts too.
    */
  def rate(pageId: Long, juryId: Long, roundId: Long, rate: Int = 1)(implicit session: DBSession = AutoSession): Int =
    withSQL {
      update(SelectionJdbc)
        .set(column.rate -> rate)
        .where
        .eq(column.pageId, pageId)
        .and
        .eq(column.juryId, juryId)
        .and
        .eq(column.roundId, roundId)
    }.update()

  /** Moves the image's votes to another round, also those moved aside (V63): a juror's
    * vote already in the new round wins. If the new round
    * is open, the votes of a file deleted on Commons are then moved aside there
    * ([[Round.settleDeletedFiles]]).
    */
  def setRound(pageId: Long, oldRoundId: Long, newRoundId: Long): Unit = {
    DB.localTx { implicit session =>
      moveVotes(newRoundId, oldRoundId, sqls"AND src.page_id = $pageId")
    }
    RoundImageCounts.invalidate(oldRoundId, newRoundId)
    Round.settleDeletedFiles(newRoundId)
  }

  def activeJurors(roundId: Long): Int =
    sql"""SELECT count( 1 )
    FROM (

      SELECT s.jury_id
    FROM selection s
    WHERE s.round_id = $roundId AND s.deleted_at IS NULL
    GROUP BY s.jury_id
    HAVING sum(s.rate) > 0
    ) j"""
      .map(_.int(1))
      .single()
      .get

  def allJurors(roundId: Long): Long =
    where("roundId" -> roundId)
      .distinctCount("juryId")

  /** Removes the image from the round, also its votes moved aside (V63): restoring its file
    * on Commons must not bring them back. Their criteria rates go with them ([[deleteVotes]]).
    */
  def removeImage(pageId: Long, roundId: Long): Unit = {
    DB.localTx { implicit session =>
      deleteVotes(sqls"v.page_id = $pageId AND v.round_id = $roundId")
    }
    RoundImageCounts.invalidate(roundId)
  }

  /** Removes the round's unrated votes, also those moved aside (V63), as [[removeImage]]. */
  def removeUnrated(roundId: Long): Unit = {
    DB.localTx { implicit session =>
      deleteVotes(sqls"v.rate = 0 AND v.round_id = $roundId")
    }
    RoundImageCounts.invalidate(roundId)
  }

  /** Deletes the votes `where` selects (on alias `v`), in selection and in selection_deleted,
    * with their criteria rates (criteria_rate.selection has no foreign key, and a cascade
    * would not do: moving a vote aside deletes it from selection). Per table, the votes are
    * locked first (`FOR UPDATE`: a shared lock taken by the joins below would deadlock, on
    * its upgrade, with a juror voting on one of them), then their rates and they are deleted:
    * rates are written only while their vote is locked ([[org.intracer.wmua.CriteriaRate.updateRate]]).
    *
    * @return the votes deleted
    */
  def deleteVotes(where: SQLSyntax)(implicit session: DBSession): Int =
    Seq(sqls"selection", sqls"selection_deleted").map { table =>
      lockVotes(table, where)
      // from the votes to their rates by criteria_selection_index: from criteria_rate, the
      // statement would lock (and scan) every rate
      sql"DELETE cr FROM $table v STRAIGHT_JOIN criteria_rate cr ON cr.selection = v.id WHERE $where".update()
      sql"DELETE v FROM $table v WHERE $where".update()
    }.sum

  /** Locks the votes `where` selects (on alias `v`) in `table` for an update: `COUNT(*)`
    * locks every row it reads and sends none.
    */
  private[scalikejdbc] def lockVotes(table: SQLSyntax, where: SQLSyntax)(implicit session: DBSession): Unit =
    sql"SELECT COUNT(*) FROM $table v WHERE $where FOR UPDATE".map(_.int(1)).single(): Unit

  /** Moves the source round's votes `andWhere` selects (on `src`) to the target round, in
    * selection and in selection_deleted, but those of a juror who has the image in the
    * target round already: they are dropped, with their criteria rates. The moved votes keep
    * their ids, so their criteria rates follow them.
    */
  private def moveVotes(targetRoundId: Long, sourceRoundId: Long, andWhere: SQLSyntax)(implicit
      session: DBSession
  ): Unit =
    Seq(sqls"selection", sqls"selection_deleted").foreach { table =>
      val source = sqls"src.round_id = $sourceRoundId $andWhere"
      val inTarget = sqls"""JOIN $table tgt
              ON tgt.round_id = $targetRoundId
             AND tgt.page_id = src.page_id
             AND tgt.jury_id = src.jury_id"""
      sql"SELECT COUNT(*) FROM $table src WHERE $source FOR UPDATE".map(_.int(1)).single()
      sql"""DELETE cr FROM $table src $inTarget STRAIGHT_JOIN criteria_rate cr ON cr.selection = src.id
            WHERE $source""".update()
      sql"DELETE src FROM $table src $inTarget WHERE $source".update()

      sql"UPDATE $table src SET src.round_id = $targetRoundId WHERE $source".update()
    }

}
