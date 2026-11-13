package org.intracer.wmua

import java.time.{DateTimeException, Instant, LocalDateTime, OffsetDateTime, ZoneId, ZonedDateTime}
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
  body: String) {

  /** When it was written, from `created_at` ([[Comment.parseTime]]); None if that can't be read. */
  def createdInstant: Option[Instant] = Comment.parseTime(createdAt)
}

object Comment {

  /** A `comment.created_at` as an instant, leniently: the app has written it as an
    * OffsetDateTime (`2026-11-12T14:05:00+02:00`, [[CommentJdbc.now]]) and before that as a
    * ZonedDateTime's text, with a zone id and fractions of a second
    * (`2016-10-05T12:34:56.464+02:00[Europe/Kiev]`). The migrations make the column varchar(40),
    * which would cut such a text short; production's column has drifted to mediumtext, with values
    * up to 50 characters, whole. Also
    * a space for the `T`, a `Z` offset, no seconds. The offset decides the instant; a zone id
    * alone (no offset) is read in that zone. A text without either, cut inside its offset, or
    * not a time at all is None: the large view then shows no time, never a wrong one.
    */
  def parseTime(text: String): Option[Instant] =
    Option(text).map(_.trim).filter(_.nonEmpty).flatMap { raw =>
      val t = raw.replaceFirst("""^(\d{4}-\d{2}-\d{2}) """, "$1T")
      def attempt[A](f: => A): Option[A] =
        try Some(f)
        catch { case _: DateTimeException => None }
      // a zone id that the text has whole: `…[Europe/Kiev]`
      val zone = """\[([^\]]+)\]$""".r.findFirstMatchIn(t).flatMap(m => attempt(ZoneId.of(m.group(1))))
      // the text without a zone id, or without a cut one (`…+02:00[Europe/Ki`)
      val withoutZone = t.replaceFirst("""\[[^\]]*\]?$""", "")
      attempt(OffsetDateTime.parse(withoutZone).toInstant)
        .orElse(attempt(ZonedDateTime.parse(t).toInstant))
        .orElse(zone.flatMap(z => attempt(LocalDateTime.parse(withoutZone).atZone(z).toInstant)))
    }
}

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

  /** The number of comments on each of the files (`room`) in the contest, as their large views
    * show them ([[findBySubjectAndContest]]): one grouped query on comment's (contest_id, room)
    * index (V101); a file without comments is absent.
    */
  def countsByRoom(contestId: Long, rooms: Seq[Long])(implicit session: DBSession = autoSession): Map[Long, Int] =
    if (rooms.isEmpty) Map.empty
    else
      sql"""select room, count(*) from comment
            where contest_id = $contestId and room in (${rooms.distinct}) group by room"""
        .map(rs => rs.long(1) -> rs.int(2)).list().toMap

  /** The comments on the files (`room`) in the contest, in the round and by the account
    * (`userId`) if given, in the order written (by id): a page of the comments report's files at
    * once, on comment's (contest_id, room) index (V101).
    */
  def findByRooms(contestId: Long, rooms: Seq[Long], roundId: Option[Long] = None, userId: Option[Long] = None)(implicit
      session: DBSession = autoSession
  ): List[Comment] =
    if (rooms.isEmpty) Nil
    else
      withSQL {
        select.from(CommentJdbc as c)
          .where
          .eq(c.contestId, contestId).and
          .in(c.room, rooms.distinct)
          .and(roundId.map(r => sqls.eq(c.roundId, r)))
          .and(userId.map(u => sqls.eq(c.userId, u)))
          .orderBy(c.id)
      }.map(CommentJdbc(c)).list()

  /** The name the account last signed a comment in the contest with (`comment.username`, its
    * full name when it wrote it): the comments report's filter by author shows it. None if the
    * account has no comment in the contest: the report never names an account from the users
    * table, which could be another contest's. The latest id from the contest's comments on
    * comment's (contest_id, room) index (V101), then its row by primary key: ORDER BY id DESC
    * LIMIT 1 instead is planned as a scan of the primary key from the end, which for an account
    * without comments in the contest reads the whole table (production's copy: 20,822 rows,
    * 7 ms, against 1,943 index rows, 1.9 ms).
    */
  def authorName(contestId: Long, userId: Long)(implicit session: DBSession = autoSession): Option[String] =
    sql"""select username from comment where id =
            (select max(id) from comment where contest_id = $contestId and user_id = $userId)"""
      .map(_.string(1)).single()

  def findBySubjectAndContest(subject: Long, contestId: Long)(implicit session: DBSession = autoSession): List[Comment] = withSQL {
    select.from(CommentJdbc as c)
      .where
      .eq(c.room, subject).and
      .eq(c.contestId, contestId)
      .orderBy(c.id)
  }.map(CommentJdbc(c)).list()
}