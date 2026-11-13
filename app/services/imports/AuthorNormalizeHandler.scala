package services.imports

import db.scalikejdbc.User
import org.intracer.wmua.Image
import play.api.Logging
import play.api.libs.json._
import scalikejdbc._
import services.jobs._

import javax.inject.{Inject, Singleton}

/** The resume cursor and the figures of an `author_normalize` job (`job.state`).
  *
  * @param cursor      the last page id a committed step covered
  * @param total       files with an author with markup when the job started
  * @param read        of those, read so far
  * @param changed     authors written: an account, or none ([[unresolved]])
  * @param fromUploader of [[changed]], the file's original uploader: a credit template that
  *                    links to nobody, or markup that names nobody readable
  * @param unresolved  of [[changed]], none (NULL): an unknown author, or the uploader looked
  *                    up but not found (a file deleted on Commons) or a bot
  * @param skipped     left as they were: already as an import stores them, or changed by an
  *                    import between the step's read and its write
  * @param templates   the credit templates that link to nobody (their files got the uploader)
  */
final case class AuthorNormalizeState(
    cursor: Long = 0L,
    total: Option[Long] = None,
    read: Long = 0L,
    changed: Long = 0L,
    fromUploader: Long = 0L,
    unresolved: Long = 0L,
    skipped: Long = 0L,
    templates: Seq[String] = Nil
)

object AuthorNormalizeState {
  implicit val format: OFormat[AuthorNormalizeState] = Json.using[Json.WithDefaultValues].format[AuthorNormalizeState]
}

/** One file's author as stored, and as the job stores it. */
final case class AuthorFix(pageId: Long, stored: String, author: Option[String], how: ImageText.Resolved)

/** What one fetch of an `author_normalize` job got: the files with an author with markup
  * after the cursor, up to the next cursor, each with the author an import would store.
  */
final case class AuthorChunk(fixes: Seq[AuthorFix], nextCursor: Long, last: Boolean, total: Option[Long])

/** Stores the accounts of the authors that files imported before authors were resolved still
  * hold as wikitext (`[[User:X|Y]]`, `{{SergiyBobokPhotos}}`): a root-only maintenance job of
  * kind `author_normalize`, of no contest (every stored file), that can be run again: a run
  * after one that finished finds nothing to change.
  *
  * Each step is a window of [[scanRows]] files by page id, of which at most [[chunkRows]]
  * with markup ([[StoredAuthors.markupSql]]):
  *  1. fetched on the scheduler's fetch lane, outside any transaction: the files' stored
  *     authors, read by page id, then resolved as an import resolves the author its page
  *     text names ([[StoredAuthors.author]], [[ImageText.resolvedAuthors]]): the user a link
  *     names; a credit template's user (one Commons request for the window's templates);
  *     the original uploader for a template that links to nobody (a request per
  *     [[CommonsSource.AuthorBatch]] such files), none for a file deleted on Commons, a bot's
  *     upload or an unknown author;
  *  2. written in one transaction: the files' rows locked by page id, and each author that
  *     is still what the fetch read written by page id (`images.author` is
  *     `utf8mb4_general_ci`: a write by value would also change `[[User:ABC|x]]` for
  *     `[[User:AbC|x]]`); one an import changed meanwhile is left as it is.
  *
  * Nothing caches authors: conflicts of interest ([[db.scalikejdbc.CoiMembers]]) read them
  * when asked, and match these files from the next read on.
  */
@Singleton
class AuthorNormalizeHandler(source: CommonsSource, scanRows: Int, chunkRows: Int)
    extends FetchingJobHandler[AuthorChunk]
    with Logging {

  import AuthorNormalizeHandler._

  @Inject() def this(source: CommonsSource) = this(source, AuthorNormalizeHandler.ScanRows, AuthorNormalizeHandler.ChunkRows)

  require(scanRows > 0 && chunkRows > 0, "scanRows and chunkRows must be positive")

  val kind: String = Kind

  override def title(job: Job): String = "Normalizing the stored authors"

  override def details(job: Job): JsObject = Json.toJsObject(stateOf(job))

  def cursorOf(job: Job): JsValue = Json.obj("cursor" -> stateOf(job).cursor)

  // ── fetch: outside any transaction ─────────────────────────────────────────

  def fetch(job: Job): AuthorChunk = {
    val st = stateOf(job)
    val (rows, windowEnd, total) = DB.readOnly { implicit session =>
      val total = st.total.orElse(Some(
        sql"SELECT COUNT(*) FROM images WHERE ${StoredAuthors.markupSql(sqls"author")}".map(_.long(1)).single().getOrElse(0L)))
      // the window: the next scanRows files by the primary key, None if fewer are left
      val windowEnd = sql"""SELECT page_id FROM images WHERE page_id > ${st.cursor}
                            ORDER BY page_id LIMIT 1 OFFSET ${scanRows - 1}"""
        .map(_.long(1)).single()
      val upTo = windowEnd.fold(sqls"")(end => sqls"AND page_id <= $end")
      val rows = sql"""SELECT page_id, author FROM images
                       WHERE page_id > ${st.cursor} $upTo AND ${StoredAuthors.markupSql(sqls"author")}
                       ORDER BY page_id LIMIT $chunkRows"""
        .map(rs => rs.long(1) -> rs.string(2)).list()
      (rows, windowEnd, total)
    }
    val parsed = rows.map { case (pageId, stored) =>
      val ref = StoredAuthors.author(stored)
      val author = ref match {
        case ImageText.Author.Named(name) => ImageText.storedAuthor(name)
        case _                            => None
      }
      Image(pageId, "", author = author) -> Some(ref)
    }
    val storedById = rows.toMap
    val fixes = ImageText.resolvedAuthors(parsed, source).map { case (image, how) =>
      AuthorFix(image.pageId, storedById(image.pageId), image.author, how)
    }
    val full = rows.size == chunkRows
    AuthorChunk(
      fixes,
      nextCursor = if (full) rows.last._1 else windowEnd.getOrElse(rows.lastOption.fold(st.cursor)(_._1)),
      last = !full && windowEnd.isEmpty,
      total = total
    )
  }

  // ── write: in the step's transaction ───────────────────────────────────────

  def write(job: Job, chunk: AuthorChunk, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val toWrite = chunk.fixes.filterNot(f => f.author.contains(f.stored)).sortBy(_.pageId)
    // the rows locked in page id order, their authors compared exactly (not in the column's
    // collation): one an import wrote since the fetch is left as it is
    val current =
      if (toWrite.isEmpty) Map.empty[Long, Option[String]]
      else
        sql"SELECT page_id, author FROM images WHERE page_id IN (${toWrite.map(_.pageId)}) ORDER BY page_id FOR UPDATE"
          .map(rs => rs.long(1) -> rs.stringOpt(2)).list().toMap
    val written = toWrite.filter(f => current.get(f.pageId).flatten.contains(f.stored))
    if (written.nonEmpty)
      sql"UPDATE images SET author = ? WHERE page_id = ?".batch(written.map(f => Seq(f.author, f.pageId)): _*).apply()
    val unresolvedTemplates = written.collect {
      case AuthorFix(_, _, _, ImageText.Resolved.Uploader(Some(template))) => template
    }
    val next = st.copy(
      cursor = chunk.nextCursor,
      total = st.total.orElse(chunk.total),
      read = st.read + chunk.fixes.size,
      changed = st.changed + written.size,
      fromUploader = st.fromUploader + written.count(f => f.author.isDefined && f.how.isInstanceOf[ImageText.Resolved.Uploader]),
      unresolved = st.unresolved + written.count(_.author.isEmpty),
      skipped = st.skipped + (chunk.fixes.size - written.size),
      templates = (st.templates ++ unresolvedTemplates).distinct.take(MaxTemplates)
    )
    if (chunk.last) {
      logger.info(s"author_normalize job ${job.id}: ${next.read} authors with markup read, ${next.changed} changed " +
        s"(${next.fromUploader} to the uploader, ${next.unresolved} to none), ${next.skipped} left as they were" +
        (if (next.templates.nonEmpty) s"; credit templates that link to nobody: ${next.templates.mkString(", ")}" else ""))
      StepResult.Done(Json.toJsObject(next), progress(next))
    } else StepResult.Continue(Json.toJsObject(next), progress(next))
  }

  private def progress(st: AuthorNormalizeState) = Progress(done = Some(st.read), total = st.total, unit = Some("files"))
}

object AuthorNormalizeHandler {

  val Kind = "author_normalize"

  /** One run at a time. */
  val DedupeKey: String = Kind

  /** Files per step's window by page id: a primary key range read. */
  val ScanRows = 20000

  /** Files with markup per step, at most: written in one transaction. */
  val ChunkRows = 1000

  val MaxTemplates = 50

  def stateOf(job: Job): AuthorNormalizeState =
    job.state.validate[AuthorNormalizeState].getOrElse(AuthorNormalizeState())

  /** A run over every stored file: no contest (root's). */
  def newJob(by: User): NewJob =
    NewJob(kind = Kind, dedupeKey = Some(DedupeKey), state = Json.toJsObject(AuthorNormalizeState()),
      progress = Progress(done = Some(0L), unit = Some("files")), createdBy = by.id)
}
