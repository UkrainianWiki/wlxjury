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
    * organisers' (`user/0`) views ("all"). docs/plans/2026-10-25-region-improvements.md
    * has the measurements behind the default.
    */
  private val roundRegionCounts = config.getOptional[String]("gallery.regionCounts").getOrElse("all")

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

  def list(asUserId: Long, page: Int = 1, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.ofPath(asUserId)), asUserId, Left(page), region, roundId)

  /** The page of the list with the file ([[RoundList.atUrl]]): a redirect to it, so the URL
    * shows the page.
    */
  def listAtId(asUserId: Long, pageId: Long, region: String = "all", roundId: Long = 0): EssentialAction =
    listGeneric(Some(GalleryView.ofPath(asUserId)), asUserId, Right(pageId), region, roundId)

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
      if (roundId == 0) Round.activeRounds(sessionUser).headOption
      else Round.findById(roundId)
    // a round of another of the user's contests opens in that contest
    val user = maybeRound.fold(sessionUser)(r => Secured.inContest(sessionUser, r.contestId))

    maybeRound.fold {
      // no such round, or no active one: a page saying so, not a server error
      if (roundId == 0) Ok(views.html.errorPage(Some(sessionUser), "error.title", "no.round.yet"))
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
      onUnAuthorized(user, request, Secured.Denial.round(round, Secured.ViewerRoles))
    } else galleryService.viewAs(user, round, asUserId) match {
      // a juror's votes are their own: another's, or all together, for the organisers
      case refusal if refusal != GalleryService.ViewAs.Allowed =>
        viewAsRefused(user, round, refusal, json = false)
      case _ =>
        view.fold[Result](NotFound(views.html.errorPage(Some(user), "error.title", "error.not.found"))) { view =>
          ContestJuryJdbc.findById(round.contestId).fold[Result](
            NotFound(views.html.errorPage(Some(user), "error.title", "error.contest.not.found"))
          ) { contest =>
            val list = RoundList.of(round, user, asUserId, region, view)
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

    // the viewer's own tabs: the counts above when the list is their own
    lazy val rates = counts.filter(_ => user.id.contains(asUserId)).fold(galleryService.rateDistribution(user, round))(c =>
      new RateDistribution(c.rates))

    lazy val jurors =
      if (user.canViewOrgInfo(round) && asUserId == 0) User.findByRoundSelection(round.getId)
      else Seq(asUser)

    list.view match {
      case GalleryView.Gallery if round.isBinary =>
        Ok(RequestTiming.render(views.html.gallery(user, list, files, pager, rounds, menu, rates)))

      case GalleryView.Csv =>
        val name = "WLXJury_Round_" + round.description
        // a criteria round's average columns, named in the viewer's language (U8)
        val criteria = if (round.hasCriteria) RoundCriterion.ofRound(round.getId).map(c => c.id -> c.name) else Nil
        csvStream(Csv.rateRows(list, jurors, criteria)(blocking), name)

      case GalleryView.FileList =>
        val useTable = !round.isBinary || asUserId == 0
        val showAuthor = user.canViewOrgInfo(round) && files.exists(_.image.author.nonEmpty)
        val criteria = criteriaResults(list, files)
        Ok(views.html.fileList(user, withCriteria(list, criteria), asUser, files, ranks(files), jurors, rounds, menu, pager,
          "wiki", useTable, rates, showAuthor, criteria))

      case GalleryView.Gallery | GalleryView.ByRate =>
        // a criteria round's stars are per criterion, and its files' criteria averages: read
        // once for the page
        val criteria = criteriaResults(list, files)
        // the by-rate gallery's ranks among all the list's files, as a file list's; none in file
        // order, which they would not follow (RoundList.ranked)
        Ok(RequestTiming.render(views.html.galleryByRate(user, withCriteria(list, criteria), files, pager, rounds, menu,
          rates, ranks(files), criteria)))
    }
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
    * URL of its regions ([[regions]]); None in a contest whose files have no monument ids.
    */
  private def regionMenu(list: RoundList, contest: ContestJury)(implicit messages: play.api.i18n.Messages)
      : Option[RegionMenu] =
    Option.when(ContestKind.monumentIdTemplate(contest).isDefined) {
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
      val all = list.query.copy(regions = Set.empty)
      val counted = !subRegions && (roundRegionCounts == "all" || roundRegionCounts == "juror" && list.asUserId != 0)
      val byRegion = counts match {
        case Some(c) => c.regions(list.filter).map { case (id, n) => id -> Option.when(counted)(n) }
        case None =>
          if (counted) all.regionCounts().map { case (id, n) => id -> Option(n) }
          else all.byRegionStat()
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
                val view = request.getQueryString("module").flatMap(GalleryView.parse).getOrElse(GalleryView.ofPath(asUserId))
                val list = RoundList.of(round, user, asUserId, region, view)
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
    // round (VoteJdbc.rate): a file not assigned to them changes nothing and is a 404.
    withAuthJson(
      roundPermission(
        User.ADMIN_ROLES ++ User.JURY_ROLES ++ User.ORG_COM_ROLES,
        roundId
      )
    ) { user => implicit request =>
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
    */
  def contestFiles(contestId: Long, page: Int, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(contestId, deleted, round, region, country, coi).fold(notFoundPage(user, _), { listed =>
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
        // of the country: its files, or its files deleted on Commons
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
    * see the page; a script request, which never switches the session's contest.
    */
  def contestFileRegions(contestId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false): EssentialAction =
    withAuthJsonOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { _ => implicit request =>
      contestFilesView(contestId, deleted, round, region, country, coi).fold(notFoundJson, { view =>
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
      country: Option[String]): EssentialAction =
    withAuthJsonOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { _ => implicit request =>
      contestFilesView(contestId, deleted, round, region, country).fold(notFoundJson, { view =>
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

  /** The contest's files of each country, for a contest of many countries. */
  private def countryCounts(view: ContestFilesView): Seq[(String, Int)] =
    view.countryFiles.values.groupMapReduce(identity)(_ => 1)(_ + _).toSeq

  /** The large view of a file of the contest files page ([[contestFiles]]), in the same list
    * (all, deleted or a round's deleted files, in a region or all): the files around it,
    * to go to the previous and next ones, its monument and author, and its page on Commons
    * (a deleted file's undeletion there too). Read-only: no round or votes. For those who
    * may see the files page.
    */
  def contestFileLarge(contestId: Long, pageId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false): EssentialAction =
    withAuthOn(blocking)(contestPermission(User.ADMIN_ROLES ++ User.ORG_COM_ROLES, Some(contestId))) { user => implicit request =>
      contestFilesView(contestId, deleted, round, region, country, coi).fold(notFoundPage(user, _), { view =>
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
    * interest (`coi`, not of a round's, of any country); its counts not read yet. If there
    * is no such contest, round in it, or country category: the message key saying so.
    */
  private def contestFilesView(contestId: Long, deleted: Boolean, round: Option[Long], region: String,
      country: Option[String], coi: Boolean = false): Either[String, ContestFilesView] = {
    val contest = ContestJuryJdbc.findById(contestId)
    val maybeRound = round.flatMap(Round.findById).filter(_.contestId == contestId)
    val coiFiles = Option.when(coi && maybeRound.isEmpty) {
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
    else if (chosenCountry.exists(c => !countryFiles.values.exists(_ == c))) Left("error.not.found")
    else
      Right(ContestFilesView(contest.get, deletedOnly = deleted || maybeRound.isDefined, maybeRound,
        Some(region).filter(_ != "all"), chosenCountry, countryFiles, coiFiles = coiFiles))
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
    coiFiles: Option[Map[Long, CoiMembers.Member]] = None
) {

  /** The list is the conflicts of interest. */
  def coi: Boolean = coiFiles.isDefined

  /** The files listed, if there are any: none for a contest without a category. */
  def list: Option[ImageJdbc.FileList] =
    round.map(r => ImageJdbc.FileList.RoundDeleted(r.getId))
      .orElse(contest.categoryId.map(ImageJdbc.FileList.Category(_, deletedOnly, countryFilter)))

  /** The chosen country's files ([[ImageJdbc.CountryFilter]]), or the conflicts of interest. */
  def countryFilter: Option[ImageJdbc.CountryFilter] =
    coiFiles.map(files => ImageJdbc.CountryFilter(files.keys.toSeq.sorted))
      .orElse(country.map(c => ImageJdbc.CountryFilter(countryFiles.collect { case (pageId, `c`) => pageId }.toSeq.sorted)))

  private def roundId: Option[Long] = round.flatMap(_.id)

  /** This list's page. */
  def pageUrl(page: Int): String = pageUrl(page, region.getOrElse("all"), country)

  /** This list's first page in the region, or all of it ("all"). */
  def regionUrl(region: String): String = pageUrl(1, region, country)

  /** This list's first page of the country, or all of it ("all"), in all regions. */
  def countryUrl(country: String): String = pageUrl(1, "all", Some(country).filter(_ != "all"))

  private def pageUrl(page: Int, region: String, country: Option[String]): String =
    routes.GalleryController.contestFiles(contest.getId, page, deletedOnly, roundId, region, country, coi).url

  /** The large view of a file in this list. */
  def largeUrl(pageId: Long): String =
    routes.GalleryController.contestFileLarge(contest.getId, pageId, deletedOnly, roundId, region.getOrElse("all"),
      country, coi).url

  /** A contest of monuments, whose files are in regions. */
  def byMonuments: Boolean = ContestKind.monumentIdTemplate(contest).isDefined

  /** The region menu of a contest of monuments, in the navbar: of the conflicts of interest
    * too, their regions counted.
    */
  def regionMenu(implicit messages: play.api.i18n.Messages): Option[RegionMenu] =
    Option.when(byMonuments)(RegionMenu.regions(region.map(RegionScheme.of(contest).region(_, None).name),
      regionUrl("all"), pageUrl(1),
      routes.GalleryController.contestFileRegions(contest.getId, deletedOnly, roundId, "all", country, coi).url))

  /** The country menu of a contest of many countries (not of a round's files), in the navbar. */
  def countryMenu: Option[RegionMenu] =
    Option.when(round.isEmpty && !coi && ContestCountries.ofManyCountries(contest))(RegionMenu.countries(country,
      countryUrl("all"), pageUrl(1),
      routes.GalleryController.contestFileCountries(contest.getId, deletedOnly, roundId, region.getOrElse("all"),
        country).url))
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

class RateDistribution(rateMap: Map[Int, Int]) {

  def unrated: Int = rateMap.getOrElse(0, 0)

  def selected: Int = rateMap.getOrElse(1, 0)

  def rejected: Int = rateMap.getOrElse(-1, 0)

  def all: Int = rateMap.values.sum

  def positive: Int = all - unrated - rejected

  def rated: Int = all - unrated

}
