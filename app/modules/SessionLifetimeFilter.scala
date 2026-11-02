package modules

import controllers.{Secured, SessionLifetime}
import db.scalikejdbc.User
import org.apache.pekko.stream.Materializer
import play.api.Logger
import play.api.http.HeaderNames.CACHE_CONTROL
import play.api.mvc._
import play.api.mvc.request.{Cell, RequestAttrKey}

import java.util.Locale
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

/** The session's lifetime on every request (security batch S3; [[SessionLifetime]]), before any
  * other filter, so that the CSRF filter and the actions see the session as it is here:
  *
  *  - a signed-in session past its absolute or idle limit loses its sign-in keys
  *    ([[SessionLifetime.SignInKeys]]) for this request and in the browser (logged
  *    `session_expired`);
  *  - a session from before S3 (no [[SessionLifetime.IssuedAt]] or [[SessionLifetime.LastSeen]])
  *    is upgraded in place: if it still resolves by S2's rules (its name, version 0 or the
  *    version and account it has), it gets the account's id and version and both times as now;
  *    otherwise it loses its sign-in. Only these requests read the database here;
  *  - [[SessionLifetime.LastSeen]] is refreshed once it is [[SessionLifetime.Settings.touchEvery]]
  *    old, so that a busy session sets a cookie every few minutes, not on every response.
  *
  * The session cookie leaves without Max-Age (Play's `session.maxAge` is unset: a browser-session
  * cookie) unless "Keep me signed in" was checked ([[SessionLifetime.Keep]]): then this filter
  * writes it, the same cookie with Max-Age ([[SessionLifetime.maxAgeSeconds]]). A response a
  * shared cache may keep never gets a session cookie from here (as Play's CSRF filter).
  *
  * The account's version (a sign-out everywhere, a password change, root's "end sessions") is
  * checked by [[Secured.sessionUserOf]], as before, with no extra query here.
  */
@Singleton
class SessionLifetimeFilter(
    sessionBaker: SessionCookieBaker,
    settings: SessionLifetime.Settings,
    now: () => Long
)(implicit val mat: Materializer, ec: ExecutionContext)
    extends EssentialFilter {

  @Inject() def this(sessionBaker: SessionCookieBaker)(implicit mat: Materializer, ec: ExecutionContext) =
    this(sessionBaker, SessionLifetime.default, () => SessionLifetime.nowSeconds())

  private val logger = Logger("security.auth")

  override def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    val session = request.session
    val at = now()
    // signed out: only a sign-in's new session may need this filter's cookie
    if (session.get(Secured.UserName).isEmpty) next(request).map(respond(_, None, at))
    else {
      val checked: Either[String, Session] =
        SessionLifetime.expiry(session, at, settings).toLeft(session).flatMap { live =>
          if (SessionLifetime.needsUpgrade(live)) upgraded(live, at).toRight("old_cookie_unmatched")
          else Right(live)
        }
      checked match {
        case Left(reason) =>
          logger.info(s"session_expired reason=$reason accountId=${session.get(Secured.AccountId).getOrElse("none")}")
          val signedOut = session -- SessionLifetime.SignInKeys
          next(withSession(request, signedOut)).map(respond(_, Some(signedOut), at))
        case Right(live) =>
          val touched =
            if (SessionLifetime.needsTouch(live, at, settings)) live + (SessionLifetime.LastSeen -> at.toString) else live
          if (touched == session) next(request).map(respond(_, None, at))
          else next(withSession(request, touched)).map(respond(_, Some(touched), at))
      }
    }
  }

  /** A session from before S3 with the account's id and version and both times as now, if it
    * still resolves by S2's rules ([[Secured.sessionMatches]], by its exact name); else None.
    */
  private def upgraded(session: Session, at: Long): Option[Session] =
    for {
      name <- session.get(Secured.UserName)
      user <- User.byUserName(Secured.normalizeUserName(name))
      if Secured.sessionMatches(user, session.get(Secured.AuthVersion), session.get(Secured.AccountId))
    } yield {
      logger.info(s"session_upgraded accountId=${user.getId}")
      session +
        (Secured.AccountId -> user.getId.toString) +
        (Secured.AuthVersion -> user.authVersion.toString) +
        (SessionLifetime.IssuedAt -> session.get(SessionLifetime.IssuedAt).getOrElse(at.toString)) +
        (SessionLifetime.LastSeen -> at.toString)
    }

  private def withSession(request: RequestHeader, session: Session): RequestHeader =
    request.addAttr(RequestAttrKey.Session, Cell(session))

  /** The response with its session: the action's own if it set one, else `changed` (an
    * upgrade, a refresh, a sign-in taken out); a persistent one as this filter's cookie.
    */
  private def respond(result: Result, changed: Option[Session], at: Long): Result =
    if (cacheableBySharedCache(result)) result
    else
      result.newSession.orElse(changed) match {
        case Some(s) if s.get(Secured.UserName).nonEmpty && SessionLifetime.keep(s) =>
          val cookie = sessionBaker.encodeAsCookie(s).copy(maxAge = Some(SessionLifetime.maxAgeSeconds(s, at, settings)))
          result.copy(newSession = None).withCookies(cookie)
        case Some(s) => result.withSession(s)
        case None    => result
      }

  /** A Cache-Control without no-store, no-cache or private (Play's CSRF filter's rule). */
  private def cacheableBySharedCache(result: Result): Boolean =
    result.header.headers.get(CACHE_CONTROL).exists { value =>
      val directives = value.toLowerCase(Locale.ROOT).split(",").map(_.trim)
      !directives.exists(d => d == "no-store" || d == "no-cache" || d == "private" || d.startsWith("private="))
    }
}
