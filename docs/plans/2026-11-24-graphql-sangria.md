# GraphQL (Sangria) branch: verification and update plan

Status: verification done, implementation not started. Date: 2026-11-24.

## 1. What the branches are

- `graphql-sangria` (98222f2a) is a port of an archived branch (19 commits, written 2026-04-21 to 04-25)
  stacked on a port of the `react` branch (21 commits: a Vite/React contests grid under `ui/`, served at
  `/ui`). Its merge base with master is recent (fa45a746) and master is only 6 commits ahead of it (docs, CI
  and the top bar), so the merge itself is conflict-free. The age shows in the code, not in the git distance.
- It adds `app/graphql/` (13 files, 1,037 lines in the merge-base diff; the 1,264 of the whole `app`/`conf`/`build.sbt` diff also counts the SDL, routes and build changes): `SchemaDefinition`, `GraphQLContext`, `GraphQLRoute`,
  `SubscriptionEventBus`, resolvers for contests, rounds, images, users, comments, monuments and login, plus
  `conf/graphql/schema.graphql` (SDL, 204 lines, documentation only: the code builds the schema in Scala), 10
  specs in `test/graphql/`, and a design spec and implementation plan under `docs/superpowers/`.
- Queries: contests, contest, round(s), roundStat, images (roundId, userId, page, rate, region), image, users,
  user, me, comments, monuments. Mutations: login (returns a 30-day JWT), create/update/delete contest,
  create/update/setActive round, add jurors, rating and comment mutations (see the SDL). Subscriptions: image
  rated and round progress, over a Pekko stream event bus.
- Serving as written: `GraphQLRoute` is a Pekko HTTP `Route` (`POST /graphql`) meant for port 9001, like the
  old unserved `Api.routes`. Nothing binds it; `conf/routes` only gained `/ui` for the React build.
  It resolves the user from a trusted `X-Authenticated-User` header (any client can send it: an
  authentication bypass wherever the proxy does not strip it) or from a self-issued HS256 JWT whose secret
  defaults to `changeme-graphql-secret`. Both must go.
- Stack: Sangria 4.1.0, `sangria-play-json` 2.0.2, `jwt-play-json` 10.0.1, `pekko-http-play-json` 2.0.0.
  Dependencies resolved and everything outside the resolvers compiled on master's Scala 2.13 / Play 3 / Pekko
  classpath; Sangria and play-json meet through `sangria.marshalling.playJson`, and no version conflict was
  seen. Sangria is maintained but slow-moving; subscriptions need a stream adapter (the branch has one).
- `graphql` (413d26bd) is the same branch plus two older commits that add a Caliban 2.8.1 stub
  (`app/graphql/caliban/GraphQLApi.scala`, one `contests` query returning `Nil`) and a Caliban dependency. It is
  a dead end from 2024; `graphql-sangria` supersedes it. Recommendation: ignore `graphql`, and do not carry
  Caliban (two GraphQL libraries make no sense).

## 2. Gap to master

Merging `origin/master` into the branch is clean, with no conflicts. `Test/compile` then fails with 26 errors,
all in `app/graphql/` (the compiler stops in the typer, so more may follow):

| Area | On the branch | On master | Cost |
|---|---|---|---|
| Votes | `Selection`, `SelectionJdbc`, `ImageWithRating.selection`, `.rank` | `Vote`, `VoteJdbc` / `VoteSql` (V82 renamed the table), `ImageWithRating` has another shape | rewrite the rating types in `ImageResolver`; the GraphQL type `Selection` becomes `Vote` (nothing consumes it yet) |
| Password check | `User.sha1` | bcrypt via `PasswordHasher.check`, auth version, legacy SHA-1 upgrade | drop the `login` mutation (section 4) |
| Roles | `user.hasRole("organizer")` on the account | per-contest roles in `user_contest` (V61/V62); roles exist only on users loaded for a contest | every resolver runs as the user in the resource's contest (`Secured.inContest`) |
| Monuments | `MonumentJdbc.findAll` | no longer there | small |
| Rounds | `Round` as of April | `distributing`, round image counts (`round_image`), jobs (V60), lineage, criteria | type mismatch in `RoundResolver` plus new fields; mutations go through `RoundService` and the job path, never a direct `Round.create` |
| Authorization | `requireRole`, no contest scope, no visibility rules | `Secured` builders, `Denial` logging on `security.authz`, `GalleryService.viewerRounds/mayViewRound/viewAs`, `User.canViewOrgInfo` | the real work; none of it exists in the branch |
| API auth | own JWT and proxy header | `api.ApiAuth.caller`: personal Bearer tokens or the session, mounted under `/api` (`ApiRouter`), see `docs/api.md` | reuse it |
| CSRF | none | CSRF filter; `Csrf-Token` for session POSTs | session mutations need it; only a request with a Bearer header and no session cookie skips it (`ApiAuth.bearerWithoutSession`); with a cookie CSRF is required |
| Serving | Pekko HTTP :9001 route | Play routes only; Tapir served through Play | new Play action |
| Build | `uiTest` wired into `Test / test`, needs npm in `ui/` | `jsTest` only | the React UI is a separate topic (Q2) |
| Series | none | contest series in flight (#119-#124) | go through `ContestService`; add series fields after they merge |
| Tests | specs build a bare `GraphQLContext(Some(user))` | `SharedTestDb`, `SharedPlayApp`, `CsrfTestApp` | rewrite on the shared fixtures |

Note: CLAUDE.md "REST API" in some checkouts still says the Tapir endpoints are not served and have no
authentication. On master `Api` is served under `/api` with token or session auth. The GraphQL work should sit
next to that, not add a second, weaker door.

## 3. Update or port: recommendation

Port onto a fresh branch from master, taking only what is worth keeping; do not finish the merge branch.

- The merge is clean but yields a branch that does not compile, and what remains broken is exactly the part
  that has to be redone: authentication, authorization, the vote and round model, serving. Reusable: the object
  type definitions (about 40%), the SDL as documentation, the event bus idea, the shape of the specs.
- The port is: a new `app/graphql/` with the Sangria schema (copy the types for Contest, Round, Image, Comment,
  Monument, stats), a new context, a Play action, resolvers over the existing services; no `ui/`, no JWT, no
  `login`, no Pekko route, no Caliban.
- Effort (focused agent work including review): read-only schema, action, auth and negative tests 2-3 days;
  limits, timeouts, logging and docs half a day; mutations (separate PR, after Q3) 2 days; subscriptions 1-2 days
  if wanted. The local merge branch `feature/graphql-sangria-update` is not pushed because it does not compile.

## 4. Serving design (never unauthenticated)

1. Endpoint: a Play action `POST /api/graphql`, inside the `/api` router so it shares `ApiAuth.caller`, the 401
   shape, the CSRF rule (a Bearer request without a session cookie skips it, one with a cookie does not), the `Host` check and the docs. JSON body `{query, variables, operationName}`; body size
   limit (for example 64 KB). Persisted queries are out of scope.
2. Authentication: `ApiAuth.caller` only (Bearer token or session). 401 JSON when absent, as for REST. No proxy
   header, no JWT, no `login` mutation (tokens are created on the account page).
3. Token scope: personal API tokens on master are full-power (they act as the user; `docs/api.md` shows a PUT
   with one), so over REST they can write today. A read-only token scope is only planned (D-1b, #121, V108
   reserved in `2026-11-18-contest-series.md`). GraphQL sets its own rule: a token caller may run queries only;
   a mutation or subscription from a token is refused with 403 and logged until a write scope exists. Session
   callers may mutate only with the CSRF token. If a request carries both a token and a session cookie, the
   token decides (the rule `ApiAuth.caller` already applies) and so the query-only limit applies: a cookie can
   never raise a token's rights.
4. Contest and role: resolvers never take a contest id from the query as a permission. Each top-level field
   loads the resource (round, contest, image) first, then runs as `Secured.inContest(user, contestId)` (active
   membership, root bypass). A missing resource and a contest the caller is not in give the same answer (null
   with a `NOT_FOUND` error), and there is no `.get` on a request-supplied id.
5. Visibility rules, reused and not reinvented: votes and image lists of a round go through
   `GalleryService.viewerRounds` / `mayViewRound` / `viewAs`. A juror sees only their own votes; others' (or
   `userId` 0 for all jurors) only with `User.canViewOrgInfo`, and only of a juror of the round
   (`User.isRoundJuror`), else not found. `users` lists only members of the caller's contests and shows email
   only to admins and root of that contest. Rounds still `distributing` are hidden from jurors.
6. Denials: one GraphQL denial path builds a `Secured.Denial` (contest, resource, required roles, reason), logs
   it on `security.authz`, and returns its reference in the error `extensions.reference`. Errors carry message
   keys only, never exception text; an internal error gets a logged reference as in `ErrorHandler`.
7. Limits: Sangria `QueryReducer.rejectMaxDepth` (about 8) and `rejectComplexQuery` (a budget, list fields
   multiplied by `pageSize`); introspection only for authenticated callers; page size capped (for example 200);
   a per-request timeout (for example 20 s) with resolvers on the blocking JDBC pool (as `withAuthJsonOn`), not
   the default dispatcher; one operation per request, no batching.
8. Explorer: none in production. If wanted, a root-only `GET /api/graphql/explorer` with same-origin assets and
   no third-party CDN.
9. Subscriptions: not in the first PR. They need WebSocket authentication and a visibility check per event, and
   must stop when a token is revoked or a membership ends.
10. Mutations (second PR): each calls the service the UI uses (`RoundService`, `ContestService`, votes through
    the gallery's path, so jobs, counts, `distributing` and audit stay right), with the permission of the
    matching controller action, CSRF for session callers, write-scoped tokens only.

## 5. Test plan

Positive: a schema validation spec (SDL matches the Scala schema); per-resolver happy paths on `SharedTestDb`
with truncation (resolvers use `autoSession`); a router test through `SharedPlayApp` for `POST /api/graphql`.

Negative authorization (each must also log one `security.authz` WARN carrying the reference):

- no credentials: 401 JSON; revoked, expired, unknown and malformed tokens: 401.
- a user asks for a round of a contest they are not in: same answer as a missing round; also an inactive
  membership.
- a juror reads another juror's votes (`userId`), or all jurors (`userId` 0): refused.
- an organizer asks for votes of a user who is not a juror of the round: not found.
- a juror lists `users` and sees members or emails of other contests: none.
- a juror queries a `distributing` round: not found.
- a non-admin calls a mutation; a token calls a mutation (queries only for tokens); a session mutation without
  the CSRF token; Bearer plus a session cookie without the CSRF token: refused.
- signed-out introspection (`__schema`, `__type`) is refused; a signed-in juror may introspect (the schema is not
  secret), and the test pins which of the two the plan chose.
- batching: an array body, or several operations in one document, is rejected; `GET /api/graphql` does not
  execute anything (405).
- an organizer sees no emails or admin-only fields while the contest's admin does.
- token and session in the same request: the token wins, no escalation (a read-only caller with an admin cookie
  stays query-only).
- one user with different roles in two contests, querying both in one request: each field is judged in its own
  contest.
- a mutation naming another contest's round id: refused, nothing changed.
- a `Host` not in `WLXJURY_ALLOWED_HOSTS` gets the REST API's 400.
- the complexity budget counts list times nested list (`rounds { images { votes } }` with page sizes), not only
  depth.
- cross-contest id guessing: a round id of contest B inside a query about contest A.
- a depth bomb, an alias flood and an over-complex query are rejected before any SQL; an oversize body; a page
  size over the cap is clamped; a slow resolver hits the timeout and leaves the pool usable.
- the `X-Authenticated-User` header and a forged JWT have no effect (regression for the old branch).
- root bypass works, and root without a contest sees every contest.
- error bodies never contain exception text or SQL.

Review: the normal review and fix cycle; no template changes, so the large-view specs are not affected.

## 6. Order of work

1. This plan merged, Q1-Q3 answered.
2. PR A: read-only schema (contests, rounds, round stats, images, the viewer's votes, monuments), action, auth,
   limits, denial logging, tests, a section in `docs/api.md`.
3. PR B: comments and users queries; series fields once #119-#124 are in.
4. PR C: mutations with write-scoped tokens and CSRF.
5. PR D (optional): subscriptions.

## 7. Open questions for the maintainer

- Q1. Should GraphQL replace the REST `/api` or sit beside it? Beside it (my assumption) keeps one auth path and
  lets clients move piece by piece.
- Q2. Is the React `ui/` contests grid (from the `react` branch under this one) still wanted? The plan leaves it
  out; it would be its own topic and brings `npm` into `sbt test`.
- Q3. Tokens can write over REST today and a read-only scope is only planned (D-1b). Agree that GraphQL tokens
  are query-only until a write scope exists, and which operations that scope should later allow?
- Q4. Is Sangria fixed as the library? Caliban (ZIO) exists only on the old `graphql` branch and Tapir has no
  GraphQL. I recommend Sangria: no ZIO runtime, plain Futures like the rest of the app.
- Q5. Which clients will call it (a React UI, external tools, Montage)? That decides subscriptions,
  introspection, and whether a root-only explorer is enough.
- Q6. Is `Vote` the right public name (the branch used `Selection`), and may emails appear in the `User` type
  for contest admins?
- Q7. Should round creation over GraphQL exist at all, given job-based distribution (a mutation would only
  enqueue and return the job)?
