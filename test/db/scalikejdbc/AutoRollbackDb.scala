package db.scalikejdbc

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
 *  Each `new AutoRollbackDb` opens a transaction and rolls it back after the test.
 *  The spec class must call SharedTestDb.init() in beforeAll to ensure the
 *  ScalikeJDBC ConnectionPool is ready.
 */
trait AutoRollbackDb extends SJAutoRollback with TestDb
