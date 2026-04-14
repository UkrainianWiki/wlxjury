package db.scalikejdbc

import org.scalawiki.wlx.dto.Monument
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll
import scalikejdbc.DBSession

class MonumentSpec extends Specification with BeforeAll with TestDb {

  override def beforeAll(): Unit = SharedTestDb.init()

  private def monument(
      id:      String,
      name:    String         = "Monument name",
      year:    Option[String] = None,
      city:    Option[String] = None,
      typ:     Option[String] = None,
      subType: Option[String] = None,
  ): Monument = new Monument(id = id, name = name, year = year, city = city,
                              typ = typ, subType = subType, page = "Test")

  private def insert(ms: Monument*)(implicit session: DBSession): Unit =
    monumentDao.batchInsert(ms.toSeq)

  private def find(id: String)(implicit session: DBSession): Option[Monument] =
    monumentDao.find(id)

  // ── 1. Fresh insert ────────────────────────────────────────────────────────

  "batchInsert" should {

    "insert new monuments into an empty table" in new AutoRollbackDb {
      insert(monument("01-001-0001", name = "Castle"), monument("01-001-0002", name = "Bridge"))
      find("01-001-0001").map(_.name) must beSome("Castle")
      find("01-001-0002").map(_.name) must beSome("Bridge")
    }

  // ── 2. Upsert semantics ───────────────────────────────────────────────────

    "overwrite an existing monument when called again with the same id" in new AutoRollbackDb {
      insert(monument("02-001-0001", name = "Old name"))
      insert(monument("02-001-0001", name = "New name"))
      find("02-001-0001").map(_.name) must beSome("New name")
    }

    "update an optional field that was previously absent" in new AutoRollbackDb {
      insert(monument("02-002-0001", year = None))
      insert(monument("02-002-0001", year = Some("1900")))
      find("02-002-0001").flatMap(_.year) must beSome("1900")
    }

  // ── 3. Field truncation ────────────────────────────────────────────────────

    "truncate name longer than 512 characters" in new AutoRollbackDb {
      insert(monument("03-001-0001", name = "A" * 600))
      find("03-001-0001").map(_.name.length) must beSome(512)
    }

    "not truncate name of exactly 512 characters" in new AutoRollbackDb {
      insert(monument("03-002-0001", name = "B" * 512))
      find("03-002-0001").map(_.name.length) must beSome(512)
    }

    "truncate year longer than 255 characters" in new AutoRollbackDb {
      insert(monument("03-003-0001", year = Some("Y" * 300)))
      find("03-003-0001").flatMap(_.year).map(_.length) must beSome(255)
    }

    "truncate city longer than 255 characters" in new AutoRollbackDb {
      insert(monument("03-004-0001", city = Some("C" * 300)))
      find("03-004-0001").flatMap(_.city).map(_.length) must beSome(255)
    }

    "truncate typ longer than 255 characters" in new AutoRollbackDb {
      insert(monument("03-005-0001", typ = Some("T" * 300)))
      find("03-005-0001").flatMap(_.typ).map(_.length) must beSome(255)
    }

    "truncate subType longer than 255 characters" in new AutoRollbackDb {
      insert(monument("03-006-0001", subType = Some("S" * 300)))
      find("03-006-0001").flatMap(_.subType).map(_.length) must beSome(255)
    }
  }
}
