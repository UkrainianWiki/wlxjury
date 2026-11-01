package db

import db.scalikejdbc.{Round, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._
import java.nio.file.{Files, Paths}

/** V74: "exclude files rejected by at least N jurors", a nullable column added to the rounds,
  * NULL no filter; additive, so rerunning it changes nothing.
  */
class PrevRejectedByMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential
  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def migrate(): Unit = {
    val sql = new String(Files.readAllBytes(Paths.get(
      "conf/db/migration/default/V74__Rounds_prev_rejected_by.sql")), "UTF-8")
    val statements = sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
    DB.autoCommit { implicit session => statements.foreach(statement => SQL(statement).execute.apply()) }
  }

  "the rejected-by column" should {
    "be a nullable integer column, NULL for a round that has none" in {
      DB.readOnly { implicit session =>
        sql"""SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.columns
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds' AND COLUMN_NAME = 'prev_rejected_by'"""
          .map(rs => (rs.string(1), rs.string(2), rs.stringOpt(3).filterNot(_ == "NULL"))).single()
      } === Some(("int", "YES", None))
    }

    "keep the rounds and their values when run again" in {
      implicit val contest: ContestJury = createContests(1).head
      val plain = roundDao.create(Round(None, 1, Some("Plain"), contest.getId))
      val set = roundDao.create(Round(None, 2, Some("Set"), contest.getId, prevRejectedBy = Some(2)))
      migrate()
      val stored = Seq(plain, set).map(r => roundDao.findById(r.getId).get)
      (stored.map(_.prevRejectedBy) === Seq(None, Some(2))) and
        (stored.map(_.name) === Seq(Some("Plain"), Some("Set")))
    }
  }
}
