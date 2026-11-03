package db

import db.scalikejdbc.{Round, VoteJdbc, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._
import java.nio.file.{Files, Paths}
import java.time.ZonedDateTime

class CategoryClauseMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential
  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def migrate(): Unit = {
    val sql = new String(Files.readAllBytes(Paths.get(
      "conf/db/migration/default/V70__Named_category_clauses.sql")), "UTF-8")
    val statements = sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
      .map(_.replaceAll("\\brounds\\b", "round")) // the table as V82 renamed it
    DB.autoCommit { implicit session => statements.foreach(statement => SQL(statement).execute.apply()) }
  }

  "category clause storage" should {
    "store named operations in a text column" in {
      DB.readOnly { implicit session =>
        sql"""SELECT DATA_TYPE FROM information_schema.columns
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'round' AND COLUMN_NAME = 'category_clause'"""
          .map(_.string(1)).single()
      } === Some("varchar")
    }

    "upgrade populated integer storage without changing category names, dates or votes" in {
      implicit val contest: ContestJury = createContests(1).head
      val juror = createUsers("jury", 1).head
      val cases = Seq[(Option[String], Option[String])](None -> None, Some("1") -> Some("include"), Some("-1") -> Some("exclude"),
        Some("0") -> Some("exclude"), Some("999") -> Some("999"))
      DB.autoCommit { implicit session =>
        sql"ALTER TABLE round MODIFY COLUMN category_clause INT NULL DEFAULT NULL".execute.apply()
      }
      try {
        val rounds = cases.zipWithIndex.map { case ((legacy, _), index) =>
          val round = roundDao.create(Round(None, index + 1L, Some(s"Legacy $index"), contest.getId,
            category = Some("Category:A"), excludeCategory = Some("Category:B"),
            createdAt = ZonedDateTime.parse("2020-01-01T00:00:00Z")))
          DB.autoCommit { implicit session =>
            sql"UPDATE round SET category_clause = $legacy, created_at = created_at WHERE id = ${round.getId}"
              .update.apply()
          }
          roundDao.findById(round.getId).get
        }
        insertImagesFor(1L)
        VoteJdbc.create(1L, 1, juror.getId, rounds.head.getId)
        val votes = VoteJdbc.byRound(rounds.head.getId)
        def stored = rounds.map(round => roundDao.findById(round.getId).get)
        migrate()
        stored.map(_.categoryClause) === cases.map(_._2)
        stored.map(r => (r.category, r.excludeCategory, r.createdAt)) ===
          rounds.map(r => (r.category, r.excludeCategory, r.createdAt))
        VoteJdbc.byRound(rounds.head.getId) === votes
        migrate()
        stored.map(_.categoryClause) === cases.map(_._2)
      } finally migrate()
    }
  }
}
