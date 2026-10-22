package services

import play.api.Configuration
import services.imports._
import services.jobs.JobScheduler

/** An [[ImageService]] for specs without a Play app, over `commons` and `lists`: its
  * import jobs run inline, to their end, in the call that starts them (as with
  * `jobs.mode = inline`).
  *
  * @param pageSize files per import step (`jobs.commonsImport.pageSize`); None leaves it
  *                 unset, so it follows `commons`' batch size
  */
class TestImportServices(
    commons: CommonsSource,
    pageSize: Option[Int] = Some(ImageImportHandler.DefaultPageSize),
    lists: MonumentListSource = new FakeMonumentListSource(),
    listPageSize: Int = MonumentListImportHandler.DefaultPageSize
) {

  val imageImport: ImageImportHandler =
    new ImageImportHandler(commons, Configuration(pageSize.map("jobs.commonsImport.pageSize" -> _).toSeq: _*))

  val monumentLists: MonumentListImportHandler =
    new MonumentListImportHandler(lists, Configuration("jobs.monumentImport.pageSize" -> listPageSize))

  val scheduler: JobScheduler = TestRoundServices.inlineScheduler(imageImport, monumentLists)

  val imageService: ImageService = new ImageService(scheduler, imageImport, monumentLists)
}
