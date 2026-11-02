package db

import db.scalikejdbc.{SharedTestDb, TestDb, User}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._

import java.nio.file.{Files, Paths}
import scala.util.Try

/** V77: verified flags and auth version on users, and the account_token table. Running it again
  * marks root accounts with an email verified and nobody else; the table refuses an unknown
  * purpose or a role that is not a contest role.
  */
class AccountVerificationMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private val Script = "conf/db/migration/default/V77__Account_verification_and_tokens.sql"

  private def statements: Seq[String] =
    new String(Files.readAllBytes(Paths.get(Script)), "UTF-8").linesIterator
      .filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq
      // run on the vote tables as V82 renamed them
      .map(_.replaceAll("\\bselection_deleted\\b", "vote_aside").replaceAll("\\bselection\\b", "vote"))

  private def run(): Unit =
    DB.autoCommit { implicit s => statements.foreach(statement => SQL(statement).execute.apply()) }

  private def verified(id: Long): Boolean =
    DB.readOnly(implicit s => sql"SELECT email_verified_at IS NOT NULL FROM users WHERE id = $id".map(_.boolean(1)).single())
      .contains(true)

  private def insertToken(userId: Long, purpose: String, role: Option[String], hash: String): Unit =
    DB.autoCommit { implicit s =>
      sql"""INSERT INTO account_token (user_id, purpose, token_hash, email, role, expires_at)
            VALUES ($userId, $purpose, $hash, 'x@example.com', $role, NOW(3) + INTERVAL 1 DAY)""".update.apply()
    }

  "the migration" should {
    "mark verified root accounts with an email, and used accounts: an access membership, a round, a vote" in {
      createContests(10)
      def user(email: String, root: Boolean = false, wiki: Option[String] = None) =
        User.create(User(email, email, isRoot = root, password = Some("hash"), wikiAccount = wiki)).getId
      val root = user("root@example.com", root = true)
      val rootNoEmail = User.create(User("Root2", "", isRoot = true, wikiAccount = Some("Root two"))).getId
      val juror = user("juror@example.com", wiki = Some("Juror"))
      val organizer = user("organizer@example.com")
      val boardOnly = user("board@example.com")
      val assigned = user("assigned@example.com")
      val voter = user("voter@example.com")
      val deletedVoter = user("deleted-voter@example.com")
      val oldVoter = user("old-voter@example.com")
      val unused = user("unused@example.com")
      val deletedMember = user("deleted@example.com")
      DB.autoCommit { implicit s =>
        sql"SET FOREIGN_KEY_CHECKS=0".execute.apply()
        sql"""INSERT INTO user_contest (user_id, contest_id, role) VALUES
              ($juror, 10, 'jury'), ($organizer, 10, 'organizer'), ($boardOnly, 10, 'board'), ($deletedMember, 10, 'admin')"""
          .update.apply()
        sql"INSERT INTO round_user (user_id, round_id, role, active) VALUES ($assigned, 1, 'jury', 0)".update.apply()
        sql"INSERT INTO vote (jury_id, round_id, page_id, rate) VALUES ($voter, 1, 1, 1), ($deletedVoter, 1, 2, 1)"
          .update.apply()
        sql"INSERT INTO vote_aside (jury_id, round_id, page_id, rate) VALUES ($oldVoter, 1, 3, 1)".update.apply()
        sql"SET FOREIGN_KEY_CHECKS=1".execute.apply()
        sql"UPDATE users SET deleted_at = NOW() WHERE id IN ($deletedVoter, $deletedMember)".update.apply()
      }

      try {
        run()
        run()

        val verifiedIds = Seq(root, juror, organizer, assigned, voter, oldVoter)
        val unverifiedIds = Seq(rootNoEmail, boardOnly, unused, deletedVoter, deletedMember)
        (verifiedIds.filterNot(verified) must beEmpty) and (unverifiedIds.filter(verified) must beEmpty) and
          (User.findAll().forall(u => !u.wikiAccountVerified && u.authVersion == 0) must beTrue)
      } finally DB.autoCommit { implicit s =>
        sql"DELETE FROM round_user".update.apply()
        sql"DELETE FROM vote".update.apply()
        sql"DELETE FROM vote_aside".update.apply()
      }
    }

    "leave account_token refusing unknown purposes and roles that are not contest roles" in {
      val user = User.create(User("User", "user@example.com"))
      val refused = Seq(("root_reset", None), ("invitation", Some("root")), ("invitation", Some("Jury")),
        ("Invitation", None), ("invitation", Some("jury,root")))
        .zipWithIndex
        .filter { case ((purpose, role), i) => Try(insertToken(user.getId, purpose, role, f"$i%064d")).isFailure }
      insertToken(user.getId, "email_change", None, "a" * 64)
      insertToken(user.getId, "invitation", Some("organizer"), "b" * 64)
      (refused.size === 5) and
        (DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_token".map(_.int(1)).single()) === Some(2))
    }

    "cascade a deleted account's links" in {
      val user = User.create(User("User", "user@example.com"))
      insertToken(user.getId, "invitation", None, "c" * 64)
      DB.autoCommit(implicit s => sql"DELETE FROM users WHERE id = ${user.getId}".update.apply())
      DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM account_token".map(_.int(1)).single()) === Some(0)
    }
  }
}
