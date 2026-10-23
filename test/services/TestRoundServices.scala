package services

import db.scalikejdbc.{ImageJdbc, Round}
import org.intracer.wmua.cmd.DistributeImages
import play.api.Configuration
import services.distribution.RoundDistributionHandler
import services.imports.{CommonsSource, FakeCommonsSource}
import services.jobs.{JobHandler, JobScheduler}

/** A [[RoundService]] for specs without a Play app: its distribution jobs run inline, to
  * their end, in the call that starts them (as with `jobs.mode = inline`).
  */
object TestRoundServices {

  /** A Commons with no files: a listed file not stored is missing. */
  def emptyCommons: CommonsSource = new FakeCommonsSource(Nil)

  def handler(
      chunkRows: Int = RoundDistributionHandler.DefaultChunkRows,
      commons: CommonsSource = emptyCommons
  ): RoundDistributionHandler =
    new RoundDistributionHandler(new DistributeImages(ImageJdbc), config(chunkRows), commons)

  def config(chunkRows: Int): Configuration =
    Configuration("jobs.roundDistribution.chunkRows" -> chunkRows)

  def inlineScheduler(handlers: JobHandler*): JobScheduler =
    new JobScheduler(handlers, JobScheduler.Settings(mode = JobScheduler.Mode.Inline))

  def roundService(distribution: RoundDistributionHandler = handler()): RoundService =
    new RoundService(Round, inlineScheduler(distribution), distribution)
}
