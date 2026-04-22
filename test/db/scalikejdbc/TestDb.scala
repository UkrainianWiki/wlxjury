package db.scalikejdbc

import com.dimafeng.testcontainers.MariaDBContainer
import db.scalikejdbc.TestDb._
import org.intracer.wmua.ContestJury
import org.testcontainers.utility.DockerImageName
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.Helpers.running

import java.time.ZonedDateTime

trait TestDb {

  /** Data Access Objects */
  val contestDao: ContestJuryJdbc.type = ContestJuryJdbc
  val imageDao: ImageJdbc.type = ImageJdbc
  val roundDao: Round.type = Round
  val selectionDao: SelectionJdbc.type = SelectionJdbc
  val userDao: User.type = User

  /** Current time accessor */
  def now: ZonedDateTime = TestDb.now

  /** Runs a test block with a temporary containerized database
    *
    * @param block
    *   Function to execute with the application
    * @param additionalConfig
    *   Additional configuration parameters
    * @return
    *   Result of the block execution
    */
  def testDbApp[T](
      block: Application => T
  )(implicit additionalConfig: Map[String, String] = Map.empty): T = {
    val container = MariaDBContainer(
      dockerImageName = DockerImageName.parse("mariadb:10.6.22"),
      dbName = Schema,
      dbUsername = UserName,
      dbPassword = Password
    )

    container.start()
    try {
      val fakeApp = getApp(additionalConfig, container)
      running(fakeApp) {
        roundDao.usersRef // init ref TODO fix somehow

        block(fakeApp)
      }
    } finally {
      container.stop()
    }
  }

  /** Creates a Play application with database configuration
    */
  private def getApp(additionalConfig: Map[String, String], container: MariaDBContainer): Application = {
    val dbConfiguration = Map(
      "db.default.driver" -> container.driverClassName,
      "db.default.username" -> container.username,
      "db.default.password" -> container.password,
      "db.default.url" -> container.jdbcUrl
    )

    new GuiceApplicationBuilder()
      .configure(dbConfiguration ++ additionalConfig)
      .build()
  }

  /** Simplified wrapper to run a test block with database
    */
  def withDb[T](block: => T)(implicit additionalConfig: Map[String, String] = Map.empty): T = {
    testDbApp { _ =>
      block
    }
  }

  /** Creates test contests with specified IDs
    *
    * @param contestIds
    *   IDs for the contests to create
    * @return
    *   Sequence of created contests
    */
  def createContests(contestIds: Long*): Seq[ContestJury] = contestIds.map { id =>
    val contest = ContestJuryJdbc.create(Some(id), s"contest$id", 2000 + id.toInt, s"country$id")
    ContestJuryJdbc.setImagesSource(id, Some(s"Images from ${contest.name}"))
    contest
  }

  /** Creates a user for a contest with specified role
    */
  def contestUser(i: Long, role: String = "jury")(implicit contest: ContestJury): User =
    User(
      s"fullname$i",
      s"email$i",
      None,
      Set(role),
      Some("password hash"),
      contest.id,
      Some("en"),
      Some(TestDb.now)
    )

  /** Overloaded methods for creating multiple users
    */
  def createUsers(
      userIndexes: Seq[Int]
  )(implicit contest: ContestJury, d: DummyImplicit): Seq[User] =
    createUsers(userIndexes: _*)

  def createUsers(userIndexes: Int*)(implicit contest: ContestJury): Seq[User] =
    createUsers("jury", userIndexes: _*)

  def createUsers(role: String, userIndexes: Seq[Int])(implicit
      contest: ContestJury,
      d: DummyImplicit
  ): Seq[User] = createUsers(role, userIndexes: _*)

  def createUsers(role: String, userIndexes: Int*)(implicit contest: ContestJury): Seq[User] =
    userIndexes
      .map(i => contestUser(i, role))
      .map(userDao.create)
}

object TestDb {

  private val UserName = "WLXJURY_DB_USER"

  private val Password = "WLXJURY_DB_PASSWORD"

  private val Schema = "wlxjury"

  def now: ZonedDateTime = ZonedDateTime.now.withNano(0)

}
