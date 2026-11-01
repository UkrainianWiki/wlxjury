package views

import org.jsoup.Jsoup
import play.api.mvc.{Call, Request}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Paths}

/** The start/stop button of a round (rounds page) or of a juror in a round (round statistics,
  * icon only): its accessible name says what it does, and without the Yes/No text the
  * outline's colour shows the state.
  */
class StartStopViewSpec extends PlaySpecification {

  implicit val request: Request[_] = FakeRequest("GET", "/admin/rounds").withCSRFToken
  // stub messages: a key renders as itself
  implicit val messages: play.api.i18n.Messages = LargeViewRenders.messages(request)

  private val submit = () => Call("POST", "/admin/setround")

  private def button(active: Boolean, withText: Boolean, what: String) =
    Jsoup.parse(views.html.snippets.startStop(active, 7L, 3L, submit, withText, what).body).selectFirst("button")

  "the start/stop button" should {

    "be named by what it does, for a round and for a juror" in {
      for {
        (what, withText) <- Seq("round" -> true, "juror" -> false)
        active           <- Seq(true, false)
      } yield {
        val b      = button(active, withText, what)
        val action = if (active) "stop" else "start"
        (b.attr("aria-label") must_== s"$what.$action") and (b.attr("title") must_== s"$what.$action")
      }
    }

    "be grey in both variants, the state shown by the icon and the name, not by colour" in {
      for {
        (what, withText) <- Seq("round" -> true, "juror" -> false)
        active           <- Seq(true, false)
      } yield {
        val b = button(active, withText, what)
        (b.hasClass("btn-outline-secondary") must beTrue) and
          (b.className must not(contain("danger"))) and (b.className must not(contain("success"))) and
          (b.select(if (active) "i.bi-stop-fill" else "i.bi-play-fill").size must_== 1)
      }
    }

    "have its names in every language" in {
      val keys = Seq("round.start", "round.stop", "juror.start", "juror.stop")
      for (lang <- Seq("en", "fr", "uk", "ru")) yield {
        val defined = new String(Files.readAllBytes(Paths.get(s"conf/messages.$lang")), UTF_8)
          .linesIterator.map(_.takeWhile(_ != '=').trim).toSet
        keys.filterNot(defined) aka s"keys missing in messages.$lang" must beEmpty
      }
    }
  }
}
