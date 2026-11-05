package db.scalikejdbc

import java.time.ZonedDateTime
import org.intracer.wmua.Vote
import scalikejdbc._
import scalikejdbc.orm.CRUDMapper

object VoteJdbc extends CRUDMapper[Vote] {

  implicit def session: DBSession = autoSession

  override val tableName = "vote"

  val s = VoteJdbc.syntax("s")
  val u = User.u

  private def isNotDeleted = sqls.isNull(s.deletedAt)

  override lazy val defaultAlias = createAlias("s")

  override def extract(rs: WrappedResultSet, c: ResultName[Vote]): Vote = Vote(
    pageId     = rs.long(c.pageId),
    juryId     = rs.long(c.juryId),
    roundId    = rs.long(c.roundId),
    rate       = rs.int(c.rate),
    id         = rs.longOpt(c.id),
    createdAt  = rs.timestampOpt(c.createdAt).map(_.toZonedDateTime),
    deletedAt  = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
    monumentId = rs.stringOpt(c.monumentId)
  )

  def apply(c: ResultName[Vote])(rs: WrappedResultSet): Vote =
    Vote(
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
  )(implicit session: DBSession = AutoSession): Vote = {
    // the vote and its file's row in the round commit together (RoundImageJdbc.addVoted);
    // the round first, for update: a structural write (RoundWrites)
    val id = RoundImageJdbc.inTransaction(session) { implicit session =>
      RoundWrites.lock(Seq(roundId))
      val id = withSQL {
        insert
          .into(VoteJdbc)
          .namedValues(
            column.pageId     -> pageId,
            column.rate       -> rate,
            column.juryId     -> juryId,
            column.roundId    -> roundId,
            column.monumentId -> monumentId
          )
      }.updateAndReturnGeneratedKey()
      RoundImageJdbc.addVoted(Seq(roundId -> pageId), RoundImageJdbc.Origin.Manual)
      id
    }

    Vote(
      pageId     = pageId,
      juryId     = juryId,
      roundId    = roundId,
      rate       = rate,
      id         = Some(id),
      createdAt  = createdAt,
      monumentId = monumentId
    )
  }

  /** Inserts the vote rows, and adds their files to their rounds ([[RoundImageJdbc.addVoted]],
    * as `origin` added them), in one transaction: the caller's, or one of its own. Their
    * rounds are locked for update first ([[RoundWrites.lock]]): an insert is a structural
    * write, and round_image's foreign key would otherwise lock them after the votes.
    */
  def batchInsert(selections: Iterable[Vote], origin: RoundImageJdbc.Origin = RoundImageJdbc.Origin.Manual)(implicit
      session: DBSession = AutoSession
  ): Unit = if (selections.nonEmpty) RoundImageJdbc.inTransaction(session) { implicit session =>
    RoundWrites.lock(selections.map(_.roundId).toSeq)
    val column = VoteJdbc.column
    val batchParams: Seq[Seq[Any]] =
      selections.map(i => Seq(i.pageId, i.rate, i.juryId, i.roundId, i.monumentId.orNull)).toSeq
    withSQL {
      insert
        .into(VoteJdbc)
        .namedValues(
          column.pageId     -> sqls.?,
          column.rate       -> sqls.?,
          column.juryId     -> sqls.?,
          column.roundId    -> sqls.?,
          column.monumentId -> sqls.?
        )
    }.batch(batchParams: _*).apply()
    RoundImageJdbc.addVoted(selections.map(v => v.roundId -> v.pageId), origin)
  }

  def byRound(roundId: Long): Seq[Vote] =
    where("roundId" -> roundId).apply()

  def byRoundSelected(roundId: Long): Seq[Vote] =
    where("roundId" -> roundId)
      .where(VoteSql.voted(s.rate))
      .apply()

  def byUser(user: User, roundId: Long): Seq[Vote] =
    where("juryId" -> user.id, "roundId" -> roundId).apply()

  def byUserSelected(user: User, roundId: Long): Seq[Vote] =
    where("juryId" -> user.id, "roundId" -> roundId)
      .where(VoteSql.voted(s.rate))
      .apply()

  def byUserNotSelected(user: User, roundId: Long): Seq[Vote] =
    where("juryId" -> user.id, "roundId" -> roundId, "rate" -> Vote.Unrated).apply()

  /** Every juror's vote on the file in the round, with the juror, for the organisers' large
    * view: the highest rates first (a binary round's approvals), then the rejections, then the
    * jurors yet to vote; jurors of a rate by name. One query.
    */
  def byRoundAndImageWithJury(roundId: Long, imageId: Long): Seq[(Vote, User)] =
    withSQL {
      select
        .from(VoteJdbc as s)
        .innerJoin(User as u)
        .on(u.id, s.juryId)
        .where
        .eq(s.roundId, roundId)
        .and
        .eq(s.pageId, imageId)
        .and
        .append(isNotDeleted)
    }.map(rs => (VoteJdbc(s)(rs), User(u)(rs))).list()
      .sortBy { case (vote, juror) =>
        (if (vote.isPositive) 0 else if (vote.isRejected) 1 else 2, -vote.rate, juror.fullname, juror.getId)
      }

  /** Whether the file is in the round: `in` it ([[RoundImageJdbc.isIn]]: it has vote rows
    * there), or with `juryId`, has that juror's vote row in it. As the galleries and large
    * view list a round's files, rows with vote's legacy `deleted_at` set count too.
    */
  def inRound(roundId: Long, pageId: Long, juryId: Option[Long]): Boolean =
    juryId match {
      case None => RoundImageJdbc.isIn(roundId, pageId)
      case Some(id) =>
        sql"""SELECT EXISTS(SELECT 1 FROM vote WHERE round_id = $roundId AND page_id = $pageId AND jury_id = $id)"""
          .map(_.boolean(1)).single().getOrElse(false)
    }

  def findBy(pageId: Long, juryId: Long, roundId: Long): Option[Vote] =
    where("pageId" -> pageId, "juryId" -> juryId, "roundId" -> roundId)
      .apply()
      .headOption

  /** A juror's vote on the file in the round (W1/h4 of
    * docs/plans/2026-11-03-write-path-simplification.md): the number of votes rated, 0 or 1.
    * One transaction, retried on a deadlock ([[WriteTx]]): the vote's id by a plain read, then its row
    * locked by its primary key (so the juror locks the clustered row first, as every delete or
    * move of it does, not its unique secondary entry first: one row, two indexes, deadlocked);
    * then, read with a lock, whether the juror is stopped on the round (an inactive round_user
    * row, P6: also when the stop races the vote) and whether the round has criteria (a criteria
    * round's votes are per criterion, [[org.intracer.wmua.CriteriaRate.updateRate]]; a change
    * of its criteria locks the round's votes first, [[RoundCriterion.update]], so a vote waiting
    * for it reads the committed criteria); then the rate. A juror's vote never locks the round:
    * it is row-level, not a structural write ([[RoundWrites]]).
    */
  def rate(pageId: Long, juryId: Long, roundId: Long, rate: Int = 1)(implicit session: DBSession = AutoSession): Int = {
    session match {
      case _: AutoSession =>
        // the id read on the transaction's own connection, before the locks (a plain read)
        WriteTx.write(tx => voteId(pageId, juryId, roundId)(tx).fold(0)(id => rateIn(id, juryId, roundId, rate)(tx)))
      case s => voteId(pageId, juryId, roundId)(s).fold(0)(id => rateIn(id, juryId, roundId, rate)(s)) // the caller's transaction
    }
  }

  private def voteId(pageId: Long, juryId: Long, roundId: Long)(implicit session: DBSession): Option[Long] =
    sql"SELECT id FROM vote WHERE page_id = $pageId AND jury_id = $juryId AND round_id = $roundId".map(_.long(1)).single()

  /** [[rate]] of the vote `id` in the caller's transaction. A rate that changes marks the
    * file's counts in the round dirty, last ([[RoundCounts.markFile]], P2a): no count delta and
    * no lock on the round or its `round_image` row.
    *
    * A ✗ (-1) is checked against the round's worst mark by the caller (`Round.acceptsRate`, on the
    * round read before), not here: a vote doesn't lock the round row, so a ✗ racing the option's
    * turn-off is taken, as a ✗ given just before it is (both stay, counted as 0; round images §18).
    */
  private[scalikejdbc] def rateIn(id: Long, juryId: Long, roundId: Long, rate: Int)(implicit session: DBSession): Int = {
    // the vote's file and its rate before, read as it is locked
    val locked = sql"SELECT page_id, rate FROM vote WHERE id = $id AND jury_id = $juryId AND round_id = $roundId FOR UPDATE"
      .map(rs => rs.long(1) -> rs.int(2)).single()
    def stopped = sql"""SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $juryId AND active = FALSE
                        LOCK IN SHARE MODE""".map(_ => ()).single().isDefined
    def criteria = sql"SELECT 1 FROM round_criterion WHERE round_id = $roundId LIMIT 1 LOCK IN SHARE MODE"
      .map(_ => ()).single().isDefined
    locked match {
      case Some((pageId, before)) if !stopped && !criteria =>
        val updated = sql"UPDATE vote SET rate = $rate WHERE id = $id".update()
        if (rate != before) RoundCounts.markFile(roundId, pageId)
        updated
      case _ => 0
    }
  }

  def allJurors(roundId: Long): Long =
    where("roundId" -> roundId)
      .distinctCount("juryId")

  /** Whether a juror has rated a file of the round (any vote, a ✗ included, of a vote not
    * deleted): until then every file scores 0 under each score rule. One read of the
    * (round_id, rate) index; the round form's score rule warning.
    */
  def hasRatedVote(roundId: Long): Boolean =
    sql"""SELECT 1 FROM vote WHERE round_id = $roundId AND ${VoteSql.voted(VoteSql.bare)} AND deleted_at IS NULL LIMIT 1"""
      .map(_.int(1)).single().isDefined

  /** Whether the round has a vote below 0 (a yes/no round's no, a rated round's ✗): one read of
    * the (round_id, rate) index. The CSV's legend of a rated round whose option was turned off.
    */
  def hasRejected(roundId: Long): Boolean =
    sql"""SELECT 1 FROM vote WHERE round_id = $roundId AND ${VoteSql.rejected(VoteSql.bare)} LIMIT 1"""
      .map(_.int(1)).single().isDefined

  private[scalikejdbc] val DeleteChunk = 5000

  /** Those of the votes `ids` with a criterion rated (a rate above 0), read with a shared
    * lock: votes whose rate is 0 but that hold a juror's work, in a criteria round (U5). The
    * caller has locked the votes (their rates are written only then).
    */
  private[scalikejdbc] def votedByCriteria(ids: Seq[Long])(implicit session: DBSession): Set[Long] =
    ids.grouped(DeleteChunk).flatMap { chunk =>
      sql"SELECT DISTINCT vote_id FROM criteria_rate WHERE vote_id IN ($chunk) AND rate > 0 LOCK IN SHARE MODE"
        .map(_.long(1)).list()
    }.toSet

  /** Deletes the votes `where` selects (on alias `v`), in vote and in vote_aside,
    * with their criteria rates (criteria_rate.vote_id has no foreign key, and a cascade
    * would not do: moving a vote aside deletes it from vote). Per table, the votes are
    * locked first (`FOR UPDATE`: a shared lock taken by the joins below would deadlock, on
    * its upgrade, with a juror voting on one of them), then their rates and they are deleted:
    * rates are written only while their vote is locked ([[org.intracer.wmua.CriteriaRate.updateRate]]).
    * Last, their files' rows in their rounds follow what is left ([[RoundImageJdbc.sync]]: a
    * file with no votes left there is no longer in the round, as before V84). A structural
    * write: the caller has locked the votes' rounds first ([[RoundWrites]]; checked).
    *
    * @return the votes deleted
    */
  def deleteVotes(where: SQLSyntax)(implicit session: DBSession): Int = {
    val touched = Seq(sqls"vote", sqls"vote_aside").map { table =>
      val pairs = lockPairs(table, where)
      RoundWrites.requireRounds(pairs.map(_._1))
      // from the votes to their rates by criteria_rate_vote_id_idx: from criteria_rate, the
      // statement would lock (and scan) every rate
      sql"DELETE cr FROM $table v STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = v.id WHERE $where".update()
      pairs -> sql"DELETE v FROM $table v WHERE $where".update()
    }
    RoundImageJdbc.syncPairs(touched.flatMap(_._1).distinct)
    touched.map(_._2).sum
  }

  /** Locks the votes `where` selects (on alias `v`) in `table` for an update, as
    * [[lockVotes]] does: their (round id, page id) pairs.
    */
  private[scalikejdbc] def lockPairs(table: SQLSyntax, where: SQLSyntax)(implicit session: DBSession): Seq[(Long, Long)] =
    sql"SELECT DISTINCT v.round_id, v.page_id FROM $table v WHERE $where FOR UPDATE"
      .map(rs => rs.long(1) -> rs.long(2))
      .list()

  /** Locks the votes `where` selects (on alias `v`) in `table` for an update: `COUNT(*)`
    * locks every row it reads and sends none.
    */
  private[scalikejdbc] def lockVotes(table: SQLSyntax, where: SQLSyntax)(implicit session: DBSession): Unit =
    sql"SELECT COUNT(*) FROM $table v WHERE $where FOR UPDATE".map(_.int(1)).single(): Unit

}
