package modules

import db.scalikejdbc.SessionUpgradeWindowJdbc
import play.api.Logger

import java.util.concurrent.atomic.AtomicReference
import scala.util.control.NonFatal

import SessionUpgradeWindow._

/** Until when (epoch seconds, the app's clock) a session cookie from before S3 is upgraded in
  * place: the end V80 recorded at the S3 deploy (`session_upgrade_window`), read once, at the
  * first such cookie, as the time left on the database clock. A failed read is not kept (the next
  * old cookie tries again) and leaves that request's cookie as it is ([[Unknown]]); no row (or no
  * table: V80 undone) closes the window for good. The window is kept for the JVM's life once read,
  * so undoing V80 takes effect at the next app restart.
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

  /** Whether a pre-S3 cookie seen at `at` (epoch seconds) may still be upgraded: [[Open]] before
    * the window's end, [[Closed]] after it or without a row, [[Unknown]] if it could not be read
    * (a database hiccup): then the cookie is neither upgraded nor signed out.
    */
  def state(at: Long): State =
    end() match {
      case Left(())            => Unknown
      case Right(Some(e)) if at < e => Open
      case Right(_)            => Closed
    }

  /** The window's end (None: no row), or Left if it could not be read (not kept: tried again). */
  private def end(): Either[Unit, Option[Long]] =
    until.get().map(Right(_)).getOrElse {
      try {
        val read = secondsLeft().map(now() + _)
        if (read.isEmpty) logger.warn("session_upgrade_window_missing")
        until.compareAndSet(None, Some(read))
        Right(until.get().flatten)
      } catch {
        case NonFatal(e) =>
          logger.warn(s"session_upgrade_window_unreadable error=${e.getClass.getSimpleName}")
          Left(())
      }
    }
}

object SessionUpgradeWindow {
  sealed trait State
  case object Open extends State
  case object Closed extends State
  case object Unknown extends State
}
