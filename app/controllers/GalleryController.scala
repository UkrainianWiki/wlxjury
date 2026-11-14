package controllers

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
import services.imports.ContestCountries
import services.regions.{ContestKind, RegionScheme}

import javax.inject.Inject
import modules.{BlockingExecutionContext, RequestTiming}

/** Backend for getting and displaying images
  */
class GalleryController @Inject() (
    galleryService: GalleryService,
    cc: ControllerComponents,
    blocking: BlockingExecutionContext,
    config: Configuration = Configuration.empty
) extends Secured(cc)
    with I18nSupport {

  /** Whether the session cookie is Secure (production.conf): the sort cookies follow it. */
  private val sessionSecure = config.getOptional[Boolean]("play.http.session.secure").getOrElse(false)

  val Selected = "selected"

  val Filter = "filter"

  val UrlInProgress =
    "https://upload.wikimedia.org/wikipedia/commons/thumb/8/8e/Icon_tools.svg/120px-Icon_tools.svg.png"

  /** Whether the contest files page's region counts are cached ([[RegionCountCache]]). */
  private val countCache = config.getOptional[Boolean]("regions.countCache").getOrElse(true)

  /** Which round galleries' region navs show their regions' file counts
    * (`gallery.regionCounts`): "off", a juror's ("juror"), or also the whole round's, the
    * organisers' (`user/0`) views ("all"). docs/performance/practices.md §3.3 (measured in 98a8a79e:docs/plans/2026-10-25-region-improvements.md)
    * has the measurements behind the default.
    */
  private val roundRegionCounts = config.getOptional[String]("gallery.regionCounts").getOrElse("all")

  /** All the jurors' lists from `round_image`'s counts (`roundImage.read`, P2b; off by default):
    * [[RoundList.countsQuery]], each statement's snapshot proved ([[RoundList.fromCounts]]).
    */
  private val countsRead = config.getOptional[Boolean]("roundImage.read").getOrElse(false)

  /** All the category's files' counts per region, cached unless switched off. */
  private def categoryCounts(categoryId: Long): RegionCountCache.Counts =
    if (countCache) RegionCountCache.category(categoryId)(ImageJdbc.categoryRegionCounts(categoryId))
    else ImageJdbc.categoryRegionCounts(categoryId)

  /** A round's files as [[RoundList]] has them: its view (gallery, by rate, file list, CSV),
    * a juror's (or all jurors', with `asUserId` 0) votes, a page, a region; the filter and
    * order from the request's query.
    *
    * @param module one of "gallery", "byrate", "filelist" or "csv"
    */
  def query(
      module: String,
      asUserId: Option[Long],
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0
  ): EssentialAction =
    listGeneric(GalleryView.parse(module), asUserId.getOrElse(0), Left(page), region, roundId)

  /** The gallery of a juror's votes, or of all the jurors' (`asUserId` 0) as the view menu's
    * Gallery opens it; their by-rate gallery is [[byRate]].
    */
  def list(asUserId: Long, page: Int = 1, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.Gallery), asUserId, Left(page), region, roundId)

  /** The page of the list with the file ([[RoundList.atUrl]]): a redirect to it, so the URL
    * shows the page.
    */
  def listAtId(asUserId: Long, pageId: Long, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.Gallery), asUserId, Right(pageId), region, roundId)

  def byRate(asUserId: Long, page: Int = 1, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.ByRate), asUserId, Left(page), region, roundId)

  /** The page of the list by rate with the file. */
  def byRateAt(asUserId: Long, pageId: Long, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.ByRate), asUserId, Right(pageId), region, roundId)

  def fileList(
      asUserId: Long,
      page: Int = 1,
      region: String = "all",
      roundId: Long = 0,
      format: String = "wiki"
  ): EssentialAction =
    listGeneric(Some(GalleryView.FileList), asUserId, Left(page), region, roundId)

  /** The comments report of a round's list ([[GalleryView.Comments]]): its files with comments
    * in its scope ([[CommentScope]]), a page of them, each with its comments. The organisers'
    * of any list; a juror's of their own list only, with all the contest's comments ([[gallery]]).
    */
  def comments(asUserId: Long, page: Int = 1, region: String = "all", roundId: Long): EssentialAction =
    listGeneric(Some(GalleryView.Comments), asUserId, Left(page), region, roundId)

  /** The page of the comments report with the file ([[RoundList.atUrl]]), as [[listAtId]]. */
  def commentsAt(asUserId: Long, pageId: Long, region: String = "all", roundId: Long): EssentialAction =
    listGeneric(Some(GalleryView.Comments), asUserId, Right(pageId), region, roundId)

  /** The comments report's comments as CSV ([[Csv.commentRows]]): the organisers' only ([[gallery]]). */
  def commentsCsv(asUserId: Long, region: String = "all", roundId: Long): EssentialAction =
    listGeneric(Some(GalleryView.CommentsCsv), asUserId, Left(1), region, roundId)

  def listCurrent(page: Int = 1, region: String = "all"): EssentialAction =
    withAuth() { user => implicit request =>
      Redirect(routes.GalleryController.list(user.getId, page, region, 0).url, request.queryString)
    }

  /** A round's list (the user's active round's for round 0) at a page, or at the page with a
    * file; an unknown view (`/query?module=`) a page that does not exist. Never cached, as it
    * shows votes ([[noStore]]).
    */
  def listGeneric(
      view: Option[GalleryView],
      asUserId: Long,
      at: Either[Int, Long],
      region: String = "all",
      roundId: Long = 0
  ): EssentialAction = noStore(withAuthOn(blocking)() { sessionUser => implicit request =>
    val maybeRound =
      if (roundId == 0) JurorContests.firstOpenRound(sessionUser)
      else Round.findById(roundId)
    // a round of another of the user's contests opens in that contest
    val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))

    maybeRound.fold {
      // no such round, or no active one: a page saying so, not a server error
      if (roundId == 0) JurorContests.noRoundPage(sessionUser)
      else NotFound(views.html.errorPage(Some(sessionUser), "error.title", "error.round.not.found"))
    } { round =>
      Secured.switchingContest(sessionUser, user)(gallery(user, view, asUserId, at, region, round))
    }
  })

  private def gallery(
      user: User,
      view: Option[GalleryView],
      asUserId: Long,
      at: Either[Int, Long],
      region: String,
      round: Round
  )(implicit request: Request[AnyContent]): Result = {
    val rounds = galleryService.viewerRounds(user, round)

    if (galleryService.isNotAuthorized(user, Some(round), round.contestId, rounds)) {
      // not a viewer of the round (another contest, an inactive membership, a round stopped or
      // still being distributed for a juror): JSON to a script, as the comments refusal below
      val denial = Secured.Denial.round(round, Secured.ViewerRoles)
      if (Secured.isScriptRequest(request)) onUnAuthorizedJson(user, request, denial)
      else onUnAuthorized(user, request, denial)
    } else if (view.exists(_.comments) && !user.canViewOrgInfo(round) &&
        !(view.contains(GalleryView.Comments) && user.id.contains(asUserId))) {
      // the comments report and its CSV: the round's contest's admins, organizers and root, as
      // the view menu that offers it; a juror only their own list's report (the round filters
      // proposal v4 §2.2), never its CSV, another juror's or all the jurors' (refused here, as
      // JSON to a script, before viewAs, whose refusal is a page)
      val denial = Secured.Denial.round(round, User.ADMIN_ROLES ++ User.ORG_COM_ROLES)
      if (Secured.isScriptRequest(request)) onUnAuthorizedJson(user, request, denial)
      else onUnAuthorized(user, request, denial)
    } else galleryService.viewAs(user, round, asUserId) match {
      // a juror's votes are their own: another's, or all together, for the organisers
      case refusal if refusal != GalleryService.ViewAs.Allowed =>
        viewAsRefused(user, round, refusal, json = false)
      case _ =>
        view.fold[Result](NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found"))) { view =>
          ContestJuryJdbc.findById(round.contestId).fold[Result](
            NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
          ) { contest =>
            val list = RoundList.of(round, user, asUserId, region, view).withCountsRead(countsRead)
            at.fold(
              page => galleryOf(user, list, page, rounds, contest).withCookies(RoundList.remembered(list, user, sessionSecure).toSeq: _*),
              // the page with the file, or where it would be if the filter now leaves it out
              pageId => Redirect(list.position(pageId).fold(list.pageUrl(1))(p =>
                list.pageUrl(Pager.pageOf(p, list.pageSize)) + "#" + pageId))
            )
          }
        }
    }
  }

  private def galleryOf(
      user: User,
      list: RoundList,
      page: Int,
      rounds: Seq[Round],
      contest: ContestJury
  )(implicit request: Request[AnyContent]): Result = {
    val round = list.round
    val asUserId = list.asUserId

    lazy val asUser = galleryService.getAsUser(asUserId, user)

    // a juror's list: its total, region counts and tabs from one query over the juror's files.
    // Lazy, as the pager, menu and tabs: a CSV streams all the list's files without them
    lazy val counts = list.jurorCounts()

    // a ranked list's (a file list, by rate): the number of its files of each rank key, which
    // rank the page, and whose sum is its total: no count of its own
    lazy val rankCounts = Option.when(list.ranked)(list.rankCounts())
    def ranks(files: Seq[ImageWithRating]): Seq[String] = rankCounts.fold(Seq.empty[String])(list.ranks(files, _))

    // the list's total: a juror's from their counts, a ranked list's from its rank counts
    lazy val pager = Pager(page,
      counts.map(_.total(list.filter, list.region))
        .orElse(rankCounts.map(_.map(_._2).sum))
        .getOrElse(list.count()),
      list.pageSize)
    lazy val files = list.files(pager)

    // the region menu, whose regions are read when it is opened (regions)
    lazy val menu = regionMenu(list, contest)

    // the Votes menu's counts: the list's juror's (the viewer's own, or one juror's as the
    // organisers view them; none for all the jurors' votes together), each choice counting what it
    // opens (decision 2 = A): within the monument, author and comments filters and the region
    lazy val rates = if (asUserId == 0) RateDistribution.empty else votesCounts(list, counts, pager.count)

    // the staff's "My votes" link (navbar): whether the viewer has files of their own in the
    // round, when they view another juror's votes or all of them
    lazy val ownFiles = !user.id.contains(asUserId) && user.canViewOrgInfo(round) &&
      galleryService.rateDistribution(user, round).hasFiles

    // the Comments menu's number: the list's files with comments, the list's own total when on
    lazy val commented = Some(if (list.comments) pager.count else list.commentedCount())

    // the page's files' comments in the contest, for their badges (one grouped query)
    def commentCounts(files: Seq[ImageWithRating]): Map[Long, Int] =
      CommentJdbc.countsByRoom(round.contestId, files.map(_.pageId))

    lazy val jurors =
      if (user.canViewOrgInfo(round) && asUserId == 0) User.findByRoundSelection(round.getId)
      else Seq(asUser)

    list.view match {
      case GalleryView.Gallery if round.isBinary =>
        Ok(RequestTiming.render(views.html.gallery(user, list, files, pager, rounds, menu, rates, commented,
          commentCounts(files), ownFiles)))

      case GalleryView.Csv =>
        val name = "WLXJury_Round_" + round.description
        // a criteria round's average columns, named in the viewer's language (U8)
        val criteria = if (round.hasCriteria) RoundCriterion.ofRound(round.getId).map(c => c.id -> c.name) else Nil
        csvStream(Csv.rateRows(list, jurors, criteria)(blocking), name)

      case GalleryView.Comments =>
        // the page's files' comments in the scope, and the names of the other rounds they were
        // written in (all the contest's), for their badges: the organisers' only, as in the large
        // view (LargeViewController.showsCommentRounds); a juror's own report names no round
        val staff = user.canViewOrgInfo(round)
        val comments = list.commentsOf(files)
        val otherRounds =
          if (!staff) Nil else comments.values.flatten.map(_.roundId).filter(_ != round.getId).toSeq.distinct
        val roundNames =
          if (otherRounds.isEmpty) Map.empty[Long, String]
          else Round.findByIds(round.contestId, otherRounds).map(r => r.getId -> r.description).toMap
        // a criteria round's results drawn per criterion, as by rate: the round with its number
        // of criteria (withCriteria), else a 3-criteria 4/4/4 vote would be 12 stars
        // the filter by author's name, from their comments in the contest ([[CommentJdbc.authorName]])
        val commenter = list.commentsBy.map(id => id -> CommentJdbc.authorName(round.contestId, id))
        Ok(RequestTiming.render(views.html.commentsReport(user, withCriteria(list, criteriaResults(list, files)), files,
          pager, rounds, menu, rates, comments, roundNames, list.commentCount(), commenter,
          if (staff) jurorGalleries(comments.values.flatten.toSeq, rounds) else Set.empty, ownFiles)))

      case GalleryView.CommentsCsv =>
        csvStream(Csv.commentRows(list)(blocking), "WLXJury_Round_" + round.description + "_comments")

      case GalleryView.FileList =>
        val useTable = !round.isBinary || asUserId == 0
        val showAuthor = user.canViewOrgInfo(round) && files.exists(_.image.author.nonEmpty)
        val criteria = criteriaResults(list, files)
        Ok(views.html.fileList(user, withCriteria(list, criteria), asUser, files, ranks(files), jurors, rounds, menu, pager,
          "wiki", useTable, rates, showAuthor, criteria, commented, ownFiles))

      case GalleryView.Gallery | GalleryView.ByRate =>
        // a criteria round's stars are per criterion, and its files' criteria averages: read
        // once for the page
        val criteria = criteriaResults(list, files)
        // the by-rate gallery's ranks among all the list's files, as a file list's; none in file
        // order, which they would not follow (RoundList.ranked)
        Ok(RequestTiming.render(views.html.galleryByRate(user, withCriteria(list, criteria), files, pager, rounds, menu,
          rates, ranks(files), criteria, commented, commentCounts(files), ownFiles)))
    }
  }

  /** The Votes menu's counts of a juror's list (navbar.scala.html votesMenu): from the juror's
    * counts read for the pager (`counts`), in the list's region, else, under a monument, author
    * or comments filter, from [[RoundList.tabCounts]]. In a round of sub-regions there are no
    * counts by sub-region: in all of them its juror's files by rate (the filtered list's counts,
    * else all their files in the round); in one of them one count per choice, the shown
    * choice's being the pager's `total` (D8 of the navigation proposal v3, measured in its PR).
    */
  private def votesCounts(list: RoundList, counts: Option[JurorCounts], total: => Int): RateDistribution =
    counts match {
      case Some(c) => RateDistribution.of(c, list.region)
      case None if list.query.subRegions && list.region.isDefined =>
        val choices = VoteFilter.ofRound(list.round)
          .map(f => f -> (if (f == list.filter) total else list.withFilter(f).count())).toMap
        RateDistribution.ofChoices(choices, ImageJdbc.rateDistribution(list.asUserId, list.roundId).nonEmpty)
      case None =>
        list.tabCounts().fold(new RateDistribution(ImageJdbc.rateDistribution(list.asUserId, list.roundId)))(
          RateDistribution.of(_, None))
    }

  /** Of the comments' authors, by the rounds they were written in, those whose one-juror gallery
    * of that round the comments report links ((round id, user id)): a juror of the round
    * ([[User.roundsJuredIn]], as the galleries' juror menu and [[GalleryService.viewAs]] decide it)
    * in a round the viewer may see (`rounds`, [[GalleryService.viewerRounds]]); never a comment
    * without an account (`user_id` 0). One query per author on the page.
    */
  private def jurorGalleries(comments: Seq[org.intracer.wmua.Comment], rounds: Seq[Round]): Set[(Long, Long)] = {
    val visible = rounds.flatMap(_.id).toSet
    comments.filter(c => c.userId > 0 && visible.contains(c.roundId))
      .groupMap(_.userId)(_.roundId).toSeq
      .flatMap { case (userId, roundIds) => User.roundsJuredIn(userId, roundIds.distinct).map(_ -> userId) }
      .toSet
  }

  /** A criteria round's results of a page of its list's files: the averages of the list's
    * juror (all of them for `asUserId` 0), whose votes the viewer may see ([[gallery]]'s
    * `viewAs`); none in a round without criteria.
    */
  private def criteriaResults(list: RoundList, files: Seq[ImageWithRating]): CriteriaResults =
    CriteriaResults.of(list.round, files.map(_.pageId), list.asUserId)

  /** The list with its round's number of criteria, which draws its stars per criterion. */
  private def withCriteria(list: RoundList, criteria: CriteriaResults): RoundList =
    if (!list.round.hasCriteria) list
    else list.withRound(list.round.copy(criteriaCount = criteria.criteria.size.max(1)))

  /** The region menu of a round's list in a contest of monuments: the region shown, and the
    * URL of its regions ([[regions]]); None in a contest whose files have no monument ids, and
    * while the list has one monument's files (a monument is in one region).
    */
  private def regionMenu(list: RoundList, contest: ContestJury)(implicit messages: play.api.i18n.Messages)
      : Option[RegionMenu] =
    Option.when(ContestKind.monumentIdTemplate(contest).isDefined && list.monument.isEmpty) {
      val current = list.region.map(RegionScheme.of(contest).region(_, None).name)
      RegionMenu.regions(current, list.withRegion("all").pageUrl(1), list.pageUrl(1), list.regionsUrl)
    }

  /** The list's regions (without its region filter), named by the contest's kind, with the
    * files the list's filter has in each where they are counted (`gallery.regionCounts`; not
    * sub-regions): from a juror's counts, else the whole round's. None in a contest whose files
    * have no monument ids.
    */
  private def listRegions(list: RoundList, contest: ContestJury, counts: Option[JurorCounts])(implicit
      messages: play.api.i18n.Messages
  ): Seq[Region] =
    if (ContestKind.monumentIdTemplate(contest).isEmpty) Nil
    else {
      val subRegions = list.query.subRegions
      val counted = !subRegions && (roundRegionCounts == "all" || roundRegionCounts == "juror" && list.asUserId != 0)
      val byRegion = counts match {
        case Some(c) => c.regions(list.filter).map { case (id, n) => id -> Option.when(counted)(n) }
        case None =>
          if (counted) list.regionCounts().map { case (id, n) => id -> Option(n) }
          else list.query.copy(regions = Set.empty).byRegionStat()
      }
      RegionScheme.of(contest).regions(byRegion, subRegions)
    }

  /** The regions of a round's list ([[RoundList.regionsUrl]]: its viewer, filter, order and
    * view), as JSON for the gallery's region menu, read when it is first opened
    * (region-menu.js): each region's name, its file count if counted, and its list's first
    * page; and the list in all regions. By the gallery's rules (the round's contest, may view
    * it, whose votes); sent by script, it never switches the session's contest.
    */
  def regions(asUserId: Long, region: String, roundId: Long): EssentialAction =
    withAuthJsonOn(blocking)() { sessionUser => implicit request =>
      import play.api.libs.json.Json
      Round.findById(roundId).fold[Result](NotFound(Json.obj("error" -> "round_not_found"))) { round =>
        val user = Secured.inContest(sessionUser, round.contestId)
        if (!galleryService.mayViewRound(user, round))
          onUnAuthorizedJson(user, request, Secured.Denial.round(round, Secured.ViewerRoles))
        else
          galleryService.viewAs(user, round, asUserId) match {
            case refusal if refusal != GalleryService.ViewAs.Allowed => viewAsRefused(user, round, refusal, json = true)
            case _ =>
              ContestJuryJdbc.findById(round.contestId).fold[Result](
                NotFound(Json.obj("error" -> "contest_not_found"))
              ) { contest =>
                val view = request.getQueryString("module").flatMap(GalleryView.parse).getOrElse(GalleryView.Gallery)
                val list = RoundList.of(round, user, asUserId, region, view).withCountsRead(countsRead)
                val regions = listRegions(list, contest, list.jurorCounts())
                Ok(placesJson(list.region, messagesApi.preferred(request)("region.all"),
                  list.withRegion("all").pageUrl(1),
                  regions.map(r => Place(r.id, r.name, r.count, r.kind.toString, list.withRegion(r.id).pageUrl(1)))))
              }
          }
      }
    }

  def selectWS(roundId: Long, pageId: Long, select: Int): EssentialAction =
    // a tile's vote, sent by script: JSON answers (401, 403 with the denial's reference, 409,
    // 400 for a rate the round doesn't take or any rate in a criteria round) that the gallery
    // explains (vote-errors.js). It changes only the juror's own selection of the file in the
    // round (VoteJdbc.rate): a file not assigned to them changes nothing and is a 404. Only a
    // juror of the round's contest votes (Secured.VotePermission): an organizer, admin or root
    // without that role is a 403.
    withAuthJson(Secured.VotePermission(roundId)) { user => implicit request =>
    val round = Round.findById(roundId)
    // Votes only in a round open to its jurors: not in a stopped round, a previous round
    // frozen while a distribution reads its votes, or a round still being distributed; and only
    // by a juror not stopped on the round (P6: as if the round were stopped for them).
    if (!round.exists(_.openToJurors) || RoundUser.isStopped(roundId, user.getId))
      Conflict(play.api.libs.json.Json.obj("error" -> "round_closed"))
    // a criteria round's votes are per criterion, in the large view: a file's own rate would
    // overwrite the sum of its criteria (its tiles have no stars)
    else if (!round.exists(r => r.acceptsRate(select) && !r.hasCriteria))
      BadRequest(play.api.libs.json.Json.obj("error" -> "invalid_rate"))
    else if (VoteJdbc.rate(pageId = pageId, juryId = user.getId, roundId = roundId, rate = select) == 0)
      NotFound(play.api.libs.json.Json.obj("error" -> "not_found"))
    else
      Ok("success")
  }

  /** CSV rows as a download, sent as they are read ([[Csv.rateRows]]). */
  def csvStream(lines: Source[Seq[String], _], filename: String): Result = {
    val source = lines.map(line => ByteString(Csv.writeRow(line)))
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

  /** The thumbnails of the contest's or the round's files, as absolute URLs for wget or a
    * script: those served by this server's proxy under `host` (`wlxjury.host`), the files
    * themselves on Wikimedia.
    */
  def resizedImagesUrls(
      contestId: Long,
      roundId: Option[Long],
      host: String
  ): List[String] = {
    val images =
      roundId.fold(ImageJdbc.findByContestId(contestId))(ImageJdbc.byRound)
    val urls =
      for (
        image <- images;
        px <- Global.thumbnailWidths(image.width, image.height)
      )
        yield Global.resizeToPx(image, px) match {
          case path if path.startsWith("/") && !path.startsWith("//") => host.stripSuffix("/") + path
          case url => url
        }

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
    * ([[ImageJdbc.roundDeletedFiles]]). All, or those of a region or of a country (the
    * contest's country categories the files are in, of a contest of many, [[ContestCountries]]),
    * chosen in the navbar's menus, as in a round's gallery: they read the regions and the
    * countries with their files' counts when opened ([[contestFileRegions]],
    * [[contestFileCountries]]); the page reads only the counts of its tabs and its pager. For
    * the contest's admins and organizers ([[User.canViewContestFiles]]). A tile opens the
    * file's large view ([[contestFileLarge]]); a deleted file's mark links its page on Commons.
    * With `coi`, the files that are conflicts of interest ([[CoiMembers.files]]), each with
    * the person it is by and their role: which files a round leaves out, so never for jurors.
    * With `author`, the files whose stored author is exactly that (byte for byte,
    * [[ImageJdbc.categoryAuthorFiles]]), in any of these lists but a round's; only for those
    * who see authors ([[User.canViewAuthors]]), ignored for anyone else. With `monument`, the
    * files of that monument id exactly ([[ImageJdbc.categoryMonumentFiles]]), likewise; the
    * monument page links it.
    *
    * Never for the contest's jurors (the maintainer's decision of 2026-11-14, reversing D9 of
    * 2026-11-13): they browse within their round, whose lists take a monument filter
    * ([[RoundList.monument]]); a juror gets the 403 page, with the suggestion of their round.
    */
  def contestFiles(contestId: Long, page: Int, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false, author: Option[String] = None,
      monument: Option[String] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region, country, coi, author, monument).fold(notFoundPage(user, _), { listed =>
        // all the category's files' counts (cached), which also give their number
        val all = categoryCountsOf(listed)
        val view = listed.round match {
          case Some(r) =>
            val (deletedCount, counted) = ImageJdbc.roundDeletedFileCounts(r.getId)
            listed.copy(deletedCount = deletedCount, countedInRound = counted)
          case None =>
            listed.copy(allCount = all.fold(0)(_.total),
              deletedCount = listed.contest.categoryId.fold(0)(ImageJdbc.categoryDeletedCount(_)))
        }
        // of the country (the monument, ...): its files, or its files deleted on Commons
        val listCount = view.countryFilter.fold(if (view.deletedOnly) view.deletedCount else view.allCount) { country =>
          if (view.deletedOnly) view.contest.categoryId.fold(0)(ImageJdbc.categoryDeletedCount(_, Some(country)))
          else country.pageIds.size
        }
        // as many files per page as the galleries; in a region, the list's files in it
        val pager = Pager(math.max(page, 1), view.region.fold(listCount)(fileRegionCounts(view, all).getOrElse(_, 0)))
        val files = view.list.fold(Seq.empty[ImageJdbc.StoredFile])(
          ImageJdbc.listFiles(_, view.region, pager.pageSize, pager.offset))
        Ok(views.html.contestFiles(user, files, pager, view))
      })
    }

  /** The regions of a contest files page's list (without its region), with the list's files in
    * each: its region menu's, read when the menu is opened (region-menu.js). For those who may
    * see the page (a juror gets JSON 403); a script request, which never switches the session's
    * contest.
    */
  def contestFileRegions(contestId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false, author: Option[String] = None,
      monument: Option[String] = None): EssentialAction =
    withAuthJsonOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region, country, coi, author, monument).fold(notFoundJson, { view =>
        val messages = messagesApi.preferred(request)
        val regions =
          if (!view.byMonuments) Seq.empty[Region]
          else RegionScheme.of(view.contest).regions(
            fileRegionCounts(view, categoryCountsOf(view)).map { case (id, n) => id -> Some(n) })(messages)
        Ok(placesJson(view.region, messages("region.all"), view.regionUrl("all"),
          regions.map(r => Place(r.id, r.name, r.count, r.kind.toString, view.regionUrl(r.id)))))
      })
    }

  /** The countries of a contest files page's list, of a contest of many, with their files: its
    * country menu's, read when the menu is opened, as [[contestFileRegions]].
    */
  def contestFileCountries(contestId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], author: Option[String] = None, monument: Option[String] = None): EssentialAction =
    withAuthJsonOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region, country, author = author, monument = monument)
        .fold(notFoundJson, { view =>
        val collator = java.text.Collator.getInstance(java.util.Locale.ROOT)
        val countries = countryCounts(view).sortWith((a, b) => collator.compare(a._1, b._1) < 0)
        Ok(placesJson(view.country, messagesApi.preferred(request)("country.all"), view.countryUrl("all"),
          countries.map { case (name, n) => Place(name, name, Some(n), "Country", view.countryUrl(name)) }))
      })
    }

  /** All the category's files' counts (cached), for the contest's files rather than a round's. */
  private def categoryCountsOf(view: ContestFilesView): Option[RegionCountCache.Counts] =
    view.contest.categoryId.filter(_ => view.round.isEmpty).map(categoryCounts)

  /** The files of the page's list (without its region) in each region: the category's, from
    * `all`'s cached counts, else counted.
    */
  private def fileRegionCounts(view: ContestFilesView, all: Option[RegionCountCache.Counts]): Map[String, Int] =
    view.list match {
      case Some(ImageJdbc.FileList.Category(_, false, None)) => all.fold(Map.empty[String, Int])(_.byRegion)
      case list => list.fold(Map.empty[String, Int])(ImageJdbc.regionCounts(_))
    }

  /** The contest's files of each country (of the author's and the monument's files, with
    * them), for a contest of many countries.
    */
  private def countryCounts(view: ContestFilesView): Seq[(String, Int)] = {
    val narrowed = Seq(view.authorFiles, view.monumentFiles).flatten.map(_.toSet)
    val files = view.countryFiles.filter { case (pageId, _) => narrowed.forall(_(pageId)) }
    files.values.groupMapReduce(identity)(_ => 1)(_ + _).toSeq
  }

  /** The large view of a file of the contest files page ([[contestFiles]]), in the same list
    * (all, deleted or a round's deleted files, in a region or all): the files around it,
    * to go to the previous and next ones, its monument and author, and its page on Commons
    * (a deleted file's undeletion there too). Read-only: no round or votes. For those who
    * may see the files page.
    */
  def contestFileLarge(contestId: Long, pageId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false, author: Option[String] = None,
      monument: Option[String] = None): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(user, contestId, deleted, round, region, country, coi, author, monument).fold(notFoundPage(user, _), { view =>
        // two files before it and five in all to move among, as a round's large view has them,
        // and on to the last one its strip of following files shows
        val (read, offset) = view.list.fold((Seq.empty[ImageJdbc.StoredFile], 0)) { list =>
          val offset = math.max(0, ImageJdbc.fileRank(list, view.region, pageId) - Neighbourhood.Side)
          (ImageJdbc.listFiles(list, view.region, Neighbourhood.Side + 1 + Neighbourhood.Following, offset), offset)
        }
        val files = read.take(Neighbourhood.Window)
        val index = files.indexWhere(_.image.pageId == pageId)
        if (index < 0) Redirect(view.pageUrl(1))
        else {
          val monument = files(index).image.monumentId.filter(_.trim.nonEmpty).flatMap(MonumentJdbc.find(_))
          val following = read.drop(index + 1).take(Neighbourhood.Following)
          Ok(views.html.large.contestFile(user, view, files, index, following, Pager.pageOf(offset + index), monument))
        }
      })
    }

  /** The list of the files page: the contest's files, all or deleted, or the round's deleted
    * ones, in the region and of the country (not of a round's) or all, or the conflicts of
    * interest (`coi`, not of a round's, of any country); of the author (not of a round's,
    * only for a `user` who sees authors: [[User.canViewAuthors]], else ignored); of the monument
    * (not of a round's); its counts not read yet. Only for those who may open the page (its
    * actions' permission). If there is no such contest, round in it, or country category: the
    * message key saying so. An author or a monument without files in the contest is an empty list.
    */
  private def contestFilesView(user: User, contestId: Long, deleted: Boolean, round: Option[Long],
      region: String, country: Option[String], coi: Boolean = false, author: Option[String] = None,
      monument: Option[String] = None): Either[String, ContestFilesView] = {
    val contest = ContestJuryJdbc.findById(contestId)
    val maybeRound = round.flatMap(Round.findById).filter(_.contestId == contestId)
    // the monument id exactly as given (an import stores it trimmed)
    val chosenMonument = monument.filter(m => m.trim.nonEmpty && maybeRound.isEmpty)
    val monumentFiles = chosenMonument.map { m =>
      contest.flatMap(_.categoryId).fold(Seq.empty[Long])(ImageJdbc.categoryMonumentFiles(_, m))
    }
    // the author exactly as given (an import stores it trimmed), only for those who see authors
    val chosenAuthor = author.filter(a => a.trim.nonEmpty && maybeRound.isEmpty && user.canViewAuthors(contestId))
    val authorFiles = chosenAuthor.map { a =>
      contest.flatMap(_.categoryId).fold(Seq.empty[Long])(ImageJdbc.categoryAuthorFiles(_, a))
    }
    // the people of the conflicts of interest name the files' authors: only for those who see them
    val coiFiles = Option.when(coi && maybeRound.isEmpty && user.canViewAuthors(contestId)) {
      contest.flatMap(_.categoryId).fold(Map.empty[Long, CoiMembers.Member])(
        CoiMembers.files(_, CoiMembers.forContest(contestId)))
    }
    val chosenCountry = country.filter(_ => maybeRound.isEmpty && coiFiles.isEmpty)
    // the countries of a contest of many: of its files' links to its country categories
    val countryFiles = (for {
      c <- contest.filter(c => maybeRound.isEmpty && ContestCountries.ofManyCountries(c))
      categoryId <- c.categoryId
    } yield ContestCountries.fileCountries(c, ImageJdbc.categoryCountryLinks(categoryId, ContestCountries.prefixes(c))))
      .getOrElse(Map.empty[Long, String])
    if (contest.isEmpty) Left("error.contest.not.found")
    else if (round.isDefined && maybeRound.isEmpty) Left("error.round.not.found")
    else if (chosenCountry.exists(c => !countryFiles.values.exists(_ == c)))
      Left("error.not.found")
    else
      Right(ContestFilesView(contest.get, deletedOnly = deleted || maybeRound.isDefined, maybeRound,
        Some(region).filter(_ != "all"), chosenCountry, countryFiles, coiFiles = coiFiles, author = chosenAuthor,
        authorFiles = authorFiles, monument = chosenMonument, monumentFiles = monumentFiles))
  }

  private def notFoundPage(user: User, messageKey: String)(implicit request: RequestHeader): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", messageKey))

  /** A script request's 404: the message key as its error code (`contest_not_found`, ...). */
  private def notFoundJson(messageKey: String): Result =
    NotFound(play.api.libs.json.Json.obj("error" -> messageKey.stripPrefix("error.").replace('.', '_')))

  /** A place menu's places ([[RegionMenu]], region-menu.js): the place shown (None: all), all of
    * them, and each with its files' count and its list's URL.
    */
  private def placesJson(current: Option[String], allName: String, allUrl: String, places: Seq[Place])
      : play.api.libs.json.JsObject = {
    import play.api.libs.json.Json
    Json.obj(
      "current" -> current,
      "all" -> Json.obj("name" -> allName, "url" -> allUrl),
      "regions" -> places.map(p => Json.obj("id" -> p.id, "name" -> p.name, "count" -> p.count, "kind" -> p.kind,
        "url" -> p.url))
    )
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
        val host = config.getOptional[String]("wlxjury.host")
          .getOrElse((if (request.secure) "https://" else "http://") + request.host)
        val urls = resizedImagesUrls(contestId, roundId, host)
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
  * @param country        the country ([[ContestCountries]]), if one is chosen
  * @param countryFiles   the countries of the contest's files, by page id, of a contest of many
  * @param countedInRound of the round's deleted files, those still in its galleries and results
  * @param coiFiles       the conflicts of interest, if they are the list: by page id, with the
  *                       person each file is by
  * @param author         the author whose files the list is narrowed to, as stored, if one is
  *                       chosen (only for those who see authors, [[User.canViewAuthors]])
  * @param authorFiles    the author's files in the contest, by page id, if one is chosen
  * @param monument       the monument id whose files the list is narrowed to, if one is chosen
  * @param monumentFiles  the monument's files in the contest, by page id, if one is chosen
  */
final case class ContestFilesView(
    contest: ContestJury,
    deletedOnly: Boolean,
    round: Option[Round],
    region: Option[String] = None,
    country: Option[String] = None,
    countryFiles: Map[Long, String] = Map.empty,
    allCount: Int = 0,
    deletedCount: Int = 0,
    countedInRound: Int = 0,
    coiFiles: Option[Map[Long, CoiMembers.Member]] = None,
    author: Option[String] = None,
    authorFiles: Option[Seq[Long]] = None,
    monument: Option[String] = None,
    monumentFiles: Option[Seq[Long]] = None
) {

  /** The list is the conflicts of interest. */
  def coi: Boolean = coiFiles.isDefined

  /** The files listed, if there are any: none for a contest without a category. */
  def list: Option[ImageJdbc.FileList] =
    round.map(r => ImageJdbc.FileList.RoundDeleted(r.getId))
      .orElse(contest.categoryId.map(ImageJdbc.FileList.Category(_, deletedOnly, countryFilter)))

  /** The files the list is narrowed to ([[ImageJdbc.CountryFilter]]): the conflicts of
    * interest or the chosen country's, the chosen author's and the chosen monument's; those in
    * all of them.
    */
  def countryFilter: Option[ImageJdbc.CountryFilter] = {
    val ofCountry = country.map(c => countryFiles.collect { case (pageId, `c`) => pageId }.toSeq)
    Seq(coiFiles.map(_.keys.toSeq).orElse(ofCountry), authorFiles, monumentFiles).flatten
      .reduceOption { (a, b) => val inB = b.toSet; a.filter(inB) }
      .map(ids => ImageJdbc.CountryFilter(ids.sorted))
  }

  /** The people whose files are the conflicts of interest, with their numbers of files, by
    * account; none if they aren't the list.
    */
  def coiPeople: Seq[(CoiMembers.Member, Int)] =
    coiFiles.fold(Seq.empty[(CoiMembers.Member, Int)]) { files =>
      val collator = java.text.Collator.getInstance(java.util.Locale.ROOT)
      files.values.groupMapReduce(identity)(_ => 1)(_ + _).toSeq
        .sortWith((a, b) => collator.compare(a._1.account, b._1.account) < 0)
    }

  private def roundId: Option[Long] = round.flatMap(_.id)

  /** This list's page. */
  def pageUrl(page: Int): String = pageUrl(page, region.getOrElse("all"), country)

  /** This list's first page in the region, or all of it ("all"). */
  def regionUrl(region: String): String = pageUrl(1, region, country)

  /** This list's first page of the country, or all of it ("all"), in all regions. */
  def countryUrl(country: String): String = pageUrl(1, "all", Some(country).filter(_ != "all"))

  /** This list's first page without its author: the author chip's remove link. */
  def withoutAuthorUrl: String =
    routes.GalleryController.contestFiles(contest.getId, 1, deletedOnly, roundId, region.getOrElse("all"), country,
      coi, None, monument).url

  /** This list's first page without its monument: the monument chip's remove link. */
  def withoutMonumentUrl: String =
    routes.GalleryController.contestFiles(contest.getId, 1, deletedOnly, roundId, region.getOrElse("all"), country,
      coi, author).url

  private def pageUrl(page: Int, region: String, country: Option[String]): String =
    routes.GalleryController.contestFiles(contest.getId, page, deletedOnly, roundId, region, country, coi, author,
      monument).url

  /** The large view of a file in this list. */
  def largeUrl(pageId: Long): String =
    routes.GalleryController.contestFileLarge(contest.getId, pageId, deletedOnly, roundId, region.getOrElse("all"),
      country, coi, author, monument).url

  /** A contest of monuments, whose files are in regions. */
  def byMonuments: Boolean = ContestKind.monumentIdTemplate(contest).isDefined

  /** The region menu of a contest of monuments, in the navbar: of the conflicts of interest
    * too, their regions counted.
    */
  def regionMenu(implicit messages: play.api.i18n.Messages): Option[RegionMenu] =
    Option.when(byMonuments)(RegionMenu.regions(region.map(RegionScheme.of(contest).region(_, None).name),
      regionUrl("all"), pageUrl(1),
      routes.GalleryController.contestFileRegions(contest.getId, deletedOnly, roundId, "all", country, coi,
        author, monument).url))

  /** The country menu of a contest of many countries (not of a round's files), in the navbar. */
  def countryMenu: Option[RegionMenu] =
    Option.when(round.isEmpty && !coi && ContestCountries.ofManyCountries(contest))(RegionMenu.countries(country,
      countryUrl("all"), pageUrl(1),
      routes.GalleryController.contestFileCountries(contest.getId, deletedOnly, roundId, region.getOrElse("all"),
        country, author, monument).url))
}

/** A place of a place menu's ([[RegionMenu]]): a region or a country, with its files' count, if
  * counted, and its list's URL.
  */
final case class Place(id: String, name: String, count: Option[Int], kind: String, url: String)

/** A gallery's menu of the places its files are in, regions or countries (regionMenu.scala.html,
  * in the navbar): the place shown (its name; None: all), the list in all of them and in this
  * one, and the URL of the places with their files' counts, read when the menu is first opened
  * ([[GalleryController.regions]], [[GalleryController.contestFileRegions]],
  * [[GalleryController.contestFileCountries]]).
  *
  * @param id       the menu's element id
  * @param label    its label's message key
  * @param allLabel the message key of all the places
  */
final case class RegionMenu(
    id: String,
    label: String,
    allLabel: String,
    current: Option[String],
    allUrl: String,
    currentUrl: String,
    optionsUrl: String
)

object RegionMenu {

  def regions(current: Option[String], allUrl: String, currentUrl: String, optionsUrl: String): RegionMenu =
    RegionMenu("regionmenu", "region.menu", "region.all", current, allUrl, currentUrl, optionsUrl)

  def countries(current: Option[String], allUrl: String, currentUrl: String, optionsUrl: String): RegionMenu =
    RegionMenu("countrymenu", "country", "country.all", current, allUrl, currentUrl, optionsUrl)
}

/** A list's files by the Votes menu's choices (navbar.scala.html votesMenu): what each choice
  * would list ([[count]]), and whether its juror has files in the round ([[hasFiles]]: the menu,
  * the order menu and the staff's "My votes" show only then).
  */
final class RateDistribution private (counts: VoteFilter => Int, val hasFiles: Boolean) {

  /** From the files per rate (all regions; [[RateDistribution.fromRates]]): a file with any
    * rate counted, also as 0, is the juror's.
    */
  def this(rateMap: Map[Int, Int]) = this(RateDistribution.fromRates(rateMap), rateMap.nonEmpty)

  /** The files the choice would list. */
  def count(filter: VoteFilter): Int = counts(filter)

  def unrated: Int = count(VoteFilter.Unrated)

  def selected: Int = count(VoteFilter.Rate(1))

  def rejected: Int = count(VoteFilter.Rate(-1))

  def all: Int = count(VoteFilter.All)

  def rated: Int = count(VoteFilter.Rated)
}

object RateDistribution {

  /** No counts: all the jurors' votes, or a page without a round's list. */
  val empty: RateDistribution = new RateDistribution(Map.empty[Int, Int])

  /** The choices' counts from the files per rate: rated is every file not unrated. */
  def fromRates(rateMap: Map[Int, Int])(filter: VoteFilter): Int = filter match {
    case VoteFilter.All     => rateMap.values.sum
    case VoteFilter.Unrated => rateMap.getOrElse(0, 0)
    case VoteFilter.Rated   => rateMap.values.sum - rateMap.getOrElse(0, 0)
    case VoteFilter.Rate(n) => rateMap.getOrElse(n, 0)
  }

  /** A juror's counts in the list's region (all of them for None), each choice counting what it
    * opens there (D8 of the navigation proposal v3: a choice keeps the region), as the pager's
    * total ([[JurorCounts.total]]): no query of its own. The juror has files if they have any in
    * the round, in any region.
    */
  def of(counts: JurorCounts, region: Option[String]): RateDistribution =
    new RateDistribution(counts.total(_, region), counts.byRegionRate.nonEmpty)

  /** Each choice's count read on its own (a round of sub-regions in one of them, whose juror
    * counts are by top-level region: one count per choice, [[RoundList.count]]); a choice not
    * given counts 0.
    */
  def ofChoices(counts: Map[VoteFilter, Int], hasFiles: Boolean): RateDistribution =
    new RateDistribution(counts.getOrElse(_, 0), hasFiles)
}
