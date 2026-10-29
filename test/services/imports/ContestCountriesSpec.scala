package services.imports

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification

/** A WLx file's country, of its contest's country categories ([[ContestCountries]]). */
class ContestCountriesSpec extends Specification {

  private val contest = ContestJury(Some(1L), "Wiki Loves Earth", 2026, "International", None)
  private val images = "Category:Images from Wiki Loves Earth 2026 in "
  private val plain = "Category:Wiki Loves Earth 2026 in "

  "candidates" should {

    "collapse names that overlap to the shortest, keeping those that don't" in {
      ContestCountries.candidates(contest,
        Seq(images + "Serbia", images + "Serbia - detail", images + "Serbia - final round", "Category:Lakes")) ===
        Seq("Serbia" -> (images + "Serbia"))
      ContestCountries.candidates(contest, Seq(images + "Bangladesh under assessment", images + "Bangladesh")) ===
        Seq("Bangladesh" -> (images + "Bangladesh"))
      ContestCountries.candidates(contest, Seq(images + "England", images + "the United Kingdom")) ===
        Seq("England" -> (images + "England"), "the United Kingdom" -> (images + "the United Kingdom"))
    }

    "not take a name for another's part only because it starts with it" in {
      ContestCountries.candidates(contest, Seq(images + "Niger", images + "Nigeria", images + "Guinea-Bissau",
        images + "Guinea")).map(_._1) === Seq("Guinea", "Guinea-Bissau", "Niger", "Nigeria")
    }

    "take the other prefix, the upload campaigns' first for the same name" in {
      ContestCountries.candidates(contest, Seq(plain + "Tanzania")) === Seq("Tanzania" -> (plain + "Tanzania"))
      ContestCountries.candidates(contest, Seq(plain + "Serbia", images + "Serbia")) === Seq("Serbia" -> (images + "Serbia"))
      ContestCountries.candidates(contest, Seq("Category:Images from Wiki Loves Earth 2025 in Serbia")) must beEmpty
    }
  }

  "choose" should {

    "take the others' parent of names that don't overlap" in {
      val uk = images + "the United Kingdom"
      val candidates = ContestCountries.candidates(contest, Seq(images + "England", uk))
      ContestCountries.choose(candidates, Map(images + "England" -> Seq(uk, "Category:Lakes"))) === Some(uk)
      // without a parent: the shortest
      ContestCountries.choose(candidates, Map.empty) === Some(images + "England")
      ContestCountries.choose(Nil, Map.empty) must beNone
    }
  }

  "fileCountries" should {

    "count a file under its category with the most of the contest's files, overlapping names collapsed" in {
      val (uk, ni, serbia, detail) = (1L, 2L, 3L, 4L)
      val links = Seq(
        // the import linked the four UK files to the UK; an NI contest's category has one of them
        (10L, uk, images + "the United Kingdom"), (11L, uk, images + "the United Kingdom"),
        (12L, uk, images + "the United Kingdom"), (13L, uk, images + "the United Kingdom"),
        (13L, ni, images + "Northern Ireland"),
        // a Serbian contest's own category of some of Serbia's files
        (20L, serbia, images + "Serbia"), (20L, detail, images + "Serbia - detail"),
        (21L, detail, images + "Serbia - detail"),
        (30L, 5L, plain + "Tanzania")
      )
      ContestCountries.fileCountries(contest, links) === Map(10L -> "the United Kingdom", 11L -> "the United Kingdom",
        12L -> "the United Kingdom", 13L -> "the United Kingdom", 20L -> "Serbia", 21L -> "Serbia - detail",
        30L -> "Tanzania")
    }
  }
}
