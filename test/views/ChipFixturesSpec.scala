package views

import org.specs2.mutable.Specification
import play.api.i18n.Messages
import play.api.mvc.{AnyContentAsEmpty, Request}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import java.time.ZonedDateTime

/** The chips (snippets.voteChip, binaryTally's chip, snippets.commentChip) for
  * javascript-test/chips.test.js: the page's templates the gallery's tiles clone
  * (snippets.chipTemplates) and the texts tile-votes.js reads (voteTexts), and the server's own
  * renders of the chips the test builds on a tile, which it compares with them (structure,
  * classes, attributes, words). Committed under javascript-test/fixtures/, as
  * [[LargeViewFixturesSpec]]'s, since Jest runs before the Scala tests; regenerated with
  * FIXTURES_UPDATE=true:
  * {{{
  * FIXTURES_UPDATE=true sbt "testOnly views.ChipFixturesSpec"
  * }}}
  */
class ChipFixturesSpec extends Specification {

  val file: Path = Paths.get("javascript-test", "fixtures", "chips.html")

  val votedAt: ZonedDateTime = ZonedDateTime.parse("2026-11-12T14:05:00Z")

  def render(): String = {
    implicit val req: Request[AnyContentAsEmpty.type] = LargeViewRenders.request("/gallery/round/2/user/7/page/1")
    implicit val msgs: Messages = LargeViewRenders.realMessages
    def sample(id: String, html: play.twirl.api.Html) = s"""<div id="server-$id">${html.body.trim}</div>"""
    Seq(
      s"""<div id="holder" ${html.voteTexts().body.trim}>""",
      html.snippets.chipTemplates().body,
      """<div class="ug-tile"></div>""",
      "</div>",
      sample("comments-link", html.snippets.commentChip(3, Some("/large/round/2/user/7/pageid/101?sort=rate#comments"))),
      sample("comments", html.snippets.commentChip(1)),
      sample("tally", html.binaryTally(12, 3, chip = true)),
      sample("vote-selected", html.snippets.voteChip(1)),
      sample("vote-rejected-time", html.snippets.voteChip(-1, Some(Some(votedAt)))),
      sample("vote-time-none", html.snippets.voteChip(0, Some(None)))
    ).mkString("\n")
  }

  /** As [[LargeViewFixturesSpec.normalize]]: lines without their indentation, no blank ones. */
  def normalize(html: String): String =
    html.split("\r?\n").map(_.trim).filter(_.nonEmpty).mkString("", "\n", "\n")

  val update: Boolean =
    sys.props.get("fixtures.update").contains("true") || sys.env.get("FIXTURES_UPDATE").contains("true")

  "the chips' fixture for Jest" should {
    "match a render of the templates and the server's chips" in {
      val rendered = normalize(render())
        .replaceAll("""(data-csrf-token=")[^"]*(")""", "$1fixture-csrf-token$2")
      if (update) {
        Files.createDirectories(file.getParent)
        Files.write(file, rendered.getBytes(UTF_8))
        ok
      } else {
        val committed =
          if (Files.exists(file)) Some(normalize(new String(Files.readAllBytes(file), UTF_8)))
          else None
        if (!committed.contains(rendered)) {
          val actual = Paths.get("target", "fixtures-actual", "chips.html")
          Files.createDirectories(actual.getParent)
          Files.write(actual, rendered.getBytes(UTF_8))
        }
        (committed must beSome(rendered)).updateMessage(_ =>
          s"$file is ${if (committed.isEmpty) "missing" else "stale"}: the chips render differently. Regenerate it " +
            "with FIXTURES_UPDATE=true sbt \"testOnly views.ChipFixturesSpec\", then run Jest (javascript-test) and commit it.")
      }
    }

    "have each chip's template, as the gallery page has them" in {
      val doc = org.jsoup.Jsoup.parse(render())
      Seq("vote", "tally", "comments-link", "comments").map(n => doc.select(s"template#wlx-chip-$n-template").size) ===
        Seq(1, 1, 1, 1)
    }
  }
}
