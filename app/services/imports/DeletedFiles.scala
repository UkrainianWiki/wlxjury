package services.imports

import db.scalikejdbc.{ImageJdbc, Round, WriteTx}
import scalikejdbc.{DB, DBSession}

/** Files deleted on Commons (V63): marked deleted, with their votes in the open rounds
  * ([[Round.openCond]]) moved aside, and back when Commons has them again (an undeletion,
  * seen by the next import).
  *
  * Finished rounds, and those not started yet, keep a deleted file's votes: past results
  * don't change. A round that opens later moves them aside then ([[Round.setActiveGuarded]]),
  * and so does one that votes are moved into ([[Round.settleDeletedFiles]]).
  *
  * Moving votes doesn't upset a running distribution's count check, which counts the
  * round's votes moved aside too
  * ([[services.distribution.RoundDistributionHandler]]).
  */
object DeletedFiles {

  /** Locks the open rounds, then the files, `pageIds` and `alsoLock` together in page id
    * order ([[ImageJdbc.lockFiles]]), then marks `pageIds` deleted.
    *
    * @param alsoLock files the caller updates next, in the same transaction
    * @return the rounds that lost rows
    */
  def markDeleted(pageIds: Seq[Long], alsoLock: Seq[Long] = Nil)(implicit session: DBSession): Seq[Long] = {
    val open = ImageJdbc.lockOpenRoundsOf(pageIds) // first: see there
    ImageJdbc.lockFiles(pageIds ++ alsoLock)
    ImageJdbc.setDeleted(pageIds)
    ImageJdbc.archiveVotes(pageIds, open)
  }

  /** After [[markDeleted]] committed, in a transaction of its own: moves aside the votes of
    * those of `pageIds` still marked deleted in the rounds open now. [[markDeleted]] reads
    * the rounds from its snapshot, so it misses votes another transaction moved into an open
    * round ([[db.scalikejdbc.VoteJdbc.setRound]]) and committed meanwhile; one that
    * commits after [[markDeleted]] moves them itself ([[Round.settleDeletedFiles]]).
    *
    * @return the rounds that lost rows
    */
  def settle(pageIds: Seq[Long]): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else
      WriteTx.write { implicit session =>
        // the files' open rounds first (h3: rounds before images, as every structural write),
        // then the files still marked deleted, read with a share lock (a restore meanwhile is
        // waited for and seen)
        val open = ImageJdbc.lockOpenRoundsOf(pageIds)
        val deleted = ImageJdbc.lockDeleted(pageIds)
        ImageJdbc.archiveVotes(deleted, open)
      }

  /** Restores those of `pageIds` that were marked deleted or have votes moved aside.
    * Cheap when none were: one read, no locks.
    *
    * @param alsoLock files the caller updates next, in the same transaction: locked with
    *                 those restored, in page id order ([[ImageJdbc.lockFiles]])
    * @return the rounds that gained rows
    */
  def restore(pageIds: Seq[Long], alsoLock: Seq[Long] = Nil)(implicit session: DBSession): Seq[Long] = {
    val deleted = ImageJdbc.deletedOrArchived(pageIds)
    if (deleted.isEmpty) Nil
    else {
      ImageJdbc.lockRoundsOfArchived(deleted) // first: rounds before files and votes, as markDeleted
      ImageJdbc.lockFiles(deleted ++ alsoLock)
      ImageJdbc.clearDeleted(deleted)
      ImageJdbc.restoreVotes(deleted)
    }
  }
}
