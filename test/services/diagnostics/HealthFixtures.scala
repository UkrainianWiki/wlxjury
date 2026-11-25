package services.diagnostics

import db.scalikejdbc.{CategoryJdbc, CategoryLinkJdbc, ContestJuryJdbc, CountryFilesCache, ImageJdbc, Round, User}
import org.intracer.wmua.{ContestJury, Image}
import play.api.libs.json.Json
import scalikejdbc._
import services.ImageService
import services.distribution.RoundDistributionHandler
import services.imports.{ImageImportHandler, ImportParams, ImportState}
import services.jobs.{JobJdbc, JobStatus, NewJob, Progress}

/** Committed rows for the health report's specs (the service reads with `autoSession`): run after
  * `SharedTestDb.truncateAll()`.
  */
object HealthFixtures {

  /** A day after imports began recording countries (ContestHealth.Settings.countriesRecordedSince):
    * an import then records them, whenever the specs run.
    */
  val AfterCountriesRecorded: java.time.Instant =
    ContestHealth.Settings().countriesRecordedSince.plus(java.time.Duration.ofDays(1))

  val WleCountries = "Category:Images from Wiki Loves Earth 2020 in "
  val NepalCategory = "Category:Images from Wiki Loves Monuments 2026 in Nepal"
  val NepalPage = "Wiki Loves Monuments 2026 in Nepal"
  val NepalWinners = "Commons:Wiki Loves Monuments 2026 in Nepal/Winners"

  /** The contest with its own category (as its first import makes it), holding these files. */
  def contestWithFiles(name: String, year: Int, country: String, source: Option[String], files: Seq[Long]): ContestJury = {
    // category ids are reused after a truncation: the country cache must not keep another's
    CountryFilesCache.clear()
    val contest = ContestJuryJdbc.create(None, name, year, country)
    val categoryId = CategoryJdbc.findOrInsert(ImageService.ownCategory(contest.getId))
    ContestJuryJdbc.setImagesSource(contest.getId, source)
    ContestJuryJdbc.setCategory(contest.getId, categoryId)
    val stored = DB.readOnly(implicit s => ImageJdbc.existingIds(files.toSet)).toSet
    ImageJdbc.batchInsert(files.filterNot(stored).map(id => Image(id, s"File:Health $id.jpg", None, None, 640, 480, None)))
    DB.autoCommit(implicit s => CategoryLinkJdbc.linkIgnore(categoryId, files))
    ContestJuryJdbc.findById(contest.getId).get
  }

  /** Adds the files to the contest's category, as an import does. */
  def addFiles(contest: ContestJury, files: Seq[Long]): Unit = {
    val stored = DB.readOnly(implicit s => ImageJdbc.existingIds(files.toSet)).toSet
    ImageJdbc.batchInsert(files.filterNot(stored).map(id => Image(id, s"File:Health $id.jpg", None, None, 640, 480, None)))
    DB.autoCommit(implicit s => CategoryLinkJdbc.linkIgnore(contest.categoryId.get, files))
    CountryFilesCache.clear()
  }

  /** Puts the files in the country categories (`prefix` + country), as an import does. */
  def inCountries(prefix: String, byCountry: (String, Seq[Long])*): Unit =
    DB.autoCommit { implicit s =>
      CategoryLinkJdbc.addCountries(byCountry.flatMap { case (country, ids) => ids.map(_ -> (prefix + country)) })
    }

  /** A done (or `status`) import job of the contest from `source`, with its state's counts. */
  def importJob(contest: ContestJury, source: String, state: ImportState, status: String = JobStatus.Done,
      createdBy: Option[Long] = None, error: Option[String] = None, daysAgo: Int = 0,
      at: Option[java.time.Instant] = None): Long =
    DB.localTx { implicit s =>
      val params = ImportParams(contest.getId, ImageImportHandler.Mode.Import, source, contest.categoryId)
      val id = JobJdbc.insert(NewJob(ImageImportHandler.Kind, contest.id, Some(ImageImportHandler.SubjectType), contest.id,
        params = Json.toJsObject(params), createdBy = createdBy), None)
      JobJdbc.update(id, status, Json.toJsObject(state), Progress(), error)
      if (daysAgo > 0) sql"UPDATE job SET created_at = NOW(3) - INTERVAL $daysAgo DAY WHERE id = $id".update()
      at.foreach(t => sql"UPDATE job SET created_at = ${java.sql.Timestamp.from(t)} WHERE id = $id".update())
      id
    }

  /** A round of the contest: `previous` its source rounds, its status set as the round form's
    * lifecycle does; `files` in it (`round_image`), and `jurors` active in it.
    */
  def round(contest: ContestJury, number: Long, name: String, status: String = Round.Status.Active,
      previous: Seq[Long] = Nil, files: Seq[Long] = Nil, jurors: Seq[User] = Nil, distributing: Boolean = false,
      prevSelectedBy: Option[Int] = None, category: Option[String] = None, freezeCount: Int = 0,
      daysAgo: Int = 0, tweak: Round => Round = identity): Round = {
    val created = Round.create(tweak(Round(None, number, Some(name), contest.getId,
      active = status == Round.Status.Active, distributing = distributing,
      previous = Option.when(previous.nonEmpty)(previous.mkString(",")), prevSelectedBy = prevSelectedBy,
      category = category)))
    val id = created.getId
    DB.autoCommit { implicit s =>
      sql"UPDATE round SET status = $status, freeze_count = $freezeCount WHERE id = $id".update()
      if (daysAgo > 0)
        sql"""UPDATE round SET created_at = NOW() - INTERVAL $daysAgo DAY, status_changed_at = NOW() - INTERVAL $daysAgo DAY
              WHERE id = $id""".update()
      if (files.nonEmpty)
        sql"INSERT INTO round_image (round_id, page_id, status, added_how) VALUES (?, ?, 'in', 'fill')"
          .batch(files.map(f => Seq(id, f)): _*).apply()
      jurors.foreach(j =>
        sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES (${j.getId}, $id, 'jury', TRUE)".update())
    }
    Round.findById(id).get
  }

  /** The round's distribution job, `status`, with its state's counts and error. */
  def distributionJob(round: Round, status: String, candidatesSeen: Long, imagesAdded: Long,
      error: Option[String] = None, minutesAgo: Int = 0): Long =
    DB.localTx { implicit s =>
      val id = JobJdbc.insert(NewJob(RoundDistributionHandler.Kind, Some(round.contestId),
        Some(RoundDistributionHandler.SubjectType), round.id), None)
      val state = Json.obj("phase" -> "verify", "candidatesSeen" -> candidatesSeen, "imagesAdded" -> imagesAdded,
        "imagesBefore" -> 0)
      JobJdbc.update(id, status, state, Progress(), error)
      if (minutesAgo > 0) sql"UPDATE job SET created_at = NOW(3) - INTERVAL $minutesAgo MINUTE WHERE id = $id".update()
      id
    }

  /** An old international contest as production has many (WLE 2019): its files imported before
    * imports recorded countries (no country links) and before the job table (no import jobs), its
    * source its own category, and its rounds of years ago, all paused (V105 made every inactive
    * round `paused`): round 1 of every file, round 2 from its selected files, round 3 empty (an
    * abandoned one). Nothing in it is wrong.
    */
  def oldContest(): ContestJury = {
    val contest = contestWithFiles("Wiki Loves Earth", 2019, "", Some("Category:Images from Wiki Loves Earth 2019"),
      201L to 230L)
    val r1 = round(contest, 1, "First", status = Round.Status.Paused, files = 201L to 230L, daysAgo = 2400)
    round(contest, 2, "Second", status = Round.Status.Paused, previous = Seq(r1.getId), prevSelectedBy = Some(2),
      files = 201L to 205L, daysAgo = 2380)
    round(contest, 3, "Abandoned", status = Round.Status.Paused, previous = Seq(r1.getId), daysAgo = 2370)
    contest
  }

  /** The Nepal case (support diagnostics plan §2): an old international WLE 2020 contest of 20
    * foreign files in its country categories (imported again lately, so their countries are recorded) (Armenia, Sri Lanka, Bhutan), reused for WLM 2026 in
    * Nepal: 12 Nepal files imported from the WLM category (in none of this contest's country
    * categories), a page import that kept nothing, a winners page import that left files out as
    * in no country, the contest's source now the WLM one; round 1 ("1974") of every file and
    * round 2 ("1975") from its selected files, empty.
    */
  final case class Nepal(contest: ContestJury, admin: User, wleImport: Long, nepalImport: Long, pageImport: Long,
      winnersImport: Long, round1974: Round, round1975: Round)

  def nepal(): Nepal = {
    val old = 1L to 20L
    val nepalFiles = 101L to 112L
    val contest = contestWithFiles("Wiki Loves Earth", 2020, "", Some(NepalCategory), old ++ nepalFiles)
    inCountries(WleCountries, "Armenia" -> (1L to 8L), "Sri Lanka" -> (9L to 14L), "Bhutan" -> (15L to 20L))
    val admin = User.create(User("Nepal Admin", "nepal.admin@example.org", None, Set("admin"), contestId = contest.id))
    val juror = User.create(User("Nepal Juror", "nepal.juror@example.org", None, Set("jury"), contestId = contest.id))
    // imported again since imports record countries: its files have theirs
    val wleImport = importJob(contest, "Category:Images from Wiki Loves Earth 2020", ImportState(seen = 20, inserted = 20),
      at = Some(AfterCountriesRecorded))
    val pageImport = importJob(contest, NepalPage, ImportState(seen = 40, notInCountries = 40, byCountry = Some(true)),
      createdBy = admin.id)
    val nepalImport = importJob(contest, NepalCategory, ImportState(seen = 12, inserted = 12), createdBy = admin.id)
    val winnersImport = importJob(contest, NepalWinners,
      ImportState(seen = 15, linked = 3, notInCountries = 12, byCountry = Some(true)), createdBy = admin.id)
    val r1974 = round(contest, 1, "1974", files = old ++ nepalFiles, jurors = Seq(juror))
    val r1975 = round(contest, 2, "1975", previous = Seq(r1974.getId), prevSelectedBy = Some(1), jurors = Seq(juror))
    distributionJob(r1975, JobStatus.Done, candidatesSeen = 0, imagesAdded = 0)
    Nepal(contest, admin, wleImport, nepalImport, pageImport, winnersImport, r1974, r1975)
  }
}
