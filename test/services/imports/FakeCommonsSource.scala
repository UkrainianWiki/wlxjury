package services.imports

import org.intracer.wmua.Image
import org.scalawiki.dto.{Namespace, Page}
import play.api.libs.json.Json

import java.util.concurrent.atomic.AtomicInteger

/** A file on the fake Commons: its image info, and its page text. */
final case class FakeFile(image: Image, text: Option[String] = None)

/** Commons for specs: the files of any source are `files`, listed by page id, `limit` at a
  * time with an offset as the cursor.
  */
class FakeCommonsSource(files: Seq[FakeFile]) extends CommonsSource {

  private val sorted = files.sortBy(_.image.pageId)
  private val byId = files.map(f => f.image.pageId -> f).toMap
  private val byTitle = files.map(f => f.image.title -> f).toMap

  val listCalls = new AtomicInteger()

  /** Fails the n-th call of [[listFiles]] (from 1), once. */
  @volatile var failListCall: Int = 0

  def listFiles(source: ImportSource, cursor: play.api.libs.json.JsObject, limit: Int): FilePage = {
    val n = listCalls.incrementAndGet()
    if (n == failListCall) {
      failListCall = 0
      throw new RuntimeException(s"Commons is down (call $n)")
    }
    val from = (cursor \ "offset").asOpt[Int].getOrElse(0)
    val next = from + limit
    FilePage(sorted.slice(from, next).map(_.image.pageId), Option.when(next < sorted.size)(Json.obj("offset" -> next)))
  }

  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] =
    pageIds.flatMap(byId.get).map(page(_, info, text))

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] =
    titles.map(t => byTitle.get(t).map(page(_, info, text)).getOrElse(Page(None, Some(Namespace.FILE), t, missing = true)))

  def categoryFileCount(category: String): Option[Long] = Some(files.size.toLong)

  private def page(f: FakeFile, info: Boolean, text: Boolean): Page = {
    val i = f.image
    Page(
      id = Some(i.pageId),
      ns = Some(Namespace.FILE),
      title = i.title,
      revisions = if (text) Page.revisionsFromText(f.text) else Nil,
      images =
        if (!info) Nil
        else
          Seq(
            org.scalawiki.dto.Image(
              title = i.title,
              url = i.url,
              pageUrl = i.pageUrl,
              size = i.size.map(_.toLong),
              width = Some(i.width),
              height = Some(i.height),
              mime = i.mime,
              mediaType = i.mediaType
            )
          )
    )
  }
}
