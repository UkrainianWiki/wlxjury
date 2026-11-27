package views

import org.jsoup.Jsoup
import org.specs2.mutable.Specification
import play.api.i18n.Messages
import play.api.i18n.Messages.{parse, UrlMessageSource}

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}
import scala.jdk.CollectionConverters._

/** The tool's help links into the user guide (docs/plans/2026-11-26-help-links-and-guide-split.md
  * §4.2): every topic of [[HelpLinks]] points at a page of docs/user and an explicit `<a id>` in it
  * (never a heading's slug, which changes with its words), has its link text in all four languages,
  * and is placed in a template; the templates name only the table's topics; a link is pinned to the
  * build's release tag, a snapshot's to master; and the link opens in a new tab with its mark and
  * says, for screen readers, that the guide is in English.
  */
class HelpLinksSpec extends Specification {

  private val userDocs = Paths.get("docs", "user")
  private val langs = Seq("en", "fr", "ru", "uk")

  private def read(p: Path): String = new String(Files.readAllBytes(p), UTF_8)

  /** A page's explicit ids (`<a id="…">`). */
  private def ids(page: String): Set[String] =
    """<a id="([^"]+)"></a>""".r.findAllMatchIn(read(userDocs.resolve(page))).map(_.group(1)).toSet

  private lazy val messages: Map[String, Map[String, String]] = langs.map { l =>
    l -> (parse(UrlMessageSource(Paths.get(s"conf/messages.$l").toUri.toURL), s"messages.$l") match {
      case Right(m) => m
      case Left(e)  => throw e
    })
  }.toMap

  /** Every `helpLink("…")` / `helpSection("…")` key in the templates, and a field's help with its
    * link (`withHelp(text, "…")`, editRound and imageFiltering), with its file. */
  private lazy val placed: Seq[(String, String)] = {
    val Call = """(?:help(?:Link|Section)\(|withHelp\([^
]*?,\s*)"([^"]*)"\)""".r
    Files.walk(Paths.get("app", "views")).iterator().asScala.toSeq
      .filter(_.toString.endsWith(".scala.html"))
      .filterNot(p => Set("helpLink.scala.html", "helpSection.scala.html").contains(p.getFileName.toString))
      .flatMap(p => Call.findAllMatchIn(read(p)).map(m => m.group(1) -> p.toString))
  }

  "the help topics" should {
    "point at a page of docs/user and an explicit id in it" in {
      val problems = HelpLinks.topics.toSeq.sortBy(_._1).flatMap { case (key, t) =>
        if (!Files.isRegularFile(userDocs.resolve(t.page))) Seq(s"$key: no docs/user/${t.page}")
        else if (t.anchor.nonEmpty && !ids(t.page).contains(t.anchor)) Seq(s"$key: no <a id=\"${t.anchor}\"> in ${t.page}")
        else Nil
      }
      (HelpLinks.topics.size must be_>=(40)) and (problems must beEmpty)
    }

    "have their link texts, and the words about the guide's language, in every language" in {
      val keys = HelpLinks.topics.keys.toSeq.map(HelpLinks.messageKey) :+ HelpLinks.EnglishKey
      val missing = for {
        l <- langs
        k <- keys
        if !messages(l).get(k).exists(_.trim.nonEmpty)
      } yield s"$l: $k"
      (messages("en")(HelpLinks.messageKey("guide")) === "Help") and (missing must beEmpty)
    }

    "be placed in the templates, which name no other topic" in {
      val unknown = placed.filterNot(p => HelpLinks.topics.contains(p._1))
      val unplaced = HelpLinks.topics.keySet -- placed.map(_._1)
      (placed.size must be_>=(HelpLinks.topics.size)) and (unknown must beEmpty) and (unplaced must beEmpty)
    }
  }

  "a help link's URL" should {
    "be the page at its id as of a release's tag" in {
      (HelpLinks.url("tracks", "26.11.17.1") must beSome(
        "https://github.com/UkrainianWiki/wlxjury/blob/26.11.17.1/docs/user/tracks.md#a-track-label")) and
        (HelpLinks.url("guide", "26.12") must beSome(
          "https://github.com/UkrainianWiki/wlxjury/blob/26.12/docs/user/juror-guide.md")) and
        (HelpLinks.url("no-such-topic") must beNone)
    }

    "take the release tag from the build's version, and master for a snapshot or none" in {
      (Guide.versionOf("""{"version":"26.11.17.1"}""") must beSome("26.11.17.1")) and
        (Guide.versionOf("""{"version":"26.12"}""") must beSome("26.12")) and
        (Guide.versionOf("""{"version":"26.11.18-SNAPSHOT"}""") must beNone) and
        (Guide.versionOf("{}") must beNone) and
        (HelpLinks.url("statistics", Guide.versionOf("""{"version":"26.11.18-SNAPSHOT"}""").getOrElse(Guide.Fallback)) must
          beSome("https://github.com/UkrainianWiki/wlxjury/blob/master/docs/user/statistics.md#o-statistics"))
    }
  }

  "a help link" should {
    implicit val messagesEn: Messages = LargeViewRenders.realMessages

    "open in a new tab, marked as leaving the tool, saying that the guide is in English" in {
      val doc = Jsoup.parseBodyFragment(views.html.snippets.helpLink("score-rule").body)
      val a = doc.selectFirst("a.help-link")
      (a.attr("href") === HelpLinks.url("score-rule").get) and
        (a.attr("data-help-topic") === "score-rule") and
        (ExternalLinksCheck.newTab(a) must beTrue) and
        (ExternalLinksCheck.marked(a) must beTrue) and
        (a.select("i.bi-question-circle[aria-hidden=true]").size === 1) and
        (ExternalLinksCheck.ownText(a) === "More about the file score (guide in English)") and
        (a.select(".visually-hidden").asScala.map(_.text).toSeq === Seq("(guide in English)", "(opens in a new tab)"))
    }

    "render nothing for a topic not in the table" in {
      views.html.snippets.helpLink("no-such-topic").body.trim must beEmpty
    }
  }
}
