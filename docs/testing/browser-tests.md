# Browser tests: what each spec covers

What each spec under `browser-test/tests/` checks, and the fixture data it uses. The rules for
running and writing these tests are in [testing practices](practices.md) §10. Each row lists the
stories its tests carry ([stories.md](stories.md)); `npx playwright test --list` lists the tests.

## Specs

| Spec | What it shows | Stories |
|---|---|---|
| `guards` | the fixture sends the production CSP; a CSP violation is reported; an image from another host, a lookalike of Commons' upload host and `commons.wikimedia.org`, and a request to Commons' API, are refused and reported; Commons' and the image proxy's images get the placeholder; the routeless browser refuses other hosts too (the guards' self-tests, practices §4.2) | S-03 |
| `sign-in` | sign in through the form, sign out through the user menu's POST with its token; a sign-out without the token is refused and the session stays; a wrong password | J-02, J-03, J-06, J-07, J-34, S-02 |
| `voting` | binary round: large view by key and by button, a gallery tile's ✓ button (its lower-left badge, no frame); rated round: large view by key and by star, gallery star (reading the tile's rate first); Back after a large-view vote shows the stored vote, as a fresh load does (practices §10.3, and below); a tile's ✓ and ✗ level under its zoom, all the large view's size, the large view's ✓ and ✗ with its navigation buttons' border width; each ✓, ✗ and zoom glyph centred in its button (its drawn pixels, within 1 px), and a tile's buttons 3:1 or more on a white photo; the comments link of the large view, the Next files strip, a gallery tile, the by-rate gallery and the comments list to the file's comments, and a by-rate vote chip's name with its time formatted | J-16, J-17, J-20, J-21, J-24, J-25, J-36, S-02 |
| `sections` | the large view's and the contest file's side sections restored as stored by DOMContentLoaded; the first-frame check is `fixme`, triage U18 ([UI](../ui/practices.md) §9) | J-33 |
| `theme` | the login page's switch (no request), the navbar's CSRF POST; `auto` in both schemes with every frame, from the first, in the scheme's mode: the `data-bs-theme` attribute, the root's computed `color-scheme` and the page's computed background (the "Firefox theme flash" check); the Votes toggle on the selected or the rejected in its vote colour at 4.5:1 at rest, pointed at and open, in both modes; the Codex look's one selected highlight and one hover grey (distinct, both showing on the bar, text 4.5:1 on each, the pager range box's border 3:1), the bar and its menus on the page's background with the bar's border, an open menu's toggle the hover grey, links underlined only on hover (in a sentence at rest), controls' border 3:1, placeholder 4.5:1, exactly 32 px (44 large, 28 small) and 2 px corners; Tom Select's control 32 px, its list on the page's background, the pointed-at option the hover grey and a chosen one the selected highlight | J-30 |
| `rounds-filter` | the Rounds page's and the round statistics' Status, Type and Order menus (their toggles' names, a choice opening the page with it, the statistics link and the Rounds link back keeping it, switching round keeping the order, the round menu reordered); the rounds' look on both pages and in the galleries' round menu: each round's type icons before its name (✓ ✗, a star, or an empty slot), coloured for an active round at 3:1 or more, grey for an inactive one, whose text is in the secondary grey at 4.5:1 on each background (the stripes, the menu, the hover grey), its links in the link colour, no strikethrough; the Type menu's legend; the icons and names lined up on all three (the round icons contest); the galleries' round menu scrolling inside itself, the keyboard keeping the focused item in view; no filter menus in a gallery; in light and dark mode; in Chromium an axe-core scan of each page with a menu open; opt-in screenshots (ROUNDS_FILTER_SHOTS, ROUND_ICONS_SHOTS) | A-28, O-07 |
| `large-side` | the large view for all the jurors' votes on the rated round (the by-rate gallery's top file): the vote list's rank numbers inside the window at 1280 and 1920 px; the score written once, the line under it its denominator alone (not starting with the bold number; a criteria round keeps its Σ, also for one juror, and a sum or half-star round its whole text: `AverageRuleSpec`); the full resolution link and the image's Commons link opening in a new tab (`target="_blank" rel="noopener"`); opt-in screenshots (LARGE_SIDE_SHOTS) | O-08 |
| `access` | a juror refused another juror's votes (gallery, large view, all jurors) and the admin pages, an organizer the round form: the 403 page with its reference | J-27, J-35, S-01 |
| `organizer` | the organizer role's round statistics and by-rate list; the admin's rounds list, round form with its jurors' table, edit-then-cancel (nothing saved, no job), new round form, the add juror and reassign pages opened from the statistics (read only); root's contests page, and a round of one juror (a contest of its own): no Reassign files…, its page refusing with nobody to reassign to | O-01, O-02, A-01, A-02, A-04, R-01 |
| `review` | a round's review before distribution, as the admin: the new-round form (setup step 1) without jurors or file distribution; "Save and find matching files" holds it in review and lands on its review page (step 2, "N of the contest's M files match"); the review page's Distribute opens the jurors form, the contest's jurors chosen, refused with none; Edit the round's "Re-apply filters" (a monument filter of ids separated by ", ") takes files out and the page shows how many; a comment in the review's large view; two jurors picked, one per file, Distribute opens the round. Step 3 from the step bar: the jurors form, its heading naming the round and the step, the images and the form; the rounds list reads "Setting up – step 3 of 3: choose jurors" (nothing distributed). Writes two rounds per engine to the fixture: one distributed to jurors no other test signs in as, one left in review | A-21, A-01 |
| `round-form-back` | Back after saving a new round ("Save and find matching files") opens the saved round's Edit page with "Round N is saved. You can change its settings here.", never the filled new-round form (the form's history entry carries its token; `no-store`; a back/forward cache restore reloads), in the routeless browser (practices §10.3); the landing page's saved line | A-15, A-21 |
| `users` | the users page's search for existing accounts: a script's POST (the text in its body, never the URL), a part of an address outside the admin's contests finding nothing, the engine's own account found by its name with a masked address, added to the contest (an invitation, cancelled again so a rerun starts over); Edit user's "wiki account taken" panel: a save giving the engine's member another account's wiki account refused, that account shown masked, Keep them separate putting the wiki account back without a request, then Add that account instead (an invitation, cancelled again); as root, in the retire pairs' own contest (no rounds): the panel's unticked "Retire the account being edited" with its conflict-of-interest change, ticked with the add, the edited member retired and no longer listed (three pairs per engine: a rerun takes the next; none in the guides' screenshot mode); the add row given only a wiki account two accounts have, nobody verified: nothing added, the confirmation page listing both by name only (no address), none picked, the box unticked, axe-clean in Chromium, the browser keeping it without a pick, then the account picked and confirmed (an invitation, cancelled again) | A-22, A-23, A-25, A-27 |
| `duplicates` | the duplicates report, as the admin: opened from the users page, the engine's first group still listed (a wiki account written three ways), the contest member's address in full with its edit link, the others masked; one account kept apart from the others, then the remaining pair, each a form POST with its token; the group gone, also after a reload. Uses one of the engine's four fixture groups per run; skipped in the guides' screenshot mode (no groups). As root, in the join pairs' own contest (no rounds): Join… on the engine's first pair still listed, nothing preselected, the kept account chosen, the preview (no votes, the Google sign-in unlinked, the account retired, the mail unticked), the join run in the background and its page's result, the group gone (three pairs per engine: a rerun takes the next; none in the guides' screenshot mode) | A-24, A-26 |
| `health` | the contest health page on the fixture's Nepal case (an old WLE 2020 contest of its own reused for WLM 2026 in Nepal), as root: opened from the Rounds page, its checks (H1 and H2 warn, H7 fails on the empty round, H8 warns on the round of every file) and facts in light and dark mode, an axe-core scan of each in Chromium, no page scroll at a phone's width; the files page's identity warning (DG10) for the contest's source, hidden for a matching typed source and back for the Nepal one, axe-clean in Chromium; an organizer refused the page (403). `HEALTH_SHOTS=<dir>` saves the screenshots (Chromium) | A-29 |
| `navbar` | the navbar's menus by keyboard (Space and Enter open them, the arrows move through the items, greyed-out ones skipped, Esc gives the focus back); the Votes button's unrated as muted as its count; the organizers' bar of one juror's votes on one line in Ukrainian with a long juror name, compact at 1280 px and full from 1800 px, with 10 px to spare ("Navbar width", below); the pages as links or in the Pages menu by width and language; the contest files tabs' counts | J-12, O-03 |
| `topbar` | the sticky top bar on the galleries and lists: it stays at the window's top while scrolling (1366 px), an open menu above the content with its list inside the window, a keyboard-focused file below it (WCAG 2.4.11), `--wlx-topbar-height` from 1366 to 390 px, on the Users page too, Back to gallery putting the file below it; none in the large view; on a phone it scrolls away with the page, its menus inside the window; the by-rate lists (all jurors, a juror's own, a binary round) never scroll sideways at 1280 and 1463 px and at device scale 1.75 (#165) | J-14, J-17, O-02, R-03 |
| `gallery-back` | Back and Esc from a file's large view to the by-rate gallery, the comments report and the tiles: from the first file the page at its top with the navbar in view, from a file further down that file (or row) in view; the old `#<pageId>` fragment's jump kept as a check. Uses its own round of one juror with this spec's own comments | O-02 |
| `contests-filter` | the contests page's Type, Country, Year and Order menus: the toggles' names, a choice opening the page with it and every menu's links keeping the others, the Country list scrolling inside its menu, no item of 0 but the chosen one and All; an axe-core scan with each menu open (Chromium), light and dark mode, a phone's width without horizontal scroll; opt-in screenshots | R-01 |
| `users-filter` | root's all-users page's Type, Country, Year and Role menus (every account by default, each item's count the accounts its link lists), with the same checks as `contests-filter`; a contest's users page without menus, its edit links passing the axe-core scan; opt-in screenshots | R-03 |
| `category-suggest` | the category fields' saved value (a Tom Select) on the contest files page's import source and the round form's first category: a click or a phone's tap selects all of it, typing replaces it, leaving unchanged keeps it, Enter keeps it and a second Enter sends it, a form sent from the focused field sends the text typed. Commons' suggestions answered empty | A-01, A-09 |
| `criteria-form` | the round form's criteria: only "Add criteria" at first, criteria added, chosen and removed with the focus following, up to ten by keyboard; without script all ten selects and no add button; a round of one criterion's gallery naming it once, its stars rating it, the large view showing it. Nothing saved | A-01, J-23 |
| `image-cache` | "Download images locally" on the contest files page and a round's form, posted by script: no reload, the status shown in place; a refused post (a stale CSRF token) says why under the button. In the round icons contest (no files, offline), as root | A-02 |
| `pages` | a tour of the pages (each role's, a round's jurors form and reassign page, the duplicates report's two and the account join's preview among them): status 200, one `h1`; in Chromium an axe-core scan per page (practices §10.5) | S-04 |

## Opt-in screenshots

The specs' own screenshots run only when their variable is set: `ROUNDS_FILTER_SHOTS` and
`ROUND_ICONS_SHOTS` (`rounds-filter`), `LARGE_SIDE_SHOTS` (`large-side`), `HEALTH_SHOTS=<dir>`
(`health`, Chromium), `CONTESTS_FILTER_SHOTS=<dir>` (`contests-filter`), `USERS_FILTER_SHOTS=<dir>`
(`users-filter`).

The user guides' screenshots (`docs-screenshots.spec.js`, `DOCS_SCREENSHOTS=1`; `0` or unset is
off, any other value an error) use a docs image mode: `DOCS_SCREENSHOTS=1 node
fixture-server.mjs start` (or `UI_IMAGES=docs`) passes `-Dui.images=docs`, `server.json` says
`"images": "docs"`, and the spec fails unless it does (a running server of the other mode is never
reused). The stub then serves the CC0 and public-domain photos that `browser-test/docs-photos.py`
verified under `target/docs-photos/` (each list's `manifest.json`; that script checks every
file's licence on Commons on every run, names files by a hash of their entry and deletes the
rest). `DocsPhotos` maps a file by its ordinal in the fixture (by page id), so consecutive files
get different photos; one in four gets a degraded one, never a file the shots show at the top of
a page (`UiShotsServer.shownFiles`), and the stub answers 503 until it knows them. The image
proxy's `target/ui-shots/thumbs-docs` is emptied on every docs-mode start. `DOCS_PHOTOS` names the
lists (`wlm-ua`, `wlm-intl`, `wle-ua`, `wle-intl`; default `wlm-ua,wlm-intl`): one contest's
photos per screenshot. The browser guard keeps the proxy's photos in that run and fetches the
upload host's images from the proxy. Normal runs and CI keep the grey stub; the photos are never
committed.

## Harness details

- **WebKit on Linux** types some image loads `other`: the guard (`lib/fixtures.js`,
  `isCommonsUpload`) accepts `other` from the upload host only for a Commons file's path, so a
  fetch, a script or any other path there is still refused.
- **A refused file's console line** in the routeless browser (practices §10.3) is accepted only
  when its location is that `upload.wikimedia.org` file, or, where the line carries no location
  (WebKit), when the same page had an `upload.wikimedia.org` load refused; never by its text alone.
- **`node fixture-server.mjs stop`** asks the server to stop and waits for it (up to 3 minutes).
- **The Allure reporter's `resultsDir`** (`../target/browser-test/allure-results`) is resolved
  from the working directory: run the tests and `npm run report` from `browser-test/` (practices
  §10.10).

## Jurors and fixture data

`UiShotsServer` writes up to eight jurors with files in both rounds to `server.json`. Each engine
votes as its own juror (Chromium the first, Firefox the second, WebKit the third), signs in and
out and switches the theme as another, and checks that a seventh's votes stay hidden. Each voting
test reads the file's vote first and casts one that changes it, so a rerun against the same
server votes again. The `review`, `users` and `duplicates` rows above say which rounds, pairs and
groups each engine uses.

## Back after a vote: which engine catches a dropped `no-store`

The Back tests run in the routeless browser (practices §10.3). On the large view:

- **Firefox:** the Back tests fail without `no-store`.
- **Chromium:** Back restores the page from the back/forward cache with the old vote (U23), with
  `no-store` or without it; with `WLX_CHROMIUM_BFCACHE=off` it shows the stored one. While U23
  stands the tests assert it there (`pageshow` with `persisted`, the old vote shown, a fresh load
  showing the stored one, a `U23` annotation), so they fail once Chromium stops restoring the
  stale page.
- **WebKit (Windows):** the tests pass without `no-store` too, so Back after a vote in Safari
  stays a manual check ([release checklist](../deployment.md#release-checklist)).

## Navbar width

The navbar's width tests (`navbar.spec.js`, "stays on one line") log each bar's room as `[width]`
lines: the px free after the pager, or by how much a wrapping bar is short. Keep 10 px free with
Linux's fonts at the tightest case. Ubuntu draws the navbar's text wider than Windows, so run
them with Linux's fonts (practices §10.6) before trusting a Windows pass.

## Not covered yet

- **Mobile profiles,** which come with mobile support ([UI](../ui/practices.md) §6.2; mobile is
  not supported yet).
- **A double click must not save twice** (`data-submit-once`): an idea for a later test.
