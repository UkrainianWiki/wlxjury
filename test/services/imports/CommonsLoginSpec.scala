package services.imports

import org.intracer.wmua.JuryTestHelpers
import org.mockito.Mockito.{never, times, verify}
import org.scalawiki.MwBot
import org.specs2.mutable.Specification
import play.api.Configuration

import scala.concurrent.Future
import scala.concurrent.duration._

/** [[CommonsLogin]] against canned `meta=userinfo` and login answers. */
class CommonsLoginSpec extends Specification with JuryTestHelpers {

  private val credentials = CommonsLogin.Credentials("IlyaBot@jury", "secret")

  private val anonymous = """{"batchcomplete":"","query":{"userinfo":{"id":0,"name":"203.0.113.7","anon":""}}}"""
  private val bot = """{"batchcomplete":"","query":{"userinfo":{"id":678,"name":"IlyaBot","rights":["read","bot","apihighlimits"]}}}"""
  private val noHighLimits = """{"batchcomplete":"","query":{"userinfo":{"id":679,"name":"Someone","rights":["read","edit"]}}}"""

  /** A login over `bot` on a clock the spec moves (`clock(0) += ...`). */
  private def login(bot: MwBot, clock: Array[Long] = Array(0L)) =
    new CommonsLogin(bot, Some(credentials), recheck = 10.minutes, now = () => clock(0))

  private def userInfoRequests(bot: MwBot, n: Int) = {
    verify(bot, times(n)).post(CommonsLogin.UserInfoParams)
    ok
  }

  "ensure" should {

    "ask nothing and stay anonymous without credentials" in {
      val mw = mockBot()
      val anon = CommonsLogin.anonymous(mw)
      anon.ensure() === CommonsLogin.Status.Anonymous
      anon.idsPerRequest === 50
      verify(mw, never()).post(any[Map[String, String]])
      verify(mw, never()).login(anyString, anyString)
      ok
    }

    "use a session that is logged in already" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(bot)
      val l = login(mw)
      l.ensure() === CommonsLogin.Status(Some("IlyaBot"), highLimits = true)
      l.idsPerRequest === 500
      verify(mw, never()).login(anyString, anyString)
      ok
    }

    "log in when the session is anonymous" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("IlyaBot@jury", "secret") returns Future.successful("Success")
      login(mw).idsPerRequest === 500
      verify(mw, times(1)).login("IlyaBot@jury", "secret")
      ok
    }

    "keep the anonymous limit when the login fails" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("IlyaBot@jury", "secret") returns Future.successful("Failed")
      login(mw).idsPerRequest === 50
    }

    "try a refused login again only after loginRetry" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("IlyaBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 1.hour.toNanos
      l.idsPerRequest === 50
      verify(mw, times(1)).login("IlyaBot@jury", "secret")
      clock(0) += CommonsLogin.LoginRetry.toNanos
      l.idsPerRequest === 50
      verify(mw, times(2)).login("IlyaBot@jury", "secret")
      userInfoRequests(mw, 2)
    }

    "check again after recheck when the API couldn't be asked" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.failed(new java.io.IOException("connection reset")) thenReturns
        Future.successful(bot)
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
    }

    "keep the anonymous limit when the API can't be asked" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.failed(new java.io.IOException("connection reset"))
      login(mw).idsPerRequest === 50
    }

    "keep the anonymous limit when the API answers with an error page" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful("<html>502 Bad Gateway</html>")
      login(mw).idsPerRequest === 50
    }

    "give 50 to an account without apihighlimits" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(noHighLimits)
      login(mw).idsPerRequest === 50
    }

    "ask again only when the last answer is older than recheck" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(bot)
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 500
      clock(0) += 9.minutes.toNanos
      l.idsPerRequest === 500
      userInfoRequests(mw, 1)
      clock(0) += 1.minute.toNanos
      l.idsPerRequest === 500
      userInfoRequests(mw, 2)
    }

    "log in again when the session has ended" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(bot) thenReturns
        Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("IlyaBot@jury", "secret") returns Future.successful("Success")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 500
      verify(mw, never()).login(anyString, anyString)
      clock(0) += 1.day.toNanos
      l.idsPerRequest === 500
      verify(mw, times(1)).login("IlyaBot@jury", "secret")
      userInfoRequests(mw, 3)
    }

    "ask again after invalidate" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(bot)
      val l = login(mw)
      l.ensure()
      l.invalidate()
      l.ensure()
      userInfoRequests(mw, 2)
    }
  }

  "credentials" should {

    "need both the user and the password" in {
      CommonsLogin.credentials(Configuration("commons.user" -> "IlyaBot@jury", "commons.password" -> "secret")) ===
        Some(credentials)
      CommonsLogin.credentials(Configuration("commons.user" -> "IlyaBot@jury")) === None
      CommonsLogin.credentials(Configuration("commons.user" -> "", "commons.password" -> "")) === None
      CommonsLogin.credentials(Configuration.empty) === None
    }

    "leave the password out of toString" in {
      credentials.toString must not(contain("secret"))
    }
  }

  "parseUserInfo" should {
    "tell an anonymous session from an account" in {
      import play.api.libs.json.Json
      CommonsLogin.parseUserInfo(Json.parse(anonymous)) === CommonsLogin.Status.Anonymous
      CommonsLogin.parseUserInfo(Json.parse(bot)) === CommonsLogin.Status(Some("IlyaBot"), highLimits = true)
      CommonsLogin.parseUserInfo(Json.parse(noHighLimits)) === CommonsLogin.Status(Some("Someone"), highLimits = false)
    }
  }
}
