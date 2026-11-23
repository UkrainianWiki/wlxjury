# One gallery of our own tiles: replacing Unite Gallery (plan, 2026-11-19)

**Status (2026-11-23):** planned, not built; build right after release 26.11.14. **All
questions (Q1–Q25) and the one-criterion rule are decided by the maintainer** (2026-11-19 to
2026-11-23); the decisions are applied in the sections they affect and listed in §8.

This plan carries out option C of the
[gallery investigation](../investigations/2026-11-04-gallery-keep-or-replace.md) ("our own
tiles"), which the maintainer chose on 2026-11-04 for "later". It also weighs lightGallery 3
(§2), at the maintainer's request.

**Revision history:**

- **2026-11-19:** three independent reviews and the first decisions (Q1–Q4, one gallery for every
  view). They produced keyset continuation for chunks, JSON refusals for chunks, the engines'
  real `srcset` choice, a cache cleanup that fails closed, ratio classes instead of a
  script-set ratio, and the two size schemes.
- **2026-11-23:** the maintainer's answers to Q5–Q25, the one-criterion rule (decided
  2026-11-20) and the review fixes applied: the Pages / Continuous choice, window-sized batches,
  a screen profile and prefetch of the large sizes, a switch that reuses cached images, the
  caption-aware tile width.

Written from the code at the 26.11.13.1 hotfix. Nothing was run for it:
no sbt, fixture server, browser or Docker. Where a fact needs a browser, it is a measurement step (§5, G-0), not a claim.

**Builds on, does not reopen:**

- The patch inventory and tests of the vendored gallery: [docs/vendor/unitegallery.md](../vendor/unitegallery.md).
- The galleries' authorization: `GalleryService.viewerRounds`, `refusal`, `pausedFor`, `viewAs`
  and the comments-report rule, all in `GalleryController.gallery` (CLAUDE.md "Own votes").
- The image proxy and the local image cache ("Download images locally"):
  `ImageProxyController`, `LocalImageCacheService`, `Global.thumbnailWidths`.
- The large view's Back-to-gallery link and Esc key (`RoundList.atUrl`), the vote endpoint
  `selectWS` with its CSRF header, and the by-rate stars (`tile-votes.js addListStars`).
- The maintainer's earlier layout decisions: chips at the tile's lower left on gallery tiles
  (2026-11-15), and in the by-rate list with the rank under the thumbnail, not on it
  (2026-11-16).
- UI practices U2 and U11 (docs/ui/practices.md §9), secure-coding §5 (CSP exceptions),
  dependencies §1 and §6.
- **A separate PR** adds a reappearing top bar (hides on scroll down, shows on scroll up) on the
  gallery and list pages. This plan only reserves room for it (§3.7); it does not build it.

## Contents

- [Goals and non-goals](#goals-and-non-goals)
- [1. What exists today](#1-what-exists-today)
- [2. Option A (own tiles) and option B (lightGallery 3)](#2-option-a-own-tiles-and-option-b-lightgallery-3)
- [3. Design](#3-design)
- [4. Server and API changes](#4-server-and-api-changes)
- [5. Steps: small reviewable PRs, in order](#5-steps-small-reviewable-prs-in-order)
- [6. Test plan](#6-test-plan)
- [7. Risks](#7-risks)
- [8. Questions and decisions](#8-questions-and-decisions)

## Goals and non-goals

**Goals**

1. **One gallery.** One Twirl partial, one stylesheet section and one script module serve
   every gallery:
   - the binary galleries;
   - the rated galleries and every by-rate list;
   - the contest files;
   - the round review.
   Where the views differ, the tile has options: vote controls, tally, caption, chips' place.
2. **Tiles show as they load.** Each tile appears as soon as its own thumbnail arrives. A slow or
   failed thumbnail never hides the others. Off-screen thumbnails load only when needed
   (`loading="lazy"`), and every tile has its size before its image arrives (no layout shift).
3. **Sharp thumbnails, never enlarged** (Q3, decided). Each image gets a `srcset`/`sizes` (a
   list of sizes, from which the browser picks the one its screen and zoom need), all served
   from this server's cache.
   - Rows keep their height: 200 px, growing to at most 247 px to fill a full row.
   - A short row (the last, or the only one) stays short.
   - Each contest uses one of two size schemes (Q4, decided): Wikimedia's standard widths for
     new or re-downloaded contests' caches, today's widths for existing caches.
4. **Pages or continuous loading, the person's choice** (Q16, Q18, Q20, decided). The view menu
   offers **Pages** (the default: the pager, as today) and **Continuous** (the next files load
   as the person scrolls, in server-rendered batches of one screen, Q15). Either way votes cast
   on the page never make the list skip files. In Continuous the URL follows the scroll, so Back
   from the large view returns to the same place. Without script, the pager still works.
5. **Position without auto-scroll.** The last-viewed file is marked (outline and label). If it
   is off-screen, a floating button jumps to it. The page never scrolls by itself.
6. **Everything the tiles and the by-rate list do now**, plus a keyboard path: the tile is a
   real link, focusable, opens with Enter and opens in a new tab.
7. **Justified rows**, in CSS (flex-wrap and each file's aspect ratio). No new jQuery code.
   Unite Gallery's four files, its patch tests and its notice are removed at the end, and so is
   the by-rate grid's old markup.

**Non-goals**

- No lightbox or quick preview (Q2, decided). A tile opens the large view, where jurors vote
  with the keyboard, read comments and see the file's details.
- **The comments report stays its own layout** (§3.11): it is a list of comments with their
  thumbnails, not a gallery. It shares the tile's image (`srcset`, lazy loading) and the
  last-viewed mark. The file list stays a table.
- **No forced migration of existing caches.** A contest keeps its current thumbnail widths until
  its images are downloaded again (§3.5). Thumbnails of files deleted on Commons are never
  deleted (Q5–Q7).
- No thumbnail straight from `upload.wikimedia.org` in a tile's `srcset` for now (Q3). The large
  view's main image keeps its direct Wikimedia URLs, as today.
- No windowing (removing far tiles from the page) in the first build; a later spike compares a
  windowing library with our own (G-5b, Q18).
- The large view does not change, apart from its thumbnail bar's widths per scheme.
- The reappearing top bar (separate PR).
- Removing `'unsafe-inline'` from `style-src` is prepared here, as its own last step (§3.10).

## 1. What exists today

### 1.1 Two galleries

| Gallery | Pages | Template | Built by |
|---|---|---|---|
| **Unite Gallery** (vendored, patched) | A **binary** round's gallery (a juror's own, one juror's as organizers see it, all jurors' with the tally); the contest's files; the round review | `gallery.scala.html`, `contestFiles.scala.html` | `GalleryController.scala:254-256` (`GalleryView.Gallery if round.isBinary`), `:580-610`; `RoundReviewController.scala:103` |
| **The by-rate grid** (ours, server-rendered) | A **rated** round's gallery and every by-rate list, binary or rated | `galleryByRate.scala.html` | `GalleryController.scala:300-307` (`GalleryView.Gallery`, on a rated round, falls through to it) |

**Unite Gallery pages.** Both templates write one `<img>` per file inside
`<div id="gallery" style="display:none;">` (`gallery.scala.html:35`,
`contestFiles.scala.html:109`):

- the caption as escaped HTML in `alt`;
- `data-*` for the size, the vote, the large-view URL, the tally, the comments and the deleted
  mark.

`snippets/galleryScripts.scala.html` loads `unitegallery.js` (586 KB), `ug-theme-tiles.js`, two
stylesheets and `gallery-init.js`, which starts the tiles theme (`gallery-init.js:23-53`: rows
200 px high, 5 px apart).

**The by-rate grid** (`galleryByRate.scala.html:115-133`).

- **Boxes.** `<li class="gallerybox" id="@file.pageId">` boxes of a fixed 300 × 250, holding:
  - a link around the cropped image (`img.cropped`, `main.css:232-259`);
  - under it, the rank line (`showRateGalery`, `:28-57`): the rank, a rated round's stars and
    rate text (the juror's own stars clickable, `tile-votes.js addListStars`, raty), a criteria
    round's averages, and the chips (tally or vote, vote time, comments; `:59-80`).
- **The hint.** "A changed rate keeps its place until reload" (`.wlx-byrate-hint`).
- **No group headings.** By-rate shows each file's rank in its own line; it has no headings
  per rate.
- **Already lazy-free.** The images have `src` and a `1.5x`/`2x` `srcset` of the box, and no
  `loading` attribute.

The tile stars of patch T1 (`data-stars`, `addStars` on a `.ug-tile`) are not reachable:
`views.TileStars.round` is `None` for a binary round, and `gallery.scala.html` is rendered only
for binary rounds. Rated rounds' stars live in the by-rate list. The T1 path is dropped (Q21,
decided).

The comments report (`commentsReport.scala.html:185-188`) is a list of `<article>` rows (a
thumbnail, the title, then the file's comments), with lazy thumbnails. It is a list of comments,
not a gallery (§3.11). The file list is a table.

### 1.2 How thumbnails are built

- `Global.resizeTo(image, gallerySizeY)` (`Global.scala:63-66`): the thumbnail 250 px **high**,
  at whatever width that gives the file. It is a path on this server,
  `/wikipedia/commons/thumb/...`, served by `ImageProxyController.serve` (`conf/routes:14`).
- The proxy serves only the widths in `LocalImageCacheService.servedWidths`
  (`LocalImageCacheService.scala:774-777`):
  - `Global.thumbnailWidths` (`Global.scala:24-41`): heights 250, 375 and 500 for the binary
    gallery, the 300 × 250 box and its multiples for the by-rate grid, and 150 × 120 for the
    large view's thumbnail bar;
  - plus the large view's fallback.

  Any other width is redirected to the nearest served one (`ImageProxyController.scala:116-117`,
  `:137-144`).
- **A miss** fetches the nearest **Wikimedia standard width** at or above the target
  (`Global.wikimediaSteps` = 20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840;
  `Global.scala:114`) and scales it down here. It caches every target size of that file on the
  way (`fetchAndCacheAll` and `source`, `LocalImageCacheService.scala:115-165`, `:476-490`).
  - A 429 from Wikimedia is retried with backoff of 2^n seconds, up to 3 attempts (`:564-620`).
- "Download images locally" fills the same widths ahead of time, per contest or per round
  (`startDownload`, `startDownloadForRound`, `:310-338`).
- **The cache is shared.** It has one directory per Commons file (`localFile`, `:518-526`),
  whatever contests the file is in. An in-memory registry holds the paths of the cached files
  (`:93-102`, `initRegistry` `:197-211`).
- **The cleanup.** At start-up, when the sizes listed in the global `.cache-sizes` marker differ
  from `Global.thumbnailBoxes` (`:215-258`), `cleanUpUnusedSizes` (run from `:729`) deletes the
  cached files of other widths (`unusedFiles`, `:269-287`).
  - `unusedFiles` reads the widest file of each directory and recomputes the used widths from
    its dimensions, capped at its width (`ImageUtil.resizeTo(w, h, y)` takes `min(…, w)`). That
    is right while the widest file follows the current widths.
  - Nothing protects the thumbnails of **files deleted on Commons**, which cannot be fetched
    again (`Global.cachedSrcSet`, `largestCachedThumbnail`, `Global.scala:80-100`).
- **`largestCachedWidth`** (`Global.scala:99-100`) is the widest **listed** width below the
  file's own, not the widest on disk. A deleted file's large view uses it
  (`large/contestFile.scala.html:89`, `:115`, `:121`).
- `/thumb_urls/:contestId` (`GalleryController.thumbnailUrls`, `:746`, built by `resizedImagesUrls`, `:515-535`) lists every file's
  `thumbnailWidths` for an external download.
- The binary gallery's `srcset` offers heights 375 (1.5x) and 500 (2x)
  (`gallery.scala.html:45`); the by-rate grid's, the box at 1.5x and 2x (`Global.srcSet`).

### 1.3 Why tiles show late, or not at all

Read from the code; step G-0 confirms it in a browser.

1. **The gallery waits for every thumbnail.** The justified type hides all tiles
   (`objTiles.fadeTo(0,0)`) and shows them only when `checkImagesLoaded` has counted every image
   as loaded or failed (`unitegallery.js:9418-9428`, `:2126-2180`). The slowest thumbnail of the
   page decides when the first one shows. An uncached thumbnail goes proxy → Wikimedia → scale
   here. With Wikimedia's 429 backoff, that can take seconds, sometimes tens of seconds.
2. **"No images at all":**
   - **A thumbnail that never settles.** A request that stays pending (a stalled fetch on the
     server, a proxy waiting on Wikimedia's backoff) never fires `load` or `error`, so the
     counter never completes, and the page stays blank. There is no timeout.
   - **A script error before the layout.** `#gallery` starts as `display:none` and only the
     library shows it (`unitegallery.js:19571-19573`). If the library fails to load or throws,
     the files stay hidden, with no fallback. P5 (jQuery 3's `.load`) was one such error, on
     every gallery page, until 2026-10.
   - **A session that lapsed.** The proxy answers 401 to every thumbnail
     (`ImageProxyController.scala:52-58`). Every tile then turns into P6's grey failed box, which
     looks like "no images".
3. **All files at once.** Every `<img>` of the page is in the HTML with its `src`, inside a
   hidden container. The browser fetches them all at once, and the page also builds and runs the
   unused lightbox (`ug-theme-tiles.js:122`, `:169`).
4. **Double downloads (to confirm).** The theme builds its own `<img src=urlThumb>` for each tile,
   without `srcset` (`unitegallery.js:10646`). The template's `<img>` with `srcset` is only
   parsed and then removed (`clearInitData`, `:19772-19775`). On a HiDPI screen the browser may
   fetch the hidden image's 2x candidate **and** the tile's 1x `src`, and it shows the 1x one.

### 1.4 Why thumbnails are blurry

1. **The visible tile never gets a `srcset`** (§1.3 item 4). It always shows the 250 px-high
   thumbnail, whatever the screen's pixel ratio or zoom.
2. **Rows stretch, the last one too.** The library splits the files into
   `ceil(total width / gallery width)` rows and scales every row, the last included, to the
   gallery's width (`unitegallery.js:9206-9270`). With three files on a 1,900 px screen, the one
   row is about 470 px high, made from 250 px thumbnails: "blurry with few images".
3. **Zoom.** Zooming raises the device pixel ratio. A `srcset` would let the browser pick a
   larger file, but the tile has none.

### 1.5 Paging and position

- `Pager.pageSize = 15` (`Pager.scala:31`), used by the galleries (`RoundList.pageSize`,
  `RoundList.scala:366-369`) and the contest files. The pager sits in the navbar (`pagerNav`),
  showing "46–60 of 5,712" with first, previous, next, last and a page box.
- **Pages are read by `OFFSET`** (`RoundList.files`, `RoundList.scala:486-487`).
- **Placing a file by key:**
  - A juror's list can be placed by key: `SelectionQuery.keyed`, `orderKey` and `countBefore`
    (`ImageDbNew.scala:371-391`), used by `RoundList.position` and `around`
    (`RoundList.scala:521-558`).
  - `orderKey` reads a file's order values **as they are now**. After a vote those are the new
    values, which move the file in every order except file order.
  - A binary juror gallery has four orders: file order, by rate, latest vote and oldest vote
    (`RoundList.scala:105-132`). The last two sort on `UnratedLast` and `VoteTime`.
  - `keyed` is false for lists that need the monument table (several regions, or one
    sub-region; `ImageDbNew.scala:372`, `:547-552`).
- **Back to the gallery:**
  - The large view's button (`large/navigationButtons.scala.html:77`) and its Esc key
    (`large/fileKeyboardNav.scala.html:9`) both go to `RoundList.atUrl(pageId)`
    (`RoundList.scala:776-785`).
  - `listAtId` / `byRateAt` / `commentsAt` redirect to the page holding the file, with
    `#<pageId>` (`GalleryController.scala:196-197`).
  - The contest files' large view links its page (`large/contestFile.scala.html:48`).
- **What the fragment does:**
  - In the binary gallery, nothing: unitegallery's tiles have no `id`.
  - In the by-rate grid and the comments report, `id="@file.pageId"` makes the browser jump to
    the file. That is the auto-scroll the maintainer wants to avoid.

### 1.6 What the galleries do

| Feature | Today | In the shared tile |
|---|---|---|
| Opens the file's large view (keeps filter and order) | Unite: T3, a click handler on a `div`; by-rate: a link | `<a href>` holding the image (works without script, middle-click, Ctrl+click, Enter) |
| Binary vote ✓ / ✗, toggles, pressed state, Enter/Space | P11, T4, T5, `tile-votes.js addVote/vote` | Option `votes = thumbs`: server-rendered `<button aria-pressed>`, the same `vote()` |
| A rated round's own stars, worst mark, rate text | by-rate: `addListStars` (raty) in the rank line | Option `votes = stars`: the same stars in the caption; a one-criterion round's gallery shows them like any rated round (§3.1) |
| Rank, a criteria round's averages | by-rate rank line | Option `caption = rank`: the same line under the image |
| Organizers' tally | Unite: T2, P9, `addTally`; by-rate: `binaryTally(chip = true)` | Option `tally`: the server-rendered chip |
| One juror's vote, read-only; vote time; comments | Unite: `addVoteMark`, `addVoteTime`, `addComments`; by-rate: `snippets.voteChip`, `commentChip` | The server-rendered chips, in either place (option `chips`) |
| "A changed rate keeps its place" hint | by-rate | Kept, on lists with own stars |
| Deleted on Commons: red frame, badge to Commons | P7, P8, P9 | Server-rendered; the badge beside the link, not inside it; it links to the file's Commons page, with a second "request undeletion" link (Q7, §3.4) |
| Failed thumbnail: grey box, keeps its size | P6, `main.css:375-389`, `:509-522` | Box from `width`/`height`; `wlx-thumb-failed` from an `error` listener |
| Hover overlay, icons shown | upstream `UGTileDesign` | CSS `:hover` / `:focus-within`; always shown where `(hover: none)` |
| Zoom icon (decorative) | P11/upstream | Dropped (Q17): G-3 mock-ups try bigger Yes/No vote areas instead |
| Conflict-of-interest line (`data-coi`) | `contestFiles.scala.html:119-124` | Visible in `title`, visually hidden text in the link |
| Video mark | by-rate (`snippets.videoMark`) | Kept in the link |
| Keyboard | Unite: only the thumbs focus, invisibly (U11); by-rate: the link | Link and buttons focus, visible focus ring |

## 2. Option A (own tiles) and option B (lightGallery 3)

**Decided by the maintainer, 2026-11-19: A, our own tiles** (Q1). The comparison is kept for
the record. Facts read on 2026-11-19 from lightGallery's documentation, its npm entry and the
3.0.0 sources on unpkg (`lightgallery.umd.js`, `plugins/justified/lg-justified.umd.js`).
Nothing was installed.

- **Version and age.** 3.0.0, released 2026-10-09 after a week of betas; no patch since. The 2.x
  line ran 2021–2025.
- **License.** `(GPL-3.0-only OR LicenseRef-Commercial)`.
  - With no key, the default test key applies and a warning is logged.
  - An empty key logs an error.
  - The documentation's "free key for open-source projects by email" is unverified.
- **Size.** 18.6 KB gzipped core, 2.4 KB justified plugin, no dependencies.
- **Justified plugin.**
  - It reads ratios from `data-lg-size` or `width`/`height` and lays out at once.
  - With `justifiedReveal: 'image'`, tiles show one by one as their images load.
  - It places tiles through the CSSOM (allowed by our CSP) and writes `sizes`.
  - It relays out all tiles on `refresh()`.
  - It lays out non-dynamic galleries only, and keeps thumbnails hidden until laid out.
- **Core.**
  - `openGalleryOnItemClick` binds every trigger's click with `preventDefault()` and opens the
    lightbox. `lgBeforeOpen` can't cancel it, and only `dynamic` mode, which has no grid, skips
    it.
  - Its lightbox sets `style` attributes, which a strict `style-src` refuses.
- **Virtualization** covers the lightbox only. **Infinite scroll** is not built in.

**Why A:**

1. The tile's click belongs to lightGallery's lightbox, with no way to cancel it, and tiles must
   open the voting page.
2. Its layout needs script; ours is CSS.
3. It is GPL-3.0-only and needs a key, or a console warning.
4. It is a brand-new major, five weeks old with no patch release.

The row maths it would add is what flex-wrap does in CSS.

## 3. Design

### 3.1 One tile, with options

**One partial, `snippets/galleryTile.scala.html`**, renders a file's tile for every gallery.

- **Inside a page:** `snippets/galleryTiles.scala.html` wraps the tiles in a section (§3.6).
- **Who uses them:**
  - `gallery.scala.html` (binary galleries);
  - `galleryByRate.scala.html` (rated galleries and by-rate lists), rewritten around it;
  - `contestFiles.scala.html` (contest files and round review);
  - the chunk responses.
- **The options:** one Scala value, `views.TileOptions`, built per page. It is computed by the
  controllers from what they already compute today: `GalleryTiles.jury`, `TileStars.round`,
  `list.aggregateBinary`, `GalleryTiles.viewVotes`, `list.ranked`, `list.showsVoteTimes`.

| Option | Values | Binary gallery | Rated gallery / by-rate | Contest files, round review |
|---|---|---|---|---|
| `votes` | `thumbs` / `stars` / `none` | `thumbs` for a juror's own active round, else `none` | `stars` for a juror's own active rated round, one-criterion rounds included (a multi-criteria round shows averages only, `none`), else `none` | `none` |
| `tally` | on / off | on for all jurors' votes (`aggregateBinary`) | on for a binary round's by-rate of all jurors | off |
| `caption` | `none` / `rank` | `none` | `rank`: the rank (ranked lists), stars or rate text, criteria averages | `none` |
| `chips` | `overlay` / `caption` | `overlay` (lower left on the image, the 2026-11-15 decision) | `caption` (in the rank line, the 2026-11-16 decision) | `overlay` |
| `layout` | `rows` | `rows` | `rows` (one look everywhere, Q14) | `rows` |

**One-criterion rounds** (decided 2026-11-20): the gallery shows stars like any rated round
(`votes = stars`). The criterion's name is shown in the top bar's round menu, next to the round
name, never on a tile and never above the tiles. A multi-criteria round keeps showing averages
only on its tiles. This needs `TileStars.round` and the by-rate stars to accept a one-criterion
round (G-1b).

Sketch of a tile (attribute order and class names are for the PR):

```html
<div class="wlx-tile wlx-r-130" data-page-id="123" data-round-id="7" data-rate="1"
     data-w="4000" data-h="3000" [data-deleted …]>
  <a class="wlx-tile-link" href="/large/…">
    <!-- standard scheme (§3.5); the current scheme lists 333w, 500w, 666w -->
    <img src="…/330px-…" srcset="…/250px-… 250w, …/330px-… 330w, …/500px-… 500w, …/960px-… 960w"
         sizes="329px" width="266" height="200" loading="lazy" decoding="async" alt="File title">
  </a>
  <span class="visually-hidden" id="tile-title-123">File title</span>
  <div class="wlx-tile-votes">        <!-- votes = thumbs -->
    <button type="button" class="… wlx-vote-btn-select" aria-pressed="true" aria-label="Selected"
            aria-describedby="tile-title-123">…</button>
    <button type="button" class="… wlx-vote-btn-reject" aria-pressed="false" aria-label="Rejected"
            aria-describedby="tile-title-123">…</button>
  </div>
  <div class="wlx-tile-foot"><div class="wlx-tile-chips">…</div></div>   <!-- chips = overlay -->
  <div class="wlx-tile-caption rank-line wlx-byrate-line">…</div>          <!-- caption = rank: rank, stars, chips -->
  <a class="wlx-deleted-label badge …" href="https://commons…" target="_blank" rel="noopener">Deleted on Commons 2026-…</a>
  <a class="wlx-undelete-link" href="https://commons…/Commons:Undeletion_requests" target="_blank" rel="noopener">Request undeletion</a>
</div>
```

- **The link holds the image**, so the tile works before scripts, opens in a new tab, and Enter
  opens it.
  - `alt` is the file's title as text (Twirl escapes it once), with "Video:" before a video's,
    as by-rate does today.
  - The double-escaped caption HTML that Unite Gallery needed in `alt` and `data-description`
    goes away, and with it register items N8, N14 and N15.
- **Buttons, the caption's stars, the deleted badge and the undeletion link are siblings of the
  link**, never inside it (interactive content can't nest in `<a>`). A deleted file's tile has
  two links: the badge to the file's Commons page, where the reason is visible, and "Request
  undeletion" to Commons:Undeletion requests (Q7).
- **Bigger vote areas** (Q17): instead of a zoom or magnifier on the tile, G-3's mock-ups try
  larger Yes/No vote areas on binary tiles. Nothing beyond mock-ups is decided.
- **The vote buttons keep their names** "Selected" / "Rejected", which the browser tests and UI
  checks find them by. `aria-describedby` points at the file's title, so a screen reader says
  which file a button votes on.
- **The caption** is today's rank line (`showRateGalery` and `rankChips`), moved from
  `galleryByRate.scala.html:28-80` into the partial unchanged, with the same CSS classes
  (`.rank-line`, `.wlx-byrate-line`, `.wlx-byrate-stars`), so `addListStars` and the by-rate
  styles keep working.
- **Chips are always server-rendered** with the existing snippets (`snippets.voteChip`,
  `snippets.commentChip`, `binaryTally(…, chip = true)`). `tile-votes.js` only updates them
  after a vote.
- **`width`/`height`** are the displayed size at the base row height (200 px × aspect ratio),
  or the file's own size for a file less than 200 px high (`wlx-tile-small`, §3.2). A file with
  no stored size gets 4:3, as P6 does.
- **The ratio class** `wlx-r-NNN` (§3.2) is computed by the server from the stored size.
- **No `id` equal to the page id** on any element (`tile-title-<id>` is prefixed), so the
  `#<pageId>` fragment never makes the browser jump (§3.7).

### 3.2 One layout: justified rows, never enlarged (Q3, decided)

**Each tile's aspect ratio is a server-rendered class.** The class `wlx-r-NNN` gives the ratio
in steps of 0.05, from 0.30 to 4.00 (75 classes, written once in `main.css`, for example
`.wlx-r-130 { --r: 1.30; }`).

- The server **rounds the file's width ÷ height down** to the step, so the tile is never wider
  in proportion than the file. The image then fills it with `object-fit: cover`, losing at
  most one step's sliver at the sides (under 4 % for a 4:3 file).
- **Files taller than 0.30** (narrower ratio) take 0.30 with `object-fit: contain`: grey beside
  the image, never enlarged.
- **Files wider than 4.00** take 4.00, cropped at the sides.
- **No script and no style attribute set the layout.** The page is laid out correctly before
  any script runs, with no reflow when scripts start, and the CSP needs nothing. (An earlier
  draft set `--r` from script, which reflowed the page and left the layout wrong without
  script.)

```css
.wlx-tiles { display: flex; flex-wrap: wrap; gap: 5px; }
.wlx-tiles::after { content: ""; flex-grow: 1000000; }      /* a short row keeps its height */
.wlx-tile { flex: var(--r) 1 max(calc(var(--r) * 200px), var(--wlx-caption-min, 0px));
            max-width: max(calc(var(--r) * 247px), var(--wlx-caption-min, 0px));
            position: relative; }   /* no overflow: hidden, so no info line is clipped */
.wlx-tile-link img { display: block; aspect-ratio: var(--r); object-fit: cover; height: auto;
                     width: calc(100% - max(0px, var(--wlx-caption-min, 0px) - var(--r) * 200px));
                     max-width: calc(var(--r) * 247px); margin-inline: auto; }
.wlx-tile-small .wlx-tile-link img { width: auto; max-width: 100%; aspect-ratio: auto; }
```

**How the rows behave:**

- **Rows keep their height.** A tile grows in proportion to `--r` from a 200 px-high basis, so
  all tiles in a row reach the same height, and a full row fills the width.
- **Growth is capped at 247 px high** through the tile's `max-width`. A row that would need
  more ends a little short of the right edge.
- **A short row stays short.** The last row, or the only one on a page with few files, keeps its
  200 px height (the `::after` filler) and is not stretched to the page width. That ends §1.4
  item 2.
- **Never enlarged:**
  - `sizes` (§3.4) is the widest the image can be shown, the file's exact aspect × 247. Since
    the class rounds the ratio down, the tile is never wider than that.
  - **A file smaller than the row** (less than 200 px high) gets `wlx-tile-small`. Its `width`
    and `height` attributes are the file's own size, and the image is shown at that size,
    centred in its tile with grey around it, never larger than the file itself.
  - **A panorama wider than the page** shrinks to the page width, smaller, never larger.
- **Why 247:**
  - The common 4:3 file is then at most 329 px wide, so at 1x it lands on Wikimedia's 330 px
    width in the standard scheme.
  - The current scheme's widths stay at least 1x, 1.5x and 2x of `sizes`, despite
    `ImageUtil.resizeTo`'s truncation (§3.4).

**Info lines wider than the image** (Q14, decided). One look everywhere, with this rule: when a
tile's info line (the by-rate caption with its stars and rate text, or the chips) is wider than
its image, as for narrow or portrait files, the tile gets more width and spacing.

- **Tile width = max(image width, the info's minimum width).** The image stays at the width it
  would have had without the info (the `width: calc(…)` above), centred, with grey beside it. No
  image is widened, so the images in a row keep one height. The info is never overlapped or
  clipped: only the image is cropped (by `object-fit`, as always), never the tile or its info.
- **The caption's minimum width**, `--wlx-caption-min`, is a class per star count, in CSS: enough
  for the stars, the worst-mark button and the rate text (about 13rem for 10 stars). The chips'
  minimum is set the same way, from the widest chip set the tile can show.
- Tiles in a row take the row's tallest caption height (flex `stretch`), so captions line up.
- G-1b checks the row heights and the narrow-portrait case in its screenshots.

**Looks change:** the binary galleries' last rows and crops, and the by-rate list, from equal
boxes to rows. The screenshots change (R1).

**Why one layout and not the 300 × 250 box for by-rate** (Q14, decided: no view keeps another
look):

- The box crops every file to 6:5 (`img.cropped`). Rows show whole files, as the binary gallery
  does, and the no-stretch rule keeps them sharp.
- The box's merits were equal cells for comparing ranks by eye and room for the caption; the
  info's minimum width keeps the room, and each tile shows its rank.

### 3.3 Lazy loading

- **`loading="lazy"` and `decoding="async"`** on every thumbnail. The first chunk's first 8 are
  `loading="eager"`. No script is needed for lazy loading.
- **No layout shift.** `width`/`height` and the ratio class give each tile its box before the
  image arrives, shown in the page's grey in both colour modes.
- **Failed thumbnail:**
  - One `error` listener on the container in the capture phase (`error` doesn't bubble) adds
    `wlx-thumb-failed`. At start-up the script also marks images that already failed
    (`complete && naturalWidth === 0`).
  - The box keeps its size.
  - There are no inline `onerror` attributes, which would need `'unsafe-inline'` in
    `script-src`.
- **No "wait for all" and no hidden container.**
  - A stalled thumbnail delays only its own tile.
  - A script error leaves working tiles, laid out, and the pager.
  - A lapsed session shows grey boxes as now. The chunk requests (§3.6) then get a JSON 401,
    and the page says to sign in again.

### 3.4 Sharp images

**Decided (Q3):**

- a tile is never shown larger than its thumbnail (§3.2);
- the tiles' **markup** lists only this server's proxy URLs, never `upload.wikimedia.org`;
- which widths a page lists depends on its contest's size scheme (§3.5).

**What Q3 doesn't change: the proxy's redirects.** A miss it can't fill, or a failed fetch,
is still answered with a redirect to Wikimedia (`ImageProxyController` `wikimedia` /
`onWikimedia`, 303). For that one request the browser then loads from `upload.wikimedia.org`,
as today.

**Where sizes are fetched.** The browser never asks Wikimedia for a tile size. The server
fetches from Wikimedia, through the proxy and at its throttled rate, and caches what it fetched:
the 500 px source (or the file's own width when narrower) on a first miss or a prefetch, a
960 or 1280 px size only for a juror whose screen needs it (§3.5, "Screen profile"). The one
exception is the proxy's redirect above.

**Candidates**, width descriptors through the proxy:

- **Current scheme:** the file's three cached tile widths, `image.resizeTo(250)`,
  `resizeTo(375)` and `resizeTo(500)`, exactly what `Global.thumbnailWidths` lists (truncated,
  as `ImageUtil.resizeTo` does). A 4000 × 3000 file: 333w, 500w, 666w.
- **Standard scheme:** 250, 330, 500 and 960, **plus 1280 for files wider than 1.9:1**. Without
  it, a panorama's `sizes` (up to 4.00 × 247 = 988) would pass the widest candidate even at
  DPR 1, and the file would be enlarged. The same 4000 × 3000 file: 250w, 330w, 500w, 960w.
- **In both schemes:**
  - Only widths below the file's own are listed.
  - A file narrower than the smallest candidate keeps today's `src` (the file itself) and no
    `srcset`.
  - A file of unknown width (0) gets `src` only, with no `srcset` or `sizes`, as
    `Global.resizeTo` gives it now.

**`sizes` = exactly `ImageUtil.resizeTo(width, height, 247)` px**, the widest the image can be
shown (§3.2). For a `wlx-tile-small` file, `sizes` is the file's own width. The browser divides
each candidate's width by `sizes` to get its density, but **the engines choose differently**:

- **Firefox** takes the candidate with the smallest density at least the screen's pixel ratio
  (DPR), or the widest there is.
- **Chromium** does the same at DPR 1. Above it, it takes the next lower candidate when the DPR
  is below the geometric mean of the two densities around it, √(lower × upper). That saves
  bytes and accepts a slightly soft image.
- **WebKit** is not known here; G-0 (e) measures all three, and the browser tests then assert
  each engine's own choice.

**4:3 file, `sizes` = 329:**

| Screen | Current scheme (all engines) | Standard, Firefox | Standard, Chromium |
|---|---|---|---|
| DPR 1 (needs 329) | 333w (1.01x) | 330w (1.003x) | 330w (1.003x) |
| DPR 1.5 (494) | 500w (1.52x) | 500w (1.52x) | 500w (1.52x) |
| DPR 2 (658) | 666w (2.02x) | 960w (2.92x) | **500w (1.52x)**: 2 < √(1.52 × 2.92) = 2.11 |
| Phone, DPR 3 (987) | 666w, the widest (2.02x) | 960w, the widest (2.92x) | 960w, the widest (2.92x) |

**3:4 file, `sizes` = 185**, standard scheme at DPR 2: Firefox takes 500w (2.70x), Chromium
**330w (1.78x)** (2 < √(1.78 × 2.70) = 2.19).

**The trade-off (Q12, decided: accept).** In the standard scheme, Chromium (most users) shows
DPR-2 tiles slightly soft (1.5–1.8x), because no standard width lies between 500 and 960. The
maintainer accepts this; it is revisited after G-0 (e)'s measurements. The alternative, leaving
500 out of the tiles' `srcset` so that DPR 1.5 and 2 take 960 in every engine (sharper, about
twice the bytes), stays available. The current scheme has no such gap: its widths sit at 1x,
1.5x and 2x.

**Clean densities in the current scheme.** Its widths come out just above 1x, 1.5x and 2x for
every aspect ratio, because `trunc(a × 250) ≥ trunc(a × 247)`, and so on. A rounded-up `sizes`
(334 for a 333 px thumbnail) would put them just under, and the engines would take the next
candidate.

**Overshoot in the standard scheme.** The ladder is fixed (250, 330, 500, 960), so in Firefox a
tile gets between 1x and about 1.9x the pixels it needs (a 3:2 file, `sizes` 370, takes 500w at
DPR 1). 247 makes the most common ratio, 4:3, land on 330 at 1x and 500 at 1.5x. G-0 (e)
measures the bytes per page in both schemes and all three engines.

**Zoom.** Chromium and Firefox choose again on page zoom, up to the widest candidate. Whether
WebKit does is G-0 (e).

**Files deleted on Commons (Q5–Q7, decided).** Their `srcset` lists **the widths actually on
disk**, of either scheme or any older one (Q5: show whatever is on disk). These thumbnails are
never removed (Q6: no switch and no cleanup removes them; the cleanup fails closed, §3.5) and
never fetched from Wikimedia (Q7).

- The controller lists the directories of the page's deleted files (`Files.list`, a few
  directories per page; the "deleted" tab has at most a page of them) and passes the widths to
  the template. There is no in-memory index; the 512 MB production heap is left alone.
- **The proxy serves any width that is on disk**, before its `servedWidths` redirect, so a
  listed width is always answered with its file.
- Never the original upload URL (`resizeToPx` returns it when a width reaches the file's own;
  the file is gone) and never a Wikimedia URL.
- With no width on disk, the box shows as failed, as now.
- **The deleted mark** links to the file's Commons page, to see why it was deleted, and a second
  link, "Request undeletion", leads to Commons:Undeletion requests (Q7).

**The comments report's thumbnails** (§3.11) use the same image partial with their 300 × 250 box,
`sizes` = `ImageUtil.resizeTo(width, height, 300, 250)`:

- **current scheme:** the box's cached widths at 1x, 1.5x and 2x;
- **standard scheme:** 250/330/500/960.

### 3.5 Two size schemes (Q4, decided 2026-11-19)

**The two schemes:**

- **Current** (`legacy`): today's widths, `Global.thumbnailBoxes`.
  - Heights 250/375/500 at any width for the tiles.
  - The 300 × 250 box and its 1.5x and 2x for the comments report (and the by-rate grid until
    G-1b).
  - The 150 × 120 box and multiples for the large view's thumbnail bar.
  - All are truncated by aspect ratio, so they differ per file.
- **Standard** (`standard`): Wikimedia's widths (`Global.wikimediaSteps`), the same for every
  file and view: **250, 330, 500 and 960, plus 1280 for files wider than 1.9:1**, below the
  file's own width.
  - The thumbnail bar takes 250 at 1x (150 px shown) and 330 at 2x.
  - To fill 250, 330 and 500, the cache fetches one Wikimedia width, 500, and **scales the
    smaller ones down here**, as `fetchAndCacheAll` does today. Only that fetch hits Wikimedia's
    own thumbnail cache at a standard width.
  - 960 and 1280 are fetched and stored when first requested (Q11).

**Where the scheme lives: a column on the contest**, `contest_jury.thumb_sizes`, `legacy` or
`standard`.

- **Migration:** the next free number when G-1a merges (no number is named here; other plans
  have reserved the numbers just above the last merged one). G-1a adds its reservation to the
  roadmap's migration list ("Next free").
- **Reading it.** The `ContestJury` case class is not widened: that would touch
  `api/JsonFormat`, `ApiSpec` and every constructor. Instead:
  - a small read `ContestJuryJdbc.thumbSizes(contestId)` and `setThumbSizes`;
  - behind them, an in-memory map contestId → scheme, refreshed after a minute and cleared for a
    contest when its scheme flips.
  The galleries, the large views (`LargeViewController` doesn't read the contest row today)
  and the contest files page all read through it, so a page costs at most one tiny query a
  minute per contest.
- **Defaults.**
  - Existing contests get `legacy`.
  - **New contests are created as `legacy` until G-3 and as `standard` from G-3 on** (Q10).
    Between G-1a and G-3 the binary galleries still render through Unite Gallery, with one
    `src` per tile and no `srcset`, which has no defined mapping to the standard widths.
- **It is per contest, not per cache directory.** A directory belongs to a Commons file shared by
  every contest the file is in (§1.2). A file in a `legacy` contest and in a `standard` one
  simply holds both sets.

**How the views pick widths.**

- Every page that shows thumbnails knows its contest: the round's for the galleries, the comments
  report and the large view, and the page's own for contest files and the round review.
- It passes the contest's scheme (from the map above) to the URL helpers, new in `Global`:
  `tileSrcSet(image, scheme)`, `boxSrcSet(image, scheme)` and `stripSrcSet(image, scheme)`.
- `/thumb_urls` lists the contest's scheme's widths.

**How the proxy and the cache serve each scheme.** A proxy URL names a file and a width, not a
contest.

- **Served widths** (`servedWidths`) become the union: the current scheme's widths, the standard
  widths and the large view's fallback. Before that check comes **any width on disk**, which is
  served as it is (§3.4).
- **On a miss**, `fetchAndCacheAll` fills one set:
  - **the current set if the width is one of the file's current widths**, checked first;
  - otherwise the standard set, if it is a standard width.
  Several widths are in both: 250, or 500, which is a 4:3 file's height-375 width and a 1:1
  file's height-500 width. Checking the file's current widths first keeps a `legacy` contest's
  misses from filling the standard set.
- **The 960 and 1280 px files are stored on demand** (Q11, decided): a contest's full download
  or switch does not fetch them for every file. They are fetched for the jurors whose screens
  need them (below), and any remaining miss fetches once through the proxy and is cached.
  Filling 250, 330 and 500 needs one Wikimedia width, 500, and scales the smaller two here.
- **`localSourceFile` scales from any cached width at least as wide as the target, in either
  scheme**, read from the registry, not only from the target set's own widths. So filling the
  second set for a file never fetches from Wikimedia again when a wide enough file is on disk.
  Only a width wider than everything cached needs a fetch.
- **"Download images locally" never switches the scheme** (Q8, decided). A run, for the whole
  contest or for a round (`startDownloadForRound`), fills the files that are missing **at the
  contest's current sizes**, whichever scheme that is.

**Screen profile and prefetch** (decided 2026-11-23). Only standard contests list 960 and 1280.

- **A coarse profile per account**: a density class (1x, 2x or 3x) and a rough width bucket (for
  example phone, laptop, large). It is not a fingerprint: two values, no exact sizes. The script
  reports it when it changes; it is stored in a small table (a Flyway migration, numbered the
  next free one at merge).
- **When files are distributed to a juror with a known profile** (a new round, a top-up, "Add a
  juror", "Distribute new files"), the distribution job queues a **paced prefetch** of the sizes
  that profile needs for that juror's files, at `wlxjury.thumbs.rate-per-second`.
- **A juror without a profile:** the prefetch is queued on the first visit, once the profile is
  reported.
- **Any miss left** is fetched once through the proxy and cached.

**The switch** (Q8, Q9, Q10 and the 2026-11-23 decision):

- **New contests** start in `standard` once G-3 has shipped (Q10, below).
- **An existing contest switches only by its own admin button, "Switch to standard sizes"**,
  separate from "Download images locally". The run **scales the standard sizes from cached
  images that are wide enough** and downloads only the files that have no wide-enough source;
  there is no full re-download. Scaled thumbnails differ slightly from the ones Wikimedia makes;
  at these sizes the difference is small. The run asks for the standard set explicitly (the
  current-set-first rule of a miss does not apply to it; a test covers it). The column flips to
  `standard` when the run **completes**.
  - "Completes" means the download stream ends. Per-file errors are counted, as today, but
    don't stop it.
  - An interrupted run or a server restart during it means no flip; the contest stays as it
    was.
- **What the switch costs, told before it starts:** the number of files that need a download
  (the rest is scaled here) and the extra disk. **Unmeasured until G-0 (f):** the plan had
  assumed about an hour per 40,000 images and about 6 GB for 38k files, which holds only if all
  were downloaded and all 960 px sizes stored. The button shows the counts.
  - The admins' guide's advice to download before a round opens says the same for a `legacy`
    contest.
- **Races are harmless.** The proxy serves both schemes' widths at all times, so a page rendered
  just before the flip and its thumbnails fetched after it, or the other way round, are always
  answered.
- **Never automatic**, not even when a cache is empty: checking that means a registry scan over
  every file of the contest, and a running contest's cache is rarely empty.
- **No way back in the UI.** Root can set the column; both sets stay on disk, so it works at
  once.

**Cleanup, redone so that no scheme deletes another's files, failing closed:**

- **The marker.** `.cache-sizes` becomes a versioned description of both schemes,
  `v2 legacy=<thumbnailBoxes> standard=250,330,500,960,1280`. The first start after deploy sees
  a new marker and runs the cleanup once.
- **Deleted files first, or nothing.** The run starts by reading the files deleted on Commons
  from the database and skips their directories entirely. **If that read fails, there is no
  cleanup and no marker is written**, so it runs again at the next start.
- **`unusedFiles`, per directory:**
  - It keeps any standard width.
  - It keeps any width matching the current scheme's widths, computed from the **aspect ratio
    only** (the widest file's height-to-width ratio, with the existing tolerance), never capped
    at the widest file's width. The old rule would have treated a directory holding only 330
    and 500 standard files as an original 500 px wide.
  - **It never deletes a directory's widest file**, whatever its width.
  - It deletes only widths in neither scheme, which today means none.
- **Why "never the widest":** a file can be deleted on Commons without being marked yet.
  `deleted_at` (V63) is set only after a failed fetch and the check that follows. Its only copy
  may be a single odd-sized file, and keeping the widest file of every directory keeps that
  copy.
- **The current scheme's files of a contest that switched stay on disk** while any contest uses
  the current sizes (Q13, decided). Another contest may still use them, and a directory doesn't
  know its contests. Once none does, removing them is a separate ops step, decided then.
- **Deleted files' thumbnails are never removed** by the switch or the cleanup (Q6, decided).

**`largestCachedWidth` and a deleted file's large view**, in both schemes:

- **Today** it is the widest **listed** width below the file's own (`Global.scala:99-100`), not
  necessarily on disk. Once the standard widths are listed, a 3:4 deleted file would get 500,
  while its directory holds only the current scheme's 375, and its large view would break
  (`large/contestFile.scala.html:115-121`).
- **It becomes the widest width on disk**, from a `Files.list` of the file's directory (one
  directory, in that view).
- `largestCachedThumbnail`, `largestCachedWidth` and `cachedSrcSet` move from `Global` to
  `LocalImageCacheService`. Their only caller, the contest file's large view
  (`large/contestFile.scala.html:89`, `:115`, `:121`), gets the values from its controller.

**The large view's thumbnail bar** uses the contest's scheme (`stripSrcSet`). Its main image keeps
its direct Wikimedia `srcset` and its fallback through the proxy at `largeFallbackPx`, as today.

### 3.6 Pages and continuous scrolling

**Which galleries:** every tile gallery: binary, rated and by-rate, contest files. The round
review offers the same choice (Q22, decided).

**The choice: Pages or Continuous** (Q16, Q18 and Q20, decided).

- The view menu has a **Pages / Continuous** choice. **Pages is the default**: the pager as
  today, no chunk loading.
- **It is remembered per person, in the account.** That needs storage, so the first PR that
  builds it carries a Flyway migration, numbered the next free one at merge. The view menu reads
  it with the page, and a change is saved by a CSRF-protected POST. The browser's own storage is
  not used (it would not follow the person between devices).
- **Windowing** (removing far tiles) may become a third option later, if it is not too complex
  to maintain (G-5b, below).

**Batches and pages** (Q15, decided; window size and page links decided 2026-11-23).

- **A batch is one screen**, and so is a page. The script computes how many tiles fill the
  viewport (its width and height, the 200 px row height) and stores the window size in a
  **cookie**. The server renders every later page at exactly that many tiles, capped to 10..80.
  Where the browser supports them (Chromium), the viewport client hints (`Accept-CH`,
  `Sec-CH-Viewport-Width`) let the server size the first page of a visit too. The first visit
  without a cookie or hint, and a browser without script, get the default of about 24; if that
  doesn't fill the screen, the script tops up with a chunk. Chunk requests send `n` explicitly.
- **Page links carry the batch size** (`?n=…`) once it is set: the pager, `atUrl` /
  `listAtId` (the large view's Back and Esc) and the navbar pager's links all honour it, so a
  shared or reloaded link shows the same files, and Back from the large view lands on the same
  page even if the window has changed since.
- The first chunk of a page is exactly that page of the pager, so every URL stays meaningful
  without script: `/gallery/round/7/user/12/page/5?n=24…` is both "page 5" and where continuous
  scrolling starts.
- **Tile galleries get one size rule**: binary, rated, by-rate, contest files and the round
  review. The comments report and the file list keep their page sizes.

**Continuing by key, not by offset.** A chunk after the first must not be "page n+1 by
`OFFSET`":

- A juror under the Unrated filter votes the tiles on screen (a batch) and scrolls. The voted
  files have left the filter, so `OFFSET n` skips the next `n` unvoted files.
- By-rate, latest-vote and oldest-vote orders shift the same way when the juror votes.
- "Distribute new files" adds rows mid-list, which `OFFSET` would show twice.

**So each chunk continues from the key its last row had when it was rendered.**

- **The cursor travels in the URL.** When the server renders a chunk, it writes the last row's
  values of the list's order columns into the next chunk's URL, `after=<pageId>&k=<v1>&k=<v2>…`.
  For example, in latest-vote order:
  `k=<unrated 0|1>&k=<vote time ms>&k=<monument id>&k=<page id>`.
- **Chunks never call `orderKey`.** It would read the values as they are at request time; after
  a vote on the chunk's last tile, the next chunk would start from the tile's new place and
  jump back.
- **The server's side:**
  - It parses each `k` by its column's type, in `ListOrder.columns` order, and refuses a wrong
    count or an unparseable value with JSON 400.
  - It binds the values as SQL parameters, never as SQL text.
  - It reads the next `n + 1` rows with `Keyset(values, before = false)` (the row at the
    key and those after it) plus `s.page_id <> after`. The order columns end in `s.page_id`, so
    no other row has the same key.
  - A tampered key only selects another slice of the same list. The list's conditions (juror,
    round, filters) are unchanged.
- **Which lists continue by key:** every list of one juror in one round that isn't grouped,
  `userId.isDefined && roundId.isDefined && !grouped`. G-5 checks whether `groupWithDetails`
  lists must be left out too.
  - The carried key's conditions are on `s.*` columns only, so they don't need `keyed`'s
    monument-table restriction (`ImageDbNew.scala:372`).
  - That includes a juror's list in a sub-region round, and a juror's by-rate list. A rated
    round's by-rate order is the rate, then file order; a juror's change of rate is the case
    the carried key exists for.
- **Lists that don't continue by key** are the organizers' all-jurors views (grouped, binary or
  by-rate) and the contest files. They continue by `OFFSET` and can change while the viewer
  scrolls (jurors voting, an import, "Distribute new files"), so a file can appear twice or be
  skipped there.
- **The script drops tiles whose `data-page-id` is already on the page**, so a repeat is
  harmless. A skip in those views is accepted: they are the organizers' overviews, and a reload
  shows the list as it now is.
- **By-rate's ranks per chunk.** A ranked list's chunk computes its files' ranks as the page
  does (`list.ranks(files)`, one grouped `rankCounts` query; `GalleryController.scala:220-223`).
  A criteria round's chunk reads its criteria averages for its files (`criteriaResults`, one
  read). So a by-rate chunk costs about what a by-rate page costs today, without the navbar's
  counts.

**Page markup** (all server-rendered):

- **Sections.** `<section class="wlx-tile-chunk" data-page="5" data-first="161" data-last="200">`
  wraps each chunk's tiles, inside one flex container, with `display: contents` on the section so
  rows flow across chunks.
  - `data-page`, `data-first` and `data-last` are nominal for chunks after the first: the first
    chunk's page plus one per chunk.
  - After in-page votes under a filter they can be off until a reload (R10).
- **At the end:** a "Next files" link (`rel="next"`), the fallback without script.
  - For a list that continues by key, its `href` is `pageUrl(page + 1)` with the same
    `after=…&k=…`. The page action honours them and computes its range with one
    `countBefore`, so votes don't skip files even without script.
  - Otherwise it is `pageUrl(page + 1)`.
  - It carries `data-chunk-url`, the next chunk's URL (§4).
- **At the start**, on pages after the first: a "Show earlier files" link to `pageUrl(page − 1)`.
  Its `data-chunk-url` carries the **first** tile's key as `before=<pageId>&k=…`. The server
  reads `Keyset(values, before = true)` nearest first, takes `n` rows and reverses them,
  as `RoundList.around` does (`RoundList.scala:540-558`). Lists that don't continue by key use
  `OFFSET`.
- **Before the tiles:** a skip link "Skip to the end of the files", visually hidden until
  focused. It moves focus to the status line after the tiles.
- The navbar's pager stays as it is.

**Script** (`gallery-tiles.js`, plain DOM, no jQuery). The loading, URL and earlier-files parts
below run only in Continuous; the failed-box and last-viewed parts run in both.

1. **Loading.** An IntersectionObserver on the "Next files" link, with a root margin of about one
   screen, fetches `data-chunk-url` with exactly `X-Requested-With: XMLHttpRequest` (the value
   `Secured.isScriptRequest` checks, `Secured.scala:697`).
   - **What gets inserted.** Only a response that is `ok`, `text/html`, carries the header
     `X-Wlx-Chunk: 1`, and after parsing holds exactly one `section.wlx-tile-chunk`. Anything
     else, an HTML page included, is never inserted.
   - A JSON refusal (401, 403, 404, 409 `round_paused`, the round state) stops loading and shows
     its text from `voteTexts()`. Any other non-OK answer shows a generic "Couldn't load more
     files" and leaves the link for a retry.
   - **Parsing.** The chunk is parsed with `DOMParser` (inert: no script runs) and its nodes are
     moved in. `RawHtmlSinksSpec` gets `DOMParser`/`parseFromString` in its `DomRaw` sinks and
     lists `gallery-tiles.js` with what it inserts.
   - **After inserting:**
     - tiles already on the page are dropped;
     - on lists with own stars, `addListStars` runs on the new section.
   - One fetch at a time. An aborted or failed fetch leaves the link for a click.
   - **Keyboard:** while focus is on the end link (reached by Tab), it doesn't load by itself;
     Enter loads it. So Tab can pass it and reach the footer. The cap also ends auto-loading.
2. **URL.**
   - An IntersectionObserver on the sections keeps the URL at the nominal page of the topmost
     section in view, with `history.replaceState`, never `pushState`.
   - On a tile link's plain click (not a modified or middle click), the script replaces the URL
     with that file's `atUrl` (`/pageid/`, which redirects to the file's current page +
     `#<pageId>`). Browser Back from the large view then lands on the right page, even after
     votes, and marks the tile.
   - Contest files have no `/pageid/` route and don't change by voting: there it is the
     chunk's page + `#<pageId>`.
   - Esc and the large view's Back already go to `atUrl`.
3. **Scroll restoration.** `history.scrollRestoration = 'manual'` on these pages. After a Back
   the page starts at the file's page, so the browser's remembered offset would point elsewhere.
   The mark and the jump button take its place.
4. **The navbar pager** follows the topmost section: its range text from `data-first`/`data-last`,
   and its previous/next links set to that page ± 1. The page box is unchanged.
5. **Earlier files.** A click on "Show earlier files" loads the chunk before the first tile (by
   key, `before=`) above. The script keeps the view in place by measuring the first visible
   tile before and after the insert; WebKit has no CSS scroll anchoring to rely on. It never
   loads upwards by itself (Q16, decided: simple now, downwards plus "Show earlier files").

**Very large rounds.** A screening round can give one juror all 38k files (about 1,600 chunks of
24 tiles).

- Native lazy loading keeps far-away images from loading, and browsers drop the decoded data of
  off-screen images. The DOM is what grows: about 12 nodes per tile, more with a caption.
- **Cap (Q18, decided by measurement):** G-0 (g) measures 100, 250, 500, 1,000 and 2,000 tiles
  (memory, scroll smoothness, vote response; Chromium and Firefox; a modest-laptop and a phone
  profile). The cap is set below the point where it degrades and rechecked in G-5b; about 480
  tiles is a placeholder until then. At the cap the script stops loading by
  itself and leaves the end link, "Continue with the next files", which opens them as a fresh
  page, with `after=…&k=…` where the list continues by key.
- **Windowing** (removing far chunks and leaving a spacer of their height) is not in the first
  build: justified rows change height on every resize, which makes spacers fragile.
  **Spike G-5b** (after G-5) compares a windowing library with our own windowing on the fixture:
  code size, resize behaviour, scroll anchoring, keyboard and screen readers. If it is not too
  complex to maintain, windowing becomes a third choice beside Pages and Continuous.
- **Keyset reads stay fast at any depth**, unlike `OFFSET`. G-5 measures chunks at pages 1, 100
  and 900 on the Gatling fixture.

**Load on Wikimedia.** Each batch can start as many proxy misses at once as it has tiles (10 to
80), from the server's one IP address. A 429 backoff then stalls those tiles: only their own
boxes, not the page.

- G-0 (c) measures a cold, fast scroll.
- **A queue for proxy misses in `ImageProxyController` only if stalls are observed** (Q24,
  decided); no queue is built before then.
- Either way, the admins' guide advises "Download images locally" before a round opens.

### 3.7 Position without auto-scroll

- **What is "last viewed":** the file in the fragment `#<pageId>`.
  - The large view's Back and Esc set it (`atUrl`), and so does the tile click (§3.6).
  - Tiles carry `data-page-id`, not `id="<pageId>"`, so the fragment matches no element and the
    browser does not jump. The script finds the tile and marks it.
- **The mark:** the class `wlx-last-viewed` (a 3 px outline in the theme's focus colour) and a
  small "Last viewed" label in the tile's top-left corner.
  - The label is a server-rendered translated text on the container, put into a `span` and
    announced once (`role="status"`).
  - The mark stays until another tile is opened.
- **Off-screen:** a floating button "↓ Last viewed file" (or ↑) appears at the bottom right.
  - A click scrolls the tile into the middle of the view (`scrollIntoView({block: 'center'})`)
    and moves focus to its link.
  - The button hides while the tile is in view.
- **Room for the reappearing top bar:** `.wlx-tile, .wlx-comments-row { scroll-margin-top:
  var(--wlx-topbar-height, 0px); }`. The top-bar PR sets the variable.
- **No automatic scroll** anywhere: no `scrollIntoView` on load, and no `id` equal to the
  fragment.
- **One script for every list** (`last-viewed.js`): all tile galleries, by-rate included since
  it is a tile gallery now, and the comments report (Q19, decided: yes), so every list brings
  you back the same way. The comments report's `id="@file.pageId"` becomes `data-page-id`; G-4 checks with `rg` that those `id`s serve only as fragment targets.

### 3.8 Keyboard and accessibility

- **Tab order** per tile:
  - the link (image and title);
  - ✓ and ✗, or the stars;
  - the chips' links;
  - the deleted badge and the "Request undeletion" link.
- **Button names** stay "Selected" / "Rejected", described by the file's title. The stars keep
  raty's own labels, as today.
- **Visibility:** the vote buttons show on `:hover` and `:focus-within`, and
  always on touch screens (`@media (hover: none)`). A focused button is fully opaque (fixes U11).
  The caption's stars are always visible, as today.
- **Focus ring:** `:focus-visible` on the link (the whole tile) and on each button, at least 3:1
  against the photo (an inner white and outer dark ring), in both colour modes.
- **Hit areas** stay at least 24 × 24 px.
- **The status line** under the tiles is `role="status"`.
- **The footer stays reachable:** the skip link, no auto-load while the end link has focus, and
  the cap.
- **Section labels:** each chunk section gets an `aria-label` ("Files 161–200"). Sections are
  `display: contents`, which older WebKit dropped from the accessibility tree; G-5 checks it in
  WebKit and drops the labels if they aren't announced.

### 3.9 Votes (`tile-votes.js`)

- **Binary thumbs** (`votes = thumbs`) today work from Unite Gallery's `item` objects. After the
  change:
  - They read the tile's `data-*`.
  - One delegated `click` listener on the container serves the page's tiles and appended
    chunks, through `vote()`, `pressed()` and `busy()`, with the vote-time and vote-chip updates
    after a vote.
  - Enter/Space come free with `<button>`.
- **Stars** (`votes = stars`): `addListStars` keeps working on `.wlx-byrate-stars` inside the
  caption.
  - Its `.closest('.gallerybox')` (`tile-votes.js:415`) becomes `.closest('.wlx-tile')`.
  - It runs again on each appended chunk.
  - The "changed rate keeps its place" hint stays.
- **A tile voted out of the list's filter** stays shown with its new vote until a reload, as now
  (Q25, decided; to be revisited after a juror trial). The carried key keeps the next chunk
  right either way.
- **Unchanged:** the CSRF header (`data-csrf-token`) and the error texts (`voteTexts`).

### 3.10 CSP

- **Remaining inline `style` after Unite Gallery goes:**
  - the templates `pageLinks`, `round/edit/imageFiltering` and `snippets/jobStatus` (UI U2);
  - `display:none` attributes in a few templates;
  - possibly libraries (Bootstrap's JS, Tom Select, raty).
- `conf/application.conf:129-130` names them as the reasons. So `'unsafe-inline'` cannot go in
  the same PR.
- **Step G-7** (Q23, decided: remove `'unsafe-inline'` from `style-src` after one Report-Only
  release):
  1. Classes instead of the three templates' `style` attributes.
  2. **One release with a `Content-Security-Policy-Report-Only` header** without
     `'unsafe-inline'` in `style-src`. Report-Only means the browser reports what the stricter
     policy would block but blocks nothing. Reports go to a small endpoint, or are caught by the
     browser tests' `securitypolicyviolation` listener.
  3. Remove `'unsafe-inline'` when clean. Update `ContentSecurityPolicySpec`, secure-coding §5 and
     UI U2.

### 3.11 Views

| View | Shared tile (options) | srcset/lazy, schemes | Continuous (when chosen; Pages by default) | Last-viewed mark |
|---|---|---|---|---|
| Binary gallery, a juror's own | yes (`thumbs`, chips overlay) | yes | yes, by key | yes |
| Binary gallery, organizers (one juror's; all jurors' with tally) | yes (`none`, `tally` for all) | yes | yes (one juror's by key; all jurors' by offset) | yes |
| Rated gallery and every by-rate list | yes (`stars` or `none`, `caption = rank`, chips in caption) | yes | yes (Q20, covered by the choice): a juror's by key, all jurors' by offset | yes |
| Contest files (all, deleted, COI, a round's deleted) | yes (`none`) | yes | yes, by offset | yes |
| Round review | yes (`none`) | yes | yes, the same Pages / Continuous choice (Q22) | yes |
| Comments report | **no**: its own article rows (below) | the shared image partial | no | yes (Q19) |
| File list | no (a table) | — | no | no |

**Why the comments report is not a gallery.** Each row's main content is the file's comments, of
any length, with their authors, rounds and links. The thumbnail is a small handle beside them, not
the content. In tiles, the comments would have to be cut or hidden behind a click. That is the
opposite of what the report is for: reading the discussion in one place. It shares the tile's
image partial (`srcset` per scheme, lazy loading, the failed box) and the last-viewed mark, so it
looks and behaves the same where it overlaps.

## 4. Server and API changes

**One chunk route per tile gallery, through the same action code.** Chunks must be authorized
exactly as their page, so they run the same path, not a copy.

**The gallery and by-rate chunks.** `GalleryController.listGeneric(view, asUserId, at, region,
roundId)` gains a `part` mode (`Page` / `Chunk(cursor)`).

- **Routes:**
  - `GET /gallery/round/:round/user/:user/region/:region/page/:page/tiles?after=…&k=…` (or
    `before=…&k=…`) → `listGeneric(Some(GalleryView.Gallery), …, part = Chunk(cursor))`.
  - `GET /byrate/round/:round/user/:user/region/:region/page/:page/tiles?…` →
    `listGeneric(Some(GalleryView.ByRate), …)`.
  - The page routes accept the same optional cursor (§3.6, "Next files" without script).
- **The cursor** is parsed by the order's columns, bound as parameters, and never looked up with
  `orderKey`. A wrong count or type → JSON 400.
- **Checks.** Everything in `gallery()` runs unchanged before rendering
  (`GalleryController.scala:148-202`):
  - `viewerRounds` and `refusal` (denied → 403, round state);
  - `pausedFor` (409 `round_paused`);
  - the comments rule;
  - `viewAs` (another juror's votes only for `canViewOrgInfo`, only of a juror of the round,
    else 404).
- **Every answer in chunk mode that isn't a chunk is JSON.** Most refusals already answer a
  script request with JSON. Chunk mode changes the rest:
  - **Round 0** (the user's active round) gets `noRoundPage`, a 200 `text/html` page
    (`GalleryController.scala:139-141`). Chunk mode refuses round 0 with JSON 404.
  - **No such round** (`:142`), **an unknown view or no such contest** (`:188-190`): JSON 404.
  - **`viewAs`'s refusal**, rendered with `viewAsRefused(…, json = false)` (`:186`), passes
    `json = Secured.isScriptRequest(request)`.
  - **A gallery-view chunk** renders the shared tiles for both binary and rated rounds. A rated
    round's gallery is its by-rate list, so its chunk has `caption = rank`, as its page. No case
    falls through to a full page.
- Only `galleryOf` picks the chunk partial over the full page, and **only a chunk carries
  `X-Wlx-Chunk: 1`**.

**The contest files chunk.**

- `GET /contest/:contestId/files/tiles?page=…&…`, placed **before**
  `/contest/:contestId/files/:pageId` in `conf/routes` (`:214`). After it, `tiles` would match
  `:pageId`, and the `Long` binder's failure gives a 400; Play doesn't fall through to a later
  route.
- It shares `contestFiles`'s body but is built with `withAuthJsonOn(blocking)` (same
  `contestPermission`). `withAuthOn` would answer a refused permission with the HTML 403 page
  (`Secured.scala:210-222`).
- Its not-found answers are JSON 404 in chunk mode, and only its chunk carries the marker.

**Rendering a chunk:** `snippets/galleryTiles` in chunk form: the `<section>` and the next link.
No navbar or menus, and no counts the chunk doesn't show.

- **The batch size** is the request's `n` (tiles per screen, Q15), parsed and capped to 10..80
  by the server. A missing `n` takes the window-size cookie, then the viewport client hint, then
  the default of about 24; a bad `n` takes the default. Every page link the server writes (the
  pager, the navbar pager, `atUrl` / `listAtId`, "Next files", "Show earlier files") carries
  `n`.
- A list that continues by key reads `n + 1` rows after the carried key to know `hasNext`, and
  writes the last shown row's key into the next link. Other lists read `Limit(n + 1, offset)`.
- A ranked list computes its chunk's ranks (`rankCounts`). A criteria round reads its chunk's
  averages.
- `commentCounts` is the one grouped query the page already runs.
- The juror counts, place menu and Votes menu (lazy in `galleryOf`) are not touched.
- **No sort cookie:** a chunk does not set `RoundList.remembered`.

**Headers and switching:**

- `noStore`; `text/html` with `X-Wlx-Chunk: 1`; `RequestTiming` label `gallery chunk`.
- **Never switches contest:** `Secured.switchingContest` acts only on page requests
  (`isPageRequest`), and a fetch with `X-Requested-With: XMLHttpRequest` isn't one.

**Size schemes** (§3.5):

- a Flyway migration for `contest_jury.thumb_sizes`; contest creation sets `standard` from G-3
  on (Q10);
- `Global.tileSrcSet`/`boxSrcSet`/`stripSrcSet` per scheme;
- `servedWidths` as the union, and the miss filling the requested width's scheme; 960 and 1280
  stored when first requested (Q11);
- the admin action "Switch to standard sizes": it scales the standard set from cached images
  that are wide enough, downloads only the files without one, requests the standard set
  explicitly, and flips the column on completion; "Download images locally" never flips it (Q8, Q9);
- the cleanup's v2 marker, the aspect-only rule and the deleted-file skip;
- the proxy serving any width on disk; deleted files' widths and `largestCachedWidth` read from
  their directory, with `largestCached*` moved to `LocalImageCacheService`;
- `ContestJuryJdbc.thumbSizes`/`setThumbSizes` and the per-contest scheme cache;
- `/thumb_urls` per scheme.

**The screen profile** (§3.5): a small table keyed by account (a Flyway migration, numbered the
next free one at merge), written by a CSRF-protected POST from the script, read by the
distribution job to queue the paced prefetch.

**The Pages / Continuous preference** (§3.6): a column on the account (a Flyway migration,
numbered the next free one at merge), read with the page for the view menu and saved by a POST
with the CSRF token; the default is Pages.

No JSON API; the Tapir API is unchanged.

## 5. Steps: small reviewable PRs, in order

Each PR gets the usual review and fix cycle, and the browser tests in three engines where it
touches the tiles. **Total: about 28–33 days.**

| Step | Content | Size |
|---|---|---|
| **G-0** | **Measure first, no product code.** On the fixture server (38k-file Gatling fixture DB), with Playwright's network log and traces, in Chromium, Firefox and WebKit, for the binary gallery, a by-rate list and contest files, at DPR 1, 2 and a phone viewport at DPR 3: <br>(a) does the hidden lightbox fetch large images; <br>(b) are both the hidden `srcset` image and the tile's `src` fetched; <br>(c) time to first tile and to all tiles, cold and warm cache, and a **cold, fast scroll** (proxy misses, 429s, stalled tiles); <br>(d) bytes per page; <br>(e) which candidate each engine takes at 100 %, 150 % and 200 % zoom, and bytes per chunk of about one screen (24 tiles), in the current and the standard widths (the latter fetched through the proxy at those widths); <br>(f) disk and download count for the standard set (250/330/500, and 960 as it is requested) against the current set, and the switch's number of files without a wide-enough source on the fixture; <br>(g) the Continuous cap: 100, 250, 500, 1,000 and 2,000 tiles, memory, scroll smoothness and vote response, in Chromium and Firefox, with a modest-laptop and a phone profile. <br>Results go into this plan as a "Measured" section. | S (2 days) |
| **G-1a** | **Two size schemes, server side** (§3.5): migration (the next free number at merge, reserved in the roadmap) and `ContestJuryJdbc.thumbSizes` with its per-contest cache (no change to the `ContestJury` case class); `servedWidths` union, and any width on disk served first; a miss fills the file's current set first, else the standard set; `localSourceFile` from any cached width ≥ the target; "Download images locally" (contest or round) never switches: it fills missing files at the contest's current sizes (Q8); the separate admin button "Switch to standard sizes" scales from cached images and downloads only the files without a wide-enough source, requests the standard set explicitly and flips the contest on completion, with the counts on the button (Q8, Q9); 960 and 1280 px files stored on demand, with the screen profile table and the distribution job's paced prefetch (Q11, §3.5); new contests still `legacy` (Q10); cleanup v2 (marker, deleted files first or nothing, aspect-only rule, the widest file kept); deleted files' widths and `largestCached*` from their directory, moved to `LocalImageCacheService`; `/thumb_urls` and the large view's thumbnail bar per scheme. The tile pages keep their markup; their `src` follows the scheme. | L (7–8 days) |
| **G-1b** | **The shared tile, first on by-rate** (the rated galleries and every by-rate list, already server-rendered): `snippets/galleryTile` and `galleryTiles` with `TileOptions`; the ratio classes and `wlx-tile-small`; the caption (rank line) moved in unchanged; the `rows` layout with the caption minimum width; `gallery-tiles.js` (failed box) and the shared CSS; `srcset`/`sizes` per scheme; lazy loading; `addListStars` on `.wlx-tile`; the one-criterion round's stars and the round menu's criterion name (§3.1); the script's screen-profile report. The comments report gets the shared image partial. Unite Gallery is untouched. | M–L (3–4 days) |
| **G-2** | **Contest files and round review on the shared tile** (`votes = none`): deleted badge with the "Request undeletion" link, COI text. Unite Gallery leaves these pages. | M (2 days) |
| **G-3** | **Binary galleries on the shared tile** (`thumbs`, tally, chips overlay, read-only vote mark): `tile-votes.js` from items to elements with a delegated listener; `voting.spec.js` reworked; screenshots redone; mock-ups of bigger Yes/No vote areas on tiles (Q17; not decided beyond the mock-ups). Unite Gallery is no longer used. **New contests are created as `standard` from here on** (Q10). | M–L (3–4 days, mock-ups included) |
| **G-4** | **Last-viewed mark and jump button** (`last-viewed.js`) on every tile gallery and the comments report (Q19), whose `id="@pageId"` becomes `data-page-id`; `scroll-margin-top` for the top bar. | S (1 day) |
| **G-5** | **The Pages / Continuous choice and continuous loading** for binary, by-rate and contest files (§3.6): the view-menu choice (default Pages) and its account column with a migration (next free number at merge); one-screen batches: the window-size cookie, client hints, `n` capped 10..80 and carried by every page link (Q15); the chunk routes, marker and JSON-only answers (§4); the carried key (`after=`/`before=` with `k=`) for every one-juror list, sub-regions included, and on the page routes; ranks and criteria per chunk; dropping duplicates; sections, next/earlier/skip links; history and the tile click's `atUrl`; navbar pager follow; DOM cap from G-0 (g) (Q18); the round review offered the same choice (Q22). Gatling: chunks keyed and not, binary and by-rate. | L (6 days) |
| **G-5b** | **Windowing spike** (Q16, Q18): a windowing library against our own windowing on the fixture (code size, resize, scroll anchoring, keyboard and screen readers); the result decides whether windowing becomes a third choice beside Pages and Continuous; it also rechecks the Continuous cap. No product code merged. | S (1–2 days) |
| **G-6** | **Remove Unite Gallery and the by-rate grid's old markup:** `unitegallery.js`, `ug-theme-tiles.js`, both stylesheets, `galleryScripts.scala.html`, `gallery-init.js`, its images, the `ug-*` and `li.gallerybox`/`img.cropped` rules in `main.css`, `unitegallery-patches.test.js`, `unitegallery-alt.test.js`, `gallery-init.test.js`. Also: the `NOTICE` entry, `RawHtmlSinksSpec`'s exemption, dependencies §7 and T4, and `docs/vendor/unitegallery.md` (to `docs/history/`). The T1 tile-star path goes too (Q21, decided). | S (1 day) |
| **G-7** | **CSP `style-src` without `'unsafe-inline'`**: templates to classes, one release Report-Only, then enforce (§3.10). | S–M, over two releases |

**Order notes:**

- **G-1a first:** server only, so every later step renders per scheme from its first release.
- **G-1b before G-2 and G-3:** by-rate is already ours and server-rendered, so it is the shared
  tile's lowest-risk first user. The partial, layout, captions, `srcset` and schemes meet real
  jurors (rated rounds) before the vote-heavy binary galleries move.
- **G-2 before G-3:** contest files have no votes.
- **G-4 before G-5:** G-5's Back behaviour relies on the mark.
- **G-6** only after G-2 and G-3 have run in a real contest round for a few days.
- **G-5b** after G-5, once the continuous mode has run with real jurors.
- **A proxy-miss queue (Q24)** is a separate small PR, only if G-0 (c) or G-5 shows stalls (decided).

## 6. Test plan

**Scala (specs2/munit), per step:**

- **New `GalleryTileViewSpec`** (the partial, for each set of options):
  - The link's `href` is `largeUrl`; `alt` is the title escaped once ("Video:" for a video).
  - The `img` has `width`/`height`, `loading`, `decoding` and `sizes` =
    `ImageUtil.resizeTo(w, h, 247)`. A file less than 200 px high has `wlx-tile-small`, its
    own `width`/`height`, and `sizes` = its width.
  - **The ratio class** is the file's ratio rounded **down** to 0.05, clamped to 0.30–4.00. So
    the tile is never proportionally wider than the file, and `sizes` ≥ the tile's widest.
  - Its `srcset` holds only proxy URLs:
    - the current scheme's three widths, or the standard 250/330/500/960, plus 1280 for files
      wider than 1.9:1;
    - only below the file's width;
    - none for a file narrower than the smallest, or of width 0.
  - A deleted file's `srcset` lists only the widths given as on disk: no original upload URL,
    no Wikimedia URL.
  - **Options:**
    - `thumbs` gives buttons with `aria-pressed` and an existing `aria-describedby` target;
    - `stars` gives the caption's `.wlx-byrate-stars` with its data attributes;
    - `caption = rank` gives the rank line as `galleryByRate` renders it today, the criteria
      averages included;
    - `tally` gives the chip;
    - the chips sit overlaid or in the caption per option.
  - The deleted badge is outside the link, with `rel="noopener"`, next to the "Request
    undeletion" link (Commons:Undeletion requests).
  - **A one-criterion round's tile** shows stars and no criterion name; the name is in the top
    bar's round menu.
  - **Info wider than the image:** a narrow portrait tile with 10 stars or full chips has a width
    of at least the info's minimum, the image is centred at its own width, and the info is not
    clipped (browser test, G-1b).
  - **No `style=` attribute** anywhere, and **no `id` equal to a page id**.
  - A title with `<`, `"`, `'` and `&` stays text.
- **New, for the choice (G-5):** the view menu's Pages / Continuous choice defaults to Pages,
  is saved per account by a CSRF-protected POST, is read back with the page, and a refused or
  signed-out POST changes nothing.
- **Updated:**
  - `GalleryByRateViewSpec`, `CriteriaResultsViewSpec`, `HeadingsViewSpec`: the rank line, stars
    and averages, now inside tiles; a juror's own stars only per `TileStars.round`.
  - `GalleryDescriptionViewSpec` (single escape), `GalleryTileStarsViewSpec` (per Q21).
  - `ContestFilesPageSpec`, `ContestFilesMonumentSpec`.
  - `CommentsFilterSpec`, `JurorCommentsReportSpec`, `VoteTimeOrderSpec`, `ViewMenuSpec`,
    `NavbarMenusSpec`.
  - `ChipFixturesSpec`, `PagerSpec`, `RoundListSpec` (the batch size `n`: capped 10..80, the
    default for a missing or bad value, one rule for every tile gallery; the cookie and client
    hint order) and `PagerSpec` (every page link, the navbar pager's included, carries `n`;
    `atUrl` / `listAtId` honour it, so Back lands on the same page after a window change).
  - `LargeViewFixturesSpec` and `LargeViewSideSpec`: the thumbnail bar per scheme (G-1a).
- **New `GalleryChunkSpec`: authorization equals the page's.** A table run against the page and
  the chunk URL, for the binary gallery and by-rate, as JSON for the chunk:
  - a juror's own list → 200;
  - another juror's list as a juror → refusal (JSON, not the `viewAsRefused` page);
  - all jurors' (`asUserId` 0) as a juror → refusal (JSON);
  - another juror's as an organizer → 200, but not for a juror outside the round (404);
  - a round of another contest → 403 JSON;
  - an inactive membership → 403 JSON;
  - a stopped round / one being distributed / a stopped juror → the state (JSON);
  - paused → 409 `round_paused`;
  - signed out → JSON 401;
  - round 0, no such round, no such contest, an unknown view → JSON 404;
  - a juror on the contest files chunk → 403 JSON.
  No refusal carries `X-Wlx-Chunk`; every chunk does.
- **The tile's options follow the viewer**, on the page and in the chunk:
  - an organizer viewing one juror's list gets `votes = none`: no thumbs, no clickable stars,
    only the read-only vote chip or rate;
  - a juror viewing their own list in a stopped round gets `votes = none` too;
  - only a juror's own active round gets `thumbs` or `stars`.
- **More chunk cases:**
  - no change to `current_contest_id`;
  - `Cache-Control: no-store`, no sort cookie;
  - `hasNext` right at the end;
  - no count query beyond a ranked list's `rankCounts` (a query counter);
  - the contest files `tiles` route not taken as a page id;
  - a by-rate chunk's ranks match the same files' ranks on the full page;
  - a criteria round's chunk has the averages.
- **Keyset continuation** (in `GalleryChunkSpec` or `RoundListDbSpec`):
  - under Unrated, vote the first chunk's files, then follow its next link: it starts at the
    next unvoted file;
  - the same in a **sub-region round**;
  - in by-rate, latest-vote and oldest-vote order, vote the chunk's last tile, then follow its
    next link: it continues after that tile's old place, nothing repeated or skipped;
  - a **juror's by-rate list in a rated round**: change the rate of the chunk's last file, then
    the same check;
  - file order, the same;
  - "Show earlier files" by `before=`: the `n` files before the first tile, in order;
  - "Distribute new files" adds rows: no file twice across a juror's chunks;
  - a wrong number of `k` values, or a bad type → JSON 400;
  - a `k` with a quote is only a value (bound parameters).
- **Size schemes** (G-1a):
  - **`GlobalSrcSetSpec`**:
    - `tileSrcSet`, `boxSrcSet` and `stripSrcSet` per scheme and aspect ratio;
    - the current scheme's widths ≥ 1x/1.5x/2x of `sizes` for aspect ratios 0.3–4;
    - width-0 and narrow files.
  - **`GlobalLargeImageSpec`**: the large view's own image unchanged.
  - **`ImageProxyWidthSpec`:**
    - every width of both schemes is served without a redirect;
    - **any width on disk is served** even if it is in neither scheme (a deleted file's old
      size);
    - **a miss on a width in both schemes** (a 4:3 file's 500, a 1:1 file's 250) fills the
      **current** set and fetches no 960;
    - a miss on a standard-only width fills the standard set;
    - **filling the second set when the first is cached** scales from the cached files and
      makes no Wikimedia request, unless a wider width is needed.
  - **New `LocalImageCacheCleanupSpec`**:
    - a directory holding **only standard files (330, 500)** loses nothing;
    - a directory holding both schemes loses nothing;
    - an odd width (400) is deleted, **unless it is the directory's widest file**;
    - **a directory holding a single odd-sized file keeps it** (a file deleted on Commons, not
      yet marked);
    - a marked deleted file's directory is untouched, odd widths included;
    - **if reading the deleted files fails, nothing is deleted and no marker is written**;
    - the v2 marker runs the cleanup once.
  - **`largestCachedWidth` by disk:**
    - a 3:4 deleted file whose directory holds only the current scheme's widths gets 375, not
      500;
    - one holding only standard widths gets its widest;
    - its large view renders.
  - **The scheme read:**
    - `ContestJuryJdbc.thumbSizes` and its cache;
    - a flip clears the contest's entry;
    - the large view's thumbnail bar follows it;
    - `ApiSpec` and `JsonFormat` are unchanged.
  - **The switch:**
    - "Download images locally", for the contest or a round, fills missing files at the current
      sizes and never flips the column (Q8);
    - a completed "Switch to standard sizes" run flips the column, even with per-file errors;
    - an interrupted run, or a restart during it, doesn't flip it;
    - the switch scales from cached images that are wide enough and downloads only files
      without one; it requests the standard set explicitly, so a width in both schemes fills the
      standard set;
    - 960 and 1280 px files are stored on demand, not by the switch (Q11);
    - **screen profile:** stored per account, coarse values only; a distribution (new round,
      top-up, Add a juror, Distribute new files) queues the paced prefetch for a juror with a
      profile, and a juror without one gets it on the first visit; a legacy contest queues
      none;
    - new contests are `legacy` before G-3 and `standard` from G-3;
    - the migration leaves existing ones `legacy`;
    - the switch button shows the cost text on a `legacy` contest, and is absent on a `standard` one.
  - **`/thumb_urls`** lists the scheme's widths.
- **`ContentSecurityPolicySpec` and `InlineScriptHashesSpec`** stay green. `RawHtmlSinksSpec`
  gains `DOMParser`/`parseFromString` and lists `gallery-tiles.js`, then loses the vendored
  exemption in G-6.

**Jest (jsdom):**

- **New `gallery-tiles.test.js`:**
  - `error` → `wlx-thumb-failed`, and an already-failed image is marked at start;
  - the chunk fetch sends `X-Requested-With: XMLHttpRequest` with the link's cursor, one at a
    time;
  - a response is inserted only with `ok`, `text/html`, `X-Wlx-Chunk: 1` and exactly one
    section;
  - JSON refusals stop loading with their text; other failures show the generic text and leave
    the link;
  - `DOMParser`, so no script runs from a chunk;
  - duplicates are dropped;
  - `addListStars` runs on a by-rate chunk;
  - `replaceState` follows the top section;
  - a plain click sets `atUrl`, a modified click doesn't;
  - the end link doesn't auto-load while focused;
  - the cap holds;
  - none of this runs in Pages mode;
  - the cap comes from the measured value (G-0 (g)).
  - "earlier" keeps the view in place.
  IntersectionObserver is faked.
- **New `last-viewed.test.js`:**
  - the fragment marks the tile;
  - no `scrollIntoView` on load;
  - the button shows and hides with the observer;
  - a click scrolls and focuses the link;
  - an unknown id does nothing.
- **`tile-votes.test.js`:**
  - reworked to element tiles: the six click cases, pressed, busy, the vote-time chip, errors;
  - `addListStars` inside `.wlx-tile` captions, and the hint.
  The coverage thresholds stay.

**Playwright, Chromium, Firefox and WebKit:**

- **`voting.spec.js`:**
  - Tiles are found by role (`link` with the title, `button` "Selected" / "Rejected").
  - **The by-rate cases** (rate with stars in the list, the hint, chips under the image) run on
    the shared tile from G-1b.
  - The existing checks stay: vote, toggle, 24 px hit areas in both modes, chip links.
  - `organizer.spec.js`, `pages.spec.js`, `navbar.spec.js`, `access.spec.js` and
    `large-side.spec.js` follow the markup change where they touch `gallerybox` or tiles.
- **Keyboard:**
  - Tab to the tile link, and Enter opens it;
  - Tab to ✓/✗ (visible when focused) and Space votes;
  - Tab to the stars in a by-rate list;
  - the buttons' description is the title;
  - Ctrl+click opens a new tab;
  - the skip link and Tab past the end link reach the footer.
- **New `gallery-scroll.spec.js`**, for the binary gallery and a by-rate list:
  - scrolling loads the next chunk and the URL changes;
  - under Unrated, vote every tile of the first chunk, then scroll: the next chunk starts at the
    next unvoted file, no tile twice;
  - in latest-vote order, and in a rated by-rate list, change the last tile's vote, then
    scroll: no jump back;
  - open a file, then browser Back: the same page, the tile marked; Esc gives the same;
  - a far tile shows the jump button, which scrolls to it;
  - `scrollY` stays 0 on load;
  - with JS disabled, the tiles and the "Next files" link work.
- **Sharpness, per engine, for both schemes** (each engine's expectation fixed from G-0 (e)):
  - **current scheme, every engine:** DPR 1 takes the `resizeTo(250)` width, DPR 1.5 the
    `resizeTo(375)` width, DPR 2 the `resizeTo(500)` width (`currentSrc`);
  - **standard scheme, Firefox:** the smallest density ≥ DPR (4:3 at DPR 2: 960w);
  - **standard scheme, Chromium:** the geometric-mean rule (4:3 at DPR 2: 500w; 3:4: 330w);
    or, if Q12 leaves 500 out, 960w;
  - **WebKit:** what G-0 (e) recorded;
  - a panorama (5:1) at DPR 1 takes a candidate at least as wide as it is shown (1280w);
  - a file less than 200 px high is shown at its own size;
  - only proxy URLs appear;
  - with three files, the row is 200 px high, not stretched;
  - at DPR 1, no image is shown wider than its `currentSrc`'s natural width. At DPR 2 that
    holds for Firefox, while Chromium may show the next lower candidate (Q12).
- **CSP:** no `securitypolicyviolation` on every gallery page; with Report-Only in G-7.
- **Screenshots:** `docs-screenshots.spec.js` and `ui-shots.py` redone (by-rate in G-1b, the
  rest in G-2/G-3).

**Performance:**

- **Gatling, on the 38k fixture:** `JurorGallerySimulation` with chunk scenarios for the binary
  gallery and by-rate. Pages 1, 100 and 900 by carried key (a juror's list, also in a
  sub-region round) and by `OFFSET` (all jurors'); the page versus its chunk. Per-query SQL
  debug logging is off for both variants, and absolute deltas and run variation are reported
  separately.
- **Browser:** G-0's measurements repeated after G-1b, G-3 and G-5.

## 7. Risks

- **R1. The galleries look different.**
  - The binary galleries' last row is no longer stretched, and wide or tall files crop
    differently.
  - **The by-rate list changes from equal boxes to rows.**
  - The screenshots and the juror guide's pictures change.
  - Mitigation: by-rate first (G-1b), with screenshots in the PR for the maintainer. Q14 is
    decided: no view keeps a different look.
- **R2. Voting regressions** in G-1b (stars) and G-3 (thumbs). Mitigation: the browser tests
  already vote in both kinds of list in three engines; they are reworked in the same PRs.
- **R3. Skipped or repeated files while voting.** `OFFSET` chunks skip files under a vote filter,
  and a cursor re-read at request time jumps back after a vote.
  - Mitigation: the key carried in the URL for every one-juror list, duplicates dropped, and the
    Unrated, sub-region and per-order tests.
  - The organizers' all-jurors views and contest files keep `OFFSET`, where a skip or a
    (dropped) repeat is possible while others vote or import.
- **R4. Cache, schemes and deleted files.**
  - A cleanup bug could delete thumbnails of files gone from Commons, marked or not yet marked.
    Mitigation:
    - the cleanup reads the deleted files first or does nothing, and skips their directories;
    - it never deletes a directory's widest file;
    - the aspect-only rule keeps both schemes;
    - specs cover each case.
  - The v2 marker makes the first start after deploy walk the cache once. Its duration is
    measured in G-0 (f) on the fixture's cache.
- **R5. Disk.**
  - Contests that switch keep their current-scheme files (Q13) and add the standard set. The
    960 px files (about 150 KB each) are stored for the jurors who need them (Q11), so the disk
    grows with use; the earlier figure of about 6 GB for 38k files is unmeasured and holds only
    if every file's 960 is stored. G-0 (f) measures.
  - A `legacy` contest's misses never fill the standard set, since the current set is checked
    first.
  - Mitigation: switching is per contest, explicit and costed on its button; the images page
    shows the cache size.
- **R5a. Switching takes time.** "Switch to standard sizes" scales most files here and
  downloads those without a wide-enough source, throttled at 10 a second. The count is
  unmeasured until G-0 (f); the button and the admins' guide show it.
- **R6. Wikimedia load.** All sizes are fetched by the server, from its one IP, never by the
  browser (§3.4). A batch can burst up to 80 proxy misses, and a 429 backoff stalls those tiles.
  Mitigation: the paced prefetch at distribution time (§3.5) means most sizes are cached before
  a juror opens the round; lazy loading limits the remaining misses to tiles near the view;
  G-0 (c) measures; Q24's queue only if stalls are observed.
- **R7. History and Back differ by engine.** bfcache is usually not used for `no-store` pages,
  but engines differ. The design relies on neither bfcache nor scroll restoration, and the
  browser tests cover Back in three engines.
- **R8. WebKit's `srcset` choice on zoom** may not change. Zoomed WebKit then stays at the DPR's
  candidate, still sharper than today's single 250 px image.
- **R9. Page numbers change meaning.** A page is one screen of tiles (`n`, Q15), so page
  numbers depend on the screen, and by-rate used to have 15 files per page. Every page link
  carries `n`, so a reloaded or shared link means the same files. Low impact: the pager always
  lands somewhere valid, and `atUrl` links are by file.
- **R10. Nominal page numbers.** After in-page votes under a filter, the URL's page and the
  navbar's range can be off until a reload. A tile click uses the file's `atUrl`, so Back is
  exact.
- **R11. The carried key is client input.** It is parsed by column type and bound as parameters,
  and it selects only another slice of the viewer's own list. Specs cover a bad count, a bad
  type and a value with a quote.
- **R12. Narrow portrait tiles with long info lines** (10 stars plus the rate text, or the
  chips) are widened to the info's minimum, with grey beside the image and nothing clipped
  (Q14). That is a visible change in rated rounds, checked in G-1b's screenshots.
- **R13. Chromium at DPR 2 in the standard scheme** shows tiles at 1.5–1.8x, slightly soft,
  where the current scheme gives 2x (Q12).
- **R14. Ratio classes crop a sliver.** Rounding the ratio down to 0.05 crops up to one step at
  the sides (under 4 % for 4:3), in exchange for a layout without script.

## 8. Questions and decisions

Each question was asked on its own. **All are decided** (the maintainer, 2026-11-19 to
2026-11-23); the decisions are applied in the sections they affect.

- **Revisit later:** Q12 (after G-0's measurements), Q25 (after a juror trial), and the
  Continuous cap (G-0 (g), G-5b).

**Terms used:**

- **srcset:** an image's list of sizes, from which the browser picks the one its screen and zoom
  need.
- **DPR (device pixel ratio):** screen pixels per CSS pixel: 1 on an ordinary monitor, 2 on most
  laptops' sharp screens, about 3 on phones; page zoom raises it.
- **Size scheme:** the set of thumbnail widths a contest's pages ask for and its cache holds:
  "current" (today's) or "standard" (Wikimedia's widths).
- **Keyset continuation:** asking for "the files after this place" in the list's order, the place
  written into the link when the page was shown, instead of "files 41 to 80".
- **Windowing:** removing far-away tiles from the page and keeping a blank space of their height.
- **Report-Only:** a CSP header under which the browser reports what a stricter policy would
  block, without blocking it.

**Decided (maintainer, 2026-11-19 unless stated):**

- **Q1.** Our own tiles, not lightGallery 3.
- **Q2.** No lightbox.
- **Q3.** Tiles are never enlarged beyond their thumbnail, short rows stay short, and only
  cached sizes are used (no direct Wikimedia URLs in the tiles for now).
- **Q4.** Two size schemes, chosen per contest: standard Wikimedia widths for the caches of new
  or re-downloaded contests; the current layout keeps serving existing caches (§3.5).
- **Q5.** Deleted files' thumbnails: show whatever is on disk (§3.4).
- **Q6.** They are never removed: no switch and no cleanup removes them, and the cleanup fails
  closed (§3.5).
- **Q7.** They are never fetched from Wikimedia. The deleted mark links to the file's Commons
  page, to see why, and a "Request undeletion" link leads to Commons:Undeletion requests
  (§3.1, §3.4).
- **Q8.** "Download images locally" never switches the scheme: it fills missing files at the
  contest's current sizes. Switching is a separate admin button, "Switch to standard sizes"
  (§3.5).
- **Q9.** Obsolete, merged into Q8. Switching scales the standard sizes from cached images that
  are wide enough and downloads only the files without one (decided 2026-11-23); the button
  shows the counts (§3.5).
- **Q10.** New contests are created with the current sizes until G-3 (§3.5).
- **Q11.** The 960 px files are stored on demand: prefetched, paced, for jurors whose coarse
  screen profile needs them, otherwise on a first miss (§3.5).
- **Q12.** Accept Chromium's slightly soft DPR-2 tiles in the standard scheme; revisit after
  G-0's measurements (§3.4).
- **Q13.** Keep the old-size files while any contest uses them (§3.5).
- **Q14.** One look everywhere, the justified rows. When a tile's info line is wider than its
  image (narrow or portrait files), the tile gets more width and spacing: tile width = max(image,
  the info's minimum), the image centred, nothing overlapped or clipped (§3.2).
- **Q15.** A batch is one screen: the script stores the window size in a cookie, the server
  renders every later page at that size (capped 10..80; Chromium viewport client hints where
  supported), the first visit and no-script default to about 24, and every page link carries
  `?n=…` (§3.6).
- **Q16, Q18.** Simple now: continuous loading downwards, "Show earlier files" and a cap set by
  measurement (G-0 (g), about 480 tiles as a placeholder). A later spike (G-5b) compares a
  windowing library with our own windowing (§3.6).
- **Q17.** Instead of a whole-tile zoom or magnifier, G-3's mock-ups try bigger Yes/No vote
  areas on tiles (the maintainer's idea; not decided beyond the mock-ups) (§3.1).
- **Q20.** Covered by the Pages / Continuous choice in the view menu, remembered per person in
  the account (a migration, numbered the next free one at merge). The default is Pages; windowing
  may become a third choice later if it is not too complex to maintain (§3.6).
- **Q21.** Drop the unreachable tile-stars path (T1), in G-6.
- **Q23.** Remove `'unsafe-inline'` from `style-src` after one Report-Only release (§3.10).
- **Q24.** A queue for proxy misses only if stalls are observed (§3.6).
- **Q25.** A tile voted out of the list's filter stays shown with its new vote; revisit after a
  juror trial (§3.9).
- **One gallery** for every gallery view.
- **One-criterion rounds (2026-11-20, refined 2026-11-23):** the gallery shows stars like normal
  rated rounds; the criterion's name is in the top bar's round menu next to the round name,
  never on a tile (§3.1).
- **Q19.** The last-viewed mark (no jump on load) on the comments report too: yes (2026-11-23).
- **Q22.** The round review offers the same Pages / Continuous choice as the other galleries
  (2026-11-23).
