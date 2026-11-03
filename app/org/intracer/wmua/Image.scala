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

}

object ImageUtil {

  def fmt = new DecimalFormat("0.0")

  def resizeTo(w: Int, h: Int, resizeToX: Int, resizeToY: Int): Int = {
    val yRatio = h.toDouble / resizeToY

    Seq(resizeToX, (w / yRatio).toInt, w).min
  }

  def resizeTo(w: Int, h: Int, resizeToY: Int): Int = {
    val yRatio = h.toDouble / resizeToY

    Seq((w / yRatio).toInt, w).min
  }

}
