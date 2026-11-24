package api

import controllers.LogCapture
import db.scalikejdbc.{ApiTokenJdbc, ApiTokenScope, PlayTestDb, User}
import play.api.libs.json.Json
import play.api.test.{FakeRequest, PlaySpecification}
import scalikejdbc._
import services.ApiTokenService

/** An account an account join retires (J-2) is refused on the API as it is on the session path:
  * its tokens, of either scope, get 401 on a REST endpoint and on `/api/graphql`, the refusal is
  * logged by token id (never the value), nothing is recorded as a use, and a normal account is
  * not affected.
  */
class ApiTokenRetiringSpec extends PlaySpecification with PlayTestDb {

  sequential

  private val rootEmail = "root@example.com"
  private val retiringEmail = "retiring@example.com"

  private def setUp(): Unit = {
    createContests(10)
    User.create(User(rootEmail, rootEmail, isRoot = true))
    User.create(User(retiringEmail, retiringEmail, isRoot = true))
  }

  private def userId(email: String): Long = User.findByEmail(email).head.getId

  private def tokenOf(email: String, scope: ApiTokenScope = ApiTokenScope.Full): String =
    ApiTokenService.create(userId(email), "cli", Some(90), scope = scope)

  /** A running join of root's account (kept) with the retiring one (joined). */
  private def holdJoin(): Unit = DB.autoCommit { implicit s =>
    sql"""INSERT INTO job (kind, contest_id, subject_type, subject_id, dedupe_key, status, params, state)
          VALUES (${db.scalikejdbc.AccountJoinJdbc.Kind}, 10, 'user', ${userId(rootEmail)}, 'join-test', 'running',
            ${s"""{"joinedId": ${userId(retiringEmail)}}"""}, '{}')""".update()
  }

  private def tokenId(token: String): Long = ApiTokenJdbc.findByHash(ApiTokenService.hash(token)).get.id

  "a token of an account an account join retires" should {

    "be refused with 401 on REST and GraphQL, both scopes, logged by id; a normal account's token works" in {
      testDbApp { app =>
        setUp()
        val full = tokenOf(retiringEmail)
        val local = tokenOf(retiringEmail, ApiTokenScope.LocalRead)
        val normal = tokenOf(rootEmail)
        def rest(t: String) = route(app, FakeRequest(GET, "/api/contests").withHeaders(AUTHORIZATION -> s"Bearer $t")).get
        def gql(t: String) = route(app, FakeRequest(POST, "/api/graphql").withHeaders(AUTHORIZATION -> s"Bearer $t")
          .withJsonBody(Json.obj("query" -> "{ me { id } }"))).get
        status(rest(full)) === OK
        status(gql(full)) === OK

        holdJoin()
        val (results, logs) = LogCapture("services.ApiTokenService") {
          Seq(rest(full), rest(local), gql(full), gql(local)).map(status(_))
        }
        results === Seq(UNAUTHORIZED, UNAUTHORIZED, UNAUTHORIZED, UNAUTHORIZED)
        val lines = logs("services.ApiTokenService")
        lines.count(_.contains("reason=account_retiring")) === 4
        lines.exists(_.contains(s"tokenId=${tokenId(full)}")) must beTrue
        lines.exists(_.contains(s"tokenId=${tokenId(local)}")) must beTrue
        lines.mkString("\n") must not(contain(full))
        lines.mkString("\n") must not(contain(local))
        ApiTokenService.authenticate(full) must beNone

        // the kept account (root's, holding the join) and unrelated accounts are not the retiring one
        status(rest(normal)) === OK
        status(gql(normal)) === OK
      }
    }
  }
}
