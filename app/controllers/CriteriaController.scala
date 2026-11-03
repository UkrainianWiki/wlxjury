package controllers

import db.scalikejdbc.{CriterionJdbc, User}
import org.intracer.wmua.Criterion
import play.api.Logging
import play.api.data.Form
import play.api.data.Forms._
import play.api.i18n.{I18nSupport, Messages}
import play.api.mvc.{ControllerComponents, EssentialAction}

import javax.inject.Inject

/** The global criteria catalogue (U3, V83): its list, and adding a criterion to it, which
  * every contest's round form then offers. For root and the admins of the session's contest
  * (or of `contestId`, which also picks the page's contest): the catalogue belongs to no
  * contest, so any contest's admin may add to it. Nobody changes or removes an entry here:
  * rounds of every contest and their rates refer to it. A criterion added gets a message key
  * of its own ([[Criterion.customKey]]), shown by its fallback text until translated.
  */
class CriteriaController @Inject() (cc: ControllerComponents) extends Secured(cc) with I18nSupport with Logging {

  import CriteriaController.addForm

  def list(contestId: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestId)) { user => implicit request =>
      Ok(views.html.criteriaCatalogue(user, CriterionJdbc.all(), addForm, contestId.orElse(user.currentContest)))
    }

  def add(contestId: Option[Long] = None): EssentialAction =
    withAuth(contestPermission(User.ADMIN_ROLES, contestId)) { user => implicit request =>
      val pageContest = contestId.orElse(user.currentContest)
      val catalogue = CriterionJdbc.all()
      def refused(form: Form[String]) =
        BadRequest(views.html.criteriaCatalogue(user, catalogue, form, pageContest))
      addForm.bindFromRequest().fold(
        refused,
        name =>
          if (CriteriaController.named(catalogue, name))
            refused(addForm.fill(name).withError("name", CriterionJdbc.AddRefusal.Exists.messageKey))
          else
            CriterionJdbc.add(name, user.id) match {
              case Left(refusal) => refused(addForm.fill(name).withError("name", refusal.messageKey))
              case Right(criterion) =>
                logger.info(s"Criterion ${criterion.id} added to the catalogue by user ${user.getId}")
                Redirect(routes.CriteriaController.list(contestId)).flashing("success" -> criterion.id.toString)
            }
      )
    }
}

object CriteriaController {

  /** A new criterion's name: its fallback text, trimmed. */
  val addForm: Form[String] = Form(
    single(
      "name" -> text
        .transform[String](_.trim, identity)
        .verifying("error.required", _.nonEmpty)
        .verifying("error.criteria.name.length", _.length <= Criterion.MaxNameLength)
    )
  )

  /** Whether a criterion of the catalogue has that name, as shown in the viewer's language
    * or as its fallback text, ignoring case: the round form would list two of one name.
    */
  def named(catalogue: Seq[Criterion], name: String)(implicit messages: Messages): Boolean =
    catalogue.exists(c => c.name.equalsIgnoreCase(name) || c.fallbackName.equalsIgnoreCase(name))
}
