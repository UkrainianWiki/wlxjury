package controllers

import db.scalikejdbc.{ContestJuryJdbc, User}
import org.intracer.wmua.ContestJury
import org.scalawiki.MwBot
import org.scalawiki.wlx.CampaignList
import org.scalawiki.wlx.dto.{Contest, ContestType, NoAdmDivision}
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.I18nSupport
import play.api.mvc.ControllerComponents
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
    if (contestType.isEmpty) {
      CampaignList.yearsContests()
    } else {
      (for (ct <- contestType; y <- year) yield {
        val contest = Contest(ContestType.byCode(ct).get, NoAdmDivision, y)
        CampaignList.contestsFromCategory(contest.imagesCategory)
      }).getOrElse(Future.successful(Nil))
    }
  }

  def list(contestType: Option[String], year: Option[Int], country: Option[String]) =
    withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
      val contests = contestService.findContests()
      val filtered = contests.filter { c =>
        contestType.forall(c.name.equals) && year.forall(c.year.equals)
      }

      Ok(
        views.html.contests(
          user,
          contests,
          filtered,
          editContestForm,
          importContestsForm,
          contestType,
          year
        )
      )
    }

  def saveContest() = withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
    editContestForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          BadRequest(
            views.html.contests(
              user,
              contestService.findContests(),
              Seq.empty,
              formWithErrors,
              importContestsForm
            )
          )
        },
        formContest => {
          contestService.createContest(formContest)
          Redirect(routes.ContestController.list())
        }
      )
  }

  def importContests() = withAuth(rolePermission(Set(User.ROOT_ROLE))) { user => implicit request =>
    importContestsForm
      .bindFromRequest()
      .fold(
        formWithErrors => {
          BadRequest(
            views.html.contests(
              user,
              contestService.findContests(),
              Seq.empty,
              editContestForm,
              formWithErrors
            )
          )
        },
        formContest => {
          contestService.importContests(formContest)
          Redirect(routes.ContestController.list())
        }
      )
  }

  def regions(contestId: Long): Map[String, String] = {
    ContestJuryJdbc
      .findById(contestId)
      .filter(_.country == "Ukraine")
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
