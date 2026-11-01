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

### Review follow-ups

- `/webjars/*file` goes to `controllers.WebJars`, which serves only the webjars in
  `WebJars.Served` (bootstrap, jquery, font-awesome; Phase 2 adds its own there) and answers 404
  for any other (Tapir's Swagger UI is on the classpath), also through `../` or encoded paths.
  `play.assets.cache."/META-INF/resources/webjars/" = "public, max-age=31536000, immutable"`
  (Play 3.0.7 matches it as a prefix of `Assets.at`'s resource name, in every mode).
  `controllers.WebJarsSpec` checks both; `LayoutSpec` now checks the rendered `main`,
  `mainAdmin` and both large views for scripts or stylesheets from another host; the
  screenshot script blocks the old CDN hosts. `Test/compile` 0; `testOnly views.LayoutSpec
  controllers.AssetDigestSpec controllers.WebJarsSpec` 0 (14 passed); `views.*` 0 (45 passed);
  screenshots again 15 of 15 identical to `phase1`, no request to another host.

## Step 2a results (form helpers)

- Branch `ukwikidev/theme-forms` (commits `c07b4740`, `e3d8c8c8`, `41b8550c`, `5d452200`), merged into
  `ukwikidev/theme-modes`. New files only: `app/views/bs/` (`Layout`, `FieldView`, `text`, `password`,
  `textarea`, `select`, `checkbox`, `form` and the internal `inputType`, `fieldGroup`, `feedback`),
  `test/views/BsFormHelpersSpec.scala`, `test/views/bstest/horizontalField.scala.html`.
- Checks: `Test/compile` ok; `testOnly views.BsFormHelpersSpec` 30 passed, 0 failed.
- Review: no XSS path, and no change to what current call sites submit. Six findings were fixed:
  constraint attributes (`required`, `min`, `max`, `minlength`, `maxlength`, `pattern`) taken from
  the field's constraints as b3 did; `aria-invalid`; `readonly` + `disabled`; the readonly
  single-select fallback; `placeholder` translation; a caller's `aria-describedby` merged. A second
  review found nothing material.
- Decided: no `input-lg` → `form-control-lg` conversion. Phase 2 changes the login and sign-up call
  sites to `form-control-lg`.
- One intended difference from b3: a readonly single select whose value is not among its options
  submits the first option, or the `_default` blank. Nothing saved changes, because editing a round
  saves only its name, `excludeCoi` and `newImages`.
- For Phase 2: run `RoundEditPageSpec` and `PrevRoundHiddenInputSpec` after the switch (a readonly
  multiple select now submits one `name[]` per selected value). The templates compile into
  `views.html.bs`, which play-bootstrap-core's package object shares until play-bootstrap is
  removed.

## Phase 2 results (Bootstrap 5.3)

Commits `69204d7e..` on `ukwikidev/theme-modes` (after `c878a724`). Not yet reviewed.

### What changed

- **Assets:** Bootstrap 5.3.8 (`bootstrap.bundle.min.js`, Popper included; the webjar's
  `popper.js` dependency excluded), Bootstrap Icons 1.13.1, Tom Select 2.6.2 (complete build,
  its `@orchidjs` webjars excluded; `tom-select.bootstrap5.min.css`) and jQuery 3.7.1 as
  webjars, served by `controllers.WebJars` (`Served` = bootstrap, bootstrap-icons, jquery,
  tom-select). Removed: the Bootstrap 3 webjar, Font Awesome, `com.adrianhurt play-bootstrap`,
  `views/bootstrap/{text,password}`, `bootstrap-multiselect` (js, css),
  `bootstrap-typeahead.js`, `autocomplete.js`, `checkwidth.js`.
- **Colour mode until Phase 3:** `layout(theme = "light" | "dark")` renders
  `<html data-bs-theme>` (anything but `"dark"` is light). `main`/`mainAdmin` with
  `gallery = true` and both large views pass dark; `body.gallery` keeps #303030 (also as
  `--bs-body-bg`). The navbar is `bg-dark` with its own `data-bs-theme="dark"`. The 30 lines of
  `.contest-files-head` overrides and the dropdown-form button rules are gone (BS5's dark mode
  and `dropdown-item` cover them).
- **Navbar and menus (items 3 and 9):** `navbar navbar-expand` with `nav-item`/`nav-link`,
  closed `<li>`s, no `.navbar-inner`/`a.brand`; signed out, a `navbar-brand` span. The own-vote
  tabs are `nav-item vote-selected|vote-rejected` with `nav-link active` + `aria-current`
  (green/red and the dark active background kept in `main.css`). `dropDownSelection`: one
  `bi bi-*` class string, `iconOnly` shows the icon with a visually hidden label and a title,
  the current item `active`. `logged`: `dropdown-menu-end`; the contest switch and sign-out
  stay CSRF POST forms whose buttons are `dropdown-item`s. Pager: `page-item`/`page-link`,
  `pagination-sm`, the page box a `data-bs-auto-close="outside"` dropdown. `regionMenu`:
  `dropdown-item`s; `regionNav` as BS5 pagination (unused: only `monuments` calls it, with no
  regions).
- **Dropdown events:** Bootstrap 5.3's `EventHandler.trigger` triggers a jQuery event on the
  toggle when `window.jQuery` is present (unless `data-bs-no-jquery`), then dispatches a native
  bubbling `show.bs.dropdown`; a jQuery `.on('show.bs.dropdown')` binds the type `show`, so it
  sees only the jQuery one. `region-menu.js` now uses `menu.addEventListener('show.bs.dropdown')`
  and `pager-jump.js` a delegated native `shown.bs.dropdown` listener on `document`
  (`dropdown-menu-end` instead of `dropdown-menu-right`; in a navbar BS5 places dropdowns
  statically, without Popper, so the fit check stays). Neither needs jQuery any more.
- **Forms:** every `b3.*` call, and the Play `inputText`/`select`/`textarea` helpers, now call
  `bs.*` (horizontal via `@implicitLayout`); `bootstrap.text` → `bs.text(f, '_label -> label,
  'placeholder -> label)`; login/sign-up use `form-control-lg` and a `d-grid` button.
  Hand-written form markup: `mb-3`, `form-label`, `d-inline` (start/stop, whose button also
  lacked the `btn` class).
- **Tom Select (`public/javascripts/multi-select.js`):** `#previousRound`, `#regions`, `#jurors`
  (and the monuments page's region select) get checkboxes (`checkbox_options`), a "select all"
  checkbox in a `dropdown_header`, no typing (`controlInput: null`, like the old button), and a
  summary in the control computed as bootstrap-multiselect's `buttonText` (none selected; up to
  three names; "4 Selected"; "All selected (5)"), from the same message keys. The original
  `<select>` stays the field; Tom Select fires its native `change`, so the existing jQuery
  handlers in `mainAdmin` (previous rounds → filter blocks, jurors → distribution) are
  unchanged. A disabled select stays disabled. Fixed on the way: jQuery 3 returns `[]` for an
  empty multiple select, so the jurors handler now checks `jurors.length`.
- **The typeahead was not ported:** `autocomplete.js` queried `commons.wikimedia.org` from the
  browser (XHR and JSONP), which the CSP (`default-src 'self'`, before Phase 1 too) already
  blocked, so it had done nothing. Deleted; see open questions.
- **Other templates:** panels/wells → `card`, `label label-*` → `badge text-bg-*`,
  `btn-default` → `btn-outline-secondary`, `btn-xs` → `btn-sm` (`py-0 px-1` in the round stat
  tables), `col-xs-*` → `col-*` (the large views' main column is `col`, which replaces
  `checkwidth.js`), `data-bs-*`, `collapse show`, `btn-close` (new message key `close` in the
  four languages), `table-sm`, glyphicons/Font Awesome → Bootstrap Icons, the add-user
  button's image from upload.wikimedia.org → `bi-person-plus-fill`, the large views' buttons
  `btn-secondary`/`btn-success`/`btn-danger` with `data-nav="gallery|up|down"` and
  `aria-disabled`, the deleted-file mark in `unitegallery.js` a `badge text-bg-danger`, the
  round stat page's round links a BS5 pagination.
- **Tests:** `LayoutSpec` (served files; colour mode, including an unexpected value),
  `WebJarsSpec` (the new files, the icon font and Tom Select), `ContestFilesPageSpec`,
  `SecuredSpec`, `LargeViewUrlsSpec` (`data-nav`), `GalleryOrderSpec`, `RegionMenuSpec` now
  assert ids, data attributes, text or `active`. Jest: `region-menu` and `pager-jump` open the
  menus through Bootstrap 5's bundle (npm `bootstrap@5.3.8`), also without jQuery; new
  `multi-select.test.js` (npm `tom-select@2.6.2`); jQuery 3.7.1; large-view fixtures
  regenerated.
- **Screenshot harness:** `pages()` entries may list selectors to click before the shot; added
  `gallery-pagerbox`, `gallery-usermenu`, `byrate-viewmenu`, `contestfiles-regionmenu`,
  `editround-new`, `editround-new-jurors`, `editround-new-prevround`.

### Checks

| Command | Exit | Result |
|---|---|---|
| `Test/compile` + `testOnly views.BsFormHelpersSpec` at `c878a724` | 0 | 30 passed |
| `FIXTURES_UPDATE=true … testOnly views.LargeViewFixturesSpec` | 0 | 4 passed, fixtures rewritten |
| `testOnly views.* controllers.*` (first) | 1 | 467 total, 3 failed: `GalleryOrderSpec`, `ContestFilesPageSpec`, `RegionMenuSpec` still asserted BS3 link markup; fixed |
| `testOnly controllers.GalleryOrderSpec controllers.ContestFilesPageSpec controllers.RegionMenuSpec controllers.RoundEditPageSpec controllers.PrevRoundHiddenInputSpec` | 0 | 56 passed |
| `Test/compile` + `testOnly views.* controllers.*` (final) | 0 | 467 passed, 0 failed |
| `npm test` (javascript-test) | 0 | 8 suites, 110 tests passed |

The full suite wasn't run (not required for this phase).

### Screenshots

`target/ui-shots/phase2/light/` (22 shots, no request to another host) against
`phase1-followup` (`target/ui-shots/diff/phase1-followup-vs-phase2/light/`): all 15 common
pages differ, as expected. Looked at every one; fixed during the pass: the round stat buttons
(too large, underlined spaces between them inside their links), the large view's comment header
text (light on grey), the users page's no-account note (`text-warning` too pale). The opened
menus (pager box, user menu, view menu, region menu) and the new round form's Tom Selects
(jurors, previous rounds) render correctly.

Intended differences: Bootstrap 5's typography (system font stack, larger headings, underlined
links), the BS5 dark navbar and its dark dropdowns, the pager and region menu dark on the dark
pages (they were white), horizontal form labels left-aligned and not bold, checkboxes with
their label to the right, the large views' buttons flat grey instead of BS3's pressed white,
the `#CCCCCC` admin tables now white (BS5 paints cell backgrounds; Phase 3 removes the inline
colour anyway), the users table's add row with visible labels (per the `bootstrap.text`
mapping), the current round on the round stat page shown active instead of bold.

### Open questions

- The Commons category autocomplete (contests' and contest images' "source") was dead under
  the CSP and is now removed. Reviving it needs a same-origin endpoint proxying Commons'
  prefixsearch (Tom Select's `load` could use it); not done.
- Commit hygiene: `69204d7e` also took deletions staged earlier (views/bootstrap, multiselect,
  typeahead, autocomplete, checkwidth), so `69204d7e` and `6ecfc299` don't compile on their own
  (editUser/users still call `views.html.bootstrap.text` until `96a89630`). HEAD compiles and
  passes; history was not rewritten.
- Pre-existing, unchanged: messages inside `'…'` JS strings in `mainAdmin`/`monuments` are
  HTML-escaped by Twirl (an apostrophe would show as `&#x27;`).

### Review follow-ups

- **CSP (`6fcc9d9e`):** production `img-src` now allows `data:` (Bootstrap 5.3's and Tom Select's
  select arrows, checkbox ticks and `btn-close` are data: SVGs; the tests' config has no CSP, so
  only production would have lost them). `views.ContentSecurityPolicySpec` reads the policy from
  `conf/application.conf`: `default-src` names no host and no `script-src`/`style-src`/`font-src`
  widens it, `img-src` has `'self'` and `data:` (it replaces LayoutSpec's CSP example). The
  harness's `--csp` (with `--start-server`) runs the server with that policy
  (`-Dui.csp=production` → `GatlingTestFixture.cspConfig`), reports a page without the header and
  logs console errors, CSP violations included. `target/ui-shots/phase2-csp/light/`: 22 of 22
  identical to `phase2`, arrows and ticks drawn (the Tom Select and editround shots), no CSP
  violation in the console (the only console errors are the two 404 pages' own status). No shot
  shows a `btn-close` (it appears only after a vote moves a file); it uses the same data: mechanism.
- **Translations (`517553bf`):** a script over the Messages keys on the lines Phase 2 added found,
  besides keys meant as literal text (`Contests`, `Name`, `Reset password`): missing in ru `add`,
  `select.all`, `none.selected`, `all.selected`, `cancel`, `check`, `full.name`, `id.template`,
  `image.list.help`, `images`, `language`, `reset.password`, `role`, `round.name.optional`, `save`,
  `send.greeting.to.new.users`, `source`, `wiki.account`, `year`; missing in all four `sort` and
  `signin.repeat.your.password`. All added. messages.ru still lacks 62 more keys used by the
  admin pages Phase 2 touched (on unchanged lines); a pre-existing gap, not filled here. The
  add-user control is a `<button type="button">` with `aria-label`, submitting the form by name as
  before.
- **Script texts (`5ad491c8`):** `views.JsTexts` writes the multi-select and distribution texts
  as a JSON object (`<` escaped) instead of HTML-escaped `'…'` literals (`JsTextsSpec`).
- Checks: `Test/compile` 0; `testOnly views.JsTextsSpec views.ContentSecurityPolicySpec
  views.LayoutSpec controllers.WebJarsSpec controllers.UserControllerSpec
  controllers.RoundEditPageSpec controllers.PrevRoundHiddenInputSpec` 0 (28 passed);
  `testOnly controllers.CoiAuthorizationSpec controllers.MultiContestUserSpec
  controllers.SignOutAndSwitchSpec` 0 (39 passed). Jest not rerun (multi-select.js unchanged).

## Phase 3 results (light / dark / auto)

Commits `0272bfc9..` on `ukwikidev/theme-modes` (after `1ddf679e`). Not yet reviewed.

### What changed

- **Mode resolution (`app/controllers/Theme.scala`):** `Theme` = `Light | Dark | Auto`
  (`value`, a Bootstrap Icons `icon`); `Theme.parse` takes exactly `light`, `dark` and `auto`,
  anything else (cookie, DB, form) is `Auto`; `Theme.of(request)` reads the `theme` cookie.
  `ThemeCookies` (injected `SessionConfiguration`) writes it: `SameSite=Lax`, `Secure` when
  `play.http.session.secure` is, path `/`, a year, not HttpOnly. `LoginController` and
  `UserController` take it as a third constructor argument with a default
  (`ThemeCookies.default`), so the specs that build them with `new` still compile.
- **Every shell:** `layout.scala.html` has no `theme` parameter any more; it renders
  `<html data-bs-theme="light|dark" data-theme-pref="light|dark">` for an explicit mode, and for
  auto `data-theme-pref="auto"` without `data-bs-theme`. Right after `<meta charset>`, before
  any stylesheet, a 5-line inline script sets `data-bs-theme` from
  `matchMedia('(prefers-color-scheme: dark)')` while `data-theme-pref` is `auto` and follows its
  `change` event (rendered on every page; it does nothing for an explicit mode). Only the fixed
  strings reach the page. The gallery/large-view forced dark and the navbar's own dark are gone:
  the navbar is `bg-body-tertiary border-bottom`.
- **`public/stylesheets/theme.css`** (after Bootstrap, before `main.css`): per mode under
  `:root, [data-bs-theme=light]` / `[data-bs-theme=dark]`: `--wlx-image-bg` (#e3e3e3 / #303030),
  `--wlx-thumb-failed-bg` and `-mark`, `--wlx-current-bg` (the large view's current thumbnail),
  `--wlx-deleted`, `--wlx-selected` / `--wlx-rejected` (#146c43 / #b02a37 light, #75b798 /
  #ea868f dark, at least 4.5:1 on the navbar), `--wlx-nav-active-bg`. `main.css` has no hex
  colour left: `body.gallery`, the vote tabs, the active tab, `.current`, the comments (`.msg`,
  `.comment-header`, `.imageComment` now on `--bs-*` colours), `.bs-callout-info`, the failed and
  deleted marks. Templates: the `#CCCCCC` table backgrounds (contests, rounds, users) and the four
  inline `alert-danger` colours removed, `style="color: red"` → `text-danger`, the white rank
  texts (galleryByRate, galleryByRateRegions, showRate) inherit the body colour. The gallery vote
  frames (`ug-theme-tiles.js`) use `var(--wlx-selected)`, `var(--wlx-rejected)` and
  `var(--bs-secondary-color)` instead of limegreen / brown / white; the gallery init sets
  `tile_enable_background: false` (unitegallery wrote an inline `#F0F0F0`, which also beat
  main.css's failed-thumbnail grey). Loaders: the only one shown, unitegallery's tiles preloader
  (a dark grey spinner), is inverted in dark mode by CSS; the `loader-black/white` GIFs belong to
  the slider and lightbox, which the tiles theme doesn't open. Vendor CSS untouched.
- **Storage:** `V71__Users_add_theme.sql` adds `users.theme VARCHAR(8) NULL`. Decided: all three
  values are stored as chosen, NULL means never chosen (auto); signing in sets the cookie from a
  non-NULL value (`auto` too) and leaves the browser's cookie alone for NULL. `User.theme` (last
  field, default None) is read and written by the mapper and `create`; `User.updateTheme(id,
  Theme)` writes only one of the three values.
- **Profile (`editUser`):** a `bs.select` of the three modes on the user's own profile only (an
  empty column preselects the browser's current mode). `UserController.updateUser` stores it and
  sets the cookie only for the own profile and only for one of the three values; another user's
  form has no field, and an admin saving it changes neither that user's mode nor the admin's
  cookie.
- **Switch in the user menu (`logged.scala.html`):** a POST form to `/theme`
  (`UserController.setTheme`, `withAuth(_ => true)`: any signed-in user, root or without a
  contest) with `csrfField`, a hidden `returnTo` (a GET's own URI through
  `Secured.safeLocalPath`) and three submit buttons as dropdown items, the current one `active`
  with `aria-pressed`. The action stores the mode in the session user's row only, sets the
  cookie, and redirects to `returnTo` if it passes `safeLocalPath`, else to the start page. An
  invalid or missing value: a redirect, nothing stored, no cookie. It never touches the session
  (no contest switch). On an error page the form has no `returnTo` (`main(backHere = false)` from
  `errorPage`), because the error URL may carry text that must not appear in the page
  (`LoginControllerSpec`'s "never the link's text" caught it); the switch then goes to the start
  page.
- **Login page switch:** a `btn-group` of three `type="button"` buttons (`data-theme-value`) and
  `public/javascripts/theme-switch.js`: it sets the cookie in the browser (`Path=/`, `Max-Age` a
  year, `SameSite=Lax`, `Secure` on an https page, since the browser can't see the server's
  session config), `data-theme-pref` and `data-bs-theme` at once, and marks the pressed button.
  It sends no request.
- **Messages:** `theme`, `theme.light`, `theme.dark`, `theme.auto` (System / Système / Як у
  системі / Как в системе) in en, fr, uk, ru. Fixed ru `all.selected = Выбраны все` and
  `full.name = Полное имя` (`11df6a66`).
- **Screenshot harness:** `--mode light,dark,auto` in one server run (light/dark: the `theme`
  cookie in the browser context; auto: no cookie and Playwright's `color_scheme` = `--scheme`,
  dark by default); each shot prints and checks the `data-bs-theme`/`data-theme-pref` it got. New
  shots: `users-usermenu`, `contestfiles-marks`, `contestfile-large-marks` (a script marks tiles
  and thumbnails deleted or failed, which the fixture has none of).

### Checks

| Command | Exit | Result |
|---|---|---|
| `Test/compile` after each code commit | 0 | |
| `testOnly views.ThemeViewSpec views.LayoutSpec controllers.ThemeSwitchSpec db.scalikejdbc.UserDbSpec` (first) | 1 | 32 total: 3 mistakes in the new spec (DB read before awaiting a result, a GET without a CSRF token, a per-test app not truncated); fixed |
| the same (final) | 0 | 32 passed |
| `testOnly views.* controllers.* db.scalikejdbc.*` | 1 | 683 total, 5 failed: `LargeViewFixturesSpec` ×4 (fixtures stale after the `<head>` change; regenerated), `LoginControllerSpec` "never the link's text" (the theme form's `returnTo` on the error page; fixed, see above) |
| `testOnly views.ThemeViewSpec controllers.LoginControllerSpec` | 0 | 23 passed |
| `FIXTURES_UPDATE=true … testOnly views.LargeViewFixturesSpec` | 0 | 4 passed, fixtures rewritten (the head script, `data-theme-pref`, `theme.css`) |
| `npm test` (javascript-test) | 0 | 9 suites, 119 tests (new `theme-switch.test.js`: the login switch and the layout's auto script, read from the template) |
| **full suite**: `Test/compile` + `test` | 0 | **1119 total, 0 failed, 0 errors** (1113 passed, 6 skipped, 1 pending); Jest within it 119 passed |

The DB specs ran V71 on the shared Testcontainer's fresh database. The screenshot server
rebuilt the Gatling fixture cache (`data/cache/`, keyed by the migrations) because of V71.

### Screenshots

**Incomplete.** `shoot --label phase3 --mode light,dark,auto --start-server --csp` was stopped by
Claude Code while the system was low on memory (sbt, the server JVM, MariaDB and Chrome at
once), after three light shots (`target/ui-shots/phase3/light/login.png`, `error404-anon.png`,
`gallery.png`). It was not restarted. Those three look right: the light navbar with readable
vote tabs (selected green, rejected red, "All" active on a grey background), the pager, the
photos on the light grey, the login page's switch with Light pressed. No dark or auto shot was
taken, so the dark mode's contrast (tables, forms, dropdowns, Tom Select, the large view's
buttons, raty stars, comments, thumbs bar, the marks) and a no-flash check in a real browser are
still to do. The script order (the auto script first in `<head>`, before every `<link>`) is
checked by `ThemeViewSpec` for every shell.

### Open questions

- Screenshots in dark and auto (and the rest of light) need a run when memory allows.
- Where the switch lives: in the user menu (after Profile, before Sign out) as three menu items,
  and under the login form as a small button group; the profile page has the select too. Not on
  the sign-up page or the signed-out error pages.
- The login page's script marks the cookie `Secure` by the page's scheme, not by the server's
  session config; the server-set cookie follows the config.
- `auto` is stored explicitly (not as NULL): choosing System on one device makes signing in on
  another use System too. NULL only for users who never chose.

### Review follow-ups

- **By-rate galleries (high, `5843872f`):** `1e026ddb` had replaced the rank block's opening
  `<div style="margin-bottom: 10px;` in `galleryByRate` and `galleryByRateRegions` with a U+0001
  control character (a script's `` replacement written as a literal byte; it was the `□`
  glyph) and a stray `">`, so the block's `</div>` closed the gallery list after the first tile.
  The block is `<div class="rank-line">` again (margin in `main.css`; `showRate` too). No other
  file in the repo contains U+0001, and the commit's other template edits only dropped colour
  attributes. `views.GalleryByRateViewSpec` renders both templates for a rated round and checks
  with Jsoup that every `li.gallerybox` sits in `ul.gallery` with one `div.rank-line`, and that
  there is no stray `">` text or control character.
- **Theme on user creation (low, `36783bec`):** the edit form's `theme` keeps only light, dark or
  auto (anything else becomes none), and `User.create` stores nothing else, so an admin-created
  user with a bogus value gets `theme` NULL instead of a 500 (`ThemeSwitchSpec`). Decided: drop
  the value, no form error (the add-user form has no theme field to show one on).
- Checks: `Test/compile` + `testOnly views.GalleryByRateViewSpec controllers.ThemeSwitchSpec
  views.ThemeViewSpec db.scalikejdbc.UserDbSpec controllers.UserControllerSpec
  controllers.MultiContestUserSpec controllers.GalleryOrderSpec` 0 (60 passed). The full suite was
  not rerun.
- Screenshots: `shoot --label phase3-fix --mode light,dark --start-server --csp` (no other JVM
  running), 25 shots per mode in `target/ui-shots/phase3-fix/`, no CSP violation, every page in
  the mode asked for; the only console errors are the two 404 pages' own status. `byrate` and
  `byrate-viewmenu` are a regular three-column grid in both modes, each tile with its stars and
  rate below it and no stray text. The fixture's all-jurors by-rate view has no rank, so the
  rank text itself is empty there.

## Phase 4 results (cleanup)

Commits `4760b4b7..` on `ukwikidev/theme-modes` (after `7b9c0776`). Not yet reviewed.

### What changed

- **Inline styles (item 8):** 51 `style="..."` attributes in `app/views` before, 6 after. The
  large views' layout is `main.css` classes (`.large-view`, `.large-side` = the 220px column,
  `.large-main`, `.large-nav`, `.large-box` / `.large-line` for the side column's boxes and
  lines) and Bootstrap utilities (`h-100`, `mw-100 mh-100` for the image, `d-none` for the
  vote form, `me-1`, `mt-1`, `my-1 mx-auto`); `layout` puts `h-100` on `<html>` and `<body>`
  instead of `height: 100%`. Elsewhere: `d-inline`, `d-none` (editRound's hidden jurors
  select, editUser's hidden reset form; no script shows either), `text-center`, `.rate-stars`,
  `.tile-anchor`, `.form-action-wide` (the 25% Save/Cancel), `mb-2`. `main`'s `margin: 0` on
  `container-fluid` was a no-op and is gone; `pageLinks`' unused `style` parameter is gone.
  **Kept, on purpose:** the two `#gallery` `display:none` (unitegallery builds from the hidden
  list; `ContestFilesPageSpec` checks it), `jobStatus`' two forms (`job-status.js` toggles
  `style.display`, its Jest test reads it), `#prevRoundFilters` (jQuery `show()`/`hide()`,
  which `d-none`'s `!important` would defeat), and the pager box's width computed from the
  page count (`GalleryOrderSpec`). Margins of 5px became 4px utilities in three places
  (`mt-1`, `me-1`, `my-1`), not visible; 10px ones kept their value in classes.
- **Stars:** raty uses `starType: 'i'` with Bootstrap Icons (`bi-star-fill`, `bi-star-half`,
  `bi-star`, cancel `bi-x-circle` / `bi-x-circle-fill`), set once as raty defaults in
  `large/raty.scala.html`; the per-star image options and the images path are gone, the vote
  callbacks (`voteLarge(prefix + score + query)`) are unchanged. New theme variable
  `--wlx-star` (#a36a00 light: 4.5:1 on white, 3.5:1 on the image grey; #ffc107 dark: 8:1 on
  the image grey); `.wlx-star` is 1rem, so ten stars and the cancel icon fit the side column
  on one line (`.large-stars { white-space: nowrap }`); a round with more than ten stars
  (1-11 .. 1-20 without half stars) gets `.many-stars` (0.6875rem). The cancel icon is the
  secondary text colour, red while hovered. `showStar` renders the `<i>` itself (full / half /
  empty, the same arithmetic as before) for `showRate` and both by-rate galleries. Removed:
  the ten `star-*`/`cancel-*` PNGs and `jquery.raty.css` (its raty font files were never in
  the repo). `AssetDigestSpec`'s `images/star.png` is its own temp file, not the asset.
- **Buttons (item 11):** the large views' navigation buttons `btn-secondary` →
  `btn-outline-secondary` (select/reject keep green/red: vote semantics); contests' import and
  add were `<div class="btn btn-primary" onClick>` → real buttons (import a submit, add a
  `type="button"` submitting the form by name, as add-user does, since that form can't wrap a
  table row); rounds' "Add round" was a link around a div whose `onClick` submitted a form
  that isn't on the page → `<a class="btn btn-primary">`; users' "Mass import" and "Edit
  greeting message" (primary divs inside links) → `a.btn-outline-secondary`; contest images'
  "Update monuments" (a second primary in the import form) → outline-secondary; the greeting
  template's "Check" `btn-info` → outline-secondary; start/stop of a round or juror
  `btn-danger`/`btn-success` → `btn-outline-secondary` (stopping is reversible, not
  destructive; the icon and the Yes/No text show the state); `snippets/button` was a
  `<button>` inside an `<a>` → one `a.btn`. Left as they are: one primary per form (sign in,
  sign up, sign out, save, import, add user, submit comment, the pager's Go, access denied's
  suggestion, the error page's sign in); the by-rate galleries' green check buttons of a 1-2
  round (they show a rate, see open questions). No JS builds button markup.
- **Widths (item 13):** `mainAdmin`'s content is `container-xxl` (full width up to 1400px,
  then 1320px) for every admin page. Forms keep their own column widths (`col-md-4`,
  `col-md-6 offset-md-3`, the horizontal layout's columns), so `container-lg` wasn't needed to
  keep them readable, and one container class keeps the rule simple. Galleries, by-rate, file
  list, contest files, round statistics and both large views stay full width; fileList's
  nested `.container` is gone. A table placed directly in a `.row` (contests, rounds, users,
  round statistics) sat at the page's edge (the row's negative margin; Bootstrap's gutter
  padding doesn't apply to a collapsed-border table): `.row > .table` gets the gutter as
  margin, so it lines up with the heading above it.
- **Small fixes:** rounds' first `.row` was closed only when there were rounds; the unused
  `tabbedSelection.scala.html` is deleted.

### Checks

| Command | Exit | Result |
|---|---|---|
| `Test/compile` | 0 | |
| `FIXTURES_UPDATE=true … testOnly views.LargeViewFixturesSpec` | 0 | 4 passed; the four large-view fixtures rewritten (classes, raty options, no `jquery.raty.css`) |
| `npm test` (javascript-test) | 0 | 9 suites, 124 tests (5 new in `large-view-keys.test.js`: raty draws five `bi-star` icons and the cancel icon, no `<img>`; clicking stars 1, 3, 5 posts the vote form with that score; the cancel icon posts 0) |
| `testOnly views.* controllers.*` | 1 | 493 total, 1 failed: `ContestFilesPageSpec` looked for `<body class="gallery"` (now `gallery h-100`); assertion updated |
| `testOnly controllers.ContestFilesPageSpec` | 0 | 26 passed |
| **full suite**: `Test/compile` + `test` (branch gate) | 0 | **1122 total, 0 failed, 0 errors** (1116 passed, 6 skipped, 1 pending); Jest within it 124 passed |

The `.row > .table` rule (CSS only, added after the full suite) was checked by screenshots,
not by a test run.

### Screenshots

`shoot --label phase4 --mode light,dark --start-server --csp` (no other JVM), 25 shots per
mode, every page in the mode asked for, no CSP violation; the only console errors are the two
404 pages' own status. `diff phase3-fix phase4`: 18 of 25 differ in each mode, all explained:

- `large`, `large-rated`, `contestfile-large(-marks)`: the outlined navigation buttons;
  `large-rated`: ten icon stars and the cancel icon on one line in both modes (were two lines
  of PNGs, the "off" ones solid white in dark); the thumbnails start higher by the saved line.
- `byrate`, `byrate-viewmenu`: icon stars (amber on the light grey, yellow on the dark), the
  "off" ones outlined. The rank line is narrower than with the old 24px PNGs, so a tile is as
  wide as its thumbnail and the grid has four columns instead of three (page 1320 → 1061px).
- `users(-usermenu)`, `rounds`, `editround*`, `edituser`, `login`, `error404-anon`
  (mainAdmin): full width at 1366px (`container-xxl`) instead of 1140px; tables aligned with
  their headings; users' two outlined buttons; rounds' outlined start/stop; forms keep their
  column widths (editround's help text wraps less, so `editround-new-prevround` is 24px
  shorter).
- `roundstat`: the table inside the page's gutter; outlined start/stop.
- `filelist`: full width without the nested container.
- identical: `gallery*`, `contestfiles*`, `error404`.

### Open questions

- Icon-only buttons without a text label: start/stop (rounds, round statistics) has no
  `title` or `aria-label`; adding them needs message keys.
- The by-rate galleries of a 1-2 round show the rate as green `<button>`s that do nothing; an
  icon would be more honest, but it is a rate display, left alone here.
- `galleryByRateRegions` draws `rates.id / 2` stars whatever the half-star setting, and
  `galleryByRate` uses `numberOfStars`, which multiplies by the criteria count; both predate
  this phase.
- Page titles: admin pages use `h3`, the error page `h4`, galleries none; one level (e.g.
  `h1.h3`) would be a template-wide change.
- fileList's content (inside its `.row`) starts at the page's edge, and it prints its HTML
  comment line (`<!-- Round … -->`) as visible text in the `contenteditable` block meant for
  copying into a wiki page; both unchanged.
