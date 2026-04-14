package db.scalikejdbc

import java.time.ZonedDateTime
import org.intracer.wmua.{CriteriaRate, Selection}
import scalikejdbc._
import scalikejdbc.orm.CRUDMapper

object SelectionJdbc extends CRUDMapper[Selection] {

  implicit def session: DBSession = autoSession

  override val tableName = "selection"

  val s = SelectionJdbc.syntax("s")
  val u = User.u
  val c = CriteriaRate.c

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
        .gt(s.rate, 0)
        .and
        .append(isNotDeleted)
        .orderBy(s.rate)
        .desc
    }.map(rs => (SelectionJdbc(s)(rs), User(u)(rs))).list()

  def findBy(pageId: Long, juryId: Long, roundId: Long): Option[Selection] =
    where("pageId" -> pageId, "juryId" -> juryId, "roundId" -> roundId)
      .apply()
      .headOption

  def byRoundWithCriteria(roundId: Long): Seq[Selection] =
    withSQL {
      select(SQLSyntax.sum(c.rate), SQLSyntax.count(c.rate), s.result.*)
        .from(SelectionJdbc as s)
        .leftJoin(CriteriaRate as c)
        .on(s.id, c.selection)
        .where
        .eq(s.roundId, roundId)
        .and
        .append(isNotDeleted)
        .groupBy(s.id)
    }.map(rs => (SelectionJdbc(s)(rs), rs.intOpt(1).getOrElse(0), rs.intOpt(2).getOrElse(0)))
      .list()
      .map { case (selection, sum, criterias) =>
        if (criterias > 0) selection.copy(rate = sum / criterias)
        else selection
      }

  def destroy(pageId: Long, juryId: Long, roundId: Long): Unit =
    updateBy(
      sqls
        .eq(s.pageId, pageId)
        .and
        .eq(s.juryId, juryId)
        .and
        .eq(s.roundId, roundId)
    ).withAttributes("rate" -> -1)

  def rate(pageId: Long, juryId: Long, roundId: Long, rate: Int = 1)(implicit session: DBSession = AutoSession): Unit =
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

  def setRound(pageId: Long, oldRoundId: Long, newRoundId: Long): Unit =
    withSQL {
      update(SelectionJdbc)
        .set(column.roundId -> newRoundId)
        .where
        .eq(column.pageId, pageId)
        .and
        .eq(column.roundId, oldRoundId)
    }.update()

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

  def destroyAll(pageId: Long): Unit =
    updateBy(sqls.eq(s.pageId, pageId))
      .withAttributes("deletedAt" -> ZonedDateTime.now)

  def removeImage(pageId: Long, roundId: Long): Unit =
    deleteBy(
      sqls
        .eq(s.pageId, pageId)
        .and
        .eq(s.roundId, roundId)
    )

  def removeUnrated(roundId: Long): Unit =
    deleteBy(
      sqls
        .eq(column.rate, 0)
        .and
        .eq(column.roundId, roundId)
    )

  def mergeRounds(targetRoundId: Long, sourceRoundId: Long): Unit =
    updateBy(sqls.eq(s.roundId, sourceRoundId))
      .withNamedValues(s.roundId -> targetRoundId)

}
