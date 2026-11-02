# Security Incident Prevention Guidelines

## Purpose

This document describes the security practices that are already present in the WLX Jury codebase, the standard we should follow across the repository, and the places where current code does not yet meet that standard.

The goal is incident prevention:

- prevent unauthorized access
- prevent credential compromise
- prevent injection and unsafe data handling
- reduce blast radius when failures happen
- make unsafe changes visible during review

This document is based on the current code in the repository as of 2026-04-20.

## Threat Model

The highest-value assets in this application are:

- authenticated jury, organizer, admin, and root sessions
- user credentials and password hashes
- contest configuration and round state
- juror selections, comments, and ratings
- Wikimedia-backed image-fetching and proxying paths
- deployment secrets and production database credentials

The most relevant incident classes for this codebase are:

- account takeover
- privilege escalation
- CSRF-triggered state changes
- SQL injection
- secret leakage
- SSRF or unsafe remote fetches
- excessive error disclosure
- host-header abuse and cache poisoning

## Practices Already Used In The Codebase

### 1. Role-based authorization with contest scoping

The main authorization model is already centralized and should remain the default:

- `app/controllers/Secured.scala` uses `withAuth(...)` for authenticated controller actions.
- `Secured.contestPermission(...)` and `Secured.roundPermission(...)` scope admin and jury access to the correct contest or round.
- `db.scalikejdbc.User` implements role checks such as `hasRole`, `hasAnyRole`, `isAdmin`, `canEdit`, and `canViewOrgInfo`.

Standard:

- new controller actions must use `withAuth`
- actions that mutate contest or round state must use contest- or round-scoped permission checks, not just generic authentication
- user-specific edit paths must enforce ownership or admin/root privileges

### 2. Play form binding for request validation

Many endpoints already rely on Play `Form` binding instead of hand-parsing request bodies:

- `app/controllers/LoginController.scala`
- `app/controllers/UserController.scala`
- `app/controllers/ContestController.scala`
- `app/controllers/ImageController.scala`
- `app/controllers/ImageDiscussionController.scala`

This is a good baseline because it gives structured validation and reduces ad-hoc parsing bugs.

Standard:

- prefer `Form` mappings or typed request DTOs for all POST bodies
- validate identifiers, enums, and booleans as typed fields
- normalize user-controlled identifiers such as email/login values before persistence and comparison

### 3. SQL is mostly built through ScalikeJDBC parameter binding

Most repository code uses ScalikeJDBC query builders and named values:

- `SelectionJdbc`, `User`, `Round`, `ImageJdbc`, `CommentJdbc`
- `insert.into(...).namedValues(...)`
- `withSQL { ... where.eq(...).and.eq(...) }`
- CRUDMapper helpers such as `where("field" -> value)`

This is an important prevention practice because it avoids string-concatenated SQL in most DAO code.

Standard:

- all SQL must use ScalikeJDBC bind parameters or SQL syntax helpers
- raw string interpolation into SQL is not allowed for request-derived values
- dynamic `ORDER BY` fields must be chosen from a fixed allowlist

### 4. CSRF protection is present in most HTML forms

Most rendered forms include CSRF fields:

- `app/views/login.scala.html`
- `app/views/signUp.scala.html`
- `app/views/users.scala.html`
- `app/views/editUser.scala.html`
- `app/views/editRound.scala.html`
- `app/views/contest_images.scala.html`
- `app/views/large/commentsView.scala.html`

Standard:

- all state-changing browser actions must be protected by Play CSRF
- state changes should use POST/PUT/DELETE, not GET
- `+ nocsrf` routes must be exceptional and documented inline

### 5. Twirl auto-escaping is the default rendering model

Most pages render user and database content through Twirl templates, which escape text output by default. This is a meaningful XSS prevention baseline.

Standard:

- prefer normal Twirl interpolation for untrusted text
- use `Html(...)` only for content produced by trusted code that already returns safe markup
- new uses of `Html(...)` should be justified in review

### 6. External fetches are constrained in the image cache/proxy flow

The image caching path already uses a narrower fetch model than arbitrary URL proxying:

- `LocalImageCacheService` defaults to `https://upload.wikimedia.org`
- cacheability checks are tied to Wikimedia Commons thumbnail paths
- `ImageProxyController` reconstructs the image from the existing DB entry instead of accepting an arbitrary remote URL
- Apache `conf/apache/jury-images.conf` serves cached files and proxies misses only to `upload.wikimedia.org`

Standard:

- remote fetch code must use explicit host allowlists
- never add “fetch arbitrary URL” behavior for image handling
- path parsing must remain strict and typed

### 7. Some operational controls already exist at deployment edge

Apache config already provides useful protection patterns:

- HTTP to HTTPS redirection in `conf/apache/0002-wlxjury.conf`
- maintenance-mode switch via `/etc/wlxjury/maintenance`
- `Options -Indexes` on the local image cache in `conf/apache/jury-images.conf`

Standard:

- edge config should continue to enforce HTTPS
- local storage roots must not expose directory listings
- operational toggles used during incidents should be documented and tested

### 8. Secrets are partly externalized through environment variables

Some credentials are already sourced from environment variables:

- DB host/name/user/password in `conf/application.conf`
- Commons credentials in `conf/application.conf`

Standard:

- secrets must not be committed in repo config
- production secrets must come from environment files or a secret manager
- sample config files may document variable names, never real values

## Practices Not Followed Consistently Today

State on 2026-11-02 (`ukwikidev/followups`, batches B1–B6, with `ukwikidev/api-auth` merged).
The findings this section listed before are fixed; what is still open comes first.

### Still open

1. **No per-IP or per-account sign-in throttling.** Sign-in and sign-up are bounded only by
   the bcrypt semaphore (`bcrypt.maxConcurrent`, `bcrypt.maxQueued`, 503 `signin.busy` when
   full), which protects the CPU, not the accounts: password guessing is limited only by
   bcrypt's cost. Required standard: throttle failed sign-ins per client address and per
   account (in the app or at Apache, e.g. `mod_evasive`/fail2ban on the `/auth` log).
2. **`style-src 'unsafe-inline'`.** unitegallery builds its tiles with `style=` attributes,
   and six template attributes remain (the galleries' `display:none`, the pager's width, the
   previous-round filters, the job status). `script-src` has no `'unsafe-inline'` or
   `'unsafe-eval'` any more. Required standard: move the template styles to classes, then
   drop `'unsafe-inline'` from `style-src` once unitegallery is patched or replaced.
3. **The CSP is set through the deprecated `play.filters.headers.contentSecurityPolicy`**
   (Play logs a deprecation warning). Moving it to `play.filters.csp.CSPFilter` keeps the
   same policy.
4. **Headers left at Play's defaults.** `SecurityHeadersFilter` sends `X-Frame-Options: DENY`,
   `X-Content-Type-Options: nosniff`, `X-XSS-Protection` and a `Referrer-Policy` with its
   defaults, and the CSP has `frame-ancestors 'none'`; no `Permissions-Policy` is set, and no
   test asserts these headers. `img-src`/`media-src https://*` stay broad (Commons and its
   thumbnails). HSTS belongs to the Apache vhosts in `wlxjury-infra` and is recommended there
   (docs/deployment.md, "Host names and secure cookies").
5. **Legacy SHA-1 hashes of accounts that never sign in again** stay until invalidated on a
   controlled schedule (see Done, passwords).

### Done

- **Session secret.** `production.conf` requires `PLAY_HTTP_SECRET_KEY`; the `"changeme"`
  in `application.conf` is for development and tests only.
- **Password storage.** Follow-ups batch B4: bcrypt (`$2b$`, cost 12) in
  `services.PasswordHasher`. A legacy unsalted SHA-1 hash is checked only on sign-in and
  replaced by a bcrypt hash on success; nothing writes SHA-1 any more. New passwords are at
  most 72 bytes; hash computations are bounded (`bcrypt.*`).
- **CSRF-safe HTTP semantics.** Votes, sign-out, contest switching, the theme switch and API
  token management are POSTs with the CSRF token; no `+ nocsrf` route is left.
- **Authorization on mutations.** Gallery and large-view votes and comments check the round
  and its contest (`MutationAuthorizationSpec`, `CommentAuthorizationSpec`); a denial is
  logged on `security.authz` with a reference and the account id only (no email).
- **SQL construction.** `ImageDbNew`'s filters are `SQLSyntax` with bound values.
- **Error handling.** One error page with message keys (`views.html.errorPage`); a server
  error shows only a logged reference; `{error, reference}` JSON for scripts and the API.
- **Host-header protection.** `AllowedHostsFilter` is on; production allows only
  `WLXJURY_ALLOWED_HOSTS` (batch B5).
- **Secure cookies.** Production marks the session, flash, language, theme and gallery-order
  cookies `Secure` (batch B5).
- **CSP.** No inline scripts but two, allowed by their hashes (the theme script and the large
  views' restore); no `'unsafe-eval'`; `object-src 'none'`, `base-uri 'self'`,
  `form-action 'self'`, `frame-ancestors 'none'` (batch B5).
- **The REST API** (`/api/...`) is served with authentication: the session cookie or a
  personal token, root-only contest endpoints (`ukwikidev/api-auth`).
- **Operational scripts.** `scripts/run-with-prod-dump.sh` passes no password on a command
  line: a temporary client option file (mode 600, mounted read-only) and an `--env-file`,
  removed by a trap on every exit path (batch B6).
- **Unescaped HTML.** Every `Html(...)` in `app/` has a trusted source; the monuments' wiki
  text from the lists goes through `views.WikiLinks`, which escapes it (batch B6; the audit
  is in `docs/plans/2026-11-01-open-followups.md`, item 2.6).

## Repository Security Standards Going Forward

Every new feature or refactor should satisfy these rules:

1. Authentication and authorization
- Use `withAuth`.
- Use contest/round/user ownership checks for every mutation.

2. Input handling
- Bind and validate request data through typed forms or DTOs.
- Normalize logins and emails before lookup and persistence.

3. Database access
- Use parameterized ScalikeJDBC builders only.
- Treat all route/query/body values as untrusted.

4. Browser safety
- Use CSRF protection on all browser mutations.
- Use POST for state change.
- Keep Twirl escaping as the default.
- Vendored scripts, kept as copied and unmaintained upstream: `public/javascripts/unitegallery.js`
  (Unite Gallery 1.7.28) and `jquery.raty.js` (jQuery Raty 2.7.1). Both insert what they are
  given with jQuery's `.html()`, so text passed to them is escaped for that sink: a tile's
  `alt` caption and `data-description` are escaped once more inside the attribute
  (`gallery.scala.html`, `GalleryDescriptionViewSpec`); raty gets numbers and empty hints only
  (`large-stars.js`). Replace them rather than patch them; `security.RawHtmlSinksSpec` lists
  the app's own raw HTML sinks.

5. Secrets and credentials
- Keep secrets out of committed config.
- Use strong password hashing.
- Avoid logging or printing credentials.

6. Error handling and logging
- Show generic error pages to users.
- Log structured details server-side.
- Show message keys and a logged reference, never an exception's text (job status included:
  `services.jobs.JobError`); `security.ErrorTextSpec`.
- Log accounts by id; no bound SQL values in production logs (`db.scalikejdbc.SqlLogging`);
  `security.LogHygieneSpec`.
- Exports write user text through `controllers.Csv.text` (spreadsheet formulas neutralised);
  `security.ExportsSpec`.

7. External I/O
- Restrict outbound fetches to explicit allowlists.
- Validate remote paths and local file paths strictly.

8. Deployment hardening
- Enforce HTTPS and host allowlisting.
- Keep security headers documented and tested.

## Review Checklist

Use this checklist in code review:

- Does every new route have the correct authorization scope?
- Does every state-changing browser action use POST and CSRF?
- Is all SQL parameterized?
- Are secrets or passwords exposed in config, logs, scripts, or responses?
- Does any error path reveal internal exception text?
- Does any new remote fetch accept arbitrary user-provided URLs?
- Does any new `Html(...)` render untrusted input?
- Does deployment config stay aligned with documented allowed hosts and headers?

