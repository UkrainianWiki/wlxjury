package services.imports

import org.scalawiki.dto.{Image => SImage}
import scalikejdbc._

/** Authors as `images.author` stores them: the account an import resolves
  * ([[ImageText.withText]], [[ImageText.withAuthors]]), never wiki markup. Files imported
  * before authors were resolved hold their page's `{{Information}}` author wikitext
  * (`[[User:X|Y]]`, a credit template `{{SergiyBobokPhotos}}`); the `author_normalize` job
  * ([[AuthorNormalizeHandler]]) stores their accounts, and [[shown]] keeps any such value
  * that is still stored from being shown raw.
  */
object StoredAuthors {

  /** What an author with markup has: a wiki link, a template, an external link. */
  private val MarkupParts = Seq("[[", "]]", "{{", "}}", "[http", "[//")

  /** Whether the stored value is wiki markup, not an account name. */
  def hasMarkup(value: String): Boolean = MarkupParts.exists(value.contains)

  /** [[hasMarkup]] in SQL, of the `images` column `author` (no index: a scan): compared
    * case-sensitively, as [[hasMarkup]] does (the column is `utf8mb4_general_ci`, where
    * `[HTTP` would match `[http`).
    */
  def markupSql(author: SQLSyntax): SQLSyntax =
    sqls"(${sqls.joinWithOr(MarkupParts.map(part => sqls"$author LIKE BINARY ${"%" + part + "%"}"): _*)})"

  /** An account an old import's encoding loss turned into question marks
    * (`[[User:???????? ????????|…]]`): `??` in it, or nothing but `?` and spaces. Not a name
    * to store: Commons still has the real one.
    */
  def isMangled(name: String): Boolean =
    name.contains("??") || (name.contains('?') && name.forall(c => c == '?' || c.isWhitespace))

  /** `Автор = Я` ("me", uk.wikipedia's `{{Зображення}}`): the uploader, as scalawiki reads it. */
  private val SelfAuthors = Set("Я", "я")

  /** Who an author parameter's wikitext names, as an import reads it from the page
    * ([[ImageText.author]]): the user it links to, a credit template as it is (resolved by
    * [[ImageText.withAuthors]]), nobody (`{{Unknown|author}}`), or the uploader.
    */
  def author(value: String): ImageText.Author = {
    val v = value.trim
    if (v.isEmpty) ImageText.Author.Unknown
    else if (SelfAuthors.contains(v)) ImageText.Author.Uploader
    else {
      val name = SImage.parseUser(v).trim
      if (name.isEmpty) ImageText.Author.Unknown else ImageText.Author.Named(name)
    }
  }

  /** A stored author as a plain account name, for display: the account of a value with
    * markup that an older import stored, without resolving a credit template (its name
    * without braces). Any other value as it is; None for none or an empty one.
    */
  def shown(stored: Option[String]): Option[String] =
    stored.map(_.trim).filter(_.nonEmpty).flatMap { value =>
      if (!hasMarkup(value)) Some(value)
      else
        author(value) match {
          case ImageText.Author.Named(name) =>
            ImageText.storedAuthor(name).flatMap { account =>
              SImage.creditTemplate(account).orElse(Some(account)).map(plain).filter(_.nonEmpty)
            }
          case _ => None
        }
    }

  /** Text without what is left of markup: brackets and braces. */
  private def plain(text: String): String =
    text.replaceAll("\\[\\[|\\]\\]|\\{\\{|\\}\\}|\\[(?:https?:)?//\\S*\\s*|\\]", "").trim
}
