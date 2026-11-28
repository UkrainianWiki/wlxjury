package controllers

import db.scalikejdbc.{ContestJuryJdbc, ImageJdbc, MonumentJdbc, Round, RoundImageJdbc, RoundStatLinks, User}
import modules.BlockingExecutionContext
import org.intracer.wmua.{CommentJdbc, ContestJury}
import play.api.data.Form
import play.api.data.Forms.{boolean, mapping, number, optional, seq, text}
import play.api.i18n.Messages
import play.api.libs.json.Json
import play.api.mvc.{AnyContent, ControllerComponents, EssentialAction, Request, Result}
import services.RoundService
import services.distribution.RoundDistributionHandler
import services.jobs.{Job, JobJdbc}
import services.places.{Place, PlaceScheme}

import javax.inject.Inject

/** A round's review before its files go to its jurors (P3 of
  * docs/plans/2026-11-02-round-images-two-step.md, §6 "UI"; P3-3 of
  * docs/plans/2026-11-16-round-editing.md): the round's files (`round_image`) laid out as the
  * contest files page ([[ImageJdbc.FileList.RoundImages]]: tiles, pager, place menu, large view),
  * by status (in, deleted on Commons, disqualified), with a summary; and its **jurors form**
  * ([[jurors]], the maintainer's decision of 2026-11-16, superseding D4's checkbox), where its
  * admins choose the jurors and the file distribution and Distribute.
  *
  * The place menu is the contest's one level of places ([[PlaceScheme]]): an international
  * contest's countries, a national one's regions, each counted in the status tab shown, so each
  * country's (region's) number of files can be checked before distributing.
  *
  * Authorization (D20): the pages for the round's contest's admins and organizers (organizers read
  * only) and root, the round checked in its own contest ([[Secured.inContest]]), 404 if there is
  * none; the jurors form and Distribute for its admins and root, Distribute a POST with the CSRF
  * token, never switching the session's contest, refused unless the round is in review
  * ([[Round.inReview]]) with no job. Jurors never see a round in review: it is `distributing`,
  * hidden from them everywhere. Staff comment on its files in the review's large view
  * ([[ImageDiscussionController.addComment]]): ordinary round comments.
  *
  * The jurors form: the round form's juror picker and "file distribution" select
  * ([[distributeForm]]), the round's jurors chosen (else the contest's), saved with the distribute
  * under the round's lock ([[RoundService.distributeReviewed]]); read only once the round has a
  * vote (a partial distribution). It shows the number of images that passed the filters, and per
  * place. While the round's fill runs it shows the job, and the page reloads as it ends: a new
  * round's jurors are chosen here, step 3 of its setup. A POST without the picker distributes to the round's
  * jurors, refused if it has none. The previous rounds' warning (D18, with its confirmation) and
  * an unfinished re-apply show there too.
  */
class RoundReviewController @Inject() (
    cc: ControllerComponents,
    blocking: BlockingExecutionContext,
    roundsService: RoundService
) extends Secured(cc)
    with play.api.i18n.I18nSupport {

  import RoundReviewController._

  private def mayView(user: User, round: Round): Boolean =
    contestPermission(ViewRoles, Some(round.contestId))(user)

  private def mayDistribute(user: User, round: Round): Boolean = Secured.administersRound(user, round)

  /** The review page: the round's files of `status`, a page of them, in a region or of a
    * country, or all.
    */
  def review(id: Long, status: String, page: Int, region: String, country: Option[String]): EssentialAction =
    withAuthOn(blocking)(rolePermission(ViewRoles, derived = true)) { sessionUser => implicit request =>
      withRound(sessionUser, id) { (user, round) =>
        reviewView(round, status, region, country).fold(notFound(user, _), { view =>
          val counts = RoundImageJdbc.statusCounts(round.getId)
          val list = view.list.get
          val status = view.reviewStatus.get
          // the pager's total: of the place chosen, as its menu counts it (placeCounts), else the tab's
          val listCount = (list, view.region) match {
            case (ImageJdbc.FileList.RoundImages(roundId, st, country @ Some(_)), _) => ImageJdbc.roundImageCount(roundId, st, country)
            case (_, Some(r)) => ImageJdbc.regionCounts(list).getOrElse(r, 0)
            case _            => counts.getOrElse(status, 0)
          }
          val pager = Pager(math.max(page, 1), listCount)
          val files = ImageJdbc.listFiles(list, view.region, pager.pageSize, pager.offset)
          val job = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType,
            Seq(round.getId)).get(round.getId)
          val lastFill = job.filter(j => j.isDone && RoundDistributionHandler.fillOnly(j))
          // D18's check reads the previous rounds' votes: once per page view, on the review's main view only
          // (its first page of the round's files in all places), not on every page or place of the list
          val mainView = page <= 1 && region == "all" && country.isEmpty && status == RoundImageJdbc.Status.In
          val nextStep = RoundNextStep.of(round, job, counts.getOrElse(RoundImageJdbc.Status.In, 0))
          val summary = RoundReview(
            round = round,
            statusCounts = counts,
            addedHow = RoundImageJdbc.addedHowCounts(round.getId),
            conflicts = RoundStatLinks.counts(round).conflicts,
            job = job,
            jobTitle = job.map(roundsService.jobTitle),
            mayDistribute = mayDistribute(user, round) && round.inReview && !job.exists(_.isActive),
            mayEdit = mayDistribute(user, round),
            roundJurors = User.distributionJurors(round.getId).sortBy(_.getId),
            prevChanged = mainView && round.inReview && RoundService.prevChanged(round),
            reapplied = lastFill.filter(RoundDistributionHandler.reapplyMode).map { j =>
              val st = RoundDistributionHandler.stateOf(j)
              ReapplyDiff(st.imagesAdded, st.removedTotal, st.removedTitles)
            },
            // a re-apply failed or cancelled: its filters saved, its files half chosen (Distribute refuses)
            reapplyUnfinished = reapplyUnfinished(round, job),
            // one read of the contest's rounds
            links = services.RoundLineage.links(round, Round.findByContest(round.contestId)).visibleTo(user),
            // being set up: its next step, and Distribute's state (#186)
            nextStep = nextStep,
            // step 2's heading once the matching has ended: the contest's files (a count over its
            // category), read only then, not on each view while the files are being matched
            contestFiles = if (nextStep.exists(_.state != RoundNextStep.Matching)) ImageJdbc.countByContest(view.contest) else 0L
          )
          Secured.switchingContest(sessionUser, user)(
            Ok(views.html.contestFiles(user, files, pager, view, Some(summary))))
        })
      }
    }

  /** The review list's places, the contest's one level of them ([[PlaceScheme]]: countries or
    * regions), with its files of `status` in each: the place menu's (region-menu.js), `region` or
    * `country` the place shown. For those who may see the review page (a juror gets JSON 403); a
    * script request, which never switches the session's contest.
    */
  def regions(id: Long, status: String, region: String, country: Option[String]): EssentialAction =
    withAuthJsonOn(blocking)(rolePermission(ViewRoles, derived = true)) { sessionUser => implicit request =>
      Round.findById(id) match {
        case None => NotFound(Json.obj("error" -> "round_not_found"))
        case Some(round) =>
          val user = Secured.inContest(sessionUser, round.contestId)
          if (!mayView(user, round)) onUnAuthorizedJson(sessionUser, request, Secured.Denial.round(round, ViewRoles))
          else reviewView(round, status, "all", None).fold(_ => NotFound(Json.obj("error" -> "not_found")), { view =>
            val messages: Messages = messagesApi.preferred(request)
            placeCounts(view.contest, round, view.reviewStatus.get, view.countries).fold(
              Ok(PlaceMenu.json(None, messages("region.all"), view.regionUrl("all"), Nil, view.regionUrl))) {
              case (scheme, counts) =>
                val places = scheme.places(counts.map { case (place, n) => place -> Some(n) })(messages)
                scheme match {
                  case _: PlaceScheme.Countries =>
                    Ok(PlaceMenu.json(country, messages("country.all"), view.countryUrl("all"), places, view.countryUrl))
                  case _ =>
                    Ok(PlaceMenu.json(Some(region).filter(_ != "all"), messages("region.all"), view.regionUrl("all"),
                      places, view.regionUrl))
                }
            }
          })
      }
    }

  /** The large view of a file of the review list, among its neighbours: read only, but for its
    * comments, which its staff may write ([[ImageDiscussionController.addComment]]).
    */
  def file(id: Long, pageId: Long, status: String, region: String, country: Option[String]): EssentialAction =
    withAuthOn(blocking)(rolePermission(ViewRoles, derived = true)) { sessionUser => implicit request =>
      withRound(sessionUser, id) { (user, round) =>
        reviewView(round, status, region, country).fold(notFound(user, _), { view =>
          val list = view.list.get
          val offset = math.max(0, ImageJdbc.fileRank(list, view.region, pageId) - Neighbourhood.Side)
          val read = ImageJdbc.listFiles(list, view.region, Neighbourhood.Side + 1 + Neighbourhood.Following, offset)
          val files = read.take(Neighbourhood.Window)
          val index = files.indexWhere(_.image.pageId == pageId)
          if (index < 0) Redirect(view.pageUrl(1))
          else {
            val monument = files(index).image.monumentId.filter(_.trim.nonEmpty).flatMap(MonumentJdbc.find(_))
            val following = read.drop(index + 1).take(Neighbourhood.Following)
            // the file's comments in the contest, as a round's large view shows them, with the form
            val comments = CommentJdbc.findBySubjectAndContest(pageId, round.contestId)
            val otherRounds = comments.map(_.roundId).filter(_ != round.getId).distinct
            val commentRounds =
              if (otherRounds.isEmpty) Map.empty[Long, String]
              else Round.findByIds(round.contestId, otherRounds).map(r => r.getId -> r.description).toMap
            Secured.switchingContest(sessionUser, user)(
              Ok(views.html.large.contestFile(user, view, files, index, following, Pager.pageOf(offset + index), monument,
                Some(ReviewComments(comments, commentRounds, ImageDiscussionController.mayCommentInReview(user, round))))))
          }
        })
      }
    }

  /** The jurors form of a round in review: its jurors and file distribution, Distribute. For the
    * round's contest's admins and root (an organizer gets the 403 page). While the round's fill
    * runs, its job's status (the page reloads as it ends); once the round left review, the state
    * and the way to its review page, without the form; a creation that failed, was cancelled or didn't
    * finish (no job: its round and job are written in one transaction), its state as the Rounds page says
    * it. `waited` is ignored: the old reload count, kept so that its links still open the page.
    */
  def jurors(id: Long, waited: Int): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES, derived = true)) { sessionUser => implicit request =>
      Round.findById(id) match {
        case None => notFound(sessionUser, "error.round.not.found")
        case Some(round) =>
          val user = Secured.inContest(sessionUser, round.contestId)
          if (!mayDistribute(user, round)) onUnAuthorized(sessionUser, request, Secured.Denial.round(round, User.ADMIN_ROLES))
          else ContestJuryJdbc.findById(round.contestId).fold(notFound(user, "error.contest.not.found")) { contest =>
            Secured.switchingContest(sessionUser, user)(
              Ok(views.html.round.jurorsForm(user, jurorsView(contest, round))))
          }
      }
    }

  /** The jurors form's figures and choices, as the page shows them. */
  private def jurorsView(contest: ContestJury, round: Round)(implicit messages: Messages): JurorsFormView = {
    val job = RoundService.latestJob(round)
    // its state as the Rounds page says it (RoundStartBlock): its images being selected (a fill held for
    // review running: the page shows the job and reloads as it ends), or, neither that nor in review,
    // being distributed or its creation failed, cancelled or not finished
    val block = services.distribution.RoundStartBlock.of(round, job)
    val filling = block.contains(services.distribution.RoundStartBlock.Selecting)
    val ready = round.inReview && !job.exists(_.isActive)
    val inCount = if (ready) RoundImageJdbc.statusCounts(round.getId).getOrElse(RoundImageJdbc.Status.In, 0) else 0
    val criterionIds = db.scalikejdbc.RoundCriterion.idsOf(round.getId)
    val roundJurors = User.distributionJurors(round.getId).sortBy(_.getId)
    val contestJurors = if (ready) User.loadJurors(round.contestId).filter(_.deletedAt.isEmpty) else Nil
    val chosen = if (roundJurors.nonEmpty) roundJurors.flatMap(_.id) else contestJurors.flatMap(_.id)
    // the images that passed the filters, and per place: only once the fill ended
    val places = if (!ready) None else placeCounts(contest, round, RoundImageJdbc.Status.In, None).map {
      case (scheme, counts) => (scheme, scheme.places(counts.map { case (p, n) => p -> Some(n) }))
    }
    JurorsFormView(
      round = round,
      job = job,
      jobTitle = job.map(roundsService.jobTitle),
      filling = filling,
      ready = ready,
      inCount = inCount,
      placeLabel = places.map { case (scheme, _) => PlaceMenu.placeLabel(scheme) },
      places = places.fold(Seq.empty[Place])(_._2),
      roundJurors = roundJurors,
      contestJurors = contestJurors,
      jurorsEditable = ready && !RoundImageJdbc.hasVotes(round.getId),
      ratesEditable = ready && !RoundImageJdbc.hasVotes(round.getId) &&
        !RoundController.takenFrom(round.getId, Round.findByContest(round.contestId)),
      prevChanged = ready && RoundService.prevChanged(round),
      reapplyUnfinished = reapplyUnfinished(round, job),
      form = distributeForm.fill(DistributeChoice(jurorsShown = true, chosen.map(_.toString),
        Some(round.distribution), confirmPrevChanged = false, jury = Some(RoundService.jurySignature(round.getId)),
        rates = Some(round.rates.id), averageRule = Some(round.averageRule.param),
        criteria = criterionIds.map(_.toString),
        criteriaShown = true, worstMark = round.worstMark, worstMarkShown = true,
        ratingSignature = Some(RoundService.ratingSignature(round, criterionIds)))),
      criteria = db.scalikejdbc.CriterionJdbc.all(),
      scoreRuleWarning = RoundController.ScoreRuleWarning.of(round.getId, Round.findByContest(round.contestId)),
      // being set up: step 3's heading and the step bar
      nextStep = RoundNextStep.of(round, job, inCount),
      block = block
    )
  }

  /** A re-apply failed or cancelled: its filters saved, its files half chosen (Distribute refuses). */
  private def reapplyUnfinished(round: Round, job: Option[Job]): Boolean =
    round.inReview && job.exists(j => RoundDistributionHandler.reapplyMode(j) && !j.isDone && !j.isActive)

  /** Distribute: the round's files to its jurors, as a job (`round_distribution`, mode
    * `distribute`); the round opens to them when it ends. Started: to the review page, which
    * shows its progress; refused: back to the jurors form, saying why (also a POST without the
    * picker: the form of a round whose jurors are fixed, or an older rounds list's button).
    */
  def distribute(id: Long): EssentialAction =
    withAuthOn(blocking)(rolePermission(User.ADMIN_ROLES, derived = true)) { sessionUser => implicit request =>
      Round.findById(id) match {
        case None => notFound(sessionUser, "error.round.not.found")
        case Some(round) =>
          val user = Secured.inContest(sessionUser, round.contestId)
          val messages = messagesApi.preferred(request)
          // the jurors as picked (the picker shown: none picked is refused as no jurors), else the round's
          val bound = distributeForm.bindFromRequest().fold(_ => None, Some(_))
          val choice = bound.getOrElse(DistributeChoice())
          val toReview = Redirect(routes.RoundReviewController.review(round.getId))
          // a refusal: always the jurors form, which says why (with or without the picker)
          val back = Redirect(routes.RoundReviewController.jurors(round.getId, 0))
          if (!mayDistribute(user, round)) onUnAuthorized(sessionUser, request, Secured.Denial.round(round, User.ADMIN_ROLES))
          else if (bound.isEmpty) back.flashing("error" -> messages("error.invalid"))
          // no file matched: nothing to give out (the pages show Distribute disabled, RoundNextStep)
          else if (RoundNextStep.of(round, RoundService.latestJob(round), RoundNextStep.inCount(round))
              .exists(_.state == RoundNextStep.NoFiles))
            back.flashing("error" -> messages("round.next.no.files"))
          else roundsService.distributeReviewed(round.getId, user.id,
            jurors = Option.when(choice.jurorsShown)(choice.jurors.flatMap(_.toLongOption)),
            distribution = choice.distribution.filter(_ => choice.jurorsShown),
            confirmPrevChanged = choice.confirmPrevChanged,
            shownJury = choice.jury.filter(_ => choice.jurorsShown),
            rates = choice.rates.flatMap(Round.ratesById.get),
            averageRule = choice.averageRule.flatMap(org.intracer.wmua.AverageRule.parse),
            criteria = Option.when(choice.criteriaShown)(EditRound.criterionIdsOf(choice.criteria)),
            worstMark = Option.when(choice.worstMarkShown)(choice.worstMark),
            shownRating = choice.ratingSignature) match {
            case RoundService.ReviewDistribution.Started(_) =>
              toReview.flashing("success" -> messages("round.review.distribute.started", round.number))
            case RoundService.ReviewDistribution.Running(_) =>
              back.flashing("error" -> messages("round.distribute.running", round.number))
            case RoundService.ReviewDistribution.Refused(key, args) =>
              back.flashing("error" -> messages(key, args: _*))
            case RoundService.ReviewDistribution.NotInReview =>
              back.flashing("error" -> messages("round.review.distribute.not.in.review", round.number))
          }
      }
    }

  /** The round, checked in its own contest: `f` with the user in that contest; 404 for no round,
    * 403 (logged) for one the user may not view.
    */
  private def withRound(user: User, id: Long)(f: (User, Round) => Result)(implicit request: Request[AnyContent]): Result =
    Round.findById(id) match {
      case None => notFound(user, "error.round.not.found")
      case Some(round) =>
        val inRound = Secured.inContest(user, round.contestId)
        if (mayView(inRound, round)) f(inRound, round)
        else onUnAuthorized(user, request, Secured.Denial.round(round, ViewRoles))
    }

  private def notFound(user: User, key: String)(implicit request: Request[AnyContent]): Result =
    NotFound(views.html.errorPage(Some(user), "error.title", key))

  /** The review list of the round's files of `status` (one of [[Statuses]]), in the region
    * ("all": every region) or of the country (an international contest's, [[PlaceScheme]]: one
    * not among the contest's files is not found); the message key of what isn't found otherwise.
    * The contest's countries are read (cached, db.scalikejdbc.CountryFilesCache) only with a country.
    */
  private def reviewView(round: Round, status: String, region: String, country: Option[String])
      : Either[String, ContestFilesView] =
    if (!Statuses.contains(status)) Left("error.not.found")
    else
      ContestJuryJdbc.findById(round.contestId).toRight("error.contest.not.found").flatMap { contest =>
        val countries = country.filter(_ => PlaceScheme.byCountries(contest)).flatMap(_ => PlaceScheme.countries(contest))
        val chosen = country.filter(_ => countries.isDefined)
        if (chosen.exists(c => !countries.exists(_.has(c)))) Left("error.not.found")
        else Right(ContestFilesView(contest, deletedOnly = false, Some(round), Some(region).filter(_ != "all"),
          country = chosen, countries = countries, reviewStatus = Some(status)))
      }
}

object RoundReviewController {

  /** Who may open the review pages, in the round's contest (D20): its admins and organizers. */
  val ViewRoles: Set[String] = User.ADMIN_ROLES ++ User.ORG_COM_ROLES

  /** The review list's tabs: the round's files `in` it, deleted on Commons, disqualified. */
  val Statuses: Seq[String] = Seq(RoundImageJdbc.Status.In, RoundImageJdbc.Status.Deleted, RoundImageJdbc.Status.Dq)

  /** The round's files of `status` in each of the contest's places ([[PlaceScheme]]), with the
    * scheme: countries counted as every page counts them ([[PlaceScheme.Countries.counts]] of the
    * round's files, `countries` if already read), regions by the round's files' monument ids
    * ([[ImageJdbc.regionCounts]]); None for a contest of neither.
    */
  def placeCounts(contest: ContestJury, round: Round, status: String, countries: Option[PlaceScheme.Countries])
      : Option[(PlaceScheme, Map[String, Int])] =
    countries.orElse(Option.when(PlaceScheme.byCountries(contest))(PlaceScheme.countries(contest)).flatten) match {
      case Some(cs) => Some(cs -> cs.counts(RoundImageJdbc.pageIds(round.getId, status)))
      case None if PlaceScheme.byRegions(contest) =>
        Some(PlaceScheme.Regions(contest) -> ImageJdbc.regionCounts(ImageJdbc.FileList.RoundImages(round.getId, status)))
      case None => None
    }

  /** Distribute's form: the jurors form's juror picker (`jurors[]`, ids) and "file distribution"
    * select (jurors per file, 0 for all), `jurorsShown` when the page had them (a POST without, the
    * rounds list's, keeps the round's jurors), and the D18 confirmation.
    */
  val distributeForm: Form[DistributeChoice] = Form(mapping(
    "jurorsShown" -> boolean,
    "jurors" -> seq(text).verifying("error.invalid", _.size <= 10000),
    "distribution" -> optional(number(min = 0)),
    "confirmPrevChanged" -> boolean,
    // the round's jurors as the page showed them (RoundService.jurySignature)
    "jury" -> optional(text(maxLength = 20000)),
    "rates" -> optional(number).verifying("error.invalid", _.forall(Round.ratesById.contains)),
    "averageRule" -> optional(text).verifying("error.invalid", _.forall(org.intracer.wmua.AverageRule.parse(_).isDefined)),
    "criteria" -> seq(text).verifying("error.invalid", _.size <= org.intracer.wmua.Criterion.MaxEntries),
    "criteriaShown" -> boolean,
    "worstMark" -> boolean,
    "worstMarkShown" -> boolean,
    "ratingSignature" -> optional(text(maxLength = 10000))
  )(DistributeChoice.apply)(DistributeChoice.unapply))
}

/** What Distribute's form posted ([[RoundReviewController.distributeForm]]). */
final case class DistributeChoice(
    jurorsShown: Boolean = false,
    jurors: Seq[String] = Nil,
    distribution: Option[Int] = None,
    confirmPrevChanged: Boolean = false,
    jury: Option[String] = None,
    rates: Option[Int] = None,
    averageRule: Option[String] = None,
    criteria: Seq[String] = Nil,
    criteriaShown: Boolean = false,
    worstMark: Boolean = false,
    worstMarkShown: Boolean = false,
    ratingSignature: Option[String] = None
)

/** A re-apply's result (P3-5): files added, files removed (no longer passing, D16) and the first
  * of those removed.
  */
final case class ReapplyDiff(added: Long, removed: Long, removedTitles: Seq[String])

/** The review page's summary: the round's files by status and by how they were added, its
  * conflicts of interest, its latest distribution job, its jurors, and whether the viewer may
  * distribute it now (the jurors form's link).
  */
final case class RoundReview(
    round: Round,
    statusCounts: Map[String, Int],
    addedHow: Map[String, Int],
    conflicts: Int,
    job: Option[services.jobs.Job],
    /** The job's title: its status's label (snippets/jobStatus). */
    jobTitle: Option[String] = None,
    mayDistribute: Boolean,
    mayEdit: Boolean = false,
    roundJurors: Seq[User] = Nil,
    prevChanged: Boolean = false,
    reapplied: Option[ReapplyDiff] = None,
    reapplyUnfinished: Boolean = false,
    /** The rounds it was created from and those created from it, those the viewer may see. */
    links: services.RoundLineage.Links = services.RoundLineage.Links.empty,
    nextStep: Option[RoundNextStep] = None,
    /** The contest's files (step 2's heading), if it is being set up. */
    contestFiles: Long = 0L
) {
  def count(status: String): Int = statusCounts.getOrElse(status, 0)
}

/** The comments of a review list's file (its large view): the contest's on it, the names of the
  * other rounds they were written in, and whether the viewer may write one.
  */
final case class ReviewComments(comments: Seq[org.intracer.wmua.Comment], rounds: Map[Long, String], mayComment: Boolean)

/** The jurors form of a round in review ([[RoundReviewController.jurors]]).
  *
  * @param filling     the round's fill runs (the form waits for it)
  * @param ready       the round is in review with no job: the form is shown
  * @param inCount     the images that passed the filters (`in` the round)
  * @param placeLabel  the message key of the contest's places (countries, regions), if it has any
  * @param places      those images in each place, if the contest has places
  */
final case class JurorsFormView(
    round: Round,
    job: Option[Job],
    /** The job's title: its status's label (snippets/jobStatus). */
    jobTitle: Option[String] = None,
    filling: Boolean,
    ready: Boolean,
    inCount: Int,
    placeLabel: Option[String],
    places: Seq[Place],
    roundJurors: Seq[User],
    contestJurors: Seq[User],
    jurorsEditable: Boolean,
    ratesEditable: Boolean,
    criteria: Seq[org.intracer.wmua.Criterion],
    scoreRuleWarning: Option[RoundController.ScoreRuleWarning],
    prevChanged: Boolean,
    reapplyUnfinished: Boolean,
    form: Form[DistributeChoice],
    /** Being set up: its setup step (the step bar, step 3's heading). */
    nextStep: Option[RoundNextStep] = None,
    /** Its state as the Rounds page says it (None: open or stopped). */
    block: Option[services.distribution.RoundStartBlock] = None
)
