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

  /** The widths the views show a `width` × `height` image at, as thumbnails, at each of
    * [[sizeFactors]]: [[gallerySizeY]] tall in a binary round's gallery, fit into the
    * gallery box in a rated one and into the thumbnail box in the large view's bar.
    * Those of them narrower than the file are cached ([[services.LocalImageCacheService]]).
    */
  def thumbnailWidths(width: Int, height: Int): Seq[Int] =
    sizeFactors.flatMap { factor =>
      def scaled(size: Int) = (size * factor).toInt
      Seq(
        ImageUtil.resizeTo(width, height, scaled(gallerySizeY)),
        ImageUtil.resizeTo(width, height, scaled(gallerySizeX), scaled(gallerySizeY)),
        ImageUtil.resizeTo(width, height, scaled(thumbSizeX), scaled(thumbSizeY))
      )
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

    // Commons imageinfo API appends UTM tracking params to `url` (e.g. "?utm_source=..."),
    // which must be stripped before treating the trailing segment as a filename.
    val url = info.url.getOrElse("").takeWhile(_ != '?')
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
