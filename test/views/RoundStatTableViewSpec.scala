package views

import controllers.RoundStat
import db.scalikejdbc.{Round, User}
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import play.api.mvc.{Flash, Request}
import play.api.test.PlaySpecification

import scala.jdk.CollectionConverters._

/** The round statistics' table (roundStatTable.scala.html): each juror's gallery and file
  * list in any round, as links drawn as buttons (never a button inside a link), disabled
  * with no files. No database or Play app.
  */
class RoundStatTableViewSpec extends PlaySpecification {

  import LargeViewRenders._

  implicit val req: Request[_] = LargeViewRenders.request("/roundstat/1")
  implicit val flash: Flash = Flash()
  implicit val msgs: play.api.i18n.Messages = LargeViewRenders.messages(req)

  private val admin = User("Admin", "admin@example.org", id = Some(9), roles = Set("admin"), contestId = Some(1))
  private val idle = User("Idle", "idle@example.org", id = Some(2), roles = Set("jury"), contestId = Some(1))

  private def render(round: Round): Document = Jsoup.parse(contentAsString(views.html.roundStatTable(admin, round,
    RoundStat(Seq(juror, idle), round, Seq(round), Map(juror.getId -> 3), Map(juror.getId -> Map(1 -> 2, 0 -> 1)), 3,
      Map(1 -> 2)))))

  /** A juror's row's files cell: the fifth. */
  private def filesCell(doc: Document, row: Int) = doc.select("tbody tr").get(row).select("td").get(4)

  "the round statistics" should {

    "link each juror's gallery and file list, in a rated, a criteria and a yes/no round" in {
      forall(Seq(ratedRound, criteriaRound, binaryRound)) { round =>
        val links = filesCell(render(round), 0).select("a").asScala.toList
        (links.map(_.attr("href")) must_== List(
          s"/gallery/round/${round.getId}/user/${juror.getId}/page/1?sort=rate",
          s"/filelist/round/${round.getId}/user/${juror.getId}/page/1?sort=rate")) and
          (links.map(_.attr("aria-label")) must_== List("gallery", "filelist")) and
          (links.map(_.attr("title")) must_== List("gallery", "filelist")) and
          (links.forall(_.hasClass("btn")) must beTrue) and
          (links.exists(_.hasClass("disabled")) must beFalse) and
          (filesCell(render(round), 0).text must_== "3")
      }
    }

    "disable a juror's links with no files: no href, out of the tab order" in {
      val links = filesCell(render(ratedRound), 1).select("a").asScala.toList
      (links.size must_== 2) and
        (links.forall(l => l.hasClass("disabled") && !l.hasAttr("href") && l.attr("aria-disabled") == "true" &&
          l.attr("tabindex") == "-1") must beTrue)
    }

    "count a binary round's files with an approval, a vote yet to come and a rejection, linking each list" in {
      val doc = Jsoup.parse(contentAsString(views.html.roundStatTable(admin, binaryRound,
        RoundStat(Seq(juror), binaryRound, Seq(binaryRound), Map(juror.getId -> 3),
          Map(juror.getId -> Map(1 -> 2, 0 -> 1)), 4, Map(1 -> 2, 0 -> 3, -1 -> 1)))))
      val footer = doc.select("tfoot th").asScala.toList
      val byRate = footer.drop(2)
      (footer.size must_== 5) and
        // as wide as the rows: the total's four columns, the files, selected, unrated, rejected
        (doc.select("thead th").size must_== 8) and
        (footer.head.attr("colspan") must_== "4") and
        (footer.head.select("small").text must_== "round.stat.total.note") and
        (byRate.map(_.ownText) must_== List("2", "3", "1")) and
        (byRate.map(_.select("a").asScala.toList.map(_.attr("href"))) must_== List("selected", "unrated", "rejected")
          .map(filter => List(s"/byrate/round/${binaryRound.getId}/user/0/page/1?filter=$filter&sort=rate",
            s"/filelist/round/${binaryRound.getId}/user/0/page/1?filter=$filter&sort=rate")))
    }

    "show the jurors' pause buttons to the contest's admin only, and to others each juror's state as text" in {
      val organizer = User("Org", "org@example.org", id = Some(8), roles = Set("organizer"), contestId = Some(1))
      val otherAdmin = User("Other", "other@example.org", id = Some(7), roles = Set("admin"), contestId = Some(2))
      val paused = idle.copy(active = Some(false))
      def table(viewer: User) = Jsoup.parse(contentAsString(views.html.roundStatTable(viewer, ratedRound,
        RoundStat(Seq(juror, paused), ratedRound, Seq(ratedRound), Map(juror.getId -> 3), Map.empty, 3, Map.empty))))
      /** Each row's fourth cell, the state's. */
      def states(doc: Document) = doc.select("tbody tr").asScala.toList.map(_.select("td").get(3))
      val asAdmin = table(admin)
      (asAdmin.select("form[id^=set-active-form-]").size must_== 2) and
        (asAdmin.select("thead th").get(3).text must_== "start.stop") and
        (asAdmin.select(".juror-voting").size must_== 0) and
        // a paused juror's button resumes their voting
        (states(asAdmin).map(_.select("button").attr("aria-label")) must_== List("juror.stop", "juror.start")) and
        forall(Seq(organizer, otherAdmin, juror)) { viewer =>
          val doc = table(viewer)
          (doc.select("form[id^=set-active-form-]").size must_== 0) and
            (doc.select("tbody button").size must_== 0) and
            // the same columns, the state as text
            (doc.select("thead th").size must_== 7) and
            (doc.select("thead th").get(3).text must_== "juror.voting") and
            (states(doc).map(_.attr("data-state")) must_== List("active", "paused")) and
            (states(doc).map(_.text) must_== List("juror.voting.active", "juror.voting.paused")) and
            (doc.select("tfoot th").first.attr("colspan") must_== "4") and
            (doc.select("tbody tr").first.select("td").get(4).text must_== "3")
        }
    }

    "have no button inside a link" in {
      forall(Seq(ratedRound, binaryRound)) { round =>
        render(round).select("a button").size must_== 0
      }
    }
  }
}
