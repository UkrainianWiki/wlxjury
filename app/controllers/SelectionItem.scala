package controllers

import play.api.i18n.Messages
import play.api.i18n.Lang
import play.twirl.api.{Html, HtmlFormat}

/** A navbar menu's item (dropDownSelection.scala.html). `count`: its count, muted at its right edge
  * as the Votes menu's (navbar.scala.html itemCount); `itemClass`: classes of its own (an inactive
  * round's wlx-round-inactive); `after`: markup after its text (a round's badges,
  * snippets/roundBadges).
  */
case class SelectionItem(text: String,
                         icon: Option[String] = None,
                         localized: Boolean = true,
                         count: Option[Int] = None,
                         itemClass: String = "",
                         after: Html = HtmlFormat.empty) {

  def localizedText(implicit messages: Messages): String = {
    if (localized) {
      Messages(text)
    } else {
      text
    }
  }
}
