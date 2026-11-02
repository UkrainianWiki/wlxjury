# Security review checklist

For every change that touches routes or controllers, `Secured`, roles or accounts, sessions,
SQL, views or JavaScript, config or deployment, exports, logging or dependencies (the PR
template's boxes). The reviewer quotes the lines they checked; each points to its section of
[secure-coding.md](secure-coding.md). Status changes go into [register.md](register.md) in the
same commit as the fix.

## Routes, auth and sessions

- [ ] Every new action uses a `Secured` builder with a scoped permission, or is added to
  `RouteAuthSpec.Public` with a reason; no new public route without one (§9).
- [ ] A resource is checked in its own contest (`Secured.inContest`), never by switching the
  session on a POST or poll; request ids never `.get` (§9).
- [ ] Negative tests: another contest's admin, an organizer, a juror, an inactive membership,
  signed out (§9).
- [ ] A credential or email change bumps `auth_version`; a session check uses
  `Secured.sessionUser` (§15).
- [ ] `returnTo` and other redirects pass `Secured.safeLocalPath` (§7).

## Roles and accounts

- [ ] Role values come from `User.ContestRoles`, matched exactly; nothing splits a role string;
  root only from `users.is_root` (§10).
- [ ] No role can be granted by a lower role (`User.mayAssignRoles`); a new role comes with a
  migration of V76's constraint (§10).
- [ ] A lookup by email or wiki account that grants access uses a verified identifier
  (`User.mayTakeAccessRole`); refused links and lookups don't reveal whether an account exists
  (§14).

## SQL and queries

- [ ] Every new SQL fragment is bound or comes from an enumerated set; a new `createUnsafely` or
  `SQL(s"…")` is in `RawSqlSpec.Allowed` with a reason (§1).
- [ ] Passwords hashed only by `PasswordHasher`; statements writing a hash are quiet (§3).

## Views and JavaScript

- [ ] No inline script, handler or `javascript:` URL; the CSP unchanged or narrower (§5).
- [ ] No new raw HTML sink (`Html(`, `HtmlFormat.raw`, `.html(`, `innerHTML`); values passed to
  vendored widgets escaped for their sink (§8).
- [ ] Every state change is a POST with the CSRF token (`@csrfField()` or the `Csrf-Token`
  header); no GET changes anything; no `nocsrf` (§11).
- [ ] Errors shown as message keys and a reference, never exception or request text (§12).

## Config and deployment

- [ ] No credential, key or token literal in the diff; no password on a command line or in an
  exported variable (§2, §4).
- [ ] A new cookie follows `play.http.session.secure`; security headers unchanged (§6).
- [ ] No URL built from the request's host; absolute URLs from `wlxjury.host` (§7).
- [ ] A new outbound URL is built from a configured host (§17).
- [ ] A new unauthenticated or costly endpoint has a limit (§18).
- [ ] A production config change is made in the deployed template too, and the release notes
  say what the server needs (§20).

## Exports and logs

- [ ] A new export writes through `Csv.writeRow`/`Csv.write` (§16).
- [ ] New log lines carry ids only: no email, name, password, token or whole account; no bound
  SQL values (§13).

## Dependencies

- [ ] A new dependency is used, maintained and pinned; Pekko versions stay in step with
  scalawiki (§19).

## Guards

- [ ] `testOnly security.*` passes. A guard hit (also one appearing after a merge) is reviewed:
  the code is fixed, or that one file is allowlisted with a one-line reason; patterns are never
  widened or checks dropped to make it pass.

## Release gate

Recorded in the release's results (the plan's results section or the release notes):

1. `testOnly security.*` (needs Docker: `SignedOutSpec` starts the test app) and the
   authorization specs: `MutationAuthorizationSpec`, `SecuredSpec`, `RoleAssignmentSpec`,
   `OwnVotesSpec`, `CoiAuthorizationSpec`, `CommentAuthorizationSpec`, `api.ApiSpec`.
2. The full Scala suite and Jest (`javascript-test/`), once, at the release boundary.
3. The dependency report (G11; a report, not a failure, decision 3): the direct dependencies of
   `build.sbt` and the vendored files checked against published advisories (e.g. osv.dev) and
   `npm audit` in `javascript-test/` (dev dependencies only), with anything found recorded in
   the register.
4. The pre-deploy items of [the roadmap](../plans/2026-11-02-roadmap.md) ("Before any production
   deploy of followups"):
   - O1: B5's allowed-hosts and Secure-cookie settings in the production config template, and
     `WLXJURY_ALLOWED_HOSTS` (jury host names only) in `runtime.env`;
   - HTTP → HTTPS redirect and HSTS on every jury vhost; `services.ApiTokenService` and `coi`
     at INFO in the production logback;
   - `<logger name="org.mariadb.jdbc" level="ERROR"/>` in the production logback;
   - V76: the role query first (a bad row fails the migration and needs
     `wlxjury-release restore`);
   - the case-sensitive pre-release password query (follow-ups plan, B4);
   - manual checks: Firefox theme flash and Back after a vote; category suggestions against
     live Commons.
5. Infra template parity: G14 or `ProductionConfigSpec` on the template, once the infra plan
   provides it; until then the O1 item above, checked by hand.
