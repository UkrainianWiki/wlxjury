package services

import db.scalikejdbc.Round
import org.specs2.mutable.Specification
import services.RoundLineage.{Context, Kind, Track}

/** The track rule v4 and the manual label (round lineage plan §2.3, decisions 3 and 4; slice A,
  * §8.1) on synthetic contests, without a database: the phase-1 fixture (quality + quantity → Main →
  * two regional rounds, one a V95 backtrack, a nomination, a cancelled test root, a failed creation),
  * WLM 2026's shape with and without its two labels, the common prefix, rules 2, 3 and 4, media type
  * and name roots, missing previous rounds, a cycle, the tracks' order and the spines.
  */
class RoundLineageSpec extends Specification {

  private val Prefix = "Category:WLM 2026 in Ukraine - "

  private val context = Context(
    contestCategory = Some("Category:Images from Wiki Loves Monuments 2026 in Ukraine"),
    nominationsByPrefix = Map("99" -> "Jewish heritage"),
    realRegions = Set("26", "32", "80")
  )

  private def round(
      id: Long,
      name: String,
      previous: Seq[Long] = Nil,
      category: Option[String] = None,
      track: Option[String] = None,
      nomination: Option[String] = None,
      regions: Seq[String] = Nil,
      monuments: Option[String] = None,
      media: Option[String] = None,
      status: Option[String] = None,
      exclude: Seq[Long] = Nil,
      distributing: Boolean = false
  ): Round =
    Round(Some(id), id, Some(name), contestId = 1,
      previous = Option.when(previous.nonEmpty)(previous.mkString(",")),
      category = category, track = track, specialNomination = nomination,
      regions = Option.when(regions.nonEmpty)(regions.mkString(",")), monuments = monuments, mediaType = media,
      status = status, excludeRounds = exclude, distributing = distributing)

  private def names(l: RoundLineage.Lineage, t: Track): Seq[String] = l.roundsOf(t).map(_.description)

  private def spine(l: RoundLineage.Lineage, t: Track): Seq[String] =
    l.spines.getOrElse(t, Nil).map(l.rounds(_).description)

  private val quality = Track(Kind.Category, "Quality")
  private val quantity = Track(Kind.Category, "Quantity")

  "the phase-1 fixture" should {
    val rounds = Seq(
      round(1, "Q-00", category = Some(Prefix + "Quality")),
      round(2, "Qt-00", category = Some(Prefix + "Quantity")),
      round(3, "M-01", previous = Seq(1, 2)),
      round(4, "M-02", previous = Seq(3)),
      round(5, "K-01", previous = Seq(4), regions = Seq("80")),
      // a backtrack: from M-01, leaving out K-01, the same region
      round(6, "K-02", previous = Seq(3), regions = Seq("80"), exclude = Seq(5)),
      round(7, "N-00", nomination = Some("Jewish heritage")),
      round(8, "[test] Q-00", category = Some(Prefix + "Quality"), status = Some(Round.Status.Cancelled)),
      // a failed creation: still distributing, a draft
      round(9, "M-03", previous = Seq(4), distributing = true, status = Some(Round.Status.Draft))
    )
    val l = RoundLineage(rounds, context)

    "place merges in Main and the roots by their category less the common prefix" in {
      (l.trackOf(3) === Track.Main) and (l.trackOf(4) === Track.Main) and (l.trackOf(9) === Track.Main) and
        (l.merges === Set(3L)) and
        (names(l, quality) === Seq("Q-00", "[test] Q-00")) and (names(l, quantity) === Seq("Qt-00"))
    }

    "put a region subset on a region track, a backtrack on the same one" in {
      val kyiv = Track(Kind.Region, "80")
      (l.trackOf(5) === kyiv) and (l.trackOf(6) === kyiv)
    }

    "place a nomination by its own name" in {
      l.trackOf(7) === Track(Kind.Nomination, "Jewish heritage")
    }

    "order Main, its feeders as they join, the others by name, nominations, then regions" in {
      l.tracks === Seq(Track.Main, quality, quantity, Track(Kind.Nomination, "Jewish heritage"), Track(Kind.Region, "80"))
    }

    "run each spine to the deepest round, skipping cancelled rounds" in {
      (spine(l, Track.Main) === Seq("M-01", "M-02", "M-03")) and (spine(l, quality) === Seq("Q-00")) and
        (l.deepest(Track.Main).map(_.description) === Some("M-03"))
    }

    "have no lone merge (Main has several rounds) and nothing missing" in {
      (l.loneMerge must beNone) and (l.missingPrevious must beEmpty) and (l.cycle must beEmpty)
    }
  }

  "WLM 2026's shape" should {
    def rounds(labels: Map[Long, String]) = Seq(
      round(1, "Q-00", category = Some(Prefix + "Quality")),
      round(2, "Qn-00", category = Some(Prefix + "Quantity")),
      round(3, "Kha-00", category = Some(Prefix + "Quality"), nomination = Some("Харків")),
      round(4, "[delete] Q-00-1", previous = Seq(1)),
      round(5, "Q-01", previous = Seq(1)),
      round(6, "Q-00-1", previous = Seq(1)),
      round(7, "Qn-00-1", previous = Seq(2)),
      round(8, "Q-01-nf", previous = Seq(5)),
      round(9, "Qn-00-1-nf", previous = Seq(7)),
      round(10, "Q-00-1-nf", previous = Seq(6)),
      round(11, "Q-02", previous = Seq(8, 9)),
      round(12, "Q-00-1.5", previous = Seq(10))
    ).map(r => r.copy(track = labels.get(r.getId)))

    "without labels: Quality 7, Quantity 3, Харків 1, Main only the merge, Quality's spine to its deepest" in {
      val l = RoundLineage(rounds(Map.empty), context)
      (l.roundsOf(quality).size === 7) and (l.roundsOf(quantity).size === 3) and
        (names(l, Track(Kind.Nomination, "Харків")) === Seq("Kha-00")) and
        (names(l, Track.Main) === Seq("Q-02")) and (l.loneMerge.map(_.description) === Some("Q-02")) and
        (spine(l, quality) === Seq("Q-00", "Q-00-1", "Q-00-1-nf", "Q-00-1.5"))
    }

    "with Main on Q-00 and a label of its own on Q-00-1: #181's chain" in {
      val l = RoundLineage(rounds(Map(1L -> "Main", 6L -> "Second look")), context)
      val second = Track(Kind.Label, "Second look")
      (names(l, Track.Main) === Seq("Q-00", "[delete] Q-00-1", "Q-01", "Q-01-nf", "Q-02")) and
        (names(l, second) === Seq("Q-00-1", "Q-00-1-nf", "Q-00-1.5")) and
        (spine(l, Track.Main) === Seq("Q-00", "Q-01", "Q-01-nf", "Q-02")) and
        (l.deepest(Track.Main).map(_.description) === Some("Q-02")) and (l.loneMerge must beNone) and
        (l.tracks === Seq(Track.Main, quantity, second, Track(Kind.Nomination, "Харків")))
    }

    "with Main on Q-00 alone: the second look in Main, its spine through Q-00-1.5" in {
      val l = RoundLineage(rounds(Map(1L -> "main")), context)
      (l.roundsOf(Track.Main).size === 8) and (spine(l, Track.Main) === Seq("Q-00", "Q-00-1", "Q-00-1-nf", "Q-00-1.5"))
    }

    "skip a cancelled trial in the spine; identical labels share a track" in {
      val l = RoundLineage(rounds(Map(1L -> "Main", 6L -> "Second look", 12L -> "Second look")).map(r =>
        if (r.getId == 4) r.copy(status = Some(Round.Status.Cancelled)) else r), context)
      (l.trackOf(12) === Track(Kind.Label, "Second look")) and (l.trackOf(4) === Track.Main) and
        (spine(l, Track.Main) === Seq("Q-00", "Q-01", "Q-01-nf", "Q-02"))
    }
  }

  "the other rules" should {
    "rule 2: regions or monuments that all name one nomination by prefix" in {
      val l = RoundLineage(Seq(
        round(1, "A", monuments = Some("99-141-2306, 99-141-0001")),
        round(2, "B", monuments = Some("99-141-2306,80-361-0001")),
        round(3, "C", regions = Seq("99"))
      ), context)
      (l.trackOf(1) === Track(Kind.Nomination, "Jewish heritage")) and
        (l.trackOf(2) === Track(Kind.Name, "B")) and (l.trackOf(3) === Track(Kind.Nomination, "Jewish heritage"))
    }

    "rule 3 before rule 4: a regional round of a nomination stays in it" in {
      val l = RoundLineage(Seq(round(1, "N", nomination = Some("Харків")), round(2, "N-K", Seq(1), regions = Seq("80"))), context)
      l.trackOf(2) === Track(Kind.Nomination, "Харків")
    }

    "rule 4: all the real regions are no region" in {
      val l = RoundLineage(Seq(round(1, "Q", category = Some(Prefix + "Quality")),
        round(2, "All", Seq(1), regions = Seq("80", "26", "32")), round(3, "Two", Seq(1), regions = Seq("32", "26"))), context)
      (l.trackOf(2) === quality) and (l.trackOf(3) === Track(Kind.Region, "26,32"))
    }

    "rule 7: the contest's own category is none; then the media type; then the name" in {
      val l = RoundLineage(Seq(
        round(1, "V-00", category = Some("Category:Images_from Wiki Loves Monuments 2026 in Ukraine"), media = Some("video")),
        round(2, "Plain"),
        round(3, "Interior", category = Some("Category:Something else"), media = Some("image"))
      ), context)
      // a single root category: nothing it shares with others, none of the contest's words at its ends
      (l.trackOf(1) === Track(Kind.Media, "video")) and (l.trackOf(2) === Track(Kind.Name, "Plain")) and
        (l.trackOf(3) === Track(Kind.Category, "Something else"))
    }

    "name no track after a file list or a Commons: page in the category field (WLE Kyrgyzstan 2026)" in {
      val kg = Context(contestCategory = Some("Category:Images from Wiki Loves Earth 2026 in Kyrgyzstan"),
        boilerplate = Set("wiki", "loves", "earth", "2026", "kyrgyzstan", "images", "from", "in"))
      val l = RoundLineage(Seq(
        round(1, "Wiki Loves Earth 2026 in Kyrgyzstan 1 round", category = Some("Category:Images from Wiki Loves Earth 2026 in Kyrgyzstan")),
        round(2, "Wiki Loves Earth 2026 in Kyrgyzstan 2 round",
          category = Some("Category:Quality images from Wiki Loves Earth 2026 in Kyrgyzstan"), media = Some("image")),
        round(4, "Финальный раунд", category = Some("Category:File:Beautiful landscapes.jpg File:Кол Укок 4.jpg"),
          media = Some("image")),
        round(5, "шорт лист Wiki Loves Earth 2026 in Kyrgyzstan",
          category = Some("Category:Commons:Wiki Loves Earth 2026 in Kyrgyzstan/Long list")),
        round(6, "6", category = Some("Category:Wiki Loves Earth 2026 in Kyrgyzstan long list"), media = Some("image")),
        round(8, "8", category = Some("Category:wiki loves earth 2026 in Kyrgyzstan long list"))), kg)
      val longList = Track(Kind.Category, "long list")
      (l.trackOf(1) === Track(Kind.Name, "Wiki Loves Earth 2026 in Kyrgyzstan 1 round")) and
        // its media type, image, differs from the other roots' (all)
        (l.trackOf(2) === Track(Kind.Category, "Quality")) and (l.trackOf(4) === Track(Kind.Media, "image")) and
        (l.trackOf(5) === Track(Kind.Name, "шорт лист Wiki Loves Earth 2026 in Kyrgyzstan")) and
        // the same category in another case: one track
        (l.trackOf(6) === longList) and (l.trackOf(8) === longList)
    }

    "name roots that share no start by what is left of their categories without the contest's words (WLE Ukraine 2026)" in {
      val wle = Context(contestCategory = Some("Category:Images from Wiki Loves Earth 2026 in Ukraine"),
        boilerplate = Set("wiki", "loves", "earth", "2026", "ukraine", "images", "from", "in"))
      val l = RoundLineage(Seq(
        round(1, "Відео: 0 раунд", media = Some("video")),
        round(2, "Відсіювання: Кількісна", category = Some("Category:Wiki Loves Earth Ukraine 2026 - Quantity")),
        round(3, "Відсіювання: Якісна", category = Some("Category:Wiki Loves Earth Ukraine 2026 - Quality")),
        round(4, "Втрати природи: 0 раунд",
          category = Some("Category:Images from Wiki Loves Earth 2026 Nature Loss Special Category"))), wle)
      // a video root beside roots of all media: named by its media type
      (l.trackOf(1) === Track(Kind.Media, "video")) and (l.trackOf(2) === quantity) and (l.trackOf(3) === quality) and
        (l.trackOf(4) === Track(Kind.Category, "Nature Loss Special Category"))
    }

    "name a root by its media type only if the roots differ in media type, else by its name" in {
      // WLE international: one rated image round; two image roots
      val one = RoundLineage(Seq(round(1, "1 round: main category", media = Some("image"))), context)
      val same = RoundLineage(Seq(round(1, "A", media = Some("image")), round(2, "B", media = Some("image"))), context)
      // images and video roots
      val two = RoundLineage(Seq(round(1, "Photos", media = Some("image")), round(2, "Films", media = Some("video"))), context)
      (one.trackOf(1) === Track(Kind.Name, "1 round: main category")) and (same.trackOf(2) === Track(Kind.Name, "B")) and
        (two.trackOf(1) === Track(Kind.Media, "image")) and (two.trackOf(2) === Track(Kind.Media, "video"))
    }

    "a round whose previous rounds are all gone is a root, with a warning" in {
      val l = RoundLineage(Seq(round(1, "R1", category = Some(Prefix + "Quality")),
        round(5, "Orphan", Seq(999), category = Some(Prefix + "Quantity")), round(6, "Half", Seq(1, 998))), context)
      (l.missingPrevious === Map(5L -> Seq(999L), 6L -> Seq(998L))) and (l.trackOf(5) === quantity) and
        (l.trackOf(6) === quality) and
        // all gone: placed as a root; some left: placed by them (the Rounds page's two warnings)
        (l.allPreviousMissing(5) must beTrue) and (l.allPreviousMissing(6) must beFalse) and
        (l.allPreviousMissing(1) must beFalse)
    }

    "take the common prefix from all the roots' categories: labelling a root changes no other root's track" in {
      val roots = Seq(round(1, "A", category = Some(Prefix + "Quality")), round(2, "B", category = Some(Prefix + "Quantity")),
        round(3, "C", category = Some("Category:Other photos - Interior")))
      val plain = RoundLineage(roots, context)
      // C labelled, or given a nomination or regions: A's and B's tracks stay as they were
      val changed = Seq(
        roots.updated(2, roots(2).copy(track = Some("Second look"))),
        roots.updated(2, roots(2).copy(specialNomination = Some("Jewish heritage"))),
        roots.updated(2, roots(2).copy(regions = Some("80"))))
      (plain.trackOf(1) === Track(Kind.Category, "WLM 2026 in Ukraine - Quality")) and
        (plain.trackOf(2) === Track(Kind.Category, "WLM 2026 in Ukraine - Quantity")) and
        foreach(changed) { rs =>
          val l = RoundLineage(rs, context)
          (l.trackOf(1) === plain.trackOf(1)) and (l.trackOf(2) === plain.trackOf(2)) and (l.trackOf(3) !== plain.trackOf(3))
        }
    }

    "report a cycle and still place every round" in {
      val l = RoundLineage(Seq(round(1, "A", Seq(2)), round(2, "B", Seq(1)), round(3, "C")), context)
      (l.cycle === Seq(1L, 2L)) and (l.trackOf.keySet === Set(1L, 2L, 3L))
    }

    "run a spine through a cancelled round, skipping it, to the deepest live round" in {
      val l = RoundLineage(Seq(
        round(1, "M1", track = Some("Main")), round(2, "M2", Seq(1)),
        round(3, "M3", Seq(2), status = Some(Round.Status.Cancelled)), round(4, "M4", Seq(3)), round(5, "M5", Seq(2))
      ), context)
      (spine(l, Track.Main) === Seq("M1", "M2", "M4")) and (l.deepest(Track.Main).map(_.description) === Some("M4"))
    }

    "group labels by exact match after trimming: case and inner white space count, Main in any case" in {
      val l = RoundLineage(Seq(round(1, "A", track = Some(" Second look ")), round(2, "B", track = Some("second look")),
        round(3, "C", track = Some("Second look")), round(4, "D", track = Some("MAIN")),
        round(5, "E", track = Some("Second  look"))), context)
      (l.trackOf(1) === Track(Kind.Label, "Second look")) and (l.trackOf(3) === l.trackOf(1)) and
        (l.trackOf(2) === Track(Kind.Label, "second look")) and (l.trackOf(4) === Track.Main) and
        (l.trackOf(5) === Track(Kind.Label, "Second  look"))
    }

    "give stable keys, and find a track by its key" in {
      val l = RoundLineage(Seq(round(1, "A", track = Some("Second look"))), context)
      (Track.Main.key === "main") and (l.trackOf(1).key === "label:Second look") and
        (l.byKey("label:Second look") === Some(Track(Kind.Label, "Second look"))) and (l.byKey("label:x") must beNone)
    }
  }

  "the root categories' names" should {
    def names(categories: Seq[String], boilerplate: String = ""): Seq[String] = {
      val words = boilerplate.split(" ").filter(_.nonEmpty).map(_.toLowerCase).toSet
      val m = RoundLineage.scopeNames(categories.map(RoundLineage.categoryName), words)
      categories.map(c => m(RoundLineage.categoryName(c)))
    }

    "drop the words all of them start with (WLM Ukraine)" in {
      names(Seq("Category:Wiki Loves Monuments in Ukraine 2024 - Film", "Category:Wiki Loves Monuments in Ukraine 2024 - Film - Checked",
        "Category:Wiki Loves Monuments in Ukraine 2024 - Quality")) === Seq("Film", "Film - Checked", "Quality")
    }

    "drop the words all of them end with (Wiki Science Competition)" in {
      names(Seq("Category:General category images from Wiki Science Competition 2025 in Ukraine",
        "Category:Images of people in science from Wiki Science Competition 2025 in Ukraine",
        "Category:Microscopy images from Wiki Science Competition 2025 in Ukraine"),
        "Wiki Science Competition 2025 Ukraine Images from in") ===
        Seq("General category images", "Images of people in science", "Microscopy images")
    }

    "drop a shared start and end around a slash (Public Art & Cemeteries, Serbia)" in {
      names(Seq("Category:Images from Wiki Loves Public Art & Cemeteries 2024 in Serbia/Cemeteries/Prejury selection",
        "Category:Images from Wiki Loves Public Art & Cemeteries 2024 in Serbia/Public Art/Prejury selection")) ===
        Seq("Cemeteries", "Public Art")
    }

    "drop the contest's words at the ends when they share no start or end (WLE Serbia, Kyrgyzstan); one category after its last dash (Moldova)" in {
      (names(Seq("Category:Images from Wiki Loves Earth 2026 in Serbia - Vojvodina ",
        "Category:Images from Wiki Loves Earth 2026 in Serbia - protected species",
        "Category:Videos from Wiki Loves Earth 2026 in Serbia"),
        "Wiki Loves Earth 2026 Serbia Images from in final round") === Seq("Vojvodina", "protected species", "Videos")) and
        // one category: after its last " - "
        (names(Seq("Category:Images from Wiki Loves Monuments 2025 in Moldova - eligible for drone special prize"),
          "Wiki Loves Monuments 2025 Moldova Images from in eligible for phase I (national)") ===
          Seq("eligible for drone special prize")) and
        (names(Seq("Category:Commons:Wiki Loves Earth 2026 in Kyrgyzstan/Long list"),
          "Wiki Loves Earth 2026 Kyrgyzstan Images from in") === Seq("Long list"))
    }

    "keep a name with nothing to drop, and leave nothing of a category of the contest's words alone, in any case" in {
      (names(Seq("Category:Something else")) === Seq("Something else")) and
        (names(Seq("Category:Images from Wiki Loves Earth 2024 in Ghana"), "Wiki Loves Earth 2024 Ghana WLE videos in") ===
          Seq("")) and
        (names(Seq("Category:wiki loves earth 2026 in"), "Wiki Loves Earth 2026 Kyrgyzstan") === Seq("")) and
        // a year alone is nothing either
        (names(Seq("Category:Wiki Loves Earth 2025 in Ireland"), "Wiki Loves Earth 2024 Ireland") === Seq(""))
    }

    "take only Category: pages" in {
      (RoundLineage.categoryOf("Category:File:A.jpg File:B.jpg") must beNone) and
        (RoundLineage.categoryOf("Category:Commons:Wiki Loves Earth 2026 in Kyrgyzstan/Long list") must beNone) and
        (RoundLineage.categoryOf("File:A.jpg") must beNone) and (RoundLineage.categoryOf("Commons:WLE 2026/Winners") must beNone) and
        (RoundLineage.categoryOf("Category:") must beNone) and
        (RoundLineage.categoryOf(" category:Images_from WLE ") === Some("Images from WLE")) and
        (RoundLineage.categoryOf("Category:WLE 2026: winners") === Some("WLE 2026: winners"))
    }

    "keep one or two such words at an end, which may be part of the name (WSC Bangladesh, WLE Ireland)" in {
      (names(Seq("Category:Image sets from Wiki Science Competition 2025 in Bangladesh",
        "Category:Images of people in science from Wiki Science Competition 2025 in Bangladesh (first round)",
        "Category:Microscopy images from Wiki Science Competition 2025 in Bangladesh"),
        "Wiki Loves Science 2025 Bangladesh Images from Wiki Science Competition in") ===
        Seq("Image sets", "Images of people in science from Wiki Science Competition 2025 in Bangladesh (first round)",
          "Microscopy")) and
        (names(Seq("Category:Images from Wiki Loves Earth 2025 in Northern Ireland"),
          "Wiki Loves Earth 2024 Ireland Images from Wiki Loves a Future for Thatch 2026") === Seq("Northern Ireland"))
    }

    "keep two categories apart that would get one name" in {
      names(Seq("Category:Images from Wiki Loves Earth 2026 in Ukraine - Quality", "Category:Wiki Loves Earth 2026 Quality"),
        "Wiki Loves Earth 2026 Ukraine Images from in") ===
        Seq("Images from Wiki Loves Earth 2026 in Ukraine - Quality", "Wiki Loves Earth 2026 Quality")
    }
  }

  "the stored label" should {
    "be trimmed, its inner white space kept, Main in any case or language, cut at 64 code points" in {
      val emoji = new String(Character.toChars(0x1F600))
      (Round.trackLabel(Some("  Second   look ")) === Some("Second   look")) and (Round.trackLabel(Some("  ")) must beNone) and
        (Round.trackLabel(Some(emoji * 70)) === Some(emoji * 64)) and
        (Round.trackLabel(Some("MAIN")) === Some("Main")) and
        (Round.trackLabel(Some("основний"), Seq("Основний")) === Some("Main")) and
        (Round.trackLabel(Some("x" * 70)).map(_.length) === Some(64))
    }
  }
}
