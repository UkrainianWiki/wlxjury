# Security re-review: consolidated report (2026-11-02)

For the team. Detailed evidence is in the two working documents:

- [Previous reviews re-checked](2026-11-02-rereview-previous.md) (item by item, `a1883256`);
- [New findings N1–N11](2026-11-02-rereview-new.md) (`76b60f23`).

The resolution plan is [docs/plans/2026-11-02-security-resolution.md](../plans/2026-11-02-security-resolution.md);
the plan for secure-coding rules, checklists and guard specs is
[docs/plans/2026-11-02-security-practices.md](../plans/2026-11-02-security-practices.md).

## Executive summary

**Reviewed:** the application at `9e3bc9ec` (`ukwikidev/security-rereview` = the integrated
`ukwikidev/followups`: batches B1–B6 and B8, api-auth, bcrypt, allowed hosts, CSP with hashes),
the deployment template in `wlxjury-infra` at `d544c17` (read only, no vault or secret files), and,
for the role finding, the 2024 `master` (`71e39b73`) and the release recorded as deployed on
2026-10-27 (`0.13-authz-6fa5b56fb021`, commit `6fa5b56f`). Static review; the new-findings work
also ran one throwaway spec (not committed) confirming N1 and O2. This consolidation re-read the
code paths of N1–N11, O2 and O3 independently (static only).

**Headline risks**

1. **N1 (High): a contest admin can make any account root**, their own included, through the
   free-text role field of the user edit form. Root sees and changes every contest and every
   account. **Production is exposed** (see below). This needs a hotfix before anything else.
2. **O2 (Medium; High where admins are added by email): account pre-claim.** Anyone can sign up
   with an email they don't own; when an organizer later adds that email (or a wiki account the
   claimant has set on their profile), the claimant's account joins the contest with the
   claimant's password.
3. **O3 (Medium): sessions never expire or get revoked on the server**, and (N2) the own profile
   changes the email and password without the current password, so a copied cookie is a
   permanent takeover that sign-out or a password change doesn't end.
4. **O1 (Medium, infra): the deployed config template lacks batch B5's settings** (allowed hosts,
   Secure cookies). A release of the current branch on it refuses to start; patched halfway, the
   cookies stay non-Secure.
5. **No sign-in throttling** (Medium).

The rest is Low or Info. No previously fixed item regressed in code.

## N1: production exposure

**Production is vulnerable, and so is the 2024 `master`.**

- **Deployed release.** The last recorded deployment (2026-10-27) is `0.13-authz-6fa5b56fb021`
  at schema V66. Its code has the same three pieces as the reviewed commit: the edit form's
  `"roles" -> optional(text)`, `updateAccount` refusing only a role equal to `root`
  (`UserController.scala:222` there), and `UserContest.roles` splitting `user_contest.role` on
  commas (`UserContestJdbc.scala:10`). Whether a later release has been deployed since is not
  verified here: check `readlink /opt/wlxjury/current` on the server. Every 0.13 build with
  `user_contest` (V61/V62) has the issue.
- **`master` (2024).** Same class through `users.roles`: `saveUser` refuses only a role set that
  contains exactly `root` (`UserController.scala` ~171), `User.updateUser` stores the text, and
  the row reader splits it on commas and trims (`User.scala:222,236`). In addition, `master`'s
  `havingEditRights(user, formUser)` checks the admin's rights against the contest id *sent in
  the form*, so an admin could edit any account id (password included) by sending their own
  contest. Not relevant while 0.13 runs, but a restore to a pre-0.13 release brings both back.
- **Who can exploit it:** any account that is `admin` in at least one contest. With O2, anyone
  who pre-claimed the email an organizer later adds as admin.
- **Traces:** the app writes no audit log of role changes (P19), and access logs show only
  `POST /users/save`. The evidence is in the data: `user_contest.role` rows that are not exactly
  one known role, and `users.is_root` accounts nobody expects. V62 turned every `users.roles`
  that contained `root` into `is_root = 1`, so an exploitation under `master` would show as an
  unexpected root account today. The read-only queries are in S1 of the resolution plan.

## Consolidated table

Severity is after this verification. Verdict: CONFIRMED (code path re-read end to end),
PLAUSIBLE, REJECTED; for previous items, the status the re-check found. Owner: **app** (code in
this repository), **infra** (`wlxjury-infra` / the planned `deploy/`), **ops** (the server, data,
workstation). Batch: the resolution plan's batch.

| ID | Title | Severity | Verdict | Location | Status | Owner | Batch |
|---|---|---|---|---|---|---|---|
| N1 | Contest admin can make any account root through the free-text role | High | CONFIRMED (spec in the new-findings run; code re-read) | `UserController.scala:283-311,485`, `UserService.scala:44-57`, `User.scala:50-61`, `UserContestJdbc.scala:10,44-52` | Open; production exposed | app (+ops data check) | S1 |
| O2 | Account pre-claim: open sign-up, unverified email and wiki account join an organizer's contest | Medium (High where admins are added by email) | CONFIRMED | `LoginController.scala:134-160`, `UserService.scala:44-57,88-92` | Fixed in S2 (`ukwikidev/security-s2`, not merged) | app | S2 |
| O3 | No server-side session expiry; sign-out and password change don't end other copies | Medium (previous doc: Low) | CONFIRMED | Play defaults (`session.maxAge = null`); `Secured.sessionUser`; `LoginController.logout:173-178` | Fixed on branch (S3: absolute/idle limits, per-account version ended by sign-out everywhere, password change, root; not reviewed yet) | app | S3 |
| O1 | Infra `production.conf.j2` lacks B5's allowed hosts and Secure-cookie lines | Medium | CONFIRMED | infra `roles/wlxjury_app/templates/production.conf.j2` | Open | infra | S8 |
| P1 | No sign-in throttling (per address, per account) | Medium | Open (re-checked) | `LoginController.auth`/`signUp` | Open | app or infra | S4 |
| N2 | Own password and email change without the current password; API tokens survive a password change | Low | CONFIRMED | `UserController.scala:212-311,484`; `ApiTokenJdbc` | Fixed on branches: current password (S2); a password change ends other sessions, tokens kept by user decision with "revoke all tokens" (S3, not reviewed yet) | app | S2, S3 |
| N3 | CSV export open to spreadsheet formula injection (juror names, titles) | Low | CONFIRMED | `Csv.scala:81-85`, `GalleryController.csvStream:304-319` | Fixed (S5): `Csv.text`, `CsvSpec`, G8 | app | S5 |
| N4 | Wiki account names not validated or unique; session resolves the name to the lowest id; unencoded in Commons links | Low | CONFIRMED | `User.scala:312-319,474-477`; `users.scala.html:59,64,74`; no unique key (V55) | Fixed in S2 (validation, encoding, ambiguous names refused; duplicates postponed) | app | S2 |
| N5 | Greeting mail sent "From" the admin's unverified name and address, to any address | Low | CONFIRMED (delivery depends on the forged domain's SPF/DMARC; the new-findings doc's "passes SPF" is overstated) | `SendMail.scala:27-39` | Open (postponed by the user) | app | S2 |
| P2 | No HSTS on the HTTPS vhosts | Low | Open (re-checked) | infra `wlxjury-ssl.conf.j2` | Open | infra | S8 |
| P3 | `style-src 'unsafe-inline'`; broad `img-src`/`media-src https://*` | Low | Open | `application.conf:105` | Open | app | S7 |
| P4 | Security headers never asserted on a response; no `Permissions-Policy` | Low | Open | tests | Open | app | S7 |
| P5 | Image proxy: any width ≤ 3840, off-set widths rescaled per request, no rate limit | Low | Partially fixed | `ImageProxyController`, `LocalImageCacheService:36,113-139` | Partial | app | S6 |
| P6 | A juror deactivated in a round can still vote on assigned files | Low | Open (intent to confirm) | `SelectionJdbc.rate`, vote actions | Open | app | S6 |
| P7 | Root "reset password" sends nothing but says it did | Low | Open | `UserController.scala:532-559` | Fixed in S2 (user decision (a): a mailed link) | app | S2 (moved from S6) |
| P8 | Dormant legacy SHA-1 hashes | Low | Open / count needs production | `User.login` | Open | app + ops | S6, S9 |
| P9 | Original-file fetch has no host allowlist | Low | Partially fixed | `LocalImageCacheService:477,561` | Partial | app | S6 |
| P10 | Production dump with personal data and bench volumes on the workstation | Low | Open | `C:\scala\wlxjury-bench-work\proddata\`, `wlxjury-bench-*` volumes | Open | ops | S9 |
| N6 | Login CSRF from a browser with no cookie for the site | Info | CONFIRMED | Play `csrf.header.protectHeaders` default; `POST /auth`, `/signup` | Open | app | S4 |
| N7 | Sign-up tells whether an email has an account | Info | CONFIRMED | `LoginController.scala:216-218` | Fixed in S2 (sign-up gone) | app | S2 |
| N8 | Tile caption is HTML inserted with `.html()`, the title escaped once | Info | CONFIRMED (not exploitable with Commons titles) | `gallery.scala.html:35`; `unitegallery.js` | Fixed (S5): caption escaped once more, `GalleryDescriptionViewSpec`, G5 | app | S5 |
| N9 | Job status JSON shows the raw exception message | Info | CONFIRMED | `JobScheduler.scala:375,441,523`; `JobController.scala:91`; `job-status.js:64` | Fixed (S5): `JobError` key + reference, `JobControllerSpec`, G13 | app | S5 |
| N10 | Unused pac4j on the classpath; `pekko-http` 1.0.1; vendored unitegallery and raty | Info | CONFIRMED (no CVE database queried) | `build.sbt:145,186-187` | Fixed (S10): pac4j removed, vendored JS noted; `pekko-http` deferred to a scalawiki-first bump | app | S10 |
| N11 / P12 | Slow and failing SQL logged with bound values; JDBC `useSSL=false` | Info | CONFIRMED | `application.conf:116-120`; `production.conf:6` | App part fixed (S5): `SqlLogging`, `SqlLoggingSpec`, G9; `useSSL` open (S8) | app (+infra if the DB is remote) | S5, S8 |
| P11 | CSP set through the deprecated key | Info | Open | `application.conf:105` | Open | app | S7 |
| P13 | API: no limit on failed tokens; root can't revoke others' tokens; double click makes two | Info | Open | `ApiTokenService`, `UserController.revokeApiToken` | Root revocation and double click fixed (S10); failed-token limit open (S4) | app | S4, S10 |
| P14 | Sign-in and sessions ignore `users.deleted_at` | Info | Open | `User.byUserName` | Fixed in S2 | app | S2 |
| P15 | Infra logback lacks `ApiTokenService`/`coi` at INFO | Info | Open | infra `logback.xml.j2` | Open | infra | S8 |
| P16 | Stale docs and comments; no secret-rotation procedure | Info | Open | see previous doc item 16 | Texts fixed (S10); rotation procedure documented (S3, `docs/deployment.md`); the rotation run itself open (S9) | app (docs) + ops | S9, S10 |
| P17 | `/api/docs` under the production CSP never checked in a browser | Info | Cannot verify | `/api/docs` | Open | app | S7 |
| P18 | `byUserName` validates the trimmed name but looks up the untrimmed one | Info | Open | `User.scala:312-319` | Fixed in S2 | app | S2 |
| P19 | No audit log of admin actions (role and account changes) | Info (feature; matters for N1 forensics) | Open | — | Open | app | S10 |
| P20 | First sign-up on an empty database becomes root | Info (install-time race) | CONFIRMED | `LoginController.scala:148` | Fixed in S2 (/setup with a setup token) | app | S2 |

P-numbers 1–17 are the previous doc's open items 1–17 (O1–O3 keep their ids); P18–P20 are its
remaining notes, listed here so the table is complete.

## Verification of N1–N11, O2, O3

| ID | Verdict | Severity (theirs → this review) | Evidence |
|---|---|---|---|
| N1 | CONFIRMED | High → High | `updateAccount` compares the whole value with `root` (`UserController.scala:309`); `contestRole` drops only an exact `root` (`User.scala:50-51`), so `User.create`/`createMembership` store it for a new user too; `UserContest.roles` splits and trims (`UserContestJdbc.scala:10`) and `inContest` adds the parts (`User.scala:57-61`). A `USER_ID_<n>` value is also stored and read as that marker (minor) |
| N2 | CONFIRMED | Low → Low | edit form has no current-password field (`UserController.scala:484`); `setPasswordHash` touches no token or session state |
| N3 | CONFIRMED | Low → Low | `Csv.header`/`row` write `fullname` and title as they are (`Csv.scala:81-85`) |
| N4 | CONFIRMED | Low → Low | index on `wiki_account` is not unique (V55); `findByAccount(...).headOption`; `@user.wikiAccount` in URLs (`users.scala.html:59,64,74`); the plain `User:` link shows even for names Commons doesn't know |
| N5 | CONFIRMED | Low → Low | `from = s"${fromUser.fullname} <${fromUser.email}>"` (`SendMail.scala:32`); no bounce address set, so the envelope sender is the forged address too and the recipient's SPF/DMARC check of that domain decides delivery |
| N6 | CONFIRMED | Info → Info | Play checks CSRF only with a `Cookie` or `Authorization` header; no override in `conf/` |
| N7 | CONFIRMED | Info → Info | `signup.email.taken` constraint (`LoginController.scala:216-218`) |
| N8 | CONFIRMED | Info → Info | `alt="<a …>@file.title</a>"` (`gallery.scala.html:35`), decoded by the browser and inserted with `.html()` |
| N9 | CONFIRMED | Info → Info | `describe(e)` = `getMessage` → `fail(jobId, …)` → `"error"` in the JSON → shown by `job-status.js:64` |
| N10 | CONFIRMED | Info → Info | pac4j only in `build.sbt`; nothing in `app/` imports it (CLAUDE.md still says pac4j does authentication: stale) |
| N11 | CONFIRMED | Info → Info | `warningEnabled = true`, `warningLogLevel = warn`; `useSSL=false` in the URL |
| O2 | CONFIRMED | Medium → Medium (High where admins are added by email) | sign-up creates an unverified account; `existingAccount` matches by email, or by a self-set wiki account; `createMembership` joins it |
| O3 | CONFIRMED | Low (previous) / Medium (new) → Medium | no `play.http.session.maxAge` or `jwt.expiresAfter` in `conf/`; session holds only the login; `logout` only replaces the browser's cookie; the only global revocation is rotating `PLAY_HTTP_SECRET_KEY` |

No finding was rejected.

## Confirmed fixed (brief)

SQL injection in `ImageDbNew.where` and every sibling `createUnsafely` (constants, enumerated
orders, integers only); the hard-coded secret (env-only, Play refuses `changeme` in Prod);
password storage (bcrypt cost 12, legacy SHA-1 checked once and rehashed, constant time, dummy
hash, quiet statements, CSPRNG passwords); `MYSQL_PWD` in the dump script; CSP for scripts
(`script-src 'self'` + two hashes, `object-src 'none'`, `frame-ancestors 'none'`); Secure and
HttpOnly cookies (repo); allowed hosts (repo); `@Html` and DOM sinks audited and guarded
(`WikiLinksSpec`, `NoInlineScriptsSpec`, `InlineScriptHashesSpec`); CSRF on every POST, no
`nocsrf`, Bearer bypass only for token-only `/api/` requests; mutation and view authorization in
the resource's contest (`MutationAuthorizationSpec`, `CommentAuthorizationSpec`, `OwnVotesSpec`,
`CoiAuthorizationSpec`); own-votes-only views; vote value checks; error pages with message keys
only; local-only `returnTo`; denial logs by account id; the REST API served root-only with
hashed personal tokens; the local-images follow-ups #1–#3, #7, #8.

## Links

- [2026-11-02-rereview-previous.md](2026-11-02-rereview-previous.md): every previous item, with
  evidence and counts (45 fixed, 9 partial, 16 open, 0 regressed).
- [2026-11-02-rereview-new.md](2026-11-02-rereview-new.md): N1–N11 with scenarios, fixes and
  guarding tests, and the verification run.
- [Resolution plan](../plans/2026-11-02-security-resolution.md),
  [practices plan](../plans/2026-11-02-security-practices.md).
