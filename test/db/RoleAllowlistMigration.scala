package db

import db.scalikejdbc.User
import _root_.scalikejdbc._

import java.nio.file.{Files, Paths}

/** V76 (user_contest.role: exactly one contest role, a binary-exact CHECK constraint) for the
  * specs that need a stored role the constraint refuses: drop it, insert the row, and
  * [[restore]] the constraint afterwards.
  */
object RoleAllowlistMigration {

  val Script = "conf/db/migration/default/V76__User_contest_role_allowlist.sql"
  val Constraint = "chk_user_contest_role"

  def statements: Seq[String] = {
    val sql = new String(Files.readAllBytes(Paths.get(Script)), "UTF-8")
    sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq
  }

  /** Runs V76 as Flyway does, one statement after another. */
  def run(): Unit =
    DB.autoCommit { implicit session => statements.foreach(statement => SQL(statement).execute.apply()) }

  def dropConstraint(): Unit =
    DB.autoCommit { implicit session =>
      SQL(s"ALTER TABLE user_contest DROP CONSTRAINT IF EXISTS $Constraint").execute.apply()
    }

  /** A membership row as a DB browser would write it, past the DAO's and the table's checks. */
  def insertRaw(userId: Long, contestId: Long, role: String): Unit = {
    dropConstraint()
    DB.autoCommit { implicit session =>
      sql"INSERT INTO user_contest (user_id, contest_id, role) VALUES ($userId, $contestId, $role)".update.apply()
    }
  }

  def constraintExists(): Boolean =
    DB.readOnly { implicit session =>
      sql"""SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS
            WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'user_contest'
              AND CONSTRAINT_NAME = $Constraint AND CONSTRAINT_TYPE = 'CHECK'"""
        .map(_.int(1)).single().contains(1)
    }

  /** Deletes the rows the constraint refuses, then runs V76: the constraint is back. */
  def restore(): Unit = {
    DB.autoCommit { implicit session =>
      sql"DELETE FROM user_contest WHERE BINARY role NOT IN (${User.ContestRoles.toSeq})".update.apply()
    }
    run()
  }
}
