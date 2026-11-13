# Query plan checks: statements captured from the code, plans checked in CI and at release (v2)

**Status:** proposal, 2026-11-13. Nothing is built. Two pilots ran (§3): one on the Gatling
fixture (v1), one of a seeded spec database (option D, v2). Decisions for the maintainer: §8, one
per item.

**Revisions:**
- **v1** (af4d4c64): capture from the browser flows on the fixture, a history corner with an
  injected `n_rows`, a CI check in `browser.yml`, a release check.
- **v2**, after an independent review: option D (a seeded spec in `sbt test`) piloted and
  recommended as P1, the browser capture moved after it; the injection is a complete statistics
  snapshot, with auto-recalc off and read back, redone after the canary's ALTER; the corner on its
  own juror and contest; caller identities without line numbers; R2 tied to a request or scenario
  context, R4 defined; rebinding of inlined literals; capture blind spots; `plans.py time`
  SELECT-only; the effort re-estimated with the review cycles.

## 1. Problem

V99 (voted_at in the juror gallery index) and V100 (vote's four single-column indexes dropped)
were validated by EXPLAINing ~636 vote statements on production's copy (MariaDB 10.6.22,
3.35M votes): 44 statements used an `index_merge` **intersect** (round ∩ juror) before V100, 0
after. That check was a one-off (scratchpad `bench2.py` → `bench3.py` → `relcheck.py`), and
its weak spot is the catalog: every statement was **rebuilt by hand** from ImageDbNew, VoteJdbc,
RoundCounts, the distribution, … So:

- it drifts from the code as soon as a query changes (and nobody notices: the hand copy still
  passes);
- it only covers what someone remembered to copy (the follow-up had to add 40 page_id write paths
  the first catalog lacked);
- each new question (an index, an optimizer switch) starts a new ad-hoc script.

Goal: statements taken **from the code that runs**, their plans checked **automatically** so a
regression (a new intersect, a full scan on a juror page, a lost key) is caught, and one harness
the next index or optimizer question reuses.

## 2. What exists

| Piece | What it gives | Limit |
|---|---|---|
| scratchpad `bench2.py` / `bench3.py` / `relcheck.py` | 636 statements × variants (`IGNORE INDEX`, `optimizer_switch`), EXPLAIN tables, paired timings, summaries | hand-built SQL, prod copy only, outside the repo |
| `test/gatling/setup/ExplainQueries.scala` | EXPLAIN + ANALYZE of ~17 literal statements on the fixture | literal SQL again; prints, asserts nothing |
| `test/db/VoteGalleryIndexSpec` | one EXPLAIN assertion (filesort or not, with `FORCE INDEX`) | tiny data: the optimizer's choice isn't tested, only what the index can give |
| Gatling fixture (`GatlingDbSetup`/`GatlingDbCache`) | ~38k images, 20 jurors, a binary and a rated round, cached dump; built in CI by `browser.yml` (~1.5 min on a miss) | uniform (§3.2); shared with the Gatling baselines, the browser tests and the UI shots |
| `ci.yml` | `sbt test` on 10.6.22 for every PR, plus 10.11.14 weekly and on master | — |
| `scripts/schema_parity.py` + release checklist row | precedent: a release-time check on the rehearsal's production copy, with its own CI unit tests | — |
| ScalikeJDBC `GlobalSettings.queryCompletionListener` | already used by `RequestTimingFilter` and `RegionMenuSpec`; `RequestTiming` keeps the current request (method, path) in a thread-local | — |
| MariaDB Connector/J 3.5 (text protocol by default) | the server sees each statement with its literals (general log, performance_schema) | — |

## 3. Pilots (2026-11-13, on `stack/123-drop-vote-single-indexes`)

No pilot code is committed. It stays in the session's scratchpad: `PlanCapturePilot.scala`, a
test-only `Test/runMain`, and the scripts `explain_captured.py`, `pilot_d.py` and
`pilot_d_small.py`.

### 3.1 Capture from the code works (fixture)

A `queryCompletionListener` (chained after the existing one) recorded, for 16 requests (juror
login; gallery page 1, page 5 and a region page, large view, a vote and the large view again, in
both rounds; organizer login, `/roundstat`, the all-jurors gallery): **39 statement shapes, 19
on `vote`**, each with a count, its first raw statement with typed bound values, and its caller
(`ImageDbNew$SelectionQuery.countBefore:303`, `VoteJdbc$.rateIn:243`,
`User$.findRoundJurorsToView:570`, …; background ones too: `JobJdbc.claimable`,
`RoundCounts.roundsToRecount`). All 39 rendered and EXPLAINed on the fixture with no error (the
two vote `UPDATE`s included: `EXPLAIN UPDATE` doesn't write). Lessons:
- **5 shapes carry inlined numeric literals** (`ImageDbNew.regionRateCounts` builds
  `s.jury_id = 1 AND s.round_id = 2` with `createUnsafely`; `RoundCounts` has `active = 1`).
  Unless the shape key folds the literals compared with id columns, each juror × round is a new
  shape; flags such as `active = 1` stay.
- `ImageDbNew$SelectionQuery.list` alone gave 7 shapes (tab, order, grouping). The caller is a
  group, not one statement; and its raw form (line numbers, `$anonfun$…$1`) changes with
  unrelated edits (§4.3).
- **Rebinding to another DB is feasible by column:** of 35 parameterised shapes, 24 have every
  `?` on an id column (`round_id` 23×, `jury_id` 11×, `page_id`, `contest_id`, `user_id`, `id`,
  `monument_id`). The other 11 also bind status, rate or email values, which keep the sample's.

### 3.2 The fixture as it is does not reproduce V100's problem

767,340 votes, 2 rounds × 20 jurors × ~19.2k each: perfectly uniform. With V100's four indexes
re-added (+ `ANALYZE`), the 39 captured shapes' plans are **identical** to V100's: 0 index
merges. The independence estimate (juror's share × round's share) is right when every juror
votes in every round. And each round is half the table, so scanning its single-column index is
never cheap.

### 3.3 A production-shaped corner reproduces it (fixture)

Production's case is r759: one juror, 20,501 votes, the juror with votes in other rounds, 3.35M
rows; the optimizer estimates the intersection at 487 rows. On the fixture, with the four
indexes re-added:

| Added to the fixture | `vote` rows (`n_rows` the optimizer sees) | gallery inner query (the corner's juror and round) | juror count query |
|---|---|---|---|
| nothing (rounds 1 and 2 only) | 0.77M | ref, gallery index | ref |
| nothing, `n_rows` set to 3.35M | 0.77M (3.35M) | ref | ref |
| nothing, `n_rows` set to 30M | 0.77M (30M) | **intersect** (est. 2,347) | ref |
| "history": juror 1 with 4 past rounds × 15k votes and the only juror of a 20k-file round (80k rows) | 0.85M | ref | **intersect** (est. 14,526) |
| history + 0.77M filler votes (20 other rounds) | 1.6M | **intersect** (est. 6,695) | **intersect** |
| history + 2.66M filler votes (70 other rounds) | 3.53M | **intersect** (est. 3,207; 20,000 real) | **intersect** |
| history, `n_rows` set to 3.35M | 0.85M (3.35M) | **intersect** (3,248) | **intersect** |
| history + 2.66M filler, **V100 applied** | 3.53M | ref, gallery index | ref (`vote_round_jury_rate_idx`) |

- The trigger is the **shape**: a small round wholly inside one juror who has votes elsewhere.
  The table's size comes second; size alone needs an absurd 30M.
- Timing on the 3.53M table (warm, `ANALYZE FORMAT=JSON`, 3 reps): intersect 69–143 ms, gallery
  index 14–18 ms, the same 4–5× as production's 107 → 28 ms. One cold run (438 ms) shows why
  timings stay out of CI.
- Only `n_rows` was faked here (`mysql.innodb_table_stats`, `STATS_AUTO_RECALC=0`,
  `FLUSH TABLE vote` after the `ANALYZE`); §3.4 shows why that alone isn't enough.

### 3.4 Option D: a seeded database, a statistics snapshot, three fresh runs

`pilot_d.py` starts a fresh `mariadb:10.6.22` container with `--innodb-stats-auto-recalc=0`. It
creates `images` and `vote` with the pre-V100 indexes, and seeds only **the corner** and
**filler** with `seq_*` inserts: 40k images; juror 9001 with 4 rounds × 15k votes and the only
juror of round 9105 (20k files); then 25 other rounds × 40k with 7 other jurors. Then:
1. `ANALYZE`: EXPLAIN with the real statistics.
2. `n_rows` set to 3.35M: EXPLAIN.
3. A snapshot injected: EXPLAIN. The snapshot is taken once in run 1:
   `STATS_SAMPLE_PAGES=2000`, `ANALYZE`, all of `vote`'s `innodb_index_stats` rows as sampled,
   `n_rows` and the page sizes scaled to 3.35M. In each run it is injected after a fresh
   `ANALYZE` and read back.
4. V100's four indexes dropped, `ANALYZE` and the snapshot injected again: EXPLAIN.

| Seed (`vote` rows; seed time) | Statistics | gallery inner query | juror count query |
|---|---|---|---|
| 1.08M (45–62 s) | real (`ANALYZE`) | **intersect** (est. 5,075) | ref |
| 1.08M | `n_rows` 3.35M only | **intersect** (1,630) | **intersect** (1,630) |
| 1.08M | full snapshot | **intersect** (1,630) | **intersect** (1,630) |
| 1.08M | full snapshot, **V100 applied** | ref, gallery index (40,748) | ref, `vote_round_jury_rate_idx` |
| 0.2M (21–24 s) | real | ref, `vote_round_page_rate_idx` | **intersect** (20,101) |
| 0.2M | `n_rows` 3.35M only | **intersect** (1,630) | **intersect** (1,630) |
| 0.2M | full snapshot (high sample) | **ref, gallery index** | **intersect** (1,630) |
| 0.2M | full snapshot, V100 applied | ref, gallery index | ref |

- **Stable:** 4 fresh runs of the 1.08M seed (one took the snapshot) and 3 of the 0.2M seed gave
  the same plans and the same estimates in every row. Each run's default-sample `n_diff` values
  were identical too, since the data is built the same way each time. The review's flakiness
  concern holds where the data is not rebuilt identically: on the fixture after
  UiShotsServer's URL update, and with a different sample. Hence a committed snapshot, injected
  and read back.
- **The index statistics matter**, not only `n_rows`. On the 0.2M seed, the high-sample
  snapshot moves the gallery query back to the gallery index, while `n_rows` alone gives the
  intersect. A check that injects only `n_rows` could pass or fail depending on what the sample
  happened to see. On the 1.08M seed the intersect holds whatever the statistics: that is the
  robust seed.
- The canary's order is written into the procedure: ALTER, then `ANALYZE`, then inject the
  snapshot, then read it back, then EXPLAIN. Both the ALTER and the `ANALYZE` rewrite the
  persistent statistics.
- Not piloted: injection for `images` and `round_image` (only `vote` was injected, in both
  pilots), MariaDB 10.11, the services' own statements on this seed (the pilot EXPLAINed the two
  statements by hand), capture over the full `sbt test`, the production copy
  (wlxjury-rehearsal2 was left alone).

## 4. Options

### 4.1 Statement capture

| Option | How | For | Against |
|---|---|---|---|
| **A. ScalikeJDBC listener in the tests** | a test-only `StatementCapture`: every completed statement's template (with `?`), typed bound values, the caller (§4.3) and a **context**: the request's route from `RequestTiming`'s thread-local in a Play app, or a scenario name the spec sets ("juror gallery", "distribution") | caller and context give stable names | only the JVM it's installed in; the blind spots below |
| B. Server-side log | `general_log` (or `performance_schema.events_statements_history_long`) while flows run, normalized in Python | any client: the packaged app on the rehearsal, Gatling's external target | no caller, no context; never on production |
| C. Hand catalog (today) | — | full control of cases | drifts; incomplete |

**Blind spots of A** (each listed in the report, none silent):
- batches: the listener gets the template, but no single parameter list (`singleParams` is
  empty), so a batch shape is recorded without a sample;
- failed statements go to `queryFailureListener`, not the completion listener: capture both;
- raw JDBC outside ScalikeJDBC is not seen (Flyway, a few maintenance paths);
- what no flow runs: the distribution, round creation and admin writes are not reached by the
  browser flows. D's spec calls them directly (`RoundDistributionHandler` on a seeded round);
- in a Play app, the capture file is written and flushed by a stop hook before the container
  stops (UiShotsServer stops the server, then the container).

**Rebinding to another DB** (the production copy): a **case profile** per target picks ids
(the biggest round and its heaviest juror, r759-like single-juror rounds, a 2026 rated round, a
hot page_id). Binding is by column: a `?` **and an inlined numeric literal** compared with
`jury_id`, `round_id`, `page_id`, `contest_id`, `monument_id` (`col = x`, `col IN (…)`) take the
case's value; other values keep the sample's. A plan whose Extra says `Impossible WHERE …`,
`no matching row in const table` or `Impossible WHERE noticed after reading const tables`
counts as **unbound** (the case doesn't fit), not as a pass.

### 4.2 Where plans are checked

| Option | For | Against |
|---|---|---|
| (a) CI on the Gatling fixture (capture from the browser flows) | what pages really run, every page the browser tests open | uniform data (§3.2); a corner there changes Gatling baselines and UI shots unless isolated; `browser.yml` runs 3 engines and no 10.11 leg |
| **(D) a spec in `sbt test`** (ci.yml) that seeds only the corner and filler, injects the snapshot, calls the real services and DAOs for the corner's juror, captures in-JVM, EXPLAINs and asserts, canary included | deterministic (§3.4), no shared fixture to disturb, 10.6 on every PR and 10.11 weekly; reaches the distribution and admin writes | covers the scenarios the spec calls (the SQL is the code's, the call list is hand-kept); ~1–1.5 min of seeding per run |
| (b) release-time on the rehearsal's production copy | the real data and statistics: the only place V100's problem was seen | once per release; late (but before deploy) |

### 4.3 What to assert, and identities

**Shape identity:** `group` = the caller's class and method, without line numbers and with
`$anonfun$…$N` and `$1` suffixes stripped (`ImageDbNew.SelectionQuery.list`), plus a hash of the
normalized text (whitespace, `?` lists, id literals folded). A changed hash in the same group
is reported as **"shape changed: old plan → new plan"**, not as one removed and one new. A
missing shape fails only for the **named groups** (R3's list); others are listed.

1. **Rules:**
   - R1: no `index_merge` with `intersect` on `vote` (or `round_image`). It only means something
     on statements bound to the corner. So the check **rebinds every vote shape to the corner's
     juror and round** (by column, §4.1), whatever ids the flow used, and the canary names the
     shapes that must show the intersect without V100: the gallery inner query
     (`ImageDbNew.SelectionQuery.list`, the deferred join's inner select) and the juror's count
     queries (`SelectionQuery`'s gallery count and `countBefore`; their exact group names are
     fixed in P1).
   - R2: no `type=ALL` on `vote`, `images`, `round_image`, `criteria_rate` **in a juror
     context**: the request's route (gallery, large view, vote) in a Play app, or the spec's
     scenario name in D. The first app frame can't decide it: DAO methods are shared by juror,
     organizer and job paths, and a Future loses the controller frame. An allow-list names the
     deliberate scans by group, with a reason (a round's full recount, the distribution's
     chunked scans, which run in a job context and so aren't juror ones).
   - R3: for named groups, the expected key. The juror gallery's inner query uses the gallery
     index; `VoteJdbc.byUser` the gallery index; a round's counts `vote_round_*`. Keys can differ
     per server version (10.6, 10.11).
   - R4 (production copy only): rows examined, from `ANALYZE FORMAT=JSON` of **SELECTs only**.
     For a statement bound to (juror, round), `vote`'s `r_rows` must be ≤ 2 × that juror's votes
     in the round (counted by the harness) + 100. That catches the intersect's read-everything
     pattern, which the estimate (487 for 20,501) hides.
2. **Baseline diff** (the browser capture, later): per shape × case, the table, access type,
   key and Extra flags (filesort, temporary, intersect/union), as a committed TSV, with no row
   estimates. Changes print as regression (to ALL, to index_merge, a key lost, filesort or
   temporary added) or improvement. A regression fails unless the same PR updates the baseline
   (`plans.py check --update-baseline`; the TSV diff is reviewed like code).
3. **Stability:** the MariaDB image pinned to the patch version (as CI does); a **complete
   committed statistics snapshot** (`innodb_table_stats` + `innodb_index_stats` of the checked
   tables, high sample, `n_rows` scaled), injected after the last `ANALYZE` and read back before
   EXPLAIN. Auto-recalc is off: `--innodb-stats-auto-recalc=0` on a container of its own, or
   `STATS_AUTO_RECALC=0` on the tables in the shared test DB, restored afterwards. A writer that
   touches >10% of a table, such as UiShotsServer's image URL update, otherwise queues a recalc
   that overwrites the injection. `optimizer_switch` at defaults and printed in the report.

### 4.4 Timings and the harness

Timings stay **out of CI** (shared runners: ±30% noise; a plan regression is caught by the plan).
A **harness in the repository** replaces the scratchpad scripts, so the next question reuses it:

```
scripts/perf/
  plans.py        # explain | check (rules, baseline diff) | variants | time | summarize
  profiles/       # prodcopy.sql: the cases (ids) per target; stats snapshot(s)
  baseline/       # fixture-10.6.tsv (the browser capture's, later)
  rules.yaml      # named groups: expected key per server, the R2 allow-list with reasons
  test_plans.py   # unit tests of the normalizer, binder, identities and diff (as schema_parity's)
```

Captured catalogs (with sample values) are CI artifacts, not committed (decision 7).

`plans.py` keeps bench2's tested pieces: variants per statement (`IGNORE INDEX` on every `vote`
reference) or per session (`optimizer_switch`), **paired interleaved timing** (each rep runs every
variant in a rotated order, medians and min/max per variant, the sbt lock checked so a run never
overlaps a test JVM), EXPLAIN tables to CSV, summaries (intersect count, keys used per table,
changed statements). `time` and R4's `ANALYZE` run **SELECTs only**; a write is only EXPLAINed (or
its SELECT equivalent timed, as bench3 did). It refuses a target not marked disposable or
read-only: the rehearsal copy runs read-only, never production. A DB is reached by
`docker exec` (container name) or a DSN; the rehearsal's secrets file stays outside the repo.

Where the output goes: in CI the check writes a Markdown table to the job summary
(`$GITHUB_STEP_SUMMARY`: failures, warnings, improvements) and the full CSV as an artifact. The
PR's author reads it, and so does the orchestrating agent that merges the stack. At release,
the release agent attaches the report to the release notes.

## 5. Recommendation

**D first, then the release check, then the browser capture.**

1. **P1, the D spec** (`test/db/QueryPlanSpec`, in `sbt test`, so 10.6 on every PR and 10.11
   weekly and on master):
   - seeds a dedicated contest: the corner's juror (an account of its own, not a fixture
     juror), 4 past rounds and the single-juror round, plus ~1M rows of filler;
   - injects the committed snapshot, with `STATS_AUTO_RECALC=0` on the tables;
   - calls the real services and DAOs as that juror under named scenarios: gallery (every
     order, first and last page, region), large view, vote, counts, `RoundDistributionHandler`
     on a seeded round;
   - captures in-JVM with A, EXPLAINs every vote statement captured, asserts R1 and R3;
   - runs the canary in the same spec: V100's four indexes re-added, then `ANALYZE`, injection
     and read-back; the named shapes must show the intersect; then the indexes are dropped again;
   - restores everything in `finally`: the indexes, `STATS_AUTO_RECALC=DEFAULT`, `ANALYZE`,
     `truncateAll`. The other specs share the DB, which works because sbt runs a forked JVM's
     specs one after another.
2. **P2, the harness and the release check:**
   - `scripts/perf/plans.py`: render, bind, EXPLAIN, rules, report, then `variants` and `time`;
   - `profiles/prodcopy.sql`, R1–R4 on the rehearsal copy after the release's migrations, run on
     the shapes P1 captured;
   - a row in `docs/deployment.md`'s release checklist, next to schema parity.
3. **P3, the browser capture** (optional, coverage of what pages really run):
   - capture on in `browser.yml`'s UiShotsServer, chromium leg only;
   - the baseline diff, warn-only at first;
   - the injection, if any, after the browser tests in the same container;
   - no corner in the shared fixture: the corner stays D's, on its own juror and contest, so the
     Gatling baselines, the browser expectations and the UI shots don't change.
4. **Timings:** never in CI. `plans.py time` is the local and release-time tool.

Why D first: it is the only option that reproduced the failure deterministically from a fresh
database in under a minute (§3.4). It runs where 10.11 is already tested, and the 10.11 move is
next. It disturbs no shared fixture, and it reaches the job and admin paths the browser flows
don't. What it gives up is coverage of page composition; P3 adds that back.

## 6. Phases and effort

| Phase | Content | Effort (incl. one review and fix cycle) |
|---|---|---|
| P1 | `StatementCapture` (context, caller identity, literal folding, failure listener) + `QueryPlanSpec` (seed, snapshot, scenarios, R1/R3, canary, cleanup) + the snapshot command; on 10.6, then once on 10.11 | 2.5–3 days |
| P2 | `scripts/perf/plans.py` (render, bind, explain, rules R1–R4, report) + `test_plans.py`; prodcopy profile; runbook row; first run at the next release's rehearsal; `ExplainQueries.scala` retired | 2–2.5 days |
| P2b | `variants` and paired `time` ported from bench2 (deferred out of P1/P2) | 0.5–1 day |
| P3 (optional) | Browser capture (chromium), baseline TSV + diff, job summary | 1.5–2 days |

About **6–8 days** with P3, **4.5–6.5** without, each phase reviewed as the repository's rules
require. P1 alone catches V100's class of regression on every PR.

## 7. Cost

- **CI minutes:** P1 adds ~1–1.5 min to each `ci.yml` leg. Seeding 1.08M rows took 45–62 s in a
  fresh container with 10 indexes (V100's 6 should be quicker), plus the canary's ALTER and two
  `ANALYZE` runs. P3 adds ~1–2 min to the chromium browser leg.
- **Maintenance:**
  - a deliberate query change that changes an expected key edits `rules.yaml` in the same PR;
  - a new scenario when a page or job is added;
  - the snapshot is regenerated (one command) when the seed changes;
  - production's `n_rows` in the snapshot is refreshed at a release when the vote count has
    moved by >25%.
- **False positives:**
  - the scaled `n_rows` can lead the optimizer to plans production wouldn't choose. The rules
    are narrow (an intersect on vote, full scans in juror contexts) and expected keys are per
    server;
  - a CI hit the release check doesn't reproduce becomes an allow-list entry with a reason, not
    a code change;
  - a MariaDB patch upgrade re-baselines deliberately.
- **False negatives:** plans that depend on production's distribution beyond the corner (many
  small rated rounds, round_image sizes; images and round_image statistics not yet injected) are
  caught only at release (P2).

## 8. Open decisions

1. **P1: the D spec or the browser capture.** Recommendation: the D spec (§5). The browser
   capture comes later and is optional.
2. **Seed size.** Recommendation: ~1M rows (45–62 s, intersect even with real statistics), not
   0.2M (21–24 s, but the gallery plan then depends on the statistics, §3.4).
3. **Statistics source.** Recommendation: a high-sample snapshot of the seeded data with
   production's `n_rows`, committed and regenerated by one command. Production's own `vote`
   index statistics (read-only SELECTs on the rehearsal copy) are the alternative, worth
   comparing once P2 exists.
4. **D's database.** Recommendation: the shared test DB, with `STATS_AUTO_RECALC=0` on the
   tables and a full restore in `finally`, checked by a cleanup assertion. A schema of its own
   would need a second pool, and the services use the default one.
5. **Capture mechanism.** Recommendation: the listener in the tests (A), with context and caller
   identity; the server's general log (B) only if the release check later needs statements no
   scenario runs.
6. **Fail or warn.** Recommendation: in D, R1, R3 and the canary fail from day one; the browser
   baseline (P3) warns for ~2 weeks of runs, then fails. Results go to the job summary, read by
   the PR's author and the merging agent.
7. **What is committed.** Recommendation: the statistics snapshot, `rules.yaml` and (P3) the
   baseline TSV. Captured catalogs with sample values are CI artifacts.
8. **Release check cadence.** Recommendation: every release (≈1–2 min on the rehearsal copy,
   read-only), in the release checklist like schema parity.
9. **10.11.** Recommendation: D on ci.yml's 10.11 leg (weekly and master) with per-server
   expected keys, and one deliberate D run on 10.11 before the server move; no 10.11 leg in
   `browser.yml`.
10. **Timings.** Recommendation: never in CI; `plans.py time` SELECT-only, locally and at release
    when a plan changed.
11. **The scratchpad scripts and `ExplainQueries.scala`.** Recommendation: `plans.py` absorbs
    them; `ExplainQueries.scala` is deleted in P2; the scratchpad scripts are not copied into
    the repo as they are (hard-coded SQL and a secrets path).
