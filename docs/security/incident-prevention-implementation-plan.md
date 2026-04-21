# Security Incident Prevention Implementation Plan

## Goal

Bring the codebase into consistent compliance with the practices in `docs/security/incident-prevention-guidelines.md`, and document the final standards so they stay enforced during future changes.

## Priority Order

### P0: Immediate risk reduction

1. Replace the committed Play secret
- Change `conf/application.conf` to load the secret from an environment variable.
- Update deployment env examples and server env files documentation.
- Add rollout notes for rotating existing sessions after deployment.
- Acceptance: the app does not start in production without an explicit secret.

2. Replace SHA-1 password hashing
- Add Argon2id or bcrypt dependency.
- Support legacy SHA-1 verification during migration only.
- Re-hash on successful login and store the modern hash.
- Add tests for legacy login migration and new-user password creation.
- Acceptance: no newly created or updated password uses SHA-1.

3. Fix authorization on selection/comment mutations
- Update `GalleryController.selectWS(...)` to require round-scoped permission.
- Update `LargeViewController.rateByPageId(...)` to require round-scoped permission.
- Update `ImageDiscussionController.addComment(...)` to verify access to the target round and contest.
- Add controller tests for unauthorized access attempts across contests.
- Acceptance: a logged-in user cannot mutate rounds they do not belong to.

4. Remove raw SQL injection paths from `ImageDbNew`
- Refactor `SelectionQuery` to use `SQLSyntax` fragments instead of interpolated strings.
- Restrict sortable fields to an allowlist owned by server code.
- Add regression tests using malicious `region` input.
- Acceptance: region and paging input cannot alter SQL structure.

### P1: Browser and deployment hardening

5. Remove unsafe GET mutations and normalize CSRF coverage
- Convert rating/select actions to POST.
- Remove `+ nocsrf` from browser mutation routes unless a non-browser reason remains and is documented.
- Keep CSRF tokens in all rendered forms and AJAX calls.
- Update keyboard-navigation and gallery UI code to POST instead of link-triggered GET changes.
- Acceptance: browser-driven state changes are POST + CSRF protected.

6. Stop leaking exception messages to users
- Change `ErrorHandler` to return generic localized error pages.
- Keep full exception details only in logs.
- Add tests for 4xx and 5xx responses.
- Acceptance: user-visible responses do not include stack or exception text.

7. Re-enable host allowlisting
- Enable `AllowedHostsFilter`.
- Configure allowed production hostnames for `jury.wikilovesearth.org.ua`, `jury.wikilovesearth.org`, `jury.wikilovesmonuments.org.ua`, `jury.wle.org.ua`, `jury.wlm.org.ua`, `wlxjury.wikimedia.in.ua`, and `jury-canary.wle.org.ua`.
- Document local-dev and test overrides.
- Acceptance: unexpected `Host` headers are rejected.

8. Tighten browser security headers
- Replace the current permissive CSP with the narrowest policy the UI supports.
- Remove `unsafe-eval` if libraries allow it.
- Minimize or remove `unsafe-inline` using nonces or by reducing inline scripts.
- Add explicit policy for frame embedding, content sniffing, referrer leakage, and browser feature access.
- Acceptance: documented security headers are emitted in production and covered by tests where feasible.

### P2: Operational safety and cleanup

9. Remove password exposure from helper scripts
- Update `scripts/run-with-prod-dump.sh` to use a temporary client config file instead of `MYSQL_PWD`.
- Make cleanup deterministic on error paths.
- Document safe usage for local prod-dump workflows.
- Acceptance: DB passwords are not exposed through command environment variables during script execution.

10. Fix session-handling inconsistencies
- Correct `LoginController.signUp()` so the session stores the username, not the password.
- Review session creation and invalidation paths for consistency.
- Document the expected session keys and login/logout behavior.
- Acceptance: no path ever writes credentials into the session payload.

11. Review unsafe HTML rendering call sites
- Audit current `Html(...)` usage in Twirl templates.
- Keep trusted markup helpers, but remove or wrap any usage that can receive untrusted content.
- Document approved patterns for safe HTML rendering.
- Acceptance: every remaining `Html(...)` call has a clear trusted source.

## Documentation Deliverables

The implementation work should update documentation alongside code:

1. Keep `docs/security/incident-prevention-guidelines.md` as the normative standard.
2. Add a short “Security Controls” section to `README.md` that links to the security docs.
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
