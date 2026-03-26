package db.scalikejdbc

import org.intracer.wmua.ContestJury
import scalikejdbc.{AutoSession, DBSession}

import java.time.ZonedDateTime

/** Shared DAO accessors and test-data helpers.
 *  DB lifecycle is handled by AutoRollbackDb / AutoRollbackMunitDb
 *  (which call SharedTestDb.init() before tests run).
 *
 *  Helpers accept implicit DBSession (default: AutoSession) so that
 *  when called inside a transaction context (AutoRollback test) all
 *  inserts participate in the transaction and are rolled back.
 */
trait TestDb {

  val contestDao: ContestJuryJdbc.type = ContestJuryJdbc
  val imageDao: ImageJdbc.type         = ImageJdbc
  val monumentDao: MonumentJdbc.type   = MonumentJdbc
  val roundDao: Round.type             = Round
  val selectionDao: SelectionJdbc.type = SelectionJdbc
  val userDao: User.type               = User

  def now: ZonedDateTime = TestDb.now

  def createContests(contestIds: Long*)(implicit session: DBSession = AutoSession): Seq[ContestJury] =
    contestIds.map { id =>
      val contest = ContestJuryJdbc.create(Some(id), s"contest$id", 2000 + id.toInt, s"country$id")
      ContestJuryJdbc.setImagesSource(id, Some(s"Images from ${contest.name}"))
      contest
    }

  def contestUser(i: Long, role: String = "jury")(implicit contest: ContestJury): User =
    User(
      s"fullname$i", s"email$i", None, Set(role),
      Some("password hash"), contest.id, Some("en"), Some(TestDb.now)
    )

  def createUsers(userIndexes: Seq[Int])(implicit
      contest: ContestJury,
      session: DBSession,
      d: DummyImplicit
  ): Seq[User] = createUsers(userIndexes: _*)

  def createUsers(userIndexes: Int*)(implicit
      contest: ContestJury,
      session: DBSession
  ): Seq[User] = createUsers("jury", userIndexes: _*)

  def createUsers(role: String, userIndexes: Seq[Int])(implicit
      contest: ContestJury,
      session: DBSession,
      d: DummyImplicit
  ): Seq[User] = createUsers(role, userIndexes: _*)

  def createUsers(role: String, userIndexes: Int*)(implicit
      contest: ContestJury,
      session: DBSession = AutoSession
  ): Seq[User] =
    userIndexes.map(i => contestUser(i, role)).map(userDao.create)
}

object TestDb {
  def now: ZonedDateTime = ZonedDateTime.now.withNano(0)
}
