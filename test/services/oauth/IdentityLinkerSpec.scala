package services.oauth

import db.scalikejdbc.{AccountTokenJdbc, SharedTestDb, TestDb, User, UserContestJdbc, UserIdentity, UserIdentityJdbc}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.AccountTokenService

import java.time.ZonedDateTime

/** The original's IdentityLinkerSpec (oauth-login, 97cf9d07 + 405f6a7e), ported to S2's account
  * rules: a first sign-in links only to an account nobody else may hold.
  */
class IdentityLinkerSpec extends Specification with TestDb with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  /** An account whose holder proved its email (an accepted invitation): it has a password. */
  private def user(email: String, wikiAccount: Option[String] = None): User =
    User.create(User("name", email, password = Some("hash"), wikiAccount = wikiAccount, emailVerifiedAt = Some(ZonedDateTime.now)))

  /** An account with a password and an email nobody proved (a pre-S2 sign-up, say). */
  private def unverified(email: String, wikiAccount: Option[String] = None): User =
    User.create(User("name", email, password = Some("hash"), wikiAccount = wikiAccount))

  /** An invitee who has not accepted yet: no password. */
  private def invitee(email: String, wikiAccount: Option[String] = None): User =
    User.create(User("name", email, password = None, wikiAccount = wikiAccount))

  private def verifyWiki(u: User): User = {
    DB.autoCommit { implicit s => sql"UPDATE users SET wiki_account_verified_at = NOW(3) WHERE id = ${u.getId}".update.apply() }
    u
  }

  /** A live invitation of the account to `email` into contest 10 as a juror, sent by root. */
  private def invite(u: User, email: String): Long = {
    val root = User.findByEmail("root@example.com").headOption
      .getOrElse(User.create(User("root", "root@example.com", isRoot = true, emailVerifiedAt = Some(ZonedDateTime.now))))
    AccountTokenService.create(u.getId, AccountTokenJdbc.Invitation, email, Some(10L), Some("jury"), root.id)._1
  }

  private def wikiVerified(u: User): Boolean = User.findById(u.getId).exists(_.wikiAccountVerified)

  /** A Google profile; a Workspace account's (an `hd` claim) unless `hd` says otherwise (M4). */
  private def google(sub: String, email: String, verified: Boolean = true, hd: Option[String] = Some("example.com")) =
    ExternalProfile(OAuthProviders.Google, sub, Some(email), verified, hostedDomain = hd)

  private def wikimedia(sub: String, username: String, email: Option[String] = None, confirmed: Boolean = false) =
    ExternalProfile(OAuthProviders.Wikimedia, sub, email, confirmed, Some(username))

  private def softDelete(u: User): Unit =
    DB.autoCommit { implicit s => sql"UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ${u.getId}".update.apply() }

  "resolve" should {
    "link a verified Google email to its verified account, then find it by subject" in {
      val alice = user("alice@example.com")

      IdentityLinker.resolve(google("g-1", "Alice@Example.com")).map(_.getId) === Right(alice.getId)
      UserIdentityJdbc.find("google", "g-1").map(_.userId) === Some(alice.getId)

      // the email changed at Google: the subject still signs in to the account
      IdentityLinker.resolve(google("g-1", "alice@new.example")).map(_.getId) === Right(alice.getId)
      UserIdentityJdbc.find("google", "g-1").flatMap(_.email) === Some("alice@new.example")
    }

    "accept an invitee's live invitation by the provider-verified address (decision 1, B3)" in {
      createContests(10L)
      val bob = invitee("bob@example.com")
      val invitation = invite(bob, "bob@example.com")

      IdentityLinker.resolve(google("g-2", "bob@example.com")).map(_.getId) === Right(bob.getId)
      val reloaded = User.findById(bob.getId).get
      reloaded.emailVerified must beTrue
      reloaded.authVersion !== bob.authVersion
      AccountTokenJdbc.find(invitation).flatMap(_.usedAt) must beSome
      UserContestJdbc.find(bob.getId, 10L).map(_.role) === Some("jury")
      UserIdentityJdbc.find("google", "g-2").map(_.userId) === Some(bob.getId)
    }

    "not link an invitee without a live invitation: none, revoked, expired, or to another address" in {
      createContests(10L)
      val bob = invitee("bob@example.com")
      IdentityLinker.resolve(google("g-2", "bob@example.com")) === Left(LinkError.Unverified)

      val revoked = invite(bob, "bob@example.com")
      AccountTokenJdbc.revokeUnused(bob.getId, AccountTokenJdbc.AllPurposes)
      IdentityLinker.resolve(google("g-2", "bob@example.com")) === Left(LinkError.Unverified)

      val expired = invite(bob, "bob@example.com")
      DB.autoCommit { implicit s => sql"UPDATE account_token SET expires_at = NOW(3) - INTERVAL 1 SECOND WHERE id = $expired".update.apply() }
      IdentityLinker.resolve(google("g-2", "bob@example.com")) === Left(LinkError.Unverified)

      // an invitation sent to another address (before an email fix) is not this one's
      val other = invite(bob, "old@example.com")
      IdentityLinker.resolve(google("g-2", "bob@example.com")) === Left(LinkError.Unverified)
      AccountTokenJdbc.find(other).flatMap(_.usedAt) must beNone
      UserContestJdbc.find(bob.getId, 10L) must beNone
      User.findById(bob.getId).get.emailVerified must beFalse
      UserIdentityJdbc.find("google", "g-2") must beNone
      revoked !== expired
    }

    "not link an account with a password and an unverified email (S2's pre-claim)" in {
      // whoever registered the address without owning it holds the password
      unverified("alice@example.com")

      IdentityLinker.resolve(google("g-1", "alice@example.com")) === Left(LinkError.Unverified)
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "take Google's verified email as proof only for Gmail or a Workspace account (M4, the user's decision)" in {
      createContests(10L)
      val gmail = user("someone@gmail.com")
      IdentityLinker.resolve(google("g-1", "Someone@GMail.com", hd = None)).map(_.getId) === Right(gmail.getId)

      val work = user("ann@example.org")
      IdentityLinker.resolve(google("g-2", "ann@example.org", hd = Some("example.org"))).map(_.getId) === Right(work.getId)

      // no hd, not Gmail: refused even with email_verified, and nothing linked or verified
      val other = invitee("bob@example.net")
      invite(other, "bob@example.net")
      IdentityLinker.resolve(google("g-3", "bob@example.net", hd = None)) === Left(LinkError.EmailNotAuthoritative)
      user("carol@example.net")
      IdentityLinker.resolve(google("g-4", "carol@example.net", hd = None)) === Left(LinkError.EmailNotAuthoritative)
      UserIdentityJdbc.find("google", "g-3") must beNone
      UserIdentityJdbc.find("google", "g-4") must beNone
      User.findById(other.getId).get.emailVerified must beFalse

      // email_verified false: refused, even for Gmail
      IdentityLinker.resolve(google("g-5", "someone@gmail.com", verified = false, hd = None)) === Left(LinkError.NoAccount)
      UserIdentityJdbc.find("google", "g-5") must beNone
    }

    "not link an unverified Google email" in {
      user("alice@example.com")

      IdentityLinker.resolve(google("g-1", "alice@example.com", verified = false)) === Left(LinkError.NoAccount)
      UserIdentityJdbc.find("google", "g-1") must beNone
    }

    "never create an account for an uninvited email or wiki user" in {
      user("alice@example.com")
      val before = User.count()

      IdentityLinker.resolve(google("g-1", "stranger@example.com")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("9", "Stranger", Some("stranger@example.org"), confirmed = true)) ===
        Left(LinkError.NoAccount)
      User.count() === before
      User.findByEmail("stranger@example.com") must beEmpty
      UserIdentityJdbc.find("google", "g-1") must beNone
      UserIdentityJdbc.find("wikimedia", "9") must beNone
    }

    "link a Wikimedia username to a placeholder's wiki account, however organizers typed it" in {
      val a = invitee("", Some("Jane doe"))
      val b = invitee("", Some("john_Smith"))
      val c = invitee("", Some("User:Ann Lee"))

      IdentityLinker.resolve(wikimedia("1", "Jane doe")).map(_.getId) === Right(a.getId)
      IdentityLinker.resolve(wikimedia("2", "John Smith")).map(_.getId) === Right(b.getId)
      IdentityLinker.resolve(wikimedia("3", "Ann Lee")).map(_.getId) === Right(c.getId)
    }

    "not link by an unverified wiki account of an account someone can sign in to (H1)" in {
      // an admin's typo, or a name the account's holder set for themselves before S2
      user("a@example.com", Some("Jane Doe"))
      invitee("b@example.com", Some("John Smith"))

      IdentityLinker.resolve(wikimedia("1", "Jane Doe")) === Left(LinkError.Unverified)
      IdentityLinker.resolve(wikimedia("2", "John Smith")) === Left(LinkError.Unverified)
      UserIdentityJdbc.find("wikimedia", "1") must beNone
      UserIdentityJdbc.find("wikimedia", "2") must beNone
    }

    "link a wiki-only placeholder (no password, no email) and verify its wiki account" in {
      val board = invitee("", Some("Board Member"))

      IdentityLinker.resolve(wikimedia("5", "Board Member")).map(_.getId) === Right(board.getId)
      wikiVerified(board) must beTrue
    }

    "verify the wiki account when its own Wikimedia account signs in by email" in {
      val alice = user("alice@example.com", Some("Alice W"))

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)).map(_.getId) ===
        Right(alice.getId)
      wikiVerified(alice) must beTrue
    }

    "link a Wikimedia account by its confirmed email if no wiki account matches" in {
      val alice = user("alice@example.com")

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = false)) ===
        Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)).map(_.getId) ===
        Right(alice.getId)
      // the account's wiki account is not this username: it stays unset
      User.findById(alice.getId).flatMap(_.wikiAccount) must beNone
    }

    "not link by a wiki account a Wikimedia identity has or had: a rename's name taken (M1)" in {
      val verified = verifyWiki(invitee("", Some("Jane Doe")))
      val linked   = invitee("", Some("John Smith"))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "old", linked.getId, username = Some("John Smith")))

      IdentityLinker.resolve(wikimedia("1", "Jane Doe")) === Left(LinkError.OtherWikimediaIdentity)
      IdentityLinker.resolve(wikimedia("2", "John Smith")) === Left(LinkError.OtherWikimediaIdentity)
      UserIdentityJdbc.findByUser(verified.getId) must beEmpty
    }

    "unverify the wiki account when its Wikimedia account is renamed away from it (M1)" in {
      val jane = verifyWiki(invitee("", Some("Jane Doe")))
      UserIdentityJdbc.create(UserIdentity("wikimedia", "1", jane.getId, username = Some("Jane Doe")))

      IdentityLinker.resolve(wikimedia("1", "Jane Doe 2")).map(_.getId) === Right(jane.getId)
      wikiVerified(jane) must beFalse
      // and someone who registers the free name gets nothing
      IdentityLinker.resolve(wikimedia("2", "Jane Doe")) === Left(LinkError.OtherWikimediaIdentity)
    }

    "match the rest of a wiki account exactly, as MediaWiki does" in {
      invitee("", Some("FooBar"))
      invitee("", Some("José Foo"))

      IdentityLinker.resolve(wikimedia("1", "Foobar")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("2", "Jose Foo")) === Left(LinkError.NoAccount)
      IdentityLinker.resolve(wikimedia("3", "fooBar")).isRight must beTrue
    }

    "refuse a wiki account and an email of two different accounts" in {
      // a wiki account must not win over the signer's email
      user("alice@example.com")
      invitee("", Some("Alice W"))

      IdentityLinker.resolve(wikimedia("1", "Alice W", Some("alice@example.com"), confirmed = true)) ===
        Left(LinkError.Ambiguous)
      UserIdentityJdbc.find("wikimedia", "1") must beNone
    }

    "give a wiki account two accounts have to the chooser, linking nothing yet" in {
      val a = user("a@example.com", Some("Jane Doe"))
      val b = user("b@example.com", Some("Jane_Doe"))

      IdentityLinker.resolve(wikimedia("1", "Jane Doe")) must beLike {
        case Left(LinkError.Choose(accounts)) => accounts.map(_.id).toSet === Set(a.id, b.id)
      }
      UserIdentityJdbc.find("wikimedia", "1") must beNone
    }

    "not sign in to a deleted account" in {
      val linked = user("linked@example.com")
      UserIdentityJdbc.create(UserIdentity("google", "g-1", linked.getId))
      softDelete(linked)
      softDelete(invitee("", Some("Gone")))

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

  "wikiName" should {
    "write a Wikimedia username as MediaWiki does, and none for another provider" in {
      IdentityLinker.wikiName(wikimedia("1", " jane__doe ")) === Some("Jane doe")
      IdentityLinker.wikiName(wikimedia("1", "User:ann_lee")) === Some("Ann lee")
      IdentityLinker.wikiName(wikimedia("1", "")) must beNone
      IdentityLinker.wikiName(google("g", "a@b.c").copy(username = Some("Jane"))) must beNone
    }
  }
}
