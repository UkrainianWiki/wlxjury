package views

import db.scalikejdbc.{ContestChange, RoundChanges}
import play.api.i18n.Messages
import play.api.libs.json._

/** How a round's settings entry ([[RoundChanges]]) reads on the round's statistics page and in the
  * contest's change log: each changed setting by its label, "old → new", in [[RoundChanges.Fields]]'
  * order; a setting this binary doesn't know (an entry of a later one) by its stored name. A value
  * longer than [[Shown]] characters is cut, its whole text in the item's title.
  */
object RoundChangeText {

  /** One changed setting as shown: its stored name, label, and old and new values (full and cut). */
  final case class Item(field: String, label: String, from: Option[String], to: String, fromFull: Option[String],
      toFull: String) {
    /** Whether a value is cut: the item then has a title with the whole text. */
    def cut: Boolean = from != fromFull || to != toFull
    def title: String = fromFull.fold(toFull)(f => s"$f → $toFull")
  }

  val Shown = 60

  /** The label of a setting: its message, else its stored name. */
  def label(field: String)(implicit messages: Messages): String = {
    val key = "round.change.field." + field
    if (messages.isDefinedAt(key)) messages(key) else field
  }

  /** A stored value as text: none "—", yes/no, a list comma-separated. */
  def value(v: JsValue)(implicit messages: Messages): String = v match {
    case JsNull | JsString("") => "—"
    case JsBoolean(b)          => messages(if (b) "yes" else "no")
    case JsString(s)           => s
    case JsNumber(n)           => n.bigDecimal.stripTrailingZeros.toPlainString
    case JsArray(items)        => if (items.isEmpty) "—" else items.map(value).mkString(", ")
    case other                 => Json.stringify(other)
  }

  private def short(text: String): String = if (text.length <= Shown) text else text.take(Shown - 1) + "…"

  /** The entry's changed settings, known ones first in their order; a creation's without "from". */
  def items(c: ContestChange)(implicit messages: Messages): Seq[Item] = {
    val changed = (c.detail \ "changed").asOpt[JsObject].getOrElse(Json.obj())
    val known = RoundChanges.Fields.filter(changed.keys.contains)
    val order = known ++ changed.keys.toSeq.filterNot(RoundChanges.Fields.contains).sorted
    val created = c.kind == db.scalikejdbc.ContestChangeJdbc.RoundCreated
    order.map { f =>
      val from = Option.when(!created)(value((changed \ f \ "from").getOrElse(JsNull)))
      val to = value((changed \ f \ "to").getOrElse(JsNull))
      Item(f, label(f), from.map(short), short(to), from, to)
    }
  }

  /** The round's number, as the entry stored it. */
  def number(c: ContestChange): String = (c.detail \ "number").asOpt[Long].fold("?")(_.toString)
}
