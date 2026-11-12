package services

/** Exceptions for a log line without their messages (R220-1): a message can carry an email, a
  * name or SQL values. The class and stack of the exception and of each cause are kept, which is
  * what support needs to find the failing step.
  */
object LogSafe {

  private val MaxCauses = 10

  /** `e` and its causes as exceptions with only their class names and stacks. */
  def withoutMessages(e: Throwable): Throwable = {
    def copy(t: Throwable, depth: Int): Throwable = {
      val cause = Option(t.getCause).filter(c => (c ne t) && depth < MaxCauses).map(copy(_, depth + 1)).orNull
      val safe = new Exception(s"${t.getClass.getName} (message not logged)", cause)
      safe.setStackTrace(t.getStackTrace)
      safe
    }
    copy(e, 0)
  }

  /** The class names of `e`'s causes, the nearest first. */
  def causeClasses(e: Throwable): Seq[String] =
    Iterator.iterate(e.getCause)(_.getCause).takeWhile(c => c != null && (c ne e)).take(MaxCauses)
      .map(_.getClass.getName).toSeq

  /** ` causes=a,b` for a log line, or nothing without a cause. */
  def causesField(e: Throwable): String = causeClasses(e) match {
    case Seq() => ""
    case cs    => " causes=" + cs.mkString(",")
  }
}
