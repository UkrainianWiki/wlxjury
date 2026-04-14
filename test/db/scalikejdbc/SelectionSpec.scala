package db.scalikejdbc

import org.intracer.wmua.Selection
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.time.ZonedDateTime

class SelectionSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  private val time: ZonedDateTime = TestDb.now

  private def sameTime(s: Seq[Selection]): Seq[Selection] = s.map(_.copy(createdAt = Some(time)))
  private def noIds(s: Seq[Selection]): Seq[Selection] = s.map(_.copy(id = None))

  "fresh database" should {
    "be empty" in new AutoRollbackDb {
      selectionDao.findAll() === Nil
    }

    "insert selection" in new AutoRollbackDb {
      val s = Selection(pageId = -1, juryId = 20, roundId = 0, rate = 30, id = Some(40))

      val created = selectionDao.create(s.pageId, s.rate, s.juryId, s.roundId)
      val id = created.getId
      created === s.copy(id = Some(id))
      selectionDao.findById(id) === Some(created)
      selectionDao.findAll() === Seq(created)
    }

    "batch insert selections" in new AutoRollbackDb {
      val s = sameTime(Seq(
        Selection(pageId = 20, juryId = 1, roundId = 0, rate = 10),
        Selection(pageId = 21, juryId = 2, roundId = 1, rate = 11),
        Selection(pageId = 22, juryId = 3, roundId = -1, rate = 12)
      ))

      selectionDao.batchInsert(s)

      val selections = sameTime(selectionDao.findAll())
      val ids = selections.flatMap(_.id)
      ids.size === 3
      ids === ids.min.to(ids.min + 2).toList
      noIds(selections) === s
    }

    "rate selections" in new AutoRollbackDb {
      val s = sameTime(Seq(
        Selection(pageId = 1, roundId = 20, juryId = 10),
        Selection(pageId = 1, roundId = 20, juryId = 11),
        Selection(pageId = 2, roundId = 20, juryId = 10),
        Selection(pageId = 2, roundId = 20, juryId = 11),
        Selection(pageId = 1, roundId = 21, juryId = 10)
      ))

      selectionDao.batchInsert(s)

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 1)

      noIds(sameTime(selectionDao.findAll())) === s.head.copy(rate = 1) +: s.tail

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = -1)
      noIds(sameTime(selectionDao.findAll())) === s.head.copy(rate = -1) +: s.tail

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 0)
      noIds(sameTime(selectionDao.findAll())) === s
    }

    "persist and read back monumentId via create" in new AutoRollbackDb {
      val created = selectionDao.create(
        pageId = -10L, rate = 1, juryId = 20L, roundId = 0L,
        monumentId = Some("13-220")
      )
      val id = created.getId
      created.monumentId === Some("13-220")
      selectionDao.findById(id).map(_.monumentId) === Some(Some("13-220"))
    }

    "persist None monumentId via create" in new AutoRollbackDb {
      val created = selectionDao.create(
        pageId = -11L, rate = 0, juryId = 20L, roundId = 0L
      )
      val id = created.getId
      created.monumentId === None
      selectionDao.findById(id).map(_.monumentId) === Some(None)
    }

    "batchInsert preserves monumentId per row" in new AutoRollbackDb {
      val selections = Seq(
        Selection(pageId = 1L, juryId = 1L, roundId = 1L, rate = 1, monumentId = Some("13-001")),
        Selection(pageId = 2L, juryId = 1L, roundId = 1L, rate = 0, monumentId = Some("07-101")),
        Selection(pageId = 3L, juryId = 1L, roundId = 1L, rate = 1, monumentId = None)
      )
      selectionDao.batchInsert(selections)
      val all = selectionDao.findAll().sortBy(_.pageId)
      all.map(_.monumentId) === Seq(Some("13-001"), Some("07-101"), None)
    }
  }
}
