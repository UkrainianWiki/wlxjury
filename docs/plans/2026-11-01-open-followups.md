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

Audit (batch B6, after B3; `rg -n 'Html\(|HtmlFormat\.raw' app`, with `Accepts.Html()` left
out: it is content negotiation, not markup). There is no `HtmlFormat.raw` in `app/`.

| File:line | Source of the markup | Verdict |
|---|---|---|
| `monuments.scala.html:52,54,55` (was `@Html(Monument.wikiLinkToUrl(…))`) | the monument's name, place and city from the DB, imported from the wiki lists that anyone can edit; scalawiki's `wikiLinkToUrl` turns `[[…]]` into links but leaves the text around them and the labels unescaped | **Changed:** `@views.WikiLinks(…)`, which escapes everything but the links it builds (`WikiLinksSpec`) |
| `large/monumentInfo.scala.html:8,9,10` (the same: city, name, place) | as above, in both large views | **Changed:** `@views.WikiLinks(…)` |
| `large/monumentInfo.scala.html` (was line 10, `<!-- @Html(m.typ … m.subType) -->`) | the monument's type and subtype from the wiki lists; Twirl renders `@` inside an HTML comment, so a `-->` in them ended the comment | **Removed** (a dead line) |
| `views/WikiLinks.scala:32` | its own output: escaped text, escaped labels, escaped URL-encoded hrefs | trusted |
| `contestFiles.scala.html:13` | two constant attribute strings | trusted |
| `bs/FieldView.scala:86` | `aria-describedby` ids escaped with `HtmlFormat.escape`, a constant `aria-invalid` | trusted |
| `bs/FieldView.scala:193` | attribute names checked against `[a-zA-Z][a-zA-Z0-9_:.-]*`, values escaped | trusted |
| `views/GalleryTiles.scala:19` | booleans and numbers; the stars JSON escaped | trusted |
| `views/JsTexts.scala:18` | message texts as JSON, `<` written as `\u003c`, in a `type="application/json"` block; in the monuments' attribute as a String (`.body`), which Twirl escapes | trusted |
| `large/contestFile.scala.html:69`, `large/fullSizeUrl.scala.html:6` | `full.resolution`'s message text (with `<nobr>`) and two numbers (`width x height`, megapixels) | trusted (message files and integers) |
| `large/navigationButtons.scala.html:16` (`title="@Html(title)"`) | the `previous.image`, `next.image`, `up.key`, `down.key` messages and a constant `&lt;…&gt;` | trusted (message files only) |
| `pageLinks.scala.html:13,14,29,30` | constant entities (`&laquo;` …) | trusted |
| `contestFiles.scala.html:64`, `gallery.scala.html:41` (`data-description="<b>@title</b>…"`, a DOM sink, not `Html(...)`) | the file's Commons title, escaped once by Twirl; unitegallery shows the attribute with jQuery's `.html()`, so the title was parsed as HTML. Not exploitable with Commons titles (MediaWiki forbids `<` and `>` in titles), but a character reference in one would be decoded | **Changed** (review fixes): the title is escaped twice (`HtmlFormat.escape(…).body`), so `.html()` shows it as text; the COI lines were already escaped twice (`GalleryDescriptionViewSpec`) |
| `main`, `mainAdmin`, `navbar` (`pager`, `filters`, `head` defaults), `large/fileKeyboardNav.scala.html:1` (`votes`) | empty `Html("")` defaults; callers pass rendered templates, which escape | trusted |

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
| B8 | 3.5 | medium | A paired timing against the pre-branch base, after B1–B7, with no other DB work running. Report and stop. Also: `RegionCountPairedBenchmark` still checks for region counts in the gallery HTML (`Volyn O. (`), which the region menu now loads from `data-regions-url` (the B1 second review): delete it or rework it to read that URL |

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

### Merged ukwikidev/api-auth

2026-11-02, before B5: `git merge --no-ff ukwikidev/api-auth` (at `dd7c5d31`) into this
branch, merge commit `992d8eb2`.

- **Conflicts:** none textual; git auto-merged every file both sides changed. Checked by
  hand: both `application.conf` files keep the `CsrfModule` swap with `PlayFixtureModule`
  still removed; `build.sbt` has tapir-play-server (pekko-http testkit/play-json gone) and no
  scalikejdbc play initializer/fixture; the four messages files hold the same 383 keys (the
  API's keys added, B1–B3's deleted keys not back); `editUser` has the token card and the
  reset `<button form=…>`; CLAUDE.md has both sides' sections; `V75__Api_tokens` is the only
  V75.
- **Post-merge fix** `6ee47529`: the token card's `copy-field.js` moves from a script tag
  in `apiTokens.scala.html` to `editUser`'s `head`, loaded only with the card (the own
  profile), as B3's pages do. The card has no inline code or `on…` handlers. Swagger
  (`/api/docs`) is Tapir's page, not a template; left as is.
- **Checks:** `Test/compile`, `Gatling/compile` and `ApiSpec`, `ApiTokenPageSpec`,
  `ApiTokenJdbcSpec`, `ErrorHandlerSpec`, `UserControllerSpec`, `SecuredSpec`,
  `LoginControllerSpec`, `MutationAuthorizationSpec`, `ConnectionPoolConfigSpec`,
  `views.*`, `MigrationNamingSpec`: exit 0, 272 passed, 0 failed
  (`target/checks/20261102-005007-23776.log`). Jest: exit 0, 18 suites, 240 tests
  (`javascript-test/target/checks/20261102-004818-24892.log`). No full suite (after B5).

### Merged ukwikidev/followups-b4

2026-11-02, after B5: `git merge --no-ff ukwikidev/followups-b4` (at `4f263e0f`) into this
branch, merge commit `d9dbe0ed`.

- **Conflicts:** `UserController`'s imports (kept api-auth's `ApiTokenService` and B4's
  `BlockingExecutionContext`, `PasswordHasher`); this plan (the B3 review fixes and B5
  results, then the B4 results without B4's stale "What remains" line). Auto-merged and
  checked by hand: both `application.conf` files have `sign-in-dispatcher` and `bcrypt.*`
  beside B5's and api-auth's changes; the four messages files hold the same 388 keys;
  `build.sbt` has bcrypt 0.10.2; CLAUDE.md has the Passwords line; `UserController` keeps the
  token endpoints, `ownProfile` and the nonce with B4's `withAuthOn(blocking)` password paths.
- **Post-merge fix** `1381c88d`: `ApiSpec`, `ApiTokenPageSpec`, `ApiTokenJdbcSpec` and
  `UiShotsServer` used the removed `User.sha1`; they store `PasswordHasher.default.hash(...)`
  (`UiShotsServer` with the renamed `passwordHash` parameter).
- **Checks:** `Test/compile`, `Gatling/compile` and `PasswordStorageSpec`,
  `PasswordHasherSpec`, `UserPasswordSpec`, `LoginControllerSpec`, `UserControllerSpec`,
  `UserDbSpec`, `MultiContestUserSpec`, `SecuredSpec`, `ApiSpec`, `ApiTokenPageSpec`,
  `ApiTokenJdbcSpec`, `ProductionConfigSpec`, `AllowedHostsSpec`, `views.*`
  (`MessagesCompletenessSpec` included): exit 0, 327 passed, 0 failed
  (`target/checks/20261102-021714-452.log`). The full suite (`20261102-022113-17192.log`) was
  stopped by the host for low system memory before it finished, with no failure until then.
- **Full suite** (the release check for B1–B5, api-auth and B4), rerun by the orchestrator at
  `70d48397` with nothing else running: exit 0 in 14 min, Scala **1404 total, 0 failed,
  0 errors** (1398 passed, 6 skipped, 1 pending); Jest 18 suites, 240 passed
  (`target/checks/20261102-023459-13464.log`).
- **Final full suite** (the release check after B6 and B8) at `3f98b77a`: exit 0 in 15 min,
  Scala **1412 total, 0 failed, 0 errors** (1406 passed, 6 skipped, 1 pending); Jest 18 suites,
  240 passed (`target/checks/20261102-083340-18164.log`).

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

## Batch B3 results

Branch `ukwikidev/followups`, from `faa5fb92`. Commits `b04a9670`..`ae248a6c`, then this one.

**5.1** `main` and `mainAdmin` take `head: Html = Html("")`. Pages add what they use from three
snippets: `snippets/galleryScripts` (unitegallery, its tiles theme and CSS, `gallery-init.js`)
for `gallery` and `contestFiles`; `snippets/tileVoteScripts(stars)` (`vote-errors.js`,
`tile-votes.js`, and raty with `large-rate.js` when there are stars) for `gallery` and
`galleryByRate`; `snippets/multiSelectScripts` (Tom Select, `multi-select.js`) for `editRound`
and `monuments`. `region-menu.js` and `pager-jump.js` stay in `main`. The global `contestId` is
gone. `ug-theme-tiles.js` calls `wlxTileVotes.addTally` only when `tile-votes.js` is loaded:
contestFiles has no votes and doesn't load it.

**5.2** Every block in the table moved to a file that reads data attributes:
- `gallery-init.js`: `#gallery`'s `data-jury`, `data-binary` and `data-stars` (JSON), rendered
  by `views.GalleryTiles`.
- `round-form.js` (editRound only): the multi-selects, the previous rounds' filters, the
  distribution choices, the COI confirmation, the lazy jurors' table and Count new files. Its
  texts are one `<script type="application/json" id="round-form-texts">` (`JsTexts`, `<`
  escaped).
- `large-view.js` (votes by the vote form, `[data-vote-url]` clicks, image fallbacks),
  `large-keys.js` (`#large-keys`' `data-key-*` and `data-vote-key-*`, all paths) and
  `large-stars.js` (each `.large-stars`' `data-number`, `-half`, `-score`, `-rate-number`,
  `-readonly`, `-vote-prefix`, `-vote-query`). `main_large` lost its `score` and `byCriteria`
  parameters; `navigationButtons` takes `readOnly`. A criteria round no longer sets up stars
  on a `#rate-image` it doesn't render.
- `cache-status.js` starts itself on `[data-cache-status-url]`; `multi-select.js` on
  `select[data-multi-select]` (monuments; cleared when its form is reset).
- `wlxLargeSections.restore` **stays inline** (large, contestFile), the one exception besides
  the theme script. The new first-frame probe (`scripts/ui_checks.py`) saw Chrome paint frames
  with the side column while `document.readyState` was still `loading` (baseline: 3 of 5 loads
  of the large view; after B3, 5 of 5 of both views), so a restore at DOMContentLoaded would
  show the default states first. With the inline restore every first frame had the stored
  state. B5 can allow it by its hash.

**5.3** The add-contest and add-user buttons are `<button type="submit" form=…>`; editUser's
reset is `<button type="submit" form="reset-password">`. The new view spec also found the
comment form's `onClick` (`commentsView`): it is a plain submit input now. The images'
`onerror` handlers are `data-failed-mark="thumb|large"`, handled by one capture-phase `error`
listener that `large-view.js` registers in `<head>`, with the same `data-fallback` logic.

**1.6** `ismouseover`'s `.load(e)` is `.on("load", e)`. That error was an uncaught exception,
not a console message, so the harness never showed it; it now reports page errors, and there
are none on any page after B3.

**4.4** 57 keys deleted from all four files (the sign-up and mail flow, `wiki.loves.*` and
others nothing reads). Kept: Play's own `format.*`, `error.*` and `constraint.*` keys, the
region names (`RegionScheme`), and the keys built at run time (`role.*`, `theme.*`,
`binary.order.*`, `round.start`, …).

**6.5** The two `.xcf` files are deleted.

**3.1 / 3.3, the harness.** `ui-shots.py` saves auto shots in `auto-<scheme>/`, writes
`requests.json` (each page's scripts and stylesheets), logs page errors, and with `--checks`
runs `scripts/ui_checks.py` in Chrome: a key vote in the large view of the binary and of the
rated round, then Back, must show what a fresh load shows; and the side sections' first frame.
`--csp-strict` serves the production policy plus `script-src 'self' 'unsafe-eval'`
(`GatlingTestFixture.cspConfig`), so each inline script left is reported.

**Also:** `SchemaBaselineSpec` re-registers the shared pool in a `finally` after its own app,
only if the shared container was started (`SharedTestDb.isStarted`, which doesn't start it).
New `views.NoInlineScriptsSpec`: no template has a `<script>` without `src` other than the theme
script, the two restores and JSON blocks, and no `on…=` attribute.

**Checks** (through `scripts/agent-check.py`):
- `Test/compile`: exit 0 (`20261101-232857-19092.log`).
- `LargeViewFixturesSpec` with `FIXTURES_UPDATE=true`, twice (the second after the comment
  button): exit 0, 6/6 (`20261101-234125-4092.log`).
- `views.*` + `RoundEditPageSpec`, `ContestFilesPageSpec`, `GalleryOrderSpec`, `RegionMenuSpec`,
  `ImportFilesPageSpec`, `UserControllerSpec`, `OwnVotesSpec`, `CoiAuthorizationSpec`,
  `PrevRoundHiddenInputSpec`, `MonumentControllerSpec`, `AssetDigestSpec`, `ThemeSwitchSpec`,
  `WebJarsSpec`, `LargeImageSpec`, `GalleryRegionsSpec`, `AllJurorsListSpec`,
  `MultiContestUserSpec`, `ContestTemplateSpec`, `LoginControllerSpec`,
  `CommentAuthorizationSpec`, `SchemaBaselineSpec` (`views.*` includes
  `MessagesCompletenessSpec`, `LargeViewControllerSpec`, `LayoutSpec` and `LargeViewFixturesSpec`
  without the update). First run: exit 1, 370 total, 5 failed (the comment's `onClick`; a
  criteria-round count that included the dead `#rate-image` setup; `OwnVotesSpec` reading the
  by-rate list's stars from main's old script; `20261101-233547-2500.log`). After the fixes:
  exit 0, 375 total, 374 passed, 1 skipped (`20261101-234245-4000.log`).
- Jest (local `npm ci` tree): exit 0, 17 suites, 237 tests
  (`javascript-test/target/checks/20261101-234227-27020.log`). New: `gallery-init`,
  `round-form`, `cache-status-init`, `large-view`, `large-keys-stars`, and `multi-select`'s
  `initAll`. `large-view-keys` runs the files on the regenerated fixtures and asserts that the
  only inline scripts are the theme script and the restore.
- The full suite was not run (it comes after B5).

**Screenshots** (Chrome, `--start-server --csp`; labels `b3-base` at `faa5fb92` and `b3`):
- light, dark, auto (scheme dark): 0 of 28 shots differ from the baseline in each mode. The
  only console errors, before and after: the 404 pages' own 404.
- auto (scheme light): rendered light (the harness checks `data-bs-theme`). Against `b3` light,
  21 shots differ only in the theme control (the navbar's icon, the login switch and the theme
  menu show "System"); the other 7 are identical. The baseline has no auto shots with a light
  scheme: by then the worktree held B3 code.
- `--csp-strict`, light and dark: 0 of 28 differ from `b3`. The only violations are the theme
  script (every page, and the sign-in page) and the restore (the four large-view shots): the
  two exceptions above.
- `requests.json`: `login` and `roundstat` load no unitegallery, Tom Select or tile votes.
  Before, login loaded Tom Select and `multi-select.js`, and roundstat unitegallery and
  `tile-votes.js`.
- `--checks`: vote then Back passed in both rounds (binary: Del, and Back shows the select
  button, as a fresh load does; rated: digit 3, and Back shows 3 instead of 7), each vote one
  POST to a path on the app's host. The side sections' first frame passed on both large views
  (5 loads each).

**Open questions**
- The restore stays inline, as measured and as the plan allows. B5 allows it and the theme
  script by their hashes. A hash covers the exact text, line ends included (Twirl keeps CRLF
  from a Windows checkout), so B5 should take it from a rendered page in the build, or use a
  nonce.
- 3.1 and 3.3 in Firefox remain the user's, by hand.

**Intended behaviour change:** `<button form=…>` and the comment's submit input fire the submit
event and the browser's form validation, which `form.submit()` skipped. `bs.FieldView` marks
`nonEmptyText` fields `required` (a contest's name and country, a user's full name), so the
browser now stops an empty add-contest or add-user row that the server would refuse anyway,
and Enter in such a row submits it.

### B3 review fixes

- Medium-high, a regression: the category suggestions were gone on `/contests` and
  `/contest/:id/images`, since `mainAdmin` stopped loading Tom Select and `category-suggest.js`
  does nothing without it. `snippets.tomSelectScripts` (Tom Select's CSS and script, also
  included by `multiSelectScripts`) is in both pages' `head`. The pages with a
  `data-category-suggest` field are these two and editRound (its filters), which loads it with
  `multiSelectScripts`. `CategorySuggestViewSpec` now asserts that both pages load
  `tom-select.complete.min.js`, and that every template with such a field (or the page that
  includes it) loads Tom Select. Commit `5219789c`.
- Medium: `NoInlineScriptsSpec`'s handler pattern stopped at the first `>` in a tag, Twirl's
  `=>` included, so it would have missed the old large image's `onerror`. It now strips the
  scripts' bodies and searches the whole template for `(?<![\w-])on[a-z]+\s*=\s*["'@]`, also
  for `javascript:`, and takes a script as having a src only from a `src=` attribute, not
  `data-src=`. New examples run the checks on sample texts: the old large-image tag, an
  `onClick`, a `javascript:` href, data attributes and script bodies that must not count, and
  a `data-src` script. Commit `327c1a91`.
- Low, wording: the submit buttons' validation is an intended behaviour change (see above),
  not an open question.
- Nit: `ui_checks.py` refuses requests to other hosts, as `ui-shots.py` does. The harness also
  shoots `contests` (as a root account that `UiShotsServer` creates in its own container) and
  `contest-images`, and logs how many category fields became Tom Select controls. Commit
  `b4dba585`, plus this commit's change to count only Tom Select's wrappers.

Checks:
- `Test/compile`, `Gatling/compile`, `views.*` + `ContestFilesPageSpec`,
  `ImportFilesPageSpec`, `ContestTemplateSpec`: exit 1, 191 total, 1 failed. The new
  CategorySuggest example counted the snippet's own Twirl comment
  (`20261102-002644-29220.log`). After stripping comments, `CategorySuggestViewSpec` +
  `NoInlineScriptsSpec`: exit 0, 16/16 (`20261102-002854-27876.log`).
- Harness, light, `--csp`, `--only contests,contest-images,login,editround`: exit 0, no
  failures. Both new pages load `tom-select.complete.min.js` and `category-suggest.js`.
  `contests` has 2 of 2 category fields as Tom Select controls and `contest-images` 1 of 1
  (second run, after counting only the wrappers). `login` and `editround` are pixel-identical
  to `b3`. No JS changed, so Jest wasn't rerun.

**What remains:** a second review of the fixes if wanted, then B4.

## Batch B5 results

Branch `ukwikidev/followups`, from `6c745bf5`. Commits `8fb9bbe2`, `ba4623fc`, `986cdf97`, then
this one.

**2.2, the allowed hosts.** `AllowedHostsFilter` is on (`application.conf` no longer disables
it). Play's `AllowedHostsModule` is replaced by `modules.AllowedHostsModule` (as `CsrfModule`
replaces the CSRF module). It adds the names in `wlxjury.allowedHosts`, one string split on
commas and whitespace, because HOCON can't split an environment variable into a list. Each name
must be a host name or a `.suffix`, with an optional port. `.`, `*`, URLs and the like stop the
start. `production.conf`:

```
wlxjury.allowedHosts = ${WLXJURY_ALLOWED_HOSTS}
play.filters.hosts.allowed = []
```

So production allows only the deployed names: no loopback names (they were allowed at first;
see the review fixes) and not Play's `*.local`. The variable is
required (an unresolved substitution). In Mode.Prod the module also refuses to start without a
name. A runtime directory with an old `production.conf` therefore fails at the readiness check,
with maintenance still on, instead of answering 400 to every public URL after `reopen`.
- The deploy tools ask `http://127.0.0.1:$PORT/health/ready`, so curl and urllib send
  `Host: 127.0.0.1:9000`. That route is tagged `+ anyhost` (review fixes), so it answers any
  Host, and still only loopback clients.
- Apache's vhosts keep the client's Host (`ProxyPreserveHost On` in
  `conf/apache/0002-wlxjury.conf`), so the check sees the real names.
- A refused request gets `ErrorHandler`'s 400 page (`error.bad.request`, without echoing the
  host). Under `/api/` it gets `{"error": "bad_request"}`.
- `sbt run` and the tests keep Play's default list (`localhost`, `127.0.0.1`, `*.local`).
  FakeRequest, Gatling and the harness all use one of these. `CsrfTestApp` still disables the
  filter.
- `docker-compose.yml` runs in Mode.Prod without `production.conf`, so it passes
  `-Dwlxjury.allowedHosts=localhost`.
- Documented in `runtime.env.example`, `docs/deployment.md` ("Host names and secure cookies")
  and the 400 row of `docs/api.md`.
- The packaged-release smoke test (`scripts/deployment/test_database_restore.py`) sets the
  variable, and checks that a foreign Host gets 400 and a listed one 200. It runs only in the
  Linux deployment container and was not run here.

**2.3, Secure cookies.** `production.conf` sets `play.http.session.secure`,
`play.http.flash.secure` and `play.i18n.langCookieSecure` (sign-in and the profile set
`PLAY_LANG`).
- The theme cookie follows the session's setting (`ThemeCookies`).
- The CSRF token lives in the session, so there is no CSRF cookie.
- The login page's `theme-switch.js` already sets `Secure` from the page's scheme.
- Play needn't see the request as HTTPS to set the attribute. `play.http.forwarded.trustedProxies`
  keeps Play's default (`127.0.0.1`, `::1`), so Play would believe an `X-Forwarded-Proto: https`
  from Apache. Apache doesn't send one today (see the `absoluteURL` note in CLAUDE.md). Nothing
  in production depends on `request.secure`: `GalleryController.thumbnailUrls` uses
  `wlxjury.host`.

**2.4, the CSP.** The policy (`conf/application.conf`):

```
default-src 'self'; script-src 'self' 'sha256-3mX0eBrNyY2mJrtf51OU6PnsrmuoX73+0Vh/ViinYqs=' 'sha256-JC+r7/1h2CV/BLGACtVSizzlXtFWiVQwoalmdJJrIY4='; style-src 'self' 'unsafe-inline'; img-src 'self' data: https://*; media-src 'self' https://*; connect-src 'self' https://commons.wikimedia.org; object-src 'none'; base-uri 'self'; form-action 'self'; frame-ancestors 'none'
```

- **The two hashes** are the layout's theme script and the large views' restore (the same
  text in `large` and `contestFile`). The new `views.InlineScriptHashesSpec` renders the layout,
  the binary and rated large views and a contest file's large view. It hashes each inline
  script (no `src`, not `application/json`) over its text, with CRLF and CR read as LF, as the
  HTML parser gives it. It checks that each hash is in `script-src`, and that `script-src` has
  no hash that no page renders. It also checks its hash function against the CSP spec's example.
- **`.gitattributes`** has `app/views/** text eol=lf`. The index already held LF, so the commit
  changes no template. The worktree's 80 templates were checked out again with LF.
- **`'unsafe-eval'` is gone.** The harness found the one script that used it: unitegallery's
  `initTheme` ran `eval("UGTheme_tiles")` to find its theme. Under the new policy that threw on
  every gallery page (gallery, contestFiles). It now takes `window[name]`, and throws if the
  theme isn't loaded (`986cdf97`). My first grep had missed it, because its pattern needed 50
  characters before `eval` and the line is short. `ContentSecurityPolicySpec` now checks that no
  file in `public/javascripts` calls `eval`, `new Function`/`Function(` or a timer with a
  string. jQuery 3.7.1, Bootstrap 5.3.8's bundle and Tom Select 2.6.2 have none of these.
  Swagger UI 5.18.3's bundles have only `Function("return this")()` fallbacks behind
  `globalThis`/`self`, which a browser never reaches; the shots show no violation.
- **`style-src 'self' 'unsafe-inline'` stays**, as the batch allows. unitegallery builds its
  tiles' HTML with `style=` attributes (16 in `unitegallery.js`), and 6 template attributes
  remain: the galleries' `display:none`, the pager's width, the previous-round filters and the
  job status. Without `'unsafe-inline'` the browser would drop them, and `'unsafe-hashes'`
  would need one hash per value.
- **New directives:** `object-src 'none'`, `base-uri 'self'`, `form-action 'self'` and
  `frame-ancestors 'none'` (matching Play's `X-Frame-Options: DENY`). `form-action 'self'` is
  safe: every form posts to this host, there is no OAuth or Commons form, and every redirect
  after a POST is local.
- **Swagger UI at `/api/docs`:** Tapir's `index.html` (swagger-ui 5.18.3) has no inline script
  or style. It loads `swagger-ui-bundle.js`, the preset and our `swagger-initializer.js` from
  this host. The harness now shoots it (`api-docs`): the four operations and the schemas render
  with no console error or violation.
- **The harness's `--csp-strict`** used to append a second `script-src`, which a browser
  ignores once the policy has its own. It now replaces `script-src` with `'self'`, so every
  inline script is still reported.
- **Deprecation:** Play logs that `play.filters.headers.contentSecurityPolicy` is deprecated in
  favour of `play.filters.csp.CSPFilter`. Unchanged here; the policy is the same either way.

**Checks** (through `scripts/agent-check.py`):
- New and changed specs (`ContentSecurityPolicySpec`, `InlineScriptHashesSpec`,
  `ProductionConfigSpec`, `AllowedHostsSpec`, `ReadinessControllerSpec`):
  - first run: exit 1, a compile error (`NoMaterializer` is in
    `org.apache.pekko.stream.testkit`; `20261102-011210-21772.log`);
  - second run: 1 error, because the sign-in page needs a CSRF token in the request
    (`20261102-011330-27856.log`);
  - third run: exit 0, 35/35 (`20261102-011506-20260.log`).
- After the unitegallery fix: `ContentSecurityPolicySpec` exit 0, 8/8
  (`20261102-012709-23964.log`).
- `Test/compile`, `Gatling/compile`, `views.*`, `ProductionConfigSpec`, `api.ApiSpec`,
  `ReadinessControllerSpec`, `AllowedHostsSpec`, `LoginControllerSpec`, `ThemeSwitchSpec`,
  `SecuredSpec`: exit 0, 259 passed (`20261102-013642-23032.log`).
- Jest: exit 0, 18 suites, 240 tests (`javascript-test/target/checks/20261102-013853-14884.log`).
- Full suite, the release boundary for B1–B5: `Test/compile` and `test` (Jest included): exit 0, Scala 1361 total, 1355 passed, 0 failed, 6 skipped, 1 pending; Jest 18 suites, 240 tests (`20261102-014027-14092.log`, 15 min).

**Screenshots** (Chrome, `--start-server --csp`, light and dark, label `b5`):
- First run: every gallery page had the page error "Evaluating a string as JavaScript violates
  … 'unsafe-eval'". That covered the gallery with its pager and user menu, and contestFiles with
  its region menu and marks. The cause was unitegallery's theme lookup, fixed above.
- After the fix there is no CSP violation and no page error. The only console errors are the
  404 pages' own 404.
- Against `b3`, the 28 shared shots are identical in both modes. Against `b3-fix`, `contests`
  and `contest-images` are identical (light).
- `api-docs` is new. Swagger UI has no `data-bs-theme`, so the harness prints
  "mode shown [None, None]" for it, as expected.

**Deployment** (with this release):
- Add `WLXJURY_ALLOWED_HOSTS` to `runtime.env` before installing the new `production.conf`. It
  lists every jury `ServerName`, comma-separated. On WMUA's server that is the six in
  `conf/apache/0002-wlxjury.conf`: `jury.wikilovesearth.org.ua`, `jury.wikilovesearth.org`,
  `jury.wikilovesmonuments.org.ua`, `jury.wle.org.ua`, `jury.wlm.org.ua`,
  `wlxjury.wikimedia.in.ua`.
  - Without the variable the app doesn't start: readiness fails and maintenance stays on.
  - A name left out answers 400 at its URLs, and `reopen`'s URL check names it.
  - Older releases ignore the setting (their `application.conf` disables the filter), so a
    restore to one works with the new file.
- Cookies are `Secure`, so signing in over plain HTTP stops working. `jury.wikilovesearth.org`'s
  HTTP vhost in `0002-wlxjury.conf` had no redirect to HTTPS; it has one now (review fixes).
  The production vhosts from `wlxjury-infra` need it too, with or before this release, and an
  HTTPS vhost for that name.
- `wlxjury-infra`'s templates (`production.conf.j2`, `runtime.env.j2`) need the same keys.
  `wlxjury_hostnames` already lists the names.

**Open questions**
- `jury.wikilovesearth.org` over plain HTTP: `0002-wlxjury.conf` now redirects it (review
  fixes); the production vhosts from `wlxjury-infra` need the same, with or before this release.
- `X-Forwarded-Proto`: not needed (nothing reads `request.secure`); if it is ever added, it must
  be set on every vhost, or a client could send its own (review fixes).
- Left for later: moving the policy to `play.filters.csp` (the deprecation warning), and
  dropping `'unsafe-inline'` from `style-src` (unitegallery's tiles).

### B5 review fixes

The independent review found nothing blocking. Fixes:

| Finding | Fix | Commit |
|---|---|---|
| Medium: `0002-wlxjury.conf`'s HTTP vhost for `jury.wikilovesearth.org` proxied to the app without the HTTPS redirect, so with Secure cookies signing in there would lose the session (CSRF 403) | The same `RewriteCond %{SERVER_NAME}` / `RewriteRule ^ https://… [END,NE,R=permanent]` as the other vhosts (`RewriteEngine On` comes from the maintenance include). `docs/deployment.md`: `wlxjury-infra`'s vhost templates need the redirect for every name with or before this release, and HSTS on the HTTPS vhosts is recommended | `e6d3c2aa` |
| Low: the sort cookies (`gallerySort`, `binarySort`) weren't Secure | `RoundList.remembered` takes `secure`; `GalleryController` passes `play.http.session.secure`, as `ThemeCookies` follows it. `GalleryOrderSpec`: not Secure in the tests, Secure with the setting on; `ProductionConfigSpec` checks the cookie with the production config | `5bbd0072` |
| Low: production allowed `127.0.0.1`/`localhost`, which Apache's default vhost would pass through with `ProxyPreserveHost On` | `/health/ready` (the only health route; the deploy tools call nothing else on loopback, `reopen` checks the public URLs) is tagged `+ anyhost` in `conf/routes`, which Play's `routeModifiers.whiteList = ["anyhost"]` exempts; `production.conf` sets `play.filters.hosts.allowed = []`. Readiness still answers only loopback clients (404 otherwise, no build details). `AllowedHostsSpec` now builds an app configured as production: loopback names get 400 on `/auth`, readiness answers any Host for a local client and 404 for a remote one. The local smoke-test commands in `docs/deployment.md` send a jury name (`curl -H 'Host: …'`); the packaged smoke test checks `/auth` on loopback gets 400 (Linux container only, not run) | `db2ea456` |
| Low (docs): the `X-Forwarded-Proto` advice could be spoofed | Dropped as advice: nothing reads `request.secure`; if a header is ever added, it must be set on every vhost (`https` on 443, `http` on 80) | `db2ea456` |
| Low: `AllowedHosts.parse` accepted a one-label suffix (`.org`) and default ports (`:443`, which browsers leave out, so the name would never match) | A suffix needs two labels; `:80`/`:443` stop the start with "leave out the default port". `ProductionConfigSpec` tests both | `db2ea456` |
| Info: the filter's WARN doubled `ErrorHandler`'s `client_error` line for each scanner request | `play.filters.hosts.AllowedHostsFilter` at ERROR in `conf/logback.xml` and `conf/wlxjury/logback.xml` | `db2ea456` |

Changed deploy test script: `scripts/deployment/test_database_restore.py` (runs only in the Linux
deployment container; not run here).

Checks: `Test/compile`, `Gatling/compile`, `ProductionConfigSpec`, `AllowedHostsSpec`, `views.*`
(with `ContentSecurityPolicySpec` and `InlineScriptHashesSpec`), `ReadinessControllerSpec`,
`GalleryOrderSpec`, `AllJurorsListSpec`, `RoundListSpec`, `LargeImageSpec`,
`MutationAuthorizationSpec`. The first run failed to compile, because `RoundListSpec` still
called `remembered` without the new parameter (`20261102-021016-7888.log`). After fixing that:
exit 0, 314 passed (`20261102-021101-1696.log`). No full suite: it passed at `bc70ff44`, and the
orchestrator runs it after merging B4.

## Batch B4 results

Branch `ukwikidev/followups-b4`, from `2b92947e`. Commits `a1648e48` (the hasher), `49fea433`
(every password path), `9ec7a4ab` (Gatling fixture), `6748a3c4` (docs).

**2.1** Passwords are bcrypt `$2b$` (`at.favre.lib:bcrypt` 0.10.2, the latest release on Maven
Central; its only dependency is `at.favre.lib:bytes` 1.5.0), in `services.PasswordHasher`.
`users.password varchar(255)` holds the 60-character hash: no migration.
- **Sign-in** (`User.login`): a `$2a$`/`$2b$` hash (cost 4–16) is checked at its own cost; one
  below the configured cost is rehashed. A legacy hash (exactly 40 lowercase hex characters)
  is compared with `MessageDigest.isEqual` against the SHA-1 of the trimmed password, as
  before, and on success replaced by a bcrypt hash with a conditional `UPDATE … WHERE
  password = <the legacy hash>`, so a password changed meanwhile is never overwritten. Any
  other value, no value, an unknown login or a blank password never matches; those paths
  run a dummy bcrypt check, so a sign-in takes about as long whatever the account. The login
  form no longer checks the password itself (it was checked twice per sign-in).
- **Setting a password** stores only bcrypt: sign-up (the first account, root, included),
  `UserService.createAccount` (organizer's new user, import, generated greeting passwords),
  the edit form (an admin's change and the own profile) and root's reset all go through
  `PasswordHasher.default.hash` / `User.setPassword`. `User.hash`, `User.sha1` and the public
  `User.updateHash` are gone; `User.create(fullname, email, passwordHash, …)` takes a hash
  (renamed parameter). Generated passwords now use `SecureRandom`. `User.toString` hides the
  hash.
- **Trimming:** a password is trimmed both when set and when checked. Before, sign-in trimmed
  but setting didn't, so a password set with surrounding spaces could never be used.
- **72 bytes:** a new password is refused over 72 UTF-8 bytes after trimming, or when blank
  (`password.too.long`, `password.empty`, on the sign-up and edit forms; the edit form now shows
  its password error). A legacy password over 72 bytes still signs in: such input is pre-hashed
  with SHA-512 before bcrypt (the library's `hashSha512` strategy), the same way when hashing
  and checking, so it is rehashed and only the whole password matches (not its first 72
  bytes). Refusing alone would have left those accounts on SHA-1.
- **Config** `bcrypt.*` (not `password.*`: the deployment script's credential scan reads any
  key containing "password" as a literal credential): `cost = 12`, `maxConcurrent = 2` (the
  server has 2 CPUs shared with MariaDB; the plan's example was 4), `maxWait = 10 seconds`. A
  cost below 10 is raised to 10 (with a warning) unless `bcrypt.allowLowCost`, which only
  `test/resources/application.conf` sets (cost 4). `PasswordHasher.default` reads the config
  the app is started with (`-Dconfig.file`).
- **Concurrency:** a fair semaphore of `maxConcurrent` turns around every bcrypt computation.
  A sign-in waits for a turn until its deadline (`maxWait` from when the request arrived,
  the time queued included), then gets 503 with `signin.busy` and `Retry-After: 5`. Sign-in
  and sign-up run on a new `sign-in-dispatcher` (4 threads, `modules.SignInExecutionContext`);
  saving a user, import and reset moved to the blocking dispatcher (`withAuthOn`), so no
  hash runs on Play's default dispatcher. An admin path that gets no turn in time fails with
  the server error page.
- Nothing logs a password or a hash; a rehash logs `password hash upgraded: accountId=<id>`
  on `security.password`.
- The Gatling fixture hashes with the tests' cost (`FixtureVersion` v4). `bench/scripts/
  anonymise.sql` still writes SHA-1 (MariaDB has no bcrypt): the benchmark's first sign-in per
  account rehashes, its comment says so.

**Tests:** `services.PasswordHasherSpec` (18: format and salt, trimming, cost upgrade, `$2a$`,
legacy match/mismatch/format, garbage and a cost-31 hash, blank, the 72-byte rule and the long
legacy password, settings and the shipped config's cost 12, the semaphore's peak and Busy at
the deadline). `controllers.PasswordStorageSpec` (10: legacy wrong password keeps the row;
legacy success rewrites the row and the second sign-in uses bcrypt; a changed password isn't
overwritten; wrong bcrypt password; garbage, missing and unknown; 503 when every turn is taken;
sign-up, given and generated passwords, profile, reset, import and a legacy sign-in, then a
scan of `users.password`: all `$2b$04$`, none 40-hex; the 72-byte and blank rules on sign-up
and the profile; a long legacy password).

**Checks** (all through `scripts/agent-check.py`):
- `Test/compile` + `Gatling/compile` + `PasswordStorageSpec`, `UserControllerSpec`,
  `UserDbSpec`, `MultiContestUserSpec`, `SecuredSpec`, `CoiAuthorizationSpec`, `ThemeSwitchSpec`,
  `MessagesCompletenessSpec`, `SignOutAndSwitchSpec`, `AdminPagesCurrentContestSpec`,
  `MutationAuthorizationSpec`: 145/146, the one failure a test-only mistake in
  `PasswordStorageSpec` (`20261102-005612-28624.log`).
- `PasswordHasherSpec` 18/18, `LoginControllerSpec` 16/16 (`20261102-010106-28376.log`);
  `PasswordStorageSpec` after the fix: exit 0, 10/10 (`20261102-010320-10416.log`).
- One fixture start (rebuilt, v4), `gatling.GatlingSmokeSpec`: exit 0, 7/7, signing in as the
  fixture's jurors and organizer (`20261102-010537-19228.log`).
- Not run: `LocalImageCacheServiceDockerSpec` (Docker-gated; compiled), the full suite (after
  B5).

**Deployment:** users notice nothing. Each account's first sign-in after the release replaces
its SHA-1 hash; a sign-in costs about 0.25 s of CPU at cost 12. Accounts that never sign in
keep their legacy hash. No config change is needed: `production.conf` includes
`application.conf`. Before the release, two case-sensitive counts (run on the tests' MariaDB
by `db.scalikejdbc.UserPasswordSpec`):
- values that will never match, neither legacy nor bcrypt (such an account can't sign in by
  password, before or after): `SELECT COUNT(*) FROM users WHERE password IS NOT NULL AND
  password NOT REGEXP BINARY '^([0-9a-f]{40}|\\$2[ab]\\$[0-9]{2}\\$[./A-Za-z0-9]{53})$'`;
- legacy hashes still stored: `SELECT COUNT(*) FROM users WHERE password REGEXP BINARY
  '^[0-9a-f]{40}$'`.

**Merge note:** `ukwikidev/followups` `b4dba585` adds `test/gatling/setup/UiShotsServer.scala`
with `User.sha1(...)`: replace it with `services.PasswordHasher.default.hash(...)` (the stored
value for `User(..., password = Some(...))`).

**Open questions**
- `maxConcurrent = 2` rather than 4, for the 2-CPU server; raise it in config if sign-in
  bursts queue.
- Whether to invalidate legacy hashes of accounts that never sign in again, on a schedule.

**B4 review fixes** (the review found nothing blocking):
1. *Sign-up never shed load* → `a0f7c97d`. Sign-up takes its deadline on arrival and passes
   it through `UserService.createAccount` to `hash`; a `Busy` is a 503 with `signin.busy`
   (nothing created). Sign-ins and sign-ups in flight are capped by an atomic counter
   (`bcrypt.maxQueued`, 50; 500 in the tests' config for Gatling's stress runs): over it, 503
   at once, without queueing. The Pekko queue isn't bounded.
2. *Hashes could reach logs through ScalikeJDBC* (slow statements at warn, failures at error,
   both with the bound values) → `f9a5f8e4`. The statements that bind a hash (`setPasswordHash`,
   `replaceHash`, the `create` insert in its own session) run through `User.quietly`: an
   auto-commit session with `SettingsProvider` turning off `loggingSQLAndTime` (warning
   included) and `loggingSQLErrors`; checked in `StatementExecutor` 4.3.2. `UserPasswordSpec`
   forces failing writes (a value too long for the column) and captures the logs: the control
   statement logs the value, the three password writes don't, nor does the exception's text.
3. *A failed rehash failed the sign-in* → `f9a5f8e4`. `User.upgradeHash` logs
   `password hash not upgraded: accountId=… error=<class>` and returns the user.
4. *The conditional UPDATE wasn't exercised* → `f9a5f8e4`. `UserPasswordSpec` reads the
   legacy hash, changes the password, and runs `replaceHash` with the old value: false, the new
   hash kept; with the current value: true.
5. *Busy left a partial edit; an import dropped it silently* → `a0f7c97d`. The edit form hashes
   before writing anything (`User.setPasswordHash`); a `Busy` is a 503 with the form and
   `password.busy`, nothing changed (also for a new user from the form). An import stops trying
   after one got no turn, and the message adds `users.not.added` with the count of those not
   added (any failure). A reset without a turn keeps the password and flashes `password.busy`.
6. *The legacy count wasn't case-sensitive* → this commit: the deployment note's two counts
   above (`REGEXP BINARY`), run on the tests' MariaDB (10.6) by `UserPasswordSpec`.
7. *An invalid cost failed only the first sign-in* → `f9a5f8e4`: `AppModule` reads the
   settings and creates `PasswordHasher.default` at start (`UserPasswordSpec`: cost 99 fails
   the start with `bcrypt.cost must be between 4 and 16, was 99`).

The tests' `bcrypt.maxWait` is now 2 seconds, so the specs that take every turn wait less.

Not fixed, noted: a large import is N × 0.25 s in one request (300 users ≈ 75 s, which may
reach Apache's proxy timeout); the first-account-is-root race in sign-up is wider now (the
hash takes longer between the count and the insert), on a fresh database only.
`maxConcurrent` stays 2, the user's call pending; the reviewer suggests 1 or 2, measured on
the production CPU.

**Checks after the fixes** (all through `scripts/agent-check.py`, each started when no other
JVM ran):
- `Test/compile` + `Gatling/compile` + `UserPasswordSpec` (5), `PasswordStorageSpec` (14),
  `PasswordHasherSpec` (18): exit 0, 37/37 (`20261102-015649-19240.log`).
- `Test/compile` + `LoginControllerSpec`, `UserControllerSpec`, `UserDbSpec`,
  `MultiContestUserSpec`, `SecuredSpec`, `MessagesCompletenessSpec`, `CoiAuthorizationSpec`,
  `ThemeSwitchSpec`: exit 0, 99/99 (`20261102-015918-21780.log`).

**What remains:** merging into `ukwikidev/followups` (and fixing `UiShotsServer`'s `User.sha1`,
see the merge note).

## Batch B6 results

Branch `ukwikidev/followups`, from `87953e66`. Commits `2a1600a7` (4.1), `73680fca` (2.8),
`2cd09112` (2.5), `173fb89d` (2.6), `05778356` (2.7), `ed9094d8` (4.8), then this one.

**2.5** `scripts/run-with-prod-dump.sh` passes no password on a command line:
- The prod `mysqldump` reads a `[client]` option file (user and password, quoted and escaped),
  mode 600 in a `mktemp -d` directory of mode 700. It is mounted read-only into the `--rm`
  client container and given as `--defaults-extra-file` (the first option), and removed as soon
  as the dump ends.
- The local container takes its four `MARIADB_*` values from an `--env-file` in the same
  directory, removed right after `docker run -d`.
- The local root client (`docker exec … mysql`, three places) reads `/root/.my.cnf` (mode 600),
  written inside the container from the container's own `MARIADB_ROOT_PASSWORD`. The
  `--reuse-container` path writes it too, so containers made by the old script work.
- `trap cleanup EXIT` removes the directory on every path; `HUP`, `INT` and `TERM` exit through
  it. Under Git Bash the dump's `docker run` gets `MSYS_NO_PATHCONV=1` and a `cygpath -m` host
  path: without them MSYS rewrote `/etc/wlxjury-dump/client.cnf` as a Windows path.
- Checked with `bash -n` (no shellcheck on this machine), and end to end with a fake `docker` on
  `PATH` (`--dump-only`): the dump container got only the mount and `--defaults-extra-file`; the
  option file held `password="p\"a\\ss w$rd"` for `p"a\ss w$rd`; the local container got only
  `--env-file`; no argument held a password; and the temporary directory was gone after a
  success, after a failing `docker` (exit 3) and after `kill -TERM` (exit 143). Not run against
  real data. My first attempt had a Windows-style `PATH`, so the real `docker` ran the
  `mariadb:10.6` client once with a dummy host and password; it stopped at the option file's
  path, before connecting. That is how the MSYS rewrite was found. On Windows (NTFS) the files
  show mode 644; on Linux they are 600 and 700.

**2.6** The audit table is under item 2.6. One use was unsafe: the monuments' wiki text (the
monuments page and both large views), now `views.WikiLinks`. The commented-out type line inside
`<!-- -->` is deleted. New `views.WikiLinksSpec` (5 examples): links and labels, the target's
encoding, escaping of the text, labels and quotes, an unclosed link, and the large view's
monument box rendered with `<script>`, `<img onerror>`, `<b>` and `-->` in its fields.

**2.7**
- `incident-prevention-guidelines.md`, "Practices Not Followed Consistently Today", now has
  "Still open": no per-IP or per-account sign-in throttling (only the bcrypt semaphore);
  `style-src 'unsafe-inline'`; the deprecated CSP key; Play's default headers untested, no
  `Permissions-Policy`, HSTS in `wlxjury-infra`; the legacy hashes of accounts that never sign
  in. And "Done": the secret, passwords, CSRF and POST, mutation authorization, SQL, errors,
  hosts, Secure cookies, the CSP, the API with authentication, the dump script, the `Html`
  audit.
- The implementation plan has a status at the top and a **Done** line under each of items 1–11
  and the README deliverable. Item 7 notes that `jury-canary.wle.org.ua` is no longer in the
  host list.
- `review.md` has a status table for every finding and step (the `preloadImages` injection: the
  template is gone).
- README: a short "Security Controls" section linking the four security docs, CLAUDE.md and
  docs/deployment.md.

**2.8** `Secured.denialLogLine` writes `accountId=<id>` and no `account=<email>`. I checked the
other log lines that could name the signed-in user (`rg -n -i 'email|emailLo|username|fullname'`
near logger calls, and every logger call in `app/controllers`, `ErrorHandler`,
`CsrfErrorHandler`, `api/`, `ApiTokenService`, `User` and `RoundConflicts`). None writes an email
or a name: `security.password` and the `coi` logger already log ids, and `CommonsLogin` logs the
configured Commons bot account, not a signed-in user (left as is). CLAUDE.md's sentence now says
the line has the account id only. `SecuredSpec`'s exact line drops the `account=` field, and a
new example checks that neither the email nor the name appears. `LoginControllerSpec` asserts
only the reason. The error-handling plan (`2026-10-27-error-handling.md`) still describes the
old field; it is history and not changed.

**4.1** Deleted: `RoundController.mergeRounds` (no route) with `mergeRoundsForm` and
`MergeRoundsForm`, `RoundService.mergeRounds`, `SelectionJdbc.mergeRounds` and `Round.delete` (no
caller in `app/` or `test/`), and the commented-out `Tools.distributeImages` block in
`RoundController.setImages`. Kept, because `SelectionJdbc.setRound` (moving one file to another
round) uses them: `moveVotes`, `Round.settleDeletedFiles` and the `RoundImageCounts`
invalidations. Tests: `SelectionSpec`'s "mergeRounds" (2 examples) and `RoundImageCountsDbSpec`'s
"follow merging rounds" are deleted. In `DeletedFilesSpec`, "be moved aside there when a deleted
file's are merged in" is deleted (the `setRound` example next to it covers the same path), and
"bring the votes of a merged round back" now moves the file with `setRound`. The doc comments
that named `mergeRounds` (`DeletedFiles`, `Round`, `SelectionJdbc`, `RoundImageCounts`) are
updated.

**4.8**
- Theme plan: the header says done (Phases 1–4, step 2a and the follow-ups on
  `ukwikidev/theme-modes` up to `cb103b24`; that branch is not in `master` yet). Phases 2, 3
  and 4's "Not yet reviewed" now say "Reviewed: see Review follow-ups"; each phase has that
  section.
- `docs/superpowers/plans/*`: a status line under each title with the commits that
  `git log --grep` found: saveResized `09ce2d84`, `b9f7e773`, `f88e0a10`; AutoRollback
  `2596e074`, `7f2a870a`, `45a81e9e`; Flyway baseline `a330d903`, `8b93ca4b`; Gatling from
  `83aef073`; monument_id `4a2ff9fd`, `f4ec8447`; SQL injection `b403abc7`, `5d63fa10`,
  `d2b93cdb`. The multi-contest plan already had its status. The blue/green plan and both
  blue/green designs say "superseded: the blue/green scripts are disabled …, see
  docs/deployment.md".
- Deleted the empty `docs/dbperf/{explain.txt,last-agent-message.json,last-agent-message.md}`
  and `docs/queries-improvement.txt`. `AGENTS.md` (it held only the text `CLAUDE.md`) is kept,
  with one sentence pointing to CLAUDE.md.
- `DEV.md`: "Creating the root admin" (the first sign-up on an empty database becomes root,
  `users.is_root`; an `UPDATE` makes an existing account root); Play 3.0.x and sbt 1.x links;
  the Activator note, Scala IDE, NetBeans and ENSIME replaced by Metals.
- docs/deployment.md, "One app instance": what lives in each JVM (`RoundImageCounts` with its
  10-minute TTL, the job scheduler's limits, the bcrypt limits, the download progress, the
  distribution's caches). Rounds are locked in the database and jobs hold leases, but two
  instances on one database were never designed for. The "in-JVM round locks" this plan names
  no longer exist: rounds are locked with `FOR UPDATE`.
- Skipped: the COI comparison rows (WJ-F4.9, WJ-F5.7, WJ-F6.2, eligibility).
  `docs/comparison/` is not in this repo; it exists only untracked in the main checkout.
- B8's row now says to delete or rework `RegionCountPairedBenchmark`.

**Checks** (through `scripts/agent-check.py`, no other JVM running):
- `Test/compile`, `Gatling/compile` and `SecuredSpec`, `MutationAuthorizationSpec`,
  `CoiAuthorizationSpec`, `LoginControllerSpec`, `AdminPagesCurrentContestSpec` (the specs that
  read `security.authz`), `RoundControllerSpec`, `RoundImageCountsDbSpec`, `SelectionSpec`,
  `DeletedFilesSpec`, `MonumentControllerSpec` and `views.*` (with the new `WikiLinksSpec`):
  exit 0, 304 passed, 0 failed (`target/checks/20261102-025815-14360.log`).
- The script: `bash -n` and the fake-`docker` runs above.
- No full suite. The shared code that changed is `Secured.denialLogLine` (one log field, covered
  by the authorization specs) and deleted dead code.

**Open questions**
- Sign-in throttling per client address or per account is not in any batch. The guidelines
  list it as open.
- `CommonsLogin` logs the Commons bot account's user name. That is config, not a signed-in
  user's personal data, so it is left as is.

### B6 review fixes

| Finding | Fix | Commit |
|---|---|---|
| Medium, display: `WikiLinks` escaped `&`, so the lists' markup showed as text. Counts in `data/wlm-ua-monuments.csv`'s name, address and municipality columns: 5,505 `&nbsp;`, 91 `<br>`, 7 `<ref>` (one inside `<!-- -->`), 29 `{{ref+\|…}}`, 31 `{{s\|…}}`, 5 `{{comment\|…}}`, 18 `<nowiki>`, 16 `<sup>`, 10 `<sub>`, 1 `<small>`, 2 `&supN;`, 359 `''`/`'''` | Before escaping: `<!-- … -->`, `<ref …>…</ref>`, `<ref …/>` and `{{ref+\|…}}` are dropped with their content; `{{s\|…}}`, `{{nowrap\|…}}` and `{{comment\|text\|…}}` become their (first) text; `<nowiki>…</nowiki>` becomes its text, its brackets made references so they build no link; `<br>` (any form or case) is a space; the `<small>`, `<sup>`, `<sub>`, `<s>`, `<u>`, `<big>`, `<span>` tags are dropped (text kept); `''`/`'''` are dropped; then character references are decoded (decimal, hex, and 34 names: `nbsp`, `amp`, `lt`, `gt`, `quot`, `apos`, the dashes and quotes, `sup1`–`sup3`, `deg`, `times`, `numero`, …; an unknown name or an invalid code point stays as written). The result is escaped as before, so a decoded `&lt;script&gt;` is text, and other tags stay escaped text. `WikiLinksSpec` +5 examples (references, an encoded script and img, `<br>`, the formatting tags, `''`, `{{s\|}}` and `{{comment\|}}`, `<nowiki>`, `<ref>`, `{{ref+\|}}` and comments, unknown tags); the `<script>`, `<img onerror>` and `-->` examples are kept | `aecc9b9d`, `23948b4d` |
| Low: `set -a; source .env` exported `WLXJURY_DB_PASSWORD` to every child (docker, gzip, zcat) | `export -n WLXJURY_DB_PASSWORD` after `set +a`; phase 4 already passes sbt the local values explicitly. The `--dump-only` hint prints `-p` (prompt) instead of the password | `6350feec` |
| Audit: the gallery descriptions' DOM sink | Recorded in the 2.6 table; the title is escaped twice (harmless to the display: `.html()` shows the same text). New `GalleryDescriptionViewSpec` | `ff2c2664` |

Checks:
- No other JVM running. `Test/compile` + `controllers.MonumentControllerSpec` and `views.*` (with `WikiLinksSpec`, now 10 examples, `GalleryDescriptionViewSpec` and `LargeViewFixturesSpec`): exit 0, 179 passed, 0 failed (`target/checks/20261102-032030-13844.log`); again after the `{{ref+}}`/`{{comment}}` handling: exit 0, 179 passed (`target/checks/20261102-032344-23792.log`).
- The script: `bash -n` passed. A fake-`docker` run (`--dump-only`, the fake first on `PATH`; it logged all 7 calls) showed `WLXJURY_DB_PASSWORD` unset in every `docker` call's environment, the option file as before, and the temporary directory gone afterwards. The real `docker` was not run.

## Batch B8 results

Branch `ukwikidev/followups`. Commits `ea575a59` (the benchmark), then this one.

**3.5, method.** Base `f9c31ce5` (the commit `ukwikidev/theme-modes` was branched from), in a
detached worktree removed afterwards; variant `a4692f16` (this branch's HEAD before B8). Not
`master`: it is from 2024-11 and predates most of the schema (the multi-contest migrations
V61/V62, the `job` table), so the fixture and pages don't exist there. Both commits have the
same `GalleryLatencyBenchmark` and the same fixture data (`GatlingDbSetup`: 38,366 files, 20
jurors, 383,670 votes a round; v4 differs only in the password hashes), each tree its own
`data/cache` dump (the base built its own). The UI fixture server was not used: the base lacks
it. Each run: one fork of `Test/runMain gatling.setup.GalleryLatencyBenchmark` with
`-Dbench.cases=^organizer-(binary|rated)-byrate-p1$ -Dbench.warmup=1 -Dbench.samples=15`
(organiser session over HTTP, `/byrate/round/{1,2}/user/0/page/1`, 1 warm-up then 15 GETs, taken
as 3 blocks of 5), fork `-Xmx1024m -Dfile.encoding=UTF-8 -Dgatling.timing=true
-Dscalikejdbc.global.loggingSQLAndTime.enabled=false` (SQL logging off in both; root logger
WARN), HikariCP pool in both (`[pool]` line), MariaDB 10.6.22 container per run, Windows, no
other JVM. Order A, B, B, A (4 runs, ~2.5 min each). Round 1 (yes/no) is the control. Raw:
`target/checks/b8-byrate-paired.json` (summary and every sample),
`target/checks/b8-byrate-paired/{A1,B1,B2,A2}-{client.csv,timing.jsonl}` (client times and
the server's per-query timing log). Paired deltas: B run k minus A run k, sample by sample, and
per block of 5.

| Page | Base median (min-max) | HEAD median (min-max) | Paired delta, median (min-max) | Block median deltas |
|---|---|---|---|---|
| by-rate, round 2 (rated), all jurors | 946 ms (874-1239) | 1132 ms (1059-1351) | **+196 ms** (-155 to +427) | +103 to +306 (6 of 6 positive) |
| by-rate, round 1 (yes/no), all jurors | 945 ms (879-1152) | 745 ms (697-845) | **-206 ms** (-431 to -101) | -131 to -274 (6 of 6 negative) |

n = 30 a cell. Block medians varied by 61 ms (base, rated) and 127 ms (HEAD, rated) between
blocks, smaller than the delta, so the pilot settles it and was not expanded.

**Decision.** Neither commit takes ~2 s on this fixture: the 1999 ms of the aggregate plan
(533c4b88, UI fixture server) isn't reproduced at HEAD (1.13 s here). The rated round's page did
get slower on these branches, by ~0.19 s (+20%); the yes/no round's got faster by ~0.21 s (the
binary-aggregate work). From the timing log (median ms per query, rated page):

| Query | Base | HEAD |
|---|---|---|
| `COUNT(DISTINCT page_id)` of the round (the pager) | 125 | 130 |
| the grouped page (`sum(rate)`, `count(rate)`, … `GROUP BY s.page_id ORDER BY rate desc LIMIT`) | 772 | 841 |
| `keyCounts` (files per summed rate, over the whole round) | none | 113 |

Two commits:
- `95783736` "Rank the by-rate gallery and tally the organisers' tiles": `RoundList.ranks` runs
  `SelectionQuery.keyCounts` for the tiles' ranks, one more `GROUP BY s.page_id` over the
  round's 383k votes (~113 ms). This is the feature's cost; it isn't redundant.
- `79f5c51e` "Show a binary round's result as approvals, not stars": the grouped select now
  always adds `sum(s.rate > 0) as approvals, sum(s.rate < 0) as rejections`
  (`ImageDbNew.scala:92`), rated rounds too (~+70 ms on the page query). A rated round's
  by-rate page shows the rate sum, not approvals; whether anything else reads the two sums for
  a rated round wasn't checked. Not fixed here (the select is shared with the file list, CSV and large view,
  so it needs those specs); the change, adding the two sums only for a binary round, is a
  candidate for a later batch. No `EXPLAIN` was run: the per-query timings name the queries,
  and neither changed plan (both are the round's `GROUP BY s.page_id`).

**`RegionCountPairedBenchmark`: deleted** (`ea575a59`), not reworked. It compared
`gallery.regionCounts` off and on (the 2026-10-25 decision, made since) with SQL pairs
(`regionCounts` vs `byRegionStat`) and controller pairs that checked the counts in the gallery
HTML. Since the region menu the gallery page renders no counts, so its page pairs measure
nothing; the endpoint that does is timed by `GalleryLatencyBenchmark`'s `regions-<round>-juror`
and `regions-<round>-organizer` cases (over HTTP, separately from the gallery pages), and the
SQL by `RegionCountBenchmark` (`Q4j`/`Q4r` `byRegionStat`, `Q5app`/`Q6*` `regionCounts`). No
sbt task referred to it; the region-improvements plan now says it was removed and keeps its
recorded commands.

Checks: `Test/compile` + `Gatling/compile` after the deletion: exit 0
(`target/checks/20261102-034540-19504.log`). Runs: base `target/checks/20261102-033439-8788.log`
and `…-034206-28872.log` (in the removed base worktree, summarised here), HEAD
`target/checks/20261102-033721-24660.log` and `…-033958-24388.log`. No specs (no app code
changed).

**Open questions**
- Drop the two sums for rated rounds (~70 ms a rated by-rate page)? It needs the file list, CSV
  and large view specs.
- The 2 s of the aggregate plan came from the UI fixture server at 533c4b88; it wasn't re-run
  here (HEAD measures 1.13 s on the Gatling fixture). The single 4.9 s and 5.2 s outliers there
  did not recur in 60 samples per variant (max 1351 ms).

## Batch B9: the approval sums only for a yes/no round

Branch `ukwikidev/followups`, base `9e3bc9ec`. B8's open question: the grouped select
(`ImageDbNew.SelectionQuery.query`, `grouped = true`) always adds `sum(s.rate > 0) as approvals,
sum(s.rate < 0) as rejections`, read into `ImageWithRating.approvalsFromDb`/`rejectionsFromDb`.

**Readers** (`rg -n "approvals|rejections|approvalsFromDb|grouped\s*=" app test public/javascripts`):

| Reader | Query | Yes/no round | Rated round |
|---|---|---|---|
| `RoundList.query` (all jurors, not file list/CSV): gallery tiles, by-rate gallery, large view neighbours | grouped | gallery's `data-approvals/-rejections/-tally` and by-rate `binaryTally` only under `list.aggregateBinary` / `round.isBinary && asUserId == 0`; the large view reads neither | never read: by-rate shows stars and `rateString` (rate sum) |
| `RoundList.query` of a juror (`asUserId != 0`) | vote rows | `binaryVote(file.rate)` | stars |
| file list, CSV (`groupWithDetails`) | grouped `idOnly` for order/page (never had the sums), then `withVotes` rows | `binaryTally`/`rateString` count from the vote rows | `rateString` from the rows |
| `keyCounts`, `pageKeys`, `imageRank`, `count` | own selects (order expressions `sum(s.rate > 0)` etc.) | unaffected | unaffected |
| `ImageJdbc.byRoundMerged` → `DistributeImages` | grouped | binary top-N (`binaryCandidates`, `SelectTopByRating` with an order) and "exclude rejected by at least" read them, only for yes/no previous rounds; the current round's rows and the other previous-round reads don't | never read (`totalRate`, `rateSum`) |
| `RoundDistributionHandler` (job path) | its own SQL with the two sums | unaffected | unaffected |
| `tile-votes.js addTally`, `ug-theme-tiles.js`, `unitegallery.js` | `data-tally` attributes | tally where the attribute is | none emitted (gated above) |

Without the columns, `approvals`/`rejections` fall back to counting the selection rows, which
for a grouped row (one synthetic selection with the sum) would be wrong, so every yes/no reader
of a grouped row must ask for them.

**Design.** An explicit flag, `SelectionQuery.tally: Boolean = false`: with `grouped`, the select
adds the two sums and the grouped reader reads them; without it neither (the fields stay
`None`). Callers that know the round set it: `RoundList.query` with `tally = aggregateBinary`
(all jurors on a yes/no round), `byRoundMerged(..., tally)` (`ImageRepo` too) with `tally = true`
only on `DistributeImages`' two yes/no-only reads. No guessing from the rate filter or round id.
Rated pages already never show a tally (gated on `isBinary`); specs pin that.

### B9 results

Commits `a62a286f` (the plan above), `a004dac8` (the change), then this one.

**Change.** `SelectionQuery.tally` (default false) adds `sum(s.rate > 0) as approvals,
sum(s.rate < 0) as rejections` to a grouped select and has `Readers.groupedReader(tally)` read
them; otherwise `approvalsFromDb`/`rejectionsFromDb` stay `None`. Set by `RoundList.query` as
`aggregateBinary && !withDetails` (all jurors on a yes/no round; a file list's and CSV's tallies
count their vote rows, and their grouped `idOnly` query never had the sums), and by
`DistributeImages` through `byRoundMerged(..., tally = true)` on its two yes/no-only reads
(both derived from the round's kind since the review fixes below).
Counts, `idOnly`, `keyCounts`, `pageKeys` and a juror's rows are unchanged. No view changed: the
rated pages already showed no tally; specs now pin it.

**Specs.** `ImagesSqlSpec` (grouped SQL without the sums; with `tally`; count/idOnly/juror SQL
the same either way), `ImageDbNewDbSpec` (the sums `None` without `tally`, sum and count still
there), `JurorImagesSpec` (approvals and rejections with `tally`, `None` without),
`RoundListSpec` (`tally` only for all jurors on a yes/no round's gallery/by-rate list),
`GalleryTileStarsViewSpec` (a rated round's organiser tiles carry no `data-approvals`,
`-rejections`, `-tally`), `GalleryByRateViewSpec` (a rated round's organiser by-rate page: stars
and `rateString`, no `.wlx-approvals`); `RoundDistributionJobSpec`'s binary top-N reference
reads with `tally = prev.isBinary`, as `DistributeImages` does. Runs: `Test/compile`,
`Gatling/compile` and DB/controller/view/distribution specs (`db.scalikejdbc.{ImagesSqlSpec,
ImageDbNewDbSpec,JurorImagesSpec}`, `controllers.{RoundListSpec,RoundListDbSpec,
AllJurorsListSpec,CsvSpec,OwnVotesSpec,GalleryOrderSpec,LargeImageSpec,
TopBinaryOrderChoiceSpec}`, `views.*`, `org.intracer.wmua.cmd.*`,
`services.distribution.{RoundRedistributionFiltersSpec,RoundDistributionJobSpec}`): 422 tests,
1 failure (`target/checks/20261102-104458-26564.log`), the new `RoundListSpec` case expecting no
`tally` on a file list, fixed by `&& !withDetails`; rerun of `controllers.{RoundListSpec,
RoundListDbSpec,AllJurorsListSpec,CsvSpec}`, `views.{FileListViewSpec,LargeViewControllerSpec}`:
71 passed (`target/checks/20261102-110006-10192.log`). No full suite.

**Benchmark, method.** As B8: one fork of `Test/runMain gatling.setup.GalleryLatencyBenchmark`
per run, `-Dbench.cases=^organizer-(binary|rated)-byrate-p1$ -Dbench.warmup=1
-Dbench.samples=15`, fork `-Xmx1024m -Dfile.encoding=UTF-8 -Dgatling.timing=true
-Dscalikejdbc.global.loggingSQLAndTime.enabled=false` (SQL logging off in both), HikariCP in
both, one JVM at a time (shared lock with the other worktree's agent). A = `9e3bc9ec` in a
detached worktree with its own `data/cache` (removed afterwards), B = `a004dac8`. The timing
logs confirm the variants: the rated page's grouped query has `as approvals` in all A samples
and none of B's; the yes/no page's in both (the control: same SQL). Pilot A, B, B, A was
ambiguous for the rated page (block deltas -139 to +215 ms), so it was expanded once with
B, A, A, B. Raw: `target/checks/b9-byrate-paired.json` (summary, every sample),
`target/checks/b9-byrate-paired/{A1..A4,B1..B4}-{client.csv,timing.jsonl,sbt.log}`. Paired
deltas: B run k minus A run k, sample by sample and per block of 5.

| Page (client ms) | Base median (min-max) | HEAD median (min-max) | Paired delta, median (min-max) | Block median deltas |
|---|---|---|---|---|
| by-rate, round 2 (rated), all jurors | 1220 (1060-1841) | 1229 (1074-2703) | **-18** (-531 to +1520) | -139 to +1051; 5 of 12 positive |
| by-rate, round 1 (yes/no, control), all jurors | 827 (714-1243) | 802 (710-1204) | -32 (-472 to +307) | -137 to +56 |

Run medians, rated: A 1191, 1284, 1203, 1223; B 1343, 1110, 1608, 1202 (B3 slow throughout:
its count and `keyCounts` were slower too). Server-side median per query (timing log, n = 60):

| Query | Rated A | Rated B | Yes/no A | Yes/no B |
|---|---|---|---|---|
| grouped page (`GROUP BY s.page_id … LIMIT`) | 900 | 908 | 475 | 456 |
| `COUNT(DISTINCT page_id)` | 139 | 152 | 145 | 138 |
| `keyCounts` | 121 | 125 | 160 | 152 |

Per run, the rated grouped query: A 888, 917, 892, 926; B 961, 811, 1220, 880.

**Decision.** The change stays: the rated round no longer computes two sums nothing reads, and
every yes/no display is as before. Its time saving isn't measurable on this fixture: run-to-run
variation (~±100 ms on the grouped query, the unchanged yes/no control moving 20-30 ms) is
larger than any effect, and B8's ~70 ms came from comparing commits that differ in more than
the sums. Not expanded further: the change only removes work, so more runs couldn't change it.

**Open**
- A same-JVM SQL pair (the grouped page with and without the sums, alternated) would size the
  aggregates' own cost if that ever matters; not run.

The large view's JSON (`LargeViewController`, `Json.writes[ImageWithRating]`, the `Accepts.Json`
branch) writes the case class's fields, so a rated round's grouped rows there no longer carry
`approvalsFromDb`/`rejectionsFromDb` (they were the rated round's `sum(rate > 0)` and 0); a
yes/no round's still do. Nothing in `public/javascripts` reads either field.

### B9 review fixes

| Finding | Fix | Commit |
|---|---|---|
| Medium: `tally` opt-in, so a new grouped reader of a yes/no round would silently count the one synthetic selection | `RoundList.query` sets `tally = round.isBinary` (only a grouped query reads it); `byRoundMerged`'s `tally` has no default and `DistributeImages` passes the round's `isBinary` on every read (current and previous rounds); unused `Round.allImages` deleted. `byRoundMerged` keeps a round id, not a `Round`: `DistributeImagesSpec`'s mock is stubbed by id and its calls pass `round.copy(...)`. Guard: `ImageWithRating.approvals`/`rejections` `require` the count from the query on a grouped row (a selection of juror 0). Checked the juror-0 rows without sums: `RoundDistributionHandler.previousCandidates`/`ratedPage` feed only `perImageFilters` (no top-N, no rejected-by: `SelectedAtLeast` reads `rateSum`, min average `totalRate`); `binaryPage` sets both; `DistributeImages`' rows now have them for a yes/no round, and a rated round's are read by `totalRate`/`rateSum` only; view fixtures with juror-0 rows set them or are rated (not read). `JurorImagesSpec` asserts the failure | `166e8082` |
| Low: `approvals` scaladoc | says the grouped row's counts come only with `tally`, and that without it reading fails | `166e8082` |
| Low: the large view's JSON | recorded above | this commit |
| Nit: two view test names said "whose query has no tally" | renamed/commented: they pin the view's gate with hand-built rows | `166e8082` |

Checks: `Test/compile`, `Gatling/compile`, `testOnly db.scalikejdbc.{ImagesSqlSpec,
ImageDbNewDbSpec,JurorImagesSpec} controllers.{RoundListSpec,RoundListDbSpec,AllJurorsListSpec,
CsvSpec} views.* org.intracer.wmua.* services.distribution.*`: 447 passed, 0 failed, 1 pending
(`target/checks/20261102-122135-19824.log`). No benchmark rerun.

## Batch B11: ranks in the page query

Branch `ukwikidev/followups-ranks` (from `9e3bc9ec`). Commits `504e1a09` (the benchmark), then
this one. Phase 1: measured and selected; the change waits for B9, which changes the same
grouped select.

**Method.** `test/gatling/setup/RankQueryBenchmark` (new), one `Test/runMain` fork
(`-Xmx1024m`, `-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`, and
`GlobalSettings.loggingSQLAndTime` off in code), on the Gatling fixture this tree built
(`GatlingDbSetup`: 38,367 files, 20 jurors, 383,670 votes a round; rated round 2, rates 1-10,
69 distinct rate sums; binary round 1, 11 distinct approval/rejection pairs), MariaDB 10.6.22
container, HikariCP, Windows, no other JVM. The queries are the app's own
(`SelectionQuery.query`/`list`/`count`/`keyCounts`/`withVotes`), the window columns inserted
into their SQL. A sample is a whole page view's queries, one after another, each on its own
autoSession as the app runs them. Per case: 2 warm-up views of each variant, then 15 rounds of
all the variants in an order rotated each round; deltas are each round's variant minus its A.
Run 1: the by-rate gallery of all the jurors' votes, page 1 and page 1279 (offset 19,170), both
rounds. Run 2: page 1 again with DK, the file list's page 1 (1,000 rows), a juror's by-rate page
1, and `ANALYZE FORMAT=JSON`. Run 2's rated cases had outliers of 3-7 s (the machine was short of
memory; that run's compile took 16 minutes), so its rated rows have a wide spread; its binary and
juror rows are as steady as run 1's.

| Variant | Queries of a page view |
|---|---|
| A (today) | pager `COUNT(DISTINCT s.page_id)`, the grouped page, `keyCounts` |
| K | the page, `keyCounts`; the total is the sum of `keyCounts`' counts |
| W | the count, the page with `rank() over (order by <rank key>)` and `count(*) over (partition by <rank key>)` |
| WC | W with `count(*) over ()` as the total, no count (one when the page is empty) |
| D (for information) | a narrow grouped page from `selection` alone (ids, sums, the windows, the total), then the page's 15 images by id |
| DK (for information) | D without the windows; ranks and total from `keyCounts` |
| WCn (file list, for information) | WC's id query without the join to `images` |

By-rate gallery of all the jurors' votes, ms: A's median (p25-p75); the others' paired delta
median (p25 to p75) and the rounds of 15 in which they were faster than A.

| Case | A | K | W | WC | D | DK |
|---|---|---|---|---|---|---|
| rated p1 (run 1) | 1261 (1126-1392) | **-235** (-408 to -107), 13 | +313 (+52 to +401), 3 | +66 (-64 to +364), 4 | -256 (-441 to -169), 15 | |
| rated p1279 (run 1) | 1230 (1127-1313) | **-177** (-231 to -51), 13 | +197 (+126 to +317), 1 | +76 (-4 to +196), 5 | -335 (-383 to -259), 15 | |
| binary p1 (run 1) | 779 (712-841) | **-163** (-200 to -92), 14 | +268 (+188 to +407), 0 | +20 (-23 to +109), 6 | -353 (-416 to -317), 15 | |
| binary p1279 (run 1) | 804 (730-829) | **-135** (-183 to -93), 12 | +267 (+196 to +330), 1 | +49 (-61 to +121), 5 | -364 (-428 to -347), 15 | |
| rated p1 (run 2, noisy) | 1300 (1170-3004) | +54 (-185 to +171), 7 | +174 (-39 to +286), 4 | -46 (-286 to +61), 8 | -307 (-560 to -128), 12 | -408 (-881 to -309), 15 |
| binary p1 (run 2) | 739 (719-773) | **-104** (-185 to -75), 14 | +248 (+216 to +311), 1 | +80 (+23 to +119), 2 | -357 (-391 to -323), 15 | -335 (-371 to -291), 15 |

File list of all the jurors' votes, page 1 (run 2), same columns:

| Case | A | K | W | WC | WCn |
|---|---|---|---|---|---|
| rated (noisy) | 1146 (1071-2812) | **-161** (-910 to -78), 14 | +16 (-84 to +110), 6 | -93 (-674 to -1), 12 | -186 (-308 to -134), 12 |
| binary | 672 (618-769) | **-129** (-210 to -106), 14 | -17 (-80 to +54), 8 | -213 (-271 to -135), 14 | -299 (-329 to -237), 15 |

A juror's by-rate page 1 (A: the deferred-join page and `keyCounts`; W: the windows in the
deferred join's inner query; the pager's total comes from `JurorCounts` in both): rated 38 ms,
W -3 (14 of 15); binary 42 ms, W -1 (11 of 15).

Per query, medians (run 1, page 1): rated count 138, page 1002, `keyCounts` 125, W's page 1416;
binary count 148, page 464, `keyCounts` 154, W's page 936. The windows add 400-470 ms to the
page query, three times what `keyCounts` costs by itself.

**EXPLAIN** (`ANALYZE FORMAT=JSON`, rated round page 1 unless named; full JSON in
`analyze.json.txt`):
- Today's page: 383,670 rows from `idx_selection_round_page_rate` with the images' primary
  key, one temporary table (the GROUP BY), one filesort with a priority queue (LIMIT 15):
  1001 ms. The deep page sorts without a priority queue: 1026 ms.
- W: the same, plus two more filesorts of all 38,367 grouped rows (one per window spec, no
  priority queue, 4 sort passes in a 64 KB buffer) and `window_functions_computation`: 1309 ms;
  WC 1146 ms; binary WC 777 ms against 438. The windows are computed over every grouped row of the
  temporary table, which carries the images' columns too, before the LIMIT applies.
- `keyCounts`: the same index read into a temporary table of one integer per file and a sort of
  69 rows: 134 ms. The count: index only, 167 ms.
- DK, the narrow page without the images join: rated 711 ms, binary 295 ms (today's 1001 and
  438). The rated page costs more than twice the binary one even without the join; not
  investigated.
- The file list's id query: one sort of all 38,367 rows (no priority queue at LIMIT 1000, 6
  passes): 768 ms; WC 1325, WCn 1019.
- A juror's page: 13.6 ms today, 34.4 ms with the windows (two more sorts of the juror's ~19k rows).

**Correctness.** W's ranks equal today's (`Ranks(keyCounts)`) everywhere checked, as do WC's,
D's, DK's and K's ranks and totals (`MISMATCH` count 0): whole lists, and pages 1, 2, the
middle, the last and past the end (with 69 or 11 keys among 38k files, page 2 and the middle
page start inside a tie). Lists: rated all, rated "rated", rated unrated (empty), region 80, rated
+ region; two regions (the monument join; adm0 01 and 51, 105 files; whole list only); binary in
each `BinaryOrder`, selected, rejected, unrated (empty), a region; jurors' lists (rated, rated
"rated", binary; the deferred join); file lists rated, rated "rated" + region, binary, binary
difference selected, a juror's. In the timing runs every variant's page equalled A's in every
round (ids, ranks, total). The fixture's rated round has no rate 0 or below, so the in-memory
`rateSum` (rates below 0 as 0) against SQL `sum(s.rate)` in `RankKey` wasn't exercised.

**Decision: K.** The pager's total of a ranked list is the sum of `keyCounts`' counts; the ranks
stay as they are.
- W is slower in every organiser case: +174 to +313 ms median, faster in at most 4 of 15 rounds.
  MariaDB 10.6 computes the two windows by sorting every grouped row twice more, without the
  LIMIT's priority queue, which costs more than `keyCounts`' separate narrow aggregation
  (125-160 ms).
- WC is at best even on gallery pages (-46 to +80 ms) and saves 93-213 ms on a file list, less
  than K, and needs a count query anyway for an empty page or one past the end.
- K drops the pager's `COUNT(DISTINCT s.page_id)` (130-150 ms) in every organiser case:
  -104 to -235 ms median, 12-14 of 15 rounds faster; the exception is run 2's noisy rated page 1
  (+54, 7 of 15). It changes no SQL, so it doesn't touch B9's select; the ranks are today's by
  construction, and the sum is right for an empty list and past the end without a fallback.
- Jurors' lists: W saves 1-3 ms of ~40 and their totals already come from `JurorCounts`; they
  keep `keyCounts`, so `keyCounts` stays (not dead code).
- Not a rank change, for a later batch after B9: the narrow grouped page (DK: the page's ids and
  sums from `selection` alone, then the page's images, as the deferred join does for a juror's
  page) saves a further ~150-250 ms over K on these pages (DK -335 to -408 against K -104 to
  -235 on page 1), and the file list's id query needs no join to `images` either (WCn). Both change
  the grouped select that B9 changes.

**Implementation plan (phase 2, on B9).**
- `RoundList`: `ranked` (the file list, or by rate in any order but file order: the controller's
  conditions today), `rankCounts()` = `query.keyCounts(rankKey.columns)` and `ranks(files,
  counts)`; the CSV doesn't use them.
- `GalleryController.galleryOf`: a lazy `rankCounts` when `list.ranked`; the pager's total from
  the juror's counts first, then the sum of `rankCounts`, else `list.count()`; the ranks from the
  same counts, so a page view runs `keyCounts` once and no count.
- Specs: `RoundListDbSpec`: the sum of the rank counts equals `count()` for rated and binary
  lists (each `BinaryOrder`), each vote filter (an empty one too), a region and two regions (the
  monument join), by-rate and file list; ranks of pages that split a tie unchanged (the existing
  file list test); a page past the end with the right total and no ranks. `AllJurorsListSpec`
  (the file list's "1–1,000 of 1,003" and its ranks, the by-rate gallery's ranks) and
  `GalleryOrderSpec` as they are; the CSV specs unaffected. Then the paired `GalleryLatencyBenchmark`
  pilot (A, B, B, A) as planned.

Raw data (in the worktree): run 1 `target/checks/b11-ranks/run1/{summary.txt,samples.csv}`, log
`target/checks/20261102-110214-23108.log` (it stopped after the by-rate timings on a benchmark
bug in WCn's SQL; the file list and juror rows are run 2's); run 2
`target/checks/b11-ranks/run2/{summary.txt,samples.csv,analyze.json.txt}`, log
`target/checks/20261102-112302-8200.log`. A first attempt (`…-105343-9556.log`) stopped on a page
0 of an empty list, a benchmark bug. No specs (no app code changed).

### B11 phase 2: K implemented

After B9 (`ukwikidev/followups` at `b641c78d`, merged as `9bb607a6`; the plan file's conflict
kept both sections). Commit `aaad3662`.

- `RoundList.ranked` (a file list, or by rate in any order but file order), `rankCounts()`
  (`query.keyCounts(rankKey.columns)`) and `ranks(files, counts)`; `ranks(files)` reads the
  counts itself.
- `GalleryController.galleryOf`: a lazy `rankCounts` for a ranked list; the pager's total from
  the juror's counts, else the sum of `rankCounts`, else `list.count()`; the file list's and the
  by-rate gallery's ranks from the same counts. A page view of all the jurors' votes runs
  `keyCounts` once and no `COUNT(DISTINCT s.page_id)`.
- `RankKey.of` for a rated round: the file's rates summed as they are (`selection.map(_.rate).sum`),
  not `rateSum` (rates below 0 as 0), so the key read in memory is SQL's `sum(s.rate)`. Before,
  a file with a rate below 0 got no rank in the by-rate gallery if its sum was below 0, and in the
  file list (whose rows have each vote) the rank of its positive rates' sum, or none. The fixture has no such
  votes; the new spec has.
- `RankQueryBenchmark`: `groupedReader(q.tally)` for B9's reader; it compiles (`Test/compile`).
- `keyCounts` stays (the ranks, now the totals too).

Specs (new): `RoundListDbSpec` "a ranked list (a file list, by rate)": which lists are ranked;
the sum of the rank counts equals `count()` for a rated round (all the jurors and each juror;
each filter and `Rate(5)`; no region, regions 12 and 14, an empty region 99; by rate and file
list: 128 lists) and a yes/no round (each `BinaryOrder` and filter, the unrated empty; by rate
and file list), and in two regions through the monument table; a rated round with rates below 0
(sums 6, 5, 3, 2, 2, -1) ranked "1", "2", "3", "4-5", "4-5", "6" over pages of 2, a page past
the end empty, in the by-rate gallery and the file list; a yes/no round's last page ranked "4"
in each order and the page past it empty. `AllJurorsListSpec`: the by-rate gallery's page 2 of
20 files says "16–20 of 20" ranked "11-20." in a binary order, and "16–20 of 20" in file order
(its count). Checks: `Test/compile`, `Gatling/compile`, `testOnly controllers.{RoundListDbSpec,
RoundListSpec, AllJurorsListSpec, CsvSpec, GalleryOrderSpec, OwnVotesSpec, GalleryRegionsSpec,
CoiAuthorizationSpec, RegionMenuSpec} org.intracer.wmua.RankSpec db.scalikejdbc.ImageDbNewDbSpec
views.{FileListViewSpec, GalleryByRateViewSpec}`: 172 passed, 0 failed
(`target/checks/20261102-124103-27020.log`).

**Pilot.** `GalleryLatencyBenchmark` as B8's (`-Dbench.cases=^organizer-(binary|rated)-byrate-p1$
-Dbench.warmup=1 -Dbench.samples=15`, fork `-Xmx1024m -Dgatling.timing=true`, SQL logging off),
order A, B, B, A; A the merged base `9bb607a6` in a detached worktree (its own fixture dump;
removed afterwards; the benchmark's one-line `groupedReader` fix applied there uncommitted, as
its tests didn't compile without it), B `aaad3662`. Client times, n = 30 a cell; paired deltas B
run k minus A run k, sample by sample, and per block of 5.

| Page | A median (min-max) | B median (min-max) | Paired delta, median (min-max) | B faster | Block median deltas |
|---|---|---|---|---|---|
| by-rate, round 2 (rated), all jurors | 1136 ms (1032-1512) | 1005 ms (912-1804) | **-122 ms** (-514 to +707) | 25 of 30 | -89, -119, -133, +25, -153, -130 |
| by-rate, round 1 (yes/no), all jurors | 803 ms (711-2289) | 667 ms (612-977) | **-134 ms** (-1588 to +231) | 27 of 30 | -147, +34, -127, -204, -399, -131 |

The server's timing log: B runs no `COUNT(DISTINCT s.page_id)` (A's median 130-146 ms rated,
133-152 ms binary); the grouped page (830-850 rated, 444-477 binary) and `keyCounts` (113-132,
160-166) are as they were; all the page's queries 1082-1129 → 969-1001 ms rated, 743-816 →
615-634 ms binary. The per-run medians drifted (A2 was the slowest run of each page), less than
the delta; the pilot settles it and was not expanded. Raw: `target/checks/b11-pilot/`
(`{A1,B1,B2,A2}-{client.csv,timing.jsonl}`, `summary.txt`, `b11-pilot-summary.json`); logs
`target/checks/20261102-125306-6136.log`, `…-125524-17100.log` (B), A's in the removed worktree.
