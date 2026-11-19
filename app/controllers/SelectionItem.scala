package controllers

import play.api.i18n.Messages
import play.api.i18n.Lang
import play.twirl.api.{Html, HtmlFormat}

/** A navbar menu's item (dropDownSelection.scala.html). `count`: its count, muted at its right edge
  * as the Votes menu's (navbar.scala.html itemCount); `itemClass`: classes of its own (an inactive
  * round's wlx-round-inactive); `before`, `after`: markup before and after its text (a round's
  * type icons, snippets/roundIcon, and what they say for screen readers, snippets/roundSpoken).
  */
case class SelectionItem(text: String,
                         icon: Option[String] = None,
                         localized: Boolean = true,
                         count: Option[Int] = None,
                         itemClass: String = "",
                         before: Html = HtmlFormat.empty,
                         after: Html = HtmlFormat.empty) {

  def localizedText(implicit messages: Messages): String = {
    if (localized) {
      Messages(text)
    } else {
      text
    }
  }
}
