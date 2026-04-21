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

### 1. Session secret management is not compliant

`conf/application.conf` still contains:

- `play.http.secret.key = "changeme"`

This breaks the “no committed secrets / strong session integrity” standard and makes session forgery plausible.

Required standard:

- the Play secret must come from deployment-time configuration
- the committed config must not contain a reusable secret value

### 2. Password storage is not compliant

`db.scalikejdbc.User.hash` still uses unsalted SHA-1.

This does not meet modern password-storage requirements.

Required standard:

- passwords must use a slow password KDF such as Argon2id or bcrypt
- legacy hashes must be migrated transparently or invalidated on a controlled schedule

### 3. State-changing routes are not consistently protected by CSRF-safe HTTP semantics

The codebase mixes good CSRF form usage with unsafe exceptions:

- `conf/routes` disables CSRF on `/auth`
- `conf/routes` disables CSRF on `/rate/round/:round/pageid/:pageId/select/:select`
- `LargeViewController.rateByPageId(...)` performs a state change through `GET`

Required standard:

- no mutating endpoint may use `GET`
- no browser-facing state-changing route should carry `+ nocsrf` unless there is a documented non-browser API use case and compensating controls

### 4. Authorization is not enforced consistently on mutation endpoints

Some mutating endpoints only require authentication, not round-specific authorization:

- `GalleryController.selectWS(...)`
- `LargeViewController.rateByPageId(...)`
- `ImageDiscussionController.addComment(...)`

Required standard:

- any route that changes selections, comments, or round state must verify that the acting user is allowed to access the target round and contest

### 5. Safe SQL construction is not followed everywhere

`app/db/scalikejdbc/rewrite/ImageDbNew.scala` constructs SQL with string interpolation for `regions`, `withPageId`, and related filters.

This breaks the repository’s otherwise good parameterized SQL pattern.

Required standard:

- rewrite `SelectionQuery` to produce `SQLSyntax` or parameterized SQL fragments
- prohibit request-derived raw SQL construction

### 6. Error handling leaks internal details

`app/ErrorHandler.scala` logs exceptions and also returns exception messages to the user.

Required standard:

- user-facing errors must be generic
- detailed exception text belongs in logs, not HTML responses

### 7. Host-header protection is explicitly disabled

`conf/application.conf` disables:

- `play.filters.hosts.AllowedHostsFilter`

Required standard:

- enable host allowlisting for all deployed hostnames
- document how local development and tests opt into safe overrides

### 8. Browser hardening headers are incomplete or too permissive

Current config has a CSP, but it is permissive:

- `unsafe-inline`
- `unsafe-eval`
- broad `img-src https://*`

The repo also does not define an application-level policy for headers such as:

- `X-Frame-Options` / `frame-ancestors`
- `X-Content-Type-Options`
- `Referrer-Policy`
- `Permissions-Policy`

Required standard:

- tighten CSP toward a minimal allowlist
- define a documented baseline for security headers

### 9. Sensitive operational scripts still expose credentials unsafely

`scripts/run-with-prod-dump.sh` passes database passwords through `MYSQL_PWD`.

Required standard:

- operational scripts must avoid putting sensitive passwords in process environments when safer alternatives exist

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

5. Secrets and credentials
- Keep secrets out of committed config.
- Use strong password hashing.
- Avoid logging or printing credentials.

6. Error handling and logging
- Show generic error pages to users.
- Log structured details server-side.

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

