package controllers

import org.intracer.wmua.Image
import org.specs2.mutable.Specification

/** The large view's image: loaded from Wikimedia at its sizes only, falling back to this
  * server's proxy.
  */
class GlobalLargeImageSpec extends Specification {

  private val Upload = "https://upload.wikimedia.org/wikipedia/commons"

  private def image(width: Int, height: Int, name: String = "Photo.jpg", url: Option[String] = None): Image =
    Image(1L, s"File:$name", Some(url.getOrElse(s"$Upload/a/ab/$name")), None, width, height)

  private def thumb(px: Int, name: String = "Photo.jpg") = s"$Upload/thumb/a/ab/$name/${px}px-$name"

  "largeImage" should {

    "offer Wikimedia's sizes from the one the large box needs to twice that" in {
      val large = Global.largeImage(image(4000, 3000)) // shown 1280 wide

      large.src === thumb(1280)
      large.srcset === s"${thumb(1280)} 1280w, ${thumb(1920)} 1920w, ${thumb(3840)} 3840w"
      large.sizes must beSome("1280px")
      large.fallback must beSome("/wikipedia/commons/thumb/a/ab/Photo.jpg/1280px-Photo.jpg")
      large.width must beSome(1280)
    }

    "fit a tall image into the box by its height" in {
      val large = Global.largeImage(image(3300, 4400)) // shown 825 wide

      large.srcset === s"${thumb(960)} 960w, ${thumb(1280)} 1280w, ${thumb(1920)} 1920w"
      large.sizes must beSome("825px")
      large.width must beSome(825)
    }

    "offer the file itself in place of the sizes no narrower than it" in {
      Global.largeImage(image(3000, 2000)).srcset ===
        s"${thumb(1280)} 1280w, ${thumb(1920)} 1920w, $Upload/a/ab/Photo.jpg 3000w"

      val small = Global.largeImage(image(800, 600))
      small.src === s"$Upload/a/ab/Photo.jpg"
      small.srcset === s"$Upload/a/ab/Photo.jpg 800w"
    }

    "offer only thumbnails of a file a browser can't show, also wider than it" in {
      val pdf = Global.largeImage(image(300, 400, "Doc.pdf"))
      pdf.srcset === Seq(330, 500, 960).map(px => s"$Upload/thumb/a/ab/Doc.pdf/page1-${px}px-Doc.pdf.jpg ${px}w").mkString(", ")
    }

    "keep the tracking parameters out of the thumbnails, and in the file's own url" in {
      val withQuery = image(800, 600, url = Some(s"$Upload/a/ab/Photo.jpg?utm_source=commons"))
      Global.largeImage(withQuery).src === s"$Upload/a/ab/Photo.jpg?utm_source=commons"
      Global.largeImage(withQuery.copy(width = 4000, height = 3000)).src === thumb(1280)
    }

    "load a file not on Commons, or of unknown size, through this server as before" in {
      val other = image(4000, 3000, url = Some("https://upload.wikimedia.org/wikipedia/uk/a/ab/Photo.jpg"))
      Global.largeImage(other).fallback must beNone
      Global.largeImage(other).sizes must beNone
      // shown at the density of the screen's srcset candidate: a width not known here
      Global.largeImage(other).width must beNone

      val unknown = image(0, 0)
      Global.largeImage(unknown).src === Global.resizeTo(unknown, Global.largeSizeX, Global.largeSizeY)
      Global.largeImage(unknown).fallback must beNone
    }
  }

  "largeImageBox" should {

    "be the width and the height of the stored proportions" in {
      Global.largeImageBox(image(4000, 3000), Some(1280)) must beSome((1280, 960))
      Global.largeImageBox(image(3300, 4400), Some(825)) must beSome((825, 1100))
      Global.largeImageBox(image(6000, 4001), Some(1280)) must beSome((1280, 854)) // rounded
      Global.largeImageBox(image(100000, 10), Some(1280)) must beSome((1280, 1)) // never 0
    }

    "be none without a width or a stored size" in {
      Global.largeImageBox(image(4000, 3000), None) must beNone
      Global.largeImageBox(image(4000, 3000), Some(0)) must beNone
      Global.largeImageBox(image(0, 0), Some(1280)) must beNone
      Global.largeImageBox(image(4000, 0), Some(1280)) must beNone
      Global.largeImageBox(image(0, 3000), Some(1280)) must beNone
    }
  }

  "wikimediaStep" should {
    "be the narrowest of Wikimedia's sizes no narrower, else the widest" in {
      Global.wikimediaStep(250) === 250
      Global.wikimediaStep(251) === 330
      Global.wikimediaStep(5000) === 3840
    }
  }
}
