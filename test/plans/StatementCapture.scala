package plans

import scalikejdbc.GlobalSettings

import java.security.MessageDigest
import java.util.concurrent.ConcurrentHashMap
import scala.collection.mutable
import scala.jdk.CollectionConverters._

/** Test-only capture of the statements ScalikeJDBC runs (docs/plans/2026-11-13-query-plan-checks.md,
  * §4.1 A, decision 5): each completed or failed statement by shape, with its first sample of
  * bound values, its caller (the first frame of the application's code, without line numbers)
  * and the scenarios it ran in.
  *
  * The context is explicit: [[within]] sets it on the calling thread for its body. A statement on
  * a thread without one (a Future's, a background job's) is recorded under [[NoContext]], never
  * given a global "current scenario".
  *
  * Blind spots, each visible in the catalog: a batch reaches the listener with its template and
  * no parameters (recorded without a sample, `batch`); raw JDBC outside ScalikeJDBC (Flyway, this
  * harness's own EXPLAINs) is not seen.
  */
object StatementCapture {

  val NoContext = "no context"

  /** One shape: a caller group and the hash of its normalized text. */
  final class Shape(val group: String, val hash: String, val template: String, val normalized: String) {
    @volatile var count = 0
    @volatile var failures = 0
    @volatile var sample: Option[Seq[Any]] = None
    @volatile var batch = false
    val contexts: mutable.Map[String, Int] = mutable.TreeMap.empty
    def id: String = s"$group#$hash"
  }

  private val shapes = new ConcurrentHashMap[String, Shape]()
  private val context = new ThreadLocal[String]
  private val paused = new ThreadLocal[java.lang.Boolean]
  @volatile private var installed: Option[(GlobalSettings.QueryCompletionListener, GlobalSettings.QueryFailureListener)] = None

  /** Chains the capture after the listeners in place; [[uninstall]] puts them back. */
  def install(): Unit = synchronized {
    if (installed.isEmpty) {
      val completion = GlobalSettings.queryCompletionListener
      val failure = GlobalSettings.queryFailureListener
      installed = Some((completion, failure))
      GlobalSettings.queryCompletionListener = (sql, params, millis) => {
        record(sql, params, failed = false)
        completion(sql, params, millis)
      }
      GlobalSettings.queryFailureListener = (sql, params, e) => {
        record(sql, params, failed = true)
        failure(sql, params, e)
      }
    }
  }

  def uninstall(): Unit = synchronized {
    installed.foreach { case (completion, failure) =>
      GlobalSettings.queryCompletionListener = completion
      GlobalSettings.queryFailureListener = failure
    }
    installed = None
  }

  def reset(): Unit = shapes.clear()

  /** `body` with `scenario` as this thread's context. */
  def within[T](scenario: String)(body: => T): T = {
    val previous = context.get
    context.set(scenario)
    try body
    finally context.set(previous)
  }

  /** `body` without capture on this thread (the harness's own statements). */
  def pausedFor[T](body: => T): T = {
    val previous = paused.get
    paused.set(true)
    try body
    finally paused.set(previous)
  }

  def all: Seq[Shape] = shapes.values().asScala.toSeq.sortBy(s => (s.group, s.hash))

  private def record(sql: String, params: collection.Seq[Any], failed: Boolean): Unit =
    if (paused.get == null || !paused.get) {
      val normalized = normalize(sql)
      val group = callerGroup()
      val hash = shortHash(normalized)
      val shape = shapes.computeIfAbsent(s"$group#$hash", _ => new Shape(group, hash, oneLine(sql), normalized))
      shape.synchronized {
        shape.count += 1
        if (failed) shape.failures += 1
        val ctx = Option(context.get).getOrElse(NoContext)
        shape.contexts.update(ctx, shape.contexts.getOrElse(ctx, 0) + 1)
        if (placeholders(sql) > 0 && params.isEmpty) shape.batch = true
        else if (shape.sample.isEmpty && !failed) shape.sample = Some(params.toSeq)
      }
    }

  def oneLine(sql: String): String = sql.replaceAll("\\s+", " ").trim

  /** Columns whose compared literals and bound values are ids: folded in a shape's text, bound
    * to the corner's ids by [[Rebind]].
    */
  val IdColumns: Seq[String] = Seq("jury_id", "round_id", "page_id", "contest_id", "user_id", "monument_id")

  private val idLiteral =
    ("(?i)\\b((?:\\w+\\.)?(?:" + IdColumns.mkString("|") + "|id))\\s*=\\s*\\d+").r
  private val idLiteralList =
    ("(?i)\\b((?:\\w+\\.)?(?:" + IdColumns.mkString("|") + "|id))\\s+in\\s*\\(\\s*\\d+(?:\\s*,\\s*\\d+)*\\s*\\)").r

  /** One line; `(?, ?, …)` lists of any length as one; numbers compared with id columns as `#`
    * (`ImageDbNew.regionRateCounts` inlines the juror and round), other literals kept (`active = 1`).
    */
  def normalize(sql: String): String = {
    val line = oneLine(sql).replaceAll("\\(\\s*\\?(?:\\s*,\\s*\\?)+\\s*\\)", "(?...)")
    idLiteralList.replaceAllIn(idLiteral.replaceAllIn(line, m => s"${m.group(1)} = #"), m => s"${m.group(1)} in (#...)")
  }

  def shortHash(s: String): String =
    MessageDigest.getInstance("SHA-1").digest(s.getBytes("UTF-8")).take(5).map("%02x".format(_)).mkString

  /** The number of `?` placeholders outside string literals. */
  def placeholders(sql: String): Int = {
    var n = 0
    var quote: Char = 0
    sql.foreach { c =>
      if (quote != 0) { if (c == quote) quote = 0 }
      else if (c == '\'' || c == '"' || c == '`') quote = c
      else if (c == '?') n += 1
    }
    n
  }

  private val appPackages = Seq("db.", "services.", "controllers.", "org.intracer.", "modules.", "api.")
  private val notCallers = Seq("db.scalikejdbc.SqlLogging")
  private val fromApp = new ConcurrentHashMap[String, java.lang.Boolean]()

  private def location(c: Class[_]): Option[String] =
    Option(c.getProtectionDomain.getCodeSource).flatMap(cs => Option(cs.getLocation)).map(_.toString)

  /** Where the application's classes are (a directory or a jar): those of `app/`, not `test/`. */
  private lazy val appLocation: Option[String] = location(classOf[db.scalikejdbc.User])

  /** Whether the class comes from the application's classes, not a test helper of the same
    * package (`db.scalikejdbc.TestDb`). Unknown (no code source): taken as the app's.
    */
  private def isAppClass(className: String): Boolean =
    fromApp.computeIfAbsent(className, name =>
      try {
        val here = location(Class.forName(name, false, getClass.getClassLoader))
        here.isEmpty || appLocation.isEmpty || here == appLocation
      } catch { case _: Throwable => true })

  /** The first frame of the application's code: `ImageDbNew.SelectionQuery.list`, without the
    * package, line numbers and `$anonfun$…$N` / `$1` suffixes, so unrelated edits keep it. A
    * statement the spec itself runs is `test:` and its first frame in this package (`plans.`).
    */
  def callerGroup(): String = {
    val frames = Thread.currentThread.getStackTrace.toSeq
    val app = frames.find { f =>
      val c = f.getClassName
      appPackages.exists(c.startsWith) && !notCallers.exists(c.startsWith) && isAppClass(c)
    }
    app.map(groupOf).getOrElse(
      frames.find(f => f.getClassName.startsWith("plans.") && !f.getClassName.startsWith("plans.StatementCapture"))
        .map(f => "test:" + groupOf(f)).getOrElse("?"))
  }

  def groupOf(frame: StackTraceElement): String = groupOf(frame.getClassName, frame.getMethodName)

  def groupOf(className: String, methodName: String): String = {
    val simple = className.substring(className.lastIndexOf('.') + 1)
      .replaceAll("\\$\\$anon(fun)?\\$\\d+", "")
      .replaceAll("\\$\\d+", "")
      .split('$').filter(_.nonEmpty).mkString(".")
    val method = methodName match {
      case m if m.startsWith("$anonfun$") => m.stripPrefix("$anonfun$").replaceAll("\\$\\d+$", "").split('$').filter(_.nonEmpty).headOption.getOrElse(m)
      case m => m.replaceAll("\\$\\d+$", "").replaceAll("\\$default\\$\\d+", "")
    }
    s"$simple.$method"
  }
}
