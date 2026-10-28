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

(filled in by the investigation)
