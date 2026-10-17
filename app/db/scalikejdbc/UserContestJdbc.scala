package db.scalikejdbc

import scalikejdbc._
import scalikejdbc.orm.JoinTable

/** A user's membership in a contest, with their role in it (jury, organizer, admin, ...). */
case class UserContest(userId: Long, contestId: Long, role: String) {

  /** users.roles could hold a comma-separated list; V62 copied it as written. */
  def roles: Set[String] = role.split(",").map(_.trim).filter(_.nonEmpty).toSet
}

object UserContestJdbc extends JoinTable[UserContest] {
  override val tableName = "user_contest"
  override lazy val defaultAlias = createAlias("uc")

  override def extract(rs: WrappedResultSet, n: ResultName[UserContest]): UserContest =
    UserContest(
      userId    = rs.long(n.userId),
      contestId = rs.long(n.contestId),
      role      = rs.string(n.role)
    )

  /** The user's memberships, the latest contest first. */
  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("userId" -> userId).orderBy(defaultAlias.contestId.desc).apply()

  def find(userId: Long, contestId: Long)(implicit session: DBSession = AutoSession): Option[UserContest] =
    where("userId" -> userId, "contestId" -> contestId).apply().headOption

  def findByContest(contestId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("contestId" -> contestId).apply()

  /** Adds the user to the contest with the role; false, changing nothing, if already a
    * member of it (their role there stays: [[updateRole]] changes it).
    */
  def createMembership(userId: Long, contestId: Long, role: String)
                      (implicit session: DBSession = AutoSession): Boolean =
    sql"""INSERT IGNORE INTO user_contest (user_id, contest_id, role)
          VALUES ($userId, $contestId, $role)""".update.apply() > 0

  def updateRole(userId: Long, contestId: Long, role: String)
                (implicit session: DBSession = AutoSession): Unit =
    updateBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
      .withAttributes("role" -> role)

  def delete(userId: Long, contestId: Long)
            (implicit session: DBSession = AutoSession): Unit =
    deleteBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
}
