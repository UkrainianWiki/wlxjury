package modules

import org.apache.pekko.stream.Materializer
import play.api.Configuration
import play.api.inject.ApplicationLifecycle
import play.api.libs.json.{JsObject, Json}
import play.api.libs.typedmap.TypedKey
import play.api.mvc.{EssentialAction, EssentialFilter, RequestHeader}
import scalikejdbc.GlobalSettings

import java.io.{BufferedWriter, File, FileOutputStream, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.{ExecutionContext, Future}

/** A timing log for measurements (docs/plans/2026-10-28-gallery-latency.md): per request, its
  * time in all, the time it waited before its action ran, the action's own time, its
  * queries (by statement, count and time) and the time spent rendering templates. Off unless
  * [[RequestTimingFilter]] is enabled (`play.filters.enabled`), as the Gatling fixture does
  * with `-Dgatling.timing=true`: without it every call here is a no-op.
  */
object RequestTiming {

  /** One request's times; written by one thread at a time, as the request moves between them. */
  final class Timing(val method: String, val path: String, val label: Option[String]) {
    val arrived: Long = System.nanoTime()
    val arrivedEpochMs: Long = System.currentTimeMillis()
    @volatile private[RequestTiming] var firstStart: Long = 0L
    @volatile private[RequestTiming] var queuedNanos: Long = -1L
    @volatile private[RequestTiming] var workNanos: Long = 0L
    @volatile private[RequestTiming] var renderNanos: Long = 0L
    @volatile private[RequestTiming] var renderQueryMs: Long = 0L
    @volatile private[RequestTiming] var queryMs: Long = 0L
    private[RequestTiming] val queries = mutable.LinkedHashMap.empty[String, (Int, Long)]

    private[RequestTiming] def add(statement: String, millis: Long): Unit = synchronized {
      val (n, ms) = queries.getOrElse(statement, (0, 0L))
      queries(statement) = (n + 1, ms + millis)
      queryMs += millis
    }

    def json(status: Int, done: Long): JsObject = synchronized {
      def ms(nanos: Long) = BigDecimal(nanos / 1e6).setScale(2, BigDecimal.RoundingMode.HALF_UP)
      Json.obj(
        "at" -> arrivedEpochMs,
        "method" -> method,
        "path" -> path,
        "label" -> label,
        "status" -> status,
        "totalMs" -> ms(done - arrived),
        "startMs" -> (if (firstStart == 0) None else Some(ms(firstStart - arrived))),
        "queuedMs" -> (if (queuedNanos < 0) None else Some(ms(queuedNanos))),
        "workMs" -> ms(workNanos),
        "renderMs" -> ms(renderNanos),
        "renderQueryMs" -> renderQueryMs,
        "queryMs" -> queryMs,
        "queries" -> queries.map { case (sql, (n, t)) => Json.arr(sql, n, t) }
      )
    }
  }

  val Key: TypedKey[Timing] = TypedKey[Timing]("requestTiming")

  private val current = new ThreadLocal[Timing]

  /** Runs `body` as part of the request's work: its queries counted, its time added. */
  def within[A](request: RequestHeader)(body: => A): A =
    request.attrs.get(Key).filterNot(_ eq current.get).fold(body) { timing =>
      val previous = current.get
      val start = System.nanoTime()
      if (timing.firstStart == 0L) timing.firstStart = start
      current.set(timing)
      try body
      finally {
        timing.workNanos += System.nanoTime() - start
        current.set(previous)
      }
    }

  /** The request starts running on a pool it was queued for (the blocking dispatcher). */
  def dequeued(request: RequestHeader): Unit =
    request.attrs.get(Key).foreach(t => if (t.queuedNanos < 0) t.queuedNanos = System.nanoTime() - t.arrived)

  /** Renders a template, its time (and that of the queries it runs) counted as rendering. */
  def render[A](body: => A): A =
    Option(current.get).fold(body) { timing =>
      val start = System.nanoTime()
      val queriesBefore = timing.queryMs
      try body
      finally {
        timing.renderNanos += System.nanoTime() - start
        timing.renderQueryMs += timing.queryMs - queriesBefore
      }
    }

  private[modules] def query(statement: String, millis: Long): Unit =
    Option(current.get).foreach(_.add(normalize(statement), millis))

  /** A statement without its literals, on one line: queries differing only in ids are one. */
  def normalize(statement: String): String =
    statement
      .replaceAll("'[^']*'", "?")
      .replaceAll("\\b\\d+\\b", "N")
      .replaceAll("\\s+", " ")
      .replaceAll("\\(\\s*\\?(\\s*,\\s*\\?)+\\s*\\)", "(?...)")
      .trim
}

/** Writes [[RequestTiming]]'s line per request, as JSON, to `timing.file`
  * (`target/timing.jsonl` by default). Only for measurements.
  */
@Singleton
class RequestTimingFilter @Inject() (config: Configuration, lifecycle: ApplicationLifecycle)(implicit
    val mat: Materializer,
    ec: ExecutionContext
) extends EssentialFilter {

  private val file = new File(config.getOptional[String]("timing.file").getOrElse("target/timing.jsonl"))
  file.getAbsoluteFile.getParentFile.mkdirs()
  private val out = new BufferedWriter(new OutputStreamWriter(new FileOutputStream(file, true), UTF_8))

  private val previousListener = GlobalSettings.queryCompletionListener
  GlobalSettings.queryCompletionListener = (statement, params, millis) => {
    RequestTiming.query(statement, millis)
    previousListener(statement, params, millis)
  }

  lifecycle.addStopHook { () =>
    GlobalSettings.queryCompletionListener = previousListener
    out.synchronized(out.close())
    Future.unit
  }

  private def write(line: String): Unit = out.synchronized {
    out.write(line)
    out.newLine()
    out.flush()
  }

  override def apply(next: EssentialAction): EssentialAction = EssentialAction { request =>
    val timing = new RequestTiming.Timing(request.method, request.uri, request.headers.get("X-Timing-Label"))
    next(request.addAttr(RequestTiming.Key, timing)).map { result =>
      write(timing.json(result.header.status, System.nanoTime()).toString)
      result
    }
  }
}
