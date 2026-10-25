package services.regions

import org.intracer.wmua.ContestJury
import org.scalawiki.wlx.dto.ContestType
import org.specs2.mutable.Specification

class ContestKindSpec extends Specification {

  private def contest(name: String, country: String, year: Int = 2025, campaign: Option[String] = None) =
    ContestJury(None, name, year, country, None, campaign = campaign)

  "ContestKind.of" should {

    "find a contest without a campaign by its name and country, in its year" in {
      val kind = ContestKind.of(contest("Wiki Loves Monuments", "Ukraine", 2024))
      kind.map(_.contestType) must beSome(ContestType.WLM)
      kind.map(_.country.code) must beSome("UA")
      kind.map(_.year) must beSome(2024)
    }

    "take the country by its code too" in {
      ContestKind.of(contest("Wiki Loves Earth", "ua")).map(_.campaign) must beSome("wle-UA")
      ContestKind.of(contest("Wiki Loves Earth", "UA")).map(_.campaign) must beSome("wle-UA")
    }

    "prefer the campaign to the name" in {
      ContestKind.of(contest("Wiki Loves Monuments", "Ukraine", campaign = Some("wle-ua"))).map(_.contestType) must
        beSome(ContestType.WLE)
    }

    "not take a country only like Ukraine's for Ukraine" in {
      Seq("Ukraine Int", "Ukraine, Animals", "Ukraine ineligible").map(c =>
        ContestKind.of(contest("Wiki Loves Earth", c))
      ) must contain(beNone).foreach
    }

    "know nothing of an unknown contest type or country" in {
      ContestKind.of(contest("contest10", "Ukraine")) must beNone
      ContestKind.of(contest("Wiki Loves Monuments", "country10")) must beNone
    }

    "give a country without a campaign config only the type and the country" in {
      val kind = ContestKind.of(contest("Wiki Loves Monuments", "Germany"))
      kind.map(_.country.name) must beSome("Germany")
      kind.flatMap(_.fileTemplate) must beNone
    }
  }

  "ContestKind.nominations" should {

    "be WLM Ukraine's for a WLM Ukraine contest, and none for WLE's" in {
      ContestKind.nominations(contest("Wiki Loves Monuments", "Ukraine")).map(_.name) must
        contain("Єврейська спадщина")
      ContestKind.nominations(contest("Wiki Loves Earth", "Ukraine")) must beEmpty
      ContestKind.nominations(contest("contest10", "country10")) must beEmpty
    }
  }
}
