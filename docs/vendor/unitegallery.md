# The vendored gallery: Unite Gallery 1.7.28, patched

The juror and organizer galleries (`gallery.scala.html`) and the contest files page
(`contestFiles.scala.html`) lay their tiles out with Unite Gallery's tiles theme
(`snippets/galleryScripts.scala.html`, started by `gallery-init.js`). The files are copied into
the tree and patched for wlxjury. This doc lists every patch, how the copy was compared with
upstream, the tests, and what is still unescaped. The keep-or-replace decision is the user's:
[investigation, 2026-11-04](../investigations/2026-11-04-gallery-keep-or-replace.md).
Rules: [UI practices](../ui/practices.md) §7, [dependencies](../dependencies/practices.md) §7,
[secure coding](../security/secure-coding.md) §8.

## Contents

1. [Origin](#1-origin)
2. [The patches](#2-the-patches)
3. [Sinks left as upstream wrote them](#3-sinks-left-as-upstream-wrote-them)
4. [Tests and coverage](#4-tests-and-coverage)
5. [Comparing with upstream again](#5-comparing-with-upstream-again)

## 1. Origin

| File | Upstream | Changed by wlxjury |
|---|---|---|
| `public/javascripts/unitegallery.js` | Unite Gallery **1.7.28** (released 04 Jul 2016), [vvvmax/unitegallery](https://github.com/vvvmax/unitegallery) tag `1.7.28` (`caf7bbc4`), `package/unitegallery/js/unitegallery.js` | yes, §2 |
| `public/javascripts/ug-theme-tiles.js` | same tag, `package/unitegallery/themes/tiles/ug-theme-tiles.js` | yes, §2 (about 67 of its 246 non-blank lines, comments included) |
| `public/stylesheets/unite-gallery.css` | same tag, `package/unitegallery/css/unite-gallery.css` | yes: the thumbs' icons (C1) |
| `public/stylesheets/ug-theme-default.css` | same tag, `package/unitegallery/themes/default/ug-theme-default.css` | no |

**How the version was identified:** the file's first line says `Unite Gallery, Version: 1.7.28,
released 04 Jul 2016`. The first copy in this repository (`214b7998`, 2016-10-05) is
identical to the tag's file apart from line endings (CRLF here). Upstream is MIT-licensed. The
copies carry no license header (dependencies §9, T4).

**Upstream since then:** tags `1.7.31` to `1.7.40` (2016-11-29) and a `master` commit "updated to
1.7.45" (2017-02-27). Those releases still look the theme up with `eval`, still put the title
unescaped in the image-effect overlay's `alt`, and still escape the thumbnail's `alt` with
`stripTags`. Updating to them would fix none of §2's issues. Maintenance status: the
investigation, §1.

The 2016 commits are the gallery voting of issue #6 (`214b7998` to `ebfcfefd`, 2016-10-05 to
2016-10-19). The 2026 ones came with the Commons deletions (`feba8519`, `52ad411b`), the
Bootstrap 5 theme (`5fc320ba`, `68c4716c`), binary aggregate votes (`340edf09`), the CSP
follow-ups B3 and B5 (`249ddc76`, `c5c6e881`) and security batch S5 (`eb7f706b`).

## 2. The patches

Every patch carries a `wlxjury:` comment at its place. *Was marked* says whether it had one
before this inventory: the unmarked ones were found by diffing against the tag (§5) and are
marked now. *Test* names the test in `javascript-test/unitegallery-patches.test.js`
(`unitegallery-alt.test.js` where it says so) that fails when the patch is reverted. The revert
proof (2026-11-04) reverted each patch on its own and ran both files: every patch below failed at
least one test, except P15 and C1, which change no behaviour a test could see.

### unitegallery.js

| ID | Where | What and why | Commits | Was marked | Test |
|---|---|---|---|---|---|
| P1 | `UGFunctions.htmlToText` | An item's title (the tile's `alt`) is HTML: the caption link that `gallery.scala.html` writes. This returns its text, parsed inert with `DOMParser` (no scripts, no image loads), entities decoded once. **Security.** | `eb7f706b` (S5) | yes | alt: "htmlToText runs nothing"; patches: "the caption's markup is text" |
| P2 | `UGFunctions.escapeAttr` | Escapes text for a single- or double-quoted attribute (`& < > " '`). Upstream's `htmlentitles` left `'` alone, and the tile's attributes are single-quoted. **Security.** | `eb7f706b` | yes | every escaping test below |
| P3 | `UGTileDesign` `setHtmlThumb`: the thumbnail `<img>` | `alt` is `escapeAttr(htmlToText(title))` (was `htmlentitles(stripTags(title))`), and `src` is `escapeAttr(urlThumb)` (was unescaped). **Security.** | `eb7f706b` | yes | "an alt that would close the attribute stays its text", "a src with quotes stays the src", "the caption's markup is text"; alt: "both thumbnail sinks" |
| P4 | `setHtmlThumb`: the image effect's overlay `<img>` | The same for the copy that `tile_enable_image_effect` adds. Upstream put the raw title in `alt`. wlxjury doesn't turn the effect on (gallery-init.js), but the sink is fixed anyway. **Security.** | `eb7f706b` | yes | "the image effect's overlay copy too" |
| P5 | `ismouseover` (minified, after `UGFunctions`) | `.on("load", e)` in place of `.load(e)`. jQuery 3 removed the event method, and `.load` is now the ajax method, which threw "a.indexOf is not a function" on document ready on every gallery page. | `249ddc76` (B3) | yes | "runs on document ready without an error" |
| P6 | `UGThumbsGeneral` `onThumbImageLoaded` | A thumbnail that failed to load (a file deleted or renamed on Commons) has no natural size, and its 0/0 ratio left every tile of the justified layout unplaced. This keeps the `data-width`/`data-height` size (else 4:3) and adds the class `wlx-thumb-failed` (main.css draws the grey box). | `feba8519` | yes | "is marked and keeps a size" |
| P7 | `setHtmlThumb`: the deleted-file mark | A file deleted on Commons (`data-deleted`) gets the tile class `wlx-deleted` (a red frame) and a badge, which links to the file's Commons page (`data-commonsUrl`) in a new tab with `rel=noopener`. The badge's text and title are set with `.text()` / `.attr()`. Bootstrap 5 classes since `5fc320ba`. | `52ad411b`, `5fc320ba` | yes | "its tile is marked…", "the mark's text is text" |
| P8 | `onDeletedLabelClick`, and its delegate in `initEvents` | Stops the mark's click from reaching the tile, whose click would open the large view in this tab. | `52ad411b` | yes (the delegate's comment is new) | "a click on the mark doesn't reach the tile" |
| P9 | The item parser (`UniteGalleryMain`, `<img>` children) | Reads `data-deleted`, `data-commonsUrl` (P7), and `data-approvals`, `data-rejections`, `data-tally` (the organizers' tally on a binary round, `tile-votes.js addTally`). | `52ad411b`, `340edf09` | yes | the P7 tests; theme: "the jurors' tally on every tile" |
| P10 | `UniteGalleryMain` `initTheme` | Looks the theme's constructor up as `window["UGTheme_" + name]` rather than with `eval`, which the CSP refuses (no `'unsafe-eval'`). A missing theme throws an error that names it. | `c5c6e881` (B5) | yes | "the tiles start with eval refused", "a theme that isn't loaded…" |
| P11 | `UGTileDesign`: events `SELECT_CLICK`, `REJECT_CLICK`; the select/reject icons in `setHtmlThumb` (only with the options `jury` and `binary`); `getButtonSelect`/`getButtonReject`; `onSelectButtonClick`/`onRejectButtonClick` and their delegates | The binary round's thumbs up and down on a juror's tiles. The select icon takes the link icon's place in `setHtmlThumb`: upstream hid `.ug-icon-link` there, and made the tile clickable only when there was no link icon. | `40390606`, `bb0a59aa` (jury only), `ebfcfefd` (binary only) | **no**, marked now | "a juror's tiles in a binary round have them…", "no thumbs for an organiser, nor in a rated round", the six click cases |
| P12 | `UGTileDesign` `positionElements` | The zoom icon goes a third of the way down and the thumbs below it, where upstream put zoom and link icons side by side. The gap above a text panel is a third of its height, not half. Layout only, for icons on portrait files. | `40390606`, `632e3121` | **no**, marked now | — (jsdom lays nothing out; the browser tests' `voting.spec.js` clicks the thumbs) |
| P13 | `UGFunctions.placeImageInsideParent` | The lightbox slide's url (`data-image`) is escaped for its double-quoted `src`. Upstream's `urlImage.replace('"','\"')` changed nothing, because `'\"'` is `'"'`. The lightbox is built and its first slides placed on every gallery page, although wlxjury never opens it. **Security** (register N14, Low). | this branch | new | "a data-image with a double quote stays the src" |
| P14 | The item parser | Reads `data-pageId`, `data-roundId`, `data-rate` and `data-largeUrl` for the theme's votes and clicks. | `51d67172`, `7d5024a4`, `cb782f30` | **no**, marked now | the theme's click and vote tests |
| P15 | `setHtmlThumb` `toSeparateIcon`; the item parser's `lazyLoadSrc`; the comment "prototype gallery function" | Spelling only (`toSaparateIcon`, `lasyLoadSrc`, `funciton`). No change in behaviour. | `40390606`, `632e3121`, `51d67172` | **no**, the first two marked now | — |

### ug-theme-tiles.js

| ID | Where | What and why | Commits | Was marked | Test |
|---|---|---|---|---|---|
| T1 | `setHtml` | Shows a juror's stored votes on the tiles of a binary round (`wlxTileVotes.addVote`) and a rated round's stars (`addStars`, option `wlx_stars`). | `68c4716c` | yes | "a juror's tiles in a binary round have them…", "a rated round's stars on every tile" |
| T2 | `setHtml` | Shows the jurors' tally on the organizers' tiles, if `tile-votes.js` is loaded (the contest files page doesn't load it). | `340edf09` | yes | "the jurors' tally on every tile", "a page without tile-votes.js…" |
| T3 | `onTileClick` | A tile click opens the file's large view in this tab (`data-largeUrl`, which keeps the gallery's filter and order), not upstream's lightbox. | `cb782f30`, `51608464`, `cf56da73` | **no**, marked now | "a tile click opens the file's large view…" |
| T4 | `onSelectClick`, `onRejectClick`, `serviceCall` | A thumb click votes 1 / -1, or 0 when the file already has that vote. The vote is sent by `wlxTileVotes.vote`, which shows it once it is stored. | `49136f58`, `51d67172`, `68c4716c` | **no** (only `serviceCall`'s comment), marked now | the six click cases |
| T5 | `initEvents` | Binds `SELECT_CLICK` and `REJECT_CLICK` (P11) to T4. | `49136f58` | **no**, marked now | the six click cases |

### unite-gallery.css

| ID | What | Commit | Was marked |
|---|---|---|---|
| C1 | The `ug-icon-select` and `ug-icon-reject` icon images | `40390606` | **no**, marked now |

## 3. Sinks left as upstream wrote them

The vendored files insert markup with jQuery's `.html()` and string concatenation in many
places. `security.RawHtmlSinksSpec` (G5) exempts the vendored files by name. These are the ones
that item data reaches:

| Sink | What reaches it | State |
|---|---|---|
| The lightbox and text panel's title and description, `UGTextPanel.setTextPlain` (and `UniteGalleryMain.setTextContainers`), `.html()` | The tile's `alt` and `data-description` | Safe only because the templates escape the text once more (`gallery.scala.html`, `contestFiles.scala.html`; register N8; `GalleryDescriptionViewSpec`, `ContestFilesPageSpec`). Jest: "the lightbox's title (upstream, not patched)". |
| The link icon's `href` in `setHtmlThumb` | `objItem.link`, the `href` of an `<a>` that wraps a tile's `<img>` | Unescaped and not reachable: `tile_show_link_icon` and `tile_as_link` are off, and no template wraps a tile in a link. Register N15 (Info). Escape it before turning either option on. |
| The gallery's error box, `innerHTML` | The message of an error thrown while starting the gallery, e.g. P10's theme name | The name is the constant `tiles` from gallery-init.js. |
| `placeImageInsideParent` | The lightbox slide's url | Escaped now (P13, N14). |

## 4. Tests and coverage

`javascript-test/unitegallery-patches.test.js` loads jQuery 3.7.1, `unitegallery.js` and
`ug-theme-tiles.js` into jsdom as the page loads them, then starts the gallery on a `#gallery`
of `<img>` tags built the way the templates write them. jsdom neither loads images nor lays
them out, so two fakes stand in: an image's natural size comes from a table keyed by its
`src`, and setting `src` fires `load`, or `error` when the src isn't in the table. With these,
the tiles, the lightbox and their events run unchanged. `tile-votes.js` is replaced by a
recording stub, since its own tests cover it. The test file has 25 tests. The older
`unitegallery-alt.test.js` checks the two thumbnail sinks.

**Coverage.** Jest's V8 coverage sees only files loaded through its module system. The
vendored files are plain scripts that define globals, so the tests load them with an indirect
`eval`, and `collectCoverageFrom` keeps them excluded (with `jquery.hotkeys` and `jquery.raty`).
Measured once, with a throwaway transform that loaded the theme as a module (not committed):
the patch tests cover `ug-theme-tiles.js` at **86.2 % of lines, 82.4 % of branches**, and every
line of T1–T5. The uncovered lines are upstream code wlxjury doesn't use (`initThumbsPanel`,
the shuffle order, the lightbox auto-open, `destroy`). The theme is about a quarter ours, not
mostly ours, so it stays excluded like `unitegallery.js`. The patch tests assert the patches
instead (testing practices §9.1).

## 5. Comparing with upstream again

```bash
git clone https://github.com/vvvmax/unitegallery.git /tmp/ug   # into a scratch directory
git -C /tmp/ug show 1.7.28:package/unitegallery/js/unitegallery.js | tr -d '\r' > up.js
tr -d '\r' < public/javascripts/unitegallery.js > ours.js
diff -u -w -B up.js ours.js     # every hunk is one of §2's patches
```

Do the same for `themes/tiles/ug-theme-tiles.js` and `css/unite-gallery.css`. Ignoring
whitespace matters: `40390606` re-indented about 13,000 lines, and without `-w` the diff is
33,000 lines long. With it, 2026-11-04's diff was 19 hunks in `unitegallery.js`, 4 in the
theme and 1 in the CSS, all listed above.
