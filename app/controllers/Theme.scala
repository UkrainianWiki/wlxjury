package controllers

import play.api.http.SessionConfiguration
import play.api.mvc.{Cookie, RequestHeader, Result}

import javax.inject.Inject
import scala.concurrent.duration._

/** The colour mode a user picks: light, dark, or auto (the browser's `prefers-color-scheme`).
  * Read from the [[Theme.CookieName]] cookie on every page (layout.scala.html) and kept in
  * `users.theme` so that it follows the user to another browser (set on sign-in). Any other
  * value, from the cookie, the database or a form, is [[Theme.Auto]]: only these three
  * fixed strings are ever written into a page.
  */
sealed abstract class Theme(val value: String)

object Theme {
  case object Light extends Theme("light")
  case object Dark  extends Theme("dark")
  case object Auto  extends Theme("auto")

  val All: Seq[Theme] = Seq(Light, Dark, Auto)

  /** Not HttpOnly: the login page's switch (theme-switch.js) writes it. The server uses it
    * only to pick the page's colours, never for authorisation.
    */
  val CookieName = "theme"

  /** One of the three values, else none. */
  def find(value: String): Option[Theme] = All.find(_.value == value)

  def parse(value: String): Theme = find(value).getOrElse(Auto)

  def parse(value: Option[String]): Theme = value.fold[Theme](Auto)(parse)

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
