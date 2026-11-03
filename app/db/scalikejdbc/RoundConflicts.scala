package db.scalikejdbc

import db.scalikejdbc.RoundImageJdbc.{DqCode, DqRow, Status}
import play.api.Logger
import scalikejdbc._

/** Conflicts of interest in a round ([[CoiMembers]]), disqualified and requalified
  * (docs/plans/2026-11-02-round-images-two-step.md, §7 and P1b; D7, D8):
  *
  *  - **Disqualify** ("Leave out conflicts of interest" saved ticked on an existing round, or
  *    the round's conflicts page): the files' votes move aside (`vote_aside`) with their ids,
  *    so their rates and `criteria_rate` rows are kept, and their `round_image` rows become
  *    `dq` (`dq_code = coi`, the matched account and role as `dq_detail`, who, when). The files
  *    leave the jurors' lists and the round's counts; a restore on Commons leaves their votes
  *    aside; "Distribute new files" never adds them again (its anti-join reads every row).
  *  - **Requalify** (a mistaken match or an ended conflict, an admin's action with a reason
  *    code): the votes come back exactly, the rows `in` again. A file deleted on Commons
  *    meanwhile is `in` too, as any deleted file of a round that is not open: its votes move
  *    aside when the round opens ([[Round.setActiveGuarded]]).
  *
  * Both are structural writes (W2): the round is locked for update first, in the action's own
  * transaction, and they are allowed only while the round is not open to its jurors (W7 of
  * docs/plans/2026-11-03-write-path-simplification.md): stopped, not being created, not frozen
  * by a next round's distribution, with no distribution of it queued or running. The status
  * is read under that lock. A juror's vote already in flight can still land: it is row-level
  * (its vote row locked by primary key), so a disqualify waits for it and moves the voted row,
  * or the vote finds its row gone and changes nothing.
  *
  * Each action is a log line on the `coi` logger, written at INFO (both logback.xml files), with
  * ids and codes only.
  */
object RoundConflicts {

  val logger: Logger = Logger("coi")

  /** Why a file is requalified (D8): a code, not free text, so the log line carries no
    * request text.
    */
  object RequalifyReason {
    val MistakenMatch = "mistaken_match"
    val ConflictEnded = "conflict_ended"
    val All: Seq[String] = Seq(MistakenMatch, ConflictEnded)
  }

  /** What an action did, or why it did nothing. */
  sealed trait Outcome
  object Outcome {

    /** The files disqualified: page id, title and the person each is by. */
    final case class Disqualified(files: Seq[(Long, String, CoiMembers.Member)]) extends Outcome

    /** The files requalified. */
    final case class Requalified(pageIds: Seq[Long]) extends Outcome

    /** The round is open to its jurors (W7): it must be stopped first. */
    case object Open extends Outcome

    /** A next round's distribution reads the round's votes (it is frozen). */
    case object Frozen extends Outcome

    /** The round is being created, or a distribution of it is queued or running: it may
      * have read the round before the action, and add the files back.
      */
    case object Distributing extends Outcome

    case object NotFound extends Outcome
  }

  /** Locks the round for update (recorded, [[RoundWrites]]) and decides from what it reads
    * under that lock whether an action may run: the contest id, or why not.
    *
    * @param distributionKey the dedupe key of the round's distribution jobs. Creating or
    *                        retrying one locks the round first too, so either the job sees the
    *                        action's rows, or the action sees the job.
    */
  private def gate(roundId: Long, distributionKey: String)(implicit session: DBSession): Either[Outcome, Long] =
    sql"""SELECT contest_id, distributing, freeze_count, ${Round.openCond(sqls"round")}
          FROM round WHERE id = $roundId FOR UPDATE"""
      .map(rs => (rs.long(1), rs.booleanOpt(2).getOrElse(false), rs.int(3), rs.booleanOpt(4).getOrElse(false)))
      .single() match {
      case None => Left(Outcome.NotFound)
      case Some((contestId, distributing, frozen, open)) =>
        RoundWrites.recordRounds(Seq(roundId))
        // a plain read under the round's lock (READ COMMITTED: the latest committed job)
        def jobActive = sql"SELECT 1 FROM job WHERE active_dedupe_key = $distributionKey".map(_ => ()).single().isDefined
        if (distributing || jobActive) Left(Outcome.Distributing)
        else if (frozen > 0) Left(Outcome.Frozen)
        else if (open) Left(Outcome.Open)
        else Right(contestId)
    }

  /** Disqualifies the round's conflict-of-interest files that aren't yet, in one transaction
    * with the round locked first, while the round is not open ([[gate]]).
    *
    * @param by              the admin who asked: `dq_by` and the log line
    * @param distributionKey the dedupe key of the round's distribution jobs ([[gate]])
    * @param only            only these of them (the conflicts page's selection), if given:
    *                        a file no longer a conflict is never disqualified
    * @param excludeCoi      also set the round's [[Round.excludeCoi]] (the round form), also
    *                        when the round is open or frozen (refused, but no distribution of
    *                        it is active)
    */
  def disqualify(
      roundId: Long,
      by: User,
      distributionKey: String,
      only: Option[Set[Long]] = None,
      excludeCoi: Boolean = false
  ): Outcome = {
    def setFlag()(implicit session: DBSession): Unit =
      if (excludeCoi) sql"UPDATE round SET exclude_coi = TRUE WHERE id = $roundId".update(): Unit
    val outcome = WriteTx.write { implicit session =>
      gate(roundId, distributionKey) match {
        // no distribution of it is active: the flag, set under the round's lock, applies to
        // its later ones (review finding 4)
        case Left(refused @ (Outcome.Open | Outcome.Frozen)) =>
          setFlag()
          refused
        case Left(refused) => refused
        case Right(contestId) =>
          setFlag()
          val conflicts = CoiMembers.roundFiles(roundId, CoiMembers.forContest(contestId))
            .filter { case (pageId, _, _) => only.forall(_.contains(pageId)) }
          val pageIds = conflicts.map(_._1)
          // the round (above), then the files' votes moved aside (vote, vote_aside,
          // criteria_rate, their rows `deleted` for now), then the rows marked `dq`: round_image last
          ImageJdbc.moveVotesAsideInRound(roundId, pageIds)
          RoundImageJdbc.disqualify(roundId, conflicts.map { case (p, _, m) => p -> m.describe }.toMap, DqCode.Coi,
            by.id)
          Outcome.Disqualified(conflicts)
      }
    }
    outcome match {
      case Outcome.Disqualified(files) if files.nonEmpty =>
        RoundImageCounts.invalidate(roundId)
        logger.info(
          s"Round $roundId: disqualified ${files.size} files as conflicts of interest, by user ${by.getId}: " +
            files.map { case (pageId, _, m) => s"$pageId (user ${m.userId}, ${m.role})" }.mkString(", ")
        )
      case _ =>
    }
    outcome
  }

  /** Requalifies the round's disqualified files among `pageIds` (D8), in one transaction with
    * the round locked first, while the round is not open ([[gate]]): their votes back with
    * their exact rates and criteria rates, their rows `in`, as before. A file deleted on
    * Commons meanwhile too: a round that is not open keeps a deleted file's votes until it
    * opens ([[services.imports.DeletedFiles]]), so it is `in` as it was (review finding 1; the
    * plan's §5 first had it `deleted`, votes aside). A requalified file is offered again by a
    * later re-check while its author still matches (no overrides yet, plan §7).
    *
    * @param reason one of [[RequalifyReason.All]]
    */
  def requalify(roundId: Long, pageIds: Seq[Long], reason: String, by: User, distributionKey: String): Outcome = {
    require(RequalifyReason.All.contains(reason), s"unknown requalify reason $reason")
    val outcome = WriteTx.write { implicit session =>
      gate(roundId, distributionKey) match {
        case Left(refused) => refused
        case Right(_) =>
          val requested = pageIds.distinct.sorted
          // under the round's lock, which every writer of its rows holds: read plainly
          val dq =
            if (requested.isEmpty) Nil
            else
              sql"""SELECT page_id FROM round_image WHERE round_id = $roundId AND page_id IN ($requested)
                    AND status = ${Status.Dq} ORDER BY page_id""".map(_.long(1)).list()
          ImageJdbc.moveVotesBackInRound(roundId, dq)
          // every one of them follows its votes now (one with none left leaves the round, as
          // before V84)
          RoundImageJdbc.requalifyRows(roundId, dq)
          Outcome.Requalified(dq)
      }
    }
    outcome match {
      case Outcome.Requalified(files) if files.nonEmpty =>
        RoundImageCounts.invalidate(roundId)
        logger.info(s"Round $roundId: requalified ${files.size} files ($reason), by user ${by.getId}: ${files.mkString(", ")}")
      case _ =>
    }
    outcome
  }

  /** A disqualified file on the conflicts page: its row, title, and whether its author is a
    * conflict of interest now (the re-check: a match that ended is offered for requalifying).
    */
  final case class DisqualifiedFile(row: DqRow, title: String, matchNow: Option[CoiMembers.Member])

  /** The round's conflicts page (the re-check, plan §7): the files in it that are conflicts
    * of interest now and not disqualified, and the disqualified ones, with whether they
    * still match. Read only; nothing is automatic.
    */
  final case class Review(matches: Seq[(Long, String, CoiMembers.Member)], disqualified: Seq[DisqualifiedFile]) {
    def ended: Seq[DisqualifiedFile] = disqualified.filter(_.matchNow.isEmpty)
  }

  def review(round: Round)(implicit session: DBSession = AutoSession): Review = {
    val members = CoiMembers.forContest(round.contestId)
    val rows = RoundImageJdbc.disqualified(round.getId)
    val files =
      if (rows.isEmpty) Map.empty[Long, (String, Option[String])]
      else
        rows.map(_.pageId).grouped(RoundImageJdbc.Chunk).flatMap { chunk =>
          sql"SELECT page_id, title, author FROM images WHERE page_id IN ($chunk)"
            .map(rs => rs.long(1) -> (rs.string(2), rs.stringOpt(3))).list()
        }.toMap
    Review(
      CoiMembers.roundFiles(round.getId, members),
      rows.map { row =>
        val (title, author) = files.getOrElse(row.pageId, (s"#${row.pageId}", None))
        DisqualifiedFile(row, title, CoiMembers.matchOf(members)(author))
      }
    )
  }
}
