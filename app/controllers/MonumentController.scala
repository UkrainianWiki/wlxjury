package controllers

import db.scalikejdbc.MonumentJdbc
import org.scalawiki.wlx.MonumentDB
import org.scalawiki.wlx.dto.{Contest, ContestType, Monument, SpecialNomination}
import org.scalawiki.wlx.query.MonumentQuery
import org.scalawiki.wlx.stat.ContestStat
import play.api.i18n.I18nSupport
import play.api.mvc.{ControllerComponents, EssentialAction}

import javax.inject.Inject

/** The monument lists, for signed-in users. */
class MonumentController @Inject()(cc: ControllerComponents)
    extends Secured(cc)
    with I18nSupport {

  def list: EssentialAction = withAuth() { _ => implicit request =>
    val monuments = MonumentJdbc.findAll(Some(20))
    Ok(views.html.monuments(monuments))
  }

  def byId(id: String): EssentialAction = withAuth() { _ => implicit request =>
    val monuments = MonumentJdbc.find(id).toSeq
    Ok(views.html.monuments(monuments))
  }


}
