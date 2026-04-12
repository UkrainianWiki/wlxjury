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
