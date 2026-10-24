package controllers

import org.intracer.wmua._
import org.scalawiki.MwBot

import java.net.URLEncoder

object Global {
  final val COMMONS_WIKIMEDIA_ORG = "commons.wikimedia.org"

  val gallerySizeX = 300
  val gallerySizeY = 250
  val thumbSizeX = 150
  val thumbSizeY = 120
  val largeSizeX = 1280
  val largeSizeY = 1100

  val sizeFactors = Seq(1.0, 1.5, 2.0)

  /** The boxes the views fit thumbnails into, (width, height), at each of [[sizeFactors]]:
    * [[gallerySizeY]] tall at any width in a binary round's gallery, the gallery box in a
    * rated one and the thumbnail box in the large view's bar.
    */
  val thumbnailBoxes: Seq[(Option[Int], Int)] =
    sizeFactors.flatMap { factor =>
      def scaled(size: Int) = (size * factor).toInt
      Seq(
        None                       -> scaled(gallerySizeY),
        Some(scaled(gallerySizeX)) -> scaled(gallerySizeY),
        Some(scaled(thumbSizeX))   -> scaled(thumbSizeY)
      )
    }.distinct

  /** The widths a `width` × `height` image is shown at in the [[thumbnailBoxes]]. Those of
    * them narrower than the file are cached ([[services.LocalImageCacheService]]).
    */
  def thumbnailWidths(width: Int, height: Int): Seq[Int] =
    thumbnailBoxes.map {
      case (None, y)    => ImageUtil.resizeTo(width, height, y)
      case (Some(x), y) => ImageUtil.resizeTo(width, height, x, y)
    }.distinct.sorted

  /** Where the image is shown `px` wide, as [[resizeTo]] gives it. */
  def resizeToPx(info: Image, px: Int): String =
    resizeToPx(info, if (info.width > 0) (info.height.toLong * px / info.width).toInt else 0, px)

  /** The app's Commons bot: `MwBot.fromHost` keeps one bot (and its session cookies) per
    * host, so this is [[modules.AppModule.bot]] too, logged in by the import jobs'
    * [[services.imports.CommonsLogin]] when credentials are set. Anonymous where no app
    * has started (scripts, some specs), unless `SCALAWIKI_LOGIN` / `SCALAWIKI_PASSWORD`
    * are set: then scalawiki logs it in as that account when it is created.
    */
  lazy val commons: MwBot = MwBot.fromHost(COMMONS_WIKIMEDIA_ORG)

  val useLegacyThumbUrl = true
  val thumbUrl: (Image, Int, Int) => String =
    if (useLegacyThumbUrl) legacyThumbUlr else thumbPhpUrl

  def resizeTo(info: Image, resizeToWidth: Int, resizeToHeight: Int): String = {
    val px = info.resizeTo(resizeToWidth, resizeToHeight)
    resizeToPx(info, resizeToHeight, px)
  }

  def resizeTo(info: Image, resizeToHeight: Int): String = {
    val px = info.resizeTo(resizeToHeight)
    resizeToPx(info, resizeToHeight, px)
  }

  private def resizeToPx(info: Image, resizeToHeight: Int, px: Int) = {
    if (px < info.width || info.thumbnailOnly) {
      thumbUrl(info, px, resizeToHeight)
    } else {
      info.url.getOrElse("")
    }
  }

  def srcSet(image: Image, resizeToWidth: Int, resizeToHeight: Int): String = {
    s"${resizeTo(image, (resizeToWidth * 1.5).toInt, (resizeToHeight * 1.5).toInt)} 1.5x, ${resizeTo(image, resizeToWidth * 2, resizeToHeight * 2)} 2x"
  }

  /** [[srcSet]] of a file deleted on Commons: only the sizes the cache keeps, those narrower
    * than the file ([[services.LocalImageCacheService]]), as the file itself is gone. Empty
    * if there are none.
    */
  def cachedSrcSet(image: Image, resizeToWidth: Int, resizeToHeight: Int): String =
    Seq(1.5 -> "1.5x", 2.0 -> "2x").flatMap { case (factor, descriptor) =>
      val height = (resizeToHeight * factor).toInt
      val px = image.resizeTo((resizeToWidth * factor).toInt, height)
      Option.when(px < image.width)(s"${thumbUrl(image, px, height)} $descriptor")
    }.mkString(", ")

  /** The widths Wikimedia serves thumbnails at ($wgThumbnailSteps): it refuses others. */
  val wikimediaSteps: Seq[Int] = Seq(20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840)

  /** The narrowest of [[wikimediaSteps]] at least `px` wide, else the widest. */
  def wikimediaStep(px: Int): Int = wikimediaSteps.find(_ >= px).getOrElse(wikimediaSteps.last)

  private val WikimediaUpload = "https://upload.wikimedia.org"

  /** The large view's image, as the browser loads it from Wikimedia, not from this server:
    * at Wikimedia's sizes ([[wikimediaSteps]]) only, so nothing is fetched or scaled here.
    *
    * @param sizes    the width the image is shown at in the large box: of `srcset`, the
    *                 browser loads the narrowest at least that times the screen's pixel
    *                 ratio wide
    * @param fallback the image's thumbnail on this server, to load if Wikimedia doesn't have
    *                 it (`onerror`): the file may have been renamed or deleted on Commons,
    *                 which the proxy finds out ([[ImageProxyController]])
    */
  final case class LargeImage(src: String, srcset: String, sizes: Option[String], fallback: Option[String])

  /** [[LargeImage]] of a Commons file of known width: Wikimedia's sizes from the one the
    * large box needs to twice that, the file itself in place of those no narrower than it
    * (unless only thumbnails show it, [[Image.thumbnailOnly]]). Other files' as before,
    * through this server.
    */
  def largeImage(image: Image): LargeImage =
    if (image.width <= 0 || !image.url.exists(services.imports.CommonsFileCheck.isCommonsUrl))
      LargeImage(resizeTo(image, largeSizeX, largeSizeY), srcSet(image, largeSizeX, largeSizeY), None, None)
    else {
      val px = image.resizeTo(largeSizeX, largeSizeY)
      val (from, to) = (wikimediaStep(px), wikimediaStep(2 * px))
      val candidates = wikimediaSteps
        .filter(step => step >= from && step <= to)
        .map { step =>
          if (!image.thumbnailOnly && step >= image.width) image.width -> image.url.getOrElse("")
          else step -> (WikimediaUpload + legacyThumbUrl(image, step))
        }
        .distinctBy(_._1)
      LargeImage(
        src = candidates.head._2,
        srcset = candidates.map { case (width, url) => s"$url ${width}w" }.mkString(", "),
        sizes = Some(s"${px}px"),
        fallback = Some(legacyThumbUrl(image, from))
      )
    }

  def thumbPhpUrl(info: Image, px: Int, resizeToHeight: Int): String = {
    val file = URLEncoder.encode(
      info.title.replaceFirst("File:", "").replace(" ", "_"),
      "UTF-8"
    )
    s"https://commons.wikimedia.org/w/thumb.php?f=$file&w=$px"
  }

  def legacyThumbUlr(info: Image, px: Int, resizeToHeight: Int): String =
    legacyThumbUrl(info, px)

  private[controllers] def legacyThumbUrl(info: Image, px: Int): String = {
    val lowerCaseTitle = info.title.toLowerCase
    val isPdf = lowerCaseTitle.endsWith(".pdf")
    val isTif = List(".tif", ".tiff").exists(lowerCaseTitle.endsWith)

    val url = services.imports.CommonsFileCheck.withoutQuery(info.url.getOrElse(""))
    val lastSlash = url.lastIndexOf("/")
    val utf8Size = info.title.getBytes("UTF8").length
    val thumbStr = if (utf8Size > 165) "thumbnail.jpg" else url.substring(lastSlash + 1)

    url.replaceFirst(
      "(?:https?:)?//upload\\.wikimedia\\.org/wikipedia/commons/",
      "/wikipedia/commons/thumb/"
    ) + "/" +
      (if (isPdf) "page1-" else if (isTif) "lossy-page1-" else "") +
      px + "px-" + thumbStr + (if (isPdf || isTif) ".jpg" else "")
  }
}
