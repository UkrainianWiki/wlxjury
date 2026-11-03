package views

import play.api.i18n.Messages
import play.api.libs.json.{JsObject, JsString}
import play.twirl.api.Html

/** Localised texts for a page's script, as a JSON object: in a `<script type="application/json">`
  * data block (editRound), the strings JSON-escaped, not HTML-escaped (the block's text is read
  * as it is), and every `<` written as its JSON unicode escape, so no text ends the element; or,
  * as a String, in an attribute, which Twirl escapes (`data-multi-select`, monuments).
  */
object JsTexts {

  private val Backslash = 92.toChar.toString
  private val EscapedLt = Backslash + "u003c"

  def apply(texts: (String, String)*): Html =
    Html(JsObject(texts.map { case (k, v) => k -> JsString(v) }).toString.replace("<", EscapedLt))

  /** multi-select.js's texts, bootstrap-multiselect's before. */
  def multiSelect(implicit messages: Messages): Html = apply(
    "selectAll" -> Messages("select.all"),
    "none"      -> Messages("none.selected"),
    "n"         -> Messages("selected"),
    "all"       -> Messages("all.selected")
  )
}
