package db

import db.scalikejdbc.{Round, SharedTestDb, TestDb}
import org.intracer.wmua.{BinaryOrder, ContestJury}
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._
import java.nio.file.{Files, Paths}

/** V72: the order of a round's top-N from a yes/no previous round, a nullable column added to
  * the rounds, NULL (a round from before it) the legacy rule; additive, so rerunning it changes
  * nothing.
  */
class TopBinaryOrderMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential
  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def migrate(): Unit = {
    val sql = new String(Files.readAllBytes(Paths.get(
      "conf/db/migration/default/V72__Rounds_top_binary_order.sql")), "UTF-8")
    val statements = sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
    DB.autoCommit { implicit session => statements.foreach(statement => SQL(statement).execute.apply()) }
  }

  "the top-N order column" should {
    "be a nullable text column, NULL for a round that has none" in {
      DB.readOnly { implicit session =>
        sql"""SELECT DATA_TYPE, IS_NULLABLE, COLUMN_DEFAULT FROM information_schema.columns
              WHERE TABLE_SCHEMA = DATABASE() AND TABLE_NAME = 'rounds' AND COLUMN_NAME = 'top_binary_order'"""
          .map(rs => (rs.string(1), rs.string(2), rs.stringOpt(3).filterNot(_ == "NULL"))).single()
      } === Some(("varchar", "YES", None))
    }

    "keep the rounds and their orders when run again, a round without one by the legacy rule" in {
      implicit val contest: ContestJury = createContests(1).head
      val plain = roundDao.create(Round(None, 1, Some("Plain"), contest.getId, topImages = Some(5)))
      val chosen = roundDao.create(Round(None, 2, Some("Chosen"), contest.getId, topImages = Some(5),
        topBinaryOrder = Some(BinaryOrder.ApprovalsOnly)))
      migrate()
      val stored = Seq(plain, chosen).map(r => roundDao.findById(r.getId).get)
      stored.map(_.topBinaryOrder) === Seq(None, Some(BinaryOrder.ApprovalsOnly))
      stored.map(_.topOrderParam) === Seq(BinaryOrder.LegacyParam, BinaryOrder.ApprovalsOnly.param)
      stored.map(_.name) === Seq(Some("Plain"), Some("Chosen"))
    }
  }
}
