package views

import db.scalikejdbc.{ContestChange, MediaType, Round, RoundChanges}
import org.intracer.wmua.{AverageRule, BinaryOrder, RejectedMode}
import play.api.i18n.Messages
import play.api.libs.json._

/** How a round's settings entry ([[RoundChanges]]) reads on the round's statistics page and in the
  * contest's change log: each changed setting by its label, "old → new", in [[RoundChanges.Fields]]'
  * order; a setting this binary doesn't know (an entry of a later one) by its stored name. Codes
  * (status, rating, score rule, rejected files, top-N order, media type) are stored as they are and
  * shown in the viewer's language by the keys the other pages use; an unknown code as it is. A value
  * longer than [[Shown]] characters is cut, its whole stored text in the item's title; two values
  * that read the same although they differ (cut past the stored length, or lists whose texts hold
  * commas) get their hashes, of the stored values (never of the text shown).
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

  private def known(key: String, code: String)(implicit messages: Messages): String =
    if (messages.isDefinedAt(key)) messages(key) else code

  /** A setting's code in the viewer's language, by the key the other pages show it with. */
  def code(field: String, code: String)(implicit messages: Messages): String = field match {
    case "status"           => known(s"health.status.$code", code)
    case "rates"            => Round.rates.find(_.name == code).fold(code)(RatingType.label)
    case "averageRule"      => AverageRule.parse(code).fold(code)(r => known(r.messageKey, code))
    case "prevRejectedMode" => RejectedMode.parse(code).fold(code)(m => known(m.messageKey, code))
    case "topBinaryOrder"   => BinaryOrder.parse(code).fold(code)(o => known(o.messageKey, code))
    case "mediaType"        => if (MediaType.mediaTypes.contains(code)) known(code, code) else code
    case RoundChanges.TrackField => if (code == services.RoundLineage.MainLabel) messages("round.track.main") else code
    case _                  => code
  }

  /** A stored value as text: none "—", yes/no, a code translated ([[code]]), a list comma-separated. */
  def value(field: String, v: JsValue)(implicit messages: Messages): String = v match {
    case JsNull | JsString("") => "—"
    case JsBoolean(b)          => messages(if (b) "yes" else "no")
    case JsString(s)           => code(field, s)
    case JsNumber(n)           => n.bigDecimal.stripTrailingZeros.toPlainString
    case JsArray(items)        => if (items.isEmpty) "—" else items.map(value(field, _)).mkString(", ")
    case other                 => Json.stringify(other)
  }

  private def short(text: String): String = RoundChanges.cut(text, Shown)

  /** The entry's changed settings, known ones first in their order; a creation's without "from". */
  def items(c: ContestChange)(implicit messages: Messages): Seq[Item] = {
    val changed = (c.detail \ "changed").asOpt[JsObject].getOrElse(Json.obj())
    val known = RoundChanges.Fields.filter(changed.keys.contains)
    val order = known ++ changed.keys.toSeq.filterNot(RoundChanges.Fields.contains).sorted
    val created = c.kind == db.scalikejdbc.ContestChangeJdbc.RoundCreated
    order.map { f =>
      val entry = changed \ f
      val from = Option.when(!created)(value(f, (entry \ "from").getOrElse(JsNull)))
      val to = value(f, (entry \ "to").getOrElse(JsNull))
      val item = Item(f, label(f), from.map(short), short(to), from, to)
      // the same text shown for two different values (cut, or a list whose texts hold its commas): each
      // with its hash, the whole value's as stored beside a value cut on writing, else the stored value's
      // (RoundChanges.valueHash: a list's of its JSON, never of the text shown)
      if (!item.from.contains(item.to)) item
      else {
        def tagged(side: String, shown: String) = s"$shown #${(entry \ s"${side}Hash").asOpt[String]
          .getOrElse(RoundChanges.valueHash((entry \ side).getOrElse(JsNull)))}"
        item.copy(from = item.from.map(tagged("from", _)), to = tagged("to", item.to))
      }
    }
  }

  /** The round's number, as the entry stored it. */
  def number(c: ContestChange): String = (c.detail \ "number").asOpt[Long].fold("?")(_.toString)
}
