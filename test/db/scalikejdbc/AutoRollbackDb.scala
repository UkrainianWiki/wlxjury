package db.scalikejdbc

import scalikejdbc.DB
import scalikejdbc.specs2.mutable.{AutoRollback => SJAutoRollback}

/** Use as a per-test anonymous instance inside specs2 test blocks:
 *
 *  {{{
 *    class MySpec extends Specification with BeforeAll {
 *      override def beforeAll(): Unit = SharedTestDb.init()
 *
 *      "insert something" in new AutoRollbackDb {
 *        // implicit val session: DBSession is provided by AutoRollback
 *        myDao.create(...)
 *        myDao.findAll() === Seq(...)
 *      }
 *    }
 *  }}}
 *
 *  Each `new AutoRollbackDb` empties the database, then opens a transaction and
 *  rolls it back after the test - like [[AutoRollbackMunitDb]]. Specs that commit
 *  data (truncating only before their own tests, the Gatling setup) leave rows in
 *  the shared container that would otherwise collide with these tests' inserts.
 */
trait AutoRollbackDb extends SJAutoRollback with TestDb {

  // called by AutoRollbackLike before it opens the test's transaction
  override def db(): DB = {
    SharedTestDb.init()
    SharedTestDb.truncateAll()
    super.db()
  }
}
