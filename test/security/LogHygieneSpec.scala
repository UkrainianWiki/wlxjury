package security

import org.specs2.mutable.Specification

/** G9 (N11, 2.8): log lines identify accounts by id. No logger call in `app/` interpolates
  * (`$x`, `${x}`) or concatenates an expression naming an email, a full name, a password, a
  * token or a secret. A call's text is read up to its closing parenthesis (or 600 characters).
  */
class LogHygieneSpec extends Specification {

  /** "file:line" of a reviewed call, with why it is fine. */
  private val Allowed: Map[String, String] = Map.empty

  private val Call = """\b(?:logger|log|Logger)\.(?:info|warn|error|debug|trace)\(""".r
  private val Interpolated = """\$\{([^}]*)\}|\$([A-Za-z_][A-Za-z0-9_.]*)""".r
  private val Concatenated = """\+\s*([A-Za-z_][A-Za-z0-9_.]*)|([A-Za-z_][A-Za-z0-9_.]*)\s*\+""".r
  private val Sensitive = """(?i)email|fullname|password|token|secret""".r

  /** The text of the call starting at `from` (after its opening parenthesis). */
  private def callText(text: String, from: Int): String = {
    var depth = 1
    var i = from
    while (i < text.length && depth > 0 && i - from < 600) {
      text.charAt(i) match {
        case '(' => depth += 1
        case ')' => depth -= 1
        case _   =>
      }
      i += 1
    }
    text.substring(from, i)
  }

  /** What the call's text puts into the line: interpolated and concatenated expressions,
    * outside its string literals' plain text.
    */
  private def expressions(call: String): Seq[String] = {
    val interpolated = Interpolated.findAllMatchIn(call).map(m => Option(m.group(1)).getOrElse(m.group(2))).toList
    val outsideStrings = call.replaceAll("\"\"\"(?s).*?\"\"\"|\"(?:[^\"\\\\]|\\\\.)*\"", "\"\"")
    val concatenated = Concatenated.findAllMatchIn(outsideStrings).map(m => Option(m.group(1)).getOrElse(m.group(2))).toList
    interpolated ++ concatenated
  }

  "logger calls in app/" should {
    "not put an email, a name, a password, a token or a secret into the line" in {
      val found = for {
        source <- SourceScan.files("app", ".scala")
        call <- Call.findAllMatchIn(source.text).toList
        where = s"${source.path}:${source.lineOf(call.start)}"
        expr <- expressions(callText(source.text, call.end))
        if Sensitive.findFirstIn(expr).nonEmpty && !Allowed.contains(where)
      } yield s"$where: $expr"
      found must beEmpty
    }

    "be found by the scan (it is not vacuous)" in {
      expressions("""s"user ${user.email} at $time" + other.fullname""") must contain(allOf("user.email", "other.fullname"))
    }
  }
}
