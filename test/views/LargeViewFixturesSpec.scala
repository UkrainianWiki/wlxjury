package views

import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** The large views that javascript-test/large-view-keys.test.js presses keys in (in jsdom),
  * rendered by [[LargeViewRenders]] as served at https://jury.example. Jest runs before the
  * Scala tests, so the renders are committed under javascript-test/fixtures/large/; this spec
  * fails when one is stale. To regenerate them, run it with FIXTURES_UPDATE=true in the
  * environment (or -Dfixtures.update=true in the forked test JVM's options):
  * {{{
  * FIXTURES_UPDATE=true sbt "testOnly views.LargeViewFixturesSpec"
  * }}}
  */
class LargeViewFixturesSpec extends Specification {

  import LargeViewRenders._

  val dir: Path = Paths.get("javascript-test", "fixtures", "large")

  /** Each fixture, by file name. */
  def renders: Seq[(String, () => String)] = Seq(
    // the juror's own view of a binary round, at an unrated file in the middle
    "binary-own.html" -> (() => large(binaryRound, middle)),
    // the juror's own view of a rated round (1..5 stars)
    "rated-own.html" -> (() => large(ratedRound, middle)),
    // another juror's view of the binary round: read-only
    "binary-readonly.html" -> (() => large(binaryRound, middle, asUserId = otherJurorId)),
    // a contest file's large view: read-only, its own keys (fileKeyboardNav)
    "contest-file.html" -> (() => contestFile(middle))
  )

  /** Stable across renders and checkouts: a fixed CSRF token, and lines without their
    * indentation, with no blank ones. Twirl lays out a template checked out with CRLF line
    * ends (core.autocrlf on Windows) with other blank lines and indentation than one with LF.
    */
  def normalize(html: String): String =
    html
      .replaceAll("""(name="csrfToken" value=")[^"]*(")""", "$1fixture-csrf-token$2")
      .split("\r?\n")
      .map(_.trim)
      .filter(_.nonEmpty)
      .mkString("", "\n", "\n")

  val update: Boolean =
    sys.props.get("fixtures.update").contains("true") || sys.env.get("FIXTURES_UPDATE").contains("true")

  "the large-view fixtures for Jest" should {
    Fragment.foreach(renders) { case (name, render) =>
      s"match a render of $name" in {
        val html = normalize(render())
        html must contain("value=\"fixture-csrf-token\"").when(!name.contains("readonly") && !name.contains("contest"))
        val file = dir.resolve(name)
        if (update) {
          Files.createDirectories(dir)
          Files.write(file, html.getBytes(UTF_8))
          ok
        } else {
          val committed = if (Files.exists(file)) Some(normalize(new String(Files.readAllBytes(file), UTF_8))) else None
          if (!committed.contains(html)) { // for a diff with the committed one
            val actual = Paths.get("target", "fixtures-actual", "large", name)
            Files.createDirectories(actual.getParent)
            Files.write(actual, html.getBytes(UTF_8))
          }
          (committed must beSome(html)).updateMessage(_ =>
            s"$file is ${if (committed.isEmpty) "missing" else "stale"}: the large view renders differently. " +
              "Regenerate the fixtures with FIXTURES_UPDATE=true sbt \"testOnly views.LargeViewFixturesSpec\", " +
              "then run Jest (javascript-test) and commit them.")
        }
      }
    }
  }
}
