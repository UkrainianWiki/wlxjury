package services

import org.intracer.wmua.Image
import org.scalawiki.MwBot
import org.scalawiki.dto.Namespace

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext}

trait CommonsImageFetcher {

  implicit def ec: ExecutionContext

  private val imageInfoProps = Set("size", "url", "mime")

  /** Fetch up to `limit` eligible images from a Commons category via scalawiki. */
  def fetchImagesFromCommons(category: String, limit: Int): Seq[Image] = {
    val bot   = MwBot.fromHost("commons.wikimedia.org")
    val pages = Await.result(
      bot
        .page(category)
        .imageInfoByGenerator(
          "categorymembers",
          "cm",
          namespaces  = Set(Namespace.FILE),
          props       = imageInfoProps,
          titlePrefix = None
        )
        .map(_.toSeq),
      60.seconds
    )
    pages
      .flatMap { page =>
        page.images.headOption.flatMap { ii =>
          for {
            id  <- page.id
            url <- ii.url
            w   <- ii.width
            h   <- ii.height
          } yield Image(id, page.title, Some(url), ii.pageUrl, w, h, mime = ii.mime)
        }
      }
      .filter(img => img.isImage && img.url.isDefined && img.width > 0 && img.height > 0)
      .take(limit)
  }
}
