# Standard Commons thumbnail sizes, and whether wlxjury needs to resize

Recovered 2026-11-25; originally written 2026-11-04.

2026-11-04. Read-only investigation of the followups checkout at `66badcfc` (mirror of
`stack/72-ops-retention-monit`). There was no sbt and no Docker. 11 anonymous `curl` requests went to `upload.wikimedia.org`.

Reframed during the work, by the user: **resizing stays on our own server.** It lets wlxjury
fetch one size per file from Commons and derive the others locally, which keeps it within
Commons' limits on external fetches. "No resizing" (`thumbs.mode = commons`) is evaluated only
for hosting inside Wikimedia.

## Recommendation

1. **Own server: keep the resizing, and keep the single fetch at a standard width. The code
   already does this.** Each file costs one Commons fetch, at the narrowest standard width
   (`Global.wikimediaSteps`) no narrower than the widest cached size. That is 960 px for a 4:3
   landscape (widest size 666) and 500 px for a 3:4 portrait (widest 375). When that step is
   no narrower than the file, it fetches the original. Every other size is scaled locally from
   that one download. No change is needed to which width is fetched.
2. **Fix the one place that still sends non-standard widths to Commons: the proxy's redirects
   to `upload.wikimedia.org`.** `ImageProxyController.wikimedia(path)` redirects the browser to
   the width the page asked for: 333, 666, 300, 450, 225 or 400 px. Since 2026-05-13 Wikimedia
   answers those with **400**. This redirect is used for a file not in the DB, a file of
   unknown size, a fetch failure on a non-direct lookup, and a Commons check that isn't
   conclusive. In all of those cases the browser now gets an error page instead of an image.
   Redirect to `Global.wikimediaStep(px)` instead. This is small, with specs already in place.
3. **Optional hardening.** Make the test fake Commons refuse non-standard widths with 400, so
   a regression fails in CI. Bound the extreme-panorama case (below). Check the bandwidth
   budget (below).
4. **`thumbs.mode = commons` (no cache, no resizing) only for hosting inside Wikimedia.** The
   large view already works that way on every host. Drop mode (a), caching standard widths
   byte for byte: it multiplies Commons fetches (up to 4 per file instead of 1).
5. **Base image:** resizing stays, so the container needs a JRE that includes the
   `java.desktop` module (AWT/ImageIO, so not a jlink image without it) and runs headless
   (`-Djava.awt.headless=true`). Scaling with `Graphics2D.drawImage` and the JPEG/PNG codecs
   needs no fonts or X libraries. Leave the base-image choice to the jar investigation, and
   verify it by running `ResizeSpec` / `LocalImageCacheServiceSpec` inside the image.

## 1. What wlxjury does today

### URLs the pages emit

- `Global.useLegacyThumbUrl = true` is hard-coded, so `thumbUrl = legacyThumbUlr`. That gives
  a **relative** `/wikipedia/commons/thumb/<h>/<hh>/<name>/<px>px-<name>` (`legacyThumbUrl`,
  `Global.scala`). On production the vhost proxies `/` to Play, so every gallery thumbnail
  goes through `ImageProxyController.serve` (deleted/renamed plan, §3a).
- Widths come from three boxes × `sizeFactors = 1.0, 1.5, 2.0` (`Global.thumbnailBoxes`). The
  device pixel ratio is handled through `srcset` `1.5x` / `2x` descriptors. Screen size plays
  no part.

| Page | Code | Box | 4000×3000 file: 1x / 1.5x / 2x | 3000×4000 file |
|---|---|---|---|---|
| Juror gallery (justified tiles) | `gallery.scala.html:40-41` | height 250, any width | 333 / 500 / 666 | 187 / 281 / 375 |
| By-rate grid | `galleryByRate.scala.html:88-89` (`srcSet`) | 300×250 | 300 / 450 / 600 | 187 / 281 / 375 |
| Large view's thumbs bar, contest-file bar | `thumbsBar.scala.html:21-22`, `contestFile.scala.html:82-86` | 150×120 | 150 / 225 / 300 | 90 / 135 / 180 |
| Contest files page | `contestFiles.scala.html:62` | height 250, `src` only | 333 | 187 |
| fileList, monuments | `fileList:126`, `monuments:76` | 300×250, `src` only | 300 | 187 |
| **Large view** | `Global.largeImage` (`large.scala.html:55-58`, `contestFile.scala.html:121-124`) | 1280×1100 | **already standard and straight from `upload.wikimedia.org`**: `srcset` 1280w, 1920w, 3840w with `sizes="1280px"` | 825 px: 960w, 1280w, 1920w |

- The large view's `onerror` fallback is the proxy at `largeFallbackPx` =
  `wikimediaStep(shown width)`, for example 1280. That size is not cached: `fetchAndCacheAll`
  fetches it once and passes Wikimedia's bytes through unchanged (`passOn`).
- The juror gallery's `srcset` is **not used by unitegallery.** It builds tiles from `src`
  (`unitegallery.js:19852-19861`; it has no `srcset` handling), and the tile row is 200 px tall
  (`gallery-init.js:25`). The hidden source `<img>`s still carry `srcset`, so a high-DPI
  browser may also fetch the 2x candidate. This is unverified and worth one DevTools check.
- The specs' "800 px source" (`LocalImageCacheServiceSpec`: `jpegBytes(800, 600)`,
  `saveResized(img, src, 800)`) is only a synthetic test source. In production the source is a
  Wikimedia step. `neededWidths` in the specs is `Global.thumbnailWidths(SW, SH).filter(_ < SW)`,
  the same sizes as the table.

### The single fetch per file: checked against the code

- `cachedWidths(image)` = `thumbnailWidths` narrower than the file: up to 8 widths per file
  (150, 225, 300, 333, 450, 500, 600, 666 for 4:3).
- `source(image, px)` (`LocalImageCacheService.scala:472-482`) takes
  `step = wikimediaSteps.find(_ >= px)`. It uses the **original** if
  `step.forall(_ >= image.width)` and the file isn't `thumbnailOnly`, otherwise **the
  thumbnail at `step`** (or 3840).
- Prefetch (`runDownload` → `downloadAndResize` → `downloadFromWikimedia`) and on-demand
  (`fetchAndCacheAll`) both call `source(image, widths.max)`. Each makes **one download**, then
  `saveResized` → `cascadeScale` writes every cached width from it, largest first. A later
  request at another width is scaled from the widest cached file (`localSource`), with no
  Commons call. Concurrent requests for the same URL share one download (`inFlight`).
- Why that width: it is the narrowest image Commons will serve that still covers the 2x
  gallery tile, so there is no upscaling and no penalty. 960 px is about 311 KB, where the
  original is 10 MB (measured below).
- A second fetch per file happens only for the large view's fallback, and only when the
  browser's direct Commons load failed. It is also at a standard width (`largeFallbackPx`).
  Renamed or changed files use `serveCurrent`, which also redirects at `wikimediaStep`.

**Gaps found:**

1. **Redirects at non-standard widths.** `ImageProxyController.wikimedia(path)` replays the
   requested path on `upload.wikimedia.org`. That path is one of the page's widths (333, 666,
   300, 450, 225, …), and it is used in four branches: not in DB; `servedWidths` empty (width
   unknown); a fetch failure when the lookup wasn't direct (a former name); and
   `checkOnCommons` returning `Unknown`, `Unchanged` or any other answer. Wikimedia refuses
   those widths with 400 (§2). `ImageProxyWidthSpec` pins the broken URLs:
   `.../400px-New_file.jpg` at lines 137 and 146.
2. **Extreme panoramas.** The gallery box has no width limit (`resizeTo(w, h, 500)` at 2x).
   For a file wider than about 7.7:1, the widest cached width is over 3840. `step` is then
   `None`, `forall` is true, and the **original** is downloaded and decoded with ImageIO
   (`ImageProxyWidthSpec` already lists 10000×1000). Originals are allowed by policy, but they
   cost memory and bandwidth. A non-decodable (`thumbnailOnly`) panorama fetches 3840, and the
   widths above it are never saved, so `allSizesCached` stays false and every run fetches it
   again. This is rare and low priority.
3. **Bandwidth budget.** The robot policy allows at most 2 concurrent requests and 25 Mbps
   (§2). Prefetch uses `parallelism = 2` and `rate-per-second = 10`. At the measured 311 KB per
   960-px source, 10/s comes to about 25 Mbps, right at the cap if latency ever allowed that
   rate. With 2 in flight the real rate is lower. On-demand proxy fetches are not throttled at
   all (a gallery burst from several jurors). It is worth either a bytes-per-second limit or
   documenting why the actual rate stays below the cap.

### What the cache does besides resizing

| Job | Where | Needs resizing? |
|---|---|---|
| Serves signed-in users only (401, `Cache-Control: private`) | `ImageProxyController.serve`, `mayView` | No |
| Survives Commons outages and 429s: served from disk; retry with backoff and `Retry-After` | `fileIfCached`, `downloadWithRetry`, `retryDelay` | No |
| Prefetches a round's images before judging, throttled | `startDownload(ForRound)`, `runDownloadImpl` | No |
| Deleted, renamed and re-uploaded files: check on Commons, serve the new name, evict the old version, keep a deleted file's cached sizes for finished rounds | `afterFailedFetch`, `checkOnCommons`, `evict`, `Global.cachedSrcSet` / `largestCachedThumbnail` | No, but it depends on the cache having the sizes |
| Failed-thumbnail marks (`FailureCounts`, `wlx-thumb-failed` tile) | `afterFailedDownload`, unitegallery patch | No |
| Width normalisation: an odd width is redirected to a served one | `atServedWidth` | No |
| PDF, TIFF, SVG, video: always the Commons thumbnail (page1 / lossy-page1 / `.png` / `--` still), never the file | `thumbnailOnly`, `ImageUtil.thumbName` | Yes, they are re-encoded as JPEG |
| Large originals: never fetched, except when the step is no narrower than the file (or the panorama case) | `source` | No |
| **Resizing** one standard source into up to 8 widths, re-encoded as JPEG | `saveResized`, `cascadeScale`, `scale` | This is what resizing is for |

## 2. Wikimedia's standard sizes

- **Standard widths:** 20, 40, 60, 120, 250, 330, 500, 960, 1280, 1920, 3840
  ([Common thumbnail sizes](https://www.mediawiki.org/wiki/Common_thumbnail_sizes), last edited
  2026-10-20). They match `Global.wikimediaSteps`.
- **Non-standard widths:** requests through MediaWiki (imageinfo API, InstantCommons) are
  **rounded up** to the next step. A direct `upload.wikimedia.org` URL is **rejected**. The
  history:
  - The steps were rolled out in MediaWiki between 2025-04-10 and 2025-04-16
    ([T360589](https://phabricator.wikimedia.org/T360589), resolved 2025-04-16; backports to
    REL1_43–45 on 2026-03-13).
  - "Standard thumbnail sizes only" was enforced on the CDN as a 429 "thumbnail steps" limit
    ([T414805](https://phabricator.wikimedia.org/T414805), created 2026-01-16). The general ban
    was re-enabled on 2026-04-23.
  - **400 has replaced 429 since 2026-05-13** (puppet: "Return 400 instead of 429 for
    non-standard thumbnail sizes"). The task was resolved on 2026-05-21. The mediawiki.org FAQ
    still says 429, which is outdated.
- **Bots, tools and Cloud Services:**
  - No source exempts any client from the standard-size rule. It is enforced at the edge
    (Varnish `int-front`) before any per-client logic, and my anonymous requests got it.
  - The [Robot policy](https://wikitech.wikimedia.org/wiki/Robot_policy) (2026-03-16) asks
    media fetchers for originals or standard sizes, at most 2 concurrent requests, at most
    25 Mbps, and a descriptive User-Agent, and to honour `Retry-After` on 429. It exempts
    Toolforge and other WMCS bots from the *general rate limits* only ("explicitly exempted
    from such limits"), and the Foundation may still limit them.
  - Toolforge egress to production wiki endpoints is exempt from the Cloud VPS NAT limit
    ([T363296](https://phabricator.wikimedia.org/T363296)). In 2018, Varnish 429s to Tool Labs
    IPs were handled by exemptions ([T214313](https://phabricator.wikimedia.org/T214313)).
  - Non-WMF apps are still treated as bots for media 429s, with User-Agent buckets
    ([T413570](https://phabricator.wikimedia.org/T413570), open, last comment 2026-11-02).
  - Nothing distinguishes signed-in users.

### Checks (2026-11-04, 12:16–12:17 UTC, from 176.37.x via esams cp3076)

File: `1 Палац графів Шенборнів.jpg` (fixture CSV, 4000×3000, 9,986,627 B). User-Agent:
`wlxjury-investigation/1.0 (https://github.com/UkrainianWiki/wlxjury; <email redacted>)`.

| Width | Status | Bytes | `x-cache` / `x-cache-status` |
|---|---|---|---|
| 250 | 200 | 23,905 | `cp3076 miss, cp3076 miss` / miss. A repeat 11 s later: `hit/1`, hit-front |
| 330 | 200 | 39,081 | miss (`server: Thumbor/7.8.0`, rendered on demand) |
| 500 | 200 | 86,039 | miss |
| 960 | 200 | 310,820 | miss |
| 1280 | 200 | 553,523 | miss |
| 300, 333, 666 | **400** | 2,010 (HTML) | `cp3076 int` / int-front: "Use thumbnail sizes listed on https://w.wiki/GHai" |
| original | 200 | 9,986,627 | `pass` (not cached at the edge) |

There was no redirect and no 429. Non-standard widths fail immediately at the edge, without
reaching Thumbor. These are a gallery's real 1x and 2x widths (333, 666) and the by-rate
grid's (300).

## 3. Could the pages use only standard widths?

Replace each needed width with the nearest standard width at least as large, and let CSS
scale it down. Bytes are measured for 250–1280 on the file above. Our own widths are
estimated at the same ~0.46 B/pixel, so they are only indicative.

| Page (4:3 file) | Needed 1x / 1.5x / 2x | Standard | Bytes per tile at 1x (ours → Commons) | At 2x |
|---|---|---|---|---|
| Juror gallery | 333 / 500 / 666 | 500 / 500 / 960 | ~38 KB → 86 KB | ~153 KB → 311 KB |
| By-rate grid | 300 / 450 / 600 | 330 / 500 / 960 | ~31 KB → 39 KB | ~166 KB → 311 KB |
| Thumbs bar | 150 / 225 / 300 | 250 / 250 / 330 | ~8 KB → 24 KB | ~31 KB → 39 KB |
| Portrait gallery | 187 / 281 / 375 | 250 / 330 / 500 | ~12 KB → 24 KB | ~48 KB → 86 KB |

- A 15-tile gallery page goes from about 0.6 MB to about 1.3 MB at 1x, and from about
  2.3 MB to about 4.7 MB at 2x. That is about twice the bytes. Quality is the same or better,
  since each image is only ever scaled down, and 960 at 2x is sharper than needed.
- Nothing visual needs resizing. unitegallery's justified tiles scale any image to the
  200-px row with CSS. There is no crop (`img.cropped` in monuments is a CSS crop). No format
  conversion is required, since Commons gives JPEG/PNG and video/PDF/TIFF stills. There is no
  WebP need.
- So resizing is not needed **for display**. On our own server it is needed **for fetch
  economy and the cache's jobs** (one Commons fetch per file and offline serving), which is
  the user's reason.

## 4. The three modes

| | (a) cache standard widths byte for byte | (b) `thumbs.mode = commons`: no cache, browsers load from `upload.wikimedia.org` | (c) today: one standard fetch, resized locally |
|---|---|---|---|
| Commons fetches per file (server) | **Up to 4** (250, 330, 500, 960 for 4:3), each a separate fetch | 0 from the server. Each juror's browser fetches every image, but it is mostly edge hits | **1** |
| AWT/ImageIO | None | None | Required (headless) |
| Privacy | Same as (c) | Browsers contact Wikimedia (as Montage does). CSP `img-src 'self' data: https://*` already allows it | Only our server contacts Wikimedia |
| Signed-in gate on thumbnails | Kept | Lost: the images are public on Commons anyway | Kept |
| Speed | Our server | Wikimedia edge (esams/drmrs in Europe), cached after first view | Our server; scaled once, then served from disk |
| Commons outage or rate limit | Survives once cached | Images fail; the client `onerror` falls back to the app route | Survives once cached; prefetch ahead of judging |
| Deleted/renamed files | Kept | App route as fallback only | Kept |
| Bytes per page | About 2x (§3) | About 2x (§3) | Smallest |
| Code removed | `saveResized`, `cascadeScale`, `scale`, `ImageIO` decode and encode, `ResizeSpec`, `SaveResizedBenchmark` | Also the prefetch, the "Cache images" UI and the walk; `local-path` becomes optional | None |

- **On our own server, (c) is right.** (a) gives up the fetch economy the user wants. (b)
  gives up outage resilience and the signed-in gate, and puts every juror on Commons' public
  limits.
- **(b) inside Wikimedia (Toolforge / Cloud VPS).** The robot policy exempts WMCS bots from the
  general limits, but the standard-size rule still applies at the edge. A cache there would be
  served from eqiad through the WMCS proxy (and NFS on Toolforge) instead of the juror's
  nearest edge (`2026-11-04-wikimedia-cloud.md` §6). So in that setting (b) is faster and
  simpler. It needs standard widths everywhere: `thumbUrl`/`srcSet` emitting
  `wikimediaStep(px)` URLs on `upload.wikimedia.org`, as `Global.largeImage` already does.
  Whether proxying counts under ToU §4.5 is still unconfirmed (same doc), which is another
  reason for (b) there.

## 5. Steps for the recommendation, with tests

1. **Redirect to standard widths.** In `ImageProxyController.wikimedia(path)` (and the
   `servedWidths.isEmpty` branch of `atServedWidth`), rewrite the last path segment with
   `LocalImageCacheService.withThumbnailPx(name, Global.wikimediaStep(px))` before redirecting.
   Tests: update `ImageProxyWidthSpec` (lines 137 and 146 expect `400px`, which should now be
   `500px`) and `ImageProxyControllerSpec` (the `upload.wikimedia.org` redirect cases at lines
   78, 135, 146, 157 and 167; 146 expects 120px, which is already standard). Add one case per
   branch: not in DB, unknown size, failed fetch of a former name, inconclusive check.
2. **Make the fake Commons strict.** `test/services/FakeWikimedia.scala` and the
   `withServer` stubs in `LocalImageCacheServiceSpec` answer 400 for a `/thumb/.../<px>px-`
   path whose px isn't in `wikimediaSteps`. Add a spec that `source` / `downloadFromWikimedia`
   only ever request a step or the original, for the `ImageProxyWidthSpec` size list
   (800×600, 4000×3000, 3000×4000, 10000×1000, …). Tests: `LocalImageCacheServiceSpec`,
   `LocalImageCacheFileCheckSpec`, `ImageProxyWidthSpec`.
3. **Panoramas (optional).** Cap `cachedWidths` at the source the cache will fetch (3840, or
   the file width). Alternatively, give the 250-px-tall box a maximum width in
   `thumbnailBoxes`, so no file needs an original over 3840. Tests: `ImageProxyWidthSpec`
   (10000×1000), `LocalImageCacheServiceSpec` (`allSizesCached`, `cleanUpUnusedSizes`: a change
   to `thumbnailBoxes` changes the size signature, and old files get cleaned).
4. **Bandwidth (optional).** Document the measured average source size against
   `rate-per-second`, or add a bytes-per-second throttle to prefetch. Tests:
   `LocalImageCacheServiceSpec` throttle cases, and `FetchAndCacheAllBenchmark` if measured.
5. **Base image:** run `ResizeSpec` and `LocalImageCacheServiceSpec` in the chosen container
   with `java.awt.headless=true`.
6. **Later, for WMCS hosting only:** `thumbs.mode = commons` as sketched in
   `docs/investigations/2026-11-04-wikimedia-cloud.md` §6. Gallery and bar URLs come from
   `wikimediaStep`, there is a tile `onerror` to the app route, and no prefetch runs. Tests:
   `GlobalLargeImageSpec` extended to the gallery and bar helpers, a spec per mode,
   `NoInlineScriptsSpec` (no inline `onerror`), and the browser tests
   (`javascript-test/large-view.test.js`, plus a gallery fixture with a failed tile).

Steps 1 and 2 are about half a day together. The rest are independent.

## Sources

- mediawiki.org, [Common thumbnail sizes](https://www.mediawiki.org/wiki/Common_thumbnail_sizes), 2026-10-20
- Phabricator [T414805](https://phabricator.wikimedia.org/T414805): standard sizes only; 429 → 400 on 2026-05-13; resolved 2026-05-21
- Phabricator [T360589](https://phabricator.wikimedia.org/T360589): thumbnail steps in MediaWiki, 2025-04-10..16
- wikitech, [Robot policy](https://wikitech.wikimedia.org/wiki/Robot_policy), 2026-03-16
- Phabricator [T413570](https://phabricator.wikimedia.org/T413570): media 429s for non-WMF apps, open, 2026-11-02
- Phabricator [T363296](https://phabricator.wikimedia.org/T363296) (Toolforge egress exemption) and [T214313](https://phabricator.wikimedia.org/T214313) (2018, Tool Labs 429s)
- `curl -sI` checks above, 2026-11-04
