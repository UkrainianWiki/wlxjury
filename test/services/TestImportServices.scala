package services

import play.api.Configuration
import services.imports.{CommonsSource, ImageImportHandler}
import services.jobs.JobScheduler

/** An [[ImageService]] for specs without a Play app, over `source`: its import jobs run
  * inline, to their end, in the call that starts them (as with `jobs.mode = inline`).
  */
class TestImportServices(source: CommonsSource, pageSize: Int = ImageImportHandler.DefaultPageSize) {

  val imageImport: ImageImportHandler =
    new ImageImportHandler(source, Configuration("jobs.commonsImport.pageSize" -> pageSize))

  val scheduler: JobScheduler = TestRoundServices.inlineScheduler(imageImport)

  val imageService: ImageService = new ImageService(scheduler, imageImport, new MonumentService)
}
