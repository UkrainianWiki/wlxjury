package controllers

import play.api.http.SessionConfiguration
import play.api.mvc.{Cookie, RequestHeader, Result}

import javax.inject.Inject
import scala.concurrent.duration._

/** The colour mode a user picks: light, dark, or auto (the browser's `prefers-color-scheme`).
  * Read from the [[Theme.CookieName]] cookie on every page (layout.scala.html) and kept in
  * `users.theme` so that it follows the user to another browser (set on sign-in). A user
  * who never chose one (no cookie, `users.theme` NULL) gets [[Theme.Default]], dark; so
  * does any other value of the cookie. A form's or the database's other value changes
  * nothing ([[Theme.find]]). Only these three fixed strings are ever written into a page.
  */
sealed abstract class Theme(val value: String, val icon: String)

object Theme {
  case object Light extends Theme("light", "sun-fill")
  case object Dark  extends Theme("dark", "moon-stars-fill")
  case object Auto  extends Theme("auto", "circle-half")

  /** In the switches' order. `icon`: its Bootstrap Icons name. */
  val All: Seq[Theme] = Seq(Light, Dark, Auto)

  /** Not HttpOnly: the login page's switch (theme-switch.js) writes it. The server uses it
    * only to pick the page's colours, never for authorisation.
    */
  val CookieName = "theme"

  /** One of the three values, else none. */
  def find(value: String): Option[Theme] = All.find(_.value == value)

  /** The mode of a user who never chose one: dark, as the galleries always were. An explicit
    * choice, auto included, is kept.
    */
  val Default: Theme = Dark

  /** One of the three values, else [[Default]]. */
  def parse(value: String): Theme = find(value).getOrElse(Default)

  def parse(value: Option[String]): Theme = value.fold(Default)(parse)

  /** The request's mode, from its cookie. */
  def of(request: RequestHeader): Theme = parse(request.cookies.get(CookieName).map(_.value))
}

/** Sets the [[Theme.CookieName]] cookie: `SameSite=Lax`, `Secure` when the session cookie is
  * (`play.http.session.secure`), path `/`, kept for a year (renewed whenever it is set).
  */
class ThemeCookies @Inject() (session: SessionConfiguration) {

  def cookie(theme: Theme): Cookie =
    Cookie(
      Theme.CookieName,
      theme.value,
      maxAge = Some(ThemeCookies.MaxAge.toSeconds.toInt),
      path = "/",
      secure = session.secure,
      httpOnly = false,
      sameSite = Some(Cookie.SameSite.Lax)
    )

  def set(result: Result, theme: Theme): Result = result.withCookies(cookie(theme))

  /** The result with the cookie of the user's stored mode, if they ever chose one. */
  def setStored(result: Result, stored: Option[String]): Result =
    stored.flatMap(Theme.find).fold(result)(set(result, _))
}

object ThemeCookies {
  val MaxAge: FiniteDuration = 365.days

  /** For controllers built in tests without the injector: the default session config. */
  def default: ThemeCookies = new ThemeCookies(SessionConfiguration())
}
