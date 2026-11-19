package services.diagnostics

import db.scalikejdbc.{CountryFilesCache, ImageJdbc, Round, RoundImageJdbc, RoundUser, User}
import org.intracer.wmua.ContestJury
import play.api.Configuration
import scalikejdbc._
import services.distribution.{RoundDistributionHandler, RoundFreezes}
import services.imports.{ContestCountries, ImageImportHandler, ImportParams, ImportState, SourceName}
import services.jobs.{Job, JobError, JobJdbc, JobStatus}

import java.time.{Duration, Instant}

/** A contest's health report (support diagnostics plan §3.3, §5; D-1a): what its data shows now,
  * and the checks the Nepal case needed, each pass, info, warn or fail with its hints. It reads
  * only this contest's rows: its jobs by `job.contest_id`, its rounds, its files, and the names
  * of its members; no other contest's data, no global job, and no Commons call. Root and the
  * contest's admins see it (DG2), on `/admin/contests/:id/health`.
  *
  *  - **H1** the contest's name and year against its import sources (the decisive check):
  *    [[ContestIdentity]];
  *  - **H2** its files by country category, and those in none (an international contest's only);
  *  - **H4** its import jobs: failed, kept no files, files left out as in no country;
  *  - **H5** sources without a namespace prefix, read as a page;
  *  - **H7** empty or tiny rounds; **H8** a round of every file of a mixed contest;
  *  - **H9** rounds being created too long, or whose distribution failed; **H10** rounds frozen
  *    with no job;
  *  - **H12** in a contest with recent activity: a round open to jurors that no juror can vote
  *    in (a warning per round); else no round open to jurors.
  */
object ContestHealth {

  /** A check's result. `key`: its message; `css`: its badge's class. */
  sealed abstract class Outcome(val key: String, val css: String, val rank: Int)
  object Outcome {
    case object Pass extends Outcome("health.outcome.pass", "text-bg-success", 0)
    case object Info extends Outcome("health.outcome.info", "text-bg-secondary", 1)
    case object Warn extends Outcome("health.outcome.warn", "text-bg-warning", 2)
    case object Fail extends Outcome("health.outcome.fail", "text-bg-danger", 3)
  }
  import Outcome._

  /** A hint: a message key with numeric and id arguments only (plan §3.3), and the result it
    * gives its check.
    */
  final case class Hint(outcome: Outcome, key: String, args: Seq[Long] = Nil) {

    /** The hint's text: an argument its pattern formats as a number (a plural, `{i,number}`) is
      * passed as one; the others (ids, round numbers) as text, never grouped ("12,345"), as
      * [[JobError.message]] passes a job error's.
      */
    def message(implicit messages: play.api.i18n.Messages): String = {
      val numeric = JobError.numericArgs(key)
      messages(key, args.zipWithIndex.map { case (a, i) => if (numeric(i)) a else a.toString }: _*)
    }
  }

  /** A check (`code`: H1, H2, …) with its hints; its result the worst of theirs. */
  final case class Check(code: String, hints: Seq[Hint]) {
    def outcome: Outcome = hints.map(_.outcome).maxByOption(_.rank).getOrElse(Pass)
    def titleKey: String = s"health.check.$code"
  }

  /** The thresholds (`diagnostics.health.*`), tuned in D-1a's review on a production copy.
    *
    * @param recentDays          H12 only for a contest with a job, a new round or a round's status
    *                            change in these days; a paused round counts as open (H7, H8) only
    *                            if it was stopped (or created) in these days
    * @param distributingMinutes H9: a round's distribution queued or running longer than this
    * @param tinyRoundShare      H7: a round filtered down to below this share of its source, and
    * @param tinyRoundFiles      to at most this many files
    * @param noCountryShare      H2: warn when at least this share of the files, and
    * @param noCountryMin        at least this many, are in no country category; H4 likewise for
    *                            the files an import left out of those it listed
    * @param importsShown        H4: the latest import jobs listed
    * @param countriesRecordedSince H2: imports created from this day on record their files'
    *                            countries: production's since release 26.11 was deployed
    *                            (2026-11-11), its earliest release with 6a3dbef2
    */
  final case class Settings(
      recentDays: Int = 14,
      distributingMinutes: Int = 30,
      tinyRoundShare: Double = 0.01,
      tinyRoundFiles: Int = 10,
      noCountryShare: Double = 0.05,
      noCountryMin: Int = 5,
      importsShown: Int = 20,
      countriesRecordedSince: Instant = Instant.parse("2026-11-11T00:00:00Z")
  )

  object Settings {
    def apply(config: Configuration): Settings = {
      val d = Settings()
      def int(key: String, default: Int) = config.getOptional[Int](s"diagnostics.health.$key").getOrElse(default)
      def double(key: String, default: Double) = config.getOptional[Double](s"diagnostics.health.$key").getOrElse(default)
      Settings(int("recentDays", d.recentDays), int("distributingMinutes", d.distributingMinutes),
        double("tinyRoundShare", d.tinyRoundShare), int("tinyRoundFiles", d.tinyRoundFiles),
        double("noCountryShare", d.noCountryShare),
        int("noCountryMin", d.noCountryMin), int("importsShown", d.importsShown),
        config.getOptional[String]("diagnostics.health.countriesRecordedSince")
          .map(day => java.time.LocalDate.parse(day).atStartOfDay(java.time.ZoneOffset.UTC).toInstant)
          .getOrElse(d.countriesRecordedSince))
    }
  }

  /** An import job of the contest, with its params and state, and who started it as the viewer
    * may see it ([[Creator]]).
    */
  final case class ImportFacts(job: Job, params: ImportParams, state: ImportState, creator: Option[Creator],
      identity: ContestIdentity.SourceCheck) {
    def prefixless: Boolean = params.titleCount == 0 && SourceName.prefixless(params.source)
    def keptNoFiles: Boolean = ImageImportHandler.foundNoFiles(job)
  }

  /** Who started a job: a member of this contest by name; anyone else by name for root only
    * (`member = false`), and for an admin as "an account outside this contest" (`name` None).
    * A name has any address typed into it masked (AccountSearch.maskEmailsIn).
    */
  final case class Creator(name: Option[String], member: Boolean)

  /** A round's source: its previous round, with its files, or the contest's files (`round` None). */
  final case class RoundSource(round: Option[Round], files: Long)

  /** A filter of a round: its label's key and its value as stored. */
  final case class Filter(key: String, value: String)

  /** A round's distribution job and its count check's numbers: the candidates it read, the files
    * it added, and on a failed check (`job.error.count.mismatch`) the round's files and the
    * expected number.
    */
  final case class Distribution(job: Job, candidatesSeen: Long, imagesAdded: Long, imagesBefore: Option[Long],
      removed: Long, mismatch: Option[(Long, Long)]) {
    def expected: Long = imagesBefore.getOrElse(0L) + imagesAdded - removed
  }

  /** A round of the contest as the report shows it. `flagged`: the codes of the checks that
    * name it; `recent`: stopped (its status changed) or, without a recorded change, created within
    * `recentDays`; `excluded`: the files of the rounds it leaves out ("Leave out files of rounds").
    */
  final case class RoundFacts(round: Round, files: Long, jurors: Int, sources: Seq[RoundSource], filters: Seq[Filter],
      distribution: Option[Distribution], leftFrozen: Int, flagged: Seq[String], recent: Boolean = true,
      excluded: Long = 0L) {

    /** Running, or stopped lately (H7, H8). Nothing marks a round finished: V105 made every
      * inactive round `paused`, so a past year's rounds are paused too, and are not open.
      */
    def open: Boolean = round.lifecycle == Round.Status.Active || (round.lifecycle == Round.Status.Paused && recent)
  }

  /** The files in each of the contest's country categories, and in none (H2); None for a contest
    * of one country (its imports put files in country categories only from a page source). Files
    * deleted on Commons are not counted, as in the contest's `files`.
    *
    * Imports record a file's country only since [[Settings.countriesRecordedSince]] (6a3dbef2), with
    * no backfill, and which import brought a file isn't stored (D-6). So a file in no country
    * category is surely one only among the files that recording imports brought: `noneRecorded`
    * is the least number of those in none, `min(recordedFiles, files) - inCountries` (the
    * recording imports' inserted and linked files, less every file with a country; an older file
    * with a country makes it smaller, never larger). A contest with older files and one recent
    * winners page import gets 0 there, not its old files as "no country"; one whose files all came
    * by recording imports gets `none`.
    *
    * @param none          files in no country category, recorded or not
    * @param noneRecorded  of them, at least this many brought by imports that record countries
    */
  final case class Countries(byCountry: Seq[(String, Int)], none: Long, noneRecorded: Long) {

    /** Whether any file's country is recorded; an older contest has none. */
    def recorded: Boolean = byCountry.nonEmpty
  }

  final case class Report(
      contest: ContestJury,
      identity: ContestIdentity,
      files: Long,
      contestSource: Option[ContestIdentity.SourceCheck],
      imports: Seq[ImportFacts],
      countries: Option[Countries],
      rounds: Seq[RoundFacts],
      checks: Seq[Check],
      recentActivity: Boolean
  ) {
    def check(code: String): Option[Check] = checks.find(_.code == code)
    def outcome: Outcome = checks.map(_.outcome).maxByOption(_.rank).getOrElse(Pass)
  }

  /** The contest's import jobs (files and monument ids), the newest first, at most `limit`. */
  def importJobs(contestId: Long, limit: Int)(implicit session: DBSession = AutoSession): Seq[Job] =
    JobJdbc.ofContest(contestId, Seq(ImageImportHandler.Kind), limit)

  /** H2's read: the countries of the contest's files (one query of its files' links to its
    * country categories, [[ImageJdbc.categoryCountryLinks]], cached as the country menus cache it),
    * by country, the most first; and the files in none ([[Countries]]: `recordedFiles`, the files
    * that imports recording countries brought).
    */
  def countries(contest: ContestJury, files: Long, recordedFiles: Long): Option[Countries] =
    for {
      categoryId <- contest.categoryId
      if ContestCountries.ofManyCountries(contest)
    } yield {
      val prefixes = ContestCountries.prefixes(contest)
      val byFile = CountryFilesCache.countries(categoryId, prefixes)(
        ContestCountries.fileCountries(contest, ImageJdbc.categoryCountryLinks(categoryId, prefixes)))
      val live = if (byFile.isEmpty) byFile else byFile -- deletedFiles(categoryId)
      val byCountry = live.values.groupMapReduce(identity)(_ => 1)(_ + _).toSeq.sortBy { case (c, n) => (-n, c) }
      Countries(byCountry, math.max(files - live.size, 0L), math.max(math.min(recordedFiles, files) - live.size, 0L))
    }

  /** The category's files deleted on Commons (V63), by the deleted files' index (V64): few. */
  private def deletedFiles(categoryId: Long)(implicit session: DBSession = AutoSession): Set[Long] =
    sql"""SELECT i.page_id FROM images i STRAIGHT_JOIN category_members cm
            ON cm.category_id = $categoryId AND cm.page_id = i.page_id
          WHERE i.deleted_at IS NOT NULL""".map(_.long(1)).list().toSet

  /** The round's filters as stored, those set only. */
  def filters(round: Round): Seq[Filter] = {
    def opt(key: String, value: Option[Any]) =
      value.map(_.toString.trim).filter(v => v.nonEmpty && v != "0").map(Filter(s"health.filter.$key", _))
    Seq(
      opt("selectedBy", round.prevSelectedBy),
      opt("rejectedBy", round.prevRejectedBy),
      opt("minAvgRate", round.prevMinAvgRate),
      opt("topImages", round.topImages),
      opt("category", round.category),
      opt("excludeCategory", round.excludeCategory),
      opt("categoryClause", round.categoryClause),
      opt("regions", round.regions),
      opt("monuments", round.monuments),
      opt("minMpx", round.minMpx),
      opt("minImageSize", round.minImageSize),
      opt("mediaType", round.mediaType.filterNot(_ == db.scalikejdbc.MediaType.All)),
      opt("specialNomination", round.specialNomination),
      opt("excludeRounds", Option.when(round.excludeRounds.nonEmpty)(round.excludeRounds.mkString(", ")))
    ).flatten
  }

  /** Filters that only narrow a round down from its source by votes or rank (a next round's): a
    * round with only these is expected to be much smaller than its source (H7's tiny check skips it).
    */
  private val VoteFilters = Set("health.filter.selectedBy", "health.filter.rejectedBy", "health.filter.minAvgRate",
    "health.filter.topImages")

  /** Filters that make a round small on purpose: a special nomination, a monuments list, a category
    * nomination (its category or category rule). H7's tiny check skips a round with one.
    */
  private val NominationFilters = Set("health.filter.specialNomination", "health.filter.monuments",
    "health.filter.category", "health.filter.categoryClause")

  /** The filters that narrow a round down by its files' own properties: H7's tiny check is for a
    * round they left almost empty. "Leave out files of rounds" isn't one: it makes the source smaller.
    */
  private val NarrowingFilters = Set("health.filter.regions", "health.filter.minMpx", "health.filter.minImageSize",
    "health.filter.mediaType", "health.filter.excludeCategory")

  /** The report on the contest for `viewer` (root, or one of its admins: the controller checks). */
  def report(contest: ContestJury, viewer: User, settings: Settings = Settings(), now: Instant = Instant.now()): Report = {
    val contestId = contest.getId
    val files = ImageJdbc.countByContest(contest)
    val identity = ContestIdentity.of(contest)

    // ── imports (H1, H4, H5) ──────────────────────────────────────────────
    val jobs = importJobs(contestId, settings.importsShown)
    val creators = creatorsOf(contestId, jobs.flatMap(_.createdBy), viewer)
    val imports = jobs.map { job =>
      val params = ImageImportHandler.paramsOf(job)
      ImportFacts(job, params, ImageImportHandler.stateOf(job), job.createdBy.flatMap(creators.get),
        ContestIdentity.check(contest, params.source))
    }
    val fileImports = imports.filter(_.params.mode == ImageImportHandler.Mode.Import)
    val contestSource = contest.images.map(_.trim).filter(_.nonEmpty).map(ContestIdentity.check(contest, _))

    val h1 = {
      val mismatched = (contestSource.toSeq ++ fileImports.map(_.identity)).filter(_.mismatch).map(_.source).distinct
      val named = (contestSource.toSeq ++ fileImports.map(_.identity)).exists(s => !s.identity.isEmpty)
      Check("H1",
        if (mismatched.nonEmpty) Seq(Hint(Warn, "health.h1.warn", Seq(mismatched.size.toLong)))
        else if (named) Seq(Hint(Pass, "health.h1.pass"))
        else Seq(Hint(Info, "health.h1.unknown")))
    }

    // ── files by country (H2) ─────────────────────────────────────────────
    // the files imports recording countries brought: every done one's of the contest, not only those listed
    val recordedFiles = importJobs(contestId, 1000).filter(j => j.isDone && !j.createdAt.isBefore(settings.countriesRecordedSince))
      .map(j => j -> ImageImportHandler.paramsOf(j)).filter(_._2.mode == ImageImportHandler.Mode.Import)
      .map { case (j, _) => val st = ImageImportHandler.stateOf(j); st.inserted + st.linked }.sum
    val countryCounts = countries(contest, files, recordedFiles)
    val h2 = Check("H2", countryCounts match {
      case None                       => Seq(Hint(Info, "health.h2.national"))
      case Some(_) if files == 0      => Seq(Hint(Info, "health.h2.nofiles"))
      // countries not recorded (imports from before they were): no file is "in none" for that
      case Some(c) if !c.recorded     => Seq(Hint(Info, "health.h2.unrecorded"))
      case Some(c) =>
        val n = c.noneRecorded
        val counted =
          if (n >= settings.noCountryMin && n >= files * settings.noCountryShare) Seq(Hint(Warn, "health.h2.warn", Seq(n, files)))
          else if (n > 0) Seq(Hint(Info, "health.h2.few", Seq(n, files)))
          else Nil
        // files in none that older imports may have brought: not counted
        val older = Option.when(c.none > n)(Hint(Info, "health.h2.partly", Seq(c.none - n)))
        if (counted.isEmpty && older.isEmpty) Seq(Hint(Pass, "health.h2.pass")) else counted ++ older
    })

    val h4 = Check("H4",
      if (imports.isEmpty) Seq(Hint(Info, "health.h4.none"))
      else {
        // a later done import of the same source that kept files: a failed or empty one before it is history
        def laterDone(i: ImportFacts) =
          imports.find(n => n.job.id > i.job.id && n.job.isDone && n.params.source == i.params.source && !n.keptNoFiles)
        val hints = imports.flatMap { i =>
          val left = i.state.notInCountries
          (i.job.isFailed || i.keptNoFiles, laterDone(i)) match {
            case (true, Some(later)) => Seq(Hint(Info, "health.h4.superseded", Seq(i.job.id, later.job.id)))
            case _ if i.job.isFailed => Seq(Hint(Fail, "health.h4.failed", Seq(i.job.id)))
            case _ if i.keptNoFiles  => Seq(Hint(Warn, "health.h4.nofiles", Seq(i.job.id)))
            case _ if i.job.isDone && left >= settings.noCountryMin && left >= i.state.seen * settings.noCountryShare =>
              Seq(Hint(Warn, "health.h4.notincountries", Seq(i.job.id, left)))
            // a few: a winners page's icons and logos (ImageImportHandler)
            case _ if i.job.isDone && left > 0 =>
              Seq(Hint(Info, "health.h4.notincountries.few", Seq(i.job.id, left, i.state.seen)))
            case _ => Nil
          }
        }
        if (hints.isEmpty) Seq(Hint(Pass, "health.h4.pass")) else hints
      })

    val h5 = {
      val pages = fileImports.filter(_.prefixless)
      val hints = pages.map { i =>
        if (i.keptNoFiles) Hint(Warn, "health.h5.warn", Seq(i.job.id)) else Hint(Info, "health.h5.page", Seq(i.job.id))
      } ++ contestSource.filter(s => SourceName.prefixless(s.source) && !pages.exists(_.params.source == s.source))
        .map(_ => Hint(Info, "health.h5.contest"))
      Check("H5", if (hints.isEmpty) Seq(Hint(Pass, "health.h5.pass")) else hints)
    }

    // ── rounds (H7–H10, H12) ──────────────────────────────────────────────
    val rounds = Round.findByContest(contestId).filterNot(_.lifecycle == Round.Status.Cancelled)
    val roundIds = rounds.flatMap(_.id)
    // uncached: the report shows the stored counts now
    val counts = RoundImageJdbc.countsIn(roundIds).map { case (k, v) => k -> v.toLong }
    val jurors = RoundUser.activeCounts(roundIds)
    val distributions = JobJdbc.latestBySubjects(RoundDistributionHandler.Kind, RoundDistributionHandler.SubjectType, roundIds)
    val frozen = RoundFreezes.leftFrozen(rounds)
    val recentRounds = recentlyChanged(contestId, settings.recentDays)
    val mixed = h1.outcome == Warn || h2.outcome == Warn

    val facts = rounds.map { round =>
      val id = round.getId
      val previous = round.previousIds
      val sources =
        if (previous.isEmpty) Seq(RoundSource(None, files))
        else previous.map(p => RoundSource(rounds.find(_.id.contains(p)), counts.getOrElse(p, 0L)))
      RoundFacts(round, counts.getOrElse(id, 0L), jurors.getOrElse(id, 0), sources, filters(round),
        distributions.get(id).map(distribution), frozen.getOrElse(id, 0), Nil, recentRounds(id),
        round.excludeRounds.map(counts.getOrElse(_, 0L)).sum)
    }

    def minutesSince(t: Instant) = Duration.between(t, now).toMinutes

    val h7 = facts.flatMap { f =>
      // the files it could get: its source's, but those of the rounds it leaves out
      val source = math.max(f.sources.map(_.files).sum - f.excluded, 0L)
      val keys = f.filters.map(_.key).toSet
      val tinyByFilters = keys.exists(NarrowingFilters) && !keys.exists(VoteFilters) && !keys.exists(NominationFilters)
      if (!f.open || f.round.distributing) None
      else if (f.files == 0) Some(Hint(Fail, "health.h7.empty", Seq(f.round.number)))
      else if (tinyByFilters && source > 0 && f.files < source * settings.tinyRoundShare && f.files <= settings.tinyRoundFiles)
        Some(Hint(Warn, "health.h7.tiny", Seq(f.round.number, f.files, source)))
      else None
    }
    // no source round and no filter: every file of the contest, those of later imports too
    val h8 = facts.flatMap { f =>
      Option.when(mixed && f.open && f.round.previousIds.isEmpty && f.filters.isEmpty && f.files > 0)(
        Hint(Warn, "health.h8.warn", Seq(f.round.number)))
    }
    val h9 = facts.flatMap { f =>
      val creating = f.round.distributing || f.round.lifecycle == Round.Status.Draft
      f.distribution.map(_.job) match {
        case Some(j) if j.isFailed || j.status == JobStatus.Cancelled =>
          // a failed or cancelled fill: the round stays hidden (being created), or keeps what it had
          Option.when(creating || j.isFailed)(Hint(Fail, "health.h9.failed", Seq(f.round.number, j.id)))
        case Some(j) if j.isActive && minutesSince(j.createdAt) >= settings.distributingMinutes =>
          Some(Hint(Warn, "health.h9.slow", Seq(f.round.number, minutesSince(j.createdAt), j.id)))
        case None if f.round.distributing && !f.round.inReview =>
          Some(Hint(Warn, "health.h9.nojob", Seq(f.round.number)))
        case _ => None
      }
    }
    val h10 = facts.filter(_.leftFrozen > 0).map(f => Hint(Fail, "health.h10.fail", Seq(f.round.number)))

    val recent = recentActivity(contestId, settings.recentDays)
    // only for a contest with recent activity (the maintainer's decision, as the other checks):
    // each open round no juror can vote in (the Rounds table's icon, the statistics page's
    // notice: RoundUser.openWithoutActiveJuror), else whether any round is open to jurors, by the
    // same "open" (frozen while open too: Round.openToJurorsIds)
    val h12 = {
      lazy val openToJurors = Round.openToJurorsIds(rounds)
      lazy val nobodyVotes = RoundUser.openWithoutActiveJuror(rounds)
      lazy val noVoter = facts.filter(f => nobodyVotes.contains(f.round.getId))
        .map(f => Hint(Warn, "health.h12.jurorspaused", Seq(f.round.number)))
      if (!recent) Seq(Hint(Info, "health.h12.quiet", Seq(settings.recentDays.toLong)))
      else if (noVoter.nonEmpty) noVoter
      else if (openToJurors.isEmpty) Seq(Hint(Info, "health.h12.noactive"))
      else Seq(Hint(Pass, "health.h12.pass"))
    }

    def orPass(code: String, hints: Seq[Hint]) = Check(code, if (hints.isEmpty) Seq(Hint(Pass, s"health.${code.toLowerCase}.pass")) else hints)
    val roundChecks = Seq(orPass("H7", h7), orPass("H8", h8), orPass("H9", h9), orPass("H10", h10), Check("H12", h12))

    // each round with the checks that name it (their first argument is its number)
    val flaggedFacts = facts.map { f =>
      val codes = roundChecks.filter(c => c.hints.exists(h => h.outcome.rank >= Warn.rank && h.args.headOption.contains(f.round.number)))
        .map(_.code)
      f.copy(flagged = codes)
    }

    Report(contest, identity, files, contestSource, imports, countryCounts, flaggedFacts,
      Seq(h1, h2, h4, h5) ++ roundChecks, recent)
  }

  private def distribution(job: Job): Distribution = {
    val st = RoundDistributionHandler.stateOf(job)
    val mismatch = job.failure.filter(_.key == JobError.CountMismatchKey).flatMap { e =>
      e.args match {
        case Seq(_, count, expected) => for (c <- count.toLongOption; x <- expected.toLongOption) yield c -> x
        case _                       => None
      }
    }
    Distribution(job, st.candidatesSeen, st.imagesAdded, st.imagesBefore, st.removed, mismatch)
  }

  /** A job of the contest, a new round or a round's status change in the last `days` (the
    * database's clock, as the rows' times).
    */
  private def recentActivity(contestId: Long, days: Int)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT EXISTS(SELECT 1 FROM job WHERE contest_id = $contestId AND created_at >= NOW() - INTERVAL $days DAY)
            OR EXISTS(SELECT 1 FROM round WHERE contest_id = $contestId
                      AND COALESCE(status_changed_at, created_at) >= NOW() - INTERVAL $days DAY)"""
      .map(_.boolean(1)).single().getOrElse(false)

  /** The contest's rounds by whether their status changed (or, with no change recorded, they were
    * created) in the last `days`.
    */
  private def recentlyChanged(contestId: Long, days: Int)(implicit session: DBSession = AutoSession): Map[Long, Boolean] =
    sql"""SELECT id, COALESCE(status_changed_at, created_at) >= NOW() - INTERVAL $days DAY FROM round
          WHERE contest_id = $contestId""".map(rs => rs.long(1) -> rs.boolean(2)).list().toMap.withDefaultValue(false)

  /** The jobs' creators as `viewer` may see them: members of the contest by name (masked), others
    * by name for root only.
    */
  private def creatorsOf(contestId: Long, ids: Seq[Long], viewer: User): Map[Long, Creator] =
    if (ids.isEmpty) Map.empty
    else {
      val distinct = ids.distinct
      val members = User.contestMembers(contestId, ids = Some(distinct)).flatMap(u => u.id.map(_ -> u)).toMap
      val others = if (viewer.isRoot) User.findByIds(distinct.filterNot(members.contains)).flatMap(u => u.id.map(_ -> u)).toMap
                   else Map.empty[Long, User]
      distinct.map { id =>
        id -> members.get(id).map(u => Creator(Some(services.AccountSearch.maskEmailsIn(u.fullname)), member = true))
          .orElse(others.get(id).map(u => Creator(Some(services.AccountSearch.maskEmailsIn(u.fullname)), member = false)))
          .getOrElse(Creator(None, member = false))
      }.toMap
    }

  private val TimeFormat = java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm 'UTC'")
    .withZone(java.time.ZoneOffset.UTC)

  /** A job's time as the page shows it, in UTC (the logs' zone). */
  def time(at: Instant): String = TimeFormat.format(at)

  /** DG10: the import form's warning for a typed source, before the job is queued; never a refusal. */
  def sourceWarning(contest: ContestJury, source: String): Option[ContestIdentity.SourceCheck] =
    Option(source).map(_.trim).filter(_.nonEmpty).map(ContestIdentity.check(contest, _)).filter(_.mismatch)
}
