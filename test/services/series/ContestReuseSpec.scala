package services.series

import db.scalikejdbc.{SeriesJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.diagnostics.HealthFixtures
import services.diagnostics.HealthFixtures._
import services.imports.ImportState
import scalikejdbc._

/** The inline reuse check (contest series plan §3.2; D-1a's H1 logic, and every round's signals:
  * the maintainer's refinement of 2026-11-20) and how it keeps contests out of the assignment
  * proposals (§3.5). Committed rows (the check reads with `autoSession`).
  */
class ContestReuseSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val settings = ContestReuse.Settings()
  private def signs(c: ContestJury, s: ContestReuse.Settings = settings) = ContestReuse.checkAll(Seq(c), s).getOrElse(c.getId, Nil)

  private val NepalWlm25 = "Category:Images from Wiki Loves Monuments 2025 in Nepal"
  private val Wlm25 = "Category:Images from Wiki Loves Monuments 2025 in "

  "the inline reuse check" should {

    "pass a contest whose sources and rounds are its own year and country" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some(NepalWlm25), 1L to 20L)
      importJob(c, NepalWlm25, ImportState(seen = 20, inserted = 20))
      inCountries(Wlm25, "Nepal" -> (1L to 20L))
      round(c, 1, "First", files = 1L to 20L)
      signs(c) must beEmpty
    }

    "flag an import source or the contest's source of another type, year or country" in {
      val n = nepal()
      signs(n.contest).collect { case ContestReuse.OtherSource(source, _) => source } must contain(NepalCategory)
    }

    "take any country of its type and year as an international contest's own" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "International", Some("Category:Images from Wiki Loves Earth 2020"), 1L to 20L)
      importJob(c, "Category:Images from Wiki Loves Earth 2020 in Armenia", ImportState(seen = 10, inserted = 10))
      importJob(c, "Category:Images from Wiki Loves Earth 2020 in Bhutan", ImportState(seen = 10, inserted = 10))
      inCountries(WleCountries, "Armenia" -> (1L to 10L), "Bhutan" -> (11L to 20L))
      round(c, 1, "Armenia", files = 1L to 10L)
      round(c, 2, "Bhutan", files = 11L to 20L)
      signs(c) must beEmpty
      // another year is still another contest
      importJob(c, "Category:Images from Wiki Loves Earth 2021 in Armenia", ImportState())
      signs(c).collect { case s: ContestReuse.OtherSource => s.source } === Seq("Category:Images from Wiki Loves Earth 2021 in Armenia")
    }

    "flag a national contest whose rounds come from two other countries, though its own source is right" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some(NepalWlm25), 1L to 30L)
      importJob(c, NepalWlm25, ImportState(seen = 10, inserted = 10))
      inCountries(Wlm25, "Nepal" -> (1L to 10L), "Armenia" -> (11L to 20L), "Bhutan" -> (21L to 30L))
      round(c, 1, "Nepal", files = 1L to 10L)
      val armenia = round(c, 2, "Armenia", files = 11L to 20L)
      val bhutan = round(c, 3, "Bhutan", files = 21L to 30L)
      val found = signs(c)
      found.collect { case ContestReuse.RoundFiles(r, named, n, of) => (r, named.country, n, of) }.toSet ===
        Set((armenia.getId, Some("Armenia"), 10L, 10L), (bhutan.getId, Some("Bhutan"), 10L, 10L))
      found.collect { case ContestReuse.RoundCountries(cs) => cs } === Seq(Seq("AM", "BT", "NP"))
      // and the proposals leave it out
      val result = SeriesProposals.of(Seq(db.scalikejdbc.ContestJuryJdbc.findById(c.getId).get), SeriesJdbc.findAll(),
        ContestReuse.checkAll(Seq(c)))
      result.proposals must beEmpty
      result.left.map(l => l.contest.getId -> l.reason) === Seq(c.getId -> "reuse")
    }

    "flag a round whose files are another contest's, and a round filtered by another contest's category" in {
      val other = contestWithFiles("Wiki Loves Monuments", 2024, "Armenia", None, 41L to 50L)
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some(NepalWlm25), (1L to 10L) ++ (41L to 50L))
      val r = round(c, 1, "Mixed", files = (1L to 10L) ++ (41L to 50L))
      val filtered = round(c, 2, "Filtered", category = Some("Category:Images from Wiki Loves Monuments 2025 in Bhutan"))
      val found = signs(c)
      found.collect { case ContestReuse.RoundFiles(id, named, n, of) => (id, named.year, n, of) } === Seq((r.getId, Some(2024), 10L, 20L))
      found.collect { case ContestReuse.RoundSource(id, _, _) => id } === Seq(filtered.getId)
      other.getId must be_>(0L)
    }

    "read the rounds' files by keys only, in the forced order" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some(NepalWlm25), 1L to 30L)
      inCountries(Wlm25, "Armenia" -> (1L to 30L))
      val r = round(c, 1, "First", files = 1L to 30L)
      val plan = DB.readOnly { implicit s =>
        sql"EXPLAIN ${ContestReuse.roundFilesQuery(Seq(r.getId))}"
          .map(rs => (rs.string("table"), rs.stringOpt("key"))).list.apply()
      }
      plan.map(_._1) === Seq("r", "cj", "ri", "cm")
      plan.map(_._2) === Seq(Some("PRIMARY"), Some("PRIMARY"), Some("PRIMARY"), Some("FK_category_member_page_id"))
    }

    "leave a few foreign files below the thresholds, and flag round ids far apart" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some(NepalWlm25), 1L to 300L)
      inCountries(Wlm25, "Armenia" -> (1L to 4L))
      round(c, 1, "First", files = 1L to 300L)
      round(c, 2, "Second", previous = Nil)
      signs(c) must beEmpty
      // the span alone never flags; beside another sign it is shown
      signs(c, settings.copy(roundIdSpan = 0)) must beEmpty
      round(c, 3, "Filtered", category = Some("Category:Images from Wiki Loves Monuments 2024 in Nepal"))
      signs(c, settings.copy(roundIdSpan = 0)).collect { case s: ContestReuse.RoundSpan => s } must haveSize(1)
    }
  }
}
