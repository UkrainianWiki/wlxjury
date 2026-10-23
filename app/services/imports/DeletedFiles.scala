package services.imports

import db.scalikejdbc.{ImageJdbc, Round}
import scalikejdbc.DBSession

/** Files deleted on Commons (V63): marked deleted, with their votes in the open rounds
  * ([[Round.openCond]]) moved aside, and back when Commons has them again (an undeletion,
  * seen by the next import).
  *
  * Finished rounds, and those not started yet, keep a deleted file's votes: past results
  * don't change. A round that opens later moves them aside then ([[Round.setActiveGuarded]]).
  *
  * Moving votes doesn't upset a running distribution's count check, which counts the
  * round's votes moved aside too
  * ([[services.distribution.RoundDistributionHandler]]).
  */
object DeletedFiles {

  /** @return the rounds that lost rows */
  def markDeleted(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else {
      val open = ImageJdbc.lockOpenRoundsOf(pageIds) // first: see there
      ImageJdbc.setDeleted(pageIds)
      ImageJdbc.archiveVotes(pageIds, open)
    }

  /** Restores those of `pageIds` that were marked deleted or have votes moved aside.
    * Cheap when none were: one read, no locks.
    *
    * @return the rounds that gained rows
    */
  def restore(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] = {
    val deleted = ImageJdbc.deletedOrArchived(pageIds)
    if (deleted.isEmpty) Nil
    else {
      ImageJdbc.clearDeleted(deleted)
      ImageJdbc.restoreVotes(deleted)
    }
  }
}
