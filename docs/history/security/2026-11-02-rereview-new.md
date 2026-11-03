# Security re-review: new findings (2026-11-02)

> **Status: historical** (the 2026-11-02 re-review). Live status: [register.md](../../security/register.md).

## Scope, commit, method

- **Tree:** `ukwikidev/security-rereview` at `9e3bc9ec` (the integrated `ukwikidev/followups`:
  batches B1–B8 and `ukwikidev/api-auth`).
- **Goal:** issues that `98a8a79e:docs/security/review.md`, `incident-prevention-guidelines.md`,
  `incident-prevention-implementation-plan.md` and `local-images-review-followups.md` do not
  already list, nor `docs/history/security/2026-11-02-rereview-previous.md` (written in parallel; its O2 and O3 are
  referenced, not repeated, with verification detail below). Known open items (no sign-in throttling, `style-src 'unsafe-inline'`, the
  deprecated CSP key, default headers, HSTS in Apache, legacy SHA-1 hashes, public Swagger) are
  not repeated.
- **Areas:** sign-in, sign-up, profile and password changes, sessions; personal API tokens,
  `CsrfModule`'s Bearer bypass and the Tapir endpoints; every route in `conf/routes` (role and
  contest checks, IDOR, POST/CSRF); user and role management; SQL construction; Twirl and JS
  sinks; CSV and text downloads; outbound calls (Commons, monument lists, the image proxy and
  local cache); configuration, logging, dependencies.
- **Method:** static review (route → action → permission → data access), then a throwaway
  spec (not committed) running the real controllers against the Testcontainers DB to confirm
  N1 and O2. Each finding says how it was verified.

## Findings

| ID | Severity | Confidence | Title | Location |
|---|---|---|---|---|
| N1 | High | Confirmed (spec) | A contest admin can make any account, their own included, root through the role field | `app/controllers/UserController.scala:293-302`, `app/db/scalikejdbc/UserContestJdbc.scala:10` |
| N2 | Low | High | Own password and email change without the current password; API tokens survive a password change (amplifies O3) | `app/controllers/UserController.scala:212-302` |
| N3 | Low | High | CSV export is open to spreadsheet formula injection (juror names in the header) | `app/controllers/Csv.scala:82-86`, `app/controllers/GalleryController.scala:192-196` |
| N4 | Low | High | Wiki account names are neither validated nor unique: one account can take another's wiki-account sign-in and session, and the users page builds Commons links from them unencoded | `app/db/scalikejdbc/User.scala:312-319,474-477`, `app/views/users.scala.html:59-77` |
| N5 | Low | Medium | Outgoing greeting mail is sent "From" the admin's own unverified name and email to any address an admin enters | `app/services/SendMail.scala:25-39`, `app/controllers/UserController.scala:344-385` |
| N6 | Info | High | Sign-in and sign-up are open to login CSRF from a browser with no cookie for the site | `conf/application.conf` (Play CSRF `protectHeaders` default), `app/controllers/LoginController.scala:65-71` |
| N7 | Info | High | Sign-up tells whether an email has an account | `app/controllers/LoginController.scala:206-218` |
| N8 | Info | High | The binary gallery's tile caption is HTML that unitegallery inserts with `.html()`; the file title in it is escaped only once | `app/views/gallery.scala.html:35`, `public/javascripts/unitegallery.js:20901` |
| N9 | Info | High | Job status JSON shows the raw exception message | `app/services/jobs/JobScheduler.scala:524`, `app/controllers/JobController.scala:93` |
| N10 | Info | Medium | Dependencies: unused `play-pac4j`/pac4j on the classpath; `pekko-http` 1.0.1 client; vendored unitegallery and jquery.raty are unmaintained | `build.sbt:144-148,186-187` |
| N11 | Info | Medium | Slow statements (over 1 s) and failed statements are logged with their bound values at WARN/ERROR; the JDBC URL has `useSSL=false` | `conf/application.conf:116-120`, `conf/wlxjury/production.conf:6` |

---

### N1. A contest admin can make any account, their own included, root through the role field

- **Severity:** High. **Confidence:** confirmed by a spec against the real controller.
- **Location:** `UserController.updateAccount` (`app/controllers/UserController.scala:293-302`),
  `UserService.addToContest` (`app/services/UserService.scala:44-57`), `UserContest.roles`
  (`app/db/scalikejdbc/UserContestJdbc.scala:10`), `User.inContest`
  (`app/db/scalikejdbc/User.scala:57-61`).
- **Description:** the edit form's `roles` field is free text (`"roles" -> optional(text)`,
  `UserController.scala:509`). `updateAccount` refuses only the exact string `root`
  (`role != User.ROOT_ROLE`) and stores anything else in `user_contest.role` with
  `UserContestJdbc.updateRole`, unchecked against `User.FORM_ROLES`. When a membership is read,
  `UserContest.roles` splits that column on commas and trims each part, and `User.inContest`
  adds those parts to the user's roles. So a value that is not literally `root` but contains
  it as one comma-separated part (or `root` with surrounding spaces) passes the check and is
  read back as the `root` role. The new-user branch (`id = 0`) has the same gap:
  `addToContest` takes `formUser.contestRole` (which filters out only the exact `root`) and
  `createMembership` stores it. `importUsers` is not affected (it checks `IMPORT_ROLES`).
- **Impact / scenario:** the admin of any one contest posts their own profile (or a member's,
  or a new user) with such a role value for their contest. From the next request on, in that
  contest, `hasRole("root")` is true: every `ContestPermission`/`hasContestRole` check passes
  for every contest (`User.hasContestRole` lets root through regardless of contest), and the
  root-only pages open: all users and contests, editing any account's email and password
  (`canEditAccount`), resetting passwords, creating contests, the root-only REST API with a
  personal token. One contest's admin takes over the whole installation and every other
  contest's jury data.
- **Evidence:** throwaway `ThrowawaySecReviewSpec` (not committed): an admin of contest 10 posts
  `/users/save` for their own id with a comma-joined role value containing `root`; the response
  is 303, `user_contest.role` holds the value as sent, `Secured.sessionUser(email, "10")` has
  `root`, and `allUsers()` (root only) answers 200 for that session. Result: passed (see "Verification run").
- **Suggested fix:** accept only a role from `User.FORM_ROLES` (exact match) in
  `editUserForm` (`verifying`) and again in `updateAccount`/`addToContest` before any write;
  make `UserContestJdbc.updateRole`/`createMembership` reject anything else (a single-role
  allowlist at the DAO). Stop splitting `user_contest.role` on commas (V62 writes one role),
  or at least never accept `root` (and `USER_ID_*`) from that column: root comes only from
  `users.is_root`. Consider a DB `CHECK` on `user_contest.role`. Audit existing rows for a
  comma or `root` in `role`.
- **Guarding test:** `MultiContestUserSpec` (or a new `RoleAssignmentSpec`): an admin's save
  with a role outside `FORM_ROLES` (comma list, `root` with spaces, `USER_ID_1`, unknown
  word) is a 400 and changes nothing, for an existing member and for `id = 0`; and a
  `UserContestSpec` example that a stored `jury,root` row never yields `root`.

### N2. Own password and email change without the current password; API tokens survive it

- **Severity:** Low on its own; it is what turns O3's long-lived session into a permanent
  takeover. **Confidence:** high (code read).
- **Location:** `UserController.saveUser`/`updateUser`/`updateAccount`
  (`app/controllers/UserController.scala:212-302`), `ApiTokenJdbc`.
- **Description:** the own-profile form (`/users/save` with the user's own id) sets a new
  password (`formUser.password`) and a new email with only the session and the CSRF token;
  `canEditAccount` is true for oneself, and no current password is asked for. A password change
  revokes neither the user's personal API tokens nor (O3) their other sessions.
- **Impact / scenario:** whoever holds a copy of a session cookie (O3: it never expires) changes
  the email and password and creates an API token; the owner can neither sign in nor end the
  attacker's access, and resetting the password (root) leaves the token working.
- **Suggested fix:** ask for the current password on an own password or email change (not on
  an admin's change of another account); on any password change or reset, bump the session
  version O3's fix adds and offer to revoke (or revoke) the user's API tokens.
- **Guarding test:** `PasswordStorageSpec`/`ApiTokenPageSpec`: an own password change without
  the current password is a 400 and changes nothing; after a password change, older sessions
  are refused (O3) and the tokens are revoked (or listed for revocation).

### Already raised as O2 and O3 (`docs/history/security/2026-11-02-rereview-previous.md`): verification and severity

- **O2, account pre-claim: confirmed by a spec.** A sign-up through `LoginController.signUp`
  with an email nobody has, then an admin's `/users/save` with `id = 0`, that email and role
  `jury` in contest 10: the one account with the email gets the `jury` membership, and
  `User.login` with the sign-up's password returns it (result under "Verification run"). Two
  additions: an own-profile email change also pre-claims an address (no verification there
  either), and sign-up's "email taken" message (N6) tells an attacker which addresses are still
  free to claim. Severity: Medium as O2 says, but High where admins are added by email (the
  claimant administers the contest, and with N1 the installation).
- **O3, sessions: agreed, but Medium rather than Low.** Play 3.0.7's defaults
  (`play_2.13-3.0.7/reference.conf:151,175`: `maxAge = null`, `jwt.expiresAfter = maxAge`)
  give the cookie no expiry, and no production setting overrides them; combined with N2 a
  copied cookie is a permanent takeover the owner cannot end.

### N3. CSV export is open to spreadsheet formula injection

- **Severity:** Low. **Confidence:** high (code read).
- **Location:** `Csv.header`/`Csv.row` (`app/controllers/Csv.scala:82-86`), served by
  `GalleryController.csvStream` (`GalleryController.scala:192-196,304-319`).
- **Description:** the round CSV puts each juror's `fullname` in the header and the file title
  in each row as they are; `CSVWriter` quotes, but a cell that starts with `=`, `+`, `-`, `@`
  (or a tab/CR) is still a formula to Excel, LibreOffice and Google Sheets. Any juror sets
  their own full name on their profile.
- **Impact / scenario:** a juror names themselves with a formula; the organiser who opens the
  round's CSV in a spreadsheet gets a formula that can build links to outside sites with
  data from the sheet, or (in old Excel with DDE enabled) worse.
- **Suggested fix:** in `Csv.writeRow`/`row`/`header`, prefix a cell starting with
  `= + - @ \t \r` with a `'` (OWASP CSV injection guidance); numbers written by the app (rates,
  ranks) are not affected because they are produced, not user text.
- **Guarding test:** `CsvSpec`: a juror name and a title starting with each of those
  characters come out prefixed.

### N4. Wiki account names are neither validated nor unique

- **Severity:** Low. **Confidence:** high (code read).
- **Location:** `User.byUserName`/`findByAccount` (`app/db/scalikejdbc/User.scala:312-319,474-477`),
  `Secured.sessionUser` (`Secured.scala:410-411`), `UserController.updateAccount`,
  `app/views/users.scala.html:59-77`.
- **Description:** a user may set their own `wiki_account` to any text; there is no unique key.
  Sign-in by wiki account and every request of a session signed in that way resolve the name
  with `findByAccount(...).headOption`, the lowest id. The session stores the login as typed
  (the wiki name), not the account id. The users page puts `wikiAccount` into Commons URLs
  (`User_talk:@user.wikiAccount&action=edit&...`, `Special:EmailUser/@user.wikiAccount?...`)
  without URL encoding (HTML escaping only).
- **Impact / scenario:** (a) an account older than a juror who signs in by wiki account sets
  its own wiki account to that juror's: the juror's sign-ins check the older account's
  password and fail, and the juror's open session switches to the older account on its next
  request (votes and pages of the wrong account). (b) A juror's wiki account with `&` and
  further parameters adds parameters to the Commons link an admin clicks (e.g. `preload` an
  edit with text of the juror's choosing). (c) the wiki-account pre-claim of O2.
- **Suggested fix:** store the account id in the session (and look it up by id); validate wiki
  account names (MediaWiki user-name rules), normalise them and add a unique key; URL-encode
  them in links (`URLEncoder` as `monuments.scala.html` does).
- **Guarding test:** `UserControllerSpec`: a wiki account another account has is refused; a
  session survives another account taking a similar name; `users` page links encode the name.

### N5. Greeting mail is sent "From" the admin's own unverified name and email

- **Severity:** Low. **Confidence:** medium (code read; the MTA's relay and SPF/DMARC policy
  are not in this repository).
- **Location:** `SendMailSMTP.sendMail` (`app/services/SendMail.scala:25-39`), called from
  `UserService.sendMail` for every new member when the contest's greeting is on.
- **Description:** the mail's `From` is `"<admin full name> <admin email>"`, both set by the
  admin on their own profile without verification, and the body is the admin-editable greeting
  template; recipients are any emails the admin enters (one by one or a whole import list).
- **Impact / scenario:** an admin of any contest (or anyone with N1, or O2) sends mail through the
  production MTA that claims to be from any address, with any text, to any list of addresses:
  phishing that passes the server's SPF and harms the domain's reputation.
- **Suggested fix:** send from a fixed address of the tool (`play.mailer` config), put the admin
  in `Reply-To` only, and cap the number of greetings per import/hour.
- **Guarding test:** `UserServiceSpec`: the sent `Email.from` is the configured sender whatever
  the creator's profile says.

### N6. Login CSRF from a browser with no cookie for the site

- **Severity:** Info. **Confidence:** high.
- **Location:** Play's CSRF filter defaults (`play.filters.csrf.header.protectHeaders` =
  `Cookie`, `Authorization`), `POST /auth`, `POST /signup`.
- **Description:** the filter checks only requests that carry a cookie or an `Authorization`
  header. A browser that has never visited the site (no session, language or theme cookie)
  can be made to post the sign-in form of an account the attacker controls.
- **Impact:** the victim then works in the attacker's account (anything they enter, e.g. a
  comment, is the attacker's to read). Low value here, noted for completeness.
- **Suggested fix:** set `play.filters.csrf.header.protectHeaders = null` (check every
  POST), or check `Origin`/`Sec-Fetch-Site` on `/auth` and `/signup`.
- **Guarding test:** `CsrfTestApp` router spec: a cookieless `POST /auth` without a token is
  refused.

### N7. Sign-up tells whether an email has an account

- **Severity:** Info. **Confidence:** high.
- **Location:** `signUpForm`'s `signup.email.taken` (`LoginController.scala:216-218`).
- **Description / impact:** an unauthenticated visitor learns which emails are registered
  (jurors, organisers): input for phishing, and for O2 (which addresses are not yet taken).
- **Suggested fix:** the same response for taken and new emails (with verification by mail,
  O2), and throttling (open item 1 of the guidelines).

### N8. Tile captions are HTML inserted with `.html()`, the title escaped only once

- **Severity:** Info (not exploitable with Commons titles today). **Confidence:** high.
- **Location:** `app/views/gallery.scala.html:35`; unitegallery's text panel
  (`public/javascripts/unitegallery.js:15388,20901`).
- **Description:** the `<img alt>` holds an `<a>` element whose text is `@file.title`.
  Twirl escapes it for the attribute, the browser decodes the attribute, and unitegallery
  inserts the decoded value with `.html()`; so the title reaches the DOM as markup. MediaWiki
  titles cannot contain `<`, `>` or HTML entities, so a Commons title cannot inject today; a
  title from another source (a future import, a test fixture, a renamed-file path) could.
  `data-description` next to it is escaped twice and is safe.
- **Suggested fix:** build the caption with `HtmlFormat.escape(file.title)` inside the
  attribute (as `data-description` does), or let gallery-init.js build the link with
  `textContent`.
- **Guarding test:** `GallerySpec`: a title with `<` renders as text in the tile caption.

### N9. Job status JSON shows the raw exception message

- **Severity:** Info. **Confidence:** high.
- **Location:** `JobScheduler` stores `e.getMessage` as the job's `error`
  (`JobScheduler.scala:524`), returned by `JobController.json` (`JobController.scala:93`) and
  shown by job-status.js.
- **Description / impact:** contest admins see driver and Commons client messages (SQL
  fragments with values, internal host names) contrary to the "message keys only" rule in
  CLAUDE.md. Admin-only, so low exposure.
- **Suggested fix:** store a message key (and a log reference) in `job.error`; log the
  exception text only.

### N10. Dependencies

- **Severity:** Info. **Confidence:** medium (versions read from `build.sbt`; no CVE database
  was queried).
- `play-pac4j` 12.0.0 / pac4j 6.0.4.1 are on the classpath but no code uses them: remove.
- `pekko-http` 1.0.1 (the image cache's HTTP client): update to the current 1.x line.
- `public/javascripts/unitegallery.js` and `jquery.raty.js` are vendored, unmaintained copies
  with many `.html()` sinks (see N8): keep their inputs escaped, or replace them.
- Play 3.0.7, jQuery 3.7.1, Bootstrap 5.3.8, Tom Select 2.6.2, MariaDB Connector/J 3.5.3,
  bcrypt 0.10.2: no known issue that applies.

### N11. SQL values in WARN/ERROR logs; `useSSL=false`

- **Severity:** Info. **Confidence:** medium.
- **Location:** `conf/application.conf:116-120` (`warningEnabled = true`,
  `warningLogLevel = warn`, and ScalikeJDBC's default `loggingSQLErrors`),
  `conf/wlxjury/production.conf:6`.
- **Description:** password-hash statements are quiet (`User.quietly`), but every other
  statement slower than 1 s, and every failing one, is logged with its bound values at
  WARN/ERROR, which the production logback keeps: emails, names, comment text, API token
  hashes (harmless by themselves: SHA-256 of 256 random bits). The JDBC URL disables TLS, which
  matters only if `WLXJURY_DB_HOST` is not local.
- **Suggested fix:** keep the slow-query warning but log the statement without values
  (`printUnprocessedStackTrace`/a custom `GlobalSettings.queryCompletionListener`), and
  `useSSL=true` (or `sslMode=verify-full`) when the database is remote.

## Verification run

A throwaway `controllers.ThrowawaySecReviewSpec` (two examples, deleted after the run, not
committed) ran on this commit through `scripts/agent-check.py` (`Test/compile`, then
`testOnly`): **Passed: Total 2, Failed 0, Errors 0** (log
`target/checks/20261102-111320-3836.log`).

1. **N1:** an admin of contest 10 (no root) posts `/users/save` for their own id with a
   comma-joined role value containing `root` and `contest = 10`: 303; `user_contest.role` holds
   the value as sent; `Secured.sessionUser(email, Some("10"))` has `root`; `allUsers()` (root
   only) answers 200 to that session.
2. **O2:** with a root and an admin of contest 10 in place, `LoginController.signUp` creates an
   account for `future.juror@example.com` with the claimant's password; the admin's
   `/users/save` with `id = 0`, that email, `jury`, contest 10 answers 303; there is still one
   account with the email, now with `(10, jury)`, and `User.login` with the claimant's password
   returns it.

## Checked and fine

- **Bearer CSRF bypass (`CsrfModule`, `ApiAuth.bearerWithoutSession`):** needs `/api/` (the
  router's prefix `/api` only matches `/api/...`, so `/apix` and `/api` are not bypassed),
  exactly one `Authorization` header of the Bearer scheme (case-insensitive scheme; two
  headers neither bypass nor authenticate), no session cookie and an empty session; the
  caller is then the token alone, and page routes never read tokens.
- **Personal tokens:** 32 random bytes, SHA-256 stored, unique key, format checked before any
  lookup, revoked/expired/user checks, revoke limited to the owner, create needs the session,
  CSRF token and a one-time nonce, 20 active cap, `no-store`; never logged (prefix or id).
- **Tapir API:** every endpoint is root-only via `Secured.checked`, before the body is read;
  decode failures and exceptions answer fixed codes; a JSON 404 for any other path.
- **Votes and comments:** `selectWS`, `rateByPageId` and criteria votes change only the
  caller's own selection in an open round of a contest where they have a role; comments check
  the round's contest, `mayViewRound` and that the file is in the viewer's view.
- **Galleries, large view, region JSON, CSV:** `viewAs` limits other jurors' votes to
  `canViewOrgInfo` and to jurors of the round; contest switching only on page GETs.
- **Round admin (save, set, set user, stat table, new-files count, edit):** the round's
  contest is checked with `adminOfRound`; an edited round must be in the submitted contest;
  jurors are loaded only from the contest's members; previous rounds only from the same
  contest.
- **Jobs:** status, cancel and retry need admin of the job's contest; others' jobs answer as
  missing.
- **Contest files, COI view, thumbnail URL list, image import and cache:** contest permission,
  and a round parameter must belong to the contest.
- **User editing (other than N1):** `editableUser` restricts admins to their current
  contest's members; `canEditAccount` keeps an admin from changing an account that is in, or
  worked in, another contest or is root.
- **SQL:** `createUnsafely` is used only with constant fragments, enumerated orders
  (`ListOrder`, `BinaryOrder`), and integers; request values are bound.
- **Open redirects:** `safeLocalPath` (no scheme, authority, `//` or backslash, length cap) on
  every `returnTo`; the image proxy's redirect has a fixed host.
- **Image proxy and local cache:** needs a session that may view galleries; files and URLs are
  built from the stored Commons URL, not the request path; no SSRF (Commons and the
  configured lists hosts only).
- **Twirl and JS:** the remaining `Html(...)` uses are constants or escaped
  (`GalleryTiles`, `JsTexts`, `WikiLinks`, `FieldView`); JS sinks write numbers or use
  `textContent`; Tom Select's renderers escape; password inputs never echo a value.
- **Webjars:** only four webjars are served, after decoding and normalising the path.
- **Readiness:** loopback only, after Play's trusted-proxy `X-Forwarded-For` resolution; no
  secrets in the body.
- **Allowed hosts, secure cookies, error pages, CSRF error page:** as documented; no request
  or exception text in responses.
- **bcrypt path:** trimmed input, 72-byte rule for new passwords, SHA-512 pre-hash for long
  legacy ones, dummy hash for unknown logins, guarded rehash (`replaceHash` with the expected
  hash), semaphore and in-flight cap, quiet statements.
- **Sign-out and contest switch:** POST with CSRF; GET `/switch-contest` and `/logout` change
  nothing.
- **Greeting links:** use `wlxjury.host`, not the request's Host.
