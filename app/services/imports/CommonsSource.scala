package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.prop.iiprop.{IiProp, IiPropArgs}
import org.scalawiki.dto.cmd.query.prop.rvprop.RvProp
import org.scalawiki.dto.cmd.query.prop.{CategoryInfo, ImageInfo, Prop, PropArg, Revisions, RvPropArgs}
import org.scalawiki.dto.cmd.query.{Generator, PageIdsParam, Query, QueryParam, TitlesParam}
import org.scalawiki.dto.{MwException, Namespace, Page}
import org.scalawiki.json.Parser
import play.api.Logging
import play.api.libs.json.{JsObject, JsString}

import java.net.URLDecoder
import javax.inject.{Inject, Singleton}
import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success, Try}

/** Where a contest's files come from: the "source" field of the files page. */
sealed trait ImportSource {
  def title: String
}

object ImportSource {

  /** The category's files. */
  final case class Category(title: String) extends ImportSource

  /** The files that embed the template. */
  final case class Template(title: String) extends ImportSource

  /** The files used on the page, e.g. a list of winners. */
  final case class PageFiles(title: String) extends ImportSource

  def apply(source: String): ImportSource = {
    val lower = source.toLowerCase
    if (lower.startsWith("category:")) Category(source)
    else if (lower.startsWith("template:")) Template(source)
    else PageFiles(source)
  }

  /** The titles of a pasted list, one per line: trimmed, URL-decoded, without the Commons
    * URL prefix, with spaces for underscores, without duplicates.
    */
  def titles(list: String): Seq[String] =
    list
      .split("(\r\n|\n|\r)")
      .iterator
      .map(_.trim)
      .filter(_.nonEmpty)
      .map(t => Try(URLDecoder.decode(t, "UTF8")).getOrElse(t))
      .map(_.replace("https://commons.wikimedia.org/wiki/", "").replace("_", " "))
      .toSeq
      .distinct
}

/** One page of a source's files: their page ids, and the cursor of the next page (None:
  * this was the last).
  */
final case class FilePage(pageIds: Seq[Long], next: Option[JsObject])

/** Commons, one bounded request at a time: what a step of a
  * [[ImageImportHandler]] job fetches. Blocking: called on the job scheduler's fetch lane.
  */
trait CommonsSource {

  /** One page of at most `limit` files of the source, from `cursor` (`{}`: the first). */
  def listFiles(source: ImportSource, cursor: JsObject, limit: Int): FilePage

  /** The files (at most 50), with their image info and/or their page text. */
  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page]

  /** The files (at most 50) by title; a missing file comes back without an id. */
  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page]

  /** The number of files in the category, if it is one. */
  def categoryFileCount(category: String): Option[Long]
}

/** [[CommonsSource]] over the API. A source's files are listed with a generator query
  * without props, one API page per call, so its `continue` map is the cursor of the next
  * page; the files' details are fetched by page id, where `bot.run` merges the pages that
  * a `continue` splits (`rvcontinue`, `iicontinue`).
  */
@Singleton
class MwCommonsSource @Inject() (bot: MwBot) extends CommonsSource with Logging {

  import MwCommonsSource._

  def listFiles(source: ImportSource, cursor: JsObject, limit: Int): FilePage = {
    val (module, generatorTitle, pageTitle) = source match {
      case ImportSource.Category(t)  => ("categorymembers", Some(t), None)
      case ImportSource.Template(t)  => ("embeddedin", Some(t), None)
      case ImportSource.PageFiles(t) => ("images", None, Some(t))
    }
    val generator = ListArgs.toDsl(module, generatorTitle, None, Set(Namespace.FILE), Some(limit.toString)).get
    val params: Seq[QueryParam[Any]] = pageTitle.map(t => TitlesParam(Seq(t))).toSeq :+ Generator(generator)
    val action = Action(Query(params: _*))
    val continue = if (cursor.keys.isEmpty) Map("continue" -> "") else cursor.value.collect {
      case (k, JsString(v)) => k -> v
      case (k, v)           => k -> v.toString
    }.toMap
    val (pages, next) = postOnce(action, continue)
    FilePage(pages.flatMap(_.id).sorted, Option.when(next.nonEmpty)(JsObject(next.map { case (k, v) => k -> JsString(v) })))
  }

  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] =
    if (pageIds.isEmpty) Nil else run(Query(PageIdsParam(pageIds), Prop(props(info, text): _*)))

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] =
    if (titles.isEmpty) Nil else run(Query(TitlesParam(titles), Prop(props(info, text): _*)))

  def categoryFileCount(category: String): Option[Long] =
    if (!category.toLowerCase.startsWith("category:")) None
    else run(Query(TitlesParam(Seq(category)), Prop(CategoryInfo))).headOption.flatMap(_.categoryInfo.map(_.files))

  private def props(info: Boolean, text: Boolean): Seq[PropArg] =
    Option.when(info)(ImageInfo(IiProp(IiPropArgs.byNames(ImageInfoProps): _*))).toSeq ++
      Option.when(text)(Revisions(RvProp(RvPropArgs.byNames(TextProps): _*)))

  private def run(query: Query): Seq[Page] =
    withRetries(Action(query).pairs.toString)(Await.result(bot.run(Action(query)), RequestTimeout).toSeq)

  /** One API request, without following its `continue`: the pages and the next cursor. */
  private def postOnce(action: Action, continue: Map[String, String]): (Seq[Page], Map[String, String]) = {
    val params = (action.pairs ++ Seq("format" -> "json", "utf8" -> "")).toMap ++ continue
    withRetries(params.toString) {
      val body = Await.result(bot.post(params), RequestTimeout)
      val trimmed = body.trim
      if (!trimmed.startsWith("{")) throw new RetryableException(s"not JSON: ${trimmed.take(200)}")
      val parser = new Parser(action)
      parser.parse(body) match {
        case Success(pages)                                       => (pages, parser.continue)
        case Failure(e: MwException) if RetryableCodes(e.code)    => throw new RetryableException(e.toString)
        case Failure(e)                                           => throw e
      }
    }
  }

  /** Retries failed requests (timeouts, HTTP errors, a lagged or busy API) with backoff. */
  private def withRetries[T](what: => String)(f: => T): T = {
    var attempt = 0
    var result: Option[T] = None
    while (result.isEmpty) {
      try result = Some(f)
      catch {
        case e: MwException => throw e // an API error: retrying gives the same answer
        case NonFatal(e) if attempt < Retries =>
          attempt += 1
          logger.warn(s"Commons request failed, retry $attempt of $Retries: ${e.getMessage} ($what)")
          Thread.sleep(retryDelay.toMillis * (1L << (attempt - 1)))
      }
    }
    result.get
  }

  /** The wait before the first retry; it doubles with each. */
  protected def retryDelay: FiniteDuration = RetryDelay
}

object MwCommonsSource {
  val ImageInfoProps: Seq[String] = Seq("timestamp", "user", "size", "url", "mime", "mediatype")
  val TextProps: Seq[String] = Seq("content", "timestamp", "user", "comment")
  val RetryableCodes: Set[String] = Set("maxlag", "ratelimited", "readonly", "internal_api_error_DBQueryError")
  val Retries = 4
  val RetryDelay: FiniteDuration = 2.seconds
  val RequestTimeout: FiniteDuration = 2.minutes

  final class RetryableException(message: String) extends RuntimeException(message)

}
