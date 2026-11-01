package db.scalikejdbc

import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import scalikejdbc._
import services.ApiTokenService

import java.time.temporal.ChronoUnit

/** Personal API tokens (V75, [[ApiTokenJdbc]], [[ApiTokenService]]): only the hash is stored,
  * and a token lets its user in only while it is well-formed, known, neither revoked nor
  * expired, and its user is not deleted. The service writes with autoSession, so the
  * tables are truncated before each example.
  */
class ApiTokenJdbcSpec extends Specification with BeforeAll with BeforeEach with TestDb {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()

  override protected def before: Any = SharedTestDb.truncateAll()

  private def newUser(email: String): User =
    User.create(User(email, email, password = Some(User.sha1("secret"))))

  private def setColumn(id: Long, column: String, value: SQLSyntax): Unit =
    DB.autoCommit { implicit session =>
      SQL(s"UPDATE api_token SET $column = ${value.value} WHERE id = ?").bind(id).update.apply()
    }

  private def tokenId(token: String): Long = ApiTokenJdbc.findByHash(ApiTokenService.hash(token)).get.id

  "V75" should {
    "create api_token with its columns, a unique hash and the user's tokens deleted with the user" in {
      val columns = DB.readOnly { implicit session =>
        sql"""SELECT column_name, column_type, is_nullable FROM information_schema.columns
              WHERE table_schema = DATABASE() AND table_name = 'api_token' ORDER BY ordinal_position"""
          .map(rs => (rs.string(1), rs.string(2), rs.string(3))).list.apply()
      }
      columns.map(_._1) === Seq("id", "user_id", "name", "token_hash", "prefix", "created_at", "expires_at",
        "last_used_at", "revoked_at")
      columns.find(_._1 == "token_hash").map(_._2) === Some("char(64)")
      columns.filter(_._3 == "YES").map(_._1) === Seq("expires_at", "last_used_at", "revoked_at")

      val user = newUser("alice@example.com")
      val token = ApiTokenService.create(user.getId, "cli", Some(90))
      DB.autoCommit { implicit session =>
        sql"INSERT INTO api_token (user_id, name, token_hash, prefix) VALUES (${user.getId}, 'copy', ${ApiTokenService.hash(token)}, 'x')"
          .update.apply() must throwA[java.sql.SQLException]
      }
      User.deleteById(user.getId)
      ApiTokenJdbc.findByUser(user.getId) must beEmpty
    }
  }

  "a token" should {
    "be stored as its SHA-256 only, with its prefix" in {
      val user = newUser("alice@example.com")
      val token = ApiTokenService.create(user.getId, "laptop", Some(90))
      token must beMatching("wlxj_[A-Za-z0-9_-]{43}")

      val row = DB.readOnly { implicit session =>
        sql"SELECT * FROM api_token".map(_.toMap().values.map(String.valueOf).toSeq).list.apply()
      }
      row must haveSize(1)
      row.head.exists(_.contains(token)) must beFalse
      row.head.exists(_.contains(token.drop(ApiTokenService.Prefix.length + 6))) must beFalse
      row.head must contain(ApiTokenService.hash(token))

      val found = ApiTokenJdbc.findByUser(user.getId)
      found.map(_.name) === Seq("laptop")
      found.map(_.prefix) === Seq(token.take(11))
      ApiTokenService.hash(token) must beMatching("[0-9a-f]{64}")
    }

    "expire after the days chosen, or never" in {
      val user = newUser("alice@example.com")
      val in30 = ApiTokenJdbc.find(tokenId(ApiTokenService.create(user.getId, "a", Some(30)))).get
      val never = ApiTokenJdbc.find(tokenId(ApiTokenService.create(user.getId, "b", None))).get
      in30.expiresAt.map(ChronoUnit.DAYS.between(in30.createdAt, _)) === Some(30L)
      never.expiresAt must beNone
      Seq(in30, never).forall(_.usable) must beTrue
    }

    "let its user in, in their latest contest, and record the use" in {
      createContests(10, 20)
      val user = newUser("alice@example.com")
      UserContestJdbc.createMembership(user.getId, 10, "jury")
      UserContestJdbc.createMembership(user.getId, 20, "admin")
      val token = ApiTokenService.create(user.getId, "cli", Some(90))
      ApiTokenJdbc.find(tokenId(token)).get.lastUsedAt must beNone

      val (found, signedIn) = ApiTokenService.authenticate(token).get
      found.id === tokenId(token)
      signedIn.id === user.id
      signedIn.contestId === Some(20L)
      signedIn.hasRole(User.ADMIN_ROLE) must beTrue
      ApiTokenJdbc.find(found.id).get.lastUsedAt must beSome
    }

    "record a use at most once a minute" in {
      val user = newUser("alice@example.com")
      val token = ApiTokenService.create(user.getId, "cli", Some(90))
      val id = tokenId(token)
      setColumn(id, "last_used_at", sqls"NOW(3) - INTERVAL 30 SECOND")
      val recent = ApiTokenJdbc.find(id).get.lastUsedAt
      ApiTokenService.authenticate(token) must beSome
      ApiTokenJdbc.find(id).get.lastUsedAt === recent

      setColumn(id, "last_used_at", sqls"NOW(3) - INTERVAL 2 MINUTE")
      val older = ApiTokenJdbc.find(id).get.lastUsedAt.get
      ApiTokenService.authenticate(token) must beSome
      ApiTokenJdbc.find(id).get.lastUsedAt.exists(_.isAfter(older)) must beTrue
    }

    "not let anyone in when revoked, expired, malformed, unknown or of a deleted user" in {
      val user = newUser("alice@example.com")
      val revoked = ApiTokenService.create(user.getId, "revoked", Some(90))
      ApiTokenJdbc.revoke(tokenId(revoked), user.getId) must beTrue
      val expired = ApiTokenService.create(user.getId, "expired", Some(30))
      setColumn(tokenId(expired), "expires_at", sqls"NOW(3) - INTERVAL 1 SECOND")
      val unknown = ApiTokenService.generate()
      val malformed = Seq("", "wlxj_", "wlxj_short", ApiTokenService.generate() + "x", "abc" + ApiTokenService.generate().drop(3))

      ApiTokenService.authenticate(revoked) must beNone
      ApiTokenService.authenticate(expired) must beNone
      ApiTokenJdbc.find(tokenId(expired)).get.expired must beTrue
      ApiTokenService.authenticate(unknown) must beNone
      malformed.flatMap(ApiTokenService.authenticate) must beEmpty

      val deletedUser = newUser("bob@example.com")
      val ofDeleted = ApiTokenService.create(deletedUser.getId, "cli", None)
      DB.autoCommit { implicit session =>
        sql"UPDATE users SET deleted_at = NOW() WHERE id = ${deletedUser.getId}".update.apply()
      }
      ApiTokenService.authenticate(ofDeleted) must beNone

      // none of the refused ones counted as a use
      ApiTokenJdbc.findByUser(user.getId).flatMap(_.lastUsedAt) must beEmpty
    }

    "be revoked only by its own user" in {
      val alice = newUser("alice@example.com")
      val bob = newUser("bob@example.com")
      val token = ApiTokenService.create(alice.getId, "cli", Some(90))
      val id = tokenId(token)
      ApiTokenJdbc.revoke(id, bob.getId) must beFalse
      ApiTokenJdbc.find(id).get.revoked must beFalse
      ApiTokenJdbc.revoke(id, alice.getId) must beTrue
      ApiTokenJdbc.revoke(id, alice.getId) must beFalse
      ApiTokenJdbc.find(id).get.revoked must beTrue
    }
  }
}
