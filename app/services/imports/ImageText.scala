package services.imports

import db.scalikejdbc.{CoiMembers, ImageJdbc}
import org.intracer.wmua.{ContestJury, Image}
import org.scalawiki.dto.{Namespace, Page, Image => SImage}
import org.scalawiki.wikitext.TemplateParser

/** What an import reads from a file's page text. */
object ImageText {

  /** The first parameter of the template on the page. */
  def defaultParam(text: String, templateName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt("1"))

  /** The parameter of the template on the page. */
  def namedParam(text: String, templateName: String, paramName: String): Option[String] =
    TemplateParser.parseOne(text, Some(templateName)).flatMap(_.getParamOpt(paramName))

  /** The longest author `images.author` (`varchar(255)`) holds. */
  val MaxAuthor = 255

  /** Who a file's page text says its author is. */
  sealed trait Author

  object Author {

    /** The user it links to, else the author's text without markup (`Image.parseUser`); a
      * photographer's credit template (`{{Yuriy Kvach}}`) as it is, resolved by
      * [[withAuthors]].
      */
    final case class Named(name: String) extends Author

    /** `{{Unknown|author}}` and the like: nobody, not even the uploader. */
    case object Unknown extends Author

    /** No author, or `Я` ("me", uk.wikipedia's `{{Зображення}}`): the original uploader. */
    case object Uploader extends Author
  }

  /** The author of the page text, as scalawiki reads it (`Image.fromPageRevision`):
    * `{{Information}}`'s `author`, else `{{Зображення}}`'s `Автор`.
    */
  def author(text: String): Author = {
    val page = Page(None, Some(Namespace.FILE), "File:-", revisions = Page.revisionsFromText(Some(text)))
    SImage.fromPageRevision(page, None).flatMap(_.author).map(_.trim) match {
      case None                => Author.Uploader
      case Some(a) if a.isEmpty => Author.Unknown
      case Some(a)             => Author.Named(a)
    }
  }

  /** An author as stored: normalized as MediaWiki normalizes user names
    * ([[CoiMembers.normalize]]), at most [[MaxAuthor]] characters; none if empty.
    */
  def storedAuthor(name: String): Option[String] =
    Some(CoiMembers.normalize(name).take(MaxAuthor).trim).filter(_.nonEmpty)

  /** The image with what its page's text gives: the monument id (the template's first
    * parameter), the description of {{Information}} and the author it names ([[author]]:
    * none for an unknown one or one [[withAuthors]] takes from the uploader).
    */
  def withText(image: Image, text: Option[String], monumentIdTemplate: Option[String]): Image = {
    val monumentId = monumentIdTemplate.flatMap(t => text.flatMap(defaultParam(_, t))).map(_.take(100))
    image.copy(
      monumentId = monumentId.filterNot(_.trim.isEmpty),
      description = Some(text.flatMap(namedParam(_, "Information", "description")).getOrElse("")),
      author = text.map(author).collect { case Author.Named(name) => name }.flatMap(storedAuthor)
    )
  }

  /** The files among the pages, as the contest's images by page id, with what their text
    * gives ([[withText]]) if they were fetched with it; a page without text is the file's
    * image info only.
    */
  def images(pages: Seq[Page], contest: ContestJury, monumentIdTemplate: Option[String]): Seq[Image] =
    parsed(pages, contest, monumentIdTemplate).map(_._1)

  /** [[images]] with their authors resolved as scalawiki resolves them, by up to two more
    * requests per [[CommonsSource.AuthorBatch]] files, usually none: a credit template's
    * user ([[CommonsSource.creditTemplateUsers]]), and for a file whose page names no
    * author (or a credit template that links to nobody), its original uploader
    * ([[CommonsSource.originalUploaders]]), never a bot (`Image.authorFromUploader`).
    */
  def imagesWithAuthors(
      pages: Seq[Page],
      contest: ContestJury,
      monumentIdTemplate: Option[String],
      source: CommonsSource
  ): Seq[Image] =
    withAuthors(parsed(pages, contest, monumentIdTemplate), source)

  private def parsed(pages: Seq[Page], contest: ContestJury, monumentIdTemplate: Option[String])
      : Seq[(Image, Option[Author])] =
    pages
      .flatMap { page =>
        ImageJdbc.fromPage(page, contest).map { image =>
          page.text.fold[(Image, Option[Author])](image -> None) { text =>
            withText(image, Some(text), monumentIdTemplate) -> Some(author(text))
          }
        }
      }
      .sortBy(_._1.pageId)

  /** See [[imagesWithAuthors]]: the images with what their text said of their author. */
  def withAuthors(images: Seq[(Image, Option[Author])], source: CommonsSource): Seq[Image] = {
    def templateName(name: String) = name.replace('_', ' ').trim.capitalize
    def creditTemplate(image: Image) = image.author.flatMap(SImage.creditTemplate).map(templateName)
    val templates = images.flatMap { case (image, _) => creditTemplate(image) }.distinct
    val users = if (templates.isEmpty) Map.empty[String, String] else source.creditTemplateUsers(templates)
    val credited = images.map { case (image, ref) =>
      creditTemplate(image).fold(image -> ref) { template =>
        users.get(template).fold[(Image, Option[Author])](image.copy(author = None) -> Some(Author.Uploader)) { user =>
          image.copy(author = storedAuthor(user)) -> ref
        }
      }
    }
    val unauthored = credited.collect { case (image, Some(Author.Uploader)) => image.pageId }
    val uploaders = if (unauthored.isEmpty) Map.empty[Long, String] else source.originalUploaders(unauthored)
    credited.map {
      case (image, Some(Author.Uploader)) =>
        image.copy(author = uploaders.get(image.pageId).flatMap(SImage.authorFromUploader).flatMap(storedAuthor))
      case (image, _) => image
    }
  }

  /** The monument id that "Update monuments" sets from a file's text: the template's
    * first parameter, if it is a valid id.
    */
  def validMonumentId(text: String, monumentIdTemplate: String): Option[String] =
    defaultParam(text, monumentIdTemplate).filter(_.matches(MonumentListImportHandler.ValidId))
}
