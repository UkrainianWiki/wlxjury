package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.list.ListArgs
import org.scalawiki.dto.cmd.query.prop.iiprop.{IiProp, IiPropArgs, User => IiUser}
import org.scalawiki.dto.cmd.query.prop.rvprop.{RvProp, Content => RvContent}
import org.scalawiki.dto.cmd.query.prop.{Categories, CategoryInfo, ClLimit, IiLimit, ImageInfo, Prop, PropArg, Revisions, RvPropArgs}
import org.scalawiki.dto.cmd.query.{Generator, PageIdsParam, Query, QueryParam, RedirectsParam, TitlesParam}
import org.scalawiki.dto.{MwException, Namespace, Page}
import play.api.Logging
import play.api.libs.json.{JsObject, Json}

import javax.inject.{Inject, Singleton}
import scala.concurrent.Await
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
    * A file the page names by an old title is the file it redirects to.
    */
  final case class PageFiles(title: String) extends ImportSource

  def apply(raw: String): ImportSource = {
    val source = WikiTitle.normalize(raw)
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

/** A files source's name as typed on the files page, without a namespace prefix (support
  * diagnostics plan, DG11): such a name is checked on Commons as a page and as a category before
  * an import is queued. A name with a namespace prefix (`Category:`, `Template:`, `Commons:`, …)
  * is read as before ([[ImportSource.apply]]).
  */
object SourceName {

  /** Commons' namespace names and aliases, lowercased, with spaces. */
  val Namespaces: Set[String] = Set(
    "media", "special", "talk", "user", "user talk", "commons", "commons talk", "project", "project talk",
    "com", "file", "file talk", "image", "image talk", "mediawiki", "mediawiki talk", "template",
    "template talk", "help", "help talk", "category", "category talk", "creator", "creator talk",
    "timedtext", "timedtext talk", "sequence", "sequence talk", "institution", "institution talk",
    "module", "module talk", "translations", "translations talk", "campaign", "campaign talk",
    "data", "data talk", "gallery", "gallery talk"
  )

  /** The namespace the title starts with, if any (lowercased). */
  def namespaceOf(title: String): Option[String] = {
    val colon = title.indexOf(':')
    if (colon <= 0) None
    else Some(title.take(colon).trim.replace('_', ' ').toLowerCase).filter(Namespaces)
  }

  /** Whether `source` names a page or category without a namespace prefix. */
  def prefixless(source: String): Boolean = {
    val name = source.trim
    name.nonEmpty && namespaceOf(name).isEmpty
  }

  /** The category of a prefix-less name. */
  def category(name: String): String = "Category:" + name.trim

  /** The title's page on Commons, for the admin to look at. */
  def commonsUrl(title: String): String =
    "https://commons.wikimedia.org/wiki/" +
      java.net.URLEncoder.encode(title.trim.replace(' ', '_'), java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20")
}

/** What Commons has under a prefix-less name ([[SourceName]]): a page of that title, a
  * category `Category:<name>` (with members, or a category page), the category's file count, and,
  * only when both exist, the number of files the page shows (one request, at most
  * [[NameProbe.PageFilesCap]]: `pageFilesCapped` when it shows more).
  */
final case class NameProbe(
    page: Boolean,
    category: Boolean,
    categoryFiles: Long = 0L,
    pageFiles: Option[Int] = None,
    pageFilesCapped: Boolean = false
)

object NameProbe {

  /** The files of a page counted at most, one API request's worth. */
  val PageFilesCap = 500
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

  /** The files (at most [[maxIdsPerRequest]]) by title; a missing file comes back without an id.
    * A title that redirects (a renamed file's old one) is the file it redirects to.
    */
  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page]

  /** The number of files in the category, if it is one. */
  def categoryFileCount(category: String): Option[Long]

  /** What Commons has under a name without a namespace prefix ([[NameProbe]]): at most two
    * requests, both within `timeout` together. Throws when Commons can't be reached, times out,
    * or answers with an error: the files page then says it could not check the name (DG11).
    * `countPageFiles`: false when the counts aren't shown (the admin chose already), so the
    * page's files are not asked for (`pageFiles` None).
    */
  def probeName(name: String, timeout: FiniteDuration, countPageFiles: Boolean = true): NameProbe

  /** The categories of the files (at most [[maxIdsPerRequest]]), by page id; a file without
    * categories is left out.
    */
  def categories(pageIds: Seq[Long]): Map[Long, Seq[String]]

  /** The categories of the pages (at most [[maxIdsPerRequest]]), by title: of a category,
    * its parents. A page without categories is left out.
    */
  def categoriesByTitle(titles: Seq[String]): Map[String, Seq[String]]

  /** The original uploader of each file (the user of its oldest version), by page id: the
    * author of a file whose page names none ([[ImageText.withAuthors]]). A request per
    * [[CommonsSource.AuthorBatch]] files, with their whole upload history.
    */
  def originalUploaders(pageIds: Seq[Long]): Map[Long, String]

  /** The user each photographer's credit template (`{{Yuriy Kvach}}` as a whole author)
    * links to on its page, by the template's name without its namespace, as the API names
    * it. A request per [[CommonsSource.AuthorBatch]] templates; a template that links to
    * nobody is left out.
    */
  def creditTemplateUsers(templates: Seq[String]): Map[String, String]
}

object CommonsSource {

  /** Files or templates per request of [[CommonsSource.originalUploaders]] and
    * [[CommonsSource.creditTemplateUsers]], as scalawiki's `ImageQueryApi` asks for them:
    * a file's history (`iilimit=max`) or a template's text may be long.
    */
  val AuthorBatch = 50
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
    // an illegal title makes the API answer an error that scalawiki fails to read (`JsError.get`): refused here
    if (!WikiTitle.isLegal(source.title))
      throw MwException("invalidtitle", s"Not a title: ${controllers.Secured.logValue(source.title)}")
    val (module, generatorTitle, pageTitle) = source match {
      case ImportSource.Category(t)  => ("categorymembers", Some(t), None)
      case ImportSource.Template(t)  => ("embeddedin", Some(t), None)
      case ImportSource.PageFiles(t) => ("images", None, Some(t))
    }
    val generator = ListArgs.toDsl(module, generatorTitle, None, Set(Namespace.FILE), Some(limit.toString)).get
    val params: Seq[QueryParam[Any]] =
      pageTitle.toSeq.flatMap(t => Seq(TitlesParam(Seq(t)), RedirectsParam())) :+ Generator(generator)
    val (pages, next) = api.page(Action(Query(params: _*)), cursor)
    FilePage(pages.flatMap(_.id).sorted, next)
  }

  def files(pageIds: Seq[Long], info: Boolean, text: Boolean): Seq[Page] =
    batch(pageIds)(ids => api.run(Query(PageIdsParam(ids), Prop(props(info, text): _*))))

  def missingFiles(pageIds: Seq[Long]): Seq[Long] =
    batch(pageIds)(api.missingPageIds)

  def filesByTitles(titles: Seq[String], info: Boolean, text: Boolean): Seq[Page] =
    batch(titles)(ts => api.run(Query(TitlesParam(ts), RedirectsParam(), Prop(props(info, text): _*))))

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
          logger.warn(s"${bot.host}: ${values.size} values refused (${controllers.Secured.logValue(String.valueOf(e.info))}); fetching ${CommonsLogin.Limit} per request")
          values.grouped(CommonsLogin.Limit).flatMap(request).toSeq
      }

  def categories(pageIds: Seq[Long]): Map[Long, Seq[String]] =
    batch(pageIds)(ids => api.run(Query(PageIdsParam(ids), Prop(Categories(ClLimit("max"))))))
      .flatMap(page => page.id.filter(_ => page.categories.nonEmpty).map(_ -> page.categories))
      .toMap

  def categoriesByTitle(titles: Seq[String]): Map[String, Seq[String]] =
    batch(titles)(ts => api.run(Query(TitlesParam(ts), Prop(Categories(ClLimit("max"))))))
      .filter(_.categories.nonEmpty)
      .map(page => page.title -> page.categories)
      .toMap

  def originalUploaders(pageIds: Seq[Long]): Map[Long, String] =
    pageIds
      .grouped(CommonsSource.AuthorBatch)
      .flatMap(ids => api.run(Query(PageIdsParam(ids), Prop(ImageInfo(IiProp(IiUser), IiLimit("max"))))))
      // imageinfo lists the versions newest first; `bot.run` merges those an `iicontinue` splits
      .flatMap(page => page.id.zip(page.images.lastOption.flatMap(_.uploader).flatMap(_.login)))
      .toMap

  def creditTemplateUsers(templates: Seq[String]): Map[String, String] =
    templates
      .grouped(CommonsSource.AuthorBatch)
      .flatMap(ts => api.run(Query(TitlesParam(ts.map("Template:" + _)), Prop(Revisions(RvProp(RvContent))))))
      // a redirect's text links to its target, which is the user's page all the same
      .flatMap(page => page.text.flatMap(org.scalawiki.dto.Image.userIn).map(page.title.stripPrefix("Template:") -> _))
      .toMap

  def categoryFileCount(category: String): Option[Long] =
    if (!category.toLowerCase.startsWith("category:")) None
    else api.run(Query(TitlesParam(Seq(category)), Prop(CategoryInfo))).headOption.flatMap(_.categoryInfo.map(_.files))

  def probeName(name: String, timeout: FiniteDuration, countPageFiles: Boolean = true): NameProbe = {
    val title = name.trim
    // a `|` would split the titles parameter; no title has one
    if (title.isEmpty || title.contains('|')) NameProbe(page = false, category = false)
    else {
      val deadline = timeout.fromNow
      def query(params: (String, String)*): JsObject = {
        val left = deadline.timeLeft
        if (left <= Duration.Zero) throw new java.util.concurrent.TimeoutException(s"name check over $timeout")
        val body = Await.result(bot.post((params ++ Seq("action" -> "query", "format" -> "json",
          "formatversion" -> "2", "utf8" -> "")).toMap), left)
        val json = Json.parse(body).as[JsObject]
        (json \ "error" \ "code").asOpt[String].foreach(code => throw MwException(code, code))
        json
      }
      def present(p: JsObject) = !p.keys.contains("missing") && !p.keys.contains("invalid")
      val categoryTitle = SourceName.category(title)
      val pages = (query("titles" -> s"$title|$categoryTitle", "prop" -> "categoryinfo", "redirects" -> "") \ "query" \ "pages")
        .asOpt[Seq[JsObject]].getOrElse(Nil)
      val (categories, others) = pages.partition(p => (p \ "ns").asOpt[Int].contains(Namespace.CATEGORY))
      val categoryFiles = categories.flatMap(p => (p \ "categoryinfo" \ "files").asOpt[Long]).headOption.getOrElse(0L)
      // a category with members but no page of its own is a category all the same
      val category = categories.exists(p => present(p) || (p \ "categoryinfo" \ "size").asOpt[Long].exists(_ > 0))
      val page = others.exists(present)
      if (!(page && category) || !countPageFiles) NameProbe(page, category, categoryFiles)
      else {
        val shown = query("titles" -> title, "prop" -> "images", "redirects" -> "",
          "imlimit" -> NameProbe.PageFilesCap.toString)
        val files = (shown \ "query" \ "pages").asOpt[Seq[JsObject]].getOrElse(Nil)
          .flatMap(p => (p \ "images").asOpt[Seq[JsObject]].getOrElse(Nil)).size
        NameProbe(page, category, categoryFiles, Some(files), pageFilesCapped = (shown \ "continue").isDefined)
      }
    }
  }

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
