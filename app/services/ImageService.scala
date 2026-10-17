package services

import db.scalikejdbc.{CategoryJdbc, ContestJuryJdbc}
import org.intracer.wmua.{ContestJury, Image}
import play.api.Logging
import scalikejdbc.{DB, DBSession}
import services.imports.ImageImportHandler.Mode
import services.imports.{ImageImportHandler, ImportParams, ImportSource, MonumentListImportHandler}
import services.jobs.{Job, JobScheduler}

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

  /** Starts importing the contest's files from `source` (a category, a template or a page),
    * or the files of the pasted `titles` list instead; the source becomes the contest's.
    * @return the new job (Right), or the contest's import that is running already (Left)
    */
  def importFiles(contest: ContestJury, source: String, titles: String, createdBy: Option[Long] = None): Either[Job, Job] =
    start { implicit session =>
      val categoryId = CategoryJdbc.findOrInsert(source)
      val params = ImportParams(contest.getId, Mode.Import, source, Some(categoryId), ImportSource.titles(titles),
        contest.monumentIdTemplate)
      val job = jobs.enqueue(imageImport.newJob(params, createdBy))
      if (job.isRight) ContestJuryJdbc.setImagesSource(contest.getId, Some(source))
      job
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

  /** The fetched images that are stored already but changed: those
    * [[db.scalikejdbc.ImageJdbc.update]] has something to write for. Compared on the fields
    * it writes, except pageUrl, which reading an image back doesn't return.
    */
  def changedImages(fetched: Seq[Image], existing: Seq[Image]): Seq[Image] = {
    def stored(i: Image) =
      (i.title, i.url, i.width, i.height, i.monumentId, i.description, i.size, i.mime, i.mediaType)
    val existingById = existing.map(i => i.pageId -> stored(i)).toMap
    fetched.filter(i => existingById.get(i.pageId).exists(_ != stored(i)))
  }
}
