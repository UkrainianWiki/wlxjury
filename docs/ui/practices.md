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
copied to the cookie at sign-in); `Theme.parse` accepts exactly those, anything else is `auto`, and
only the fixed strings reach the page. `layout.scala.html` renders
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
their stored state from the first frame.

**5.3 Every release:** the manual checks of the roadmap's "Every release" (Firefox theme flash and
Back after a vote, the `auto` screenshots in both schemes, category suggestions against live
Commons). The automated browser layer that will take these over is planned (testing §10).

## 6. Supported browsers

**6.1 Desktop** (the user, 2026-11-03): supported and tested are the current and previous major
versions of Chromium-based browsers (Chrome, Edge, Opera), Firefox and Safari (WebKit). A major is
the first version number: Chromium and Firefox ship one every four weeks, Safari once a year with
the OS. Until the browser tests run all three engines (testing §10), a change is checked by hand in
at least Chromium and Firefox. *Why:* the engines the browser tests run.

**6.1a Compatibility floor: Chrome 109 and Firefox 115.** Signing in, voting and the core pages
must keep working there (cosmetic issues aren't fixed): no JavaScript or CSS feature newer than
those versions. *Why:* in a 14-day sample of production's signed-in visitors (2026-11-03, 105
client and browser pairs) about a fifth used old browsers: Firefox 115 ESR, Chrome 109 (the last
for Windows 7/8) and old Opera releases. Rerun the count each contest season and raise the floor as
they drop out: `ssh <server> 'python3 -' < scripts/browser-stats.py` (read-only; prints counts only,
no addresses or user agents).

**6.2 Mobile: not supported yet.** Pages render on phones but are not designed or tested for
them. Mobile support is queued in the roadmap ("Mobile support (not supported yet)"), and the
browser tests' mobile profiles come with it. Write "not supported yet", never "broken", in issues and docs.

## 7. The vendored gallery

`public/javascripts/unitegallery.js` (24,670 lines) and `ug-theme-tiles.js` are vendored and
patched (`wlxjury:` comments: escaping of titles and attributes, `.on("load")` for jQuery 3,
failed-thumbnail and deleted-file marks, the theme lookup); `jquery.hotkeys.js` and `jquery.raty.js`
are vendored too. Outside Jest coverage; `gallery-init.test.js` and `unitegallery-alt.test.js`
(a tile's alt text escaped) touch them. A change to a vendored file is marked `wlxjury:` with
its reason. The patch inventory, targeted tests and the keep-or-replace decision are queued
([roadmap, "the patched gallery"](../plans/2026-11-02-roadmap.md#queued-user-2026-11-03-the-patched-gallery);
[dependencies](../dependencies/practices.md) §7).

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
| 4.1 accessible names | review | planned: with the browser tests (an accessibility scan per page) |
| 5 zero CSP violations and console errors | `ui-shots.py` prints console messages | planned as a failing check in the browser tests |
| 6.1 three engines | — | planned (testing §10) |
| 6.1a the floor (Chrome 109, Firefox 115) | review | planned: a Browserslist or lint check of the scripts and styles |

## 9. Where the code doesn't follow these yet (for triage)

- **U1 (testing §6.1):** `ui_checks.py`'s `SIGNATURE_JS` reads the binary round's buttons by
  `.btn-success[data-vote-url]` / `.btn-danger[data-vote-url]`, so a restyled button breaks the
  check; a `data-vote` value would not.
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
- **U15 (1), low: "(Jury)" vs "Juror".** `logged.scala.html`'s contest menu names roles with
  `Messages(r)` ("Jury"); elsewhere `role.jury` ("Juror"). Use `Messages("role." + r)`.
- **U16 (1), low: `signin.password.link.hint` says to ask the contest's organizers for a
  link,** but organizers can't send one, and contest admins only to an unverified member
  (`UserController.reinvite`); for an account with a verified email and a usable password only
  root can (`resetPassword`).
- **U17, low bug: "No contests" in the account chooser.** `OAuthChooserController.entry` lists
  contests only for directly choosable accounts; one that needs its password or a mailed link
  shows "No contests" even when it has some.
