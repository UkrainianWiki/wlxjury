# Open follow-ups, gaps and cleanup from the plans and reviews

Status: plan, nothing implemented. Collected from every plan, review and doc on
`ukwikidev/theme-modes` at `cb103b24`. Each item below was checked against the code at that
commit and is still open. New features are left out (see the last section), and so are items
that a later plan or commit already resolved (see "Checked and already resolved").

Order: bugs and security first, then verification that was never done, then cleanup and docs.
Every code step follows CLAUDE.md: `Test/compile` first, the affected specs (negative
authorization cases for permission changes) through `scripts/agent-check.py`, and the full
suite once at the end.

## 1. Bugs

1.1 **Saving a round's form starts the round again.** `RoundController.saveRound`
(`RoundController.scala:266`) builds `editForm.round.copy(active = true)`, and
`Round.updateRound` passes it to `setActiveGuarded`. So renaming a stopped round starts it
again for its jurors. Source: performance-and-memory "Side issues". Fix: keep the stored
round's `active` on an edit (only a new round gets `true`), and leave start/stop to
`setRound`. Test: in `RoundEditPageSpec`, a stopped round stays stopped after its form is
saved, and a started one stays started.

1.2 **ScalikeJDBC's default pool depends on which module starts first.**
`conf/application.conf:75` enables `scalikejdbc.PlayFixtureModule`. That module registers a
dbcp2 pool with 8 connections as "default". Play's HikariCP pool (30 connections) registers
under the same name, and whichever registers last is the one used. In 21 fixture starts, dbcp2
won 12 times. Source: gallery-latency, "ScalikeJDBC's default pool is chosen by a race".
Fix: remove `PlayFixtureModule` from `play.modules.enabled` in production (fixtures load in
Dev/Test only), or set `db.default.poolMaxSize`. Then make `ConnectionPoolConfigSpec` check
`ConnectionPool.get()`, not only Play's pool.

1.3 **A contest's local image download can show "running" forever.**
`LocalImageCacheService.startDownload` (`:306`) sets `running = true` before it calls
`ImageJdbc.findByContestId`, and that call has no `Try`. The round variant already has one.
`runDownloadImpl` has no `recover` or `onComplete` on `registryReady` or on the stream, so if
either fails, `running` is never cleared and `cache-status.js` keeps polling. Source:
local-images review #7. Fix: give the contest variant the same `Try` as the round one, and set
`running = false` with the error on any failure of the whole future. Test: the image query
throws, and the progress stops running.

1.4 **The `/thumb_urls` export returns relative paths.** `Global.legacyThumbUrl` returns
`/wikipedia/commons/thumb/…`, so wget or a script fails on every line. Source: local-images
review #8. Fix: absolute URLs, built with the app's host (`wlxjury.host`) or `absoluteURL()`.
Decide which host the export is for: the app's proxy (signed-in users only) or
upload.wikimedia.org.

1.5 **`ConnectDb` always throws.** It reads the key `"db.default.pasword"`
(`app/org/intracer/wmua/cmd/ConnectDb.scala:16`). Source: local-images review #9. Fix the key,
or delete the command if nothing uses it (see 4.2).

1.6 **unitegallery's `ismouseover` plugin throws on every gallery page.** It calls
`.find("iframe").load(e)`, and jQuery 3 has no `.load` event method, so each gallery page logs
`a.indexOf is not a function` once (`public/javascripts/unitegallery.js:2308`). Source:
deleted-renamed-files. Fix: `.on("load", e)`, as one more local patch to the library. Check
that the console is clean in the screenshot harness (`scripts/ui-shots.py` already logs
console errors).

1.7 **Plain English 404s in `RoundController`.** Three places, `:328`, `:377` and `:398`,
answer `NotFound(s"Round $id not found")`. CLAUDE.md requires a 404 to use `errorPage` with
message keys. Fix: `errorPage` with `error.round.not.found`, as `GalleryController.thumbnailUrls`
already does. Test: in a controller spec, the status and that the round id doesn't appear as
text.

## 2. Security (docs/security)

The implementation plan's items 1, 3, 4, 5, 6 and 10 are done (see the end of this plan).
These are still open:

2.1 **Passwords are hashed with unsalted SHA-1** (`User.hash` → `Codecs.sha1`; also
`GlobalRefactor` and `cmd/AddUsers`). This is P0 #2 of the implementation plan. Fix: bcrypt
or Argon2id. Verify a legacy SHA-1 hash only to rehash it on a successful login, and never
store SHA-1 for a new or changed password. Tests: a legacy login is rehashed, and a new user,
a reset and a password change store the new format. Decide first how this relates to
`ukwikidev/oauth-login`: if password login goes away, the migration can be smaller.

2.2 **`AllowedHostsFilter` is turned off** (`conf/application.conf:85`). This is P1 #7. Fix:
turn it on in `production.conf` with the deployed host names (the list is in the
implementation plan; check it against the Apache vhosts first), and leave it off in tests.
Test: a request with an unknown `Host` is refused in production config.

2.3 **The session cookie isn't marked `Secure`.** Neither `production.conf` nor
`application.conf` sets `play.http.session.secure`, and Play's default is false. The theme
cookie follows the session cookie's setting, and the login page's script sets `Secure` from
the page's scheme. Source: security/review.md D, theme plan Phase 3. Fix:
`play.http.session.secure = true` (and the flash cookie) in `production.conf`. Check that
the app behind Apache sees HTTPS (`X-Forwarded-Proto`, `play.http.forwarded.trustedProxies`).
HSTS (review step 5) belongs to the Apache vhost in `wlxjury-infra`.

2.4 **The CSP allows `'unsafe-eval'` and `'unsafe-inline'`** (`conf/application.conf:84`).
This is P1 #8. Try removing `'unsafe-eval'` first: it only needs a check that no library
uses `eval` or `new Function`. Removing `'unsafe-inline'` needs section 5 done first. After
that, the only inline script left is the theme script in `layout`, which must run before the
first paint: allow it by its hash, which works because its text never changes, or by Play's
CSP nonce support (`play.filters.csp`). Extend `views.ContentSecurityPolicySpec`, and run the
harness with `--csp` in both modes.

2.5 **`scripts/run-with-prod-dump.sh` passes passwords as `docker -e MYSQL_PWD=…`** (lines
125, 139, 217), so they show in the process list. This is P2 #9. Fix: a temporary client
`.cnf` mounted read-only, removed by a `trap`.

2.6 **Audit `Html(...)` in the templates** (36 uses). This is P2 #11 and review step 4. For
each use, record where its markup comes from. Wrap or remove any use that can receive request
or Commons text.

2.7 **Docs:** the guidelines' "Practices Not Followed Consistently Today" still lists the
secret, GET votes, `nocsrf`, mutation authorization, SQL and error leaks as open, but all of
them are fixed. Update that section to what is still open (2.1–2.6), and add the README's
"Security Controls" section that the implementation plan asks for.

2.8 **Optional:** log only `accountId` in the `security.authz` denial line instead of the email
(error-handling, "Possible later fix", repeated in CLAUDE.md), so that rotated logs hold no
personal data.

## 3. Verification never done

These need the screenshot harness or a real browser. They don't change code unless something
fails.

3.1 **Theme, in a real browser:** no flash of the wrong theme on load, in Chrome and Firefox,
for each mode (Phase 3 checked only the script order in `ThemeViewSpec`). The harness has
never shot `auto` mode: Phase 3's run was stopped after three light shots, and Phase 4 and
its fix shot only `light,dark`. Run `shoot --mode auto` with `--scheme light` and with
`--scheme dark`.

3.2 **Category suggestions** (`category-suggest.js`): check them against live Commons and in
dark mode (theme follow-up 2).

3.3 **Back after a vote** (theme follow-up 5, `no-store`): vote, press Back, and check that
the stored rate is shown, in Chrome and Firefox.

3.4 **The criteria count of round 1327** (`CriteriaRate.countOf` replaced the hard-coded 2):
check it against production data. Read-only SQL on production can only be run by the user
(no DB login, see memory).

3.5 **Rated round by-rate page at ~2 s** (binary-aggregate "Left"): run it paired against
`master` (or the pre-branch base) on the same fixture, to see whether it got slower. Single
outliers of 4.9 s and 5.2 s were not investigated. If the paired run shows no regression,
record that and stop.

3.6 **Benchmark workload gaps** (prod-like-benchmark): "Rated avg 7" and the concurrent "New
files" step insert no rows, so two of the ten concurrent operations measure nothing. Make the
fixture give them rows (eligible rated images, a top-up target with new files) in a new suite
label, so that old results stay comparable. Do this only before the next capacity
measurement.

## 4. Cleanup and refactoring

4.1 **Dead code:** `RoundController.mergeRounds` (`:495`) has no route, and `Round.delete`
(`Round.scala:531`) has no caller (performance-and-memory "Side issues"). Either delete both,
together with `RoundService.mergeRounds` and its DAO tests, or decide to wire merging up. That
second choice is a feature and not part of this plan.

4.2 **`GlobalRefactor`, `cmd/AddUsers` and `cmd/ConnectDb`:** these are command-line leftovers.
`Selection.apply(Page, …)` doesn't set `monumentId`, so the rows they create drop out of
region galleries (local-images review P2). If `Tools` is unused, delete these with
`Selection.apply(Page, …)`. Otherwise set `monumentId` there, and fix 1.5.

4.4 **Messages:** 57 unused keys (mostly the old sign-up and mail flow), with English values in
uk/ru (theme follow-up 1). Delete the keys that no code reads, and keep
`MessagesCompletenessSpec` passing.

4.5 **The logging config ships with each release**
(`docs/plans/2026-10-30-logging-config-in-releases.md`, not started). Production reads
`/etc/wlxjury-runtime/logback.xml` from the infra role, so the `coi` logger's INFO lines from
the COI plan are written only if that file has them. Steps are in that plan: move the file to
`conf/logback-production.xml`, the packaging test, the deploy tool's lists, then the unit
template in `wlxjury-infra`. That last step is a server change, which the user applies.

4.6 **Retire the one-time cutover code in `wlxjury-deploy.py`** (`adopt-legacy`,
`scrub-legacy`, about 350 lines of the tool and 250 of tests; tooling-alternatives step 1).
Do it only after the user confirms that the cutover is accepted and that every recovery
record targeting the adopted legacy release is retired. Keep `wait_ready`'s legacy `/auth`
check until then.

4.7 **Benchmark leftovers** (bench/README §9): `C:\scala\wlxjury-bench-work\proddata\` still
holds a production dump copy with personal data, plus the `wlxjury-bench-*` volumes and
image. Remove them with the user's agreement once the results are accepted.

4.8 **Docs:**
- The theme plan's header still says "nothing implemented yet", and Phase 4 says "Not yet
  reviewed". Update both to done.
- `docs/superpowers/plans/*` have every task box unchecked, although the work shipped. Add one
  status line to each (done, the commit or branch). The blue/green plan and design should say
  "superseded: the blue/green scripts are disabled, see docs/deployment.md".
- Delete the empty files `docs/dbperf/{explain.txt,last-agent-message.json,last-agent-message.md}`,
  `docs/queries-improvement.txt` and `AGENTS.md`. Check first whether a tool expects
  `AGENTS.md`; Codex does.
- `DEV.md`: fill "TODO creating root admin" (`users.is_root`) and replace the Play 2.4,
  sbt 0.13 and Activator links.
- docs/deployment.md: note that the app assumes a single instance (in-JVM round locks and
  `RoundImageCounts`; local-images review P3).
- The COI plan asks to update the comparison's rows WJ-F4.9, WJ-F5.7, WJ-F6.2 and the
  eligibility rows. `docs/comparison/` exists only untracked in the main checkout, so do it
  where that document lives.

## 5. Scripts: inline code out of the templates, and page scripts out of the shells

Checked at `cb103b24`. All script files are self-hosted and linked, but templates still hold
15 inline `<script>` blocks with about 250 lines of code, plus 7 inline `on…=` handlers. The
two shells also load page-specific scripts on every page that uses them.

**Page scripts loaded by the shells for every page**

| Shell | What it loads | Pages that use it | Pages that load it |
|---|---|---|---|
| `main` | `unitegallery.js` (558 KB, not minified), `unite-gallery.css` (32 KB), `ug-theme-tiles.js`, `ug-theme-default.css`, and the inline unitegallery setup (run whenever someone is signed in, even with no `#gallery`) | `gallery`, `contestFiles` | 8: also `galleryByRate`, `fileList`, `roundStat`, `monuments`, `accessDenied`, `errorPage` |
| `main` | `tile-votes.js`, `vote-errors.js` | the tile and by-rate galleries | the same 8 (raty is already loaded only when needed) |
| `mainAdmin` | Tom Select (JS and CSS), `multi-select.js` and the round form's 53-line inline script (previous rounds, regions, jurors, distribution) | `editRound` (`monuments` loads its own copy) | 11, including `login`, `logout`, `signUp`, `errorPage`, `users`, `contests` |
| `mainAdmin` | `var contestId = …` (a global that no script reads) | none | 11 |

5.1 **The shells take a page's scripts.** `layout` already has a `head` slot. Give `main` and
`mainAdmin` the same kind of parameter (`head: Html = Html("")`), so each page adds only what
it needs. Snippets that the pages include:
- `snippets/galleryScripts`: unitegallery with its theme, its CSS and the setup, for
  `gallery` and `contestFiles`;
- `snippets/tileVoteScripts`: `vote-errors`, `tile-votes` and the raty files when there are
  stars, for `gallery` and `galleryByRate`;
- `snippets/multiSelectScripts`: Tom Select and `multi-select.js`, for `editRound` and
  `monuments`.

`region-menu.js` and `pager-jump.js` belong to the navbar's region menu and pager. Load them
where the navbar renders those parts, or keep them in `main`: they are small.

Delete the global `contestId`.

5.2 **Inline code moves to files, and data moves to `data-*` attributes.** Values from the
server go in attributes, so no JS string is built from Scala text anymore. This also removes
the `@Html(url)` uses inside JS strings that item 2.6 would otherwise have to audit.

| Inline today | File | Data it reads |
|---|---|---|
| `main`: unitegallery setup | `gallery-init.js` | `#gallery`'s `data-jury`, `data-binary`, `data-stars` |
| `mainAdmin`: round form (multi-selects, previous-round filters, distribution choices); `editRound`: COI confirmation, lazy jurors' table, "Count new files" (3 blocks) | `round-form.js`, loaded by `editRound` only | `JsTexts` as one `data-texts` JSON attribute (or a `<script type="application/json">`, which CSP doesn't treat as script) |
| `large/main_large`: `voteLarge` and the `[data-vote-url]` clicks | `large-view.js` | `#vote-form` |
| `large/keyboardNav`, `large/fileKeyboardNav`: vote and move keys | `large-keys.js` (Jest already tests these keys in `large-view-keys.test.js`) | `data-next-url`, `data-prev-url`, `data-back-url`, `data-vote-prefix`, … on the large view's root; paths only, as CLAUDE.md requires |
| `large/raty`: star setup (64 lines, with criteria) | `large-stars.js`, next to `large-rate.js` | `data-score`, `data-number`, `data-half`, `data-readonly`, `data-vote-prefix`, `data-vote-query` on each `#rate-image*` |
| `editRound`, `contest_images`: `pollCacheStatus(url, id)` | `cache-status.js` starts itself | `data-status-url` on the status element |
| `monuments`: regions multi-select | `multi-select.js` starts itself | `data-multi-select` on the select |
| `large`, `contestFile`: `wlxLargeSections.restore(document)` | `large-sections.js`, if restoring at `DOMContentLoaded` doesn't flash; otherwise it stays inline and is allowed by its hash | none |
| `layout`: the theme script, which must run before paint | stays inline, allowed by its hash (2.4) | none |

5.3 **Inline handlers.** The add-contest and add-user buttons become `<button type="submit"
form="add-contest">` and `form="add-user"`: the HTML `form` attribute needs no script.
editUser's reset `<a href="#" onclick>` becomes a `<button form="reset-password">`. The
`onerror` handlers on thumbnails and large images (`large`, `contestFile`) move to one
`error` listener on `document` in the capture phase (`error` events don't bubble), in
`large-view.js`. That listener must be registered in `<head>` so that it catches early
failures, and keeps the `data-fallback` logic.

Checks: Jest for each new file (the current inline code has no tests, apart from the keys).
Regenerate `LargeViewFixturesSpec` (`FIXTURES_UPDATE=true`, because the fixtures contain the
inline scripts). Run `views.*`, `RoundEditPageSpec`, `ContestFilesPageSpec`,
`LargeViewControllerSpec` (vote paths) and `views.LayoutSpec`. Add a new view spec: no
template renders `<script>` without `src` (apart from the theme script and JSON data blocks)
or an `on…=` attribute. Then take screenshots with `--csp` in both modes, with a CSP that has
no `'unsafe-inline'`: the harness logs any violation as a console error. Check the network
tab: a non-gallery page such as `roundStat` or `login` loads no unitegallery or Tom Select.

## 6. Found on a second pass

These came up when I reread every results section in full and checked the theme plan's 13
inconsistencies against the code. Icons, the navbar, forms, colours, titles, dead CSS and
inline styles are resolved; inline scripts were not, and section 5 covers them.

6.1 **English-only admin messages, and exception text on the round form.** These are
flashed or re-rendered as built sentences, not message keys:
- `RoundService.Resubmitted.message`;
- `RoundController.stillDistributing`;
- `JobController` "Can't retry: …";
- `ImageController` "Already running: …".

Also, `reRender(round, "round.creation.failed", detail(e))` and `"round.distribute.failed"`
show the exception's message on the page (`RoundController.scala:324`, `:360`). Sources:
d93adba review #7, error-handling plan. Fix: message keys with arguments in all four files
(`MessagesCompletenessSpec`). On the form, show the job's stored reason or a log reference,
the way `ErrorHandler` shows one.

6.2 **Dead links on the sign-up page.** "Sign up now", "Forgot your password" and the
MediaWiki provider are `<a href="">` (`signUp.scala.html:30-37`), so each one reloads the
page. Remove them. If `ukwikidev/oauth-login` brings the MediaWiki login, it adds its own
link.

6.3 **More dead code:**
- `SelectionJdbc.imageCountByRound` has no caller. It also counted soft-deleted rows (d93adba
  review #5).
- `SelectionJdbc.destroyAll` is called only from `Tools` (see 4.2).
- `regionNav.scala.html` has one caller, `monuments`, which always passes no regions (theme
  Phase 2), so it never renders.

6.4 **The unserved Tapir API.** Decided (2026-11-01): keep it, serve it, and secure it, for
the future SPA and for command-line tools. This is done on its own branch,
`ukwikidev/api-auth`, from `docs/plans/2026-11-01-api-auth.md` there: the session cookie for
the SPA, personal tokens (V75) for tools, root-only contest endpoints, Swagger UI at
`/api/docs`. It is not part of the batches below, except that 2.4 and section 5 must keep
`/api/docs` working under the stricter CSP.

6.5 **Vendor files served from `public/images`:** two GIMP sources
(`32px-Dislike.xcf`, `32px-Symbol_thumbs_up_green.xcf`) are served as assets, and so are the
slider and lightbox PNGs and GIFs that the tiles theme never shows. Delete the `.xcf` files.
Keep the vendor images unless unitegallery is trimmed.

6.6 **Alias accounts** (multi-contest design, "Recommended handling"): after V62, run
`scripts/report-alias-accounts.sql` on a production copy, triage the groups, and merge only
clear cases with the SQL in that design. This is an operational step for the user; nothing in
the repo changes.

6.7 **ChronicleMap jars** (performance-and-memory "Side issues"): the deployed tool bundled
`net.openhft.*` through scalawiki. Check whether the current release still bundles them:
`unzip -l` on the release ZIP, then grep for `openhft`. If it does, that is a scalawiki
upgrade (see the scalawiki memory: wlxjury builds with `origin/page-categories`).

6.8 **Job titles and statuses inside localized messages** (found in batch B1's review). Since
B1, "Already running: {0}." (`import.already.running`) and "Can't retry: the job is {0}, not
failed." (`job.retry.not.failed`) are message keys. Their arguments are still English: the
job's title from `JobHandler.title` (e.g. "Importing files from …") and the raw status
(`running`, `done`, …). The job status panel (`snippets/jobStatus`, `JobController.json`) shows
the same titles. Fix: give each handler a title key with arguments, and add one key per
`JobStatus`, in all four files. This is a follow-up, not part of B1.

## Order and checks

| Step | Items | Checks |
|---|---|---|
| A | 1.1, 1.7, 1.3, 1.4, 1.5/4.2 | `Test/compile`; `RoundEditPageSpec`, `RoundControllerSpec`, `MutationAuthorizationSpec`, the image cache specs, `GalleryController` export spec |
| B | 1.2 | `ConnectionPoolConfigSpec`; one fixture start that logs which pool is used |
| C | 1.6, 4.4 | Jest; `views.*`; `MessagesCompletenessSpec`; screenshots `--csp`, both modes, with a clean console |
| D | 2.1 (after the OAuth decision) | `LoginControllerSpec`, `UserControllerSpec`, `UserDbSpec`; new hash specs |
| E0 | 5.1, 5.2, 5.3 | as in section 5 |
| E | 2.2, 2.3, 2.4 (after E0) | `ContentSecurityPolicySpec`, `ProductionConfigSpec`, a host-header spec; screenshots `--csp` |
| A2 | 6.1, 6.2 | `MessagesCompletenessSpec`, `RoundEditPageSpec`, `RoundControllerSpec`, `JobControllerSpec`, `LoginControllerSpec` |
| F | 2.5, 2.6, 2.7, 4.1, 4.8, 6.3, 6.5 | `Test/compile` and affected specs for 4.1; no tests for docs |
| G | 4.5, then 4.6 and 4.7 when the user confirms | packaging test, `test_deployment.py`, `test_database_restore.py` |
| — | 3.x | manual or harness runs as listed; record the results here |

Full suite once after A–E. Steps D and E change security behaviour, so each gets one
independent review with its diff, as in the theme plan's cycle.

## Running the fixes with agents

### Setup

- **Branch:** `ukwikidev/followups`, from `ukwikidev/theme-modes` at `cb103b24`. Worktree
  `C:/scala/wlxjury-followups`, created once by the orchestrator. Its first commit is this
  plan. Batches run one after another on that branch. `ukwikidev/api-auth` (6.4) runs
  separately; it is merged into this branch before batch B5, the CSP batch, so that the CSP
  work sees `/api/docs`.
- **Migrations:** `ukwikidev/api-auth` takes V75. This branch starts at V76, if any batch
  needs a migration (2.1 might).
- **One test run at a time** on the machine: sbt, a Testcontainer, and Chrome for the
  screenshots together ran out of memory in theme Phase 3. A batch starts its tests only when
  no other agent's sbt or harness is running. The orchestrator starts the next implementer
  after the previous batch's checks, not during them. Writing code can overlap.
- **Commits:** agents commit each step with the attribution line, never push, never open a
  PR, never rewrite history.
- **Checks:** through `python scripts/agent-check.py -- …` with the direct sbt launcher from
  Git Bash, `Test/compile` first, and the batch's specs from the "Order and checks" table.
  Jest in `javascript-test/` after a local `npm ci` (never a linked `node_modules`).
  Screenshots with `scripts/ui-shots.py shoot --start-server --csp`, in both modes, plus
  `auto` where 3.1 asks for it.

### Roles

| Role | Agent | Model / effort | Gets |
|---|---|---|---|
| Implementer, one per batch | a fresh general-purpose agent in the worktree | Opus 5.5, medium. B4 and B5: high (passwords, CSP, host and cookie config) | this plan, the batch's items, CLAUDE.md, the previous batches' results |
| Reviewer, one per batch | a fresh agent, not a fork | Opus 5.5, medium. B4 and B5: high | `git diff <batch base>..HEAD`, the items' acceptance text, the invariants below, the implementer's check summary. It runs tests only for a specific doubt |
| Fix | the same implementer, continued with `SendMessage` | as above | the reviewer's confirmed findings only |
| Orchestrator (the main session) | — | — | checks each report against the branch and the logs, starts the next step, asks the user when a decision is open |

### The cycle for each batch

1. The implementer does the batch's items in small commits. It finishes with `Test/compile`,
   the batch's specs (and Jest and screenshots where listed), and reports commits, commands
   with exit codes, test totals, and open questions.
2. The orchestrator checks the report against the branch: the diff stat and the logs under
   `target/checks/`.
3. A fresh reviewer reviews the batch's diff. Each finding names a file and line and a
   concrete failure, ranked by severity. A finding is a defect or a broken invariant, not a
   style preference.
4. The implementer fixes the confirmed findings and reruns the affected checks.
5. A second review only if the fixes were material (behaviour, security, or markup and JS
   that several pages share).
6. Anything still open after two reviews, and any choice this plan doesn't settle, goes to
   the user. Agents don't decide it.
7. The implementer adds "Batch N results" to this plan (checks, findings and how they were
   resolved, what remains) and commits it.

### Batches

| Batch | Items | Effort | Notes |
|---|---|---|---|
| B1 | 1.1, 1.3, 1.4, 1.5 + 4.2, 1.7, 6.1, 6.2, 6.3 | medium | Bugs and server messages. 1.4: the export's host is a choice; default to absolute app URLs (`wlxjury.host`), and report it |
| B2 | 1.2 | medium | Production config change; the release notes say so |
| B3 | section 5 (5.1–5.3), 1.6, 4.4, 6.5; harness parts of 3.1 (auto shots) and 3.3 (vote then Back, by Playwright) | medium | Large and frontend-heavy: Jest, `LargeViewFixturesSpec` regenerated, screenshots in light, dark and auto |
| B4 | 2.1 | high | Password sign-in stays (decision 1): bcrypt, cost 12 (decision 4); the legacy SHA-1 verified only to rehash on a successful sign-in |
| B5 | 2.2, 2.3, 2.4 | high | After B3 and after merging `ukwikidev/api-auth`. The CSP without `'unsafe-inline'`; `/api/docs` must still work |
| B6 | 2.5, 2.6, 2.7, 2.8, 4.1, 4.8 | medium (docs low) | 2.6 after B3, which removes most of the `@Html` in JS strings. 2.8: the account id only (decision 3), CLAUDE.md updated. 4.1: delete the merge code (decision 2) |
| B7 | 4.5 | medium | The repo half only. The `wlxjury-infra` half and the playbook run are the user's |
| B8 | 3.5 | medium | A paired timing against the pre-branch base, after B1–B7, with no other DB work running. Report and stop |

The full suite runs once after B5 (shared layout, filters and config), and again at the end
only if B6–B7 change shared code.

**Not for agents** (the user, or production access):
- 3.2 against live Commons, and 3.1 and 3.3 in Firefox, by hand;
- 3.4, the production data check;
- 3.6, only before the next capacity measurement;
- 4.6, the cutover code, after the user confirms;
- 4.7, the benchmark data, with the user's agreement;
- 6.6, the alias accounts;
- 6.7, a one-line `unzip -l` check, which the orchestrator does.

### Decisions (the user's, 2026-11-01)

1. Password sign-in stays for now, so 2.1 is done in full: a slow KDF, with rehashing on
   sign-in.
2. Round merging isn't wanted: delete `RoundController.mergeRounds`, `RoundService.mergeRounds`,
   `SelectionJdbc.mergeRounds`, `Round.delete` if it stays unused, their tests, and the
   `RoundImageCounts` / deleted-files hooks that only these call (4.1).
3. The `security.authz` denial line logs the account id only, not the email (2.8). Support
   looks the account up by id.
4. Password hash: bcrypt (`$2b$`, cost 12) from a maintained library (e.g.
   `at.favre.lib:bcrypt`, or a current, explicit `bcprov-jdk18on`'s `OpenBSDBCrypt`; not the
   transitive `bcprov-jdk15on` 1.64). It fits `users.password varchar(255)`, so no migration.
   A legacy hash (40 hex characters) is checked once and replaced by a bcrypt hash on a
   successful sign-in, and every new, reset or changed password is stored as bcrypt. A small
   limit on concurrent hash checks (e.g. 4) keeps a flood of sign-ins from using up the CPU.
   Passwords over 72 bytes: refuse them where they are set, or pre-hash them consistently;
   the implementer picks one and tests it. The cost is read from the stored hash, so it can
   be raised later by rehashing on sign-in.

### Invariants that every reviewer checks

- Votes, sign-out, contest switching, the theme switch and token management are POSTs with
  the CSRF token. Keyboard voting uses paths, never `http://` URLs (`LargeViewControllerSpec`).
- Authorization isn't weakened: negative cases (another contest's member, a juror, an
  inactive membership) stay denied; denials are logged with a reference.
- Errors use `errorPage` or JSON with message keys, never request or exception text.
- No page loads anything from another host; the CSP never gets wider.
- Messages exist in all four files with the same placeholders (`MessagesCompletenessSpec`).
- Tests assert on ids, data attributes and text, not on styling classes.

## Checked and already resolved (not part of this plan)

Theme follow-ups 1–11 and the review fixes; Phase 2's ru keys; JS texts in `mainAdmin` (now
`views.JsTexts`); the `/admin/rounds` contest fallback (c2d220d7); the scheduler heartbeat's
readiness guard (`JobScheduler.tableReady`); votes on stopped rounds (409, `openToJurors`);
criteria rates written in the distribution's chunk transaction (`RoundDistributionJobSpec`);
long round creations (background jobs); GET votes and `nocsrf` routes (none left); the
committed Play secret (`production.conf` requires `PLAY_HTTP_SECRET_KEY`); the signup session
bug; `preloadImages` (template gone); the by-rate galleries' dead `rates.id == 2` branch;
the theme plan's inconsistencies 1–7 and 9–13 (old icons, navbar markup, play-bootstrap, inline
colours, dead CSS; 6 inline styles left on purpose); the COI plan including step 5 and the
`coi` logger; Task 12's submission token and retry fixes; the 2026-03 DB analysis's ranking
subquery and schema items (window functions, V46); the 2026-03 image-cache plans and the
superpowers plans (shipped, only their task boxes are unchecked); the binary-aggregate review items, and decision 4 with
V73; the round redistribution filters and category operators, including the production
first-access fix.

## Left out as new features

The Commons `refresh` mode for deleted and renamed files, with its button and periodic ticker
(deleted-renamed-files §2 and §4; only the thumbnail-triggered check exists). Importing the
top N of another contest's round. More ranking methods (STV, normalised ratings). The Tabler
trial and Codex colours (theme decision 2). Keyset paging of galleries. A design for faster
organisers' all-jurors views. "Count new files" as a job. Moving local image downloads to the
job table. Prefetching import pages. The import's open questions (unifying the monument
template, WLE nominations). The Debian package and Ansible deployment options.

## Batch B1 results

Branch `ukwikidev/followups`, from `fde91fb4`. Commits `20f3ebb3`..`7cd965bc`.

**1.1** A failing spec came first (`RoundEditPageSpec` "keep a stopped round stopped…": the stopped
round came back `active = true`, exit 1, `target/checks/20261101-221305-18664.log`). Now
`Round.updateRound` saves only the name, and only a new round is submitted with `active = true`.
Starting and stopping go through `setRound` alone. Because of that, the edit form no longer
flashes "still distributing".

**1.3** `startDownload` runs the contest's image query (`contestImages`) inside a `Try`, as the
round variant does. `runDownloadImpl` recovers from a failure of the registry, the scan or the
stream. On any failure the error is logged and the progress is set to `running = false` with a
new flag, `failed = true` (no exception text). `cache-status.js` then shows "Download failed" or
"… – failed" and stops polling. Specs: the image query throws, and the download itself fails.
Jest covers the new texts.

**1.4** Nothing in the code or the docs says the export is for upload.wikimedia.org: its only
documented use is wget or a script that pre-warms or fetches the thumbnails. So the thumbnails
are absolute URLs on the app's own proxy, with `wlxjury.host` as the host (falling back to the
request's host). Lines that are already absolute (the file itself on Wikimedia, when it is no
wider) stay as they are. The proxy needs a signed-in session, so wget must send the session
cookie. New spec: `ThumbnailUrlsExportSpec`.

**1.5 + 4.2** `Tools`, `GlobalRefactor`, `cmd/AddUsers` and `cmd/ConnectDb` are unreachable:
no route, no `mainClass` in `build.sbt`, nothing under `scripts/`, and no mention in the docs
except the reviews. Their only user is `scripts/GreeceESPC` (a `main` of its own, contest 78,
also unused). All five are deleted, together with `Selection.apply(Page, …)` and
`SelectionJdbc.destroyAll`. So `ConnectDb` isn't fixed: it's gone.

**1.7** `saveRound`, `setRound` and `setRoundUser` answer a missing round with `errorPage` and
`error.round.not.found`. Spec: all three give 404 with the message, and the id doesn't appear.

**6.1** These are now message keys with arguments in all four files: resubmitted round (3
variants), still distributing, "Distribute new files" already running or resumed (3),
`JobScheduler.retry`'s refusals (now `RetryRefused(key, args)`, 4 keys), and import already
running. Failures on the round form: an `IllegalArgumentException` from our own `require`
checks is shown without its "requirement failed: " prefix. Any other failure shows only a
reference (`Secured.newReference`), logged with its stack trace at ERROR, the way
`ErrorHandler` does (`round.creation.failed.reference`, `round.distribute.failed.reference`).
This path never has a job's stored reason: it fails before a job runs. The keys looked up from
values are listed in `MessagesCompletenessSpec.dynamicKeys`. Specs: a refusal is shown, a
mocked DB-style failure shows a reference without its text, and a job retry is refused with the
message.

**6.2** The `@if(false)` block with the three `href=""` links is removed from `signUp`. The keys
stay: `login` uses them. Spec: the sign-up page has no `href=""`.

**6.3** `SelectionJdbc.imageCountByRound` is deleted. The specs that used it count with
`ImageJdbc.imageCountByRounds`. `destroyAll` went with 4.2. `regionNav.scala.html`, its dead
call in `monuments` and its `#regionnav` CSS are deleted.

**Checks** (all through `scripts/agent-check.py`):
- The 1.1 spec before the fix: exit 1 (`20261101-221305-18664.log`), as intended.
- `Test/compile` + 35 specs (the A and A2 rows, the image cache specs, the specs of the
  changed files, `views.*`): 399 total, 394 passed, 4 skipped, 1 pending, 1 failed. The failure
  was in my new refusal test (duplicate form field), fixed in the helper
  (`20261101-222839-28188.log`).
- `RoundEditPageSpec` again: exit 0, 18/18 (`20261101-223414-14016.log`).
- Jest after a local `npm ci`: exit 0, 12 suites, 206 tests.
- The full suite was not run (it comes after B5).

**Slip:** commit `56635f0a` (the export) also took the deletions already staged with `git rm`
(the four tools, `GreeceESPC`, `regionNav.scala.html`), so that commit alone doesn't compile
(`monuments` still called `regionNav` until `931d966c`). History wasn't rewritten. The commits
after it say so.

**Open questions**
- 1.4: if the export is meant for upload.wikimedia.org (no session needed), the host is a
  one-line change.
- After `GreeceESPC`'s removal, `cmd/AddRound` and `cmd/AddNextRound` have no callers. They were
  left in place, as they aren't in the plan.
- `test/gatling/setup/RegionCountPairedBenchmark` and `GalleryLatencyBenchmark` still look for
  `id="regionnav"`, which the galleries haven't rendered since the region menu (before this
  batch). They were left as they are.
- Job titles (`JobHandler.title`) and the job status in "the job is {0}" are still English
  values inside the localized messages.

**What remains:** the B1 review, then B2.

### B1 review fixes

The review found nothing material. These were fixed:

- Low, 1.3 by another path: a download still in flight when the stream failed finished after
  the `recover` and wrote `running = true` again, and a late write could overwrite a newer
  run's progress. Each run now writes through `tracker.compute`, and only while it hasn't ended
  and nothing else has written the progress since its last write. Two specs: an element that
  finishes after the failure leaves `running = false, failed = true`, and a late update leaves
  a newer run's progress alone. Commit `2e6bc247`.
- Low: the `LoginControllerSpec` sign-up links example couldn't fail (the block was inside
  `@if(false)`). Dropped in `4247b4d2`.
- Decided open question: `cmd/AddRound` and `cmd/AddNextRound` are deleted. Nothing else used
  only them. The deletion went into `2e6bc247` by mistake (a staged `git rm`), not into a
  commit of its own.
- Decided open question: `RegionCountPairedBenchmark` now requires `id="regionmenu"`
  (`44406d50`). Its next check, `Volyn O. (` in the HTML, likely fails too: the region menu
  loads its counts from `data-regions-url`. That benchmark needs rework before its next run.
- Decided open question: the export stays on the app's proxy. Both links (`contest_images`
  and `roundStatTable`) now have a title, `thumbnail.urls.help` in all four files, saying that
  wget or a script must send the session cookie (`217b2291`, asserted in `RoundEditPageSpec`).
- 6.8 was added to this plan: localize job titles and statuses (`40711640`).

Checks: `Test/compile`, `Gatling/compile`, then `services.LocalImageCache*`,
`LoginControllerSpec`, `ThumbnailUrlsExportSpec`, `RoundEditPageSpec` and
`MessagesCompletenessSpec`. Exit 0: 121 total, 117 passed, 4 skipped
(`target/checks/20261101-230714-20536.log`).

## Batch B2 results

Branch `ukwikidev/followups`, from `79512eb5`. Commit `e1713e84`.

**1.2** The library sources (`scalikejdbc-play-*` 3.0.1-scalikejdbc-4.3) show where the two
pools come from. `PlayFixtureModule` binds `PlayInitializer` and `PlayFixture` eagerly.
`PlayInitializer.onStart` runs `DBs.setupAll()`, which builds a commons-dbcp2 pool from each
`db.*` block (ScalikeJDBC's defaults, 8 connections). `PlayDBApiAdapter`, bound eagerly by
`PlayDBApiAdapterModule`, wraps each of Play's `DBApi` data sources (HikariCP) and calls
`ConnectionPool.add`. Both register "default", and `add` replaces the existing pool, so the
module whose eager singleton Guice built last won. `PlayFixture` loads `db.<name>.fixtures.dev`
or `.test` scripts from `db/fixtures` in Dev and Test only. There are none (no such config and
no `conf/db/fixtures`), and nothing imports `scalikejdbc.play.*`. `PlayDBApiAdapter` also does
what `PlayInitializer` did besides the pool: it loads the global settings (SQL logging) and
`loggingSQLErrors`.

The fix drops `PlayFixtureModule` from `play.modules.enabled` in `conf/application.conf` and
in `test/resources/application.conf`. It also removes the now unused
`scalikejdbc-play-initializer` and `scalikejdbc-play-fixture` libraries, and the
`scalikejdbc.play.closeAllOnStop.enabled` key. Only `PlayInitializer` read that key, and
`PlayDBApiAdapter` only logged a warning that it ignores it. `db.default.poolMaxSize` was the
wrong fix: it would only have resized the pool that shouldn't exist. The comments in
`PlayTestDb`, `SharedTestDb` and CLAUDE.md ("Pool lifecycle") now say what closes the pool:
stopping the Play app closes its HikariCP pool. Nothing calls `ConnectionPool.closeAll()`.

`ConnectionPoolConfigSpec` (now `sequential`) checks that neither `PlayModule` nor
`PlayFixtureModule` is enabled. Then, in an app started with the production module list and
pool keys, it checks that `ConnectionPool.get().dataSource` is the same object as Play's
`DBApi` data source: a `HikariDataSource` with maximum 30, minimum idle 10 and a 30 s timeout.
The Gatling fixture's `-Dgatling.pool` no longer re-registers Play's pool: the pool at start is
always HikariCP. `dbcp` stays, to compare with runs made before this change. `as-started` is
gone (it now means the same as `hikari`).

**Deployment:** on the next release, ScalikeJDBC always uses Play's HikariCP pool: 30
connections at most, 10 idle, a 30 s connection timeout. Until now, depending on the start,
it was HikariCP (30) or a dbcp2 pool (8). The release ships two fewer jars, and the
`closeAllOnStop is ignored` warning goes away at start. `conf/wlxjury/production.conf`
needs no change: it includes `application.conf` and sets only the URL, credentials and
migration keys. MariaDB's `max_connections` must allow 30 from the app; HikariCP already
had that maximum whenever it won the race.

**Checks** (all through `scripts/agent-check.py`):
- `Test/compile` + `ConnectionPoolConfigSpec`, `ReadinessControllerSpec`,
  `ReadinessDatabaseSpec`, `SecuredSpec`, `LoginControllerSpec`, `ContestFilesPageSpec`:
  exit 0, 73/73 (`20261101-224235-5836.log`).
- One fixture start, `gatling.GatlingSmokeSpec`: exit 0, 7/7. It logged `[pool] at start:
  com.zaxxer.hikari.HikariDataSource` (`20261101-224524-27148.log`). The specs that mention
  "fixture" use test data, not `PlayFixture`.

**Open questions:** none. Only one start was observed. The race can't come back without one of
the two modules, and the spec's module check fails if one is enabled again.

**What remains:** the B2 review, then B3.
