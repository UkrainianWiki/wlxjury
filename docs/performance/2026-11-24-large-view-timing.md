# Large view and gallery timing (2026-11-24)

Where the time goes between a juror's keypress and the next file on screen, and in the count-heavy
galleries and statistics pages, measured on the real application to decide whether a client
framework (htmx 4) is needed or cheap changes suffice. Companion to the UI-approach investigation
(branch `docs/react-gallery-investigation`, `docs/investigations/2026-11-24-ui-approach.md`), whose
section 5 used a mock with made-up delays (vote 60 ms, page 40 ms, image 120 ms; relative numbers
only). This is a measurement: nothing in the application changed.

**Status:** measured 2026-11-24 on master `c9a428e8`; **one run per cell** (a cell is a round, a
network profile and a variant set; each variant has 10 interleaved votes in it); a first set of runs
had two design faults and was replaced (section 2.4). The htmx spike was **not** run: the numbers
decide without it (section 1).

## 1. Summary

1. **In production terms the wait is two round trips, the server's time and the image; the page's own
   files are not part of it.** Keypress to the next file's main image painted, binary / rated round:
   **182 / 174 ms** with no network delay, **340 / 344 ms** behind 80 ms of latency (HTTP/2, files
   cached as in production), **652 / 645 ms** at 80 ms and 8 Mbit/s. At 80 ms the median binary wait
   splits into: vote POST and its redirect 95 ms (server 62 ms of it), next page's HTML complete at
   196 ms (a further 101 ms: server 48 ms, one latency, 45 ms of download), the 13 style and script
   files 13 ms later (all from the cache), the main image complete at 307 ms (it starts when the
   HTML has been parsed, at about 210 ms, and takes 95 ms), painted at 340 ms. With no delay 113 of
   the 182 ms are the two requests' server time (POST 62 ms, GET 51 ms, of which 42 ms queries and 4
   ms rendering).
2. **A script that prefetches the next file's main image saves the image's wait**: 330 -> 238 ms at
   80 ms (-90 ms, faster in 10 of 10 votes, both rounds), 651 -> 286 ms at 80 ms and 8 Mbit/s (-364 ms,
   10 of 10; rated -375 ms). It is a `new Image()` with the preload's `src`, `srcset` and `sizes`, run
   from a script file when the page is parsed; the votes were cast 1.8 s (3 s on 8 Mbit/s) after the
   page appeared, longer than the prefetch takes. **A `<link rel="preload" as="image">` for the same
   file does nothing** (+0 to +7 ms): in this Chromium build the preloaded response was not reused by
   the next page (the main image was fetched again in 10 of 10 votes; the script's was taken from the
   cache in 10 of 10).
3. **The thumbnail strip (off or lazy) made no measurable difference** in the clean runs (-17 to +11
   ms, inside the 20-40 ms spread of A), also on 8 Mbit/s. Limit: the fixture's thumbnails are 1.2 KB
   each, real ones 5-10 KB, so the strip's cost is under-measured (section 5).
4. **htmx is not needed for the large view's wait.** What a swap saves is the next document's parse and
   style/script initialisation (DOMContentLoaded 15-30 ms after the HTML here), not a round trip: the
   POST and the page request remain two trips. The one saving a client script could add is returning the
   next file from the vote's own response (one trip instead of two, about 90 ms at 80 ms of latency,
   25 ms with none; an estimate from the breakdown, not measured here). The prefetch is cheaper and
   saves as much (-90 ms) at 80 ms.
5. **Galleries cost 50-110 ms on the server and 140-155 ms to load at 80 ms** (HTTP/2, files cached).
   About half the server time of a gallery page is the two region/rate count queries (14-25 ms each
   on the fixture's 38 000-file rounds, 15-25 times larger per juror than real ones), repeated on
   every page turn although they do not change. The region menu is already a separate lazy request
   (26-32 ms).
6. **Round statistics is the slow page on the server**: 264-269 ms binary and 103 ms rated at 80 ms
   (258-405 ms binary across three runs: the machine's noise), all count
   queries over `vote`; 83 KB of HTML of which 82-85 % differs between two pages. Contest health is
   111 ms, the rounds list 13 ms.
7. **Recommendation:** no framework for the large view. Add the script prefetch of the next main
   image (cheap, measured -90 to -375 ms); leave the strip; re-measure with real thumbnail sizes if
   the strip is touched. For galleries and statistics: cache the counts first, then judge a partial
   update (section 6).

## 2. Method

### 2.1 Fixture and what was kept off Commons

The browser-test fixture server (`gatling.setup.UiShotsServer` through
`browser-test/fixture-server.mjs`): the Gatling fixture database restored from its cached dump
(about 38 000 images in one contest, 20 jurors, a binary round 1 and a rated round 2, the measured
juror with about 38 000 files in each, 1277 gallery pages), the application with the production
filters, the production content security policy and CSRF checked on POSTs. Commons and scalawiki
credentials were removed from the server's environment.

**Images never came from Commons in a reported run.** The server's own image proxy fetches from the
fixture's placeholder stub. The large view's main image, however, is rendered with the URL
`https://upload.wikimedia.org/...` (the browser loads it from Wikimedia directly; the proxy is only the
fallback). A first pilot (15 votes) let those requests through to the real host before this was
noticed: about 25 single-image requests (one main image per vote, 6 preloads, the start page and one
probe page), nothing else. **That pilot's data was discarded and is not in this repository.** Every
reported run maps `upload.wikimedia.org` to a local HTTPS stub (Chromium `--host-resolver-rules`, a
self-signed certificate made for the run) that answers every path with one 256 KB PNG and Wikimedia-like
headers (`Cache-Control: public, max-age=31536000`, `Timing-Allow-Origin: *`); each run printed its stub
request count. So the image's own server time is about zero: **the numbers show the page's structure,
not Commons' speed.**

### 2.2 SQL logging, timing log, browser and network

**Per-statement SQL logging was off** for every number
(`-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`, checked: no statement lines in the server
log), the same for all variants. The query attribution (section 4) comes from the application's timing
log (`modules.RequestTimingFilter`, `-Dgatling.timing=true`: per request total, work, render and query
ms, and each statement's count and time), on for every run; it is not the per-statement debug log. The
fixture server's start command got these three properties in a local patch
([`variants-and-timing.patch`](data/large-view-timing/scripts/variants-and-timing.patch)).

Chromium 1248 through Playwright 1.64.1, headless, 1366x768, one juror signed in, HTTP cache on.
Network conditions by the DevTools protocol (`Network.emulateNetworkConditions`): latency 0 or 80 ms
(a minimum added to each request; "RTT" below), bandwidth unlimited or 1 000 000 byte/s (8 Mbit/s).

**Production-like serving.** Every run in the tables goes through a local TLS reverse proxy
(`h2proxy.mjs`) that speaks HTTP/2 to Chromium, as Apache does in production, and answers `/assets/`
and `/webjars/` with `Cache-Control: public, max-age=31536000, immutable`. Why: production serves its
digest-named files so (a `curl -I` of a digest-named style file under `/assets/` and of a `/webjars/`
file on the production site returned exactly that header, checked by the reviewer of this document,
not repeated here), while the fixture server serves plain names and answers every request with
`Cache-Control: no-cache` and a 304 revalidation (checked here with `scripts/cachecheck.mjs`: a second
and third large view fetched all 14 files over the network (a 304 each); behind the proxy's header all 14 came
from the cache). In the first set of runs that revalidation was a third round trip of about 200 ms
(HTTP/2) to 290 ms (HTTP/1.1) in every page load: **a test-server artefact, not production's
behaviour**, and the reason the first set was replaced.

### 2.3 Metric and variants

A capture-phase `keydown` stores `performance.timeOrigin + performance.now()` in `sessionStorage`; on
the next page the main image's `decode()` plus two animation frames mark "painted", and
`DOMContentLoaded` plus a zero timeout marks "interactive" (the keys are bound by the jQuery ready
handler). Navigation and Resource Timing give the breakdown, and the server timing log is joined by
request path and time. The keys are the real handlers (Space or Delete in the binary round, digits 1-5
in the rated one); the vote paths come from the page's `#large-keys` element (paths, never URLs).

| Variant | Change |
|---|---|
| A | today |
| B | `<link rel="preload" as="image" href imagesrcset imagesizes>` for the next file's main image (`hood.following.head`), rendered by the server |
| C | the thumbnail strip is not rendered |
| D | B and C (set 1 only) |
| E | the strip's `<img>` get `loading="lazy"` (set 1 only) |
| F | B, plus a script that starts `new Image()` with the link's `src`, `srcset`, `sizes` when the page is parsed (set 2 only; injected by the harness for the test) |
| G | F and C (set 2 only) |

The server-side parts are a cookie read by the templates (a local patch, in the data folder). **Set 1**
is A B C D E, **set 2** A B F C G; the profile order within a set was, per round (binary first, then
rated): set 1 RTT 0, RTT 80, RTT 80 with 8 Mbit/s; set 2 RTT 80, RTT 80 with 8 Mbit/s.

### 2.4 Pairing, and what was wrong with the first set

After 4 warm-up votes, 10 blocks of one measured vote per variant, the order reversed in every second
block; deltas are within a block ("faster in k of 10"). **Each measured vote is preceded by an unmeasured
neutral vote**: the neutral vote's destination page is the page the measured vote leaves, and it carries
only that measured vote's preload (so the preload has been fetched when the vote starts), no strip
change. The measured vote's own destination page carries no preload. Dwell before every vote: set 1
1500 ms (RTT 0), 1800 ms (RTT 80), 3000 ms (8 Mbit/s); set 2 1800 and 3000 ms; always longer than the
slow link's prefetch (256 KB at 1 MB/s is 260 ms plus latency).

The first set (removed from the repository) set the next vote's preload cookie on the current vote, so
the page measured under A or C also downloaded the next file's 256 KB in parallel with its own image
(on 8 Mbit/s forward blocks of A took 1065-1239 ms, reverse blocks 787-974 ms); that inflated A on slow
links, and it also ran against the revalidating asset files and HTTP/1.1. Its conclusions about B, C, D
and E on slow links were wrong, and its "third round trip" was the fixture's no-cache header.

### 2.5 Environment

Windows 11 laptop, Intel i7-8550U (4 cores, 1.8 GHz base), 16 GB, OpenJDK 17 for the server, its
MariaDB in Docker, Chromium on the same machine: server and browser share the CPU (a limitation:
noise, and both slower than on separate hosts). The heavy-run slot was held for the runs; free memory
3.9-5 GB. The harness, stub, proxy and analysis are in
[`data/large-view-timing/scripts/`](data/large-view-timing/scripts/); every vote's timings and the
server's are in [`data/large-view-timing/`](data/large-view-timing/).

## 3. The large view

### 3.1 Keypress to next main image painted (ms)

Binary / rated round. A is the median wait of the variant A votes; other cells are the **median paired
delta against A in the same block** (negative = faster); full ranges, "faster in k of 10" and the
cache columns are in the appendix.

| Network profile (HTTP/2, files cached) | A | B link preload | C strip off | D B+C | E strip lazy | F B + script prefetch | G F + strip off |
|---|---|---|---|---|---|---|---|
| RTT 0, set 1 | 182 / 174 | 0 / 0 | 0 / -2 | -6 / 0 | -7 / -4 | - | - |
| RTT 80, set 1 | 340 / 344 | -1 / -6 | +1 / +3 | -1 / -2 | 0 / 0 | - | - |
| RTT 80, set 2 | 330 / 333 | 0 / -1 | +8 / +11 | - | - | **-90 / -88** | -88 / -86 |
| RTT 80, 8 Mbit/s, set 1 | 652 / 645 | +5 / +8 | -14 / -14 | -17 / -9 | -3 / -15 | - | - |
| RTT 80, 8 Mbit/s, set 2 | 651 / 657 | +7 / +2 | -7 / -17 | - | - | **-364 / -375** | -350 / -378 |

A's own spread within a cell is 20-45 ms (min-max in the appendix); the two sets' A differ by 10 ms at
80 ms (340 against 330), which is the run-to-run noise. The "interactive" time (keys bound) is 148-161
(RTT 0), 217-228 (RTT 80) and 257-270 (8 Mbit/s) in every variant: the prefetch does not delay it.

### 3.2 Where today's wait goes (variant A, binary round, medians, ms from the keypress)

| Step | RTT 0 | RTT 80 | RTT 80, 8 Mbit/s |
|---|---|---|---|
| keypress to form submit (script) | 2 | 4 | 2 |
| vote POST: server total | 62 | 62 | 59 |
| vote POST and redirect received | 68 | 95 | 121 |
| next page request to first byte (server total 48-51) | 55 | 52 | 54 |
| next page HTML complete | 128 | 196 | 243 |
| the page's 13 style and script files complete (from cache) | 138 | 209 | 250 |
| DOMContentLoaded | 155 | 224 | 265 |
| main image complete (its own duration) | 143 (3) | 307 (95) | 623 (371) |
| strip: last thumbnail complete | 183 | 306 | 360 |
| **main image painted** | **182** | **340** | **652** |

Server side of the page GET, median: 48-51 ms total, 4 ms rendering the template, 42-46 ms in 8-12
queries. Paint follows the image's last byte by 29-39 ms. The rated round is the same within 10 ms
except its vote POST (48-59 ms) and 16 instead of 13 style and script files; its painted times are 174,
344 and 645 ms.

What the table says, in production terms:

- **No latency: the server is more than half.** POST 62 + GET 51 = 113 of 182 ms; the rest is the
  redirect, parsing, the first paint and decoding the image.
- **At 80 ms the wait is the vote POST (95 ms), the page's HTML (a further 101 ms), and the image
  (95 ms after the HTML is parsed).** The page's 13 files cost 13 ms because they come from the cache.
  The image is the last link in the chain, and the one a prefetch removes.
- **On a slow link the image dominates**: it is 371 ms of the 652 ms at 8 Mbit/s.
- **What htmx could remove is small.** The HTML to DOMContentLoaded part is 15-30 ms; the
  round trips stay. Merging the vote's response with the next file (one trip instead of two) would
  save about one latency plus the redirect hop (about 90 ms at 80 ms of latency, 25 ms with none), an
  estimate; no spike was run.

## 4. Galleries and statistics pages

Navigation to the page (full load) over HTTP/2 with cached files, medians of 8 loads after one warm-up
load; the "previous page" is the page the juror or organizer comes from. The previous-page comparison
(changed KB) is a line diff of the two HTML documents: the bytes of the second page's lines that do not
occur in the first. Server time at RTT 80 (the steadier run: the RTT 0 run shows the same pages 0-50 %
slower for the heavier ones; the appendix has both).

| Page (fixture round sizes: 38 000 files per juror) | HTML KB | changed KB | server ms (queries) | load, RTT 0 | load, RTT 80 |
|---|---|---|---|---|---|
| Juror gallery, binary: page turn | 44 | 20 | 78 (58) | 179 | 140 |
| Juror gallery, binary: filter "unrated" (an empty result) | 18 | 3 | 48 (34) | 96 | 131 |
| Juror gallery, binary: region change | 60 | 40 | 64 (52) | 165 | 153 |
| Region menu, JSON fragment (already lazy) | 4 | - | 26 (19) | 52 | 105 |
| Juror by-rate, rated: page turn | 69 | 27 | 95 (80) | 197 | 155 |
| Juror by-rate, rated: region change | 69 | 30 | 71 (58) | 155 | 143 |
| Juror gallery, rated: filter "rated" | 70 | 5 | 70 (51) | 177 | 144 |
| Round statistics, binary (`/roundstat/1`) | 83 | 71 | 269 (259) | 490 | 320 |
| Round statistics, rated (`/roundstat/2`) | 65 | 53 | 103 (88) | 145 | 158 |
| Round statistics table fragment | 67 | - | 250 (244) | 308 | 271 |
| Round statistics counts fragment (lazy) | 0.1 | - | 163 (154) | 243 | 178 |
| Rounds list | 19 | 7 | 13 (8) | 67 | 122 |
| Contest health | 18 | 9 | 111 (96) | 134 | 142 |

(Load at RTT 80 is not always above RTT 0: the RTT 0 runs shared the machine with a heavier server
phase; compare columns within a run, not across.) With the files cached the gallery's client side is
about 40-60 ms after its HTML (DOMContentLoaded 130-150 ms at 80 ms), so server time and one latency
are most of it.

### Server time by statement (RTT 0 run; ms per load, statements over 3 ms)

- **Juror gallery** (binary, any filter): 18-20 ms the region/rate counts joined with the comments
  chip rooms (`SELECT region, rate, SUM(comment present)` over `vote`), 14 ms the plain region/rate
  counts, 6-8 ms the tile query. **The two count queries are 32-34 of the 54-68 ms**, identical across
  page turns.
- **By-rate** (rated): a further 44 ms for the rank/average derived table on page 1 (14 ms with a
  region), the same two count queries (38-39 ms), the tile query 10 ms.
- **Region menu fragment**: one count query, 15-16 ms, plus the request's own statements.
- **Round statistics** (binary): 160-250 ms the distinct selected-file counts
  (`SUM(t.a>0) ... FROM (SELECT SUM(rate>0) a ...)`) and 74-118 ms `GROUP BY jury_id, rate` counts over
  `vote` (the range is three runs); (rated) 72-74 ms the group-by only. The counts fragment (round files
  by status joined with their authors, 150-220 ms) is not part of the page.
- **Contest health**: about 70 ms the category-membership join (`images` x `category_members`), 28 ms the
  per-round `round_image` status counts.
- **Rounds list**: 8 statements, 8 ms.

Full per-page lists are in the appendix.

### What a partial update would save

| Page | What changes between two requests | Partial update would send | Estimated saving |
|---|---|---|---|
| Gallery page turn (binary) | tile list (about 20 of 44 KB); the counts, the menus and the page frame do not | the tiles (20 KB) | 24 KB of HTML and about 40 ms of document parse; no server saving unless the counts are skipped (below) |
| Gallery filter or region change | tile list and the counts of the filter | tiles and counts | the same client saving; none on the server |
| By-rate page turn / region | tiles (27-30 of 69 KB) | tiles | about 40 KB, the same client saving |
| Round statistics | nearly all of the page (71 of 83 KB, 85 %; rated 53 of 65 KB, 82 %) is the table | no gain from a swap: the table is the page | none; the server's 103-269 ms is the cost (below) |
| Contest health, rounds list | everything | - | none |

| Idea | Page | Saves (estimate from the measurements) |
|---|---|---|
| Counts not recomputed on a page turn (cached for the juror and round, dropped by the vote that changes them; or sent only with the first page) | juror galleries | 32-34 ms of 54-68 ms server time here (38 000-file rounds). On a real round (15-25 times smaller) about 1-2 ms: not worth a change by itself |
| Counts loaded lazily as the region menu already is | juror galleries | the same 32 ms off the first paint of the tiles |
| Round statistics: the distinct-selected query and the group-by cached per round and recomputed after a vote batch (or by the nightly stats job) | round statistics | about 235 of 269 ms in the binary round, 72 of 103 in the rated one, on every view; a real round has fewer votes, so less |
| Round statistics: table in a lazily loaded fragment | round statistics | first paint of the page frame at about 20 ms instead of 269; the total does not fall |
| Contest health: category-membership count cached or in the job | contest health | 70 of 111 ms |

## 5. Limits of these measurements

- **One run per cell, one machine, shared CPU.** Browser, application and database run on one laptop:
  server times are inflated and noisy (the same roundstat page took 258, 320 and 405 ms in three runs);
  production's VPS is faster for the application and shared with Apache and WordPress. Compare relative
  values, not absolute ones.
- **Fixture sizes.** The measured juror has 38 000 files per round; real jurors have 15-25 times fewer,
  so every count query above is far cheaper in production; the page's HTML and tile count do not scale
  (15-18 tiles per page).
- **Network emulation is coarse.** A fixed latency per request (the DevTools protocol's "latency"), no
  TCP or TLS handshake, no loss; HTTP/2 is a local proxy and the cache header is the proxy's, not the
  application's; the 8 Mbit/s profile also throttles the application's own files. Wikimedia's CDN was a
  local stub, so a slow or cold image (an uncached file from a distant data centre) is only covered by
  the bandwidth profile.
- **Image bytes.** The main image was 256 KB (a 1280 px thumbnail is often 150-400 KB), thumbnails
  1.2 KB (the stub's placeholder; real ones 5-10 KB), so **the strip's effect is under-measured**: five
  real thumbnails are 25-50 KB against the main image's 250 KB, 10-20 % of the bytes.
- **The prefetch's votes came after a dwell of 1.8-3 s.** A juror who votes within 200-300 ms of the
  page appearing gets less (the prefetch of 256 KB at 8 Mbit/s takes 260 ms plus latency); one more
  256 KB is downloaded per file whether or not the juror stays on it.
- **The link preload's failure is an observation of this Chromium build** (1248, headless), confirmed
  once by hand (the second page's main image transferred in full after a preload, from the cache after
  the script's `Image`); not a statement about other browsers.
- **"Painted" is `decode()` plus two animation frames**, not a hardware frame; headless Chromium. The same
  method for all variants.
- **The asset header evidence is the reviewer's** (`curl -I` on the production site); the test setup
  adds the header at its proxy. If production's Apache adds a different or no-cache header to any file
  the large view loads, the third round trip of the first set returns.

## 6. Recommendation

**Large view: no htmx; one cheap script.**

1. Add a script file next to `large-keys.js` that, when the page is parsed, starts the browser's fetch
   of the next file's main image (the same URL the next page will choose; the server renders it as the
   `data-` attributes the preload link had, or the link itself, which the script reads). Measured:
   -90 ms at 80 ms, -364 ms at 80 ms and 8 Mbit/s. It must be a script `new Image()`; a `<link
   rel=preload>` alone did nothing here. CSP: a file under `/assets/`, no inline script.
2. Leave the strip alone; if it is changed, re-measure with real thumbnails (5-10 KB each).
3. Server time is the floor with no latency: the vote POST (about 60 ms) and the next page's queries
   (42-46 ms for a juror's own next file) are 113 of 182 ms. Fewer queries on the large view's page is a
   separate performance topic (the numbers here are for rounds 15-25 times larger than real ones).
4. **htmx for the vote and the move** would save at most about 30 ms of parse, or about one round trip
   (90 ms at 80 ms latency) if the vote's response carries the next file; the prefetch gives -90 ms
   alone. Revisit only if jurors still wait too long after the prefetch (above about 250 ms at 80 ms of
   latency) and a one-trip vote is wanted; the cost is 2-4 days plus the `hx-on` and CSP rules.

**Galleries (juror):** *lazy counts / cache*; no partial update yet. The server time is small
(48-95 ms) and about half of it is the two count queries; a page turn's full reload is 140-155 ms at
80 ms. A partial update of the tile list would save about 24 KB and 40 ms of parse.

**Round statistics:** *cache* the two count queries (about 235 of 269 ms per view in the binary round);
*lazy* the table only if the first paint of the page frame matters; *no partial update* (the page is its
table).

**Contest health:** *cache or job* the category-membership count (70 of 111 ms); otherwise *nothing*.

**Rounds list:** *nothing* (13 ms).

## 7. Reproduce

Local patch, committed as [`variants-and-timing.patch`](data/large-view-timing/scripts/variants-and-timing.patch)
(apply with `git apply` to the commit above): the three system properties in
`browser-test/fixture-server.mjs`'s sbt command (`-Dgatling.timing=true`,
`-Dgatling.timingFile=...`, `-Dscalikejdbc.global.loggingSQLAndTime.enabled=false`), and the variant
cookies in `large/large.scala.html` (`lvs`: `C` strip off, `L` lazy; `lvp=1`: preload link for
`hood.following.headOption`'s image) and `large/thumbsBar.scala.html` (`loading="lazy"`). The
script prefetch (F) is the harness's init script (`lvj` cookie), not an application change.

```
node browser-test/fixture-server.mjs start
# a self-signed certificate for the stub and the proxy, in the scripts folder:
#   openssl req -x509 -newkey rsa:2048 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=upload.wikimedia.org
# PW_DIR: a folder whose node_modules has playwright-core (browser-test's, after its npm ci)
cd docs/performance/data/large-view-timing/scripts
SERVER_JSON=../../../../../target/ui-shots/server.json PW_DIR=../../../../../browser-test \
TIMING_LOG=../../../../../target/lvbench-timing.jsonl H2=1 IMMUTABLE=1 BPS=1000000 \
ROUND=binary RTT=80 N=10 WARM=4 DWELL=3000 VARIANTS=A,B,F,C,G OUT=lv3-binary-rtt80-8mbit.json node lv-bench.mjs
SERVER_JSON=... H2=1 IMMUTABLE=1 SCENARIOS=scenarios.json RTT=80 REPS=8 OUT=pages-rtt80.json node page-bench.mjs
python analyze.py ..                # the appendix's tables from the JSON files
```

Result files: `lv2-*` (set 1) and `lv3-*` (set 2), `<set>-<round>-rtt<ms>[-8mbit].json` (one object per
measured vote), and `pages-rtt<ms>.json` (per page: HTML size, changed bytes, every load's timings and
the server's, its statements cut to 160 characters).

## Appendix: full tables (from `scripts/analyze.py`)

### Large view: keypress to next file's main image painted (ms)

| round | link | variant | median | min-max | paired delta vs A (median, range) | faster than A in | interactive median | main image from cache | CSS/JS from cache |
|---|---|---|---|---|---|---|---|---|---|
| binary | RTT 0 (h2, set 1) | A | 182 | 163-199 | - | - | 161 | 0 of 10 | 13 of 13 |
| binary | RTT 0 (h2, set 1) | B | 181 | 168-205 | -0 (-17..+23) | 5 of 10 | 158 | 0 of 10 | 13 of 13 |
| binary | RTT 0 (h2, set 1) | C | 178 | 168-201 | -0 (-18..+18) | 5 of 10 | 155 | 0 of 10 | 13 of 13 |
| binary | RTT 0 (h2, set 1) | D | 178 | 170-193 | -6 (-17..+18) | 7 of 10 | 156 | 0 of 10 | 13 of 13 |
| binary | RTT 0 (h2, set 1) | E | 176 | 163-190 | -7 (-23..+14) | 6 of 10 | 154 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 1) | A | 652 | 644-685 | - | - | 269 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 1) | B | 665 | 633-675 | +5 (-20..+26) | 3 of 10 | 269 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 1) | C | 644 | 622-654 | -14 (-47..+10) | 9 of 10 | 257 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 1) | D | 636 | 619-664 | -17 (-56..+12) | 8 of 10 | 260 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 1) | E | 646 | 614-703 | -3 (-46..+19) | 5 of 10 | 267 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 1) | A | 340 | 313-359 | - | - | 228 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 1) | B | 336 | 324-350 | -1 (-20..+34) | 6 of 10 | 226 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 1) | C | 341 | 328-395 | +1 (-28..+60) | 4 of 10 | 220 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 1) | D | 337 | 321-357 | -1 (-18..+25) | 5 of 10 | 217 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 1) | E | 341 | 326-361 | -0 (-15..+33) | 6 of 10 | 221 | 0 of 10 | 13 of 13 |
| rated | RTT 0 (h2, set 1) | A | 174 | 160-181 | - | - | 151 | 0 of 10 | 16 of 16 |
| rated | RTT 0 (h2, set 1) | B | 171 | 160-194 | -0 (-19..+26) | 5 of 10 | 154 | 0 of 10 | 16 of 16 |
| rated | RTT 0 (h2, set 1) | C | 173 | 158-242 | -2 (-12..+81) | 6 of 10 | 151 | 0 of 10 | 16 of 16 |
| rated | RTT 0 (h2, set 1) | D | 173 | 159-189 | -0 (-15..+13) | 5 of 10 | 152 | 0 of 10 | 16 of 16 |
| rated | RTT 0 (h2, set 1) | E | 167 | 156-206 | -4 (-20..+45) | 6 of 10 | 148 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 1) | A | 645 | 618-714 | - | - | 262 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 1) | B | 654 | 621-669 | +8 (-45..+23) | 3 of 10 | 269 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 1) | C | 646 | 601-664 | -14 (-67..+28) | 6 of 10 | 268 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 1) | D | 640 | 620-654 | -9 (-89..+18) | 7 of 10 | 262 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 1) | E | 642 | 602-674 | -15 (-52..+36) | 6 of 10 | 263 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 1) | A | 344 | 325-354 | - | - | 224 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 1) | B | 334 | 320-351 | -6 (-27..+24) | 8 of 10 | 224 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 1) | C | 340 | 323-352 | +3 (-24..+22) | 4 of 10 | 220 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 1) | D | 339 | 323-347 | -2 (-28..+20) | 8 of 10 | 217 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 1) | E | 343 | 327-359 | -0 (-27..+32) | 5 of 10 | 219 | 0 of 10 | 16 of 16 |
| binary | RTT 80, 8 Mbit/s (h2, set 2) | A | 651 | 619-684 | - | - | 266 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 2) | B | 645 | 609-667 | +7 (-57..+26) | 4 of 10 | 263 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 2) | F | 286 | 275-312 | -364 (-390..-306) | 10 of 10 | 267 | 10 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 2) | C | 642 | 626-654 | -7 (-51..+12) | 6 of 10 | 264 | 0 of 10 | 13 of 13 |
| binary | RTT 80, 8 Mbit/s (h2, set 2) | G | 295 | 269-336 | -350 (-390..-320) | 10 of 10 | 267 | 10 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 2) | A | 330 | 319-354 | - | - | 217 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 2) | B | 335 | 320-362 | -0 (-14..+39) | 5 of 10 | 221 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 2) | F | 238 | 224-258 | -90 (-129..-65) | 10 of 10 | 220 | 10 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 2) | C | 336 | 325-360 | +8 (-4..+25) | 4 of 10 | 217 | 0 of 10 | 13 of 13 |
| binary | RTT 80 (h2, set 2) | G | 246 | 226-253 | -88 (-121..-71) | 10 of 10 | 219 | 10 of 10 | 13 of 13 |
| rated | RTT 80, 8 Mbit/s (h2, set 2) | A | 657 | 599-693 | - | - | 268 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 2) | B | 659 | 634-692 | +2 (-50..+90) | 5 of 10 | 270 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 2) | F | 292 | 271-332 | -375 (-408..-267) | 10 of 10 | 269 | 10 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 2) | C | 634 | 603-659 | -17 (-81..+35) | 7 of 10 | 262 | 0 of 10 | 16 of 16 |
| rated | RTT 80, 8 Mbit/s (h2, set 2) | G | 290 | 262-297 | -378 (-407..-309) | 10 of 10 | 263 | 10 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 2) | A | 333 | 311-386 | - | - | 222 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 2) | B | 332 | 320-356 | -1 (-45..+28) | 6 of 10 | 224 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 2) | F | 246 | 223-257 | -88 (-134..-67) | 10 of 10 | 224 | 10 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 2) | C | 345 | 329-357 | +11 (-38..+37) | 2 of 10 | 222 | 0 of 10 | 16 of 16 |
| rated | RTT 80 (h2, set 2) | G | 244 | 222-251 | -86 (-165..-72) | 10 of 10 | 218 | 10 of 10 | 16 of 16 |

### Large view breakdown, variant A, medians (ms from keypress)

| round | link | keypress to form submit | vote POST + redirect | next page request to first byte | HTML download | HTML end | CSS/JS end | DOMContentLoaded | main image: response end | main image: duration | strip: images | strip: last image end | painted | server POST total | server GET total | server GET render | server GET queries |
|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|---|
| binary | rtt0 | 2 | 68 | 55 | 0 | 128 | 138 | 155 | 143 | 3 | 5 | 183 | 182 | 62 | 51 | 4 | 42 |
| binary | rtt80-8mbit | 2 | 121 | 54 | 63 | 243 | 250 | 265 | 623 | 371 | 5 | 360 | 652 | 59 | 51 | 4 | 46 |
| binary | rtt80 | 4 | 95 | 52 | 45 | 196 | 209 | 224 | 307 | 95 | 5 | 306 | 340 | 62 | 48 | 4 | 44 |
| rated | rtt0 | 1 | 64 | 49 | 0 | 118 | 129 | 146 | 133 | 3 | 5 | 139 | 174 | 59 | 46 | 3 | 42 |
| rated | rtt80-8mbit | 1 | 116 | 51 | 69 | 240 | 244 | 258 | 617 | 364 | 5 | 349 | 645 | 51 | 48 | 4 | 39 |
| rated | rtt80 | 1 | 92 | 50 | 46 | 193 | 205 | 220 | 306 | 98 | 5 | 302 | 344 | 48 | 47 | 3 | 44 |
| binary | rtt80-8mbit | 1 | 120 | 52 | 65 | 245 | 247 | 261 | 620 | 367 | 5 | 358 | 651 | 58 | 49 | 3 | 40 |
| binary | rtt80 | 1 | 86 | 50 | 46 | 186 | 196 | 212 | 297 | 95 | 5 | 296 | 330 | 60 | 47 | 3 | 37 |
| rated | rtt80-8mbit | 2 | 119 | 55 | 61 | 244 | 248 | 264 | 618 | 362 | 5 | 359 | 657 | 52 | 52 | 3 | 46 |
| rated | rtt80 | 2 | 91 | 49 | 46 | 193 | 203 | 218 | 301 | 96 | 5 | 301 | 333 | 50 | 46 | 3 | 42 |

### Gallery and statistics pages


### RTT 0 ms (medians of 8 loads)

| page | HTML KB | changed vs previous page KB | server total | server queries | server render | TTFB | HTML end | DCL | load | LCP | images (n) | DOM nodes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| juror gallery, binary: page turn | 44 | 20 | 68 | 56 | 54 | 71 | 73 | 126 | 179 | 136 | 18 | 462 |
| juror gallery, binary: filter unrated | 18 | 3 | 54 | 44 | 39 | 56 | 58 | 96 | 96 | 124 | 0 | 203 |
| juror gallery, binary: region change | 60 | 40 | 66 | 52 | 46 | 70 | 72 | 121 | 165 | 132 | 18 | 464 |
| juror regions menu fragment, binary | 4 | - | 34 | 24 | 0 | 38 | 40 | 52 | 52 | - | 0 | 7 |
| juror by-rate, rated: page turn | 69 | 27 | 113 | 97 | 71 | 116 | 118 | 188 | 197 | - | 15 | 516 |
| juror by-rate, rated: region change | 69 | 30 | 79 | 68 | 38 | 82 | 88 | 147 | 155 | - | 15 | 518 |
| juror gallery, rated: filter rated | 70 | 5 | 87 | 75 | 32 | 90 | 93 | 168 | 177 | - | 15 | 516 |
| juror regions menu fragment, rated | 4 | - | 32 | 23 | 0 | 35 | 38 | 51 | 51 | - | 0 | 7 |
| organizer round statistics, binary | 83 | 71 | 405 | 386 | 0 | 409 | 412 | 490 | 490 | - | 0 | 935 |
| organizer round statistics, rated | 65 | 53 | 100 | 87 | 0 | 105 | 106 | 144 | 145 | - | 0 | 758 |
| organizer roundstat table fragment | 67 | - | 284 | 278 | 0 | 288 | 290 | 305 | 308 | - | 0 | 769 |
| organizer roundstat counts fragment | 0 | - | 224 | 220 | 0 | 227 | 229 | 243 | 243 | - | 0 | 7 |
| organizer rounds list | 19 | 7 | 14 | 8 | 0 | 17 | 19 | 64 | 67 | - | 0 | 207 |
| organizer contest health | 18 | 9 | 104 | 96 | 0 | 107 | 111 | 130 | 134 | - | 0 | 206 |

### RTT 80 ms (medians of 8 loads)

| page | HTML KB | changed vs previous page KB | server total | server queries | server render | TTFB | HTML end | DCL | load | LCP | images (n) | DOM nodes |
|---|---|---|---|---|---|---|---|---|---|---|---|---|
| juror gallery, binary: page turn | 44 | 20 | 78 | 58 | 50 | 85 | 96 | 134 | 140 | 202 | 18 | 462 |
| juror gallery, binary: filter unrated | 18 | 3 | 48 | 34 | 34 | 51 | 93 | 130 | 131 | - | 0 | 203 |
| juror gallery, binary: region change | 60 | 40 | 64 | 52 | 48 | 68 | 93 | 134 | 153 | 208 | 18 | 464 |
| juror regions menu fragment, binary | 4 | - | 26 | 19 | 0 | 29 | 94 | 105 | 105 | - | 0 | 7 |
| juror by-rate, rated: page turn | 69 | 27 | 95 | 80 | 53 | 98 | 103 | 150 | 155 | - | 15 | 516 |
| juror by-rate, rated: region change | 69 | 30 | 71 | 58 | 29 | 74 | 89 | 138 | 143 | - | 15 | 518 |
| juror gallery, rated: filter rated | 70 | 5 | 70 | 51 | 20 | 76 | 90 | 140 | 144 | - | 15 | 516 |
| juror regions menu fragment, rated | 4 | - | 32 | 18 | 0 | 35 | 92 | 102 | 102 | - | 0 | 7 |
| organizer round statistics, binary | 83 | 71 | 269 | 259 | 0 | 274 | 276 | 319 | 320 | - | 0 | 935 |
| organizer round statistics, rated | 65 | 53 | 103 | 88 | 0 | 109 | 113 | 158 | 158 | - | 0 | 758 |
| organizer roundstat table fragment | 67 | - | 250 | 244 | 0 | 253 | 256 | 269 | 271 | - | 0 | 769 |
| organizer roundstat counts fragment | 0 | - | 163 | 154 | 0 | 166 | 169 | 178 | 178 | - | 0 | 7 |
| organizer rounds list | 19 | 7 | 13 | 8 | 0 | 16 | 88 | 120 | 122 | - | 0 | 207 |
| organizer contest health | 18 | 9 | 111 | 96 | 0 | 117 | 119 | 139 | 142 | - | 0 | 206 |

### Queries per page (RTT 0 run; median ms per load, count per load; statements over 3 ms)


**juror gallery, binary: page turn** (server total 68 ms, queries 56 ms, 9 statements)

- 20 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 9 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`
- 8 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`

**juror gallery, binary: filter unrated** (server total 54 ms, queries 44 ms, 9 statements)

- 18 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 4 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror gallery, binary: region change** (server total 66 ms, queries 52 ms, 10 statements)

- 18 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 8 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 3 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror regions menu fragment, binary** (server total 34 ms, queries 24 ms, 6 statements)

- 15 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`

**juror by-rate, rated: page turn** (server total 113 ms, queries 97 ms, 11 statements)

- 44 ms x1: `select ... from ( select IF(SUM(s.rate <> N) = N, N, sum(GREATEST(s.rate, N)) * N DIV SUM(s.rate <> N)) as k0, sum(GREATEST(s.rate, N)) as k1 from `
- 24 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 14 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 10 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 9 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`

**juror by-rate, rated: region change** (server total 79 ms, queries 68 ms, 10 statements)

- 24 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 15 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 14 ms x1: `select ... from ( select IF(SUM(s.rate <> N) = N, N, sum(GREATEST(s.rate, N)) * N DIV SUM(s.rate <> N)) as k0, sum(GREATEST(s.rate, N)) as k1 from `
- 10 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`
- 4 ms x1: `select ... from comment where contest_id = ? and room in (?...) group by room`
- 4 ms x1: `SELECT id FROM round WHERE contest_id = ? AND distributing = N ORDER BY id`

**juror gallery, rated: filter rated** (server total 87 ms, queries 75 ms, 10 statements)

- 25 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, SUM(N * (cm.room IS NOT NULL)) FROM vote s LEFT JOIN (SELECT DISTIN`
- 19 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`
- 16 ms x1: `select i.page_id as pi_on_i, i.contest as c_on_i, i.title as t_on_i, i.url as u_on_i, i.page_url as pu_on_i, i.last_round as lr_on_i, i.width as w_on_`

**juror regions menu fragment, rated** (server total 32 ms, queries 23 ms, 6 statements)

- 16 ms x1: `SELECT NULLIF(LEFT(SUBSTRING_INDEX(s.monument_id, ?, N), N), ?) AS region, s.rate, COUNT(*) FROM vote s WHERE s.jury_id = ? AND s.round_id = ? AND s.p`

**organizer round statistics, binary** (server total 405 ms, queries 386 ms, 11 statements)

- 250 ms x1: `SELECT COALESCE(SUM(t.a > N), N), COALESCE(SUM(t.u > N), N), COALESCE(SUM(t.r > N), N) FROM ( SELECT SUM(s.rate > N) a, SUM(s.rate = N) u, SUM(s.rate `
- 118 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`
- 8 ms x1: `SELECT r.id FROM round r WHERE r.id IN (?) AND r.distributing = N AND (r.active = N OR (r.freeze_count > N AND r.active_before_freeze = N)) AND NOT EX`
- 8 ms x1: `SELECT COALESCE(SUM(c.round_id = ?), N), COUNT(DISTINCT CASE WHEN c.round_id = ? THEN c.room END), COUNT(*), COUNT(DISTINCT c.room) FROM comment c WHE`
- 4 ms x1: `select round_user.user_id as ui_on_round_user, round_user.round_id as ri_on_round_user, round_user.role as r_on_round_user, round_user.active as a_on_`
- 4 ms x1: `SELECT ru.user_id FROM round_user ru WHERE ru.round_id = ? AND ( EXISTS (SELECT N FROM vote v WHERE v.round_id = ? AND v.jury_id = ru.user_id AND v.ra`

**organizer round statistics, rated** (server total 100 ms, queries 87 ms, 10 statements)

- 72 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`
- 4 ms x1: `SELECT COUNT(*), COALESCE(SUM(EXISTS (SELECT N FROM round_image ri WHERE ri.round_id = ? AND ri.page_id = i.page_id AND ri.status = ?)), N) FROM image`
- 3 ms x1: `SELECT ru.user_id FROM round_user ru WHERE ru.round_id = ? AND ( EXISTS (SELECT N FROM vote v WHERE v.round_id = ? AND v.jury_id = ru.user_id AND v.ra`

**organizer roundstat table fragment** (server total 284 ms, queries 278 ms, 8 statements)

- 182 ms x1: `SELECT COALESCE(SUM(t.a > N), N), COALESCE(SUM(t.u > N), N), COALESCE(SUM(t.r > N), N) FROM ( SELECT SUM(s.rate > N) a, SUM(s.rate = N) u, SUM(s.rate `
- 82 ms x1: `SELECT s.jury_id, s.rate, count(N) FROM vote s WHERE s.round_id = ? GROUP BY s.jury_id, s.rate`
- 4 ms x1: `SELECT ru.user_id FROM round_user ru WHERE ru.round_id = ? AND ( EXISTS (SELECT N FROM vote v WHERE v.round_id = ? AND v.jury_id = ru.user_id AND v.ra`

**organizer roundstat counts fragment** (server total 224 ms, queries 220 ms, 7 statements)

- 214 ms x1: `SELECT ri.status, MIN(i.author), COUNT(*) FROM round_image ri STRAIGHT_JOIN images i ON i.page_id = ri.page_id WHERE ri.round_id = ? GROUP BY ri.statu`

**organizer rounds list** (server total 14 ms, queries 8 ms, 8 statements)


**organizer contest health** (server total 104 ms, queries 96 ms, 12 statements)

- 56 ms x1: `select ... from images i inner join category_members cl on i.page_id = cl.page_id where cl.category_id = ? and i.deleted_at is null`
- 24 ms x1: `SELECT round_id, COUNT(*) FROM round_image WHERE round_id IN (?...) AND status = ? GROUP BY round_id`
- 6 ms x1: `SELECT EXISTS(SELECT N FROM job WHERE contest_id = ? AND created_at >= NOW() - INTERVAL ? DAY) OR EXISTS(SELECT N FROM round WHERE contest_id = ? AND `
- 5 ms x1: `SELECT ru.round_id, COUNT(*) FROM round_user ru JOIN round r ON r.id = ru.round_id WHERE ru.round_id IN (?...) AND ru.active = TRUE AND EXISTS (SELECT`
