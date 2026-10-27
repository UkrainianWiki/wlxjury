package controllers

import db.scalikejdbc.Round.binaryRound
import db.scalikejdbc._
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.intracer.wmua._
import play.api.Configuration
import play.api.http.HttpEntity
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.GalleryService
import services.regions.{ContestKind, RegionScheme}

import javax.inject.Inject
import modules.BlockingExecutionContext

/** Backend for getting and displaying images
  */
class GalleryController @Inject() (
    galleryService: GalleryService,
    cc: ControllerComponents,
    blocking: BlockingExecutionContext,
    config: Configuration = Configuration.empty
) extends Secured(cc)
    with I18nSupport {

  import Pager._

  val Selected = "selected"

  val Filter = "filter"

  val UrlInProgress =
    "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8e/Icon_tools.svg/120px-Icon_tools.svg.png"

  /** Whether the contest files page's region counts are cached ([[RegionCountCache]]). */
  private val countCache = config.getOptional[Boolean]("regions.countCache").getOrElse(true)

  /** Which round galleries' region navs show their regions' file counts
    * (`gallery.regionCounts`): "off", a juror's ("juror"), or also the whole round's, the
    * organisers' (`user/0`) views ("all"). docs/plans/2026-10-25-region-improvements.md
    * has the measurements behind the default.
    */
  private val roundRegionCounts = config.getOptional[String]("gallery.regionCounts").getOrElse("all")

  /** All the category's files' counts per region, cached unless switched off. */
  private def categoryCounts(categoryId: Long): RegionCountCache.Counts =
    if (countCache) RegionCountCache.category(categoryId)(ImageJdbc.categoryRegionCounts(categoryId))
    else ImageJdbc.categoryRegionCounts(categoryId)

  private def moduleByUserId(asUserId: Long): String =
    if (asUserId == 0) "byrate" else "gallery"

  /** Queries images
    *
    * @param module
    *   one of "gallery", "byrate" or "filelist"
    * @param asUserId
    *   view images assigned to specific user
    * @param page
    *   show specific page
    * @param region
    *   region code to filter
    * @param roundId
    *   round id
    * @param rate
    *   filter by rate. For select/reject rounds it is selected: 1, rejected: -1, unrated: 0
    * @return
    */
  def query(
      module: String,
      asUserId: Option[Long],
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0,
      rate: Option[Int],
      rated: Option[Boolean] = None
  ): EssentialAction =
    listGeneric(
      module,
      asUserId.getOrElse(0),
      pageOffset(page),
      region,
      roundId,
      rate,
      rated
    )

  def list(
      asUserId: Long,
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0,
      rate: Option[Int]
  ): EssentialAction =
    listGeneric(
      moduleByUserId(asUserId),
      asUserId,
      pageOffset(page),
      region,
      roundId,
      rate
    )

  def listAtId(
      asUserId: Long,
      pageId: Long,
      region: String = "all",
      roundId: Long = 0,
      rate: Option[Int]
  ): EssentialAction =
    listGeneric(
      moduleByUserId(asUserId),
      asUserId,
      startPageId(pageId),
      region,
      roundId,
      rate
    )

  def byRate(
      asUserId: Long,
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0,
      rated: Option[Boolean] = None
  ): EssentialAction =
    listGeneric(
      "byrate",
      asUserId,
      pageOffset(page),
      region,
      roundId,
      rated = rated
    )

  def byRateAt(
      asUserId: Long,
      pageId: Long,
      region: String = "all",
      roundId: Long = 0,
      rated: Option[Boolean] = None
  ): EssentialAction =
    listGeneric(
      "byrate",
      asUserId,
      startPageId(pageId),
      region,
      roundId,
      None,
      rated = rated
    )

  def fileList(
      asUserId: Long,
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0,
      format: String = "wiki",
      rate: Option[Int]
  ): EssentialAction =
    listGeneric("filelist", asUserId, pageOffset(page), region, roundId, rate)

  def listCurrent(
      page: Int = 1,
      region: String = "all",
      rate: Option[Int]
  ): EssentialAction =
    withAuth() { user => implicit request =>
      Redirect(routes.GalleryController.list(user.getId, page, region, 0, rate))
    }

  def listGeneric(
      module: String,
      asUserId: Long,
      pager: Pager,
      region: String = "all",
      roundId: Long = 0,
      rate: Option[Int] = None,
      rated: Option[Boolean] = None
  ): EssentialAction = withAuthOn(blocking)() { sessionUser => implicit request =>
    val maybeRound =
      if (roundId == 0) Round.activeRounds(sessionUser).headOption
      else Round.findById(roundId)
    // a round of another of the user's contests opens in that contest
    val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))

    maybeRound.fold {
      // no such round, or no active one: a page saying so, not a server error
      if (roundId == 0) Ok(views.html.errorPage(Some(sessionUser), "error.title", "no.round.yet"))
      else NotFound(views.html.errorPage(Some(sessionUser), "error.title", "error.round.not.found"))
    } { round =>
      Secured.switchingContest(sessionUser, user)(gallery(user, module, asUserId, pager, region, roundId,
        rate, rated, round))
    }
  }

  private def gallery(
      user: User,
      module: String,
      asUserId: Long,
      pager: Pager,
      region: String,
      roundId: Long,
      rate: Option[Int],
      rated: Option[Boolean],
      round: Round
  )(implicit request: Request[AnyContent]): Result = {
    val maybeRound = Some(round)
    val roundContestId = round.contestId
    val subRegions = round.specialNomination.contains("Віа Регіа")
    val rounds = if (user.canViewOrgInfo(round)) {
      Round.findByContest(roundContestId).filter(user.canViewOrgInfo)
    } else {
      Round.activeRounds(user)
    }

    if (galleryService.isNotAuthorized(user, maybeRound, roundContestId, rounds)) {
      onUnAuthorized(user, request, Secured.Denial.round(round, Secured.ViewerRoles))
    } else {

      lazy val asUser = galleryService.getAsUser(asUserId, user)

      val regions = Set(region).filter(_ != "all")

      val userDetails = Set("filelist", "csv").contains(module)
      val query = galleryService.getQuery(
        asUserId,
        rate,
        round.id,
        Some(pager),
        userDetails,
        rated,
        regions,
        subRegions
      )
      pager.setCount(query.count())

      val files = galleryService.filesByUserId(query, pager, userDetails)

      val contest = ContestJuryJdbc.findById(roundContestId).get

      val byReg = if (ContestKind.monumentIdTemplate(contest).isDefined) {
        val all = query.copy(regions = Set.empty)
        val counted = !subRegions && (roundRegionCounts == "all" || roundRegionCounts == "juror" && asUserId != 0)
        val byRegion =
          if (counted) all.regionCounts().map { case (id, n) => id -> Option(n) }
          else all.byRegionStat()
        RegionScheme.of(contest).regions(byRegion, subRegions)
      } else {
        Nil
      }

      val rates = galleryService.rateDistribution(user, round)

      //   val ranks = ImageWithRating.rankImages(sortedFiles, round)
      val useTable = !round.isBinary || asUserId == 0

      module match {
        case "gallery" if round.rates == binaryRound =>
          Ok(
            views.html.gallery(
              user,
              asUserId,
              files,
              pager,
              maybeRound,
              rounds,
              rate,
              region,
              byReg,
              rates,
              rated
            )
          )
        case "csv" =>
          val jurors = if (user.canViewOrgInfo(round) && asUserId == 0) {
            User.findByRoundSelection(roundId)
          } else {
            Seq(asUser)
          }
          val data = Csv.exportRates(files, jurors, round)
          val name = "WLXJury_Round_" + round.description
          csvStream(data, name)

        case "filelist" =>
          val ranks = ImageWithRating.rankImages(files, round)

          val jurors = if (user.canViewOrgInfo(round) && asUserId == 0) {
            User.findByRoundSelection(roundId)
          } else {
            Seq(asUser)
          }

          val showAuthor = maybeRound.exists(user.canViewOrgInfo) && files
            .exists(_.image.author.nonEmpty)
          Ok(
            views.html.fileList(
              user,
              asUserId,
              asUser,
              files,
              ranks,
              jurors,
              pager,
              maybeRound,
              rounds,
              rate,
              region,
              byReg,
              "wiki",
              useTable,
              rates,
              showAuthor
            )
          )
        case "byrate" | "gallery" =>
          if (region != "grouped") {
            Ok(
              views.html.galleryByRate(
                user,
                asUserId,
                files,
                pager,
                maybeRound,
                rounds,
                rate,
                region,
                byReg,
                rates,
                rated
              )
            )
          } else {
            Ok(
              views.html.galleryByRateRegions(
                user,
                asUserId,
                files,
                pager,
                maybeRound,
                rounds,
                rate,
                region,
                byReg,
                rated
              )
            )
          }
      }
    }
  }

  def selectWS(
      roundId: Long,
      pageId: Long,
      select: Int,
      region: String = "all",
      rate: Option[Int],
      module: String,
      criteria: Option[Int]
  ): EssentialAction =
    withAuth(
      roundPermission(
        User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES,
        roundId
      )
    ) { user => implicit request =>
    // Votes only in a round open to its jurors: not in a stopped round, a previous round
    // frozen while a distribution reads its votes, or a round still being distributed.
    if (!Round.findById(roundId).exists(_.openToJurors))
      Conflict("The round is not open for voting")
    else {
      SelectionJdbc.rate(
        pageId = pageId,
        juryId = user.getId,
        roundId = roundId,
        rate = select
      )
      Ok("success")
    }
  }

  def csvStream(lines: Seq[Seq[String]], filename: String): Result = {
    val withBom = Csv.addBom(lines).toList
    val source = Source[Seq[String]](withBom).map { line =>
      ByteString(Csv.writeRow(line))
    }
    Result(
      ResponseHeader(
        OK,
        Map(
          "Content-Disposition" -> ("attachment; filename=\"" + filename + ".csv\"")
        )
      ),
      HttpEntity.Streamed(
        source,
        None,
        Some(ContentTypes.`text/csv(UTF-8)`.value)
      )
    )
  }

  def resizedImagesUrls(
      contestId: Long,
      roundId: Option[Long]
  ): List[String] = {
    val images =
      roundId.fold(ImageJdbc.findByContestId(contestId))(ImageJdbc.byRound)
    val urls =
      for (
        image <- images;
        px <- Global.thumbnailWidths(image.width, image.height)
      )
        yield Global.resizeToPx(image, px)

    urls.sorted.distinct
  }

  def urlsStream(
      urls: collection.immutable.Iterable[String],
      filename: String
  ): Result = {
    val source = Source[String](urls).map(line => ByteString(line + "\n"))
    Result(
      ResponseHeader(
        OK,
        Map(
          "Content-Disposition" -> ("attachment; filename=\"" + filename + ".txt\"")
        )
      ),
      HttpEntity.Streamed(
        source,
        None,
        Some(ContentTypes.`text/plain(UTF-8)`.value)
      )
    )
  }

  /** The contest's files, those deleted on Commons too, marked; or only those
    * (`deleted`); or, with `round`, the round's files deleted on Commons
    * ([[ImageJdbc.roundDeletedFiles]]). All, or those of a region (with the regions and
    * their file counts above, as in a round's gallery). For the contest's admins and
    * organizers ([[User.canViewContestFiles]]). A tile opens the file's large view
    * ([[contestFileLarge]]); a deleted file's mark links its page on Commons.
    */
  def contestFiles(contestId: Long, page: Int, deleted: Boolean, round: Option[Long], region: String): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region).fold(identity, { listed =>
        // all the category's files' counts (cached), which also give their number
        val all = listed.contest.categoryId.filter(_ => listed.round.isEmpty).map(categoryCounts)
        val view = listed.round match {
          case Some(r) =>
            val (deletedCount, counted) = ImageJdbc.roundDeletedFileCounts(r.getId)
            listed.copy(deletedCount = deletedCount, countedInRound = counted)
          case None =>
            listed.copy(allCount = all.fold(0)(_.total),
              deletedCount = listed.contest.categoryId.fold(0)(ImageJdbc.categoryDeletedCount(_)))
        }
        // the regions as a round's gallery has them: for a contest of monuments
        val byMonuments = ContestKind.monumentIdTemplate(view.contest).isDefined
        val regionCounts =
          if (!(byMonuments || view.region.isDefined)) Map.empty[String, Int]
          else
            view.list match {
              case Some(ImageJdbc.FileList.Category(_, false)) => all.fold(Map.empty[String, Int])(_.byRegion)
              case list => list.fold(Map.empty[String, Int])(ImageJdbc.regionCounts(_))
            }
        val regions =
          if (byMonuments) RegionScheme.of(view.contest).regions(regionCounts.map { case (id, n) => id -> Some(n) })
          else Seq.empty[Region]

        // as many files per page as the galleries
        val pager = pageOffset(math.max(page, 1))
        pager.setCount(view.region.fold(if (view.deletedOnly) view.deletedCount else view.allCount)(regionCounts.getOrElse(_, 0)))
        val files = view.list.fold(Seq.empty[ImageJdbc.StoredFile])(
          ImageJdbc.listFiles(_, view.region, pager.pageSize, pager.offset.getOrElse(0)))
        Ok(views.html.contestFiles(user, files, pager, view.copy(regions = regions)))
      })
    }

  /** The large view of a file of the contest files page ([[contestFiles]]), in the same list
    * (all, deleted or a round's deleted files, in a region or all): the files around it,
    * to go to the previous and next ones, its monument and author, and its page on Commons
    * (a deleted file's undeletion there too). Read-only: no round or votes. For those who
    * may see the files page.
    */
  def contestFileLarge(contestId: Long, pageId: Long, deleted: Boolean, round: Option[Long], region: String)
      : EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region).fold(identity, { view =>
        // two files before it, as a round's large view has them
        val (files, offset) = view.list.fold((Seq.empty[ImageJdbc.StoredFile], 0)) { list =>
          val offset = math.max(0, ImageJdbc.fileRank(list, view.region, pageId) - 2)
          (ImageJdbc.listFiles(list, view.region, 5, offset), offset)
        }
        val index = files.indexWhere(_.image.pageId == pageId)
        if (index < 0) Redirect(view.pageUrl(1))
        else {
          val monument = files(index).image.monumentId.filter(_.trim.nonEmpty).flatMap(MonumentJdbc.find(_))
          Ok(views.html.large.contestFile(user, view, files, index, (offset + index) / Pager.pageSize + 1, monument))
        }
      })
    }

  /** The list of the files page: the contest's files, all or deleted, or the round's deleted
    * ones, in the region or all; its counts not read yet. An error page if there is no such
    * contest, or round in it.
    */
  private def contestFilesView(user: User, contestId: Long, deleted: Boolean, round: Option[Long], region: String)(
      implicit request: RequestHeader
  ): Either[Result, ContestFilesView] = {
    val contest = ContestJuryJdbc.findById(contestId)
    val maybeRound = round.flatMap(Round.findById).filter(_.contestId == contestId)
    if (contest.isEmpty || round.isDefined && maybeRound.isEmpty)
      Left(NotFound(views.html.errorPage(Some(user), "error.title",
        if (contest.isEmpty) "error.contest.not.found" else "error.round.not.found")))
    else
      Right(ContestFilesView(contest.get, deletedOnly = deleted || maybeRound.isDefined, maybeRound,
        Some(region).filter(_ != "all")))
  }

  def thumbnailUrls(contestId: Long, roundId: Option[Long]): EssentialAction =
    withAuth(contestPermission(Set(User.ADMIN_ROLE), Some(contestId))) { user => implicit request =>
      // the permission is the contest's, so the round must be one of its: never another's
      val contest = ContestJuryJdbc.findById(contestId)
      val roundInContest = roundId.forall(id => Round.findById(id).exists(_.contestId == contestId))
      contest.filter(_ => roundInContest).fold[Result](
        NotFound(views.html.errorPage(Some(user), "error.title",
          if (contest.isEmpty) "error.contest.not.found" else "error.round.not.found"))
      ) { contest =>
        val urls = resizedImagesUrls(contestId, roundId)
        val name =
          "WLXJury_Round_Thumb_Urls" + contest.fullName.replaceAll(" ", "_")
        urlsStream(urls, name)
      }
    }
}

/** What [[GalleryController.contestFiles]] shows: the contest's files, all or only those
  * deleted on Commons, or a round's deleted ones; in a region or all; and how many there are.
  *
  * @param region         the region's id ([[db.scalikejdbc.rewrite.ImageDbNew.regionOf]]), if one is chosen
  * @param countedInRound of the round's deleted files, those still in its galleries and results
  * @param regions        the regions of the list's files, with their counts
  */
final case class ContestFilesView(
    contest: ContestJury,
    deletedOnly: Boolean,
    round: Option[Round],
    region: Option[String] = None,
    allCount: Int = 0,
    deletedCount: Int = 0,
    countedInRound: Int = 0,
    regions: Seq[Region] = Seq.empty
) {

  /** The files listed, if there are any: none for a contest without a category. */
  def list: Option[ImageJdbc.FileList] =
    round.map(r => ImageJdbc.FileList.RoundDeleted(r.getId))
      .orElse(contest.categoryId.map(ImageJdbc.FileList.Category(_, deletedOnly)))

  private def roundId: Option[Long] = round.flatMap(_.id)

  /** This list's page. */
  def pageUrl(page: Int): String = pageUrl(page, region.getOrElse("all"))

  /** This list's first page in the region, or all of it ("all"). */
  def regionUrl(region: String): String = pageUrl(1, region)

  private def pageUrl(page: Int, region: String): String =
    routes.GalleryController.contestFiles(contest.getId, page, deletedOnly, roundId, region).url

  /** The large view of a file in this list. */
  def largeUrl(pageId: Long): String =
    routes.GalleryController.contestFileLarge(contest.getId, pageId, deletedOnly, roundId, region.getOrElse("all")).url
}
class RateDistribution(rateMap: Map[Int, Int]) {

  def unrated: Int = rateMap.getOrElse(0, 0)

  def selected: Int = rateMap.getOrElse(1, 0)

  def rejected: Int = rateMap.getOrElse(-1, 0)

  def all: Int = rateMap.values.sum

  def positive: Int = all - unrated - rejected

  def rated: Int = all - unrated

}
