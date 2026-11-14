package views

import controllers.{GalleryView, ListOrder, Neighbourhood, RoundList, VoteFilter}
import db.scalikejdbc.{Round, User}
import org.intracer.wmua._
import org.jsoup.Jsoup
import play.api.mvc.Flash
import play.api.test.CSRFTokenHelper._
import play.api.test.{Helpers, PlaySpecification}
import play.twirl.api.Html

import scala.jdk.CollectionConverters._

class LargeViewControllerSpec extends PlaySpecification {

  import play.api.i18n._
  implicit val lang: Lang = Lang("en-US")

  "large view [J-20, J-21]" should {
    "have correct rating link" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)
      implicit val flash: Flash = Flash()

      val files = Seq(
        ImageWithRating(
          Image(pageId = 1, title = "File:1.jpg"),
          votes = Nil)
      )

      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
      val user  = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1))
      // the juror's own view, where the keys and the stars vote
      val list = RoundList(round, asUserId = 1, filter = VoteFilter.Unrated, view = GalleryView.ByRate)
      val hood = Neighbourhood(files, 0, 0)
      val html = views.html.large.main_large(user = user, list = list, readOnly = false, hood = hood)(
        views.html.large.navigationButtons(user, list, hood, 0.0, readOnly = false))

      val view = contentAsString(html)
      val doc  = Jsoup.parse(view)
      // votes are POSTs of the vote form, with its CSRF token
      view must contain("<form id=\"vote-form\" method=\"post\"")
      view must contain("name=\"csrfToken\"")
      // the stars (large-stars.js) vote their prefix, the rate in points and their query
      val stars = doc.getElementById("rate-image")
      stars.attr("data-vote-prefix") must_== "/large/round/1/pageid/1/select/"
      stars.attr("data-vote-query") must_== "?module=byrate&filter=unrated&sort=rate"
      stars.attr("data-readonly") must_== "false"
      // the keys (large-keys.js) vote and move by paths, never by http:// URLs: behind HTTPS a
      // form posted to http:// is redirected to https as a GET, and the vote is lost
      val keys = doc.getElementById("large-keys")
      keys.attr("data-vote-key-1") must startWith("/large/round/1/pageid/1/select/1?")
      keys.attr("data-key-esc") must startWith("/")
      val urls = (keys.attributes.asScala.toSeq ++ stars.attributes.asScala.toSeq)
        .filter(a => a.getKey.startsWith("data-key-") || a.getKey.startsWith("data-vote-key-") ||
          a.getKey == "data-vote-prefix")
        .map(_.getValue)
      urls must not(beEmpty)
      urls.filterNot(u => u.startsWith("/") && !u.startsWith("//")) must beEmpty
      // no script in the page holds a vote or a move: they are in the attributes
      doc.select("body script:not([src])").asScala must beEmpty
    }

    "give the worst mark by Delete and its button, clear by Backspace and its button, all by paths [J-38]" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)
      implicit val flash: Flash = Flash()

      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5), worstMark = true)
      val user  = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1))
      val list = RoundList(round, asUserId = 1, view = GalleryView.ByRate)
      def render(rate: Int) = {
        val files = Seq(ImageWithRating(Image(pageId = 1, title = "File:1.jpg"), votes = Seq(Vote(1, 1, 1, rate))))
        val hood = Neighbourhood(files, 0, 0)
        Jsoup.parse(contentAsString(views.html.large.main_large(user = user, list = list, readOnly = false, hood = hood)(
          views.html.large.navigationButtons(user, list, hood, 0.0, readOnly = false))))
      }
      val worst = render(-1)
      val keys = worst.getElementById("large-keys")
      val buttons = worst.select(".large-worst [data-vote-url]").asScala.map(b =>
        (b.attr("data-vote-url").takeWhile(_ != '?'), b.attr("aria-label"), b.attr("aria-pressed")))
      (keys.attr("data-vote-key-del") must startWith("/large/round/1/pageid/1/select/-1?")) and
        (keys.attr("data-vote-key-backspace") must startWith("/large/round/1/pageid/1/select/0?")) and
        (keys.attr("data-vote-key-0") must startWith("/large/round/1/pageid/1/select/0?")) and
        (buttons.toSeq === Seq(
          ("/large/round/1/pageid/1/select/-1", "vote.worst", "true"), ("/large/round/1/pageid/1/select/0", "vote.clear", ""))) and
        // the stars without raty's cancel icon; the number is the worst mark's icon
        (worst.getElementById("rate-image").attr("data-cancel") === "false") and
        (worst.select(".large-rate-number").text === "") and
        (worst.select(".large-rate-number > i.bi-x-lg.wlx-worst-mark[aria-hidden=true]").size === 1) and
        (worst.select(".large-rate .visually-hidden").text === "large.rate.worst") and
        // the clear only once the file has a vote; a round without the option has neither, nor the keys
        (render(0).select(".large-worst [data-vote-url]").size === 1) and {
          val plain = Jsoup.parse(contentAsString(views.html.large.main_large(user = user,
            list = list.copy(round = round.copy(worstMark = false)), readOnly = false,
            hood = Neighbourhood(Seq(ImageWithRating(Image(pageId = 1, title = "File:1.jpg"), votes = Nil)), 0, 0))(Html(""))))
          (plain.getElementById("large-keys").hasAttr("data-vote-key-del") must beFalse) and
            (plain.getElementById("large-keys").hasAttr("data-vote-key-backspace") must beFalse)
        }
    }

    "keep the monument filter in its keys' and stars' votes and moves, all by paths" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)
      implicit val flash: Flash = Flash()

      val files = Seq(1L, 2L, 3L).map(id => ImageWithRating(Image(pageId = id, title = s"File:$id.jpg"), votes = Nil))
      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
      val user = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1))
      val list = RoundList(round, asUserId = 1, filter = VoteFilter.Unrated, order = ListOrder.FileOrder)
        .withMonument(Some("07-101-0'01"))
      val hood = Neighbourhood(files, 1, 1)
      val doc = Jsoup.parse(contentAsString(views.html.large.main_large(user = user, list = list, readOnly = false,
        hood = hood)(views.html.large.navigationButtons(user, list, hood, 0.0, readOnly = false))))
      val stars = doc.getElementById("rate-image")
      val keys = doc.getElementById("large-keys")
      val monument = "monument=07-101-0%2701"
      val urls = (keys.attributes.asScala.toSeq ++ stars.attributes.asScala.toSeq)
        .filter(a => a.getKey.startsWith("data-key-") || a.getKey.startsWith("data-vote-key-"))
        .map(_.getValue)
      (stars.attr("data-vote-query") must_== s"?filter=unrated&$monument&sort=file") and
        (keys.attr("data-vote-key-1") must startWith("/large/round/1/pageid/2/select/1?")) and
        (keys.attr("data-vote-key-1") must contain(monument)) and
        (urls must not(beEmpty)) and
        (urls.filterNot(u => u.startsWith("/") && !u.startsWith("//")) must beEmpty) and
        // the moves to the files before and after keep the filter
        (doc.select("[data-nav=up], [data-nav=down]").asScala.map(_.attr("href")).forall(_.contains(monument)) must beTrue)
    }

    "keep the author filter (staff only) in its keys' and stars' votes and moves, all by paths" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)
      implicit val flash: Flash = Flash()

      val files = Seq(1L, 2L, 3L).map(id => ImageWithRating(Image(pageId = id, title = s"File:$id.jpg"), votes = Nil))
      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
      // a root juror's own list: the only staff list that votes
      val user = User("name", "email", id = Some(1), roles = Set("jury", "root"), contestId = Some(1))
      val list = RoundList(round, asUserId = 1, filter = VoteFilter.Unrated, order = ListOrder.FileOrder)
        .withAuthor(Some("O'Brien \"X\""))
      val hood = Neighbourhood(files, 1, 1)
      val doc = Jsoup.parse(contentAsString(views.html.large.main_large(user = user, list = list, readOnly = false,
        hood = hood)(views.html.large.navigationButtons(user, list, hood, 0.0, readOnly = false))))
      val stars = doc.getElementById("rate-image")
      val keys = doc.getElementById("large-keys")
      val author = "author=O%27Brien+%22X%22"
      val urls = (keys.attributes.asScala.toSeq ++ stars.attributes.asScala.toSeq)
        .filter(a => a.getKey.startsWith("data-key-") || a.getKey.startsWith("data-vote-key-"))
        .map(_.getValue)
      (stars.attr("data-vote-query") must_== s"?filter=unrated&$author&sort=file") and
        (keys.attr("data-vote-key-1") must startWith("/large/round/1/pageid/2/select/1?")) and
        (keys.attr("data-vote-key-1") must contain(author)) and
        (urls must not(beEmpty)) and
        (urls.filterNot(u => u.startsWith("/") && !u.startsWith("//")) must beEmpty) and
        (doc.select("[data-nav=up], [data-nav=down]").asScala.map(_.attr("href")).forall(_.contains(author)) must beTrue)
    }

    "keep the binary order of all the jurors' votes in its links" in {
      implicit val request = play.api.test.FakeRequest("GET", "/").withCSRFToken
      implicit val messages = Helpers.stubMessagesApi().preferred(request)

      val files = Seq(1L, 2L).map(id => ImageWithRating(Image(pageId = id, title = s"File:$id.jpg"), votes = Nil))
      val round = Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.binaryRound)
      val html = views.html.large.main_large(
        user = User("name", "email", id = Some(1), roles = Set("jury"), contestId = Some(1)),
        list = RoundList(round, asUserId = 0, order = ListOrder.Binary(BinaryOrder.Difference), view = GalleryView.ByRate),
        readOnly = true,
        hood = Neighbourhood(files, 0, 0))(Html("html"))

      val view = contentAsString(html).replace("&amp;", "&")
      view must contain("/large/round/1/user/0/pageid/2?module=byrate&sort=difference")
      view must not contain "sort=rate"
    }
  }

}
