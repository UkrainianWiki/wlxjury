package services

import org.intracer.wmua.Image
import org.specs2.mutable.Specification

class ChangedImagesSpec extends Specification {

  private def image(id: Long) = Image(
    pageId = id, title = s"File:Image$id.jpg", url = Some(s"url$id"), pageUrl = None,
    width = 640, height = 480, monumentId = Some(s"12-345-$id"), description = Some(s"descr$id"))

  "ImageService.changedImages" should {

    "leave out unchanged images, which a re-import fetches again" in {
      val stored = (1L to 3L).map(image)
      // as fetched from Commons: with a page URL, which reading the image back lacks
      val fetched = stored.map(_.copy(pageUrl = Some("https://commons.wikimedia.org/wiki/File:X.jpg")))
      ImageService.changedImages(fetched, stored) must beEmpty
    }

    "return the changed images only" in {
      val stored = (1L to 4L).map(image)
      val fetched = Seq(
        image(1L).copy(monumentId = Some("22-345-1")),
        image(2L),
        image(3L).copy(width = 1024),
        image(4L).copy(description = Some("new")))
      ImageService.changedImages(fetched, stored).map(_.pageId) === Seq(1L, 3L, 4L)
    }

    "leave out new images" in {
      ImageService.changedImages(Seq(image(5L)), Seq(image(1L))) must beEmpty
    }
  }
}
