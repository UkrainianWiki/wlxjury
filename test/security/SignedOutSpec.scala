package security

import db.scalikejdbc.PlayTestDb
import play.api.Application
import play.api.test.{FakeRequest, PlaySpecification}
import views.AppRoutes

/** G2b: every route that is not public (`RouteAuthSpec.Public`), sent without a session
  * through the router with placeholder ids, is refused: a page request gets the login
  * redirect or a 401, a script request (`X-Requested-With`) a 401. No action runs signed out.
  */
class SignedOutSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val PathParam = """\$\w+<[^>]+>""".r

  /** The route's parameters that are neither in its path nor fixed nor defaulted, as
    * `name=value` (a number, `false` or text by type), for the query string.
    */
  private def requiredQuery(route: AppRoutes.RouteDoc): Seq[String] = {
    val inPath = """\$(\w+)<""".r.findAllMatchIn(route.pattern).map(_.group(1)).toSet
    val params = route.controller.dropWhile(_ != '(').drop(1).dropRight(1)
    params.split(",\\s*(?=\\w+\\s*:)").toSeq.map(_.trim).filter(_.nonEmpty).flatMap { p =>
      val name = p.takeWhile(c => c.isLetterOrDigit || c == '_')
      val tpe = p.drop(name.length).dropWhile(c => c == ':' || c == ' ')
      if (inPath(name) || p.contains(" = ") || p.contains("?=")) None
      else Some(s"$name=" + (if (tpe.contains("Boolean")) "false" else if (tpe.matches(".*(Long|Int).*")) "1" else "x"))
    }
  }

  private def url(route: AppRoutes.RouteDoc): String = {
    val path = PathParam.replaceAllIn(route.pattern, "1")
    val query = requiredQuery(route)
    if (query.isEmpty) path else path + "?" + query.mkString("&")
  }

  private def nonPublic: Seq[AppRoutes.RouteDoc] =
    AppRoutes.all.filterNot(r => RouteAuthSpec.Public.contains(r.action)).distinctBy(r => (r.method, r.pattern))

  private def statusAndLocation(app: Application, r: AppRoutes.RouteDoc, script: Boolean): (Int, Option[String]) = {
    val headers = Seq(ACCEPT -> "text/html") ++ (if (script) Seq("X-Requested-With" -> "XMLHttpRequest") else Nil)
    val request = FakeRequest(r.method, url(r)).withHeaders(headers: _*)
    val result =
      (if (r.method == POST) route(app, request.withFormUrlEncodedBody()) else route(app, request))
        .getOrElse(throw new IllegalStateException(s"no handler for ${r.method} ${url(r)}"))
    (status(result), redirectLocation(result))
  }

  "a request without a session" should {

    "reach every non-public route as a page and be sent to sign in, or get 401" in {
      testDbApp { app =>
        val refused = nonPublic.flatMap { r =>
          statusAndLocation(app, r, script = false) match {
            case (SEE_OTHER, Some(location)) if location.startsWith("/auth") => None
            case (UNAUTHORIZED, _)                                          => None
            case other                                                      => Some(s"${r.method} ${url(r)} -> $other")
          }
        }
        (nonPublic.size must beGreaterThan(50)) and (refused must beEmpty)
      }
    }

    "get 401 from every non-public route when sent by script" in {
      testDbApp { app =>
        val refused = nonPublic.flatMap { r =>
          statusAndLocation(app, r, script = true) match {
            case (UNAUTHORIZED, _) => None
            case other             => Some(s"${r.method} ${url(r)} -> $other")
          }
        }
        refused must beEmpty
      }
    }
  }
}
