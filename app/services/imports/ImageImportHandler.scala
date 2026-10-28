package services.imports

import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc, RegionCountCache, RoundImageCounts}
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.dto.Page
import play.api.libs.json._
import play.api.{Configuration, Logging}
import scalikejdbc.DBSession
import services.ImageService
import services.jobs._

import javax.inject.{Inject, Singleton}

/** What a [[ImageImportHandler]] job imports (`job.params`, fixed at creation).
  *
  * @param categoryId         the contest's category, which the files are put in (mode import)
  * @param source             a category, a template or a page ([[ImportSource]])
  * @param titles             a pasted list of files, imported instead of the source's
  *                           (not read back by [[ImageImportHandler.paramsOf]]: it may be long)
  * @param monumentIdTemplate the contest's: with it, the files' text is fetched too
  * @param titleCount         the size of `titles` (set by [[ImageImportHandler.newJob]])
  */
final case class ImportParams(
    contestId: Long,
    mode: String,
    source: String,
    categoryId: Option[Long] = None,
    titles: Seq[String] = Nil,
    monumentIdTemplate: Option[String] = None,
    titleCount: Int = 0
)

object ImportParams {
  implicit val format: OFormat[ImportParams] = Json.using[Json.WithDefaultValues].format[ImportParams]
}

/** The resume cursor of an import (`job.state`).
  *
  * @param cursor the API's continue map for the source's next page of files (`{}`: the first)
  * @param index  the next title of a pasted list
  * @param seen   files listed so far (the progress)
  * @param linked stored files (another contest's) put in this contest's category
  * @param missing        titles of the pasted list that Commons doesn't have
  * @param missingTitles  the first [[ImageImportHandler.MaxMissingTitles]] of them
  * @param byCountry      whether a page's files are only those in the contest's country
  *                       categories ([[ContestCountries]]): decided by the first step
  * @param notInCountries a page's files left out as in none of them (icons, logos)
  */
final case class ImportState(
    cursor: JsObject = Json.obj(),
    index: Int = 0,
    seen: Long = 0L,
    inserted: Long = 0L,
    linked: Long = 0L,
    updated: Long = 0L,
    monumentIdsChanged: Long = 0L,
    total: Option[Long] = None,
    missing: Long = 0L,
    missingTitles: Seq[String] = Nil,
    byCountry: Option[Boolean] = None,
    notInCountries: Long = 0L
)

object ImportState {
  implicit val format: OFormat[ImportState] = Json.using[Json.WithDefaultValues].format[ImportState]
}

/** What one fetch of an import got: the files of one page, and where the next page is.
  *
  * @param countries      the files' contest country categories ([[ContestCountries]]),
  *                       by page id
  * @param notInCountries files of a page source left out, as in none of those categories
  */
final case class ImportPage(
    listed: Int,
    images: Seq[Image],
    monumentIds: Seq[(Long, String)],
    missingTitles: Seq[String],
    nextCursor: JsObject,
    nextIndex: Int,
    last: Boolean,
    total: Option[Long],
    countries: Seq[(Long, String)] = Nil,
    byCountry: Option[Boolean] = None,
    notInCountries: Int = 0
)

/** Imports a contest's files from Commons in the background, as a [[Job]] of kind
  * `commons_image_import` whose subject is the contest: "Import files" (mode `import`)
  * and the files' half of "Update monuments" (mode `monument_ids`). Both modes share one
  * dedupe key per contest, as both write its files.
  *
  * Each step is one page of the source's files ([[pageSize]]: by default as many as one
  * request of their details takes, 500 when the Commons account has `apihighlimits`):
  *  1. fetched on the scheduler's fetch lane, outside any transaction: the page of the
  *     source's files ([[CommonsSource.listFiles]], the API's continue map as the cursor)
  *     or of the pasted titles, then the files' image info and, with a monument id
  *     template, their text; of a page, and of a pasted list for a contest of many
  *     countries (the international one), the files' categories, for their countries
  *     ([[ContestCountries]]): a page's files are only those in a country category, if the
  *     contest has them on Commons (a WLx contest's winners page shows icons and logos too);
  *  2. written in one transaction with the next cursor, by the rules the whole-contest
  *     import had, per page: a file not stored is inserted and put in the contest's
  *     category; a stored file of another contest is put in the category only; a file of
  *     the contest is updated if it changed ([[ImageService.changedImages]]), with its
  *     selection rows' monument id; a file is put in its country category (never taken
  *     out of one). Mode `monument_ids` sets the stored files' monument ids from their
  *     text, where they differ.
  *
  * Every write is idempotent (`INSERT IGNORE`, compare before update), so a page fetched
  * and written again after a crash or a Retry changes nothing. Held in memory: one page.
  */
@Singleton
class ImageImportHandler @Inject() (source: CommonsSource, config: Configuration)
    extends FetchingJobHandler[ImportPage]
    with Logging {

  import ImageImportHandler._

  val kind: String = Kind

  /** Files per step, `jobs.commonsImport.pageSize`; None: as many as one request of their
    * details takes ([[CommonsSource.maxIdsPerRequest]]: 500 with `apihighlimits`, else 50).
    */
  val pageSize: Option[Int] = config.getOptional[Int]("jobs.commonsImport.pageSize")
  require(pageSize.forall(_ > 0), s"jobs.commonsImport.pageSize must be positive, not ${pageSize.get}")

  /** A new import of the contest's files. */
  def newJob(params: ImportParams, createdBy: Option[Long]): NewJob = {
    val total = Option.when(params.titles.nonEmpty)(params.titles.size.toLong)
    NewJob(
      kind = Kind,
      contestId = Some(params.contestId),
      subjectType = Some(SubjectType),
      subjectId = Some(params.contestId),
      dedupeKey = Some(dedupeKey(params.contestId)),
      params = Json.toJsObject(params.copy(titleCount = params.titles.size)),
      state = Json.toJsObject(ImportState(total = total)),
      progress = Progress(Some(0L), total, Some("files")),
      createdBy = createdBy
    )
  }

  override def title(job: Job): String = {
    val p = paramsOf(job)
    if (p.mode == Mode.MonumentIds) s"Updating the monument ids of the files from ${p.source}"
    else if (p.titleCount > 0) s"Importing ${p.titleCount} listed files"
    else s"Importing files from ${p.source}"
  }

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj(
      "mode" -> paramsOf(job).mode,
      "inserted" -> st.inserted,
      "linked" -> st.linked,
      "updated" -> st.updated,
      "monumentIdsChanged" -> st.monumentIdsChanged,
      "missing" -> st.missing,
      "missingTitles" -> st.missingTitles,
      "notInCountries" -> st.notInCountries
    )
  }

  def cursorOf(job: Job): JsValue = {
    val st = stateOf(job)
    Json.obj("cursor" -> st.cursor, "index" -> st.index)
  }

  // ── fetch: outside any transaction ─────────────────────────────────────────

  def fetch(job: Job): ImportPage = {
    val p = paramsOf(job)
    val st = stateOf(job)
    val monumentIds = p.mode == Mode.MonumentIds
    val text = monumentIds || p.monumentIdTemplate.isDefined
    lazy val contest = ContestJuryJdbc
      .findById(p.contestId)
      .getOrElse(throw new NoSuchElementException(s"Contest ${p.contestId} not found"))
    // once per step: it may check the Commons session
    val idsPerRequest = source.maxIdsPerRequest
    val stepSize = pageSize.getOrElse(idsPerRequest)

    if (p.titleCount > 0 && !monumentIds) {
      val batch = titlesOf(job, st.index, math.min(stepSize, idsPerRequest))
      val pages = source.filesByTitles(batch, info = true, text = text)
      val found = pages.filter(_.id.isDefined)
      val next = st.index + batch.size
      ImportPage(
        listed = batch.size,
        images = images(contest, p, found),
        monumentIds = Nil,
        missingTitles = pages.filter(_.id.isEmpty).map(_.title),
        nextCursor = st.cursor,
        nextIndex = next,
        last = next >= p.titleCount,
        total = Some(p.titleCount.toLong),
        // a national contest's files are all of its country
        countries = if (ContestCountries.ofManyCountries(contest)) countries(contest, found).toSeq else Nil
      )
    } else {
      val listed = source.listFiles(ImportSource(p.source), st.cursor, stepSize)
      val pages = listed.pageIds.grouped(idsPerRequest).flatMap(source.files(_, info = !monumentIds, text = text)).toSeq
      // a page's files: those of the contest's countries, if it has country categories
      val byCountry = Option.when(!monumentIds && ImportSource(p.source).isInstanceOf[ImportSource.PageFiles]) {
        st.byCountry.getOrElse(source.categoryFileCount(ContestCountries.root(contest)).exists(_ > 0))
      }
      val countryOf = if (byCountry.contains(true)) countries(contest, pages) else Map.empty[Long, String]
      val kept = if (byCountry.contains(true)) pages.filter(_.id.exists(countryOf.contains)) else pages
      ImportPage(
        countries = countryOf.toSeq,
        byCountry = byCountry,
        notInCountries = pages.size - kept.size,
        listed = listed.pageIds.size,
        images = if (monumentIds) Nil else images(contest, p, kept),
        monumentIds = if (monumentIds) monumentIdsOf(p, pages) else Nil,
        missingTitles = Nil,
        nextCursor = listed.next.getOrElse(st.cursor),
        nextIndex = st.index,
        last = listed.next.isEmpty,
        total = st.total.orElse(if (st.seen == 0) source.categoryFileCount(p.source) else None)
      )
    }
  }

  /** The files' contest country categories, by page id ([[ContestCountries]]). */
  private def countries(contest: ContestJury, pages: Seq[Page]): Map[Long, String] = {
    val ids = pages.flatMap(_.id)
    if (ids.isEmpty) Map.empty
    else
      ids.grouped(source.maxIdsPerRequest).flatMap(source.categories).flatMap { case (pageId, categories) =>
        ContestCountries.category(contest, categories).map(pageId -> _)
      }.toMap
  }

  private def images(contest: ContestJury, p: ImportParams, pages: Seq[Page]): Seq[Image] =
    ImageText.images(pages, contest, p.monumentIdTemplate)

  private def monumentIdsOf(p: ImportParams, pages: Seq[Page]): Seq[(Long, String)] = {
    val template = p.monumentIdTemplate.getOrElse(throw new IllegalStateException("no monument id template"))
    for {
      page <- pages.sortBy(_.id)
      pageId <- page.id
      text <- page.text
      monumentId <- ImageText.validMonumentId(text, template)
    } yield pageId -> monumentId
  }

  // ── write: in the step's transaction ───────────────────────────────────────

  def write(job: Job, page: ImportPage, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val p = paramsOf(job)
    val st = stateOf(job)
    val written =
      if (p.mode == Mode.MonumentIds) writeMonumentIds(page, st, ctx)
      else writeImages(p.categoryId.getOrElse(throw new IllegalStateException("no category")), page, st, ctx)
    val next = written.copy(
      cursor = page.nextCursor,
      index = page.nextIndex,
      seen = st.seen + page.listed,
      total = page.total.orElse(st.total),
      missing = st.missing + page.missingTitles.size,
      missingTitles = (st.missingTitles ++ page.missingTitles).take(MaxMissingTitles),
      byCountry = page.byCountry.orElse(st.byCountry),
      notInCountries = st.notInCountries + page.notInCountries
    )
    val progress = Progress(Some(next.seen), next.total, Some("files"))
    if (!page.last) StepResult.Continue(Json.toJsObject(next), progress)
    else {
      logger.info(
        s"Job ${job.id}, contest ${p.contestId}: ${title(job)}: ${next.seen} files, ${next.inserted} inserted, " +
          s"${next.linked} linked, ${next.updated} updated, ${next.monumentIdsChanged} monument ids changed" +
          (if (next.notInCountries > 0) s", ${next.notInCountries} left out as in no country category" else "")
      )
      StepResult.Done(Json.toJsObject(next), progress)
    }
  }

  private def writeImages(categoryId: Long, page: ImportPage, st: ImportState, ctx: StepContext)(implicit
      session: DBSession
  ): ImportState = {
    val stored = ImageJdbc
      .findWithCategoryLink(page.images.map(_.pageId), categoryId)
      .map { case (image, linked) => image.pageId -> (image, linked) }
      .toMap
    val fresh = page.images.filterNot(i => stored.contains(i.pageId))
    val unlinked = page.images.filterNot(i => stored.get(i.pageId).exists(_._2))
    val changed = ImageService.changedImages(page.images, stored.values.collect { case (i, true) => i }.toSeq)
    // Locks the restored files with those updated below, in page id order
    val restored = DeletedFiles.restore(stored.keys.toSeq, alsoLock = changed.map(_.pageId))
    if (restored.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(restored: _*))
    // the category's region counts: files join it, or a changed file's monument id may change
    if (unlinked.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateCategory(categoryId))
    if (changed.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateMonumentIds())
    ImageJdbc.insertIgnore(fresh)
    CategoryLinkJdbc.linkIgnore(categoryId, unlinked.map(_.pageId))
    val countriesJoined = CategoryLinkJdbc.addCountries(page.countries)
    if (countriesJoined.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateCategory(countriesJoined: _*))
    changed.foreach { image =>
      ImageJdbc.updateIn(image)
      stored.get(image.pageId).map(_._1.title).filter(_ != image.title).foreach(ImageJdbc.recordRename(_, image.pageId))
    }
    st.copy(
      inserted = st.inserted + fresh.size,
      linked = st.linked + unlinked.size - fresh.size,
      updated = st.updated + changed.size
    )
  }

  private def writeMonumentIds(page: ImportPage, st: ImportState, ctx: StepContext)(implicit
      session: DBSession
  ): ImportState = {
    val current = ImageJdbc.monumentIds(page.monumentIds.map(_._1))
    val changed = page.monumentIds.filter { case (pageId, id) => current.get(pageId).exists(!_.contains(id)) }
    changed.foreach { case (pageId, id) => ImageJdbc.updateMonumentIdIn(pageId, id) }
    if (changed.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateMonumentIds())
    st.copy(monumentIdsChanged = st.monumentIdsChanged + changed.size)
  }
}

object ImageImportHandler {

  val Kind = "commons_image_import"
  val SubjectType = "contest"

  object Mode {
    val Import = "import"
    val MonumentIds = "monument_ids"
  }

  /** One active import per contest, of either mode. */
  def dedupeKey(contestId: Long): String = s"$Kind:$SubjectType:$contestId"

  /** Files per step without `apihighlimits` (and the specs' default). */
  val DefaultPageSize: Int = CommonsLogin.Limit
  val MaxMissingTitles = 100

  /** The job's params without the pasted titles: read for every status poll and step. */
  def paramsOf(job: Job): ImportParams = (job.params - "titles").as[ImportParams]

  /** At most `n` of the pasted titles, from the `from`th. */
  def titlesOf(job: Job, from: Int, n: Int): Seq[String] =
    (job.params \ "titles").asOpt[JsArray].fold(Seq.empty[String])(_.value.slice(from, from + n).map(_.as[String]).toSeq)

  def stateOf(job: Job): ImportState = job.state.validate[ImportState].getOrElse(ImportState())
}
