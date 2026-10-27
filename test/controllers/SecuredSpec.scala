package controllers

import db.scalikejdbc.{PlayTestDb, Round, RoundUser, SharedTestDb, User, UserContest, UserContestJdbc}
import org.specs2.mock.Mockito
import org.specs2.mutable.Specification
import org.specs2.specification.{BeforeAll, BeforeEach}
import play.api.http.HttpEntity
import play.api.mvc.{ControllerComponents, RequestHeader, Session}
import play.api.test.{FakeRequest, Helpers, PlaySpecification}

class SecuredWithCc extends Secured(Helpers.stubControllerComponents())

class SecuredSpec extends PlaySpecification with Mockito with PlayTestDb
    with BeforeAll with BeforeEach {

  sequential

  override def beforeAll(): Unit = SharedTestDb.init()
  override protected def before: Any = SharedTestDb.truncateAll()

  "safeLocalPath" should {
    "accept only local absolute paths" in {
      Secured.safeLocalPath(Some("/contest/628/files?page=2")) === Some("/contest/628/files?page=2")
      Secured.safeLocalPath(Some("https://example.com/")) === None
      Secured.safeLocalPath(Some("//example.com/")) === None
      Secured.safeLocalPath(Some("/\\\\example.com/")) === None
      Secured.safeLocalPath(Some("x" * 2049)) === None
    }
  }

  "access denial" should {
    "return 403 and identify the signed-in account" in {
      testDbApp { implicit app =>
        val user = userDao.create(User("Wrong account", "wrong-account@server.com"))
        val request = FakeRequest("GET", "/contest/628/files")
        val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
        val result = controller.onUnAuthorized(user, request)

        result.header.status === Helpers.FORBIDDEN
        val html = result.body.asInstanceOf[HttpEntity.Strict].data.utf8String
        html must contain("wrong-account@server.com")
      }
    }

    "suggest page one of a nonbinary gallery so its pager remains available" in {
      testDbApp { implicit app =>
        val contest = createContests(2025).head
        val user = userDao.create(
          User("Juror", "juror@server.com", None, Set("jury"), Some("hash"), Some(contest.getId))
        )
        val round = Round.create(
          Round(None, 1, None, contest.getId, rates = Round.commentsOnlyRound, active = true)
        )
        round.addUsers(Seq(RoundUser(round.getId, user.getId, "jury", active = true)))
        val signedIn = Secured.inCurrentContest(user, None)

        val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
        val result = controller.onUnAuthorized(signedIn, FakeRequest("GET", "/contest/999/files"))
        val html = result.body.asInstanceOf[HttpEntity.Strict].data.utf8String
        val firstGalleryPage = routes.GalleryController.byRate(signedIn.getId, 1, "all", round.getId).url

        result.header.status === Helpers.FORBIDDEN
        html must contain(firstGalleryPage)
      }
    }

    "suggest accessible contest files and keep that destination in the navigation" in {
      testDbApp { implicit app =>
        createContests(2025, 603)
        val organizer = User.create(
          User("Organizer", "organizer@server.com", None, Set("organizer"), Some("hash"), Some(2025L))
        )
        val signedIn = Secured.inCurrentContest(organizer, Some("2025"))
        val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
        val result = controller.onUnAuthorized(signedIn, FakeRequest("GET", "/contest/603/files"))
        val html = result.body.asInstanceOf[HttpEntity.Strict].data.utf8String
        val accessibleFiles = routes.GalleryController.contestFiles(2025).url

        result.header.status === Helpers.FORBIDDEN
        html must contain(accessibleFiles)
        html must contain("menu-contest-files")
      }
    }
  }

  "access denial page" should {
    def page(user: User, denial: Secured.Denial)(implicit app: play.api.Application) = {
      val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
      val result = controller.onUnAuthorized(user, FakeRequest("GET", "/admin/rounds?contestId=603"), denial)
      (result.header.status, result.body.asInstanceOf[HttpEntity.Strict].data.utf8String)
    }

    "say what the page needs, the user's role here, whom to ask and the reference" in {
      testDbApp { implicit app =>
        createContests(2025, 603)
        val juror = User.create(User("Juror", "juror@server.com", None, Set("jury"), Some("hash"), Some(2025L)))
        val (status, html) = page(Secured.inCurrentContest(juror, None),
          Secured.Denial.contest(Some(603L), User.ADMIN_ROLES))

        status === Helpers.FORBIDDEN
        html must contain("This page is for: Admin, Root.")
        html must contain("Your role in contest2025 4025 in country2025: Juror.")
        html must contain("If you need access, ask your contest organizer.")
        html must beMatching("""(?s).*Reference: [0-9a-f]{8}, \d{4}-\d\d-\d\d \d\d:\d\d UTC.*""")
        // contest 603 is not the user's: not named
        html must not(contain("contest603"))
        html must not(contain("has been deactivated"))
        // nothing to suggest: signing out is all there is
        html must not(contain("btn-primary"))
        html must contain("btn btn-default")
      }
    }

    "tell a deactivated member so" in {
      testDbApp { implicit app =>
        createContests(2025, 603)
        val juror = User.create(User("Juror", "juror@server.com", None, Set("jury"), Some("hash"), Some(2025L)))
        UserContestJdbc.createMembership(juror.getId, 603L, User.NO_ROLE)
        val (_, html) = page(Secured.inCurrentContest(juror, None), Secured.Denial.contest(Some(603L), Set("jury")))

        html must contain("Your membership in this contest has been deactivated.")
      }
    }

    "suggest a gallery of the current contest before another contest's files" in {
      testDbApp { implicit app =>
        createContests(2025, 603)
        val juror = User.create(User("Juror", "juror@server.com", None, Set("jury"), Some("hash"), Some(2025L)))
        UserContestJdbc.createMembership(juror.getId, 603L, "organizer")
        val round = Round.create(Round(None, 1, None, 2025L, rates = Round.binaryRound, active = true))
        round.addUsers(Seq(RoundUser(round.getId, juror.getId, "jury", active = true)))
        val signedIn = Secured.inCurrentContest(juror, Some("2025"))
        val (_, html) = page(signedIn, Secured.Denial.empty)

        val gallery = routes.GalleryController.list(signedIn.getId, 1, "all", round.getId, Some(0)).url
        html must contain(s"""<a class="btn btn-primary" href="${gallery.replace("&", "&amp;")}">""")
        html must not(contain(routes.GalleryController.contestFiles(603).url))
        // without a known requirement, no "This page is for"
        html must not(contain("This page is for"))
      }
    }
  }

  "the denial's log line" should {
    val user = User("Ann", "Ann\"x\n@server.com", Some(7L), Set("admin", "USER_ID_7"), contestId = Some(10L),
      userContests = Seq(UserContest(7L, 10L, "admin"), UserContest(7L, 20L, User.NO_ROLE), UserContest(7L, 40L, "jury")))
    val request = FakeRequest("GET", "/admin/jobs/5?x=1")

    "have the request's, the account's and the target's fields, untrusted text quoted" in {
      val line = Secured.denialLogLine(user, request, Secured.Denial(Some(30L), Some("job:5"), User.ADMIN_ROLES), "0a1b2c3d")
      line === "authorization_denied ref=0a1b2c3d accountId=7 account=\"ann\\\"x\\n@server.com\" method=GET " +
        "path=\"/admin/jobs/5\" currentContestId=10 targetContestId=30 resource=\"job:5\" " +
        "requiredRoles=\"admin,root\" accountRoles=\"admin\" targetContestMembershipState=missing " +
        "targetContestRoles=\"none\" reason=not_member"
    }

    "derive the reason from the target contest's membership" in {
      def reason(denial: Secured.Denial) = Secured.denialLogLine(user, request, denial, "0a1b2c3d").split(" ").last

      reason(Secured.Denial(Some(20L))) === "reason=membership_inactive"
      reason(Secured.Denial(Some(40L), requiredRoles = User.ADMIN_ROLES)) === "reason=missing_role"
      reason(Secured.Denial(Some(40L), requiredRoles = Set("jury"))) === "reason=resource_in_other_contest"
      reason(Secured.Denial(Some(40L), requiredRoles = Set("jury"), reason = Some("custom"))) === "reason=custom"
      reason(Secured.Denial.empty) === "reason=permission_check_failed"
      Secured.denialReason(user.copy(userContests = Nil, contestId = None), Secured.Denial.empty) === "not_member"
    }

    "say what is unknown" in {
      val line = Secured.denialLogLine(user, request, Secured.Denial.empty, "0a1b2c3d")
      line must contain("targetContestId=unknown resource=unknown requiredRoles=\"unknown\"")
      line must contain("targetContestMembershipState=not_applicable targetContestRoles=\"not_applicable\"")
    }

    "be found by an 8-character hex reference" in {
      Secured.newReference() must beMatching("[0-9a-f]{8}")
    }

    "be logged for a role permission's denial with its roles" in {
      Secured.denialOf(Secured.RolePermission(User.ADMIN_ROLES)).requiredRoles === User.ADMIN_ROLES
      Secured.denialOf(Secured.ContestPermission(Set("jury"), Some(5L))) ===
        Secured.Denial(Some(5L), Some("contest:5"), Set("jury"))
      Secured.denialOf((_: User) => false) === Secured.Denial.empty
    }
  }

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

  }

}
