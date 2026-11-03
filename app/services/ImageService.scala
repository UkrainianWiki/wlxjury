package services

import db.scalikejdbc.{CategoryJdbc, CategoryLinkJdbc, ContestJuryJdbc, ImageJdbc}
import org.intracer.wmua.{ContestJury, Image}
import play.api.Logging
import scalikejdbc.{DB, DBSession}
import services.imports.ImageImportHandler.Mode
import services.imports.{ImageImportHandler, ImportParams, ImportSource, MonumentListImportHandler}
import services.jobs.{Job, JobJdbc, JobScheduler}

import javax.inject.Inject

/** Imports a contest's files from Commons, and updates their monument ids and the
  * monument lists: as background jobs ([[ImageImportHandler]],
  * [[MonumentListImportHandler]]), started here.
  */
class ImageService @Inject() (
    jobs: JobScheduler,
    imageImport: ImageImportHandler,
    monumentLists: MonumentListImportHandler
) extends Logging {

  /** Starts adding to the contest's files those of `source` (a category, a template or a
    * page), which becomes the contest's source; or, with a pasted `titles` list, the listed
    * files instead, and the contest's source stays as it was.
    *
    * Imports only add: the contest's files are those of all its imports, in a category of
    * its own ([[ImageService.ownCategory]]), so its rounds all choose from one set of files.
    * Another contest with the same source, or a list, never adds to it.
    * @return the new job (Right), or the contest's import that is running already (Left)
    */
  def importFiles(contest: ContestJury, source: String, titles: String, createdBy: Option[Long] = None): Either[Job, Job] = {
    val listed = ImportSource.titles(titles)
    start { implicit session =>
      val categoryId = CategoryJdbc.findOrInsert(ImageService.ownCategory(contest.getId))
      val params = ImportParams(contest.getId, Mode.Import, source, Some(categoryId), listed, contest.monumentIdTemplate)
      val job = jobs.enqueue(imageImport.newJob(params, createdBy))
      if (job.isRight) {
        val current = ContestJuryJdbc.findById(contest.getId)
        // A contest that has none yet starts its own with the files it has: those of its
        // source's category, which other contests with the same source may share.
        current.flatMap(_.categoryId).filter(_ != categoryId).foreach(CategoryLinkJdbc.copyIgnore(_, categoryId))
        val images = if (listed.isEmpty) Some(source) else current.flatMap(_.images)
        ContestJuryJdbc.setImagesSource(contest.getId, images, Some(categoryId))
      }
      job
    }
  }

  /** Starts, for a contest with a monument id template, setting the monument ids of the
    * source's files from their text and, for a Ukrainian contest, updating its monument
    * lists: two jobs, independent of each other (a file's monument id is the id in its
    * text; the galleries look the monument up by it).
    * @return the jobs, each as [[importFiles]]; none without a monument id template
    */
  def updateMonuments(contest: ContestJury, source: String, createdBy: Option[Long] = None): Seq[Either[Job, Job]] =
    contest.monumentIdTemplate.toSeq.flatMap { template =>
      val params = ImportParams(contest.getId, Mode.MonumentIds, source, monumentIdTemplate = Some(template))
      val created = DB.localTx { implicit session =>
        monumentLists.newJob(contest, createdBy).map(jobs.enqueue).toSeq :+
          jobs.enqueue(imageImport.newJob(params, createdBy))
      }
      created.foreach(_.foreach(j => jobs.started(j.id)))
      created
    }

  /** The contest's latest job of each import kind, the newest first. */
  def latestJobs(contestId: Long): Seq[Job] =
    Seq(ImageImportHandler.Kind, MonumentListImportHandler.Kind)
      .flatMap(kind => JobJdbc.latestBySubjects(kind, ImageImportHandler.SubjectType, Seq(contestId)).get(contestId))
      .sortBy(-_.id)

  /** What the job does, for messages, e.g. "Importing files from Category:…". */
  def title(job: Job): String = jobs.handler(job.kind).fold(job.kind)(_.title(job))

  /** Creates the job in a transaction, and starts it once that has committed. */
  private def start(create: DBSession => Either[Job, Job]): Either[Job, Job] = {
    val job = DB.localTx(create)
    job.foreach(j => jobs.started(j.id))
    job
  }
}

object ImageService {

  /** The title of the contest's own category: the files of all its imports. A `#` can't be
    * in a page title, so no Commons source (whose category is by its title) has it.
    */
  def ownCategory(contestId: Long): String = s"#contest $contestId"

  /** The fetched images that are stored already but changed: those
    * [[db.scalikejdbc.ImageJdbc.update]] has something to write for. Compared on the fields
    * it writes ([[ImageJdbc.fileInfo]], the monument id and the description).
    */
  def changedImages(fetched: Seq[Image], existing: Seq[Image]): Seq[Image] = {
    def stored(i: Image) = ImageJdbc.fileInfo(i) ++ Seq("monumentId" -> i.monumentId, "description" -> i.description)
    val existingById = existing.map(i => i.pageId -> stored(i)).toMap
    fetched.filter(i => existingById.get(i.pageId).exists(_ != stored(i)))
  }
}
