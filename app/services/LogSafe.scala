package services

import java.util.{Collections, IdentityHashMap}

/** Exceptions for a log line without their messages (R220-1): a message can carry an email, a
  * name or SQL values. The class and stack of the exception, of each cause and of each suppressed
  * exception are kept, which is what support needs to find the failing step. A cycle of causes
  * is cut where it closes.
  */
object LogSafe {

  /** At most this many exceptions are copied (causes and suppressed ones together). */
  private val MaxExceptions = 20

  /** `e` with its causes and suppressed exceptions, as exceptions with only their class names
    * and stacks.
    */
  def withoutMessages(e: Throwable): Throwable = {
    val seen = Collections.newSetFromMap(new IdentityHashMap[Throwable, java.lang.Boolean]())
    def copy(t: Throwable): Throwable = {
      seen.add(t)
      val cause = Option(t.getCause).filterNot(seen.contains).filter(_ => seen.size < MaxExceptions).map(copy).orNull
      val safe = new Exception(s"${t.getClass.getName} (message not logged)", cause)
      safe.setStackTrace(t.getStackTrace)
      for (s <- t.getSuppressed if !seen.contains(s) && seen.size < MaxExceptions) safe.addSuppressed(copy(s))
      safe
    }
    copy(e)
  }

  /** The class names of `e`'s causes, the nearest first, up to a cycle. */
  def causeClasses(e: Throwable): Seq[String] = {
    val seen = Collections.newSetFromMap(new IdentityHashMap[Throwable, java.lang.Boolean]())
    seen.add(e)
    Iterator.iterate(e.getCause)(_.getCause).takeWhile(c => c != null && seen.add(c)).take(MaxExceptions)
      .map(_.getClass.getName).toSeq
  }

  /** ` causes=a,b` for a log line, or nothing without a cause. */
  def causesField(e: Throwable): String = causeClasses(e) match {
    case Seq() => ""
    case cs    => " causes=" + cs.mkString(",")
  }
}
