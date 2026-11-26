package services.regions

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc.MessageStub
import org.intracer.wmua.{ContestJury, Region}
import org.scalawiki.wlx.dto.Country
import org.specs2.mutable.Specification

import java.io.File

class RegionSchemeSpec extends Specification {

  private def contest(name: String, country: String, year: Int = 2025) =
    ContestJury(None, name, year, country, None)

  private def counts(ids: String*): Map[String, Option[Int]] = ids.map(_ -> Some(1)).toMap

  private def oblast(id: String) = Country.Ukraine.regionById(id).name

  private val other = Map("region.other" -> "Other ({0})")

  private def wlm(year: Int) = RegionScheme.of(contest("Wiki Loves Monuments", "Ukraine", year))

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

    "name an id that is no region nor nomination Other" in {
      val ids = List("", "**", "0", "00", "95", "99")
      val map = ids.zipWithIndex.map { case (id, i) => id -> Option(i) }.toMap
      RegionScheme.Ukraine.regions(map)(MessageStub(other)) ===
        ids.zipWithIndex.map { case (id, i) => Region(id, s"Other ($id)", Some(i), Region.Other) }
    }

    "keep uncounted Kyiv sub-regions uncounted and omit absent Kyiv" in {
      RegionScheme.Ukraine.regions(Map("80-361" -> None), subRegions = true)(MessageStub(Map("80" -> "Kyiv"))) ===
        Seq(Region("80", "Kyiv", None))
      RegionScheme.Ukraine.regions(Map.empty, subRegions = true)(MessageStub(Map.empty)) must beEmpty
      RegionScheme(None).regions(Map("07-101" -> None), subRegions = true)(MessageStub(Map.empty)) ===
        Seq(Region("07-101", "07-101", None, Region.Other))
    }

    "group Kyiv's sub-regions, in name order" in {
      val regions = RegionScheme.Ukraine.regions(Map("80-361" -> Some(2), "80-363" -> Some(3), "07-101" -> Some(1)),
        subRegions = true)(MessageStub(Map("80" -> "Київ")))
      regions === Seq(Region("80", "Київ", Some(5)), Region("07-101", "Луцьк", Some(1)))
    }
  }

  "a WLM Ukraine contest's scheme" should {

    "name a special nomination's prefix after it, and a region by the messages" in {
      wlm(2025).regions(counts("99", "07"))(MessageStub(Map("07" -> "Volyn O."))) === Seq(
        Region("07", "Volyn O.", Some(1)),
        Region("99", "Єврейська спадщина", Some(1), Region.Nomination))
    }

    "translate a nomination's name if the messages have it" in {
      wlm(2025).region("99", None)(MessageStub(Map("Єврейська спадщина" -> "Jewish heritage"))).name ===
        "Jewish heritage"
    }

    "put the regions first, then the nominations in Ukrainian order, then the others" in {
      // code point order would put Є (U+0404) before Б
      wlm(2025).regions(counts("91", "99", "92", "14", "07"))(MessageStub(other)).map(r => r.id -> r.kind) === Seq(
        "07" -> Region.Geographic, "14" -> Region.Geographic,
        "92" -> Region.Nomination, "99" -> Region.Nomination,
        "91" -> Region.Other)
    }

    "name a prefix of a nomination of another year" in {
      // Млини is configured for 2019-2021, yet 2025's files carry 88
      wlm(2025).region("88", None)(MessageStub(other)) === Region("88", "Млини", None, Region.Nomination)
    }

    "name 2026's nominations" in {
      wlm(2026).regions(counts("00", "90", "92"))(MessageStub(other)).map(_.name).toSet ===
        Set("Війна руйнує пам'ятки", "Чеська спадщина", "Болгарська спадщина")
    }

    "name every prefix of the fixture's 2025 files" in {
      val reader = CSVReader.open(new File("data/wlm-UA-images-2025.csv"))
      val prefixes =
        try reader.allWithHeaders().flatMap(_.get("monument_id")).flatMap(Region.of).toSet
        finally reader.close()
      prefixes.size must be_>(27)
      wlm(2025).regions(prefixes.map(_ -> Option.empty[Int]).toMap)(MessageStub(other))
        .filter(_.kind == Region.Other).map(_.id) must beEmpty
    }
  }

  "RegionScheme.monumentRegion" should {
    val zaporizhzhia = MessageStub(Map("23" -> "Запорізька", "80" -> "Київ"))

    "name a nomination's own monument by the nomination and the region its id carries" in {
      // production's 92-232-4801, on the Bulgarian heritage list of Zaporizhzhia Oblast
      wlm(2026).monumentRegion("92-232-4801")(zaporizhzhia) === "Болгарська спадщина, Запорізька"
      wlm(2025).monumentRegion("99-801-0001")(zaporizhzhia) === "Єврейська спадщина, Київ"
      // the region by the country's name where the messages lack it
      wlm(2026).monumentRegion("92-232-4801")(MessageStub(Map.empty)) === "Болгарська спадщина, " + oblast("23")
    }

    "name the nomination alone where the id carries no region" in {
      wlm(2026).monumentRegion("92-000-0001")(zaporizhzhia) === "Болгарська спадщина"
      wlm(2026).monumentRegion("92-x")(zaporizhzhia) === "Болгарська спадщина"
      wlm(2026).monumentRegion("92")(zaporizhzhia) === "Болгарська спадщина"
    }

    "translate the nomination's name if the messages have it" in {
      wlm(2026).monumentRegion("92-232-4801")(MessageStub(Map("Болгарська спадщина" -> "Bulgarian heritage",
        "23" -> "Zaporizhia O."))) === "Bulgarian heritage, Zaporizhia O."
    }

    "name any other monument by its prefix's message, as before" in {
      // Play's messages give a missing key back
      val messages = MessageStub(zaporizhzhia.dict ++ other ++ Map("91" -> "91", "92" -> "92"))
      wlm(2026).monumentRegion("23-248-0004")(messages) === "Запорізька"
      wlm(2026).monumentRegion("91-232-0001")(messages) === "91"
      RegionScheme.Ukraine.monumentRegion("92-232-4801")(messages) === "92"
      RegionScheme.of(contest("contest", "Andorra")).monumentRegion("92-232-4801")(messages) === "92"
    }
  }

  "RegionScheme.of" should {

    "name a WLE Ukraine contest's regions as Ukraine's, without nominations" in {
      val scheme = RegionScheme.of(contest("Wiki Loves Earth", "Ukraine"))
      scheme.regions(counts("26", "61", "99", "Печ"))(MessageStub(other)).map(r => r.id -> r.name) ===
        Seq("26" -> oblast("26"), "61" -> oblast("61"), "99" -> "Other (99)", "Печ" -> "Other (Печ)")
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

  "Region.holds" should {

    "match a file by its region as the counts do" in {
      Region("7", "", None).holds("7-101-0001") must beTrue
      Region("7", "", None).holds("71-101-0001") must beFalse
      Region("Печ", "", None).holds("Печера Ювілейна") must beTrue
      Region("07-101", "", None).holds("07-101-0001") must beTrue
    }
  }
}
