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
      val users = userDao.findAll()
      users.size === 0
    }

    "insert user" in new AutoRollbackDb {
      val user = User(
        fullname = "fullname",
        email = "email",
        id = None,
        roles = Set("jury"),
        password = Some("password hash"),
        contestId = Some(10),
        lang = Some("en"),
        createdAt = Some(now)
      )

      val created = userDao.create(user)

      val id = created.id

      val expected = user.copy(id = id, roles = user.roles ++ Set("USER_ID_" + id.get))
      created === expected

      val found = userDao.findById(id.get)
      found === Some(created)

      val all = userDao.findAll()
      all === Seq(created)
    }
  }
}
