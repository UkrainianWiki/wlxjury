package services.imports

import play.api.data.Forms.{optional, text}
import play.api.data.Mapping

/** A page, category or template title as an admin pastes it: from a wiki page's source, with the link's
  * brackets (`[[Category:Foo]]`, `[[:Category:Foo]]`, `[[Category:Foo|sort key]]`), spaces or underscores.
  * [[normalize]] reads such an input as the plain title Commons knows (`Category:Foo`); [[isLegal]] says
  * whether the result can be a title at all (no `< > [ ] | { }`, and no `#`, which [[normalize]] cuts with its fragment), so that the
  * forms refuse what is left, never passing it on to the Commons API.
  *
  * Kept small and here: reading wiki markup may move to the scalawiki library later.
  */
object WikiTitle {

  /** The message key of the form error for an input that is no title even when normalised. */
  val InvalidKey = "error.wiki.title.invalid"

  private val Illegal = Set('#', '<', '>', '[', ']', '|', '{', '}')

  /** The plain title of `raw`: the link's brackets, its leading colon, its `|label` (of a `[[link]]` only) and
    * its `#fragment` taken off,
    * underscores and runs of spaces read as one space, the namespace's colon without spaces after it
    * (`Category: Foo`). Already plain titles are unchanged; blank is the empty string.
    */
  def normalize(raw: String): String = {
    def spaced(s: String) = s.replace('_', ' ').replaceAll("""[\s\u00A0\u200E\u200F]+""", " ").trim
    var s = spaced(raw)
    // `|label` is a link's: cut only from a [[link]], a bare `|` is left for isLegal to refuse
    val link = s.startsWith("[[")
    if (link) s = s.drop(2)
    if (s.endsWith("]]")) s = s.dropRight(2)
    if (link) {
      val pipe = s.indexOf('|')
      if (pipe >= 0) s = s.take(pipe)
    }
    // `#fragment` is a link's section, not part of the title
    val hash = s.indexOf('#')
    if (hash >= 0) s = s.take(hash)
    s = spaced(s)
    if (s.startsWith(":")) s = spaced(s.drop(1))
    val colon = s.indexOf(':')
    if (colon > 0 && SourceName.namespaceOf(s).isDefined) s = spaced(s.take(colon)) + ":" + spaced(s.drop(colon + 1))
    s
  }

  /** Whether the (normalised) title can be one: no characters MediaWiki refuses in titles, no control
    * characters, at most 255 bytes. The empty string is legal here: a form field left empty is no source.
    */
  def isLegal(title: String): Boolean =
    !title.exists(c => Illegal(c) || Character.isISOControl(c)) &&
      title.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 255

  /** An optional form field of a title: normalised, blank as none, an illegal rest a field error. */
  def optionalMapping: Mapping[Option[String]] =
    optional(text)
      .transform[Option[String]](_.map(normalize).filter(_.nonEmpty), identity)
      .verifying(InvalidKey, _.forall(isLegal))

  /** A form field of a title that may be empty: normalised, an illegal rest a field error. */
  def mapping: Mapping[String] =
    text.transform[String](normalize, identity).verifying(InvalidKey, isLegal _)
}
