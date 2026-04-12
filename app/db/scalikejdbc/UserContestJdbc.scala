package db.scalikejdbc

import scalikejdbc._
import scalikejdbc.orm.JoinTable

case class UserContest(userId: Long, contestId: Long, role: String)

object UserContestJdbc extends JoinTable[UserContest] {
  override val tableName = "user_contest"
  override lazy val defaultAlias = createAlias("uc")

  override def extract(rs: WrappedResultSet, n: ResultName[UserContest]): UserContest =
    UserContest(
      userId    = rs.long(n.userId),
      contestId = rs.long(n.contestId),
      role      = rs.string(n.role)
    )

  def findByUser(userId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("userId" -> userId).apply()

  def findByContest(contestId: Long)(implicit session: DBSession = AutoSession): Seq[UserContest] =
    where("contestId" -> contestId).apply()

  def createMembership(userId: Long, contestId: Long, role: String)
                      (implicit session: DBSession = AutoSession): Unit =
    withSQL {
      insert.into(UserContestJdbc).namedValues(
        column.userId    -> userId,
        column.contestId -> contestId,
        column.role      -> role
      )
    }.update.apply()

  def updateRole(userId: Long, contestId: Long, role: String)
                (implicit session: DBSession = AutoSession): Unit =
    updateBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
      .withAttributes("role" -> role)

  def delete(userId: Long, contestId: Long)
            (implicit session: DBSession = AutoSession): Unit =
    deleteBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
}
