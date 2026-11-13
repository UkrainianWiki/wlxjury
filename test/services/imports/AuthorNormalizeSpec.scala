package services.imports

import db.scalikejdbc.{SharedTestDb, TestDb, User}
import org.intracer.wmua.Image
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.TestRoundServices
import services.jobs.{Job, JobJdbc, JobScheduler, JobStatus}

import java.util.concurrent.atomic.AtomicInteger

/** The `author_normalize` job ([[AuthorNormalizeHandler]]): the authors older imports stored
  * as wikitext become the accounts an import stores today, by page id, window by window; a
  * second run changes nothing. Commons is faked: no test calls it.
  */
class AuthorNormalizeSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def root: User = User("root", "root@example.com", id = Some(1L))

  private def image(id: Long, author: Option[String]) = Image(id, s"File:Image $id.jpg", None, None, 640, 480, author = author)

  /** Stored authors, as older imports and today's left them, by page id. */
  private val stored: Seq[(Long, Option[String])] = Seq(
    1L -> Some("[[User:AbC|x]]"),                    // these two are equal in utf8mb4_general_ci
    2L -> Some("[[User:ABC|x]]"),
    3L -> Some("Plain Name"),                        // as an import stores it today
    4L -> Some("[[User:Петро_Грушко|Петро]]"),        // the target, not the label; `_` a space
    5L -> Some("{{SergiyBobokPhotos}}"),             // a credit template that links to a user
    6L -> None,
    7L -> Some("{{MO}}"),                            // links to nobody: the original uploader
    8L -> Some("{{MO}}"),                            // and deleted on Commons: none
    9L -> Some("{{unknown|author}}"),                // nobody
    10L -> Some("{{own}} by [[w:Museum]]"),          // no user: the uploader, a bot here: none
    11L -> Some("[[User:Liliia2022|Liliia2022]]"),
    12L -> Some("Liliia2022"),
    13L -> Some("[[User:???????? ????????|Петро]]"), // an encoding loss: read again from Commons
    14L -> Some("[[User:???? ????|x]]"),             // the same, deleted on Commons: left as it is
    15L -> Some("[[User:?????|x]]"),                 // its page names no author: the uploader
    16L -> Some("[HTTPS://example.com Site]")        // not markup ([http is): not read
  )

  private val expected: Map[Long, Option[String]] = Map(
    1L -> Some("AbC"),
    2L -> Some("ABC"),
    3L -> Some("Plain Name"),
    4L -> Some("Петро Грушко"),
    5L -> Some("Sergiy Bobok"),
    6L -> None,
    7L -> Some("Original Uploader"),
    8L -> None,
    9L -> None,
    10L -> None,
    11L -> Some("Liliia2022"),
    12L -> Some("Liliia2022"),
    13L -> Some("Real Name"),
    14L -> Some("[[User:???? ????|x]]"),
    15L -> Some("Uploader15"),
    16L -> Some("[HTTPS://example.com Site]")
  )

  /** Commons: the credit templates' pages, and the files still there with their uploaders
    * (8 is deleted: not there).
    */
  private class Commons extends FakeCommonsSource(
        Seq(
          FakeFile(image(7L, None), uploaders = Seq("Retoucher", "Original Uploader")),
          FakeFile(image(10L, None), uploaders = Seq("MuseumUploadBot")),
          FakeFile(image(13L, None), text = Some("{{Information|description=d|author=[[User:Real_Name|Real]]}}")),
          FakeFile(image(15L, None), text = Some("{{Information|description=d}}"), uploaders = Seq("Uploader15"))
        ),
        templates = Map(
          "SergiyBobokPhotos" -> "Photo by [[User:Sergiy Bobok|Sergiy Bobok]]",
          "MO" -> "A photographer of the museum"
        )
      ) {
    /** Fails the n-th call of [[creditTemplateUsers]] (from 1), once. */
    @volatile var failTemplateCall: Int = 0
    private val templateCalls = new AtomicInteger()

    override def creditTemplateUsers(names: Seq[String]): Map[String, String] = {
      val n = templateCalls.incrementAndGet()
      if (n == failTemplateCall) {
        failTemplateCall = 0
        throw new RuntimeException(s"Commons is down (call $n)")
      }
      super.creditTemplateUsers(names)
    }
  }

  /** Windows of 3 files, at most 2 with markup: several steps. `beforeWrite` runs after each
    * fetch, with its number (from 1), before the step's write.
    */
  private class Handler(commons: CommonsSource, beforeWrite: Int => Unit = _ => ())
      extends AuthorNormalizeHandler(commons, scanRows = 3, chunkRows = 2) {
    val fetches = new AtomicInteger()
    override def fetch(job: Job): AuthorChunk = {
      val chunk = super.fetch(job)
      beforeWrite(fetches.incrementAndGet())
      chunk
    }
  }

  private def scheduler(handler: AuthorNormalizeHandler): JobScheduler = TestRoundServices.inlineScheduler(handler)

  private def run(s: JobScheduler): Job = {
    val queued = DB.localTx(implicit session => s.enqueue(AuthorNormalizeHandler.newJob(root))).toOption.get
    s.started(queued.id)
    JobJdbc.findById(queued.id).get
  }

  private def setUp(): Unit = {
    imageDao.batchInsert(stored.map { case (id, author) => image(id, author) })
    DB.autoCommit(implicit s => sql"UPDATE images SET deleted_at = NOW(3) WHERE page_id IN (8, 14)".update(): Unit)
  }

  private def authors: Map[Long, Option[String]] = DB.readOnly { implicit s =>
    sql"SELECT page_id, author FROM images".map(rs => rs.long(1) -> rs.stringOpt(2)).list().toMap
  }

  private def withMarkup: Long = DB.readOnly { implicit s =>
    sql"SELECT COUNT(*) FROM images WHERE ${StoredAuthors.markupSql(sqls"author")}".map(_.long(1)).single().get
  }

  "the author_normalize job" should {

    "store each author as an import does, by page id, window by window" in {
      setUp()
      val commons = new Commons
      val handler = new Handler(commons)
      val job = run(scheduler(handler))
      val st = AuthorNormalizeHandler.stateOf(job)
      (job.status === JobStatus.Done) and
        (authors === expected) and
        // the question-mark account Commons names nobody for is left as it was
        (withMarkup === 1L) and
        (handler.fetches.get must beGreaterThan(3)) and
        (st.total === Some(12L)) and (st.read === 12L) and (st.changed === 11L) and
        (st.fromUploader === 2L) and (st.unresolved === 3L) and (st.restored === 2L) and
        (st.notRestored === 1L) and (st.unchanged === 0L) and (st.skipped === 0L) and
        (st.templates === Seq("MO")) and
        (job.progressDone === Some(12L)) and (job.progressTotal === Some(12L))
    }

    "change nothing when run again, nor ask Commons" in {
      setUp()
      val commons = new Commons
      run(scheduler(new Handler(commons)))
      val calls = commons.authorCalls.get
      val again = run(scheduler(new Handler(commons)))
      val st = AuthorNormalizeHandler.stateOf(again)
      // only the account Commons can't restore is read again, and left again
      (again.status === JobStatus.Done) and (authors === expected) and
        (st.total === Some(1L)) and (st.read === 1L) and (st.changed === 0L) and (st.notRestored === 1L) and
        (st.skipped === 0L) and (st.unchanged === 0L) and
        (commons.authorCalls.get === calls)
    }

    "leave an author an import wrote between the fetch and the write" in {
      setUp()
      val handler = new Handler(new Commons, beforeWrite = n =>
        if (n == 1) DB.autoCommit(implicit s => sql"UPDATE images SET author = 'Reimported' WHERE page_id = 1".update(): Unit))
      val st = AuthorNormalizeHandler.stateOf(run(scheduler(handler)))
      (authors === expected.updated(1L, Some("Reimported"))) and (st.skipped === 1L) and (st.changed === 10L) and (st.unchanged === 0L)
    }

    "resume where it failed on Retry, and finish" in {
      setUp()
      val commons = new Commons
      // the window with file 5 asks for the credit template's user
      commons.failTemplateCall = 1
      val s = scheduler(new Handler(commons))
      val failed = run(s)
      val partly = authors
      val retried = s.retry(failed.id).toOption.get
      (failed.status === JobStatus.Failed) and
        (partly(1L) === Some("AbC")) and (partly(5L) === Some("{{SergiyBobokPhotos}}")) and
        (retried.status === JobStatus.Done) and (authors === expected) and
        (AuthorNormalizeHandler.stateOf(retried).changed === 11L)
    }

    "stop when cancelled, keeping what its steps wrote; a new run finishes" in {
      setUp()
      val commons = new Commons
      lazy val s: JobScheduler = scheduler(new Handler(commons, beforeWrite = n =>
        if (n == 2) JobJdbc.activeByDedupeKey(AuthorNormalizeHandler.DedupeKey).foreach(j => s.cancel(j.id))))
      val cancelled = JobJdbc.findById(run(s).id).get
      val partly = authors
      val next = run(scheduler(new Handler(commons)))
      (cancelled.status === JobStatus.Cancelled) and
        (partly(1L) === Some("AbC")) and (partly(2L) === Some("ABC")) and (partly(11L) === Some("[[User:Liliia2022|Liliia2022]]")) and
        (next.status === JobStatus.Done) and (authors === expected)
    }

    "run one at a time" in {
      val s = scheduler(new Handler(new Commons))
      val first = DB.localTx(implicit session => s.enqueue(AuthorNormalizeHandler.newJob(root)))
      val second = DB.localTx(implicit session => s.enqueue(AuthorNormalizeHandler.newJob(root)))
      (first must beRight) and (second.left.toOption.map(_.id) === first.toOption.map(_.id))
    }
  }
}
