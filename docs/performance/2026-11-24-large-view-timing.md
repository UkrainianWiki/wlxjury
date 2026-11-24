# Large view and gallery timing (2026-11-24)

Where the time goes between a juror's keypress and the next file on screen, and in the count-heavy
galleries and statistics pages, measured on the real application to decide whether a client
framework (htmx 4) is needed or cheap changes suffice. Companion to the UI-approach investigation
(branch `docs/react-gallery-investigation`, `docs/investigations/2026-11-24-ui-approach.md`), whose
section 5 used a mock with made-up delays (vote 60 ms, page 40 ms, image 120 ms; relative numbers
only). This is a measurement: nothing in the application changed.

**Status:** measured 2026-11-24 on master `c9a428e8`, one machine, one run per cell (10 interleaved
blocks per variant). The htmx spike (variant E of the brief) was **not** run: the decision was clear
without it (section 1, point 6).

## 1. Summary

1. **Today's wait is round trips and the server, not the image and not a framework.** Keypress to the
   next file's main image painted: **187 ms** (binary round) and **188 ms** (rated) with no network
   delay; **423 / 426 ms** behind 80 ms of latency with HTTP/2 (as production's Apache serves it),
   **516 / 611 ms** with the test server's HTTP/1.1. With no delay about 105 ms of the 187 is the two
   requests' server time (vote POST about 60 ms, next page GET about 44 ms, of which about 40 ms
   queries and 3-4 ms template rendering). The rest is the redirect, the page's 13 style and script
   files and the first paint.
2. **At 80 ms, three dependent round trips are the wait**: the vote POST and its redirect (about
   88 ms), the next page's HTML (done at about 190 ms), then the page's 13 style and script files
   (done at about 390 ms behind HTTP/2, 480 ms behind HTTP/1.1). The main image starts together with
   the styles and ends with them, so it is not on the critical path on a fast link. **That third
   trip is what htmx or any "swap, do not reload" removes.** Limit of this statement: whether those
   files are served from the browser's cache or revalidated on every page was not checked (the data
   point to a network request each time; section 5, open check 1).
3. **Preloading the next file's main image (B) does nothing on a fast link** (+1 to +7 ms) and helps
   only when the image is slow to arrive: on an 8 Mbit/s link -71 to -128 ms (faster in 5-7 of 10
   votes). **A strip that is off (C) or lazy (E) helps only when bandwidth is short**, because the
   strip's five thumbnails compete with the main image: on 8 Mbit/s -75 to -407 ms; on a fast link
   -17 to +20 ms (no effect). **B and C together (D) are the most consistent on a slow link**:
   -201 to -407 ms, faster in 10 of 10 votes in all four slow-link cells.
4. **Galleries are cheap on the server and heavy on the client side**: 50-95 ms of server time for a
   page of 15-18 tiles, 120-160 ms to load with no delay and 500-700 ms at 80 ms behind HTTP/1.1.
   Roughly half the server time of a gallery page is the region and rate counts (two queries, 14-18
   ms each on the fixture's 38 000-file rounds, which are 15-25 times larger per juror than real
   ones), repeated on every page turn although they do not change. The region menu is already
   fetched separately and lazily (23 ms).
5. **The round statistics page is the slow server-side page**: 258 ms (binary) and 105 ms (rated), all
   count queries over `vote` (160 + 74 ms in the binary round), 84 KB of HTML of which 83 % differs
   between two pages. The table fragment (`/roundstat/:round/table`, 244 ms) and the counts fragment
   (`/roundstat/:round/counts`, 161 ms) already exist as endpoints. Contest health (115 ms, 70 ms of
   it one category-membership join) is next, the rounds list (10 ms) is fine.
6. **Recommendation: no framework for the large view yet.** Do the cheap things first (section 6):
   rule out revalidation of the style and script files, bundle them (13 requests), make the strip
   lazy (E) and preload the next image (B) for slow links, then re-measure. htmx would then only
   remove what is left of the third round trip (up to about 200 ms at 80 ms of latency, 35 ms with
   none); that is worth its 2-4 days only if the cheap changes leave it above what jurors tolerate.
   For galleries and statistics: cache or load the counts lazily first; a partial update pays only
   for the large statistics pages.

## 2. Method

**Fixture.** The browser-test fixture server (`gatling.setup.UiShotsServer` through
`browser-test/fixture-server.mjs`): the Gatling fixture database restored from its cached dump (about
38 000 images in one contest, 20 jurors, a binary round 1 and a rated round 2, the measured juror
with about 38 000 files in each, 1277 gallery pages), the application with the production filters,
the production content security policy and CSRF checked on POSTs. Commons and scalawiki credentials
were removed from the server's environment.

**Images never came from Commons in a reported run.** The server's own image proxy fetches from the
fixture's placeholder stub. The large view's main image, however, is rendered with the URL
`https://upload.wikimedia.org/...` (the browser loads it from Wikimedia directly; the proxy is only the
fallback). A first pilot (3 blocks, discarded, not in the data) let those few requests through to the
real host before this was noticed. Every reported run maps `upload.wikimedia.org` to a local HTTPS stub
(Chromium `--host-resolver-rules`, a self-signed certificate made for the run) that answers every path
with one 256 KB PNG and Wikimedia-like headers (`Cache-Control: public, max-age=31536000`,
`Timing-Allow-Origin: *`); each run printed its stub request count. So the image's own server time is
about zero: **the numbers show the page's structure, not Commons' speed.**

**Per-statement SQL logging was off** for every number
(`-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`, checked: no statement lines in the server
log), the same for all variants. The query attribution (section 4) comes from the application's timing
log (`modules.RequestTimingFilter`, `-Dgatling.timing=true`: per request total, work, render and query
ms, and each statement's count and time), on for every run; it costs nothing measurable and is not the
per-statement debug log. The fixture server's start command got these three properties in a local patch.

**Browser and network.** Chromium 1248 through Playwright 1.64.1, headless, 1366x768, one juror signed
in, HTTP cache on. Network conditions by the DevTools protocol (`Network.emulateNetworkConditions`):
latency 0 or 80 ms (added to each request before it is sent; "RTT" below), bandwidth unlimited or
1 000 000 byte/s (8 Mbit/s). "h2" runs go through a local TLS reverse proxy (`h2proxy.mjs`), as Apache
fronts the application in production; the others reach the application's plain HTTP/1.1 port directly,
where Chromium's six connections per host serialise the page's 20 requests.

**Metric.** A capture-phase `keydown` stores `performance.timeOrigin + performance.now()` in
`sessionStorage`; on the next page the main image's `decode()` plus two animation frames mark
"painted", and `DOMContentLoaded` plus a zero timeout marks "interactive" (the keys are bound by the
jQuery ready handler). Navigation and Resource Timing give the breakdown, and the server timing log is
joined by request path and time. The keys are the real handlers (Space or Delete in the binary round,
digits 1-5 in the rated one); the vote paths come from the page's `#large-keys` element (paths, never
URLs).

**Variants** (a cookie read by the templates; a local patch, not in the repository):

| Variant | Change |
|---|---|
| A | today |
| B | `<link rel="preload" as="image" href imagesrcset imagesizes>` for the next file's main image (`hood.following.head`), rendered by the server |
| C | the thumbnail strip is not rendered |
| D | B and C |
| E | the strip's `<img>` get `loading="lazy"` |

The preload's cookie is set one vote earlier than the strip's, so each vote is measured under exactly
one variant. **Pairing:** after 4 warm-up votes of A, 10 blocks of one vote per variant (A B C D E), the
order reversed in every second block; deltas are within a block ("faster in k of 10"). Dwell between
votes 800-1500 ms. The harness, stub, proxy and analysis are in
[`data/large-view-timing/scripts/`](data/large-view-timing/scripts/); every vote's timings and the
server's are in [`data/large-view-timing/`](data/large-view-timing/).

**Environment.** Windows 11 laptop, Intel i7-8550U (4 cores, 1.8 GHz base), 16 GB, OpenJDK 17 for the
server, its MariaDB in Docker, Chromium on the same machine: server and browser share the CPU (a
limitation: noise, and both slower than on separate hosts). The heavy-run slot was held for the
runs; free memory 3.9-4.4 GB.

## 3. The large view

### 3.1 Keypress to next main image painted (ms, medians; delta vs A paired within a block)

Rated round and binary round behave alike; numbers are binary / rated.

| Link | A (min-max) | B | C | D | E |
|---|---|---|---|---|---|
| RTT 0, unlimited (HTTP/1.1) | 187 (169-194) / 188 (175-206) | +7 / +4 | +1 / -10 | -8 / -10 | -5 / -10 |
| RTT 80, unlimited (HTTP/1.1) | 516 (499-549) / 611 (600-635) | +2 / +7 | -9 / -5 | -17 / -3 | -12 / -1 |
| RTT 80, unlimited (h2) | 423 (417-447) / 426 (419-480) | +3 / +1 | +20 / +3 | +7 / +11 | -2 / -15 |
| RTT 80, 8 Mbit/s (HTTP/1.1) | 1028 (856-1169) / 1105 (941-1247) | -128 / -109 | -286 / -407 | -344 / -407 | -314 / -407 |
| RTT 80, 8 Mbit/s (h2) | 924 (765-1107) / 932 (760-1101) | -71 / -124 | -75 / -80 | -201 / -255 | -163 / -163 |

Cells are median paired deltas in ms (negative = faster than A). Faster than A in k of 10 votes, and
the delta ranges, are in the full table (appendix). Reading it: on every unlimited link the variants
are within noise (the spread of A alone is 20-50 ms); the changes matter only when the link's
bandwidth is short enough that the strip's five thumbnails and the 250 KB image queue behind each other.
The "interactive" time (keys bound) is 159 (RTT 0), 394-487 (RTT 80) and does not change with a
variant.

### 3.2 Where today's wait goes (variant A, medians, ms from the keypress)

| Step | RTT 0 | RTT 80 h2 | RTT 80 HTTP/1.1 | RTT 80, 8 Mbit/s h2 |
|---|---|---|---|---|
| keypress to form submit (script) | 1 | 1 | 1 | 1 |
| vote POST, server total | 61 | 57 | 56 | 60 |
| vote POST and redirect received | 64 | 87 | 89 | 118 |
| next page request to first byte (server total 42-45) | 46 | 44 | 45 | 47 |
| next page HTML complete | 114 | 189 | 188 | 246 |
| the page's 13 style and script files complete | 150 | 390 | 477 | 480 |
| DOMContentLoaded | 153 | 392 | 484 | 483 |
| main image fully received (its own duration) | 151 (25) | 396 (199) | 486 (289) | 885 (637) |
| strip: last thumbnail received | 200 | 302 | 480 | 368 |
| **main image painted** | **187** | **423** | **516** | **924** |

(Binary round; the rated round is the same within 10 ms at RTT 0 and 0-95 ms slower otherwise (611 against 516 on HTTP/1.1).) Server side of the page GET,
median: 42-45 ms total, 3-4 ms rendering the template, 32-44 ms in 8-12 queries. Paint follows the
image's last byte by about 25-35 ms.

What the table says:

- **With no latency the server is more than half of the wait**: POST 61 + GET 44 = 105 of 187 ms. The
  vote POST takes 55-70 ms on the server (not broken down further here); the GET runs 8-12 queries (about 40 ms).
- **Latency multiplies the three dependent steps**: 187 to 423 ms with HTTP/2 is +236 ms for 80 ms of
  latency: the POST and the HTML each cost about one latency, the style and script files another.
  HTTP/1.1's six connections add about another 90 ms for 13 files (477 against 390).
- **The main image is not the critical path while it is cheap**: it is requested with the styles and
  is complete when they are. When it is slow (8 Mbit/s) it is: 885 against 480 ms, and then the strip's
  thumbnails (368 ms to finish in the same window) and the image compete.
- The strip's five thumbnails are 1.2 KB each here (the stub's), so on this fixture they cost a
  connection each, not bandwidth; with real thumbnails (about 5-10 KB each) the slow-link gain of C and E
  is larger than measured.

## 4. Galleries and statistics pages

Navigation to the page (full load), medians of 8, after one warm-up load; the "previous page" is the
page the juror or organizer comes from. The previous-page comparison (changed KB) is a line diff of
the two HTML documents: the bytes of the second page's lines that do not occur in the first.

| Page (fixture round sizes: 38 000 files per juror) | HTML KB | changed KB | server ms (queries) | load, RTT 0 | load, RTT 80 HTTP/1.1 |
|---|---|---|---|---|---|
| Juror gallery, binary: page turn | 44 | 20 | 61 (53) | 138 | 695 |
| Juror gallery, binary: filter "unrated" (an empty result) | 18 | 3 | 50 (38) | 122 | 654 |
| Juror gallery, binary: region change | 60 | 40 | 55 (48) | 133 | 683 |
| Region menu, JSON fragment (already lazy) | 4 | - | 23 (19) | 39 | 99 |
| Juror by-rate, rated: page turn | 69 | 27 | 94 (80) | 162 | 504 |
| Juror by-rate, rated: region change | 69 | 30 | 62 (56) | 136 | 504 |
| Juror gallery, rated: filter "rated" | 70 | 5 | 63 (52) | 131 | 499 |
| Round statistics, binary (`/roundstat/1`) | 83 | 71 | 258 (242) | 331 | 659 |
| Round statistics, rated (`/roundstat/2`) | 65 | 53 | 105 (88) | 166 | 490 |
| Round statistics table fragment | 67 | - | 244 (236) | 263 | 257 |
| Round statistics counts fragment (lazy) | 0.1 | - | 161 (152) | 175 | 169 |
| Rounds list | 19 | 7 | 10 (8) | 61 | 409 |
| Contest health | 18 | 9 | 115 (108) | 158 | 418 |

The load times at 80 ms behind HTTP/1.1 include the 15-18 tile thumbnails and the page's 13 files
over six connections (DOMContentLoaded 490-575 ms against 92-100 ms for the HTML): the same
dependent-trips structure as the large view, three to four times the server time. They would be
lower behind HTTP/2 (galleries were not re-run with it).

### Server time by statement (RTT 0 runs; ms per load, statements over 3 ms)

- **Juror gallery** (binary, any filter): 16-18 ms the region/rate counts joined with the comments
  chip rooms (`SELECT region, rate, SUM(comment present)` over `vote`), 12-14 ms the plain region/rate
  counts (`SELECT region, rate, COUNT(*)`), 8-14 ms the page's tile query; 7 other statements under
  3 ms. **The two count queries are 30 of the 50-60 ms**, identical across page turns.
- **By-rate** (rated): a further 32 ms for the rank/average derived table on page 1 (8 ms with a
  region), the same two count queries (29 ms), the tile query 7-8 ms.
- **Region menu fragment**: one count query, 13 ms, plus the request's own statements.
- **Round statistics** (binary): 160 ms the distinct selected-file counts
  (`SUM(t.a>0) ... FROM (SELECT SUM(rate>0) a ... )`) and 74 ms `GROUP BY jury_id, rate` counts over
  `vote`; (rated) 74 ms the same group-by only. The counts fragment (round files by status joined with
  their authors, 149 ms) is not part of the page.
- **Contest health**: 70 ms the category-membership join (`images` x `category_members`), 28 ms the
  per-round `round_image` status counts.
- **Rounds list**: 8 statements, 8 ms.

Full per-page lists are in the appendix.

### What a partial update would save

| Page | What changes between two requests | Partial update would send | Estimated saving |
|---|---|---|---|
| Gallery page turn (binary) | tile list (about 20 of 44 KB); the counts, the menus and the page frame do not | the tiles (20 KB) | 24 KB of HTML (55 %), the 13 files' round trips and the document parse (about 480 ms of the 695 ms at RTT 80 behind HTTP/1.1); no server saving unless the counts are skipped (below) |
| Gallery filter or region change | tile list and the counts of the filter | tiles and counts | the same client saving; none on the server |
| By-rate page turn / region | tiles (27-30 of 69 KB) | tiles | 40 KB, the same client saving |
| Round statistics, binary | nearly all of the page (71 of 83 KB) is the table | no gain from a swap: the table is the page | none; the server's 258 ms is the cost (below) |
| Round statistics filters (status, type, order) | which rounds are listed | the table | small: the page is its table |
| Contest health, rounds list | everything | - | none |

| Idea | Page | Saves (estimate from the measurements) |
|---|---|---|
| Counts not recomputed on a page turn (cached for the juror and round, dropped by the vote that changes them; or sent only with the first page, the menu already is a lazy fragment) | juror galleries | 30 ms of 50-60 ms server time, here (38 000-file rounds). On a real round (15-25 times smaller) about 1-2 ms: not worth a change by itself |
| Counts loaded lazily as the region menu already is | juror galleries | the same 30 ms off the first paint of the tiles |
| Round statistics: the 160 ms distinct-selected query and the 74 ms group-by cached per round and recomputed after a vote batch (or by the nightly stats job) | round statistics | 234 of 258 ms in the binary round, 74 of 105 in the rated one, on every view; a real round has fewer votes, so less |
| Round statistics: table in a lazily loaded fragment as the counts fragment is | round statistics | first paint of the page frame at about 20 ms instead of 258; the total time does not fall |
| Contest health: category-membership count cached or in the job | contest health | 70 of 115 ms |

## 5. Limits of these measurements

- **One machine, shared CPU.** Browser, application and database run on one laptop: server times are
  inflated and noisy; production's VPS is faster for the application and shared with Apache and
  WordPress. Compare relative values, not absolute ones.
- **Fixture sizes.** The measured juror has 38 000 files per round; real jurors have 15-25 times
  fewer, so every count query above is far cheaper in production; the page's HTML and tile count do
  not scale (18 tiles per page).
- **Network emulation is coarse.** A fixed latency per request (the DevTools protocol's "latency"),
  no TCP or TLS handshake, no loss; HTTP/2 is a local proxy; the 8 Mbit/s profile also throttles the
  application's own files. Wikimedia's CDN was a local stub, so slow or cold images (an uncached file
  from a distant data centre) are only covered by the bandwidth profile.
- **Image bytes.** The main image was 256 KB (a 1280 px thumbnail is often 150-400 KB); thumbnails
  were 1.2 KB (the stub's placeholder; real ones are 5-10 KB).
- **"Painted" is `decode()` plus two animation frames**, not a hardware frame; headless Chromium. The
  same method for all variants.
- **Open checks that could change the recommendation (not done, the slot was taken by another run):**
  1. Whether the style and script files of the large view are cached (the Resource Timing data show
     a 300-byte transfer entry for each on a first load and a network-sized wait on later pages:
     `resources_end - html_end` is 36 ms at RTT 0 and 200-290 ms at RTT 80): look at their
     `Cache-Control` and conditional requests on production (`curl -I` of a file under `/assets`, and
     the browser's network panel on the second large view). If they are revalidated on each page,
     `public, max-age=31536000, immutable` on the digest-named files removes most of the third round
     trip for nothing.
  2. Galleries behind HTTP/2.
- One of the three pilot blocks contained a main image from Wikimedia (see section 2); no result here
  uses it.

## 6. Recommendation

**Large view: not htmx yet; the cheap changes first, in this order.**

1. Make sure the digest-named style and script files are served with a long `Cache-Control` and the
   page does not revalidate them (open check 1). If it does today: the largest saving on the
   list, up to about 200 ms at 80 ms of latency, for a configuration line.
2. Bundle the 13 style and script files into two or three (a small build step; no tool chosen here); with HTTP/2 the gain is smaller (HTTP/1.1: about 90 ms at 80 ms).
3. Strip lazy (E) on a slow link: -163 to -407 ms at 8 Mbit/s, no effect otherwise; zero risk to the
   page. Preload the next image (B) only together with it (D): -201 to -407 ms at 8 Mbit/s, 10 of 10
   votes faster in all four slow-link cells; on a fast link neither costs anything measurable.
4. Server time: the vote POST (55-70 ms) and the next page's queries (about 40 ms for a juror's own
   next file) are the floor with no latency (105 of 187 ms). Fewer queries on the large view's page
   are a separate performance topic; the numbers here are for a 15-25 times larger round.
5. **htmx for the vote and the move** removes the third round trip and the re-parse of the page
   (about 35 ms at no latency, about 200 ms at 80 ms with HTTP/2 if the files are fetched each
   time today, nothing if they are cached). Re-measure after steps 1-3; if the page is below about 300
   ms at 80 ms of latency, jurors do not wait for the framework and 2-4 days of work (and the
   `hx-on` and CSP rules) are not needed.

**Galleries (juror):** *lazy counts / cache*; no partial update. The server time is small (50-95 ms)
and about half of it is the two count queries, which are as large as the whole of a real round's
page. A page turn's full reload is client-bound (files, parse, tiles): the same caching and bundling
as the large view help there; an htmx partial update of the tile list would save about 24 KB and the
file round trips, which caching removes too.

**Round statistics:** *cache* the two count queries (234 of 258 ms) per round; *lazy* the table
only if the 84 KB page's first paint matters; *no partial update* (the page is its table).

**Contest health:** *cache or job* the category-membership count (70 of 115 ms); otherwise *nothing*.

**Rounds list:** *nothing* (10 ms).

## 7. Reproduce

Local patches, none committed: (a) the three system properties in
`browser-test/fixture-server.mjs`'s sbt command (`-Dgatling.timing=true`,
`-Dgatling.timingFile=...`, `-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`); (b) the variant
cookies in `large/large.scala.html` (`lvs`: `C` strip off, `L` lazy; `lvp=1`: preload of
`hood.following.headOption`'s image) and `large/thumbsBar.scala.html` (`loading="lazy"`).

```
node browser-test/fixture-server.mjs start
# a self-signed certificate for the stub and the proxy, next to the scripts:
#   openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=upload.wikimedia.org
SERVER_JSON=target/ui-shots/server.json PW_DIR=<dir with node_modules/playwright-core> \
TIMING_LOG=target/lvbench-timing.jsonl ROUND=binary RTT=80 BPS=1000000 H2=1 N=10 WARM=4 \
DWELL=1500 VARIANTS=A,B,C,D,E OUT=lv-binary-h2-rtt80-8mbit.json node scripts/lv-bench.mjs
SCENARIOS=scripts/scenarios.json RTT=0 REPS=8 OUT=pages-rtt0.json node scripts/page-bench.mjs
python scripts/analyze.py .        # the appendix's tables from the JSON files
```

Result files: `lv-<round>[-h2]-rtt<ms>[-8mbit].json` (one object per vote, warm-up marked) and
`pages-rtt<ms>.json` (per page: HTML size, changed bytes, every load's timings and the server's, its
statements cut to 160 characters).

## Appendix: full tables (from `scripts/analyze.py`)

### Large view: keypress to next file's main image painted (ms)

| round | link | variant | median | min-max | paired delta vs A (median, range) | faster than A in | interactive median |
|---|---|---|---|---|---|---|---|
| binary | h2, RTT 80, 8 Mbit/s | A | 924 | 765-1107 | - | - | 484 |
| binary | h2, RTT 80, 8 Mbit/s | B | 784 | 745-923 | -71 (-347..+111) | 7 of 10 | 483 |
| binary | h2, RTT 80, 8 Mbit/s | C | 845 | 800-864 | -75 (-257..+80) | 5 of 10 | 483 |
| binary | h2, RTT 80, 8 Mbit/s | D | 685 | 676-833 | -201 (-397..-75) | 10 of 10 | 475 |
| binary | h2, RTT 80, 8 Mbit/s | E | 758 | 654-958 | -163 (-415..+150) | 5 of 10 | 491 |
| binary | h2, RTT 80 | A | 423 | 417-447 | - | - | 394 |
| binary | h2, RTT 80 | B | 430 | 423-466 | +3 (-7..+49) | 3 of 10 | 400 |
| binary | h2, RTT 80 | C | 445 | 394-476 | +20 (-29..+42) | 3 of 10 | 418 |
| binary | h2, RTT 80 | D | 436 | 391-488 | +7 (-26..+54) | 4 of 10 | 413 |
| binary | h2, RTT 80 | E | 422 | 375-506 | -2 (-42..+86) | 5 of 10 | 407 |
| binary | RTT 0, unlimited | A | 187 | 169-194 | - | - | 159 |
| binary | RTT 0, unlimited | B | 195 | 175-211 | +7 (-11..+32) | 2 of 10 | 169 |
| binary | RTT 0, unlimited | C | 185 | 171-2234 | +1 (-23..+2060) | 5 of 10 | 162 |
| binary | RTT 0, unlimited | D | 172 | 164-197 | -8 (-25..+22) | 5 of 10 | 151 |
| binary | RTT 0, unlimited | E | 178 | 168-192 | -5 (-18..+14) | 5 of 10 | 156 |
| binary | RTT 80, 8 Mbit/s | A | 1028 | 856-1169 | - | - | 587 |
| binary | RTT 80, 8 Mbit/s | B | 890 | 872-921 | -128 (-290..+34) | 7 of 10 | 591 |
| binary | RTT 80, 8 Mbit/s | C | 753 | 729-766 | -286 (-416..-90) | 10 of 10 | 560 |
| binary | RTT 80, 8 Mbit/s | D | 685 | 669-694 | -344 (-495..-176) | 10 of 10 | 574 |
| binary | RTT 80, 8 Mbit/s | E | 708 | 670-757 | -314 (-491..-125) | 10 of 10 | 568 |
| binary | RTT 80, unlimited | A | 516 | 499-549 | - | - | 487 |
| binary | RTT 80, unlimited | B | 526 | 502-548 | +2 (-17..+49) | 4 of 10 | 499 |
| binary | RTT 80, unlimited | C | 502 | 496-539 | -9 (-27..+31) | 6 of 10 | 494 |
| binary | RTT 80, unlimited | D | 503 | 475-525 | -17 (-56..+24) | 7 of 10 | 495 |
| binary | RTT 80, unlimited | E | 503 | 479-534 | -12 (-65..+36) | 6 of 10 | 487 |
| rated | h2, RTT 80, 8 Mbit/s | A | 932 | 760-1101 | - | - | 492 |
| rated | h2, RTT 80, 8 Mbit/s | B | 809 | 756-876 | -124 (-318..+116) | 5 of 10 | 498 |
| rated | h2, RTT 80, 8 Mbit/s | C | 838 | 814-957 | -80 (-285..+170) | 5 of 10 | 504 |
| rated | h2, RTT 80, 8 Mbit/s | D | 689 | 652-728 | -255 (-415..-55) | 10 of 10 | 496 |
| rated | h2, RTT 80, 8 Mbit/s | E | 767 | 679-858 | -163 (-422..+71) | 5 of 10 | 494 |
| rated | h2, RTT 80 | A | 426 | 419-480 | - | - | 395 |
| rated | h2, RTT 80 | B | 437 | 423-456 | +1 (-42..+36) | 5 of 10 | 404 |
| rated | h2, RTT 80 | C | 439 | 398-458 | +3 (-54..+33) | 5 of 10 | 411 |
| rated | h2, RTT 80 | D | 442 | 400-452 | +11 (-35..+31) | 4 of 10 | 416 |
| rated | h2, RTT 80 | E | 416 | 403-450 | -15 (-53..+25) | 9 of 10 | 399 |
| rated | RTT 0, unlimited | A | 188 | 175-206 | - | - | 158 |
| rated | RTT 0, unlimited | B | 192 | 180-226 | +4 (-25..+38) | 3 of 10 | 161 |
| rated | RTT 0, unlimited | C | 173 | 166-184 | -10 (-37..-2) | 10 of 10 | 150 |
| rated | RTT 0, unlimited | D | 182 | 167-214 | -10 (-19..+39) | 8 of 10 | 160 |
| rated | RTT 0, unlimited | E | 182 | 170-204 | -10 (-27..+24) | 7 of 10 | 160 |
| rated | RTT 80, 8 Mbit/s | A | 1105 | 941-1247 | - | - | 687 |
| rated | RTT 80, 8 Mbit/s | B | 988 | 935-1045 | -109 (-290..+75) | 5 of 10 | 694 |
| rated | RTT 80, 8 Mbit/s | C | 691 | 654-722 | -407 (-588..-252) | 10 of 10 | 657 |
| rated | RTT 80, 8 Mbit/s | D | 701 | 674-730 | -407 (-549..-247) | 10 of 10 | 666 |
| rated | RTT 80, 8 Mbit/s | E | 686 | 676-716 | -407 (-564..-260) | 10 of 10 | 650 |
| rated | RTT 80, unlimited | A | 611 | 600-635 | - | - | 578 |
| rated | RTT 80, unlimited | B | 612 | 593-637 | +7 (-30..+23) | 3 of 10 | 584 |
| rated | RTT 80, unlimited | C | 605 | 587-627 | -5 (-26..+25) | 7 of 10 | 576 |
| rated | RTT 80, unlimited | D | 612 | 588-632 | -3 (-21..+24) | 5 of 10 | 585 |
| rated | RTT 80, unlimited | E | 610 | 597-638 | -1 (-26..+31) | 7 of 10 | 590 |

### Large view breakdown, variant A, medians (ms from keypress)

| round | link | keypress to form submit | vote POST + redirect | next page request to first byte | HTML download | HTML end | CSS/JS end | DOMContentLoaded | main image: response end | main image: duration | strip: images | strip: last image end | painted | server POST total | server GET total | server GET render | server GET queries |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| binary | h2-rtt80-8mbit | 1 | 118 | 47 | 74 | 246 | 480 | 483 | 885 | 637 | 5 | 368 | 924 | 60 | 44 | 3 | 42 |
| binary | h2-rtt80 | 1 | 87 | 44 | 53 | 189 | 390 | 392 | 396 | 199 | 5 | 302 | 423 | 57 | 42 | 3 | 38 |
| binary | rtt0 | 1 | 64 | 46 | 0 | 114 | 150 | 153 | 151 | 25 | 5 | 200 | 187 | 61 | 44 | 4 | 44 |
| binary | rtt80-8mbit | 2 | 121 | 46 | 78 | 247 | 580 | 585 | 980 | 726 | 5 | 580 | 1028 | 71 | 45 | 3 | 41 |
| binary | rtt80 | 1 | 89 | 45 | 49 | 188 | 477 | 484 | 486 | 289 | 5 | 480 | 516 | 56 | 43 | 4 | 40 |
| rated | h2-rtt80-8mbit | 1 | 120 | 48 | 71 | 246 | 487 | 490 | 882 | 637 | 5 | 375 | 932 | 46 | 45 | 3 | 44 |
| rated | h2-rtt80 | 2 | 88 | 46 | 49 | 189 | 392 | 394 | 399 | 200 | 5 | 304 | 426 | 48 | 42 | 3 | 35 |
| rated | rtt0 | 2 | 63 | 43 | 0 | 112 | 147 | 153 | 155 | 29 | 5 | 222 | 188 | 59 | 42 | 4 | 36 |
| rated | rtt80-8mbit | 2 | 114 | 45 | 72 | 240 | 680 | 685 | 1073 | 816 | 5 | 572 | 1105 | 56 | 44 | 3 | 32 |
| rated | rtt80 | 1 | 89 | 44 | 52 | 191 | 572 | 576 | 581 | 379 | 5 | 482 | 611 | 49 | 42 | 4 | 34 |

### Gallery and statistics pages


### RTT 0 ms (medians of 8 loads)

| page | HTML KB | changed vs previous page KB | server total | server queries | server render | TTFB | HTML end | DCL | load | LCP | images (n) | DOM nodes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| juror gallery, binary: page turn | 44 | 20 | 61 | 53 | 50 | 62 | 63 | 135 | 138 | 134 | 18 | 462 |
| juror gallery, binary: filter unrated | 18 | 3 | 50 | 38 | 37 | 51 | 55 | 108 | 122 | - | 0 | 203 |
| juror gallery, binary: region change | 60 | 40 | 55 | 48 | 45 | 56 | 57 | 124 | 133 | 148 | 18 | 464 |
| juror regions menu fragment, binary | 4 | - | 23 | 19 | 0 | 24 | 26 | 39 | 39 | - | 0 | 7 |
| juror by-rate, rated: page turn | 69 | 27 | 94 | 80 | 51 | 95 | 97 | 158 | 162 | - | 15 | 516 |
| juror by-rate, rated: region change | 69 | 30 | 62 | 56 | 30 | 63 | 65 | 132 | 136 | 192 | 15 | 518 |
| juror gallery, rated: filter rated | 70 | 5 | 63 | 52 | 21 | 64 | 65 | 127 | 131 | - | 15 | 516 |
| juror regions menu fragment, rated | 4 | - | 22 | 20 | 0 | 23 | 26 | 38 | 38 | - | 0 | 7 |
| organizer round statistics, binary | 83 | 71 | 258 | 242 | 0 | 262 | 264 | 330 | 331 | - | 0 | 935 |
| organizer round statistics, rated | 65 | 53 | 105 | 88 | 0 | 108 | 112 | 164 | 166 | - | 0 | 758 |
| organizer roundstat table fragment | 67 | - | 244 | 236 | 0 | 246 | 248 | 261 | 263 | - | 0 | 769 |
| organizer roundstat counts fragment | 0 | - | 161 | 152 | 0 | 163 | 166 | 175 | 175 | - | 0 | 7 |
| organizer rounds list | 19 | 7 | 10 | 8 | 0 | 12 | 13 | 58 | 61 | - | 0 | 207 |
| organizer contest health | 18 | 9 | 115 | 108 | 0 | 117 | 118 | 152 | 158 | - | 0 | 206 |

### RTT 80 ms (medians of 8 loads)

| page | HTML KB | changed vs previous page KB | server total | server queries | server render | TTFB | HTML end | DCL | load | LCP | images (n) | DOM nodes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| juror gallery, binary: page turn | 44 | 20 | 66 | 56 | 52 | 67 | 92 | 569 | 695 | 592 | 18 | 462 |
| juror gallery, binary: filter unrated | 18 | 3 | 51 | 40 | 40 | 52 | 88 | 562 | 654 | 494 | 0 | 203 |
| juror gallery, binary: region change | 60 | 40 | 52 | 44 | 42 | 53 | 89 | 576 | 683 | 666 | 18 | 464 |
| juror regions menu fragment, binary | 4 | - | 21 | 19 | 0 | 22 | 88 | 99 | 99 | - | 0 | 7 |
| juror by-rate, rated: page turn | 69 | 27 | 97 | 82 | 53 | 98 | 100 | 496 | 504 | 416 | 15 | 516 |
| juror by-rate, rated: region change | 69 | 30 | 63 | 53 | 30 | 64 | 93 | 492 | 504 | 420 | 15 | 518 |
| juror gallery, rated: filter rated | 70 | 5 | 56 | 46 | 21 | 57 | 98 | 491 | 499 | 420 | 15 | 516 |
| juror regions menu fragment, rated | 4 | - | 27 | 20 | 0 | 28 | 92 | 103 | 103 | - | 0 | 7 |
| organizer round statistics, binary | 83 | 71 | 249 | 240 | 0 | 251 | 253 | 642 | 659 | 600 | 0 | 935 |
| organizer round statistics, rated | 65 | 53 | 95 | 82 | 0 | 97 | 102 | 488 | 490 | 426 | 0 | 758 |
| organizer roundstat table fragment | 67 | - | 237 | 232 | 0 | 239 | 243 | 255 | 257 | - | 0 | 769 |
| organizer roundstat counts fragment | 0 | - | 157 | 153 | 0 | 158 | 161 | 169 | 169 | - | 0 | 7 |
| organizer rounds list | 19 | 7 | 11 | 8 | 0 | 13 | 90 | 405 | 409 | 340 | 0 | 207 |
| organizer contest health | 18 | 9 | 100 | 94 | 0 | 101 | 103 | 404 | 412 | 340 | 0 | 206 |

### Queries per page (RTT 0 run; median ms per load, count per load; statements over 3 ms)


**juror gallery, binary: page turn** (server total 61 ms, queries 53 ms, 10 statements)

- 18 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 12 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 9 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`
- 7 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror gallery, binary: filter unrated** (server total 50 ms, queries 38 ms, 9 statements)

- 16 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 12 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 3 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 3 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror gallery, binary: region change** (server total 55 ms, queries 48 ms, 9 statements)

- 16 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 8 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 4 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`

**juror regions menu fragment, binary** (server total 23 ms, queries 19 ms, 6 statements)

- 13 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`

**juror by-rate, rated: page turn** (server total 94 ms, queries 80 ms, 11 statements)

- 32 ms x1: `select ... from ( select IF(SUM(s.rate <> N) = N, N, sum(GREATEST(s.rate, N)) * N DIV SUM(s.rate <> N)) as k0, sum(GREATEST(s.rate, N)) as k1 from `
- 15 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 8 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 5 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`
- 3 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror by-rate, rated: region change** (server total 62 ms, queries 56 ms, 10 statements)

- 17 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 9 ms x1: `select ... from ( select IF(SUM(s.rate <> N) = N, N, sum(GREATEST(s.rate, N)) * N DIV SUM(s.rate <> N)) as k0, sum(GREATEST(s.rate, N)) as k1 from `
- 7 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 6 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror gallery, rated: filter rated** (server total 63 ms, queries 52 ms, 10 statements)

- 16 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 3 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`

**juror regions menu fragment, rated** (server total 22 ms, queries 20 ms, 6 statements)

- 13 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`

**organizer round statistics, binary** (server total 258 ms, queries 242 ms, 11 statements)

- 160 ms x1: `SELECT COALESCE(SUM(t.a > N), N), COALESCE(SUM(t.u > N), N), COALESCE(SUM(t.r > N), N) FROM ( SELECT SUM(s.rate > N) a, SUM(s.rate = N) u, SUM(s.rate `
- 74 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`
- 8 ms x1: `select round_user.user_id as ui_on_round_user, round_user.round_id as ri_on_round_user, round_user.role as r_on_round_user, round_user.active as a_on_`
- 7 ms x1: `SELECT COUNT(*), COALESCE(SUM(EXISTS (SELECT N FROM round_image ri WHERE ri.round_id = ? AND ri.page_id = i.page_id AND ri.status = ?)), N) FROM image`
- 6 ms x1: `SELECT COALESCE(SUM(c.round_id = ?), N), COUNT(DISTINCT CASE WHEN c.round_id = ? THEN c.room END), COUNT(*), COUNT(DISTINCT c.room) FROM comment c WHE`

**organizer round statistics, rated** (server total 105 ms, queries 88 ms, 9 statements)

- 74 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`
- 10 ms x1: `SELECT COALESCE(SUM(c.round_id = ?), N), COUNT(DISTINCT CASE WHEN c.round_id = ? THEN c.room END), COUNT(*), COUNT(DISTINCT c.room) FROM comment c WHE`
- 5 ms x1: `SELECT COUNT(*), COALESCE(SUM(EXISTS (SELECT N FROM round_image ri WHERE ri.round_id = ? AND ri.page_id = i.page_id AND ri.status = ?)), N) FROM image`
- 3 ms x1: `SELECT ru.user_id FROM round_user ru WHERE ru.round_id = ? AND ( EXISTS (SELECT N FROM vote v WHERE v.round_id = ? AND v.jury_id = ru.user_id AND v.ra`

**organizer roundstat table fragment** (server total 244 ms, queries 236 ms, 8 statements)

- 150 ms x1: `SELECT COALESCE(SUM(t.a > N), N), COALESCE(SUM(t.u > N), N), COALESCE(SUM(t.r > N), N) FROM ( SELECT SUM(s.rate > N) a, SUM(s.rate = N) u, SUM(s.rate `
- 74 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`

**organizer roundstat counts fragment** (server total 161 ms, queries 152 ms, 7 statements)

- 149 ms x1: `SELECT ri.status, MIN(i.author), COUNT(*) FROM round_image ri STRAIGHT_JOIN images i ON i.page_id = ri.page_id WHERE ri.round_id = ? GROUP BY ri.statu`

**organizer rounds list** (server total 10 ms, queries 8 ms, 8 statements)


**organizer contest health** (server total 115 ms, queries 108 ms, 15 statements)

- 70 ms x1: `select ... from images i inner join category_members cl on i.page_id = cl.page_id where cl.category_id = ? and i.deleted_at is null`
- 28 ms x1: `SELECT round_id, COUNT(*) FROM round_image WHERE round_id IN (?...) AND status = ? GROUP BY round_id`
- 7 ms x1: `SELECT ru.round_id, COUNT(*) FROM round_user ru JOIN round r ON r.id = ru.round_id WHERE ru.round_id IN (?...) AND ru.active = TRUE AND EXISTS (SELECT`
- 6 ms x1: `SELECT r.id FROM round r WHERE r.id IN (?...) AND r.distributing = N AND (r.active = N OR (r.freeze_count > N AND r.active_before_freeze = N))`
- 6 ms x1: `SELECT EXISTS(SELECT N FROM job WHERE contest_id = ? AND created_at >= NOW() - INTERVAL ? DAY) OR EXISTS(SELECT N FROM round WHERE contest_id = ? AND `
- 6 ms x1: `SELECT id, COALESCE(status_changed_at, created_at) >= NOW() - INTERVAL ? DAY FROM round WHERE contest_id = ?`
