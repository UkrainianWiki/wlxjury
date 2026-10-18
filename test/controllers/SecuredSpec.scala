package controllers

import db.scalikejdbc.{SharedTestDb, TestDb, User, UserContest, UserContestJdbc}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.mvc.{RequestHeader, Session}
import play.api.test.{FakeRequest, Helpers, PlaySpecification}

class SecuredWithCc extends Secured(Helpers.stubControllerComponents())

class SecuredSpec extends PlaySpecification with Mockito with TestDb
    with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  def mockRequest(username: String, contestId: Option[Long] = None): RequestHeader = {
    val request = mock[RequestHeader]
    val session = mock[Session]
    session.get(Secured.UserId) returns None
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

    "be in the session's contest, with the role there" in {
      createContests(10L, 20L)
      val created = User.create(User("fullname", "multi@server.com", None, Set("jury"), Some("hash"), Some(10L)))
      UserContestJdbc.createMembership(created.getId, 20L, "admin")
      val secured = new SecuredWithCc()

      val in10 = secured.userFromRequest(mockRequest("multi@server.com", Some(10L))).get
      in10.contestId === Some(10L)
      in10.contestRoles === Set("jury")

      val in20 = secured.userFromRequest(mockRequest("multi@server.com", Some(20L))).get
      in20.contestId === Some(20L)
      in20.contestRoles === Set("admin")
    }

    "be in the latest contest when the session has none, or one the user isn't in" in {
      createContests(10L, 20L, 30L)
      val created = User.create(User("fullname", "multi@server.com", None, Set("jury"), Some("hash"), Some(10L)))
      UserContestJdbc.createMembership(created.getId, 20L, "organizer")
      val secured = new SecuredWithCc()

      secured.userFromRequest(mockRequest("multi@server.com")).flatMap(_.contestId) === Some(20L)
      val notMember = secured.userFromRequest(mockRequest("multi@server.com", Some(30L))).get
      notMember.contestId === Some(20L)
      notMember.isInContest(Some(30L)) === false
    }

    "be root without a contest" in {
      User.create(User("root", "root@server.com", None, Set(User.ROOT_ROLE), Some("hash")))
      val root = new SecuredWithCc().userFromRequest(mockRequest("root@server.com")).get
      root.isRoot === true
      root.hasRole(User.ROOT_ROLE) === true
      root.contestId === None
    }

    "be None if not in db" in {
      val username = "user login"
      val user = User("fullname", username, None, Set("jury"), Some("password hash"), Some(10))

      val created = userDao.create(user)

      val request: RequestHeader = mockRequest(username + " other")
      new SecuredWithCc().userFromRequest(request) === None
    }

    "be the session's user id, not its login name" in {
      createContests(10L)
      val byId   = User.create(User("by id", "same@server.com", None, Set.empty, Some("hash"), wikiAccount = Some("Twin")))
      val byName = User.create(User("by name", "other@server.com", None, Set.empty, Some("hash"), wikiAccount = Some("Twin")))
      UserContestJdbc.createMembership(byId.getId, 10L, "jury")
      val secured = new SecuredWithCc()

      val request = FakeRequest().withSession(Secured.UserId -> byId.getId.toString, Secured.UserName -> "other@server.com")
      val user = secured.userFromRequest(request)
      user.map(_.getId) === Some(byId.getId)
      user.flatMap(_.contestId) === Some(10L)
      byName.getId !== byId.getId
    }

    "be None for a deleted user's id" in {
      val deleted = User.create(User("gone", "gone@server.com", None, Set.empty, Some("hash")))
      scalikejdbc.DB.autoCommit { implicit s =>
        scalikejdbc.SQL("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?").bind(deleted.getId).update.apply()
      }
      val request = FakeRequest().withSession(Secured.UserId -> deleted.getId.toString, Secured.UserName -> "gone@server.com")
      new SecuredWithCc().userFromRequest(request) === None
    }

    "be None for a deleted user's login name, nor log them in" in {
      val deleted = User.create(User("gone", "gone@server.com", None, Set.empty, Some(User.sha1("pass")), wikiAccount = Some("Gone")))
      scalikejdbc.DB.autoCommit { implicit s =>
        scalikejdbc.SQL("UPDATE users SET deleted_at = CURRENT_TIMESTAMP WHERE id = ?").bind(deleted.getId).update.apply()
      }
      val secured = new SecuredWithCc()
      secured.userFromRequest(FakeRequest().withSession(Secured.UserName -> "gone@server.com")) === None
      secured.userFromRequest(FakeRequest().withSession(Secured.UserName -> "gone")) === None
      User.login("gone@server.com", "pass") === None
    }

  }

}
