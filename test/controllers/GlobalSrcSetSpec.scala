package controllers

import org.intracer.wmua.Image
import org.specs2.mutable.Specification

/** A gallery tile's larger sizes: of a file deleted on Commons, only those the cache keeps. */
class GlobalSrcSetSpec extends Specification {

  private def image(width: Int, height: Int): Image =
    Image(1L, "File:Photo.jpg", Some("https://upload.wikimedia.org/wikipedia/commons/a/ab/Photo.jpg"), None, width, height)

  private def cached(px: Int) = s"/wikipedia/commons/thumb/a/ab/Photo.jpg/${px}px-Photo.jpg"

  "cachedSrcSet" should {

    "offer the thumbnails at both sizes of a file wider than them" in {
      Global.cachedSrcSet(image(4000, 3000), Global.gallerySizeX, Global.gallerySizeY) ===
        s"${cached(450)} 1.5x, ${cached(600)} 2x"
    }

    "leave out a size the file itself would be shown at, which srcSet offers" in {
      val small = image(500, 375)
      Global.srcSet(small, Global.gallerySizeX, Global.gallerySizeY) must contain("/a/ab/Photo.jpg 2x")
      Global.cachedSrcSet(small, Global.gallerySizeX, Global.gallerySizeY) === s"${cached(450)} 1.5x"
    }

    "be empty for a file no wider than its 1x size" in {
      Global.cachedSrcSet(image(300, 225), Global.gallerySizeX, Global.gallerySizeY) === ""
    }
  }
}
