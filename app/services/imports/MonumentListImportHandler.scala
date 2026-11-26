package services.imports

import db.scalikejdbc.MonumentJdbc
import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.{Contest, ContestType, Country, Monument, SpecialNomination}
import play.api.libs.json._
import play.api.{Configuration, Logging}
import scalikejdbc.DBSession
import services.jobs._
import services.regions.ContestKind

import javax.inject.{Inject, Singleton}

/** Monument lists to read: a list page, or a template whose embedding pages are lists.
  *
  * @param listTemplate the row template of a special nomination's lists (None: the
  *                     contest's own)
  * @param idPrefix     the id prefix of the monuments of its own, for the lists of the
  *                     nomination that prefix belongs to (`92` for "Болгарська спадщина":
  *                     `92-232-4801`): these lists are their home, which their rows are
  *                     written from wherever they were stored before. None for the
  *                     contest's lists and for jobs created before it existed
  */
final case class ListSource(page: String, listTemplate: Option[String] = None, idPrefix: Option[String] = None) {
  def isTemplate: Boolean = page.startsWith("Template:")

  /** Whether `id` is one of the monuments this source's nomination gives ids of its own. */
  def isHomeOf(id: String): Boolean = idPrefix.exists(prefix => id.startsWith(prefix + "-"))
}

object ListSource {
  implicit val format: OFormat[ListSource] = Json.using[Json.WithDefaultValues].format[ListSource]
}

/** What a [[MonumentListImportHandler]] job reads (`job.params`).
  *
  * @param lists   the scalawiki contest whose lists these are: `wlm_ua` or `wle_ua`
  * @param sources the contest's own lists first, then the special nominations'
  */
final case class MonumentImportParams(contestId: Long, lists: String, year: Int, sources: Seq[ListSource]) {
  def contest: Contest = MonumentListImportHandler.listContest(lists, year)
}

object MonumentImportParams {
  implicit val format: OFormat[MonumentImportParams] = Json.format[MonumentImportParams]
}

/** The resume cursor of a monument list import (`job.state`).
  *
  * @param source the next source (index into the params' sources)
  * @param cursor the API's continue map for the source's next list pages (a template's)
  */
final case class MonumentImportState(
    source: Int = 0,
    cursor: JsObject = Json.obj(),
    pages: Long = 0L,
    monuments: Long = 0L
)

object MonumentImportState {
  implicit val format: OFormat[MonumentImportState] = Json.using[Json.WithDefaultValues].format[MonumentImportState]
}

/** What one fetch got: the monuments of a few list pages, and where the next ones are. */
final case class MonumentPage(pages: Int, monuments: Seq[Monument], nextSource: Int, nextCursor: JsObject)

/** Updates the monuments of a contest's lists in the background, as a [[Job]] of kind
  * `monument_list_import`: the lists' half of "Update monuments" (Ukrainian contests with
  * a monument id template). Its subject is the contest, but there is one active job per
  * list template on its wiki, as contests of different years share the `monument` table.
  *
  * Each step is a few list pages (`jobs.monumentImport.pageSize`): fetched on the
  * scheduler's fetch lane ([[MonumentListSource]]: the API's continue map of a template's
  * embedding pages as the cursor), then their monuments with valid ids upserted in one
  * transaction. The contest's lists come first and the special nominations' own lists
  * last; a nomination's monument is written only if it isn't stored or was stored from
  * the same list page, so a monument on both keeps the contest list's data, also when
  * the job stops halfway. The exception is a monument with an id of a nomination's own
  * (its prefix, [[ListSource.idPrefix]]: `92-232-4801`) on that nomination's lists, its
  * home: written from there whatever page it was stored from, so a row moved from one
  * nomination's list to its own (production's `92-` rows, on the German heritage lists
  * until 2025-10-29) gets its new page and data. The city-based nominations add nothing:
  * they select monuments from the contest's lists.
  *
  * Held in memory: the monuments of one step's list pages. Monuments removed from the
  * lists stay in the table.
  */
@Singleton
class MonumentListImportHandler @Inject() (lists: MonumentListSource, config: Configuration)
    extends FetchingJobHandler[MonumentPage]
    with Logging {

  import MonumentListImportHandler._

  val kind: String = Kind

  /** List pages per step: they are large (hundreds of monuments each). */
  val pageSize: Int = config.getOptional[Int]("jobs.monumentImport.pageSize").getOrElse(DefaultPageSize)

  /** The import of the contest's monument lists, if it has lists (a Ukrainian WLM or WLE
    * contest).
    */
  def newJob(contest: ContestJury, createdBy: Option[Long]): Option[NewJob] =
    listsOf(contest).map { lists =>
      val wiki = listContest(lists, contest.year)
      val nominations =
        if (wiki.contestType != ContestType.WLM) Nil
        else {
          val withLists = SpecialNomination.nominations.filter(n => n.listTemplate.nonEmpty && n.pages.nonEmpty)
          // a prefix's home: the nomination it belongs to (of several, the latest year's)
          val homes = SpecialNomination.byIdPrefix(withLists)
          withLists
            .flatMap { n =>
              val prefix = n.idPrefix.filter(homes.get(_).contains(n))
              n.pages.map(ListSource(_, n.listTemplate, prefix))
            }
            .distinct
        }
      val ownLists = wiki.listTemplate.map(t => ListSource(templateTitle(t))).toSeq
      val params = MonumentImportParams(contest.getId, lists, contest.year, ownLists ++ nominations)
      NewJob(
        kind = Kind,
        contestId = contest.id,
        subjectType = Some(SubjectType),
        subjectId = contest.id,
        dedupeKey = Some(dedupeKey(wiki)),
        params = Json.toJsObject(params),
        state = Json.toJsObject(MonumentImportState()),
        progress = Progress(Some(0L), None, Some("list pages")),
        createdBy = createdBy
      )
    }

  override def title(job: Job): String = s"Updating the monument lists of ${paramsOf(job).contest.name}"

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("monuments" -> st.monuments, "source" -> st.source, "sources" -> paramsOf(job).sources.size)
  }

  def cursorOf(job: Job): JsValue = {
    val st = stateOf(job)
    Json.obj("source" -> st.source, "cursor" -> st.cursor)
  }

  def fetch(job: Job): MonumentPage = {
    val p = paramsOf(job)
    val st = stateOf(job)
    p.sources.lift(st.source) match {
      case None => MonumentPage(0, Nil, st.source, st.cursor)
      case Some(source) if source.isTemplate =>
        val (titles, next) = lists.listPages(p.contest, source.page, st.cursor, pageSize)
        val monuments = lists.monuments(p.contest, titles, source.listTemplate)
        next match {
          case Some(cursor) => MonumentPage(titles.size, monuments, st.source, cursor)
          case None         => MonumentPage(titles.size, monuments, st.source + 1, Json.obj())
        }
      case Some(source) =>
        MonumentPage(1, lists.monuments(p.contest, Seq(source.page), source.listTemplate), st.source + 1, Json.obj())
    }
  }

  def write(job: Job, page: MonumentPage, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val p = paramsOf(job)
    val st = stateOf(job)
    val valid = page.monuments.filter(_.id.matches(ValidId))
    val source = p.sources.lift(st.source)
    val nomination = source.exists(_.listTemplate.isDefined)
    // a nomination's own ids on its own lists: written whatever list they were stored from
    val (home, others) = valid.partition(m => source.exists(_.isHomeOf(m.id)))
    val written =
      if (!nomination || others.isEmpty) valid
      else {
        // another list has it: the contest's (written before the nominations') or another nomination's
        val storedOn = MonumentJdbc.pagesOf(others.map(_.id))
        home ++ others.filter(m => storedOn.get(m.id).forall(_ == m.page))
      }
    if (written.nonEmpty) MonumentJdbc.batchInsert(written)
    val next = st.copy(
      source = page.nextSource,
      cursor = page.nextCursor,
      pages = st.pages + page.pages,
      monuments = st.monuments + written.size
    )
    val progress = Progress(Some(next.pages), None, Some("list pages"))
    if (next.source < p.sources.size) StepResult.Continue(Json.toJsObject(next), progress)
    else {
      logger.info(s"monument_lists_done job=${job.id} contestId=${p.contestId} lists=${controllers.Secured.logValue(p.lists)} " +
        s"pages=${next.pages} monuments=${next.monuments}")
      StepResult.Done(Json.toJsObject(next), progress)
    }
  }
}

object MonumentListImportHandler {

  val Kind = "monument_list_import"
  val SubjectType = "contest"

  /** One active import per list template on its wiki. */
  def dedupeKey(contest: Contest): String =
    s"$Kind:${contest.listsHost.getOrElse("")}:${contest.listTemplate.getOrElse("")}"

  val DefaultPageSize = 20

  /** The ids the contests use: region, district, number. */
  val ValidId = """\d{2}-\d{3}-\d{4}"""

  /** The Ukrainian lists of the resolved campaign, with its effective monument id template. */
  def listsOf(contest: ContestJury): Option[String] =
    ContestKind.of(contest)
      .filter(_.country.code == Country.Ukraine.code)
      .filter(_ => ContestKind.monumentIdTemplate(contest).isDefined)
      .flatMap(_.contestType match {
        case ContestType.WLM => Some("wlm_ua")
        case ContestType.WLE => Some("wle_ua")
        case _               => None
      })

  def listContest(lists: String, year: Int): Contest = lists match {
    case "wlm_ua" => Contest.WLMUkraine(year)
    case "wle_ua" => Contest.WLEUkraine(year)
    case other    => throw new IllegalArgumentException(s"unknown monument lists $other")
  }

  def templateTitle(template: String): String =
    if (template.startsWith("Template:")) template else "Template:" + template

  def paramsOf(job: Job): MonumentImportParams = job.params.as[MonumentImportParams]

  def stateOf(job: Job): MonumentImportState =
    job.state.validate[MonumentImportState].getOrElse(MonumentImportState())
}
