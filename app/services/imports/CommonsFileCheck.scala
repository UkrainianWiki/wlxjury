package services.imports

import db.scalikejdbc.{ImageJdbc, RoundImageCounts}
import org.intracer.wmua.Image
import org.scalawiki.dto.Page
import play.api.Logging
import scalikejdbc.DB

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Success, Try}

/** What Commons says of a stored file whose thumbnail failed to load. */
sealed trait FileCheck

object FileCheck {

  /** Commons has the file under another title (it was renamed) or with other attributes
    * (a new version): `image` is the file as now stored.
    */
  final case class Changed(image: Image) extends FileCheck

  /** Commons no longer has the file: it is marked deleted and its votes moved aside. */
  case object Deleted extends FileCheck

  /** Commons has the file as stored: the thumbnail failed for another reason. */
  case object Unchanged extends FileCheck

  /** Commons couldn't be asked, or gave an answer that says neither. */
  case object Unknown extends FileCheck
}

/** Asks Commons, by page id, about stored files whose thumbnails failed to load
  * ([[controllers.ImageProxyController]]): the files a gallery can no longer show.
  *  - A renamed file keeps its page id, and comes back under its new title: its title, url,
  *    size and type are stored ([[ImageJdbc.updateFileInfo]]), and the old title is
  *    remembered ([[renamedFrom]]), for the pages that still link the old thumbnails.
  *  - A deleted file doesn't come back: the API answers `{"pageid": N, "missing": ""}`,
  *    without a title, which scalawiki's parser drops. Only an id missing from an answer
  *    that came counts; a failed request changes nothing. The file is marked deleted
  *    ([[ImageJdbc.markDeleted]]): its votes move to `selection_deleted`, so the galleries
  *    no longer show it.
  *
  * A gallery asks for many thumbnails at once, so the checks are batched: the files asked
  * within `batchWindow` go in one request (up to the account's ids per request), one
  * request at a time, on this check's own thread. A file is checked at most once per
  * `cooldown` (per `retryAfterError` when Commons couldn't be asked); a later ask gets the
  * last answer, so a file that keeps failing for another reason costs one request.
  *
  * @param now milliseconds
  */
class CommonsFileCheck(
    source: CommonsSource,
    batchWindow: FiniteDuration = CommonsFileCheck.BatchWindow,
    cooldown: FiniteDuration = CommonsFileCheck.Cooldown,
    retryAfterError: FiniteDuration = CommonsFileCheck.RetryAfterError,
    now: () => Long = () => System.currentTimeMillis()
) extends Logging {

  import CommonsFileCheck._

  private val executor = Executors.newSingleThreadScheduledExecutor { r =>
    val thread = new Thread(r, "commons-file-check")
    thread.setDaemon(true)
    thread
  }

  /** The last answer per page id, or the one being asked for. */
  private val answers = new ConcurrentHashMap[Long, Answer]()
  private val pending = new ConcurrentLinkedQueue[(Image, Promise[FileCheck])]()
  private val flushScheduled = new AtomicBoolean(false)
  /** Old title -> the file as renamed, for [[renamedFrom]]. */
  private val renamed = new ConcurrentHashMap[String, Image]()

  /** What Commons says of the stored `image`: asked within `batchWindow`, or the last answer. */
  def check(image: Image): Future[FileCheck] =
    answers
      .compute(
        image.pageId,
        (_, last) =>
          if (last != null && !stale(last)) last
          else {
            val promise = Promise[FileCheck]()
            pending.add(image -> promise)
            scheduleFlush()
            Answer(promise.future, now())
          }
      )
      .result

  /** The file renamed from `title` (`File:…`) by a check of this app, if any. */
  def renamedFrom(title: String): Option[Image] = Option(renamed.get(title))

  def stop(): Unit = executor.shutdownNow()

  private def stale(answer: Answer): Boolean =
    answer.result.value match {
      case None                            => false // being asked
      case Some(Success(FileCheck.Unknown)) => now() - answer.at >= retryAfterError.toMillis
      case Some(_)                         => now() - answer.at >= cooldown.toMillis
    }

  private def scheduleFlush(): Unit =
    if (flushScheduled.compareAndSet(false, true))
      executor.schedule((() => flush()): Runnable, batchWindow.toMillis, TimeUnit.MILLISECONDS)

  private def flush(): Unit = {
    flushScheduled.set(false)
    val batch = Iterator
      .continually(pending.poll())
      .takeWhile(_ != null)
      .take(Try(source.maxIdsPerRequest).getOrElse(CommonsLogin.Limit))
      .toSeq
    if (batch.nonEmpty) {
      val results = ask(batch.map(_._1))
      batch.foreach { case (image, promise) => promise.success(results.getOrElse(image.pageId, FileCheck.Unknown)) }
    }
    if (!pending.isEmpty) scheduleFlush()
  }

  private def ask(images: Seq[Image]): Map[Long, FileCheck] =
    try {
      val stored = images.map(i => i.pageId -> i).toMap
      val pages = source.files(stored.keys.toSeq, info = true, text = false).flatMap(p => p.id.map(_ -> p)).toMap
      val results = stored.map { case (id, image) => id -> compare(image, pages.get(id)) }
      write(stored, results)
      results
    } catch {
      case NonFatal(e) =>
        logger.warn(s"Could not check ${images.size} files on Commons: $e")
        images.map(_.pageId -> FileCheck.Unknown).toMap
    }

  private def compare(stored: Image, page: Option[Page]): FileCheck =
    page match {
      case None => FileCheck.Deleted
      case Some(p) =>
        p.images.headOption match {
          case None =>
            logger.warn(s"Commons has page ${stored.pageId} (${p.title}) without a file")
            FileCheck.Unknown
          case Some(info) =>
            val current = stored.copy(
              title = p.title,
              url = info.url,
              pageUrl = info.pageUrl,
              width = info.width.getOrElse(stored.width),
              height = info.height.getOrElse(stored.height),
              size = info.size.map(_.toInt),
              mime = info.mime,
              mediaType = info.mediaType
            )
            if (fileInfo(current) == fileInfo(stored)) FileCheck.Unchanged else FileCheck.Changed(current)
        }
    }

  private def write(stored: Map[Long, Image], results: Map[Long, FileCheck]): Unit = {
    val changed = results.values.collect { case FileCheck.Changed(image) => image }.toSeq
    val deleted = results.collect { case (id, FileCheck.Deleted) => id }.toSeq.sorted
    if (changed.nonEmpty || deleted.nonEmpty) {
      val rounds = DB.localTx { implicit session =>
        changed.foreach(ImageJdbc.updateFileInfo)
        ImageJdbc.markDeleted(deleted)
      }
      RoundImageCounts.invalidate(rounds: _*)
      changed.foreach { image =>
        val old = stored(image.pageId)
        if (old.title != image.title) {
          if (renamed.size >= MaxRenamed) renamed.clear()
          renamed.put(old.title, image)
          logger.info(s"Commons file ${image.pageId} was renamed: ${old.title} -> ${image.title}")
        } else logger.info(s"Commons file ${image.pageId} (${image.title}) changed")
      }
      deleted.foreach(id => logger.info(s"Commons file $id (${stored(id).title}) was deleted; its votes are moved aside"))
    }
  }
}

object CommonsFileCheck {
  val BatchWindow: FiniteDuration = 200.millis
  val Cooldown: FiniteDuration = 1.hour
  val RetryAfterError: FiniteDuration = 5.minutes

  /** Renames remembered for [[CommonsFileCheck.renamedFrom]]; past it they are forgotten. */
  val MaxRenamed = 10000

  private final case class Answer(result: Future[FileCheck], at: Long)

  /** What a check compares: the file's own attributes, not those from its page text. */
  private def fileInfo(i: Image) = (i.title, i.url, i.width, i.height, i.size, i.mime, i.mediaType)
}
