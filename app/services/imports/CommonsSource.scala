package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.prop.iiprop.{IiProp, IiPropArgs}
import org.scalawiki.dto.cmd.query.prop.rvprop.RvProp
import org.scalawiki.dto.cmd.query.prop.{Categories, CategoryInfo, ClLimit, ImageInfo, Prop, PropArg, Revisions, RvPropArgs}
import org.scalawiki.dto.cmd.query.{Generator, PageIdsParam, Query, QueryParam, TitlesParam}
import org.scalawiki.dto.{MwException, Namespace, Page}
import play.api.Logging
import play.api.libs.json.JsObject

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration._

/** Where a contest's files come from: the "source" field of the files page. */
sealed trait ImportSource {
  def title: String
}

object ImportSource {

  /** The category's files. */
  final case class Category(title: String) extends ImportSource

  /** The files that embed the template. */
  final case class Template(title: String) extends ImportSource

  /** The files used on the page, e.g. a list of winners: of a WLx contest, those in its
    * country categories only ([[ContestCountries]]), not the icons and logos it shows too.
    */
  final case class PageFiles(title: String) extends ImportSource

  def apply(source: String): ImportSource = {
    val lower = source.toLowerCase
    if (lower.startsWith("category:")) Category(source)
    else if (lower.startsWith("template:")) Template(source)
    else PageFiles(source)
  }

  /** The titles of a pasted list, one per line, in whatever form they were pasted
    * ([[FileTitles]]), as Commons names them, without duplicates.
    */
  def titles(list: String): Seq[String] = FileTitles(list)
}

/** One page of a source's files: their page ids, and the cursor of the next page (None:
  * this was the last).
  */
final case class FilePage(pageIds: Seq[Long], next: Option[JsObject])

/** Commons, one bounded request at a time: what a step of a
  * [[ImageImportHandler]] job fetches. Blocking: called on the job scheduler's fetch lane.
  */
trait CommonsSource {

  /** Files per [[files]] or [[filesByTitles]] call: 500 when the account the requests
    * use has `apihighlimits`, 50 without ([[CommonsLogin]]).
    */
  def maxIdsPerRequest: Int

  /** One page of at most `limit` files of the source, from `cursor` (`{}`: the first). */
  def listFiles(source: ImportSource, cursor: JsObject, limit: Int): FilePage

  /** The files (at most [[maxIdsPerRequest]]), with their image info and/or their page text. */
  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page]

  /** Of the page ids (at most [[maxIdsPerRequest]]), those Commons has no page of: the API
    * answers them `missing`. [[files]] can't tell them from pages it couldn't parse.
    */
  def missingFiles(pageIds: Seq[Long]): Seq[Long]

  /** The files (at most [[maxIdsPerRequest]]) by title; a missing file comes back without an id. */
  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page]

  /** The number of files in the category, if it is one. */
  def categoryFileCount(category: String): Option[Long]

  /** The categories of the files (at most [[maxIdsPerRequest]]), by page id; a file without
    * categories is left out.
    */
  def categories(pageIds: Seq[Long]): Map[Long, Seq[String]]
}

/** [[CommonsSource]] over the API. A source's files are listed with a generator query
  * without props, one API page per call ([[MwApi.page]]), so its `continue` map is the
  * cursor of the next page; the files' details are fetched by page id, where `bot.run`
  * merges the pages that a `continue` splits (`rvcontinue`, `iicontinue`).
  *
  * The batch size comes from `login`, which checks before each step that the session
  * still allows it; a batch the API still refuses as too large (`toomanyvalues`) is
  * fetched again in batches of [[CommonsLogin.Limit]], and the next step checks again.
  */
@Singleton
class MwCommonsSource @Inject() (bot: MwBot, login: CommonsLogin) extends CommonsSource with Logging {

  import MwCommonsSource._

  def maxIdsPerRequest: Int = login.idsPerRequest

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
    batch(pageIds)(ids => api.run(Query(PageIdsParam(ids), Prop(props(info, text): _*))))

  def missingFiles(pageIds: Seq[Long]): Seq[Long] =
    batch(pageIds)(api.missingPageIds)

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] =
    batch(titles)(ts => api.run(Query(TitlesParam(ts), Prop(props(info, text): _*))))

  /** One request of `values`. One the API refuses as too large for the session (it has
    * become anonymous) is fetched again [[CommonsLogin.Limit]] values per request, so the
    * step still succeeds, and the next step checks the session again.
    */
  private def batch[V, R](values: Seq[V])(request: Seq[V] => Seq[R]): Seq[R] =
    if (values.isEmpty) Nil
    else
      try request(values)
      catch {
        case e: MwException if e.code == TooManyValues && values.size > CommonsLogin.Limit =>
          login.invalidate()
          logger.warn(s"${bot.host}: ${values.size} values refused (${e.info}); fetching ${CommonsLogin.Limit} per request")
          values.grouped(CommonsLogin.Limit).flatMap(request).toSeq
      }

  def categories(pageIds: Seq[Long]): Map[Long, Seq[String]] =
    batch(pageIds)(ids => api.run(Query(PageIdsParam(ids), Prop(Categories(ClLimit("max"))))))
      .flatMap(page => page.id.filter(_ => page.categories.nonEmpty).map(_ -> page.categories))
      .toMap

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

  /** The API's error for more values than the session may send in one parameter. */
  val TooManyValues = "toomanyvalues"
}
