package controllers

import db.scalikejdbc._
import org.intracer.wmua.{BinaryOrder, ContestJury, Image, Vote}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.Application
import play.api.mvc.Result
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future
import scala.jdk.CollectionConverters._

/** The organisers' view menu (Gallery, By rate, File list, CSV, Comments) of a round's list:
  * the item shown as chosen is the page's view, from its path, whatever its order (`sort=`),
  * and the Gallery item opens the gallery, keeping the order. Production 26.11.5: the gallery
  * of all the jurors' votes (`/gallery/round/R/user/0/...`) was the by-rate view, so its menu
  * showed By rate and its Gallery item went back to the same page. Through the router.
  */
class ViewMenuSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, binary: Round, rated: Round, organizer: User, jurors: Seq[User])

  /** A yes/no and a rated round, two jurors voting on three files in each. */
  private def fixture(): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    val binary = roundDao.create(Round(None, 1, Some("Yes/no"), contest.getId, rates = Round.binaryRound, active = true))
    val rated = roundDao.create(Round(None, 2, Some("Rated"), contest.getId, rates = Round.ratesById(5), active = true))
    val jurors = (1 to 2).map(i =>
      userDao.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id)))
    val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = contest.id))
    val images = (1 to 3).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(f"80-361-$i%04d")))
    imageDao.batchInsert(images)
    Seq(binary, rated).foreach { r =>
      r.addUsers(jurors.map(j => RoundUser(r.getId, j.getId, "jury", active = true)))
      selectionDao.batchInsert(for ((j, ji) <- jurors.zipWithIndex; image <- images) yield {
        val i = image.pageId.toInt
        Vote(image, j, r, if (r.isBinary) (if ((i + ji) % 2 == 0) 1 else -1) else i + ji)
      })
    }
    Fixture(contest, binary, rated, organizer, jurors)
  }

  private def get(path: String, viewer: User)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email)
      .withHeaders(ACCEPT -> "text/html").withCSRFToken).get

  private def page(path: String, viewer: User)(implicit app: Application): Document = {
    val result = get(path, viewer)
    status(result) aka path must_== OK
    Jsoup.parse(contentAsString(result))
  }

  /** Each view menu item's icon, by view. */
  private val icons = Seq(
    GalleryView.Gallery -> "bi-grid-3x3-gap-fill",
    GalleryView.ByRate -> "bi-bar-chart-fill",
    GalleryView.FileList -> "bi-list-ul",
    GalleryView.Csv -> "bi-filetype-csv"
  )

  /** The view menu: its items (view, href, active), and the view its toggle shows. */
  private def viewMenu(doc: Document): (Seq[(GalleryView, String, Boolean)], Option[GalleryView]) = {
    val menu = doc.select("li.nav-item.dropdown").asScala
      .find(li => li.select("ul.dropdown-menu i.bi-filetype-csv").size == 1)
      .getOrElse(sys.error("no view menu"))
    val items = icons.map { case (view, icon) =>
      val a = menu.selectFirst(s"ul.dropdown-menu a.dropdown-item:has(i.$icon)")
      (view, a.attr("href"), a.hasClass("active"))
    }
    val toggleIcon = menu.selectFirst(".dropdown-toggle i")
    (items, icons.collectFirst { case (view, icon) if toggleIcon != null && toggleIcon.hasClass(icon) => view })
  }

  private def active(doc: Document): Seq[GalleryView] = viewMenu(doc)._1.collect { case (v, _, true) => v }

  private def href(doc: Document, view: GalleryView): String =
    viewMenu(doc)._1.collectFirst { case (`view`, h, _) => h }.get

  /** A yes/no round's orders, the by-rate one, file order and the vote-time ones. */
  private val binarySorts: Seq[String] =
    BinaryOrder.all.map(_.param) ++ Seq("rate", "file", "latest-vote", "oldest-vote")

  "the view menu of all the jurors' votes on a yes/no round" should {

    "show Gallery as chosen on the gallery, in every order, and the Gallery item open it" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.binary.getId
        forall("" +: binarySorts.map("?sort=" + _)) { query =>
          val path = s"/gallery/round/$r/user/0/page/1$query"
          val doc = page(path, f.organizer)
          val galleryHref = href(doc, GalleryView.Gallery)
          val again = page(galleryHref, f.organizer)
          (path, active(doc), viewMenu(doc)._2) === ((path, Seq(GalleryView.Gallery), Some(GalleryView.Gallery))) and
            (galleryHref must startWith(s"/gallery/round/$r/user/0/page/1")) and
            (href(doc, GalleryView.ByRate) must startWith(s"/byrate/round/$r/user/0/page/1")) and
            ((galleryHref, active(again)) === ((galleryHref, Seq(GalleryView.Gallery))))
        }
      }
    }

    "keep the order chosen in the Gallery and By rate items, the order apart from the view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.binary.getId
        forall(BinaryOrder.all.map(_.param)) { sort =>
          val doc = page(s"/gallery/round/$r/user/0/page/1?sort=$sort", f.organizer)
          (href(doc, GalleryView.Gallery) === s"/gallery/round/$r/user/0/page/1?sort=$sort") and
            (href(doc, GalleryView.ByRate) === s"/byrate/round/$r/user/0/page/1?sort=$sort")
        }
      }
    }

    "show By rate as chosen on the by-rate view, in every order, and its Gallery item open the gallery" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.binary.getId
        forall("" +: binarySorts.map("?sort=" + _)) { query =>
          val path = s"/byrate/round/$r/user/0/page/1$query"
          val doc = page(path, f.organizer)
          val galleryHref = href(doc, GalleryView.Gallery)
          ((path, active(doc)) === ((path, Seq(GalleryView.ByRate)))) and
            ((galleryHref, active(page(galleryHref, f.organizer))) === ((galleryHref, Seq(GalleryView.Gallery))))
        }
      }
    }

    "go from a file's place in the gallery to its page in the gallery, not by rate" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.binary.getId
        val at = get(s"/gallery/round/$r/user/0/region/all/pageid/2?sort=approvals-only", f.organizer)
        (status(at) === SEE_OTHER) and
          (redirectLocation(at).get must startWith(s"/gallery/round/$r/user/0/page/1?sort=approvals-only")) and
          (redirectLocation(at).get must endWith("#2"))
      }
    }

    "show each file's tally of all the jurors' votes on its gallery tile" in {
      testDbApp { implicit app =>
        val f = fixture()
        val doc = page(s"/gallery/round/${f.binary.getId}/user/0/page/1?sort=file", f.organizer)
        val tiles = doc.select("#gallery img[data-pageid]").asScala.toList
        // juror 1 approves the even files, juror 2 the odd ones: one approval, one rejection each
        (tiles.map(_.attr("data-pageid")) === List("1", "2", "3")) and
          (tiles.map(t => (t.attr("data-approvals"), t.attr("data-rejections"))) === List.fill(3)(("1", "1"))) and
          (tiles.map(_.attr("data-tally")) === List.fill(3)("1 approval, 1 rejection"))
      }
    }

    "show File list as chosen on the file list" in {
      testDbApp { implicit app =>
        val f = fixture()
        active(page(s"/filelist/round/${f.binary.getId}/user/0/page/1?sort=approvals-only", f.organizer)) ===
          Seq(GalleryView.FileList)
      }
    }
  }

  "the view menu of one juror's votes as the organisers view them" should {

    "show Gallery as chosen on the gallery, in every order, and By rate on the by-rate view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val (r, j) = (f.binary.getId, f.jurors.head.getId)
        forall("" +: binarySorts.map("?sort=" + _)) { query =>
          val gallery = s"/gallery/round/$r/user/$j/page/1$query"
          val byRate = s"/byrate/round/$r/user/$j/page/1$query"
          val doc = page(gallery, f.organizer)
          ((gallery, active(doc)) === ((gallery, Seq(GalleryView.Gallery)))) and
            (href(doc, GalleryView.Gallery) must startWith(s"/gallery/round/$r/user/$j/page/1")) and
            ((byRate, active(page(byRate, f.organizer))) === ((byRate, Seq(GalleryView.ByRate))))
        }
      }
    }
  }

  "the view menu of all the jurors' votes on a rated round" should {

    "show Gallery as chosen on the gallery and By rate on the by-rate view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.rated.getId
        forall(Seq("", "?sort=rate", "?sort=file", "?sort=latest-vote")) { query =>
          val gallery = s"/gallery/round/$r/user/0/page/1$query"
          val byRate = s"/byrate/round/$r/user/0/page/1$query"
          val doc = page(gallery, f.organizer)
          val galleryHref = href(doc, GalleryView.Gallery)
          ((gallery, active(doc)) === ((gallery, Seq(GalleryView.Gallery)))) and
            ((galleryHref, active(page(galleryHref, f.organizer))) === ((galleryHref, Seq(GalleryView.Gallery)))) and
            ((byRate, active(page(byRate, f.organizer))) === ((byRate, Seq(GalleryView.ByRate))))
        }
      }
    }
  }

  "the region menu's regions of all the jurors' gallery" should {

    "open the gallery, not the by-rate view" in {
      testDbApp { implicit app =>
        val f = fixture()
        val r = f.binary.getId
        val result = route(app, FakeRequest(GET, s"/regions/round/$r/user/0/region/all?sort=approvals-only")
          .withSession(Secured.UserName -> f.organizer.email).withHeaders("X-Requested-With" -> "XMLHttpRequest")).get
        (status(result) === OK) and
          (contentAsString(result) must contain(s"/gallery/round/$r/user/0/page/1?sort=approvals-only"))
      }
    }
  }
}
