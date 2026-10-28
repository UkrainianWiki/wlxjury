package org.intracer.wmua

import java.text.DecimalFormat

case class Image(
    pageId: Long,
    title: String,
    url: Option[String] = None,
    pageUrl: Option[String] = None,
    width: Int = 0,
    height: Int = 0,
    monumentId: Option[String] = None,
    description: Option[String] = None,
    author: Option[String] = None,
    size: Option[Int] = None,
    mime: Option[String] = None,
    mediaType: Option[String] = None
) extends Ordered[Image] {

  def parsedAuthor: Option[String] =
    author.map(org.scalawiki.dto.Image.parseUser)

  def compare(that: Image): Int = (this.pageId - that.pageId).sign.toInt

  def region: Option[String] = monumentId.map(_.split("-")(0))

  def resizeTo(resizeToX: Int, resizeToY: Int): Int =
    ImageUtil.resizeTo(width, height, resizeToX, resizeToY)

  def resizeTo(resizeToY: Int): Int =
    ImageUtil.resizeTo(width, height, resizeToY)

  def resolutionStr = s"$width x $height"

  def mpxStr: String = ImageUtil.fmt.format(mpx)

  def mpx: Double = width * height / 1_000_000.0

  def isImage: Boolean = !isVideo

  /** By MediaWiki's media type, else the file extension - see scalawiki's `Image.isVideo`. */
  def isVideo: Boolean = org.scalawiki.dto.Image.isVideo(title, mediaType)

  /** A file shown by a thumbnail also at its full width or wider (a PDF, TIFF, SVG or a
    * video): a browser can't show it in an `<img>`.
    */
  def thumbnailOnly: Boolean = {
    val lowerCaseTitle = title.toLowerCase
    ImageUtil.ThumbnailOnlyExtensions.exists(lowerCaseTitle.endsWith)
  }

}

object ImageUtil {

  def fmt = new DecimalFormat("0.0")

  val VideoExtensions: Seq[String] = List(".webm", ".ogv", ".mpg", ".mpeg")

  val ThumbnailOnlyExtensions: Seq[String] = List(".pdf", ".tif", ".tiff", ".svg") ++ VideoExtensions

  /** The file name of Wikimedia's thumbnail `px` wide of the file `title`, whose name in its
    * URL is `urlName`, as MediaWiki names it: `250px-A.jpg`; a PDF's first page
    * `page1-250px-A.pdf.jpg`, a TIFF's `lossy-page1-250px-A.tif.jpg`; an SVG rendered as
    * `250px-A.svg.png`; a video's still `250px--A.webm.jpg`. A name over 165 UTF-8 bytes is
    * `thumbnail` with its extension (`250px-thumbnail.svg.png`).
    */
  def thumbName(title: String, urlName: String, px: Int): String = {
    val lower = title.toLowerCase
    def is(extensions: String*) = extensions.exists(lower.endsWith)
    val name =
      if (title.getBytes("UTF-8").length > 165) {
        val dot = urlName.lastIndexOf('.')
        if (dot >= 0) "thumbnail" + urlName.substring(dot) else "thumbnail"
      } else urlName
    if (is(".pdf")) s"page1-${px}px-$name.jpg"
    else if (is(".tif", ".tiff")) s"lossy-page1-${px}px-$name.jpg"
    else if (is(".svg")) s"${px}px-$name.png"
    else if (is(VideoExtensions: _*)) s"${px}px--$name.jpg"
    else s"${px}px-$name"
  }

  def resizeTo(w: Int, h: Int, resizeToX: Int, resizeToY: Int): Int = {
    val yRatio = h.toDouble / resizeToY

    Seq(resizeToX, (w / yRatio).toInt, w).min
  }

  def resizeTo(w: Int, h: Int, resizeToY: Int): Int = {
    val yRatio = h.toDouble / resizeToY

    Seq((w / yRatio).toInt, w).min
  }

}
