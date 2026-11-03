package db.scalikejdbc

import db.RoundRepo
import org.intracer.wmua.{BinaryOrder, CategoryRule, HasId}
import scalikejdbc._
import scalikejdbc.orm.{CRUDMapper, JoinTable}

import java.time.ZonedDateTime

case class RoundLimits(
    min: Option[Int] = None,
    max: Option[Int] = None,
    recommended: Option[Int] = None
)

object MediaType {
  val All: String = "all"
  val Video: String = "video"
  val Image: String = "image"
  val mediaTypes: Seq[String] = Seq(All, Video, Image)
}

case class Round(
    id: Option[Long],
    number: Long,
    name: Option[String] = None,
    contestId: Long,
    roles: Set[String] = Set("jury"),
    distribution: Int = 0,
    rates: Rates = Round.binaryRound,
    limits: RoundLimits = RoundLimits(),
    createdAt: ZonedDateTime = ZonedDateTime.now,
    deletedAt: Option[ZonedDateTime] = None,
    active: Boolean = false,
    optionalRate: Boolean = false,
    juryOrgView: Boolean = false,
    minMpx: Option[Int] = None,
    previous: Option[String] = None,
    prevSelectedBy: Option[Int] = None,
    // "exclude files rejected by at least N jurors" of a yes/no previous round (V74); None: no filter
    prevRejectedBy: Option[Int] = None,
    prevMinAvgRate: Option[BigDecimal] = None,
    category: Option[String] = None,
    excludeCategory: Option[String] = None,
    categoryClause: Option[String] = None,
    regions: Option[String] = None,
    minImageSize: Option[Int] = None,
    hasCriteria: Boolean = false,
    halfStar: Boolean = false,
    monuments: Option[String] = None,
    topImages: Option[Int] = None,
    specialNomination: Option[String] = None,
    users: Seq[User] = Nil,
    mediaType: Option[String] = None,
    submitToken: Option[String] = None,
    distributing: Boolean = false,
    freezeCount: Int = 0,
    excludeCoi: Boolean = false,
    // the order of a top-N from a yes/no previous round (V72); None (NULL, a round from before
    // V72): the legacy rule (BinaryOrder.LegacyParam). A new round from a yes/no round stores
    // one (withExplicitTopOrder); over rated previous rounds it plays no part, and is NULL.
    topBinaryOrder: Option[BinaryOrder] = None,
    // the number of a criteria round's criteria, read where its results are drawn
    // (perCriterion: the galleries, the large view); not loaded with the round
    criteriaCount: Int = 1,
    // the round form's criteria, by position: a new round's are written with it (create);
    // not loaded with the round (RoundCriterion.idsOf)
    criterionIds: Seq[Long] = Nil
) extends HasId {

  /** Jurors may see and vote in the round: it is active, and not still being created
    * (see V60: a round being distributed stays hidden until its count check passes).
    */
  def openToJurors: Boolean = active && !distributing

  def availableJurors(implicit session: DBSession = AutoSession): Seq[User] =
    User.findByContestAndRoles(contestId, roles.toSeq)

  lazy val numberOfActiveJurors = VoteJdbc.activeJurors(id.get)

  lazy val numberOfAssignedJurors = VoteJdbc.allJurors(id.get)

  def numberOfJurorsForAverageRate: Long =
    if (!optionalRate) numberOfAssignedJurors else numberOfActiveJurors

  def description: String =
    name
      .filter(_.trim.nonEmpty)
      .getOrElse(number.toString)

  def isBinary: Boolean = rates.id == Round.binaryRound.id

  /** Rated on stars (1-N): neither binary nor comments only. Only such a round has criteria. */
  def isRated: Boolean = !isBinary && rates.id != Round.commentsOnlyRound.id

  /** A rate's stored units per star: 2 in a half-star round (a half star is 1), else 1. */
  def starUnits: Int = if (rates.id > 5 && halfStar) 2 else 1

  /** The highest rate the stars give, in stored units: all of them (a half-star 1-7 round
    * draws 3 stars, so 6).
    */
  def maxRate: Int = rates.id / starUnits * starUnits

  /** A juror's vote the round takes, in its stored units: -1, 0 or 1 in a binary round; 0
    * (unrated) or from a half star or a star up to [[maxRate]] in a rated one, also for each
    * criterion; none in a comments-only round.
    */
  def acceptsRate(rate: Int): Boolean =
    if (isBinary) rate >= -1 && rate <= 1
    else if (rates.id == Round.commentsOnlyRound.id) false
    else rate == 0 || (rate >= rates.minRate && rate <= maxRate)

  /** The stars of a file's result: the round's, also in a criteria round, whose result is
    * drawn per criterion ([[perCriterion]]).
    */
  def numberOfStars: Int = rates.id / starUnits

  /** A rate (a vote's, or a file's average) on one criterion's stars: a criteria round's
    * rate is the sum of its [[criteriaCount]] criteria's (U2), drawn as their average, `sum / n`
    * (C3); other rounds' as it is. In stored units, as `rate`.
    */
  def perCriterion(rate: Double): Double =
    if (hasCriteria) rate / criteriaCount.max(1) else rate

  def regionIds: Seq[String] = regions.map(_.split(",").toSeq).getOrElse(Nil)

  /** "Selected by at least N jurors" as the distribution reads it: N >= 1 approvals (rated
    * votes) in the previous round(s), or no filter. A stored 0 is no filter, as NULL is: the
    * old round form's way of saying it (V73 clears them); every reader goes through this.
    */
  def selectedByFilter: Option[Int] = prevSelectedBy.filter(_ > 0)

  /** "Exclude files rejected by at least N jurors" as the distribution reads it: N >= 1, or no
    * filter. It drops the files with N or more rejections across the previous rounds' votes.
    */
  def rejectedByFilter: Option[Int] = prevRejectedBy.filter(_ > 0)

  /** [[topBinaryOrder]] as the round form and the job settings show it, the legacy rule's for none. */
  def topOrderParam: String = topBinaryOrder.fold(BinaryOrder.LegacyParam)(_.param)

  /** A new submission's top-N with an order stored where it picks the files, from a yes/no
    * previous round (`prevBinary`): the default unless one was chosen. NULL there is the legacy
    * rule of the rounds from before V72. None without a top-N or over rated previous rounds.
    */
  def withExplicitTopOrder(prevBinary: Boolean): Round =
    copy(topBinaryOrder =
      topImages.filter(_ => prevBinary).flatMap(_ => topBinaryOrder.orElse(Some(BinaryOrder.Default))))

  def categoryRule: CategoryRule = CategoryRule.fromStored(category, excludeCategory, categoryClause)

  /** Canonical named settings for a new submission; existing rounds are not rewritten. */
  def withCanonicalCategoryRule: Round = {
    val rule = categoryRule
    rule.requireValid()
    copy(category = rule.first, excludeCategory = rule.second, categoryClause = rule.operation.storedValue)
  }

  /** Ids of the previous rounds this round draws its images from.
    * Stored as a comma-separated string in the `previous` column (like `regions`).
    */
  def previousIds: Seq[Long] =
    previous
      .map(_.split(",").toSeq.flatMap(s => scala.util.Try(s.trim.toLong).toOption))
      .getOrElse(Nil)

  def monumentIds: Seq[String] =
    monuments.map(_.split(",").toSeq).getOrElse(Nil)

  def fixCategory(name: String): String = {
    if (!name.toLowerCase().startsWith("category:")) {
      "Category:" + name
    } else {
      name
    }
  }

  def withFixedCategories: Round = {
    copy(
      category = category.map(_.trim).filter(_.nonEmpty).map(fixCategory),
      excludeCategory = excludeCategory.map(_.trim).filter(_.nonEmpty).map(fixCategory)
    )
  }

  def addUser(
      ru: RoundUser
  )(implicit session: DBSession = RoundUser.autoSession): Unit = {
    RoundUser.withColumns { c =>
      RoundUser.createWithNamedValues(
        c.roundId -> id,
        c.userId -> ru.userId,
        c.role -> ru.role,
        c.active -> ru.active
      )
    }
  }

  def addUsers(users: Seq[RoundUser]): Unit =
    DB localTx { implicit session => addUsersIn(users) }

  /** [[addUsers]] in the caller's session (transaction). */
  def addUsersIn(users: Seq[RoundUser])(implicit session: DBSession): Unit =
    withSQL {
      val c = RoundUser.column
      insert
        .into(RoundUser)
        .namedValues(
          c.roundId -> sqls.?,
          c.userId -> sqls.?,
          c.role -> sqls.?,
          c.active -> sqls.?
        )
    }.batch(users.map(ru => Seq(id, ru.userId, ru.role, ru.active)): _*)
      .apply()

  def deleteUser(
      user: User
  )(implicit session: DBSession = RoundUser.autoSession): Unit = {
    RoundUser.withColumns { c =>
      withSQL {
        delete.from(RoundUser).where.eq(c.roundId, id).and.eq(c.userId, user.id)
      }.update()
    }
  }

}

case class RoundUser(roundId: Long, userId: Long, role: String, active: Boolean)

case class Rates(id: Int, name: String, minRate: Int = 0, maxRate: Int = 1)

object Rates {

  val map = Map(1 -> "selected", 0 -> "unrated", -1 -> "rejected")

  val pairs: Seq[(Option[Int], String)] =
    Seq(Some(1) -> "selected", Some(0) -> "unrated", Some(-1) -> "rejected")

}

object Round extends RoundRepo with CRUDMapper[Round] {

  implicit def session: DBSession = autoSession

  override val tableName = "round"

  lazy val c = Round.syntax("c")

  lazy val s = VoteJdbc.syntax("s")

  lazy val ru = RoundUser.syntax("ru")

  override lazy val defaultAlias = createAlias("r")

  lazy val r = defaultAlias

  val commentsOnlyRound = new Rates(0, "Comments only", 0, 0)

  val binaryRound = new Rates(1, "+/-", -1, 1)

  val rateRounds: Seq[Rates] =
    (3 to 20).map(i => new Rates(i, s"1-$i rating", 1, i))

  val rates: Seq[Rates] = Seq(commentsOnlyRound, binaryRound) ++ rateRounds

  val ratesById: Map[Int, Rates] =
    rates.groupBy(_.id).view.mapValues(_.head).toMap

  lazy val usersRef = hasManyThrough[User](
    through = RoundUser,
    many = User,
    merge = (round, users) => round.copy(users = users)
  ).byDefault

  override def extract(rs: WrappedResultSet, c: ResultName[Round]): Round =
    new Round(
      id = Some(rs.long(c.id)),
      name = Option(rs.string(c.name)),
      number = rs.int(c.number),
      distribution = rs.int(c.distribution),
      contestId = rs.long(c.contestId),
      rates = Round.ratesById(rs.int(c.rates)),
      limits = RoundLimits(),
//        RoundLimits(
//        min = rs.intOpt(c.limitMin),
//        max = rs.intOpt(c.limitMax),
//        recommended = rs.intOpt(c.recommended)
//      ),
      createdAt = rs.timestamp(c.createdAt).toZonedDateTime,
      deletedAt = rs.timestampOpt(c.deletedAt).map(_.toZonedDateTime),
      active = rs.booleanOpt(c.active).getOrElse(false),
      optionalRate = rs.booleanOpt(c.optionalRate).getOrElse(false),
      juryOrgView = rs.booleanOpt(c.juryOrgView).getOrElse(false),
      minMpx = rs.intOpt(c.minMpx),
      previous = rs.stringOpt(c.previous),
      prevSelectedBy = rs.intOpt(c.prevSelectedBy),
      prevRejectedBy = rs.intOpt(c.prevRejectedBy),
      prevMinAvgRate = rs.bigDecimalOpt(c.prevMinAvgRate).map(BigDecimal.apply),
      category = rs.stringOpt(c.category),
      excludeCategory = rs.stringOpt(c.excludeCategory),
      categoryClause = rs.stringOpt(c.categoryClause),
      regions = rs.stringOpt(c.regions),
      minImageSize = rs.intOpt(c.minImageSize),
      hasCriteria = rs.booleanOpt(c.hasCriteria).getOrElse(false),
      halfStar = rs.booleanOpt(c.halfStar).getOrElse(false),
      monuments = rs.stringOpt(c.monuments),
      topImages = rs.intOpt(c.topImages),
      specialNomination = rs.stringOpt(c.specialNomination),
      mediaType = rs.stringOpt(c.mediaType),
      submitToken = rs.stringOpt(c.submitToken),
      distributing = rs.booleanOpt(c.distributing).getOrElse(false),
      freezeCount = rs.intOpt(c.freezeCount).getOrElse(0),
      excludeCoi = rs.booleanOpt(c.excludeCoi).getOrElse(false),
      topBinaryOrder = rs.stringOpt(c.topBinaryOrder).flatMap(BinaryOrder.parse)
    ).withFixedCategories

  def create(round: Round)(implicit session: DBSession = AutoSession): Round = {
    val id = withSQL {
      insert
        .into(Round)
        .namedValues(
          column.number -> round.number,
          column.name -> round.name,
          column.contestId -> round.contestId,
          column.roles -> round.roles.head,
          column.distribution -> round.distribution,
          column.rates -> round.rates.id,
//          column.limitMin -> round.limits.min,
//          column.limitMax -> round.limits.max,
//          column.recommended -> round.limits.recommended,
          column.createdAt -> round.createdAt,
          column.active -> round.active,
          column.optionalRate -> round.optionalRate,
          column.juryOrgView -> round.juryOrgView,
          column.minMpx -> round.minMpx,
          column.previous -> round.previous,
          column.prevSelectedBy -> round.prevSelectedBy,
          column.prevRejectedBy -> round.prevRejectedBy,
          column.prevMinAvgRate -> round.prevMinAvgRate,
          column.category -> round.category,
          column.excludeCategory -> round.excludeCategory,
          column.categoryClause -> round.categoryClause,
          column.regions -> round.regions,
          column.minImageSize -> round.minImageSize,
          column.halfStar -> round.halfStar,
          column.monuments -> round.monuments,
          column.topImages -> round.topImages,
          column.specialNomination -> round.specialNomination,
          column.mediaType -> round.mediaType,
          column.submitToken -> round.submitToken,
          column.distributing -> round.distributing,
          column.excludeCoi -> round.excludeCoi,
          column.topBinaryOrder -> round.topBinaryOrder.map(_.param),
          column.hasCriteria -> round.criterionIds.nonEmpty
        )
    }.updateAndReturnGeneratedKey()

    // its criteria, in the caller's session (RoundService.insert: the round's transaction);
    // the new row is the transaction's (an insert holds it for update)
    RoundWrites.recordRounds(Seq(id))
    RoundCriterion.insert(id, round.criterionIds)
    round.copy(id = Some(id), hasCriteria = round.criterionIds.nonEmpty)
  }

  /** The round form's edit: its name. Starting and stopping it is [[setActiveGuarded]]'s. */
  def updateRound(id: Long, round: Round): Unit =
    updateById(id).withAttributes("name" -> round.name): Unit

  /** The round form's "Leave out conflicts of interest", for a round with none of them to
    * take out, or to stop leaving them out ([[RoundConflicts.removeFromRound]] sets it for
    * one with some).
    */
  def setExcludeCoi(id: Long, excludeCoi: Boolean): Unit =
    updateById(id).withAttributes("excludeCoi" -> excludeCoi): Unit

  def activeRounds(contestId: Long)(implicit session: DBSession = AutoSession): Seq[Round] =
    where(sqls.eq(r.contestId, contestId).and.eq(r.active, true).and.eq(r.distributing, false))
      .orderBy(r.id)
      .apply()

  def activeRounds(user: User): Seq[Round] = {
    user.currentContest
      .map { contestId =>
        where(
          sqls
            .eq(r.contestId, contestId)
            .and
            .eq(r.active, true)
            .and
            .eq(r.distributing, false)
            .and
            .exists(
              select
                .from(RoundUser as ru)
                .where
                .eq(ru.userId, user.getId)
                .and
                .eq(ru.roundId, r.id)
                .and
                .eq(ru.active, true)
                .sql
            )
        )
          .orderBy(r.id)
          .apply()
      }
      .getOrElse(Nil)
  }

  def findByContest(contest: Long): Seq[Round] =
    where("contestId" -> contest)
      .orderBy(r.id)
      .apply()

  def findByIds(contestId: Long, roundIds: Seq[Long]): Seq[Round] = {
    where(sqls.eq(r.contestId, contestId).and.in(r.id, roundIds))
      .orderBy(r.id)
      .apply()
  }

  /** A round may draw its images from several previous rounds, but they must all share
    * one rate type (all binary or all rated) — the filtering / rate-scaling code treats
    * them as one context. An empty or single-element seq trivially satisfies this.
    */
  def sameRateType(rounds: Seq[Round]): Boolean =
    rounds.map(_.isBinary).distinct.sizeIs <= 1

  def setActive(id: Long, active: Boolean): Unit =
    updateById(id)
      .withAttributes("active" -> active)

  /** An organizer starting or stopping a round. A round still being created
    * (`distributing`) can't be started. A frozen round (a previous round of a running
    * distribution, see [[freeze]]) stays inactive until the distribution ends; the
    * change applies then.
    *
    * @return false if the round can't be started now (it is still being distributed)
    */
  def setActiveGuarded(id: Long, active: Boolean): Boolean = {
    val refused =
      sql"SELECT distributing FROM round WHERE id = $id".map(_.boolean(1)).single().getOrElse(false) && active
    val archived = WriteTx.write { implicit session =>
      val wasOpen = isOpen(id)
      sql"""UPDATE round
            SET active_before_freeze = IF(freeze_count > 0, $active AND NOT distributing, active_before_freeze),
                active = IF(freeze_count > 0, active, $active AND NOT distributing)
            WHERE id = $id""".update()
      // After the update, and read as of then: see ImageJdbc.lockOpenRoundsOf
      !wasOpen && isOpen(id) && ImageJdbc.archiveDeletedVotesInRound(id).nonEmpty
    }
    if (archived) RoundImageCounts.invalidate(id)
    !refused
  }

  /** After votes were moved into the round ([[VoteJdbc.setRound]]) and committed: if
    * it is open, moves aside its votes of files deleted on Commons, in a transaction of its
    * own. One marked deleted after this
    * reads them moves them itself ([[services.imports.DeletedFiles.settle]]).
    */
  def settleDeletedFiles(id: Long): Unit = {
    val archived = WriteTx.write { implicit session =>
      isOpen(id) && ImageJdbc.archiveDeletedVotesInRound(id).nonEmpty
    }
    if (archived) RoundImageCounts.invalidate(id)
  }

  /** A round jurors vote in, or will when a running distribution ends: active, being
    * created, or frozen while active. A file deleted on Commons leaves the open rounds only
    * (V63, [[ImageJdbc.lockOpenRoundsOf]]); the others, finished or not started yet, keep
    * its votes until they open ([[setActiveGuarded]]).
    *
    * @param table the round table or its alias
    */
  def openCond(table: SQLSyntax): SQLSyntax =
    sqls"""($table.active = 1 OR $table.distributing = 1
            OR ($table.freeze_count > 0 AND $table.active_before_freeze = 1))"""

  /** Whether the round is open ([[openCond]]), read as committed and locked for update. */
  def isOpen(id: Long)(implicit session: DBSession): Boolean = {
    val open = sql"SELECT ${openCond(sqls"round")} FROM round WHERE id = $id FOR UPDATE"
      .map(_.booleanOpt(1).getOrElse(false))
      .single()
    RoundWrites.recordRounds(Seq(id))
    open.getOrElse(false)
  }

  /** Locks the rounds for update in id order, as [[services.imports.DeletedFiles]] locks
    * them, before any of their updates.
    */
  def lock(ids: Seq[Long])(implicit session: DBSession): Unit =
    RoundWrites.lock(ids)

  /** Freezes previous rounds for a distribution that reads their votes: they become
    * inactive (no votes) until [[release]]. The first freeze of a round saves its active
    * flag; nested freezes (several distributions from one round) only count. MariaDB
    * applies the assignments left to right, each seeing the ones before.
    */
  def freeze(roundIds: Seq[Long])(implicit session: DBSession): Unit =
    roundIds.distinct.sorted.foreach { id =>
      sql"""UPDATE round
            SET active_before_freeze = IF(freeze_count = 0, COALESCE(active, 0), active_before_freeze),
                active = 0,
                freeze_count = freeze_count + 1
            WHERE id = $id""".update()
    }

  /** Ends a [[freeze]]. The last release restores the round's saved active flag, or
    * leaves it inactive when `close`: a completed round creation closes its previous
    * rounds, as creating the next round always has, also if another distribution
    * still reads them.
    */
  def release(roundIds: Seq[Long], close: Boolean)(implicit session: DBSession): Unit =
    roundIds.distinct.sorted.foreach { id =>
      sql"""UPDATE round
            SET active_before_freeze = IF($close, 0, active_before_freeze),
                freeze_count = GREATEST(freeze_count - 1, 0),
                active = IF(freeze_count = 0, COALESCE(active_before_freeze, active), active),
                active_before_freeze = IF(freeze_count = 0, NULL, active_before_freeze)
            WHERE id = $id""".update()
    }

  /** A round's creation has passed its count check: it is shown to its jurors. */
  def completeCreation(id: Long)(implicit session: DBSession): Unit =
    sql"UPDATE round SET distributing = 0, active = 1 WHERE id = $id".update()

  def setInactiveAllInContest(contestId: Long): Unit =
    withSQL {
      update(Round)
        .set(column.active -> false)
        .where
        .eq(column.contestId, contestId)
    }.update()

  /** The round created by the submission with this token (see V59), if any. */
  def findBySubmitToken(token: String)(implicit session: DBSession = AutoSession): Option[Round] =
    where(sqls.eq(r.submitToken, token)).apply().headOption

  def countByContest(contestId: Long): Long =
    countBy(sqls.eq(r.contestId, contestId))

  case class RoundStatRow(juror: Long, rate: Int, count: Int)

  def roundUserStat(roundId: Long): Seq[RoundStatRow] =
    sql"""SELECT s.jury_id, s.rate, count(1) FROM vote s
    WHERE s.round_id = $roundId
    GROUP BY s.jury_id, s.rate"""
      .map(rs => RoundStatRow(rs.int(1), rs.int(2), rs.int(3)))
      .list()

  /** Distinct images selected (rate 1) in a binary round: a scan of the round's
    * entries in vote_round_page_rate_idx, without reading the rows.
    */
  def selectedImageCount(roundId: Long): Int =
    sql"""SELECT COUNT(DISTINCT s.page_id) FROM vote s
          WHERE s.round_id = $roundId AND s.rate = 1"""
      .map(_.int(1))
      .single()
      .getOrElse(0)

  /** A binary round's files with at least one approval (1), one vote yet to come (0) and one
    * rejection (-1): what its lists of all the jurors' votes filtered by each list
    * (docs/plans/2026-11-01-binary-aggregate-votes.md, decision 3), a file in as many of them
    * as its votes are of kinds. One scan of the round's entries in
    * vote_round_page_rate_idx, without reading the rows.
    */
  def imageCountsByRate(roundId: Long): Map[Int, Int] =
    sql"""SELECT COALESCE(SUM(t.a > 0), 0), COALESCE(SUM(t.u > 0), 0), COALESCE(SUM(t.r > 0), 0) FROM (
            SELECT SUM(s.rate > 0) a, SUM(s.rate = 0) u, SUM(s.rate < 0) r FROM vote s
            WHERE s.round_id = $roundId GROUP BY s.page_id) t"""
      .map(rs => Map(1 -> rs.int(1), 0 -> rs.int(2), -1 -> rs.int(3)))
      .single()
      .getOrElse(Map.empty)

}

object RoundUser extends JoinTable[RoundUser] {
  override def defaultAlias = createAlias("round_user")
  lazy val ru = RoundUser.syntax("round_user")

  override def extract(
      rs: WrappedResultSet,
      ru: ResultName[RoundUser]
  ): RoundUser =
    RoundUser(
      rs.long(ru.roundId),
      rs.long(ru.userId),
      rs.string(ru.role),
      rs.boolean(ru.active)
    )

  def apply(r: Round, u: User): Option[RoundUser] =
    for (rId <- r.id; uId <- u.id) yield RoundUser(rId, uId, u.contestRole.getOrElse(User.JURY_ROLE), active = true)

  def activeJurors(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId, "active" -> true).apply()

  def byRoundId(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId).apply()

  /** Whether the user is stopped on the round: their round_user row is inactive (P6). They
    * get no new images ([[User.distributionJurors]]) and may not vote; a juror without a row (a
    * legacy round's) is not stopped.
    */
  def isStopped(roundId: Long, userId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT 1 FROM round_user WHERE round_id = $roundId AND user_id = $userId AND active = FALSE"""
      .map(_.int(1)).single.apply().isDefined

  def setActive(roundId: Long, userId: Long, active: Boolean): Int =
    updateBy(sqls.eq(ru.roundId, roundId).and.eq(ru.userId, userId))
      .withAttributes("active" -> active)
}
