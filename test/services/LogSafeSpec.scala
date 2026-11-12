package services

import org.specs2.mutable.Specification

import java.io.{PrintWriter, StringWriter}

/** R220-1: what an exception logged through [[LogSafe]] shows: classes and stacks, never a
  * message, of the exception, its causes and suppressed exceptions; a cycle of causes ends.
  */
class LogSafeSpec extends Specification {

  private def printed(t: Throwable): String = {
    val out = new StringWriter
    t.printStackTrace(new PrintWriter(out))
    out.toString
  }

  "an exception without its messages" should {
    "keep the classes and stacks of the exception and its causes, not a message in the chain" in {
      val root = new java.io.IOException("connection to secret@example.com reset")
      val e = new java.sql.SQLException("Duplicate entry 'person@example.com'", new IllegalStateException("x", root))
      val text = printed(LogSafe.withoutMessages(e))
      (text must contain("java.lang.Exception: java.sql.SQLException (message not logged)")) and
        (text must contain("Caused by: java.lang.Exception: java.lang.IllegalStateException (message not logged)")) and
        (text must contain("Caused by: java.lang.Exception: java.io.IOException (message not logged)")) and
        (text must contain("LogSafeSpec")) and
        (text must not(contain("@example.com"))) and
        (LogSafe.causesField(e) === " causes=java.lang.IllegalStateException,java.io.IOException")
    }

    "keep a suppressed exception's class, not its message" in {
      val e = new RuntimeException("first: person@example.com")
      e.addSuppressed(new java.sql.SQLException("closing: person@example.com", new java.io.IOException("io: other@example.com")))
      val text = printed(LogSafe.withoutMessages(e))
      (text must contain("Suppressed: java.lang.Exception: java.sql.SQLException (message not logged)")) and
        (text must contain("java.io.IOException (message not logged)")) and
        (text must not(contain("@example.com")))
    }

    "end at a cycle of causes" in {
      val a = new RuntimeException("a: person@example.com")
      val b = new IllegalArgumentException("b: person@example.com")
      a.initCause(b)
      b.initCause(a)
      val safe = LogSafe.withoutMessages(a)
      val text = printed(safe)
      (safe.getCause must not(beNull)) and (safe.getCause.getCause must beNull) and
        (text must not(contain("@example.com"))) and
        (LogSafe.causeClasses(a) === Seq("java.lang.IllegalArgumentException")) and
        (LogSafe.causesField(new RuntimeException("no cause")) === "")
    }
  }
}
