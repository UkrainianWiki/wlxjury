package services.imports

import db.scalikejdbc.ImageJdbc
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.dto.Page
import org.scalawiki.wikitext.TemplateParser

/** What an import reads from a file's page text. */
object ImageText {

  /** The first parameter of the template on the page. */
  def defaultParam(text: String, templateName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt("1"))

  /** The parameter of the template on the page. */
  def namedParam(text: String, templateName: String, paramName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt(paramName))

  /** The image with what its page's text gives: the monument id (the template's first
    * parameter), the description and the author of {{Information}}.
    */
  def withText(image: Image, text: Option[String], monumentIdTemplate: Option[String]): Image = {
    val monumentId = monumentIdTemplate.flatMap(t => text.flatMap(defaultParam(_, t))).map(_.take(100))
    image.copy(
      monumentId = monumentId.filterNot(_.trim.isEmpty),
      description = Some(text.flatMap(namedParam(_, "Information", "description")).getOrElse("")),
      author = text.flatMap(namedParam(_, "Information", "author")).map(_.take(256)).filterNot(_.trim.isEmpty)
    )
  }

  /** The files among the pages, as the contest's images by page id: with what their text
    * gives ([[withText]]) when the contest has a monument id template (the pages were
    * fetched with their text then).
    */
  def images(pages: Seq[Page], contest: ContestJury, monumentIdTemplate: Option[String]): Seq[Image] =
    pages
      .flatMap { page =>
        ImageJdbc.fromPage(page, contest).map { image =>
          if (monumentIdTemplate.isDefined) withText(image, page.text, monumentIdTemplate)
          else image
        }
      }
      .sortBy(_.pageId)

  /** The monument id that "Update monuments" sets from a file's text: the template's
    * first parameter, if it is a valid id.
    */
  def validMonumentId(text: String, monumentIdTemplate: String): Option[String] =
    defaultParam(text, monumentIdTemplate).filter(_.matches(MonumentListImportHandler.ValidId))
}
