# Light / dark / auto modes and a consistent design system

Status: investigation and plan, nothing implemented yet.

## Why pages are white or black today

The page colour doesn't depend on whether a page is an admin page or an image page. It depends on a
`gallery` flag passed to the layout, and the layout differs from page to page:

| Shell | Pages | Background | Container | Bootstrap / jQuery |
|---|---|---|---|---|
| `main.scala.html` + `gallery = true` → `body.gallery` (#303030) | gallery, galleryByRate, contestFiles | dark | `container-fluid` + inline margins | 3.3.7 / 3.2.1 |
| `main.scala.html`, `gallery = false` | fileList, roundStat, monuments, galleryByRateRegions, accessDenied, errorPage | white | `container-fluid` (fileList adds a nested `.container`) | 3.3.7 / 3.2.1 |
| `mainAdmin.scala.html` | contests, rounds, editRound, users, editUser, importUsers, contest_images, login, signUp, logout, greetingTemplate, errorPage | white | `.container` (fixed width) | 3.3.7 / 3.2.1, plus Font Awesome 4.6.3 |
| `large/main_large.scala.html` | large (voting) view | dark, `body.gallery` | inline styles | **3.3.1 / 1.11.1** |
| `large/contestFile.scala.html` (its own full page) | contest file's large view | dark, `body.gallery` | inline styles | **3.3.1 / 1.11.1** |

The four shells each copy their own `<head>`. The navbar is `navbar-inverse` (dark) on every page,
including white ones. Bootstrap's pagination and dropdowns stay white on the dark pages (see
`wlmua-2026.png`: the white pager and page box on the contest files page). Dark pages get light
text from page-specific overrides: the 30 lines of `.contest-files-head` in `main.css`,
`style="color: white"` in `large/navigationButtons.scala.html`, and `.table-striped-dark`.

Bootstrap 3 has no colour modes, so the only way to add dark mode on top of it is to override every
component (forms, dropdowns, tables, pagination, panels, wells, alerts, tabs) for each mode. Bootstrap
5.3 has this built in (`data-bs-theme="light|dark"` on `<html>`, with every component using CSS
variables).

## Other design inconsistencies found

1. **Four page shells** with copied `<head>`s and two Bootstrap/jQuery versions (above).
2. **Third-party CDNs** (maxcdn.bootstrapcdn.com, ajax.googleapis.com, cdnjs) send every juror's IP
   address to those hosts, which Wikimedia-related tools usually avoid. The CSP whitelists all
   three. `build.sbt` already declares the `bootstrap 3.3.7` and `jquery 3.2.1` webjars but doesn't
   use them (the `/webjars` route is commented out).
3. **A missing icon:** the navbar's view menu shows CSV with `fa fa-file-text-o`, but Font Awesome
   loads only in `mainAdmin`. That menu appears on gallery pages, which use `main`, so the icon is
   probably blank there. Also, `dropDownSelection.scala.html` builds the current item's class as
   `glyphicon glyphicon-@iconClass` while the icons are already full class strings, so the result
   is `glyphicon glyphicon-glyphicon glyphicon-th`. It works only by accident, and its `iconOnly`
   parameter is never used.
4. **Two icon sets plus PNGs:** Glyphicons in 13 templates (Bootstrap 4+ dropped them), Font Awesome
   4 in 2, and PNGs for the thumbs up/down, stars (raty) and loaders (both `loader-black*` and
   `loader-white*` variants exist).
5. **`main.css` loads before Bootstrap,** so when a rule and Bootstrap's have the same specificity,
   Bootstrap wins. `body { font-family: helvetica; color: #333; background: #CCC }` never applies,
   and the pager's `.disabled` rule needs a specificity workaround (its comment says so).
6. **Dead CSS** in `main.css`: chat styles copied from a Play sample (`#textField`, `.juliet`,
   `#header`, `#footer`, `.msg`, `.others`, `#roomSelect`), a commented-out `.selected`, `zoom: 1`
   and `-moz-inline-box` hacks, and a copied `.bs-callout` set used once.
7. **Hardcoded colours in markup:** admin tables have `style="background-color: #CCCCCC"` (users,
   rounds, contests), which makes grey tables on white pages. Four places repeat Bootstrap's
   `alert-danger` colours inline on an element that already has `class="alert alert-danger"`
   (formError, rounds, editRound, contest_images). There is `style="color: red"` for invalid
   accounts, and `#e0e0e0` for failed thumbnails, which would glare in dark mode.
8. **82 inline `style` attributes,** most of them in the large views (layout heights and margins).
9. **Old navbar markup:** `.navbar-inner` and `<a class="brand">` come from Bootstrap 2, `<li>`
   elements aren't closed, and signed out, there is an `<a>` directly inside the `<ul>`.
10. **Two form styles:** play-bootstrap `b3.*` helpers (35 uses in 7 templates) next to hand-written
    forms and the custom `views/bootstrap/{text,password}`.
11. **No button hierarchy:** 37 `btn-default` and 17 `btn-primary` with no rule for which action is
    primary. "Add user" is a `<div class="btn btn-primary" onClick=…>`, which the keyboard can't
    reach.
12. **Titles:** `main` localises the title (`@Messages(title)`), `mainAdmin` prints it as given.
13. **Width:** admin pages are fixed width, gallery-shell pages are full width, and some tables
    sit on each.

## Options for a design system

| | Bootstrap 5.3 | Tabler (1.x) | Wikimedia Codex (2.x) |
|---|---|---|---|
| What it is | The current version of the framework we already use | An admin UI kit built on Bootstrap 5.3: the same markup and JS with its own styling and Tabler Icons (MIT) | Wikimedia's design system: Vue 3 components, a CSS-only subset and design tokens |
| Light/dark/auto | Built in: `data-bs-theme` on `<html>`, every component follows | Same mechanism (`data-bs-theme`) | Dark tokens since 1.5 (`theme-wikimedia-ui-mode-dark.css`), documented for `prefers-color-scheme` only. An explicit toggle means applying the token file under our own attribute |
| Fits our pages | Navbar, dropdowns, grid, pagination, forms, tables, tabs, modals | All of Bootstrap, plus admin-style cards, tables and page headers | CSS-only components (buttons, inputs, selects, checkboxes, messages, cards, tabs, tables) but no grid or navbar, and menus, dialogs and lookups need Vue |
| Migration cost from BS3 | Medium: class renames, `data-bs-*`, panels/wells → cards, glyphicons → icons, forms | BS5 cost plus a stylesheet swap and some extra markup for its look | High: rewrite every template, write our own layout/navbar, and either add a Vue build or keep jQuery menus styled by hand |
| Delivery | Webjar (`org.webjars.npm:bootstrap`), self-hosted | npm/jsDelivr; a webjar exists for some versions, otherwise vendor the dist | npm only for the CSS, so vendor the dist or use a webjar |
| Look | Generic | Polished admin dashboard | The same as Commons, which jurors know |

**Recommendation: migrate to Bootstrap 5.3 and use its colour modes. Decide on Tabler afterwards,
and borrow Codex's colours rather than its components.**

- Bootstrap 5.3 gives light/dark/auto with the least new code and keeps our jQuery parts
  (unitegallery, raty) and Twirl templates.
- Tabler uses the same markup as Bootstrap 5.3, so we don't need to choose it now. After the
  migration, try its CSS on four pages (gallery, large view, rounds, editRound) and keep it only if
  it clearly looks better.
- Codex is the closest fit for a Wikimedia tool in look and feel, but without Vue it gives buttons
  and form fields only, not the navbar, menus or layout we need. For a Wikimedia feel at little
  cost, set Bootstrap 5.3's CSS variables (`--bs-primary`, `--bs-danger`, `--bs-body-bg`, …) for each
  mode to Codex token values (progressive blue, destructive red, base backgrounds). That is a small
  `theme.css` and needs no Sass build.

## Plan

### Phase 1: one page shell (no visible change)

- Add `views/layout.scala.html` with the shared `<head>` (charset, viewport, favicon, CSS, JS) and
  body slots. Make `main`, `mainAdmin`, `main_large` and `large/contestFile` wrap it. The large views
  move to the same Bootstrap/jQuery versions as the rest.
- Load `main.css` after the framework. Remove the workarounds and dead rules that were only needed
  because of the load order (items 5 and 6).
- Self-host jQuery and Bootstrap from webjars (enable the `/webjars` route) and remove the CDNs from
  the CSP. If Phase 2 follows directly, self-host Bootstrap 5.3 at that point instead of 3.3.7.
- Localise titles the same way in every shell (item 12).

### Phase 2: Bootstrap 3 → 5.3

- Markup: `data-toggle` → `data-bs-toggle`, `col-xs-*` → `col-*`, `panel`/`well` → `card`,
  `btn-default` → `btn-secondary`/`btn-outline-secondary`, `pull-right` → `float-end`, Bootstrap 4
  form markup (`form-group` → spacing utilities, `control-label` → `form-label`, `help-block` →
  `form-text`), `navbar-inverse` → `navbar` with `data-bs-theme`, the `caret` span → the toggle's
  built-in caret.
- Rewrite `navbar.scala.html`, `logged.scala.html`, `dropDownSelection.scala.html`,
  `pageLinks.scala.html` and `pagerNav.scala.html` in Bootstrap 5 markup, fixing item 9 and the
  icon class bug (item 3).
- Forms: replace play-bootstrap `b3.*` with our own `bs` helpers (see "Form helpers" below). This
  also unifies the two form styles (item 10).
- Icons: Bootstrap Icons (MIT, an SVG icon font, self-hosted) replace Glyphicons and Font Awesome:
  one set (item 4).
- jQuery plugins: `bootstrap-multiselect` (rounds form: previous rounds, regions, jurors) and
  `bootstrap-typeahead` + `autocomplete.js` need Bootstrap 5-compatible versions or a replacement.
  Tom Select covers both, has a Bootstrap 5 theme and follows `data-bs-theme`. Keep jQuery 3.7 for
  unitegallery and raty.
- Tests to update: `ContestFilesPageSpec` (`btn-default`, `contest-files-head`), `SecuredSpec`
  (`btn-default`) and `LargeViewUrlsSpec` (`glyphicon-chevron-*`). Assert on ids or data attributes
  instead of styling classes where we can.

### Phase 3: light / dark / auto

- `<html data-bs-theme="…">` on every page, rendered from a `theme` cookie (`light`, `dark` or
  `auto`; default `auto`). For `auto`, a 5-line inline script at the top of `<head>` resolves
  `prefers-color-scheme` before the first paint and listens for changes. The CSP already allows
  inline scripts.
- The preference is stored in a `users.theme` column (V71: `light`, `dark` or `auto`, nullable,
  where null means auto) and in the cookie, the same way as `lang`. `UserController` sets the cookie
  when the profile is saved, and login sets it from the column, so the preference follows the user
  to other devices. A three-way switch in the user menu (`logged.scala.html`) is a CSRF POST that
  saves both. The login page has a switch that sets only the cookie.
- `public/stylesheets/theme.css`: the app's own variables for each mode: `--wlx-image-bg` (the
  background behind photos), `--wlx-thumb-failed-bg`, `--wlx-selected` / `--wlx-rejected` (the
  navbar's vote tabs, today hardcoded green/red), plus the Codex-based `--bs-*` values if we use them.
  Replace `body.gallery`, `.table-striped-dark`, `.contest-files-head`, the `#CCCCCC` tables and the
  inline colours (item 7) with these variables or Bootstrap's own.
- Image pages follow the mode, as asked. In light mode, photos sit on a neutral light grey rather
  than pure white, so colours aren't judged against glare. The loaders use the `loader-black` or
  `loader-white` variant for the mode (or a CSS spinner, `spinner-border`). Unitegallery's tile
  overlay and raty's PNG stars work on both backgrounds and need checking only.
- Tests: a view spec that each shell renders `data-bs-theme` from the cookie (light, dark, and
  auto without a cookie), and one that no page refers to an external CDN. Check visually with
  screenshots of each page type in both modes (gallery, byrate, file list, large view, contest
  files, round stat, rounds, editRound, users, login, error page).

### Phase 4: remaining cleanup (can be done gradually)

- Move inline `style` attributes to classes, starting with the large views (item 8).
- Button rule: one `btn-primary` per form or page for its main action, `btn-outline-secondary` for
  the others, `btn-danger` only for destructive actions. Make "Add user" a real `<button>`
  (item 11).
- One width rule: full width for galleries and the large view, `container-xxl` for admin pages and
  tables (item 13).

## Form helpers

play-bootstrap has Bootstrap 3 and Bootstrap 4 builds only, for Play 2.6 to 2.8, and no sign of
Bootstrap 5 work. We already run its Play 2.8 build (`1.6.1-P28-B3`) on Play 3. So we write our own
helpers instead of porting the library. We use only a small part of it: 35 calls in 7 templates,
`text`, `password`, `textarea`, `select` and `checkbox`, the arguments `_label`, `_help`,
`_hiddenLabel`, `placeholder`, `class` and `rows`, and three layouts (vertical; horizontal with
`col-md-4`/`col-md-8` in editRound, imageFiltering and greetingTemplate; inline in login and signUp,
used only to hide the labels).

- `app/views/bs/`: `text`, `password`, `textarea`, `select`, `checkbox` and `form`, plus a small
  `Layout` value (vertical by default, or horizontal with the label and field column classes)
  passed implicitly, as play-bootstrap's field constructor is. They keep play-bootstrap's argument
  names, so a call site changes mostly from `b3.` to `bs.`.
- Each one renders Bootstrap 5 markup directly: `form-label`, `form-control` / `form-select`, `form-check`
  with the input before its label for checkboxes, `form-text` for help, `is-invalid` +
  `invalid-feedback` with the field's localised errors, `visually-hidden` for `_hiddenLabel`. Any
  other argument without an underscore becomes an HTML attribute on the input. `select` takes the
  option list and handles multiple values (`field.indexes`) as Play's `helper.select` does.
- They replace the hand-written `views/bootstrap/{text,password}` (editUser, users), which use
  Bootstrap 3's `has-error` and an `ion-@icon` class for Ionicons, which isn't loaded anywhere.
- A unit spec renders each helper with a field with and without errors, and checks the label's
  `for`, the error text, `is-invalid`, the help text and that extra attributes reach the input.

## Decisions

1. Image pages follow the mode, as everything else does.
2. Bootstrap 5.3. Try Tabler on a few pages after the migration, and borrow Codex's colours as an
   option.
3. The preference is stored in `users.theme` and a cookie.

## Running the phases with agents

### Setup

- **Branch:** `ukwikidev/theme-modes`, based on `codex/round-redistribution-filters` at `f9c31ce5`,
  not on `master`. This branch is 464 commits ahead of `master` and changes 63 view, CSS and JS
  files, including the round form that Phase 2 rewrites.
- **Worktree:** one worktree for all phases, `C:/scala/wlxjury-theme`, created once by the
  orchestrator (`git worktree add`). Phases run one after another on the same branch, so the
  Agent tool's per-agent `isolation: worktree` doesn't fit. In the worktree, run `npm ci` in
  `javascript-test/` or let `sbt test` do it. Never link `node_modules` or `data/cache` to the main
  tree.
- **Commits:** agents commit each step on the branch with the attribution line. They don't push or
  open a PR. That is left to the user.
- **Checks:** always through `python scripts/agent-check.py -- …` with the direct sbt launcher from
  Git Bash (see CLAUDE.md), and `Test/compile` before any test run. Jest runs with `npm test` in
  `javascript-test/`.
- **Screenshots:** before Phase 1 changes anything, the Phase 1 agent adds a small
  `test/gatling/setup` entry point that starts `GatlingTestFixture`'s TestServer on the cached
  fixture DB (Docker). It also adds a script that screenshots a fixed list of pages through the
  browser: gallery, byrate, file list, large view, contest files, contest file's large view, round
  stat, rounds, editRound, users, editUser, login, error page. They are saved to
  `target/ui-shots/<phase>/<mode>/`. Phase 1 compares them with a baseline taken at `f9c31ce5`. Later
  phases take them in both modes.

### Agents and models

| Role | Agent | Model / effort | Gets |
|---|---|---|---|
| Implementer, one per phase | a fresh general-purpose agent working in the worktree | Opus 5.5, medium effort. Phase 3: high effort (a migration, a cookie written into HTML, a CSRF POST) | this plan, the phase's section, the invariants below, the previous phases' results |
| Reviewer, one per phase | a fresh agent (not a fork, so that it doesn't share the implementer's reasoning) | Opus 5.5, medium effort. Phase 3: high effort | `git diff <phase base>..HEAD`, the invariants, the implementer's check summary (commands, exit codes, test totals) and the screenshot directory. It doesn't run tests unless a specific doubt needs it |
| Fix | the same implementer, continued with `SendMessage` so it keeps its context | as above | the reviewer's confirmed findings only |
| Orchestrator (this session) | — | — | checks each report against the branch (the diff stat, the check logs under `target/checks/`, the screenshots), decides the next step, asks the user when needed |

### The cycle for each phase

1. The implementer does the phase in small commits. It finishes with `Test/compile`, the phase's
   specs and Jest, then the screenshots. It reports changed files, commands with exit codes, test
   totals, screenshot paths and open questions.
2. The orchestrator checks the report against the branch.
3. The reviewer reviews the phase's diff and lists findings ranked by severity, each with a concrete
   failure (file and line, what goes wrong). A finding is a defect or a broken invariant, not a
   style preference.
4. The implementer fixes the confirmed findings and reruns the affected checks.
5. A second, last review only if the fixes were material (behaviour, security, markup that
   several pages share). Otherwise the phase is done.
6. Whatever is still open after two reviews, and any choice the plan doesn't settle (for example
   Tom Select versus an updated bootstrap-multiselect, if Tom Select doesn't fit), goes to the user.
   Agents don't decide it.
7. The implementer adds a short "Phase N results" section to this plan (checks, review findings and
   how they were resolved, what remains), and commits it.

### Order and what can run in parallel

| Step | Content | Runs | Checks |
|---|---|---|---|
| 1 | Phase 1: shared layout, self-hosted assets, CSS order, dead CSS, screenshot harness | first | `Test/compile`; `views.*`, `controllers.ContestFilesPageSpec`, `controllers.SecuredSpec`, `controllers.ErrorHandler*`; Jest; screenshots identical to the baseline (only expected differences) |
| 2a | `bs` form helpers and their unit spec (new files only, no call sites changed) | at the same time as step 1, in a second worktree `C:/scala/wlxjury-theme-forms`, merged into the branch after step 1's review | `Test/compile`; the helpers' spec |
| 2b | Phase 2: Bootstrap 5.3 for all templates, navbar/menus/pager, Bootstrap Icons, form call sites to `bs`, Tom Select, JS and Jest fixtures for Bootstrap 5 dropdowns (`show.bs.dropdown` now fires on the toggle and bubbles; `region-menu.js` and `pager-jump.js` listen for it; their tests build Bootstrap 3 markup) | after 1 and 2a | `Test/compile`; all `views.*` and `controllers.*` specs that render pages; Jest; screenshots in light mode compared with step 1's |
| 3 | Phase 3: `theme.css`, `data-bs-theme`, V71 `users.theme`, the switch in the user menu and on the login page | after 2b | as 2b, plus the user and login specs, the new theme specs, the DB specs (V71 on a fresh DB); screenshots in both modes; **the full suite once** (the release boundary) |
| 4 | Phase 4: inline styles, buttons, widths | after 3 | `Test/compile`; the specs for the templates it changes; screenshots in both modes; the full suite again only if it changes shared layout or JS |

That makes about 5 implementer runs and 5 reviews, with up to 5 follow-up reviews.

### Invariants that every reviewer checks

- **Votes and CSRF:** votes, sign-out, contest switching and the new theme switch are POSTs with the
  CSRF token (`csrfField`, or the `Csrf-Token` header from `data-csrf-token` for gallery votes).
  The large view's keys vote by path, never by `http://` URL (`LargeViewControllerSpec`).
- **The theme value:** it comes from a cookie or the DB and is matched against `light`, `dark` and
  `auto`. Anything else counts as `auto`. It is never written into the HTML as given. The theme
  POST redirects only to a path that passes `Secured.safeLocalPath`, it doesn't switch the contest,
  and it changes only the signed-in user's own row. The login page's switch is client-side only
  (it sets the cookie) and sends no request.
- **The cookie:** `SameSite=Lax`, `Secure` behind HTTPS as the session cookie is, not needed by
  the server for authorisation.
- **No external hosts:** no page refers to an external CDN. The CSP lists `'self'` for scripts,
  styles and fonts.
- **Errors:** error pages show message keys only, and a missing resource is a 404 (CLAUDE.md
  "Errors").
- **Unchanged behaviour:** galleries' vote buttons, the region menu, the pager's page box, the
  round form's dependent fields (previous rounds → filters, jurors → distribution), keyboard
  navigation in the large view and failed or deleted thumbnails all still work. The screenshots and
  the existing specs cover them.
- **Tests:** they assert on ids, data attributes and text, not on styling classes, except where a
  class is the behaviour (`is-invalid`, `active`).

## Phase 1 results

Branch `ukwikidev/theme-modes`, commits `92859c75..` on top of `2ac84622` (the plan). Not yet
reviewed.

### Screenshot harness

- `test/gatling/setup/UiShotsServer.scala`: starts `GatlingTestFixture` (MariaDB testcontainer
  restored from `data/cache/`, the app in a TestServer), writes `target/ui-shots/server.json`
  (base URL, contest and round ids, a juror with files in both rounds, the organizer, the
  juror's first page id in each round) and serves until `target/ui-shots/server.stop` exists.
  The test classpath has no `public/` assets, so the run adds the packaged assets jar
  (`set Test / unmanagedClasspath += Attributed.blank((Assets / packageBin).value)`); the server
  refuses to start without them.
- `scripts/ui-shots.py` (Playwright 1.48 for Python 3.8 + Pillow, in a venv under
  `target/ui-venv`, system Chrome, no browser download): `shoot --label L --start-server` runs
  the server through the sbt launcher, signs in through the login form as the juror (gallery,
  file list, large views, 404) or the organizer (byrate, contest files and file, round stat,
  rounds, editRound, users, editUser), signed out for login and the anonymous 404, and saves
  `target/ui-shots/<label>/light/<page>.png` (1366 px wide, full page up to 4000 px). Commons
  images, and the fixture's URL-less `/<width>px-` images, get one fixed placeholder; other
  external hosts are allowed only for the old CDNs and listed. `diff A B` writes red-marked diff
  images to `target/ui-shots/diff/A-vs-B/light/`.
- Two baseline runs at `f9c31ce5` were pixel-identical (14 of 14), so any difference is real.
- In the fixture, `/admin/rounds` and `/admin/users` without `?contestId=` deny the contest's
  admin (403, `permission_check_failed`, `targetContestId=unknown`) although the session's
  contest is set; the navbar links pass the id, so the harness does too. Not investigated
  further (outside this phase).

### What changed

- `app/views/layout.scala.html`: the one `<head>` (charset, X-UA-Compatible, viewport, title,
  favicon, Bootstrap 3.3.7 CSS, Font Awesome 4.6.3 CSS, `main.css`, jQuery 3.2.1, Bootstrap JS,
  then the shell's own `head` block) and the body (class, optional full height). `main`,
  `mainAdmin`, `large/main_large` and `large/contestFile` wrap it; each keeps its own scripts
  (unitegallery and its init, multiselect and its init, typeahead, raty, keyboard nav, vote form).
  Plugin CSS (unite-gallery, multiselect, raty) still loads after `main.css`, as before.
- Self-hosting: `/webjars/*file` → `controllers.Assets.at(path="/META-INF/resources/webjars")`,
  the existing Bootstrap and jQuery webjars plus `org.webjars:font-awesome:4.6.3`. The CSP's
  `default-src` is now `'self' 'unsafe-eval' 'unsafe-inline'` (the three CDN hosts removed; no
  other config in the repo sets a CSP). Font Awesome now loads on `main` pages too (the navbar's
  CSV icon, item 3).
- The large views moved from Bootstrap 3.3.1 / jQuery 1.11.1 to 3.3.7 / 3.2.1. raty 2.7.1,
  jquery.hotkeys and checkwidth.js work on jQuery 3 (Jest and the rated large view's shot).
- `main.css` loads after Bootstrap (item 5). Removed: the `body` rule and the list margins that
  Bootstrap always overrode (lists keep only `padding: 0`), the pager's `li.disabled` workaround,
  the chat sample (`.others`, `.juliet`, `#textField`, `#userField`, `#header`, `#footer`,
  `#roomSelect`), unused rules (`div.img-container`, `.undo`, `.table-striped-dark`, the nested
  `.active .active-selected/-rejected` rules, which never matched, the `.bs-callout` variants
  other than `-info`), the commented `.selected`, `zoom: 1` and `-moz-inline-box` (item 6).
  Equal-specificity ties between `main.css` and Bootstrap were listed with a script (tinycss2 +
  cssselect specificity); the only one whose winner flips on a real element is
  `.contest-files-head a` against Bootstrap's `a:hover/a:focus`, kept as it was with
  `:where(.contest-files-head) a:hover` (Bootstrap's specificity). Note for Phase 3: the plan
  lists `.table-striped-dark` as something to replace; it was unused and is gone.
- Titles (item 12): `mainAdmin` localises a message key as `main` does; its 12 callers and
  `errorPage` pass keys, `roundStat` stopped localising twice. `main_large` keeps
  `Messages(list.view.name)`, the contest file its file name.
- Tests: `views.LayoutSpec` (no external `src`/`href`, every webjar file on the classpath and
  routed, `main.css` after Bootstrap and before the shell's head, titles localised once, the
  CSP's `default-src` has no host). `AssetDigestSpec`'s guard accepts `Assets.at` only for a
  webjar path with a version. Jest: jQuery 3.2.1 (as the pages load), the large-view fixtures
  regenerated (`FIXTURES_UPDATE=true … views.LargeViewFixturesSpec`), and `loadPage` waits for
  jQuery 3's asynchronous ready handlers (otherwise a page's raty init ran in the next test).

### Checks

| Command | Exit | Result |
|---|---|---|
| `Test/compile` | 0 | |
| `testOnly views.* controllers.ContestFilesPageSpec controllers.SecuredSpec ErrorHandlerSpec controllers.AssetDigestSpec controllers.LargeImageSpec controllers.LoginControllerSpec controllers.MissingResourceSpec controllers.MutationAuthorizationSpec controllers.RoundEditPageSpec controllers.SignOutAndSwitchSpec controllers.UserControllerSpec controllers.PagerSpec controllers.RegionMenuSpec controllers.ImportFilesPageSpec controllers.CategoryFormSpec controllers.OwnVotesSpec controllers.CsvSpec` | 0 | 252 passed, 0 failed |
| `npm test` (javascript-test) | 0 | 7 suites, 100 tests passed |

The full suite wasn't run (not required for this phase).

### Screenshots

`target/ui-shots/baseline/light/` (at `f9c31ce5`) against `target/ui-shots/phase1/light/`: 12 of
15 pages identical, and no external host requested in phase 1. The three that differ are the
large views' button groups, from Bootstrap 3.3.1 → 3.3.7:

| Page | Pixels | Difference |
|---|---|---|
| `large` | 178, box (26,19)–(138,30) | glyphicons in the back/up/down/vote buttons drawn about 1 px apart (3.3.7's glyphicon font) |
| `large-rated` | 137, box (26,19)–(105,30) | the same |
| `contestfile-large` | 1066, box (26,10)–(105,40) | the same, and the disabled "previous" button (`active disabled`) a shade darker (3.3.7's disabled button colours) |

### Open questions

- `unitegallery.js` loads mediaelement from cdnjs for HTML5 video in its lightbox; the tiles
  theme we use doesn't open it, but if it ever did, the CSP now blocks it.
- The webjars are served with the plain-name cache time (an hour); their paths carry the
  version, so a longer `Cache-Control` would be safe if wanted.
