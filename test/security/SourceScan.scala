package security

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The sources the guard specs (docs/plans/2026-11-02-security-practices.md, G*) scan: a file's
  * path, with `/`, and its text. Twirl comments (`@* *@`) are dropped, and so are Scala/JS
  * comments if asked, so that a comment naming a sink is not a hit.
  */
object SourceScan {

  final case class Source(path: String, text: String) {
    def lineOf(offset: Int): Int = text.substring(0, offset).count(_ == '\n') + 1
  }

  def files(root: String, extensions: String*): Seq[Source] = {
    val walk = Files.walk(Paths.get(root))
    try
      walk.iterator.asScala
        .filter(p => Files.isRegularFile(p) && extensions.exists(e => p.toString.endsWith(e)))
        .toList
        .map((p: Path) => Source(p.toString.replace('\\', '/'), stripComments(new String(Files.readAllBytes(p), UTF_8))))
        .sortBy(_.path)
    finally walk.close()
  }

  /** Twirl `@* *@`, block `/* */` and line `//` comments (not `://` in URLs), as blanks of the
    * same line count, so offsets still give the right line.
    */
  def stripComments(text: String): String = {
    def blank(s: String) = s.map(c => if (c == '\n') '\n' else ' ')
    val noBlocks = """(?s)@\*.*?\*@|/\*.*?\*/""".r.replaceAllIn(text, m => java.util.regex.Matcher.quoteReplacement(blank(m.matched)))
    """(?m)(?<![:"'\\])//.*$""".r.replaceAllIn(noBlocks, m => java.util.regex.Matcher.quoteReplacement(blank(m.matched)))
  }

  /** The files (path relative to the project) where `pattern` matches, and their lines. */
  def hits(sources: Seq[Source], pattern: scala.util.matching.Regex): Map[String, Seq[Int]] =
    sources
      .map(s => s.path -> pattern.findAllMatchIn(s.text).map(m => s.lineOf(m.start)).toList)
      .filter(_._2.nonEmpty)
      .toMap
}
