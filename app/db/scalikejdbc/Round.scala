package db.scalikejdbc

import db.RoundRepo
import org.intracer.wmua.{HasId, ImageWithRating}
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
    previous: Option[Long] = None,
    prevSelectedBy: Option[Int] = None,
    prevMinAvgRate: Option[BigDecimal] = None,
    category: Option[String] = None,
    excludeCategory: Option[String] = None,
    categoryClause: Option[Int] = None,
    regions: Option[String] = None,
    minImageSize: Option[Int] = None,
    hasCriteria: Boolean = false,
    halfStar: Boolean = false,
    monuments: Option[String] = None,
    topImages: Option[Int] = None,
    specialNomination: Option[String] = None,
    users: Seq[User] = Nil,
    criteriaNames: Seq[String] = Nil,
    mediaType: Option[String] = None
) extends HasId {

  def availableJurors(implicit session: DBSession = AutoSession): Seq[User] =
    User.findAllBy(
      sqls.in(User.u.roles, roles.toSeq).and.eq(User.u.contestId, contestId)
    )

  lazy val numberOfActiveJurors = SelectionJdbc.activeJurors(id.get)

  lazy val numberOfAssignedJurors = SelectionJdbc.allJurors(id.get)

  def numberOfJurorsForAverageRate: Long =
    if (!optionalRate) numberOfAssignedJurors else numberOfActiveJurors

  def allImages: Seq[ImageWithRating] = ImageJdbc.byRoundMerged(id.get)

  def description: String =
    name
      .filter(_.trim.nonEmpty)
      .getOrElse(number.toString)

  def isBinary: Boolean = rates.id == Round.binaryRound.id

  def numberOfStars: Int =
    (if (rates.id <= 5 || !halfStar) rates.id
     else rates.id / 2) * (if (hasCriteria) (if (!id.contains(1327)) 4 else 2) else 1)

  def regionIds: Seq[String] = regions.map(_.split(",").toSeq).getOrElse(Nil)

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
      category = category.map(fixCategory),
      excludeCategory = excludeCategory.map(fixCategory)
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

  def addUsers(users: Seq[RoundUser]): Unit = {
    DB localTx { implicit session =>
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
    }
  }

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

  override val tableName = "rounds"

  lazy val c = Round.syntax("c")

  lazy val s = SelectionJdbc.syntax("s")

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
      previous = rs.longOpt(c.previous),
      prevSelectedBy = rs.intOpt(c.prevSelectedBy),
      prevMinAvgRate = rs.bigDecimalOpt(c.prevMinAvgRate).map(BigDecimal.apply),
      category = rs.stringOpt(c.category),
      categoryClause = rs.intOpt(c.categoryClause),
      regions = rs.stringOpt(c.regions),
      minImageSize = rs.intOpt(c.minImageSize),
      hasCriteria = rs.booleanOpt(c.hasCriteria).getOrElse(false),
      halfStar = rs.booleanOpt(c.halfStar).getOrElse(false),
      monuments = rs.stringOpt(c.monuments),
      topImages = rs.intOpt(c.topImages),
      specialNomination = rs.stringOpt(c.specialNomination),
      mediaType = rs.stringOpt(c.mediaType)
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
          column.previous -> round.previous,
          column.prevSelectedBy -> round.prevSelectedBy,
          column.prevMinAvgRate -> round.prevMinAvgRate,
          column.category -> round.category,
          column.categoryClause -> round.categoryClause,
          column.regions -> round.regions,
          column.minImageSize -> round.minImageSize,
          column.halfStar -> round.halfStar,
          column.monuments -> round.monuments,
          column.topImages -> round.topImages,
          column.specialNomination -> round.specialNomination,
          column.mediaType -> round.mediaType
        )
    }.updateAndReturnGeneratedKey()

    round.copy(id = Some(id))
  }

  def updateRound(id: Long, round: Round): Int =
    updateById(id)
      .withAttributes(
        "name" -> round.name,
        "active" -> round.active
      )

  def activeRounds(contestId: Long)(implicit session: DBSession = AutoSession): Seq[Round] =
    where("contestId" -> contestId, "active" -> true)
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

  def setActive(id: Long, active: Boolean): Unit =
    updateById(id)
      .withAttributes("active" -> active)

  def setInactiveAllInContest(contestId: Long): Unit =
    withSQL {
      update(Round)
        .set(column.active -> false)
        .where
        .eq(column.contestId, contestId)
    }.update()

  def countByContest(contestId: Long): Long =
    countBy(sqls.eq(r.contestId, contestId))

  def delete(roundIds: Seq[Long]): Int = {
    deleteBy(sqls.in(r.id, roundIds))
  }

  case class RoundStatRow(juror: Long, rate: Int, count: Int)

  def roundUserStat(roundId: Long): Seq[RoundStatRow] =
    sql"""SELECT u.id, s.rate, count(1) FROM users u
      JOIN selection s ON s.jury_id = u.id
    WHERE s.round_id = $roundId
    GROUP BY u.id, s.rate"""
      .map(rs => RoundStatRow(rs.int(1), rs.int(2), rs.int(3)))
      .list()

  def roundRateStat(roundId: Long): Seq[(Int, Int)] =
    sql"""SELECT rate, count(1) FROM
(SELECT DISTINCT s.page_id, s.rate FROM users u
  JOIN selection s ON s.jury_id = u.id
  WHERE s.round_id = $roundId) t
  GROUP BY rate""".map(rs => (rs.int(1), rs.int(2))).list()

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
    for (rId <- r.id; uId <- u.id) yield RoundUser(rId, uId, u.roles.head, active = true)

  def activeJurors(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId, "active" -> true).apply()

  def byRoundId(roundId: Long): List[RoundUser] =
    where("roundId" -> roundId).apply()

  def setActive(roundId: Long, userId: Long, active: Boolean): Int =
    updateBy(sqls.eq(ru.roundId, roundId).and.eq(ru.userId, userId))
      .withAttributes("active" -> active)
}
