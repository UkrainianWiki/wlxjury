# Re-review of the previous security reviews (2026-11-02)

> **Status: historical** (the 2026-11-02 re-review). Live status: [register.md](../../security/register.md). The documents it re-checks are deleted; read them with `git show 98a8a79e:<path>`.

**Scope.** Every finding, recommendation, guideline and checklist item of the earlier security
documents, checked again against the current code:

- `98a8a79e:docs/security/review.md`, `incident-prevention-guidelines.md`,
  `incident-prevention-implementation-plan.md`, `local-images-review-followups.md`;
- the security items of `docs/plans/2026-11-01-open-followups.md` (section 2, 4.5, 4.7, 6.1, 6.4,
  the B5 and B6 review-fix tables), `98a8a79e:docs/plans/2026-11-01-api-auth.md` (merged, `992d8eb2`),
  `98a8a79e:docs/plans/2026-10-27-error-handling.md`, `98a8a79e:docs/superpowers/plans/2026-04-20-sql-injection-fix.md`
  and the COI plan's authorization section;
- the security rows of the Montage comparison (`docs/comparison/01-wlxjury.md` WJ-F1, WJ-F2, WJ-Q4;
  `03-comparison.md` Q4), which exists only untracked in the main checkout (read only).

**Commit.** `ukwikidev/security-rereview` at `9e3bc9ec` (the integrated `ukwikidev/followups`:
batches B1–B6 and B8, api-auth, bcrypt, allowed hosts, CSP with hashes). Deployment config was read
in `wlxjury-infra` at `d544c17` (read only, no vault or secret files opened).

**Method.** Static reading only (`rg`, ranges of the files); no spec was run, no request was sent to
anything. Claims marked "Fixed" were re-checked in the code, including sibling call sites
(other raw SQL, other `Html(...)` and DOM sinks, other cookies, other `.get` on request ids, other
unauthenticated routes, other log lines with personal data), and the guarding tests were opened to
confirm they assert the property. Statuses: **Fixed**, **Partially fixed**, **Open**, **Regressed**,
**N/A**, **Cannot verify** (needs the deployed server). "Fixed (repo)" means the code and config in
this repository do it, but the server reads a config that `wlxjury-infra` generates (see O1 below).

Counts over the tables below: 45 Fixed (of which 6 only in the repo, see O1), 16 Holds (mechanisms and practices), 9 Partially fixed, 16 Open, 2 N/A, 2 Cannot verify, 0 Regressed.

No item regressed in code. The one material gap is deployment: the infra template of
`/etc/wlxjury-runtime/production.conf` has none of batch B5's settings.

## 1. `98a8a79e:docs/security/review.md`

| Item | Previous claim | Now | Evidence | Notes |
|---|---|---|---|---|
| Vuln 1, SQL injection in `ImageDbNew.where` | Fixed | **Fixed** | `ImageDbNew.scala:388-416`: regions bound through `sqls.in` and a bound LIKE parameter. Tests: `SelectionQuerySpec:41,50` (payload absent from SQL text, present in parameters), `ImageDbNewDbSpec:139-151` (payload as data on MariaDB) | Siblings checked: every `SQLSyntax.createUnsafely` (`ImageDbNew` 110-214, 240, 261, 389-409; `RoundDistributionHandler:798`; `DistributeImages:343`; `ImageJdbc:657`) takes constants, a column name passed to `regionOf`, `Long`/`Int` ids, or order columns from the fixed `ListOrder`/`BinaryOrder` values (`RoundList.scala:176-183`). The raw `SQL(s"…")` in `byRegionStat`/`regionCounts`/`regionRateCounts` interpolate numbers only. No interpolated SQL in the B8, category-operation or redistribution-filter code |
| Vuln 2, hard-coded secret | Fixed | **Fixed** | `conf/wlxjury/production.conf:5` and infra `production.conf.j2:5` require `${PLAY_HTTP_SECRET_KEY}`; `ProductionConfigSpec:63` | `"changeme"` stays in `application.conf:8` for dev/tests; Play refuses it in Prod. Rotation is not documented (`docs/deployment.md:65`: "rotation is separate") |
| Vuln 3, unsalted SHA-1 | Fixed (B4) | **Fixed** | `services/PasswordHasher.scala` (bcrypt `$2b$`, cost 12, legacy SHA-1 only checked then rehashed, constant-time compare, dummy hash for unknown logins). Every writer hashes with it: `UserService.createAccount:76`, `User.setPassword:288`, `UserController.updateUser:273`, `resetPassword:545`. Tests: `PasswordHasherSpec` (17 examples), `PasswordStorageSpec:85` (rehash), `:262` ("never SHA-1"), `:323,338` (72 bytes) | Generated passwords now come from `SecureRandom` (`User.scala:260-270`). Hash writes are not SQL-logged (`User.quietly`, `User.scala:586-596`). Dormant legacy hashes remain (guidelines open 5) |
| Vuln 4, `MYSQL_PWD` in `run-with-prod-dump.sh` | Fixed (B6) | **Fixed** | `scripts/run-with-prod-dump.sh:75` (`export -n`), `:110-135` (mode-700 dir, mode-600 option file), `:163-167` (EXIT trap and signals), `:202-209` (`--defaults-extra-file`), `:251-256` (`--env-file`) | No other script sets `MYSQL_PWD` or `--password=`, except a test fixture with a fake password (`scripts/deployment/test_release_script.py:565`). Not executed here |
| Mechanism 1, Twirl auto-escaping | Active | **Holds** | All `@expr`; the remaining `Html(...)` uses match the 2.6 audit (see Step 4) | |
| Mechanism 2, CSRF | Active | **Holds (stronger)** | No `+ nocsrf` in `conf/routes`; every POST passes the filter. `modules.CsrfModule` skips only `/api/` requests with exactly one Bearer header, no session cookie and an empty session (`ApiAuth.scala:78-85`); Play's `bypassHeaders` is empty by default (play-filters-helpers 3.0.7 `reference.conf`). CSRF-enabled tests: `CsrfTestApp.withCsrfApp` in `ApiSpec`, `ApiTokenPageSpec`, `CoiAuthorizationSpec:214`, `LargeImageSpec`, `SignOutAndSwitchSpec`, `ThemeSwitchSpec` | The two forms without a token field are GETs (`pageLinks` pager jump, `monuments` filter) |
| Mechanism 3, REST API content type | Active | **Holds** | The API is now served through Play (`-> /api api.ApiRouter`); errors are JSON (`api/ApiFailure`, `ErrorHandler` under `/api/`, `ErrorHandlerSpec:24`) | |
| Mechanism 4, image proxy content type | Always `image/jpeg` | **Holds (changed)** | `ImageProxyController:131-140`; `LocalImageCacheService.contentType:729-737` returns only png/gif/webp/jpeg | Never an HTML type; `nosniff` is sent by default |
| Mechanism 5 / E, CSP | Fixed for scripts (B5) | **Partially fixed** | `application.conf:105`: `script-src 'self'` plus two hashes, no `'unsafe-eval'`, `object-src 'none'`, `base-uri 'self'`, `form-action 'self'`, `frame-ancestors 'none'`. Guards: `ContentSecurityPolicySpec` (config string), `InlineScriptHashesSpec` (renders pages and hashes every inline script), `NoInlineScriptsSpec` (no handler, no `javascript:`) | Open: `style-src 'unsafe-inline'`; `img-src`/`media-src https://*`; set through the deprecated `play.filters.headers.contentSecurityPolicy`. No test asserts the header on a response |
| A, `X-Content-Type-Options` | Sent by default, untested | **Holds; untested** | Play default `contentTypeOptions = "nosniff"`, not overridden; `rg -i nosniff test` finds nothing | |
| B, `X-Frame-Options` | Sent (`DENY`) + `frame-ancestors` | **Holds; header untested** | Play default `DENY`; `ContentSecurityPolicySpec:73` checks `frame-ancestors 'none'` in the config | |
| C, `X-XSS-Protection` | Sent by default | **Holds** | Play default | Legacy header |
| D, session cookie `Secure` | Fixed (B5) | **Fixed (repo); Cannot verify in production** | `production.conf:39-41` (session, flash, language); `ThemeCookies` (`Theme.scala:52-61`) and sort cookies (`RoundList.scala:518-525`) follow `session.secure`; no JS writes cookies; `ProductionConfigSpec:120-132`, `GalleryOrderSpec` | The infra template lacks these three lines (O1) |
| JS string injection in `preloadImages` (Step 1) | Gone | **Fixed** | Template deleted; no inline script except the two hashed ones (`NoInlineScriptsSpec:111,115`); values reach scripts through `data-*` attributes and a JSON block (`JsTexts`, `<` escaped) | |
| Step 2, headers via `SecurityHeadersFilter` | Partly | **Partially fixed** | Defaults in force (filter not disabled anywhere in `conf/`); `Referrer-Policy` is Play's default | No `Permissions-Policy`; no response-level header test |
| Step 3, `session.secure` / `httpOnly` | Fixed | **Fixed (repo)** | as D; `httpOnly` is Play's default `true` | Session has no `maxAge`/JWT expiry (O3) |
| Step 4, `@Html(...)` audit | Done (B6) | **Holds** | `rg 'Html\(\|HtmlFormat\.raw'` in `app/` gives exactly the uses in the 2.6 table; `WikiLinksSpec` (incl. `<script>`, `<img onerror>`, `-->`), `GalleryDescriptionViewSpec` (double-escaped `data-description`). DOM sinks: `cache-status.js:55` `innerHTML` of numbers and constants only | |
| Step 5, HSTS in Apache | Open (infra) | **Open (infra)** | infra `roles/wlxjury_tls/templates/wlxjury-ssl.conf.j2` and the repo's `conf/apache/*` set no `Strict-Transport-Security` | HTTP→HTTPS redirects exist for every name (repo `0002-wlxjury.conf:13-70`, infra `wlxjury-http.conf.j2:16`) |

## 2. `98a8a79e:docs/security/incident-prevention-guidelines.md`

| Item | Previous claim | Now | Evidence | Notes |
|---|---|---|---|---|
| Practice 1, `withAuth` and contest/round scoping | Followed | **Holds, one gap** | Every route but the intended public ones (`/auth`, `/signup`, `/logout` page, `/health/ready`, assets, webjars, the GET `/switch-contest` redirect) uses `withAuth*`; `ImageProxyController.serve:51-56` has its own signed-in check. Resource checks in the resource's contest: `RoundController.withAdminRound` (`setRound:381`, `setRoundUser:404`), `saveRound:243-257`, `ImageController.inContest:115-117`, `GalleryController.contestFilesView:488`, `thumbnailUrls:532`. Tests: `MutationAuthorizationSpec:79,283,332,578,603,639,788,833,852,919,952,970`, `CommentAuthorizationSpec:98`, `OwnVotesSpec`, `CoiAuthorizationSpec` | Gap: a juror deactivated in a round (`round_user.active = false`) can still vote on the files already assigned (`SelectionJdbc.rate:179-189` and both vote actions check only the contest role); no test. Possibly intended ("gets no new images", `User.scala:448`) |
| Practice 2, form binding; normalize logins | Followed | **Holds; normalization partial** | Forms throughout; sign-up lowercases the email (`LoginController` `signUpForm`) | `User.byUserName:312-319` validates the trimmed name but looks up `findByEmail(username)` untrimmed (functional, Info) |
| Practice 3, parameterized SQL; ORDER BY allowlist | Followed | **Holds** | See Vuln 1 | |
| Practice 4, CSRF on all browser mutations | Followed | **Holds** | See Mechanism 2. GET `/logout` only renders a page (`LoginController.logoutPage:166`); GET `/switch-contest` only redirects (`UserController:528`) | |
| Practice 5, Twirl escaping default | Followed | **Holds** | See Step 4 | |
| Practice 6, outbound fetches allowlisted | Followed | **Partially fixed** | Thumbnails are fetched from `wikiBaseUrl` (`LocalImageCacheService:84-86,497-505`); proxy redirects always go to `upload.wikimedia.org` (`ImageProxyController:175`) | The original-file fetch uses `images.url` as stored and only rewrites an `upload.wikimedia.org` host (`LocalImageCacheService:477`, `downloadWithRetry:561`): no host check. The value comes from the Commons API, so the exposure is low |
| Practice 7, edge config | Followed | **Holds (repo); Cannot verify live** | Redirects as in Step 5; `Options -Indexes` in `conf/apache/jury-images.conf:9` and infra `wlxjury-images.conf.j2:8` | |
| Practice 8, secrets from env | Followed | **Holds** | `production.conf` / infra template use `${…}`; no secret files tracked (`git ls-files`) | |
| Still open 1, sign-in throttling | Open | **Open** | `LoginController.auth`/`signUp` are bounded only by `PasswordHasher.admit` and the bcrypt semaphore (`application.conf` `bcrypt.*`) | Same for API tokens (256-bit, so guessing is impractical) |
| Still open 2, `style-src 'unsafe-inline'` | Open | **Open** | `application.conf:105` | |
| Still open 3, deprecated CSP key | Open | **Open** | `application.conf:105` | Info |
| Still open 4, default headers untested, no `Permissions-Policy`, HSTS | Open | **Open** | See A–C, Step 2, Step 5 | |
| Still open 5, dormant legacy SHA-1 hashes | Open | **Open / Cannot verify** | Only replaced on sign-in (`User.login:278-285`) | Count needs a production query |
| Done: session secret, passwords, CSRF semantics, SQL, REST API, scripts, `Html(...)` | Done | **Fixed** | See section 1; API: `Api.scala:63-99` (all four endpoints `secured(rootOnly)`, catch-all JSON 404), `ApiSpec` (incl. "a token on a page route", `:438`) | |
| Done: mutation authorization; denial logs account id only | Done | **Fixed** | `Secured.denialLogLine` (`Secured.scala:336-360`) has `accountId`, no email or name; `SecuredSpec` "identify the account by its id only, without its email or name"; `security.password` lines use `accountId` (`User.scala:302,308`) | Sibling: SQL timing warnings (>1 s) and SQL error logs print bound values, e.g. the email of a sign-in lookup (`application.conf` `scalikejdbc.global.loggingSQLAndTime.*`); only password statements are quiet. Info |
| Done: error handling | Done | **Fixed** | `ErrorHandler` (`ErrorHandlerSpec:10,51`); `/error` renders known keys only (`LoginController.error:181`); the round form shows only our own `require` texts, otherwise a reference (`RoundController.refusal:540-545`) | Remaining `.get` take validated input (`EditRound:60` after the field constraint at `:41-42`; `GalleryController:504` after an `isEmpty` check) or are dead code (`ContestController.fetchContests:34`, no caller) |
| Done: host header, secure cookies | Done (B5) | **Fixed (repo); not in the infra template** | `AllowedHostsModule`/`AllowedHosts.config` (refuses to start in Prod without names), `production.conf:31-32`; `AllowedHostsSpec:47-65`, `ProductionConfigSpec:82-185` | O1 |
| Checklist: route authorization, POST+CSRF, SQL, exception text, `Html(...)` | — | **Holds** | as above | |
| Checklist: secrets/personal data in logs | — | **Partially** | see the denial row's sibling note | |
| Checklist: arbitrary URL fetches | — | **Partially** | Practice 6 | |
| Checklist: deployment config aligned with allowed hosts and headers | — | **Open** | O1, Step 5 | |

## 3. `98a8a79e:docs/security/incident-prevention-implementation-plan.md`

| Item | Previous claim | Now | Evidence | Notes |
|---|---|---|---|---|
| P0 1, secret from env | Done | **Fixed**; rollout notes **Open** | as Vuln 2 | "Rollout notes for rotating existing sessions" not written |
| P0 2, bcrypt with legacy rehash | Done (B4) | **Fixed** | as Vuln 3 | |
| P0 3, authorization of selection/comment mutations | Done | **Fixed** | `GalleryController.selectWS:279-301` and `LargeViewController.rateByPageId:44-87` use `roundPermission`, `openToJurors`, `acceptsRate`, and change only the caller's own row; tests `MutationAuthorizationSpec:79,190,788,970`, `CommentAuthorizationSpec`, `LargeImageSpec:575,779` | See the round-deactivated juror gap (section 2, Practice 1) |
| P0 4, SQL injection; sortable fields allowlisted | Done | **Fixed** | as Vuln 1 | |
| P1 5, no GET mutations, no `nocsrf` | Done | **Fixed** | routes; `SignOutAndSwitchSpec`, `ThemeSwitchSpec` | |
| P1 6, no exception text to users | Done | **Fixed** | as "Done: error handling" | |
| P1 7, `AllowedHostsFilter` | Done (B5) | **Fixed (repo); Cannot verify live** | as above | O1: a release on the current infra template refuses to start (fails safe at readiness) |
| P1 8, browser headers | Done for scripts | **Partially fixed** | as Mechanism 5, A–C | No header integration test, no `Permissions-Policy` |
| P2 9, script password exposure | Done (B6) | **Fixed** | as Vuln 4 | |
| P2 10, sign-up session stores the login | Done | **Fixed** | `LoginController.signUpResult` (`withSession(UserName -> user.emailLo)`); `LoginControllerSpec:288` | |
| P2 11, `Html(...)` review | Done (B6) | **Fixed** | as Step 4 | |
| Docs: README "Security Controls" | Done | **Fixed** | `README.md:5-15` | |
| Verification plan: header integration tests | — | **Open** | none found | |
| Verification plan: manual checks (cross-contest mutation, missing CSRF, malicious region, bad Host, sessions after secret rotation) | — | **Cannot verify** | automated equivalents exist for the first four (above); the last needs a deployment | |

## 4. `98a8a79e:docs/security/local-images-review-followups.md`

The document's own status table still shows #3, #4, #7, #8, #9, P2 and P3 as open; most are fixed.

| Item | Previous claim | Now | Evidence | Notes |
|---|---|---|---|---|
| 1, rounds of another contest created/edited/frozen | Fixed (`39d5aef`) | **Fixed** | `RoundController.saveRound:243-257`, `withAdminRound`; previous rounds by `findByIds(round.contestId, …)` (`RoundService.scala:272-275`); `MutationAuthorizationSpec:833,852,919,952` | |
| 2, failed distribution can't be retried | Fixed | **Fixed** | `User.findRoundJurors` falls back to `round_user` (`User.scala:448-457`); distribution is a resumable job | |
| 3, criteria rates outside the transaction | Open | **Fixed** | `DistributeImages.addCriteriaRates(…)(implicit session)` (`:341`); `RoundDistributionJobSpec:293` | Doc table stale |
| 4, image proxy cache misses (CPU/Wikimedia amplification) | Open | **Partially fixed** | `serve` now needs a signed-in viewer (`ImageProxyController:51-56`, cached per user); a width up to the widest cached one is scaled from a cached size, not fetched (`LocalImageCacheService:113-139`) | Any `px` ≤ 3840 is accepted (`MaxAllowedPx:36`); a width outside the cached set is scaled again on every request and a wider one fetched from Wikimedia each time; no per-image or per-user limit. Now only signed-in users can drive it |
| 5, regions missing from region stats | Fixed | **Fixed** | `byRegionStat` reads the `selection.monument_id` prefix | Not security |
| 6, stale `selection.monument_id` | Fixed | **Fixed** | V57, `ImageJdbc.update*` | Not security |
| 7, download "running" forever | Open | **Fixed (B1)** | `LocalImageCacheService:303-337,409-410`; `LocalImageCacheServiceSpec:540,554` | Doc table stale |
| 8, `thumb_urls` relative paths | Open | **Fixed** | `GalleryController.resizedImagesUrls:322-340` prefixes `wlxjury.host`; `ThumbnailUrlsExportSpec:14` | Export stays behind the proxy's sign-in |
| 9, `ConnectDb` key typo | Open | **N/A (deleted)** | `app/org/intracer/wmua/cmd/` holds only `DistributeImages`, `ImageWithRatingSeqFilter` | |
| P1, duplicate guard blocks retry | Fixed | **Fixed** | submit tokens (V59), job resume | |
| P2, `Selection.apply(Page, …)` without monument id | New | **N/A (deleted)** | no `Selection.apply(Page`, `GlobalRefactor` or `Tools` in `app/` | |
| P3, in-JVM locks assume one instance | New | **Fixed (documented)** | `docs/deployment.md:227` | |

## 5. Plans: open follow-ups, API auth, error handling, comparison

| Item | Previous claim | Now | Evidence | Notes |
|---|---|---|---|---|
| Open follow-ups 2.1–2.6, 2.8 | Done in B4–B6 | **Fixed** (2.2, 2.3 repo only; 2.4 scripts only) | sections 1–3 | |
| 2.7, docs of what is open | Done | **Fixed** | guidelines "Still open", README | The local-images doc's table and the api-auth plan's "denial line keeps the email" are stale (Info) |
| 4.5, logging config ships with releases | Open | **Open** | infra `logback.xml.j2:16-17` has only `application` at INFO, root WARN | Security lines at WARN (`security.authz`, revoked/expired tokens) are written; unknown-token INFO lines and `coi` are not (Info) |
| 4.7, production dump with personal data in the benchmark workspace | Open | **Open** | `C:\scala\wlxjury-bench-work\proddata\` still holds an 85 MB `.sql.gz` from 2026-10-07; seven `wlxjury-bench-*` Docker volumes exist | Contents not opened |
| 6.1, exception text on the round form | Open | **Fixed** (security part) | `RoundController.refusal:540-545` | English-only messages remain (not security) |
| 6.4, Tapir API served and secured | Done | **Fixed** | `ApiAuth`, `Api.scala`, `ApiSpec`, `ApiTokenJdbcSpec` (hash-only storage) | |
| B5 review fixes (HTTP vhost redirect, sort cookies, loopback hosts, port/suffix parsing, log level) | Done | **Fixed** | `0002-wlxjury.conf:25-26`; `RoundList.scala:518-525`; `production.conf:31-32`, `conf/routes` `+ anyhost` on `/health/ready` only; `AllowedHosts.parse`; logback `AllowedHostsFilter` at ERROR | |
| B6 review fixes (`WikiLinks` decoding, `export -n`, gallery description) | Done | **Fixed** | `WikiLinksSpec`, `run-with-prod-dump.sh:75`, `contestFiles.scala.html:64`, `gallery.scala.html:41` | |
| API auth: tokens only under `/api/`, never mixed with the session | Done | **Fixed** | `Secured.sessionUserOf` reads the session only; `ApiAuth.caller:44-58`; `ApiSpec:430,438` | |
| API auth: no rate limit on failed token attempts | Open | **Open** | `ApiTokenService.authenticate` | Info (256-bit tokens) |
| API auth: root can't revoke others' tokens; double click makes two | Open | **Open** | `UserController.revokeApiToken:198-205` (own only) | Info |
| API auth: deleted users | Token refused, session not | **Open (Info)** | `User.byUserName:312-319` ignores `deleted_at`, so sign-in and sessions of a soft-deleted user work | Nothing in the app sets `users.deleted_at` |
| API auth: `/api/docs` under the production CSP in a browser | Not run | **Cannot verify** | `ApiSpec` only checks no other host is loaded | |
| Error handling plan: denial page, JSON denials, `returnTo` local only, contest switching on page GETs only | Done | **Fixed** | `Secured.scala:35-63` (denials, `returnTo`), `:377-386` (`safeLocalPath`), `:457-474` (switching on page GETs only); `SecuredSpec`, `LoginControllerSpec`, `SignOutAndSwitchSpec` | |
| COI plan: new roles grant nothing; COI files to admins/organizers only | Done | **Fixed** | `Secured.ViewerRoles` (`Secured.scala:388`); `GalleryController.contestFiles:378` `contestPermission(ADMIN ++ ORG_COM)`; `CoiAuthorizationSpec:84-214` | |
| Comparison WJ-F2.4: juror sees another juror's or all votes by URL | Inferred open | **Fixed** | `GalleryService.viewAs:31-35`; `OwnVotesSpec:97,109` | |
| WJ-F2.4: large view without round/contest check | Inferred open | **Fixed** | `LargeViewController.show:96-134` (`mayViewRound`, `viewAs`); `LargeImageSpec:575` | |
| WJ-F2.4: vote values unchecked | Open | **Fixed** | `acceptsRate`; `MutationAuthorizationSpec:190` | |
| WJ-F2.4: switched-off jurors can vote | Open | **Partially fixed** | inactive contest members refused (`MutationAuthorizationSpec:639`); round-deactivated jurors not (section 2) | |
| WJ-Q4: keyboard URLs from the Host header | Open | **Fixed** | no `absoluteURL`/`request.host` in views; `LargeViewUrlsSpec` | `thumbnailUrls:538-539` falls back to `request.host` only if `wlxjury.host` is unset (it is always set) |
| WJ-Q4: raw monument wikitext | Open | **Fixed** | `views.WikiLinks` | |
| WJ-F1.6: reset password not sent, non-CSPRNG | Open | **Partially fixed** | CSPRNG fixed; the mail is still commented out while the flash says "New Password sent" (`UserController.scala:532-559`), so a reset locks the account out | Root-only |
| WJ-F1.2: open sign-up, first account root | By design | **Open (design)** | `LoginController.signUp:134-160` | See O2 |
| WJ-F11.3: no audit log of admin actions | Open | **Open** | none | Info (feature) |

## Open, regressed or partial items

No item regressed.

| # | Severity | Item | Suggested resolution |
|---|---|---|---|
| O1 | Medium | The deployed runtime config is generated by `wlxjury-infra` (`roles/wlxjury_app/templates/production.conf.j2`), which has none of B5's lines: `wlxjury.allowedHosts = ${WLXJURY_ALLOWED_HOSTS}`, `play.filters.hosts.allowed = []`, `play.http.session.secure`, `play.http.flash.secure`, `play.i18n.langCookieSecure`. A release on it refuses to start (`AllowedHosts.config` in Prod), and once that is patched alone the cookies stay non-Secure | Add the B5 block of `conf/wlxjury/production.conf` to the infra template and `WLXJURY_ALLOWED_HOSTS` to `runtime.env` before deploying this branch; consider a packaging or deploy check that compares the two |
| O2 | Medium | Account pre-claim (from the comparison's "open sign-up"): anyone can sign up with an unverified email, and an organizer adding that email, or a wiki account (self-editable on the own profile, not unique), joins the existing account to the contest (`UserService.addToContest:45-51`, `existingAccount:88-92`), whose password the self-registrant holds | Don't join accounts that were self-registered and never verified (or require email verification / invitation tokens); make `wiki_account` unique or not self-editable |
| 1 | Medium | No per-IP or per-account sign-in throttling | Throttle failed sign-ins per address and per account in the app, or fail2ban on the `/auth` log |
| 2 | Low | HSTS not set on the HTTPS vhosts (infra `wlxjury-ssl.conf.j2`) | `Header always set Strict-Transport-Security "max-age=31536000"` in the infra template |
| 3 | Low | `style-src 'unsafe-inline'`; broad `img-src`/`media-src https://*` | Move template styles to classes, then patch or replace unitegallery; narrow to `upload.wikimedia.org` if possible |
| 4 | Low | Security headers emitted only by default and never asserted on a response; no `Permissions-Policy` | One spec on a real response for CSP, `X-Frame-Options`, `nosniff`, `Referrer-Policy`; add `Permissions-Policy` |
| 5 | Low | Image proxy (#4): any width ≤ 3840 accepted, off-set widths re-scaled per request, no rate limit | Snap `px` to the cached/`thumbnailWidths` set (redirect others), limit misses per user |
| 6 | Low | Round-deactivated jurors can still vote on assigned files | Check `round_user.active` in both vote actions (if deactivation is meant to stop voting), with a negative test |
| 7 | Low | Root "reset password" sends nothing but says it did, locking the account out | Show the new password to root once, or send it, or remove the action |
| 8 | Low | Dormant legacy SHA-1 hashes | After a grace period, replace them with an unusable value and require a reset |
| 9 | Low | Original-file fetch has no host allowlist (`images.url`) | Refuse URLs not on `upload.wikimedia.org` (or `wikiBaseUrl`) before fetching |
| 10 | Low | Production dump with personal data and bench volumes still on the workstation (4.7) | Delete with the user's agreement |
| O3 | Low | Sessions never expire server-side; sign-out and a password change don't invalidate other copies of the cookie | Set `play.http.session.maxAge` (or `jwt.expiresAfter`) and store a per-user session version checked in `sessionUserOf` |
| 11 | Info | CSP set through the deprecated `play.filters.headers.contentSecurityPolicy` | Move to `play.filters.csp` with the same policy |
| 12 | Info | SQL timing warnings and SQL error logs print bound values (emails of sign-in lookups) | Quiet settings for user lookups, or disable value logging in production |
| 13 | Info | No rate limit on failed API tokens; root can't revoke others' tokens; a double click makes two tokens | Leave as recorded, or add when the API gets users |
| 14 | Info | Sign-in and sessions ignore `users.deleted_at` | Filter deleted users in `User.byUserName` if soft deletion is ever used |
| 15 | Info | Infra logback lacks `services.ApiTokenService`/`coi` at INFO (4.5) | Ship logging config with releases, as planned |
| 16 | Info | Stale docs and comments: `local-images-review-followups.md` table; api-auth plan's "denial line keeps the email"; `application.conf:107-111` says production allows loopback; `production.conf:34-38` says nothing reads `request.secure` (the `thumbnailUrls` fallback does); no secret-rotation procedure | Update the texts; document rotation (sessions are invalidated by a new key) |
| 17 | Info | `/api/docs` under the production CSP never checked in a browser | Run `ui-shots.py --csp` on it once |

O2 and O3 are outside the earlier documents' findings; they came up while re-checking their items
(open sign-up, session cookie) and are noted for the parallel fresh sweep.
