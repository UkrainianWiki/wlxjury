package views

import controllers.{ContestFilesView, GalleryView, RoundList, VoteFilter}
import db.scalikejdbc.{ImageJdbc, Round, User}
import org.intracer.wmua._
import play.api.http.HeaderNames.HOST
import play.api.i18n.{Lang, Messages}
import play.api.mvc.request.RemoteConnection
import play.api.mvc.{AnyContentAsEmpty, Flash, Request}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, Helpers}

/** The large views rendered without a database or a Play app, as served at
  * https://jury.example behind a TLS-terminating proxy: the request is secure and its Host
  * is not the default one, so a URL built from the request (`absoluteURL`) shows up as
  * `http://jury.example/...`. Used by [[LargeViewUrlsSpec]] and [[LargeViewFixturesSpec]].
  */
object LargeViewRenders {

  val Host = "jury.example"

  /** A juror, and the one whose votes another juror's view shows (id 2). */
  val juror: User = User("Juror", "juror@example.org", id = Some(1), roles = Set("jury"), contestId = Some(1))
  val otherJurorId = 2L

  val binaryRound: Round =
    Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.binaryRound)
  val ratedRound: Round =
    Round(id = Some(1), number = 1, contestId = 1, active = true, rates = Round.ratesById(5))
  val criteriaRound: Round =
    ratedRound.copy(hasCriteria = true, criteriaNames = Seq("Quality", "Composition"))

  /** 13 files, page ids 101..113: the middle index 6 has distinct Up / Down / Page Up /
    * Page Down / Home / End targets (102 and 111 for the pages, 101 and 113 for the ends).
    */
  val fileCount = 13
  val middle = 6
  def pageIdAt(i: Int): Long = 101L + i

  /** The files in the juror's view: `rated` maps an index to the juror's rate there. */
  def files(rated: Map[Int, Int] = Map.empty): Seq[ImageWithRating] =
    (0 until fileCount).map { i =>
      val pageId = pageIdAt(i)
      ImageWithRating(
        Image(
          pageId = pageId,
          title = s"File:Image $pageId.jpg",
          url = Some(s"https://upload.wikimedia.org/wikipedia/commons/a/ab/Image_$pageId.jpg")
        ),
        // a juror's view has the juror's row for each file, rated or not
        selection = Seq(Selection(pageId, juror.getId, 1, rated.getOrElse(i, 0)))
      )
    }

  /** A secure request for `path` on [[Host]], with a CSRF token. */
  def request(path: String): Request[AnyContentAsEmpty.type] =
    FakeRequest("GET", path)
      .withHeaders(HOST -> Host)
      .withConnection(RemoteConnection("203.0.113.7", secure = true, clientCertificateChain = None))
      .withCSRFToken
      .asInstanceOf[Request[AnyContentAsEmpty.type]]

  def messages(implicit request: Request[_]): Messages = {
    implicit val lang: Lang = Lang("en-US")
    Helpers.stubMessagesApi().preferred(request)
  }

  /** The round's large view (LargeViewController.large) of index `index`, as `asUserId`'s. */
  def large(
      round: Round,
      index: Int,
      asUserId: Long = juror.getId,
      user: User = juror,
      rated: Map[Int, Int] = Map.empty,
      module: String = "gallery",
      rate: Option[Int] = None
  ): String = {
    val fs = files(rated)
    implicit val req: Request[AnyContentAsEmpty.type] =
      request(s"/large/round/${round.getId}/user/$asUserId/pageid/${fs(index).pageId}")
    implicit val msgs: Messages = messages
    implicit val flash: Flash = Flash()
    val byCriteria = if (round.hasCriteria) Map(1 -> 2, 2 -> 3) else Map.empty[Int, Int]
    val list = RoundList(round, asUserId, filter = rate.fold[VoteFilter](VoteFilter.All)(VoteFilter.ofRate),
      view = GalleryView.parse(module).get)
    views.html.large.large(
        user = user,
        list = list,
        files = fs,
        index = index,
        start = 0,
        end = fs.size,
        monument = None,
        comments = Nil,
        selection = Nil,
        byCriteria = byCriteria
      ).body
  }

  val contest: ContestJury =
    ContestJury(id = Some(1), name = "Wiki Loves Monuments", year = 2026, country = "Ukraine", images = None,
      categoryId = Some(1))

  /** A contest files page's large view (GalleryController.contestFileLarge) of index `index`. */
  def contestFile(index: Int): String = {
    val view = ContestFilesView(contest, deletedOnly = false, round = None)
    val stored = files().map(f => ImageJdbc.StoredFile(f.image, deletedAt = None))
    implicit val req: Request[AnyContentAsEmpty.type] = request(view.largeUrl(stored(index).image.pageId))
    implicit val msgs: Messages = messages
    implicit val flash: Flash = Flash()
    views.html.large.contestFile(organizer, view, stored, index, page = 1, monument = None).body
  }

  val organizer: User = User("Organizer", "org@example.org", id = Some(3), roles = Set("organizer"), contestId = Some(1))
}
