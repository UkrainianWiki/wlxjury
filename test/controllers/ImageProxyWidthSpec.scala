package controllers

import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, Image}
import org.specs2.mutable.Specification
import org.specs2.specification.{AfterAll, BeforeAll, BeforeEach}
import play.api.test.FakeRequest
import play.api.test.Helpers._
import services.{FakeWikimedia, LocalImageCacheService}

/** The image proxy serves a stored file only at the widths the pages ask for
  * ([[LocalImageCacheService.servedWidths]]); another width is redirected to the nearest of them,
  * not scaled again on every request (P5). Wikimedia is a local server ([[FakeWikimedia]]).
  */
class ImageProxyWidthSpec
    extends Specification
    with TestDb
    with FakeWikimedia
    with BeforeAll
    with BeforeEach
    with AfterAll {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = {
    SharedTestDb.truncateAll()
    wikimediaRequests.clear()
  }
  override def afterAll(): Unit = stopWikimedia()

  private val signedIn = FakeRequest().withSession(Secured.UserName -> "juror@example.org")

  /** The images, voted on by one juror (the session's) in one round. */
  private def store(imgs: Image*): Unit = {
    implicit val contest: ContestJury = createContests(10).head
    val round = roundDao.create(Round(None, 1, None, contest.getId, createdAt = now, active = true)).getId
    val juror = userDao.create(User("Juror", "juror@example.org", None, Set("jury"), contestId = contest.id)).getId
    imageDao.batchInsert(imgs)
    imgs.foreach(img => selectionDao.create(img.pageId, rate = 1, juryId = juror, roundId = round, monumentId = img.monumentId))
  }

  private def proxy() = {
    val cache = new LocalImageCacheService(thumbsConfig(), system)
    (new ImageProxyController(stubControllerComponents(), cache), cache)
  }

  // an 800 × 600 file: shown at 150, 225, 300, 333, 450, 500, 600 and 666 (cached) and at its
  // large view's fallback, 960
  private val ShownAt = Seq(150, 225, 300, 333, 450, 500, 600, 666, 960)

  "the widths served" should {
    "be the thumbnails' and the large view's fallback, no other of Wikimedia's sizes" in {
      LocalImageCacheService.servedWidths(commonsImage(1L, "New_file")) === ShownAt
    }

    "be none for a file of unknown size: the pages show the file itself" in {
      LocalImageCacheService.servedWidths(commonsImage(1L, "New_file").copy(width = 0, height = 0)) === Nil
    }

    "include every width the pages ask the proxy for" in {
      val files = for {
        // of unknown height too: the pages still compute widths from the width (review follow-up)
        (w, h) <- Seq((800, 600), (4000, 3000), (3000, 4000), (640, 480), (10000, 1000), (200, 150), (300, 400),
          (800, 0), (4000, 0))
        name   <- Seq("Photo.jpg", "Doc.pdf")
      } yield Image(1L, s"File:$name", Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/$name"), None, w, h)
      def proxied(url: String) =
        Option.when(url.startsWith("/wikipedia/commons/thumb/"))(url.substring(url.lastIndexOf('/') + 1))
          .flatMap(LocalImageCacheService.thumbnailPx)
      forall(files) { file =>
        def boxes(x: Int, y: Int) = Seq(Global.resizeTo(file, x, y)) ++
          Global.srcSet(file, x, y).split(", ").map(_.split(" ").head) ++
          Global.cachedSrcSet(file, x, y).split(", ").filter(_.nonEmpty).map(_.split(" ").head)
        val urls = boxes(Global.gallerySizeX, Global.gallerySizeY) ++ boxes(Global.thumbSizeX, Global.thumbSizeY) ++
          Seq(1.0, 1.5, 2.0).map(f => Global.resizeTo(file, (Global.gallerySizeY * f).toInt)) ++
          Global.largestCachedThumbnail(file) ++ Global.largeImage(file).fallback
        val widths = urls.flatMap(proxied)
        widths must not(beEmpty)
        LocalImageCacheService.servedWidths(file) must containAllOf(widths.distinct)
      }
    }
  }

  "a thumbnail request" should {
    "be redirected from a width the pages don't ask for to the nearest one, nothing fetched or cached" in {
      val image = commonsImage(1L, "New_file")
      store(image)
      val (controller, cache) = proxy()

      val result = controller.serve("a/ab/New_file.jpg/400px-New_file.jpg")(signedIn)
      status(result) === FOUND
      redirectLocation(result) must beSome("/wikipedia/commons/thumb/a/ab/New_file.jpg/450px-New_file.jpg")
      header(CACHE_CONTROL, result) must beNone
      forall(ShownAt :+ 400)(px => cache.fileIfCached(image, px) must beNone)

      // the width redirected to is served
      status(controller.serve("a/ab/New_file.jpg/450px-New_file.jpg")(signedIn)) === OK
    }

    "keep a PDF's page prefix in the redirect" in {
      val pdf = Image(2L, "File:New doc.pdf", Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/New_doc.pdf"),
        None, 800, 600, Some("07-101-0002"))
      store(pdf)
      val (controller, _) = proxy()

      val result = controller.serve("a/ab/New_doc.pdf/page1-401px-New_doc.pdf.jpg")(signedIn)
      status(result) === FOUND
      redirectLocation(result) must beSome("/wikipedia/commons/thumb/a/ab/New_doc.pdf/page1-450px-New_doc.pdf.jpg")
    }

    "serve a width the pages ask for: a cached one and the large view's fallback" in {
      store(commonsImage(1L, "New_file"))
      val (controller, _) = proxy()

      status(controller.serve("a/ab/New_file.jpg/333px-New_file.jpg")(signedIn)) === OK
      status(controller.serve("a/ab/New_file.jpg/960px-New_file.jpg")(signedIn)) === OK
    }

    "send a wider Wikimedia size than the fallback to the fallback, nothing fetched (no amplification)" in {
      val image = commonsImage(1L, "New_file")
      store(image)
      val (controller, cache) = proxy()

      forall(Seq(1280, 1920, 3840)) { px =>
        val result = controller.serve(s"a/ab/New_file.jpg/${px}px-New_file.jpg")(signedIn)
        status(result) === FOUND
        redirectLocation(result) must beSome("/wikipedia/commons/thumb/a/ab/New_file.jpg/960px-New_file.jpg")
      }
      forall(ShownAt)(px => cache.fileIfCached(image, px) must beNone)
    }

    "send a file of unknown size to Wikimedia at its next size up, nothing fetched here" in {
      store(commonsImage(1L, "New_file").copy(width = 0, height = 0))
      val (controller, _) = proxy()

      // Wikimedia refuses 400 wide (T414805): the narrowest of its sizes no narrower is 500
      val result = controller.serve("a/ab/New_file.jpg/400px-New_file.jpg")(signedIn)
      status(result) === SEE_OTHER
      redirectLocation(result) must
        beSome("https://upload.wikimedia.org/wikipedia/commons/thumb/a/ab/New_file.jpg/500px-New_file.jpg")
      wikimediaRequests.isEmpty must beTrue
    }

    "send a file not stored to Wikimedia at its next size up, a PDF's page prefix kept; signed out 401" in {
      val (controller, _) = proxy()
      store(commonsImage(1L, "New_file"))

      val missing = controller.serve("x/xx/Missing.jpg/400px-Missing.jpg")(signedIn)
      status(missing) === SEE_OTHER
      redirectLocation(missing) must beSome("https://upload.wikimedia.org/wikipedia/commons/thumb/x/xx/Missing.jpg/500px-Missing.jpg")
      val pdf = controller.serve("x/xx/Missing.pdf/page1-167px-Missing.pdf.jpg")(signedIn)
      redirectLocation(pdf) must beSome("https://upload.wikimedia.org/wikipedia/commons/thumb/x/xx/Missing.pdf/page1-250px-Missing.pdf.jpg")
      // one of Wikimedia's sizes as it is
      redirectLocation(controller.serve("x/xx/Missing.jpg/330px-Missing.jpg")(signedIn)) must
        beSome("https://upload.wikimedia.org/wikipedia/commons/thumb/x/xx/Missing.jpg/330px-Missing.jpg")
      status(controller.serve("a/ab/New_file.jpg/400px-New_file.jpg")(FakeRequest())) === UNAUTHORIZED
    }

    "only ever send the browser to, or fetch from, Wikimedia at one of its sizes or the original" in {
      // the widths of production's redirects (824, 167), the pages' and odd ones
      val widths = Seq(1, 19, 21, 150, 167, 225, 300, 333, 400, 450, 600, 666, 824, 961, 1466, 2000, 3839, 3840)
      val (controller, _) = proxy()
      store(commonsImage(1L, "New_file"), commonsImage(2L, "New_unknown").copy(width = 0, height = 0),
        commonsImage(3L, "Error_file"))

      val locations = for {
        name   <- Seq("New_file", "New_unknown", "Error_file", "Missing")
        px     <- widths
        result  = controller.serve(s"a/ab/$name.jpg/${px}px-$name.jpg")(signedIn)
        if status(result) == SEE_OTHER
        url    <- redirectLocation(result)
      } yield url
      locations must not(beEmpty)
      forall(locations.filter(_.startsWith("https://upload.wikimedia.org/"))) { url =>
        FakeWikimedia.thumbnailWidth(url).forall(Global.wikimediaSteps.contains) must beTrue
      }
      wikimediaRequests.isEmpty must beFalse
      forall(wikimediaRequests.toArray.toSeq.map(_.toString))(path => FakeWikimedia.refused(path) must beFalse)
    }
  }
}
