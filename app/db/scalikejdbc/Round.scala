package db.scalikejdbc

import db.RoundRepo
import org.intracer.wmua.{AverageRule, BinaryOrder, CategoryRule, HasId, RejectedMode, Vote}
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
    // no juryOrgView: the legacy `jury_org_view` column (jurors seeing the organizer views, set
    // only by hand in SQL, never by a form) is neither read nor written; jurors always get the
    // juror view. The column stays (nullable, DEFAULT NULL) until the multi-role contract migration
    minMpx: Option[Int] = None,
    previous: Option[String] = None,
    prevSelectedBy: Option[Int] = None,
    // "rejected by at least N jurors" of the previous rounds (V74; a rated round's ✗ too); None: no
    // filter. Its mode (V93): exclude those files (the default, the meaning before V93) or only them
    prevRejectedBy: Option[Int] = None,
    prevRejectedMode: RejectedMode = RejectedMode.Default,
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
    // "Leave out files that are in round(s)" (V95): other rounds of the contest; a file with a row
    // in one of them (round_image, any status) is never distributed to this round
    excludeRounds: Seq[Long] = Nil,
    // the order of a top-N from a yes/no previous round (V72); None (NULL, a round from before
    // V72): the legacy rule (BinaryOrder.LegacyParam). A new round from a yes/no round stores
    // one (withExplicitTopOrder); over rated previous rounds it plays no part, and is NULL.
    topBinaryOrder: Option[BinaryOrder] = None,
    // how a rated round averages a file's rates (V90): every view of its results and a next
    // round's top-N and "min average rate" from it use it
    averageRule: AverageRule = AverageRule.Default,
    // a rated round's worst mark (V92): jurors may give a file ✗, stored as rate -1 and counted
    // as 0 in every average and total ([[takesWorstMark]]); off by default
    worstMark: Boolean = false,
    // the number of a criteria round's criteria, read where its results are drawn
    // (perCriterion: the galleries, the large view); not loaded with the round
    criteriaCount: Int = 1,
    // the round form's criteria, by position: a new round's are written with it (create);
    // not loaded with the round (RoundCriterion.idsOf)
    criterionIds: Seq[Long] = Nil,
    // its lifecycle state (V105, round lineage §2.6): one of Round.Status.All; None for a round
    // not backfilled (a rollback's), read from the flags ([[lifecycle]])
    status: Option[String] = None
) extends HasId {

  /** The round's state ([[Round.Status]]): the stored one, or for a round without one (created by
    * a binary from before V105) the flags' (draft while being created, active if open, else
    * paused).
    */
  def lifecycle: String =
    status.getOrElse(Round.Status.ofFlags(distributing = distributing, open = active))

  /** In review (P3): filled, not yet distributed to its jurors. "Before first activation" is told
    * by `distributing` (round lineage §2.6): a stopped round is `paused` too, with
    * `distributing = 0`.
    */
  def inReview: Boolean = distributing && lifecycle == Round.Status.Paused

  /** Jurors may see and vote in the round: it is active, and not still being created
    * (see V60: a round being distributed stays hidden until its count check passes).
    */
  def openToJurors: Boolean = active && !distributing

  /** Inactive by a freeze, so possibly paused for its jurors while a job moves its files
    * ([[Round.pausedCond]] decides, by the job): the cheap test before that lookup.
    */
  def mayBePaused: Boolean = !active && !distributing && freezeCount > 0

  def availableJurors(implicit session: DBSession = AutoSession): Seq[User] =
    User.findByContestAndRoles(contestId, roles.toSeq)

  lazy val numberOfAssignedJurors = VoteJdbc.allJurors(id.get)

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
    * criterion, and -1 (✗) where the round [[takesWorstMark]]; none in a comments-only round.
    */
  def acceptsRate(rate: Int): Boolean =
    if (isBinary) rate >= Vote.Rejected && rate <= Vote.Selected
    else if (rates.id == Round.commentsOnlyRound.id) false
    else Vote.isUnrated(rate) || (rate >= rates.minRate && rate <= maxRate) || (rate == Vote.Rejected && takesWorstMark)

  /** Its jurors may give a file ✗ (the worst mark, rate -1): a rated round without criteria with
    * the option on. A yes/no round's -1 is its "no", not this; a criteria round's votes are per
    * criterion, and never ✗ (the round form refuses the option for both).
    */
  def takesWorstMark: Boolean = worstMark && isRated && !hasCriteria

  /** Its votes may reject files: a yes/no round's "no", or a ✗ of a rated round without criteria
    * (the rounds that can take one, [[takesWorstMark]], whatever the option is now: a ✗ given
    * stays when it is turned off, and a stored "rejected by at least N" keeps reading them). A
    * next round from it may leave out the files rejected by at least N jurors
    * ([[rejectedByFilter]]); from a rated round without any ✗ the filter leaves out nothing.
    */
  def rejects: Boolean = isBinary || (isRated && !hasCriteria)

  /** A round of one criterion, its criteria read ([[criterionIds]]: the galleries'
    * `withCriteria`, the large view): voted and shown as a rated round, the criterion named once
    * (gallery stars, its rate text, the large view's digit keys). False where they aren't read.
    */
  def singleCriterion: Boolean = hasCriteria && criterionIds.sizeIs == 1

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

  /** The stored units a file's static stars are filled to for `rate` (`showStar`): a criteria
    * round's [[perCriterion]] rounded to the nearest unit, so to the nearest half star in a
    * half-star round (3.25 stars, 6.5 units, draws 3.5); other rounds' truncated, as before C3.
    */
  def starsRate(rate: Double): Int =
    if (hasCriteria) math.round(perCriterion(rate)).toInt else rate.toInt

  def regionIds: Seq[String] = regions.map(_.split(",").toSeq).getOrElse(Nil)

  /** "Selected by at least N jurors" as the distribution reads it: N >= 1 approvals (rated
    * votes) in the previous round(s), or no filter. A stored 0 is no filter, as NULL is: the
    * old round form's way of saying it (V73 clears them); every reader goes through this.
    */
  def selectedByFilter: Option[Int] = prevSelectedBy.filter(_ > 0)

  /** "Rejected by at least N jurors" as the distribution reads it: N >= 1, or no filter. By
    * [[prevRejectedMode]] it drops the files with N or more rejections across the previous rounds'
    * votes, or keeps only them.
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

  /** [[excludeRounds]] as stored (`exclude_rounds`, V95): the ids comma-separated, NULL for none. */
  def excludeRoundsStored: Option[String] = Option.when(excludeRounds.nonEmpty)(excludeRounds.mkString(","))

  /** The monument ids filter: comma-separated, each trimmed (the form's "a, b"), empty parts left out. */
  def monumentIds: Seq[String] =
    monuments.map(_.split(",").toSeq.map(_.trim).filter(_.nonEmpty)).getOrElse(Nil)

  def fixCategory(name: String): String = {
    val title = services.imports.WikiTitle.normalize(name)
    if (!title.toLowerCase().startsWith("category:")) {
      "Category:" + title
    } else {
      title
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

  /** A yes/no round. Pages show its rating type from messages (`views.RatingType`), not this name. */
  val binaryRound = new Rates(1, "Yes/No", -1, 1)

  val rateRounds: Seq[Rates] =
    (3 to 20).map(i => new Rates(i, s"1-$i rating", 1, i))

  val rates: Seq[Rates] = Seq(commentsOnlyRound, binaryRound) ++ rateRounds

  val ratesById: Map[Int, Rates] =
    rates.groupBy(_.id).view.mapValues(_.head).toMap

  /** A round's lifecycle states (`round.status`, V105; round lineage §2.6, the two-step plan's
    * §4). Written in step with `active` and `distributing`:
    *  - draft: distributing, not active (its creation's fill runs, failed or never completed);
    *  - paused: distributing, not active, filled and in review (P3); or stopped or finished
    *    (distributing = 0, not active);
    *  - active: open to its jurors (distributing = 0, active, or frozen while active);
    *  - cancelled: a creation job an admin cancelled (round lineage's Cancel later);
    *  - finalized: not written yet (Montage parity).
    */
  object Status {
    val Draft = "draft"
    val Paused = "paused"
    val Active = "active"
    val Finalized = "finalized"
    val Cancelled = "cancelled"
    val All: Seq[String] = Seq(Draft, Paused, Active, Finalized, Cancelled)

    /** The status a round's flags give, as V105's backfill and a NULL status read them. */
    def ofFlags(distributing: Boolean, open: Boolean): String =
      if (distributing) Draft else if (open) Active else Paused
  }

  /** Round ids stored comma-separated (`previous`, `exclude_rounds`); a part that is no id is skipped. */
  def parseIds(stored: Option[String]): Seq[Long] =
    stored.map(_.split(",").toSeq.flatMap(s => s.trim.toLongOption)).getOrElse(Nil)

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
      minMpx = rs.intOpt(c.minMpx),
      previous = rs.stringOpt(c.previous),
      prevSelectedBy = rs.intOpt(c.prevSelectedBy),
      prevRejectedBy = rs.intOpt(c.prevRejectedBy),
      prevRejectedMode = RejectedMode.fromStored(rs.stringOpt(c.prevRejectedMode)),
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
      excludeRounds = Round.parseIds(rs.stringOpt(c.excludeRounds)),
      topBinaryOrder = rs.stringOpt(c.topBinaryOrder).flatMap(BinaryOrder.parse),
      averageRule = AverageRule.fromStored(rs.stringOpt(c.averageRule)),
      worstMark = rs.booleanOpt(c.worstMark).getOrElse(false),
      status = rs.stringOpt(c.status)
    ).withFixedCategories

  def create(round: Round)(implicit session: DBSession = AutoSession): Round = {
    val status = round.status.getOrElse(Status.ofFlags(round.distributing, round.active))
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
          column.minMpx -> round.minMpx,
          column.previous -> round.previous,
          column.prevSelectedBy -> round.prevSelectedBy,
          column.prevRejectedBy -> round.prevRejectedBy,
          column.prevRejectedMode -> round.prevRejectedMode.param,
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
          column.excludeRounds -> round.excludeRoundsStored,
          column.topBinaryOrder -> round.topBinaryOrder.map(_.param),
          column.averageRule -> round.averageRule.param,
          column.worstMark -> round.worstMark,
          column.hasCriteria -> round.criterionIds.nonEmpty,
          // from its flags (round lineage §2.6), unless given
          column.status -> status,
          column.column("status_changed_at") -> ZonedDateTime.now
        )
    }.updateAndReturnGeneratedKey()

    // its criteria, in the caller's session (RoundService.insert: the round's transaction);
    // the new row is the transaction's (an insert holds it for update)
    RoundWrites.recordRounds(Seq(id))
    RoundCriterion.insert(id, round.criterionIds)
    round.copy(id = Some(id), hasCriteria = round.criterionIds.nonEmpty, status = Some(status))
  }

  /** The round form's edit: its name, its average rule and its worst mark. Starting and stopping
    * it is [[setActiveGuarded]]'s. The rule may change at any time: it changes how results are
    * shown, not the votes; a distribution from the round reads the rule recorded in its job's
    * settings when it was queued (`RoundService`), so a change applies to the next one. The worst
    * mark too: turned off, the ✗ votes given stay (shown, and counted as 0), and no new one is
    * taken.
    */
  def updateRound(id: Long, round: Round)(implicit session: DBSession = AutoSession): Unit =
    // created_at set to itself: production's has ON UPDATE CURRENT_TIMESTAMP (V86)
    sql"""UPDATE round SET name = ${round.name}, average_rule = ${round.averageRule.param},
            worst_mark = ${round.worstMark}, created_at = created_at
          WHERE id = $id""".update(): Unit

  /** A re-apply's filters (P3-5): the settings that choose a round's files, as the round form posts
    * them, written before its `fill` job is queued, under the round's lock
    * ([[services.RoundService.reapplyFilters]]). The rating, the jurors and the distribution aren't
    * filters.
    */
  def updateFilters(id: Long, round: Round)(implicit session: DBSession): Unit =
    // created_at set to itself: production's has ON UPDATE CURRENT_TIMESTAMP (V86)
    sql"""UPDATE round SET previous = ${round.previous}, prev_selected_by = ${round.prevSelectedBy},
            prev_rejected_by = ${round.prevRejectedBy}, prev_rejected_mode = ${round.prevRejectedMode.param},
            prev_min_avg_rate = ${round.prevMinAvgRate}, category = ${round.category},
            exclude_category = ${round.excludeCategory}, category_clause = ${round.categoryClause},
            regions = ${round.regions}, min_image_size = ${round.minImageSize}, min_mpx = ${round.minMpx},
            monuments = ${round.monuments}, top_images = ${round.topImages},
            top_binary_order = ${round.topBinaryOrder.map(_.param)}, special_nomination = ${round.specialNomination},
            media_type = ${round.mediaType}, exclude_rounds = ${round.excludeRoundsStored}, created_at = created_at
          WHERE id = $id""".update(): Unit

  /** "Leave out conflicts of interest" written in the caller's transaction (a re-apply's, P3-5: its
    * fill leaves them out as a filter).
    */
  def writeExcludeCoi(id: Long, excludeCoi: Boolean)(implicit session: DBSession): Unit =
    sql"UPDATE round SET exclude_coi = $excludeCoi, created_at = created_at WHERE id = $id".update(): Unit

  /** The round form's "Leave out conflicts of interest", for a round with none of them to
    * take out, or to stop leaving them out ([[RoundConflicts.disqualify]] sets it for
    * one with some).
    */
  def setExcludeCoi(id: Long, excludeCoi: Boolean): Unit =
    updateById(id).withAttributes("excludeCoi" -> excludeCoi): Unit

  /** The round is paused for its jurors while a `round_rebalance` job moves its unrated files
    * (docs/plans/2026-11-16-round-editing.md, §5): the job froze it while it was open
    * (`freeze_count > 0`, `active_before_freeze = 1`) and holds its dedupe key. Its jurors keep it
    * in their menus and get the "paused" page and the vote error `round_paused` instead of the
    * closed round's; a round frozen as a previous round of a distribution isn't paused. One
    * lookup of the unique `job.active_dedupe_key`.
    *
    * @param table the round table or its alias
    */
  def pausedCond(table: SQLSyntax): SQLSyntax =
    sqls"""($table.freeze_count > 0 AND $table.active_before_freeze = 1 AND $table.distributing = 0
            AND EXISTS (SELECT 1 FROM job pj
                        WHERE pj.active_dedupe_key = CONCAT(${services.distribution.RoundDistributionHandler.Kind + ":" +
      services.distribution.RoundDistributionHandler.SubjectType + ":"}, $table.id)
                          AND pj.kind = ${services.distribution.RoundRebalanceHandler.Kind}))"""

  /** Whether the round is frozen while open: inactive only because a job reads or moves it
    * (`freeze_count > 0`, `active_before_freeze = 1`, not being distributed), e.g. the previous
    * round of a distribution; it opens again when the job ends. A plain read.
    */
  def isFrozenOpen(roundId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"""SELECT 1 FROM round WHERE id = $roundId AND freeze_count > 0 AND active_before_freeze = 1
          AND distributing = 0""".map(_ => ()).single().isDefined

  /** A round `r` open to jurors as stored now: not being created, and active or frozen while
    * active ([[isFrozenOpen]]: a job reads it, it opens again when the job ends). The round
    * warnings' "open" ([[RoundUser.openWithoutActiveJuror]], [[openToJurorsIds]]).
    */
  private[scalikejdbc] val openToJurorsNowCond: SQLSyntax =
    sqls"r.distributing = 0 AND (r.active = 1 OR (r.freeze_count > 0 AND r.active_before_freeze = 1))"

  /** Of these rounds, those open to jurors as stored now ([[openToJurorsNowCond]]). One read. */
  def openToJurorsIds(rounds: Seq[Round])(implicit session: DBSession = AutoSession): Set[Long] = {
    val ids = rounds.flatMap(_.id)
    if (ids.isEmpty) Set.empty
    else sql"SELECT r.id FROM round r WHERE r.id IN ($ids) AND $openToJurorsNowCond".map(_.long(1)).list().toSet
  }

  /** Whether the round is paused for its jurors ([[pausedCond]]), a plain read. */
  def isPaused(roundId: Long)(implicit session: DBSession = AutoSession): Boolean =
    sql"SELECT ${pausedCond(sqls"round")} FROM round WHERE id = $roundId"
      .map(_.booleanOpt(1).getOrElse(false)).single().getOrElse(false)

  /** Open to its jurors as their menus list it: active, or paused ([[pausedCond]]). */
  private def openOrPausedCond(table: SQLSyntax): SQLSyntax =
    sqls"($table.active = 1 OR ${pausedCond(table)})"

  def activeRounds(contestId: Long)(implicit session: DBSession = AutoSession): Seq[Round] =
    where(sqls.eq(r.contestId, contestId).and.eq(r.active, true).and.eq(r.distributing, false))
      .orderBy(r.id)
      .apply()

  /** Of `contestIds`, those with a round open to the user as a juror, as [[activeRounds(user*]]
    * takes one: active (or paused, [[pausedCond]]), not being distributed, with an active
    * round_user row of the user. One query (the navbar's contest switcher runs it on every page
    * of a multi-contest juror).
    */
  def contestsWithOpenRound(userId: Long, contestIds: Seq[Long])(implicit
      session: DBSession = AutoSession): Set[Long] =
    if (contestIds.isEmpty) Set.empty
    else
      sql"""SELECT DISTINCT r.contest_id FROM round r
            JOIN round_user ru ON ru.round_id = r.id AND ru.user_id = $userId AND ru.active = TRUE
            WHERE r.contest_id IN (${contestIds.distinct}) AND ${openOrPausedCond(sqls"r")} AND r.distributing = FALSE"""
        .map(_.long(1)).list().toSet

  /** The user's rounds as a juror in their current contest: active or paused for a rebalance
    * ([[pausedCond]]), not being distributed, with an active round_user row of the user.
    */
  def activeRounds(user: User): Seq[Round] = {
    user.currentContest
      .map { contestId =>
        where(
          sqls
            .eq(r.contestId, contestId)
            .and
            // `r`: the default alias (createAlias("r")) the query reads the round by
            .append(openOrPausedCond(sqls"r"))
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

  /** The user's rounds in their current contest (a round_user row of theirs, active or not) that
    * are not open to them as [[activeRounds(user*]] takes one: stopped, being distributed, or with
    * the user stopped on it (an inactive round_user row); each with whether their round_user row
    * is active. One query; the
    * start page's "no round" list of closed rounds (controllers.JurorContests.noRoundPage).
    */
  def jurorRoundsNotOpen(user: User)(implicit session: DBSession = AutoSession): Seq[(Round, Boolean)] =
    user.currentContest.fold(Seq.empty[(Round, Boolean)]) { contestId =>
      sql"""SELECT ${r.result.*}, ru.active AS ru_active FROM ${Round.as(r)}
            JOIN round_user ru ON ru.round_id = ${r.id} AND ru.user_id = ${user.getId}
            WHERE ${r.contestId} = $contestId
              AND NOT (${openOrPausedCond(sqls"r")} AND ${r.distributing} = FALSE AND ru.active = TRUE)
            ORDER BY ${r.id}"""
        .map(rs => (extract(rs, r.resultName), rs.boolean("ru_active"))).list()
    }

  def findByContest(contest: Long): Seq[Round] =
    where("contestId" -> contest)
      .orderBy(r.id)
      .apply()

  /** The ids of the contest's rounds not yet distributed (`distributing`: being created, held in
    * review): their comments are their staff's until they are, so a juror's comment reads leave
    * them out (controllers.RoundList.hiddenCommentRounds). One read of the round table.
    */
  def undistributedIds(contestId: Long)(implicit session: DBSession = AutoSession): Seq[Long] =
    sql"SELECT id FROM round WHERE contest_id = $contestId AND distributing = 1 ORDER BY id".map(_.long(1)).list()

  /** The rounds of these ids, whatever their contest (the caller checks it), by id. */
  def findByIdsInAnyContest(roundIds: Seq[Long]): Seq[Round] =
    if (roundIds.isEmpty) Nil else where(sqls.in(r.id, roundIds)).orderBy(r.id).apply()

  def findByIds(contestId: Long, roundIds: Seq[Long]): Seq[Round] = {
    where(sqls.eq(r.contestId, contestId).and.in(r.id, roundIds))
      .orderBy(r.id)
      .apply()
  }

  /** A round may draw its images from several previous rounds, but they must all share one
    * rating (`rates`: all yes/no, all comments only, or all rated on the same scale, 1-5 with
    * 1-5) and the same number of criteria (none, or as many): one minimum score and one top-N
    * apply to the scores of all of them. An empty or single-element seq trivially satisfies
    * this. The round form refuses others (`error.previous.rounds.mixed.type`), the distribution
    * too. A criteria round's number of criteria is read here (it isn't loaded with the round).
    */
  def sameRateType(rounds: Seq[Round])(implicit session: DBSession = AutoSession): Boolean =
    // one rating, not only one kind: a 1-5 and a 1-10 round would rank sums of both scales
    // together, and a comments-only round has no rate to compare with a rated one's (triage
    // R196-1); a criteria round's vote is the sum of its criteria (perCriterion), so a 1-10 round
    // of 4 criteria scores up to 40, a plain 1-10 round up to 10
    rounds.sizeIs < 2 || rounds.map(ratingOf).distinct.sizeIs == 1

  /** What [[sameRateType]] compares: the scale, and a criteria round's number of criteria. */
  private def ratingOf(round: Round)(implicit session: DBSession): (Long, Int) =
    (round.rates.id, if (round.hasCriteria) RoundCriterion.countOf(round.getId) else 0)

  /** Starts or stops the round, its status in step ([[statusOfStartStop]]), unguarded: tests. */
  def setActive(id: Long, active: Boolean): Unit =
    sql"""UPDATE round SET ${statusOfStartStop(active, None)}, active = $active, created_at = created_at
          WHERE id = $id""".update(): Unit

  /** The status a start or a stop writes, in its UPDATE (round lineage §2.6): `active` or
    * `paused` as the flag it asks for (on a frozen round, the flag the freeze saved), only where
    * the round isn't being created (`distributing = 0`) nor `cancelled`; who and when with it.
    * First in the statement's SET: MariaDB applies the assignments left to right.
    */
  private def statusOfStartStop(active: Boolean, by: Option[Long]): SQLSyntax = {
    val to = if (active) Status.Active else Status.Paused
    val writes = sqls"distributing = 0 AND NOT (status <=> ${Status.Cancelled})"
    sqls"""status_changed_at = IF($writes AND NOT (status <=> $to), NOW(3), status_changed_at),
            status_changed_by = IF($writes AND NOT (status <=> $to), $by, status_changed_by),
            status = IF($writes, $to, status)"""
  }

  /** An organizer starting or stopping a round. A round still being created
    * (`distributing`) can't be started. A frozen round (a previous round of a running
    * distribution, see [[freeze]]) stays inactive until the distribution ends; the
    * change applies then.
    *
    * @return false if the round can't be started now (it is still being distributed)
    */
  def setActiveGuarded(id: Long, active: Boolean, by: Option[Long] = None): Boolean = {
    // a round of a retired contest isn't started either (contest series plan §3.1): checked again
    // in the update, which waits for a retirement holding the round's lock (ContestSeries.retireContest)
    val refused =
      sql"""SELECT r.distributing OR c.retired_at IS NOT NULL FROM round r LEFT JOIN contest_jury c ON c.id = r.contest_id
            WHERE r.id = $id""".map(_.boolean(1)).single().getOrElse(false) && active
    val archived = WriteTx.write { implicit session =>
      val wasOpen = isOpen(id)
      sql"""UPDATE round
            SET ${statusOfStartStop(active, by)},
                active_before_freeze = IF(freeze_count > 0, $active AND NOT distributing, active_before_freeze),
                active = IF(freeze_count > 0, active, $active AND NOT distributing),
                created_at = created_at
            WHERE id = $id
              AND NOT ($active AND EXISTS (SELECT 1 FROM contest_jury c WHERE c.id = round.contest_id AND c.retired_at IS NOT NULL))""".update()
      // After the update, and read as of then: see ImageJdbc.lockOpenRoundsOf
      !wasOpen && isOpen(id) && ImageJdbc.archiveDeletedVotesInRound(id).nonEmpty
    }
    if (archived) RoundImageCounts.invalidate(id)
    !refused
  }

  /** If the round is open, moves aside its votes of files deleted on Commons, in a transaction
    * of its own. Votes no longer move between rounds in the app; the tests' vote moves call this
    * after committing (`TestVoteWrites`). One marked deleted after this reads them moves them
    * itself ([[services.imports.DeletedFiles.settle]]).
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

  /** Of `ids`, the rounds open now ([[openCond]]: active, being created or reviewed, or paused by a
    * freeze while open), a plain read in one query: the conflict-of-interest rule's
    * ([[services.GalleryService.othersVotesVisible]]).
    */
  def openIds(ids: Seq[Long])(implicit session: DBSession = AutoSession): Set[Long] =
    if (ids.isEmpty) Set.empty
    else sql"SELECT id FROM round WHERE id IN (${ids.distinct}) AND ${openCond(sqls"round")}".map(_.long(1)).list().toSet

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
      // closing: `paused` as a stop writes it (round lineage §2.6); a plain release changes no status
      val status = if (close) sqls"${statusOfStartStop(active = false, None)}," else sqls""
      sql"""UPDATE round
            SET $status
                active_before_freeze = IF($close, 0, active_before_freeze),
                freeze_count = GREATEST(freeze_count - 1, 0),
                active = IF(freeze_count = 0, COALESCE(active_before_freeze, active), active),
                active_before_freeze = IF(freeze_count = 0, NULL, active_before_freeze),
                created_at = created_at
            WHERE id = $id""".update()
    }

  /** A round's creation has passed its count check: it is shown to its jurors, `active`. */
  def completeCreation(id: Long, by: Option[Long] = None)(implicit session: DBSession): Unit =
    sql"""UPDATE round SET ${statusWrite(Status.Active, by)}, distributing = 0, active = 1, created_at = created_at
          WHERE id = $id""".update(): Unit

  /** Writes the round's status, with who (None: a job's write) and when: a creation job queued
    * (`draft`), its fill done (`paused`, in review), a creation job cancelled (`cancelled`). The
    * caller holds the round's lock.
    *
    * @return whether the row changed
    */
  def setStatus(id: Long, to: String, by: Option[Long] = None)(implicit session: DBSession): Boolean = {
    require(Status.All.contains(to), s"unknown round status $to")
    sql"""UPDATE round SET ${statusWrite(to, by)}, created_at = created_at
          WHERE id = $id AND NOT (status <=> $to)""".update() > 0
  }

  /** `status = to` with who and when, as every status write has them. */
  private def statusWrite(to: String, by: Option[Long]): SQLSyntax =
    sqls"""status_changed_at = IF(status <=> $to, status_changed_at, NOW(3)),
            status_changed_by = IF(status <=> $to, status_changed_by, $by),
            status = $to"""

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
          WHERE s.round_id = $roundId AND ${VoteSql.selected()}"""
      .map(_.int(1))
      .single()
      .getOrElse(0)

  /** A binary round's files with at least one approval (1), one vote yet to come (0) and one
    * rejection (-1): what its lists of all the jurors' votes filtered by each list
    * (docs/history/2026-11-01-binary-aggregate-votes.md, decision 3), a file in as many of them
    * as its votes are of kinds. One scan of the round's entries in
    * vote_round_page_rate_idx, without reading the rows.
    */
  def imageCountsByRate(roundId: Long): Map[Int, Int] =
    sql"""SELECT COALESCE(SUM(t.a > 0), 0), COALESCE(SUM(t.u > 0), 0), COALESCE(SUM(t.r > 0), 0) FROM (
            SELECT SUM(${VoteSql.positive()}) a, SUM(${VoteSql.unrated()}) u, SUM(${VoteSql.rejected()}) r FROM vote s
            WHERE s.round_id = $roundId GROUP BY s.page_id) t"""
      .map(rs => Map(Vote.Selected -> rs.int(1), Vote.Unrated -> rs.int(2), Vote.Rejected -> rs.int(3)))
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
      // NOT NULL since V108 (an old row's NULL became FALSE, stopped)
      rs.boolean(ru.active)
    )

  def apply(r: Round, u: User): Option[RoundUser] =
    for (rId <- r.id; uId <- u.id) yield RoundUser(rId, uId, u.contestRole.getOrElse(User.JURY_ROLE), active = true)

  def activeJurors(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId, "active" -> true).apply()

  def byRoundId(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId).apply()

  /** A round_user row `ru` (of round `r`) of a juror who can vote: `active` TRUE, the account
    * not deleted, and an active jury membership of the round's contest (the role exactly `jury`, as
    * [[User.contestMembers]] keeps it and [[services.distribution.RoundRebalanceService.jury]]
    * takes its candidates).
    */
  private def votingJurorCond: SQLSyntax =
    sqls"""ru.active = TRUE
           AND EXISTS (SELECT 1 FROM users u WHERE u.id = ru.user_id AND u.deleted_at IS NULL)
           AND EXISTS (SELECT 1 FROM user_contest uc WHERE uc.user_id = ru.user_id
                       AND uc.contest_id = r.contest_id AND BINARY uc.role = ${User.JURY_ROLE})"""

  /** The rounds' jurors who can vote ([[votingJurorCond]]) by round; a round with none is absent.
    * One grouped read.
    */
  def activeCounts(roundIds: Seq[Long])(implicit session: DBSession = AutoSession): Map[Long, Int] =
    if (roundIds.isEmpty) Map.empty
    else
      sql"""SELECT ru.round_id, COUNT(*) FROM round_user ru JOIN round r ON r.id = ru.round_id
            WHERE ru.round_id IN ($roundIds) AND $votingJurorCond
            GROUP BY ru.round_id""".map(rs => rs.long(1) -> rs.int(2)).list().toMap

  /** Of these rounds, as stored now, those open to jurors with no juror who can vote
    * ([[votingJurorCond]]): all their jurors paused, gone, or none. Open: not being created, and
    * active or frozen while active ([[Round.openToJurorsNowCond]], as [[Round.openToJurorsIds]]).
    * Nobody can vote in them (DG10: the staff are warned, nothing is refused): the Rounds table's
    * icon, the statistics page's notice, the flash after a start or a pause, health H12. One read.
    */
  def openWithoutActiveJuror(rounds: Seq[Round])(implicit session: DBSession = AutoSession): Set[Long] = {
    val ids = rounds.flatMap(_.id)
    if (ids.isEmpty) Set.empty
    else
      sql"""SELECT r.id FROM round r
            WHERE r.id IN ($ids) AND ${Round.openToJurorsNowCond}
              AND NOT EXISTS (SELECT 1 FROM round_user ru WHERE ru.round_id = r.id AND $votingJurorCond)"""
        .map(_.long(1)).list().toSet
  }

  /** Whether the user is stopped on the round: their round_user row is inactive (P6; an old
    * row with no recorded status was set inactive by V108). The juror's pages say so
    * ([[services.GalleryService.refusal]]), as [[Round.activeRounds(user*]] leaves the round out. They
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
