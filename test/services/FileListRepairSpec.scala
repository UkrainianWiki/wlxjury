package services

import db.scalikejdbc._
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

/** Root's repair of shared file lists (FileListRepair, V114) on [[FileListRepairFixture]]: the
  * overview finds the contests on the shared untitled row and the titled shared row (as
  * CategoryLinkJdbc.mayCopyFrom has it) and the contest already on its own row; a repair's
  * preview counts, its confirmation (the own row gets the files that stay and becomes the list,
  * logged), rounds and votes never changing; Undo restores the list and is refused after a later
  * change; a stale preview is refused; the untitled row is renamed only once no contest has it.
  */
class FileListRepairSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  override protected def before: Any = SharedTestDb.truncateAll()

  private def fixture(): FileListRepairFixture.F = {
    insertImagesFor((1L to 22L): _*)
    FileListRepairFixture.create(1L to 22L)
  }

  private def members(categoryId: Long): Seq[Long] =
    DB.readOnly(implicit s => sql"SELECT page_id FROM category_members WHERE category_id = $categoryId ORDER BY page_id"
      .map(_.long(1)).list.apply())

  private def categoryOf(contestId: Long): Option[Long] = ContestJuryJdbc.findById(contestId).flatMap(_.categoryId)

  /** Counts and checksums of the rounds, their files and the votes. */
  private def judging(): Seq[(Long, Long)] = DB.readOnly { implicit s =>
    Seq(
      sql"SELECT COUNT(*), COALESCE(SUM(id * number), 0) FROM round",
      sql"SELECT COUNT(*), COALESCE(SUM(round_id * 100000 + page_id), 0) FROM round_image",
      sql"SELECT COUNT(*), COALESCE(SUM(round_id * 100000 + page_id + jury_id), 0) FROM vote"
    ).map(_.map(rs => (rs.long(1), rs.long(2))).single.apply().get)
  }

  private def plan(contestId: Long, counted: Option[Seq[Long]] = None): FileListRepair.Plan =
    FileListRepair.plan(contestId, counted).toOption.get

  /** A job of the contest, queued (or done); its id. */
  private def job(kind: String, contestId: Long, subject: (String, Long), done: Boolean = false): Long =
    DB.localTx { implicit s =>
      val id = services.jobs.JobJdbc.insert(services.jobs.NewJob(kind, Some(contestId), Some(subject._1), Some(subject._2)), None)
      if (done) services.jobs.JobJdbc.update(id, services.jobs.JobStatus.Done, play.api.libs.json.Json.obj(),
        services.jobs.Progress(), None)
      id
    }

  private def importJob(contestId: Long, done: Boolean = false): Long =
    job(services.imports.ImageImportHandler.Kind, contestId, (services.imports.ImageImportHandler.SubjectType, contestId), done)

  private def title(categoryId: Long): Option[String] =
    DB.readOnly(implicit s => sql"SELECT title FROM category WHERE id = $categoryId".map(_.string(1)).single.apply())

  private def root = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  "the overview" should {

    "list the contests on the shared rows, as mayCopyFrom has them, and the one already on its own row" in {
      val f = fixture()
      val o = FileListRepair.overview()
      val shared = o.shared.map(c => c.contestId -> (c.row.id, c.others, c.size)).toMap
      val sharing = Seq(f.a, f.b, f.c, f.d).map(_.getId)
      (shared.keySet === (sharing :+ f.f.getId).toSet) and
        forall(sharing)(id => shared(id) === ((f.blank, sharing.filterNot(_ == id), 20))) and
        (shared(f.f.getId) === ((f.titled, Seq(f.g.getId), 1))) and
        (o.onOwnRow.map(c => (c.contestId, c.row.id, c.left)) === Seq((f.e.getId, f.blank, 2))) and
        (o.orphans must beEmpty) and
        // the same notion as the import's guard: listed exactly when its list may not be copied
        forall(ContestJuryJdbc.findAll().filter(_.categoryId.isDefined).filterNot(_.getId == f.e.getId)) { c =>
          DB.readOnly(implicit s => CategoryLinkJdbc.mayCopyFrom(c.categoryId.get, c.getId)) must_!= shared.contains(c.getId)
        }
    }
  }

  "the overview's likely owner" should {

    "be the contest that names a titled shared row, never one of an untitled row" in {
      val f = fixture()
      // I has F's titled row as its list and names it as its source too
      val i = ContestJuryJdbc.create(None, "Repair I", 2018, "Ghana", images = Some(s"Category:Repair shared list 2018"),
        categoryId = Some(f.titled))
      val o = FileListRepair.overview().shared.map(c => c.contestId -> c.likelyOwner).toMap
      (o(i.getId) must beTrue) and (o(f.f.getId) must beFalse) and
        forall(Seq(f.a, f.b, f.c, f.d))(c => o(c.getId) must beFalse)
    }
  }

  "a repair's preview" should {

    "count what stays and leaves, the whole-list round pre-unticked" in {
      val f = fixture()
      val p = plan(f.a.getId)
      val whole = p.rounds.find(_.round.getId == f.aWhole).get
      (p.rounds.map(r => (r.round.getId, r.files, r.inSource)) === Seq((f.a1, 5, 5), (f.a2, 2, 2), (f.aWhole, 20, 20))) and
        (whole.mixed must beTrue) and (whole.mixedWith === Seq(f.b.getId, f.c.getId)) and
        (p.rounds.filterNot(_.round.getId == f.aWhole).forall(!_.mixed) must beTrue) and
        (p.counted === Seq(f.a1, f.a2)) and
        (p.now === f.range(1, 20)) and (p.stays === f.range(1, 5)) and (p.leaves === f.range(6, 20)) and
        (p.own must beNone) and (p.newSize === 5) and (p.affected must beTrue) and
        // countries: of the contests whose (not mixed) rounds show the files; none for those no round shows
        (p.byCountry(p.stays) === Seq("Armenia" -> 5)) and
        (p.byCountry(p.leaves).toMap === Map("Ghana" -> 5, "Nepal" -> 5, "" -> 5)) and
        // counting the whole-list round takes every file
        (plan(f.a.getId, Some(Seq(f.a1, f.aWhole))).stays === f.range(1, 20)) and
        // B's round shares its files with A's whole-list round only: not mixed
        (plan(f.b.getId).counted === Seq(f.b1)) and
        // no rounds: nothing stays
        (plan(f.d.getId).stays must beEmpty) and (plan(f.d.getId).leaves.size === 20)
    }

    "untick the whole-list rounds of two contests, and only those" in {
      val f = fixture()
      // B has a whole-list round too: every legitimate round stays ticked, both whole-list rounds don't
      val bWhole = Round.create(Round(None, 2, Some("Whole list"), f.b.getId, active = false)).getId
      f.files.foreach(p => VoteJdbc.create(p, 0, f.juror.getId, bWhole))
      val (a, b, c) = (plan(f.a.getId), plan(f.b.getId), plan(f.c.getId))
      def mixed(p: FileListRepair.Plan) = p.rounds.filter(_.mixed).map(_.round.getId)
      (mixed(a) === Seq(f.aWhole)) and (a.counted === Seq(f.a1, f.a2)) and
        (mixed(b) === Seq(bWhole)) and (b.counted === Seq(f.b1)) and
        (b.rounds.find(_.round.getId == bWhole).get.mixedWith === Seq(f.a.getId, f.c.getId)) and
        (mixed(c) must beEmpty) and (c.counted === Seq(f.c1)) and
        (a.stays === f.range(1, 5)) and (b.stays === f.range(6, 10)) and (c.stays === f.range(11, 15))
    }

    "keep a contest's round of most of the row ticked when two other contests have whole-list rounds" in {
      val f = fixture()
      // B and C fill whole-list rounds; A has a round of files 1-12 (most of the row, of B's and C's files
      // only some of their own rounds' ones): only the whole-list rounds are mixed
      def round(contest: org.intracer.wmua.ContestJury, number: Int, pages: Seq[Long]): Long = {
        val id = Round.create(Round(None, number, Some(s"Round $number"), contest.getId, active = false)).getId
        pages.foreach(p => VoteJdbc.create(p, 0, f.juror.getId, id))
        id
      }
      val bWhole = round(f.b, 2, f.files)
      val cWhole = round(f.c, 2, f.files)
      val aBig = round(f.a, 4, f.range(1, 12))
      val a = plan(f.a.getId)
      def mixed(p: FileListRepair.Plan) = p.rounds.filter(_.mixed).map(_.round.getId)
      (mixed(a) === Seq(f.aWhole)) and (a.counted === Seq(f.a1, f.a2, aBig)) and
        (mixed(plan(f.b.getId)) === Seq(bWhole)) and (mixed(plan(f.c.getId)) === Seq(cWhole))
    }

    "take a contest already on its own row's files from the shared row its rounds' files were left in" in {
      val f = fixture()
      val p = plan(f.e.getId)
      (p.onOwnRow must beTrue) and (p.current.id === f.eOwn) and (p.source.id === f.blank) and
        (p.now === Seq(f.newFile)) and (p.ownFiles === Seq(f.newFile)) and (p.stays === f.range(16, 17)) and
        (p.leaves.size === 18) and (p.added === f.range(16, 17)) and (p.newSize === 3)
    }
  }

  "Confirm" should {

    "move the files that stay into the contest's own row, make it the list and log it; rounds and votes unchanged" in {
      val f = fixture()
      val before = judging()
      val r = root
      val p = plan(f.a.getId)
      val repair = FileListRepair.confirm(f.a.getId, p.counted, p.hash, r.getId)
      val own = DB.readOnly(implicit s => sql"SELECT id FROM category WHERE title = ${ImageService.ownCategory(f.a.getId)}"
        .map(_.long(1)).single.apply()).get
      val change = ContestChangeJdbc.findByContest(f.a.getId, 5)
      (repair.map(_.filesAdded) must beRight(5)) and
        (categoryOf(f.a.getId) must beSome(own)) and (members(own) === f.range(1, 5)) and
        // the shared row and the other contests' lists keep their files
        (members(f.blank) === f.range(1, 20)) and (categoryOf(f.b.getId) must beSome(f.blank)) and
        (ListRepairJdbc.addedFiles(repair.toOption.get.id) === f.range(1, 5)) and
        (repair.toOption.get.roundsCounted === Seq(f.a1, f.a2)) and
        (change.map(_.kind) === Seq(ContestChangeJdbc.FilesRepaired)) and
        ((change.head.detail \ "stays").as[Int] === 5) and ((change.head.detail \ "leaves").as[Int] === 15) and
        (judging() === before)
    }

    "add only the missing files to a contest's own row and keep its new import" in {
      val f = fixture()
      val before = judging()
      val p = plan(f.e.getId)
      val repair = FileListRepair.confirm(f.e.getId, p.counted, p.hash, root.getId)
      (repair.map(_.filesAdded) must beRight(2)) and (categoryOf(f.e.getId) must beSome(f.eOwn)) and
        (members(f.eOwn) === (f.range(16, 17) :+ f.newFile).sorted) and
        (FileListRepair.overview().onOwnRow must beEmpty) and (judging() === before)
    }

    "repair a contest without rounds to an empty list" in {
      val f = fixture()
      val p = plan(f.d.getId)
      val repair = FileListRepair.confirm(f.d.getId, p.counted, p.hash, root.getId)
      (repair.map(_.filesAdded) must beRight(0)) and (categoryOf(f.d.getId).map(members) must beSome(Seq.empty[Long]))
    }

    "refuse a stale preview, changing nothing" in {
      val f = fixture()
      val p = plan(f.b.getId)
      // a round of B now shows file 18 too: the files that stay differ from the preview's
      VoteJdbc.create(f.file(18), 0, f.juror.getId, f.b1)
      val refused = FileListRepair.confirm(f.b.getId, p.counted, p.hash, root.getId)
      val otherRounds = FileListRepair.confirm(f.b.getId, Nil, p.hash, root.getId)
      (refused must beLeft("list.repair.changed")) and (otherRounds must beLeft("list.repair.changed")) and
        (categoryOf(f.b.getId) must beSome(f.blank)) and (ListRepairJdbc.recent(5) must beEmpty)
    }

    "refuse a contest whose list needs no repair, and have nothing to repair without a list" in {
      val f = fixture()
      val unique = CategoryJdbc.findOrInsert("Category:Repair unique list")
      val h = ContestJuryJdbc.create(None, "Repair H", 2018, "Ghana", categoryId = Some(unique))
      val p = plan(h.getId)
      (p.affected must beFalse) and
        (FileListRepair.confirm(h.getId, p.counted, p.hash, root.getId) must beLeft("list.repair.not.affected")) and
        (categoryOf(h.getId) must beSome(unique)) and
        (FileListRepair.plan(f.g.getId, None) must beLeft("list.repair.no.list")) and
        (FileListRepair.plan(-1L, None) must beLeft("error.contest.not.found"))
    }
  }

  "Undo" should {

    "restore the list and take out only the files the repair added; refused after a later change" in {
      val f = fixture()
      val r = root
      val before = judging()
      val p = plan(f.a.getId)
      val repair = FileListRepair.confirm(f.a.getId, p.counted, p.hash, r.getId).toOption.get
      val own = repair.newCategoryId
      val undone = FileListRepair.undo(repair.id, r.getId)
      val again = FileListRepair.undo(repair.id, r.getId)
      val restored = (categoryOf(f.a.getId), members(own), ContestChangeJdbc.findByContest(f.a.getId, 5).map(_.kind))

      // repaired again, then a later import adds a file to the own list: Undo is refused
      val p2 = plan(f.a.getId)
      val repair2 = FileListRepair.confirm(f.a.getId, p2.counted, p2.hash, r.getId).toOption.get
      DB.autoCommit(implicit s => sql"INSERT INTO category_members (category_id, page_id) VALUES ($own, ${f.newFile})".update.apply())
      val refused = FileListRepair.undo(repair2.id, r.getId)
      (undone.map(_.id) must beRight(repair.id)) and (again must beLeft("list.repair.undo.already")) and
        (restored === ((Some(f.blank), Nil, Seq(ContestChangeJdbc.FilesRepairUndone, ContestChangeJdbc.FilesRepaired)))) and
        (refused must beLeft("list.repair.undo.changed")) and (categoryOf(f.a.getId) must beSome(own)) and
        (members(own) === (f.range(1, 5) :+ f.newFile).sorted) and (judging() === before)
    }

    "keep the files the own row had before the repair" in {
      val f = fixture()
      val r = root
      val p = plan(f.e.getId)
      val repair = FileListRepair.confirm(f.e.getId, p.counted, p.hash, r.getId).toOption.get
      (FileListRepair.undo(repair.id, r.getId) must beRight) and (members(f.eOwn) === Seq(f.newFile)) and
        (categoryOf(f.e.getId) must beSome(f.eOwn))
    }
  }

  "Undo after the old row's rename" should {

    "be refused: the contest would be back on every unnamed import's files; and no import copies an app-titled row" in {
      val f = fixture()
      val r = root
      val repairs = Seq(f.a, f.b, f.c, f.d).map { c =>
        val p = plan(c.getId)
        FileListRepair.confirm(c.getId, p.counted, p.hash, r.getId).toOption.get
      }
      FileListRepair.rename(f.blank, r.getId) must beRight
      val refused = FileListRepair.undo(repairs.head.id, r.getId)
      (refused must beLeft("list.repair.undo.renamed")) and
        (categoryOf(f.a.getId) must beSome(repairs.head.newCategoryId)) and
        // a "#..." title is the app's, never a Commons category: never copied into a list
        (DB.readOnly(implicit s => CategoryLinkJdbc.mayCopyFrom(f.blank, f.a.getId)) must beFalse) and
        (DB.readOnly(implicit s => CategoryLinkJdbc.mayCopyFrom(f.eOwn, f.a.getId)) must beFalse) and
        (CategoryLinkJdbc.isAppTitle(FileListRepair.LegacyTitle) must beTrue) and (CategoryLinkJdbc.isAppTitle("Category:X") must beFalse)
    }
  }

  "Confirm and Undo with jobs" should {

    "be refused while an import or a distribution is queued or running, and Undo after an import" in {
      val f = fixture()
      val r = root
      val p = plan(f.a.getId)
      val importing = importJob(f.a.getId)
      val busyConfirm = FileListRepair.confirm(f.a.getId, p.counted, p.hash, r.getId)
      DB.autoCommit(implicit s => sql"UPDATE job SET status = 'done' WHERE id = $importing".update.apply())
      val distributing = job(services.distribution.RoundDistributionHandler.Kind, f.a.getId,
        (services.distribution.RoundDistributionHandler.SubjectType, f.a1))
      val busyConfirm2 = FileListRepair.confirm(f.a.getId, p.counted, p.hash, r.getId)
      DB.autoCommit(implicit s => sql"UPDATE job SET status = 'done' WHERE id = $distributing".update.apply())
      // the imports before the repair don't count against its Undo
      val first = FileListRepair.confirm(f.a.getId, p.counted, p.hash, r.getId).toOption.get
      val undoneDespiteOlder = FileListRepair.undo(first.id, r.getId)
      val p2 = plan(f.a.getId)
      val repair = FileListRepair.confirm(f.a.getId, p2.counted, p2.hash, r.getId).toOption.get
      val running = importJob(f.a.getId)
      val busyUndo = FileListRepair.undo(repair.id, r.getId)
      DB.autoCommit(implicit s => sql"UPDATE job SET status = 'done' WHERE id = $running".update.apply())
      // an import since the repair that added nothing: the list's hash is the same, Undo still refused
      val importedSince = FileListRepair.undo(repair.id, r.getId)
      (undoneDespiteOlder.map(_.id) must beRight(first.id)) and
        (busyConfirm must beLeft("list.repair.busy")) and (busyConfirm2 must beLeft("list.repair.busy")) and
        (busyUndo must beLeft("list.repair.busy")) and (importedSince must beLeft("list.repair.undo.imported")) and
        (categoryOf(f.a.getId) must beSome(repair.newCategoryId)) and (ListRepairJdbc.findById(repair.id).exists(_.undone) must beFalse)
    }
  }

  "Rename" should {

    "be offered and allowed only once no contest has the untitled row" in {
      val f = fixture()
      val r = root
      val early = FileListRepair.rename(f.blank, r.getId)
      Seq(f.a, f.b, f.c, f.d).foreach { c =>
        val p = plan(c.getId)
        FileListRepair.confirm(c.getId, p.counted, p.hash, r.getId) must beRight
      }
      val orphans = FileListRepair.overview().orphans
      val renamed = FileListRepair.rename(f.blank, r.getId)
      val title = DB.readOnly(implicit s => sql"SELECT title FROM category WHERE id = ${f.blank}".map(_.string(1)).single.apply())
      (early must beLeft("list.repair.rename.in.use")) and
        (orphans.map(o => (o.row.id, o.mayRename)) === Seq((f.blank, true))) and
        (renamed must beRight(FileListRepair.LegacyTitle)) and (title must beSome(FileListRepair.LegacyTitle)) and
        (FileListRepair.overview().orphans.map(o => (o.row.id, o.mayRename)) === Seq((f.blank, false))) and
        (FileListRepair.rename(f.blank, r.getId) must beLeft("list.repair.rename.not.blank")) and
        // its files are kept, for audit
        (members(f.blank).size === 20)
    }

    "add the row's id to the title if another row has it" in {
      val f = fixture()
      val r = root
      CategoryJdbc.findOrInsert(FileListRepair.LegacyTitle)
      Seq(f.a, f.b, f.c, f.d).foreach { c =>
        val p = plan(c.getId)
        FileListRepair.confirm(c.getId, p.counted, p.hash, r.getId) must beRight
      }
      (FileListRepair.rename(f.blank, r.getId) must beRight(s"${FileListRepair.LegacyTitle} #${f.blank}")) and
        (title(f.blank) must beSome(s"${FileListRepair.LegacyTitle} #${f.blank}"))
    }
  }
}
