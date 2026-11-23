package services.series

import db.scalikejdbc._
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.Configuration
import play.api.libs.json.Json
import scalikejdbc._
import services.ImageService
import services.diagnostics.HealthFixtures._
import services.jobs.{Job, JobJdbc, JobScheduler, JobStatus, StepContext, StepResult}
import services.series.ContestSplit.{Mode, Proposal, fieldId}
import services.series.ReuseFixtures._
import services.series.ReuseReport.{Catalog, Eras}

import scala.concurrent.duration._

/** Splitting a reused contest (contest series plan §4.4–§4.6; CY-4): the offer from the reuse
  * report and root's confirmation (refusals, choices, the targets), the `contest_split` job's moves
  * (R1, R2 and R4 of a 466-like host, an active round paused and resumed, empty clusters left,
  * memberships per CA11 and the Admin ticks per CA23, the files per CA10, `exclude_rounds` cut),
  * Undo (a full reversal, and its refusals), the job's failure, Retry, Cancel and lease recovery,
  * and votes cast while it runs. Committed rows (the job's steps and Undo run in transactions of
  * their own).
  */
class ContestSplitSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val settings = ReuseReport.Settings()

  /** Runs `beforeMove` at the start of each move step (in its own connection, committed): a change
    * made while the job runs.
    */
  private class Hooked(chunk: Int = 7) extends ContestSplitHandler(Configuration.from(Map("series.split.chunk" -> chunk))) {
    @volatile var beforeMove: () => Unit = () => ()
    override def step(job: Job, ctx: StepContext): StepResult = {
      if (ContestSplitHandler.stateOf(job).phase == ContestSplitHandler.Phase.Move) beforeMove()
      super.step(job, ctx)
    }
  }

  private def inline(h: ContestSplitHandler = new Hooked()) =
    new JobScheduler(Seq(h), JobScheduler.Settings(mode = JobScheduler.Mode.Inline))

  private def rootUser = User.create(User("Root", s"root${System.nanoTime()}@example.org", None, Set(User.ROOT_ROLE), isRoot = true))

  private def offerOf(c: ContestJury): (Proposal, Catalog) = DB.readOnly { implicit s =>
    val catalog = Catalog.load()
    (ContestSplit.propose(ReuseReport.report(ContestJuryJdbc.findById(c.getId).get, Eras.empty, settings, Some(catalog)), catalog), catalog)
  }

  /** The page's defaults: the hash, and each proposed move ticked. */
  private def defaults(p: Proposal): Map[String, Seq[String]] =
    Map("hash" -> Seq(p.hash)) ++ p.offers.filter(_.mode == Mode.Move).flatMap(o =>
      o.alternatives.headOption.map(a => s"c${o.cluster.index}" -> Seq(fieldId(a.key))))

  private def offerOfRound(p: Proposal, round: Round) = p.offers.find(_.cluster.roundIds.contains(round.getId)).get

  private def targetOf(p: Proposal, round: Round) = p.target(offerOfRound(p, round).alternatives.head.key).get

  private def plan(c: ContestJury, form: Proposal => Map[String, Seq[String]]): Either[ContestSplit.Refusal, ContestSplit.Plan] = {
    val (p, catalog) = offerOf(c)
    DB.readOnly(implicit s => ContestSplit.plan(p, form(p), catalog))
  }

  /** Confirms the plan and runs its job (inline: to its end). */
  private def split(c: ContestJury, root: User, jobs: JobScheduler, form: Proposal => Map[String, Seq[String]]): (Long, Job) = {
    val planned = plan(c, form)
    planned must beRight
    val (id, job) = ContestSplit.confirm(planned.toOption.get, Some("dump-1"), root, jobs).toOption.get
    jobs.started(job.id)
    (id, JobJdbc.findById(job.id).get)
  }

  private def q[A](sql: SQL[A, NoExtractor])(f: WrappedResultSet => A): List[A] = DB.readOnly(implicit s => sql.map(f).list.apply())
  private def contestOf(round: Round): Long =
    DB.readOnly(implicit s => sql"SELECT contest_id FROM round WHERE id = ${round.getId}".map(_.long(1)).single.apply().get)
  private def roundRow(round: Round) = DB.readOnly(implicit s => ContestSplit.roundsNow(sqls"id = ${round.getId}").head)
  private def categoryFiles(contestId: Long): Seq[Long] = DB.readOnly { implicit s =>
    sql"""SELECT cm.page_id FROM category_members cm JOIN contest_jury c ON c.category_id = cm.category_id
          WHERE c.id = $contestId ORDER BY cm.page_id""".map(_.long(1)).list.apply()
  }
  private def role(user: User, contestId: Long): Option[String] =
    DB.readOnly(implicit s => sql"SELECT role FROM user_contest WHERE user_id = ${user.getId} AND contest_id = $contestId"
      .map(_.string(1)).single.apply())
  private def votes(): List[(Long, Long, Long, Int)] =
    q(sql"SELECT id, round_id, jury_id, rate FROM vote ORDER BY id")(rs => (rs.long(1), rs.long(2), rs.long(3), rs.int(4)))

  /** 466 with history around the 2021 rounds: votes, a comment and a done job on the first one, the
    * contest's current round the second; `exclude_rounds` edges across clusters both ways.
    */
  private def withHistory(n: ReuseFixtures.Nepal466): Unit = DB.autoCommit { implicit s =>
    val (y1, y2) = (n.r2021.head.getId, n.r2021(1).getId)
    sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (101, ${n.juror.getId}, $y1, 1), (102, ${n.juror.getId}, $y1, -1)".update.apply()
    sql"""INSERT INTO comment (user_id, username, round_id, contest_id, room, body, created_at)
          VALUES (${n.juror.getId}, 'Nepal Juror', $y1, ${n.contest.getId}, 101, 'Nice', NOW())""".update.apply()
    sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, status, params, state, created_by, updated_at)
          VALUES ('round_distribution', ${n.contest.getId}, 'round', $y1, 'done', '{}', '{}', ${n.admin.getId}, NOW(3))""".update.apply()
    sql"UPDATE round SET exclude_rounds = ${n.own.head.getId.toString} WHERE id = $y2".update.apply()
    sql"UPDATE round SET exclude_rounds = ${y1.toString} WHERE id = ${n.own(1).getId}".update.apply()
    sql"UPDATE contest_jury SET current_round = $y2 WHERE id = ${n.contest.getId}".update.apply()
  }

  "the split's offer and confirmation" should {

    "offer each cluster as the report proposes it, and refuse what root can't confirm" in {
      val n = nepal466()
      val (p, _) = offerOf(n.contest)
      offerOfRound(p, n.own.head).mode === Mode.Stay
      offerOfRound(p, n.r2021.head).mode === Mode.Move
      offerOfRound(p, n.fashion).mode === Mode.Move
      offerOfRound(p, n.bhutan).mode === Mode.Move
      offerOfRound(p, n.philippines).mode === Mode.Empty
      offerOfRound(p, n.mixed).mode === Mode.Never
      val choose = offerOfRound(p, n.disagreeing)
      choose.mode === Mode.Choose
      // each identity its signals name: the filter's Philippines and the files' Nepal, 2023
      choose.alternatives.map(_.target.identity.describe).toSet ===
        Set("Wiki Loves Earth 2023 in Philippines", "Wiki Loves Earth 2023 in Nepal")
      // CA23: the people who ran the Bhutan round, listed, none ticked by the defaults
      targetOf(p, n.bhutan).runners.map(_.userId) === Seq(n.admin.getId)
      targetOf(p, n.bhutan).newAllowed must beTrue

      // an active round needs root's "pause it for the move"
      plan(n.contest, defaults) must beLeft(("split.active.round", Seq(n.bhutan.getId.toString)))
      // another offer than the page's
      plan(n.contest, p => defaults(p) + ("hash" -> Seq("x"))) must beLeft(("split.plan.changed", Nil))
      // nothing ticked
      plan(n.contest, p => Map("hash" -> Seq(p.hash))) must beLeft(("split.nothing", Nil))
      // an account typed for Admin that doesn't exist
      plan(n.contest, p => defaults(p) ++ Map("pause" -> Seq(n.bhutan.getId.toString),
        s"o${targetOf(p, n.bhutan).fieldId}" -> Seq("nobody@example.org"))) must beLeft(("split.admin.unknown", Seq("nobody@example.org")))
      // a runner the page didn't list
      plan(n.contest, p => defaults(p) ++ Map("pause" -> Seq(n.bhutan.getId.toString),
        s"a${targetOf(p, n.bhutan).fieldId}" -> Seq(n.juror.getId.toString))) must beLeft(("split.plan.changed", Nil))
      // a round being created can't be paused: finished or cancelled first
      DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 1, status = 'draft' WHERE id = ${n.r2021(1).getId}".update.apply())
      plan(n.contest, p => defaults(p) + ("pause" -> Seq(n.bhutan.getId.toString))) must
        beLeft(("split.round.distributing", Seq(n.r2021(1).getId.toString)))
    }

    "refuse a new contest where the series holds the year, and move into the existing one root picks" in {
      val n = nepal466()
      val root = rootUser
      val seriesId = DB.localTx(implicit s => ContestSeries.createSeries("Wiki Loves Earth in Nepal", Some("wle"), Some("Nepal"), None,
        root.getId).toOption.get)
      val existing = contestWithFiles("Wiki Loves Earth", 2021, "Nepal", Some("Category:Images from Wiki Loves Earth 2021 in Nepal"),
        Seq(150L))
      ContestSeries.setSeries(existing.getId, Some(seriesId), root.getId, None) must beRight
      val organizer = account("Target Organizer")
      UserContestJdbc.createMembership(organizer.getId, existing.getId, "organizer")
      UserContestJdbc.createMembership(n.juror.getId, existing.getId, "organizer")
      val only2021: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.r2021.head).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.r2021.head).alternatives.head.key)))
      val (p, _) = offerOf(n.contest)
      targetOf(p, n.r2021.head).holder must beSome(existing.getId)
      plan(n.contest, only2021) must beLeft(("split.target.taken", Seq("Wiki Loves Earth in Nepal", "2021")))
      val jobs = inline()
      val (id, job) = split(n.contest, root, jobs, p => only2021(p) + (s"t${targetOf(p, n.r2021.head).fieldId}" -> Seq(existing.getId.toString)))
      job.status === JobStatus.Done
      contestOf(n.r2021.head) === existing.getId
      // the existing target got its own category (its old one's files copied), then the matching files
      ContestJuryJdbc.findById(existing.getId).flatMap(_.categoryId) ===
        Some(DB.readOnly(implicit s => CategoryJdbc.findOrInsert(ImageService.ownCategory(existing.getId))))
      categoryFiles(existing.getId) === (101L to 120L) :+ 150L
      // the juror is an Organizer there: O14, skipped and listed, the Organizer role kept
      role(n.juror, existing.getId) must beSome("organizer")
      ContestSplitJdbc.items(id).filter(_.kind == ContestSplitJdbc.Kind.Skipped)
        .map(i => (i.subjectId, (i.newValue \ "reason").as[String])) === Seq((n.juror.id, "conflict"))
      ContestSplitJdbc.items(id).count(_.kind == ContestSplitJdbc.Kind.Contest) === 0
      // the target's rounds page notes the rounds moved in
      ContestSplit.notes(existing.getId).map(n => (n.out, n.rounds)) === Seq((false, 2))
    }
  }

  "the split" should {

    "move a 466-like contest's R1, R2 and R4 clusters into new contests, pausing and resuming the active round" in {
      val n = nepal466()
      withHistory(n)
      val root = rootUser
      val other = account("Other Admin")
      val before = votes()
      val sourceFiles = categoryFiles(n.contest.getId)
      val jobs = inline()
      val (id, job) = split(n.contest, root, jobs, p => defaults(p) ++ Map("pause" -> Seq(n.bhutan.getId.toString),
        s"a${targetOf(p, n.bhutan).fieldId}" -> Seq(n.admin.getId.toString),
        s"o${targetOf(p, n.r2021.head).fieldId}" -> Seq(s" ${other.email} ")))
      job.status === JobStatus.Done
      job.progressDone must beSome(3L)

      val (t2021, tFashion, tBhutan) = (contestOf(n.r2021.head), contestOf(n.fashion), contestOf(n.bhutan))
      Seq(t2021, tFashion, tBhutan).distinct.size === 3
      val targets = Seq(t2021, tFashion, tBhutan).map(ContestJuryJdbc.findById(_).get)
      targets.map(c => (c.name, c.year, c.country)) ===
        Seq(("Wiki Loves Earth", 2021, "Nepal"), ("Wiki Loves Fashion", 2022, "Philippines"), ("Wiki Loves Earth", 2026, "Bhutan"))
      // each in a series created on confirmation, of its type and country; created by root
      targets.map(_.seriesId.flatMap(SeriesJdbc.findById(_)).map(s => (s.name, s.contestType))) ===
        Seq(Some(("Wiki Loves Earth in Nepal", Some("wle"))), Some(("Wiki Loves Fashion in Philippines", Some("fashion"))),
          Some(("Wiki Loves Earth in Bhutan", Some("wle"))))
      ContestSeries.createdInfo(targets.map(_.getId)).values.map(_._1).toSet === Set(root.id)

      // the moved rounds: numbered after the target's last; the others stay
      n.r2021.map(roundRow).map(r => (r.contestId, r.number)) === Seq((t2021, 1L), (t2021, 2L))
      (n.own ++ Seq(n.philippines, n.disagreeing, n.mixed)).map(contestOf).distinct === Seq(n.contest.getId)
      // paused for the move, active again after it
      val bhutan = roundRow(n.bhutan)
      (bhutan.active, bhutan.status) === ((true, Some(Round.Status.Active)))
      ContestSplitJdbc.items(id).filter(_.kind == ContestSplitJdbc.Kind.Paused).flatMap(_.subjectId) === Seq(n.bhutan.getId)

      // exclude_rounds edges across the move cut, both ways, and recorded
      roundRow(n.r2021(1)).exclude must beEmpty
      roundRow(n.own(1)).exclude must beEmpty
      ContestSplitJdbc.items(id).count(_.kind == ContestSplitJdbc.Kind.ExcludeCut) === 1
      // comments and jobs follow their round; the source's current round cleared
      q(sql"SELECT contest_id FROM comment")(_.long(1)) === List(t2021)
      q(sql"SELECT contest_id FROM job WHERE kind = 'round_distribution'")(_.long(1)) === List(t2021)
      ContestJuryJdbc.findById(n.contest.getId).get.currentRound must beNone
      // no vote touched
      votes() === before

      // CA10: the source keeps its links; each target got the files of its own identity
      categoryFiles(n.contest.getId) === sourceFiles
      categoryFiles(t2021) === (101L to 120L)
      categoryFiles(tFashion) === (201L to 210L)
      categoryFiles(tBhutan) === (301L to 310L)

      // CA11: the moved jurors get the Juror role (the deleted one skipped, listed); CA23: the Admin
      // role for those root ticked or added; the source's memberships stay
      role(n.juror, t2021) must beSome(User.JURY_ROLE)
      role(other, t2021) must beSome(User.ADMIN_ROLE)
      role(n.admin, tBhutan) must beSome(User.ADMIN_ROLE)
      role(n.deleted, tBhutan) must beNone
      role(n.juror, n.contest.getId) must beSome(User.JURY_ROLE)
      role(n.admin, n.contest.getId) must beSome(User.ADMIN_ROLE)
      ContestSplitJdbc.items(id).filter(_.kind == ContestSplitJdbc.Kind.Skipped)
        .map(i => (i.subjectId, (i.newValue \ "reason").as[String])) === Seq((n.deleted.id, "deleted"))
      // in-app notices: the role rows with the "from contest" comment, no mail
      q(sql"""SELECT user_id, contest_id, new_value, comment, mail_sent FROM account_change WHERE field = 'role'
              ORDER BY user_id, contest_id""")(rs => (rs.long(1), rs.long(2), rs.string(3), rs.string(4), rs.booleanOpt(5))).toSet ===
        Set((n.juror.getId, t2021, "jury", s"from contest #${n.contest.getId}", None),
          (other.getId, t2021, "admin", s"from contest #${n.contest.getId}", None),
          (n.admin.getId, tBhutan, "admin", s"from contest #${n.contest.getId}", None))

      // the change log: one batch of the split's creations and moves
      val row = ContestSplitJdbc.findById(id).get
      row.batchId must beSome
      ContestChangeJdbc.findByBatch(row.batchId.get).map(_.kind).groupBy(identity).view.mapValues(_.size).toMap ===
        Map("created" -> 3, "split_out" -> 3, "split_in" -> 3)
      row.dumpRef must beSome("dump-1")
      // the source's rounds page notes the moves; the old contest-qualified links lead to the round
      ContestSplit.notes(n.contest.getId).map(n => (n.out, n.other, n.rounds)).toSet ===
        Set((true, t2021, 2), (true, tFashion, 1), (true, tBhutan, 1))
      ContestSplitJdbc.movedRound(n.r2021.head.getId, n.contest.getId) must beSome(t2021)
      ContestSplitJdbc.movedRound(n.own.head.getId, n.contest.getId) must beNone
    }

    "be undone: the rounds, links, comments, jobs and current round back, unused roles removed, created contests retired" in {
      val n = nepal466()
      withHistory(n)
      val root = rootUser
      val before = votes()
      val jobs = inline()
      val (id, _) = split(n.contest, root, jobs, p => defaults(p) ++ Map("pause" -> Seq(n.bhutan.getId.toString),
        s"a${targetOf(p, n.bhutan).fieldId}" -> Seq(n.admin.getId.toString)))
      val (t2021, tBhutan) = (contestOf(n.r2021.head), contestOf(n.bhutan))
      val linked = categoryFiles(t2021)

      val undone = ContestSplit.undo(id, root)
      undone must beRight
      undone.toOption.get.rounds === 4
      undone.toOption.get.retired.size === 3
      (n.r2021 ++ Seq(n.fashion, n.bhutan)).map(contestOf).distinct === Seq(n.contest.getId)
      n.r2021.map(roundRow).map(_.number) === Seq(3L, 4L)
      roundRow(n.r2021(1)).exclude === Seq(n.own.head.getId)
      roundRow(n.own(1)).exclude === Seq(n.r2021.head.getId)
      q(sql"SELECT contest_id FROM comment")(_.long(1)) === List(n.contest.getId)
      q(sql"SELECT contest_id FROM job WHERE kind = 'round_distribution'")(_.long(1)) === List(n.contest.getId)
      ContestJuryJdbc.findById(n.contest.getId).get.currentRound must beSome(n.r2021(1).getId)
      role(n.juror, t2021) must beNone
      role(n.admin, tBhutan) must beNone
      role(n.juror, n.contest.getId) must beSome(User.JURY_ROLE)
      ContestJuryJdbc.findById(t2021).get.retired must beTrue
      ContestJuryJdbc.findById(tBhutan).get.retired must beTrue
      // the file links stay (CA10); no vote touched; the active round still active
      categoryFiles(t2021) === linked
      votes() === before
      roundRow(n.bhutan).active must beTrue
      ContestChangeJdbc.findByContest(n.contest.getId, 1).map(_.kind) === Seq("split_undone")
      ContestSplitJdbc.movedRound(n.r2021.head.getId, n.contest.getId) must beNone
      // the removal is in the account change log
      q(sql"SELECT old_value, comment FROM account_change WHERE user_id = ${n.juror.getId} AND new_value IS NULL")(rs =>
        (rs.string(1), rs.string(2))) === List(("jury", s"split #$id undone"))
      ContestSplit.undo(id, root) must beLeft(("split.undo.already", Nil))
      ContestSplit.notes(n.contest.getId) must beEmpty
    }

    "keep on Undo a role used since and a target with rounds of its own; refuse it while a target round draws on a moved one" in {
      val n = nepal466()
      val root = rootUser
      val jobs = inline()
      val only2021: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.r2021.head).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.r2021.head).alternatives.head.key)))
      val (id, _) = split(n.contest, root, jobs, only2021)
      val t2021 = ContestJuryJdbc.findById(contestOf(n.r2021.head)).get
      // a round of the target drawing on a moved one: Undo would break it
      val next = round(t2021, 3, "Final", status = Round.Status.Paused, previous = Seq(n.r2021(1).getId), jurors = Seq(n.juror))
      val refused = ContestSplit.undo(id, root)
      refused must beLeft(("split.undo.referred", Seq(next.getId.toString, t2021.getId.toString)))
      DB.readOnly(implicit s => ContestSplit.undoRefusal(ContestSplitJdbc.findById(id).get)) must beSome
      contestOf(n.r2021.head) === t2021.getId
      // once it draws on none: undone, the juror's role kept (a juror of the target's own round), the target kept
      DB.autoCommit(implicit s => sql"UPDATE round SET previous = NULL WHERE id = ${next.getId}".update.apply())
      val undone = ContestSplit.undo(id, root).toOption.get
      undone.keptMembers === Seq(n.juror.getId -> t2021.getId)
      undone.keptContests === Seq(t2021.getId)
      role(n.juror, t2021.getId) must beSome(User.JURY_ROLE)
      ContestJuryJdbc.findById(t2021.getId).get.retired must beFalse
    }

    "move a cluster root chose, and an empty one ticked with the others; never the mixed one" in {
      val n = nepal466()
      val root = rootUser
      val jobs = inline()
      val (id, job) = split(n.contest, root, jobs, { p =>
        val choose = offerOfRound(p, n.disagreeing)
        val nepal2023 = choose.alternatives.find(_.target.identity.describe == "Wiki Loves Earth 2023 in Nepal").get
        val empty = offerOfRound(p, n.philippines)
        Map("hash" -> Seq(p.hash), s"c${choose.cluster.index}" -> Seq(fieldId(nepal2023.key)),
          s"c${empty.cluster.index}" -> Seq(fieldId(empty.alternatives.head.key)),
          s"c${offerOfRound(p, n.mixed).cluster.index}" -> Seq("anything"))
      })
      job.status === JobStatus.Done
      val t2023 = ContestJuryJdbc.findById(contestOf(n.disagreeing)).get
      (t2023.name, t2023.year, t2023.country) === (("Wiki Loves Earth", 2023, "Nepal"))
      // its files' categories name Nepal 2023: linked
      categoryFiles(t2023.getId) === (401L to 420L)
      val tPh = ContestJuryJdbc.findById(contestOf(n.philippines)).get
      (tPh.year, tPh.country) === ((2023, "Philippines"))
      categoryFiles(tPh.getId) must beEmpty
      contestOf(n.mixed) === n.contest.getId
      ContestSplitJdbc.findById(id).flatMap(ContestSplit.planOf).get.moves.size === 2
    }
  }

  "the split's job" should {

    "fail when a round joins a cluster meanwhile, resume what it paused, and finish on Retry" in {
      val n = nepal466()
      val root = rootUser
      val h = new Hooked()
      val jobs = inline(h)
      var joined = Option.empty[Long]
      h.beforeMove = () => if (joined.isEmpty)
        joined = Some(round(n.contest, 10, "Bhutan final", status = Round.Status.Paused, previous = Seq(n.bhutan.getId)).getId)
      val onlyBhutan: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.bhutan).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.bhutan).alternatives.head.key)),
        "pause" -> Seq(n.bhutan.getId.toString))
      val (id, failed) = split(n.contest, root, jobs, onlyBhutan)
      failed.status === JobStatus.Failed
      failed.failure.map(_.key) must beSome("job.error.split.rounds.joined")
      // the round it paused is active again, still in the source; the target created stays for Retry
      roundRow(n.bhutan).active must beTrue
      contestOf(n.bhutan) === n.contest.getId
      DB.readOnly(implicit s => ContestSplit.undoRefusal(ContestSplitJdbc.findById(id).get)) must beSome(("split.undo.failed", Nil))
      // the joining round gone: Retry pauses it again and moves it
      DB.autoCommit(implicit s => sql"DELETE FROM round WHERE id = ${joined.get}".update.apply())
      // no other split of the contest while it waits for its Retry or Cancel
      val onlyFashion: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.fashion).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.fashion).alternatives.head.key)))
      ContestSplit.confirm(plan(n.contest, onlyFashion).toOption.get, None, root, jobs) must
        beLeft(("split.previous.failed", Seq(id.toString)))
      jobs.retry(failed.id, by = root.id) must beRight
      val done = JobJdbc.findById(failed.id).get
      done.status === JobStatus.Done
      contestOf(n.bhutan) must be_!=(n.contest.getId)
      (roundRow(n.bhutan).active, roundRow(n.bhutan).status) === ((true, Some(Round.Status.Active)))
    }

    "be cancelled, resuming its paused round, then undone" in {
      val n = nepal466()
      val root = rootUser
      val h = new Hooked()
      val jobs = inline(h)
      h.beforeMove = () => DB.autoCommit(implicit s =>
        sql"UPDATE round SET distributing = 1 WHERE id = ${n.bhutan.getId}".update.apply())
      val onlyBhutan: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.bhutan).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.bhutan).alternatives.head.key)),
        "pause" -> Seq(n.bhutan.getId.toString))
      val (id, failed) = split(n.contest, root, jobs, onlyBhutan)
      failed.failure.map(_.key) must beSome("job.error.split.round.busy")
      // its creation done meanwhile: Cancel resumes the round it paused
      DB.autoCommit(implicit s => sql"UPDATE round SET distributing = 0 WHERE id = ${n.bhutan.getId}".update.apply())
      jobs.cancel(failed.id, root.id).map(_.status) must beSome(JobStatus.Cancelled)
      roundRow(n.bhutan).active must beTrue
      val target = ContestSplitJdbc.items(id).find(_.kind == ContestSplitJdbc.Kind.Contest).flatMap(_.subjectId).get
      val undone = ContestSplit.undo(id, root)
      undone.map(_.retired) must beRight(Seq(target))
      ContestJuryJdbc.findById(target).get.retired must beTrue
      // and the job can't be retried once undone
      jobs.retry(failed.id, by = root.id) must beLeft
    }

    "refuse a second split of a contest while one is queued, and let root alone manage it" in {
      val n = nepal466()
      val root = rootUser
      val idle = new JobScheduler(Seq(new Hooked()), JobScheduler.Settings())
      val onlyFashion: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.fashion).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.fashion).alternatives.head.key)))
      val first = ContestSplit.confirm(plan(n.contest, onlyFashion).toOption.get, None, root, idle)
      first must beRight
      ContestSplit.confirm(plan(n.contest, onlyFashion).toOption.get, None, root, idle) must
        beLeft(("split.running", Seq(first.toOption.get._2.id.toString)))
      q(sql"SELECT COUNT(*) FROM contest_split")(_.long(1)) === List(1L)
      val job = first.toOption.get._2
      new Hooked().mayManage(job, root) must beTrue
      new Hooked().mayManage(job, n.admin.copy(roles = Set(User.ADMIN_ROLE), contestId = n.contest.id)) must beFalse
      DB.readOnly(implicit s => ContestSplit.undoRefusal(ContestSplitJdbc.findById(first.toOption.get._1).get)) must
        beSome(("split.undo.running", Nil))
    }

    "be taken over after its instance died (lease), resuming from its committed state" in {
      val n = nepal466()
      val root = rootUser
      val h = new Hooked(chunk = 3)
      val idle = new JobScheduler(Seq(h), JobScheduler.Settings())
      val only2021: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.r2021.head).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.r2021.head).alternatives.head.key)))
      val (id, job) = ContestSplit.confirm(plan(n.contest, only2021).toOption.get, None, root, idle).toOption.get
      DB.autoCommit(implicit s => sql"""UPDATE job SET status = 'running', owner = 'dead-instance', attempts = 1,
              heartbeat_at = NOW(3) - INTERVAL 120 SECOND WHERE id = ${job.id}""".update.apply())
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = 1, pollInterval = 20.millis,
        heartbeatInterval = 100.millis, lease = 1.second))
      try {
        s.start()
        s.await(job.id, 30.seconds).status === JobStatus.Done
      } finally s.stop()
      n.r2021.map(contestOf).distinct.size === 1
      contestOf(n.r2021.head) must be_!=(n.contest.getId)
      // the files linked in chunks of 3 by the page-id cursor, each once
      categoryFiles(contestOf(n.r2021.head)) === (101L to 120L)
      ContestSplitJdbc.items(id).find(_.kind == ContestSplitJdbc.Kind.Files).map(i => (i.newValue \ "linked").as[Long]) must beSome(20L)
    }

    "lose no vote cast while it runs, in the moved rounds or in another contest" in {
      val n = nepal466()
      val root = rootUser
      val elsewhere = contestWithFiles("Wiki Loves Monuments", 2025, "Ukraine", None, 9001L to 9020L)
      val juror2 = account("Busy Juror")
      val otherRound = round(elsewhere, 1, "Open", files = 9001L to 9020L, jurors = Seq(juror2))
      DB.autoCommit { implicit s =>
        (101L to 120L).foreach(p => sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($p, ${n.juror.getId}, ${n.r2021.head.getId}, 0)".update.apply())
        (9001L to 9020L).foreach(p => sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES ($p, ${juror2.getId}, ${otherRound.getId}, 0)".update.apply())
      }
      val only2021: Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash),
        s"c${offerOfRound(p, n.r2021.head).cluster.index}" -> Seq(fieldId(offerOfRound(p, n.r2021.head).alternatives.head.key)))
      val h = new Hooked(chunk = 2)
      val s = new JobScheduler(Seq(h), JobScheduler.Settings(workers = 2, pollInterval = 10.millis))
      val (_, job) = ContestSplit.confirm(plan(n.contest, only2021).toOption.get, None, root, s).toOption.get
      val stop = new java.util.concurrent.atomic.AtomicBoolean(false)
      val cast = new java.util.concurrent.ConcurrentLinkedQueue[(Long, Long, Int)]()
      val voter = new Thread(() => {
        var i = 0
        while (!stop.get) {
          val rate = 1 + i % 5
          val (page, juror, rnd) = if (i % 2 == 0) (101L + i % 20, n.juror.getId, n.r2021.head.getId) else (9001L + i % 20, juror2.getId, otherRound.getId)
          if (VoteJdbc.rate(page, juror, rnd, rate) == 1) cast.add((rnd, page, rate))
          i += 1
        }
      })
      voter.start()
      try {
        s.start()
        s.await(job.id, 30.seconds).status === JobStatus.Done
      } finally { stop.set(true); voter.join(10000); s.stop() }
      cast.size must be_>(0)
      // each file's last vote is the one stored, in both rounds
      import scala.jdk.CollectionConverters._
      val last = cast.asScala.toSeq.groupBy(c => (c._1, c._2)).view.mapValues(_.last._3).toMap
      val stored = q(sql"SELECT round_id, page_id, rate FROM vote")(rs => (rs.long(1), rs.long(2)) -> rs.int(3)).toMap
      last.forall { case (k, rate) => stored.get(k).contains(rate) } must beTrue
      stored.size === 40
      contestOf(n.r2021.head) must be_!=(n.contest.getId)
    }
  }

  "the split after its review" should {

    def only(rounds: Round*): Proposal => Map[String, Seq[String]] = p => Map("hash" -> Seq(p.hash)) ++
      rounds.map(r => s"c${offerOfRound(p, r).cluster.index}" -> Seq(fieldId(offerOfRound(p, r).alternatives.head.key)))

    "move several years into one missing series, creating it once; keep on Undo a target in use" in {
      val n = nepal466()
      val c = n.contest
      addFiles(c, 601L to 610L)
      inCountries(wle(2025), "Nepal" -> (601L to 610L))
      val organizer = account("Round Organizer")
      UserContestJdbc.createMembership(organizer.getId, c.getId, "organizer")
      val y2025 = round(c, 10, "First Round 2025", status = Round.Status.Paused, category = Some(wle(2025) + "Nepal"),
        files = 601L to 610L)
      DB.autoCommit(implicit s => sql"""INSERT INTO round_user (user_id, round_id, role, active)
        VALUES (${organizer.getId}, ${y2025.getId}, 'organizer', TRUE)""".update.apply())
      val root = rootUser
      val (id, job) = split(c, root, inline(), only(n.r2021.head, y2025))
      job.status === JobStatus.Done
      val (t2021, t2025) = (contestOf(n.r2021.head), contestOf(y2025))
      val series = Seq(t2021, t2025).map(ContestJuryJdbc.findById(_).get.seriesId)
      series.distinct.size === 1
      series.head must beSome
      SeriesJdbc.findAll().map(_.name) === Seq("Wiki Loves Earth in Nepal")
      ContestSplitJdbc.items(id).count(_.kind == ContestSplitJdbc.Kind.Series) === 1
      // round_user's organizer is an Organizer there (V62's mapping), not a Juror
      role(organizer, t2025) must beSome("organizer")
      // a target someone else joined since is in use: kept on Undo, the other retired
      val newcomer = account("Newcomer")
      UserContestJdbc.createMembership(newcomer.getId, t2025, User.JURY_ROLE)
      val undone = ContestSplit.undo(id, root).toOption.get
      undone.keptContests === Seq(t2025)
      undone.retired === Seq(t2021)
    }

    "refuse Undo while a moved round is busy, or has jurors who are no members of the source" in {
      val n = nepal466()
      val root = rootUser
      val (id, _) = split(n.contest, root, inline(), only(n.r2021.head))
      val y1 = n.r2021.head.getId
      DB.autoCommit(implicit s => sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state, updated_at)
        VALUES ('round_image_check', ${contestOf(n.r2021.head)}, 'round', $y1, 'x', 'queued', '{}', '{}', NOW(3))""".update.apply())
      ContestSplit.undo(id, root) must beLeft(("split.undo.busy", Seq(y1.toString)))
      DB.autoCommit(implicit s => sql"UPDATE job SET status = 'done' WHERE kind = 'round_image_check'".update.apply())
      DB.autoCommit(implicit s => sql"UPDATE round SET freeze_count = 1 WHERE id = $y1".update.apply())
      ContestSplit.undo(id, root) must beLeft(("split.undo.busy", Seq(y1.toString)))
      DB.autoCommit(implicit s => sql"UPDATE round SET freeze_count = 0 WHERE id = $y1".update.apply())
      // a juror added in the target, no member of the source: Undo would take the round away from them
      val added = account("Target Juror")
      DB.autoCommit(implicit s => sql"""INSERT INTO round_user (user_id, round_id, role, active)
        VALUES (${added.getId}, $y1, 'jury', TRUE)""".update.apply())
      ContestSplit.undo(id, root) must beLeft(("split.undo.jurors", Seq("Target Juror")))
      UserContestJdbc.createMembership(added.getId, n.contest.getId, User.JURY_ROLE)
      ContestSplit.undo(id, root) must beRight
    }

    "leave a round finalized during the pause finalized, and give the role to a juror added during the split" in {
      val n = nepal466()
      val root = rootUser
      val h = new Hooked()
      val late = account("Late Juror")
      h.beforeMove = () => DB.autoCommit { implicit s =>
        sql"UPDATE round SET status = 'finalized' WHERE id = ${n.bhutan.getId}".update.apply()
        sql"""INSERT IGNORE INTO round_user (user_id, round_id, role, active)
              VALUES (${late.getId}, ${n.bhutan.getId}, 'jury', TRUE)""".update.apply()
      }
      val (id, job) = split(n.contest, root, inline(h), p => only(n.bhutan)(p) + ("pause" -> Seq(n.bhutan.getId.toString)))
      job.status === JobStatus.Done
      val bhutan = roundRow(n.bhutan)
      (bhutan.status, bhutan.active) === ((Some(Round.Status.Finalized), false))
      role(late, contestOf(n.bhutan)) must beSome(User.JURY_ROLE)
      ContestSplitJdbc.items(id).filter(_.kind == ContestSplitJdbc.Kind.Membership).flatMap(_.subjectId) must contain(late.getId)
    }

    "list the jurors of an active round who lose access, and require root's acknowledgement" in {
      val n = nepal466()
      val voter = account("Voter Without Round")
      DB.autoCommit(implicit s =>
        sql"INSERT INTO vote (page_id, jury_id, round_id, rate) VALUES (301, ${voter.getId}, ${n.bhutan.getId}, 1)".update.apply())
      val (p, _) = offerOf(n.contest)
      p.losing.getOrElse(n.bhutan.getId, Nil).map(l => (l.userId, l.reason)) === Seq((voter.getId, "no_round_user"))
      val form: Proposal => Map[String, Seq[String]] = p => only(n.bhutan)(p) + ("pause" -> Seq(n.bhutan.getId.toString))
      plan(n.contest, form) must beLeft(("split.access.ack", Nil))
      plan(n.contest, p => form(p) + ("ack" -> Seq("yes"))) must beRight
      // a cluster without such jurors needs none
      plan(n.contest, only(n.fashion)) must beRight
    }

    "keep on Undo the Admin role in a kept target; put an existing unassigned target into the series and take it out on Undo" in {
      val n = nepal466()
      val root = rootUser
      val existing = contestWithFiles("Wiki Loves Earth", 2021, "Nepal", None, Seq(150L))
      val boss = account("Target Boss")
      val (p, _) = offerOf(n.contest)
      val t = targetOf(p, n.r2021.head)
      t.newAllowed must beTrue
      t.existing must contain(existing.getId)
      val (id, _) = split(n.contest, root, inline(), p => only(n.r2021.head)(p) ++
        Map(s"t${targetOf(p, n.r2021.head).fieldId}" -> Seq(existing.getId.toString),
          s"o${targetOf(p, n.r2021.head).fieldId}" -> Seq(boss.email)))
      val joined = ContestJuryJdbc.findById(existing.getId).get
      joined.seriesId must beSome
      SeriesJdbc.findById(joined.seriesId.get).map(_.name) must beSome("Wiki Loves Earth in Nepal")
      role(boss, existing.getId) must beSome(User.ADMIN_ROLE)
      ContestSplit.undo(id, root) must beRight
      // the existing target is kept: its Admin role stays; it leaves the series again
      role(boss, existing.getId) must beSome(User.ADMIN_ROLE)
      ContestJuryJdbc.findById(existing.getId).get.seriesId must beNone
      ContestJuryJdbc.findById(existing.getId).get.retired must beFalse
      ContestChangeJdbc.findByContest(existing.getId, 10).map(_.kind) must contain("series_unassigned")
    }

    "refuse without the acknowledgement a pick of an existing target where a juror of the active round holds another role" in {
      val n = nepal466()
      val j = account("Bhutan Juror")
      DB.autoCommit(implicit s => sql"""INSERT INTO round_user (user_id, round_id, role, active)
        VALUES (${j.getId}, ${n.bhutan.getId}, 'jury', TRUE)""".update.apply())
      val a = contestWithFiles("Wiki Loves Earth", 2026, "Bhutan", None, Seq(900L))
      val b = contestWithFiles("Wiki Loves Earth", 2026, "Bhutan", None, Seq(901L))
      UserContestJdbc.createMembership(j.getId, b.getId, "organizer")
      // an organizer of the round (round_user) who is an Organizer in the target keeps that role: no conflict
      val org = account("Round Organizer B")
      DB.autoCommit(implicit s => sql"""INSERT INTO round_user (user_id, round_id, role, active)
        VALUES (${org.getId}, ${n.bhutan.getId}, 'organizer', TRUE)""".update.apply())
      UserContestJdbc.createMembership(org.getId, n.contest.getId, "organizer")
      UserContestJdbc.createMembership(org.getId, b.getId, "organizer")
      val (p, _) = offerOf(n.contest)
      targetOf(p, n.bhutan).existing must containAllOf(Seq(a.getId, b.getId))
      p.conflicts.get(a.getId) must beNone
      p.conflicts.getOrElse(b.getId, Nil).map { case (r, l) => (r, l.userId, l.reason) } === Seq((n.bhutan.getId, j.getId, "conflict"))
      def pick(c: Option[Long], ack: Boolean): Proposal => Map[String, Seq[String]] = p => only(n.bhutan)(p) ++
        Map("pause" -> Seq(n.bhutan.getId.toString)) ++
        c.map(id => s"t${targetOf(p, n.bhutan).fieldId}" -> Seq(id.toString)) ++ Option.when(ack)("ack" -> Seq("yes"))
      plan(n.contest, pick(None, ack = false)) must beRight
      plan(n.contest, pick(Some(a.getId), ack = false)) must beRight
      plan(n.contest, pick(Some(b.getId), ack = false)) must beLeft(("split.access.ack", Nil))
      plan(n.contest, pick(Some(b.getId), ack = true)).map(_.moves.head.targetContest) must beRight(Some(b.getId))
    }
  }
}
