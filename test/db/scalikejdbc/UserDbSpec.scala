package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

class UserDbSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
  }

  "fresh database" should {
    "be empty" in new AutoRollbackDb {
      userDao.findAll() must beEmpty
    }

    "create and reload user without contest" in new AutoRollbackDb {
      val user = User(
        fullname  = "fullname",
        email     = "email",
        roles     = Set.empty,
        password  = Some("password hash"),
        lang      = Some("en"),
        createdAt = Some(now)
      )
      val created = userDao.create(user)
      val id = created.id.get

      val found = userDao.findById(id)
      found.map(_.fullname)  === Some("fullname")
      found.map(_.email)     === Some("email")
      found.map(_.isRoot)    === Some(false)
      // roles from DB only contains USER_ID_xxx (contest role added by userFromRequest)
      found.map(_.roles)     === Some(Set("USER_ID_" + id))
      found.map(_.contestId) === Some(None)
    }

    "create a contest member, found with the role in that contest only" in new AutoRollbackDb {
      createContests(10, 20)
      val created = userDao.create(User("juror", "juror@example.com", None, Set("jury"), Some("hash"), Some(10L)))
      UserContestJdbc.createMembership(created.getId, 20L, "organizer")

      UserContestJdbc.findByUser(created.getId) ===
        Seq(UserContest(created.getId, 20, "organizer"), UserContest(created.getId, 10, "jury"))
      userDao.findByContest(10).map(u => (u.id, u.contestId, u.contestRoles)) ===
        Seq((created.id, Some(10L), Set("jury")))
      userDao.loadJurors(20) must beEmpty
      userDao.findInContest(created.getId, 20).map(_.contestRoles) === Some(Set("organizer"))
      userDao.findInContest(created.getId, 30) === None
    }

    "make a user created with the root role root, without a membership" in new AutoRollbackDb {
      createContests(10)
      val root = userDao.create(User("root", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash"), Some(10L)))
      userDao.findById(root.getId).map(_.isRoot) === Some(true)
      UserContestJdbc.findByUser(root.getId) must beEmpty
    }

    "mark root user" in new AutoRollbackDb {
      val root = userDao.create(
        User("root", "root@example.com", isRoot = true, password = Some("hash"))
      )
      val found = userDao.findById(root.getId)
      found.map(_.isRoot) === Some(true)
      found.map(_.roles)  === Some(Set(User.ROOT_ROLE, "USER_ID_" + root.getId))
    }
  }
}
