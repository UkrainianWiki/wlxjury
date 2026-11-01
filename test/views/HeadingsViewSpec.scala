package views

import controllers.{GalleryView, Pager, RateDistribution, RoundList}
import db.scalikejdbc.User
import org.intracer.wmua.{Image, ImageWithRating, Selection}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.data.Form
import play.api.data.Forms.{text, tuple}
import play.api.mvc.{Flash, Request}
import play.api.test.PlaySpecification

import scala.jdk.CollectionConverters._

/** Each page's one h1, its title: shown on an admin or error page (with a smaller heading's
  * look), for screen readers only where the page shows no title (a gallery, the large view);
  * a gallery's tiles are scroll targets by their own id, not by an empty h1 each. No database
  * or Play app.
  */
class HeadingsViewSpec extends PlaySpecification {

  import LargeViewRenders._

  implicit val req: Request[_] = LargeViewRenders.request("/")
  implicit val flash: Flash = Flash()
  implicit val msgs: play.api.i18n.Messages = LargeViewRenders.messages(req)

  private def h1s(html: String): Seq[(String, String)] =
    Jsoup.parse(html).select("h1").asScala.toSeq.map(h => h.className -> h.text)

  private val admin = User("Admin", "admin@example.org", id = Some(4), roles = Set("admin"), contestId = Some(1))

  private val files = (0 until 3).map { i =>
    ImageWithRating(Image(pageId = 201L + i, title = s"File:Image ${201 + i}.jpg"),
      selection = Seq(Selection(201L + i, juror.getId, ratedRound.getId, i + 1)))
  }

  "a page" should {

    "have its title as its one h1: an admin page's, with an h3's look" in {
      val form = Form(tuple("userstoimport" -> text, "role" -> text))
      h1s(contentAsString(views.html.importUsers(admin, form, 1L))) must_== Seq("h3" -> "import")
    }

    "have its title as its one h1: the error page's, with an h4's look, signed in or not" in {
      (h1s(contentAsString(views.html.errorPage(None, "error.title", "error.round.not.found"))) must_==
        Seq("h4" -> "error.title")) and
        (h1s(contentAsString(views.html.errorPage(Some(juror), "error.title", "error.round.not.found"))) must_==
          Seq("h4" -> "error.title"))
    }

    "have a hidden h1 in a gallery, and its tiles' ids instead of an empty h1 in each" in {
      val list = RoundList(ratedRound, juror.getId, view = GalleryView.ByRate)
      val byRate = contentAsString(views.html.galleryByRate(juror, list, files, Pager(1, files.size), Nil, None,
        new RateDistribution(Map.empty)))
      val gallery = contentAsString(views.html.gallery(juror, list.withView(GalleryView.Gallery), files,
        Pager(1, files.size), Nil, None, new RateDistribution(Map.empty)))
      val doc: Document = Jsoup.parse(byRate)
      (h1s(byRate) must_== Seq("visually-hidden" -> "byrate")) and
        (h1s(gallery) must_== Seq("visually-hidden" -> "gallery")) and
        // where byRateAt's #pageId scrolls: the tile itself
        (doc.select("li.gallerybox").asScala.map(_.id) must_== files.map(_.pageId.toString))
    }

    "have a hidden h1 in the large views" in {
      (h1s(large(ratedRound, middle)) must_== Seq("visually-hidden" -> "gallery")) and
        (h1s(large(binaryRound, middle, module = "byrate")) must_== Seq("visually-hidden" -> "byrate")) and
        (h1s(contestFile(middle)) must_== Seq("visually-hidden" -> s"Image ${pageIdAt(middle)}.jpg"))
    }
  }
}
