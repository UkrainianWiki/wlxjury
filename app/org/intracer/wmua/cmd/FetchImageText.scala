package org.intracer.wmua.cmd

import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.MwBot
import org.scalawiki.dto.Page
import org.scalawiki.wikitext.TemplateParser
import org.scalawiki.dto.Namespace

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import FetchImageText._

case class FetchImageText(source: String,
                          contest: ContestJury,
                          monumentIdTemplate: Option[String],
                          commons: MwBot,
                          max: Long = 0L) {

  def generatorParams: (String, String) = {
    if (source.toLowerCase.startsWith("category:")) {
      ("categorymembers", "cm")
    } else if (source.toLowerCase.startsWith("template:")) {
      ("embeddedin", "ei")
    }
    else {
      ("images", "im")
    }
  }

  def apply(): Future[Seq[Image]] = {
    val (generator, prefix) = generatorParams
    val future = revisionsByGenerator(source, generator, prefix,
      Set(Namespace.FILE), Set("content", "timestamp", "user", "comment"), limit = "50")

    future.map { pages =>
      val images = pages.sortBy(_.id).map { page =>
        Image(page.id.get, page.title, None, None, 0, 0, None, None)
      }

      val ids: Seq[String] = monumentIdTemplate.fold(Array.fill(pages.size)("").toSeq)(t => monumentIds(pages, t))
      val authorsSeq: Seq[String] = authors(pages)
      val descrs: Seq[String] = descriptions(pages)
      images.zip(ids).map {
        case (image, id) => image.copy(monumentId = Some(id).filterNot(_.trim.isEmpty))
      }.zip(descrs).map {
        case (image, descr) => image.copy(description = Some(descr))
      }.zip(authorsSeq).map {
        case (image, author) => image.copy(author = Some(author.take(256)).filterNot(_.trim.isEmpty))
      }
    }
  }

  def monumentIds(pages: Seq[Page], monumentIdTemplate: String): Seq[String] = {
    pages.sortBy(_.id).map { page =>
        page.text.flatMap(text => defaultParam(text, monumentIdTemplate))
          .map(id => id.take(100)).getOrElse("")
    }
  }

  def descriptions(pages: Seq[Page], existingPageIds: Set[Long] = Set.empty): Seq[String] = {
    pages.sortBy(_.id).filterNot(i => existingPageIds.contains(i.id.get)).map { page =>
        page.text.flatMap(text => namedParam(text, "Information", "description")).getOrElse("")
    }
  }

  def authors(pages: Seq[Page]): Seq[String] = {
    pages.sortBy(_.id).map { page =>
      page.text.flatMap(text => namedParam(text, "Information", "author")).getOrElse("")
    }
  }

  def revisionsByGenerator(source: String,
                           generator: String,
                           generatorPrefix: String,
                           namespaces: Set[Int],
                           props: Set[String],
                           limit: String): Future[Seq[Page]] = {

    commons.page(source)
      .withContext(Map("contestId" -> contest.id.getOrElse(0).toString, "max" -> max.toString))
      .revisionsByGenerator(generator, generatorPrefix,
        namespaces, Set("content", "timestamp", "user", "comment"), limit = "50", titlePrefix = None
      ).map(_.toSeq)
  }
}

object FetchImageText {

  def defaultParam(text: String, templateName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt("1"))

  /**
    * Get value of a template parameter
    *
    * @param text         page text, that can contain a template with parameter
    * @param templateName template name
    * @param paramName    template parameter name
    * @return optional value of template parameter
    */
  def namedParam(text: String, templateName: String, paramName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt(paramName))

  /** The image with what its page's text gives, as [[FetchImageText.apply]] and
    * [[ImageEnricher]] set them for one page: the monument id (the template's first
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

  /** The monument id that "Update monuments" sets from a file's text: the template's
    * first parameter, if it is a valid id.
    */
  def validMonumentId(text: String, monumentIdTemplate: String): Option[String] =
    defaultParam(text, monumentIdTemplate).filter(_.matches("""(\d\d)-(\d\d\d)-(\d\d\d\d)"""))
}