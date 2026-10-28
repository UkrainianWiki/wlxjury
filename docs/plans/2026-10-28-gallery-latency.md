# Juror gallery and large view: latency per click

A juror clicks through files: the gallery page, the large view, a vote (POST → redirect → the
next file's large view), back to the gallery. Each click is a page load, so its tail latency is
what jurors feel.

## What we know

The Step 5 pilot of the gallery order work (docs/plans/2026-10-27-gallery-position-and-sorting.md,
`JurorGallerySimulation`, 20 jurors, ~38k files, 1,400 gallery pages a run, SQL logging off):

| order | mean ms | p50 | p95 | p99 | std |
|---|---|---|---|---|---|
| file | 216 / 246 | 98 / 99 | 657 / 826 | 1058 / 1244 | ~335 |
| rate | 248 / 247 | 109 / 89 | 817 / 843 | 1230 / 1203 | ~350 |

- **The median is ~100 ms, the p95 ~8× that.** A tail this wide points to waiting (the
  8-thread `blocking-dispatcher` the galleries run on, the 30-connection pool, GC, the
  MariaDB container's I/O) or to a few slow cases (some pages, rounds or regions), more than to
  one query being slow on every request. That has to be measured, not assumed.
- **The load model has no think time:** each session loads 10 pages back to back; about
  20 requests/s. Real jurors pause seconds between clicks. The p95 under this load may not be
  what a juror sees; both must be measured.
- Only the gallery HTML was measured (no thumbnails, which come from Commons/the image cache).
  **The large view and the vote redirect were not measured at all.**

## What one gallery request does (branch `ukwikidev/gallery-position-and-sorting`)

From `GalleryController.listGeneric` → `gallery` → `galleryOf`, `RoundList`, the templates:

- `Secured`: the session user, their contest memberships.
- `Round.findById` (or `Round.activeRounds`), `Secured.inContest`, `GalleryService.viewerRounds`
  (`Round.activeRounds` again, or the contest's rounds for organisers), `ContestJuryJdbc.findById`.
- **Three passes over the juror's selection rows in the round** (~5.7k rows a juror in the
  fixture: 38k files × 3 jurors per file / 20 jurors):
  - `list.count()` — the pager's total;
  - `regionCounts()` — the region nav's counts (`gallery.regionCounts = all`);
  - `galleryService.rateDistribution` — the tabs' counts (All, Unrated, Rated...).
- The page itself: `RoundList.files` (a deferred join, 15 rows).
- Organisers: `User.findRoundJurorsToView` in the navbar.

The three counts overlap: the list's count *is* one of the tab counts (with all regions) or one
of the region counts (with a region), so one aggregate over the juror's rows,
`GROUP BY region, rate`, could give all three.

The large view per click: the round and permission lookups, `RoundList.position` (a
`ROW_NUMBER()` over all the juror's rows), `around` (5 rows), the file's comments, its
monument, and for organisers the file's votes.

## The pager

With 15 files a page a juror's ~5.7k files are ~380 pages; the pager lists 1–9, 10–90,
100–300. Those numbers tell a juror little: progress is the tabs' counts (Unrated n), and
moving is next/previous. Its total is either free (from the aggregate above) or not needed:
reading `pageSize + 1` rows tells whether there is a next page.

**Decision (2026-10-28):** Previous / Next with the total ("46–60 of 5,712"); no page numbers.
The total comes from the counts the page already reads (candidate 1), not a separate count.

## Investigation (an agent)

Work on a new branch `ukwikidev/gallery-latency` from `ukwikidev/gallery-position-and-sorting`.

### Phase 1 — measure where the time goes

1. **Per request, one juror, no concurrency** (warm JVM, ~50 requests each): the gallery page
   (pages 1, 5, 50, the last; all regions and one; file and rate order), the large view
   (a file early, in the middle, at the end of the list), and a vote → redirect → large view.
   For each: total time, and every query's count and time (a timing log for the run, off by
   default, not per-query DEBUG logging), Twirl render time. A table: query → calls per
   request, median and p95 ms.
2. **Under load:** the same breakdown in `JurorGallerySimulation`, plus the time a request waits
   for the blocking pool before it runs (separate waiting from working), pool and connection
   usage, GC pauses (`-Xlog:gc*`, CLAUDE.md's Gatling JVM settings).
3. **A per-click scenario:** a simulation (or an option of `JurorGallerySimulation`) that does
   gallery → large view → vote → (redirect) → … → Esc → gallery, and a realistic load
   variant with think time (2–5 s between clicks, 20–40 jurors) beside the current stress one.

Report Phase 1 before changing code if the cause is not one of the candidates below.

### Phase 2 — change only what Phase 1 shows, one change a commit, before/after numbers in each

Candidates, in the order they are likely to matter:

1. **One aggregate for the juror's counts** (`GROUP BY region, rate` over the juror's rows in
   the round, from the `(jury_id, round_id, rate, monument_id, page_id)` index): the tab counts,
   the region counts and the list's total from it; `count()`, `rateDistribution` and the
   juror's `regionCounts` go. Organisers' (`asUserId == 0`) counts likewise if they show up.
2. **The pager without page numbers** (see the decision below): Previous / Next, the files
   shown ("46–60 of 5,712") from the aggregate, no separate count. The `pageid` redirect keeps
   working (it needs the position, not the total).
3. **The large view's position:** a count of the rows ordered before the file (an index range
   for file order; with `rate` first for by-rate order) instead of `ROW_NUMBER()` over all rows.
   Only if Phase 1 shows `position` matters. An index `(jury_id, round_id, monument_id,
   page_id)` only with measurements that need it, in its own commit.
4. **Repeated lookups in one request** (`Round.activeRounds` twice, the round/contest/memberships
   read by several layers): pass what a request already read; no cross-request caches.
5. **Waiting rather than working:** if the blocking pool's queue is the tail, show it with
   numbers and propose a size (it is a production setting: propose, don't change it).
6. Deep pages (`OFFSET` over thousands of rows): keyset paging only if Phase 1 shows it
   matters; it changes URLs.
7. **A region menu instead of the region nav.** **Decision (2026-10-28, user):** the gallery no
   longer lists every region with its file count on each page; it shows the current region (or
   all) as a dropdown, and reads the regions and their counts only when the juror opens it (a
   JSON GET with the list's filter, by the gallery's checks, never switching the session's
   contest). Without script, or if the request fails, the menu still offers all regions and
   the current one, with a short message. Made whatever candidate 1 gives (a UI decision);
   its own effect on the numbers is reported separately.

### Rules (CLAUDE.md)

- Paired runs, alternating order (A, B, B, A), SQL debug logging off for both; absolute deltas
  and run variation separately; keep outliers; stop when the decision is supported.
- No benchmarks while tests run, and no tests while a benchmark runs. Estimate a run's time
  before a matrix. The machine is short of memory: check free memory before each run, keep the
  Gatling fork at `-Xmx512m`.
- Every query change has a test that its counts equal the old queries' on the same data
  (juror and organiser, binary and rated, with and without a region and filter).
- Affected specs before each commit; the full suite once at the end.
- Results (tables, logs' paths, `stats.json` numbers) go in this document under "Results".

## Results

Branch `ukwikidev/gallery-latency`, 2026-10-28, on the Gatling fixture (~38k files, 20 jurors,
~5.7k files a juror a round, MariaDB 10.6 container, 8 CPUs, Gatling fork `-Xmx512m`, laptop on
mains). SQL logging off in every run (root logger WARN; ScalikeJDBC logs at INFO). The timing log
(`-Dgatling.timing=true`, `modules.RequestTimingFilter`) was on in every run, A and B alike. All
ms are absolute; "p50 / p95 / mean". Logs and per-request JSON lines are under `target/gatling/`
(`timing-<run>.jsonl`, `timing-<run>-pool.txt`, `gc-<run>-*.log`, `latency-client-*.csv`), the
Gatling runs' directories in `target/gatling/runs.txt`; `scripts/timing-summary.py` and
`scripts/gatling-stats.py` make the tables below from them.

Tools: `GalleryLatencyBenchmark` (one juror, one request at a time over HTTP, 10 warm-up + 50
a case), `JurorGallerySimulation` (the pilot's stress load), `JurorClickSimulation` (gallery →
large view → 5 votes with their redirect → Esc → gallery; realistic: 30 jurors, 2–5 s think
time, 180 s; `gatling.clicks.stress=true`: sessions as `JurorGallerySimulation`'s, no pauses),
`PoolWatcher` (DB pool every 100 ms, GC pauses).

One sample of the first single-juror run (13.7 s, the laptop asleep while being moved) is left
out of its tables; the next ~10 requests of that case (`atfile-rated-rate`, p95 610 ms) are
kept but are suspect.

### Phase 1 — where the time goes (before any change)

**One juror, no concurrency** (`timing-20261028-043616.jsonl`): the request waits ~1 ms before
its action; rendering (net of its queries) is 1–2 ms; the queries are ~85% of the time.

| request | total | queries | queries/req |
|---|---|---|---|
| gallery page 1/5/50 (1,200) | 52–70 p50, 59–120 p95 | 44–57 p50 | 9 |
| gallery last page (400) | 54–95 p50 | 47–89 p50 | 9 |
| large view (600) | 91–141 p50, 110–238 p95 | 87–136 p50 | 7–8 |
| vote POST (200) | 113–216 p50 | 106–209 | 7–8 |
| its next large view (200) | 113–146 p50 | 106–140 | 8 |
| `pageid` redirect (Esc) (199) | 78–79 p50 | 72–76 | 6 |
| organiser all-jurors page 1 (100) | 1072–1130 p50, 1394–1468 p95 | 1062–1126 | 11–12 |
| organiser large view (100) | 1632–1706 p50 | 1630–1699 | 9–10 |

Per query, ms a request (p50 / p95 / mean):

| query | gallery p1/p5/p50 | gallery last | large view | vote | Esc |
|---|---|---|---|---|---|
| `regionCounts` | 16 / 26 / 16 | 16 / 24 / 16 | | | |
| files page (deferred join) | 15 / 26 / 15 | 28 / 67 / 37 | 30 / 63 / 33 (window) | 39 / 71 / 42 | |
| `list.count` | 7 / 17 / 7 | 7 / 16 / 7 | | | |
| `rateDistribution` | 6 / 16 / 7 | 6 / 15 / 6 | | | |
| `position` (ROW_NUMBER over all the juror's rows ⋈ images) | | | 66 / 91 / 70 | 117 / 173 / 112 (1.5 calls) | 71 / 458 / 117 |
| `rounds` ×2, users, memberships, contest | ~0–1 each p50, 2–4 mean in all | | | | |
| render, net of queries | 1 / 2 / 1 | 1 / 1 / 1 | 0 / 1 / 1 | 0 | 0 |
| action outside queries and render | 8 / 19 / 8 | 6 / 19 / 6 | 8 / 17 / 7 | 6 / 16 / 5 | 9 / 20 / 8 |

Organisers: the grouped page query 825 ms, its count 129, its `regionCounts` 134; the large
view's grouped `position` 812 plus the window 818.

**Stress load** (`JurorGallerySimulation`, `timing-base-gallery.jsonl`): gallery p50 126, p95 797
(the pilot's 98–109 / 657–843). Waiting vs working: queue for the blocking pool 1 / 395 / 56,
action 124 / 472 / 188. The tail is in the first 20 s: 20 sessions arrive in 10 s, each loading
10 pages back to back, 12–20 requests in flight for 8 threads, on a cold JVM (first requests
3.7 s of work); after 20 s, in flight ≤ 4–7, queue ≤ 1 ms, p50 83, p95 335. DB pool: active ≤ 9
of 30, no thread ever waited for a connection. GC: 46 pauses, 379 ms in all, max 29 ms, none
major — not the tail.

**Realistic load** (`JurorClickSimulation`, 30 jurors, think time; `timing-base-clicks30.jsonl`;
after the first 30 s, the cold start): nothing waits (queue 0–1 ms); gallery 75 / 192, large view
124 / 366, vote 138 / 297, large view after vote 143 / 305, Esc 108 / 236. A vote click (POST +
redirect) is ~280 ms at p50, `position` ~90–105 ms of each half.

**Stress clicks** (`timing-base-clicks-stress.jsonl`): overloaded. The large view and the vote run
on Play's default dispatcher (`withAuth`, 8 threads) at ~370 ms each; galleries and logins wait
for it *before* their action (gallery outside its action 699 / 5325 ms while its blocking-pool
queue is 1 / 13): gallery 1803 / 6340, large view 902 / 4153, vote 788 / 1656 (Gatling). Pool
active up to 16; GC 444 ms in all.

So the time is working, not waiting, at realistic load: in the gallery the three counts
(~30 ms of ~60), in the large view and the vote `position` (~70 ms, ~100 under load). The stress
tail is waiting (the ramp's burst, and the default dispatcher under the large view's work),
which shrinks with the work. Candidates 1, 3 and (as a proposal) 5 match; GC and the DB pool
do not.

### Phase 2 — changes

**1. One count query** (fb42377c). One juror, 1,600 gallery pages: p50 54.8 → 40.9, p95 90.9
→ 80.0, mean 60.2 → 47.1; 9 → 7 queries; the count query 15 ms p50 against 7 + 16 + 6.
`JurorGallerySimulation`, run A, B, B, A (`timing-base-gallery`, `c1-gallery-B1`, `-B2`, `-A2`):

| run | p50 | p95 | mean | after 20 s: p50 / p95 |
|---|---|---|---|---|
| A (before) | 126 | 797 | 248 | 83 / 335 |
| B | 44 | 583 | 132 | 40 / 52 |
| B | 44 | 529 | 125 | 40 / 54 |
| A (before) | 80 | 852 | 237 | 67 / 185 |

The whole-run p95 that remains is the ramp and the cold start.

**2. Pager: previous / next and "46–60 of 5,712"** (03ffa40f). No query change: one juror,
1,600 pages, p50 40.9 → 40.8, p95 80.0 → 84.5, mean 47.1 → 47.7 (run spread); render p50 0.75
ms in both. The contest files page has the same pager.

**3. Position by the file's row** (9e92302f): the file's order key, a count of the rows before
it, the neighbours by key (no OFFSET). One juror, 50 a case (`c3-harness-A` / `-B`), p50:
large view 97–139 → 32–58; vote 103–195 → 43–47; the large view after a vote 103–129 → 39–45;
Esc 76–85 → 17–25. `JurorClickSimulation` realistic, run B, A, A, B (p50 / p95):

| request | B | A | A | B |
|---|---|---|---|---|
| large view | 42 / 87 | 123 / 241 | 120 / 214 | 41 / 82 |
| vote | 41 / 82 | 133 / 275 | 137 / 264 | 40 / 71 |
| large view after vote | 38 / 59 | 136 / 280 | 141 / 271 | 37 / 51 |
| Esc (`pageid`) | 18 / 48 | 105 / 168 | 100 / 175 | 18 / 40 |
| gallery page at file | 41 / 63 | 50 / 87 | 47 / 93 | 40 / 53 |

A vote click is now ~80 ms at p50 (was ~270).

**7. Region menu** (2aeec40f; decision above). One juror / organiser, 50 a case
(`rm-harness-A` / `-B`): juror gallery pages p50 47.3 → 46.7 (all regions), 37.6 → 38.0 (a region)
— their region counts already came from change 1's query, so no query goes; the page 39.7 → 32.0
KB (region nav 10.1 KB → 0.8 KB). Organiser all-jurors page 1: p50 1027 → 887, p95 1219 → 1053
(`regionCounts`, 124 ms, gone), 53.4 → 46.1 KB. Opening the menu: juror 25–27 ms, organiser 140
ms, ~4.5 KB of JSON. Separately: change 1 took the juror's region counts from 16 ms (31 under
load) to nothing extra; change 7 takes the organiser's 124 ms and 7–8 KB a page from every page.

**After all changes** (`final-gallery`, `final-clicks-stress`): `JurorGallerySimulation` p50 45,
p95 236, mean 69 (was 126 / 797 / 248); after 20 s 42 / 57. Stress clicks (Gatling, p50 / p95):
gallery 51 / 253 (was 1803 / 6340), large view 47 / 300 (902 / 4153), vote 42 / 212 (788 /
1656), large view after vote 44 / 215 (776 / 1631), Esc 21 / 79 (1566 / 8244); nothing waits
outside its action (≤ 3 ms p95); pool active ≤ 9.

### Not changed (proposals, with numbers)

- **4. Repeated lookups** (`rounds` twice, user, memberships, contest): 2–4 ms a request in
  all (mean), ~0–1 ms each at p50. Too small to change now.
- **5. Pool sizes**: no change proposed. At realistic load nothing queues; the stress queueing
  came from the ramp burst and the default dispatcher's large-view work, and went with the work
  (stress clicks: waiting outside the action 699 / 5325 → 0–1 / 2–9 ms). More blocking threads
  would add DB contention (application.conf's comment). If the large view and vote should
  ever be off Play's default dispatcher, `withAuthOn(blocking)` is the way (not done: votes
  are fast now).
- **ScalikeJDBC's default pool is chosen by a race** (found while measuring; production config,
  not changed): `scalikejdbc.PlayFixtureModule` (in `conf/application.conf`) eagerly starts
  `PlayInitializer`, which registers a commons-dbcp2 pool of ScalikeJDBC's defaults (8
  connections, `db.default.poolMaxSize` unset) as "default", and `PlayDBApiAdapterModule`
  registers Play's HikariCP pool (30); the last one wins. Of 21 fixture starts in this
  work 12 began on dbcp2 and 9 on HikariCP (`[pool] at start` in `target/checks/*.log`; the
  Phase 1 stress runs were on HikariCP). `ConnectionPoolConfigSpec` checks only Play's pool. Measured now
  (stress clicks, `final-clicks-stress` vs `-dbcp`): no difference (gallery 51 / 253 vs 49 /
  273, vote 42 / 212 vs 42 / 203), the 8-connection pool at its maximum in 14 of 708 samples;
  before the changes stress clicks used up to 16 connections. Proposal: drop
  `PlayFixtureModule` from production's `play.modules.enabled` (fixtures load only in Dev/Test
  anyway) or set `db.default.poolMaxSize`, and make the spec check `ConnectionPool.get()`. The
  Gatling fixture now registers Play's pool after the start (`-Dgatling.pool`), so runs compare.
- **Index `(jury_id, round_id, monument_id, page_id)`**: not added. After change 3 a large view
  in file order reads its neighbours with two small filesorts of the juror's index entries,
  ~21 ms of 43–58 ms (one juror); by rate (from V48's index) the views are 32–47 ms. Under
  realistic load the large view is 41–42 / 82–87 ms. An index could save ~10–20 ms a large view
  in file order; not needed for the latency now.
- **6. Keyset paging of gallery pages**: not done (it changes URLs); the last page costs
  +13–35 ms (files page 28 vs 15 ms p50 at one juror).
- **Organisers' all-jurors views** stay slow: the grouped page query ~760–850 ms and the large
  view's grouped `position` ~810 ms (each over the round's ~114k selection rows). Not among the
  candidates; it needs its own design (e.g. per-file sums kept with the round).
