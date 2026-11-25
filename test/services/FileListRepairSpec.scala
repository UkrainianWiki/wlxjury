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
  }
}
