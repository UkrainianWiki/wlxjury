# UI, i18n and accessibility

How pages are built and checked: messages and translations, scripts under the CSP, the theme
modes and design system, accessible names and keyboard use, the screenshot harness, supported
browsers, and the vendored gallery. Each rule has a short why and an example. The security side
of pages (CSP, raw HTML sinks, cookies) is [secure-coding.md](../security/secure-coding.md) §5,
§6, §8; how UI tests assert is [testing/practices.md](../testing/practices.md) §6. **Sources:**
the theme-modes plan (`98a8a79e:docs/plans/2026-10-31-theme-modes-and-design-system.md`: design
system, phases, invariants); secure-coding §5, §8; performance §1.7; `scripts/ui-shots.py`,
`scripts/ui_checks.py`; CLAUDE.md "Keyboard voting"; `views.MessagesCompletenessSpec`.

## Contents

1. [Messages and translations](#1-messages-and-translations)
2. [Scripts under the CSP](#2-scripts-under-the-csp)
3. [Theme modes and the design system](#3-theme-modes-and-the-design-system)
4. [Accessibility and the keyboard](#4-accessibility-and-the-keyboard)
5. [The screenshot harness](#5-the-screenshot-harness)
6. [Supported browsers](#6-supported-browsers)
7. [The vendored gallery](#7-the-vendored-gallery)
8. [Guards](#8-guards)
9. [Where the code doesn't follow these yet (for triage)](#9-where-the-code-doesnt-follow-these-yet-for-triage)

## 1. Messages and translations

**1.1 Every text a user sees is a message key.** Templates and controllers use
`Messages("key")`; error pages show keys only, never request or exception text (secure-coding
§12). Scripts get their texts from the page (`JsTexts`, JSON-escaped, `JsTextsSpec`), not from
literals in `public/javascripts/`.

**1.2 In all four files, with the same placeholders.** A new key goes into `conf/messages.en`,
`.fr`, `.uk` and `.ru` in the same commit, with the same `{0}`, `{1}` placeholders;
`MessagesCompletenessSpec` fails otherwise (testing §6.4). *Why:* a missing key shows the raw key (or English) on that language's pages.

**1.3 Keys, not free text, for things organizers name in the app's vocabulary** (criteria
names are keys, `2026-11-02-criteria-rounds.md`).

**1.4 Translations: two channels.** Interface messages (the message keys) are to be translated on
translatewiki (planned; today the four files are edited in the repository). Long user-facing
pages (the planned user guides, [docs/README.md](../README.md) §5) are written in English in the
repository and reviewed with the code; a release step publishes them to a wiki page and marks it
for translation, and translations happen there with MediaWiki's Translate extension. The wiki is
Commons, where the jury tools' pages already are (the user, 2026-11-03); nothing is published until
the user decides. Repository docs, user docs included, are English only.

## 2. Scripts under the CSP

**2.1 No inline code.** No inline `<script>`, `on*=` attribute, `javascript:` URL or inline style
added; scripts are files under `public/javascripts/` that read `data-*` attributes (`data-vote-url`,
`data-csrf-token`, `data-regions-url`). The two hashed inline scripts (the theme script, the large
view's restore) are the only exceptions. Everything is served from this host: webjars or files
under `public/`, never a CDN (secure-coding §5).

**2.2 Write text, not HTML.** Scripts set `textContent` or attributes; a raw HTML sink only in a
listed file (secure-coding §8, `RawHtmlSinksSpec`).

**2.3 Votes and POSTs carry the CSRF token** (`csrfField`, or `Csrf-Token` from `data-csrf-token`);
a form marked `data-submit-once` is sent once (`submit-once.js`) (secure-coding §11).

## 3. Theme modes and the design system

**3.1 Three modes.** `light`, `dark`, `auto` from the `theme` cookie (and `users.theme`, V71,
copied to the cookie at sign-in); `Theme.parse` accepts exactly those, and no cookie or anything
else is `Theme.Default`, `dark`; only the fixed strings reach the page. `layout.scala.html` renders
`<html data-bs-theme=… data-theme-pref=…>`; for `auto` the hashed inline script resolves
`prefers-color-scheme` before the first paint. The switch in the user menu is a CSRF POST to
`/theme`; the login page's switch only sets the cookie. *Why:* no flash of the wrong mode, and a
cookie value is never written into HTML as given (secure-coding §6).

**3.2 Colours are variables.** Bootstrap 5.3's `--bs-*` and the app's own `--wlx-*` in
`public/stylesheets/theme.css` (`--wlx-image-bg`, `--wlx-selected`/`--wlx-rejected`,
`--wlx-nav-active-bg`, …), per mode; `main.css` has no hex colour, templates no inline colour. Text
on the navbar's vote tabs has at least 4.5:1 contrast in both modes. In light mode photos sit on a
neutral grey, not white. *Why:* one place per colour and mode; photos are judged without glare.

**3.3 One set of form helpers.** `app/views/bs/` (`text`, `password`, `textarea`, `select`,
`checkbox`, `form`, with `Layout`) render Bootstrap 5 markup: the label's `for`, `is-invalid` with
`invalid-feedback`, `form-text` help, `visually-hidden` for `_hiddenLabel` (`BsFormHelpersSpec`).
New forms use them, not hand-written markup.

**3.4 Buttons and widths.** One `btn-primary` per form or page for its main action,
`btn-outline-secondary` for the others, `btn-danger` only for destructive actions; real
`<button>`s for actions. Galleries and the large view use the full width; admin pages and tables
`container-xxl`.

**3.5 Unchanged behaviour is part of a UI change.** Gallery vote buttons, the region menu, the
pager's page box, the round form's dependent fields, the large view's keys and failed or deleted
thumbnails still work after any restyling; the screenshots (§5) and the specs show it.

## 4. Accessibility and the keyboard

**4.1 Every control has an accessible name.** An icon-only button or link has `aria-label` or
`visually-hidden` text, from a message key; a form field has a `<label for>` (the `bs` helpers);
a toggle shows its state (`aria-pressed` on the current theme). Tests find controls by role and
name (testing §6.1). *Why:* a screen reader announces an unnamed icon as "button".

**4.2 One `h1` per page,** its title: visible on admin and error pages, `visually-hidden` where the
page shows none (a gallery, the large view) (`HeadingsViewSpec`).

**4.3 The large view is keyboard-first, and votes by path.** Keys vote and move (`large-keys.js`
reads its paths from `#large-keys`' `data-vote-key-*` and `data-key-*`: Space/Del to select or
reject, digits to rate; Up/Down, Page Up/Down, Home/End and Esc to move); each vote posts to a
path on this host, never an `http://` URL. *Why:* `Call.absoluteURL` behind the HTTPS proxy
redirected the POST to a GET and lost the vote (`LargeViewControllerSpec`; CLAUDE.md "Keyboard
voting"). Back after a vote shows the stored vote (pages are `no-store`; `ui_checks.py`).

## 5. The screenshot harness

**5.1 Shoot before and after a visible change.** `scripts/ui-shots.py shoot --label <name>
--start-server` (Python with Playwright 1.48.0 and Pillow, the installed Chrome or Edge) runs
`gatling.setup.UiShotsServer` on the Gatling fixture and shoots a fixed list of pages per mode
(`--mode light,dark,auto`, `--scheme` for auto); `diff <a> <b>` writes diff images. Commons and the
image proxy's URLs get one placeholder image; any other host is blocked and listed. `--csp` runs
the production CSP; `requests.json` lists each page's scripts and stylesheets. Command and logins:
performance §1.7.

**5.2 Browser checks.** `--checks` runs `ui_checks.py` after the shots: a vote by key in the large
view of both rounds, then Back, shows the stored vote; the large view's side sections are drawn in
their stored state from the first frame. The browser tests (testing §10) run these checks, and
more, in one current build of each engine on every PR, except the side sections' first frame:
they enforce the restore by DOMContentLoaded, and the first-frame check is `fixme` there (U18).
`--checks` stays as the harness's quick check in Chrome.

**5.3 Every release:** the manual UI checks (the `auto` screenshots looked at, Back after a vote
in Safari, category suggestions against live Commons) are in the
[release checklist](../deployment.md#release-checklist), with what the browser tests cover.

## 6. Supported browsers

**6.1 Desktop** (the user, 2026-11-03): supported are the current and previous major versions of
Chromium-based browsers (Chrome, Edge, Opera), Firefox and Safari (WebKit). A major is the first
version number: Chromium and Firefox ship one every four weeks, Safari once a year with the OS.
*Tested in a browser* is less: the browser tests (testing §10) run one current build of each
engine on every PR, the one Playwright 1.64.1 bundles (Chromium 156, Firefox 157, WebKit 27.2).
The previous majors are *linted* only (`.browserslistrc`, the compatibility lint: our scripts'
APIs and the CSS features caniuse lists), never run. A visible change is still looked at by hand in at
least Chromium and Firefox (§5). *Why:* the engines the browser tests run.

**6.1a Compatibility floor: Chrome 109 and Firefox 115.** Signing in, voting and the core pages
must keep working there (cosmetic issues aren't fixed): no JavaScript or CSS feature newer than
those versions. *Checked by:* the compatibility lint only (`.browserslistrc` includes them;
`npm --prefix javascript-test run lint`, a step of `ci.yml`'s job), which sees our scripts' browser
APIs, their syntax (ES2022) and the CSS features caniuse lists (not every property: `text-wrap:
balance` isn't flagged), not how a page renders or behaves there, nor a feature a fallback
covers. The inline scripts in templates aren't linted (three today, all trivial: the layout's
theme script and the two large views' section restore, `InlineScriptHashesSpec`); the lint fails on its own
fixtures (`npm --prefix javascript-test run lint:selftest`), and reports a needless disable. Nothing runs in these versions, and nothing is planned to
(the user, 2026-11-04): a difference there is found by users or by hand. *Why:* in a 14-day sample of production's signed-in visitors (2026-11-03, 105
client and browser pairs) about a fifth used old browsers: Firefox 115 ESR, Chrome 109 (the last
for Windows 7/8) and old Opera releases. Rerun the count each contest season and raise the floor as
they drop out: `ssh <server> 'python3 -' < scripts/browser-stats.py` (read-only; prints counts only,
no addresses or user agents; the last 14 days, `'python3 - 30'` for another window, every major
version seen, and a warning when the logs don't reach back that far).

**6.2 Mobile: not supported yet.** Pages render on phones but are not designed or tested for
them. Mobile support is queued in the roadmap ("Mobile support (not supported yet)"), and the
browser tests' mobile profiles come with it. Write "not supported yet", never "broken", in issues and docs.

## 7. The vendored gallery

`public/javascripts/unitegallery.js` (24,670 lines) and `ug-theme-tiles.js` are vendored and
patched (`wlxjury:` comments: escaping of titles and attributes, `.on("load")` for jQuery 3,
failed-thumbnail and deleted-file marks, the theme lookup); `jquery.hotkeys.js` and `jquery.raty.js`
are vendored too. A change to a vendored file is marked `wlxjury:` with its reason, and comes
with a test in `unitegallery-patches.test.js` that fails when the change is reverted. That file
runs the gallery in jsdom on tiles built as the templates build them; `unitegallery-alt.test.js`
also tests the thumbnail's alt. Every patch (Unite Gallery 1.7.28, marked and formerly unmarked),
its commits and its test are listed in [docs/vendor/unitegallery.md](../vendor/unitegallery.md).
The files stay outside Jest coverage (testing §9.1). **Decision (user, 2026-11-04):** our own
tiles will replace the gallery later, planned with a UI developer with the other frontend
changes after the API is prepared; the patched copy stays until then
([investigation](../investigations/2026-11-04-gallery-keep-or-replace.md)).

## 8. Guards

| Rule | Enforced by | State |
|---|---|---|
| 1.2 messages in all four files, same placeholders | `views.MessagesCompletenessSpec` | enforced |
| 1.1 script texts escaped | `views.JsTextsSpec` | enforced |
| 2.1 no inline scripts, hashes, CSP | `views.NoInlineScriptsSpec`, `InlineScriptHashesSpec`, `ContentSecurityPolicySpec`, `security.SecurityHeadersSpec` | enforced |
| 2.1 frameworks from this host | `views.LayoutSpec` | enforced |
| 2.2 raw HTML sinks listed | `security.RawHtmlSinksSpec` | enforced |
| 3.1 theme value parsed; switch | `ThemeViewSpec`, `ThemeSwitchSpec`, `theme-switch.test.js` | enforced |
| 3.3 form helpers | `BsFormHelpersSpec` | enforced |
| 4.2 one h1 | `views.HeadingsViewSpec` | enforced |
| 4.3 keys vote by path | `views.LargeViewControllerSpec`, `large-view-keys.test.js` | enforced |
| 4.1 accessible names | axe-core per page in the browser tests' tour (`pages.spec.js`, Chromium) | enforced in CI once the job runs, for serious and critical findings not triaged for that page (U19–U21); the rest a report; not yet a required check |
| 5 zero CSP violations and console errors | the browser tests' guards (`browser-test/lib/fixtures.js`), every page of every test | enforced in CI once the job runs; not yet a required check |
| 6.1 one current build per engine, in a browser | `browser.yml`: a job per engine on every PR | enforced in CI once the job runs; not yet a required check |
| 6.1, 6.1a the supported range: previous majors, Chrome 109, Firefox 115 | the compatibility lint (`.browserslistrc`; `eslint-plugin-compat`, `stylelint-no-unsupported-browser-features`), a step of `ci.yml`'s job | enforced for our scripts' APIs and syntax and the CSS features caniuse lists; not run in those browsers |

## 9. Where the code doesn't follow these yet (for triage)

- **U1 (testing §6.1):** `ui_checks.py`'s `SIGNATURE_JS` reads the binary round's buttons by
  `.btn-success[data-vote-url]` / `.btn-danger[data-vote-url]`, so a restyled button breaks the
  check; a `data-vote` value would not. Left as it is: the browser tests' copy of the check
  (`voting.spec.js`) finds the buttons by role and name ("Select image", "Reject image"), and
  `--checks` may be dropped once the browser tests have run in CI for a while.
- **U2 (2.1):** `style-src 'unsafe-inline'` stays (secure-coding §5 exceptions, P3); inline `style`
  attributes remain in `contestFiles`, `gallery`, `pageLinks`, `round/edit/imageFiltering` and
  `snippets/jobStatus` (theme-modes Phase 4, gradual).

Found while writing the juror guide (2026-11-03, confirmed in its review); none fixed yet:

- **U3 (4.3), medium: the large view's window clamps its keys.** It knows only the files around
  its file (`Neighbourhood.Side = 2`, `Window = 5`), so Page Up/Down and Home/End move at most two
  files mid-list (a little more near its start), not five files or to the list's ends, as
  `fileKeyboardNav`'s comment and the keys' names suggest.
- **U4 (4.3), low bug: key 0 in a comments-only round.** `keyboardNav` binds the digits 0 to
  `min(maxRate, 9)` for any non-binary round; a comments-only round has maxRate 0, so 0 posts a
  vote that the server refuses ("This round doesn't take that rate…"). Guard on `round.isRated`.
- **U5 (4.3), low–medium: top rates unreachable by key** where maxRate > 9 (a 1–10 round, or a
  half-star round past 4.5 stars): the digits stop at 9.
- **U6, low–medium: three forms of a rate's text.** The gallery's `rateString` shows stored
  units ("7" in a half-star round), the large view's number shows stars ("3.5"), and
  `voteMoved` shows "★ 3.5".
- **U7 (4.3), low: Delete on a Mac.** Its Delete key sends keyCode 8 (backspace), which is not
  bound; only Fn+Delete (46) rejects, while the tooltip says "<Delete>".
- **U8 (4.3), low–medium: no on-page list of the large view's keys;** only the buttons'
  tooltips name them.
- **U9 (3.5), low bug: no failed-image mark in the rated grid and the thumbs bar.** The by-rate
  grid (`galleryByRate`) loads no `large-view.js`, so a deleted file shows the browser's
  broken-image icon, not the grey ⊘ box; the large view's "Next files" strip neither.
- **U10, question for the user: comments are visible to every juror, with names.** The large view
  loads all the file's comments in the contest (`CommentJdbc.findBySubjectAndContest`). Intended,
  or should jurors see only their own (judging bias)?
- **U11 (4.1), medium accessibility bug: a focused binary thumb is invisible.** The thumbs take
  the focus (`tile-votes.js`), but sit in the tile's overlay at opacity 0 until hovered, so the
  keyboard focus can't be seen (WCAG 2.4.7).
- **U12 (1), low: "No round configured yet" wording.** `no.round.yet` also shows when the
  juror's rounds are stopped, paused for them or being distributed, not only before any round is
  configured.
- **U13, low–medium: the large view shows no file title;** only the image's `alt` and the
  Commons link carry it.
- **U14, low: "Edit user" heading on one's own profile** (`editUser.scala.html`).
- **U15 (1), low: "(Jury)" vs "Juror".** Fixed (2026-11-05): every role name is
  `Messages("role." + r)` (contest menu, users page, imports, account chooser, role selects;
  `role.no` "No role"); the plain `jury` key is gone.
- **U16 (1), low: whom to ask.** Fixed (2026-11-05): `signin.password.link.hint` and every
  message that says whom to ask name the contest admin, the role that invites, re-invites and
  creates a link to share (`UserController.users`, `reinvite`, `shareInvitation`,
  `sharePasswordLink`); root alone mails a reset (`resetPassword`).
- **U17, low bug: "No contests" in the account chooser.** `OAuthChooserController.entry` lists
  contests only for directly choosable accounts; one that needs its password or a mailed link
  shows "No contests" even when it has some.

Found by the browser tests (2026-11-04; testing §10). None of U3–U17 is checked by them yet:
they press Space, Delete and digits, not Page Up/Down or Home/End, and not on a Mac (U3, U5,
U7); the fixture has no comments-only or half-star round and no deleted file (U4, U6, U9); axe
doesn't check that the focus is visible, and `voting.spec.js` clicks the binary thumb without
looking at its focus (U11: a check of the focused thumb's opacity would catch it); U12–U17 are
texts, for view specs. Each can get a browser test with its fix.

- **U18 (4.3, testing §10):** the large view's and the contest file's side sections can show
  their default states for a frame before the column's restore script runs: the browser tests'
  probe sees such a frame in about one load of eight in Chromium and Firefox under parallel load
  (2026-11-04). It may be a frame the browser ran but didn't paint (Chromium holds the old page's
  paint while navigating), or a real one (the parser yielding between the sections and the
  script). `sections.spec.js` enforces the restore by DOMContentLoaded and keeps the first-frame
  check as `fixme`. A fix would hide the sections until restored, or restore from the layout's
  first script.
- **U19 (3.2, 4.1):** colour contrast below 4.5:1 (axe `color-contrast`, serious) on 7 of the
  tour's 17 pages: the `btn-outline-secondary` buttons and links (round form, new round form,
  contest files' import, contest images, users), the theme switch's unpressed labels (login
  page, profile), and `text-muted`-like text on the users page.
- **U20 (4.1):** controls without an accessible name (axe `button-name` critical, `link-name`
  serious): the icon-only buttons nested in links on the users page (31, one per row,
  `<a><button>`; the contests page's two were fixed on 2026-11-05: links styled as buttons, with
  `aria-label` and a tooltip), and an empty thumbnail link in the rated gallery's strip;
  `aria-prohibited-attr` (serious): `aria-label` on the navbar's disabled view links (round
  statistics).
- **U21 (4.1, 3.3):** form fields without a label (axe `label`, `select-name`, critical): the
  contest images page's `#source`, and the new round form's `#jurors` select behind Tom Select.
- **U22 (2, testing §10.2):** in Firefox, leaving a gallery by a form POST (sign-out, the theme
  switch) twice raised a page error on the page being left, `TypeError: can't access property
  "hasAttribute", document.body is null` (Bootstrap 5.3's jQuery check, `document.body.hasAttribute`),
  in two of five three-engine runs (2026-11-04); not seen in 24 Firefox runs of those specs alone.
  Suspected cause: a Bootstrap tooltip's delayed hide (the gallery's buttons have tooltips)
  firing after Firefox removed the page's body. Still open; accepted, by its exact message, only
  in the two tests that leave a gallery by a POST (`sign-in.spec.js`'s sign-out, `theme.spec.js`'s
  navbar switch) and only in Firefox (`allowFirefoxLeavingGallery`). Users don't see it (the page
  is being left).
- **U23 (4.3), medium bug: in Chromium, Back after a vote shows the old vote.** Chromium 156 (the
  browser tests' routeless browser, its back/forward cache on) brings the large view back from
  its back/forward cache although it is `no-store`, so Back shows the file with its vote from
  before; with the cache off (`WLX_CHROMIUM_BFCACHE=off`) Back loads it again and shows the stored
  vote. Likely Chrome's back/forward cache for `no-store` pages, which restores such a page when
  no cookie changed, as after a vote. In Chromium `voting.spec.js`'s four Back checks assert it
  (the `pageshow` event `persisted`, the old vote shown, a fresh load showing the stored one,
  annotated `U23`), so they fail once Chromium stops restoring the stale page, the signal to
  revisit this (2026-11-04); to confirm in a real Chrome by hand. A fix would
  reload on `pageshow` with `persisted` (`Secured.noStore`'s comment weighs that against a flash
  of the old votes), or change a cookie on a vote.
