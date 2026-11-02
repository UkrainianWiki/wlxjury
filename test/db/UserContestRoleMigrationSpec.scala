package db

import db.scalikejdbc.{SharedTestDb, TestDb, User}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAfterEach, BeforeAll}
import _root_.scalikejdbc._

import java.sql.SQLException

/** V76: user_contest.role is exactly one contest role, byte for byte. The hand-disabled
  * jurors ('jury1', 'jury-') become 'no'; any other value fails the migration, for a person to
  * look at; the CHECK constraint refuses a bad role from then on, root, case and trailing-space
  * variants included.
  */
class UserContestRoleMigrationSpec extends Specification with TestDb with BeforeAll with BeforeAfterEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()
  override protected def after: Any = RoleAllowlistMigration.restore()

  private def userIn(n: Int): Long =
    User.create(User(s"user$n", s"user$n@example.com", None, Set.empty, Some("hash"))).getId

  private def roles(): List[(Long, Long, String)] =
    DB.readOnly { implicit session =>
      sql"SELECT user_id, contest_id, role FROM user_contest ORDER BY user_id, contest_id"
        .map(rs => (rs.long(1), rs.long(2), rs.string(3))).list()
    }

  private def insert(userId: Long, contestId: Long, role: String): Unit =
    DB.autoCommit { implicit session =>
      sql"INSERT INTO user_contest (user_id, contest_id, role) VALUES ($userId, $contestId, $role)".update.apply()
    }

  /** The bad values, as a DB browser could store them. */
  private val badRoles = Seq(
    "root", "Root", "ROOT", "root ", " root", "jury,root", "jury, root", "admin,root", "USER_ID_1",
    "jury1", "jury-", "Jury", "JURY", "jury ", "admin ", "prejury", "superuser", ""
  )

  "the constraint" should {
    "exist after the migrations" in {
      RoleAllowlistMigration.constraintExists() must beTrue
    }

    "hold the same roles as User.ContestRoles" in {
      createContests(10)
      val users = User.ContestRoles.toSeq.zipWithIndex.map { case (_, i) => userIn(i) }
      User.ContestRoles.toSeq.zip(users).foreach { case (role, user) => insert(user, 10, role) }
      roles().map(_._3).toSet === User.ContestRoles
    }

    "refuse every other value, byte for byte, on insert and on update" in {
      createContests(10)
      val user = userIn(1)
      val refused = badRoles.filter(role => scala.util.Try(insert(user, 10, role)).isFailure)
      insert(user, 10, "jury")
      val refusedUpdates = badRoles.filter { role =>
        scala.util.Try(DB.autoCommit { implicit s =>
          sql"UPDATE user_contest SET role = $role WHERE user_id = $user".update.apply()
        }).isFailure
      }
      (refused === badRoles) and (refusedUpdates === badRoles) and (roles() === List((user, 10L, "jury")))
    }
  }

  "the migration" should {
    "set the hand-disabled jurors to no and leave the other roles as they are" in {
      createContests(10, 20)
      val Seq(a, b, c, d, e) = (1 to 5).map(userIn)
      RoleAllowlistMigration.dropConstraint()
      insert(a, 10, "jury1")
      insert(a, 20, "jury")
      insert(b, 10, "jury-")
      insert(c, 10, "admin")
      insert(d, 10, "no")
      insert(e, 20, "board")

      RoleAllowlistMigration.run()

      (roles() === List((a, 10L, "no"), (a, 20L, "jury"), (b, 10L, "no"), (c, 10L, "admin"),
        (d, 10L, "no"), (e, 20L, "board"))) and
        (RoleAllowlistMigration.constraintExists() must beTrue)
    }

    "fail on any other value, keeping it for a person to look at" in {
      createContests(10)
      val examples = Seq("jury,root", "root", "Root", "root ", "Jury", "jury ", "USER_ID_1", "prejury", "")
      examples.map { role =>
        SharedTestDb.truncateAll()
        createContests(10)
        val Seq(a, b) = (1 to 2).map(userIn)
        RoleAllowlistMigration.dropConstraint()
        insert(a, 10, role)
        insert(b, 10, "jury1")
        val failed = RoleAllowlistMigration.run() must throwA[SQLException]
        val kept = roles().find(_._1 == a).map(_._3) === Some(role)
        val noConstraint = RoleAllowlistMigration.constraintExists() must beFalse
        failed and kept and noConstraint
      }.reduce(_ and _)
    }

    "change nothing when run again" in {
      createContests(10)
      val a = userIn(1)
      insert(a, 10, "organizer")
      RoleAllowlistMigration.run()
      RoleAllowlistMigration.run()
      (roles() === List((a, 10L, "organizer"))) and (RoleAllowlistMigration.constraintExists() must beTrue)
    }
  }
}
