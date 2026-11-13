package views

import play.api.i18n.Messages

/** A file's number of comments in words, as its badge's tooltip and screen readers say it
  * ("3 comments", "3 коментарі"): the plural form of the page's language ([[Plural]]) picks
  * one of `comments.count.one`, `.few`, `.many` and `.other`.
  */
object CommentCount {

  def label(n: Int)(implicit messages: Messages): String =
    Messages(s"comments.count.${Plural.form(n, messages.lang.language)}", n)
}

/** The plural forms (CLDR's) of the app's languages for a whole number: Ukrainian and Russian
  * `one` (1, 21, 101…, not 11), `few` (2-4, 22-24…, not 12-14) and `many` (the rest); French
  * `one` for 0 and 1; English `one` for 1; `other` for the rest. Java's MessageFormat choice
  * can't tell 21 from 11, so a message with a number is keyed by its form instead.
  */
object Plural {

  val Forms: Seq[String] = Seq("one", "few", "many", "other")

  def form(n: Long, language: String): String = {
    val abs = math.abs(n)
    language match {
      case "uk" | "ru" =>
        val (mod10, mod100) = (abs % 10, abs % 100)
        if (mod10 == 1 && mod100 != 11) "one"
        else if (mod10 >= 2 && mod10 <= 4 && !(mod100 >= 12 && mod100 <= 14)) "few"
        else "many"
      case "fr" => if (abs <= 1) "one" else "other"
      case _    => if (abs == 1) "one" else "other"
    }
  }
}
