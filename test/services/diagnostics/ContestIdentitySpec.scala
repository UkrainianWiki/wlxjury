package services.diagnostics

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import services.diagnostics.ContestIdentity.{check, parse, OtherCountry, OtherKind, OtherYear}

/** H1's reading of a contest and a source (support diagnostics plan, D-1a, DG10): only a "Wiki Loves
  * <type> <year> in <country>" or "WL<x> <year>" phrase names a contest; parts both name and that
  * differ are the differences, the country by its ISO code, only where both resolve.
  */
class ContestIdentitySpec extends Specification {

  private def contest(name: String, year: Int, country: String) =
    ContestJury(Some(1L), name, year, country, None)

  "a source's identity" should {

    "be read from Commons' contest categories, pages and abbreviations, as written" in {
      parse("Category:Images from Wiki Loves Monuments 2026 in Nepal") ===
        ContestIdentity(Some("Monuments"), Some(2026), Some("Nepal"))
      parse("Commons:Wiki Loves Earth 2020/Winners") === ContestIdentity(Some("Earth"), Some(2020), None)
      parse("Category:Images_from_Wiki_Loves_Earth_2021_in_the_United_Kingdom") ===
        ContestIdentity(Some("Earth"), Some(2021), Some("the United Kingdom"))
      parse("Category:WLM 2024 in Ukraine") === ContestIdentity(Some("Monuments"), Some(2024), Some("Ukraine"))
      parse("Wiki Loves Folklore") === ContestIdentity(Some("Folklore"), None, None)
    }

    "name no contest for a title without the phrase, a year elsewhere included" in {
      parse("Category:Churches built in 1900").isEmpty must beTrue
      parse("Template:Monument Ukraine").isEmpty must beTrue
      parse("").isEmpty must beTrue
    }
  }

  "a source's check against the contest" should {

    "find another type and year (the Nepal case), the type in any case" in {
      val wle2020 = contest("Wiki Loves Earth", 2020, "")
      check(wle2020, "Category:Images from Wiki Loves Monuments 2026 in Nepal").differences ===
        Seq(OtherKind, OtherYear)
      check(wle2020, "Category:Images from Wiki Loves Earth 2020 in Armenia").mismatch must beFalse
      check(wle2020, "Category:Images from wiki loves earth 2020").mismatch must beFalse
      check(wle2020, "Category:Churches").mismatch must beFalse
    }

    "compare a national contest's country by its code, a part of it matching" in {
      val ua = contest("Wiki Loves Monuments", 2026, "Ukraine")
      check(ua, "Category:Images from Wiki Loves Monuments 2026 in Nepal").differences === Seq(OtherCountry)
      check(ua, "Category:Images from Wiki Loves Monuments 2026 in Ukraine").mismatch must beFalse
      check(ua, "Category:Images from Wiki Loves Monuments 2026 in Ukraine - Kyiv").mismatch must beFalse
      ContestIdentity.countryCode(ua) must beSome("UA")
      ContestIdentity.countryCode(contest("Wiki Loves Earth", 2020, "")) must beNone
    }

    "read a contest's country typed as a code, and skip one that doesn't resolve" in {
      val code = contest("Wiki Loves Monuments", 2026, "UA")
      ContestIdentity.countryCode(code) must beSome("UA")
      check(code, "Category:Images from Wiki Loves Monuments 2026 in Ukraine").mismatch must beFalse
      check(code, "Category:Images from Wiki Loves Monuments 2026 in Nepal").differences === Seq(OtherCountry)
      // a name in another language, an abbreviation that is no ISO code, a typo: not compared
      for (unresolved <- Seq("Україна", "USA", "Ukrane")) {
        val c = contest("Wiki Loves Monuments", 2026, unresolved)
        ContestIdentity.countryCode(c) must beNone
        check(c, "Category:Images from Wiki Loves Monuments 2026 in Nepal").mismatch must beFalse
        check(c, "Category:Images from Wiki Loves Monuments 2026 in Ukraine").mismatch must beFalse
      }
      // a source's country that doesn't resolve: not compared either
      check(contest("Wiki Loves Monuments", 2026, "Ukraine"),
        "Category:Images from Wiki Loves Monuments 2026 in Atlantis").mismatch must beFalse
    }

    "describe both sides for the import form's warning (DG10), the source in its own casing" in {
      ContestIdentity.describe(contest("Wiki Loves Earth", 2020, "")) === "Wiki Loves Earth 2020"
      ContestIdentity.describe(contest("Wiki Loves Monuments", 2026, "Ukraine")) === "Wiki Loves Monuments 2026 in Ukraine"
      parse("Category:Images from Wiki Loves Monuments 2026 in Nepal").describe === "Wiki Loves Monuments 2026 in Nepal"
      parse("Category:Images_from_Wiki_Loves_Earth_2021_in_the_United_Kingdom").describe ===
        "Wiki Loves Earth 2021 in the United Kingdom"
    }
  }
}
