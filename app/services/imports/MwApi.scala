package services.imports

import org.scalawiki.MwBot
import org.scalawiki.dto.cmd.Action
import org.scalawiki.dto.cmd.query.Query
import org.scalawiki.dto.{MwException, Page}
import org.scalawiki.json.Parser
import play.api.Logging
import play.api.libs.json.{JsObject, JsString}

import scala.concurrent.Await
import scala.concurrent.duration._
import scala.util.control.NonFatal
import scala.util.{Failure, Success}

/** MediaWiki API requests for the import jobs, blocking (they run on the job scheduler's
  * fetch lane), with retries of failed requests.
  *
  * scalawiki's queries follow `continue` to the end and collect every page; [[page]]
  * makes exactly one request and returns its `continue` map as the cursor of the next
  * page, so a job can keep it in its state and resume from it.
  *
  * @param retryDelay the wait before the first retry; it doubles with each
  */
class MwApi(bot: MwBot, retryDelay: FiniteDuration = MwApi.RetryDelay) extends Logging {

  import MwApi._

  /** One API page of the query: its pages, and the cursor of the next (None: the last).
    * @param cursor the previous page's cursor; `{}` for the first
    */
  def page(action: Action, cursor: JsObject): (Seq[Page], Option[JsObject]) = {
    val continue =
      if (cursor.keys.isEmpty) Map("continue" -> "")
      else cursor.value.map { case (k, v) => k -> v.asOpt[String].getOrElse(v.toString) }.toMap
    val params = (action.pairs ++ Seq("format" -> "json", "utf8" -> "")).toMap ++ continue
    withRetries(params.toString) {
      val body = Await.result(bot.post(params), RequestTimeout)
      val trimmed = body.trim
      if (!trimmed.startsWith("{")) throw new RetryableException(s"not JSON: ${trimmed.take(200)}")
      val parser = new Parser(action)
      parser.parse(body) match {
        case Success(pages) =>
          val next = parser.continue
          (pages, Option.when(next.nonEmpty)(JsObject(next.map { case (k, v) => k -> JsString(v) })))
        case Failure(e) => throw e
      }
    }
  }

  /** The whole query, its `continue` followed (for a bounded query, e.g. 50 page ids). */
  def run(query: Query): Seq[Page] =
    withRetries(Action(query).pairs.toString)(Await.result(bot.run(Action(query)), RequestTimeout).toSeq)

  /** Retries failed requests (timeouts, HTTP errors, a lagged or busy API, a response
    * that didn't parse) with backoff; not API errors, which would answer the same
    * ([[retryable]]).
    */
  def withRetries[T](what: => String)(f: => T): T = {
    var attempt = 0
    var result: Option[T] = None
    while (result.isEmpty) {
      try result = Some(f)
      catch {
        case e: MwException if !retryable(e) => throw e
        case NonFatal(e) if attempt < Retries =>
          attempt += 1
          logger.warn(s"${bot.host} request failed, retry $attempt of $Retries: ${e.getMessage} ($what)")
          Thread.sleep(retryDelay.toMillis * (1L << (attempt - 1)))
      }
    }
    result.get
  }
}

object MwApi {

  /** A lagged or busy API (its error codes), or a response that didn't parse: scalawiki
    * reports one as an error without a code (JSON that isn't an API error) or with the
    * parser's message as both code and info.
    */
  def retryable(e: MwException): Boolean = RetryableCodes(e.code) || e.code.isEmpty || e.code == e.info

  val RetryableCodes: Set[String] = Set("maxlag", "ratelimited", "readonly", "internal_api_error_DBQueryError")
  val Retries = 4
  val RetryDelay: FiniteDuration = 2.seconds
  val RequestTimeout: FiniteDuration = 2.minutes

  final class RetryableException(message: String) extends RuntimeException(message)
}
