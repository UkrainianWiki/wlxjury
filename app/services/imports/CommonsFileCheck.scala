package services.imports

import db.scalikejdbc.{ImageJdbc, RoundImageCounts}
import org.intracer.wmua.Image
import org.scalawiki.dto.Page
import play.api.Logging
import scalikejdbc.DB

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue, Executors, ScheduledExecutorService, TimeUnit}
import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future, Promise}
import scala.util.control.NonFatal
import scala.util.{Success, Try}

/** What Commons says of a stored file whose thumbnail is not found on Wikimedia. */
sealed trait FileCheck

object FileCheck {

  /** Commons has the file under another title: `image` is the file as now stored, whose
    * thumbnail is under its new url.
    */
  final case class Renamed(image: Image) extends FileCheck

  /** Commons has the file under its title, with other attributes (a new version): `image`
    * is the file as now stored.
    */
  final case class Changed(image: Image) extends FileCheck

  /** Commons no longer has the file: it is marked deleted and its votes moved aside. */
  case object Deleted extends FileCheck

  /** Commons has the file as stored: the thumbnail failed for another reason. */
  case object Unchanged extends FileCheck

  /** Not asked (not a Commons file), no answer in time, or one that says neither. */
  case object Unknown extends FileCheck
}

/** Asks Commons, by page id, about stored files whose thumbnails Wikimedia doesn't have
  * (an HTTP 404, [[services.ThumbnailNotFound]]): the files a gallery can no longer show.
  *  - A renamed file keeps its page id, and comes back under its new title: its title, url,
  *    size and type are stored ([[ImageJdbc.updateFileInfo]]), and its former title too
  *    ([[ImageJdbc.recordRename]]), for the pages that still link the old thumbnails.
  *  - A deleted file doesn't come back: the API answers `{"pageid": N, "missing": ""}`,
  *    without a title, which scalawiki's parser drops. Only an id missing from an answer
  *    that came counts; a failed request changes nothing. The file is marked deleted, and
  *    its votes moved aside ([[DeletedFiles.markDeleted]]), so the galleries no longer show
  *    it; the next import that finds it on Commons again restores it.
  *  - Only files with a Commons url are asked about: another wiki's page id is not theirs.
  *
  * A gallery asks for many thumbnails at once, so the checks are batched: the files asked
  * within `batchWindow` go in one request (up to the account's ids per request), one
  * request at a time, on this check's own thread. A file is checked at most once per
  * `cooldown` (per `retryAfterError` when Commons couldn't be asked); a later ask gets the
  * last answer, so a file that keeps failing for another reason costs one request. Past
  * `maxAnswers` answers, the stale ones are forgotten.
  *
  * @param now milliseconds
  */
class CommonsFileCheck(
    source: CommonsSource,
    batchWindow: FiniteDuration = CommonsFileCheck.BatchWindow,
    cooldown: FiniteDuration = CommonsFileCheck.Cooldown,
    retryAfterError: FiniteDuration = CommonsFileCheck.RetryAfterError,
    now: () => Long = () => System.currentTimeMillis(),
    maxAnswers: Int = CommonsFileCheck.MaxAnswers
) extends Logging {

  import CommonsFileCheck._

  private val executor = daemonScheduler("commons-file-check")
  /** Ends the waits ([[check]]'s `within`), also while a request takes long on `executor`. */
  private val timer = daemonScheduler("commons-file-check-timer")

  /** The last answer per page id, or the one being asked for. */
  private val answers = new ConcurrentHashMap[Long, Answer]()
  private val pending = new ConcurrentLinkedQueue[(Image, Promise[FileCheck])]()
  private val flushScheduled = new AtomicBoolean(false)
  @volatile private var stopped = false

  /** What Commons says of the stored `image`: asked within `batchWindow`, or the last
    * answer. [[FileCheck.Unknown]] if it takes longer than `within`; the check goes on,
    * and a later ask gets its answer.
    */
  def check(image: Image, within: FiniteDuration = Wait): Future[FileCheck] =
    if (stopped || !isCommonsFile(image)) Future.successful(FileCheck.Unknown)
    else {
      val result = answers
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
      evict()
      if (result.isCompleted) result else waitAtMost(result, within)
    }

  def stop(): Unit = {
    stopped = true
    executor.shutdownNow()
    timer.shutdownNow()
    Iterator.continually(pending.poll()).takeWhile(_ != null).foreach(_._2.trySuccess(FileCheck.Unknown))
  }

  private def stale(answer: Answer): Boolean =
    answer.result.value match {
      case None                            => false // being asked
      case Some(Success(FileCheck.Unknown)) => now() - answer.at >= retryAfterError.toMillis
      case Some(_)                         => now() - answer.at >= cooldown.toMillis
    }

  /** Past `maxAnswers`, forgets the stale answers, and all the given ones if that is not enough. */
  private def evict(): Unit =
    if (answers.size > maxAnswers) {
      answers.values.removeIf(a => a.result.isCompleted && stale(a))
      if (answers.size > maxAnswers) answers.values.removeIf(_.result.isCompleted)
    }

  private[imports] def answerCount: Int = answers.size

  private def waitAtMost(result: Future[FileCheck], within: FiniteDuration): Future[FileCheck] = {
    val promise = Promise[FileCheck]()
    val timeout =
      timer.schedule((() => promise.trySuccess(FileCheck.Unknown)): Runnable, within.toMillis, TimeUnit.MILLISECONDS)
    result.onComplete { r =>
      timeout.cancel(false)
      promise.tryComplete(r)
    }(ExecutionContext.parasitic)
    promise.future
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
      var results = Map.empty[Long, FileCheck]
      // Every promise completes, also when ask is interrupted (stop) or fails fatally
      try results = ask(batch.map(_._1))
      finally batch.foreach { case (image, promise) => promise.trySuccess(results.getOrElse(image.pageId, FileCheck.Unknown)) }
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
            if (current.title != stored.title || current.url != stored.url) FileCheck.Renamed(current)
            else if (fileInfo(current) != fileInfo(stored)) FileCheck.Changed(current)
            else FileCheck.Unchanged
        }
    }

  private def write(stored: Map[Long, Image], results: Map[Long, FileCheck]): Unit = {
    val changed = results.values.collect {
      case FileCheck.Renamed(image) => image
      case FileCheck.Changed(image) => image
    }.toSeq.sortBy(_.pageId) // locked in page id order, as a distribution's chunk does
    val deleted = results.collect { case (id, FileCheck.Deleted) => id }.toSeq.sorted
    if (changed.nonEmpty || deleted.nonEmpty) {
      val rounds = DB.localTx { implicit session =>
        val rounds = DeletedFiles.markDeleted(deleted) // first: it locks the distribution jobs
        changed.foreach { image =>
          ImageJdbc.updateFileInfo(image)
          val former = stored(image.pageId).title
          if (former != image.title) ImageJdbc.recordRename(former, image.pageId)
        }
        rounds
      }
      RoundImageCounts.invalidate(rounds: _*)
      changed.foreach { image =>
        val former = stored(image.pageId).title
        if (former != image.title) logger.info(s"Commons file ${image.pageId} was renamed: $former -> ${image.title}")
        else logger.info(s"Commons file ${image.pageId} (${image.title}) changed")
      }
      deleted.foreach(id => logger.info(s"Commons file $id (${stored(id).title}) was deleted; its votes are moved aside"))
    }
  }
}

object CommonsFileCheck {
  val BatchWindow: FiniteDuration = 200.millis
  val Cooldown: FiniteDuration = 1.hour
  val RetryAfterError: FiniteDuration = 5.minutes

  /** How long a thumbnail request or download waits for a check by default. */
  val Wait: FiniteDuration = 5.seconds

  /** Answers kept for the cooldown; past it the stale ones, then all the given ones, are forgotten. */
  val MaxAnswers = 10000

  /** Only these urls' page ids are Commons page ids. */
  def isCommonsFile(image: Image): Boolean =
    image.url.exists(_.contains("//upload.wikimedia.org/wikipedia/commons/"))

  private final case class Answer(result: Future[FileCheck], at: Long)

  /** What a check compares: the file's own attributes, not those from its page text. */
  private def fileInfo(i: Image) = (i.title, i.url, i.width, i.height, i.size, i.mime, i.mediaType)

  private def daemonScheduler(name: String): ScheduledExecutorService =
    Executors.newSingleThreadScheduledExecutor { r =>
      val thread = new Thread(r, name)
      thread.setDaemon(true)
      thread
    }
}
