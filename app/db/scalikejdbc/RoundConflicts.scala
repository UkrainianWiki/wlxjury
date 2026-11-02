package db.scalikejdbc

import play.api.Logger
import scalikejdbc._

/** "Leave out conflicts of interest now": takes out of a round the files that are
  * conflicts of interest ([[CoiMembers]]) with their votes, for a round created before the
  * round form's checkbox, or after a member was added. The rates of such a file are not
  * kept: its `vote` rows, those moved aside to `vote_aside` (so that restoring
  * the file on Commons brings nothing back, as [[VoteJdbc.removeImage]]) and their
  * `criteria_rate` rows (which reference `vote.id` without a foreign key) are deleted
  * ([[VoteJdbc.deleteVotes]]).
  * The round then leaves them out of its later distributions too ([[Round.excludeCoi]]).
  *
  * Nothing is kept to show it later, so each removal is a log line on the `coi` logger,
  * written at INFO (both logback.xml files).
  */
object RoundConflicts {

  val logger: Logger = Logger("coi")

  /** What a removal did, or why it did nothing. */
  sealed trait Outcome
  object Outcome {

    /** The files taken out: page id, title and the person each is by. */
    final case class Removed(files: Seq[(Long, String, CoiMembers.Member)]) extends Outcome

    /** The round is not open to its jurors now: stopped, being created or frozen while a
      * distribution reads its votes.
      */
    case object NotOpen extends Outcome

    /** A distribution of the round is queued or running: it may have read the round before
      * [[Round.excludeCoi]] was set, and add the files back.
      */
    case object Distributing extends Outcome

    case object NotFound extends Outcome
  }

  /** Removes the round's conflict-of-interest files and their votes in one transaction,
    * with the round locked (rounds before votes, as [[services.imports.DeletedFiles]] locks
    * them), and sets the round's [[Round.excludeCoi]].
    *
    * @param by              the admin who asked, for the log line
    * @param distributionKey the dedupe key of the round's distribution jobs: one that is
    *                        active refuses the removal. Creating or retrying such a job locks
    *                        the round first too, so either the job sees the round's
    *                        [[Round.excludeCoi]], or the removal sees the job.
    */
  def removeFromRound(roundId: Long, by: User, distributionKey: String): Outcome = {
    val outcome = DB.localTx { implicit session =>
      sql"""SELECT contest_id, active = 1 AND distributing = 0 AND freeze_count = 0
            FROM round WHERE id = $roundId FOR UPDATE"""
        .map(rs => rs.long(1) -> rs.boolean(2))
        .single() match {
        case None                => Outcome.NotFound
        case Some((_, false))    => Outcome.NotOpen
        case Some(_) if sql"SELECT 1 FROM job WHERE active_dedupe_key = $distributionKey"
                          .map(_ => ()).single().isDefined =>
          Outcome.Distributing
        case Some((contestId, _)) =>
          sql"UPDATE round SET exclude_coi = TRUE WHERE id = $roundId".update()
          val conflicts = CoiMembers.roundFiles(roundId, CoiMembers.forContest(contestId))
          val pageIds = conflicts.map(_._1)
          // the votes locked before their criteria rates are deleted, as every vote delete.
          // Lock order: the round, then the votes, as a restore of deleted files locks them
          // (CriteriaRateSpec "lock the rounds before the files' votes when restoring them"):
          // a change here must keep it
          if (pageIds.nonEmpty) VoteJdbc.deleteVotes(sqls"v.round_id = $roundId AND v.page_id IN ($pageIds)")
          Outcome.Removed(conflicts)
      }
    }
    outcome match {
      case Outcome.Removed(files) =>
        if (files.nonEmpty) RoundImageCounts.invalidate(roundId)
        logger.info(
          s"Round $roundId: removed ${files.size} files as conflicts of interest, by user ${by.getId}: " +
            files.map { case (pageId, _, m) => s"$pageId by ${m.account} (user ${m.userId}, ${m.role})" }.mkString(", ")
        )
      case _ =>
    }
    outcome
  }
}
