package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import org.intracer.wmua.ContestJury
import org.scalawiki.MwBot
import org.scalawiki.wlx.CampaignList
import org.scalawiki.wlx.dto.{Contest, ContestType, Country, NoAdmDivision}
import services.regions.RegionScheme
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, RequestHeader}
import services.ContestService

import javax.inject.Inject
import scala.concurrent.Future

class ContestController @Inject() (
    val commons: MwBot,
    cc: ControllerComponents,
    contestService: ContestService
) extends Secured(cc)
    with I18nSupport {

  def fetchContests(
      contestType: Option[String],
      year: Option[Int],
      country: Option[String]
  ): Future[Iterable[Contest]] = {
    // CampaignList's bot is scalawiki's default commons one: make sure it is the anonymous one
    services.imports.AnonymousBots.seed()
    if (contestType.isEmpty) {
      CampaignList.yearsContests()
    } else {
      (for (ct <- contestType; y <- year) yield {
        val contest = Contest(ContestType.byCode(ct).get, NoAdmDivision, y)
        CampaignList.contestsFromCategory(contest.imagesCategory)
      }).getOrElse(Future.successful(Nil))
    }
  }

  /** The contests page, root's: the contests of the Type, Country, Year and Order menus
    * ([[ContestsFilter]]; by default those of the latest year of the contests, oldest first).
    */
  def list(contestType: Option[String], year: Option[String], country: Option[String], order: Option[String]) =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val bar = listBar(ContestsFilter.of(contestType, year, country, order))

      Ok(
        views.html.contests(
          user,
          bar.shown,
          editContestForm,
          importContestsForm,
          Some(bar)
        )
      )
    }

  /** The contests page's list and menus with this filter, the countries named in the request's language. */
  private def listBar(filter: ContestsFilter)(implicit request: RequestHeader): ContestsFilterBar =
    ContestsFilterBar(filter, f => f.addTo(routes.ContestController.list().url), contestService.findContests(),
      request2Messages(request).lang.locale)

  /** Adds a contest; then the list in the new contest's year, with the type, country and order the
    * add form carried (ContestsFilter.fromForm) where the new contest is of them, so it is listed.
    */
  def saveContest() = withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
    val form = request.body.asFormUrlEncoded.getOrElse(Map.empty)
    val carried = ContestsFilter.fromForm(name => form.get(name).flatMap(_.headOption))
    editContestForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          val bar = listBar(carried)
          BadRequest(
            views.html.contests(
              user,
              bar.shown,
              formWithErrors,
              importContestsForm,
              Some(bar)
            )
          )
        },
        formContest => {
          val added = contestService.createContest(formContest)
          Redirect(ContestsFilter.afterAdd(carried, added).addTo(routes.ContestController.list().url))
        }
      )
  }

  def importContests() = withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
    importContestsForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          val bar = listBar(ContestsFilter.Default)
          BadRequest(
            views.html.contests(
              user,
              bar.shown,
              editContestForm,
              formWithErrors,
              Some(bar)
            )
          )
        },
        formContest => {
          // the imported contests' year if they share one, else all years, so they are listed
          val imported = contestService.importContests(formContest)
          Redirect(ContestsFilter.afterImport(imported).addTo(routes.ContestController.list().url))
        }
      )
  }

  /** The round form uses the same campaign/country resolution as the gallery's [[RegionScheme]]. */
  def regions(contestId: Long): Map[String, String] = {
    ContestJuryJdbc
      .findById(contestId)
      .filter(c => RegionScheme.of(c).country.exists(_.code == Country.Ukraine.code))
      .map(_ => KOATUU.regions)
      .getOrElse(Map.empty)
  }

  val editContestForm = Form(
    mapping(
      "id" -> optional(longNumber),
      "name" -> nonEmptyText,
      "year" -> number,
      "country" -> nonEmptyText,
      "images" -> optional(text),
      "currentRound" -> optional(longNumber),
      "monumentIdTemplate" -> optional(text),
      "greetingText" -> optional(text),
      "useGreeting" -> boolean,
      "campaign" -> optional(text)
    )(
      (
          id,
          name,
          year,
          country,
          images,
          currentRound,
          monumentIdTemplate,
          greetingText,
          useGreeting,
          campaign
      ) =>
        ContestJury(
          id,
          name,
          year,
          country,
          images,
          None,
          currentRound,
          monumentIdTemplate,
          Greeting(greetingText, useGreeting),
          campaign
        )
    )((c: ContestJury) =>
      Some(
        c.id,
        c.name,
        c.year,
        c.country,
        c.images,
        c.currentRound,
        c.monumentIdTemplate,
        c.greeting.text,
        c.greeting.use,
        c.campaign
      )
    )
  )

  val importContestsForm = Form(
    single(
      "source" -> nonEmptyText
    )
  )
}
