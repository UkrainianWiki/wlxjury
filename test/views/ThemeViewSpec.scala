package views

import controllers.Theme
import db.scalikejdbc.User
import play.api.data.Form
import play.api.data.Forms.{nonEmptyText, tuple}
import play.api.i18n.{Lang, Messages}
import play.api.mvc.{Cookie, Flash, RequestHeader}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers, PlaySpecification}
import play.twirl.api.Html

/** The colour mode on every shell (layout.scala.html): `data-bs-theme` light or dark from
  * the theme cookie; with no cookie or any other value, auto: no data-bs-theme, the auto
  * marker and the script that sets it from prefers-color-scheme, first in <head>. The
  * cookie's own text never reaches the page.
  */
class ThemeViewSpec extends PlaySpecification {

  implicit val messages: Messages = Helpers.stubMessagesApi().preferred(Seq(Lang("en")))
  implicit val flash: Flash = Flash()

  private val juror = User("Juror", "juror@example.org", id = Some(1), roles = Set("jury"), contestId = Some(1))

  private def requestWith(cookie: Option[String]): RequestHeader =
    FakeRequest("GET", "/").withCookies(cookie.map(Cookie(Theme.CookieName, _)).toSeq: _*).withCSRFToken

  /** Each shell's page, rendered with the theme cookie `cookie`. */
  private def shells(cookie: Option[String]): Seq[(String, String)] = {
    implicit val request: RequestHeader = requestWith(cookie)
    val cookies = cookie.map(Cookie(Theme.CookieName, _)).toSeq
    Seq(
      "main" -> contentAsString(views.html.main("gallery")(Html(""))),
      "main signed in" -> contentAsString(views.html.main("gallery", juror, juror.getId)(Html(""))),
      "main gallery" -> contentAsString(views.html.main("gallery", juror, juror.getId, gallery = true)(Html(""))),
      "mainAdmin" -> contentAsString(views.html.mainAdmin("users")(Html(""))),
      "main_large" -> LargeViewRenders.large(LargeViewRenders.ratedRound, LargeViewRenders.middle, cookies = cookies),
      "contestFile" -> LargeViewRenders.contestFile(LargeViewRenders.middle, cookies),
      "errorPage" -> contentAsString(views.html.errorPage(None, "error.title", "error.not.found")),
      "errorPage signed in" -> contentAsString(views.html.errorPage(Some(juror), "error.title", "error.not.found"))
    )
  }

  /** The <html> start tag. */
  private def htmlTag(page: String): String = {
    val start = page.indexOf("<html")
    page.substring(start, page.indexOf('>', start) + 1)
  }

  "every shell" should {

    "render light or dark from the cookie, without the auto script's effect" in {
      forall(Seq("light", "dark")) { mode =>
        forall(shells(Some(mode))) { case (name, page) =>
          s"$name: ${htmlTag(page)}" must contain(s"""data-bs-theme="$mode"""")
          s"$name: ${htmlTag(page)}" must contain(s"""data-theme-pref="$mode"""")
        }
      }
    }

    "render auto without a cookie: the marker, no data-bs-theme, and the script first in <head>" in {
      forall(shells(None)) { case (name, page) =>
        val tag = s"$name: ${htmlTag(page)}"
        tag must contain("""data-theme-pref="auto"""")
        tag must not(contain("data-bs-theme"))
        val head = page.substring(page.indexOf("<head>"))
        val script = head.indexOf("prefers-color-scheme: dark")
        script must be_>(0)
        // before any stylesheet, so that the page is never painted in the other mode
        script must be_<(head.indexOf("<link"))
        head.indexOf("<script>") must be_<(head.indexOf("<link"))
      }
    }

    "render auto for any other value, never echoing the cookie" in {
      val garbage = Seq("\"><script>alert(1)</script>", "dark\" onload=\"x", "DARK", "auto ", "<b>light</b>", "")
      forall(garbage) { value =>
        forall(shells(Some(value))) { case (name, page) =>
          val tag = s"$name: ${htmlTag(page)}"
          tag must contain("""data-theme-pref="auto"""")
          tag must not(contain("data-bs-theme"))
          if (value.nonEmpty && value != "DARK" && value != "auto ") page must not(contain(value))
          page must not(contain("alert(1)"))
          page must not(contain("onload=\"x"))
        }
      }
    }
  }

  "the user menu's switch" should {
    "come back to the page, but not to an error page's URL" in {
      implicit val request: RequestHeader = FakeRequest("GET", "/gallery/round/1/user/1/page/2?sort=rate").withCSRFToken
      contentAsString(views.html.main("gallery", juror, juror.getId)(Html(""))) must
        contain("""name="returnTo" value="/gallery/round/1/user/1/page/2?sort=rate"""")

      val errorRequest: RequestHeader = FakeRequest("GET", "/error?message=Account+suspended").withCSRFToken
      val error = contentAsString(views.html.errorPage(Some(juror), "error.title", "error.unknown")(messages, errorRequest))
      error must contain("""action="/theme"""")
      error must not(contain("suspended"))
      error must not(contain("""name="returnTo""""))
    }
  }

  "the parser" should {
    "take light, dark and auto only" in {
      Theme.parse("light") === Theme.Light
      Theme.parse("dark") === Theme.Dark
      Theme.parse("auto") === Theme.Auto
      Theme.parse("Dark") === Theme.Auto
      Theme.parse(" dark") === Theme.Auto
      Theme.parse(Option.empty[String]) === Theme.Auto
      Theme.find("purple") must beNone
    }
  }

  "the login page" should {
    val form = Form(tuple("login" -> nonEmptyText, "password" -> nonEmptyText))

    "offer a switch that sets the cookie in the browser, with no form or request" in {
      implicit val request: RequestHeader = requestWith(Some("dark"))
      val page = contentAsString(views.html.login(form))
      val switch = page.substring(page.indexOf("""id="theme-switch""""))
      switch must contain("""data-theme-value="light"""")
      switch must contain("""data-theme-value="dark" aria-pressed="true"""")
      switch must contain("""data-theme-value="auto" aria-pressed="false"""")
      page must contain("javascripts/theme-switch.js")
      page must not(contain("""action="/theme""""))
    }

    "mark auto for no cookie" in {
      implicit val request: RequestHeader = requestWith(None)
      val page = contentAsString(views.html.login(form))
      page must contain("""data-theme-value="auto" aria-pressed="true"""")
    }
  }
}
