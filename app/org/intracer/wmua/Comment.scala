package org.intracer.wmua

import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit

import scalikejdbc._

case class Comment(
  id: Long,
  userId: Long,
  username: String,
  roundId: Long,
  contestId: Option[Long],
  room: Long,
  createdAt: String,
  body: String)

object CommentJdbc extends SQLSyntaxSupport[Comment] {

  override val tableName = "comment"

  val c = CommentJdbc.syntax("c")

  /** The time a comment is created, to the second with its offset: it fits created_at
    * (varchar(40)), which a ZonedDateTime with a zone id and microseconds does not.
    */
  def now(): String = OffsetDateTime.now.truncatedTo(ChronoUnit.SECONDS).toString

  def apply(c: SyntaxProvider[Comment])(rs: WrappedResultSet): Comment = apply(c.resultName)(rs)

  def apply(c: ResultName[Comment])(rs: WrappedResultSet): Comment = new Comment(
    id = rs.long(c.id),
    userId = rs.int(c.userId),
    username = rs.string(c.username),
    roundId = rs.int(c.roundId),
    contestId = rs.longOpt(c.contestId),
    room = rs.int(c.room),
    createdAt = rs.string(c.createdAt),
    body = rs.string(c.body)
  )

  def create(userId: Long, username: String, roundId: Long, contestId: Option[Long], room: Long, body: String,
             createdAt: String = CommentJdbc.now())(implicit session: DBSession = autoSession): Comment = {
    val id = withSQL {
      insert.into(CommentJdbc).namedValues(
        column.userId -> userId,
        column.username -> username,
        column.roundId -> roundId,
        column.room -> room,
        column.contestId -> contestId,
        column.body -> body,
        column.createdAt -> createdAt)
    }.updateAndReturnGeneratedKey()

    Comment(id = id, userId = userId, username = username, roundId = roundId, contestId = contestId, room =  room, body = body,
      createdAt = createdAt)
  }

  def findAll()(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      //      .where.append(isNotDeleted)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()

  def findByRound(roundId: Long)(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      .where //.append(isNotDeleted)
      // .and
      .eq(c.roundId, roundId)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()

  def findByRoundAndSubject(roundId: Long, subject: Long)(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      .where //.append(isNotDeleted)
      // .and
      .eq(c.roundId, roundId).and
      .eq(c.room, subject)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()

  def findBySubject(subject: Long)(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      .where //.append(isNotDeleted)
      // .and
      .eq(c.room, subject)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()

  def findBySubjectAndContest(subject: Long, contestId: Long)(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      .where
      .eq(c.room, subject).and
      .eq(c.contestId, contestId)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()
}