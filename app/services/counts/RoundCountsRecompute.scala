package services.counts

import db.scalikejdbc.{RoundCounts, WriteTx}
import db.scalikejdbc.RoundCounts.RecountStep
import play.api.{Configuration, Logger}
import services.distribution.RoundDistributionHandler

import scala.collection.mutable
import scala.concurrent.duration._
import scala.util.control.NonFatal

/** Brings the rounds' `round_image` counts in line with their votes ([[RoundCounts]], P2a), one
  * window (one short transaction, [[WriteTx]]: retried on a deadlock) at a time: first every
  * round's files marked dirty by votes, then the full recounts of the rounds whose generation
  * moved (bulk writes; after the deploy, every round), open rounds first. A tick stops starting
  * windows once its `budget` is spent; a recount left part-way goes on at the next tick from
  * where it stopped (kept here: after a restart it starts again, which is only work). A round
  * being distributed is recounted once its distribution has ended.
  *
  * Not a job ([[services.jobs.JobScheduler]]): a job row per tick would pile up in `job`, and
  * the work needs no lease: every window commits on its own and the marks it leaves are in the
  * database. Two instances would only do some windows twice (each window holds its round).
  */
class RoundCountsRecompute(settings: RoundCountsRecompute.Settings = RoundCountsRecompute.Settings()) {

  import RoundCountsRecompute._

  private val logger = Logger("round.counts")

  /** Recounts under way: round id -> (the generation it started from, the last page id done). */
  private val recounts = mutable.Map.empty[Long, (Long, Long)]

  /** One tick (or, with `budget` None, until nothing is left to do but rounds being
    * distributed). Errors are logged with their class only and leave the round for the next
    * tick.
    */
  def runOnce(budget: Option[FiniteDuration] = Some(settings.budget)): Summary = synchronized {
    val deadline = budget.map(_.fromNow)
    def timeLeft = deadline.forall(_.hasTimeLeft())
    var summary = Summary()

    // 1. files marked by votes: cheap, and what jurors' work changes
    val marked = guarded(RoundCounts.roundsWithDirtyFiles())(Nil)
    marked.foreach { roundId =>
      var more = true
      while (more && timeLeft) guarded {
        val w = WriteTx.write(implicit s => RoundCounts.fileWindow(roundId, settings.window))
        summary = summary.add(w, full = false)
        more = w.next.isDefined
      } { more = false }
    }

    // 2. full recounts: those under way first, then open rounds, then the newest
    val due = guarded(RoundCounts.roundsToRecount())(Nil)
    val order = recounts.keys.toSeq.filter(due.contains) ++ due.filterNot(recounts.contains)
    recounts.keys.toSeq.filterNot(due.contains).foreach(recounts.remove) // finished elsewhere or gone
    order.foreach { roundId =>
      var more = true
      while (more && timeLeft) guarded {
        val (gen, after) = recounts.get(roundId) match {
          case Some((g, a)) => (Some(g), Some(a))
          case None         => (None, None)
        }
        WriteTx.write(implicit s =>
          RoundCounts.roundWindow(roundId, gen, after, RoundDistributionHandler.dedupeKey(roundId), settings.window)) match {
          case RecountStep.Ran(w, from) =>
            summary = summary.add(w, full = true)
            w.next match {
              case Some(next) => recounts(roundId) = from -> next
              case None =>
                recounts.remove(roundId)
                summary = summary.copy(roundsRecounted = summary.roundsRecounted + 1)
                more = false
            }
          case RecountStep.Distributing =>
            recounts.remove(roundId)
            summary = summary.copy(skipped = summary.skipped + 1)
            more = false
          case RecountStep.Clean | RecountStep.Gone =>
            recounts.remove(roundId)
            more = false
        }
      } { recounts.remove(roundId); more = false }
    }
    if (summary.changed > 0 || summary.roundsRecounted > 0)
      logger.info(s"round_counts windows=${summary.windows} files=${summary.files} changed=${summary.changed} " +
        s"cleared=${summary.cleared} recounted=${summary.roundsRecounted} skipped=${summary.skipped}")
    summary
  }

  /** Runs ticks without a budget until no round is left to recount but those being
    * distributed (scripts, specs, the Gatling fixture).
    */
  def untilClean(): Summary = runOnce(budget = None)

  private def guarded[A](f: => A)(onError: => A): A =
    try f
    catch {
      case NonFatal(e) =>
        // the class only: a driver's message may quote values (db practices §1.6)
        logger.warn(s"round_counts_failed error=${e.getClass.getSimpleName}")
        onError
    }
}

object RoundCountsRecompute {

  /** What a tick did: windows run, files read, counts changed, dirty entries cleared, full
    * recounts finished, rounds left for their distribution.
    */
  final case class Summary(windows: Int = 0, files: Int = 0, changed: Int = 0, cleared: Int = 0,
      roundsRecounted: Int = 0, skipped: Int = 0) {
    private[counts] def add(w: RoundCounts.Window, full: Boolean): Summary =
      copy(windows = windows + 1, files = files + w.files, changed = changed + w.changed, cleared = cleared + w.cleared)
  }

  /** `roundCounts.*` in application.conf.
    * @param background a timer runs ticks (`mode = background`); `manual`: specs call [[RoundCountsRecompute.runOnce]]
    */
  final case class Settings(
      background: Boolean = true,
      interval: FiniteDuration = 5.seconds,
      window: Int = RoundCounts.DefaultWindow,
      budget: FiniteDuration = 2.seconds
  )

  object Settings {
    def from(config: Configuration): Settings = {
      val c = config.getOptional[Configuration]("roundCounts").getOrElse(Configuration.empty)
      val d = Settings()
      Settings(
        background = c.getOptional[String]("mode").forall(_ != "manual"),
        interval = c.getOptional[FiniteDuration]("interval").getOrElse(d.interval),
        window = c.getOptional[Int]("window").getOrElse(d.window),
        budget = c.getOptional[FiniteDuration]("budget").getOrElse(d.budget)
      )
    }
  }
}
