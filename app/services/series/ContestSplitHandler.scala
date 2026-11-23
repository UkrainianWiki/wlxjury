package services.series

import db.scalikejdbc._
import org.intracer.wmua.ContestJury
import play.api.{Configuration, Logging}
import play.api.libs.json._
import scalikejdbc._
import services.{AccountChangeLog, ImageService, UserService}
import services.jobs._
import services.series.ContestSplit.MovePlan

import javax.inject.{Inject, Singleton}

/** The resume cursor of a `contest_split` job (`job.state`).
  *
  * @param move   the move ([[ContestSplit.MovePlan]]) being done, from 0
  * @param phase  its step: `create`, `files`, `people`, `move`
  * @param cursor the files step's last page id linked
  * @param target the move's target contest, once created or locked
  * @param linked files linked into it so far
  * @param paused active rounds this job paused that haven't moved yet (resumed if it fails or is
  *               cancelled, paused again on Retry)
  */
final case class SplitState(move: Int = 0, phase: String = ContestSplitHandler.Phase.Create, cursor: Long = 0L,
    target: Option[Long] = None, linked: Long = 0L, paused: Seq[Long] = Nil)

object SplitState {
  implicit val format: OFormat[SplitState] = Json.using[Json.WithDefaultValues].format[SplitState]
}

/** A split of a reused contest as a [[Job]] (contest series plan §4.4; CY-4, CA13): its moves one
  * after the other, each in four steps, each step one transaction:
  *
  *  1. **create**: the target's series locked (or created: "root creates a missing series on
  *     confirmation"), then the source's and an existing target's rows (id order); the
  *     preconditions checked ([[ContestSplit.preconditionFailure]]); the active rounds root ticked
  *     paused (V105 `paused`, their status recorded); the target created (the series' type, country
  *     and year, root as its creator, its own category, a `created` row) or the existing one given
  *     its own category;
  *  2. **files**: the moved rounds' files in the matching categories linked into the target's own
  *     category, in chunks with a page-id cursor (`INSERT IGNORE`); the source keeps its links (CA10);
  *  3. **people**: the Juror role in the target for the moved rounds' active jurors, the Admin role
  *     for those root ticked (CA11, CA23), each with an account change row the owner sees as an
  *     in-app notice (no mail); deleted accounts, root (protected), O14 conflicts (another role
  *     there, or Admin ticked for a juror), those there already and those an account join holds are
  *     skipped and recorded (`skipped`);
  *  4. **move**: series, contests and rounds locked in that order, the preconditions again; the
  *     rounds get the target's contest and numbers after its last, `exclude_rounds` edges to rounds
  *     staying behind cut (and the staying rounds' to moved ones), their comments and jobs follow by
  *     round, the source's current round cleared if it moved, the paused rounds resumed;
  *     `split_out` and `split_in` rows.
  *
  * Every change is recorded in `contest_split_item` in its step's transaction, for Undo. Votes are
  * never touched: they are keyed by round id, which doesn't change. A failure (a precondition, a
  * refusal) fails the job and resumes the rounds it paused; Retry pauses them again and resumes
  * the step. Root only: [[mayManage]] refuses anyone else Cancel and Retry (the job is of the
  * source contest, whose admins would otherwise manage it).
  */
@Singleton
class ContestSplitHandler @Inject() (config: Configuration) extends JobHandler with Logging {

  import ContestSplitHandler._

  val kind: String = Kind

  private val chunk = config.getOptional[Int]("series.split.chunk").filter(_ > 0).getOrElse(DefaultChunk)
  private val log = play.api.Logger("round.admin")

  override def title(job: Job): String = s"Splitting contest ${job.contestId.fold("")(id => s"#$id")}".trim

  override def details(job: Job): JsObject = {
    val st = stateOf(job)
    Json.obj("move" -> (st.move + 1), "phase" -> st.phase, "linked" -> st.linked)
  }

  override def mayManage(job: Job, user: User): Boolean = user.isRoot || user.hasRole(User.ROOT_ROLE)

  private def fail(key: String, st: SplitState, total: Int, args: Any*): StepResult =
    StepResult.Failed(JobError(key, args.map(_.toString)), Json.toJsObject(st), progress(st, total))

  private def progress(st: SplitState, total: Int) =
    Progress(done = Some(st.move.toLong), total = Some(total.toLong), unit = Some("moves"))

  def step(job: Job, ctx: StepContext): StepResult = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val split = splitOf(job)
    val plan = split.flatMap(ContestSplit.planOf)
    (split, plan) match {
      case (None, _) | (_, None) => StepResult.Failed(JobError("job.error.split.missing"), job.state, Progress())
      case (Some(s), _) if s.undone => fail("job.error.split.undone", st, 0)
      case (Some(s), Some(p)) if st.move >= p.moves.size =>
        StepResult.Done(Json.toJsObject(st), progress(st, p.moves.size))
      case (Some(s), Some(p)) =>
        val m = p.moves(st.move)
        val actor = User.findById(s.createdBy).getOrElse(User("", "", id = Some(s.createdBy)))
        st.phase match {
          case Phase.Create => create(s, p, m, st, actor, ctx)
          case Phase.Files  => files(s, p, m, st, ctx)
          case Phase.People => people(s, p, m, st, actor)
          case _            => move(s, p, m, st, actor, ctx)
        }
    }
  }

  private def next(st: SplitState, total: Int): StepResult = StepResult.Continue(Json.toJsObject(st), progress(st, total))

  /** The contest rows, locked `FOR UPDATE` in id order (after the series'). */
  private def lockContests(ids: Seq[Long])(implicit session: DBSession): Map[Long, ContestJury] = {
    val sorted = ids.distinct.sorted
    sql"SELECT id FROM contest_jury WHERE id IN ($sorted) ORDER BY id FOR UPDATE".map(_.long(1)).list.apply()
    sorted.flatMap(id => ContestJuryJdbc.findById(id)).flatMap(c => c.id.map(_ -> c)).toMap
  }

  /** The split's batch of `contest_change` rows: the first row starts it. */
  private def change(s: ContestSplitRow, contest: Long, series: Option[Long], actor: Long, kind: String, detail: JsObject)(implicit
      session: DBSession): Unit = {
    val batch = ContestSplitJdbc.findById(s.id).flatMap(_.batchId)
    val id = ContestChangeJdbc.create(Some(contest), series, actor, kind, batch, detail ++ Json.obj("splitId" -> s.id))
    if (batch.isEmpty) { ContestChangeJdbc.startBatch(id); ContestSplitJdbc.setBatch(s.id, id) }
  }

  // ── 1. pause and create ─────────────────────────────────────────────────

  private def create(s: ContestSplitRow, p: ContestSplit.Plan, m: MovePlan, st: SplitState, actor: User, ctx: StepContext)(implicit
      session: DBSession): StepResult = {
    val total = p.moves.size
    // the plan's series; else the one an earlier move of this split created, or a live series of the
    // move's type and place created meanwhile: never a second series for one identity
    val known = m.seriesId.orElse(splitSeries(s, m)).orElse(liveSeries(m))
    val series = known.map(id => id -> SeriesJdbc.lock(id))
    series match {
      case Some((_, None))                       => fail("job.error.split.series.gone", st, total, m.seriesName)
      case Some((_, Some(se))) if se.retired     => fail("job.error.split.series.retired", st, total, se.name)
      case _ =>
        val contests = lockContests(s.contestId +: m.targetContest.toSeq)
        val existing = m.targetContest.flatMap(contests.get)
        val holder = known.flatMap(sid =>
          sql"SELECT id FROM contest_jury WHERE live_series_id = $sid AND year = ${m.year}".map(_.long(1)).single.apply())
        if (!contests.contains(s.contestId)) fail("job.error.split.missing", st, total)
        else if (m.targetContest.isDefined && existing.forall(c => c.retired || c.getId == s.contestId ||
            c.seriesId.exists(cs => !known.contains(cs))))
          fail("job.error.split.target.changed", st, total, m.targetContest.get)
        else if (holder.exists(h => !m.targetContest.contains(h))) fail("job.error.split.year.taken", st, total, m.seriesName, m.year)
        else ContestSplit.preconditionFailure(s.contestId, m, m.targetContest, allowActive = true) match {
          case Some(error) => StepResult.Failed(error, Json.toJsObject(st), progress(st, total))
          case None =>
            // the series to create, its name not taken meanwhile
            val seriesId: Either[String, Long] = known.map(Right(_)).getOrElse(
              ContestSeries.createSeries(m.seriesName, Some(m.typeCode), Some(m.country), m.campaign, actor.getId))
            seriesId match {
              case Left(_) => fail("job.error.split.series.name", st, total, m.seriesName)
              case Right(sid) =>
                if (known.isEmpty) {
                  SeriesJdbc.lock(sid)
                  ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Series, Some(sid), None,
                    newValue = Json.obj("name" -> m.seriesName, "typeCode" -> m.typeCode, "place" -> m.place))
                }
                val paused = pause(s, m, actor)
                // the series is locked and its year checked free: the key's refusal can only be a crash
                val target = existing.fold(newTarget(s, m, sid, actor))(lockedTarget(s, m, _, sid, actor))
                next(st.copy(phase = Phase.Files, target = Some(target), cursor = 0L, linked = 0L,
                  paused = (st.paused ++ paused).distinct), total)
            }
        }
    }
  }

  /** The series an earlier move of this split created for the move's type and place. */
  private def splitSeries(s: ContestSplitRow, m: MovePlan)(implicit session: DBSession): Option[Long] =
    ContestSplitJdbc.items(s.id).filter(i => i.kind == ContestSplitJdbc.Kind.Series &&
      (i.newValue \ "typeCode").asOpt[String].contains(m.typeCode) && (i.newValue \ "place").asOpt[String].contains(m.place))
      .flatMap(_.subjectId).headOption

  /** A live series of the move's type and place (one root created meanwhile): the first match in
    * [[SeriesJdbc.findAll]]'s order (name, id), as the report's target picks the series it offers.
    */
  private def liveSeries(m: MovePlan)(implicit session: DBSession): Option[Long] =
    SeriesJdbc.findAll().filter(_.live).find(se => se.contestType.exists(_.equalsIgnoreCase(m.typeCode)) &&
      se.country.map(c => SeriesIdentity.placeOfText(c).key).contains(m.place)).map(_.id)

  /** Pauses the move's active rounds root ticked (§4.5), recording their status. */
  private def pause(s: ContestSplitRow, m: MovePlan, actor: User)(implicit session: DBSession): Seq[Long] =
    m.pause.sorted.filter { id =>
      val status = sql"SELECT status FROM round WHERE id = $id AND active = 1 FOR UPDATE".map(_.stringOpt(1)).single.apply()
      status.exists { (before: Option[String]) =>
        val prior: String = before.getOrElse(Round.Status.Active)
        sql"""UPDATE round SET status = ${Round.Status.Paused}, status_changed_at = NOW(3), status_changed_by = ${actor.getId},
                active = 0, created_at = created_at
              WHERE id = $id AND active = 1""".update.apply()
        ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Paused, Some(id), Some(s.contestId),
          oldValue = Json.obj("status" -> prior, "active" -> true))
        true
      }
    }

  /** An existing target root confirmed: given its own category if it has none (as an import does),
    * and put into the move's series if it is in none (Undo takes it out again).
    */
  private def lockedTarget(s: ContestSplitRow, m: MovePlan, c: ContestJury, seriesId: Long, actor: User)(implicit
      session: DBSession): Long = {
    val own = CategoryJdbc.findOrInsert(ImageService.ownCategory(c.getId))
    if (!c.categoryId.contains(own)) {
      c.categoryId.foreach(from => CategoryLinkJdbc.copyIgnore(from, own))
      ContestJuryJdbc.setImagesSource(c.getId, c.images, Some(own))
    }
    val joins = c.seriesId.isEmpty
    if (joins) {
      sql"UPDATE contest_jury SET series_id = $seriesId WHERE id = ${c.getId} AND series_id IS NULL".update.apply()
      change(s, c.getId, Some(seriesId), actor.getId, ContestChangeJdbc.SeriesAssigned, Json.obj("to" -> seriesId, "how" -> "split"))
    }
    ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.ContestExisting, c.id, c.id,
      oldValue = Json.obj("categoryId" -> c.categoryId, "seriesId" -> c.seriesId),
      newValue = Json.obj("categoryId" -> own, "seriesId" -> seriesId, "joinedSeries" -> joins))
    c.getId
  }

  /** A new target: the series' type, country and year, its own category, root as its creator. */
  private def newTarget(s: ContestSplitRow, m: MovePlan, seriesId: Long, actor: User)(implicit session: DBSession): Long = {
      val c = ContestJuryJdbc.create(None, m.typeName, m.year, m.country, campaign = m.campaign)
      val own = CategoryJdbc.findOrInsert(ImageService.ownCategory(c.getId))
      ContestJuryJdbc.setImagesSource(c.getId, None, Some(own))
      sql"""UPDATE contest_jury SET series_id = $seriesId, created_at = NOW(3), created_by = ${actor.getId}
            WHERE id = ${c.getId}""".update.apply()
      change(s, c.getId, Some(seriesId), actor.getId, ContestChangeJdbc.Created,
        Json.obj("name" -> m.typeName, "year" -> m.year, "country" -> m.country, "how" -> "split", "from" -> s.contestId))
      ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Contest, c.id, c.id,
        newValue = Json.obj("seriesId" -> seriesId, "year" -> m.year))
      log.info(s"contest_created contestId=${c.getId} how=split splitId=${s.id} from=${s.contestId} by=${actor.getId}")
      c.getId
    }

  // ── 2. files ────────────────────────────────────────────────────────────

  private def files(s: ContestSplitRow, p: ContestSplit.Plan, m: MovePlan, st: SplitState, ctx: StepContext)(implicit
      session: DBSession): StepResult = {
    val total = p.moves.size
    val target = st.target.flatMap(ContestJuryJdbc.findById(_))
    target.flatMap(_.categoryId) match {
      case None => fail("job.error.split.target.changed", st, total, st.target.getOrElse(0L))
      case Some(category) =>
        val pages =
          if (m.matching.isEmpty) Nil
          else
            sql"""SELECT DISTINCT ri.page_id FROM round_image ri
                    STRAIGHT_JOIN category_members cm ON cm.page_id = ri.page_id
                  WHERE ri.round_id IN (${m.rounds}) AND cm.category_id IN (${m.matching}) AND ri.page_id > ${st.cursor}
                  ORDER BY ri.page_id LIMIT $chunk""".map(_.long(1)).list.apply()
        if (pages.nonEmpty) {
          CategoryLinkJdbc.linkIgnore(category, pages)
          ctx.afterCommit(RegionCountCache.invalidateCategory(category))
        }
        val linked = st.linked + pages.size
        if (pages.size == chunk) next(st.copy(cursor = pages.last, linked = linked), total)
        else {
          ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Files, None, Some(target.get.getId),
            newValue = Json.obj("linked" -> linked, "categories" -> m.matching))
          next(st.copy(phase = Phase.People, cursor = 0L, linked = linked), total)
        }
    }
  }

  // ── 3. jurors and admins ────────────────────────────────────────────────

  private def people(s: ContestSplitRow, p: ContestSplit.Plan, m: MovePlan, st: SplitState, actor: User)(implicit
      session: DBSession): StepResult = {
    val total = p.moves.size
    val target = st.target.get
    val jurors = ContestSplit.roundRoles(m.rounds)
    give(s, m, target, actor, jurors, m.admins, lock = true)
    logger.info(s"contest_split people splitId=${s.id} move=${m.index} target=$target jurors=${jurors.size} admins=${m.admins.size}")
    next(st.copy(phase = Phase.Move), total)
  }

  /** Gives the moved rounds' people their role in the target and the Admin role to `admins`, each
    * with the account change row its owner sees as an in-app notice (no mail), recorded as
    * `membership`; or records why not (`skipped`): a deleted account, root (protected), another role
    * there or an Admin tick for one of the move's people (O14), the role there already, an account
    * join holding it. `lock`: the accounts' rows locked in id order first (the people step; the move
    * step, which holds the rounds, reads them only).
    */
  private def give(s: ContestSplitRow, m: MovePlan, target: Long, actor: User, people: Seq[(Long, String)], admins: Seq[Long],
      lock: Boolean)(implicit session: DBSession): Unit = {
    val all = (people.map(_._1) ++ admins).distinct.sorted
    if (all.nonEmpty) {
      val accounts = (if (lock) all.flatMap(id => User.lockRow(id)) else User.findByIds(all)).flatMap(u => u.id.map(_ -> u)).toMap
      val roles = sql"SELECT user_id, role FROM user_contest WHERE contest_id = $target AND user_id IN ($all)"
        .map(rs => rs.long(1) -> rs.string(2)).list.apply().toMap
      val joining = AccountJoinJdbc.joining(all)
      val comment = UserService.fromContestComment(s.contestId)
      val inRounds = people.map(_._1).toSet
      def one(id: Long, role: String, conflict: Boolean): Unit = {
        val reason = accounts.get(id) match {
          case None                                             => Some("deleted")
          case Some(a) if a.deletedAt.nonEmpty                  => Some("deleted")
          case Some(a) if a.isRoot || a.hasRole(User.ROOT_ROLE) => Some("protected")
          case _ if roles.get(id).contains(role)                => Some("already")
          case _ if roles.contains(id) || conflict              => Some("conflict")
          case _ if joining(id)                                 => Some("in_join")
          case _                                                => None
        }
        reason match {
          case Some(r) =>
            ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Skipped, Some(id), Some(target),
              newValue = Json.obj("role" -> role, "reason" -> r))
          case None =>
            if (UserContestJdbc.createMembership(id, target, role)) {
              AccountChangeLog.record(actor, id, Some(target), AccountChangeJdbc.Role, newValue = Some(role), comment = Some(comment))
              ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Membership, Some(id), Some(target),
                newValue = Json.obj("role" -> role))
            }
        }
      }
      people.foreach { case (id, role) => one(id, role, conflict = false) }
      // O14: one of the move's people gets their round role, never Admin with it
      admins.foreach(id => one(id, User.ADMIN_ROLE, conflict = inRounds(id)))
    }
  }

  // ── 4. move ─────────────────────────────────────────────────────────────

  private def move(s: ContestSplitRow, p: ContestSplit.Plan, m: MovePlan, st: SplitState, actor: User, ctx: StepContext)(implicit
      session: DBSession): StepResult = {
    val total = p.moves.size
    val target = st.target.get
    m.seriesId.orElse(ContestJuryJdbc.findById(target).flatMap(_.seriesId)).foreach(SeriesJdbc.lock)
    val contests = lockContests(Seq(s.contestId, target))
    val roundIds = sql"SELECT id FROM round WHERE contest_id IN (${Seq(s.contestId, target)})".map(_.long(1)).list.apply()
    Round.lock(roundIds)
    ContestSplit.preconditionFailure(s.contestId, m, Some(target), allowActive = false) match {
      case Some(error) => StepResult.Failed(error, Json.toJsObject(st), progress(st, total))
      case None if !contests.contains(target) => fail("job.error.split.target.changed", st, total, target)
      case None =>
        val moving = m.rounds.toSet
        val sourceRounds = ContestSplit.roundsNow(sqls"contest_id = ${s.contestId}")
        var number = (ContestSplit.roundsNow(sqls"contest_id = $target").map(_.number) :+ 0L).max
        val cut = scala.collection.mutable.Buffer.empty[(Long, Long)]
        sourceRounds.filter(r => moving(r.id)).foreach { r =>
          number += 1
          val exclude = r.exclude.filter(moving)
          r.exclude.filterNot(moving).foreach(x => cut += (r.id -> x))
          sql"""UPDATE round SET contest_id = $target, number = $number,
                  exclude_rounds = ${Option.when(exclude.nonEmpty)(exclude.mkString(","))}, created_at = created_at
                WHERE id = ${r.id}""".update.apply()
          ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Round, Some(r.id), Some(target),
            oldValue = Json.obj("contestId" -> s.contestId, "number" -> r.number, "exclude" -> r.exclude),
            newValue = Json.obj("contestId" -> target, "number" -> number, "exclude" -> exclude))
        }
        sourceRounds.filter(r => !moving(r.id) && r.exclude.exists(moving)).foreach { r =>
          val exclude = r.exclude.filterNot(moving)
          r.exclude.filter(moving).foreach(x => cut += (r.id -> x))
          sql"""UPDATE round SET exclude_rounds = ${Option.when(exclude.nonEmpty)(exclude.mkString(","))}, created_at = created_at
                WHERE id = ${r.id}""".update.apply()
          ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.ExcludeCut, Some(r.id), Some(s.contestId),
            oldValue = Json.obj("exclude" -> r.exclude), newValue = Json.obj("exclude" -> exclude))
        }
        val comments = sql"UPDATE comment SET contest_id = $target WHERE round_id IN (${m.rounds}) AND contest_id = ${s.contestId}"
          .update.apply()
        val jobs = sql"""UPDATE job SET contest_id = $target WHERE subject_type = 'round' AND subject_id IN (${m.rounds})
                         AND contest_id = ${s.contestId}""".update.apply()
        if (comments > 0) ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Comments, None, Some(target),
          oldValue = Json.obj("contestId" -> s.contestId), newValue = Json.obj("count" -> comments))
        if (jobs > 0) ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.Jobs, None, Some(target),
          oldValue = Json.obj("contestId" -> s.contestId), newValue = Json.obj("count" -> jobs))
        contests.get(s.contestId).flatMap(_.currentRound).filter(moving).foreach { r =>
          sql"UPDATE contest_jury SET current_round = NULL WHERE id = ${s.contestId}".update.apply()
          ContestSplitJdbc.addItem(s.id, m.index, ContestSplitJdbc.Kind.CurrentRound, Some(s.contestId), Some(s.contestId),
            oldValue = Json.obj("currentRound" -> r))
        }
        // the moved rounds' people added since the people step (or before a Retry): their role now
        val recorded = ContestSplitJdbc.items(s.id).filter(i => i.moveIndex == m.index &&
          (i.kind == ContestSplitJdbc.Kind.Skipped || i.kind == ContestSplitJdbc.Kind.Membership)).flatMap(_.subjectId).toSet
        val late = ContestSplit.roundRoles(m.rounds).filterNot { case (id, _) => recorded(id) }
        if (late.nonEmpty) {
          val members = sql"SELECT user_id FROM user_contest WHERE contest_id = $target AND user_id IN (${late.map(_._1)})"
            .map(_.long(1)).list.apply().toSet
          give(s, m, target, actor, late.filterNot { case (id, _) => members(id) }, Nil, lock = false)
        }
        // resume what step 1 paused
        val resumed = st.paused.filter(moving)
        resumed.foreach { id =>
          sql"""UPDATE round SET status = ${Round.Status.Active}, status_changed_at = NOW(3), status_changed_by = ${actor.getId},
                  active = 1, created_at = created_at
                WHERE id = $id AND active = 0 AND distributing = 0 AND freeze_count = 0
                  AND status <=> ${Round.Status.Paused}""".update.apply()
        }
        val detail = Json.obj("rounds" -> m.rounds, "move" -> m.index)
        val sourceSeries = contests.get(s.contestId).flatMap(_.seriesId)
        val targetSeries = contests.get(target).flatMap(_.seriesId)
        change(s, s.contestId, sourceSeries, actor.getId, ContestChangeJdbc.SplitOut, detail ++ Json.obj("to" -> target))
        change(s, target, targetSeries, actor.getId, ContestChangeJdbc.SplitIn, detail ++ Json.obj("from" -> s.contestId))
        log.info(s"contest_split_moved splitId=${s.id} move=${m.index} from=${s.contestId} to=$target rounds=${m.rounds.mkString(",")} " +
          s"comments=$comments jobs=$jobs cut=${cut.size} resumed=${resumed.size} by=${actor.getId}")
        val done = st.copy(move = st.move + 1, phase = Phase.Create, target = None, cursor = 0L, linked = 0L,
          paused = st.paused.filterNot(moving))
        if (done.move >= total) StepResult.Done(Json.toJsObject(done), progress(done, total)) else next(done, total)
    }
  }

  // ── failure, cancel, retry ──────────────────────────────────────────────

  /** Resumes the rounds this job paused that haven't moved: still in the source, paused by it. One
    * being created or frozen meanwhile can't be resumed yet: it stays in the state, for a Cancel.
    */
  private def resume(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val left = splitOf(job).fold(st.paused) { s =>
      if (st.paused.isEmpty) Nil
      else {
        Round.lock(st.paused)
        sql"""UPDATE round SET status = ${Round.Status.Active}, status_changed_at = NOW(3), status_changed_by = ${s.createdBy},
                active = 1, created_at = created_at
              WHERE id IN (${st.paused}) AND contest_id = ${s.contestId} AND active = 0 AND distributing = 0
                AND freeze_count = 0 AND status <=> ${Round.Status.Paused}""".update.apply()
        sql"""SELECT id FROM round WHERE id IN (${st.paused}) AND contest_id = ${s.contestId} AND active = 0
                AND status <=> ${Round.Status.Paused}""".map(_.long(1)).list.apply()
      }
    }
    Json.toJsObject(st.copy(paused = left))
  }

  override def onFailed(job: Job, ctx: StepContext): JsObject = resume(job, ctx)

  override def onCancelled(job: Job, ctx: StepContext): JsObject = resume(job, ctx)

  /** Retry: refused once the split is undone; past its create step, the move's ticked rounds still
    * active are paused again (the failure resumed them).
    */
  override def onRetry(job: Job, ctx: StepContext): JsObject = {
    implicit val session: DBSession = ctx.session
    val st = stateOf(job)
    val split = splitOf(job)
    if (split.exists(_.undone)) throw JobScheduler.RetryRefusal("split.undo.already")
    val again = for {
      s <- split.toSeq
      p <- ContestSplit.planOf(s).toSeq
      if st.move < p.moves.size && st.phase != Phase.Create
      m = p.moves(st.move)
      actor <- User.findById(s.createdBy).toSeq
      id <- pause(s, m, actor)
    } yield id
    Json.toJsObject(st.copy(paused = (st.paused ++ again).distinct))
  }

  private def splitOf(job: Job)(implicit session: DBSession): Option[ContestSplitRow] =
    (job.params \ "splitId").asOpt[Long].flatMap(ContestSplitJdbc.findById(_))
}

object ContestSplitHandler {

  val Kind = "contest_split"

  val DefaultChunk = 5000

  object Phase {
    val Create = "create"
    val Files = "files"
    val People = "people"
    val Move = "move"
  }

  private val log = play.api.Logger(classOf[ContestSplitHandler])

  def stateOf(job: Job): SplitState =
    job.state.validate[SplitState].fold(
      errors => {
        log.warn(s"contest_split job ${job.id}: its state doesn't parse (${errors.map(_._1.toString).mkString(", ")}); read as new")
        SplitState()
      },
      identity)
}
