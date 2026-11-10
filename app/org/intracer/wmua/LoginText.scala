package org.intracer.wmua

/** A login, email or wiki account name as an admin or user typed or pasted it: from
  * spreadsheets and messengers it often comes with a leading space, a tab or an invisible
  * non-breaking space, which Java's `trim` (only chars up to U+0020) leaves.
  *
  * Not for passwords: [[services.PasswordHasher.normalize]] keeps its ASCII trim, the input
  * of every existing hash.
  */
object LoginText {

  /** Invisible characters that are neither whitespace nor space separators: the next-line
    * control U+0085, the zero-width no-break space U+FEFF (a byte order mark), zero-width space
    * U+200B, the left-to-right and right-to-left marks U+200E and U+200F, and the word joiner U+2060.
    */
  private val Invisible: Set[Char] = Set('\u0085', '\uFEFF', '\u200B', '\u200E', '\u200F', '\u2060')

  /** What `String.trim` strips (any char up to U+0020, control chars included), whitespace and
    * space separators (`\s`, tab, U+00A0, U+2007, U+202F, ...), or [[Invisible]].
    */
  def isSpace(c: Char): Boolean =
    c <= ' ' || Character.isWhitespace(c) || Character.isSpaceChar(c) || Invisible.contains(c)

  /** `s` without leading and trailing [[isSpace]] characters; inner ones are kept. Null: "". */
  def clean(s: String): String =
    if (s == null) ""
    else {
      var start = 0
      var end   = s.length
      while (start < end && isSpace(s.charAt(start))) start += 1
      while (end > start && isSpace(s.charAt(end - 1))) end -= 1
      s.substring(start, end)
    }

  /** An email (or a login, which may be one) as accounts are looked up by: cleaned, lowercased. */
  def email(s: String): String = clean(s).toLowerCase
}
