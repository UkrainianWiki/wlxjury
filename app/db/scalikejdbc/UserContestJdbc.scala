package db.scalikejdbc

import scalikejdbc._
import scalikejdbc.orm.JoinTable

/** A user's membership in a contest, with their role in it (jury, organizer, admin, ...). */
case class UserContest(userId: Long, contestId: Long, role: String) {

  /** The role, if exactly one allowlisted value ([[User.ContestRoles]]); else none, and the
    * membership is inactive. Never split, trimmed or case-folded: a stored `jury,root` or
    * `Root` grants nothing (root comes only from users.is_root).
    */
  lazy val roles: Set[String] =
    if (User.isContestRole(role)) Set(role)
    else {
      UserContest.log.warn(s"ignored a membership role that is not a contest role accountId=$userId contestId=$contestId")
      Set.empty
    }

  /** False for the edit form's "no" role and the roles without access (board, auditor,
    * staff: [[User.NON_ACCESS_ROLES]]): the user stays on the contest's user list, where
    * an admin can give them a role again, but the contest is not theirs to open (not
    * picked at login, not in the contest switcher, never the contest a request runs in).
    */
  def active: Boolean = (roles -- User.INACTIVE_ROLES).nonEmpty
}

object UserContest {
  private[scalikejdbc] val log = _root_.play.api.Logger("security.roles")
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
                      (implicit session: DBSession = AutoSession): Boolean = {
    require(User.isContestRole(role), "not a contest role")
    sql"""INSERT IGNORE INTO user_contest (user_id, contest_id, role)
          VALUES ($userId, $contestId, $role)""".update.apply() > 0
  }

  /** Adds the user to the contest with the role, or sets their role there if a member: an
    * accepted invitation's role ([[services.AccountTokenService.acceptPasswordLink]]).
    */
  def setMembership(userId: Long, contestId: Long, role: String)
                   (implicit session: DBSession = AutoSession): Unit = {
    require(User.isContestRole(role), "not a contest role")
    sql"""INSERT INTO user_contest (user_id, contest_id, role) VALUES ($userId, $contestId, $role)
          ON DUPLICATE KEY UPDATE role = VALUES(role)""".update.apply()
  }

  /** Sets the member's role: one of [[User.ContestRoles]] only. */
  def updateRole(userId: Long, contestId: Long, role: String)
                (implicit session: DBSession = AutoSession): Unit = {
    require(User.isContestRole(role), "not a contest role")
    updateBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
      .withAttributes("role" -> role)
  }

  def delete(userId: Long, contestId: Long)
            (implicit session: DBSession = AutoSession): Unit =
    deleteBy(sqls.eq(column.userId, userId).and.eq(column.contestId, contestId))
}
