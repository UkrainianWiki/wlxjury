package services.series

import db.scalikejdbc.{Round, SharedTestDb, TestDb, User}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.diagnostics.HealthFixtures._
import services.imports.ImportState
import services.series.ReuseReport.{Action, Eras, Kind, Verdict, YearCentre}

/** The reuse dry-run report of one contest (contest series plan §4.1–§4.3, CY-0): its clusters
  * under `previous`, each cluster's identity from its own signals (S5r, S2; never the host's, R4),
  * its kind (R1, R2, R4), buckets against that identity, what it proposes (move, leave, root's
  * choice, stays), and the people a move would concern. A contest shaped like 466 (WLE 2020 Nepal,
  * §4.7) covers every kind at once. Committed rows (the report reads like the scan does).
  */
class ReuseReportSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val settings = ReuseReport.Settings()
  private def wle(year: Int) = s"Category:Images from Wiki Loves Earth $year in "
  private def report(c: ContestJury, eras: Eras = Eras.empty) = DB.readOnly(implicit s => ReuseReport.report(c, eras, settings))
  private def account(name: String) = User.create(User(name, s"${name.replace(' ', '.')}${System.nanoTime()}@example.org"))

  import ReuseFixtures.{Nepal466, nepal466}

  "the reuse report" should {

    "split a 466-like contest into clusters, each with its own identity, kind and proposal" in {
      val n = nepal466()
      val r = report(n.contest)
      def of(round: Round) = r.clusters.find(_.roundIds.contains(round.getId)).get

      r.clusters.map(_.roundIds) === Seq(n.own.map(_.getId), n.r2021.map(_.getId)) ++
        Seq(n.fashion, n.philippines, n.disagreeing, n.mixed, n.bhutan).map(x => Seq(x.getId))
      r.national must beTrue

      val own = of(n.own.head)
      (own.kind, own.action, own.identity.describe) === ((Kind.Own, Action.Stay, "Wiki Loves Earth 2020 in Nepal"))
      own.buckets === ReuseReport.Buckets(20, 0, 0)

      val y2021 = of(n.r2021.head)
      (y2021.kind, y2021.action) === ((Kind.OtherYear, Action.Move))
      y2021.identity.year must beSome(2021)
      y2021.target.map(t => (t.seriesType.code, t.place.key, t.year, t.seriesName, t.seriesId)) must
        beSome(("wle", "np", 2021, "Wiki Loves Earth in Nepal", None))
      y2021.buckets === ReuseReport.Buckets(20, 0, 0)
      y2021.signals.s5r.map(_._1) === Seq(n.r2021.head.getId)

      val fashion = of(n.fashion)
      (fashion.kind, fashion.action) === ((Kind.OtherType, Action.Move))
      fashion.target.map(t => (t.seriesType.code, t.year, t.seriesName)) must
        beSome(("fashion", 2022, "Wiki Loves Fashion in Philippines"))

      val philippines = of(n.philippines)
      (philippines.kind, philippines.action, philippines.empty) === ((Kind.OtherCountry, Action.Leave, true))

      val disagreeing = of(n.disagreeing)
      disagreeing.action === Action.Choose
      disagreeing.disagreements.map(d => (d.signal, d.part)) === Seq(("s2", "country"))

      val mixed = of(n.mixed)
      (mixed.kind, mixed.action, mixed.mixed) === ((Kind.OtherYear, Action.Leave, true))
      mixed.buckets === ReuseReport.Buckets(20, 5, 0)

      val bhutan = of(n.bhutan)
      (bhutan.kind, bhutan.action) === ((Kind.OtherCountry, Action.Move))
      bhutan.activeRounds === Seq(n.bhutan.getId)
      bhutan.target.map(_.place.key) must beSome("bt")
      // its buckets against its own identity, not the host's: Bhutan's files match
      bhutan.buckets === ReuseReport.Buckets(10, 0, 0)

      r.r4Host must beTrue
      r.verdict === Verdict.Choose
      r.moveCount === 3
    }

    "list the jurors and runners a move would concern, with their memberships" in {
      val n = nepal466()
      val r = report(n.contest)
      def of(round: Round) = r.clusters.find(_.roundIds.contains(round.getId)).get
      val own = of(n.own.head)
      own.jurors.map(j => (j.name, j.hostRole, j.otherClusters, j.skip)) ===
        Seq(("Nepal Juror", Some(User.JURY_ROLE), Seq(of(n.r2021.head).index), None))
      of(n.r2021.head).signals.s6 === Seq(own.index -> 1)
      val bhutan = of(n.bhutan)
      bhutan.jurors.map(j => (j.name, j.hostRole, j.skip)) === Seq(("Gone Juror", None, Some("deleted")))
      bhutan.runners.map(x => (x.name, x.hostRole)) === Seq(("Nepal Admin", Some(User.ADMIN_ROLE)))
      // the 2021 move adds the juror; the deleted Bhutan juror is skipped; the juror still judges 2020 here
      r.jurorsToAdd === 1
      r.onlyMovedJurors === 1
      // the files only the moving clusters use (2021, Fashion, Bhutan), none shared with staying rounds
      r.unusedInSource === 40L
      val summary = ReuseReport.summary(r)
      (summary.verdict, summary.moves, summary.activeToMove, summary.r4Host, summary.filesToLink) ===
        ((Verdict.Choose, 3, Seq(n.bhutan.getId), true, 40L))
      summary.kinds === Map(Kind.Own -> 1, Kind.OtherYear -> 2, Kind.OtherType -> 1, Kind.OtherCountry -> 3)
      summary.actions === Map(Action.Stay -> 1, Action.Move -> 3, Action.Leave -> 2, Action.Choose -> 1)
    }

    "offer an existing series and its contest of the year as the target" in {
      val n = nepal466()
      val root = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))
      val seriesId = ContestSeries.createSeriesTx("WLE Nepal", Some("wle"), Some("Nepal"), None, root.getId).toOption.get
      val y2021 = db.scalikejdbc.ContestJuryJdbc.create(None, "Wiki Loves Earth", 2021, "Nepal")
      ContestSeries.setSeries(y2021.getId, Some(seriesId), root.getId, None) must beRight
      db.scalikejdbc.UserContestJdbc.createMembership(n.juror.getId, y2021.getId, "organizer")
      val cluster = report(n.contest).clusters.find(_.roundIds.contains(n.r2021.head.getId)).get
      cluster.target.map(t => (t.seriesId, t.seriesName, t.contests)) must beSome((Some(seriesId), "WLE Nepal", Seq(y2021.getId)))
      // O14: an Organizer there can't be given the Juror role
      cluster.jurors.map(_.skip) === Seq(Some("conflict"))
    }

    "take any country of its type and year as an international contest's own" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "International", Some("Category:Images from Wiki Loves Earth 2020"), 1L to 20L)
      inCountries(wle(2020), "Armenia" -> (1L to 10L), "Bhutan" -> (11L to 20L))
      round(c, 1, "Armenia", category = Some(wle(2020) + "Armenia"), files = 1L to 10L)
      round(c, 2, "All", files = 1L to 20L)
      val r = report(c)
      r.national must beFalse
      r.clusters.map(c => (c.kind, c.action)) === Seq((Kind.Own, Action.Stay), (Kind.Own, Action.Stay))
      r.clusters(1).identity.country must beSome("International")
      r.clusters(1).signals.s2ManyCountries must beTrue
      r.verdict === Verdict.Clean
      // another year is still another contest
      round(c, 3, "Next year", category = Some(wle(2021) + "Armenia"))
      report(c).clusters.last.kind === Kind.OtherYear
    }

    "never send a contest of many countries' other year to one country's series: root's choice, as of many" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "International", Some("Category:Images from Wiki Loves Earth 2020"),
        1L to 20L)
      inCountries(wle(2021), "Armenia" -> (1L to 20L))
      round(c, 1, "Armenia 2021", category = Some(wle(2021) + "Armenia"), files = 1L to 20L)
      val cluster = report(c).clusters.head
      (cluster.kind, cluster.action) === ((Kind.OtherYear, Action.Choose))
      cluster.disagreements.map(d => (d.signal, d.part)) === Seq(("s5r", "international"))
      cluster.identity.country must beSome("International")
      cluster.target.map(t => (t.place, t.year, t.seriesName)) must
        beSome((SeriesIdentity.International, 2021, "Wiki Loves Earth International"))
      // its files are of the target's type and year, any country
      cluster.buckets === ReuseReport.Buckets(20, 0, 0)
    }

    "read a country as Commons writes it as national: \"the Philippines\" (production's 281, 467)" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "the Philippines", None, 1L to 20L)
      inCountries(wle(2021), "the Philippines" -> (1L to 20L))
      round(c, 1, "2021", category = Some(wle(2021) + "the Philippines"), files = 1L to 20L)
      val r = report(c)
      r.national must beTrue
      r.clusters.map(c => (c.kind, c.action, c.identity.country)) === Seq((Kind.OtherYear, Action.Move, Some("the Philippines")))
      r.clusters.head.target.map(_.place.key) must beSome("ph")
    }

    "count a type and year's files once over all its categories" in {
      val c = contestWithFiles("Wiki Loves Earth", 2025, "Nepal", None, 1L to 20L)
      // a country's category and its part's, of WLE 2024, holding the same 8 files: 8 of 20, not 16
      inCountries(wle(2024), "Nepal" -> (1L to 8L), "Nepal - Bagmati" -> (1L to 8L))
      round(c, 1, "First", files = 1L to 20L)
      report(c).clusters.map(c => (c.kind, c.identity.year)) === Seq((Kind.Unknown, Some(2025)))
      // with 4 more files in the part's: 12 of 20 name WLE 2024 in Nepal
      inCountries(wle(2024), "Nepal - Bagmati" -> (9L to 12L))
      report(c).clusters.map(c => (c.kind, c.identity.year, c.identity.country)) ===
        Seq((Kind.OtherYear, Some(2024), Some("Nepal")))
    }

    "treat a contest of no known country as of many: another year's country round is root's choice" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "Africa", None, 1L to 40L)
      inCountries(wle(2021), "Kenya" -> (1L to 20L))
      inCountries("Category:Images from Wiki Loves Earth 2021", "" -> (21L to 40L))
      round(c, 1, "Kenya 2021", category = Some(wle(2021) + "Kenya"), files = 1L to 20L)
      round(c, 2, "All 2021", category = Some("Category:Images from Wiki Loves Earth 2021"), files = 21L to 40L)
      val r = report(c)
      r.national must beFalse
      val kenya = r.clusters.head
      (kenya.kind, kenya.action, kenya.identity.country) === ((Kind.OtherYear, Action.Choose, Some("International")))
      kenya.disagreements.map(d => (d.signal, d.part)) === Seq(("s5r", "international"))
      // no country named: the host's own place ("Africa"), its series' next year, as its series is
      val all = r.clusters(1)
      (all.kind, all.action, all.identity.country) === ((Kind.OtherYear, Action.Move, Some("Africa")))
      all.target.map(t => (t.place, t.year)) must beSome((SeriesIdentity.Unusual("Africa"), 2021))
      // a contest whose country doesn't resolve ("Wales"): its own country's other year is its own
      val w = contestWithFiles("Wiki Loves Earth", 2021, "Wales", None, 101L to 120L)
      inCountries(wle(2022), "Wales" -> (101L to 120L))
      round(w, 1, "2022", category = Some(wle(2022) + "Wales"), files = 101L to 120L)
      report(w).clusters.map(c => (c.kind, c.action, c.identity.country)) === Seq((Kind.OtherYear, Action.Move, Some("Wales")))
    }

    "read a country's parts as the country, and never take a country that doesn't resolve as another" in {
      // production's WLE 2020 Brazil (454) and WLM 2016 Bulgaria (119), each read as R4 before this
      val c = contestWithFiles("Wiki Loves Earth", 2020, "Brazil", None, 1L to 30L)
      inCountries(wle(2020), "Brazil" -> (1L to 20L), "Brazil - São Paulo" -> (1L to 15L), "Brazil - Best 115" -> (16L to 30L))
      round(c, 1, "Best", files = 1L to 30L)
      round(c, 2, "Last round", category = Some(wle(2020) + "Brazil last round"), files = 1L to 10L)
      val r = report(c)
      r.clusters.map(c => (c.kind, c.action, c.identity.country)) ===
        Seq((Kind.Own, Action.Stay, Some("Brazil")), (Kind.Own, Action.Stay, Some("Brazil")))
      r.verdict === Verdict.Clean
    }

    "pass a national contest of its own rounds, and leave a cluster no signal names as it is" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", Some("Category:Images from Wiki Loves Monuments 2025 in Nepal"),
        1L to 30L)
      importJob(c, "Category:Images from Wiki Loves Monuments 2025 in Nepal", ImportState(seen = 30, inserted = 30))
      val r1 = round(c, 1, "First", files = 1L to 30L)
      round(c, 2, "Second", previous = Seq(r1.getId), files = 1L to 10L)
      val r = report(c)
      r.clusters.map(c => (c.kind, c.action)) === Seq((Kind.Unknown, Action.Stay))
      r.verdict === Verdict.Clean
      r.clusters.head.buckets === ReuseReport.Buckets(0, 0, 30)
    }

    "place a cluster no signal names by its file era, as root's choice, never a move" in {
      val c = contestWithFiles("Wiki Loves Monuments", 2025, "Nepal", None, (1L to 10L) ++ (1001L to 1010L))
      round(c, 1, "This year", files = 1001L to 1010L)
      round(c, 2, "Old files", files = 1L to 10L)
      val eras = Eras(Seq(YearCentre(2016, Some(5), 1), YearCentre(2025, Some(1005), 2)))
      val r = report(c, eras)
      r.eras must beTrue
      r.clusters.map(c => (c.signals.s1Year, c.kind, c.action)) ===
        Seq((Some(2025), Kind.Unknown, Action.Stay), (Some(2016), Kind.OtherYear, Action.Choose))
      r.clusters(1).disagreements.map(_.part) === Seq("host")
      r.clusters(1).target.map(_.year) must beSome(2016)
    }

    "read the eras as the median of each year's contests, and a value beyond every year as none" in {
      val eras = Eras.of(Seq(
        ReuseReport.EraSample(1, 2020, Some(90), 700), ReuseReport.EraSample(2, 2020, Some(92), 720),
        ReuseReport.EraSample(3, 2020, Some(9000000), 710), ReuseReport.EraSample(4, 2021, Some(107), 950)))
      eras.years === Seq(YearCentre(2020, Some(92), 710), YearCentre(2021, Some(107), 950))
      eras.yearOfPage(95) must beSome(2020)
      eras.yearOfPage(104) must beSome(2021)
      eras.yearOfPage(9000000) must beNone
      eras.yearOfRound(900) must beSome(2021)
    }

    "read a cluster's files by keys only" in {
      val c = contestWithFiles("Wiki Loves Earth", 2020, "Nepal", None, 1L to 30L)
      inCountries(wle(2020), "Nepal" -> (1L to 30L))
      val r = round(c, 1, "First", files = 1L to 30L)
      def plan(q: SQLSyntax) = DB.readOnly { implicit s =>
        sql"EXPLAIN $q".map(rs => (rs.string("table"), rs.stringOpt("key"))).list.apply()
      }
      plan(ReuseReport.categoryCountsQuery(Seq(r.getId), c.categoryId)) ===
        Seq("ri" -> Some("PRIMARY"), "cm" -> Some("FK_category_member_page_id"))
      val buckets = plan(ReuseReport.bucketsQuery(Seq(r.getId), Seq(1L), Seq(1L, 2L)))
      buckets.map(_._1) === Seq("ri", "cm")
      buckets.head._2 must beSome("PRIMARY")
      buckets(1)._2 must beSome(beOneOf("FK_category_member_page_id", "cm_category_page"))
    }
  }
}
