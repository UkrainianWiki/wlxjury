package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc._

import java.sql.SQLIntegrityConstraintViolationException

class UserIdentityJdbcSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  private def insertUser(email: String)(implicit session: DBSession): Long =
    userDao.create(User("name", email, password = Some("hash"))).getId

  "UserIdentityJdbc" should {
    "create and find an identity" in new AutoRollbackDb {
      val userId = insertUser("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", userId, Some("alice@gmail.com")))

      UserIdentityJdbc.find("google", "g-1") === Some(UserIdentity("google", "g-1", userId, Some("alice@gmail.com")))
      UserIdentityJdbc.find("wikimedia", "g-1") must beNone
      UserIdentityJdbc.findByUser(userId).map(_.subject) === Seq("g-1")
    }

    "refuse an identity linked already, even to the same user" in new AutoRollbackDb {
      val alice = insertUser("alice@example.com")
      val bob   = insertUser("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("wikimedia", "42", alice))

      UserIdentityJdbc.create(UserIdentity("wikimedia", "42", bob)) must throwA[SQLIntegrityConstraintViolationException]
      UserIdentityJdbc.find("wikimedia", "42").map(_.userId) === Some(alice)
    }

    "keep several identities of a user, of any provider" in new AutoRollbackDb {
      val userId = insertUser("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", userId))
      UserIdentityJdbc.create(UserIdentity("google", "g-2", userId))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "42", userId, username = Some("Alice")))

      UserIdentityJdbc.findByUser(userId).map(i => i.provider -> i.subject) ===
        Seq("google" -> "g-1", "google" -> "g-2", "wikimedia" -> "42")
    }

    "update the email and username on touch" in new AutoRollbackDb {
      val userId = insertUser("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("wikimedia", "42", userId, username = Some("Old name")))

      UserIdentityJdbc.touch(UserIdentity("wikimedia", "42", userId, Some("a@b.org"), Some("New name")))

      UserIdentityJdbc.find("wikimedia", "42").map(i => (i.email, i.username)) === Some((Some("a@b.org"), Some("New name")))
    }

    "delete only the user's own identity" in new AutoRollbackDb {
      val alice = insertUser("alice@example.com")
      val bob   = insertUser("bob@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", alice))

      UserIdentityJdbc.delete(bob, "google", "g-1") must beFalse
      UserIdentityJdbc.find("google", "g-1") must beSome

      UserIdentityJdbc.delete(alice, "google", "g-1") must beTrue
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "go with its user" in new AutoRollbackDb {
      val userId = insertUser("alice@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", userId))

      userDao.deleteById(userId)

      UserIdentityJdbc.find("google", "g-1") must beNone
    }
  }
}
