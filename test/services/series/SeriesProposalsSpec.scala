package services.series

import db.scalikejdbc.Series
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification

import java.time.Instant

/** The assignment proposals (contest series plan §3.5): grouping by type and country across
  * years, "International" taken literally, an existing series offered first, and the contests
  * never proposed with why. No database.
  */
class SeriesProposalsSpec extends Specification {

  private var next = 0L
  private def c(name: String, year: Int, country: String, series: Option[Long] = None, retired: Boolean = false) = {
    next += 1
    ContestJury(Some(next), name, year, country, None, seriesId = series, retired = retired)
  }
  private def s(id: Long, name: String, kind: String, country: String, retired: Boolean = false) =
    Series(id, name, Some(kind), Some(country), None, None, Instant.EPOCH, Option.when(retired)(Instant.EPOCH), None)

  "the proposals" should {

    "group contests by type and country across years, the newest first, with a suggested name" in {
      val ua24 = c("Wiki Loves Monuments", 2024, "Ukraine")
      val ua25 = c("Wiki Loves Monuments", 2025, "Ukraine")
      val wleUa = c("Wiki Loves Earth", 2025, "Ukraine")
      val intl = c("Wiki Loves Earth", 2025, "International")
      val result = SeriesProposals.of(Seq(ua24, ua25, wleUa, intl), Nil, Map.empty)
      result.proposals.map(p => p.name -> p.contests.map(_.year)) === Seq(
        "Wiki Loves Earth International" -> Seq(2025),
        "Wiki Loves Earth in Ukraine" -> Seq(2025),
        "Wiki Loves Monuments in Ukraine" -> Seq(2025, 2024))
      result.left must beEmpty
    }

    "offer a live series of the type and country first, and leave out a year it already has" in {
      val series = s(7, "WLM UA", "wlm", "Ukraine")
      val held = c("Wiki Loves Monuments", 2023, "Ukraine", series = Some(7))
      val y23 = c("Wiki Loves Monuments", 2023, "Ukraine")
      val y24 = c("Wiki Loves Monuments", 2024, "Ukraine")
      val result = SeriesProposals.of(Seq(held, y23, y24), Seq(series, s(8, "Old", "wlm", "Ukraine", retired = true)), Map.empty)
      result.proposals.map(p => (p.existing.map(_.id), p.name, p.contests.map(_.getId))) === Seq((Some(7L), "WLM UA", Seq(y24.getId)))
      result.left.map(l => l.contest.getId -> l.reason) === Seq(y23.getId -> "taken")
    }

    "never propose unparsed names, unusual countries, duplicate years, flagged or retired contests" in {
      val unparsed = c("Photo contest", 2025, "Ukraine")
      val unusual = c("Wiki Loves Earth", 2025, "Ukraine, Animals")
      val empty = c("Wiki Loves Earth", 2024, "")
      val dupA = c("Wiki Loves Monuments", 2025, "Nepal")
      val dupB = c("Wiki Loves Monuments", 2025, "Nepal")
      val flagged = c("Wiki Loves Monuments", 2025, "Armenia")
      val retired = c("Wiki Loves Monuments", 2024, "Armenia", retired = true)
      val sign = ContestReuse.RoundCountries(Seq("AM", "NP"))
      val result = SeriesProposals.of(Seq(unparsed, unusual, empty, dupA, dupB, flagged, retired), Nil, Map(flagged.getId -> Seq(sign)))
      result.proposals must beEmpty
      result.left.map(l => l.contest.getId -> l.reason).toMap === Map(
        unparsed.getId -> "unparsed", unusual.getId -> "country", empty.getId -> "country",
        dupA.getId -> "duplicate", dupB.getId -> "duplicate", flagged.getId -> "reuse")
      result.left.find(_.reason == "reuse").map(_.signs) must beSome(Seq(sign))
    }

    "group types scalawiki doesn't know by the word after Wiki Loves (CA24)" in {
      val f25 = c("Wiki Loves Folklore", 2025, "Nepal")
      val f26 = c("Wiki Loves Folklore", 2026, "Nepal")
      val fashion = c("Wiki Loves Fashion", 2022, "Ukraine")
      val wlm = c("WLM", 2026, "Nepal")
      val result = SeriesProposals.of(Seq(f25, f26, fashion, wlm), Nil, Map.empty)
      result.proposals.map(p => (p.key, p.name, p.contests.map(_.year))) === Seq(
        ("fashion:ua", "Wiki Loves Fashion in Ukraine", Seq(2022)),
        ("folklore:np", "Wiki Loves Folklore in Nepal", Seq(2026, 2025)),
        ("wlm:np", "Wiki Loves Monuments in Nepal", Seq(2026)))
    }
  }

  "a type root types" should {
    "read as typeOf reads a contest's name (CA24)" in {
      Seq("wlm", "WLM", "Monuments", "Wiki Loves Monuments", "m").map(SeriesIdentity.typedCode) must contain(be_==(Some("wlm"))).forall
      Seq("WLF", "f", "Wiki Loves Folklore", "folklore", "Folklore").map(SeriesIdentity.typedCode) must
        contain(be_==(Some("folklore"))).forall
      SeriesIdentity.typedCode("Earth") === Some("wle")
      SeriesIdentity.typedCode("  ") must beNone
      SeriesIdentity.typedCode("Wiki Loves 2025") must beNone
    }
  }

  "the identity check" should {
    "refuse only a positive mismatch of type or country, International literally" in {
      val intl = s(1, "WLE International", "wle", "International")
      val ua = s(2, "WLM UA", "wlm", "Ukraine")
      SeriesIdentity.mismatch(intl, c("Wiki Loves Earth", 2025, "International")) must beFalse
      SeriesIdentity.mismatch(intl, c("Wiki Loves Earth", 2025, "Ukraine")) must beTrue
      SeriesIdentity.mismatch(ua, c("Wiki Loves Monuments", 2025, "UA")) must beFalse
      SeriesIdentity.mismatch(ua, c("Wiki Loves Earth", 2025, "Ukraine")) must beTrue
      SeriesIdentity.mismatch(ua, c("Wiki Loves Folklore", 2025, "Ukraine")) must beTrue
      // not recognised: no mismatch, but logged as such
      for (unread <- Seq(c("Photo contest", 2025, "Ukraine"), c("Wiki Loves Monuments", 2025, "Україна"),
          c("Wiki Loves Monuments", 2025, "USA"), c("WLM", 2025, ""))) {
        SeriesIdentity.mismatch(ua, unread) must beFalse
      }
      SeriesIdentity.recognised(ua, c("Wiki Loves Monuments", 2025, "Україна")) must beFalse
      SeriesIdentity.recognised(ua, c("WLM", 2025, "Ukraine")) must beTrue
    }
  }
}
