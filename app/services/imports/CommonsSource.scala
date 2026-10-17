package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.prop.iiprop.{IiProp, IiPropArgs}
import org.scalawiki.dto.cmd.query.prop.rvprop.RvProp
import org.scalawiki.dto.cmd.query.prop.{CategoryInfo, ImageInfo, Prop, PropArg, Revisions, RvPropArgs}
import org.scalawiki.dto.cmd.query.{Generator, PageIdsParam, Query, QueryParam, TitlesParam}
import org.scalawiki.dto.{Namespace, Page}
import play.api.libs.json.JsObject

import java.net.URLDecoder
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._
import scala.util.Try

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
  * without props, one API page per call ([[MwApi.page]]), so its `continue` map is the
  * cursor of the next page; the files' details are fetched by page id, where `bot.run`
  * merges the pages that a `continue` splits (`rvcontinue`, `iicontinue`).
  */
@Singleton
class MwCommonsSource @Inject() (bot: MwBot) extends CommonsSource {

  import MwCommonsSource._

  def listFiles(source: ImportSource, cursor: JsObject, limit: Int): FilePage = {
    val (module, generatorTitle, pageTitle) = source match {
      case ImportSource.Category(t)  => ("categorymembers", Some(t), None)
      case ImportSource.Template(t)  => ("embeddedin", Some(t), None)
      case ImportSource.PageFiles(t) => ("images", None, Some(t))
    }
    val generator = ListArgs.toDsl(module, generatorTitle, None, Set(Namespace.FILE), Some(limit.toString)).get
    val params: Seq[QueryParam[Any]] = pageTitle.map(t => TitlesParam(Seq(t))).toSeq :+ Generator(generator)
    val (pages, next) = api.page(Action(Query(params: _*)), cursor)
    FilePage(pages.flatMap(_.id).sorted, next)
  }

  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] =
    if (pageIds.isEmpty) Nil else api.run(Query(PageIdsParam(pageIds), Prop(props(info, text): _*)))

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] =
    if (titles.isEmpty) Nil else api.run(Query(TitlesParam(titles), Prop(props(info, text): _*)))

  def categoryFileCount(category: String): Option[Long] =
    if (!category.toLowerCase.startsWith("category:")) None
    else api.run(Query(TitlesParam(Seq(category)), Prop(CategoryInfo))).headOption.flatMap(_.categoryInfo.map(_.files))

  private def props(info: Boolean, text: Boolean): Seq[PropArg] =
    Option.when(info)(ImageInfo(IiProp(IiPropArgs.byNames(ImageInfoProps): _*))).toSeq ++
      Option.when(text)(Revisions(RvProp(RvPropArgs.byNames(TextProps): _*)))

  private lazy val api = new MwApi(bot, retryDelay)

  /** The wait before the first retry of a failed request; it doubles with each. */
  protected def retryDelay: FiniteDuration = MwApi.RetryDelay
}

object MwCommonsSource {
  val ImageInfoProps: Seq[String] = Seq("timestamp", "user", "size", "url", "mime", "mediatype")
  val TextProps: Seq[String] = Seq("content", "timestamp", "user", "comment")
}
