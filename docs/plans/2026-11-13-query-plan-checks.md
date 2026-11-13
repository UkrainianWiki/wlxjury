# Query plan checks: statements captured from the code, plans checked in CI and at release

**Status:** proposal, 2026-11-13. Nothing is built; a pilot (§3) ran on the Gatling fixture.
Decisions for the maintainer: §8, one per item.

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
| Gatling fixture (`GatlingDbSetup`/`GatlingDbCache`) | ~38k images, 20 jurors, a binary and a rated round, cached dump; already built in CI by `browser.yml` (~1.5 min on a miss) | small and uniform (see §3) |
| `scripts/schema_parity.py` + release checklist row | precedent: a release-time check on the rehearsal's production copy, with its own CI unit tests | — |
| ScalikeJDBC `GlobalSettings.queryCompletionListener` | already used by `RequestTimingFilter` and `RegionMenuSpec` | — |
| MariaDB Connector/J 3.5 (text protocol by default) | the server sees each statement with its literals (general log, performance_schema) | — |

## 3. Pilot (2026-11-13, ~2 h, on `stack/123-drop-vote-single-indexes`)

Prototype code is **not committed** (kept in the session's scratchpad: `PlanCapturePilot.scala`,
a test-only `Test/runMain`, and `explain_captured.py`). Fixture built from the CSVs (the cache
key changed with V99/V100) in a MariaDB 10.6.22 container; sbt under the shared lock.

**3.1 Capture from the code works.** A `queryCompletionListener` (chained after the existing
one) recorded, for 16 requests (juror login; gallery page 1, page 5 and a region page, large
view, a vote and the large view again, in both rounds; organizer login, `/roundstat`, the
all-jurors gallery): **39 statement shapes, 19 on `vote`**, each with a count, its first raw
statement with typed bound values, and its caller (`ImageDbNew$SelectionQuery.countBefore:303`,
`VoteJdbc$.rateIn:243`, `User$.findRoundJurorsToView:570`, …; background ones too:
`JobJdbc.claimable`, `RoundCounts.roundsToRecount`). All 39 rendered and EXPLAINed on the
fixture with no error (the two vote `UPDATE`s included: `EXPLAIN UPDATE` doesn't write).
Lessons:
- **5 shapes carry inlined numeric literals** (`ImageDbNew.regionRateCounts` builds
  `s.jury_id = 1 AND s.round_id = 2` with `createUnsafely`; `RoundCounts` has `active = 1`):
  unless the shape key folds numeric literals, each juror × round is a new shape. The sample
  keeps them.
- `ImageDbNew$SelectionQuery.list` alone gave 7 shapes (tab, order, grouping): the caller names a
  group, not a statement; the identity is caller + normalized text (a hash).
- **Rebinding to another DB is feasible by column:** of 35 parameterised shapes, 24 have every
  `?` on an id column (`round_id` 23×, `jury_id` 11×, `page_id`, `contest_id`, `user_id`, `id`,
  `monument_id`); the other 11 also bind status, rate or email values, which keep the sample's.

**3.2 The fixture as it is does not reproduce V100's problem.** 767,340 votes, 2 rounds × 20
jurors × ~19.2k each: perfectly uniform. With V100's four indexes re-added (+ `ANALYZE`), the 39
captured shapes' plans are **identical** to V100's: 0 index merges. The independence estimate
(juror's share × round's share) is right when every juror votes in every round, and a round is
half the table, so scanning its single-column index is never cheap.

**3.3 A production-shaped corner reproduces it; injected statistics make it cheap.**
Production's case is r759: one juror, 20,501 votes, the juror with votes in other rounds, 3.35M
rows; the optimizer estimates the intersection at 487 rows. On the fixture, with the four
indexes re-added:

| Added to the fixture | `vote` rows (`n_rows` the optimizer sees) | gallery inner query (j=1, small round) | juror count query |
|---|---|---|---|
| nothing (rounds 1 and 2 only) | 0.77M | ref, gallery index | ref |
| nothing, `n_rows` set to 3.35M | 0.77M (3.35M) | ref | ref |
| nothing, `n_rows` set to 30M | 0.77M (30M) | **intersect** (est. 2,347) | ref |
| "history": juror 1 with 4 past rounds × 15k votes and the only juror of a 20k-file round (80k rows) | 0.85M | ref | **intersect** (est. 14,526) |
| history + 0.77M filler votes (20 other rounds) | 1.6M | **intersect** (est. 6,695) | **intersect** |
| history + 2.66M filler votes (70 other rounds) | 3.53M | **intersect** (est. 3,207; 20,000 real) | **intersect** |
| history, `n_rows` set to 1.6M | 0.85M (1.6M) | **intersect** (6,801) | **intersect** |
| history, `n_rows` set to 3.35M | 0.85M (3.35M) | **intersect** (3,248) | **intersect** |
| history + 2.66M filler, **V100 applied** | 3.53M | ref, gallery index | ref (`vote_round_jury_rate_idx`) |

- The trigger is the **shape** (a small round wholly inside one juror who has votes elsewhere),
  then the table's size; size alone needs an absurd 30M.
- **80k real rows + an injected `n_rows`** (`mysql.innodb_table_stats`, `STATS_AUTO_RECALC=0`,
  `FLUSH TABLE vote`, after the `ANALYZE`) gives the same plan and nearly the same estimate as
  2.66M real filler rows (3,248 vs 3,207). Inserting those 2.66M rows took 1 min 41 s; the
  injection is instant. The uniform rounds' statements kept their plans under the injection.
- Timing on the 3.53M table (warm, `ANALYZE FORMAT=JSON`, 3 reps): intersect 69–143 ms, gallery
  index 14–18 ms, the same 4–5× as production's 107 → 28 ms. One cold run (438 ms) shows why
  timings stay out of CI.
- The injection must come **after** every `ANALYZE` (`GatlingDbCache.restore` runs one) with
  auto-recalc off, or InnoDB overwrites it. EITS (`mysql.table_stats`, `use_stat_tables`) wasn't
  needed: with 10.6's default `PREFERABLY_FOR_QUERIES` and empty EITS tables the table size comes
  from InnoDB's `n_rows`, while records-in-range still reads the real index: hence the corner
  must be real rows, only the table's size can be faked.
- Not piloted: capture over the full `sbt test` (shape coverage), the plans on 10.11, the
  production copy (wlxjury-rehearsal2 was left alone).

## 4. Options

### 4.1 Statement capture

| Option | How | For | Against |
|---|---|---|---|
| **A. ScalikeJDBC listener in the tests** | a test-only `StatementCapture` installed when `-Dwlxjury.statementCapture=FILE` is set: every completed statement's template (with `?`), its bound values (typed), and the first app stack frame (`db.`/`services.`/`controllers.`); shapes = whitespace collapsed, `IN (?, ?, …)` folded; first sample per shape kept, count of all | caller tag (VoteJdbc.byUser:123) gives a stable name; works in any JVM the tests start (specs, Gatling, UiShotsServer) | only the JVM it's installed in; values are the run's own ids |
| B. Server-side log | `general_log` (or `performance_schema.events_statements_history_long`) on the DB while flows run, normalized in Python (digest) | any client: the packaged app on the rehearsal, Gatling's external target, the browser tests; literals are real ids of that DB | no caller; the general log is all-or-nothing (fine on a disposable container, never on production) |
| C. Hand catalog (today) | — | full control of cases (first/last page, every order) | drifts; incomplete |

Which runs feed the capture:
- **the fixture flows** (UiShotsServer for the browser tests, the Gatling simulations, or a small
  driver as the pilot's): realistic juror and organizer pages, with ids that exist in the fixture;
- **the full `sbt test`**: most shapes (admin paths, jobs, write paths, error paths), but on
  tiny data with ids of rows that are truncated afterwards — useful for *coverage* (which shapes
  exist, the committed shape list), not as EXPLAIN input as is.

Turning captured statements into EXPLAIN-able SQL on another DB (the production copy): the ids
of the fixture don't exist there. A **case profile** per target: a few SQL queries that pick
representative ids (bench2's cases: the biggest round and its heaviest juror, a single-juror
round like r759, a 2026 rated round, a hot page_id), and **binding by column**: a `?` compared
with `jury_id`, `round_id`, `page_id`, `contest_id`, `monument_id` (parsed from `col = ?`,
`col IN (?…)`) takes the case's value; other values (LIMIT/OFFSET, rates, dates, names) keep the
sample's. A statement whose `?`s can't all be bound is listed as unbound, not EXPLAINed.
Variants per case (first/last page, each order) come from the flows that ran them, not from
invention; a missing case is a flow to add.

### 4.2 Where plans are checked

| Option | For | Against |
|---|---|---|
| (a) CI on the Gatling fixture | every PR; same container image as production (10.6.22, and 10.11 in the matrix) | the fixture's data shape decides the plans: see §3 |
| (a′) the fixture with injected statistics | plans closer to production's without 3M rows | statistics alone don't move every estimate (§3); must be re-derived when production changes |
| (b) release-time on the rehearsal's production copy | the real data and statistics: the only place where V100's problem showed | once per release, by the release agent; a regression found late (but before deploy) |
| (c) both | CI catches shape drift and gross plan regressions; the release check catches data-dependent choices | two places to keep |

### 4.3 What to assert

1. **Rules** (no baseline needed; few false positives):
   - R1 no `index_merge` with `intersect` on `vote` (or `round_image`);
   - R2 no `type=ALL` on `vote`, `images`, `round_image`, `criteria_rate` for a statement whose
     caller is a juror page (gallery, large view, vote) — an allow-list names the deliberate ones
     (a round's full recount, the distribution's chunked scans);
   - R3 for named groups, the expected key (the juror gallery's inner query uses the gallery
     index; `VoteJdbc.byUser` the gallery index; a round's counts `vote_round_*`);
   - R4 rows-estimate bound on the prod copy (e.g. a juror page's vote access ≤ 2 × the round's
     votes of that juror): only at release, where the numbers mean something.
2. **Baseline diff**: per shape × case, (table, access type, key, Extra flags: filesort,
   temporary, intersect/union) committed as a TSV; the check prints changes as *regression*
   (to ALL, to index_merge, a key lost, filesort/temporary added) or *improvement*, and fails on
   regressions not acknowledged in the baseline update of the same PR. Rows estimates are **not**
   in the baseline (they move with statistics).
3. Stability: the MariaDB image pinned to the exact patch version (`SharedTestDb.Image`,
   10.6.22, as CI already does), `ANALYZE TABLE` after the restore (already in
   `GatlingDbCache.restore`), `innodb_stats_auto_recalc` off for the run (no recalc mid-run),
   `optimizer_switch` at defaults and printed in the report, the fixture's FixtureVersion part of
   the baseline's header (a fixture change re-baselines deliberately).

### 4.4 Timings and the harness

Timings stay **out of CI** (shared runners: ±30% noise; a plan regression is caught by the plan).
A **harness in the repository** replaces the scratchpad scripts, so the next question reuses it:

```
scripts/perf/
  plans.py        # explain | check (rules + baseline diff) | variants | time | summarize
  catalog/        # shapes.jsonl (captured, committed), groups.yaml (name → caller pattern, expected key, allow-list)
  profiles/       # fixture.sql, prodcopy.sql: the cases (ids) per target
  baseline/       # fixture-10.6.tsv, fixture-10.11.tsv
  test_plans.py   # unit tests of the normalizer, binder and diff (CI's Python step, as schema_parity)
```

`plans.py` keeps bench2's tested pieces: variants per statement (`IGNORE INDEX` on every `vote`
reference) or per session (`optimizer_switch`), **paired interleaved timing** (each rep runs every
variant in a rotated order, medians and min/max per variant, the sbt lock checked so a run never
overlaps a test JVM), EXPLAIN tables to CSV, summaries (intersect count, keys used per table,
changed statements). A DB is reached by `docker exec` (container name) or a DSN; the rehearsal's
secrets file stays outside the repo.

## 5. Recommendation

**(c) both, with one harness and one capture mechanism:**

1. **Capture (A):** a test-only `StatementCapture` (the pilot's listener, hardened: numeric
   literals and `IN` lists folded in the shape key, caller = first app frame, typed sample
   values, a shape hash) switched on by `-Dwlxjury.statementCapture=FILE` in
   `GatlingTestFixture`. Flows: the browser tests' UiShotsServer run (they already drive juror,
   organizer and admin pages on the fixture in CI) plus a short scripted flow like the pilot's
   for what the browser tests don't open (the history round, every gallery order, first and last
   page). Capture over the full `sbt test` is a later, optional coverage report (shapes no
   fixture flow reaches), not a plan input.
2. **A production-shaped fixture corner (FixtureVersion v9):** a past contest where one of the
   main rounds' jurors has ~60k votes over 4 rounds and is the only juror of a 20k-file round
   (r759's shape), ~80k rows. The plan-check run, and only it, injects production's `n_rows` for
   `vote` (and `round_image`, `images`) from a committed stats profile after the restore's
   `ANALYZE`; the Gatling simulations keep real statistics.
3. **CI check (fixture, every run of the browser job):** `scripts/perf/plans.py check` on the
   fixture container: rules R1–R3 fail the job; the baseline diff (access type, key, Extra flags
   per shape) prints regressions and improvements and fails on unacknowledged regressions. A
   **canary** keeps it honest: the same check with `--variant pre-v100` (V100's four indexes
   re-added in the disposable container) must find the intersect, or the check has lost its
   teeth (fixture or statistics drifted) and fails.
4. **Release check (production copy):** the release agent runs `plans.py check --target
   prodcopy` on the rehearsal container after the release's migrations: the CI catalog's shapes
   bound by column to the copy's cases (`profiles/prodcopy.sql`: r114/j581, r759, a 2026 rated
   round, a hot page_id, …); rules R1–R4; the report kept with the release notes; a new row in
   `docs/deployment.md`'s release checklist, next to schema parity.
5. **Timings:** never in CI. `plans.py time` (paired, interleaved, variants) is the local and
   release-time tool for the question a plan change raises.

## 6. Phases and effort

| Phase | Content | Effort |
|---|---|---|
| P1 | `StatementCapture` (test code) + `scripts/perf/plans.py` (render, explain, rules R1–R3, report; bench2's variants and paired timing ported) + `test_plans.py`; `ExplainQueries.scala` replaced | 1.5–2 days |
| P2 | Fixture v9 history corner, stats profile, injection step, the canary; a scripted flow for the pages the browser tests miss | 1 day |
| P3 | CI: capture on in `browser.yml`'s UiShotsServer, `plans.py check` before the server stops; report as an artifact and in the job summary | 0.5 day |
| P4 | Baseline TSV (fixture, 10.6) + diff + `--update-baseline`; warn-only at first | 0.5–1 day |
| P5 | Release check: `profiles/prodcopy.sql`, binding by column, R4, the runbook row; first run at the next release's rehearsal | 0.5–1 day |
| P6 (optional) | Full-suite capture as a coverage report; a 10.11 baseline after the server move | 0.5 day |

About 4–5 days in all; P1–P3 (≈3 days) already catch V100's class of regression in CI. Each
phase gets the usual review cycle.

## 7. Cost

- **CI minutes:** the browser job already restores and starts the fixture. Added: capture
  (negligible), EXPLAIN of ~50–150 shapes × 1–3 cases, the canary's index build on ~0.85M rows
  (the pilot's re-add on 0.77M took 3.3 s): ≈ **1–2 min per run**; a fixture rebuild on a cache
  miss +~10 s for the 80k rows. A separate workflow would add a whole fixture start (+4–6 min).
- **Maintenance:** a deliberate query change that changes a plan updates the baseline in the
  same PR (`--update-baseline`, the diff reviewed like code); a new flow when a page is added;
  the stats profile refreshed at a release when production's vote count moved by >25%.
- **False positives:** the injection can make the optimizer choose plans production wouldn't.
  The rules are narrow (intersect on vote/round_image, full scans of the juror tables) and the
  baseline has no row estimates. A CI rule hit the release check doesn't reproduce becomes an
  allow-list entry with a reason, not a code change. A MariaDB patch upgrade re-baselines
  deliberately (the image is pinned).
- **False negatives:** plans that depend on production's distribution beyond the history corner
  (many small rated rounds, round_image sizes) are caught only at release (recommendation 4).

## 8. Open decisions

1. **Capture mechanism.** Recommendation: the ScalikeJDBC listener in the tests (A), with
   caller tags; the server's general log (B) only if the release check later needs statements
   no fixture flow runs.
2. **Production-likeness of the fixture.** Recommendation: the small history corner (~80k rows)
   plus injected `n_rows` in the plan-check run only; not 0.8–2.7M real filler rows (a much
   larger dump, +1.5 min per build). The canary guards the trick.
3. **Where the CI check runs.** Recommendation: inside `browser.yml`'s job, where the fixture is
   already up; not a separate workflow.
4. **Fail or warn.** Recommendation: R1 (intersect on vote) and the canary fail from day one;
   R2/R3 and the baseline diff warn-only for ~2 weeks of runs, then fail.
5. **What is committed.** Recommendation: the baseline TSV (shape hash, caller, table, access
   type, key, Extra flags) and the stats profile; the captured catalog (with sample values) is a
   CI artifact, not committed.
6. **Release check cadence.** Recommendation: every release (≈1–2 min on the rehearsal copy),
   in the release checklist like schema parity.
7. **Timings.** Recommendation: never in CI; `plans.py time` locally and at release when a plan
   changed.
8. **The scratchpad scripts and `ExplainQueries.scala`.** Recommendation: `plans.py` absorbs
   them (variants, paired timing, summaries); `ExplainQueries.scala` is deleted once P1
   reproduces its output; the scratchpad scripts are not copied into the repo as they are
   (hard-coded SQL and a secrets path).
