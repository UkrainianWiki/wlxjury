# GraphQL (Sangria) branch: verification and update plan

Status: verification done, implementation not started; the maintainer's decisions are recorded in section 7. Date: 2026-11-24.

## 1. Purpose and what the branches are

Purpose (from the maintainer): a UI developer wants to build a React UI, and GraphQL is the cheap way to give
them an API without designing and implementing REST endpoints one by one. Scope below is judged by what that
developer needs to start building screens soon and safely (section 6 has the first slice).


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
| Build | `uiTest` wired into `Test / test`, needs npm in `ui/` | `jsTest` only | `ui/` is created by #136's platform step (Q2) |
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
  Monument, stats), a new context, a Play action, resolvers over the existing services; no `ui/` (see Q2), no JWT, no
  `login`, no Pekko route, no Caliban.
- Effort: see section 6 (about 5-6 days to the first usable slice for the UI developer). The local merge branch
  `feature/graphql-sangria-update` is not pushed because it does not compile.

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
   only to root and to Admins of a contest the listed user is a member of (decision, Q6). Rounds still `distributing` are hidden from jurors.
   A coming rule (CY-2, #140): a juror of a round sees only their own votes in that round whatever their role;
   the vote and round-image resolvers must apply it, so an organizer or admin who is also a juror of the round
   gets only their own votes there.
6. Denials: one GraphQL denial path builds a `Secured.Denial` (contest, resource, required roles, reason), logs
   it on `security.authz`, and returns its reference in the error `extensions.reference`. Errors carry message
   keys only, never exception text; an internal error gets a logged reference as in `ErrorHandler`.
7. Limits: Sangria `QueryReducer.rejectMaxDepth` (about 8) and `rejectComplexQuery` (a budget, list fields
   multiplied by `pageSize`); introspection only for authenticated callers; page size capped (for example 200);
   a per-request timeout (for example 20 s) with resolvers on the blocking JDBC pool (as `withAuthJsonOn`), not
   the default dispatcher; one operation per request, no batching.
8. Explorer: GraphiQL in dev and test only, not in production (decision, Q5).
9. Subscriptions: none now; added when a screen needs one. They would need WebSocket authentication and a
   visibility check per event, and must stop when a token is revoked or a membership ends.
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
- a juror queries a `distributing` round: not found from `round(id)` and `roundStat`; `roundImages` and `image`
  answer `CONFLICT` `round_closed` with reason `round_distributing`, as the web gallery's state page tells its juror.
- a non-admin calls a mutation; a token calls a mutation (queries only for tokens); a session mutation without
  the CSRF token; Bearer plus a session cookie without the CSRF token: refused.
- signed-out introspection (`__schema`, `__type`) is refused; a signed-in juror may introspect (the schema is not
  secret), and the test pins which of the two the plan chose.
- batching: an array body, or several operations in one document, is rejected; `GET /api/graphql` does not
  execute anything (405).
- an organizer sees no emails or admin-only fields while the contest's admin does; a contest Admin sees the email
  of members of their contest only, not of other users.
- a juror of a round who is also an organizer or admin sees only their own votes in that round, through
  `roundImages` and the vote fields (CY-2, #140).
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

## 6. UI developer workflow and first slice

Purpose: a UI developer wants to build a React UI. GraphQL is the cheap way to give them an API without
designing and implementing REST endpoints one by one: they ask for the fields a screen needs, and a schema
change is a field, not a new endpoint. Scope is judged by what they need to start building screens soon, safely.
REST stays beside it for tokens and scripts. The first client is a browser React app on the same origin, with
the session cookie; the main write path is session plus CSRF, and tokens stay query-only.

- Contract: the SDL (`conf/graphql/schema.graphql`) is the checked-in contract. A test renders the served schema
  and compares it to the file, so a resolver change without an SDL change fails CI. The UI developer reads the
  SDL, and a schema change is a reviewed diff of that file.
- Types: `graphql-codegen` (typescript + typed-document-node) generates TypeScript types and typed documents from
  the SDL and from the `.graphql` documents of the screens. `ui/` itself is created by the React investigation's
  platform step (#136); this plan supplies the SDL, the endpoint and the codegen config. Generated files are not
  committed; codegen runs in #136's `uiBuild` and in CI.
- Client: urql with graphql-codegen is the recommended default (#136 adopts the same). It is small, has a document
  cache by default and is simple to read; Apollo's normalized cache pays off only with many cross-screen
  updates, which these screens do not have. Revisit if the UI grows.
- Dev setup: as in #136 section 3.2 (Vite dev server with a proxy to the local Play app, port 9000 by default, so
  the browser sees one origin: no CORS, the session cookie and CSRF work as in production). The Play app runs on a
  dev DB: the Gatling fixture DB (`data/cache/` dump, about 38k images, 20 jurors, a binary and a rated round) is
  restored with one script, and the plan adds fixture sign-in accounts for a juror, an organizer and an admin.
- CSRF: `modules.CsrfModule` checks every session POST, so GraphQL queries over POST with the session cookie need
  the `Csrf-Token` header as well. The client sends it on every request (recommended, and simplest). There is no
  `/ui` shell and no meta tag: #136 recommends React islands on per-screen Twirl pages, and the mount element
  carries `data-csrf-token`, as the gallery vote does (`voteTexts.scala.html`). Bearer without a session cookie
  is the token path and needs no header.
- Errors: GraphQL's standard `errors` array with `extensions.code` (`UNAUTHENTICATED`, `FORBIDDEN`,
  `NOT_FOUND`, `BAD_INPUT`, `CONFLICT` (a juror's round not open to them now: message `round_closed`, always with
  `extensions.reason`), `INTERNAL_ERROR`, `CSRF`, `TOO_MANY_REQUESTS`) and `extensions.reference` for the log line; messages are
  keys, never exception text. HTTP 401 for no session, 200 with `errors` otherwise.
- Pagination: cursor connections (`first`, `after`, `pageInfo { endCursor hasNextPage }`, `totalCount`), not
  offset: a 38k-image round paged by offset gets slow and unstable while jurors vote and the order shifts. For
  images GraphQL reuses #129's keyset continuation rules (the full sort key plus the page id as tie-break, per
  ordering), one cursor design for both. Page size is capped (200). Offset is kept only for small lists
  (contests, rounds, users).
- Explorer (GraphiQL): enabled in dev and test only, not in production (decided).
- Subscriptions: none now; polling the round progress query every few seconds is enough until a screen needs
  push, and a subscription is added then.

Slices (each a PR; "slice 1" and so on are what #136 should cite). Authorization design (section 4) and negative
tests (section 5) apply to every slice. Days include review.

| Slice | Content | Serves | Days |
|---|---|---|---|
| 1 | Foundation and first screens: Play action `POST /api/graphql`, auth, limits, denial logging, SDL file plus the schema-matches-SDL test, codegen config, a section in `docs/api.md`, fixture sign-in; queries `me`, `contests`, `contest(id)` with rounds | #136's first screen, the root contests grid | 3-4 |
| 2 | Done: `roundStat` (round statistics, juror stats) and `round(id)` | #136's second screen, organizer round statistics | 1-1.5 |
| 3 | Done (Q8 amended): `roundImages` (keyset connection, the viewer's own vote, through `viewerRounds` / `viewAs`), `image(roundId, pageId)` | the juror gallery and large view | 2 |
| 4 | Mutation `vote(roundId, pageId, rate, criterion?)`, after its rules are extracted into a service (below) | juror voting | 3 |
| 5 | Comments, users, monuments; series fields once #119-#124 are in. Admin and organizer screens come first, so this slice may move ahead of 3-4 | organizer and admin screens | 2 |
| 6 | Organizer and admin mutations through the services (no `createRound` in the first slices); subscriptions only when a screen needs one | later | open |

The UI developer can start against the SDL after about 2 days of slice 1. Slices 1 and 2 are about 4-5.5 days.

Voting rules (slice 4). The gallery's vote rules are inline in `GalleryController` (about lines 470-500):
`VotePermission(roundId)`, `openToJurors`, `RoundUser.isStopped`, paused gives 409 `round_paused`, closed gives
409 `round_closed`, `acceptsRate`, `hasCriteria == criteria.isDefined` else 400 `invalid_rate`, and 404 if no row
changed. They are extracted first into a service that both the controller and the GraphQL mutation call (the
controller keeps its responses; the refactor is its own commit with the existing gallery specs unchanged), so the
two paths cannot drift. The mutation has the optional `criterion` argument. Negative tests: paused round, closed
round, stopped juror, a rate the round does not accept, criteria mismatch, an organizer voting (403), another
contest's round, no CSRF header, a token caller. This is why slice 4 is 3 days and not 1.

## 7. Decisions (2026-11-24)

- Q1. GraphQL sits beside the REST `/api`; REST stays for tokens and scripts.
- Q2. Split as planned: #135 supplies the server, the SDL, the endpoint and the codegen config; #136 creates
  `ui/`.
- Q3. Token callers are query-only: mutations and subscriptions from a token get 403 and are logged until a
  write scope exists.
- Q4. Sangria is the library.
- Q5. First client: a browser React UI on the same origin with the session cookie. The explorer (GraphiQL) is for
  dev and test only, not production. No subscriptions now; one is added when a screen needs it.
- Q6. The public type name is `Vote`. `User.email` resolves only for root and for Admins of a contest the user is
  a member of.
- Q7. No `createRound` mutation in the first slices (a later one would only enqueue the distribution job).
- Q8. Admin and organizer screens first (slices 1, 2, 5); the juror slices 3-4 come after #129 and the
  vote-rule extraction.
- Q8 amended (maintainer, 2026-11-25): slice 3 no longer waits for #129's gallery build. It follows the paging
  rules of #129's plan (`2026-11-19-own-gallery-tiles.md` §3.6, keyset continuation: the full sort key with the
  page id as tie-break, the cursor carrying the last file's key as it was when its page was read, that file left
  out of the next page). Voting (slice 4) still waits for the vote-rule extraction.
- CY-2 (#140), a rule coming: a juror of a round sees only their own votes in that round whatever their role.
  The vote and round-image resolvers apply it (section 4, item 5; negative test in section 5).

Still open: which operations a future write scope for tokens should allow (Q3 asked it; not answered).

### Follow-up to slice 1 (decided 2026-11-24, built on `feat/graphql-followup`)

- F1. Series admins: GraphQL follows the web UI. A session caller is judged in a contest by
  `Secured.checked` with `ContestPermission(ACCESS_ROLES, contest, derived = true)` (listed in
  `security.SeriesReachGuardSpec`), the opt-in the round and gallery pages use: derived Admin from the
  grant's start year, derived Organizer below it (CA20), a real Juror row winning (CA7). `contests` adds
  the contests the grants reach (`Secured.seriesContestIds`). Tokens get no series reach (contest series
  plan §3.3). The conflict-of-interest rule is exposed per round as `othersVotesVisible`
  (`GalleryService.othersVotesVisibleIn`, the same rule as `othersVotesVisible`); the vote fields of
  slices 3-4 must apply it.
- F2. `me.email` is the caller's own email, always. Other accounts' emails stay Q6's rule
  (`GraphQLSchema.emailVisible`, for slice 5's `users`).
- F3. A stopped juror's round stays listed; `Round.myStatus` (`ACTIVE`, `STOPPED`, null) tells them.
- F4. `api.ApiRouter` mounts only Tapir's endpoints and `api.CallerRoutes` groups; `ApiRouter.guarded`
  resolves the caller before a group's handler runs (`api.ApiRouterGuardSpec`). A signed-out GET of
  `/api/graphql` is now 401, not 405.
