package services.series

import controllers.Secured
import db.scalikejdbc._
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.libs.json._
import scalikejdbc._
import services.{AccountChangeLog, ApiTokenService}
import services.jobs.{Job, JobScheduler, NewJob, Progress}
import services.series.ReuseReport.{Catalog, Cluster, Report, Runner, Target}

/** Splitting a reused contest (contest series plan §4.4–§4.6; PR CY-4; CA9–CA13 and CA23 decided by
  * the maintainer 2026-11-20): root confirms one contest's plan on its split page, a
  * `contest_split` job ([[ContestSplitHandler]]) moves the confirmed clusters of rounds into
  * contests of the right series and year, and Undo reverses it by its `contest_split_item` rows.
  *
  * **The offer** ([[propose]]): from the reuse report ([[ReuseReport]]), per cluster: a proposed move
  * (ticked), an empty cluster of another identity (unticked: "leave" by default, root may move it
  * with the others), root's choice where the signals disagree (each identity they name, "leave" by
  * default; never automatic), never (R3: mixed files) or stays (its own). Clusters with one target
  * (series and year) are one move. Per target: a new contest (by default), or an existing contest of
  * that series and year only when root picks it (refused while a live contest holds the series and
  * year and root hasn't picked it), and the people who ran its clusters, none ticked (CA23), with a
  * field for others. Each active round of a ticked cluster needs root's "pause it for the move"
  * (§4.5). The page carries the offer's hash: a confirmation of another offer than the one shown
  * (the rounds, their status, the targets or the people changed meanwhile) is refused.
  *
  * **The plan** ([[plan]]) is what root confirmed; [[confirm]] records it (`contest_split`) and queues
  * its job, one split per contest at a time (the job's dedupe key).
  */
object ContestSplit {

  private val log = Logger("round.admin")

  type Refusal = (String, Seq[String])

  private def refusal(key: String, args: Any*): Refusal = (key, args.map(_.toString))

  /** SHA-256 of `text` in lowercase hex, by the project's one SHA-256 helper (RawSqlSpec: digests only there). */
  def sha256(text: String): String = ApiTokenService.hash(text)

  /** The message keys of a refusal, a job's failure and an account skipped (MessagesCompletenessSpec). */
  object Keys {
    val Refusals: Seq[String] = Seq("split.plan.changed", "split.nothing", "split.round.distributing", "split.active.round",
      "split.target.taken", "split.admin.unknown", "split.running", "split.previous.failed", "split.not.found", "split.undo.already",
      "split.undo.running", "split.undo.failed", "split.undo.changed", "split.undo.referred", "split.undo.busy",
      "split.undo.jurors", "split.access.ack")
    val JobErrors: Seq[String] = Seq("missing", "undone", "series.gone", "series.retired", "target.changed", "year.taken",
      "series.name", "rounds.gone", "rounds.joined", "round.busy", "round.active", "target.refers").map("job.error.split." + _)
    val SkipReasons: Seq[String] = Seq("deleted", "protected", "conflict", "already", "in_join")
    /** Why a juror of an active round to move loses access with the move ([[Losing]]). */
    val LosingReasons: Seq[String] = Seq("conflict", "in_join", "no_round_user")
    val Modes: Seq[String] = Seq(Mode.Move, Mode.Empty, Mode.Choose, Mode.Never, Mode.Stay)
  }

  // ── the offer ─────────────────────────────────────────────────────────────

  object Mode {
    /** Proposed: ticked. */
    val Move = "move"
    /** An empty cluster of another identity: unticked (leave by default). */
    val Empty = "empty"
    /** Signals that disagree: root picks one of the identities they name, or leave (the default). */
    val Choose = "choose"
    /** R3, mixed files: never moved. */
    val Never = "never"
    /** Its own (or no sign of another): stays. */
    val Stay = "stay"
  }

  /** A target a cluster may move to: its series and year (`key`). */
  final case class Alternative(key: String, target: Target)

  final case class Offer(cluster: Cluster, mode: String, alternatives: Seq[Alternative]) {
    def selectable: Boolean = alternatives.nonEmpty && (mode == Mode.Move || mode == Mode.Empty || mode == Mode.Choose)
  }

  /** One target of the offer: `holder`, the live contest of its existing series and year (then the
    * move can only go there, root picking it); `existing`, the contests root may pick; `runners`, the
    * people who ran the clusters that may move there (CA23).
    */
  final case class TargetOffer(key: String, target: Target, holder: Option[Long], existing: Seq[Long], runners: Seq[Runner]) {
    def newAllowed: Boolean = holder.isEmpty
    def fieldId: String = ContestSplit.fieldId(key)
  }

  /** A juror of an active round to move who gets no role in the target, so loses access to the
    * round with the move: another role there (O14, `conflict`), an account join holding the account
    * (`in_join`), or votes in the round without being one of its jurors (`no_round_user`).
    */
  final case class Losing(userId: Long, name: String, reason: String)

  /** `losing`: per active round, its jurors who lose access whatever the target (`in_join`,
    * `no_round_user`); `conflicts`: per existing contest root may pick, the jurors of the active rounds
    * that may move there who hold another role in it (`conflict`, by round).
    */
  final case class Proposal(report: Report, offers: Seq[Offer], targets: Seq[TargetOffer], hash: String,
      losing: Map[Long, Seq[Losing]] = Map.empty, conflicts: Map[Long, Seq[(Long, Losing)]] = Map.empty) {
    /** Of the rounds, the jurors who lose access, moved into `target` (an existing contest, or None: a new one). */
    def losingIn(rounds: Seq[Long], target: Option[Long]): Seq[(Long, Losing)] =
      rounds.flatMap(r => losing.getOrElse(r, Nil).map(r -> _)) ++
        target.toSeq.flatMap(t => conflicts.getOrElse(t, Nil).filter(c => rounds.contains(c._1)))
    def anyLosing: Boolean = losing.nonEmpty || conflicts.nonEmpty
    def selectable: Boolean = offers.exists(_.selectable)
    def target(key: String): Option[TargetOffer] = targets.find(_.key == key)
  }

  /** A target's key: its series' type and place, and the year. */
  def keyOf(t: Target): String = s"${t.seriesType.code}~${t.place.key}~${t.year}"

  /** A key as the form names it. */
  def fieldId(key: String): String = sha256(key).take(12)

  /** The host's own key: a cluster's alternative of it is no move. */
  private def hostKey(host: ContestJury): Option[String] =
    SeriesIdentity.typeOf(host).map(t => s"${t.code}~${SeriesIdentity.placeOf(host).key}~${host.year}")

  /** The identities a cluster's signals name, completed as the report completes them (its type,
    * year and country where a signal names none), for root's choice.
    */
  private def candidates(c: Cluster): Seq[services.diagnostics.ContestIdentity] = {
    def completed(id: services.diagnostics.ContestIdentity) =
      services.diagnostics.ContestIdentity(id.kind.orElse(c.identity.kind), id.year.orElse(c.identity.year),
        id.country.orElse(c.identity.country))
    (c.identity +: (c.signals.s5r.map(_._3) ++ c.signals.s2.maxByOption(_._2).map(_._1)).map(completed)).distinct
  }

  def propose(report: Report, catalog: Catalog)(implicit session: DBSession): Proposal = {
    val host = report.contest
    val own = hostKey(host)
    def alt(t: Target) = Alternative(keyOf(t), t)
    val offers = report.clusters.map { c =>
      val proposed = c.target.map(alt).filterNot(a => own.contains(a.key)).toSeq
      import ReuseReport.Action
      c.action match {
        case Action.Move                 => Offer(c, Mode.Move, proposed)
        case Action.Leave if c.mixed     => Offer(c, Mode.Never, Nil)
        case Action.Leave if c.empty     => Offer(c, if (proposed.isEmpty) Mode.Stay else Mode.Empty, proposed)
        case Action.Choose =>
          val alts = candidates(c).flatMap(id => ReuseReport.targetFor(host, id, catalog)).map(alt)
            .filterNot(a => own.contains(a.key)).distinctBy(_.key)
          Offer(c, if (alts.isEmpty) Mode.Stay else Mode.Choose, alts)
        case _ => Offer(c, Mode.Stay, Nil)
      }
    }
    val targets = offers.flatMap(o => o.alternatives.map(a => a -> o.cluster)).groupBy(_._1.key).toSeq
      .sortBy { case (_, xs) => xs.map(_._2.index).min }
      .map { case (key, xs) =>
        val t = xs.head._1.target
        val holder = t.seriesId.flatMap(sid => catalog.contests.find(c => !c.retired && c.seriesId.contains(sid) && c.year == t.year))
          .flatMap(_.id)
        TargetOffer(key, t, holder, (holder.toSeq ++ t.contests).distinct,
          xs.flatMap(_._2.runners).distinctBy(_.userId))
      }
    val losing = losingOf(offers.filter(_.selectable))
    val conflicts = conflictsOf(offers.filter(_.selectable), targets)
    Proposal(report, offers, targets, hashOf(host, offers, targets, losing, conflicts), losing, conflicts)
  }

  /** The active rounds of the clusters, each with its active jurors. */
  /** The rounds' active people with the role their membership in a target takes, as V62 maps
    * round_user: organizer if one of their rows says so, else Juror. The split's people step gives
    * exactly these ([[ContestSplitHandler]]), and the offer's conflicts compare with them.
    */
  def roundRoles(rounds: Seq[Long])(implicit session: DBSession): Seq[(Long, String)] =
    if (rounds.isEmpty) Nil
    else
      sql"""SELECT user_id, MAX(role = 'organizer') FROM round_user WHERE round_id IN ($rounds) AND active = TRUE
            GROUP BY user_id ORDER BY user_id"""
        .map(rs => rs.long(1) -> (if (rs.booleanOpt(2).getOrElse(false)) "organizer" else User.JURY_ROLE)).list.apply()

  /** Per existing contest a target offers, the people of the active rounds that may move there who
    * hold another role in it than the one the split would give them (O14: they get none, so lose
    * access to the round). The role is the move's ([[roundRoles]] over every round that may move to
    * that target). Live accounts only, root aside.
    */
  private def conflictsOf(offers: Seq[Offer], targets: Seq[TargetOffer])(implicit session: DBSession): Map[Long, Seq[(Long, Losing)]] = {
    val activeIds = offers.flatMap(_.cluster.rounds.filter(_.status == Round.Status.Active).map(_.id))
    val active: Map[Long, Seq[Long]] = if (activeIds.isEmpty) Map.empty
      else sql"SELECT round_id, user_id FROM round_user WHERE round_id IN ($activeIds) AND active = TRUE"
        .map(rs => rs.long(1) -> rs.long(2)).list.apply().groupMap(_._1)(_._2).view.mapValues(_.distinct).toMap
    targets.flatMap { t =>
      val moving = offers.filter(_.alternatives.exists(_.key == t.key)).flatMap(_.cluster.roundIds)
      val rounds = moving.filter(active.contains)
      val people = rounds.flatMap(active).distinct
      val wanted = if (people.isEmpty) Map.empty[Long, String] else roundRoles(moving).toMap
      t.existing.map { c =>
        val other = if (people.isEmpty) Map.empty[Long, String]
          else sql"""SELECT uc.user_id, u.fullname, uc.role FROM user_contest uc JOIN users u ON u.id = uc.user_id
                     WHERE uc.contest_id = $c AND uc.user_id IN ($people) AND u.deleted_at IS NULL AND NOT u.is_root"""
            .map(rs => (rs.long(1), rs.stringOpt(2).getOrElse(""), rs.string(3))).list.apply()
            .collect { case (id, name, role) if !wanted.get(id).contains(role) => id -> name }.toMap
        c -> rounds.flatMap(r => active(r).filter(other.contains).map(id => r -> Losing(id, other(id), "conflict")))
          .sortBy { case (r, l) => (r, l.name, l.userId) }
      }
    }.filter(_._2.nonEmpty).toMap
  }

  /** Per active round of the clusters, the jurors who lose access with the move ([[Losing]]). */
  private def losingOf(offers: Seq[Offer])(implicit session: DBSession): Map[Long, Seq[Losing]] = {
    val active = offers.flatMap(o => o.cluster.rounds.filter(_.status == Round.Status.Active).map(r => r.id -> o.cluster))
    if (active.isEmpty) Map.empty
    else {
      val roundIds = active.map(_._1)
      val assigned = sql"SELECT round_id, user_id, active FROM round_user WHERE round_id IN ($roundIds)"
        .map(rs => (rs.long(1), rs.long(2), rs.booleanOpt(3).getOrElse(false))).list.apply()
      val voters = sql"SELECT DISTINCT round_id, jury_id FROM vote WHERE round_id IN ($roundIds)"
        .map(rs => rs.long(1) -> rs.long(2)).list.apply()
      val joining = AccountJoinJdbc.joining((assigned.map(_._2) ++ voters.map(_._2)).distinct)
      val people = (assigned.map(_._2) ++ voters.map(_._2)).distinct
      val accounts = if (people.isEmpty) Map.empty[Long, (String, Boolean)]
        else sql"SELECT id, fullname, deleted_at IS NOT NULL FROM users WHERE id IN ($people)"
          .map(rs => rs.long(1) -> (rs.stringOpt(2).getOrElse(""), rs.boolean(3))).list.apply().toMap
      active.map { case (round, cluster) =>
        val jurors = assigned.filter(a => a._1 == round && a._3).map(_._2).distinct
        val any = assigned.filter(_._1 == round).map(_._2).toSet
        val lose = jurors.filter(joining).map(_ -> "in_join") ++ voters.filter(v => v._1 == round && !any(v._2)).map(_._2).distinct.map(_ -> "no_round_user")
        round -> lose.filterNot { case (id, _) => accounts.get(id).forall(_._2) }
          .map { case (id, why) => Losing(id, accounts.get(id).map(_._1).getOrElse(""), why) }.sortBy(l => (l.name, l.userId))
      }.filter(_._2.nonEmpty).toMap
    }
  }

  /** The offer's hash: what the page showed (the clusters, their rounds, active rounds and modes, the
    * targets, the contests root may pick and the people listed).
    */
  private def hashOf(host: ContestJury, offers: Seq[Offer], targets: Seq[TargetOffer], losing: Map[Long, Seq[Losing]],
      conflicts: Map[Long, Seq[(Long, Losing)]]): String =
    sha256((Seq(s"contest:${host.getId}") ++
      offers.map(o => s"cluster:${o.cluster.index}|${o.cluster.roundIds.mkString(",")}|${o.mode}|" +
        s"${o.cluster.rounds.filter(_.active).map(r => s"${r.id}:${r.status}:${r.distributing}").mkString(",")}|" +
        o.alternatives.map(_.key).mkString(",")) ++
      targets.map(t => s"target:${t.key}|${t.target.seriesId.getOrElse("")}|${t.target.seriesName}|${t.holder.getOrElse("")}|" +
        s"${t.existing.mkString(",")}|${t.runners.map(_.userId).mkString(",")}") ++
      losing.toSeq.sortBy(_._1).map { case (r, ls) => s"losing:$r|${ls.map(l => s"${l.userId}:${l.reason}").mkString(",")}" } ++
      conflicts.toSeq.sortBy(_._1).map { case (c, ls) => s"conflicts:$c|${ls.map { case (r, l) => s"$r:${l.userId}" }.mkString(",")}" })
      .mkString("\n"))

  // ── the plan ──────────────────────────────────────────────────────────────

  /** One move: the clusters going to one target (series and year).
    *
    * @param seriesId      the existing series; None: create `seriesName` (type `typeCode`, country `country`)
    * @param targetContest the existing contest root picked; None: a new one
    * @param matching      the categories of the moved files that name the target's identity: those files
    *                      are linked into the target's own category
    * @param pause         active rounds root ticked "pause it for the move"
    * @param admins        accounts root ticked (or added) for the Admin role in the target (CA23)
    */
  final case class MovePlan(index: Int, key: String, typeCode: String, typeName: String, place: String, country: String,
      year: Int, seriesId: Option[Long], seriesName: String, campaign: Option[String], targetContest: Option[Long],
      clusters: Seq[Int], rounds: Seq[Long], matching: Seq[Long], pause: Seq[Long], admins: Seq[Long]) {
    def identity: String = s"$typeName $year${if (country == SeriesIdentity.InternationalText) " " + country else " in " + country}"
  }

  final case class Plan(contestId: Long, moves: Seq[MovePlan], offerHash: String)

  object Plan {
    implicit val moveFormat: OFormat[MovePlan] = Json.format[MovePlan]
    implicit val format: OFormat[Plan] = Json.format[Plan]
  }

  def planOf(split: ContestSplitRow): Option[Plan] = split.plan.asOpt[Plan]

  def planHash(plan: Plan): String = sha256(Json.stringify(Json.toJson(plan)))

  type Form = Map[String, Seq[String]]

  private def one(form: Form, name: String): Option[String] = form.get(name).flatMap(_.headOption).map(_.trim).filter(_.nonEmpty)

  /** The accounts typed in "add others": ids or email addresses, separated by commas, spaces or lines.
    * Each must be an account that isn't deleted.
    */
  def accountsTyped(text: String)(implicit session: DBSession = AutoSession): Either[Refusal, Seq[Long]] = {
    val tokens = text.split("[,;\\s]+").map(_.trim).filter(_.nonEmpty).toSeq.distinct
    val found = tokens.map { t =>
      val id = t.toLongOption match {
        case Some(n) => sql"SELECT id FROM users WHERE id = $n AND deleted_at IS NULL".map(_.long(1)).single.apply()
        case None =>
          sql"SELECT id FROM users WHERE LOWER(email) = LOWER($t) AND deleted_at IS NULL ORDER BY id LIMIT 1"
            .map(_.long(1)).single.apply()
      }
      t -> id
    }
    found.collectFirst { case (t, None) => t } match {
      case Some(unknown) => Left(refusal("split.admin.unknown", unknown))
      case None          => Right(found.flatMap(_._2).distinct)
    }
  }

  /** Root's confirmation as a plan, or why it is refused (nothing is written either way).
    *
    * The form: `hash` (the offer's), `c<cluster>` (an alternative's [[fieldId]], or `leave`; absent:
    * leave), `pause` (round ids), and per target (`<id>` its field id) `t<id>` (`new` or a contest id),
    * `a<id>` (runners' account ids ticked for Admin), `o<id>` (others, typed).
    */
  def plan(proposal: Proposal, form: Form, catalog: Catalog)(implicit session: DBSession): Either[Refusal, Plan] = {
    val host = proposal.report.contest
    if (!one(form, "hash").contains(proposal.hash)) return Left(refusal("split.plan.changed"))
    val pause = form.getOrElse("pause", Nil).flatMap(_.trim.toLongOption).toSet
    val chosen: Seq[Either[Refusal, Option[(Offer, Alternative)]]] = proposal.offers.filter(_.selectable).map { o =>
      one(form, s"c${o.cluster.index}") match {
        case None | Some("leave") => Right(None)
        case Some(id) => o.alternatives.find(a => fieldId(a.key) == id).fold[Either[Refusal, Option[(Offer, Alternative)]]](
          Left(refusal("split.plan.changed")))(a => Right(Some(o -> a)))
      }
    }
    chosen.collectFirst { case Left(r) => r } match {
      case Some(r) => Left(r)
      case None =>
        val picked = chosen.collect { case Right(Some(x)) => x }
        if (picked.isEmpty) Left(refusal("split.nothing"))
        else {
          val notPausable = picked.flatMap(_._1.cluster.rounds).find(r => r.distributing || r.status == Round.Status.Draft)
          val unticked = picked.flatMap(_._1.cluster.rounds).find(r => r.status == Round.Status.Active && !pause(r.id))
          (notPausable, unticked) match {
            case (Some(r), _) => Left(refusal("split.round.distributing", r.id))
            case (_, Some(r)) => Left(refusal("split.active.round", r.id))
            case _ =>
              val groups = picked.groupBy(_._2.key).toSeq.sortBy { case (_, xs) => xs.map(_._1.cluster.index).min }
              val moves = groups.zipWithIndex.map { case ((key, xs), i) => movePlan(i + 1, key, xs, proposal, form, pause, catalog) }
              moves.collectFirst { case Left(r) => r } match {
                case Some(r) => Left(r)
                case None =>
                  val planned = moves.collect { case Right(m) => m }
                  // the jurors who lose access, against the target root picked for each move
                  val losing = planned.flatMap(m => proposal.losingIn(m.rounds, m.targetContest))
                  if (losing.nonEmpty && !one(form, "ack").contains("yes")) Left(refusal("split.access.ack"))
                  else Right(Plan(host.getId, planned, proposal.hash))
              }
          }
        }
    }
  }

  private def movePlan(index: Int, key: String, xs: Seq[(Offer, Alternative)], proposal: Proposal, form: Form, pause: Set[Long],
      catalog: Catalog)(implicit session: DBSession): Either[Refusal, MovePlan] = {
    val host = proposal.report.contest
    val offer = proposal.target(key).get
    val t = offer.target
    val fid = offer.fieldId
    val contest: Either[Refusal, Option[Long]] = one(form, s"t$fid") match {
      case Some("new") | None if offer.newAllowed => Right(None)
      case Some("new") | None => Left(refusal("split.target.taken", t.seriesName, t.year))
      case Some(id) => id.toLongOption.filter(offer.existing.contains).fold[Either[Refusal, Option[Long]]](
        Left(refusal("split.plan.changed")))(c => Right(Some(c)))
    }
    val ticked = form.getOrElse(s"a$fid", Nil).flatMap(_.trim.toLongOption).distinct
    val admins: Either[Refusal, Seq[Long]] =
      if (ticked.exists(id => !offer.runners.exists(_.userId == id))) Left(refusal("split.plan.changed"))
      else one(form, s"o$fid").fold[Either[Refusal, Seq[Long]]](Right(Nil))(accountsTyped).map(others => (ticked ++ others).distinct)
    for {
      target <- contest
      adminIds <- admins
    } yield {
      val clusters = xs.map(_._1.cluster)
      val rounds = clusters.flatMap(_.roundIds).distinct.sorted
      val matching = xs.flatMap { case (o, a) =>
        if (o.cluster.target.exists(ct => keyOf(ct) == a.key)) o.cluster.matching
        else ReuseReport.matchingCategories(o.cluster.roundIds, host.categoryId, a.target.identity, catalog)
      }.distinct.sorted
      val series = t.seriesId.flatMap(id => catalog.series.find(_.id == id))
      MovePlan(index, key, t.seriesType.code, t.seriesType.name, t.place.key,
        series.flatMap(_.country).getOrElse(SeriesIdentity.countryName(t.place)), t.year, t.seriesId, t.seriesName,
        series.flatMap(_.campaign), target, clusters.map(_.index), rounds, matching,
        clusters.flatMap(_.rounds).filter(r => r.status == Round.Status.Active && pause(r.id)).map(_.id).sorted, adminIds)
    }
  }

  /** The dedupe key of a contest's split jobs: one at a time. */
  def dedupeKey(contestId: Long): String = s"${ContestSplitHandler.Kind}:contest:$contestId"

  /** Records the plan and queues its job, in one transaction; the split's id and its job. Refused
    * while another split of the contest is queued or running (nothing written). The caller calls
    * `jobs.started` with the job's id.
    */
  def confirm(plan: Plan, dumpRef: Option[String], actor: User, jobs: JobScheduler): Either[Refusal, (Long, Job)] =
    try Right(WriteTx.write { implicit session =>
        // a failed split of the contest holds no dedupe key, but its Retry or Cancel comes first
        ContestSplitJdbc.findByContest(plan.contestId).find(s => !s.undone &&
            s.jobId.flatMap(services.jobs.JobJdbc.findById(_)).exists(_.isFailed))
          .foreach(failed => throw SplitRefused(refusal("split.previous.failed", failed.id)))
        val splitId = ContestSplitJdbc.create(plan.contestId, Json.toJsObject(plan), planHash(plan),
          dumpRef.map(_.trim).filter(_.nonEmpty), actor.getId)
        jobs.enqueue(NewJob(ContestSplitHandler.Kind, contestId = Some(plan.contestId), subjectType = Some("contest"),
          subjectId = Some(plan.contestId), dedupeKey = Some(dedupeKey(plan.contestId)),
          params = Json.obj("splitId" -> splitId), state = Json.toJsObject(SplitState()),
          progress = Progress(done = Some(0), total = Some(plan.moves.size.toLong), unit = Some("moves")),
          createdBy = actor.id)) match {
          case Left(active) => throw SplitRefused(refusal("split.running", active.id))
          case Right(job) =>
            ContestSplitJdbc.setJob(splitId, job.id)
            log.info(s"contest_split_confirmed splitId=$splitId contestId=${plan.contestId} moves=${plan.moves.size} " +
              s"rounds=${plan.moves.map(_.rounds.size).sum} job=${job.id} by=${actor.getId}")
            splitId -> job
        }
      })
    catch { case SplitRefused(r) => Left(r) }

  /** A refusal that rolls its transaction back. */
  final case class SplitRefused(r: Refusal) extends RuntimeException(r._1, null, false, false)

  // ── reading the rounds as they are ────────────────────────────────────────

  private[series] final case class RoundNow(id: Long, contestId: Long, number: Long, previous: Seq[Long], exclude: Seq[Long],
      status: Option[String], active: Boolean, distributing: Boolean, frozen: Boolean)

  private[series] def roundsNow(where: SQLSyntax)(implicit session: DBSession): Seq[RoundNow] =
    sql"""SELECT id, contest_id, number, previous, exclude_rounds, status, active, distributing, freeze_count > 0 FROM round
          WHERE $where ORDER BY id"""
      .map(rs => RoundNow(rs.long(1), rs.long(2), rs.longOpt(3).getOrElse(0L), Round.parseIds(rs.stringOpt(4)),
        Round.parseIds(rs.stringOpt(5)), rs.stringOpt(6), rs.booleanOpt(7).getOrElse(false), rs.booleanOpt(8).getOrElse(false),
        rs.booleanOpt(9).getOrElse(false)))
      .list.apply()

  /** Why the move can't run now, if so (§4.4's preconditions, read under the caller's locks in the
    * move's transaction): its rounds all still in the source, still whole clusters there (no round
    * joined them under `previous`, which a move would leave behind), none distributing or frozen, none
    * with an active job, none active unless root ticked it for pausing (`allowActive`: before step 1's
    * pause), and no round of the target referring to the source's.
    */
  private[series] def preconditionFailure(source: Long, m: MovePlan, target: Option[Long], allowActive: Boolean)(implicit
      session: DBSession): Option[services.jobs.JobError] = {
    import services.jobs.JobError
    val inSource = roundsNow(sqls"contest_id = $source AND (status IS NULL OR BINARY status <> 'cancelled')")
    val moving = m.rounds.toSet
    val present = inSource.filter(r => moving(r.id))
    lazy val clusters = ReuseReport.clustersOf(inSource.map(r =>
      ReuseReport.RoundRow(r.id, r.number, None, r.previous, None, r.status, r.active, r.distributing, None)))
    lazy val activeJobs =
      sql"""SELECT subject_id FROM job WHERE subject_type = 'round' AND subject_id IN (${m.rounds})
              AND status IN ('queued', 'running')""".map(_.long(1)).list.apply()
    lazy val referring = target.toSeq.flatMap { t =>
      val sourceIds = inSource.map(_.id).toSet
      roundsNow(sqls"contest_id = $t").filter(r => (r.previous ++ r.exclude).exists(sourceIds))
    }
    if (present.size != moving.size) Some(JobError("job.error.split.rounds.gone", Seq(m.index.toString)))
    else if (clusters.exists(c => c.exists(r => moving(r.id)) && c.exists(r => !moving(r.id))))
      Some(JobError("job.error.split.rounds.joined", Seq(m.index.toString)))
    else present.find(r => r.distributing || r.frozen) match {
      case Some(r) => Some(JobError("job.error.split.round.busy", Seq(r.id.toString)))
      case None =>
        present.find(r => r.active && !(allowActive && m.pause.contains(r.id))) match {
          case Some(r) => Some(JobError("job.error.split.round.active", Seq(r.id.toString)))
          case None if activeJobs.nonEmpty => Some(JobError("job.error.split.round.busy", Seq(activeJobs.head.toString)))
          case None => referring.headOption.map(r => JobError("job.error.split.target.refers", Seq(r.id.toString)))
        }
    }
  }

  // ── the source's and the target's notes (§4.6) ────────────────────────────

  /** A split as the contest's rounds page notes it: rounds moved out to `other`, or in from it. */
  final case class Note(splitId: Long, out: Boolean, other: Long, otherName: String, rounds: Int, running: Boolean)

  /** The notes of the contest's rounds page: the splits (not undone) that moved rounds out of it or
    * into it, the newest first, at most `limit`; a running one says its rounds may be paused.
    */
  def notes(contestId: Long, limit: Int = 3)(implicit session: DBSession = AutoSession): Seq[Note] = {
    val out = ContestSplitJdbc.findByContest(contestId, limit).filterNot(_.undone)
    val in = ContestSplitJdbc.findIntoContest(contestId, limit)
    def running(s: ContestSplitRow) = s.jobId.flatMap(services.jobs.JobJdbc.findById(_)).exists(_.isActive)
    def name(id: Long) = ContestJuryJdbc.findById(id).map(c => s"${c.name} ${c.year} ${c.country}".trim).getOrElse(s"#$id")
    val outNotes = out.flatMap { s =>
      val items = ContestSplitJdbc.items(s.id)
      val targets = items.filter(i => i.kind == ContestSplitJdbc.Kind.Contest || i.kind == ContestSplitJdbc.Kind.ContestExisting)
        .flatMap(i => i.subjectId.map(i.moveIndex -> _)).toMap
      val moved = items.filter(_.kind == ContestSplitJdbc.Kind.Round).groupBy(_.moveIndex)
      if (targets.isEmpty) planOf(s).toSeq.flatMap(_.moves.headOption).map(m => Note(s.id, out = true, 0L, m.identity, m.rounds.size, running(s)))
      else targets.toSeq.sortBy(_._1).map { case (move, t) =>
        Note(s.id, out = true, t, name(t), moved.get(move).fold(0)(_.size), running(s))
      }
    }
    val inNotes = in.map { s =>
      val items = ContestSplitJdbc.items(s.id)
      val moves = items.filter(i => (i.kind == ContestSplitJdbc.Kind.Contest || i.kind == ContestSplitJdbc.Kind.ContestExisting) &&
        i.subjectId.contains(contestId)).map(_.moveIndex).toSet
      Note(s.id, out = false, s.contestId, name(s.contestId),
        items.count(i => i.kind == ContestSplitJdbc.Kind.Round && moves(i.moveIndex)), running(s))
    }
    (outNotes ++ inNotes).sortBy(n => -n.splitId).take(limit * 2)
  }

  // ── Undo ──────────────────────────────────────────────────────────────────

  /** What an Undo did: rounds moved back, memberships removed, those kept (account, contest: used
    * since), contests retired, and those kept (they have rounds of their own now).
    */
  final case class Undone(rounds: Int, removed: Int, keptMembers: Seq[(Long, Long)], retired: Seq[Long], keptContests: Seq[Long])

  private def ids(v: JsObject, field: String): Seq[Long] = (v \ field).asOpt[Seq[Long]].getOrElse(Nil)

  /** Why the split can't be undone now, if so: undone already; its job queued or running (or another
    * split of the contest), or failed (Cancel it first: that resumes what it paused); a moved round
    * moved on since; or a round of a target that refers to a moved one (§4.5: Undo is refused once
    * the target has rounds referring to moved ones). Read as the caller's session sees the rows: under
    * Undo's locks, or without them for the page's preview.
    */
  def undoRefusal(split: ContestSplitRow)(implicit session: DBSession): Option[Refusal] = {
    val job = split.jobId.flatMap(services.jobs.JobJdbc.findById(_))
    val other = services.jobs.JobJdbc.activeByDedupeKey(dedupeKey(split.contestId))
    val items = ContestSplitJdbc.items(split.id)
    val moved = items.filter(_.kind == ContestSplitJdbc.Kind.Round)
    val movedIds = moved.flatMap(_.subjectId).toSet
    lazy val now = if (movedIds.isEmpty) Nil else roundsNow(sqls"id IN (${movedIds.toSeq})")
    lazy val targets = items.filter(i => i.kind == ContestSplitJdbc.Kind.Contest || i.kind == ContestSplitJdbc.Kind.ContestExisting)
      .flatMap(_.subjectId).distinct
    lazy val referring = if (targets.isEmpty) Nil
      else roundsNow(sqls"contest_id IN ($targets)").filter(r => !movedIds(r.id) && (r.previous ++ r.exclude).exists(movedIds))
    // the move's own refusals: a round being created or distributed, frozen, or with a job queued or running
    lazy val busy = now.find(r => r.distributing || r.frozen).map(_.id).orElse(
      if (movedIds.isEmpty) None
      else sql"""SELECT MIN(subject_id) FROM job WHERE subject_type = 'round' AND subject_id IN (${movedIds.toSeq})
                 AND status IN ('queued', 'running')""".map(_.longOpt(1)).single.apply().flatten)
    // jurors of the moved rounds who aren't members of the source (added in the target): they would lose them
    lazy val strangers = if (movedIds.isEmpty) Nil
      else sql"""SELECT DISTINCT u.fullname, u.id FROM round_user ru JOIN users u ON u.id = ru.user_id
                 WHERE ru.round_id IN (${movedIds.toSeq}) AND u.deleted_at IS NULL
                   AND NOT EXISTS (SELECT 1 FROM user_contest uc WHERE uc.user_id = ru.user_id AND uc.contest_id = ${split.contestId})
                 ORDER BY u.fullname, u.id""".map(rs => rs.stringOpt(1).getOrElse("#" + rs.long(2))).list.apply()
    if (split.undone) Some(refusal("split.undo.already"))
    else if (job.exists(_.isActive) || other.isDefined) Some(refusal("split.undo.running"))
    else if (job.exists(_.isFailed)) Some(refusal("split.undo.failed"))
    else moved.find(i => !now.exists(r => i.subjectId.contains(r.id) && i.contestId.contains(r.contestId))) match {
      case Some(i) => Some(refusal("split.undo.changed", i.subjectId.getOrElse(0L)))
      case None =>
        referring.headOption.map(r => refusal("split.undo.referred", r.id, r.contestId))
          .orElse(busy.map(r => refusal("split.undo.busy", r)))
          .orElse(Option.when(strangers.nonEmpty)(refusal("split.undo.jurors",
            (strangers.take(5) ++ Option.when(strangers.size > 5)("…")).mkString(", "))))
    }
  }

  /** Undo (CA12): reverses the split's `contest_split_item` rows in one transaction. The rounds go
    * back with their number (the next free one if taken since), contest and `exclude_rounds` (the cut
    * edges restored, the staying rounds' too); their comments and jobs by round; the source's current
    * round if it has none; the memberships the split gave, if unused (no active round of the target
    * holds the account as a juror, no comment of theirs there, the role unchanged), else kept and
    * listed; a target it created is retired, unless rounds of its own are there now (kept, listed).
    * The file links stay (CA10). Each contest gets a `split_undone` row (one batch). Lock order as
    * the split's: the contests in id order, then their rounds in id order.
    */
  def undo(splitId: Long, actor: User): Either[Refusal, Undone] =
    try Right(WriteTx.write { implicit session =>
        val split = ContestSplitJdbc.lock(splitId).getOrElse(throw SplitRefused(refusal("split.not.found")))
        val items = ContestSplitJdbc.items(splitId)
        val source = split.contestId
        val targets = items.filter(i => i.kind == ContestSplitJdbc.Kind.Contest || i.kind == ContestSplitJdbc.Kind.ContestExisting)
          .flatMap(_.subjectId).distinct
        val contests = (source +: targets).distinct.sorted
        // the lock order of the split: the series (of the contests that may be retired or leave one), the contests, the rounds
        sql"SELECT DISTINCT series_id FROM contest_jury WHERE id IN ($contests) AND series_id IS NOT NULL ORDER BY series_id"
          .map(_.long(1)).list.apply().foreach(SeriesJdbc.lock)
        sql"SELECT id FROM contest_jury WHERE id IN ($contests) ORDER BY id FOR UPDATE".map(_.long(1)).list.apply()
        Round.lock(sql"SELECT id FROM round WHERE contest_id IN ($contests)".map(_.long(1)).list.apply())
        undoRefusal(split).foreach(r => throw SplitRefused(r))

        val moved = items.filter(_.kind == ContestSplitJdbc.Kind.Round)
        val movedIds = moved.flatMap(_.subjectId)
        val sourceNumbers = scala.collection.mutable.Set(roundsNow(sqls"contest_id = $source").map(_.number): _*)
        var nextNumber = (sourceNumbers.toSeq :+ 0L).max
        val nowById = if (movedIds.isEmpty) Map.empty[Long, RoundNow] else roundsNow(sqls"id IN ($movedIds)").map(r => r.id -> r).toMap
        moved.sortBy(_.subjectId).foreach { i =>
          val id = i.subjectId.get
          val old = (i.oldValue \ "number").asOpt[Long].getOrElse(0L)
          val number = if (old > 0 && !sourceNumbers(old)) old else { nextNumber += 1; nextNumber }
          sourceNumbers += number
          val cut = ids(i.oldValue, "exclude").filterNot(ids(i.newValue, "exclude").toSet)
          val exclude = (nowById.get(id).toSeq.flatMap(_.exclude) ++ cut).distinct
          sql"""UPDATE round SET contest_id = $source, number = $number,
                  exclude_rounds = ${Option.when(exclude.nonEmpty)(exclude.mkString(","))}, created_at = created_at
                WHERE id = $id""".update.apply()
        }
        items.filter(_.kind == ContestSplitJdbc.Kind.ExcludeCut).foreach { i =>
          val id = i.subjectId.get
          val cut = ids(i.oldValue, "exclude").filterNot(ids(i.newValue, "exclude").toSet)
          roundsNow(sqls"id = $id").headOption.foreach { r =>
            val exclude = (r.exclude ++ cut).distinct
            sql"""UPDATE round SET exclude_rounds = ${Option.when(exclude.nonEmpty)(exclude.mkString(","))}, created_at = created_at
                  WHERE id = $id""".update.apply()
          }
        }
        if (movedIds.nonEmpty && targets.nonEmpty) {
          sql"UPDATE comment SET contest_id = $source WHERE round_id IN ($movedIds) AND contest_id IN ($targets)".update.apply()
          sql"""UPDATE job SET contest_id = $source WHERE subject_type = 'round' AND subject_id IN ($movedIds)
                  AND contest_id IN ($targets)""".update.apply()
          sql"UPDATE contest_jury SET current_round = NULL WHERE id IN ($targets) AND current_round IN ($movedIds)".update.apply()
        }
        items.filter(_.kind == ContestSplitJdbc.Kind.CurrentRound).foreach { i =>
          (i.oldValue \ "currentRound").asOpt[Long].foreach { r =>
            sql"UPDATE contest_jury SET current_round = $r WHERE id = $source AND current_round IS NULL".update.apply()
          }
        }

        // the contests it created: retired, unless they are in use now: rounds, comments, members or jobs
        // of their own, or changes of their own (not this split's)
        val given = items.filter(_.kind == ContestSplitJdbc.Kind.Membership).flatMap(i => i.subjectId.zip(i.contestId)).toSet
        val created = items.filter(_.kind == ContestSplitJdbc.Kind.Contest).flatMap(_.subjectId).distinct
        def inUse(c: Long): Boolean = {
          def any(q: SQL[Nothing, NoExtractor]) = q.map(_ => ()).single.apply().isDefined
          any(sql"SELECT 1 FROM round WHERE contest_id = $c LIMIT 1") ||
          any(sql"SELECT 1 FROM comment WHERE contest_id = $c LIMIT 1") ||
          any(sql"SELECT 1 FROM job WHERE contest_id = $c LIMIT 1") ||
          sql"SELECT user_id FROM user_contest WHERE contest_id = $c".map(_.long(1)).list.apply().exists(u => !given((u, c))) ||
          any(sql"SELECT 1 FROM contest_change WHERE contest_id = $c AND NOT (batch_id <=> ${split.batchId}) LIMIT 1")
        }
        val (keep, retire) = created.partition(inUse)

        // the roles it gave: removed if unused; an Admin role only with its retired contest
        val (removed, kept) = items.filter(_.kind == ContestSplitJdbc.Kind.Membership).partition { i =>
          val (user, contest, role) = (i.subjectId.get, i.contestId.get, (i.newValue \ "role").as[String])
          val same = sql"SELECT role FROM user_contest WHERE user_id = $user AND contest_id = $contest"
            .map(_.string(1)).single.apply().contains(role)
          val juror = sql"""SELECT 1 FROM round_user ru JOIN round r ON r.id = ru.round_id
                            WHERE r.contest_id = $contest AND ru.user_id = $user LIMIT 1""".map(_ => ()).single.apply().isDefined
          val commented = sql"SELECT 1 FROM comment WHERE contest_id = $contest AND user_id = $user LIMIT 1"
            .map(_ => ()).single.apply().isDefined
          same && !juror && !commented && (role != User.ADMIN_ROLE || retire.contains(contest))
        }
        removed.foreach { i =>
          val (user, contest, role) = (i.subjectId.get, i.contestId.get, (i.newValue \ "role").as[String])
          sql"DELETE FROM user_contest WHERE user_id = $user AND contest_id = $contest AND role = $role".update.apply()
          AccountChangeLog.record(actor, user, Some(contest), AccountChangeJdbc.Role, oldValue = Some(role),
            comment = Some(undoComment(splitId)))
        }

        val seriesOf = ContestJuryJdbc.findAllBy(sqls.in(ContestJuryJdbc.defaultAlias.id, contests)).flatMap(c => c.id.map(_ -> c.seriesId)).toMap
        var batch: Option[Long] = None
        def change(contest: Long, kind: String, detail: JsObject): Unit = {
          val id = ContestChangeJdbc.create(Some(contest), seriesOf.getOrElse(contest, None), actor.getId, kind, batch, detail)
          if (batch.isEmpty) { ContestChangeJdbc.startBatch(id); batch = Some(id) }
        }
        retire.foreach { c =>
          sql"UPDATE contest_jury SET retired_at = NOW(3), retired_by = ${actor.getId} WHERE id = $c AND retired_at IS NULL".update.apply()
        }
        // an existing target the split put into a series leaves it again, if still there
        val left = items.filter(i => i.kind == ContestSplitJdbc.Kind.ContestExisting && (i.newValue \ "joinedSeries").asOpt[Boolean].contains(true))
          .flatMap(i => i.subjectId.zip((i.newValue \ "seriesId").asOpt[Long])).filter { case (c, sid) =>
            sql"UPDATE contest_jury SET series_id = NULL WHERE id = $c AND series_id = $sid".update.apply() > 0
          }
        contests.foreach { c =>
          change(c, ContestChangeJdbc.SplitUndone, Json.obj("splitId" -> splitId, "rounds" -> movedIds.size) ++
            (if (retire.contains(c)) Json.obj("retired" -> true) else Json.obj()))
        }
        retire.foreach(c => change(c, ContestChangeJdbc.Retired, Json.obj("splitId" -> splitId)))
        left.foreach { case (c, sid) =>
          val id = ContestChangeJdbc.create(Some(c), Some(sid), actor.getId, ContestChangeJdbc.SeriesUnassigned, batch,
            Json.obj("splitId" -> splitId, "from" -> sid))
          if (batch.isEmpty) { ContestChangeJdbc.startBatch(id); batch = Some(id) }
        }
        ContestSplitJdbc.setUndone(splitId, actor.getId)
        log.info(s"contest_split_undone splitId=$splitId contestId=$source rounds=${movedIds.size} membershipsRemoved=${removed.size} " +
          s"membershipsKept=${kept.size} retired=${retire.mkString(",")} kept=${keep.mkString(",")} by=${actor.getId}")
        Undone(movedIds.size, removed.size, kept.flatMap(i => i.subjectId.zip(i.contestId)), retire, keep)
      })
    catch { case SplitRefused(r) => Left(r) }

  /** The account change log's comment on a membership an Undo removed. */
  def undoComment(splitId: Long): String = s"split #$splitId undone"
}
