package db.stress

import ch.qos.logback.classic.spi.ILoggingEvent
import ch.qos.logback.core.AppenderBase
import db.scalikejdbc._
import org.intracer.wmua.{ContestJury, CriteriaRate, Image}
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, Json}
import scalikejdbc._
import services.distribution.{RoundDistributionHandler, RoundImageCheckHandler}
import services.imports.{DeletedFiles, FakeCommonsSource, FakeFile}
import services.jobs.JobJdbc
import services.{RoundService, TestRoundServices}

import java.io.{File, PrintWriter}
import java.sql.{Connection, DriverManager}
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.atomic.{AtomicBoolean, AtomicLong}
import java.util.concurrent.{ConcurrentHashMap, ConcurrentLinkedQueue}
import javax.sql.DataSource
import scala.collection.mutable
import scala.jdk.CollectionConverters._
import scala.util.Random
import scala.util.control.NonFatal

/** The write paths of `vote`, `vote_aside`, `criteria_rate`, `round_image`, `round_criterion`
  * and the monument id copies, run at once against a few rounds of a seeded test database, for a
  * set time: docs/plans/2026-11-03-write-path-simplification.md. Not a spec: run on demand,
  * never in the suite.
  *
  * {{{
  * sbt 'set Test / javaOptions ++= Seq("-Xmx768m", "-Dfile.encoding=UTF-8", "-Dstress.seconds=120",
  *        "-Dstress.variants=baseline,rc")' 'Test/runMain db.stress.WritePathStress'
  * }}}
  *
  * Each path runs on threads of its own and connects as a MariaDB user of its own (`s_<path>`,
  * a routing pool registered as ScalikeJDBC's default), so the deadlocks MariaDB prints
  * (`innodb_print_all_deadlocks`) name the two paths. The code under test is the app's, called
  * as its controllers and job handlers call it; where the caller is private (the Commons
  * import's page write, the file check's write) the harness repeats its statements' order.
  *
  * Variants (`stress.variants`, comma separated, each on a freshly seeded database in one JVM):
  * `baseline` (the code as it is, at READ COMMITTED as the app's pools), `rr` (the server's old
  * default REPEATABLE READ), `retry` (every harness operation retried 3 times on a deadlock);
  * `+` combines them, `#n` repeats one (`baseline#2`).
  *
  * Production-like settings: `-Dstress.chunkRows=15000` (the job's default), `-Dstress.importPage=500`
  * (an import page's stored files), `-Dstress.importChanged=15` (the changed ones among them).
  *
  * Reported per variant (stdout, `stress.out`/summary.txt, results.json, deadlocks.txt):
  * operations, throughput and latency per path; deadlocks (`Innodb_deadlocks`, and per pair of
  * paths from the server's log); lock wait timeouts; failures the caller sees (exceptions out
  * of the app's code, after its own retries; failed jobs); job steps retried by the
  * scheduler; and the invariants at the end (round_image's, the criteria rates', the monument
  * id copies', the rounds' freeze state).
  */
object WritePathStress {

  // ── parameters ─────────────────────────────────────────────────────────────

  private def prop(key: String, default: String) = sys.props.getOrElse(s"stress.$key", default)
  private val seconds = prop("seconds", "120").toInt
  private val imageCount = prop("images", "2000").toInt
  private val jurorCount = prop("jurors", "10").toInt
  private val perImage = prop("distribution", "3").toInt
  private val chunkRows = prop("chunkRows", "600").toInt
  private val importPage = prop("importPage", "15").toInt
  private val importChanged = prop("importChanged", "15").toInt
  private val voteThreads = prop("voteThreads", "2").toInt
  private val adminThinkMs = prop("adminThinkMs", "150").toInt
  private val voteThinkMs = prop("voteThinkMs", "5").toInt
  // the counts' recompute (P2a): a tick every 0-2x this (production: every 5 s after the last)
  private val recountThinkMs = prop("recountThinkMs", "250").toInt
  private val lockWaitTimeout = prop("lockWaitTimeout", "50").toInt
  private val seed = prop("seed", "42").toLong
  private val variants = prop("variants", "baseline").split(",").map(_.trim).filter(_.nonEmpty).toSeq
  private val pathFilter = prop("paths", ".*").r
  private val stamp = LocalDateTime.now.format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
  private val outDir = new File(prop("out", s"target/stress/write-paths-$stamp"))
  private val password = "stress-pw"

  // ── the routing pool: one MariaDB user per path ────────────────────────────

  /** The path the current thread runs, and so the user it connects as. */
  private val tag = new ThreadLocal[String] { override def initialValue(): String = "harness" }

  private def userOf(path: String): String = s"s_$path".take(80)

  private final class RoutingPool(url: String, fallbackUser: String, fallbackPassword: String, settings: ConnectionPoolSettings)
      extends ConnectionPool(url, fallbackUser, fallbackPassword, settings) {
    private val pools = new ConcurrentHashMap[String, ConnectionPool]()
    // one HikariCP pool per path (HikariTestPool, as the tests'), none kept idle as the
    // commons-dbcp2 pools this used before; no isolation of its own: the variant sets the
    // server's default (SET GLOBAL) before the pool is registered. Of `settings` only maxSize
    // applies: a borrow waits Play's 30 s (HikariTestPool), not connectionTimeoutMillis (5 s)
    private def current: ConnectionPool =
      pools.computeIfAbsent(tag.get, t =>
        HikariTestPool(url, if (t == "harness") fallbackUser else userOf(t),
          if (t == "harness") fallbackPassword else password, name = s"stress-$t",
          minimumIdle = 0, maximumPoolSize = settings.maxSize, isolation = None))
    override def borrow(): Connection = current.borrow()
    override def dataSource: DataSource = current.dataSource
    override def close(): Unit = pools.values().asScala.foreach(p => scala.util.Try(p.close()))
  }

  @volatile private var routing: Option[RoutingPool] = None

  private def registerPool(): Unit = {
    val settings = ConnectionPoolSettings(maxSize = 12)
    val factory = new ConnectionPoolFactory {
      override def apply(url: String, user: String, password: String, settings: ConnectionPoolSettings): ConnectionPool =
        { val pool = new RoutingPool(url, user, password, settings); routing.foreach(_.close()); routing = Some(pool); pool }
    }
    ConnectionPool.add(ConnectionPool.DEFAULT_NAME, SharedTestDb.jdbcUrl, SharedTestDb.username, SharedTestDb.password,
      settings)(factory)
  }

  private def root[A](f: Connection => A): A = {
    val url = SharedTestDb.jdbcUrl.takeWhile(_ != '?')
    val c = DriverManager.getConnection(url, "root", SharedTestDb.password)
    try f(c) finally c.close()
  }

  private def exec(c: Connection, sql: String): Unit = { val st = c.createStatement(); try st.execute(sql) finally st.close() }

  private def status(c: Connection, names: Seq[String]): Map[String, Long] = {
    val rs = c.createStatement().executeQuery(
      s"SHOW GLOBAL STATUS WHERE Variable_name IN (${names.map(n => s"'$n'").mkString(",")})")
    Iterator.continually(rs).takeWhile(_.next()).map(r => r.getString(1) -> r.getString(2).toLong).toMap
  }

  private val statusNames = Seq("Innodb_deadlocks", "Innodb_row_lock_waits", "Innodb_row_lock_time",
    "Innodb_row_lock_time_max", "Innodb_row_lock_current_waits")

  // ── counting the scheduler's retried steps ─────────────────────────────────

  private val retriedSteps = new ConcurrentLinkedQueue[String]()

  private object StepRetries extends AppenderBase[ILoggingEvent] {
    override def append(e: ILoggingEvent): Unit = {
      val m = e.getFormattedMessage
      if (m.contains("transient error, retrying the step")) retriedSteps.add(s"${tag.get}: ${m.take(300)}")
    }
  }

  // ── per-path accounting ────────────────────────────────────────────────────

  private final class Stats {
    val ok = new AtomicLong
    val noop = new AtomicLong
    val deadlocks = new AtomicLong
    val lockWaits = new AtomicLong
    val failures = new AtomicLong
    val retries = new AtomicLong
    val latencies = new ConcurrentLinkedQueue[java.lang.Double]()
    val samples = new ConcurrentLinkedQueue[String]()
    def sample(s: String): Unit = if (samples.size < 5) samples.add(s.take(400))
  }

  /** Thrown by an operation whose caller would see a failure without an exception. */
  private final case class Visible(what: String) extends RuntimeException(what)

  // ── the seeded contest ─────────────────────────────────────────────────────

  private final case class Fixture(
      contest: ContestJury,
      jurors: Seq[User],
      admin: User,
      binary: Long,
      rated: Long,
      criteria: Long,
      toggled: Long,
      target: Long,
      images: IndexedSeq[Image],
      service: RoundService,
      scheduler: services.jobs.JobScheduler,
      commonsFiles: Seq[FakeFile]
  ) {
    def distributed: Seq[Long] = Seq(binary, rated, criteria, toggled)
    def all: Seq[Long] = distributed :+ target
  }

  private val regions = Seq("07", "14", "26", "32", "46")
  private def monument(rnd: Random, pageId: Long) = s"${regions(rnd.nextInt(regions.size))}-101-${pageId % 1000}"

  private def seedFixture(): Fixture = {
    implicit val session: DBSession = AutoSession
    val rnd = new Random(seed)
    val contest = ContestJuryJdbc.create(Some(1L), "Stress", 2026, "Ukraine")
    ContestJuryJdbc.setImagesSource(1L, Some("Images from Stress"))
    val categoryId = ContestJuryJdbc.findById(1L).flatMap(_.categoryId).get
    val jurors = (1 to jurorCount).map { i =>
      User.create(User(s"Juror $i", s"juror$i@example.com", None, Set("jury"), Some("h"), contest.id,
        wikiAccount = Some(s"Juror $i")))
    }
    val admin = User.create(User("Admin", "admin@example.com", None, Set("admin"), Some("h"), contest.id))
    // every 40th file is by juror 1: a conflict of interest
    val images = (1 to imageCount).map { i =>
      Image(i.toLong, s"File:Stress $i.jpg", Some(s"https://upload.example/$i.jpg"), None, 640, 480,
        Some(monument(rnd, i.toLong)), author = if (i % 40 == 0) Some("Juror 1") else Some(s"Author ${i % 97}"))
    }
    images.grouped(1000).foreach { g => ImageJdbc.batchInsert(g); CategoryLinkJdbc.addToCategory(categoryId, g) }

    // the list phase reads Commons: every file, 1 px wider than stored (a changed stored file)
    val commonsFiles = images.map(i => FakeFile(i.copy(width = 641)))
    val handler = TestRoundServices.handler(chunkRows = chunkRows, commons = new FakeCommonsSource(commonsFiles))
    val scheduler = new services.jobs.JobScheduler(Seq(handler, new RoundImageCheckHandler()),
      services.jobs.JobScheduler.Settings(mode = services.jobs.JobScheduler.Mode.Inline))
    val service = new RoundService(Round, scheduler, handler)
    val ids = jurors.map(_.getId)
    def round(n: Long, r: Round) = service.createNewRound(r, ids).getId
    val base = Round(None, 1, Some("Binary"), contest.getId, distribution = perImage, active = true)
    val binary = round(1, base)
    val rated = round(2, base.copy(number = 2, name = Some("Rated"), rates = Round.ratesById(5)))
    val criteria = round(3, base.copy(number = 3, name = Some("Criteria"), rates = Round.ratesById(10),
      criterionIds = Seq(1L, 2L, 3L)))
    val toggled = round(4, base.copy(number = 4, name = Some("Toggled")))
    val target = Round.create(Round(None, 5, Some("Target"), contest.getId, active = false)).getId
    Fixture(contest, jurors, admin, binary, rated, criteria, toggled, target, images, service, scheduler, commonsFiles)
  }

  // ── the paths ──────────────────────────────────────────────────────────────

  private final case class Path(name: String, threads: Int, thinkMs: Int, entry: String, run: (Fixture, Random) => Boolean) {
    /** Retried by the caller in production: the import's steps, as JobScheduler retries a step (3 times). */
    def jobStep: Boolean = entry.startsWith("job: commons_image_import")
  }

  /** Vote rows of a round, refreshed every few seconds: (vote id, page id, juror id). */
  private final class VoteCache(roundId: Long) {
    @volatile private var rows: IndexedSeq[(Long, Long, Long)] = IndexedSeq.empty
    @volatile private var at = 0L
    def pick(rnd: Random): Option[(Long, Long, Long)] = {
      if (System.currentTimeMillis() - at > 3000 || rows.isEmpty) {
        val previous = tag.get
        tag.set("harness")
        try rows = DB.readOnly { implicit s =>
          sql"SELECT id, page_id, jury_id FROM vote WHERE round_id = $roundId".map(r => (r.long(1), r.long(2), r.long(3)))
            .list().toIndexedSeq
        } finally tag.set(previous)
        at = System.currentTimeMillis()
      }
      if (rows.isEmpty) None else Some(rows(rnd.nextInt(rows.size)))
    }
  }

  private val deletedPool = ConcurrentHashMap.newKeySet[java.lang.Long]()

  private def paths(f: Fixture, recompute: services.counts.RoundCountsRecompute): Seq[Path] = {
    val caches = f.all.map(r => r -> new VoteCache(r)).toMap
    def page(rnd: Random): Long = f.images(rnd.nextInt(f.images.size)).pageId
    def pages(rnd: Random, n: Int): Seq[Long] = Seq.fill(n)(page(rnd)).distinct
    @volatile var criteriaOfRound: Seq[Long] = Seq(1L, 2L, 3L)
    def changedImage(rnd: Random, pageId: Long): Image = {
      val i = f.images((pageId - 1).toInt)
      i.copy(monumentId = Some(monument(rnd, pageId)), width = if (rnd.nextBoolean()) 640 else 641)
    }
    def runJob(newJob: services.jobs.NewJob): Boolean = {
      DB.localTx(implicit s => f.scheduler.enqueue(newJob)) match {
        case Right(job) =>
          f.scheduler.started(job.id)
          val done = JobJdbc.findById(job.id).get
          if (done.isFailed) throw Visible(s"job ${job.kind} failed: ${done.failure.map(_.describe)}")
          true
        case Left(_) => false
      }
    }
    Seq(
      // juror votes (request threads): the gallery's and large view's
      Path("vote_binary", voteThreads, voteThinkMs, "request: GalleryController.selectWS / LargeViewController.rateByPageId",
        (_, rnd) => caches(f.binary).pick(rnd).exists { case (_, p, j) =>
          VoteJdbc.rate(p, j, f.binary, rnd.nextInt(3) - 1) > 0 }),
      Path("vote_rated", voteThreads, voteThinkMs, "request: VoteJdbc.rate on a rated round",
        (_, rnd) => caches(f.rated).pick(rnd).exists { case (_, p, j) => VoteJdbc.rate(p, j, f.rated, rnd.nextInt(6)) > 0 }),
      Path("vote_criteria", voteThreads, voteThinkMs, "request: CriteriaRate.updateRate (READ COMMITTED, retried once)",
        (_, rnd) => caches(f.criteria).pick(rnd).exists { case (id, _, _) =>
          val cs = criteriaOfRound
          CriteriaRate.updateRate(id, cs(rnd.nextInt(cs.size)), 1 + rnd.nextInt(5)) }),
      // round edits (request threads)
      Path("criteria_change", 1, adminThinkMs * 4, "request: RoundController.saveRound -> RoundCriterion.update (retried once)",
        (_, _) => {
          val next = if (criteriaOfRound.last == 3L) Seq(1L, 2L, 4L) else Seq(1L, 2L, 3L)
          RoundCriterion.update(f.criteria, next) match {
            case Right(changed) => criteriaOfRound = next; changed
            case Left(refused)  => throw Visible(s"criteria change refused: ${refused.messageKey}")
          }
        }),
      Path("round_edit", 1, adminThinkMs * 4, "request: Round.updateRound + setExcludeCoi (autocommit)",
        (_, rnd) => { val r = f.distributed(rnd.nextInt(4)); Round.updateRound(r, Round(None, 0, Some(s"R ${rnd.nextInt(99)}"), 0)); true }),
      Path("activation", 1, adminThinkMs * 4, "request: RoundService.setCurrentRound -> Round.setActiveGuarded",
        (_, _) => {
          val active = Round.findById(f.toggled).exists(_.active)
          Round.setActiveGuarded(f.toggled, !active)
        }),
      Path("coi_disqualify", 1, adminThinkMs * 6,
        "request: RoundConflictsController -> RoundConflicts.disqualify, then requalify (refused while the round is open, W7)",
        (_, rnd) => {
          val r = Seq(f.binary, f.rated)(rnd.nextInt(2))
          val key = RoundDistributionHandler.dedupeKey(r)
          RoundConflicts.disqualify(r, f.admin, key) match {
            case RoundConflicts.Outcome.Disqualified(files) =>
              files.isEmpty || RoundConflicts.requalify(r, files.map(_._1), RoundConflicts.RequalifyReason.MistakenMatch,
                f.admin, key).isInstanceOf[RoundConflicts.Outcome.Requalified]
            case _ => true // refused under the round's lock: open, frozen or distributing
          }
        }),
      // distribution jobs (job steps, inline here: one step per transaction, retried 3x)
      Path("distribute_new", 1, adminThinkMs, "job: round_distribution (Distribute, Verify), \"Distribute new files\"",
        (_, rnd) => f.service.distributeNewImages(f.distributed(rnd.nextInt(4))) >= 0),
      Path("distribute_list", 1, adminThinkMs * 2, "job: round_distribution list phase (listOnly: lockRounds, insertIgnore, updateStored, insert)",
        (_, rnd) => {
          val r = f.distributed(rnd.nextInt(4))
          val titles = pages(rnd, 30).map(p => f.images((p - 1).toInt).title)
          val start = f.service.distributeNewFiles(r, titles = titles, newFiles = false)
          val done = f.scheduler.await(start.job.id)
          if (done.isFailed) throw Visible(s"list job failed: ${done.failure.map(_.describe)}")
          true
        }),
      Path("check_job", 1, adminThinkMs * 4, "job: round_image_check of one round (sync, monument, deleted-count steps)",
        (_, rnd) => runJob(RoundImageCheckHandler.roundJob(f.all(rnd.nextInt(f.all.size)), f.contest.getId, f.admin))),
      // file removals and moves (test-only today; P3's D16a makes removeImage an admin action)
      Path("remove_image", 1, adminThinkMs, "test-only: VoteJdbc.removeImage (P3: an admin action)",
        (_, rnd) => { TestVoteWrites.removeImage(page(rnd), f.distributed(rnd.nextInt(4))); true }),
      Path("set_round", 1, adminThinkMs, "test-only: VoteJdbc.setRound (toggled round <-> target round)",
        (_, rnd) => {
          val p = page(rnd)
          if (rnd.nextBoolean()) TestVoteWrites.setRound(p, f.toggled, f.target) else TestVoteWrites.setRound(p, f.target, f.toggled)
          true
        }),
      Path("remove_unrated", 1, adminThinkMs * 20, "test-only: VoteJdbc.removeUnrated (the in-memory distribution)",
        (_, _) => { TestVoteWrites.removeUnrated(f.toggled); true }),
      // Commons: the image import's page write (ImageImportHandler.writeImages: restore with the
      // changed files, then each changed file in the listing's order) and its monument-id mode
      // (writeMonumentIds: in page id order), and the file check (CommonsFileCheck.write)
      Path("import_images", 1, adminThinkMs, "job: commons_image_import writeImages (restore, then updateIn per changed file, listing order)",
        (_, rnd) => {
          val back = deletedPool.asScala.take(2).map(_.longValue()).toSeq
          // a page of the listing: `importPage` stored files, `importChanged` of them changed
          val page = (pages(rnd, importPage) ++ back).distinct
          val changed = Random.shuffle(page.filterNot(back.contains).take(importChanged)).map(changedImage(rnd, _))
          DB.localTx { implicit s =>
            // as ImageImportHandler.writeImages: the rounds of the restored and changed files
            // only, then the files in page order, then the writes
            val toRestore = ImageJdbc.deletedOrArchived(page)
            val changedIds = changed.map(_.pageId)
            RoundWrites.lockForFiles((toRestore ++ changedIds).distinct)
            DeletedFiles.restore(toRestore, alsoLock = changedIds)
            RoundWrites.lockFiles(changedIds)
            changed.foreach(ImageJdbc.updateIn)
          }
          back.foreach(p => deletedPool.remove(p))
          true
        }),
      Path("import_monuments", 1, adminThinkMs, "job: commons_image_import writeMonumentIds (updateMonumentIdIn, page id order)",
        (_, rnd) => {
          val changed = pages(rnd, 15).sorted.map(p => p -> monument(rnd, p))
          DB.localTx { implicit s =>
            RoundWrites.lockForFiles(changed.map(_._1)) // as writeMonumentIds
            changed.foreach { case (p, id) => ImageJdbc.updateMonumentIdIn(p, id) }
          }
          true
        }),
      // round_image's counts (P2a, V89): the background recompute, as modules.RoundCountsTask ticks
      Path("recount", 1, recountThinkMs, "background: RoundCountsRecompute.runOnce (RoundCountsTask's tick, windows of 2,000)",
        (_, _) => recompute.runOnce().windows > 0),
      Path("file_check", 1, adminThinkMs * 2, "background: CommonsFileCheck.write (markDeleted + updateFileInfo, then settle)",
        (_, rnd) => {
          val deleted = if (deletedPool.size < 20) pages(rnd, 2) else Nil
          val changed = pages(rnd, 3).filterNot(deleted.contains).map(changedImage(rnd, _))
          WriteTx.write { implicit s => // as CommonsFileCheck.write
            DeletedFiles.markDeleted(deleted, alsoLock = changed.map(_.pageId))
            changed.foreach(ImageJdbc.updateFileInfo)
          }
          DeletedFiles.settle(deleted)
          deleted.foreach(p => deletedPool.add(p))
          true
        })
    ).filter(p => pathFilter.matches(p.name))
  }

  // ── a run ──────────────────────────────────────────────────────────────────

  private final case class Outcome(variant: String, json: JsObject, text: String)

  private def runVariant(variant: String): Outcome = {
    val parts = variant.split("\\+").toSet
    // the app's pools run at READ COMMITTED (W1); `rr` runs the old default
    val rc = !parts.contains("rr")
    val retryAll = parts.contains("retry")


    root { c =>
      exec(c, "SET GLOBAL innodb_print_all_deadlocks = ON")
      exec(c, "SET GLOBAL max_connections = 1000")
      exec(c, s"SET GLOBAL innodb_lock_wait_timeout = $lockWaitTimeout")
      exec(c, s"SET GLOBAL TRANSACTION ISOLATION LEVEL ${if (rc) "READ COMMITTED" else "REPEATABLE READ"}")
    }
    // new connections take the global level: a fresh pool
    registerPool()
    SharedTestDb.truncateAll()
    deletedPool.clear()
    retriedSteps.clear()
    val f = seedFixture()
    val recompute = new services.counts.RoundCountsRecompute(services.counts.RoundCountsRecompute.Settings(background = false))
    val ps = paths(f, recompute)
    root(c => ps.foreach { p =>
      exec(c, s"CREATE USER IF NOT EXISTS '${userOf(p.name)}'@'%' IDENTIFIED BY '$password'")
      exec(c, s"GRANT ALL PRIVILEGES ON *.* TO '${userOf(p.name)}'@'%'")
    })
    // every path's pool runs at the level just set (HikariTestPool sets none of its own)
    val expected = if (rc) "READ-COMMITTED" else "REPEATABLE-READ"
    ("harness" +: ps.map(_.name)).foreach { t =>
      val previous = tag.get
      tag.set(t)
      val seen = try DB.readOnly(implicit s => sql"SELECT @@tx_isolation".map(_.string(1)).single().get)
                 finally tag.set(previous)
      require(seen == expected, s"path $t runs at $seen, expected $expected")
    }
    println(s"[stress] $variant: every path's pool at $expected")
    val votesAtStart = DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM vote".map(_.long(1)).single().get)
    val logStart = SharedTestDb.container.container.getLogs().length
    val before = root(status(_, statusNames))
    val stats = ps.map(p => p.name -> new Stats).toMap
    val stop = new AtomicBoolean(false)
    val t0 = System.nanoTime()
    val threads = ps.flatMap { p =>
      (0 until p.threads).map { i =>
        val t = new Thread(() => {
          tag.set(p.name)
          val rnd = new Random(seed * 31 + p.name.hashCode + i)
          val st = stats(p.name)
          while (!stop.get()) {
            val start = System.nanoTime()
            var attempt = 0
            var done = false
            while (!done) {
              try {
                if (p.run(f, rnd)) st.ok.incrementAndGet() else st.noop.incrementAndGet()
                done = true
              } catch {
                case e: Throwable if NonFatal(e) && DbErrors.isDeadlock(e) =>
                  st.deadlocks.incrementAndGet()
                  if ((retryAll || p.jobStep) && attempt < 3) { attempt += 1; st.retries.incrementAndGet(); Thread.sleep(20L * attempt) }
                  else { st.failures.incrementAndGet(); st.sample(s"deadlock: ${e.getMessage}"); done = true }
                case e: Throwable if NonFatal(e) && DbErrors.isLockWaitTimeout(e) =>
                  st.lockWaits.incrementAndGet(); st.failures.incrementAndGet(); st.sample(s"lock wait: ${e.getMessage}")
                  done = true
                case e: Throwable if NonFatal(e) =>
                  st.failures.incrementAndGet()
                  val causes = Iterator.iterate(e: Throwable)(_.getCause).takeWhile(_ != null).take(4).map(_.toString).mkString(" <- ")
                  st.sample(causes)
                  done = true
              }
            }
            st.latencies.add((System.nanoTime() - start) / 1e6)
            if (p.thinkMs > 0) Thread.sleep(rnd.nextInt(p.thinkMs * 2 + 1).toLong)
          }
        }, s"stress-${p.name}-$i")
        t.setDaemon(true)
        t
      }
    }
    threads.foreach(_.start())
    Thread.sleep(seconds * 1000L)
    stop.set(true)
    threads.foreach(_.join(180000))
    val elapsed = (System.nanoTime() - t0) / 1e9
    val stuck = threads.filter(_.isAlive).map(_.getName)
    val after = root(status(_, statusNames))
    val log = SharedTestDb.container.container.getLogs().drop(logStart)
    val deadlocks = DeadlockLog.parse(log)

    // ── invariants ──
    // the counts brought up to date once the paths have stopped, as the next ticks would:
    // invariant 5 is then checked for every round that is clean
    val finalRecount = { val previous = tag.get; tag.set("harness"); try recompute.untilClean() finally tag.set(previous) }
    val checks = DB.readOnly { implicit s =>
      val ri = RoundImageInvariants.all()
      val criteriaRates = sql"""SELECT COUNT(*) FROM vote v
            WHERE v.round_id = ${f.criteria} AND v.rate <> (
              SELECT IF(COUNT(cr.id) = COUNT(rc.criterion_id) AND MIN(IFNULL(cr.rate, 0)) > 0, SUM(cr.rate), 0)
              FROM round_criterion rc LEFT JOIN criteria_rate cr ON cr.vote_id = v.id AND cr.criterion_id = rc.criterion_id
              WHERE rc.round_id = v.round_id)""".map(_.long(1)).single().get
      val foreignRates = sql"""SELECT COUNT(*) FROM criteria_rate cr JOIN vote v ON v.id = cr.vote_id
            WHERE NOT EXISTS (SELECT 1 FROM round_criterion rc WHERE rc.round_id = v.round_id AND rc.criterion_id = cr.criterion_id)"""
        .map(_.long(1)).single().get
      val monumentCopies = Seq("vote", "vote_aside").map { t =>
        t -> sql"SELECT COUNT(*) FROM ${SQLSyntax.createUnsafely(t)} v JOIN images i ON i.page_id = v.page_id WHERE NOT (v.monument_id <=> i.monument_id)"
          .map(_.long(1)).single().get
      }.toMap
      val rounds = sql"SELECT id, freeze_count, distributing FROM round WHERE freeze_count <> 0 OR distributing <> 0"
        .map(r => s"round ${r.long(1)} freeze ${r.int(2)} distributing ${r.boolean(3)}").list()
      val jobs = sql"SELECT status, COUNT(*) FROM job GROUP BY status".map(r => r.string(1) -> r.long(2)).list().toMap
      val votes = sql"SELECT COUNT(*) FROM vote".map(_.long(1)).single().get
      val notClean = f.all.filterNot(RoundCounts.isClean(_))
      val countDrift = f.all.map(r => r.toString -> RoundCounts.drift(r).size).filter(_._2 > 0).toMap
      Json.obj(
        "countsNotClean" -> notClean,
        "countDrift" -> countDrift,
        "finalRecount" -> Json.obj("windows" -> finalRecount.windows, "files" -> finalRecount.files,
          "changed" -> finalRecount.changed, "recounted" -> finalRecount.roundsRecounted, "skipped" -> finalRecount.skipped),
        "roundImageViolations" -> ri.groupBy(_.invariant).map { case (k, v) => k.toString -> v.size },
        "roundImageViolationSamples" -> ri.take(10).map(_.toString),
        "criteriaVoteRateWrong" -> criteriaRates,
        "criteriaRateOfCriterionNotInRound" -> foreignRates,
        "monumentCopyWrong" -> monumentCopies,
        "roundsLeftFrozen" -> rounds,
        "jobs" -> jobs,
        "votesAtStart" -> votesAtStart,
        "votesAtEnd" -> votes
      )
    }

    def pct(xs: Seq[Double], q: Double) = if (xs.isEmpty) 0.0 else xs.sorted.apply(math.min(xs.size - 1, (q * xs.size).toInt))
    val perPath = ps.map { p =>
      val st = stats(p.name)
      val lat = st.latencies.asScala.map(_.doubleValue()).toSeq
      p.name -> Json.obj(
        "entry" -> p.entry, "threads" -> p.threads,
        "ok" -> st.ok.get, "noop" -> st.noop.get, "deadlocksCaught" -> st.deadlocks.get,
        "lockWaitTimeouts" -> st.lockWaits.get, "visibleFailures" -> st.failures.get, "retries" -> st.retries.get,
        "perSecond" -> BigDecimal((st.ok.get + st.noop.get) / elapsed).setScale(2, BigDecimal.RoundingMode.HALF_UP),
        "p50ms" -> BigDecimal(pct(lat, 0.5)).setScale(1, BigDecimal.RoundingMode.HALF_UP),
        "p95ms" -> BigDecimal(pct(lat, 0.95)).setScale(1, BigDecimal.RoundingMode.HALF_UP),
        "maxms" -> BigDecimal(if (lat.isEmpty) 0.0 else lat.max).setScale(1, BigDecimal.RoundingMode.HALF_UP),
        "samples" -> st.samples.asScala.toSeq
      )
    }
    val retried = retriedSteps.asScala.toSeq
    val json = Json.obj(
      "variant" -> variant,
      "parameters" -> Json.obj("seconds" -> seconds, "elapsed" -> elapsed, "images" -> imageCount, "jurors" -> jurorCount,
        "distribution" -> perImage, "chunkRows" -> chunkRows, "voteThreads" -> voteThreads, "adminThinkMs" -> adminThinkMs,
        "voteThinkMs" -> voteThinkMs, "lockWaitTimeout" -> lockWaitTimeout, "seed" -> seed,
        "isolation" -> (if (rc) "READ COMMITTED" else "REPEATABLE READ"),
        "importPage" -> importPage, "importChanged" -> importChanged, "retryAll" -> retryAll, "mariadb" -> SharedTestDb.Image),
      "server" -> Json.obj("deadlocks" -> (after("Innodb_deadlocks") - before("Innodb_deadlocks")),
        "rowLockWaits" -> (after("Innodb_row_lock_waits") - before("Innodb_row_lock_waits")),
        "rowLockTimeMs" -> (after("Innodb_row_lock_time") - before("Innodb_row_lock_time")),
        "rowLockTimeMaxMs" -> after("Innodb_row_lock_time_max")),
      "deadlockPairs" -> deadlocks.groupBy(_.pair).map { case (k, v) => k -> v.size }.toSeq.sortBy(-_._2)
        .map { case (k, n) => Json.obj("pair" -> k, "count" -> n, "victim" -> deadlocks.filter(_.pair == k).groupBy(_.victim).map { case (v, xs) => v -> xs.size }) },
      "deadlockLocks" -> deadlocks.groupBy(d => s"${d.pair}: ${d.locks}").map { case (k, v) => k -> v.size }.toSeq.sortBy(-_._2).take(30)
        .map { case (k, n) => Json.obj("where" -> k, "count" -> n) },
      "stepRetries" -> retried.groupBy(_.takeWhile(_ != ':')).map { case (k, v) => k -> v.size },
      "stuckThreads" -> stuck,
      "paths" -> JsObject(perPath),
      "invariants" -> checks
    )
    val text = new StringBuilder
    text ++= s"== $variant: ${f"$elapsed%.0f"} s, ${ps.map(_.threads).sum} threads, " +
      s"server deadlocks ${after("Innodb_deadlocks") - before("Innodb_deadlocks")}, " +
      s"row lock waits ${after("Innodb_row_lock_waits") - before("Innodb_row_lock_waits")}, steps retried ${retried.size}\n"
    text ++= f"${"path"}%-18s ${"ops"}%7s ${"noop"}%6s ${"/s"}%7s ${"p50"}%7s ${"p95"}%8s ${"max"}%8s ${"dl"}%4s ${"lwt"}%4s ${"fail"}%5s ${"retry"}%5s\n"
    ps.foreach { p =>
      val st = stats(p.name)
      val lat = st.latencies.asScala.map(_.doubleValue()).toSeq
      text ++= f"${p.name}%-18s ${st.ok.get}%7d ${st.noop.get}%6d ${(st.ok.get + st.noop.get) / elapsed}%7.1f ${pct(lat, 0.5)}%7.1f ${pct(lat, 0.95)}%8.1f ${if (lat.isEmpty) 0.0 else lat.max}%8.1f ${st.deadlocks.get}%4d ${st.lockWaits.get}%4d ${st.failures.get}%5d ${st.retries.get}%5d\n"
    }
    text ++= "deadlock pairs (from the server's log; victim first):\n"
    deadlocks.groupBy(_.pair).toSeq.sortBy(-_._2.size).foreach { case (k, v) =>
      text ++= s"  ${v.size}x $k (victims: ${v.groupBy(_.victim).map { case (x, xs) => s"$x ${xs.size}" }.mkString(", ")})\n"
    }
    text ++= "deadlock shapes (path [statement] waits table.index lock):\n"
    deadlocks.groupBy(_.locks.replaceAll("\\d+", "N")).toSeq.sortBy(-_._2.size).take(25).foreach { case (k, v) =>
      text ++= s"  ${v.size}x $k\n"
    }
    text ++= s"invariants: ${Json.stringify(checks)}\n"
    if (stuck.nonEmpty) text ++= s"STUCK threads: ${stuck.mkString(", ")}\n"
    ps.foreach { p => stats(p.name).samples.asScala.foreach(s => text ++= s"  ${p.name}: $s\n") }

    val raw = new PrintWriter(new File(outDir, s"deadlocks-$variant.txt"), "UTF-8")
    try deadlocks.foreach(d => raw.println(d.raw + "\n")) finally raw.close()
    val serverLog = new PrintWriter(new File(outDir, s"server-log-$variant.txt"), "UTF-8")
    try serverLog.print(log) finally serverLog.close()
    Outcome(variant, json, text.toString)
  }

  def main(args: Array[String]): Unit = {
    val status = try {
      outDir.mkdirs()
      GlobalSettings.loggingSQLAndTime = LoggingSQLAndTimeSettings(enabled = false)
      val rootLogger = LoggerFactory.getLogger(org.slf4j.Logger.ROOT_LOGGER_NAME).asInstanceOf[ch.qos.logback.classic.Logger]
      StepRetries.setContext(rootLogger.getLoggerContext)
      StepRetries.start()
      rootLogger.addAppender(StepRetries)
      SharedTestDb.init()
      val summary = new PrintWriter(new File(outDir, "summary.txt"), "UTF-8")
      val results = mutable.ListBuffer.empty[JsObject]
      try variants.foreach { v =>
        val o = runVariant(v)
        println(o.text)
        summary.println(o.text)
        summary.flush()
        results += o.json
        val w = new PrintWriter(new File(outDir, "results.json"), "UTF-8")
        try w.println(Json.prettyPrint(Json.toJson(results.toSeq))) finally w.close()
      } finally summary.close()
      println(s"[stress] results in ${outDir.getPath}")
      0
    } catch { case e: Throwable => e.printStackTrace(); 1 }
    sys.exit(status)
  }
}

/** The deadlocks MariaDB prints with `innodb_print_all_deadlocks`, by the stress paths' users. */
object DeadlockLog {

  final case class Deadlock(paths: Seq[String], victim: String, locks: String, raw: String) {
    def pair: String = paths.sorted.mkString(" x ")
  }

  private val User = """thread id \d+, OS thread handle \d+, query id \d+ \S+ s_([a-z_]+)""".r
  private val Lock = """index `?(\w+)`? of table `\w+`\.`(\w+)` trx id \d+ (.*?)(?: waiting)?$""".r
  private val Noise = """^\s*(\d+: (len|SQL)|Record lock|RECORD LOCKS space id .* heap no).*""".r

  def parse(log: String): Seq[Deadlock] =
    log.split("Transactions deadlock detected, dumping detailed information\\.").drop(1).toSeq.map { b =>
      val lines = b.linesIterator.filterNot(l => Noise.matches(l) || l.trim.isEmpty).toSeq
      val text = lines.mkString("\n")
      val sections = text.split("\\*\\*\\* \\(\\d\\) TRANSACTION:").drop(1).toSeq
      val sides = sections.map { t =>
        val ls = t.linesIterator.toSeq
        val user = ls.collectFirst { case l if User.findFirstMatchIn(l).isDefined => User.findFirstMatchIn(l).get.group(1) }
          .getOrElse("harness")
        val query = ls.dropWhile(l => User.findFirstMatchIn(l).isEmpty).drop(1).headOption.getOrElse("").trim
        val waiting = ls.dropWhile(!_.contains("WAITING FOR THIS LOCK TO BE GRANTED")).drop(1)
          .collectFirst { case l if Lock.findFirstMatchIn(l).isDefined =>
            val m = Lock.findFirstMatchIn(l).get; s"${m.group(2)}.${m.group(1)} ${m.group(3)}" }.getOrElse("?")
        (user, query, waiting)
      }
      val victimNo = """WE ROLL BACK TRANSACTION \((\d)\)""".r.findFirstMatchIn(text).map(_.group(1).toInt).getOrElse(0)
      val victim = sides.lift(victimNo - 1).map(_._1).getOrElse("?")
      val locks = sides.map { case (u, q, w) => s"$u [${q.take(70)}] waits $w" }.mkString(" / ")
      Deadlock(sides.map(_._1), victim, locks, text.take(8000))
    }
}
