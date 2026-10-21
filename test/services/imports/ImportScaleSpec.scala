package services.imports

import com.github.tototoshi.csv.CSVReader
import db.scalikejdbc.{ImageJdbc, SharedTestDb, TestDb}
import gatling.setup.HeapWatcher
import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import play.api.Configuration
import services.ImageService
import services.jobs.{JobScheduler, JobStatus}

import java.io.File
import java.lang.management.ManagementFactory
import scala.concurrent.duration._
import scala.jdk.CollectionConverters._

/** Imports the WLM UA 2025 fixture (data/wlm-UA-images-2025.csv, ~38k files) into a new
  * contest through a background scheduler, over a fake Commons with
  * `-Dimport.latencyMs` per API call, and reports the time and the heap after GC
  * (target/gatling/import-memory.txt). Off by default: `-Dimport.scale=true`.
  */
class ImportScaleSpec extends Specification with TestDb with BeforeAll {

  skipAllUnless(sys.props.get("import.scale").contains("true"))

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  private val latencyMs = sys.props.get("import.latencyMs").map(_.toLong).getOrElse(0L)
  private val pageSize = sys.props.get("import.pageSize").map(_.toInt).getOrElse(50)

  private def fixture(): Seq[Image] = {
    val reader = CSVReader.open(new File("data/wlm-UA-images-2025.csv"))
    val rows = try reader.allWithHeaders() finally reader.close()
    rows.flatMap { row =>
      row.get("page_id").flatMap(_.toLongOption).map { pageId =>
        Image(
          pageId = pageId,
          title = row.getOrElse("title", ""),
          url = row.get("url").filter(_.nonEmpty),
          pageUrl = row.get("page_url").filter(_.nonEmpty),
          width = row.get("width").flatMap(_.toIntOption).getOrElse(0),
          height = row.get("height").flatMap(_.toIntOption).getOrElse(0),
          monumentId = row.get("monument_id").filter(_.nonEmpty),
          author = row.get("author").filter(_.nonEmpty),
          size = row.get("size_bytes").flatMap(_.toIntOption),
          mime = row.get("mime").filter(_.nonEmpty)
        )
      }
    }.distinctBy(_.pageId)
  }

  /** A file page's text as on Commons: {{Information}} and the monument template. */
  private def text(i: Image) =
    s"""== {{int:filedesc}} ==
       |{{Information
       ||description={{uk|1=${i.title.stripPrefix("File:")}}}{{Monument Ukraine|${i.monumentId.getOrElse("")}}}
       ||date=2025-09-15
       ||source={{own}}
       ||author=[[User:${i.author.getOrElse("")}|${i.author.getOrElse("")}]]
       |}}
       |
       |== {{int:license-header}} ==
       |{{self|cc-by-sa-4.0}}
       |{{Wiki Loves Monuments 2025|ua}}
       |[[Category:Images from Wiki Loves Monuments 2025 in Ukraine]]""".stripMargin

  private def heapUsedAfterGcMb(): Long = {
    System.gc()
    ManagementFactory.getMemoryPoolMXBeans.asScala
      .filter(_.getType == java.lang.management.MemoryType.HEAP)
      .map(p => Option(p.getCollectionUsage).map(_.getUsed).getOrElse(0L))
      .sum / 1024 / 1024
  }

  "importing the WLM UA 2025 fixture" should {
    "hold about one page of files" in {
      SharedTestDb.truncateAll()
      val images = fixture()
      // as an account with apihighlimits for a page size over 50: one details request per step
      val commons = new FakeCommonsSource(images.map(i => FakeFile(i, Some(text(i)))), math.max(pageSize, CommonsLogin.Limit)) {
        override def listFiles(source: ImportSource, cursor: play.api.libs.json.JsObject, limit: Int): FilePage = {
          Thread.sleep(latencyMs)
          super.listFiles(source, cursor, limit)
        }
        override def files(pageIds: Seq[Long], info: Boolean, text: Boolean) = {
          Thread.sleep(latencyMs)
          super.files(pageIds, info, text)
        }
      }
      val contest = contestDao.create(None, "Wiki Loves Monuments", 2025, "Ukraine",
        monumentIdTemplate = Some("Monument Ukraine"))
      val handler = new ImageImportHandler(commons, Configuration("jobs.commonsImport.pageSize" -> pageSize))
      val scheduler = new JobScheduler(Seq(handler), JobScheduler.Settings(
        workers = 2, fetchWorkers = 2, kindLimits = Map(ImageImportHandler.Kind -> 1), pollInterval = 200.millis))
      scheduler.start()
      try {
        val service = new ImageService(scheduler, handler,
          new MonumentListImportHandler(new FakeMonumentListSource(), Configuration.empty))
        val baseline = heapUsedAfterGcMb() // the fake Commons holds the whole fixture
        HeapWatcher.start("import")
        val started = System.nanoTime()
        val job = service.importFiles(contest, "Category:Images from Wiki Loves Monuments 2025 in Ukraine", "")
          .toOption.get
        val done = scheduler.await(job.id, 60.minutes)
        val seconds = (System.nanoTime() - started) / 1e9
        val memory = HeapWatcher.end()
        val line = f"import of ${images.size} files, page size $pageSize, latency $latencyMs ms: " +
          f"$seconds%.1f s, ${ImageImportHandler.stateOf(done).inserted} inserted; heap after GC: " +
          s"baseline (with the fake Commons) $baseline MB, max during the import " +
          s"${memory.flatMap(_.maxAfterGcMb).getOrElse(-1L)} MB, ${memory.map(_.gcs).getOrElse(0)} GCs " +
          s"(${memory.map(_.fullGcs).getOrElse(0)} full), max heap ${HeapWatcher.maxHeapMb} MB"
        println(line)
        HeapWatcher.report(line, "import-memory.txt")
        done.status === JobStatus.Done
        ImageJdbc.countByContest(contestDao.findById(contest.getId).get) === images.size.toLong
      } finally scheduler.stop()
    }
  }
}
