package controllers

import db.scalikejdbc.PlayTestDb
import play.api.Application
import play.api.test.CSRFTokenHelper._
import play.api.test.{FakeRequest, PlaySpecification}

/** A path with a trailing slash that no route takes (`/auth/`) is redirected, through the
  * router, to the path without it ([[TrailingSlashController]]): GET and HEAD only, the query
  * kept, never off the site.
  */
class TrailingSlashSpec extends PlaySpecification with PlayTestDb {

  sequential

  private def send(app: Application, method: String, path: String) = {
    val request = FakeRequest(method, path).withHeaders(ACCEPT -> "text/html")
    (if (method == POST) route(app, request.withFormUrlEncodedBody().withCSRFToken) else route(app, request.withCSRFToken))
      .getOrElse(throw new IllegalStateException(s"no handler for $method $path"))
  }

  private def get(app: Application, path: String) = send(app, GET, path)

  "a GET with a trailing slash" should {

    "be redirected (301) to the path without it, the query kept" in {
      testDbApp { app =>
        val auth = get(app, "/auth/")
        val withQuery = get(app, "/auth/?returnTo=%2Frounds&x=1")
        val several = get(app, "/admin/rounds///")
        (status(auth) === MOVED_PERMANENTLY) and
          (redirectLocation(auth) must beSome("/auth")) and
          (redirectLocation(withQuery) must beSome("/auth?returnTo=%2Frounds&x=1")) and
          (redirectLocation(several) must beSome("/admin/rounds"))
      }
    }

    "answer a HEAD the same way" in {
      testDbApp { app =>
        val head = send(app, HEAD, "/auth/")
        (status(head) === MOVED_PERMANENTLY) and (redirectLocation(head) must beSome("/auth"))
      }
    }

    "take a signed-out user to the target, which sends them to sign in with its returnTo" in {
      testDbApp { app =>
        val first = get(app, "/admin/rounds/")
        redirectLocation(first) must beSome("/admin/rounds")
        val second = get(app, "/admin/rounds")
        (status(second) === SEE_OTHER) and
          (redirectLocation(second) must beSome(beMatching("/auth\\?returnTo=%2Fadmin%2Frounds.*")))
      }
    }

    "never leave the site: an empty, scheme-relative or backslash path is the 404 page" in {
      testDbApp { app =>
        val offSite = Seq("//evil.example/", "///evil.example/", "/%2F%2Fevil.example/", "/%2f/evil.example/",
          "/\\evil/", "/%5Cevil/", "/%5C%5Cevil.example/", "///")
        val answers = offSite.map(p => p -> (status(get(app, p)), redirectLocation(get(app, p))))
        answers.filterNot { case (_, (st, location)) => st == NOT_FOUND && location.isEmpty } must beEmpty
      }
    }

    "redirect an encoded character that stays in the path to the same site" in {
      testDbApp { app =>
        redirectLocation(get(app, "/rounds%20x/")) must beSome("/rounds%20x")
      }
    }
  }

  "the root and other methods" should {

    "leave / to its own route" in {
      testDbApp { app =>
        val root = get(app, "/")
        status(root) !== MOVED_PERMANENTLY
      }
    }

    "leave a POST with a trailing slash a 404" in {
      testDbApp { app =>
        val post = send(app, POST, "/auth/")
        (status(post) === NOT_FOUND) and (redirectLocation(post) must beNone)
      }
    }
  }

  "the target" should {

    "be a local path or none" in {
      def target(path: String) = TrailingSlashController.target(FakeRequest(GET, path))
      (target("/auth/") must beSome("/auth")) and
        (target("/auth") must beNone) and
        (target("/") must beNone) and
        (target("//evil.example/") must beNone) and
        (target("/%2F%2Fevil.example/") must beNone) and
        (target("/\\evil/") must beNone)
    }
  }
}
