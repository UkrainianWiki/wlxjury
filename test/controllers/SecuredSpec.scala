package controllers

import db.scalikejdbc.{SharedTestDb, TestDb, User, UserContest, UserContestJdbc}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.mvc.{RequestHeader, Session}
import play.api.test.{Helpers, PlaySpecification}

class SecuredWithCc extends Secured(Helpers.stubControllerComponents())

class SecuredSpec extends PlaySpecification with Mockito with TestDb
    with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  def mockRequest(username: String, contestId: Option[Long] = None): RequestHeader = {
    val request = mock[RequestHeader]
    val session = mock[Session]
    session.get(Secured.UserName) returns Some(username)
    session.get(Secured.CurrentContestId) returns contestId.map(_.toString)
    request.session returns session
    request
  }

  "user" should {
    "load from db with contest membership" in {
      val username = "user@server.com"
      val user = User("fullname", username, None, Set.empty, Some("password hash"), createdAt = Some(now))
      val created = userDao.create(user)

      val contests = createContests(10L)
      UserContestJdbc.createMembership(created.getId, 10L, "jury")

      val request: RequestHeader = mockRequest(username, Some(10L))
      val result = new SecuredWithCc().userFromRequest(request)

      result must beSome
      result.get.contestId === Some(10L)
      result.get.roles must contain("jury")
      result.get.userContests === List(UserContest(created.getId, 10L, "jury"))
    }

    "be None if not in db" in {
      val username = "user login"
      val user = User("fullname", username, None, Set("jury"), Some("password hash"), Some(10))

      val created = userDao.create(user)

      val request: RequestHeader = mockRequest(username + " other")
      new SecuredWithCc().userFromRequest(request) === None
    }

  }

}
