# Security practices plan: rules, checklists and guards (2026-11-02)

A plan for the documents and automated guards that keep each past issue class from coming back
and keep fixed states fixed. It lists what to write and where, and the guard specs to add; it is
not the documents themselves. Inputs: the [consolidated report](../security/2026-11-02-rereview-report.md),
`docs/security/review.md`, `incident-prevention-guidelines.md` (its "Repository Security
Standards" and "Review Checklist"), `incident-prevention-implementation-plan.md`,
`local-images-review-followups.md`, CLAUDE.md "Authentication & Authorization", and the
follow-ups plan's reviewer invariants.

## Documents to create or update

| Document | Action | Outline |
|---|---|---|
| `docs/security/secure-coding.md` | **new**; absorbs `incident-prevention-guidelines.md`'s "Practices" and "Standards" (that file then points here, decision 1) | One section per issue class below: the rule, a correct example from this codebase (file and function), the guard that enforces it, the known exceptions (each with its allowlist entry) |
| `docs/security/review-checklist.md` | **new** | Checklist lines grouped by what a change touches: routes/controllers, roles and accounts, SQL, views/JS, logs, config/deployment, outbound calls, exports, dependencies; a "release gate" section |
| `docs/security/register.md` | **new** | The one live status table (ID, title, severity, status, owner, batch, guard, last checked). Seeded from the consolidated report's table. Dated reviews stay snapshots |
| CLAUDE.md | update | In "Authentication & Authorization": the role rule (exact allowlist; root only from `users.is_root`) and a pointer: "Before changing auth, SQL, views, config or exports, read `docs/security/secure-coding.md`; reviewers use `review-checklist.md`; run `testOnly security.*`". Remove the stale pac4j line (N10) |
| AGENTS.md | update | One line pointing to the two security docs (it already defers to CLAUDE.md) |
| `.github/pull_request_template.md` | **new** | "Touches: auth / roles / SQL / views or JS / config or deployment / exports / logs / dependencies" boxes; if any is ticked: "checklist applied", "negative authorization tests added", "`security.*` guards pass" |
| `docs/security/review.md`, `local-images-review-followups.md`, `incident-prevention-implementation-plan.md` | update | A header line "Status is tracked in `register.md`"; fix the stale tables (P16) |

## Issue classes: rule, documentation, guard, checklist

"Guard" names an existing spec or one to add (G-numbers, listed below). All new guards live in a
`test/security/` package so that `testOnly security.*` runs them in seconds; source-scanning guards
need no database.

| Class (past items) | Rule | Documented in | Automated guard | Reviewer checklist line |
|---|---|---|---|---|
| SQL injection (Vuln 1) | Request values are bound; `createUnsafely`/`SQLSyntax` only with constants, enumerated orders or numbers | secure-coding §SQL | `SelectionQuerySpec`, `ImageDbNewDbSpec`; **G4** (allowlist of `createUnsafely` and `SQL(s"…")` call sites) | "Every new SQL fragment is bound or comes from an enumerated set; a new `createUnsafely` is in G4's allowlist with a reason" |
| Secrets in config (Vuln 2) | Secrets only from the environment/runtime files; never a literal in `conf/` | secure-coding §Secrets | `ProductionConfigSpec`; **G12** (secret pattern scan of tracked files) | "No credential, key or token literal in the diff" |
| Weak password storage (Vuln 3, P8) | Hashes only through `PasswordHasher`; no other hash of a password | secure-coding §Passwords | `PasswordHasherSpec`, `PasswordStorageSpec` ("never SHA-1"); **G4b** (no `MessageDigest`/`sha1` in `app/` outside `PasswordHasher`) | "Passwords are hashed only by `PasswordHasher`; hash statements are quiet" |
| Secrets in env/args (Vuln 4) | Scripts pass DB passwords via option files (mode 600), never `MYSQL_PWD`, `--password=` or argv | secure-coding §Scripts | `scripts/deployment/test_release_script.py`; **G12** includes `MYSQL_PWD`/`--password=` in `scripts/` | "No password on a command line or in an exported variable" |
| CSP / inline JS (Step 1, B5) | No inline scripts or handlers except the hashed ones; the CSP never gets wider | secure-coding §Browser | `NoInlineScriptsSpec`, `InlineScriptHashesSpec`, `ContentSecurityPolicySpec` | "No inline script/handler/`javascript:`; CSP unchanged or narrower" |
| Cookies and headers (Step 2/3, P4, O1) | Session/flash/lang/theme/sort cookies Secure+HttpOnly in production; security headers on every response | secure-coding §Browser | `ProductionConfigSpec`, `GalleryOrderSpec`; **G6** (`SecurityHeadersSpec` on real responses) | "A new cookie follows `session.secure`; headers unchanged" |
| `@Html` and DOM sinks (Step 4, N8) | No `Html(...)`/`HtmlFormat.raw` of request or DB text; JS writes text with `textContent`; vendored libs get escaped input | secure-coding §Output | `WikiLinksSpec`, `GalleryDescriptionViewSpec`; **G5** (allowlist of `Html(`, `HtmlFormat.raw`, `.innerHTML`, `.html(`, `insertAdjacentHTML` in `app/` and non-vendored `public/javascripts/`) | "No new raw HTML sink; values to vendored widgets are escaped for their sink" |
| IDOR / contest scoping (P0 3, WJ-F2.4, P6) | A resource is checked in its own contest (`Secured.inContest`), never by switching the session; request ids never `.get` | secure-coding §Authorization | `MutationAuthorizationSpec`, `CommentAuthorizationSpec`, `OwnVotesSpec`, `CoiAuthorizationSpec`, `SecuredSpec`; **G2** (every route's action uses a `Secured` builder or is on the public allowlist) | "Negative cases: another contest's admin, organizer, juror, inactive member, signed out" |
| Role and privilege handling (N1) | A stored role is exactly one value of `User.ContestRoles`; no role string is split; root only from `users.is_root`; role fields in forms are allowlisted | secure-coding §Roles; CLAUDE.md | **G1** (`RoleValuesSpec`: no `split` on a role value in `app/`; every `"role"`/`"roles"` form field has an allowlist `verifying`; `UserContestJdbc` writers refuse `root`); `RoleAssignmentSpec`, `UserContestSpec` (S1) | "Role values come from the allowlist; no privilege can be granted by a lower role; root never from a contest table" |
| CSRF / mutating GETs (P1 5, N6) | Every state change is a POST with the token; no `nocsrf`; GET handlers change nothing | secure-coding §CSRF | `SignOutAndSwitchSpec`, `ThemeSwitchSpec`, CSRF router tests; **G3** (`CsrfCoverageSpec`: every POST route from `views.AppRoutes`, sent without a token under `CsrfTestApp`, is refused); **G3b** (no `nocsrf` in `conf/routes`; GET actions named `save*/delete*/set*/update*/add*/remove*/import*/rate*/select*` fail unless allowlisted) | "New mutation is a POST with the token; the form renders `csrfField`" |
| Error leakage (P1 6, N9) | Users see message keys and a reference only; never request or exception text (pages, JSON, job status) | secure-coding §Errors | `ErrorHandlerSpec`, `JobControllerSpec` (S5); **G13** (no `getMessage`/`toString` of a throwable in `Ok`/`BadRequest`/`Json` or persisted user-facing fields, allowlisted) | "Errors shown as keys + reference" |
| Personal data in logs (2.8, N11) | Log lines identify accounts by id; no email, name, password, token or bound SQL values | secure-coding §Logging | `SecuredSpec` (denial line); **G9** (logger calls in `app/` don't interpolate `.email`, `.fullname`, `password`, `token`); slow-query listener spec (S5) | "New log lines carry ids only" |
| Account linking and verification (O2, N4, N5, N7) | An account joins a contest only if its email (or wiki account) is verified or invited; identifiers are unique and normalised; mail is sent from the tool's address | secure-coding §Accounts | S2's specs in `MultiContestUserSpec`, `UserControllerSpec`, `UserServiceSpec` | "Any lookup by email/wiki account that grants access uses a verified identifier" |
| Session lifecycle (O3, N2) | Sessions carry the account id and an auth version; expire (idle, absolute); password/email change invalidates other sessions; sensitive changes need the current password | secure-coding §Sessions | S3's specs in `SecuredSpec`, `PasswordStorageSpec`; **G7** (`play.http.session.maxAge` set in `application.conf`) | "New credential change bumps the auth version" |
| Brute force / DoS limits (P1, P5, P13) | Authentication attempts and expensive per-request work are bounded per address/account | secure-coding §Limits | S4's specs; image proxy width spec (S6) | "New unauthenticated or costly endpoint has a limit" |
| CSV / spreadsheet injection (N3) | Every export goes through `Csv.writeRow`, which neutralises leading `= + - @ \t \r` | secure-coding §Exports | `CsvSpec` (S5); **G8** (no `CSVWriter`/`text/csv` outside `Csv`/`csvStream`) | "New export uses `Csv.writeRow`" |
| SSRF / host checks (Practice 6, P9) | Outbound requests only to configured hosts (Commons, upload.wikimedia.org, lists); redirects to fixed hosts; `returnTo` via `safeLocalPath` | secure-coding §Outbound | `LargeViewUrlsSpec`, `SecuredSpec` (`safeLocalPath`); **G10** (allowlist of outbound call sites: `Http().singleRequest`, `ws.url`, `MwBot` constructions, `URL(`) | "New outbound URL is built from a configured host" |
| Host header (B5) | Absolute URLs from `wlxjury.host`, never `request.host`; allowed hosts required in Prod | secure-coding §Browser | `AllowedHostsSpec`, `ProductionConfigSpec`; **G10b** (no `request.host`/`absoluteURL` in `app/` outside an allowlist) | "No URL built from the request's host" |
| Dependency hygiene (N10) | No unused dependency; vendored JS listed with its version and the escaping rule; quarterly update check | secure-coding §Dependencies | **G11** (`sbt dependencyUpdates` and an OWASP/`sbt-dependency-check` run at the release gate; `npm audit` in `javascript-test/`) | "New dependency is used, maintained and pinned" |
| Deployment config drift (O1, P2, P15) | Every setting `ProductionConfigSpec` requires is in the deployed template; HSTS on HTTPS vhosts | secure-coding §Deployment; infra plan | `ProductionConfigSpec`; **G14** (`ProductionConfigParitySpec` until the infra plan's Step 3: the B5 keys present in the infra template if a path is configured, else skipped with a message; after Step 3 `ProductionConfigSpec` reads the template); infra `test_check_vhosts.py` checks HSTS | "Config change made in the template too; release notes say what the server needs" |

## Guard specs to add

| Guard | Kind | What it asserts | Batch it lands with |
|---|---|---|---|
| G1 `security.RoleValuesSpec` | source scan + unit | no `.split(` on `role`/`roles` values in `app/`; every form field named `role`/`roles` has an allowlist `verifying`; `UserContestJdbc.createMembership`/`updateRole` refuse `root`, `USER_ID_1`, `jury,root` | S1 |
| G2 `security.RouteAuthSpec` | `views.AppRoutes` + source scan | every routed action's body starts with `withAuth`, `withAuthOn`, `withAuthJson` or `secured` (API), or the route is in an explicit public list (`/auth`, `/signup`, `/logout` page, `/health/ready`, assets, webjars, `/switch-contest` GET redirect, `/error`) | S1 or S10 (independent) |
| G2b `security.SignedOutSpec` | router test | every non-public route, with placeholder ids, answers 401 (script) or a login redirect (page) without a session | with G2 |
| G3 `security.CsrfCoverageSpec` | router test under `CsrfTestApp` | every POST route without a token is refused; G3b: no `nocsrf`; GET actions with mutating names allowlisted | S4 (with N6) |
| G4 `security.RawSqlSpec` | source scan | `createUnsafely(` and `SQL(s"` call sites match a reviewed allowlist (file, enclosing method, reason); G4b: no `MessageDigest`/`sha1` outside `PasswordHasher` | S5 |
| G5 `security.RawHtmlSinksSpec` | source scan | `Html(`, `HtmlFormat.raw`, `.innerHTML`, `.html(`, `insertAdjacentHTML`, `document.write` in `app/` and `public/javascripts/` (vendored files excluded by name) match an allowlist | S5 |
| G6 `security.SecurityHeadersSpec` | Play app | real responses carry CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`, `Permissions-Policy` | S7 |
| G7 `security.SessionConfigSpec` | config | session `maxAge` set; `httpOnly` true; production `secure` true | S3 |
| G8 `security.ExportsSpec` | source scan | `CSVWriter`/`text/csv` only in `Csv`/`GalleryController.csvStream` | S5 |
| G9 `security.LogHygieneSpec` | source scan | logger calls don't interpolate `email`, `fullname`, `password`, `token`, `secret` | S5 |
| G10 `security.OutboundHostsSpec` | source scan | outbound client call sites and `request.host`/`absoluteURL` uses match an allowlist | S6 |
| G11 dependency check | sbt/npm task | dependency update and vulnerability report at the release gate (report, not a test failure, decision 3) | S10 |
| G12 `security.NoSecretsSpec` (or `check-no-secrets.sh`) | source scan | no secret-shaped literals, `MYSQL_PWD`, `--password=` in tracked files (test fixtures allowlisted) | S10; reuse the infra plan's `check-no-secrets.sh` when it lands |
| G13 `security.ErrorTextSpec` | source scan | no throwable message into responses or user-visible persisted fields outside an allowlist | S5 |
| G14 `security.ProductionConfigParitySpec` | config | B5's keys in the deployed template (until the infra plan's Step 3) | S8 |

Allowlists are small Scala `Seq`s in the spec, each entry with a one-line reason; adding an entry
is itself a review item.

## Maintenance

- **When the checklist applies:** every PR or batch that touches routes, controllers, `Secured`,
  roles or accounts, SQL, views or JS, config or deployment, exports, logging or dependencies
  (the PR template's boxes). The reviewer quotes the checklist lines they checked.
- **Release gate:** `testOnly security.*` plus the authorization specs (`MutationAuthorizationSpec`,
  `SecuredSpec`, `RoleAssignmentSpec`, `OwnVotesSpec`, `CoiAuthorizationSpec`, `ApiSpec`), the
  G11 report, and the infra template parity (G14 or `ProductionConfigSpec` on the template).
  Recorded in the release's results.
- **Re-review cadence:** a full re-review like this one before each contest season (WLE in spring,
  WLM in late summer) and after any change to authentication, roles or sessions (decision 2).
- **Status tables:** `register.md` is the only live table. A batch that changes an item's status
  updates its row (status, batch, guard, last checked) in the same commit as the fix. Dated review
  documents are never edited except for a pointer to the register; stale tables (P16) get that
  pointer.
- **Agents (Claude, Codex):** CLAUDE.md's pointer (AGENTS.md defers to it) makes implementers read
  `secure-coding.md` before such changes; the reviewer prompt in plans includes
  `review-checklist.md` and the invariants; `testOnly security.*` is part of each batch's checks.
  The follow-ups plan's "Invariants that every reviewer checks" link to the checklist instead of
  repeating it.

## Decisions (user)

1. Fold `incident-prevention-guidelines.md` into `secure-coding.md` (recommended), or keep both.
2. Re-review cadence: per contest season, quarterly, or per release.
3. G11 as a failing gate or as a report.
4. Whether to add a CI workflow for `security.*` (there is no working CI today; `appveyor.yml`
   is obsolete), or keep the guards in the release gate only.

## Order

1. With S1: G1, CLAUDE.md role rule, `register.md` seeded.
2. Then `secure-coding.md`, `review-checklist.md`, PR template, AGENTS.md pointer (docs only, no
   tests).
3. Each remaining guard with its batch (table above); G2/G2b may go early since they only assert
   the current state.
