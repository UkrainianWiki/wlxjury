package db.scalikejdbc

import play.api.Logger
import scalikejdbc._

/** "Leave out conflicts of interest now": takes out of a round the files that are
  * conflicts of interest ([[CoiMembers]]) with their votes, for a round created before the
  * round form's checkbox, or after a member was added. The rates of such a file are not
  * kept: its `selection` rows, those moved aside to `selection_deleted` (so that restoring
  * the file on Commons brings nothing back, as [[SelectionJdbc.removeImage]]) and their
  * `criteria_rate` rows (which reference `selection.id` without a foreign key) are deleted.
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
            FROM rounds WHERE id = $roundId FOR UPDATE"""
        .map(rs => rs.long(1) -> rs.boolean(2))
        .single() match {
        case None                => Outcome.NotFound
        case Some((_, false))    => Outcome.NotOpen
        case Some(_) if sql"SELECT 1 FROM job WHERE active_dedupe_key = $distributionKey"
                          .map(_ => ()).single().isDefined =>
          Outcome.Distributing
        case Some((contestId, _)) =>
          sql"UPDATE rounds SET exclude_coi = TRUE WHERE id = $roundId".update()
          val members = CoiMembers.forContest(contestId)
          val conflicts =
            if (members.isEmpty) Nil
            else
              sql"""SELECT i.page_id, i.title, i.author FROM (
                      SELECT page_id FROM selection WHERE round_id = $roundId
                      UNION SELECT page_id FROM selection_deleted WHERE round_id = $roundId) p
                    JOIN images i ON i.page_id = p.page_id
                    WHERE i.author IN (${members.keys.toSeq}) ORDER BY i.page_id"""
                .map(rs => (rs.long(1), rs.string(2), rs.string(3)))
                .list()
                .flatMap { case (pageId, title, author) =>
                  CoiMembers.matchOf(members)(Some(author)).map((pageId, title, _))
                }
          val pageIds = conflicts.map(_._1)
          if (pageIds.nonEmpty) {
            Seq(sqls"selection", sqls"selection_deleted").foreach { table =>
              sql"""DELETE cr FROM criteria_rate cr JOIN $table s ON s.id = cr.selection
                    WHERE s.round_id = $roundId AND s.page_id IN ($pageIds)""".update()
            }
            sql"DELETE FROM selection WHERE round_id = $roundId AND page_id IN ($pageIds)".update()
            sql"DELETE FROM selection_deleted WHERE round_id = $roundId AND page_id IN ($pageIds)".update()
          }
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
