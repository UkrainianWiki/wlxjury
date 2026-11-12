package db.scalikejdbc

import org.intracer.wmua.Vote
import org.specs2.mutable.Specification
import org.specs2.specification.BeforeAll

import java.time.ZonedDateTime

class SelectionSpec extends Specification with BeforeAll {

  override def beforeAll(): Unit = SharedTestDb.init()

  private val time: ZonedDateTime = TestDb.now

  private def sameTime(s: Seq[Vote]): Seq[Vote] = s.map(_.copy(createdAt = Some(time)))
  private def noIds(s: Seq[Vote]): Seq[Vote] = s.map(_.copy(id = None))
  // the vote times (V97, VoteVotedAtSpec checks them): here whether a row has one
  private def untimed(s: Seq[Vote]): Seq[Vote] = s.map(_.copy(votedAt = None))
  private def timed(s: Seq[Vote]): Seq[Boolean] = s.map(_.votedAt.isDefined)

  "fresh database" should {
    "be empty" in new AutoRollbackDb {
      selectionDao.findAll() === Nil
    }

    "insert selection" in new AutoRollbackDb {
      val s = Vote(pageId = -1, juryId = 20, roundId = 0, rate = 30, id = Some(40))

      insertImagesFor(s.pageId)
      val created = selectionDao.create(s.pageId, s.rate, s.juryId, s.roundId)
      val id = created.getId
      created === s.copy(id = Some(id))
      // read back, the rated vote has the time it was inserted at (create returns none)
      selectionDao.findById(id).map(_.copy(votedAt = None)) === Some(created)
      selectionDao.findById(id).flatMap(_.votedAt) must beSome
      untimed(selectionDao.findAll()) === Seq(created)
    }

    "batch insert selections" in new AutoRollbackDb {
      val s = sameTime(Seq(
        Vote(pageId = 20, juryId = 1, roundId = 0, rate = 10),
        Vote(pageId = 21, juryId = 2, roundId = 1, rate = 11),
        Vote(pageId = 22, juryId = 3, roundId = -1, rate = 12)
      ))

      insertImagesFor(s.map(_.pageId): _*)
      selectionDao.batchInsert(s)

      val selections = sameTime(selectionDao.findAll())
      val ids = selections.flatMap(_.id)
      ids.size === 3
      ids === ids.min.to(ids.min + 2).toList
      untimed(noIds(selections)) === s
      timed(selections) === Seq(true, true, true)
    }

    "reject a vote row whose image doesn't exist" in new AutoRollbackDb {
      selectionDao.create(pageId = 404, rate = 0, juryId = 10, roundId = 1) must throwA[java.sql.SQLException].like {
        case e => e.getMessage must contain("vote_page_id_fk")
      }
    }

    "reject a duplicate selection for the same image, juror and round" in new AutoRollbackDb {
      insertImagesFor(1)
      selectionDao.create(pageId = 1, rate = 0, juryId = 10, roundId = 1)
      selectionDao.create(pageId = 1, rate = 1, juryId = 10, roundId = 1) must throwA[Exception]
    }

    "rate selections" in new AutoRollbackDb {
      val s = sameTime(Seq(
        Vote(pageId = 1, roundId = 20, juryId = 10),
        Vote(pageId = 1, roundId = 20, juryId = 11),
        Vote(pageId = 2, roundId = 20, juryId = 10),
        Vote(pageId = 2, roundId = 20, juryId = 11),
        Vote(pageId = 1, roundId = 21, juryId = 10)
      ))

      insertImagesFor(s.map(_.pageId): _*)
      selectionDao.batchInsert(s)

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 1)

      untimed(noIds(sameTime(selectionDao.findAll()))) === s.head.copy(rate = 1) +: s.tail
      timed(selectionDao.findAll()) === Seq(true, false, false, false, false)

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = -1)
      untimed(noIds(sameTime(selectionDao.findAll()))) === s.head.copy(rate = -1) +: s.tail

      selectionDao.rate(pageId = 1, juryId = 10, roundId = 20, rate = 0)
      // cleared: no time again
      noIds(sameTime(selectionDao.findAll())) === s
    }

    "persist and read back monumentId via create" in new AutoRollbackDb {
      insertImagesFor(-10L)
      val created = selectionDao.create(
        pageId = -10L, rate = 1, juryId = 20L, roundId = 0L,
        monumentId = Some("13-220")
      )
      val id = created.getId
      created.monumentId === Some("13-220")
      selectionDao.findById(id).map(_.monumentId) === Some(Some("13-220"))
    }

    "persist None monumentId via create" in new AutoRollbackDb {
      insertImagesFor(-11L)
      val created = selectionDao.create(
        pageId = -11L, rate = 0, juryId = 20L, roundId = 0L
      )
      val id = created.getId
      created.monumentId === None
      selectionDao.findById(id).map(_.monumentId) === Some(None)
    }

    "batchInsert preserves monumentId per row" in new AutoRollbackDb {
      val selections = Seq(
        Vote(pageId = 1L, juryId = 1L, roundId = 1L, rate = 1, monumentId = Some("13-001")),
        Vote(pageId = 2L, juryId = 1L, roundId = 1L, rate = 0, monumentId = Some("07-101")),
        Vote(pageId = 3L, juryId = 1L, roundId = 1L, rate = 1, monumentId = None)
      )
      insertImagesFor(selections.map(_.pageId): _*)
      selectionDao.batchInsert(selections)
      val all = selectionDao.findAll().sortBy(_.pageId)
      all.map(_.monumentId) === Seq(Some("13-001"), Some("07-101"), None)
    }
  }

}
