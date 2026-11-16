package services.places

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification

/** The shared place scheme's rule (the maintainer's review of 2026-11-16, from production's
  * contests): a monument id template makes a contest national, by regions, whatever its free-text
  * country; without one, a contest of many countries with a category is by countries; else none.
  */
class PlaceSchemeSpec extends Specification {

  private def contest(name: String, year: Int, country: String, template: Option[String], category: Option[Long] = Some(5L)) =
    ContestJury(Some(1L), name, year, country, None, categoryId = category, monumentIdTemplate = template)

  private def kind(c: ContestJury): String =
    if (PlaceScheme.byRegions(c)) "regions" else if (PlaceScheme.byCountries(c)) "countries" else "none"

  "the place scheme" should {

    "be regions for a contest with a monument id template, whatever its country" in {
      (kind(contest("Wiki Loves Monuments", 2024, "Ukraine", Some("UkrainianMonument"))) === "regions") and
        (kind(contest("Wiki Loves Earth", 2017, "Ukraine, Animals", Some("UkrainianNaturalHeritageSite"))) === "regions") and
        (kind(contest("Wiki Loves Earth", 2014, "Andorra & Catalan areas", Some("Monument"))) === "regions") and
        (PlaceScheme.of(contest("Wiki Loves Earth", 2014, "Andorra & Catalan areas", Some("Monument"))) must
          beSome(beAnInstanceOf[PlaceScheme.Regions]))
    }

    "be countries for an international contest with a category and no template" in {
      (kind(contest("Wiki Loves Earth", 2026, "International", None)) === "countries") and
        (kind(contest("Wiki Loves Earth", 2026, "", None)) === "countries")
    }

    "be none without a category or a template" in {
      kind(contest("Wiki Loves Earth", 2026, "International", None, category = None)) === "none"
    }
  }
}
