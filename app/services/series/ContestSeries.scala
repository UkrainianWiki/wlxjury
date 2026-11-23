package services.series

import controllers.Secured
import db.scalikejdbc.{ContestChangeJdbc, ContestJuryJdbc, DbErrors, Series, SeriesJdbc}
import org.intracer.wmua.ContestJury
import play.api.Logger
import play.api.libs.json.{JsObject, Json}
import scalikejdbc._

import java.time.Instant

/** Root's work on contest series (contest series plan §3.1, §3.5; PR CY-1): creating, renaming,
  * retiring and un-retiring series; assigning, moving and unassigning contests; retiring and
  * un-retiring contests; the bulk assignment from proposals and its undo. Each is one transaction
  * that locks the series row(s) `FOR UPDATE` first, then the contest's, re-checks, writes and logs
  * a `contest_change` row (CA8) and an INFO line on `round.admin`. A refusal is a message key
  * (`Left`), nothing written. The caller checks that the actor is root.
  *
  * The previous year's contest is derived, never stored ([[previous]]): the live contest of the
  * same series with the next lower year, so nothing here recomputes anything.
  */
object ContestSeries {

  private val log = Logger("round.admin")

  type Refusal = String

  // ── reading ───────────────────────────────────────────────────────────────

  /** The previous year's contest: the live contest of the contest's series with the next lower
    * year (the key's index, `live_series_id, year`); none outside a series.
    */
  def previous(contest: ContestJury)(implicit session: DBSession = AutoSession): Option[ContestJury] =
    contest.seriesId.flatMap { seriesId =>
      sql"""SELECT id FROM contest_jury WHERE live_series_id = $seriesId AND year < ${contest.year}
            ORDER BY year DESC LIMIT 1""".map(_.long(1)).single.apply().flatMap(ContestJuryJdbc.findById(_))
    }

  /** [[previous]] of each of `contests` among `all` (root's list: every contest already read). */
  def previousIn(contests: Seq[ContestJury], all: Seq[ContestJury]): Map[Long, ContestJury] = {
    val live = all.filter(c => !c.retired && c.seriesId.isDefined).groupBy(_.seriesId.get)
    contests.flatMap { c =>
      for {
        seriesId <- c.seriesId
        prev <- live.getOrElse(seriesId, Nil).filter(_.year < c.year).maxByOption(_.year)
      } yield c.getId -> prev
    }.toMap
  }

  /** When and by whom each contest was created (V109; None before it): one query. */
  def createdInfo(contestIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, (Option[Long], Option[Instant])] =
    if (contestIds.isEmpty) Map.empty
    else
      sql"SELECT id, created_by, UNIX_TIMESTAMP(created_at) FROM contest_jury WHERE id IN (${contestIds.distinct})"
        .map(rs => rs.long(1) -> (rs.longOpt(2), db.scalikejdbc.DbTime.instantOpt(rs, 3))).list.apply().toMap

  /** The contests of the series, the newest year first. */
  def contestsOf(seriesId: Long)(implicit session: DBSession = AutoSession): Seq[ContestJury] =
    ContestJuryJdbc.findAllBy(sqls.eq(ContestJuryJdbc.defaultAlias.seriesId, seriesId))
      .sortBy(c => (-c.year, c.getId))

  /** Why the contest can't be retired now, if so: a round open to jurors (active, or frozen while
    * active: "paused" in the code's sense; `contest.retire.live.round`), or one being created or
    * reviewed, or whose creation failed (`distributing`: `contest.retire.draft.round`, which says
    * to finish or cancel it on the Rounds page); a cancelled creation doesn't count. `lock`: the
    * contest's rounds are read `FOR UPDATE` (in the retiring transaction, after the contest's row),
    * so a round started meanwhile waits for the retirement or is seen by it.
    */
  def liveRoundRefusal(contestId: Long, lock: Boolean = false)(implicit session: DBSession = AutoSession): Option[Refusal] = {
    val rows =
      (if (lock) sql"""SELECT active, freeze_count > 0 AND active_before_freeze = 1, distributing FROM round
                       WHERE contest_id = $contestId AND (status IS NULL OR BINARY status <> 'cancelled') FOR UPDATE"""
       else sql"""SELECT active, freeze_count > 0 AND active_before_freeze = 1, distributing FROM round
                  WHERE contest_id = $contestId AND (status IS NULL OR BINARY status <> 'cancelled')""")
        .map(rs => (rs.boolean(1), rs.boolean(2), rs.boolean(3))).list.apply()
    if (rows.exists { case (active, frozenOpen, distributing) => !distributing && (active || frozenOpen) })
      Some("contest.retire.live.round")
    else if (rows.exists(_._3)) Some("contest.retire.draft.round")
    else None
  }

  def hasLiveRound(contestId: Long)(implicit session: DBSession = AutoSession): Boolean =
    liveRoundRefusal(contestId).isDefined

  // ── series ────────────────────────────────────────────────────────────────

  /** Runs of white space (a no-break space too) as one space, none at the ends. */
  def normalizeSpaces(s: String): String = s.replaceAll("[\\s\\p{Z}]+", " ").trim

  private def cleanName(name: String): Option[String] =
    Option(normalizeSpaces(name)).filter(_.nonEmpty).filter(_.length <= SeriesJdbc.NameMaxLength)

  private def blankToNone(s: Option[String]): Option[String] = s.map(normalizeSpaces).filter(_.nonEmpty)

  /** A new series; its id. Refused for a blank or too long name, or one a live series has. */
  def createSeries(name: String, contestType: Option[String], country: Option[String], campaign: Option[String],
      actorId: Long, batchId: Option[Long] = None)(implicit session: DBSession): Either[Refusal, Long] =
    cleanName(name) match {
      case None => Left("series.name.invalid")
      case Some(n) if SeriesJdbc.liveByName(n).isDefined => Left("series.name.taken")
      case Some(_) if blankToNone(contestType).exists(t => SeriesIdentity.typedCode(t).isEmpty) => Left("series.type.invalid")
      case Some(n) =>
        val kind = blankToNone(contestType).flatMap(SeriesIdentity.typedCode)
        val (place, camp) = (blankToNone(country), blankToNone(campaign))
        nameTaken {
          val id = SeriesJdbc.create(n, kind, place, camp, actorId)
          ContestChangeJdbc.create(None, Some(id), actorId, ContestChangeJdbc.Created, batchId,
            Json.obj("name" -> n) ++ opt("contestType", kind) ++ opt("country", place) ++ opt("campaign", camp))
          log.info(s"series_created seriesId=$id name=${Secured.logValue(n)} by=$actorId")
          Right(id)
        }
    }

  /** `write`, or `series.name.taken` if the key of one live series per name (V109's `live_name`)
    * refuses it: a race the check before it didn't see.
    */
  private def nameTaken[T](write: => Either[Refusal, T]): Either[Refusal, T] =
    try write
    catch { case e: Exception if DbErrors.isDuplicateKey(e) => Left("series.name.taken") }

  def createSeriesTx(name: String, contestType: Option[String], country: Option[String], campaign: Option[String],
      actorId: Long): Either[Refusal, Long] =
    DB.localTx(implicit s => createSeries(name, contestType, country, campaign, actorId))

  def rename(seriesId: Long, name: String, actorId: Long): Either[Refusal, Unit] =
    DB.localTx { implicit s =>
      SeriesJdbc.lock(seriesId) match {
        case None => Left("series.not.found")
        case Some(series) =>
          cleanName(name) match {
            case None                          => Left("series.name.invalid")
            case Some(n) if n == series.name   => Right(())
            case Some(n) if SeriesJdbc.liveByName(n).exists(_.id != seriesId) => Left("series.name.taken")
            case Some(n) => nameTaken {
              SeriesJdbc.rename(seriesId, n)
              ContestChangeJdbc.create(None, Some(seriesId), actorId, ContestChangeJdbc.Identity,
                detail = Json.obj("old" -> series.name, "new" -> n))
              log.info(s"series_renamed seriesId=$seriesId name=${Secured.logValue(n)} by=$actorId")
              Right(())
            }
          }
      }
    }

  /** Retires the series: its contests stay live (they show under "Other contests"), nothing can be
    * created in it; CY-2's grants on it give no access. Logged.
    */
  def retireSeries(seriesId: Long, actorId: Long, comment: Option[String]): Either[Refusal, Unit] =
    DB.localTx { implicit s =>
      SeriesJdbc.lock(seriesId) match {
        case None                    => Left("series.not.found")
        case Some(se) if se.retired  => Left("series.already.retired")
        case Some(_) =>
          SeriesJdbc.setRetired(seriesId, Some(actorId))
          ContestChangeJdbc.create(None, Some(seriesId), actorId, ContestChangeJdbc.Retired, comment = comment)
          log.info(s"series_retired seriesId=$seriesId by=$actorId")
          Right(())
      }
    }

  /** Un-retires the series. CY-2 adds root's re-confirmation of each grant (plan §3.1); there are
    * no grants before it.
    */
  def unretireSeries(seriesId: Long, actorId: Long, comment: Option[String]): Either[Refusal, Unit] =
    DB.localTx { implicit s =>
      SeriesJdbc.lock(seriesId) match {
        case None                   => Left("series.not.found")
        case Some(se) if se.live    => Left("series.not.retired")
        case Some(se) if SeriesJdbc.liveByName(se.name).isDefined => Left("series.name.taken")
        case Some(_) => nameTaken {
          SeriesJdbc.setRetired(seriesId, None)
          ContestChangeJdbc.create(None, Some(seriesId), actorId, ContestChangeJdbc.Unretired, comment = comment)
          log.info(s"series_unretired seriesId=$seriesId by=$actorId")
          Right(())
        }
      }
    }

  // ── contests ──────────────────────────────────────────────────────────────

  private def opt(key: String, value: Option[Any]): JsObject =
    value.fold(Json.obj()) {
      case v: Long   => Json.obj(key -> v)
      case v: Int    => Json.obj(key -> v)
      case v: String => Json.obj(key -> v)
      case v         => Json.obj(key -> v.toString)
    }

  /** The contest row's series, retirement and year, locked `FOR UPDATE` (after its series'). */
  private def lockContest(contestId: Long)(implicit session: DBSession): Option[ContestJury] =
    sql"SELECT id FROM contest_jury WHERE id = $contestId FOR UPDATE".map(_.long(1)).single.apply()
      .flatMap(ContestJuryJdbc.findById(_))

  /** The live contest that holds the series and year, other than `except`. */
  private def holder(seriesId: Long, year: Int, except: Long)(implicit session: DBSession): Option[Long] =
    sql"SELECT id FROM contest_jury WHERE live_series_id = $seriesId AND year = $year AND id <> $except"
      .map(_.long(1)).single.apply()

  /** Locks the series in id order (two for a move), then the contest; the contest as locked, if
    * its series is still `expected` (else another change came first: refused).
    */
  private def lockFor(contestId: Long, expected: Option[Long], other: Option[Long])(implicit
      session: DBSession): Either[Refusal, (ContestJury, Map[Long, Series])] = {
    val ids = (expected.toSeq ++ other.toSeq).distinct.sorted
    val locked = ids.flatMap(id => SeriesJdbc.lock(id).map(id -> _)).toMap
    if (locked.size != ids.size) Left("series.not.found")
    else
      lockContest(contestId) match {
        case None                                   => Left("contest.not.found")
        case Some(c) if c.seriesId != expected      => Left("series.changed.meanwhile")
        case Some(c)                                => Right((c, locked))
      }
  }

  /** Assigns the contest to `target`, moves it there from its series, or (`target` None) takes it
    * out of its series; `batchId` for a bulk assignment's row. Refused if the target is retired,
    * the contest clearly belongs to another series ([[SeriesIdentity.mismatch]]: its recognised
    * type or country differs; root moves it to a fitting series instead), or a live contest of the
    * series already has its year (the key, CA15). A contest whose type or country isn't recognised
    * may be assigned, and its row says so (`identity`: "not recognised; assigned by root"). Logged
    * as `series_assigned`, `series_changed` or `series_unassigned`.
    */
  def setSeriesIn(contestId: Long, target: Option[Long], actorId: Long, comment: Option[String] = None,
      batchId: Option[Long] = None, detail: JsObject = Json.obj())(implicit session: DBSession): Either[Refusal, Option[Long]] = {
    val current = ContestJuryJdbc.findById(contestId).map(_.seriesId)
    current match {
      case None => Left("contest.not.found")
      case Some(from) if from == target => Left("series.unchanged")
      case Some(from) =>
        lockFor(contestId, from, target).flatMap { case (contest, locked) =>
          val to = target.flatMap(locked.get)
          (target, to) match {
            case (Some(_), Some(se)) if se.retired => Left("series.retired.closed")
            case (Some(_), Some(se)) if SeriesIdentity.mismatch(se, contest) => Left("series.identity.mismatch")
            case (Some(t), _) if !contest.retired && holder(t, contest.year, contestId).isDefined => Left("series.year.taken")
            case _ =>
              try {
                sql"UPDATE contest_jury SET series_id = $target WHERE id = $contestId".update.apply()
                val kind = (from, target) match {
                  case (None, Some(_)) => ContestChangeJdbc.SeriesAssigned
                  case (Some(_), None) => ContestChangeJdbc.SeriesUnassigned
                  case _               => ContestChangeJdbc.SeriesChanged
                }
                val unrecognised = to.exists(se => !SeriesIdentity.recognised(se, contest))
                val id = ContestChangeJdbc.create(Some(contestId), target.orElse(from), actorId, kind, batchId,
                  detail ++ opt("from", from) ++ opt("to", target) ++
                    opt("identity", Option.when(unrecognised)(UnrecognisedIdentity)), comment)
                log.info(s"$kind contestId=$contestId seriesId=${target.orElse(from).getOrElse("")} " +
                  s"from=${from.getOrElse("")} by=$actorId${batchId.fold("")(b => s" batch=$b")}")
                Right(Some(id))
              } catch {
                case e: Exception if DbErrors.isDuplicateKey(e) => Left("series.year.taken")
              }
          }
        }
    }
  }

  /** The log's note on a contest assigned though its type or country wasn't recognised. */
  val UnrecognisedIdentity = "not recognised; assigned by root"

  def setSeries(contestId: Long, target: Option[Long], actorId: Long, comment: Option[String]): Either[Refusal, Unit] =
    inTx(implicit s => setSeriesIn(contestId, target, actorId, comment).map(_ => ()))

  /** Runs `f` in a transaction, rolled back on a refusal. */
  private def inTx[T](f: DBSession => Either[Refusal, T]): Either[Refusal, T] = {
    final case class Refused(key: Refusal) extends RuntimeException(key, null, false, false)
    try Right(DB.localTx(s => f(s).fold(k => throw Refused(k), identity)))
    catch { case Refused(k) => Left(k) }
  }

  /** Retires the contest: its memberships give no access, menus and lists leave it out, the key
    * and "previous" ignore it; the account rules still count it on the target's side. Refused
    * while a round is open, frozen while open, or being created ([[liveRoundRefusal]], the rounds
    * locked). Logged.
    */
  def retireContest(contestId: Long, actorId: Long, comment: Option[String]): Either[Refusal, Unit] =
    inTx { implicit s =>
      val seriesId = ContestJuryJdbc.findById(contestId).flatMap(_.seriesId)
      lockFor(contestId, seriesId, None).flatMap { case (contest, _) =>
        if (contest.retired) Left("contest.already.retired")
        else liveRoundRefusal(contestId, lock = true).map(Left(_)).getOrElse {
          sql"UPDATE contest_jury SET retired_at = NOW(3), retired_by = $actorId WHERE id = $contestId".update.apply()
          ContestChangeJdbc.create(Some(contestId), seriesId, actorId, ContestChangeJdbc.Retired, comment = comment)
          log.info(s"contest_retired contestId=$contestId seriesId=${seriesId.getOrElse("")} by=$actorId")
          Right(())
        }
      }
    }

  /** Un-retires the contest; refused if a live contest of its series holds its year. Logged. */
  def unretireContest(contestId: Long, actorId: Long, comment: Option[String]): Either[Refusal, Unit] =
    inTx { implicit s =>
      val seriesId = ContestJuryJdbc.findById(contestId).flatMap(_.seriesId)
      lockFor(contestId, seriesId, None).flatMap { case (contest, _) =>
        if (!contest.retired) Left("contest.not.retired")
        else if (seriesId.exists(holder(_, contest.year, contestId).isDefined)) Left("series.year.taken")
        else
          try {
            sql"UPDATE contest_jury SET retired_at = NULL, retired_by = NULL WHERE id = $contestId".update.apply()
            ContestChangeJdbc.create(Some(contestId), seriesId, actorId, ContestChangeJdbc.Unretired, comment = comment)
            log.info(s"contest_unretired contestId=$contestId seriesId=${seriesId.getOrElse("")} by=$actorId")
            Right(())
          } catch {
            case e: Exception if DbErrors.isDuplicateKey(e) => Left("series.year.taken")
          }
      }
    }

  /** Records a contest's creation (root's add form or import): its creator and time on the row
    * (V109) and a `created` entry. In the creating transaction.
    */
  def recordCreated(contest: ContestJury, actorId: Long, how: String)(implicit session: DBSession): Unit = {
    sql"UPDATE contest_jury SET created_at = NOW(3), created_by = $actorId WHERE id = ${contest.getId}".update.apply()
    ContestChangeJdbc.create(Some(contest.getId), contest.seriesId, actorId, ContestChangeJdbc.Created,
      detail = Json.obj("name" -> contest.name, "year" -> contest.year, "country" -> contest.country, "how" -> how))
    log.info(s"contest_created contestId=${contest.getId} how=$how by=$actorId")
  }

  /** An identity edit (name, year, country) and settings of a contest, in one transaction: the
    * contest's series locked first, then its row. Refused if the edit makes it clearly belong to
    * another series ([[SeriesIdentity.mismatch]]; root moves it first, §3.1) or the new year is
    * taken in its series (checked, and the key's refusal read as the same). Logged (`identity`).
    */
  def editIdentity(contestId: Long, after: ContestJury, actorId: Option[Long])(write: DBSession => Unit): Either[Refusal, Unit] =
    inTx { implicit s =>
      val seriesId = ContestJuryJdbc.findById(contestId).flatMap(_.seriesId)
      lockFor(contestId, seriesId, None).flatMap { case (before, locked) =>
        val series = seriesId.flatMap(locked.get)
        if (series.exists(SeriesIdentity.mismatch(_, after))) Left("series.identity.mismatch")
        else if (!before.retired && after.year != before.year && seriesId.exists(holder(_, after.year, contestId).isDefined))
          Left("series.year.taken")
        else
          try {
            write(s)
            actorId.foreach(recordIdentity(before, after, _))
            Right(())
          } catch { case e: Exception if DbErrors.isDuplicateKey(e) => Left("series.year.taken") }
      }
    }

  /** Logs an identity edit's old and new values (`identity`). */
  def recordIdentity(before: ContestJury, after: ContestJury, actorId: Long)(implicit session: DBSession): Unit = {
    def fields(c: ContestJury) = Json.obj("name" -> c.name, "year" -> c.year, "country" -> c.country) ++ opt("campaign", c.campaign)
    if (fields(before) != fields(after)) {
      ContestChangeJdbc.create(Some(before.getId), before.seriesId, actorId, ContestChangeJdbc.Identity,
        detail = Json.obj("old" -> fields(before), "new" -> fields(after)))
      log.info(s"contest_identity contestId=${before.getId} by=$actorId")
    }
  }

  // ── bulk assignment ───────────────────────────────────────────────────────

  /** One ticked proposal: into an existing series, or a new one of this name, type, country and
    * campaign; with the contests root kept ticked.
    */
  final case class Choice(existing: Option[Long], name: String, contestType: Option[String], country: Option[String],
      campaign: Option[String], contestIds: Seq[Long])

  /** Assigns the choices' contests in one transaction, one batch (`batch_id`, the id of its first
    * row): new series are created first. All or nothing: any refusal (a contest assigned, retired
    * or of a year taken meanwhile) refuses the batch, with the contest it concerns. The batch id.
    */
  def confirm(choices: Seq[Choice], actorId: Long): Either[(Refusal, Option[Long]), Long] = {
    final case class Refused(key: Refusal, contest: Option[Long]) extends RuntimeException(key, null, false, false)
    val nonEmpty = choices.filter(_.contestIds.nonEmpty)
    if (nonEmpty.isEmpty) Left(("series.assign.none", None))
    else
      try DB.localTx { implicit s =>
          var batch: Option[Long] = None
          def started(id: Long): Unit = if (batch.isEmpty) { ContestChangeJdbc.startBatch(id); batch = Some(id) }
          nonEmpty.foreach { choice =>
            val seriesId = choice.existing.getOrElse {
              val id = createSeries(choice.name, choice.contestType, choice.country, choice.campaign, actorId, batch)
                .fold(k => throw Refused(k, None), identity)
              // the series' `created` row: the batch's first if it opens it
              if (batch.isEmpty) ContestChangeJdbc.findBySeries(id, 1).headOption.foreach(c => started(c.id))
              id
            }
            choice.contestIds.distinct.foreach { contestId =>
              ContestJuryJdbc.findById(contestId) match {
                case None                         => throw Refused("contest.not.found", Some(contestId))
                case Some(c) if c.seriesId.isDefined => throw Refused("series.assign.already", Some(contestId))
                case Some(c) if c.retired          => throw Refused("contest.already.retired", Some(contestId))
                case Some(_) =>
                  setSeriesIn(contestId, Some(seriesId), actorId, batchId = batch)
                    .fold(k => throw Refused(k, Some(contestId)), _.foreach(started))
              }
            }
          }
          batch.get
        } match {
        case id => Right(id)
      } catch { case Refused(k, c) => Left((k, c)) }
  }

  /** "Undo batch": takes each contest of the batch out of the series it assigned it to, as one new
    * batch of `series_unassigned` rows (`undoOf` the batch). A contest changed since (moved,
    * unassigned, retired, renamed) is left as it is and listed. Refused for a batch already undone.
    * The undone contests and those left.
    */
  def undoBatch(batchId: Long, actorId: Long): Either[Refusal, (Seq[Long], Seq[Long])] =
    inTx { implicit s =>
      val rows = ContestChangeJdbc.findByBatch(batchId).filter(_.kind == ContestChangeJdbc.SeriesAssigned)
      if (rows.isEmpty) Left("series.batch.not.found")
      else if (ContestChangeJdbc.undoneBatches(Seq(batchId)).nonEmpty) Left("series.batch.undone.already")
      else {
        val latest = ContestChangeJdbc.latestOf(rows.flatMap(_.contestId))
        var batch: Option[Long] = None
        val (undone, left) = rows.partitionMap { row =>
          val contestId = row.contestId.get
          val unchanged = latest.get(contestId).exists(_.id == row.id)
          if (!unchanged) Right(contestId)
          else
            setSeriesIn(contestId, None, actorId, batchId = batch, detail = Json.obj("undoOf" -> batchId)) match {
              case Right(Some(id)) =>
                if (batch.isEmpty) { ContestChangeJdbc.startBatch(id); batch = Some(id) }
                Left(contestId)
              case _ => Right(contestId)
            }
        }
        log.info(s"series_batch_undone batch=$batchId undone=${undone.size} left=${left.size} by=$actorId")
        Right((undone, left))
      }
    }
}
