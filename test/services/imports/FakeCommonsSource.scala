package services.imports

import org.intracer.wmua.Image
import org.scalawiki.dto.{Namespace, Page}
import play.api.libs.json.Json

import java.util.concurrent.atomic.AtomicInteger

/** A file on the fake Commons: its image info, and its page text. */
final case class FakeFile(image: Image, text: Option[String] = None)

/** Commons for specs: the files of any source are `files`, listed by page id, `limit` at a
  * time with an offset as the cursor.
  *
  * @param maxIdsPerRequest the batch size its account allows (500: `apihighlimits`)
  */
class FakeCommonsSource(files: Seq[FakeFile], val maxIdsPerRequest: Int = CommonsLogin.Limit) extends CommonsSource {

  private val sorted = files.sortBy(_.image.pageId)
  private val byId = files.map(f => f.image.pageId -> f).toMap
  private val byTitle = files.map(f => f.image.title -> f).toMap

  val listCalls = new AtomicInteger()

  /** Calls of [[files]], [[missingFiles]] and [[filesByTitles]], each one API request on the real Commons. */
  val detailCalls = new AtomicInteger()

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

  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] = {
    detail(pageIds.size)
    pageIds.filterNot(unparsed).flatMap(byId.get).map(page(_, info, text))
  }

  /** Files [[files]] leaves out, as scalawiki's parser drops a page it can't parse. */
  @volatile var unparsed: Set[Long] = Set.empty

  def missingFiles(pageIds: Seq[Long]): Seq[Long] = {
    detail(pageIds.size)
    pageIds.filterNot(byId.contains)
  }

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] = {
    detail(titles.size)
    titles.map(t => byTitle.get(t).map(page(_, info, text)).getOrElse(Page(None, Some(Namespace.FILE), t, missing = true)))
  }

  /** Counts a details request, refusing one larger than the account allows, as the API does. */
  private def detail(n: Int): Unit = {
    detailCalls.incrementAndGet()
    if (n > maxIdsPerRequest) throw new IllegalArgumentException(s"$n files in one request, the limit is $maxIdsPerRequest")
  }

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
