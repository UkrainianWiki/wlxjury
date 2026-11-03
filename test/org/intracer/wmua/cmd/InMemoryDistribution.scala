package org.intracer.wmua.cmd

import db.scalikejdbc.{RoundImageCounts, TestVoteWrites, WriteTx, User}
import db.scalikejdbc.Round
import org.intracer.wmua.Image

/** The old in-memory distribution (W8 of docs/plans/2026-11-03-write-path-simplification.md:
  * out of the app, which distributes only by the background job,
  * [[services.distribution.RoundDistributionHandler]]): the reference the job is checked
  * against, and a quick way for specs to fill a round. `import InMemoryDistribution._`.
  */
object InMemoryDistribution {

  implicit class InMemory(val d: DistributeImages) extends AnyVal {

    /** Builds and inserts the vote rows for `images` in `round`, in one transaction (all chunks
      * or none).
      *
      * @return the number of vote rows written
      */
    def distributeImages(round: Round, images: Seq[Image], jurors: Seq[User]): Int = {
      DistributeImages.checkJury(round, jurors)
      val selection = d.newSelection(round, images, jurors)
      WriteTx.write(implicit session => DistributeImages.insertRows(selection))
      round.id.foreach(RoundImageCounts.invalidate(_))
      selection.size
    }

    def distributeImages(round: Round, jurors: Seq[User], prevRounds: Seq[Round], removeUnrated: Boolean = false): Int = {
      if (removeUnrated) TestVoteWrites.removeUnrated(round.getId)
      distributeImages(round, d.imagesByRound(round, prevRounds), jurors)
    }
  }
}
