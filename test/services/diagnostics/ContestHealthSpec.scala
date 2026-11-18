package services.diagnostics

import db.scalikejdbc.{Round, SharedTestDb, TestDb, User}
import scalikejdbc._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import services.diagnostics.ContestHealth.Outcome
import services.diagnostics.HealthFixtures._
import services.imports.ImportState
import services.jobs.{JobError, JobStatus}

/** The contest health report (support diagnostics plan, D-1a): each check of the Nepal case, its
  * results and hints, against committed rows (the report reads with `autoSession`).
  */
class ContestHealthSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val roots = new java.util.concurrent.atomic.AtomicInteger
  private def root: User =
    User.create(User("Root", s"root${roots.incrementAndGet()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def keys(report: ContestHealth.Report, code: String): Seq[(Outcome, String, Seq[Long])] =
    report.check(code).toSeq.flatMap(_.hints.map(h => (h.outcome, h.key, h.args)))

  "the Nepal case [A-29]" should {

    "fire H1, H2, H4, H5, H7 and H8 on a reused WLE 2020 contest with WLM 2026 Nepal imports" in {
      val n = nepal()
      val report = ContestHealth.report(n.contest, n.admin)

      // H1: the contest's source and the two WLM 2026 imports name another type and year
      report.check("H1").map(_.outcome) must beSome(Outcome.Warn: Outcome)
      keys(report, "H1") === Seq((Outcome.Warn, "health.h1.warn", Seq(3L)))
      report.contestSource.map(_.differences) must beSome(Seq(ContestIdentity.OtherKind, ContestIdentity.OtherYear))
      report.imports.find(_.job.id == n.wleImport).map(_.identity.mismatch) must beSome(false)
      report.imports.find(_.job.id == n.nepalImport).map(_.identity.mismatch) must beSome(true)

      // H2: the 12 Nepal files are in no country category of this contest
      report.countries.map(_.byCountry) must beSome(Seq("Armenia" -> 8, "Bhutan" -> 6, "Sri Lanka" -> 6))
      report.countries.map(_.none) must beSome(12L)
      keys(report, "H2") === Seq((Outcome.Warn, "health.h2.warn", Seq(12L, 32L)))

      // H4: the page import kept nothing; the winners page left 12 files out as in no country
      keys(report, "H4") === Seq(
        (Outcome.Warn, "health.h4.notincountries", Seq(n.winnersImport, 12L)),
        (Outcome.Warn, "health.h4.nofiles", Seq(n.pageImport)))
      // H5: the prefix-less source was read as a page, and kept nothing
      keys(report, "H5") === Seq((Outcome.Warn, "health.h5.warn", Seq(n.pageImport)))

      // H7: round 1975 is empty, with its source round and filter; H8: round 1974 takes every file
      keys(report, "H7") === Seq((Outcome.Fail, "health.h7.empty", Seq(2L)))
      keys(report, "H8") === Seq((Outcome.Warn, "health.h8.warn", Seq(1L)))
      val r1975 = report.rounds.find(_.round.id == n.round1975.id).get
      r1975.files === 0
      r1975.sources.map(s => s.round.flatMap(_.id) -> s.files) === Seq(n.round1974.id -> 32L)
      r1975.filters.map(f => f.key -> f.value) === Seq("health.filter.selectedBy" -> "1")
      r1975.distribution.map(d => (d.job.status, d.candidatesSeen, d.imagesAdded)) must beSome((JobStatus.Done, 0L, 0L))
      r1975.flagged === Seq("H7")
      report.rounds.find(_.round.id == n.round1974.id).map(_.flagged) must beSome(Seq("H8"))
      // the others pass: no round being created, none frozen, jurors have a round
      Seq("H9", "H10", "H12").map(c => report.check(c).map(_.outcome)) === Seq.fill(3)(Some(Outcome.Pass))
      report.outcome === Outcome.Fail
    }

    "pass H1, H2, H4, H5, H7 and H8 on a contest whose imports match it" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", Some("Category:Images from Wiki Loves Earth 2026"),
        1L to 10L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 6L), "Bhutan" -> (7L to 10L))
      importJob(contest, "Category:Images from Wiki Loves Earth 2026", ImportState(seen = 10, inserted = 10))
      round(contest, 1, "First", files = 1L to 10L, jurors = Seq(User.create(User("J", "j@example.org", None,
        Set("jury"), contestId = contest.id))))
      val report = ContestHealth.report(contest, root)
      Seq("H1", "H2", "H4", "H5", "H7", "H8", "H9", "H10", "H12").map(c => c -> report.check(c).map(_.outcome)) ===
        Seq("H1", "H2", "H4", "H5", "H7", "H8", "H9", "H10", "H12").map(_ -> Some(Outcome.Pass))
      report.countries.map(_.none) must beSome(0L)
    }

    "not count a national contest's files by country, and compare its country with a source's" in {
      val contest = contestWithFiles("Wiki Loves Monuments", 2026, "Ukraine",
        Some("Category:Images from Wiki Loves Monuments 2026 in Nepal"), 1L to 3L)
      val report = ContestHealth.report(contest, root)
      report.countries must beNone
      keys(report, "H2") === Seq((Outcome.Info, "health.h2.national", Nil))
      report.contestSource.map(_.differences) must beSome(Seq(ContestIdentity.OtherCountry))
      report.check("H1").map(_.outcome) must beSome(Outcome.Warn: Outcome)
    }

    "still catch it with round 1974 stopped lately, and an empty round stopped lately (H7, H8)" in {
      val n = nepal()
      DB.autoCommit(implicit s =>
        sql"UPDATE round SET status = 'paused', active = FALSE, status_changed_at = NOW(3) WHERE id IN (${n.round1974.getId}, ${n.round1975.getId})"
          .update())
      val report = ContestHealth.report(n.contest, n.admin)
      keys(report, "H7") === Seq((Outcome.Fail, "health.h7.empty", Seq(2L)))
      keys(report, "H8") === Seq((Outcome.Warn, "health.h8.warn", Seq(1L)))
      report.check("H1").map(_.outcome) must beSome(Outcome.Warn: Outcome)
    }

    "catch a round of every file made before the later imports (H8: no source round, no filter)" in {
      val n = nepal()
      // made from the 20 old files, before the Nepal imports
      round(n.contest, 3, "Before the imports", files = 1L to 20L)
      keys(ContestHealth.report(n.contest, n.admin), "H8") ===
        Seq((Outcome.Warn, "health.h8.warn", Seq(1L)), (Outcome.Warn, "health.h8.warn", Seq(3L)))
    }

    "count no file deleted on Commons by country (H2), as the contest's files leave them out" in {
      val n = nepal()
      DB.autoCommit(implicit s => sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id IN (1, 2, 101)".update())
      val report = ContestHealth.report(n.contest, n.admin)
      report.files === 29L
      report.countries.map(_.byCountry) must beSome(Seq("Armenia" -> 6, "Bhutan" -> 6, "Sri Lanka" -> 6))
      report.countries.map(_.none) must beSome(11L)
    }

    "fail H4 on a failed import, and show no other contest's jobs" in {
      val n = nepal()
      val other = contestWithFiles("Wiki Loves Earth", 2026, "", None, Seq(500L))
      val otherJob = importJob(other, "Category:Secret other contest", ImportState(), status = JobStatus.Failed,
        error = Some(JobError.crashed("ref-other").encode))
      val failed = importJob(n.contest, NepalCategory, ImportState(), status = JobStatus.Failed,
        error = Some(JobError.crashed("ref-1").encode))
      val report = ContestHealth.report(n.contest, n.admin)
      keys(report, "H4").head === ((Outcome.Fail, "health.h4.failed", Seq(failed)))
      report.imports.map(_.job.id) must not(contain(otherJob))
      report.imports.forall(_.job.contestId == n.contest.id) must beTrue
    }

    "note a failed or empty import that a later one of the same source made good (H4)" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", None, 1L to 10L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 10L))
      val source = "Category:Images from Wiki Loves Earth 2026"
      val failed = importJob(contest, source, ImportState(), status = JobStatus.Failed,
        error = Some(JobError.crashed("ref-2").encode))
      val empty = importJob(contest, source, ImportState(seen = 0))
      val good = importJob(contest, source, ImportState(seen = 10, inserted = 10))
      keys(ContestHealth.report(contest, root), "H4") === Seq(
        (Outcome.Info, "health.h4.superseded", Seq(empty, good)),
        (Outcome.Info, "health.h4.superseded", Seq(failed, good)))
    }

    "note a winners page's few icons left out, and warn when many files were (H4)" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", None, 1L to 10L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 10L))
      val icons = importJob(contest, "Commons:Wiki Loves Earth 2026/Winners",
        ImportState(seen = 200, inserted = 194, notInCountries = 6, byCountry = Some(true)))
      val many = importJob(contest, "Commons:Wiki Loves Earth 2026/Other",
        ImportState(seen = 100, inserted = 50, notInCountries = 50, byCountry = Some(true)))
      keys(ContestHealth.report(contest, root), "H4") === Seq(
        (Outcome.Warn, "health.h4.notincountries", Seq(many, 50L)),
        (Outcome.Info, "health.h4.notincountries.few", Seq(icons, 6L, 200L)))
    }
  }

  "an old contest" should {

    "show nothing wrong: countries not recorded, no import jobs, its past rounds paused years ago" in {
      val contest = oldContest()
      val report = ContestHealth.report(contest, root)
      report.checks.filter(_.outcome.rank >= Outcome.Warn.rank).map(_.code) must beEmpty
      keys(report, "H2") === Seq((Outcome.Info, "health.h2.unrecorded", Nil))
      keys(report, "H4") === Seq((Outcome.Info, "health.h4.none", Nil))
      keys(report, "H12") === Seq((Outcome.Info, "health.h12.quiet", Seq(14L)))
      Seq("H1", "H5", "H7", "H8", "H9", "H10").map(c => report.check(c).map(_.outcome)) ===
        Seq.fill(6)(Some(Outcome.Pass))
      report.rounds.map(_.open) === Seq(false, false, false)
    }

    "fire H1 when it is reused for WLM 2026 in Nepal, as contest 466 was, and H8 by H1 alone" in {
      val contest = oldContest()
      importJob(contest, NepalCategory, ImportState(seen = 12, inserted = 12))
      val report = ContestHealth.report(contest, root)
      keys(report, "H1") === Seq((Outcome.Warn, "health.h1.warn", Seq(1L)))
      // its countries still unrecorded: H2 says so, and no past round is flagged
      keys(report, "H2") === Seq((Outcome.Info, "health.h2.unrecorded", Nil))
      report.check("H8").map(_.outcome) must beSome(Outcome.Pass: Outcome)
      // the real path: a new round of every file, no source round, no filter, running
      round(contest, 4, "Nepal jurying", files = 201L to 230L)
      val after = ContestHealth.report(contest, root)
      keys(after, "H8") === Seq((Outcome.Warn, "health.h8.warn", Seq(4L)))
      keys(after, "H2") === Seq((Outcome.Info, "health.h2.unrecorded", Nil))
    }

    "not count an older contest's files as in no country after one recent import recorded a few (H2, H8)" in {
      val contest = oldContest()
      // a winners page imported lately: its 5 files have their countries; the 30 older ones have none
      HealthFixtures.addFiles(contest, 231L to 235L)
      inCountries("Category:Images from Wiki Loves Earth 2019 in ", "Nepal" -> (231L to 235L))
      importJob(contest, "Commons:Wiki Loves Earth 2019/Winners",
        ImportState(seen = 8, inserted = 5, notInCountries = 3, byCountry = Some(true)))
      round(contest, 4, "Every file", files = 201L to 235L)
      val report = ContestHealth.report(contest, root)
      report.countries.map(c => (c.none, c.noneRecorded)) must beSome((30L, 0L))
      keys(report, "H2") === Seq((Outcome.Info, "health.h2.partly", Seq(30L)))
      report.check("H8").map(_.outcome) must beSome(Outcome.Pass: Outcome)
      report.checks.filter(_.outcome.rank >= Outcome.Warn.rank).map(_.code) must beEmpty
    }
  }

  "the round checks" should {

    "warn on a slow distribution, fail a failed one and a round frozen with no job (H9, H10)" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", None, 1L to 5L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 5L))
      val juror = User.create(User("J", "j@example.org", None, Set("jury"), contestId = contest.id))
      round(contest, 1, "Open", files = 1L to 5L, jurors = Seq(juror))
      val slow = round(contest, 2, "Slow", status = Round.Status.Draft, distributing = true)
      val slowJob = distributionJob(slow, JobStatus.Running, 2, 1, minutesAgo = 45)
      val failed = round(contest, 3, "Failed", status = Round.Status.Draft, distributing = true)
      val failedJob = distributionJob(failed, JobStatus.Failed, 5, 4,
        error = Some(JobError(JobError.CountMismatchKey, Seq(failed.getId.toString, "3", "4")).encode))
      round(contest, 4, "Frozen", status = Round.Status.Paused, files = Seq(1L), freezeCount = 1)

      val report = ContestHealth.report(contest, root)
      // the minutes from the database's clock to the JVM's: 45, give or take the container's drift
      val h9 = keys(report, "H9")
      h9.map { case (o, k, args) => (o, k, args.head, args.last) } === Seq(
        (Outcome.Warn, "health.h9.slow", 2L, slowJob),
        (Outcome.Fail, "health.h9.failed", 3L, failedJob))
      h9.head._3(1) must beBetween(44L, 46L)
      keys(report, "H10") === Seq((Outcome.Fail, "health.h10.fail", Seq(4L)))
      report.rounds.find(_.round.id == failed.id).flatMap(_.distribution.flatMap(_.mismatch)) must beSome((3L, 4L))
      // a round being created is no empty round (H7), and no round of every file (H8: no mix)
      report.check("H7").map(_.outcome) must beSome(Outcome.Pass: Outcome)
      report.check("H8").map(_.outcome) must beSome(Outcome.Pass: Outcome)
    }

    "warn on a round its filters left almost empty, not on a next round by votes nor a nomination (H7)" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", None, 1L to 300L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 300L))
      val all = round(contest, 1, "All", files = 1L to 300L)
      round(contest, 2, "By region", files = Seq(1L), tweak = _.copy(regions = Some("14")))
      round(contest, 3, "Top", previous = Seq(all.getId), prevSelectedBy = Some(3), files = Seq(2L))
      // nominations: small on purpose, with a region filter too
      round(contest, 4, "Category nomination", category = Some("Category:Rare"), files = Seq(3L),
        tweak = _.copy(regions = Some("14")))
      round(contest, 5, "Category rule", files = Seq(4L), tweak = _.copy(categoryClause = Some("x"), regions = Some("14")))
      round(contest, 6, "Special nomination", files = Seq(5L),
        tweak = _.copy(specialNomination = Some("Trees"), regions = Some("14")))
      round(contest, 7, "Monuments", files = Seq(6L), tweak = _.copy(monuments = Some("14-101-0001"), regions = Some("14")))
      keys(ContestHealth.report(contest, root), "H7") === Seq((Outcome.Warn, "health.h7.tiny", Seq(2L, 1L, 300L)))
    }

    "count a round's source without the rounds it leaves out, and warn only up to a few files (H7)" in {
      val contest = contestWithFiles("Wiki Loves Earth", 2026, "", None, 1L to 300L)
      inCountries("Category:Images from Wiki Loves Earth 2026 in ", "Nepal" -> (1L to 300L))
      val big = round(contest, 1, "Big", files = 1L to 280L)
      // 300 files less round 1's 280: 20 it could get, 2 of them kept (not under 1%)
      round(contest, 2, "Leaves out round 1", files = Seq(281L, 282L),
        tweak = _.copy(excludeRounds = Seq(big.getId), regions = Some("14")))
      keys(ContestHealth.report(contest, root), "H7") === Seq((Outcome.Pass, "health.h7.pass", Nil))
      // a half kept by its filter: tiny for a share of 0.9, but 20 files are more than the floor of 10
      val settings = ContestHealth.Settings(tinyRoundShare = 0.9)
      round(contest, 3, "By size", files = 1L to 20L, tweak = _.copy(minImageSize = Some(5000)))
      keys(ContestHealth.report(contest, root, settings), "H7").map(_._3.head) must not(contain(3L))
      round(contest, 4, "By size, few", files = 1L to 5L, tweak = _.copy(minImageSize = Some(9000)))
      keys(ContestHealth.report(contest, root, settings), "H7") === Seq(
        (Outcome.Warn, "health.h7.tiny", Seq(2L, 2L, 20L)),
        (Outcome.Warn, "health.h7.tiny", Seq(4L, 5L, 300L)))
    }

    "say jurors see no round only for a contest with recent activity (H12)" in {
      val quiet = contestWithFiles("Wiki Loves Earth", 2019, "", None, Seq(1L))
      round(quiet, 1, "Old", status = Round.Status.Finalized, daysAgo = 400)
      keys(ContestHealth.report(quiet, root), "H12") === Seq((Outcome.Info, "health.h12.quiet", Seq(14L)))

      val busy = contestWithFiles("Wiki Loves Earth", 2026, "", None, Seq(2L))
      importJob(busy, "Category:Images from Wiki Loves Earth 2026", ImportState(seen = 1, inserted = 1))
      keys(ContestHealth.report(busy, root), "H12") === Seq((Outcome.Info, "health.h12.noactive", Nil))
      round(busy, 1, "Open, nobody", files = Seq(2L))
      keys(ContestHealth.report(busy, root), "H12") === Seq((Outcome.Info, "health.h12.nojurors", Seq(1L)))
    }
  }

  "the creators of import jobs" should {

    "be named for members, masked, and for root only outside the contest" in {
      val n = nepal()
      val named = User.create(User("Ann ann@example.org", "ann@example.org", None, Set("organizer"), contestId = n.contest.id))
      val outsider = User.create(User("Outside Person", "out@example.org", None, Set.empty))
      importJob(n.contest, NepalCategory, ImportState(), createdBy = named.id)
      importJob(n.contest, NepalCategory, ImportState(), createdBy = outsider.id)
      val asAdmin = ContestHealth.report(n.contest, n.admin).imports.flatMap(_.creator)
      asAdmin.take(2) === Seq(ContestHealth.Creator(None, member = false),
        ContestHealth.Creator(Some("Ann a***@example.org"), member = true))
      val asRoot = ContestHealth.report(n.contest, root).imports.flatMap(_.creator)
      asRoot.head === ContestHealth.Creator(Some("Outside Person"), member = false)
    }
  }
}
