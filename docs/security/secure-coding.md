# Secure coding rules

The rules every change to this repository follows, one section per issue class that has
occurred here. Each section gives the rule, a correct example in this codebase, the guard that
enforces it and the known exceptions. Reviewers apply [review-checklist.md](review-checklist.md);
the status of each security item is in [register.md](register.md). This document replaces the
"Practices" and "Standards" of `incident-prevention-guidelines.md` (2026-04, now a pointer here).

**Guards** are specs in `test/security/` (`testOnly security.*`; most scan the sources and need
no database, `SignedOutSpec` starts the test app) and the named specs elsewhere. "Planned: G<n>"
names a guard of the [practices plan](../plans/2026-11-02-security-practices.md) and the batch
it lands with.

**Allowlists.** A guard's allowlist is a small map in the spec: one entry per file (or, for
`RouteAuthSpec`, per action), each with a one-line reason. Adding an entry is a review item.
When a guard fails, including after a merge, review the hit: fix the code, or allowlist that
one file (or action) with its reason. Never widen a pattern, drop a check or allowlist a
directory to make a guard pass.

## Threat model

Assets: signed-in sessions (juror, organizer, admin, root); credentials and password hashes;
contest, round and membership state; votes, comments and ratings; the Commons fetch and proxy
paths; deployment secrets and the production database. Incident classes: account takeover,
privilege escalation, CSRF, SQL injection, secret leakage, SSRF, error disclosure, host-header
abuse.

## Contents

1. [SQL](#1-sql)
2. [Secrets in configuration](#2-secrets-in-configuration)
3. [Password storage](#3-password-storage)
4. [Secrets in scripts](#4-secrets-in-scripts)
5. [CSP and inline JavaScript](#5-csp-and-inline-javascript)
6. [Cookies and security headers](#6-cookies-and-security-headers)
7. [Host header and absolute URLs](#7-host-header-and-absolute-urls)
8. [Raw HTML and DOM sinks](#8-raw-html-and-dom-sinks)
9. [Authorization and contest scoping](#9-authorization-and-contest-scoping)
10. [Roles and privileges](#10-roles-and-privileges)
11. [CSRF and mutating GETs](#11-csrf-and-mutating-gets)
12. [Errors shown to users](#12-errors-shown-to-users)
13. [Personal data in logs](#13-personal-data-in-logs)
14. [Accounts: linking and verification](#14-accounts-linking-and-verification)
15. [Sessions](#15-sessions)
16. [Exports](#16-exports)
17. [Outbound requests](#17-outbound-requests)
18. [Limits](#18-limits)
19. [Dependencies](#19-dependencies)
20. [Deployment configuration](#20-deployment-configuration)

## 1. SQL

**Rule.** Request values are bound (`sql"…"`/`sqls"…"` interpolation, `where.eq`, CRUDMapper
helpers). `SQLSyntax.createUnsafely` and `SQL(s"…")` take only constants, enumerated orders or
numbers. Dynamic `ORDER BY` comes from a fixed set owned by server code. Bind request data
through typed `Form` mappings or typed route parameters, never by hand-parsing.

**Example.** `ImageDbNew.SelectionQuery.where` returns `SQLSyntax` with bound values (the
region filter was Vuln 1); `orderBy` is built from the query's enumerated `sortOrder`.

**Guards.** `security.RawSqlSpec` (G4: `createUnsafely(`/`SQL(s"` only in listed files);
`SelectionQuerySpec`, `ImageDbNewDbSpec` (malicious region input).

**Exceptions** (`RawSqlSpec.Allowed`): `ImageJdbc` (`regionOf` over a fixed column),
`ImageDbNew` (structure, group, order and limit from enumerated options and numbers),
`DistributeImages` (criteria ids, constants), `RoundDistributionHandler` (an order from an
enumerated set).

## 2. Secrets in configuration

**Rule.** Secrets come from the environment or runtime files, never a literal in `conf/` or
any tracked file. Examples and templates name variables, never values.

**Example.** `conf/wlxjury/production.conf`: `play.http.secret.key = ${PLAY_HTTP_SECRET_KEY}`
and the DB credentials from `WLXJURY_DB_*`; `runtime.env.example` lists names only. The
`changeme` default in `application.conf` is for development and tests; Play refuses it in Prod.

**Guards.** `deployment.ProductionConfigSpec` (the key is required). Planned: G12 secret scan
of tracked files (`NoSecretsSpec` or the infra plan's `check-no-secrets.sh`; S10 deferred it to
the infra plan).

**Exceptions.** Test fixtures and `conf/application.conf`'s development default.

## 3. Password storage

**Rule.** Passwords are hashed only by `services.PasswordHasher` (bcrypt `$2b$`, cost 12, at
most 72 bytes); no other digest of a password. Statements that write a hash are not logged
(`User.quietTx`). A legacy SHA-1 hash is only checked at sign-in and replaced; after its grace
period (V81, 3 months from the S6 deploy) it no longer signs in and is replaced by an unusable
value (`PasswordHasher.RetiredLegacy`, `LegacyPasswordRetirementJdbc.retire`), the account
recorded in `legacy_password_retired`. A password is checked against `User.usableHash` (sign-in,
the current password of one's own change); "has a password" for invitations means
`User.hasUsablePassword` (a retired hash is none).

**Example.** `PasswordHasher.hash` / `PasswordHasher.check` (constant time, a dummy hash for
unknown accounts, `bcrypt.maxConcurrent` turns).

**Guards.** `PasswordHasherSpec`, `PasswordStorageSpec` (never SHA-1; P8 grace period and
retirement), `db.LegacyPasswordRetirementMigrationSpec`;
`security.RawSqlSpec` G4b (`MessageDigest` only in listed files, SHA-1 only in `PasswordHasher`).

**Exceptions** (`RawSqlSpec.Digests`): `ApiTokenService.hash`, SHA-256 of random 256-bit
tokens (API tokens; `AccountTokenService.hash` reuses it for account links); `LoginController`
hashes both sides of the setup token to compare them in constant time (nothing stored).

## 4. Secrets in scripts

**Rule.** Scripts pass DB passwords in option files (mode 600), never `MYSQL_PWD`,
`--password=` or another argument or exported variable; temporary files are removed by a trap.

**Example.** `scripts/run-with-prod-dump.sh`: a `[client]` option file written mode 600,
mounted read-only, `--defaults-extra-file` first, an `--env-file`, a trap on every exit path.

**Guards.** `scripts/deployment/test_release_script.py`. Planned: G12 also flags `MYSQL_PWD`
and `--password=` in `scripts/`.

**Exceptions.** None.

## 5. CSP and inline JavaScript

**Rule.** No inline `<script>`, event-handler attribute or `javascript:` URL; scripts are files
under `public/javascripts/` reading `data-*` attributes. The two inline scripts (the theme
script, the large views' restore) are allowed by their hashes. The CSP never gets wider.

**Example.** `play.filters.csp.directives` in `conf/application.conf` (Play's `CSPFilter`; S7
moved it from the deprecated `play.filters.headers.contentSecurityPolicy`, P11): every directive
set there, none of Play's own, no nonce; `script-src 'self'` and two hashes, `object-src 'none'`,
`base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`. `submit-once.js` is a served
file, not inline. Swagger UI (`/api/docs`) runs under the same policy (no inline script; P17).
No route has the `nocsp` modifier.

**Guards.** `views.NoInlineScriptsSpec`, `views.InlineScriptHashesSpec`,
`views.ContentSecurityPolicySpec` (exact directive set, no wider than before S7),
`security.SecurityHeadersSpec` (the header on real responses, Swagger UI's included),
`ProductionConfigSpec` (production.conf changes none of it).

**Exceptions.** `style-src 'unsafe-inline'` and `img-src`/`media-src https://*` (P3: later,
user decision).

## 6. Cookies and security headers

**Rule.** In production the session, flash, language, theme and gallery-sort cookies are
`Secure`; a new cookie follows `play.http.session.secure` and is `HttpOnly` unless script
must read it. Every response that passes the filters carries the CSP, `X-Frame-Options: DENY`,
`X-Content-Type-Options: nosniff`, `Referrer-Policy: same-origin`, `X-XSS-Protection: 0`,
`X-Permitted-Cross-Domain-Policies: master-only` and a `Permissions-Policy` that turns unused
browser features off (camera, microphone, geolocation, payment, ...). The header filters run
first (`play.filters.enabled`), so a CSRF refusal or a bad Host's 400 has them too; the allowed
hosts come next, so a foreign Host gets its 400 before any session or database work. Play's
server answers a server error, and a request it can't parse, outside the filters: `ErrorHandler`
adds the same headers to those (`modules.ResponseSecurityHeaders`, from the same configuration
and code). A page whose URL holds a secret (a mailed link's token) sends `no-referrer`; it is the
only header an action may set in place of the filter's (`modules.AppSecurityHeadersFilter`).

**Example.** `ThemeCookies` (`controllers/Theme.scala`) and `RoundList.remembered` take
`secure` from the session's setting; `production.conf` sets `play.http.session.secure`,
`play.http.flash.secure`, `play.i18n.langCookieSecure`.

**Guards.** `ProductionConfigSpec` ("the cookies in production", "the security headers and
CSRF in production"), `GalleryOrderSpec`, G6 `security.SecurityHeadersSpec` (S7, P4: a page, a
token page, a redirect, JSON errors, Swagger UI, a webjar, a CSRF refusal, a bad Host; through a
running server, a server error and an unparsable request).

**Exceptions.** None.

## 7. Host header and absolute URLs

**Rule.** Absolute URLs come from `wlxjury.host`, not the request. The request's host is used
only where it is named exactly in `wlxjury.allowedHosts` (no suffix, no port). Production
requires the allowed hosts (B5). A `returnTo` passes `Secured.safeLocalPath` (local paths only).

**Example.** `services.LinkHost` and `UserService.linkUrl` (mailed links: the request's host if
allowed exactly, else `wlxjury.host`; scheme always `wlxjury.host`'s); the large view's keys
vote by paths, never `Call.absoluteURL` (`LargeViewUrlsSpec`).

**Guards.** `controllers.AllowedHostsSpec`, `ProductionConfigSpec` ("the allowed hosts in
production"), `AccountReviewFixesSpec` (link host: forged, suffix, port), `SecuredSpec`
(`safeLocalPath`). Planned: G10b (no `request.host`/`absoluteURL` in `app/` outside a list, S6).

**Exceptions.** `LinkHost.of` (checked as above); `GalleryController.thumbnailUrls`' fallback
to `request.host` when `wlxjury.host` is unset (never in production).

## 8. Raw HTML and DOM sinks

**Rule.** Twirl escaping is the default. No `Html(...)`/`HtmlFormat.raw` of request or
database text. JavaScript writes text with `textContent`, never `.html()`/`innerHTML` of data.
Text given to a vendored library is escaped for the sink that library uses.

**Example.** `views.WikiLinks` (monument wikitext as links, text and hrefs escaped);
`region-menu.js` builds its items with `textContent`; the gallery tile caption is
`HtmlFormat.escape(file.title)` inside the `alt` attribute (`gallery.scala.html`).

**Vendored libraries**, copied and unmaintained upstream: `public/javascripts/unitegallery.js`
(Unite Gallery 1.7.28), `jquery.raty.js` (jQuery Raty 2.7.1), `jquery.hotkeys.js`. unitegallery
and raty insert with jQuery's `.html()`: the tile `alt` and `data-description` are escaped once
more (`gallery.scala.html`, `contestFiles.scala.html`); raty gets numbers and empty hints only
(`large-stars.js`). Local patch to unitegallery (S5): the thumbnail `alt` (`setHtmlThumb`, the
image-effect overlay) is `escapeAttr(htmlToText(title))` and `urlThumb` is escaped; keep it if
the file is replaced, and prefer replacing the library to patching more of it.

**Guards.** `security.RawHtmlSinksSpec` (G5), `views.WikiLinksSpec`,
`views.GalleryDescriptionViewSpec`, `ContestFilesPageSpec`, Jest `unitegallery-alt.test.js`.

**Exceptions** (`RawHtmlSinksSpec.Allowed`, each with what it inserts): `bs/FieldView`,
`GalleryTiles`, `JsTexts`, `WikiLinks`, `contestFiles`, `large/contestFile`,
`large/fullSizeUrl`, `large/navigationButtons`, `large/fileKeyboardNav`, `main`, `mainAdmin`,
`navbar`, `pageLinks`, `cache-status.js`; the vendored files by name.

## 9. Authorization and contest scoping

**Rule.** Every action is built with a `Secured` builder (`withAuth`, `withAuthOn`,
`withAuthJson`, `withAuthJsonOn`) with a contest-, round- or role-scoped permission, or is on
the public list. A resource (round, job, comment, file) is checked in its own contest as the
user's membership there (`Secured.inContest`), never by switching the session's contest on a
POST or poll. A request-supplied id is looked up with `fold`/`getOrElse` to a 404, never `.get`.
Editing another account checks the editor's admin or root role in that account's contest
(`UserController.saveUser` through `editableUser`: an admin only for members of their
current contest; root any account).
A juror sees only their own votes (`GalleryService.viewerRounds`, `mayViewRound`, `viewAs`).
A vote needs the round open to its jurors and the juror not stopped on it (`RoundUser.isStopped`:
an inactive `round_user` row, P6); otherwise the answer of a stopped round, nothing written. The
vote's update statements check the same, so a stop racing a vote wins.
Denials go through `onUnAuthorized`/`onUnAuthorizedJson` with a `Secured.Denial`.

**Example.** `GalleryController.selectWS`: `withAuthJson(roundPermission(..., roundId))`,
then the round open to jurors, then only the juror's own selection; `ImageDiscussionController
.addComment` checks the round's contest via `Secured.inContest` and `mayComment`.

**Guards.** `security.RouteAuthSpec` (G2: every routed action uses a builder or is listed),
`security.SignedOutSpec` (G2b: every non-public route refuses a request without a session),
`MutationAuthorizationSpec`, `CommentAuthorizationSpec`, `OwnVotesSpec`,
`CoiAuthorizationSpec`, `SecuredSpec`, `api.ApiSpec` (the API's own authentication).

**Exceptions** (`RouteAuthSpec.Public` / `ChecksSessionItself`): readiness (loopback only),
assets and webjars, sign-in/sign-out pages, invitation and email-confirmation links
(token-checked), `/setup` (empty database and setup token only), the old GET
`/switch-contest` link (redirects only); `ImageProxyController.serve`,
`RoundController.newFilesCount` and `JobController.status` check the session themselves
(401 signed out).

**Tests.** A permission change has negative cases: another contest's admin, an organizer, a
juror, an inactive membership, signed out.

## 10. Roles and privileges

**Rule.** A stored contest role is exactly one value of `User.ContestRoles` (= `FORM_ROLES`):
exact match, no splitting, trimming or case folding. Root comes only from `users.is_root`, set
by root through `User.setRoot`, never from `user_contest`. A role field in a form has an
allowlist `verifying`. No role can grant more than its holder has (`User.mayAssignRoles`).
Adding a role needs a migration replacing V76's `CHECK` constraint. (N1: a free-text role
field split on commas made any contest admin root.)

**Example.** `UserController.editUserForm`'s `"roles"` verifies `User.isContestRole(r) || r
== root`; `saveUser` refuses what `mayAssignRoles` rejects before any write;
`UserContestJdbc.createMembership`/`updateRole` and `User.create` `require` a contest role;
`UserContest.roles` ignores any other stored value (the membership is inactive).

**Guards.** `security.RoleValuesSpec` (G1: no split of a role value; role form fields
allowlisted; the allowlist is exact), `RoleAssignmentSpec`, `UserContestSpec`,
`UserContestRolesSpec`, `db.UserContestRoleMigrationSpec` (the constraint).

The round form's hidden `"roles"` (always `jury`, `editRound.scala.html`; `round.roles` is
read only by the navbar) verifies `User.isContestRole` too.

**Exceptions** (`RoleValuesSpec.UncheckedRoleFields`): none.

## 11. CSRF and mutating GETs

**Rule.** Every state change is a POST with the CSRF token; no `+ nocsrf` route; a GET handler
changes nothing. Forms render `@csrfField()`; script calls send the `Csrf-Token` header from
`data-csrf-token`. A refused token goes to `CsrfErrorHandler`.

**Example.** Sign-out (`POST /logout`), contest switching (`POST /switch-contest/:id`; the GET
only redirects), the theme switch, votes, API token management.

**Guards.** `SignOutAndSwitchSpec`, `ThemeSwitchSpec`, router tests under
`controllers.CsrfTestApp.withCsrfApp` (tests otherwise run with the filter off). Planned: G3
`CsrfCoverageSpec` (every POST route refused without a token) and G3b (GET actions with
mutating names allowlisted). `controllers.LoginCsrfSpec` (N6, S7): a cookieless `POST /auth`,
`/setup`, `/invitation` or `/email/confirm` without the token is refused, the sign-in page sets
the token; `SecurityHeadersSpec` checks that no route has `nocsrf`.

**Exceptions.** A Bearer-token `/api/` request without a session needs no CSRF token. Every
other POST needs it, cookies or not (`play.filters.csrf.header.protectHeaders = null`, S7, N6):
a cookieless API POST with no credentials gets the JSON CSRF refusal (403, with a `hint` to send
`Authorization: Bearer <token>`), not a 401. A sign-in form left open after its session cookie
is gone gets the CSRF page asking to reload.

## 12. Errors shown to users

**Rule.** Users see message keys and a logged reference, never request text or an exception's
message: pages, JSON, job status and persisted user-facing fields. A missing resource is a 404
with `views.html.errorPage`.

**Example.** `ErrorHandler` (a server error shows a reference); `services.jobs.JobError` (a
job's error is a key, numeric arguments and the reference of its ERROR log line).

**Guards.** `security.ErrorTextSpec` (G13), `ErrorHandlerSpec`, `JobControllerSpec`.

**Exceptions** (`ErrorTextSpec.Allowed`, capped per file): `RoundController` (the text of our
own `require` on round settings), `Job` (the dedupe index's name), `DbErrors` (deadlock and lock
wait tests), `RoundService` (the submit token's unique index): each tests the text, none shows it.

## 13. Personal data in logs

**Rule.** Log lines identify accounts by id (`accountId`, `userId`), never an email, name,
password, token, secret or a whole `User` (its `toString` has the email). No bound SQL values
in production logs. Paths with a token are masked (`Secured.logPath`).

**Example.** `Secured.denialLogLine` (key-value, `accountId`, quoted untrusted text);
`db.scalikejdbc.SqlLogging` (slow and failed statements as templates, `?` for values, the
exception's class, SQLState and code); `conf/wlxjury/logback.xml` keeps `org.mariadb.jdbc` at
ERROR (the driver's WARN lines carry bound values).

**Guards.** `security.LogHygieneSpec` (G9), `SqlLoggingSpec`, `SecuredSpec` (denial line),
`AccountReviewFixesSpec` (no token in log lines).

**Exceptions** (`LogHygieneSpec.Allowed`): none. The production logback is in `wlxjury-infra`;
its `org.mariadb.jdbc` line is a pre-deploy item until the infra plan's Step 4.

## 14. Accounts: linking and verification

**Rule.** An access role (jury, organizer, admin) reaches an existing account only if nobody
but the email's owner can sign in to it (`User.mayTakeAccessRole`: root, verified email, or no
password); otherwise an add becomes an invitation. A wiki-account match never attaches an
access role to an unverified wiki account. Emails are trimmed and lowercased; wiki names
normalised and unique. Links are 32 random bytes, stored as SHA-256, single use, expiring,
bound to account and address. Every refused link gives the same page (no account
enumeration). One's own password or email change needs the current password; nobody sets
another's password. Mail that is not the person's goes from `UserService.systemSender`.

**Example.** `UserService.addToContest` (results `NewAccount`, `JoinedContest`, `Invited`,
`WikiAccountUnverified`); `services.AccountTokenService.check`; acceptance in one transaction
with the token row locked.

**Guards.** `AccountInvitationSpec`, `OwnAccountSpec`, `AccountReviewFixesSpec`, `SetupSpec`,
`db.AccountVerificationMigrationSpec`, `MultiContestUserSpec`, `UserControllerSpec`.

**Exceptions.** N5 (postponed): the greeting/invitation is sent "From" the inviter.
Duplicate wiki accounts are refused at sign-in, not merged (postponed).

## 15. Sessions

**Rule.** A session carries the account id (`Secured.AccountId`) and the account's session
version (`Secured.AuthVersion`); `Secured.sessionUser` refuses another id or version. A
password or email change, invitation acceptance, "sign out everywhere" and an admin reset bump
`users.auth_version` (`User.BumpAuthVersion`). S3 adds the sign-in time, an absolute lifetime
(1 year) and an idle timeout (1 month), and bumps on every credential change.

**Example.** `Secured.sessionUserOf` (used by `ErrorHandler` and `CsrfErrorHandler` too);
`ImageProxyController.viewerKey` keys its cache by name, version and id.

**Guards.** `SecuredSpec`, `AccountReviewFixesSpec` (H2: old cookies, re-addressed accounts),
`PasswordStorageSpec`, `ApiTokenPageSpec`. Planned: G7 `SessionConfigSpec` (S3: `maxAge` set,
`httpOnly`, production `secure`).

**Exceptions.** Pre-S2 cookies without an id resolve by name to version-0 accounts only (S3
upgrades them in place); key rotation (P16) ends every session.

## 16. Exports

**Rule.** Every export goes through `controllers.Csv`: `Csv.writeRow`/`Csv.write` pass each
cell through `Csv.cell` (numbers as they are, other text through `Csv.text`, which prefixes `'`
to a cell starting, after whitespace, with `= + - @`, tab or CR). Served by
`GalleryController.csvStream`.

**Example.** `Csv.rateRows` (juror names in the header, file titles in rows).

**Guards.** `security.ExportsSpec` (G8), `CsvSpec`.

**Exceptions** (`ExportsSpec.Allowed`): `Csv.scala`, `GalleryController.scala` (`csvStream`).

## 17. Outbound requests

**Rule.** Outbound requests go only to configured hosts: Commons (`commons.host`),
`upload.wikimedia.org`, the contest's lists host (set by root). Never fetch a URL taken from a
request. Remote and local paths are parsed strictly; the image proxy rebuilds the file from the
database entry.

**Example.** `LocalImageCacheService` (base `https://upload.wikimedia.org`, Commons thumbnail
paths only; `downloadWithRetry` fetches only from `wiki-base-url`'s scheme, host and port, and an
original only for a url `CommonsFileCheck.isCommonsUrl` accepts, a prefix match: P9; redirects
are never followed); `ImageProxyController.serve` (path → database file, width capped at 3840);
`MwBot.fromHost` in `AppModule.bot`; Apache `conf/apache/jury-images.conf` proxies misses only
to `upload.wikimedia.org`.

**Guards.** `LocalImageCacheServiceSpec` ("a fetch": another host, scheme, port, userinfo,
a redirect, a stored foreign url), `ImageProxyFileCheckSpec`, `LargeViewUrlsSpec`. Planned: G10
`OutboundHostsSpec` (call sites of `singleRequest`, `ws.url`, `MwBot`, `new URL(` match a list;
not done in S6).

**Exceptions.** `MonumentListSource` uses `contest.listsHost` (root-set configuration).

## 18. Limits

**Rule.** Authentication attempts and costly per-request work are bounded per address,
account or globally; a new unauthenticated or expensive endpoint gets a limit. The image proxy
serves a stored file only at the widths the pages ask for (`LocalImageCacheService.servedWidths`);
another is a 302 to the nearest (P5).

**Example.** `PasswordHasher` (`bcrypt.maxConcurrent`, `maxWait`, `maxQueued`; 503
`signin.busy` when full); `ApiTokenService.MaxActive` (20 tokens per account, checked under the
account's row lock); `ImageProxyController` (`MaxAllowedPx`, `MaxViewers`); the email-change
request (one live link, one per 60 s).

**Guards.** `PasswordHasherSpec`, `ApiTokenPageSpec`, `AccountReviewFixesSpec` (L5),
`ImageProxyWidthSpec` (P5). Planned: fail2ban on sign-in failures (S4, infra plan).

**Exceptions.** No per-address sign-in throttling in the app (P1; fail2ban, infra plan); no
per-user count of image proxy misses (P5's rate limit: not done). The
in-memory limits assume one app instance (`docs/deployment.md`).

## 19. Dependencies

**Rule.** No unused dependency; versions pinned; a new dependency is used, maintained and
reviewed. Pekko and Pekko HTTP stay in step with scalawiki, whose versions are in its own
`project/Dependencies.scala` (`Library.Pekko`: `PekkoV`, `PekkoHttpV`): bump scalawiki first,
then wlxjury's `PekkoVersion`/`PekkoHttpVersion` in `build.sbt`,
then rerun the image cache specs. Vendored JavaScript is listed with its version (§8).

**Example.** S10 removed `play-pac4j` (nothing used it) and checked the runtime classpath
before and after (`sbt dist`).

**Guards.** Planned: G11, a dependency report at the release gate (report only, decision 3):
not automated yet (no update or vulnerability plugin in `project/plugins.sbt`); see the
release gate in the checklist.

**Exceptions.** `pekko-http` 1.0.1 (deferred to a scalawiki-first bump); the vendored files.

## 20. Deployment configuration

**Rule.** Every setting `ProductionConfigSpec` requires is in the deployed template too
(`wlxjury-infra`'s `production.conf.j2`, later the in-repo `deploy/`); a config change in the
repo names what the server needs in the release notes. HTTPS everywhere, HSTS on HTTPS vhosts,
no directory listings, the maintenance switch tested.

**Example.** `conf/wlxjury/production.conf` (allowed hosts, Secure cookies, Flyway without
baseline or clean); `conf/apache/0002-wlxjury.conf` (HTTPS redirect),
`conf/apache/jury-images.conf` (`Options -Indexes`), `conf/apache/wlxjury-maintenance.conf`.

**Guards.** `ProductionConfigSpec`; the deploy tool tests in `scripts/deployment/`. Planned:
G14 `ProductionConfigParitySpec` (S8: B5's keys in the infra template), infra
`test_check_vhosts.py` (HSTS).

**Exceptions.** O1: the infra template lacks B5's block; applied by hand before any followups
deploy (register). `useSSL=false` for a local DB (S8).
