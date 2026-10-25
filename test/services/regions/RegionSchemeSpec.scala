package services.regions

import db.scalikejdbc.MessageStub
import org.intracer.wmua.{ContestJury, Region}
import org.scalawiki.wlx.dto.Country
import org.specs2.mutable.Specification

class RegionSchemeSpec extends Specification {

  private def contest(name: String, country: String, year: Int = 2025) =
    ContestJury(None, name, year, country, None)

  private def counts(ids: String*): Map[String, Option[Int]] = ids.map(_ -> Some(1)).toMap

  private def oblast(id: String) = Country.Ukraine.regionById(id).name

  "RegionScheme.regions" should {

    "be empty" in {
      RegionScheme.Ukraine.regions(Map.empty)(MessageStub(Map.empty)) === Nil
    }

    "name a region by the messages" in {
      RegionScheme.Ukraine.regions(Map("80" -> Some(42)))(MessageStub(Map("80" -> "Kyiv"))) ===
        Seq(Region("80", "Kyiv", Some(42)))
    }

    "name a sub-region by its monument id prefix" in {
      RegionScheme.Ukraine.regions(Map("07-101" -> Some(42)))(MessageStub(Map.empty)) ===
        Seq(Region("07-101", "Луцьк", Some(42)))
    }

    "name an id that is no region Unknown" in {
      val ids = List("", "**", "0", "00", "95", "99")
      val map = ids.zipWithIndex.map { case (id, i) => id -> Option(i) }.toMap
      RegionScheme.Ukraine.regions(map)(MessageStub(Map.empty)) ===
        ids.zipWithIndex.map { case (id, i) => Region(id, "Unknown", Some(i)) }
    }

    "group Kyiv's sub-regions, in name order" in {
      val regions = RegionScheme.Ukraine.regions(Map("80-361" -> Some(2), "80-363" -> Some(3), "07-101" -> Some(1)),
        subRegions = true)(MessageStub(Map("80" -> "Київ")))
      regions === Seq(Region("80", "Київ", Some(5)), Region("07-101", "Луцьк", Some(1)))
    }
  }

  "RegionScheme.of" should {

    "name a WLE Ukraine contest's regions as Ukraine's" in {
      val scheme = RegionScheme.of(contest("Wiki Loves Earth", "Ukraine"))
      scheme.regions(counts("26", "61", "Печ"))(MessageStub(Map.empty)).map(r => r.id -> r.name) ===
        Seq("26" -> oblast("26"), "61" -> oblast("61"), "Печ" -> "Unknown")
    }

    "name a Ukrainian contest's regions whatever its name" in {
      RegionScheme.of(contest("WLE", "Ukraine")).regions(counts("07"))(MessageStub(Map("07" -> "Volyn O."))) ===
        Seq(Region("07", "Volyn O.", Some(1)))
    }

    "not name another country's regions as Ukraine's" in {
      val messages = MessageStub(Map("07" -> "Volyn O."))
      RegionScheme.of(contest("Wiki Loves Monuments", "Germany")).regions(counts("07"))(messages).map(_.name) ===
        Seq("07")
      RegionScheme.of(contest("contest10", "country10")).regions(counts("07"))(messages).map(_.name) === Seq("07")
    }
  }
}
