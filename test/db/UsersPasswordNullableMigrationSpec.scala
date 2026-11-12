package db

import db.scalikejdbc.{SharedTestDb, TestDb, User}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._

import java.nio.file.{Files, Paths}
import scala.util.Try

/** V98: users.password nullable on a database whose column is NOT NULL (production's, from the
  * 2015 Play evolution), unchanged where it already is (a fresh one). Run twice; existing
  * passwords are kept.
  */
class UsersPasswordNullableMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  import UsersPasswordNullableMigration._

  "V98" should {
    "make production's NOT NULL users.password nullable, keeping the stored hashes, and be rerunnable" in {
      val withPassword = User.create(User("Has password", "has@example.com", password = Some("hash"))).getId
      try {
        productionShape()
        val refusedBefore = Try(DB.autoCommit(implicit s =>
          sql"INSERT INTO users (fullname, email, password) VALUES ('x', 'x@example.com', NULL)".update.apply())).isFailure

        run()
        val afterFirst = passwordColumn()
        run()
        val afterSecond = passwordColumn()

        (refusedBefore must beTrue) and
          (afterFirst === Column(nullable = true, default = None)) and
          (afterSecond === afterFirst) and
          (User.findById(withPassword).flatMap(_.password) === Some("hash")) and
          (User.create(User("No password", "none@example.com", password = None)).password must beNone) and
          (DB.readOnly(implicit s => sql"SELECT COUNT(*) FROM users WHERE password IS NULL".map(_.int(1)).single())
            === Some(1))
      } finally restore()
    }

    "leave a fresh database's nullable column as it is" in {
      val before = passwordColumn()
      run()
      (before === Column(nullable = true, default = None)) and (passwordColumn() === before)
    }
  }
}

/** V98's statements, and the production shape of users.password, for the specs that need them. */
object UsersPasswordNullableMigration {

  val Script = "conf/db/migration/default/V98__Users_password_nullable.sql"

  /** The script's statements, without its comment lines (none of them has a `;` in a string). */
  def statements: Seq[String] =
    new String(Files.readAllBytes(Paths.get(Script)), "UTF-8").linesIterator
      .filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty).toSeq

  def run(): Unit = DB.autoCommit { implicit s => statements.foreach(st => SQL(st).execute.apply()) }

  /** users.password as production has it before V98: NOT NULL, no default. Every row must have one. */
  def productionShape(): Unit =
    DB.autoCommit(implicit s => sql"ALTER TABLE users MODIFY password VARCHAR(255) NOT NULL".execute.apply())

  /** The column as V2 created it (and V98 leaves it): the test database's own shape. */
  def restore(): Unit =
    DB.autoCommit(implicit s => sql"ALTER TABLE users MODIFY password VARCHAR(255) NULL DEFAULT NULL".execute.apply())

  final case class Column(nullable: Boolean, default: Option[String])

  def passwordColumn(): Column = DB.readOnly { implicit s =>
    sql"""SELECT IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.COLUMNS
          WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'users' AND COLUMN_NAME = 'password'"""
      .map(rs => Column(rs.string(1) == "YES", rs.stringOpt(2).filterNot(_ == "NULL"))).single()
  }.get
}
