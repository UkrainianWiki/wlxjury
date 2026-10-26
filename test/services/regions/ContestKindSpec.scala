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

    "load the config for a mixed-case campaign as stored by contest creation" in {
      val c = contest("Custom title", "Other country", campaign = Some(" WLE-UA "))
      ContestKind.monumentIdTemplate(c) must beSome("UkrainianNaturalHeritageSite")
    }

    "keep an explicit unknown campaign from falling back to the name" in {
      ContestKind.of(contest("Wiki Loves Monuments", "Ukraine", campaign = Some("unknown-ua"))) must beNone
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

  "ContestKind.monumentIdTemplate" should {

    "be the campaign config's for a contest without one" in {
      ContestKind.monumentIdTemplate(contest("Wiki Loves Earth", "Ukraine")) must beSome("UkrainianNaturalHeritageSite")
      ContestKind.monumentIdTemplate(contest("Wiki Loves Monuments", "Ukraine")) must beSome("Monument Ukraine")
      ContestKind.monumentIdTemplate(contest("Wiki Loves Monuments", "Ukraine").copy(monumentIdTemplate = Some(" ")))
        .must(beSome("Monument Ukraine"))
    }

    "be the admin's when there is one" in {
      ContestKind.monumentIdTemplate(contest("Wiki Loves Earth", "Ukraine").copy(monumentIdTemplate = Some("Custom"))) must
        beSome("Custom")
    }

    "be none for a contest of no known campaign" in {
      ContestKind.monumentIdTemplate(contest("contest10", "country10")) must beNone
      ContestKind.monumentIdTemplate(contest("Wiki Loves Earth", "Ukraine Int")) must beNone
      ContestKind.monumentIdTemplate(contest("Wiki Loves Monuments", "Germany")) must beNone
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
