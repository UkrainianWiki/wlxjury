package controllers

import db.scalikejdbc.Round.binaryRound
import db.scalikejdbc._
import org.apache.pekko.http.scaladsl.model.ContentTypes
import org.apache.pekko.stream.scaladsl.Source
import org.apache.pekko.util.ByteString
import org.intracer.wmua._
import play.api.http.HttpEntity
import play.api.i18n.I18nSupport
import play.api.mvc._
import services.GalleryService

import javax.inject.Inject

/** Backend for getting and displaying images
  */
class GalleryController @Inject() (
    galleryService: GalleryService,
    cc: ControllerComponents
) extends Secured(cc)
    with I18nSupport {

  import Pager._

  val Selected = "selected"

  val Filter = "filter"

  val UrlInProgress =
    "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8e/Icon_tools.svg/120px-Icon_tools.svg.png"

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
  ): EssentialAction = withAuth() { user => implicit request =>
    val maybeRound =
      if (roundId == 0) Round.activeRounds(user).headOption
      else Round.findById(roundId)

    val roundContestId = maybeRound.map(_.contestId).getOrElse(0L)
    val round = maybeRound.get
    val subRegions = round.specialNomination.contains("Віа Регіа")
    val rounds = if (user.canViewOrgInfo(round)) {
      Round.findByContest(roundContestId).filter(user.canViewOrgInfo)
    } else {
      Round.activeRounds(user)
    }

    if (galleryService.isNotAuthorized(user, maybeRound, roundContestId, rounds)) {
      onUnAuthorized(user)
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

      val byReg = if (contest.monumentIdTemplate.isDefined) {
        query.copy(regions = Set.empty).byRegionStat()
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
              rates
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
                byReg
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
    SelectionJdbc.rate(
      pageId = pageId,
      juryId = user.getId,
      roundId = roundId,
      rate = select
    )
    Ok("success")
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
        (x, y) <- Global.smallSizes;
        factor <- Global.sizeFactors
      )
        yield Seq(
          Global.resizeTo(image, (x * factor).toInt, (y * factor).toInt),
          Global.resizeTo(image, (y * factor).toInt)
        )

    urls.flatten.sorted.distinct
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

  def thumbnailUrls(contestId: Long, roundId: Option[Long]): EssentialAction =
    withAuth() { user => implicit request =>
      val contest = ContestJuryJdbc.findById(contestId).get
      if (user.isAdmin(Some(contestId))) {
        val urls = resizedImagesUrls(contestId, roundId)
        val name =
          "WLXJury_Round_Thumb_Urls" + contest.fullName.replaceAll(" ", "_")
        urlsStream(urls, name)
      } else {
        onUnAuthorized(user)
      }
    }
}

class RateDistribution(rateMap: Map[Int, Int]) {

  def unrated: Int = rateMap.getOrElse(0, 0)

  def selected: Int = rateMap.getOrElse(1, 0)

  def rejected: Int = rateMap.getOrElse(-1, 0)

  def all: Int = rateMap.values.sum

  def positive: Int = all - unrated - rejected

  def rated: Int = all - unrated

}
