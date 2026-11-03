package db.scalikejdbc

import scalikejdbc._

/** Vote writers the app no longer has (docs/plans/2026-11-03-write-path-simplification.md,
  * W8): removing a file's or the unrated votes of a round and moving a file to another round
  * had no production caller. Specs and the stress harness still use them, as structural
  * writers that lock their rounds first ([[RoundWrites]]). P3's `removeImage` (D16a: `dq`)
  * is written anew in the app then.
  */
object TestVoteWrites {

  /** Removes the image from the round, also its votes moved aside (V63): restoring its file
    * on Commons must not bring them back. Their criteria rates go with them
    * ([[VoteJdbc.deleteVotes]]).
    */
  def removeImage(pageId: Long, roundId: Long): Unit = {
    WriteTx.write { implicit session =>
      RoundWrites.lock(Seq(roundId))
      VoteJdbc.deleteVotes(sqls"v.page_id = $pageId AND v.round_id = $roundId")
    }
    RoundImageCounts.invalidate(roundId)
  }

  /** Removes the round's unrated votes, also those moved aside (V63), as [[removeImage]]. A
    * partly rated criteria vote (its rate 0 until every criterion is rated, U5) is kept.
    */
  def removeUnrated(roundId: Long): Unit = {
    WriteTx.write { implicit session =>
      RoundWrites.lock(Seq(roundId))
      val unrated = sqls"v.rate = 0 AND v.round_id = $roundId"
      val tables = Seq(sqls"vote", sqls"vote_aside")
      tables.foreach(VoteJdbc.lockVotes(_, unrated))
      val ids = tables.flatMap(t => sql"SELECT v.id FROM $t v WHERE $unrated FOR UPDATE".map(_.long(1)).list())
      val partial = VoteJdbc.votedByCriteria(ids)
      ids.filterNot(partial).grouped(VoteJdbc.DeleteChunk).foreach(chunk => VoteJdbc.deleteVotes(sqls"v.id IN ($chunk)"))
    }
    RoundImageCounts.invalidate(roundId)
  }

  /** Moves the image's votes to another round, also those moved aside (V63): a juror's vote
    * already in the new round wins. Both rounds for update, then the file's image row, then
    * the votes, then round_image. If the new round is open, the votes of a file deleted on
    * Commons are then moved aside there ([[Round.settleDeletedFiles]]).
    */
  def setRound(pageId: Long, oldRoundId: Long, newRoundId: Long): Unit = {
    WriteTx.write { implicit session =>
      RoundWrites.lock(Seq(oldRoundId, newRoundId))
      ImageJdbc.lockFiles(Seq(pageId))
      moveVotes(newRoundId, oldRoundId, sqls"AND src.page_id = $pageId")
    }
    RoundImageCounts.invalidate(oldRoundId, newRoundId)
    Round.settleDeletedFiles(newRoundId)
  }

  /** Moves the source round's votes `andWhere` selects (on `src`) to the target round, in
    * vote and in vote_aside, but those of a juror who has the image in the
    * target round already: they are dropped, with their criteria rates. The moved votes keep
    * their ids, so their criteria rates follow them. Then the files' rows in both rounds
    * follow their votes ([[RoundImageJdbc.sync]]): out of the source round, into the target
    * one (added `manual`).
    */
  private def moveVotes(targetRoundId: Long, sourceRoundId: Long, andWhere: SQLSyntax)(implicit
      session: DBSession
  ): Unit = {
    val pageIds = Seq(sqls"vote", sqls"vote_aside").flatMap { table =>
      val source = sqls"src.round_id = $sourceRoundId $andWhere"
      val inTarget = sqls"""JOIN $table tgt
              ON tgt.round_id = $targetRoundId
             AND tgt.page_id = src.page_id
             AND tgt.jury_id = src.jury_id"""
      val moved = sql"SELECT DISTINCT src.page_id FROM $table src WHERE $source FOR UPDATE".map(_.long(1)).list()
      sql"""DELETE cr FROM $table src $inTarget STRAIGHT_JOIN criteria_rate cr ON cr.vote_id = src.id
            WHERE $source""".update()
      sql"DELETE src FROM $table src $inTarget WHERE $source".update()

      sql"UPDATE $table src SET src.round_id = $targetRoundId WHERE $source".update()
      moved
    }.distinct
    RoundImageJdbc.syncPairs(pageIds.flatMap(p => Seq(sourceRoundId -> p, targetRoundId -> p)))
  }

}
