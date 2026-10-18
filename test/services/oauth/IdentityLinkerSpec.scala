package services.oauth

import db.scalikejdbc.{SharedTestDb, TestDb, User, UserIdentity, UserIdentityJdbc}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._

class IdentityLinkerSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def user(email: String, wikiAccount: Option[String] = None): User =
    User.create(User("name", email, password = Some("hash"), wikiAccount = wikiAccount))

  private def google(sub: String, email: String, verified: Boolean = true) =
    ExternalProfile(OAuthProviders.Google, sub, Some(email), verified)

  private def wikimedia(sub: String, username: String, email: Option[String] = None, confirmed: Boolean = false) =
    ExternalProfile(OAuthProviders.Wikimedia, sub, email, confirmed, Some(username))

  private def softDelete(u: User): Unit =
    DB.autoCommit { implicit s => sql"UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ${u.getId}".update.apply() }

  "resolve" should {
    "link a verified Google email to its account, then find it by subject" in {
      val alice = user("alice@example.com")

      IdentityLinker.resolve(google("g-1", "Alice@Example.com")).map(_.getId) === Right(alice.getId)
      UserIdentityJdbc.find("google", "g-1").map(_.userId) === Some(alice.getId)

      // the email changed at Google: the subject still signs in to the account
      IdentityLinker.resolve(google("g-1", "alice@new.example")).map(_.getId) === Right(alice.getId)
      UserIdentityJdbc.find("google", "g-1").flatMap(_.email) === Some("alice@new.example")
    }

    "not link an unverified Google email" in {
      user("alice@example.com")

      IdentityLinker.resolve(google("g-1", "alice@example.com", verified = false)) === Left(LinkError.NoAccount)
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "never create an account" in {
      user("alice@example.com")

      IdentityLinker.resolve(google("g-1", "stranger@example.com")) === Left(LinkError.NoAccount)
      User.findByEmail("stranger@example.com") must beEmpty
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "link a Wikimedia username to the wiki account, however organizers typed it" in {
      val a = user("a@example.com", Some("Jane doe"))
      val b = user("b@example.com", Some("john_Smith"))
      val c = user("c@example.com", Some("User:Ann Lee"))

      IdentityLinker.resolve(wikimedia("1", "Jane doe")).map(_.getId) === Right(a.getId)
      IdentityLinker.resolve(wikimedia("2", "John Smith")).map(_.getId) === Right(b.getId)
      IdentityLinker.resolve(wikimedia("3", "Ann Lee")).map(_.getId) === Right(c.getId)
    }

    "link a Wikimedia account by its confirmed email if no wiki account matches" in {
      val alice = user("alice@example.com")

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = false)) ===
        Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)).map(_.getId) ===
        Right(alice.getId)
    }

    "match the rest of a wiki account exactly, as MediaWiki does" in {
      user("a@example.com", Some("FooBar"))
      user("b@example.com", Some("José Foo"))

      IdentityLinker.resolve(wikimedia("1", "Foobar")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("2", "Jose Foo")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("3", "fooBar")).isRight must beTrue
    }

    "refuse a wiki account and an email of two different accounts" in {
      // anyone can set their own wiki account: it must not win over the signer's email
      user("alice@example.com")
      user("other@example.com", Some("Alice W"))

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)) ===
        Left(LinkError.Ambiguous)
      UserIdentityJdbc.find("wikimedia", "1") must beNone
    }

    "link a wiki account and an email of the same account" in {
      val alice = user("alice@example.com", Some("Alice W"))

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)).map(_.getId) ===
        Right(alice.getId)
    }

    "refuse a wiki account two accounts have" in {
      user("a@example.com", Some("Jane Doe"))
      user("b@example.com", Some("Jane_Doe"))

      IdentityLinker.resolve(wikimedia("1", "Jane Doe")) === Left(LinkError.Ambiguous)
      UserIdentityJdbc.find("wikimedia", "1") must beNone
    }

    "not sign in to a deleted account" in {
      val linked = user("linked@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", linked.getId))
      softDelete(linked)
      softDelete(user("deleted@example.com", Some("Gone")))

      IdentityLinker.resolve(google("g-1", "linked@example.com")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("1", "Gone")) === Left(LinkError.NoAccount)
    }
  }

  "link" should {
    "link an identity to the user, or keep it theirs" in {
      val alice = user("alice@example.com")

      IdentityLinker.link(alice.getId, google("g-1", "personal@gmail.com")) === Right(())
      IdentityLinker.link(alice.getId, google("g-1", "personal@gmail.com")) === Right(())
      UserIdentityJdbc.findByUser(alice.getId).map(_.subject) === Seq("g-1")
    }

    "refuse an identity linked to another user" in {
      val alice = user("alice@example.com")
      val bob   = user("bob@example.com")
      IdentityLinker.link(alice.getId, google("g-1", "personal@gmail.com"))

      IdentityLinker.link(bob.getId, google("g-1", "personal@gmail.com")) === Left(LinkError.LinkedToOther)
      UserIdentityJdbc.find("google", "g-1").map(_.userId) === Some(alice.getId)
    }

    "refuse a user who is gone as no account" in {
      IdentityLinker.link(Long.MaxValue, google("g-1", "personal@gmail.com")) === Left(LinkError.NoAccount)
      UserIdentityJdbc.find("google", "g-1") must beNone
    }
  }

  "canonicalWikiName" should {
    "write a username as MediaWiki does" in {
      IdentityLinker.canonicalWikiName(" jane__doe ") === "Jane doe"
      IdentityLinker.canonicalWikiName("User:ann_lee") === "Ann lee"
      IdentityLinker.canonicalWikiName("") === ""
    }
  }
}
