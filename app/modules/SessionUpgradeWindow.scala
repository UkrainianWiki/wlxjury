package modules

import db.scalikejdbc.SessionUpgradeWindowJdbc
import play.api.Logger

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

/** Until when (epoch seconds, the app's clock) a session cookie from before S3 is upgraded in
  * place: the end V80 recorded at the S3 deploy (`session_upgrade_window`), read once, at the
  * first such cookie, as the time left on the database clock. A failed read is not kept (the next
  * old cookie tries again) and closes the window for that request; no row closes it for good.
  *
  * @param secondsLeft the window's time left, from the database ([[SessionUpgradeWindowJdbc]])
  * @param now         the app's clock, in epoch seconds
  */
class SessionUpgradeWindow(
    secondsLeft: () => Option[Long] = () => SessionUpgradeWindowJdbc.secondsLeft(),
    now: () => Long = () => controllers.SessionLifetime.nowSeconds()
) {

  private val logger = Logger("security.auth")

  /** The window's end once read: None before, Some(None) without a row. */
  private val until = new AtomicReference[Option[Option[Long]]](None)

  /** Whether a pre-S3 cookie seen at `at` (epoch seconds) may still be upgraded. */
  def open(at: Long): Boolean =
    end().exists(at < _)

  private def end(): Option[Long] =
    until.get().getOrElse {
      try {
        val read = secondsLeft().map(now() + _)
        if (read.isEmpty) logger.warn("session_upgrade_window_missing")
        until.compareAndSet(None, Some(read))
        until.get().flatten
      } catch {
        case NonFatal(e) =>
          logger.warn(s"session_upgrade_window_unreadable error=${e.getClass.getSimpleName}")
          None
      }
    }
}
