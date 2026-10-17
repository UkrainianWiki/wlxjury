package services

import play.api.Configuration
import services.imports._
import services.jobs.JobScheduler

/** An [[ImageService]] for specs without a Play app, over `commons` and `lists`: its
  * import jobs run inline, to their end, in the call that starts them (as with
  * `jobs.mode = inline`).
  */
class TestImportServices(
    commons: CommonsSource,
    pageSize: Int = ImageImportHandler.DefaultPageSize,
    lists: MonumentListSource = new FakeMonumentListSource(),
    listPageSize: Int = MonumentListImportHandler.DefaultPageSize
) {

  val imageImport: ImageImportHandler =
    new ImageImportHandler(commons, Configuration("jobs.commonsImport.pageSize" -> pageSize))

  val monumentLists: MonumentListImportHandler =
    new MonumentListImportHandler(lists, Configuration("jobs.monumentImport.pageSize" -> listPageSize))

  val scheduler: JobScheduler = TestRoundServices.inlineScheduler(imageImport, monumentLists)

  val imageService: ImageService = new ImageService(scheduler, imageImport, monumentLists)
}
