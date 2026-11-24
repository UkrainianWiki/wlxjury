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
  (logger `services.ApiTokenService`): a revoked or expired token, or one of a deleted user,
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
