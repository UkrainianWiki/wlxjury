package views

import db.scalikejdbc.Round
import org.jsoup.Jsoup
import org.jsoup.nodes.{Document, Element}
import org.specs2.mutable.Specification
import org.specs2.specification.core.Fragment

import scala.jdk.CollectionConverters._

/** Every URL a large view votes or moves to is a path on the page's own origin, never an
  * absolute http:// URL: behind production HTTPS a vote form posted to http:// is redirected
  * to https as a GET, and the vote is lost (fixed in c4a6c6b1, where the keys' URLs were
  * built with `Call.absoluteURL(false)`). Rendered as served at https://jury.example, so a
  * URL built from the request shows the host. No database or Play app.
  */
class LargeViewUrlsSpec extends Specification {

  import LargeViewRenders._

  private val VoteAction = "controllers.LargeViewController.rateByPageId"

  /** What a rendered large view votes and moves to. */
  final case class Urls(
      keyVotes: Seq[String],
      ratyVotePrefixes: Seq[String],
      dataVoteUrls: Seq[String],
      locations: Seq[String],
      hrefs: Seq[(Element, String)],
      formActions: Seq[String],
      doc: Document,
      html: String
  ) {
    /** The votes, with a sample score for the stars'. */
    def votes: Seq[String] = keyVotes ++ dataVoteUrls ++ ratyVotePrefixes.map(_ + "3?module=gallery")
  }

  private def captures(regex: String, text: String): Seq[String] =
    regex.r.findAllMatchIn(text).map(_.group(1)).toSeq

  def urls(html: String): Urls = {
    val doc = Jsoup.parse(html)
    val scripts = doc.select("script:not([src])").asScala.map(_.data()).mkString("\n")
    Urls(
      keyVotes = captures("""voteLarge\("([^"]*)"\)""", scripts),
      ratyVotePrefixes = captures("""voteLarge\('([^']*)' \+""", scripts),
      dataVoteUrls = doc.select("[data-vote-url]").asScala.map(_.attr("data-vote-url")).toSeq,
      locations = captures("""window\.location = "([^"]*)"""", scripts) ++ captures("""wlxGo\(e, "([^"]*)"\)""", scripts),
      hrefs = doc.select("a[href]").asScala.map(a => a -> a.attr("href")).toSeq,
      formActions = doc.select("form[action]").asScala.map(_.attr("action")).toSeq,
      doc = doc,
      html = html
    )
  }

  /** A path on this origin: not absolute, not protocol-relative. */
  private def isPath(url: String) = url.startsWith("/") && !url.startsWith("//")

  /** A link is "#", a path, or an https link elsewhere (Commons, Wikimedia's images). */
  private def isFineHref(href: String) =
    href == "#" || isPath(href) ||
      href.startsWith("https://") && !href.startsWith(s"https://$Host")

  /** The round views' Up / Down buttons (navigationButtons.scala.html), still absolute: see
    * the pending example below.
    */
  private def isUpDownButton(a: Element) =
    a.selectFirst("span.glyphicon-chevron-up, span.glyphicon-chevron-down") != null &&
      a.parents().asScala.exists(_.hasClass("btn-group"))

  private def checkCommon(u: Urls, roundView: Boolean) = {
    // no absolute URL is voted or moved to, in a script or an attribute
    """(voteLarge\(|window\.location\s*=\s*|wlxGo\(e,\s*|form\.action\s*=\s*|data-vote-url=)['"](https?:)?//[^'"]*""".r
      .findAllIn(u.html).toSeq must beEmpty
    (u.votes ++ u.locations ++ u.formActions).filterNot(isPath) must beEmpty
    u.hrefs.filterNot { case (a, _) => roundView && isUpDownButton(a) }.map(_._2).filterNot(isFineHref).map(h => s"'$h'") must beEmpty

    // a vote routes as a POST to the vote action; a GET of it doesn't vote
    u.votes.map(v => v -> AppRoutes.actionFor("POST", v)).filterNot(_._2.contains(VoteAction)) must beEmpty
    u.votes.map(v => v -> AppRoutes.actionFor("GET", v)).filter(_._2.contains(VoteAction)) must beEmpty
    // a move routes as a GET
    (u.locations ++ u.hrefs.map(_._2).filter(isPath))
      .map(l => l -> AppRoutes.actionFor("GET", l)).filter(_._2.isEmpty) must beEmpty
  }

  private def checkVoteForm(u: Urls, readOnly: Boolean) =
    if (readOnly) {
      u.doc.select("#vote-form").size must_== 0
      u.votes must beEmpty
    } else {
      val forms = u.doc.select("form#vote-form").asScala
      forms must haveSize(1)
      forms.head.attr("method") must_== "post"
      forms.head.hasAttr("action") must beFalse
      forms.head.select("input[type=hidden][name=csrfToken]").attr("value") must not(beEmpty)
    }

  private val indices = Seq(0, middle, fileCount - 1)
  // the juror's rates: rated in, at the first; rated out, at the last; none in the middle
  private val binaryRates = Map(0 -> 1, (fileCount - 1) -> -1)

  "a round's large view" should {

    for {
      (roundName, round) <- Seq("binary" -> binaryRound, "rated" -> ratedRound, "criteria" -> criteriaRound)
      index <- indices
    } {
      val rated = if (round.isBinary) binaryRates else Map(0 -> 4)
      val navCount = 5 + (if (index > 0) 1 else 0) + (if (index < fileCount - 1) 1 else 0)

      s"vote and move by paths in the juror's own $roundName view at index $index" in {
        val u = urls(large(round, index, rated = rated))
        checkCommon(u, roundView = true)
        checkVoteForm(u, readOnly = false)
        u.locations must haveSize(navCount)
        if (round.isBinary) {
          // Space to select an unselected file, Del to reject one not rejected
          val expected = (if (index == 0) Nil else Seq(1)) ++ (if (index == fileCount - 1) Nil else Seq(-1))
          u.keyVotes must_== expected.map(s => s"/large/round/1/pageid/${pageIdAt(index)}/select/$s")
          u.dataVoteUrls must haveSize(expected.size)
        } else {
          u.keyVotes must_== (0 to 5).map(s => s"/large/round/1/pageid/${pageIdAt(index)}/select/$s")
          u.ratyVotePrefixes must haveSize(if (round.hasCriteria) 3 else 1)
          u.ratyVotePrefixes.head must_== s"/large/round/1/region/all/pageid/${pageIdAt(index)}/select/"
        }
      }

      s"only move, by paths, in another juror's $roundName view at index $index" in {
        val u = urls(large(round, index, asUserId = otherJurorId))
        checkCommon(u, roundView = true)
        checkVoteForm(u, readOnly = true)
        u.locations must haveSize(navCount)
        u.html must not(contain("voteLarge(\""))
      }

      s"only move, by paths, in the juror's own $roundName view of a closed round at index $index" in {
        val u = urls(large(round.copy(active = false), index, rated = rated))
        checkCommon(u, roundView = true)
        checkVoteForm(u, readOnly = true)
        u.locations must haveSize(navCount)
      }
    }

    "keep the module and rate filter in its key URLs, as paths" in {
      val u = urls(large(ratedRound, middle, module = "byrate", rate = Some(1)))
      checkCommon(u, roundView = true)
      u.keyVotes must not(beEmpty)
      u.keyVotes.forall(_.contains("module=byrate")) must beTrue
      u.locations.filter(_.startsWith("/large/")).forall(_.contains("module=byrate")) must beTrue
    }

    "link the previous and next files by paths, not http:// URLs" in {
      // navigationButtons.scala.html's Up / Down buttons: with Call.absoluteURL(false) a click
      // behind HTTPS went to http:// and was redirected
      val u = urls(large(binaryRound, middle))
      val upDown = u.hrefs.collect { case (a, href) if isUpDownButton(a) => href }
      upDown must not(beEmpty)
      upDown.filterNot(isPath) must beEmpty
    }
  }

  "a contest file's large view" should {
    Fragment.foreach(indices) { index =>
      s"move by paths, with no vote form, at index $index" in {
        val u = urls(contestFile(index))
        checkCommon(u, roundView = false)
        checkVoteForm(u, readOnly = true)
        u.locations must haveSize(5 + (if (index > 0) 1 else 0) + (if (index < fileCount - 1) 1 else 0))
        u.locations.filter(_.startsWith("/contest/1/files/")) must not(beEmpty)
      }
    }
  }
}
