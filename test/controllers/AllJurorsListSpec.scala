package controllers

import db.scalikejdbc._
import org.apache.pekko.stream.scaladsl.Sink
import org.intracer.wmua.{ContestJury, Image, ImageWithRating, Selection}
import org.jsoup.Jsoup
import play.api.Application
import play.api.mvc.{Cookie, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.duration._
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.jdk.CollectionConverters._

/** A binary round's file list and CSV of all the jurors' votes (`user/0`), the organisers':
  * a page at a time, the CSV streamed, both ranked among all the round's files
  * (docs/plans/2026-11-01-binary-aggregate-votes.md, issue 4). Through the router.
  */
class AllJurorsListSpec extends PlaySpecification with PlayTestDb {

  sequential

  private case class Fixture(contest: ContestJury, round: Round, organizer: User, jurors: Seq[User])

  /** A binary round of `files` files, page ids 1.., each voted on by two jurors: juror 1
    * selects the first `firstSelects` files and rejects the others, juror 2 selects the first
    * two and leaves the others unrated.
    */
  private def fixture(files: Int, firstSelects: Int): Fixture = {
    val contest = contestDao.create(None, "Wiki Loves Monuments", 2026, "Ukraine")
    val round = roundDao.create(Round(None, 1, Some("R"), contest.getId, rates = Round.binaryRound, active = true))
    val jurors = (1 to 2).map(i =>
      userDao.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), contestId = contest.id)))
    round.addUsers(jurors.map(j => RoundUser(round.getId, j.getId, "jury", active = true)))
    val organizer = userDao.create(User("Org", "org@example.com", None, Set("organizer"), contestId = contest.id))
    val images = (1 to files).map(i => Image(i.toLong, s"File:Image$i.jpg", None, None, 640, 480, Some(f"80-361-$i%04d")))
    imageDao.batchInsert(images)
    selectionDao.batchInsert(images.flatMap { image =>
      val i = image.pageId
      Seq(Selection(image, jurors(0), round, if (i <= firstSelects) 1 else -1),
        Selection(image, jurors(1), round, if (i <= 2) 1 else 0))
    })
    Fixture(contest, round, organizer, jurors)
  }

  private def get(path: String, viewer: User, cookies: Cookie*)(implicit app: Application): Future[Result] =
    route(app, FakeRequest(GET, path).withSession(Secured.UserName -> viewer.email)
      .withCookies(cookies: _*).withHeaders(ACCEPT -> "text/html").withCSRFToken).get

  private def fileList(f: Fixture, page: Int, query: String = "") = s"/filelist/round/${f.round.getId}/user/0/page/$page$query"

  /** The file list's rows: each its rank and file. */
  private def rows(html: String): List[(String, String)] =
    Jsoup.parse(html).select("table tbody tr").asScala.toList.map { tr =>
      val cells = tr.select("td").asScala.map(_.text.trim)
      (cells(0), cells(1))
    }

  /** The whole list as the old file list read it, all its files at once. */
  private def wholeList(f: Fixture): Seq[ImageWithRating] =
    RoundList(f.round, 0, view = GalleryView.FileList).query.list()

  "an organiser's file list of all the jurors' votes" should {

    "show a page of 1000 files, the next page the rest, ranked among all the round's files" in {
      testDbApp { implicit app =>
        val f = fixture(files = 1003, firstSelects = 500)
        val first = get(fileList(f, 1), f.organizer)
        status(first) === OK
        val second = contentAsString(get(fileList(f, 2), f.organizer))
        val (page1, page2) = (rows(contentAsString(first)), rows(second))

        val all = wholeList(f)
        val ranks = ImageWithRating.rankImages(all, f.round)
        page1.size === 1000
        page2.size === 3
        (page1 ++ page2) === all.map(_.title).zip(ranks).map { case (t, r) => (r, t) }.toList
        // tied files share one range across the pages
        page2.map(_._1).distinct === List("501-1003")
        page1.head._1 === "1-2"
        contentAsString(first) must contain("1–1,000 of 1,003")
        second must contain(s"""href="${fileList(f, 1)}?sort=rate" rel="prev"""")
      }
    }
  }

  "the CSV of all the jurors' votes" should {

    "stream the rows the whole list gives, its votes read a chunk of files at a time" in {
      testDbApp { implicit app =>
        val f = fixture(files = 7, firstSelects = 4)
        val list = RoundList(f.round, 0, view = GalleryView.Csv)
        val jurors = User.findByRoundSelection(f.round.getId)
        val streamed = Await.result(
          Csv.rateRows(list, jurors, chunk = 2)(ExecutionContext.global).runWith(Sink.seq)(app.materializer), 10.seconds)
        streamed === Csv.addBom(Csv.exportRates(wholeList(f), jurors, f.round))
        streamed.size === 8

        val result = get(s"/query?module=csv&round=${f.round.getId}&user=0", f.organizer)
        status(result) === OK
        contentAsString(result)(defaultAwaitTimeout, app.materializer) === streamed.map(Csv.writeRow).mkString
      }
    }
  }
}
