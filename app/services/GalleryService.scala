package services

import controllers.{Pager, RateDistribution}
import db.scalikejdbc.{ImageJdbc, Round, User}
import db.scalikejdbc.rewrite.ImageDbNew
import db.scalikejdbc.rewrite.ImageDbNew.{Limit, SelectionQuery}
import org.intracer.wmua.ImageWithRating

class GalleryService {

  def getSortedImages(
      asUserId: Long,
      rate: Option[Int],
      roundId: Option[Long],
      module: String,
      pager: Pager = Pager.pageOffset(1)
  ): Seq[ImageWithRating] = {
    val userDetails = module == "filelist"
    filesByUserId(
      getQuery(asUserId, rate, roundId, Some(pager), userDetails),
      pager,
      userDetails
    )
  }

  def isNotAuthorized(
      user: User,
      maybeRound: Option[Round],
      roundContest: Long,
      rounds: Seq[Round]
  ): Boolean = {
    val userContest = user.currentContest.getOrElse(0L)
    val notAuthorized = maybeRound.isEmpty ||
      (!user.hasRole("root") && userContest != roundContest) ||
      (user.roles.intersect(Set("admin", "organizer", "root")).isEmpty
        && !rounds.exists(_.id == maybeRound.flatMap(_.id))
        && !maybeRound.exists(_.juryOrgView))
    notAuthorized
  }

  def getAsUser(asUserId: Long, user: User): User = {
    if (asUserId == 0) {
      null
    } else if (asUserId != user.getId) {
      User.findById(asUserId).get
    } else {
      user
    }
  }

  def filesByUserId(
      query: SelectionQuery,
      pager: Pager,
      userDetails: Boolean = false
  ): Seq[ImageWithRating] = {

    val withPageIdOffset = pager.startPageId
      .fold(query) { pageId =>
        val rank = query.imageRank(pageId)
        val pageSize = pager.pageSize
        val page = rank / pageSize
        val offset = page * pageSize
        pager.page = page + 1
        query.copy(limit = Some(Limit(Some(pageSize), Some(offset))))
      }
      .copy(limit = query.limit.filter(_ => !userDetails))

    withPageIdOffset.list()
  }

  def getQuery(
      userId: Long,
      rate: Option[Int],
      roundId: Option[Long],
      pager: Option[Pager] = None,
      userDetails: Boolean = false,
      rated: Option[Boolean] = None,
      regions: Set[String] = Set.empty,
      subRegions: Boolean = false,
      withPageId: Option[Long] = None
  ): SelectionQuery = {
    val userIdOpt = Some(userId).filter(_ != 0)

    ImageDbNew.SelectionQuery(
      userId = userIdOpt,
      rate = rate,
      rated = rated,
      roundId = roundId,
      regions = regions,
      order = Map("rate" -> -1, "s.monument_id" -> 1, "s.page_id" -> 1),
      grouped = userIdOpt.isEmpty && !userDetails,
      groupWithDetails = userDetails,
      limit = pager.map(p => Limit(Some(p.pageSize), p.offset, p.startPageId)),
      subRegions = subRegions,
      withPageId = withPageId
    )
  }

  def rateDistribution(user: User, round: Round): RateDistribution = {
    val rateMap = ImageJdbc.rateDistribution(user.getId, round.getId)
    new RateDistribution(rateMap)
  }

}
