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

  private def insertUser(fullname: String, email: String)
                        (implicit session: DBSession): Long =
    userDao.create(User(fullname, email, password = Some("hash"))).getId

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

    "not change the role of an existing member" in new AutoRollbackDb {
      createContests(5L)
      val userId = insertUser("Frank", "frank@example.com")
      UserContestJdbc.createMembership(userId, 5L, "admin") === true
      UserContestJdbc.createMembership(userId, 5L, "jury") === false

      UserContestJdbc.find(userId, 5L).map(_.role) === Some("admin")
    }

    "find the latest contest first, and split a role list" in new AutoRollbackDb {
      createContests(6L, 7L)
      val userId = insertUser("Gina", "gina@example.com")
      UserContestJdbc.createMembership(userId, 6L, "jury")
      UserContestJdbc.createMembership(userId, 7L, "jury, organizer")

      UserContestJdbc.findByUser(userId).map(m => (m.contestId, m.roles)) ===
        Seq((7L, Set("jury", "organizer")), (6L, Set("jury")))
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
