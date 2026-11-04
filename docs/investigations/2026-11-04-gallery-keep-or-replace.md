# The gallery: keep the patched Unite Gallery or replace it (2026-11-04)

Investigation, 2026-11-04, for the patched-gallery batch (roadmap queue item 3, step 4). **Not
decided: the user decides.** The patch inventory and tests it builds on:
[docs/vendor/unitegallery.md](../vendor/unitegallery.md).

## 1. Is upstream maintained?

No, as far as the published record shows (read 2026-11-04):

- **Releases:** the last tag is `1.7.40` (2016-11-29). The last commit on `master` is "updated
  to 1.7.45 version" (2017-02-27). npm's `unitegallery` package is at 1.7.28 (2016).
- **Activity:** GitHub's API reports the last push on 2021-01-25, with 180 open issues and 9
  open pull requests. The repository isn't archived. It is MIT-licensed.
- **Its latest code** still looks the theme up with `eval`, which our CSP refuses, and still
  puts titles unescaped into attributes (vendor doc §1). It is written for jQuery 1.x. An update
  would bring no fix and would need every patch ported to it.

## 2. What wlxjury uses of it

Of the 586 KB script (123 KB gzipped) and its two stylesheets, wlxjury uses only the tiles
theme's **justified rows**: tiles 200 px high, 5 px apart, each as wide as its file's aspect
ratio, the rows stretched to the page's width. Around that, wlxjury's own code (patches P6–P14
and T1–T5, plus `tile-votes.js`) does the rest:

| Feature | Where it lives now |
|---|---|
| Tile click opens the file's large view (`data-largeUrl`) | T3 (theme) |
| Thumbs up/down in a binary round, toggle votes, pressed state, Enter/Space | P11, T4, T5; `tile-votes.js` (`addVote`, `vote`) |
| A rated round's stars on the tiles | T1; `tile-votes.js addStars` (raty) |
| The organizers' tally (approvals, rejections) | T2, P9; `tile-votes.js addTally` |
| Deleted-file mark linking to Commons; failed-thumbnail box | P6–P9; `main.css` |
| Hover overlay (darkens the tile, shows the icons) | upstream `UGTileDesign` |
| Keyboard | Only the thumbs can be focused (`tile-votes.js`). The tile itself is a `div` with a click handler: it can't be focused, opened from the keyboard, or opened in a new tab. A focused thumb is invisible under the overlay's opacity 0 (UI practices U11). |

Not used, but loaded and run: the lightbox, slider, video players, carousels and grid panels.
The theme builds the lightbox on every gallery page (`g_lightbox.putHtml()`, `run()`), and in
jsdom its first slides got the large images' `src` set
(`unitegallery-patches.test.js`). If browsers do the same, every gallery page downloads two or
three large images that nobody sees. This hasn't been checked in a browser's network log yet.
The library's inline `style` attributes are also one of the reasons `style-src` still needs
`'unsafe-inline'` (UI U2, register P3).

## 3. The options

| | A. Keep the patched fork | B. Another library | C. Our own tiles: CSS plus a small script |
|---|---|---|---|
| What | No change. The rule stays: no new patch without a `wlxjury:` comment and a test, and prefer replacing to patching. | A justified-layout library in place of the tiles theme, with our tile markup. Candidates: `justifiedGallery` 3.8.1 (jQuery, MIT; last release 2020-05), `justified-layout` 4.1.0 (Flickr, layout maths only, no DOM; ISC; 2021-01), `flickr-justified-gallery` 2.2.0 (MIT; 2024-02). | The server renders each tile as an `<a href=largeUrl>` holding the `<img>` (with `width`/`height`), with the thumbs, deleted mark and tally as real elements in the template. A flex-wrap row layout (`flex-grow` and `width` from each file's aspect ratio, set by a few lines of script through the CSSOM, which the CSP allows) gives the justified rows. `tile-votes.js` reads the tile's `data-*` in place of unitegallery's item. |
| Effort | None now. Each later fix is a patch in a 24,700-line file. | Medium. A library's DOM and options to learn, and the same template and `tile-votes.js` work as C. | **Medium, about 3–5 days:** `gallery.scala.html`, `contestFiles.scala.html` and `GalleryTiles`; about 60 lines of CSS and 30 of script; `tile-votes.js` reworked from items to elements; delete 4 vendored files and their images. Tests to change: Jest `tile-votes`, `gallery-init` and the two unitegallery tests (replaced); Scala `GalleryDescriptionViewSpec`, `GalleryTileStarsViewSpec`, `ContestFilesPageSpec`, `ContentSecurityPolicySpec`, `RawHtmlSinksSpec`'s vendored list; the browser tests' `voting.spec.js` (which finds `.ug-tile`) and `ui-shots.py`; new screenshots. |
| Risk | Low today, rising: unmaintained code on every gallery page, jQuery-1-era APIs (P5 broke on jQuery 3), and the HTML sinks of §3 of the vendor doc. | Medium: one more dependency, and the first two candidates are old too. | **Low to medium:** the rows won't match unitegallery's exactly (the last row, the crop on very wide or tall files), so the screenshots change; the hover and focus styles need redoing; keyboard and voting flows need retesting in all three browser engines. |
| Gains | — | Less vendored code. | Removes 586 KB of script and both stylesheets from gallery pages, and the unused lightbox's image loads (if confirmed). Tiles become links: focusable, they open with Enter, open in a new tab, and work before the scripts run. Twirl escapes all tile text, so the vendored sinks go away (N8, N14, N15). Fixes U11 in the redo, and removes one blocker for dropping `'unsafe-inline'` from `style-src` (P3). |

## 4. Recommendation

**C: replace the gallery with our own tiles**, as its own batch after the current release
work, with the browser tests as the safety net (they already vote through the tiles in three
engines). Until then, **A** holds: the patched fork stays, and the patch tests guard it.
**B** isn't worth it: it costs about what C costs, plus a dependency that is itself barely
maintained, or (`justified-layout`) solves only the layout maths, which is the easy part.

Before deciding, worth checking: a browser's network log of a gallery page, to see whether the
hidden lightbox really fetches large images. If it does, it is a cheap reason to do C sooner,
and the same page loads would measure the gain.
