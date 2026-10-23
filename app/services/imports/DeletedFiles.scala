package services.imports

import db.scalikejdbc.ImageJdbc
import scalikejdbc.DBSession
import services.distribution.RoundDistributionHandler

/** Files deleted on Commons (V63): marked deleted, with their votes moved aside, and back
  * when Commons has them again (an undeletion, seen by the next import).
  *
  * Both leave alone the rounds a distribution is filling: moving votes in or out of them
  * would upset its count check. Such a round's votes are moved when its distribution ends,
  * done, failed or cancelled ([[RoundDistributionHandler]]): aside those of a deleted file,
  * back those of a restored one. Until then the distribution doesn't give a restored file
  * whose votes are aside to other jurors.
  */
object DeletedFiles {

  /** @return the rounds that lost rows */
  def markDeleted(pageIds: Seq[Long])(implicit session: DBSession): Seq[Long] =
    if (pageIds.isEmpty) Nil
    else {
      val busy = RoundDistributionHandler.lockRoundsBeingDistributed() // first: see there
      ImageJdbc.setDeleted(pageIds)
      ImageJdbc.archiveVotes(pageIds, except = busy)
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
      val busy = RoundDistributionHandler.lockRoundsBeingDistributed()
      ImageJdbc.clearDeleted(deleted)
      ImageJdbc.restoreVotes(deleted, except = busy)
    }
  }
}
