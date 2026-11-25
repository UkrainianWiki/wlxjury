package views

import db.scalikejdbc.{ContestChange, ContestChangeJdbc, Round, RoundChanges}
import org.specs2.mutable.Specification
import play.api.i18n.{Lang, Messages}
import play.api.libs.json._

/** Two values of a round's settings entry that read the same although they differ get hashes that tell
  * them apart (CodeRabbit on #178): a list whose texts were cut on writing keeps the whole list's hash,
  * and a value shown the same as the other side is hashed as stored, never as the text shown, so
  * `["A, B"]` and `["A", "B"]` (both "A, B") differ. No database: the entry is built from
  * [[RoundChanges.detail]].
  */
class RoundChangeHashSpec extends Specification {

  private implicit val messages: Messages = play.api.test.Helpers.stubMessagesApi().preferred(Seq(Lang("en")))

  private val round = Round(Some(7), 2, Some("R"), contestId = 1)

  private def entry(field: String, from: JsValue, to: JsValue): ContestChange =
    ContestChange(1, Some(1), None, 1, ContestChangeJdbc.RoundChanged, None,
      RoundChanges.detail(round, RoundChanges.Path.Edit, Seq((field, from, to))), None, java.time.Instant.now)

  private def changed(c: ContestChange, field: String): JsObject = (c.detail \ "changed" \ field).as[JsObject]

  private def list(items: String*): JsValue = JsArray(items.map(JsString(_)).toIndexedSeq)

  "a list value" should {

    "keep the whole list's hash when a text of it is cut, and none when nothing is" in {
      val long = "c" * 250
      val c = entry("criteria", list("Light", long + "1"), list("Light", long + "2"))
      val stored = changed(c, "criteria")
      val shortOnly = changed(entry("criteria", list("A"), list("B")), "criteria")

      ((stored \ "from").get === (stored \ "to").get) and
        ((stored \ "from" \ 1).asOpt[String].map(t => t.codePointCount(0, t.length)) ===
          Some(RoundChanges.MaxValueLength)) and
        ((stored \ "fromHash").asOpt[String] === Some(RoundChanges.valueHash(list("Light", long + "1")))) and
        ((stored \ "fromHash").asOpt[String] must not(beEqualTo((stored \ "toHash").asOpt[String]))) and
        ((stored \ "fromHash").asOpt[String].map(_.length) === Some(8)) and
        (shortOnly.keys === Set("from", "to"))
    }

    "read as different when its texts differ only past the cut: each side with its stored hash" in {
      val long = "c" * 250
      val from = list("Light", long + "1")
      val to = list("Light", long + "2")
      val item = RoundChangeText.items(entry("criteria", from, to)).head

      (item.from.map(_.endsWith(s" #${RoundChanges.valueHash(from)}")) must beSome(true)) and
        (item.to.endsWith(s" #${RoundChanges.valueHash(to)}") must beTrue) and
        (item.from must not(beSome(item.to)))
    }

    "read as different from a list of the same texts split at its comma (hashed as stored, not as shown)" in {
      val joined = list("A, B")
      val split = list("A", "B")
      val stored = changed(entry("criteria", joined, split), "criteria")
      val item = RoundChangeText.items(entry("criteria", joined, split)).head

      // nothing cut: no stored hash, the shown text the same on both sides
      (stored.keys === Set("from", "to")) and
        (RoundChangeText.value("criteria", joined) === RoundChangeText.value("criteria", split)) and
        (item.from === Some(s"A, B #${RoundChanges.valueHash(joined)}")) and
        (item.to === s"A, B #${RoundChanges.valueHash(split)}") and
        (RoundChanges.valueHash(joined) must not(beEqualTo(RoundChanges.valueHash(split))))
    }
  }

  "a text value" should {

    "keep its text's hash, the same whether stored beside a cut text or computed when shown" in {
      val long = "m" * 250
      val stored = changed(entry("monuments", JsString(long + "1"), JsString(long + "2")), "monuments")
      // shown cut (60) but stored whole (under 200): no stored hash, the text's own
      val mid = "n" * 100
      val item = RoundChangeText.items(entry("monuments", JsString(mid + "1"), JsString(mid + "2"))).head

      ((stored \ "fromHash").asOpt[String] === Some(RoundChanges.hash(long + "1"))) and
        (RoundChanges.valueHash(JsString(long + "1")) === RoundChanges.hash(long + "1")) and
        (item.from.map(_.endsWith(s" #${RoundChanges.hash(mid + "1")}")) must beSome(true)) and
        (item.to.endsWith(s" #${RoundChanges.hash(mid + "2")}") must beTrue)
    }
  }
}
