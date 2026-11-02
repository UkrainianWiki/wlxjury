package controllers

import db.scalikejdbc._
import org.apache.pekko.stream.scaladsl.Sink
import org.intracer.wmua.{BinaryOrder, ContestJury, Image, ImageWithRating, Selection}
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
  private def wholeList(f: Fixture, order: BinaryOrder = BinaryOrder.Default): Seq[ImageWithRating] =
    RoundList(f.round, 0, order = ListOrder.Binary(order), view = GalleryView.FileList).query.list()

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
        second must contain(s"""href="${fileList(f, 1)}?sort=approvals" rel="prev"""")
      }
    }
  }

  "a vote filter of all the jurors' votes" should {

    "list the files with a matching vote, each with all its votes, in the file list and CSV" in {
      testDbApp { implicit app =>
        // files 4 and 5: rejected by juror 1, unrated by juror 2
        val f = fixture(files = 5, firstSelects = 3)
        val page = contentAsString(get(fileList(f, 1, "?filter=rejected"), f.organizer))
        rows(page).map(_._2) === List("File:Image4.jpg", "File:Image5.jpg")
        // the juror columns: juror 2's unrated votes too, not left blank
        Jsoup.parse(page).select("table tbody tr").asScala.toList.map(_.select("td").asScala.takeRight(2)
          .map(_.select(".visually-hidden").text).toList) === List.fill(2)(List("Rejected", "Unrated"))

        val csv = contentAsString(get(s"/query?module=csv&round=${f.round.getId}&user=0&filter=rejected", f.organizer))(
          defaultAwaitTimeout, app.materializer)
        val lines = csv.linesIterator.toList
        lines.slice(1, 3).map(_.split(",").toList.takeRight(2)) === List(List("-1", "0"), List("-1", "0"))
      }
    }
  }

  "the order of all the jurors' votes" should {

    "be offered in the navbar, and the one chosen remembered for the next list" in {
      testDbApp { implicit app =>
        val f = fixture(files = 3, firstSelects = 2)
        val chosen = get(fileList(f, 1, "?sort=difference"), f.organizer)
        val html = contentAsString(chosen).replace("&amp;", "&")
        forall(Seq("approvals", "difference", "approvals-only")) { param =>
          html must contain(s"""href="${fileList(f, 1)}?sort=$param"""")
        }
        html must contain("Approvals minus rejections")
        // the CSV of the list in the view menu, in its order
        html must beMatching("""(?s).*"/query\?module=csv[^"]*&sort=difference".*""")
        val cookie = cookies(chosen).get(RoundList.BinarySortCookie)
        cookie.map(_.value) === Some("difference")

        // a list linked by rate, as the statistics page links it, in the remembered order
        val next = contentAsString(get(s"/byrate/round/${f.round.getId}/user/0/page/1?sort=rate", f.organizer, cookie.get))
        next.replace("&amp;", "&") must contain(s"/filelist/round/${f.round.getId}/user/0/page/1?sort=difference")
      }
    }

    "rank the by-rate gallery in a binary order, not in file order, which the ranks would not follow" in {
      testDbApp { implicit app =>
        val f = fixture(files = 3, firstSelects = 2)
        def ranks(query: String) = Jsoup.parse(contentAsString(
          get(s"/byrate/round/${f.round.getId}/user/0/page/1$query", f.organizer)))
          .select("div.rank-line b").asScala.map(_.text).toList
        (ranks("?sort=approvals") === List("1-2.", "1-2.", "3.")) and
          (ranks("?sort=file").distinct === List(""))
      }
    }

    "count the by-rate gallery's files from its ranks, and in file order without them" in {
      testDbApp { implicit app =>
        // files 1-2 approved twice, 3-10 once, 11-20 rejected once
        val f = fixture(files = 20, firstSelects = 10)
        def page(query: String) = Jsoup.parse(contentAsString(
          get(s"/byrate/round/${f.round.getId}/user/0/page/2$query", f.organizer)))
        val ranked = page("?sort=approvals")
        (ranked.text must contain("16–20 of 20")) and
          (ranked.select("div.rank-line b").asScala.map(_.text).distinct.toList === List("11-20.")) and
          (page("?sort=file").text must contain("16–20 of 20"))
      }
    }

    "not be offered in a juror's own gallery" in {
      testDbApp { implicit app =>
        val f = fixture(files = 3, firstSelects = 2)
        val html = contentAsString(get(s"/gallery/round/${f.round.getId}/user/${f.jurors.head.getId}/page/1", f.jurors.head))
        html must not(contain("Approvals minus rejections"))
      }
    }
  }

  "the CSV of all the jurors' votes" should {

    "stream the rows the whole list gives, its votes read a chunk of files at a time" in {
      testDbApp { implicit app =>
        val f = fixture(files = 7, firstSelects = 4)
        val jurors = User.findByRoundSelection(f.round.getId)
        def streamed(order: BinaryOrder) = Await.result(
          Csv.rateRows(RoundList(f.round, 0, order = ListOrder.Binary(order), view = GalleryView.Csv), jurors, chunk = 2)(
            ExecutionContext.global).runWith(Sink.seq)(app.materializer), 10.seconds)
        forall(BinaryOrder.all) { order =>
          streamed(order) === Csv.addBom(Csv.exportRates(wholeList(f, order), jurors, f.round, order))
        }
        val rows = streamed(BinaryOrder.Default)
        rows.size === 9 // the header, 7 files and the legend

        val result = get(s"/query?module=csv&round=${f.round.getId}&user=0", f.organizer)
        status(result) === OK
        contentAsString(result)(defaultAwaitTimeout, app.materializer) === rows.map(Csv.writeRow).mkString
      }
    }
  }
}
