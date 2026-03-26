package db.scalikejdbc

import munit.{FunFixtures, FunSuite}
import scalikejdbc.{ConnectionPool, DB, DBSession}

/** Mix into munit FunSuite DB tests:
 *
 *  {{{
 *    class MySpec extends FunSuite with AutoRollbackMunitDb {
 *      dbTest("insert something") { implicit session =>
 *        myDao.create(...)
 *        assertEquals(myDao.findAll(), List(...))
 *      }
 *    }
 *  }}}
 */
trait AutoRollbackMunitDb extends FunFixtures with TestDb { self: FunSuite =>

  /** Opens a transaction; teardown always rolls it back.
   *  Carries both (DB, DBSession) because `DB.rollbackIfActive()` requires
   *  the original DB handle — it cannot be reconstructed from the session alone
   *  (`session.conn` is package-private in ScalikeJDBC). */
  private val dbFixture: FunFixture[(DB, DBSession)] = FunFixture(
    setup = _ => {
      SharedTestDb.init()
      SharedTestDb.truncateAll()
      val db = DB(ConnectionPool.borrow())
      db.begin()
      (db, db.withinTxSession())
    },
    teardown = { case (db, _) =>
      db.rollbackIfActive()
      db.close()
    }
  )

  /** Declare a test that receives an implicit DBSession. */
  def dbTest(name: String)(body: DBSession => Any): Unit =
    dbFixture.test(name) { case (_, session) =>
      implicit val s: DBSession = session
      body(session)
    }
}
