# Security Incident Prevention Implementation Plan

## Goal

Bring the codebase into consistent compliance with the practices in `docs/security/incident-prevention-guidelines.md`, and document the final standards so they stay enforced during future changes.

Status on 2026-11-02 (`ukwikidev/followups`, batches B1–B6): items 1–11 and the README
section are done; each item says where. Still open, recorded in the guidelines' "Still open"
list: per-IP and per-account sign-in throttling, `style-src 'unsafe-inline'` (unitegallery's
tiles), the deprecated CSP setting, a `Permissions-Policy` and tests for the default headers,
and HSTS on the Apache vhosts (`wlxjury-infra`).

## Priority Order

### P0: Immediate risk reduction

1. Replace the committed Play secret
- Change `conf/application.conf` to load the secret from an environment variable.
- Update deployment env examples and server env files documentation.
- Add rollout notes for rotating existing sessions after deployment.
- Acceptance: the app does not start in production without an explicit secret.
- **Done:** `production.conf` sets `play.http.secret.key = ${PLAY_HTTP_SECRET_KEY}`.

2. Replace SHA-1 password hashing
- Add Argon2id or bcrypt dependency.
- Support legacy SHA-1 verification during migration only.
- Re-hash on successful login and store the modern hash.
- Add tests for legacy login migration and new-user password creation.
- Acceptance: no newly created or updated password uses SHA-1.
- **Done** (follow-ups batch B4, 2026-11-02): bcrypt `$2b$`, cost 12, in
  `services.PasswordHasher` (`at.favre.lib:bcrypt` 0.10.2). A legacy SHA-1 hash is checked
  only to sign in, then replaced by a bcrypt hash; every new, reset, changed or imported
  password is stored as bcrypt; new passwords are at most 72 bytes; at most
  `bcrypt.maxConcurrent` hash computations at once. Tests: `services.PasswordHasherSpec`,
  `controllers.PasswordStorageSpec`.

3. Fix authorization on selection/comment mutations
- Update `GalleryController.selectWS(...)` to require round-scoped permission.
- Update `LargeViewController.rateByPageId(...)` to require round-scoped permission.
- Update `ImageDiscussionController.addComment(...)` to verify access to the target round and contest.
- Add controller tests for unauthorized access attempts across contests.
- Acceptance: a logged-in user cannot mutate rounds they do not belong to.
- **Done:** `MutationAuthorizationSpec`, `CommentAuthorizationSpec`, `OwnVotesSpec`.

4. Remove raw SQL injection paths from `ImageDbNew`
- Refactor `SelectionQuery` to use `SQLSyntax` fragments instead of interpolated strings.
- Restrict sortable fields to an allowlist owned by server code.
- Add regression tests using malicious `region` input.
- Acceptance: region and paging input cannot alter SQL structure.
- **Done:** `ImageDbNew` builds `SQLSyntax` with bound values.

### P1: Browser and deployment hardening

5. Remove unsafe GET mutations and normalize CSRF coverage
- Convert rating/select actions to POST.
- Remove `+ nocsrf` from browser mutation routes unless a non-browser reason remains and is documented.
- Keep CSRF tokens in all rendered forms and AJAX calls.
- Update keyboard-navigation and gallery UI code to POST instead of link-triggered GET changes.
- Acceptance: browser-driven state changes are POST + CSRF protected.
- **Done:** no `+ nocsrf` route is left; votes, sign-out, contest switching, the theme
  switch and token management are POSTs with the token.

6. Stop leaking exception messages to users
- Change `ErrorHandler` to return generic localized error pages.
- Keep full exception details only in logs.
- Add tests for 4xx and 5xx responses.
- Acceptance: user-visible responses do not include stack or exception text.
- **Done:** `views.html.errorPage` with message keys; a server error shows a logged
  reference (`ErrorHandlerSpec`). The `security.authz` denial line logs the account id only
  (batch B6).

7. Re-enable host allowlisting
- Enable `AllowedHostsFilter`.
- Configure allowed production hostnames for `jury.wikilovesearth.org.ua`, `jury.wikilovesearth.org`, `jury.wikilovesmonuments.org.ua`, `jury.wle.org.ua`, `jury.wlm.org.ua`, `wlxjury.wikimedia.in.ua`, and `jury-canary.wle.org.ua`.
- Document local-dev and test overrides.
- Acceptance: unexpected `Host` headers are rejected.
- **Done** (batch B5): the filter is on; production allows only the names in
  `WLXJURY_ALLOWED_HOSTS` (no loopback names; `/health/ready` is exempt for local clients).
  `jury-canary.wle.org.ua` is not in the list any more: the blue/green canary is disabled
  (docs/deployment.md). Tests: `AllowedHostsSpec`, `ProductionConfigSpec`.

8. Tighten browser security headers
- Replace the current permissive CSP with the narrowest policy the UI supports.
- Remove `unsafe-eval` if libraries allow it.
- Minimize or remove `unsafe-inline` using nonces or by reducing inline scripts.
- Add explicit policy for frame embedding, content sniffing, referrer leakage, and browser feature access.
- Acceptance: documented security headers are emitted in production and covered by tests where feasible.
- **Done for scripts** (batch B5): `script-src 'self'` plus the hashes of the two inline
  scripts left, no `'unsafe-eval'`, and `object-src 'none'`, `base-uri 'self'`,
  `form-action 'self'`, `frame-ancestors 'none'` (`ContentSecurityPolicySpec`,
  `InlineScriptHashesSpec`). Session, flash and the app's cookies are `Secure` in
  production. **Open:** `style-src 'unsafe-inline'`; framing, sniffing and referrer
  headers are Play's `SecurityHeadersFilter` defaults, untested; no `Permissions-Policy`.

### P2: Operational safety and cleanup

9. Remove password exposure from helper scripts
- Update `scripts/run-with-prod-dump.sh` to use a temporary client config file instead of `MYSQL_PWD`.
- Make cleanup deterministic on error paths.
- Document safe usage for local prod-dump workflows.
- Acceptance: DB passwords are not exposed through command environment variables during script execution.
- **Done** (batch B6): a client option file (mode 600, in a mode-700 temporary directory,
  mounted read-only) for the prod dump, an `--env-file` for the local container and
  `/root/.my.cnf` inside it for its root client; removed once used and by the EXIT trap
  (signals included).

10. Fix session-handling inconsistencies
- Correct `LoginController.signUp()` so the session stores the username, not the password.
- Review session creation and invalidation paths for consistency.
- Document the expected session keys and login/logout behavior.
- Acceptance: no path ever writes credentials into the session payload.
- **Done:** sign-up stores the login.

11. Review unsafe HTML rendering call sites
- Audit current `Html(...)` usage in Twirl templates.
- Keep trusted markup helpers, but remove or wrap any usage that can receive untrusted content.
- Document approved patterns for safe HTML rendering.
- Acceptance: every remaining `Html(...)` call has a clear trusted source.
- **Done** (batch B6): the audit table is in `docs/plans/2026-11-01-open-followups.md`
  (item 2.6). The monuments' wiki text now goes through `views.WikiLinks`
  (`WikiLinksSpec`); the rest is constant markup, message texts or values the code escapes.

## Documentation Deliverables

The implementation work should update documentation alongside code:

1. Keep `docs/security/incident-prevention-guidelines.md` as the normative standard.
2. Add a short “Security Controls” section to `README.md` that links to the security docs. **Done.**
3. Add inline comments only where the security reason is not obvious from the code.
4. Add test names that make the intended control explicit, for example:
- rejects cross-contest selection mutation
- rejects malicious region filter input
- does not expose exception text in error page

## Recommended Execution Sequence

1. Secrets and password migration
2. Authorization fixes
3. SQL injection refactor
4. CSRF and HTTP method cleanup
5. Error handling and host filtering
6. Header hardening
7. Script cleanup and session cleanup
8. Documentation refresh and final verification

## Verification Plan

For each implementation phase, add or update automated checks:

- controller tests for authz and CSRF-sensitive routes
- DAO tests for malicious filter inputs
- integration tests for expected security headers
- tests for password migration behavior
- tests for generic error rendering

Manual verification should also include:

- attempt mutation from a user in a different contest
- attempt mutation without CSRF token
- attempt malicious region input in gallery routes
- verify rejected `Host` header behavior
- verify session invalidation after secret rotation deployment

## Ownership Suggestion

- Authentication, authorization, and session work: controllers + `User`
- SQL safety refactor: `ImageDbNew` + gallery query tests
- Browser protections: routes, Twirl templates, frontend JS
- Deployment hardening: `conf/application.conf` + Apache/systemd docs
- Operational safety: scripts and local-dev documentation

## Definition Of Done

This plan is complete when:

- the codebase follows the guidelines in all state-changing and query-building paths
- critical gaps are covered by automated tests
- deployment configuration documents required secrets and allowed hosts
- security-relevant practices are discoverable in repo docs instead of tribal knowledge
