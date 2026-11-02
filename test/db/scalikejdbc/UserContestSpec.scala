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

    "find the latest contest first" in new AutoRollbackDb {
      createContests(6L, 7L)
      val userId = insertUser("Gina", "gina@example.com")
      UserContestJdbc.createMembership(userId, 6L, "jury")
      UserContestJdbc.createMembership(userId, 7L, "organizer")

      UserContestJdbc.findByUser(userId).map(m => (m.contestId, m.roles)) ===
        Seq((7L, Set("organizer")), (6L, Set("jury")))
    }

    "refuse to store anything but one contest role" in new AutoRollbackDb {
      createContests(8L)
      val userId = insertUser("Hal", "hal@example.com")
      UserContestSpec.badRoles.foreach { role =>
        UserContestJdbc.createMembership(userId, 8L, role) must throwA[IllegalArgumentException]
      }
      UserContestJdbc.findByUser(userId) === Nil

      UserContestJdbc.createMembership(userId, 8L, "jury")
      UserContestSpec.badRoles.foreach { role =>
        UserContestJdbc.updateRole(userId, 8L, role) must throwA[IllegalArgumentException]
      }
      UserContestJdbc.find(userId, 8L).map(_.role) === Some("jury")
    }

    "not create a user with a membership role that is not a contest role" in new AutoRollbackDb {
      createContests(9L)
      UserContestSpec.badRoles.filterNot(r => r == User.ROOT_ROLE || r.startsWith(User.USER_ID_PREFIX)).foreach { role =>
        userDao.create(User("Ivy", "ivy@example.com", None, Set(role), Some("hash"), Some(9L))) must
          throwA[IllegalArgumentException]
      }
      userDao.findByEmail("ivy@example.com") === Nil
      UserContestJdbc.findByContest(9L) === Nil
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

/** The membership reader: one allowlisted role, compared exactly; anything else grants nothing. */
class UserContestRolesSpec extends Specification {

  "UserContest.roles" should {
    "be the role for each contest role" in {
      User.ContestRoles.toSeq.map(r => UserContest(1, 2, r).roles) === User.ContestRoles.toSeq.map(Set(_))
    }

    "be empty, and the membership inactive, for anything else" in {
      UserContestSpec.badRoles.map(r => r -> UserContest(1, 2, r).roles).filter(_._2.nonEmpty) === Nil
      UserContestSpec.badRoles.filter(r => UserContest(1, 2, r).active) === Nil
    }
  }

  "User.inContest" should {
    "never take root or a USER_ID_ marker from a membership" in {
      val user = User("Kim", "kim@example.com", Some(5), roles = Set("USER_ID_5"))
      UserContestSpec.badRoles.map { r =>
        r -> user.inContest(UserContest(5, 2, r)).roles
      }.filterNot(_._2 == Set("USER_ID_5")) === Nil
      user.inContest(UserContest(5, 2, "jury")).roles === Set("USER_ID_5", "jury")
    }
  }
}

object UserContestSpec {
  /** Values that are not exactly one contest role: N1's root via a list, a marker, variants. */
  val badRoles: Seq[String] = Seq(
    "root", "jury,root", "jury, root", "admin,root", " root", "root ", "Root", "ROOT", "USER_ID_1",
    "jury1", "jury-", "Jury", "jury ", " jury", "admin ", "prejury", "superuser", ""
  )
}
