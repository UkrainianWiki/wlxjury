# Error handling: authorization denials, recovery and support evidence (October 2026)

**Goal:** finish what `b5a85973` ("Improve authorization denial guidance") and its uncommitted follow-up started, so that every error a user can hit:

- has the right HTTP status, and JSON for JSON endpoints;
- tells the user, in their language, what happened, what they have, what the page needs and what to do next;
- leaves one log line that lets support tell what was denied or what failed, why, and for which account and contest, linked to the page by a reference the user can quote.

Builds on the working tree of `ukwikidev/deployment-branch` (the commit's changes plus the uncommitted cross-contest suggestion, `access.denied.suggested.files` and `accessDenied.scala.html`, which is untracked but part of the work).

## Constraints

- Do not weaken authorization. A check moves to a resource's contest only where the user is an **active** member there (`Secured.inContest`), as `Secured.permitted` and `RoundController.roundStat` already do. Every change ships with a negative test: an account that is not an admin of the resource's contest is still denied.
- A background request (a JSON poll, an HTML fragment loaded by script) or a POST must not rewrite the session's current contest. Only full-page GETs may switch contest (`Secured.switchingContest`), as today.
- New message keys go to all four of `conf/messages.{en,fr,uk,ru}`.

## 1. Denial context and the log line

`Secured.scala` today parses the target contest from `/contest/N/files` only, never logs the required roles, and its `reason` is always `permission_check_failed`.

- Add `Secured.Denial(contestId: Option[Long], resource: Option[String], requiredRoles: Set[String], reason: Option[String] = None)` (`resource` e.g. `round:123`, `job:45`, `contest:603`).
- `onUnAuthorized(user, request, denial = Denial.empty)`. Each call site passes what it knows: `JobController.withJob` (job's contest, `job:<id>`, `ADMIN_ROLES`), `RoundController.withAdminRound` and the other `RoundController` denials (round's or submitted contest), `GalleryController` (round's contest, `round:<id>`), `ImageDiscussionController`, `UserController.switchContest` (target contest), `GalleryController.contestFiles` and friends.
- `withAuth` / `withAuthOn` pass their permission's roles. Make `rolePermission(roles)` return a `Secured.RolePermission(roles: Set[String]) extends (User => Boolean)`, so existing `rolePermission(X)(user)` calls keep compiling, and read `roles` from it when denying; other permissions log `requiredRoles=unknown`.
- Remove `ContestFilesPath` and the path parsing.
- `reason` is derived when the call site does not give one, from the target contest's membership: `not_member`, `membership_inactive`, `missing_role` (member, but none of the required roles), `resource_in_other_contest` (the user is an active member of the target contest, the session is in another one, and the call site did not switch). Call sites may set a more specific reason.
- Build the log fields in a pure function (`Secured.denialLogLine(user, request, denial, reference): String`) and unit-test it. Fields: `ref`, `accountId`, `account` (email, as now), `method`, `path`, `currentContestId`, `targetContestId`, `resource`, `requiredRoles`, `accountRoles`, `targetContestMembershipState`, `targetContestRoles`, `reason`. Keep `logValue` escaping.
- Log through a dedicated logger, `play.api.Logger("security.authz")`, at WARN, instead of the per-controller logger the `Logging` mixin gives.
- `reference`: 8 lowercase hex characters from a random UUID, generated per denial.

## 2. Resource checks in the resource's contest

`JobController.withJob`, `RoundController.withAdminRound` and `RoundController.newFilesCount` check `contestPermission(ADMIN_ROLES, Some(round.contestId))` against the session's current contest. An admin of contests A and B, with A's job page open, who switches to B in another tab, gets 403s on A's job poll, and `public/javascripts/job-status.js` then retries every `2 × interval` forever, logging a WARN line and running the suggestion queries on each poll.

- Check permission against `Secured.inContest(user, resource.contestId)`. Do not change the session in these helpers (they serve JSON polls, fragments and POSTs).
- `job-status.js`: on 401 or 403 stop polling and show a short localized-neutral text (e.g. the status element's `data-denied-text`, set from a message key in `snippets/jobStatus.scala.html`); keep the existing back-off for other failures and network errors. If there is a JS test for `formatJobStatus`, extend it; otherwise do not add a JS test harness.
- Tests: an admin of A and B, session in B, gets 200 from A's job status and A's `newFilesCount`, and the session cookie is not rewritten; an admin of B only still gets 403 for A's job and round.

## 3. JSON endpoints get JSON

`/admin/jobs/:id` and `/admin/rounds/newfiles` return JSON on success and on 404, but the full HTML page on 403.

- Add `onUnAuthorizedJson(user, request, denial)`: same log line, `Forbidden(Json.obj("error" -> "forbidden", "reference" -> ref))`, no suggestion queries. Use it in `JobController.status` and `RoundController.newFilesCount`. `cancel` / `retry` are form POSTs: keep the HTML page.
- `editRound.scala.html`'s `$.getJSON(...).fail` keeps showing `?`; no change needed.

## 4. Unauthenticated requests keep their destination

`onUnAuthenticated` redirects to `login()` without `returnTo`, so an expired session or a link from an email ends on the role's default page.

- `onUnAuthenticated(request)` → `Redirect(routes.LoginController.login(Secured.safeLocalPath(Option.when(request.method == "GET")(request.uri))))`.
- Test: a GET to a protected page without a session redirects to `/auth?returnTo=<that page>`; a POST redirects to `/auth` without it; signing in with that `returnTo` lands on the page (existing `LoginControllerSpec` test covers the last step).
- Signup keeping `returnTo` is out of scope.

## 5. The denial page

`app/views/accessDenied.scala.html`.

- The suggested destination is the primary button; "Sign out and sign in with another account" becomes a secondary (`btn-default`) button.
- Explain what the page needs and what the user has, without naming contests the user is not a member of:
  - `access.denied.required=This page is for: {0}.` with the required roles, localized (reuse existing role message keys if there are any, else add `role.admin`, `role.organizer`, `role.jury`, `role.root`); omitted when unknown;
  - `access.denied.current.role=Your role in {0}: {1}.` for the current contest (its name) when the user has one;
  - when the reason is `membership_inactive`: `access.denied.inactive=Your membership in this contest has been deactivated.`;
  - `access.denied.contact=If you need access, ask your contest organizer.`
- No suggestion: show the contact text and the sign-out button only.
- Show the reference: `access.denied.reference=Reference: {0}, {1}` (reference, UTC time `yyyy-MM-dd HH:mm 'UTC'`), small and muted, so a user can quote it.
- `Secured.suggestedAccess`: look at the current contest first, for both kinds (contest files if the user may view them, else a gallery of an active round), then the other active memberships in the same order. Today "contest files anywhere" beats "a gallery in the current contest".
- Pass `denial` and `reference` to the view; keep the layout's navigation as it is.

## 6. One error page for the rest

Three paths render errors today: `ErrorHandler` (raw English, every 4xx logged at ERROR, `exception.getMessage` shown to users on 500), `/error?message=…` (status 200, text from the query string, mixed keys and English), and the new denial page.

- Add `app/views/errorPage.scala.html(user: Option[User], titleKey, messageKey, args, reference: Option[String], time)` using `main` when there is a user, else `mainAdmin`, with a link to `/`.
- `ErrorHandler.onClientError`: 404 logged at DEBUG, other 4xx at WARN, both with `method`, `path`, `status`, `message`; render `errorPage` with `error.not.found` / `error.bad.request` / `error.client` keys; never put the raw `message` on the page.
- `ErrorHandler.onServerError`: log at ERROR with `ref`, method, path and the exception; the page shows `error.server` ("Something went wrong on our side. If it happens again, tell the organizers this reference: {0}.") and the reference, never the exception message.
- Replace redirects to `/error`:
  - `GalleryController.contestFilesView` (`Contest not found` / `Round not found`) → `NotFound(errorPage(...))`;
  - `RoundController` "There is no active rounds in your contest" and "Round not found" (around lines 399 and 420) → a 404 page, or the existing `no.round.yet` message, with a status that is not 200;
  - `GalleryController.gallery`: `maybeRound.get` runs before `isNotAuthorized`; a missing round must give 404, not a 500;
  - `LoginController.indexRedirect`: a juror with no active round → `errorPage` with `no.round.yet` (status 200 is fine there: it is a state, not an error); an account with no role in any contest → the denial page (403) with reason `not_member` and the contact text, instead of `/error?message=You don't have permission…`.
- Keep the `/error` route for old links, but `LoginController.error` renders only known message keys; anything else renders a generic `error.unknown` message.
- Tests: a 404 for an unknown round in the gallery and on contest files; a forced server error does not show its exception message and shows a reference; a user without any role gets 403 with the contact text; `/error?message=<arbitrary text>` does not echo the text.

## 7. Tests and validation

- Update existing specs that expect redirects to `/error` (search `test/` for `"/error"`).
- Compile first, then the affected specs, via `python scripts/agent-check.py -- …` with the direct sbt launcher as in `CLAUDE.md`: `SecuredSpec`, `LoginControllerSpec`, `MultiContestUserSpec`, `MutationAuthorizationSpec`, `ContestFilesPageSpec`, `RoundEditPageSpec`, `ImageProxyControllerSpec`, any `JobController` / `RoundController` / `GalleryController` / `ErrorHandler` specs, and new ones.
- `Secured` and `ErrorHandler` are shared by every controller: run the full suite once at the end. On a failure, fix and rerun the affected specs; rerun the full suite only if the fix touched shared code.

## Out of scope

- The `CLAUDE.md` model-selection change in `b5a85973` stays (it stopped agents spending quota out of proportion to small tasks); it may ship with this work or as its own commit.
- The untracked 2018 templates (`error401/403/500.scala.html`, `loginForm.scala.html`, `scalaHelper/`) were unused and have been deleted.
- The denial log line keeps the account's email for support's convenience. Possible later fix: log only `accountId` (support looks the account up), so rotated logs hold no personal data.
- Signup keeping `returnTo`.

## Follow-up (implemented)

`b7349951` ("Explain authorization denials and unify error pages") implemented the plan above. Reviews of it, and of the own-votes and voting work that followed, were addressed in:

- `fec056f4` Address review of authorization denial handling.
- `d0bd21fd` Restrict organizer round views to the round's contest.
- `a44cba9f` Restrict thumbnail URL lists to the contest's own rounds.
- `c55e2786` Run the sbt launcher example from Git Bash (`CLAUDE.md`).
- `62f4af61` Authorize the large image view by round and juror.
- `e53ad907` Address second review of authorization denial handling.
- `454b8cc7` Vote in the large view by POST.
- `b007de1e` Show jurors only their own votes (`GalleryService.viewerRounds` / `mayViewRound` / `viewAs`).
- `9c19f6fd` Require a CSRF token for gallery votes (`Csrf-Token` header).
- `5689cbe0` Handle missing selections in criteria votes and views.
- `c4a6c6b1` Vote and move by keys with paths, not `http://` URLs.
- `1fbe1a61` Answer missing jurors, contests and modules without server errors (404 error page; the juror menu lists every juror `isRoundJuror` accepts; no round is a 200 state).
- `7694d301` Check comments against the round the user may view (`Secured.inContest`, `mayViewRound`, the file in the user's view).
- `362b51f9` Hide job ids from other contests (404 as for a missing job, the denial still logged) and sign out and switch contest by POST with the CSRF token.
- `ae988ec2` Explain vote failures to jurors (`vote-errors.js`, `CsrfErrorHandler`).
- Document the authorization and error-handling rules (`CLAUDE.md`).
