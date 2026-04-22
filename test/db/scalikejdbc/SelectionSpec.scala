package db.scalikejdbc

import org.intracer.wmua.Selection
import org.specs2.mutable.Specification

import java.time.ZonedDateTime

class SelectionSpec extends Specification with TestDb {

  sequential

  private val time: ZonedDateTime = now

  private def sameTime(s: Seq[Selection]): Seq[Selection] = s.map(_.copy(createdAt = Some(time)))
  private def noIds(s: Seq[Selection]): Seq[Selection] = s.map(_.copy(id = None))

  private def findAll(): Seq[Selection] = sameTime(selectionDao.findAll())

  "fresh database" should {
    "be empty" in {
      withDb {
        selectionDao.findAll() === Nil
      }
    }

    "insert selection" in {
      withDb {

        val s = Selection(pageId = -1, juryId = 20, roundId = 0, rate = 30, id = Some(40))

        val created = selectionDao.create(s.pageId, s.rate, s.juryId, s.roundId)
        val id = created.getId
        created === s.copy(id = Some(id))
        selectionDao.findById(id) === Some(created)
        selectionDao.findAll() === Seq(created)
      }
    }

    "batch insert selections" in {
      withDb {
        val s = sameTime(Seq(
          Selection(pageId = 20, juryId = 1, roundId = 0, rate = 10),
          Selection(pageId = 21, juryId = 2, roundId = 1, rate = 11),
          Selection(pageId = 22, juryId = 3, roundId = -1, rate = 12)
        ))

        selectionDao.batchInsert(s)

        val selections = sameTime(selectionDao.findAll())
        selections.flatMap(_.id) === Seq(1,2,3)
        noIds(selections) === s
      }
    }

    "rate selections" in {

      withDb {
        val s = sameTime(Seq(
          Selection(pageId = 1, roundId = 20, juryId = 10),
          Selection(pageId = 1, roundId = 20, juryId = 11),
          Selection(pageId = 2, roundId = 20, juryId = 10),
          Selection(pageId = 2, roundId = 20, juryId = 11),
          Selection(pageId = 1, roundId = 21, juryId = 10)
        ))

        selectionDao.batchInsert(s)

        selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 1)

        noIds(findAll()) === s.head.copy(rate = 1) +: s.tail

        selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = -1)
        noIds(findAll()) === s.head.copy(rate = -1) +: s.tail

        selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 0)
        noIds(findAll()) === s
      }
    }

  }
}
