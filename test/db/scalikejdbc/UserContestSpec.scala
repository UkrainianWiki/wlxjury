package db.scalikejdbc

import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

class UserContestSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
  }

  /** Insert a user directly, bypassing the legacy `roles`/`contest_id` columns
   *  that were dropped by V51 but are still referenced by User.create(user). */
  private def insertUser(fullname: String, email: String)
                        (implicit session: DBSession): Long =
    sql"""INSERT INTO users (fullname, email, password, created_at)
          VALUES ($fullname, $email, 'hash', NOW())"""
      .updateAndReturnGeneratedKey
      .apply()

  "UserContestJdbc" should {
    "create and find membership" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(1L), "WLE", 2024, "Ukraine")
      val userId = insertUser("Alice", "alice@example.com")
      UserContestJdbc.createMembership(userId, 1L, "jury")

      val memberships = UserContestJdbc.findByUser(userId)
      memberships.map(_.role) === Seq("jury")
      memberships.map(_.contestId) === Seq(1L)
    }

    "find by contest" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(2L), "WLM", 2024, "Ukraine")
      val userId1 = insertUser("Bob", "bob@example.com")
      val userId2 = insertUser("Carol", "carol@example.com")
      UserContestJdbc.createMembership(userId1, 2L, "jury")
      UserContestJdbc.createMembership(userId2, 2L, "organizer")

      val memberships = UserContestJdbc.findByContest(2L)
      memberships.map(_.userId).sorted === Seq(userId1, userId2).sorted
    }

    "update role" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(3L), "WLE", 2023, "Poland")
      val userId = insertUser("Dave", "dave@example.com")
      UserContestJdbc.createMembership(userId, 3L, "jury")
      UserContestJdbc.updateRole(userId, 3L, "organizer")

      UserContestJdbc.findByUser(userId).map(_.role) === Seq("organizer")
    }

    "delete membership" in new AutoRollbackDb {
      implicit val contest: ContestJury =
        contestDao.create(Some(4L), "WLM", 2023, "Poland")
      val userId = insertUser("Eve", "eve@example.com")
      UserContestJdbc.createMembership(userId, 4L, "jury")
      UserContestJdbc.delete(userId, 4L)

      UserContestJdbc.findByUser(userId) === Nil
    }
  }
}
