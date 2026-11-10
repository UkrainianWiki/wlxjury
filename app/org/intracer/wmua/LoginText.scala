package org.intracer.wmua

/** A login, email or wiki account name as an admin or user typed or pasted it: from
  * spreadsheets and messengers it often comes with a leading space, a tab or an invisible
  * non-breaking space, which Java's `trim` (only chars up to U+0020) leaves.
  *
  * Not for passwords: [[services.PasswordHasher.normalize]] keeps its ASCII trim, the input
  * of every existing hash.
  */
object LoginText {

  /** Whitespace and space separators (`\s`, tab, U+00A0, U+2007, U+202F, …), plus the
    * zero-width no-break space U+FEFF (a byte order mark) and zero-width space U+200B, which
    * are invisible and are neither.
    */
  def isSpace(c: Char): Boolean =
    Character.isWhitespace(c) || Character.isSpaceChar(c) || c == '﻿' || c == '​'

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
