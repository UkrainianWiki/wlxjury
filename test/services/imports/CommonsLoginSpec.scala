package services.imports

import org.intracer.wmua.JuryTestHelpers
import org.mockito.Mockito.{never, timeout, times, verify}
import org.scalawiki.MwBot
import org.specs2.mutable.Specification
import play.api.{Configuration, Mode}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future, Promise}

/** [[CommonsLogin]] against canned `meta=userinfo` and login answers. */
class CommonsLoginSpec extends Specification with JuryTestHelpers {

  private val credentials = CommonsLogin.Credentials("ExampleBot@jury", "secret")

  private val anonymous = """{"batchcomplete":"","query":{"userinfo":{"id":0,"name":"203.0.113.7","anon":""}}}"""
  private val bot = """{"batchcomplete":"","query":{"userinfo":{"id":678,"name":"ExampleBot","rights":["read","bot","apihighlimits"]}}}"""
  private val readOnly = """{"error":{"code":"readonly","info":"The wiki is currently in read-only mode."}}"""
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
      l.ensure() === CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
      l.idsPerRequest === 500
      verify(mw, never()).login(anyString, anyString)
      ok
    }

    "log in when the session is anonymous" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Success")
      login(mw).idsPerRequest === 500
      verify(mw, times(1)).login("ExampleBot@jury", "secret")
      ok
    }

    "keep the anonymous limit when the login fails" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      login(mw).idsPerRequest === 50
    }

    "try a refused login again only after 5 minutes, when an import needs it" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 4.minutes.toNanos
      l.idsPerRequest === 50
      verify(mw, times(1)).login("ExampleBot@jury", "secret")
      userInfoRequests(mw, 1)
      clock(0) += 1.minute.toNanos
      l.idsPerRequest === 50
      verify(mw, times(2)).login("ExampleBot@jury", "secret")
      userInfoRequests(mw, 2)
    }

    "double the gap after each refused login in a row: 5, 10, 20, 40 minutes, then 1 hour" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure()
      val gaps = Seq(5, 10, 20, 40, 60, 60, 60).map(_.minutes)
      gaps.zipWithIndex.foreach { case (gap, i) =>
        clock(0) += gap.toNanos - 1
        l.ensure()
        verify(mw, times(i + 1)).login("ExampleBot@jury", "secret")
        clock(0) += 1
        l.ensure()
        verify(mw, times(i + 2)).login("ExampleBot@jury", "secret")
      }
      ok
    }

    "stay within Wikimedia's failed-login limits with a password that stays wrong" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      // an import step every 30 seconds for 48 hours
      (0 until (48 * 60 * 2)).foreach { _ =>
        l.ensure()
        clock(0) += 30.seconds.toNanos
      }
      // 4 tries in the first 75 minutes, then one an hour
      verify(mw, times(51)).login("ExampleBot@jury", "secret")
      ok
    }

    "reset the gap after a successful login" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed") thenReturns
        Future.successful("Failed") thenReturns Future.successful("Success") thenReturns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure() // refused: 5 minutes
      clock(0) += 5.minutes.toNanos
      l.ensure() // refused: 10 minutes
      clock(0) += 10.minutes.toNanos
      l.ensure() // logged in, but the session stays anonymous: check again after recheck
      verify(mw, times(3)).login("ExampleBot@jury", "secret")
      clock(0) += 10.minutes.toNanos
      l.ensure() // refused again: 5 minutes, not 20
      verify(mw, times(4)).login("ExampleBot@jury", "secret")
      clock(0) += 5.minutes.toNanos
      l.ensure()
      verify(mw, times(5)).login("ExampleBot@jury", "secret")
      ok
    }

    "reset the gap when the session is found logged in" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns
        Future.successful(anonymous) thenReturns Future.successful(bot) thenReturns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure() // refused: 5 minutes
      clock(0) += 5.minutes.toNanos
      l.ensure() // refused: 10 minutes
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500 // logged in (e.g. by another process using the same bot)
      clock(0) += 10.minutes.toNanos
      l.ensure() // anonymous again, refused: 5 minutes
      verify(mw, times(3)).login("ExampleBot@jury", "secret")
      clock(0) += 5.minutes.toNanos
      l.ensure()
      verify(mw, times(4)).login("ExampleBot@jury", "secret")
      ok
    }

    "not log in before the gap, however many steps call ensure or invalidate" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure()
      (1 to 100).foreach { i =>
        if (i % 10 == 0) l.invalidate()
        l.ensure()
        clock(0) += 2.seconds.toNanos
      }
      verify(mw, times(1)).login("ExampleBot@jury", "secret")
      ok
    }

    "not log in before the gap from concurrent steps" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure()
      clock(0) += 4.minutes.toNanos
      implicit val ec: ExecutionContext = ExecutionContext.global
      val steps = Future.sequence((1 to 200).map(i => Future { if (i % 20 == 0) l.invalidate(); l.ensure() }))
      Await.result(steps, 10.seconds).forall(_ == CommonsLogin.Status.Anonymous) === true
      verify(mw, times(1)).login("ExampleBot@jury", "secret")
      clock(0) += 1.minute.toNanos
      Await.result(Future.sequence((1 to 200).map(_ => Future(l.ensure()))), 10.seconds)
      verify(mw, times(2)).login("ExampleBot@jury", "secret")
      ok
    }

    "not shorten the refused-login gap after a login that throws" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Failed") thenReturns
        Future.successful("Failed") thenReturns Future.successful("Failed") thenReturns
        Future.failed(new RuntimeException("<html>503</html>")) thenReturns Future.successful("Failed")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.ensure() // refused: 5
      clock(0) += 5.minutes.toNanos
      l.ensure() // refused: 10
      clock(0) += 10.minutes.toNanos
      l.ensure() // refused: 20
      clock(0) += 20.minutes.toNanos
      l.ensure() // throws: at least 20, not recheck
      verify(mw, times(4)).login("ExampleBot@jury", "secret")
      clock(0) += 10.minutes.toNanos
      l.invalidate()
      l.ensure()
      verify(mw, times(4)).login("ExampleBot@jury", "secret")
      clock(0) += 10.minutes.toNanos
      l.ensure() // refused: 40
      verify(mw, times(5)).login("ExampleBot@jury", "secret")
      ok
    }

    "describe the waits it logs" in {
      CommonsLogin.describe(5.minutes) === "5 minutes"
      CommonsLogin.describe(1.hour) === "1 hour"
      CommonsLogin.describe(2.hours) === "2 hours"
      CommonsLogin.describe(90.seconds) === "90 seconds"
      CommonsLogin.describe(1.second) === "1 second"
      CommonsLogin.describe(150.seconds) === "3 minutes"
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

    "try a login refused for its token again after recheck, not the refused-login gap" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns
        Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("WrongToken") thenReturns Future.successful("Success")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
      verify(mw, times(2)).login("ExampleBot@jury", "secret")
      ok
    }

    "try a login that threw again after recheck" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns
        Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("ExampleBot@jury", "secret") returns Future.failed(new RuntimeException("<html>503</html>")) thenReturns
        Future.successful("Success")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
    }

    "not log in when the API answers the check with an error" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(readOnly) thenReturns Future.successful(bot)
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      verify(mw, never()).login(anyString, anyString)
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
    }

    "not keep other callers waiting while one checks" in {
      val mw = mockBot()
      val answer = Promise[String]()
      mw.post(CommonsLogin.UserInfoParams) returns answer.future
      val l = login(mw)
      val first = Future(l.ensure())(ExecutionContext.global)
      verify(mw, timeout(5000)).post(CommonsLogin.UserInfoParams)
      Await.result(Future(l.ensure())(ExecutionContext.global), 5.seconds) === CommonsLogin.Status.Anonymous
      answer.success(bot)
      Await.result(first, 5.seconds) === CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
      l.ensure() === CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
      userInfoRequests(mw, 1)
    }

    "check again when invalidated while a check runs" in {
      val mw = mockBot()
      val answer = Promise[String]()
      mw.post(CommonsLogin.UserInfoParams) returns answer.future thenReturns Future.successful(bot)
      val l = login(mw)
      val first = Future(l.ensure())(ExecutionContext.global)
      verify(mw, timeout(5000)).post(CommonsLogin.UserInfoParams)
      l.invalidate()
      answer.success(bot)
      Await.result(first, 5.seconds) === CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
      l.ensure()
      userInfoRequests(mw, 2)
    }

    "keep a logged-in answer when the API can't be asked" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(bot) thenReturns
        Future.failed(new java.io.IOException("connection reset")) thenReturns Future.successful(readOnly)
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 500
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
      verify(mw, never()).login(anyString, anyString)
      userInfoRequests(mw, 3)
    }

    "try again after recheck when a successful login leaves the session anonymous" in {
      val mw = mockBot()
      mw.post(CommonsLogin.UserInfoParams) returns Future.successful(anonymous) thenReturns
        Future.successful(anonymous) thenReturns Future.successful(anonymous) thenReturns Future.successful(bot)
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Success")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 50
      clock(0) += 10.minutes.toNanos
      l.idsPerRequest === 500
      verify(mw, times(2)).login("ExampleBot@jury", "secret")
      ok
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
      mw.login("ExampleBot@jury", "secret") returns Future.successful("Success")
      val clock = Array(0L)
      val l = login(mw, clock)
      l.idsPerRequest === 500
      verify(mw, never()).login(anyString, anyString)
      clock(0) += 1.day.toNanos
      l.idsPerRequest === 500
      verify(mw, times(1)).login("ExampleBot@jury", "secret")
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
      CommonsLogin.credentials(Configuration("commons.user" -> "ExampleBot@jury", "commons.password" -> "secret"), Mode.Prod) ===
        Some(credentials)
      CommonsLogin.credentials(Configuration("commons.user" -> "ExampleBot@jury"), Mode.Prod) === None
      CommonsLogin.credentials(Configuration("commons.user" -> "", "commons.password" -> ""), Mode.Prod) === None
      CommonsLogin.credentials(Configuration.empty, Mode.Prod) === None
    }

    "be None in Mode.Test, unless commons.allowTestLogin is true" in {
      val set = Configuration("commons.user" -> "ExampleBot@jury", "commons.password" -> "secret")
      (CommonsLogin.credentials(set, Mode.Test) must beNone) and
        (CommonsLogin.credentials(set ++ Configuration(CommonsLogin.AllowTestLoginKey -> false), Mode.Test) must beNone) and
        (CommonsLogin.credentials(set ++ Configuration(CommonsLogin.AllowTestLoginKey -> true), Mode.Test) must
          beSome(credentials)) and
        (CommonsLogin.credentials(set, Mode.Dev) must beSome(credentials))
    }

    "leave the password out of toString" in {
      credentials.toString must not(contain("secret"))
    }
  }

  "parseUserInfo" should {
    "tell an anonymous session from an account" in {
      import play.api.libs.json.Json
      CommonsLogin.parseUserInfo(Json.parse(anonymous)) === CommonsLogin.Status.Anonymous
      CommonsLogin.parseUserInfo(Json.parse(bot)) === CommonsLogin.Status(Some("ExampleBot"), highLimits = true)
      CommonsLogin.parseUserInfo(Json.parse(noHighLimits)) === CommonsLogin.Status(Some("Someone"), highLimits = false)
    }

    "not take an API error for an anonymous session" in {
      import play.api.libs.json.Json
      CommonsLogin.parseUserInfo(Json.parse(readOnly)) must throwA[IllegalStateException]
    }
  }
}
