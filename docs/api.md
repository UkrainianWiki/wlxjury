# REST API

The jury tool serves a small REST API under `/api` (Tapir, `app/api/Api.scala`). Swagger UI
and the OpenAPI document are public at `/api/docs` (`/api/docs/docs.yaml`); they hold no data.

So far the API has the contest endpoints, for root only, as the contest pages are, and
read-only diagnostics endpoints (support diagnostics plan, D-1b) that the `wlxjury-diag` tool
reads ([operations §5.5](operations/practices.md)):

| Method | Path | Who | |
|---|---|---|---|
| `GET` | `/api/contests` | root | all contests |
| `GET` | `/api/contests/{id}` | root | one contest |
| `POST` | `/api/contests` | root | create a contest (an `id` in the body is ignored) |
| `PUT` | `/api/contests` | root | update the contest of the body's `id` |
| `GET` | `/api/contests/{id}/health` | root and the contest's admins | the contest's health report, as its health page shows it |
| `GET` | `/api/diagnostics/recent?hours=24` | root | every contest's jobs and round status changes of the last `hours` (1 to 720, 24 by default), the worst first, and the jobs of no contest |
| `GET` | `/api/diagnostics/reuse` | root | the reuse dry-run report: the latest scan job's progress and the latest finished scan's totals and reported contests, as root's reuse page shows them |
| `GET` | `/api/diagnostics/reuse/{id}` | root | one contest's reuse report: its clusters, read now against the latest finished scan's eras |

All `GET` endpoints above, the contests' reads included, are open to read-only, server-only tokens (`ApiAuth.Endpoints`); `POST` and `PUT` are not.

**The health report** (`/api/contests/{id}/health`) has the contest (`id`, `name`, `year`,
`country`, `retired`), its `outcome` (`pass`, `info`, `warn`, `fail`: the worst check's), `files`,
`recentActivity`, the `checks` (`code` H1…H12, `title`, `outcome`, and `hints`, each with its
`outcome`, message `key`, numeric `args` and English `text`), the `imports` (job id, status, source,
counts, error key and reference, and who started it as the health page names them to you) and the
`rounds` (id, number, status, files, active jurors, the checks that flag it). The contest's admins
get it as active members of the contest; organizers, jurors and other contests' admins get 403. A
retired contest's report is root's only, as the page is. **The recent report** groups by contest
(`contest`, `outcome`, `jobs`, `roundChanges`); a failed job that a later job of the same subject
did not follow is `fail`, a job queued or running for over 30 minutes `warn`. It reads no log: the
log's denials are `wlxjury-diag`'s to add.

## Signing in

- **A personal API token**, for command-line tools: `Authorization: Bearer wlxj_...`. Root creates
  one on their own profile page (their name in the navbar, then "API tokens"; other roles have no such card), with a name and an
  expiry of 30 days, 90 days, a year or never. The token is shown once, right after you
  create it: copy it then. Revoke it there when you no longer need it, or if it may have
  leaked. You can have at most 20 usable (not revoked, not expired) tokens. A token acts as
  you, with your memberships and roles; it can't create or revoke tokens. Keep it out of shell history and scripts you share (an environment variable or a
  file only you can read).
- **Its access** is chosen when you create it: **Full** (the default, and every token made
  before V110) reads and writes from anywhere; **Read-only, from the server only** is taken on
  `GET` only, and only in a request made on the server itself: from a loopback address
  (`127.0.0.1`, `::1`) with no `Forwarded` or `X-Forwarded-*` header, so never through the
  website (Apache adds `X-Forwarded-For` to every request it passes on). Anything else with it
  gets 403 `forbidden`. It is the token for `wlxjury-diag` over ssh: if it leaks, it reads only
  what your account may read, only from the server, and writes nothing.
- **The session cookie**, for a page of the tool calling the API from the browser. A write
  (`POST`, `PUT`, `DELETE`) then needs the `Csrf-Token` header, as the gallery's votes send it.

A request with an `Authorization` header is judged by that header alone: a bad token is
refused even if the request also carries a signed-in session cookie. A write with a token
needs no CSRF token only when the request carries no session cookie: a tool's request.

**Swagger UI's "Try it out"** works for reads (`GET`) in a signed-in browser, with the session.
Writes from it fail with `{"error": "csrf"}`: the browser sends the session cookie, and Swagger
UI sends no `Csrf-Token` header. This is on purpose (a request with the session cookie always
needs the CSRF token, a Bearer header or not); make writes with `curl` or a tool and a token.

```bash
export WLXJURY_TOKEN=wlxj_...   # from your profile page
curl -H "Authorization: Bearer $WLXJURY_TOKEN" https://jury.wle.org.ua/api/contests

# PUT takes the whole contest, as GET gives it: fetch, edit, send back
curl -H "Authorization: Bearer $WLXJURY_TOKEN" https://jury.wle.org.ua/api/contests/603 > contest.json
curl -H "Authorization: Bearer $WLXJURY_TOKEN" -H "Content-Type: application/json" \
     -X PUT --data @contest.json https://jury.wle.org.ua/api/contests
```

The contest JSON has no `currentRound` field (removed; it was always null). A body that still
sends it is accepted and the key is ignored.

## Errors

Errors are JSON, with a fixed code and never the request's text:

| Status | Body | When |
|---|---|---|
| 400 | `{"error": "bad_request"}` | a body or path that can't be read, or a `Host` that isn't one of the deployment's names (`WLXJURY_ALLOWED_HOSTS`, docs/deployment.md): call the API by the jury tool's host name, not the server's address |
| 401 | `{"error": "unauthenticated"}`, `WWW-Authenticate: Bearer` | no credentials, or a token that is unknown, revoked, expired or malformed |
| 403 | `{"error": "forbidden", "reference": "1a2b3c4d"}` | signed in, but not allowed, or a read-only token used for a write or not from the server itself; the reference finds the log line |
| 403 | `{"error": "csrf", "reference": "…"}` | a session's write without a valid `Csrf-Token` header |
| 404 | `{"error": "not_found"}` | no such contest, or no such path under `/api` |
| 413 | `{"error": "payload_too_large"}` | a body over Play's parser limit (`play.http.parser.maxMemoryBuffer`) |
| 500 | `{"error": "server_error", "reference": "…"}` | a server error, logged with the reference |

## For operators

- Tokens are stored as their SHA-256 only (`api_token`, migration V75), with a prefix
  (`wlxj_` and 6 characters) that names them in the profile list and in log lines.
- A refused token is logged as `api_token_refused reason=… tokenId=…`, never its value
  (logger `services.ApiTokenService`): a revoked or expired token, or one of a deleted user or an account an account join is retiring,
  at WARN (a revoked token still in use is a sign it leaked); an unknown one at INFO with its
  prefix (`services.ApiTokenService` is at INFO in `conf/logback.xml` and in production's
  `conf/logback-production.xml`, which ships in the release); a malformed token or another
  `Authorization` scheme only at DEBUG, without a database lookup. A denial is logged on `security.authz` with its
  reference and `auth=session` or `auth=token tokenId=…` (and `scope=local_read` for a read-only
  token). A read-only token refused by its scope is logged there too, before any permission check,
  as `authorization_denied … resource=api:<endpoint> … reason=token_read_only` (not a `GET`),
  `reason=token_endpoint` (an endpoint not open to read-only tokens: each endpoint is classified
  in `ApiAuth.Endpoints`, a new one closed until it is) or `reason=token_not_local` (not from the
  server itself), then `auth=token tokenId=… scope=local_read remote="…"`: the client's address as
  the app resolved it (behind Apache, the forwarded one), to see where a leaked token is used.
  A token whose stored scope this release doesn't know (a newer release's, after a backout) is
  refused as `api_token_refused reason=unknown_scope`, never taken as full.
- The scope is `api_token.scope` (V110: `full` or `local_read`, a CHECK keeps the two). Before a
  rollback of the migration (its last comment), revoke the read-only tokens: an older release
  ignores the column and would let them write.

## For developers

- **Serving.** Tapir's Play interpreter (`tapir-play-server`) serves `app/api/Api.scala` under
  `/api` (`-> /api api.ApiRouter` in `conf/routes`), so requests pass Play's filters and run on
  the blocking dispatcher for JDBC.
- **Who calls** (`api.ApiAuth.caller`): an `Authorization` header must be `Bearer <personal
  token>` and the token usable, else 401 (the session is then ignored, never mixed in); without
  the header, the session user as `Secured.userFromRequest` gives it. Permissions are the
  `Secured` ones (`Secured.checked`).
- **Errors** are built by `api.ApiFailure`, also by `ErrorHandler` and `CsrfErrorHandler` for any
  path under `/api/`. A 401 is the same as `withAuthJson`'s; a 403 carries the reference of
  `Secured.logDenial`'s line; a 400 never repeats the input.
- **Tokens** (`services.ApiTokenService`, `ApiTokenJdbc`): `wlxj_` + 32 random bytes base64url.
  Shown once, in the response that creates it (`no-store`), never flashed, logged or put in a
  URL. `last_used_at` is written at most once a minute. Created by root only, on root's own
  profile (`UserController.createApiToken`, `rolePermission(ROOT_ROLE)`; `revokeApiToken`; CSRF
  POST forms, session only); the create form carries a one-time nonce from the session
  (`ApiTokensCard.NonceKey`), so reloading the creating response makes no second token; a user
  revokes only their own. Tokens are accepted only under `/api/`: the page routes read the
  session alone.
- **Token scope** (`api_token.scope`, V110): `full` (default) or `local_read`, which
  `ApiAuth.scopeRefusal` lets in only on a GET of an endpoint allowlisted in `ApiAuth.Endpoints`
  (every endpoint is named there; `ApiEndpointScopeSpec`), from the server itself
  (`ApiAuth.fromServerItself`: loopback, no `Forwarded` / `X-Forwarded-*`); a new GET endpoint must
  be classified there, and only one that only reads may be open to `local_read`.
- **CSRF.** `modules.CsrfModule` replaces Play's CSRF module (same filter class) so that only a
  request under `/api/` with a Bearer header and without the session cookie skips the check
  (`ApiAuth.bearerWithoutSession`).
- **Tests.** A FakeRequest's cookies are an attribute (`withCookies`); a `Cookie` header alone is
  not read as cookies. `api.ApiSpec` covers the API's own authentication.

## GraphQL

`POST /api/graphql` serves a GraphQL API for the tool's own React screens (the GraphQL plan,
`docs/plans/2026-11-24-graphql-sangria.md`; Sangria). It sits beside the REST endpoints, under the
same `/api` router, so it takes the same callers, the same `Host` check and the same CSRF rule.
The schema is the checked-in SDL, [`conf/graphql/schema.graphql`](../conf/graphql/schema.graphql):
the contract the UI is written against. So far (slices 1 and 2) it has queries only:

| Query | Who sees what |
|---|---|
| `me` | the caller: id, name, their own `email` (every role), whether root, the session's current contest, `authMethod` (`SESSION` or `TOKEN`). Other accounts' emails, when a later slice lists users, only for root and for the Admins of a contest the account is a member of |
| `contests(offset, limit)` | root: every contest, retired ones too; anyone else: the contests where they hold an access role (juror, organizer, admin) in a contest not retired, and, from a session, the contests a series admin's grants reach (below). The latest year first; `limit` 100 by default, clamped to 1..200; `totalCount`, and each contest's `viewerRoles` |
| `contest(id)` | one of those contests, else null with a `NOT_FOUND` error: the same answer for a missing contest and for one the caller is not in (the second is logged as a denial) |
| a contest's `rounds` | all its rounds for root and its admins and organizers; for a juror, the rounds they are a juror of, once distributed (a round being created or in review is not listed), also one they are stopped in |
| a round's `myStatus` | the caller as a juror of the round: `ACTIVE`, `STOPPED` (stopped on it by its admins: still listed, no new files, no votes), or null when not one of its jurors |
| a round's `othersVotesVisible` | whether the caller may see other jurors' votes there, as in its galleries: root and the contest's admins and organizers, but not while the round is open if they are one of its jurors (conflict of interest: their own votes only); never a juror |
| `round(id)` | one round, if the caller sees it in its contest's `rounds` (above); else null with `NOT_FOUND`, the same for a missing round, a deleted one, one of a contest the caller is not in, and (for a juror) one they don't judge or one being created (the denials logged). A round's `contestId` names its contest |
| `roundStat(roundId)` | the round statistics page's numbers, for those it shows them to: root and the contest's admins and organizers. Its `totalFiles`, a yes/no round's files with at least one vote of each kind (`selectedFiles`, `unratedFiles`, `rejectedFiles`), files deleted on Commons, the comments on its files, `noActiveJuror`, and per juror with votes in it (`jurors`): their files, `unrated`, a rated round's `rated`, a yes/no round's `selected` and `rejected`, and `status` (`ACTIVE`, `STOPPED`). A juror of the contest gets null with `FORBIDDEN` (the page's 403), anyone not in the contest `NOT_FOUND`; both logged |

`roundStat` keeps the own-votes rule of the page: a viewer who is a juror of the open round sees
each juror's progress (files, unrated, rated) but another juror's `selected` and `rejected`, and the
round's files by kind, are null until the round is closed. A juror's `email` follows decision Q6
(root and the contest's Admins only; the web page shows it to organizers too).

**Series admins** (contest series plan §3.3, CA7, CA20) get from a **session** the roles the web
pages give them: derived Admin in their series' live contests from the grant's start year on,
derived Organizer (read-only) in older ones, a real role adding on top; but in a contest where the
account holds a real Juror row the series doesn't apply (CA7), and they are a juror there. A
revoked grant ends this on the next request. A **personal token** never gets series reach: only the
account's real memberships count, as on the REST API.

**Calling it.** A JSON body `{"query": "...", "operationName": ..., "variables": {...}}` with
`Content-Type: application/json`; one operation per request (an array body, a batch, is 400, and a
document with two operations is refused). GET and other methods are 405 for a caller (401
without one): queries are POSTs.

- **From a page of the tool** (the session cookie): every request needs the `Csrf-Token` header,
  queries too (they are POSTs; the CSRF filter checks every session POST). The island's mount
  element carries `data-csrf-token`, as the gallery does for votes; the client sends it on every
  request. A refused token is the REST API's 403 `{"error": "csrf"}`.
- **With a personal API token** (`Authorization: Bearer wlxj_...`, no session cookie, no CSRF token):
  **queries only**. A mutation or subscription from a token is 403 and logged
  (`reason=token_query_only`) until tokens get a write scope. A read-only, server-only token is
  refused (`reason=token_read_only`), as on the REST API's writes. When a token and a session
  cookie come together, the token decides, as on REST, and the query-only rule holds: a cookie
  never adds to a token's rights (and the cookie makes the CSRF token required).
- **Signed out**: refused, introspection included: 401 for a request with the session cookie (a
  signed-out or expired session) or a refused token; a POST with neither a session cookie nor a
  Bearer token is refused first by the CSRF filter (403 `csrf`), as every cookieless POST is. A
  signed-in caller may introspect (the schema is not secret).

```bash
curl -H "Authorization: Bearer $WLXJURY_TOKEN" -H "Content-Type: application/json" \
     --data '{"query": "{ me { fullname } contests { totalCount items { id name year } } }"}' \
     https://jury.wle.org.ua/api/graphql
```

**Errors.** GraphQL's `errors` array; each has a message key (never request or exception text),
`extensions.code` (`UNAUTHENTICATED`, `FORBIDDEN`, `NOT_FOUND`, `BAD_INPUT`, `INTERNAL_ERROR`,
`CSRF`, `TOO_MANY_REQUESTS`) and, for `NOT_FOUND`, a denial, a CSRF refusal or a server error, `extensions.reference`,
which finds the log line. HTTP 401 (no credentials), 403 (a token's mutation, a read-only token, a
CSRF refusal), 400 (a body that isn't one request object), 405, 415 and 429 also carry the REST
API's `error` field; 413 has that field only; the rest are 200 with `errors`. A query that breaks a rule of the language or the schema is `invalid_query` with the
rule's name (`extensions.rule`, e.g. `UndefinedField`) and its locations.

**Limits** (`wlxjury.graphql` in `conf/application.conf`), checked before anything runs: a body of
at most 64 KB (413); brackets nested at most 64 deep, checked before the query is parsed
(`query_too_deep`); fields nested at most 8 deep (`query_too_deep`; introspection at most 15, its
type lists nested at most twice: `introspection_too_deep`); at most 30 aliases
(`too_many_aliases`) and 1,000 selections (fields, fragment spreads, inline fragments), fragments
counted where spread (`query_too_large`); every fragment used (`unused_fragment`), named once
(`duplicate_fragment`), and nothing but operations and fragments (`non_executable_definition`); a complexity budget of 5,000, each field 1 and a list times its page size
(`contests` its limit, a contest's `rounds` 20: `query_too_complex`). Everything after reading the
body runs on the blocking pool and is answered within 20 seconds (`timeout`); a run past that stops
at its next database read, and one that waited for a thread past it does nothing. An account has
at most 2 runs at once, its tokens' and sessions' together (429 `too_many_requests`): the blocking
pool is shared with the galleries and the statistics. A request with many denied fields writes one
denial line, with a second line counting the rest.

**No explorer in production.** GraphiQL is for development and tests only (decision Q5); it is not
served by the app in this slice. Use the SDL, or introspection from a signed-in session.

**For the UI developer.**

- Types: `graphql-codegen` (typescript, typescript-operations, typed-document-node) from the SDL
  and the screens' `.graphql` documents; its config is
  [`conf/graphql/codegen.yml`](../conf/graphql/codegen.yml), run in `ui/` (created by the React
  islands' platform step, which adds the packages). Generated files are not committed. The
  recommended client is urql.
- A schema change is a change to `GraphQLSchema` and to the SDL file in the same commit:
  `sbt "Test/runMain graphql.RenderSchema"` rewrites the file, and `graphql.GraphQLSchemaSpec`
  fails while the two differ.
- A dev database with sign-in accounts: the browser tests' fixture server
  (`node browser-test/fixture-server.mjs start`, testing practices §10) runs the app with the
  production filters, CSRF on, on the Gatling fixture database (about 38,000 files, 20 jurors, a
  binary and a rated round). Its `target/ui-shots/server.json` lists the accounts to sign in with:
  jurors, the contest's admin (`organizer@gatling.test`), an account with the organizer role, and
  root.

**For developers.** `graphql.GraphQLController` (the HTTP part and the checks, in order),
`graphql.GraphQLSchema` (types and resolvers), `graphql.RoundQueries` (the round queries' resolvers:
the round loaded first, then the caller judged in its contest), `graphql.GraphQLContext` (the caller and
`asMemberOf`: the caller judged in the resource's contest by `Secured.checked` with a permission
that takes a series grant's derived role, as the round pages do, never a contest id taken from the
query as a permission; root bypasses), `graphql.QueryLimits`. The router (`api.ApiRouter`) mounts
only Tapir's endpoints and `api.CallerRoutes` groups, such as GraphQL's, whose handlers take the
caller: `ApiRouter.guarded` resolves it (`ApiAuth.caller`) before the handler runs and answers the
group's 401 without one. `api.ApiRouterGuardSpec` is a tripwire for a route added around it (another
kind of dependency of the router, another composition of its routes, a non-Tapir route in
`Api.routes`, another `conf/routes` entry under `/api`), not a proof: a new route is still reviewed. A resolver loads the resource first, then
judges the caller in its contest; a contest the caller may not see is logged on `security.authz`
(`resource=contest:<id>`) with the reference the `NOT_FOUND` error carries. Tests:
`graphql.GraphQLApiSpec` (through the router and filters), `graphql.GraphQLRoundsSpec` (the round
queries, through the router) and `graphql.GraphQLSchemaSpec`.
