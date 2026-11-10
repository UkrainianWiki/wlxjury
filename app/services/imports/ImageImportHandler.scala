package services.imports

import db.scalikejdbc.{CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc, RegionCountCache, RoundImageCounts, RoundWrites}
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
  * @param monumentIdTemplate the contest's: the files' monument ids come from their text with it
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
  *                       categories ([[ContestCountries]]): decided by the first step, by
  *                       whether any of the page's files is in one
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
  * @param textless       files fetched without their page text (none, normally): a stored
  *                       one keeps its description and author
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
    notInCountries: Int = 0,
    textless: Set[Long] = Set.empty
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
  *     template, their text; of a page, and of any source for a contest of many countries
  *     (the international one), the files' categories, for their countries
  *     ([[ContestCountries]]): a page's files are only those in a country category, if any
  *     of them is in one (a WLx contest's winners page shows icons and logos too);
  *  2. written in one transaction with the next cursor, by the rules the whole-contest
  *     import had, per page: a file not stored is inserted and put in the contest's
  *     category; a stored file of another contest is put in the category; a stored file is
  *     updated if it changed ([[ImageService.changedImages]]), with its vote rows'
  *     monument id; a file is put in its country category (never taken out of one). Mode
  *     `monument_ids` sets the stored files' monument ids from their text, where they differ.
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
    // every contest's: the files' authors, for conflicts of interest (db.scalikejdbc.CoiMembers)
    val text = true
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
        textless = textless(found),
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
      val ofPage = !monumentIds && ImportSource(p.source).isInstanceOf[ImportSource.PageFiles]
      // the files' countries: of a page, and of any source of a contest of many countries
      val countryOf =
        if (ofPage || (!monumentIds && ContestCountries.ofManyCountries(contest))) countries(contest, pages)
        else Map.empty[Long, String]
      // a page's files: only those of the contest's countries if any of its files is in one
      // (not by whether the countries' root category has files of its own: it may hold only
      // their categories); decided by the first step and kept, so a later step of icons only
      // changes nothing
      val byCountry = Option.when(ofPage) {
        st.byCountry.getOrElse(countryOf.nonEmpty || countriesAhead(contest, ImportSource(p.source), listed.next,
          idsPerRequest))
      }
      val kept = if (byCountry.contains(true)) pages.filter(_.id.exists(countryOf.contains)) else pages
      ImportPage(
        countries = countryOf.toSeq,
        byCountry = byCountry,
        notInCountries = pages.size - kept.size,
        listed = listed.pageIds.size,
        images = if (monumentIds) Nil else images(contest, p, kept),
        textless = if (monumentIds) Set.empty else textless(kept),
        monumentIds = if (monumentIds) monumentIdsOf(p, pages) else Nil,
        missingTitles = Nil,
        nextCursor = listed.next.getOrElse(st.cursor),
        nextIndex = st.index,
        last = listed.next.isEmpty,
        total = st.total.orElse(if (st.seen == 0) source.categoryFileCount(p.source) else None)
      )
    }
  }

  /** The files' contest country categories, by page id ([[ContestCountries.choose]]): of a
    * file in several that don't overlap (`the United Kingdom`, `England`), by their parent
    * categories, asked for those only.
    */
  private def countries(contest: ContestJury, pages: Seq[Page]): Map[Long, String] = {
    val ids = pages.flatMap(_.id)
    val candidates = ids.grouped(source.maxIdsPerRequest).flatMap(source.categories)
      .map { case (pageId, categories) => pageId -> ContestCountries.candidates(contest, categories) }
      .filter(_._2.nonEmpty)
      .toMap
    val several = candidates.values.filter(_.size > 1).flatMap(_.map(_._2)).toSeq.distinct
    val parents = several.grouped(source.maxIdsPerRequest).flatMap(source.categoriesByTitle).toMap
    candidates.flatMap { case (pageId, cs) => ContestCountries.choose(cs, parents).map(pageId -> _) }
  }

  /** Whether a file of the source after `cursor` is in one of the contest's country
    * categories: asked by a page's first step when its own files are in none (the icons and
    * logos may come first), up to the first such file, at most [[CountryProbeRequests]]
    * listings of `perRequest` files (beyond them: none, so the page's files are all imported).
    */
  private def countriesAhead(contest: ContestJury, src: ImportSource, cursor: Option[JsObject],
      perRequest: Int): Boolean = {
    @scala.annotation.tailrec
    def from(cursor: Option[JsObject], requests: Int): Boolean = cursor match {
      case Some(c) if requests < CountryProbeRequests =>
        val listed = source.listFiles(src, c, perRequest)
        val found = listed.pageIds.grouped(perRequest).flatMap(source.categories)
          .exists { case (_, categories) => ContestCountries.candidates(contest, categories).nonEmpty }
        found || from(listed.next, requests + 1)
      case _ => false
    }
    from(cursor, 0)
  }

  private def images(contest: ContestJury, p: ImportParams, pages: Seq[Page]): Seq[Image] =
    ImageText.imagesWithAuthors(pages, contest, p.monumentIdTemplate, source)

  private def textless(pages: Seq[Page]): Set[Long] = pages.filter(_.text.isEmpty).flatMap(_.id).toSet

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
      else
        writeImages(p.categoryId.getOrElse(throw new IllegalStateException("no category")), page, st, ctx,
          withMonumentIds = p.monumentIdTemplate.isDefined)
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

  /** @param withMonumentIds whether the contest has a monument id template: without it a
    *                        fetched file has no monument id, so a stored file keeps the one
    *                        another contest's import set (its rounds' regions read them). A
    *                        file fetched without its text ([[ImportPage.textless]]) keeps its
    *                        stored description and author too.
    */
  private def writeImages(categoryId: Long, page: ImportPage, st: ImportState, ctx: StepContext,
      withMonumentIds: Boolean)(
      implicit session: DBSession
  ): ImportState = {
    val stored = ImageJdbc
      .findWithCategoryLink(page.images.map(_.pageId), categoryId)
      .map { case (image, linked) => image.pageId -> (image, linked) }
      .toMap
    val fetched = page.images.map(i => stored.get(i.pageId).fold(i) { case (s, _) =>
      ImageService.withStoredText(i, s, text = !page.textless.contains(i.pageId), withMonumentIds)
    })
    val fresh = fetched.filterNot(i => stored.contains(i.pageId))
    val unlinked = fetched.filterNot(i => stored.get(i.pageId).exists(_._2))
    // a stored file of another contest too: its author may be from before authors were resolved
    val changed = ImageService.changedImages(fetched, stored.values.map(_._1).toSeq)
    // A structural write of every round the restored and the changed files are in (their
    // votes move back, their monument id copies change): those rounds for update first
    // (RoundWrites; only these files, so a page with nothing to restore or change locks none),
    // then the files in page id order (h2, also when nothing is restored), then the writes
    val toRestore = ImageJdbc.deletedOrArchived(stored.keys.toSeq)
    val changedIds = changed.map(_.pageId)
    RoundWrites.lockForFiles((toRestore ++ changedIds).distinct)
    val restored = DeletedFiles.restore(toRestore, alsoLock = changedIds)
    RoundWrites.lockFiles(changedIds)
    if (restored.nonEmpty) ctx.afterCommit(RoundImageCounts.invalidate(restored: _*))
    // the category's region counts: files join it, or a changed file's monument id may change
    if (unlinked.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateCategory(categoryId))
    if (changed.nonEmpty) ctx.afterCommit(RegionCountCache.invalidateMonumentIds())
    ImageJdbc.insertIgnore(fresh)
    CategoryLinkJdbc.linkIgnore(categoryId, unlinked.map(_.pageId))
    // the countries of the files written or stored only: a file Commons sent without its
    // image info is not written, and its link would have no `images` row (refused by the key)
    val written = page.images.map(_.pageId).toSet
    val storedOthers = ImageJdbc.existingIds(page.countries.map(_._1).filterNot(written).toSet).toSet
    val countriesJoined =
      CategoryLinkJdbc.addCountries(page.countries.filter { case (id, _) => written(id) || storedOthers(id) })
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
    RoundWrites.lockForFiles(changed.map(_._1)) // their rounds first (RoundWrites), then each file
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

  /** Listings a page's first step reads ahead for a file in a country category. */
  val CountryProbeRequests = 20

  /** The job's params without the pasted titles: read for every status poll and step. */
  def paramsOf(job: Job): ImportParams = (job.params - "titles").as[ImportParams]

  /** At most `n` of the pasted titles, from the `from`th. */
  def titlesOf(job: Job, from: Int, n: Int): Seq[String] =
    (job.params \ "titles").asOpt[JsArray].fold(Seq.empty[String])(_.value.slice(from, from + n).map(_.as[String]).toSeq)

  def stateOf(job: Job): ImportState = job.state.validate[ImportState].getOrElse(ImportState())
}
