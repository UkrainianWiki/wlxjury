package controllers

import db.scalikejdbc.{PlayTestDb, Round, User, UserContestJdbc}
import play.api.Application
import play.api.libs.json.Json
import play.api.mvc.{ControllerComponents, Result}
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

import scala.concurrent.Future

/** The denial page of a page of a contest the account has no role in (a link to a round of
  * another contest): it says so first, without naming that contest, lists the account's own
  * contests with its role in each, and says whom to ask; it no longer puts "this page is for:
  * Admin" beside "your role in <the account's contest>: Admin" (26.11.5, reference 5b176f4a).
  */
class DenialContestReasonSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def member(email: String, memberships: (Long, String)*): User = {
    val created = User.create(User(email, email, None, Set.empty, Some("hash")))
    memberships.foreach { case (contest, role) => UserContestJdbc.createMembership(created.getId, contest, role) }
    created
  }

  /** Contests 10 and 20, each with an open round; the round of 20. */
  private def fixture(): Round = {
    createContests(10, 20)
    Round.create(Round(None, 1, Some("Round of ten"), 10, active = true))
    Round.create(Round(None, 1, Some("Round of twenty"), 20, active = true))
  }

  private def get(path: String, user: User, currentContest: Option[Long], headers: (String, String)*)(implicit
      app: Application
  ): Future[Result] =
    route(app,
      FakeRequest(GET, path)
        .withSession(Seq(Secured.UserName -> user.email) ++ currentContest.map(Secured.CurrentContestId -> _.toString): _*)
        .withHeaders(headers: _*)
        .withCSRFToken
    ).get

  private def withAuthzLog[T](f: => T): (T, Seq[String]) = {
    val logger = org.slf4j.LoggerFactory.getLogger("security.authz").asInstanceOf[ch.qos.logback.classic.Logger]
    val appender = new ch.qos.logback.core.read.ListAppender[ch.qos.logback.classic.spi.ILoggingEvent]
    appender.start()
    logger.addAppender(appender)
    try {
      val result = f
      import scala.jdk.CollectionConverters._
      (result, appender.list.asScala.toSeq.map(_.getFormattedMessage))
    } finally logger.detachAppender(appender)
  }

  private def gallery(round: Round) = s"/gallery/round/${round.getId}/user/0/page/1"

  "a page of a contest the account has no role in" should {
    "say so, list the account's contests with its roles, and whom to ask, without naming that contest" in {
      testDbApp { implicit app =>
        val round = fixture()
        val admin = member("ann@example.com", 10L -> "admin")

        val (result, log) = withAuthzLog {
          val result = get(gallery(round), admin, Some(10L))
          status(result)
          result
        }
        val html = contentAsString(result)

        status(result) === FORBIDDEN
        html must contain("You are signed in as ann@example.com.")
        html must contain("The requested page belongs to a contest in which this account has no role.")
        html must contain("This account’s roles:")
        html must contain("<li>Admin in “contest10 2010 in country10”</li>")
        html must contain("If you need this page, ask an admin of that contest to add this account to it.")
        html must beMatching("""(?s).*Reference: [0-9a-f]{8}, \d{4}-\d\d-\d\d \d\d:\d\d UTC.*""")
        // what the page needs in the other contest beside the account's role was the contradiction
        html must not(contain("This page is for"))
        html must not(contain("Your role in"))
        html must not(contain("ask your contest admin"))
        html must not(contain("does not have permission to open"))
        // the other contest and its round are not named
        html must not(contain("contest20"))
        html must not(contain("Round of twenty"))
        // the way forward: the account's own contest, and signing in with another account
        html must contain(routes.GalleryController.contestFiles(10).url)
        html must contain("Sign out and sign in with another account")
        // the log line has the target contest and the reason
        log.count(line =>
          line.contains("currentContestId=10 targetContestId=20 ") && line.contains(s"resource=\"round:${round.getId}\"") &&
            line.contains("targetContestMembershipState=missing") && line.endsWith("reason=not_member")) === 1
      }
    }

    "list every contest of the account, the current one first, in each language" in {
      testDbApp { implicit app =>
        val round = fixture()
        createContests(30)
        val user = member("ann@example.com", 10L -> "jury", 30L -> "organizer")

        def page(lang: String) =
          contentAsString(get(gallery(round), user, Some(10L), "Accept-Language" -> lang))

        page("en") must beMatching(
          """(?s).*<li>Juror in “contest10 2010 in country10”</li>\s*<li>Organizer in “contest30 2030 in country30”</li>.*""")
        val uk = page("uk")
        uk must contain("Запитана сторінка належить конкурсу, у якому цей обліковий запис не має ролі.")
        uk must contain("Ролі цього облікового запису:")
        uk must contain("<li>Член журі у «contest10 2010 in country10»</li>")
        uk must contain("<li>Організатор у «contest30 2030 in country30»</li>")
        uk must contain("Якщо вам потрібна ця сторінка, попросіть адміністратора того конкурсу додати до нього цей обліковий запис.")
        val ru = page("ru")
        ru must contain("Запрошенная страница относится к конкурсу, в котором у этой учётной записи нет роли.")
        ru must contain("Роли этой учётной записи:")
        ru must contain("<li>Член жюри в «contest10 2010 in country10»</li>")
        ru must contain("<li>Организатор в «contest30 2030 in country30»</li>")
        ru must contain("Если вам нужна эта страница, попросите администратора того конкурса добавить в него эту учётную запись.")
        val fr = page("fr")
        fr must contain("La page demandée appartient à un concours dans lequel ce compte n’a aucun rôle.")
        fr must contain("Rôles de ce compte :")
        fr must contain("<li>Juré dans « contest10 2010 in country10 »</li>")
        fr must contain("<li>Organisateur dans « contest30 2030 in country30 »</li>")
        fr must contain("Si vous avez besoin de cette page, demandez à un administrateur de ce concours d’y ajouter ce compte.")
        Seq(uk, ru, fr).foreach(_ must not(contain("contest20")))
        ok
      }
    }

    "tell a former member of that contest it was deactivated" in {
      testDbApp { implicit app =>
        val round = fixture()
        val user = member("ann@example.com", 10L -> "admin", 20L -> User.NO_ROLE)
        val html = contentAsString(get(gallery(round), user, Some(10L)))

        html must contain("The requested page belongs to a contest in which this account has no role.")
        html must contain("Your membership in this contest has been deactivated.")
        html must contain("This account’s roles:")
        html must contain("<li>Admin in “contest10 2010 in country10”</li>")
        html must not(contain("contest20"))
      }
    }

    "answer a script with the JSON denial, no contest named, logged with the target contest" in {
      testDbApp { implicit app =>
        fixture()
        val admin = member("ann@example.com", 10L -> "admin")

        val (result, log) = withAuthzLog {
          val result = get("/contest/20/files/regions", admin, Some(10L), "X-Requested-With" -> "XMLHttpRequest")
          status(result)
          result
        }

        status(result) === FORBIDDEN
        val json = contentAsJson(result)
        (json \ "error").as[String] === "forbidden"
        (json \ "reference").as[String] must beMatching("[0-9a-f]{8}")
        json.as[play.api.libs.json.JsObject].keys === Set("error", "reference")
        Json.stringify(json) must not(contain("contest"))
        log.count(line =>
          line.contains("targetContestId=20 resource=\"contest:20\"") && line.endsWith("reason=not_member")) === 1
      }
    }
  }

  "a page of another of the account's contests" should {
    "open in that contest when the account's role there lets it in: no denial" in {
      testDbApp { implicit app =>
        val round = fixture()
        val user = member("ann@example.com", 10L -> "admin", 20L -> "admin")
        val result = get(gallery(round), user, Some(10L))

        status(result) === OK
        session(result).get(Secured.CurrentContestId) must beSome("20")
        // a script's request is let in too, without switching the session's contest
        status(get("/contest/20/files/regions", user, Some(10L), "X-Requested-With" -> "XMLHttpRequest")) === OK
      }
    }

    "give the account's role in that contest, not in the session's current one" in {
      testDbApp { implicit app =>
        fixture()
        val user = member("ann@example.com", 10L -> "admin", 20L -> "jury")
        val result = get("/admin/rounds?contestId=20", user, Some(10L))
        val html = contentAsString(result)

        status(result) === FORBIDDEN
        html must contain("This page is for: Admin, Root.")
        html must contain("Your role in contest20 2020 in country20: Juror.")
        html must contain("If you need access, ask your contest admin.")
        html must not(contain("Your role in contest10"))
        html must not(contain("belongs to a contest in which this account has no role"))
      }
    }
  }

  "root" should {
    "open a round of any contest" in {
      testDbApp { implicit app =>
        val round = fixture()
        val root = User.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash")))
        status(get(gallery(round), root, None)) === OK
      }
    }

    "keep what a refused vote needs: no list of contests" in {
      testDbApp { implicit app =>
        val round = fixture()
        val root = Secured.inCurrentContest(
          User.create(User("Root", "root@example.com", None, Set(User.ROOT_ROLE), Some("hash"))), None)
        val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
        val result = controller.onUnAuthorized(root, FakeRequest(GET, gallery(round)),
          Secured.Denial.round(round, User.VoterRoles))
        val html = contentAsString(Future.successful(result))

        html must contain("This page is for: Juror.")
        html must not(contain("belongs to a contest in which this account has no role"))
        html must not(contain("This account’s roles"))
      }
    }
  }

  "an account without an active role anywhere" should {
    "say it has no role in any contest" in {
      testDbApp { implicit app =>
        val round = fixture()
        val user = Secured.inCurrentContest(member("ann@example.com", 10L -> User.NO_ROLE), None)
        val controller = new Secured(app.injector.instanceOf[ControllerComponents]) {}
        val result = controller.onUnAuthorized(user, FakeRequest(GET, gallery(round)),
          Secured.Denial.round(round, Secured.ViewerRoles))
        val html = contentAsString(Future.successful(result))

        html must contain("The requested page belongs to a contest in which this account has no role.")
        html must contain("This account has no role in any contest.")
        html must not(contain("contest10"))
        html must not(contain("contest20"))
      }
    }
  }
}
