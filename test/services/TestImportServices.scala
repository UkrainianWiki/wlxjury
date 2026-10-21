package services

import play.api.Configuration
import services.imports._
import services.jobs.JobScheduler

/** An [[ImageService]] for specs without a Play app, over `commons` and `lists`: its
  * import jobs run inline, to their end, in the call that starts them (as with
  * `jobs.mode = inline`).
  *
  * @param pageSize files per import step; [[TestImportServices.AccountPageSize]] leaves
  *                 `jobs.commonsImport.pageSize` unset, so it follows `commons`' batch size
  */
class TestImportServices(
    commons: CommonsSource,
    pageSize: Int = ImageImportHandler.DefaultPageSize,
    lists: MonumentListSource = new FakeMonumentListSource(),
    listPageSize: Int = MonumentListImportHandler.DefaultPageSize
) {

  val imageImport: ImageImportHandler =
    new ImageImportHandler(
      commons,
      if (pageSize == TestImportServices.AccountPageSize) Configuration.empty
      else Configuration("jobs.commonsImport.pageSize" -> pageSize)
    )

  val monumentLists: MonumentListImportHandler =
    new MonumentListImportHandler(lists, Configuration("jobs.monumentImport.pageSize" -> listPageSize))

  val scheduler: JobScheduler = TestRoundServices.inlineScheduler(imageImport, monumentLists)

  val imageService: ImageService = new ImageService(scheduler, imageImport, monumentLists)
}

object TestImportServices {

  /** A `pageSize` that leaves the step size to the Commons account's batch size. */
  val AccountPageSize = 0
}
