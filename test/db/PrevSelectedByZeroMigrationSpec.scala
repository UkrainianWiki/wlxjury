package db

import db.scalikejdbc.{Round, SharedTestDb, TestDb}
import org.intracer.wmua.ContestJury
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import _root_.scalikejdbc._
import java.nio.file.{Files, Paths}

/** V73: "selected by at least 0", the old form's way of saying no filter, becomes NULL, with
  * or without a previous round; every other value stays. Rerunning it changes nothing.
  */
class PrevSelectedByZeroMigrationSpec extends Specification with TestDb with BeforeAll with BeforeEach {
  sequential
  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  private def migrate(): Unit = {
    val sql = new String(Files.readAllBytes(Paths.get(
      "conf/db/migration/default/V73__Rounds_prev_selected_by_zero_to_null.sql")), "UTF-8")
    val statements = sql.linesIterator.filterNot(_.trim.startsWith("--")).mkString("\n")
      .split(";").map(_.trim).filter(_.nonEmpty)
    DB.autoCommit { implicit session => statements.foreach(statement => SQL(statement).execute.apply()) }
  }

  "the migration" should {
    "set every 0 to NULL, and change nothing when run again" in {
      implicit val contest: ContestJury = createContests(1).head
      def round(n: Long, previous: Option[String], selectedBy: Option[Int]) =
        roundDao.create(Round(None, n, Some(s"Round $n"), contest.getId, previous = previous,
          prevSelectedBy = selectedBy))
      val first = round(1, None, Some(0))
      val rounds = Seq(
        first,
        round(2, Some(""), Some(0)),
        round(3, Some("  "), Some(0)),
        round(4, Some(first.getId.toString), Some(0)),
        round(5, None, Some(2)),
        round(6, Some(first.getId.toString), Some(3)),
        round(7, None, None),
        round(8, Some(first.getId.toString), None)
      )
      def stored = rounds.map(r => roundDao.findById(r.getId).get)

      migrate()
      val after = stored
      val once = (after.map(_.prevSelectedBy) === Seq(None, None, None, None, Some(2), Some(3), None, None)) and
        (after.map(_.previous) === rounds.map(_.previous)) and
        (after.map(_.name) === rounds.map(_.name))

      migrate()
      once and (stored.map(_.prevSelectedBy) === after.map(_.prevSelectedBy))
    }
  }
}
